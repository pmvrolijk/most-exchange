# Low-Latency Deterministic Matching Engine Specification

**Target Runtime:** Kotlin on GraalVM Native Image (Zero-Allocation Profile)

**Messaging Subsystem:** Aeron Cluster (Raft-replicated log) + Aeron IPC, generated SBE codecs

---

## 1. System Architecture Overview

The matching engine is a single-threaded deterministic state machine driven by an append-only,
strictly sequenced event log. Execution is isolated to one core and performs no dynamic memory
allocation in steady state, eliminating Garbage Collection pauses and locking overhead.

Two changes define this revision: **high availability is delegated to Aeron Cluster** rather than a
hand-rolled primary/hot-spare pair, and **market data formatting is moved out of the engine process**
so the matching thread never pays for feed fan-out.

```
      ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐
        Protocol Gateways (FIX, proprietary, …) — OUTSIDE this project
      │ Translate their own wire protocol to the binary SBE messages  │
        of §5 before anything reaches the order entry gateway.
      └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┬─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘
                                     │ SBE
                                     ▼
                        ┌──────────────────────────────────────────────┐
                        │             Order Entry Gateways             │
                        │  • securityId validated against shard map    │
                        │  • malformed / out-of-shard orders rejected  │
                        │  • order state kept for the outbound leg     │
                        └──────────────────────┬───────────────────────┘
                                               │ SBE over Aeron (cluster ingress)
                                               ▼
                        ┌──────────────────────────────────────────────┐
                        │      Aeron Cluster (Raft, 3 or 5 nodes)      │
                        │  Consensus • Global Sequencing • Snapshots   │
                        └──────────────────────┬───────────────────────┘
                                               │ replicated log
                        ┌──────────────────────┴───────────────────────┐
                        │       MatchingEngineService (per node)       │
                        │      ClusteredService, single-threaded       │
                        │        ≤ 10 securities per shard             │
                        └───────┬──────────────────────────┬───────────┘
                                │                          │
                 cluster egress │                          │ Book Event Stream
              (leader only, by  │                          │ (leader only, IPC 12)
               Cluster session) ▼                          ▼
                    ┌──────────────────┐      ┌────────────────────────────┐
                    │ Execution Reports│      │  Market Data Process       │
                    │  → participants  │      │  (separate process)        │
                    └──────────────────┘      │  derives L1 / L2 / L3      │
                                              └─────────────┬──────────────┘
                                                            │
                                          ┌─────────────────┼─────────────────┐
                                          ▼                 ▼                 ▼
                                    ┌──────────┐     ┌──────────┐     ┌──────────┐
                                    │ L1 BBO   │     │ L2 MBP   │     │ L3 MBO   │
                                    │ + Last   │     │ Aggreg.  │     │ Per-Order│
                                    └────┬─────┘     └────┬─────┘     └────┬─────┘
                                         └────────────────┼────────────────┘
                                                          │ SBE over multicast
                                                          ▼
                                            ┌──────────────────────────┐
                                            │  Subscribers / downstream│
                                            │  protocol adapters       │
                                            └──────────────────────────┘
```

### High Availability via Aeron Cluster

* **Raft consensus** replaces the muted hot-spare design. Every node runs an identical
  `MatchingEngineService` over the identical replicated log; only the leader's egress reaches
  clients, and leadership fencing is handled by the cluster rather than by heartbeat guesswork.
* **No split-brain.** The previous design's primary/spare pair could double-publish on a bad
  failover. Cluster's leader epoch makes a superseded leader's output impossible.
* **Snapshots.** `onTakeSnapshot` bounds recovery time; the previous design had no snapshot story
  and would have required replay from genesis — billions of events after a single trading day.
* **Market data still needs explicit muting.** The Book Event Stream is a plain publication, not
  cluster egress, so the service gates it on `Cluster.Role.LEADER` via `onRoleChange`.

### Order Entry Gateway

The gateway in this project is **protocol-agnostic**. It speaks the binary SBE messages of §5 on
both legs and knows nothing about FIX. Protocol translation — FIX, or any proprietary session
protocol — happens in **separate upstream gateways outside this project**, which map their own wire
format to these SBE messages before anything reaches order entry. Adding a new client protocol
therefore means adding a protocol gateway, not changing this one.

Its responsibilities:

* **Validate `securityId`** against the shard map, rejecting malformed or out-of-shard orders before
  they reach the cluster. The engine range-checks defensively as well, but the gateway is where a bad
  security is meant to die (§4.7).
* **Hold per-order state for the outbound leg.** The engine's `ExecutionReport` omits `origQty` and
  therefore `cumQty`, because the packed order slot has no room for a field the boundary can
  reconstruct (§3.1). The gateway retains the `origQty` it forwarded and restores the cumulative
  quantity as `origQty − leavesQty` on every execution report it passes back.
* **Perform any other outbound mapping** required by the client-facing shape — correlating
  `exchangeOrderId` back to the submitting session, and enriching reports with anything the engine
  deliberately does not carry.

A cluster session belongs to a **gateway**, not to an end participant, and the maker side of a fill
needs a route back that the inbound message cannot supply. The engine therefore keeps a
`participantId → clusterSessionId` map learned from inbound traffic: deterministic, because every
node sees the same messages in the same order, and self-healing, because a stale id simply fails to
resolve. A report for a participant with no live session is counted and dropped — blocking the engine
thread on an absent consumer is worse than losing the report. Binding the participant from an
authenticated session principal at session open would be stronger; see §8.

This is why the gateway is stateful: it is the only component that remembers an order's original
quantity, and that memory is what lets the engine keep an order at exactly one cache line.

#### Backpressure on the inbound leg

**An order the gateway cannot forward must not be consumed.** `AeronCluster.offer` returns
`BACK_PRESSURED` or `ADMIN_ACTION` under congestion; treating either as a drop loses an order the
client believes it has placed, with no acknowledgement and no rejection — the one outcome a client
cannot recover from, since there is nothing to retry against and nothing to time out on.

The gateway therefore polls its client subscription with `controlledPoll`. A message the cluster
cannot take right now leaves the fragment unconsumed (`Action.ABORT`) and is offered again on the
next poll, which propagates congestion back to the client's own publication instead of swallowing
it. Successfully handled fragments return `Action.COMMIT`, not `Action.CONTINUE`: `CONTINUE` commits
the position only at the end of the whole poll, so a later `ABORT` would rewind past fragments
already forwarded and send them to the cluster a second time.

Two consequences follow. The pending `origQty` recorded before the offer has to be unwound when the
offer does not send, or a retried order is recorded twice and a failed one leaks an entry nothing
ever releases. And a genuinely dead session — `NOT_CONNECTED`, `CLOSED` — is not retryable, so the
order is rejected back to the client with `RejectReason.GATEWAY_UNAVAILABLE`, a reason no engine
ever emits because a message the engine never saw cannot be rejected by it.

**The outbound leg is different, and is honestly lossy.** Cluster egress cannot be left unconsumed
the way ingress can — the poller that drains it is the same thread that keeps the session alive — so
a subscriber that falls behind loses execution reports, counted as `droppedToClient`. Retrying the
outbound offer was measured and made matters worse: the spin burned the poller thread that also
drives ingress and keepalives, moving the round-trip p99 from 0.3 ms to 4 ms while still dropping.
The remedy is a larger term buffer or a faster subscriber, not a busier gateway.

### Determinism Protocol

* No `System.currentTimeMillis()` / `nanoTime()` in the engine. Time is read only from
  `Cluster.time()`, which is the sequenced consensus timestamp, or from timestamps carried on
  sequenced commands.
* All temporal events — session transitions, the expiry purge — enter the log as sequenced
  commands or as Aeron Cluster timers (`Cluster.scheduleTimer`, delivered through the log and
  therefore deterministic).
* Given the same log, every node produces byte-identical state.

---

## 2. Sharding & Capacity

The engine is single-threaded, so per-order cost is shared across every security it hosts. Capacity
is therefore stated per *shard*, not per security.

| Parameter | Value | Notes |
| --- | --- | --- |
| Securities per shard | **10** (max) | `securityId` is validated at the gateway and mapped to a shard |
| Orders per book | **1,000,000** | Pre-allocated pool, no growth at runtime |
| Price levels per book | 65,536 (configurable) | Direct-mapped ladder, per side |
| Target throughput | 100,000 orders/sec/security | 1M orders/sec/shard aggregate at full fan-out |

### Throughput Budget

100k orders/sec/security across 10 securities is a 1 µs aggregate budget per order. Estimated cost
per order in the continuous phase:

| Stage | Cost |
| --- | --- |
| SBE decode + validation (bands, date, security) | ~15 ns |
| Book lookup (direct array index) | ~2 ns warm |
| Ladder insert or match step | ~50 ns logic |
| Order pool access | ~80 ns (**one** cache miss — see §3) |
| Encode + publish, 2 messages via `tryClaim` | ~100–200 ns |

This lands near **0.5 µs per order**, leaving roughly 2x headroom at the 10-security fan-out and
10–20x for a single hot security. Beyond 10 securities per shard the margin disappears; scale by
adding shards, which is safe because no cross-instrument matching is specified — books are fully
independent.

### Memory Footprint (per shard)

| Structure | Size |
| --- | --- |
| Order pool: 10 books × 1M orders × 64 B | 640 MB |
| `orderIdToIndex` maps: 10 × ~34 MB (`Long2LongHashMap`, 2²¹ slots × 16 B) | 336 MB |
| Price ladders: 10 books × 2 sides × 65,536 levels × 20 B | 26 MB |
| **Total** | **~1.0 GB** |

Allocated eagerly at startup and never grown. This footprint mandates huge pages (§7).

Agrona has no `Long2IntHashMap`, so the id map is a `Long2LongHashMap` and the `int` node index
is widened to a `long` — 16 bytes per slot rather than 12. That is the difference between the
~0.9 GB an int-valued map would have cost and the ~1.0 GB above.

### Pool Exhaustion Policy

1M orders is only ~10 seconds of *gross* arrivals at 100k/sec; the real requirement is net resting
depth after cancels, which must be measured against production flow. Regardless of sizing, the pool
must never be allowed to drain to zero: exceeding a configured **high-water mark rejects new orders**
with `RejectReason.BOOK_CAPACITY`. The previous design threw on exhaustion, which — being
deterministic — would have killed every cluster node at the same log position simultaneously.

---

## 3. Low-Latency Memory & Data Structures

```
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │              Security Array Router (securityId → 0..9, validated)           │
 └──────────────────────┬──────────────────────────────┬───────────────────────┘
                        ▼                              ▼
             ┌──────────────────┐           ┌──────────────────┐
             │   OrderBook[0]   │   ...     │   OrderBook[9]   │
             └────────┬─────────┘           └──────────────────┘
                      │
        ┌─────────────┴─────────────┐
        ▼                           ▼
 ┌──────────────────┐      ┌─────────────────────────────────────────────────┐
 │  Price Ladders   │      │        Packed Order Pool (cache-aligned)        │
 │  (bids / asks)   │      │  orders: LongArray(MAX_ORDERS * 8)              │
 │                  │      │  ┌───────────────────────────────────────────┐  │
 │ head:  IntArray  │      │  │ word 0  price                             │  │
 │ tail:  IntArray  │      │  │ word 1  leavesQty                         │  │
 │ qty:   LongArray │      │  │ word 2  participantId                     │  │
 │ count: IntArray  │◄────►│  │ word 3  smpId (effective)                 │  │
 │ occupancy:       │      │  │ word 4  clOrdId                           │  │
 │        LongArray │      │  │ word 5  exchangeOrderId                   │  │
 │        (bitset)  │      │  │ word 6  next:int32 | prev:int32           │  │
 └──────────────────┘      │  │ word 7  expireDate | side | smpStrategy   │  │
                           │  └───────────────────────────────────────────┘  │
                           │      64 bytes = exactly one cache line           │
                           │                                                 │
                           │  orderIdToIndex: Long2LongHashMap(id → nodeIdx)  │
                           └─────────────────────────────────────────────────┘
```

### 3.1 Packed Order Pool — One Cache Line Per Order

The previous revision used seven parallel arrays (struct-of-arrays). Matching touches *all* fields of
*one* order at a time, so that layout cost up to **seven cache misses per order**. Fields are instead
packed into a single `LongArray` with a stride of 8 longs, so an order occupies exactly one 64-byte
cache line and costs **one** miss. Access is `orders[nodeIdx * STRIDE + FIELD]`.

`leavesQty` is a stored field (word 1) rather than derived at publish time. This fixes the
partial-fill reporting bug in the previous revision and, more importantly, lets a partially filled
resting order continue to participate in matching with its true remaining quantity.

**`origQty` is deliberately not stored.** Self-match prevention (§4.5) requires an `smpId` on every
resting order, and the eight words were already full. `origQty` served only to derive `cumQty` for
execution reports — a value the **gateway** can reconstruct, since it sent `origQty` and the engine
reports `leavesQty` on every fill. Pushing that derivation to the boundary, where the data already
exists, keeps an order at exactly one cache line. `cumQty` is correspondingly absent from the
engine's `ExecutionReport`; the gateway restores it on the outbound leg (§1, *Order Entry Gateway*).

**Trade-off, accepted:** the expiry sweep scans one field across many orders, which struct-of-arrays
served better. That sweep now walks the price ladder instead (§4.3) and runs once per day outside
trading hours, so the hot path wins.

### 3.2 Price Ladder — Direct-Mapped with Occupancy Bitset

The previous revision had **no book structure at all**: `addRestingOrder` wrote the order into the
pool and the ID map but linked it to nothing, leaving `matchOrBook` with nothing to traverse.

Each side of each book is a flat array of price levels, indexed by ticks from a configured floor:

```
levelIndex = ((price - priceFloor) / tickSize).toInt()
price      = priceFloor + levelIndex * tickSize
```

Each level holds `head` / `tail` node indices for a FIFO (time priority), plus aggregate `qty` and
`orderCount` for market data and auction computation. A `LongArray` **occupancy bitset** marks
non-empty levels, so finding the next best price is a word scan using
`countLeadingZeroBits` / `countTrailingZeroBits` — 64 levels per operation. `bestBidLevel` and
`bestAskLevel` are cached hints maintained on insert and removal.

**Ladder range invariant:** the configured ladder range must be strictly wider than the static
collar band (§4.4). Collar rejection then always fires first, making ladder overflow
unreachable.
`RejectReason.PRICE_OUT_OF_LADDER` exists as a defensive backstop only.

### 3.3 Value Classes

Domain primitives (`Price`, `Quantity`, `ParticipantId`, `ExchangeOrderId`) are
`@JvmInline value class` — compile-time type safety, zero runtime boxing. **They box in generic
contexts** (nullable types, collections, `Array<Price>`), so pool storage is raw `LongArray` and every
callback taking them must remain an `inline fun`. Converting a match callback to a functional
interface or object would box on every fill and, under a zero-allocation profile, is fatal.

---

## 4. Trading Session Lifecycle & Execution Rules

```
     ┌───────────────┐
     │    CLOSED     │  ◄── orders rejected
     └───────┬───────┘
             │ PurgeExpiredOrders(tradingDate)   ── scheduled well before PRE_OPEN
             │ ──► [Purge: expireDate in 1..<tradingDate]
             ▼
     ┌───────────────┐
     │   PRE-OPEN    │ ──► [Accept + BOOK orders. No matching. No indicative price.]
     └───────┬───────┘
             │ SessionTransition(OPEN_AUCTION)
             ▼
     ┌───────────────┐
     │ OPEN AUCTION  │ ──► [Accept + BOOK orders. No matching.
     └───────┬───────┘      Publish indicative uncrossing price.]
             │ SessionTransition(CONTINUOUS) ──► [UNCROSS at open price]
             ▼
     ┌───────────────┐
     │  CONTINUOUS   │ ──► [Price-Time Priority Matching, Dynamic Collar, SMP]
     └───────┬───────┘
             │ Aggressing order breaches the DYNAMIC COLLAR
             │ ──► [Breaching fill suppressed. Aggressor remainder canceled.
             │      Resting book left intact. VolatilityHalted event emitted.]
             ▼
     ┌───────────────┐
     │    CLOSED     │  ◄── a volatility halt lands here. No automatic recovery:
     └───────────────┘      an operator drives PRE_OPEN → OPEN_AUCTION →
                            CONTINUOUS, reopening via the uncross algorithm.
```

There is no distinct `VOLATILITY_HALT` phase. A halt transitions the security to `CLOSED`, which
already has exactly the required behaviour — reject all orders, retain the resting book — and
recovery is operator-driven either way; a separate phase would be a state with no distinct semantics.
The `VolatilityHalted` book event (§5) is what distinguishes a halt from a scheduled close
downstream. Phase is per security, so a halt on one book does not affect the others in the shard.

### 4.1 Order Acceptance vs. Matching

**Orders are accepted and booked in every phase except `CLOSED`.** Only *matching* is gated on
`CONTINUOUS`. The previous revision acknowledged an order with a `NEW` execution report and then
silently discarded it if the phase was not `CONTINUOUS` — the order was never booked and never
rejected.

| Phase | Accept | Book | Match |
| --- | --- | --- | --- |
| `CLOSED` | ✗ (reject `MARKET_CLOSED`) | — | — |
| `PRE_OPEN` | ✓ | ✓ | ✗ |
| `OPEN_AUCTION` | ✓ | ✓ | ✗ (indicative price only) |
| `CONTINUOUS` | ✓ | ✓ | ✓ |

A crossed book is therefore normal and expected outside `CONTINUOUS`; it is resolved by the
uncrossing algorithm.

### 4.2 Opening Auction — Uncrossing Algorithm

Executed on the `OPEN_AUCTION → CONTINUOUS` transition. The same algorithm computes the indicative
price published during `OPEN_AUCTION`.

For every candidate level `P` in the crossed range `[bestAskLevel, bestBidLevel]`:

```
demand(P) = Σ bid qty at levels ≥ P        (cumulative, descending scan)
supply(P) = Σ ask qty at levels ≤ P        (cumulative, ascending scan)
executable(P) = min(demand(P), supply(P))
imbalance(P)  = demand(P) − supply(P)
```

Selection, applied in order until a single price remains:

1. **Maximum executable volume** — the price trading the most shares.
2. **Minimum absolute imbalance** — the price leaving the least surplus.
3. **Surplus side** — if the remaining imbalance is on the buy side at every tied price, take the
   **highest** tied price; if on the sell side, the **lowest**.
4. **Nearest `dynamicReference`** — the last traded price, or the `SecurityDefinition` seed for a
   security that has not yet traded.

If the book is not crossed there is no auction trade; the opening reference stays at
`dynamicReference` and the book moves to `CONTINUOUS` unchanged.

All crossing orders then execute **at the single uncrossing price**, in strict price-time priority,
each emitting a `TRADE` execution report and a `TradeExecuted` book event. A single
`AuctionUncrossed` event carries the final price and total executed volume.

Self-match prevention applies here too, which makes the uncross iterative rather than a single
computation — the price and the set of SMP cancellations are mutually dependent. See
§4.5 for the fixed-point loop and its termination argument.

An uncross that executes sets **both** references: `staticReference` (the session anchor for the
static collar) and `dynamicReference` (as with any trade). An uncross that produces no trade leaves
both untouched. See §4.4.

**The auction is uncollared.** Neither collar constrains the uncross price: the auction may print at
whatever price maximises executable volume, however far that sits from either reference. This is
deliberate — it is what lets a halted security reprice and reopen (§4.6), and what lets a security
open away from its previous close.

The auction is not unbounded in practice, because **every order participating in it passed the static
collar at acceptance**. The collar constrains the inputs; it does not constrain the clearing price
derived from them.

**Cost:** a linear scan over occupied levels in the crossed range, using one engine-wide scratch
`LongArray` for the cumulative demand curve (safe to share — the engine is single-threaded). This
runs once per session, outside any latency budget. The **indicative** price, however, is recomputed
during `OPEN_AUCTION` and must be throttled — recompute on a sequenced tick, not on every order.

### 4.3 Order Expiry — Date-Based, Purged Off-Session

Intraday expiry is **not supported**. Orders are good for the day or good-til-cancelled; the
`expireTime` epoch-millisecond field is replaced by:

```
expireDate : int32   // YYYYMMDD. 0 = good-til-cancelled.
```

An order is expired when `0 < expireDate < currentTradingDate`. All day orders effectively expire at
23:59:59 of `expireDate`, so a date comparison is exact and no clock is consulted.

The purge runs as its own sequenced `PurgeExpiredOrders` command at a configured time **well before
`PRE_OPEN`**, so the sweep never competes with order acceptance or matching. It is a deliberate
off-session housekeeping run, not a session-transition side effect.

**The sweep walks the price ladders, not the ID hash map.** The previous revision iterated
`Long2LongHashMap.entrySet()` and called `iterator.remove()`; because that map is open-addressed and
compacts its probe chain on removal, entries can be relocated into slots the iterator has already
passed and be silently skipped. It also read `entry.key` / `entry.value` through the boxing generic
`Map.Entry` view. Walking the ladder FIFOs is allocation-free, has no removal hazard, and has far
better locality.

Each purged order emits a private `EXPIRED` execution report and an `OrderRemoved` book event.

### 4.4 Reference Prices & Collars

The two collars anchor to **two different reference prices**, which move on different timescales.

| Reference | Seeded by | Updated by | Anchors |
| --- | --- | --- | --- |
| `staticReference` | `SecurityDefinition` | Every **executing** uncross (§4.2) | Static collar |
| `dynamicReference` | `SecurityDefinition` | **Every trade** — continuous fills and auction | Dynamic collar |

`SecurityDefinition.referencePrice` seeds both; from then on they diverge. The static anchor is
fixed for the session at the opening auction price, so the acceptance band does not drift with
intraday trading. The dynamic anchor tracks the last trade, so the execution band follows the market.

Both collars are configured per security as **basis points** (`int32`, 1 bp = 0.01%):

| Control | Field | Anchor | Applied | On breach |
| --- | --- | --- | --- | --- |
| Static collar | `staticCollarBps` | `staticReference` | Order **acceptance** | Reject: `PRICE_OUT_OF_BOUNDS` |
| Dynamic collar | `dynamicCollarBps` | snapshot of `dynamicReference` | Trade **execution** | Volatility halt (§4.6) |

```
staticBound  = staticReference × staticCollarBps  / 10_000
dynamicBound = collarReference × dynamicCollarBps / 10_000
```

**Static collar** is evaluated against the incoming limit price in every phase that accepts orders.
An order outside the band is rejected outright and never booked.

**Dynamic collar** is evaluated per candidate *price level* during continuous matching, before any
fill at that level is applied. It is a level gate: if the level breaches, nothing at that level or
beyond trades.

**The dynamic collar is measured against a snapshot.** `collarReference` is captured from
`dynamicReference` at the arrival of the aggressing order and is **not** re-read as that order's own
fills advance it. Re-reading would let a single large order ratchet: each successive level would only
need to sit within the collar of the price it just printed, allowing it to walk arbitrarily far in
one event — precisely what the control exists to prevent. The reference still advances with every
trade; the aggressor is simply judged against the market as it stood when it arrived.

**Every executing uncross resets `staticReference`, including the reopening auction after a halt.**
This is required, not incidental. A halt means the price has moved decisively; if `staticReference`
stayed pinned to the morning's opening price, the reopening would immediately reject the very orders
that reflect the new level, and the security could not reopen. An uncross that produces **no trade**
(an uncrossed book) leaves `staticReference` untouched — on day one that means the
`SecurityDefinition` seed stands, and thereafter the previous session's opening price.

The ladder range invariant (§3.2) means the static collar always fires before a ladder overflow
could, and the static band should be configured wider than the dynamic band so that order acceptance
is the coarse filter and execution the fine one.

### 4.5 Self-Match Prevention

**Identity.** Each order carries an optional `smpId`. The **effective** SMP id is resolved once, at
order entry, as `smpId != 0 ? smpId : participantId`, and only the resolved value is stored (word 3
of the order). Two orders self-match when their effective SMP ids are equal.

**Strategy.** Taken from the **aggressing** order's `smpStrategy` field, defaulting to
`CANCEL_AGGRESSOR`. The aggressor governs because the decision is made at the moment it arrives and
it is the party with live intent; the resting order's own instruction is not consulted.

| Strategy | Effect |
| --- | --- |
| `CANCEL_AGGRESSOR` (default) | Matching stops. The aggressor's remaining quantity is canceled (`CANCELED` / `SELF_MATCH_PREVENTED`) and is **not** booked. The resting order is untouched. |
| `CANCEL_RESTING` | The resting order is canceled in full (`CANCELED` / `SELF_MATCH_PREVENTED` to its owner, plus an `OrderRemoved` event) and unlinked. The aggressor continues against the next order in the FIFO. |

`CANCEL_BOTH` from the previous revision is removed; the strategy set is exactly these two.

**Evaluation order within matching.** The dynamic collar is a *level* gate and is checked first; SMP
is an *order* gate checked inside the level, per resting order, before the fill is applied. A
self-match never prints a trade and therefore never moves the reference price.

#### SMP in the Opening Auction — Multi-Pass to a Fixed Point

SMP is enforced in the auction as well as in continuous trading. This is inherently iterative: a
cancellation removes volume, which can move the uncross price, which changes which orders cross,
which can create or dissolve self-match pairs.

**There is no aggressor in an auction** — every order is passive and all fills print at one price. The
continuous-phase roles are therefore mapped onto arrival time: within a self-matching pair, the
**later-arriving order plays the aggressor**, and its `smpStrategy` governs. Arrival order is read
from `exchangeOrderId`, which is monotonic, so the mapping is exact and identical on every cluster
node.

| Strategy on the later order | Cancels |
| --- | --- |
| `CANCEL_AGGRESSOR` (default) | The **later** order |
| `CANCEL_RESTING` | The **earlier** order |

The loop:

```
loop:
  P = computeUncrossPrice()                 // over the current, post-cancellation book
  if book is not crossed: break             // no auction trade
  n = resolveSelfMatches(P)                 // one linear two-cursor pass; resolves every
                                            // self-match encountered at this P
  if n == 0: executeAllocation(P); break    // stable: print the fills
  // cancellations removed volume — P may have moved. Recompute.
```

**One walk resolves every collision at a given price.** The allocation is a two-cursor merge of both
sides in price-time priority; cancelling an order simply advances that side's cursor past it, and the
pairing from that point on is exactly what a recompute *at the same P* would produce. So a walk never
needs restarting for its own cancellations — only for the price move they may cause. This is what
keeps the loop from over-cancelling: an order is never killed on the basis of a pairing that a
previous cancellation in the same walk has already invalidated.

**Termination.** Every iteration either cancels at least one order or exits. Cancellation is
monotonic — orders leave the book and never return — over a finite set, so the loop terminates in at
most one iteration per self-matching order. Typical auctions converge in one or two passes.

**Cost and safety valve.** Worst case is O(N²) in the crossed region, which is affordable for a
once-per-session event with no latency budget but is not bounded by anything cheap. Configure a
maximum pass count; exceeding it should raise an operator alert rather than spin, since it indicates
either a pathological book or a defect.

Each cancellation emits a `CANCELED` / `SELF_MATCH_PREVENTED` execution report to the owner and an
`OrderRemoved` book event. These are produced inside the session-transition command, so they are part
of deterministic log processing like any other output.

### 4.6 Volatility Halt

Triggered when an aggressing order would trade at a price breaching the dynamic collar. The sequence
is exact and ordered:

1. **The breaching fill is suppressed.** No trade prints at or beyond the breaching level.
2. **Fills already completed by this order stand.** They executed at prices inside the collar and are
   reported normally; the reference price reflects them.
3. **The aggressor's remaining quantity is canceled** — `CANCELED` with
   `rejectReason = VOLATILITY_HALT`. It is not booked.
4. **The security's phase becomes `CLOSED`.** Resting orders are left entirely intact — no purge, no
   cancellation.
5. **A `VolatilityHalted` book event is emitted**, carrying `collarReference`, the `attemptedPrice`,
   the `breachedBound`, and the aggressor side, so the Market Data Process can distinguish a halt
   from a scheduled close and publish the reason.
6. **Subsequent orders are rejected** with `MARKET_CLOSED` until an operator intervenes.

**Recovery is manual. There is no automatic resumption.** An operator issues
`SessionTransition(PRE_OPEN)`, then `SessionTransition(OPEN_AUCTION)`, then
`SessionTransition(CONTINUOUS)` — the last performing the standard uncross (§4.2).

**Ordering caveat: the static collar is still anchored to the pre-halt price during recovery.**
`staticReference` is not reset until the reopening uncross *executes*, but orders are accepted from
the moment the security enters `PRE_OPEN`. If the halt moved price outside the static band, the
orders needed to reopen at the new level will be rejected with `PRICE_OUT_OF_BOUNDS` before the
auction that would have reset the anchor can run — a deadlock.

The lever is `SecurityDefinition`, which **may be re-issued at any time** to re-seed both references
and adjust the collar widths. Re-seeding `staticReference` before `PRE_OPEN` is therefore a required
step in the halt-recovery runbook whenever the halt price sits outside the static band, not an
exceptional intervention. This is inherent to a session-fixed static anchor rather than a defect, and
the same drift can require an intraday re-seed during ordinary trading if price moves far from the
opening price without ever breaching the dynamic collar. Orders submitted
during the reopening phases are booked against the retained book, and the reopening auction resolves
whatever crossing results.

Note that the retained book is typically **not** crossed immediately after a halt: the aggressor that
breached the collar was canceled rather than booked, so the resting book is unchanged and the
reopening auction produces a trade only if new orders arriving during `PRE_OPEN` / `OPEN_AUCTION`
cross it.

### 4.7 Other Matching Rules

1. **Cancellation.** `OrderCancelRequest` must match `participantId`, `origClOrdId`, and
   `exchangeOrderId`. Full cancellation only; partial cancels are rejected.
2. **Security validation.** `securityId` is validated at the gateway against the shard map. The
   engine additionally range-checks it defensively — an unvalidated index would be an
   `ArrayIndexOutOfBoundsException` that, being deterministic, kills every cluster node at the same
   log position.

---

## 5. Message Specification (Generated SBE)

Codecs are **generated by the SBE tool** from the schema below; byte offsets are never written by
hand. The previous revision hand-maintained offsets in three places (spec tables, encoder, decoder),
which is how its `ExecutionReport` came to place an `int64` at offset 2 and every subsequent field at
an unaligned address. Generation also provides schema versioning and forward/backward evolution.

Fields are declared in descending width order so natural alignment falls out of the layout, and each
`blockLength` is rounded to a multiple of 8.

### Template Identifier Map

| ID | Message | Direction | Stream |
| --- | --- | --- | --- |
| `1` | `NewOrderSingle` | Inbound | Cluster ingress |
| `2` | `OrderCancelRequest` | Inbound | Cluster ingress |
| `3` | `SessionTransition` | Inbound | Cluster ingress |
| `4` | `PurgeExpiredOrders` | Inbound | Cluster ingress |
| `5` | `SecurityDefinition` | Inbound | Cluster ingress |
| `10` | `ExecutionReport` | Outbound, private | Cluster egress |
| `20` | `OrderAdded` | Outbound, book event | IPC 12 |
| `21` | `OrderReduced` | Outbound, book event | IPC 12 |
| `22` | `OrderRemoved` | Outbound, book event | IPC 12 |
| `23` | `TradeExecuted` | Outbound, book event | IPC 12 |
| `24` | `AuctionUncrossed` | Outbound, book event | IPC 12 |
| `25` | `SessionChanged` | Outbound, book event | IPC 12 |
| `26` | `VolatilityHalted` | Outbound, book event | IPC 12 |
| `30`–`33` | `SnapshotEngineState` / `SnapshotBook` / `SnapshotOrder` / `SnapshotEnd` | Snapshot | Cluster snapshot |

The snapshot messages are cluster-internal and never reach a client. A snapshot walks each book's
ladders and writes only **occupied** orders, never the whole 1M-slot pool; restoring replays them in
the same ladder order, which reproduces each level's FIFO exactly because booking appends at the
tail.

The engine emits a raw **book event stream**; it no longer publishes market data formats directly.
The Market Data Process consumes stream 12 and derives L3 (per-order MBO), L2 (price-aggregated MBP)
and L1 (BBO + last trade) feeds. This halves the engine's publication work and decouples the matching
core from slow feed consumers.

#### Sequence Numbers and Shard Namespacing

Every book event carries a monotonic `seqNum`, stamped by the engine where the event is
**generated** — which happens identically on every node, so a new leader continues the sequence
rather than restarting it. It is snapshotted alongside `nextExchangeOrderId`.

This makes the L3 feed a **verbatim forward**: the engine's per-order events already *are* market by
order and already carry a sequence, so the Market Data Process copies the bytes rather than
re-encoding them. Re-encoding would add latency and a chance to disagree with the engine. The L1 and
L2 feeds are derived, so they carry their own per-feed sequences.

`OrderRemoved` carries `leavesQty` for the same reason: with the removed quantity on the event, the
whole L2 aggregate is derivable from the event stream alone. Without it every downstream aggregator —
the Market Data Process first, then each consumer — would have to shadow the engine's per-order
state just to know how much to subtract.

**Every book event and every derived feed message also carries `shardId`, and sequences are tracked
per shard.** Each shard numbers its streams from 1 independently, so on a shared multicast group a
subscriber would see two interleaved sequences and read the entire feed as gaps. Namespacing by
shard is what lets several shards publish to one group — the alternative, a channel per shard, makes
a subscriber that wants the whole market join N groups and manage N subscriptions.

The shard id has to live on the **book event**, not just the derived messages, because L3 is a
verbatim forward: the Market Data Process never re-encodes those, so anything a subscriber needs to
demultiplex them must already be there. Deriving the shard from `securityId` through the directory
would work — securities are unique to one shard — but it puts a lookup on the hot path and fails for
a security the subscriber has not yet seen in a directory broadcast.

`FeedSequenceTracker` in the `reference` module implements the per-shard detection, and is what a
subscriber embeds alongside `DirectoryClient`. It distinguishes a **gap** (a jump forward, counted
with how many were missed) from a **replay** (a lower sequence, which Aeron's ordering guarantee
means is a duplicate rather than loss), and treats the first message from a shard as establishing
the baseline so joining mid-session does not report everything before it as lost.

The Market Data Process uses the same shard id to **reject foreign events**: it subscribes to one
engine, so an event stamped with another shard means it is pointed at the wrong one. Those are
counted and dropped rather than aggregated, which turns a misconfiguration that would have silently
folded another shard's orders into these books into a visible counter.

#### Feed Transport

The feeds are published **in SBE form over Aeron UDP multicast**, with unicast channels as a fallback
for subscribers that cannot receive multicast. FIX and other client-facing formats are not produced
here: as on the order entry side, a downstream protocol adapter translates the SBE feed for consumers
that need something else.

Multicast is what keeps feed cost independent of audience size — the Market Data Process writes each
message once regardless of how many subscribers are attached. The unicast fallback does not have that
property: its cost scales with subscriber count, so it should stay bounded and exceptional rather
than becoming a second primary path.

**Flow control is the multicast analogue of the engine's backpressure problem, and the default is
already correct — do not change it.** Aeron's `aeron.multicast.flow.control.strategy` defaults to
`MaxMulticastFlowControl` (verified against Aeron 1.53.0), where the publisher progresses at the rate
of the **fastest** receiver. Switching it to `MinMulticastFlowControl` would let the **slowest**
subscriber throttle the publisher, reintroducing exactly the coupling that moving market data out of
the engine process was meant to eliminate — one slow consumer would degrade the feed for everyone.

The consequence of `Max` is that a slow subscriber falls behind and takes an unrecoverable gap rather
than slowing anyone else down. That is the correct trade for market data, but it makes gap detection
and recovery a **subscriber** responsibility: consumers must detect a gap and re-synchronise from a
snapshot rather than assume a continuous stream. Unicast fallback subscribers do get per-channel
backpressure, but each has its own publication, so a stalled one affects only itself.

### The L2 recovery feed

The snapshot that paragraph calls for is a fourth stream, published by the Market Data process on a
repeating cycle: `DepthSnapshotBegin`, one `DepthSnapshotLevel` per occupied level, `DepthSnapshotEnd`.
One security per slice, so a full pass takes the configured cycle however many books the shard hosts,
and that pass is the worst case a subscriber waits before it can trust a book.

**A late joiner and a gapped subscriber are the same problem.** Neither has a book it is entitled to
apply an increment to, and the fix for both is an image plus everything published after it.

**The splice is `l2SeqNum`** — the incremental sequence the image was taken at, carried on both ends
of the cycle. A subscriber buffers the increments that arrive while the image is in flight, installs
the image, discards the buffered updates at or below that sequence, and replays the rest.
`DepthFeedAssembler` in `reference` is the one implementation of that, shared by the operator CLI,
the control plane and anything else that rebuilds a book; a consumer that wrote its own would be a
second implementation of a rule with no way to detect that it disagreed.

Three properties the design depends on:

* **The image is taken on the poll thread**, between two book events, never on a timer. The sequence
  stamped on it and the levels walked for it are consistent only because nothing can be applied in
  between. A torn image is undetectable downstream: every message in it is individually valid and
  the book they describe simply never existed.
* **Begin and End repeat the sequence and the level count**, so a cycle that lost messages in the
  middle is discarded rather than installed — the staging discipline `discovery` already applies to
  the universe broadcast. A dropped snapshot message is never retried for the same reason: the cycle
  it belonged to is already void, and the next one is along within the interval.
* **A snapshot is ignored while a subscriber is synchronised.** Increments past the image's sequence
  have already been applied straight to the book and were never buffered, so installing it would
  silently rewind the book to an older state.

Only *occupied* levels are sent, walked through the depth ladder's occupancy bitset — the same rule
as the engine's cluster snapshot, which walks the ladders rather than the 1M-slot pool. An empty book
still sends a bracketed, zero-level cycle: "no liquidity" and "I cannot yet know" are different
answers, and a consumer must not render the first when it means the second.

### `message-schema.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<sbe:messageSchema xmlns:sbe="http://fixprotocol.io/2016/sbe"
                   package="com.engine.sbe" id="1" version="1"
                   semanticVersion="1.0" byteOrder="littleEndian">
  <types>
    <!-- SBE frame header: 8 bytes, so every message body starts 8-byte aligned. -->
    <composite name="messageHeader" description="Template ID and length of message root">
      <type name="blockLength" primitiveType="uint16"/>
      <type name="templateId"  primitiveType="uint16"/>
      <type name="schemaId"    primitiveType="uint16"/>
      <type name="version"     primitiveType="uint16"/>
    </composite>

    <type name="ParticipantId"   primitiveType="int64"/>
    <type name="ClOrdId"         primitiveType="int64"/>
    <type name="ExchangeOrderId" primitiveType="int64"/>
    <type name="SecurityId"      primitiveType="int32"/>
    <type name="Price"           primitiveType="int64" description="Fixed point, 8 implied decimals"/>
    <type name="Quantity"        primitiveType="int64"/>
    <type name="TradingDate"     primitiveType="int32" description="YYYYMMDD; 0 = GTC"/>
    <type name="Timestamp"       primitiveType="int64" description="Sequenced cluster time"/>
    <type name="SmpId"           primitiveType="int64" description="0 = default to participantId"/>
    <type name="CollarBps"       primitiveType="int32" description="Basis points; 1 bp = 0.01%"/>
    <type name="LevelCount"      primitiveType="int32"/>
    <type name="SeqNum"          primitiveType="int64" description="Per-stream, monotonic, gap-detectable"/>
    <type name="OrderCount"      primitiveType="int32"/>
    <type name="ShardId"         primitiveType="int32"/>
    <type name="StreamId"        primitiveType="int32"/>
    <type name="Symbol"          primitiveType="char" length="16"/>
    <type name="Isin"            primitiveType="char" length="12"/>
    <type name="CurrencyCode"    primitiveType="char" length="3"/>
    <type name="SecurityName"    primitiveType="char" length="48"/>
    <type name="ChannelUri"      primitiveType="char" length="128"/>

    <enum name="Side" encodingType="int8">
      <validValue name="BUY">0</validValue>
      <validValue name="SELL">1</validValue>
    </enum>
    <enum name="ExecType" encodingType="int8">
      <validValue name="NEW">0</validValue>
      <validValue name="TRADE">1</validValue>
      <validValue name="CANCELED">2</validValue>
      <validValue name="EXPIRED">3</validValue>
      <validValue name="REJECTED">4</validValue>
    </enum>
    <enum name="Phase" encodingType="int8">
      <validValue name="CLOSED">0</validValue>
      <validValue name="PRE_OPEN">1</validValue>
      <validValue name="OPEN_AUCTION">2</validValue>
      <validValue name="CONTINUOUS">3</validValue>
    </enum>
    <enum name="SmpStrategy" encodingType="int8">
      <validValue name="CANCEL_AGGRESSOR">0</validValue>
      <validValue name="CANCEL_RESTING">1</validValue>
    </enum>
    <enum name="RemoveReason" encodingType="int8">
      <validValue name="CANCELED">0</validValue>
      <validValue name="FILLED">1</validValue>
      <validValue name="EXPIRED">2</validValue>
    </enum>
    <enum name="RejectReason" encodingType="int32">
      <validValue name="NONE">0</validValue>
      <validValue name="PRICE_OUT_OF_BOUNDS">1</validValue>
      <validValue name="ORDER_EXPIRED">2</validValue>
      <validValue name="UNKNOWN_ORDER">3</validValue>
      <validValue name="UNAUTHORIZED_PARTICIPANT">4</validValue>
      <validValue name="MARKET_CLOSED">5</validValue>
      <validValue name="UNKNOWN_SECURITY">6</validValue>
      <validValue name="BOOK_CAPACITY">7</validValue>
      <validValue name="PRICE_OUT_OF_LADDER">8</validValue>
      <validValue name="SELF_MATCH_PREVENTED">9</validValue>
      <validValue name="VOLATILITY_HALT">10</validValue>
      <!-- Gateway-only: the cluster session could not accept the message. The engine
           never emits it, since a message it never saw cannot be rejected by it. -->
      <validValue name="GATEWAY_UNAVAILABLE">11</validValue>
    </enum>
  </types>

  <!-- ============================ Inbound ============================ -->

  <sbe:message name="NewOrderSingle" id="1" blockLength="56">
    <field name="participantId" id="1" type="ParticipantId"/>
    <field name="clOrdId"       id="2" type="ClOrdId"/>
    <field name="price"         id="3" type="Price"/>
    <field name="qty"           id="4" type="Quantity"/>
    <field name="smpId"         id="5" type="SmpId"/>
    <field name="securityId"    id="6" type="SecurityId"/>
    <field name="expireDate"    id="7" type="TradingDate"/>
    <field name="side"          id="8" type="Side"/>
    <field name="smpStrategy"   id="9" type="SmpStrategy"/>
  </sbe:message>

  <sbe:message name="OrderCancelRequest" id="2" blockLength="40">
    <field name="participantId"   id="1" type="ParticipantId"/>
    <field name="origClOrdId"     id="2" type="ClOrdId"/>
    <field name="clOrdId"         id="3" type="ClOrdId"/>
    <field name="exchangeOrderId" id="4" type="ExchangeOrderId"/>
    <field name="securityId"      id="5" type="SecurityId"/>
    <field name="side"            id="6" type="Side"/>
  </sbe:message>

  <sbe:message name="SessionTransition" id="3" blockLength="16">
    <field name="transitionTime" id="1" type="Timestamp"/>
    <field name="tradingDate"    id="2" type="TradingDate"/>
    <field name="targetPhase"    id="3" type="Phase"/>
  </sbe:message>

  <sbe:message name="PurgeExpiredOrders" id="4" blockLength="16">
    <field name="purgeTime"   id="1" type="Timestamp"/>
    <field name="tradingDate" id="2" type="TradingDate"/>
  </sbe:message>

  <sbe:message name="SecurityDefinition" id="5" blockLength="40">
    <field name="referencePrice"    id="1" type="Price"/>
    <field name="priceFloor"        id="2" type="Price"/>
    <field name="tickSize"          id="3" type="Price"/>
    <field name="securityId"        id="4" type="SecurityId"/>
    <field name="staticCollarBps"   id="5" type="CollarBps"/>
    <field name="dynamicCollarBps"  id="6" type="CollarBps"/>
    <field name="levelCount"        id="7" type="LevelCount"/>
  </sbe:message>

  <!-- ====================== Outbound: private ======================== -->

  <!-- cumQty is intentionally absent: the gateway derives it from origQty - leavesQty. -->
  <sbe:message name="ExecutionReport" id="10" blockLength="64">
    <field name="participantId"   id="1" type="ParticipantId"/>
    <field name="clOrdId"         id="2" type="ClOrdId"/>
    <field name="exchangeOrderId" id="3" type="ExchangeOrderId"/>
    <field name="price"           id="4" type="Price"/>
    <field name="lastQty"         id="5" type="Quantity"/>
    <field name="leavesQty"       id="6" type="Quantity"/>
    <field name="securityId"      id="7" type="SecurityId"/>
    <field name="rejectReason"    id="8" type="RejectReason"/>
    <field name="execType"        id="9" type="ExecType"/>
    <field name="side"            id="10" type="Side"/>
  </sbe:message>

  <!-- =================== Outbound: book events ======================= -->

  <sbe:message name="OrderAdded" id="20" blockLength="48">
    <field name="seqNum"          id="0" type="SeqNum"/>
    <field name="exchangeOrderId" id="1" type="ExchangeOrderId"/>
    <field name="price"           id="2" type="Price"/>
    <field name="qty"             id="3" type="Quantity"/>
    <field name="securityId"      id="4" type="SecurityId"/>
    <field name="shardId"         id="5" type="ShardId"/>
    <field name="side"            id="6" type="Side"/>
  </sbe:message>

  <sbe:message name="OrderReduced" id="21" blockLength="56">
    <field name="seqNum"          id="0" type="SeqNum"/>
    <field name="exchangeOrderId" id="1" type="ExchangeOrderId"/>
    <field name="price"           id="2" type="Price"/>
    <field name="lastQty"         id="3" type="Quantity"/>
    <field name="leavesQty"       id="4" type="Quantity"/>
    <field name="securityId"      id="5" type="SecurityId"/>
    <field name="shardId"         id="6" type="ShardId"/>
    <field name="side"            id="7" type="Side"/>
  </sbe:message>

  <!-- leavesQty makes the L2 feed derivable from the event stream alone: without the removed
       quantity every downstream aggregator would have to shadow per-order state. -->
  <sbe:message name="OrderRemoved" id="22" blockLength="48">
    <field name="seqNum"          id="0" type="SeqNum"/>
    <field name="exchangeOrderId" id="1" type="ExchangeOrderId"/>
    <field name="price"           id="2" type="Price"/>
    <field name="leavesQty"       id="3" type="Quantity"/>
    <field name="securityId"      id="4" type="SecurityId"/>
    <field name="shardId"         id="5" type="ShardId"/>
    <field name="side"            id="6" type="Side"/>
    <field name="reason"          id="7" type="RemoveReason"/>
  </sbe:message>

  <sbe:message name="TradeExecuted" id="23" blockLength="56">
    <field name="seqNum"        id="0" type="SeqNum"/>
    <field name="price"         id="1" type="Price"/>
    <field name="qty"           id="2" type="Quantity"/>
    <field name="takerOrderId"  id="3" type="ExchangeOrderId"/>
    <field name="makerOrderId"  id="4" type="ExchangeOrderId"/>
    <field name="securityId"    id="5" type="SecurityId"/>
    <field name="shardId"       id="6" type="ShardId"/>
    <field name="aggressorSide" id="7" type="Side"/>
  </sbe:message>

  <sbe:message name="AuctionUncrossed" id="24" blockLength="32">
    <field name="seqNum"       id="0" type="SeqNum"/>
    <field name="uncrossPrice" id="1" type="Price"/>
    <field name="executedQty"  id="2" type="Quantity"/>
    <field name="securityId"   id="3" type="SecurityId"/>
    <field name="shardId"      id="4" type="ShardId"/>
  </sbe:message>

  <sbe:message name="SessionChanged" id="25" blockLength="24">
    <field name="seqNum"     id="0" type="SeqNum"/>
    <field name="securityId" id="1" type="SecurityId"/>
    <field name="shardId"    id="2" type="ShardId"/>
    <field name="phase"      id="3" type="Phase"/>
  </sbe:message>

  <sbe:message name="VolatilityHalted" id="26" blockLength="48">
    <field name="seqNum"          id="0" type="SeqNum"/>
    <field name="collarReference" id="1" type="Price"     description="Snapshot at aggressor arrival"/>
    <field name="attemptedPrice"  id="2" type="Price"/>
    <field name="breachedBound"   id="3" type="Price"/>
    <field name="securityId"      id="4" type="SecurityId"/>
    <field name="shardId"         id="5" type="ShardId"/>
    <field name="aggressorSide"   id="6" type="Side"/>
  </sbe:message>


  <!-- ============ Derived feeds, published by the Market Data process ============ -->

  <sbe:message name="DepthUpdate" id="40" blockLength="40">
    <field name="seqNum"       id="1" type="SeqNum"/>
    <field name="price"        id="2" type="Price"/>
    <field name="aggregateQty" id="3" type="Quantity" description="0 means the level is gone"/>
    <field name="securityId"   id="4" type="SecurityId"/>
    <field name="shardId"      id="5" type="ShardId"/>
    <field name="orderCount"   id="6" type="OrderCount"/>
    <field name="side"         id="7" type="Side"/>
  </sbe:message>

  <sbe:message name="TopOfBook" id="41" blockLength="48">
    <field name="seqNum"     id="1" type="SeqNum"/>
    <field name="bidPrice"   id="2" type="Price"/>
    <field name="bidQty"     id="3" type="Quantity"/>
    <field name="askPrice"   id="4" type="Price"/>
    <field name="askQty"     id="5" type="Quantity"/>
    <field name="securityId" id="6" type="SecurityId"/>
    <field name="shardId"    id="7" type="ShardId"/>
  </sbe:message>

  <sbe:message name="LastTrade" id="42" blockLength="40">
    <field name="seqNum"        id="1" type="SeqNum"/>
    <field name="price"         id="2" type="Price"/>
    <field name="qty"           id="3" type="Quantity"/>
    <field name="securityId"    id="4" type="SecurityId"/>
    <field name="shardId"       id="5" type="ShardId"/>
    <field name="aggressorSide" id="6" type="Side"/>
  </sbe:message>

  <!-- The L2 recovery feed, on its own stream so a subscriber that does not want it pays nothing
       and a snapshot burst never delays the incremental feed.

       `l2SeqNum` is the DepthUpdate sequence the image is valid at, and it is what lets a late
       joiner splice the two feeds: install the image, discard buffered updates at or below it,
       replay the rest. Begin and End repeat it and the level count so a truncated cycle is
       discarded rather than installed: the staging discipline discovery already uses for the
       universe. One message per level rather than a repeating group: a full book exceeds the
       largest message a term buffer will carry. -->

  <sbe:message name="DepthSnapshotBegin" id="43" blockLength="48">
    <field name="seqNum"         id="1" type="SeqNum"/>
    <field name="l2SeqNum"       id="2" type="SeqNum"     description="The DepthUpdate sequence this image is valid at"/>
    <field name="lastTradePrice" id="3" type="Price"      description="Long.MIN_VALUE if nothing has traded"/>
    <field name="lastTradeQty"   id="4" type="Quantity"/>
    <field name="securityId"     id="5" type="SecurityId"/>
    <field name="shardId"        id="6" type="ShardId"/>
    <field name="levelCount"     id="7" type="OrderCount" description="DepthSnapshotLevel messages that follow"/>
  </sbe:message>

  <!-- Deliberately the same shape as DepthUpdate: a consumer applies a snapshot level and an
       incremental update through one code path, so an image cannot be assembled by rules the
       increments do not follow. -->
  <sbe:message name="DepthSnapshotLevel" id="44" blockLength="40">
    <field name="seqNum"       id="1" type="SeqNum"/>
    <field name="price"        id="2" type="Price"/>
    <field name="aggregateQty" id="3" type="Quantity"/>
    <field name="securityId"   id="4" type="SecurityId"/>
    <field name="shardId"      id="5" type="ShardId"/>
    <field name="orderCount"   id="6" type="OrderCount"/>
    <field name="side"         id="7" type="Side"/>
  </sbe:message>

  <sbe:message name="DepthSnapshotEnd" id="45" blockLength="32">
    <field name="seqNum"     id="1" type="SeqNum"/>
    <field name="l2SeqNum"   id="2" type="SeqNum"/>
    <field name="securityId" id="3" type="SecurityId"/>
    <field name="shardId"    id="4" type="ShardId"/>
    <field name="levelCount" id="5" type="OrderCount"/>
  </sbe:message>

  <!-- ================= Client-facing, published by the Gateway =================== -->

  <!-- The engine omits cumQty and origQty (no room in a 64-byte order slot); the gateway
       remembers origQty and restores both on the outbound leg. -->
  <sbe:message name="ClientExecutionReport" id="50" blockLength="80">
    <field name="participantId"   id="1"  type="ParticipantId"/>
    <field name="clOrdId"         id="2"  type="ClOrdId"/>
    <field name="exchangeOrderId" id="3"  type="ExchangeOrderId"/>
    <field name="price"           id="4"  type="Price"/>
    <field name="lastQty"         id="5"  type="Quantity"/>
    <field name="leavesQty"       id="6"  type="Quantity"/>
    <field name="cumQty"          id="7"  type="Quantity"/>
    <field name="origQty"         id="8"  type="Quantity"/>
    <field name="securityId"      id="9"  type="SecurityId"/>
    <field name="rejectReason"    id="10" type="RejectReason"/>
    <field name="execType"        id="11" type="ExecType"/>
    <field name="side"            id="12" type="Side"/>
  </sbe:message>


  <!-- ============ Tradable universe directory, published by Discovery ============ -->

  <!-- Broadcast periodically on a well-known channel rather than served on request: an adapter
       joins, waits for the next cycle, and builds its routing table with no session state. -->

  <sbe:message name="DirectoryBegin" id="60" blockLength="16">
    <field name="universeVersion" id="1" type="SeqNum" description="Changes iff the universe does"/>
    <field name="shardCount"      id="2" type="OrderCount"/>
    <field name="securityCount"   id="3" type="OrderCount"/>
  </sbe:message>

  <!-- The GATEWAY's client-facing endpoints, not the cluster's. An adapter talks to the
       gateway; the cluster ingress is internal to the gateway process. -->
  <sbe:message name="ShardEntry" id="61" blockLength="272">
    <field name="shardId"                 id="1" type="ShardId"/>
    <field name="orderEntryStreamId"      id="2" type="StreamId"/>
    <field name="executionReportStreamId" id="3" type="StreamId"/>
    <field name="orderEntryChannel"       id="4" type="ChannelUri"/>
    <field name="executionReportChannel"  id="5" type="ChannelUri"/>
  </sbe:message>

  <sbe:message name="SecurityEntry" id="62" blockLength="112">
    <field name="priceFloor"  id="1" type="Price"/>
    <field name="tickSize"    id="2" type="Price"/>
    <field name="securityId"  id="3" type="SecurityId"/>
    <field name="shardId"     id="4" type="ShardId"/>
    <field name="levelCount"  id="5" type="LevelCount"/>
    <field name="symbol"      id="6" type="Symbol"/>
    <field name="isin"        id="7" type="Isin"/>
    <field name="currency"    id="8" type="CurrencyCode"/>
    <field name="name"        id="9" type="SecurityName"/>
  </sbe:message>

  <sbe:message name="DirectoryEnd" id="63" blockLength="8">
    <field name="universeVersion" id="1" type="SeqNum"/>
  </sbe:message>

  <!-- ================= Snapshot (cluster-internal, never on the wire) ============ -->

  <sbe:message name="SnapshotEngineState" id="30" blockLength="16">
    <field name="nextExchangeOrderId" id="1" type="ExchangeOrderId"/>
    <field name="nextBookEventSeqNum" id="2" type="SeqNum"/>
  </sbe:message>

  <sbe:message name="SnapshotBook" id="31" blockLength="40">
    <field name="staticReference"   id="1" type="Price"/>
    <field name="dynamicReference"  id="2" type="Price"/>
    <field name="securityId"        id="3" type="SecurityId"/>
    <field name="tradingDate"       id="4" type="TradingDate"/>
    <field name="staticCollarBps"   id="5" type="CollarBps"/>
    <field name="dynamicCollarBps"  id="6" type="CollarBps"/>
    <field name="phase"             id="7" type="Phase"/>
  </sbe:message>

  <sbe:message name="SnapshotOrder" id="32" blockLength="64">
    <field name="participantId"   id="1" type="ParticipantId"/>
    <field name="smpId"           id="2" type="SmpId"/>
    <field name="clOrdId"         id="3" type="ClOrdId"/>
    <field name="exchangeOrderId" id="4" type="ExchangeOrderId"/>
    <field name="price"           id="5" type="Price"/>
    <field name="leavesQty"       id="6" type="Quantity"/>
    <field name="securityId"      id="7" type="SecurityId"/>
    <field name="expireDate"      id="8" type="TradingDate"/>
    <field name="side"            id="9" type="Side"/>
    <field name="smpStrategy"     id="10" type="SmpStrategy"/>
  </sbe:message>

  <sbe:message name="SnapshotEnd" id="33" blockLength="8">
    <field name="restingOrderCount" id="1" type="Quantity"/>
  </sbe:message>
</sbe:messageSchema>
```

---

## 6. Kotlin Implementation

Targets GraalVM Native Image. Zero heap allocation in steady state: primitive arrays, `inline fun`
callbacks, reused scratch objects, and `tryClaim` in-place encoding.

```kotlin
package com.engine.core

import io.aeron.ExclusivePublication
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.BufferClaim
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.collections.Long2LongHashMap

// --- Domain Value Classes (inline-only; never store in generic containers) ---
@JvmInline value class ParticipantId(val value: Long)
@JvmInline value class ExchangeOrderId(val value: Long)
@JvmInline value class Price(val value: Long)
@JvmInline value class Quantity(val value: Long)

const val NULL_INDEX = -1
const val NULL_LEVEL = -1
const val NO_UNCROSS = Long.MIN_VALUE   // auction produced no trade

// --- Packed order pool layout: 8 longs = 64 bytes = one cache line ---
object OrderField {
    const val STRIDE            = 8
    const val PRICE             = 0
    const val LEAVES_QTY        = 1
    const val PARTICIPANT_ID    = 2
    const val SMP_ID            = 3   // effective: smpId, or participantId when smpId == 0
    const val CL_ORD_ID         = 4
    const val EXCHANGE_ORDER_ID = 5
    const val LINKS             = 6   // next: low int32 | prev: high int32
    const val META              = 7   // expireDate: int32 | side: byte 4 | smpStrategy: byte 5
}

// Not inline: no function parameters, nothing to box. `inline` is reserved below for the
// callback-taking hot-path functions, where it prevents lambda and value-class allocation.
private fun packLinks(next: Int, prev: Int): Long =
    (next.toLong() and 0xFFFF_FFFFL) or (prev.toLong() shl 32)

private fun nextOf(links: Long): Int = links.toInt()
private fun prevOf(links: Long): Int = (links ushr 32).toInt()

private fun packMeta(expireDate: Int, side: Byte, smpStrategy: Byte): Long =
    (expireDate.toLong() and 0xFFFF_FFFFL) or
    ((side.toLong() and 0xFF) shl 32) or
    ((smpStrategy.toLong() and 0xFF) shl 40)

private fun expireDateOf(meta: Long): Int = meta.toInt()
private fun sideOf(meta: Long): Byte = ((meta ushr 32) and 0xFF).toByte()
private fun smpStrategyOf(meta: Long): Byte = ((meta ushr 40) and 0xFF).toByte()

object Side { const val BUY: Byte = 0; const val SELL: Byte = 1 }

object SmpStrategy { const val CANCEL_AGGRESSOR: Byte = 0; const val CANCEL_RESTING: Byte = 1 }

/** Resolved once at order entry; only the effective value is stored. */
fun effectiveSmpId(smpId: Long, participantId: Long): Long =
    if (smpId != 0L) smpId else participantId

const val BPS_DENOMINATOR = 10_000L

/** Reusable scratch — why an aggressive order stopped. Never allocated on the hot path. */
object MatchStatus {
    const val COMPLETE: Byte = 0        // filled, or rested with no obstruction
    const val SELF_MATCH_STOP: Byte = 1 // CANCEL_AGGRESSOR fired
    const val COLLAR_BREACH: Byte = 2   // dynamic collar breached -> volatility halt
}

class MatchOutcome {
    var filledQty: Long = 0L
    var status: Byte = MatchStatus.COMPLETE
    var collarReference: Long = 0L
    var attemptedPrice: Long = 0L
    var breachedBound: Long = 0L
}

// =====================================================================
//  Price Ladder — direct-mapped levels + occupancy bitset
// =====================================================================
class PriceLadder(private val levelCount: Int) {
    val head      = IntArray(levelCount) { NULL_INDEX }
    val tail      = IntArray(levelCount) { NULL_INDEX }
    val levelQty  = LongArray(levelCount)
    val orderCount = IntArray(levelCount)
    private val occupancy = LongArray((levelCount + 63) ushr 6)

    fun markOccupied(level: Int) {
        occupancy[level ushr 6] = occupancy[level ushr 6] or (1L shl (level and 63))
    }

    fun markEmpty(level: Int) {
        occupancy[level ushr 6] = occupancy[level ushr 6] and (1L shl (level and 63)).inv()
    }

    /** Best bid: highest occupied level at or below [start]. */
    fun highestOccupiedAtOrBelow(start: Int): Int {
        if (start < 0) return NULL_LEVEL
        var w = start ushr 6
        var word = occupancy[w] and (-1L ushr (63 - (start and 63)))
        while (true) {
            if (word != 0L) return (w shl 6) + (63 - word.countLeadingZeroBits())
            if (w == 0) return NULL_LEVEL
            w--
            word = occupancy[w]
        }
    }

    /** Best ask: lowest occupied level at or above [start]. */
    fun lowestOccupiedAtOrAbove(start: Int): Int {
        if (start >= levelCount) return NULL_LEVEL
        var w = start ushr 6
        var word = occupancy[w] and (-1L shl (start and 63))
        while (true) {
            if (word != 0L) return (w shl 6) + word.countTrailingZeroBits()
            w++
            if (w >= occupancy.size) return NULL_LEVEL
            word = occupancy[w]
        }
    }
}

// =====================================================================
//  Order Book
// =====================================================================
class OrderBook(
    val securityId: Int,
    private val priceFloor: Long,
    private val tickSize: Long,
    private val levelCount: Int = 65_536,
    private val maxOrders: Int = 1_000_000,
    private val capacityHighWaterMark: Int = (1_000_000 * 0.95).toInt()
) {
    var phase: Byte = Phase.CLOSED
    var tradingDate: Int = 0

    // Both seeded by SecurityDefinition, then diverge (§4.4):
    var staticReference: Long = 0L    // session anchor; reset by every executing uncross
    var dynamicReference: Long = 0L   // tracks every trade
    var staticCollarBps: Int = 0
    var dynamicCollarBps: Int = 0

    // @PublishedApi internal, not private: the inline matching and purge functions below
    // read these directly, and a public inline fun cannot touch private members.
    @PublishedApi internal val orders = LongArray(maxOrders * OrderField.STRIDE)
    @PublishedApi internal val bids = PriceLadder(levelCount)
    @PublishedApi internal val asks = PriceLadder(levelCount)
    @PublishedApi internal var bestBidLevel = NULL_LEVEL
    @PublishedApi internal var bestAskLevel = NULL_LEVEL

    // Agrona has no Long2IntHashMap; Long2LongHashMap is the narrowest primitive map with
    // a long key, so the int node index is widened to a long value.
    private val orderIdToIndex =
        Long2LongHashMap(maxOrders * 2, 0.65f, NULL_INDEX.toLong())
    private var freeHead = 0
    private var usedCount = 0

    init {
        for (i in 0 until maxOrders - 1) {
            orders[i * OrderField.STRIDE + OrderField.LINKS] = packLinks(i + 1, NULL_INDEX)
        }
        orders[(maxOrders - 1) * OrderField.STRIDE + OrderField.LINKS] =
            packLinks(NULL_INDEX, NULL_INDEX)
    }

    fun levelOf(price: Long): Int = ((price - priceFloor) / tickSize).toInt()
    fun priceOf(level: Int): Long = priceFloor + level * tickSize
    fun isLevelInRange(level: Int): Boolean = level in 0 until levelCount
    fun hasCapacity(): Boolean = usedCount < capacityHighWaterMark

    fun leavesQtyOf(nodeIdx: Int): Long = orders[nodeIdx * OrderField.STRIDE + OrderField.LEAVES_QTY]

    // --- Collars (§4.4) ------------------------------------------------

    /**
     * Static collar, applied at order ACCEPTANCE against the live reference price.
     * The ladder range is configured strictly wider than this band, so it always fires
     * before a ladder overflow could.
     */
    fun isPriceOutOfBounds(price: Long): Boolean {
        val bound = staticReference * staticCollarBps / BPS_DENOMINATOR
        val deviation = if (price >= staticReference) price - staticReference
                        else staticReference - price
        return deviation > bound
    }

    /**
     * Dynamic collar, applied at EXECUTION as a per-level gate. [collarReference] is the
     * snapshot taken when the aggressing order arrived — deliberately not the live
     * dynamicReference, which its own fills are advancing. See §4.4 on ratcheting.
     */
    fun dynamicBound(collarReference: Long): Long =
        collarReference * dynamicCollarBps / BPS_DENOMINATOR

    fun breachesDynamicCollar(price: Long, collarReference: Long, bound: Long): Boolean {
        val deviation = if (price >= collarReference) price - collarReference
                        else collarReference - price
        return deviation > bound
    }

    // --- Booking -----------------------------------------------------

    /** Links a new order at the tail of its price level (time priority). */
    fun book(
        exchangeOrderId: Long, participantId: Long, smpId: Long, clOrdId: Long,
        price: Long, leavesQty: Long, expireDate: Int, side: Byte, smpStrategy: Byte
    ): Int {
        val idx = freeHead
        check(idx != NULL_INDEX) { "pool exhausted despite high-water-mark guard" }
        freeHead = nextOf(orders[idx * OrderField.STRIDE + OrderField.LINKS])
        usedCount++

        val base = idx * OrderField.STRIDE
        orders[base + OrderField.PRICE]             = price
        orders[base + OrderField.LEAVES_QTY]        = leavesQty
        orders[base + OrderField.PARTICIPANT_ID]    = participantId
        orders[base + OrderField.SMP_ID]            = smpId   // already resolved at entry
        orders[base + OrderField.CL_ORD_ID]         = clOrdId
        orders[base + OrderField.EXCHANGE_ORDER_ID] = exchangeOrderId
        orders[base + OrderField.META]              = packMeta(expireDate, side, smpStrategy)

        val ladder = if (side == Side.BUY) bids else asks
        val level = levelOf(price)
        val prevTail = ladder.tail[level]
        orders[base + OrderField.LINKS] = packLinks(NULL_INDEX, prevTail)

        if (prevTail == NULL_INDEX) {
            ladder.head[level] = idx
            ladder.markOccupied(level)
            if (side == Side.BUY) {
                if (level > bestBidLevel) bestBidLevel = level
            } else {
                if (bestAskLevel == NULL_LEVEL || level < bestAskLevel) bestAskLevel = level
            }
        } else {
            val pb = prevTail * OrderField.STRIDE + OrderField.LINKS
            orders[pb] = packLinks(idx, prevOf(orders[pb]))
        }
        ladder.tail[level] = idx
        ladder.levelQty[level] += leavesQty
        ladder.orderCount[level]++

        orderIdToIndex.put(exchangeOrderId, idx)
        return idx
    }

    @PublishedApi internal fun unlink(nodeIdx: Int) {
        val base = nodeIdx * OrderField.STRIDE
        val side = sideOf(orders[base + OrderField.META])
        val ladder = if (side == Side.BUY) bids else asks
        val level = levelOf(orders[base + OrderField.PRICE])
        val links = orders[base + OrderField.LINKS]
        val next = nextOf(links)
        val prev = prevOf(links)

        if (prev == NULL_INDEX) ladder.head[level] = next
        else { val pb = prev * OrderField.STRIDE + OrderField.LINKS
               orders[pb] = packLinks(next, prevOf(orders[pb])) }

        if (next == NULL_INDEX) ladder.tail[level] = prev
        else { val nb = next * OrderField.STRIDE + OrderField.LINKS
               orders[nb] = packLinks(nextOf(orders[nb]), prev) }

        ladder.levelQty[level] -= orders[base + OrderField.LEAVES_QTY]
        ladder.orderCount[level]--

        if (ladder.head[level] == NULL_INDEX) {
            ladder.markEmpty(level)
            if (side == Side.BUY && level == bestBidLevel) {
                bestBidLevel = ladder.highestOccupiedAtOrBelow(level - 1)
            } else if (side == Side.SELL && level == bestAskLevel) {
                bestAskLevel = ladder.lowestOccupiedAtOrAbove(level + 1)
            }
        }

        orderIdToIndex.remove(orders[base + OrderField.EXCHANGE_ORDER_ID])
        orders[base + OrderField.LINKS] = packLinks(freeHead, NULL_INDEX)
        freeHead = nodeIdx
        usedCount--
    }

    fun indexOf(exchangeOrderId: Long): Int = orderIdToIndex.get(exchangeOrderId)

    // --- Continuous matching -----------------------------------------

    /**
     * Walks the opposite ladder from the touch while the taker price crosses, consuming each
     * level's FIFO in time priority.
     *
     * Two gates, in this order (§4.5):
     *   - dynamic collar, per LEVEL, before any fill at that level;
     *   - self-match prevention, per RESTING ORDER, before the fill is applied.
     *
     * Results land in the reusable [outcome]; callbacks are inline, so no lambda object and
     * no boxing of the value-class parameters.
     */
    inline fun matchAggressive(
        takerPrice: Long, takerQty: Long, takerSide: Byte,
        takerSmpId: Long, takerSmpStrategy: Byte,
        outcome: MatchOutcome,
        onFill: (makerNodeIdx: Int, fillPrice: Long, fillQty: Long, makerLeavesQty: Long) -> Unit,
        onSelfMatchCancelResting: (makerNodeIdx: Int) -> Unit
    ) {
        // Snapshot: NOT re-read as our own fills advance dynamicReference (§4.4).
        val collarReference = dynamicReference
        val bound = dynamicBound(collarReference)
        var remaining = takerQty
        outcome.status = MatchStatus.COMPLETE
        outcome.collarReference = collarReference

        levels@ while (remaining > 0) {
            val level = if (takerSide == Side.BUY) bestAskLevel else bestBidLevel
            if (level == NULL_LEVEL) break
            val levelPrice = priceOf(level)
            val crosses = if (takerSide == Side.BUY) levelPrice <= takerPrice
                          else levelPrice >= takerPrice
            if (!crosses) break

            // Level gate: nothing at or beyond this price may trade.
            if (breachesDynamicCollar(levelPrice, collarReference, bound)) {
                outcome.status = MatchStatus.COLLAR_BREACH
                outcome.attemptedPrice = levelPrice
                outcome.breachedBound = bound
                break@levels
            }

            val ladder = if (takerSide == Side.BUY) asks else bids
            while (remaining > 0) {
                val maker = ladder.head[level]
                if (maker == NULL_INDEX) break
                val mb = maker * OrderField.STRIDE

                // Order gate: self-match. Never prints a trade, so never moves the reference.
                if (orders[mb + OrderField.SMP_ID] == takerSmpId) {
                    if (takerSmpStrategy == SmpStrategy.CANCEL_AGGRESSOR) {
                        outcome.status = MatchStatus.SELF_MATCH_STOP
                        break@levels
                    }
                    onSelfMatchCancelResting(maker)   // CANCEL_RESTING
                    unlink(maker)
                    continue
                }

                val makerLeaves = orders[mb + OrderField.LEAVES_QTY]
                val fill = if (makerLeaves <= remaining) makerLeaves else remaining
                val makerAfter = makerLeaves - fill
                orders[mb + OrderField.LEAVES_QTY] = makerAfter
                ladder.levelQty[level] -= fill
                remaining -= fill

                dynamicReference = levelPrice   // tracks every trade (§4.4)
                onFill(maker, levelPrice, fill, makerAfter)
                if (makerAfter == 0L) unlink(maker)
            }
        }
        outcome.filledQty = takerQty - remaining
    }

    // --- Opening auction ---------------------------------------------

    /**
     * Uncrossing price: max executable volume, then min imbalance, then surplus side,
     * then nearest reference price. [scratch] is an engine-wide reusable buffer.
     */
    fun computeUncrossPrice(scratch: LongArray): Long {
        val bb = bestBidLevel; val ba = bestAskLevel
        if (bb == NULL_LEVEL || ba == NULL_LEVEL || ba > bb) return dynamicReference

        var demand = 0L
        for (lvl in bb downTo ba) { demand += bids.levelQty[lvl]; scratch[lvl] = demand }

        var supply = 0L
        var bestVolume = -1L; var bestImbalance = Long.MAX_VALUE
        var chosen = dynamicReference
        for (lvl in ba..bb) {
            supply += asks.levelQty[lvl]
            val d = scratch[lvl]
            val volume = if (d < supply) d else supply
            val imbalance = d - supply
            val absImb = if (imbalance < 0) -imbalance else imbalance
            val price = priceOf(lvl)
            val better = when {
                volume > bestVolume  -> true
                volume < bestVolume  -> false
                absImb < bestImbalance -> true
                absImb > bestImbalance -> false
                imbalance > 0        -> true   // buy surplus: prefer higher price
                imbalance < 0        -> false  // sell surplus: prefer lower price
                else -> kotlin.math.abs(price - dynamicReference) <
                        kotlin.math.abs(chosen - dynamicReference)
            }
            if (better) { bestVolume = volume; bestImbalance = absImb; chosen = price }
        }
        return chosen
    }

    /**
     * Runs the auction to a fixed point (§4.5). The uncross price and the set of SMP
     * cancellations are mutually dependent, so recompute until a walk cancels nothing.
     *
     * Terminates because every iteration either exits or cancels at least one order, and
     * cancellation is monotonic over a finite book. [maxPasses] is a safety valve, not part
     * of the algorithm: exceeding it signals a pathological book or a defect.
     *
     * Returns the executed uncross price, or NO_UNCROSS when the book does not cross.
     */
    inline fun uncross(
        scratch: LongArray,
        maxPasses: Int,
        onSelfMatchCancel: (nodeIdx: Int) -> Unit,
        onPassLimitExceeded: () -> Unit,
        onFill: (buyIdx: Int, sellIdx: Int, price: Long, qty: Long) -> Unit
    ): Long {
        var pass = 0
        while (true) {
            val price = computeUncrossPrice(scratch)
            if (bestBidLevel == NULL_LEVEL || bestAskLevel == NULL_LEVEL ||
                bestAskLevel > bestBidLevel) return NO_UNCROSS

            if (++pass > maxPasses) { onPassLimitExceeded(); return NO_UNCROSS }

            val canceled = resolveSelfMatches(price, onSelfMatchCancel)
            if (canceled == 0) {
                executeAllocation(price, onFill)
                onUncrossExecuted(price)
                return price
            }
            // Volume left the book — the price may have moved. Recompute.
        }
    }

    /**
     * One two-cursor merge over both sides in price-time priority, pairing every buy that
     * crosses [price] against every such sell. On a pair sharing an effective smpId, the later
     * order (higher exchangeOrderId) plays the aggressor and its smpStrategy decides which
     * side is canceled; the cursor advances past the canceled order and the walk continues —
     * which is exactly a recompute at the same price, so no restart is needed and no order is
     * canceled on a pairing an earlier cancellation already invalidated.
     *
     * Cancels and reports; prints no trades. Returns the number of orders canceled.
     */
    inline fun resolveSelfMatches(price: Long, onSelfMatchCancel: (nodeIdx: Int) -> Unit): Int {
        // Cursor mechanics elided: descend bids from bestBidLevel to levelOf(price),
        // ascend asks from bestAskLevel to levelOf(price), FIFO within each level.
        return 0
    }

    /**
     * The same merge, run once the walk is stable, printing fills at the single uncross price.
     * Guaranteed self-match free: [resolveSelfMatches] returned 0 for this exact book and price.
     */
    inline fun executeAllocation(
        price: Long,
        onFill: (buyIdx: Int, sellIdx: Int, price: Long, qty: Long) -> Unit
    ) {
        // Decrements leavesQty, unlinks fully filled orders, advances dynamicReference.
    }

    /**
     * An uncross that EXECUTES sets both references (§4.4). Not called when the auction
     * produces no trade — an uncrossed book leaves both anchors untouched.
     */
    fun onUncrossExecuted(uncrossPrice: Long) {
        staticReference = uncrossPrice
        dynamicReference = uncrossPrice
    }


    // --- Off-session expiry purge ------------------------------------

    /**
     * Walks both ladders. Avoids the Long2LongHashMap iterator-removal hazard entirely and
     * has far better locality than a hash scan.
     */
    inline fun purgeExpired(
        currentTradingDate: Int,
        onExpire: (nodeIdx: Int, price: Long, side: Byte) -> Unit
    ) {
        purgeLadder(bids, currentTradingDate, onExpire)
        purgeLadder(asks, currentTradingDate, onExpire)
    }

    inline fun purgeLadder(
        ladder: PriceLadder, currentTradingDate: Int,
        onExpire: (nodeIdx: Int, price: Long, side: Byte) -> Unit
    ) {
        var level = ladder.lowestOccupiedAtOrAbove(0)
        while (level != NULL_LEVEL) {
            var node = ladder.head[level]
            while (node != NULL_INDEX) {
                val base = node * OrderField.STRIDE
                val meta = orders[base + OrderField.META]
                val nextNode = nextOf(orders[base + OrderField.LINKS])  // capture before unlink
                val expDate = expireDateOf(meta)
                if (expDate in 1 until currentTradingDate) {
                    onExpire(node, orders[base + OrderField.PRICE], sideOf(meta))
                    unlink(node)
                }
                node = nextNode
            }
            level = ladder.lowestOccupiedAtOrAbove(level + 1)
        }
    }
}

// =====================================================================
//  Clustered Service
// =====================================================================
class MatchingEngineService(
    private val books: Array<OrderBook>,          // ≤ 10 per shard
    private val securityIdToIndex: Long2LongHashMap
) : ClusteredService {

    private lateinit var cluster: Cluster
    private var bookEventPub: ExclusivePublication? = null   // leader only
    private var isLeader = false

    private var nextExchangeOrderId = 1L
    private val claim = BufferClaim()
    private val uncrossScratch = LongArray(65_536)
    private val matchOutcome = MatchOutcome()   // reused; never allocated per order

    override fun onStart(cluster: Cluster, snapshotImage: io.aeron.Image?) {
        this.cluster = cluster
        snapshotImage?.let { loadSnapshot(it) }
    }

    override fun onRoleChange(newRole: Cluster.Role) {
        // Book events are a plain publication, not cluster egress, so mute explicitly.
        isLeader = newRole == Cluster.Role.LEADER
    }

    override fun onSessionMessage(
        session: ClientSession, timestamp: Long,
        buffer: DirectBuffer, offset: Int, length: Int, header: Header
    ) {
        // Decode with generated SBE flyweights; dispatch on templateId.
        // Time comes from `timestamp` / cluster.time() — never from the system clock.
    }

    private fun processNewOrder(session: ClientSession, /* decoded fields */) {
        // 1. securityId → book index (defensive range check; gateway validated already)
        // 2. reject if phase == CLOSED               → MARKET_CLOSED
        // 3. reject if expireDate in 1..<tradingDate → ORDER_EXPIRED
        // 4. reject if !book.hasCapacity()           → BOOK_CAPACITY
        // 5. reject if book.isPriceOutOfBounds()     → PRICE_OUT_OF_BOUNDS  (static collar)
        // 6. smpId = effectiveSmpId(smpId, participantId)   — resolve ONCE, here
        // 7. assign exchangeOrderId; emit NEW execution report
        // 8. if phase == CONTINUOUS: matchAggressive(...), tracking a RUNNING remainder for
        //    leavesQty on each fill report (see §3.1) — not origQty − thisFill
        // 9. dispatch on matchOutcome.status (below)
    }

    /** Resolution of an aggressive order's outcome. See §4.5 and §4.6. */
    private fun onMatchComplete(book: OrderBook, /* order context */) {
        when (matchOutcome.status) {
            MatchStatus.COMPLETE ->
                // Book any remainder — in EVERY phase except CLOSED (§4.1).
                Unit

            MatchStatus.SELF_MATCH_STOP ->
                // CANCEL_AGGRESSOR: cancel the remainder, do NOT book it.
                // CANCELED / SELF_MATCH_PREVENTED. Resting order untouched.
                Unit

            MatchStatus.COLLAR_BREACH -> {
                // §4.6, in order. Fills already taken stand and were reported normally.
                //   a) cancel the aggressor's remainder: CANCELED / VOLATILITY_HALT, not booked
                //   b) book.phase = Phase.CLOSED — resting orders left entirely intact
                //   c) emit VolatilityHalted(collarReference, attemptedPrice, breachedBound, side)
                //   d) emit SessionChanged(securityId, CLOSED)
                // Recovery is operator-driven: no timer is scheduled, no automatic reopen.
                Unit
            }
        }
    }

    /** Encodes directly into the log buffer — no scratch buffer, no copy. */
    private inline fun publishBookEvent(length: Int, encode: (BufferClaim) -> Unit) {
        val pub = bookEventPub ?: return
        if (!isLeader) return
        var backoff = 0
        while (pub.tryClaim(length, claim) < 0) {
            // Sustained backpressure is an operational emergency, not a normal state:
            // it cannot be buffered (no allocation) or dropped (consumers need every event).
            // Count it, alert on it, and escalate to failover rather than spinning forever.
            if (++backoff > BACKPRESSURE_ALERT_THRESHOLD) onBackpressureStall()
        }
        encode(claim)
        claim.commit()
    }

    override fun onTakeSnapshot(snapshotPublication: ExclusivePublication) {
        // Serialize only OCCUPIED orders by walking each book's ladders — never the whole
        // 1M-slot pool. Includes phase, tradingDate, BOTH references, the collar
        // configuration, and nextExchangeOrderId.
    }

    override fun onTimerEvent(correlationId: Long, timestamp: Long) {
        // Cluster timers are delivered through the replicated log and are therefore
        // deterministic — a valid trigger for the off-session purge.
    }

    private fun loadSnapshot(image: io.aeron.Image) { /* inverse of onTakeSnapshot */ }
    private fun onBackpressureStall() { /* metric + operator alert */ }

    override fun onSessionOpen(session: ClientSession, timestamp: Long) {}
    override fun onSessionClose(session: ClientSession, timestamp: Long,
                                closeReason: io.aeron.cluster.codecs.CloseReason) {}
    override fun onTerminate(cluster: Cluster) {}

    private companion object { const val BACKPRESSURE_ALERT_THRESHOLD = 1_000_000 }
}
```

---

## 7. GraalVM Native Image Deployment & Tuning

### Build Configuration

```ini
Args = --no-fallback \
       -O3 \
       -march=x86-64-v3 \
       --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
       --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
       -J--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
       -J--add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
       --initialize-at-build-time=kotlin.DeprecationLevel \
       --initialize-at-build-time=org.agrona.UnsafeApi \
       --initialize-at-run-time=io.aeron.driver.MediaDriver,org.agrona.concurrent.UnsafeBuffer
```

These live in the **root** `build.gradle.kts`, applied to every module that carries the native
plugin, because they are a property of the Aeron/Agrona dependency stack rather than of any one
process. A module's own `graalvmNative` block sets only its image name, its main class, and (for
`engine` alone) the staged Epsilon switch. Four copies of this list is how one of them drifts and
fails at run time in the one process nobody rebuilt.

**No `reflection-config.json` is needed.** Earlier drafts of this section assumed one registering
`sun.misc.Unsafe` and `UnsafeBuffer`. Building the images proved otherwise: the analysis reaches
everything Aeron and Agrona need on its own, and the reachability-metadata repository is switched
off (`metadataRepository { enabled = false }`) because neither library appears in it and its schema
demands a newer GraalVM than the build is pinned to.

**The `--add-exports` flags are the load-bearing part, and both forms are required.** Agrona 2.x
reaches `jdk.internal.misc.Unsafe` and the Aeron driver reaches `sun.nio.ch`. On the JVM these are
`--add-opens` at *run* time; for a native image they must be exports at *image-build* time. Without
them native-image does not fail — the analysis simply cannot see the class, silently omits it from
the image, and the binary starts, prints its fingerprint, and dies with
`NoClassDefFoundError: jdk.internal.misc.Unsafe` on the first `UnsafeBuffer`. That is only reached
once a real Aeron CnC file exists, so a smoke test against a *missing* media driver passes and hides
it. The bare form applies to the image; the `-J` form opens the package to the builder JVM, which is
what actually loads the class during analysis.

**`org.agrona.UnsafeApi` must be initialized at build time, and cannot be anything else.** It reaches
`Unsafe` through an `invokedynamic` call site, and resolving that site during analysis runs its
`<clinit>` — so leaving it to the default run-time policy fails the build with "unintentionally
initialized at build time". SVM substitutes `jdk.internal.misc.Unsafe` with its own singleton, so
nothing host-specific is baked into the image heap. Note this sits directly beside
`--initialize-at-run-time=...UnsafeBuffer`: the buffer defers, the API holder cannot.

**`-march` is pinned, not `native`.** A `native` build SIGILLs when the build host's CPU differs from
production. Build in a container matching the production instance type.

### Garbage Collector — Staged Adoption

`--gc=epsilon` is the eventual target but **not the initial production setting**. Epsilon turns a
benign, slow allocation leak into a hard crash, and — being deterministic — it crashes every cluster
node at the same log position. Sequence:

1. Ship with Serial GC and a generously sized young generation.
2. Prove zero steady-state allocation empirically under production-shaped load.
3. Add an allocation assertion to CI so a regression fails the build, not the exchange.
4. Then switch to `--gc=epsilon`.

Run the Aeron `MediaDriver` as a **separate process** (the engine attaches via the CnC file). The
driver allocates; keeping it out of the engine binary means the engine can credibly claim a
zero-allocation steady state.

### Process Layout

A node is **two processes**, because the media driver allocates and must stay out of the engine
binary for the Epsilon GC profile to be credible:

```
  1. io.aeron.cluster.ClusteredMediaDriver   driver + archive + consensus module
  2. matching-engine                          ClusteredServiceContainer + MatchingEngineService
```

Both attach to the same Aeron directory. The engine binary starts only the service container; if the
driver is not already running it reports that and exits rather than stack-tracing a
`DriverTimeoutException`.

### Market Data Process

Consumes the book event stream and maintains a **`DepthBook`** per security: price-aggregated
quantity and order count per level, with the same occupancy-bitset trick the engine's ladder uses so
best bid and offer are a word scan. It keeps no order queues and no per-order state at all — the
event stream is self-describing enough without it.

L1 is published only when the touch actually **moves**. A busy book generates depth updates
continuously, and forwarding an unchanged top-of-book on each one would flood the feed that most
subscribers care about most.

Geometry (`priceFloor`, `tickSize`, `levelCount`) must match the engine's exactly: the same values
that turn a price into a ladder level there turn it into a depth level here.

### Where Reference Data Is Authored

Reference data and shard topology are authored in Postgres through the `control` module and
**published** as the same shard security files described below; `docs/ControlPlane.md` is the whole
picture. The split is deliberate and is the design's, not a convenience:

* **The database is not on any process's boot path.** A node reads a file. A database outage would
  otherwise stop a node starting, and — the reason that matters — a write landing between two nodes'
  boots would give them different geometry. They would not fail; they would diverge on the first
  order, which is the one class of misconfiguration consensus cannot catch.
* **A release is immutable.** Publishing writes a numbered directory and records the fingerprint per
  shard; republishing allocates the next version rather than rewriting one, so a directory a running
  process was pointed at never changes underneath it. Rendering is deterministic — ordered, no
  timestamp — so identical content publishes to identical bytes and two releases can be diffed.
* **The control plane reimplements no rule.** It constructs the real `SecuritySpec`, `ShardSpec`,
  `ShardRoute` and `Universe` from its rows, so ISIN check digits, the wire-derived length limits,
  ten securities per shard, one shard per security, and the fingerprint itself all come from one
  implementation. A second one that drifted by a separator would report agreement between processes
  that disagree, which is worse than not checking.
* **A release carries topology, not deployment.** Aeron directories, cluster directories and feed
  channels stay in each process's own configuration; the release carries only what every process
  must agree on.

One duplication this removes: a gateway's client endpoints are declared in both `gateway.properties`
and `discovery.properties` today with nothing checking they agree, and are now one row rendered into
both.

`ShardSpec.render()` and `Universe.render()` are the inverses of the `from(Properties)` parsers and
live beside them, so a generator that emitted a key the parser does not read fails a round-trip test
rather than a deployment.

---

### Shard Configuration — One Security List

A shard has **one security file**, read by the engine, the gateway, the market data process and
discovery alike. Each carries identity for humans and downstream systems (`symbol`, `isin`, `name`,
`currency`) and geometry for the books (`priceFloor`, `tickSize`, `levelCount`, `maxOrders`).

The processes previously each kept their own list with nothing checking they agreed: a gateway
listing a security its engine did not host would forward orders that came back `UNKNOWN_SECURITY`,
and one listing fewer would reject orders the engine would have taken. One file removes the class of
error rather than detecting it.

Reference prices and collar widths are *not* configuration: they arrive as `SecurityDefinition`
commands through the replicated log, because they change during a session and every node must apply
them at the same log position (§4.4).

**Every node and every process must boot against the same file.** Geometry decides how a price maps
to a ladder level, so a mismatch would diverge the books rather than fail loudly — the one class of
misconfiguration consensus cannot catch. All four processes print a **fingerprint** of the shard
spec at startup, so an operator compares one hex string instead of diffing files. Node-local
settings (`serviceId`, directories, channels) are excluded, since those legitimately differ, and so
is `name`, which is display only.

Nothing has a default. A wrong tick size misprices every order silently, and ISINs are validated
including their check digit: a transposed digit is the classic reference-data typo, and it is far
cheaper to reject at boot than to discover in a published directory.

### Tradable Universe Discovery

A gateway serves exactly one shard — it holds a single cluster connection and rejects anything
outside its own security list. Something therefore has to tell an upstream protocol adapter *which*
gateway to send a given symbol to. The **discovery process** publishes that:

```
   shard security files  ──►  Discovery  ──► DirectoryBegin
   (the same files the                       ShardEntry     x shards
    shards boot from)                        SecurityEntry  x securities
                                             DirectoryEnd
                                                  │ multicast, every N seconds
                                                  ▼
                                   Upstream protocol adapters (FIX, …)
                                   build symbol -> shard -> ingress channel
```

The directory is **derived from the shards' own security files**, so the universe and the running
engines cannot describe different geometry — there is one definition, read twice.

It is **broadcast on a cycle rather than served on request**. An adapter joins, waits at most one
interval, and is ready: no session state, no request protocol, and a restarted adapter recovers by
itself. The same reasoning as the market data feeds.

A broadcast is **staged and committed atomically** by the client: entries accumulate and only
replace the live routing table when `DirectoryEnd` arrives carrying the version `DirectoryBegin`
announced. A cycle truncated by a lost datagram leaves the previous table intact rather than routing
against half a universe.

`universeVersion` changes if and only if the content does, so a repeat broadcast is distinguishable
from a genuine update without diffing.

**What the directory publishes is the *gateway's* client endpoints**, not the cluster's ingress and
egress. Those belong to the gateway process, which is the only thing holding a cluster session; an
adapter that connected to them directly would bypass the validation and the `cumQty` reconstruction
the gateway exists to perform. Each `ShardEntry` therefore carries an order entry channel and stream
and an execution report channel and stream.

**The invariant discovery enforces is one shard per security.** Books are independent and nothing
matches across shards, so a security served by two shards would hand clients two disjoint books
under a single identifier with no error anywhere to say so. Symbols and ISINs must likewise be
unique across the whole universe.

### JVM Flags

Agrona 2.x reaches `jdk.internal.misc.Unsafe` for its buffer intrinsics and the Aeron driver uses
`sun.nio.ch`; neither is exported to the unnamed module on JDK 17+. Every JVM running this code needs:

```
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
```

Without them, the first `UnsafeBuffer` construction throws `IllegalAccessError` at class-init time.

### OS & Hardware Tuning

1. **CPU pinning:** run the engine thread on an isolated core (`taskset -c 2`, or
   `pthread_setaffinity_np`).
2. **Kernel isolation:** `isolcpus=2,3` keeps the scheduler off the busy-spin thread.
3. **Disable C-states:** `processor.max_cstate=0 intel_idle.max_cstate=0` locks clock frequency.
4. **Huge pages — required.** ~1.0 GB of randomly accessed arrays is ~250k pages at 4 KB, and the TLB
   thrash shows up directly in p99. Configure huge pages for the heap and place Aeron term buffers on
   `hugetlbfs`.
5. **Shared memory:** mount `/dev/shm` as `tmpfs` for the Aeron ring buffers, bypassing disk I/O.
6. **Term buffer sizing:** size the book event stream's term buffers so a consumer must be down for
   seconds — not milliseconds — before backpressure reaches the engine.

---

### Operator Tools

The `tools` module ships a single `most` binary. Every subcommand begins by listening for a directory
broadcast, because the routing table is the only thing mapping a symbol to the shard and gateway that
serve it — which makes the tools the first real consumer of discovery, and the reason the published
route had to change from cluster ingress to gateway endpoints.

| Command | Purpose |
| --- | --- |
| `most securities` | The tradable universe: symbol, ISIN, shard, currency; `--verbose` adds geometry and endpoints |
| `most send` | Submit an order, routed to the gateway owning the symbol; follows execution reports |
| `most cancel` | Cancel a resting order |
| `most book` | Rebuild and print books from the L2 depth feed |
| `most load` | Drive a shard at a fixed rate and measure round-trip latency and throughput |

Two things the tools do differently from the engine, deliberately:

* **The book inspector uses a `TreeMap`, not a flat ladder.** It prints a handful of levels a few
  times a second, so sorted iteration and readable code are worth more than allocation-free access —
  and unlike the engine it does not know a security's geometry until discovery tells it.
* **Arguments are validated before the network is touched.** A malformed price must report a
  malformed price, not whatever the transport happens to fail with first. Tick alignment is checked
  after the directory arrives, since only it carries the tick; phase, collars and capacity stay in
  the engine, because duplicating them in a client would mean two places to get them wrong.

The inspector tracks sequences with `FeedSequenceTracker` and prints a staleness warning on a gap:
under `MaxMulticastFlowControl` a slow subscriber takes an unrecoverable gap by design, so an
operator reading depth off the screen has to be told when it can no longer be trusted.

#### Operator commands from the control plane

`most define` / `most session` / `most purge` are not the only senders any more: the control plane
sends the same three commands, through the same gateway client channel, using the same encoders —
`OperatorCommands` in `reference`, shared by both so a wire message has one implementation.

Two properties of these commands shape everything built on them.

**They are not acknowledged.** The engine applies or rejects an operator command without replying,
and a rejected `SecurityDefinition` increments `rejectedDefinitions` and nothing else. So a sender
can honestly report only that bytes reached the gateway. The control plane closes as much of the
loop as the feed allows: it watches L3 and treats `SessionChanged` as confirmation of a phase
transition, which covers session and reopen. Nothing confirms a definition, and its responses say so
rather than implying success. What can be checked *before* sending is checked instead — a static
band that falls outside the ladder is refused locally, because the engine would refuse it in silence.

**A halt is visible only on L3.** `VolatilityHalted` is forwarded verbatim to L3 and nothing is
derived from it onto L1 or L2, so a depth subscriber cannot tell a halt from a scheduled close.
Anything that needs to *know* a security broke — and recovery cannot begin otherwise — must subscribe
to the book event stream or L3. That is why the control plane consumes L3 rather than L1.

#### Session scheduling

The trading calendar lives in the control plane, not the engine. `onTimerEvent` remains unused and
`Cluster.scheduleTimer` is still uncalled — deliberately. The `SessionTransition` the scheduler
emits is sequenced through the log like any other command, so every node applies it at the same log
position and no determinism is lost by scheduling outside; what is gained is that weekends,
holidays and daylight saving stay out of a state machine where a bug kills every node at once.

Each tick is a **reconciliation** rather than a trigger: it compares the phase the calendar wants
against the phase L3 reports and sends the difference, which makes it idempotent and self-healing
after an outage with no missed-timer state anywhere. Two consequences are worth stating, because
both are easy to get wrong:

* **The difference between two phases is a path.** The uncross runs only on
  `OPEN_AUCTION → CONTINUOUS`, so catching up to `CONTINUOUS` from `CLOSED` walks the intermediate
  phases. Sending `CONTINUOUS` directly is accepted and silently skips the auction, leaving a
  crossed resting book crossed.
* **A halted security is never reconciled back open.** §4.6 makes recovery operator-driven, and an
  automatic reconciliation would additionally reopen it *without* an auction, since the shard is
  already past `OPEN_AUCTION` as far as the scheduler is concerned.

The engine emits `SessionChanged` only on a transition, so a cold cluster's phase is unknown to any
observer until something moves it. The scheduler waits rather than assuming `CLOSED` — a
control-plane restart mid-session looks identical from the feed, and assuming there would shut a
live market. Sending any session command once establishes the baseline.

#### `most load` — the measurement harness

`most load` is the only tool with a hot path. It generates orders into parallel primitive arrays
before the run starts, then sends them with `tryClaim`, encoding straight into the log buffer, so
nothing allocates once the clock starts and the generator is not what is being measured. Prices are
drawn as *tick indices* over a configured band and materialised as `floor + tick x index`, which
makes on-tick alignment true by construction; a seeded `xorshift64*` makes a run reproducible.

Three properties make its numbers trustworthy:

* **The schedule is absolute.** Order *i* is due at `start + i x delay`, never `sleep(delay)` after
  the last send. A relative delay drifts, and catching up after a stall by sending flat out turns a
  paced run into a burst — which is the opposite of the sustained rate being measured.
* **Response time is reported alongside service time.** Service time is `report - actual send`;
  response time is `report - scheduled send`, and includes the time an order waited because the
  sender itself had fallen behind. Reporting only service time is coordinated omission: at the rate
  the stack cannot sustain, the two diverge by orders of magnitude and only the second is what a
  client would experience. Sender lateness is recorded separately, so "the generator was the
  bottleneck" is visible rather than hidden inside the latency figures.
* **A trade is attributed to the aggressor by ordering, not by a threshold.** Egress is one ordered
  stream from one deterministic engine, so every report an aggressing order generates arrives before
  any report for a later order. A `TRADE` arriving while its own order is still the most recently
  acknowledged one is therefore an immediate fill; a later one is the resting side of somebody
  else's aggression, whose latency would measure how long it sat on the book.

What it measures is the **whole client round trip** — gateway, cluster ingress, Raft append and
archive write, engine, egress, gateway, client publication. On a single-node local cluster the
archive's disk write dominates, so the figure is an end-to-end capacity number and not the engine's
internal budget from §2. Splitting the stages needs timestamps in the gateway and the engine.

Two failure modes turn a benchmark into a measurement of something else, so both are called out in
the summary rather than left to be inferred: a price band too wide to cross fills the book until
every order is a `BOOK_CAPACITY` reject, and a band outside the static collar is rejected at
acceptance. Reject reasons are printed with counts whenever any are non-zero.

---

### End-to-End Test

`e2e/run-e2e.sh` runs every process — cluster host, engine, gateway, market data, discovery — and
drives a real trade through the CLI: list the universe, define a security, open the session, rest an
order, cross it, watch the depth, cancel the remainder. Single node and IPC rather than multicast,
because it proves the components talk to each other, not that the network is configured.

It found three defects that unit tests could not:

* **The engine overwrote Aeron's cluster session header.** `ClientSession.tryClaim` reserves
  `SESSION_HEADER_LENGTH` bytes ahead of the payload; encoding at `claim.offset()` clobbered it and
  every execution report was rejected by the egress adapter as carrying the wrong schema. The fake
  session in the unit tests did not model the header, so the tests validated the wrong layout — it
  now reserves the same space.
* **The gateway reported a cancelled order as fully filled.** `cumQty` was derived as
  `origQty - leavesQty`, but a terminal report carries `leavesQty = 0` whether the order filled or
  was cancelled. It is now accumulated from each fill's `lastQty`, which is what `CumQty` means. The
  unit test that should have caught this asserted the buggy value while its name described the
  correct one.
* **Nothing enforced the ladder-range invariant.** §3.2 requires the ladder to be wider than the
  static collar band; a configuration that broke it had orders inside the band rejected by the
  `PRICE_OUT_OF_LADDER` backstop instead. `SecurityDefinition` now rejects a collar whose band falls
  outside the ladder.

## 8. Open Items

* ~~**No market data snapshot for late joiners.**~~ **Done.** The Market Data process publishes a
  periodic per-security image on its own stream, and `DepthFeedAssembler` splices it onto the
  incremental feed (§5, "The L2 recovery feed"). The e2e test no longer starts its inspector before
  the depth exists; it starts it afterwards, which is the case a real consumer is always in.
  What is *not* covered: a request-response recovery channel for a subscriber that cannot wait a
  cycle, and any snapshot of L1 — top of book is derivable from the L2 image, but a consumer that
  takes only L1 still has nothing to join to.
* **Halt-recovery authorisation.** The *procedure* is now executable —
  `POST /api/shards/{id}/reopen` in the control plane re-seeds the definition and walks
  `PRE_OPEN → OPEN_AUCTION → CONTINUOUS` in that order (`docs/ControlPlane.md`). Who is authorised to
  issue it is still undefined: the control plane has no authentication.
* **Auction SMP pass limit.** §4.5 specifies a maximum pass count as a safety valve; the value needs
  to come from measurement against realistic auction books.
* **Reference price seeding for a security with no trades.** The `SecurityDefinition` value stands
  until the first executing uncross or trade; confirm whether a day with no trades should carry both
  anchors forward or be re-seeded operationally.
* **Closing auction**, if required, and whether it reuses the opening uncross algorithm — including
  whether a closing uncross should also reset `staticReference`.
* **Net resting depth** measured against production flow, to confirm the 1M order pool and set the
  capacity high-water mark.
* **Order modify/replace:** currently unsupported (cancel/new only) — confirm this is intentional.
* **`SecurityDefinition` geometry.** The message carries `priceFloor`, `tickSize` and `levelCount`,
  but the ladders are pre-allocated, so geometry is fixed when a book is constructed. The engine
  accepts references and collars and **rejects the whole definition** if the geometry disagrees, on
  the grounds that half-applying a definition is worse than rejecting it. Splitting this into a
  boot-time `SecurityDefinition` and a runtime `SecurityReconfigure` would make the distinction
  explicit.
* **Participant-to-session binding.** Learned from inbound traffic today (§1). Production should bind
  from the authenticated session principal at session open, so a maker who has not sent a message
  since failover is still reachable.
* **`SecurityDefinition` distribution:** the Market Data Process currently learns the reference
  prices only implicitly, from trades. If downstream needs the collars or tick size, a corresponding
  book event is required.
