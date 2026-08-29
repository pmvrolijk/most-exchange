# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository status

`docs/Design.md` is the authoritative specification — read it before implementing anything, and
update it alongside code when the design changes. §8 tracks the open questions.

The Gradle skeleton is in place and green: seven modules (`sbe`, `reference`, `discovery`, `engine`, `market-data`, `gateway`, `tools`),
SBE codegen wired, 184 tests passing. **Implemented so far:** `Domain.kt` (packed layout, bit-packing
helpers, reusable outcome scratch), `PriceLadder`, and `OrderBook` — booking, cancel validation,
continuous matching with both gates, the auction (price selection, SMP fixed point, allocation), and
the expiry purge; and `MatchingEngineService` — the full `ClusteredService`, message dispatch,
execution-report egress, book-event publication, and snapshot/restore; and `EngineConfig` /
`EngineMain` — the `ClusteredServiceContainer` wiring; `MarketDataService` / `DepthBook` — L1/L2/L3
derivation; `GatewayService` / `OrderStateStore` — validation and `cumQty` reconstruction; and
`reference` / `discovery` — the shared shard security list and the tradable-universe directory; and
`tools` — the `most` operator CLI. All seven modules are implemented.

## Commands

A node is **two processes**: `io.aeron.cluster.ClusteredMediaDriver` (driver + archive + consensus
module) and this binary (service container only). The driver allocates, so it stays out of the engine
process. Boot config carries only geometry and capacity — reference prices and collars arrive as
`SecurityDefinition` commands through the log. **Every node must boot with identical geometry**;
`EngineConfig.fingerprint()` exists so that is checkable, since it is the one misconfiguration
consensus cannot catch.

```sh
./gradlew build                                    # compile, generate codecs, test
./gradlew :engine:test --tests '*PriceLadderTest*' # a single test class
./gradlew :sbe:generateSbeCodecs                   # regenerate codecs only
./gradlew :engine:nativeCompile                    # native binary (needs a GraalVM toolchain)
```

- **Never hand-write SBE byte offsets.** Edit `sbe/src/main/resources/message-schema.xml`; codecs
  regenerate into `sbe/build/generated/sbe/`. Keep the schema and Design.md §5 in step — the schema
  file was extracted from the doc and the two are meant to stay identical.
- **Warnings are errors** in every module. This is deliberate: a silent "inline function cannot be
  inlined" would break the zero-allocation profile. Do not disable it to get a build through — fix
  the warning. Reserve `inline` for functions taking callbacks; on plain helpers Kotlin correctly
  warns it buys nothing.
- Native build knobs live in `gradle.properties`: `engine.march` (CI/prod must set it) and
  `engine.useEpsilonGc` (off until zero-allocation is proven). Both are explained in README.md.

## Architecture (from docs/Design.md)

A low-latency deterministic matching engine. The invariants below drive nearly every implementation
decision, so violating one is usually a bug even when the code compiles and passes tests.

**Deterministic single-threaded state machine.** Each shard is one thread consuming Aeron Cluster's
Raft-replicated log. Every node runs the identical service over the identical log and must produce
byte-identical state.

- **No wall-clock calls.** `System.currentTimeMillis()`/`nanoTime()` are banned. Time comes only from
  `Cluster.time()`, the sequenced consensus timestamp, or from timestamps on sequenced commands.
  Cluster timers (`scheduleTimer`) are delivered through the log and are therefore deterministic.
- **Determinism cuts both ways.** A crash, an unvalidated array index, or a pool exhaustion throw
  kills *every* cluster node at the same log position simultaneously. Validate defensively and prefer
  rejecting an order over throwing.
- **No allocation on the hot path.** Native image will eventually run `--gc=epsilon`, where any
  steady-state allocation is fatal. Watch for: boxing through generic views (`Map.Entry`,
  `Array<Price>`), and `@JvmInline value class` parameters on any callback that isn't an `inline fun`
  — converting a match callback to a functional interface or object boxes on every fill.
- **Zero-copy publishing.** Use `Publication.tryClaim` and encode directly into the log buffer; do not
  encode into a scratch buffer and `offer` it.

**One security list per shard.** `reference`'s `ShardSpec` is read by the engine, gateway,
market-data and discovery alike — do not reintroduce per-process security lists. It carries identity
(`symbol`, `isin`, `name`, `currency`) and geometry; ISINs are check-digit validated at boot. Every
process prints `ShardSpec.fingerprint()` at startup, which is how a geometry mismatch is caught
before it silently diverges the books.

**The directory publishes the GATEWAY's client endpoints**, not the cluster ingress/egress — an
adapter connecting to the cluster directly would bypass the gateway's validation and `cumQty`
reconstruction.

**A gateway serves exactly one shard** — one cluster connection, and it rejects anything outside its
list. `discovery` publishes the universe (security → shard → ingress channel) on a repeating
multicast cycle so upstream adapters can route; `DirectoryClient` is what they embed. Discovery
enforces one shard per security, and stages each broadcast so a truncated cycle never replaces a good
routing table.

**Sharding.** Max **10 securities per shard**, max **1M orders per book**. `securityId` is validated
at the gateway against the shard map (and range-checked defensively in the engine). Books are fully
independent — no cross-instrument matching — so scale by adding shards, not securities per shard.

**Data flow.** Gateways → Aeron Cluster ingress → `MatchingEngineService` → execution reports via
cluster egress (leader only, automatic) **and** a raw *book event stream* on IPC 12 (leader only,
muted explicitly via `onRoleChange` since it is a plain publication, not egress). A separate **Market
Data Process** consumes stream 12 and derives L1/L2/L3, published as **SBE over UDP multicast**
(unicast as a bounded fallback) — the engine never formats market data.

**Do not change Aeron's multicast flow control.** It defaults to `MaxMulticastFlowControl` (fastest
receiver governs), which is correct here; `MinMulticastFlowControl` would let the slowest subscriber
throttle the publisher and reintroduce exactly the coupling that moving market data out of the engine
was meant to remove. The trade-off is that slow subscribers take unrecoverable gaps, so gap detection
and snapshot re-synchronisation are subscriber responsibilities.

**Both boundaries are binary SBE, not FIX.** The `gateway` module is protocol-agnostic; FIX and
proprietary session protocols are handled by separate gateways upstream of it (and downstream of
`market-data`) that are outside this project. The gateway is nonetheless **stateful**: it holds the
`origQty` it forwarded so it can restore `cumQty = origQty - leavesQty` on the outbound leg, which is
precisely what lets the engine omit `origQty` and keep an order at one cache line.

**Two data structures carry the performance.**
- *Packed order pool*: a single `LongArray` with a stride of 8 longs, so each order is exactly one
  64-byte cache line and costs one cache miss. Field offsets are in `OrderField`; `next`/`prev` and
  `expireDate`/`side` are bit-packed into words 6 and 7.
- *Price ladder*: per side, a flat array of levels indexed by ticks from a floor, each holding a FIFO
  head/tail plus aggregate qty, with a `LongArray` occupancy bitset so next-best-price is a
  `countLeadingZeroBits`/`countTrailingZeroBits` word scan. The ladder range is configured strictly
  wider than the static circuit breaker band, so band rejection always fires before ladder overflow.

`leavesQty` is a **stored field on the order**, not derived at publish time — partially filled
resting orders match on their true remainder, and fill reports must track a running remainder rather
than `origQty - thisFill`. `origQty` is deliberately *not* stored (the eight words are full); the
gateway derives `cumQty` from the `origQty` it sent and the `leavesQty` the engine reports.

The inline matching and purge functions read the pool and ladders directly, so those members are
`@PublishedApi internal`, not `private` — a public `inline fun` cannot touch private members.

**Two reference prices, not one.** `staticReference` is seeded by `SecurityDefinition` and reset by
every *executing* uncross (including the reopening auction after a halt — otherwise the security
could not reopen, since the collar would reject orders at the new price level). `dynamicReference` is
seeded the same way but tracks **every** trade. The static collar anchors on the former and gates
order *acceptance* (`PRICE_OUT_OF_BOUNDS`); the dynamic collar anchors on the latter and gates
*execution* per price level. An uncross producing no trade leaves both untouched.

The dynamic collar is measured against a **snapshot** of `dynamicReference` taken when the aggressing
order arrived — never the live value, which that order's own fills are advancing; re-reading it would
let one order ratchet arbitrarily far. On breach: the breaching fill is suppressed, prior fills stand,
the aggressor's remainder is canceled (`VOLATILITY_HALT`) and not booked, the phase goes to `CLOSED`
with the resting book intact, and a `VolatilityHalted` event is emitted. No automatic recovery.

**Self-match prevention.** The effective SMP id is `smpId != 0 ? smpId : participantId`, resolved
once at order entry and stored (word 3). The **aggressor's** `smpStrategy` governs, defaulting to
`CANCEL_AGGRESSOR` (stop matching, cancel the aggressor's remainder, leave the resting order);
`CANCEL_RESTING` cancels the resting order and continues. Only these two strategies exist. Within
matching, the dynamic collar is a *level* gate checked first; SMP is an *order* gate inside the
level. A self-match never prints a trade, so it never moves either reference.

**The auction is uncollared** — neither collar constrains the uncross price. That is what lets a
halted security reprice and reopen. It is not unbounded in practice, since every participating order
passed the static collar at acceptance. **Recovery caveat:** `staticReference` is not reset until the
reopening uncross *executes*, but orders are accepted from `PRE_OPEN` onward, so a halt that moved
price outside the static band deadlocks — the orders needed to reopen get rejected before the auction
that would reset the anchor can run. `SecurityDefinition` may be re-issued at any time to re-seed the
references; doing so before `PRE_OPEN` is a required halt-recovery step, not an exception.

**SMP applies in the opening auction too, which makes the uncross a fixed-point loop** rather than a
single computation: cancelling removes volume → the uncross price moves → a different set of orders
crosses. An auction has no aggressor, so the **later-arriving** order of a self-matching pair (higher
`exchangeOrderId`) plays that role and its strategy governs. One two-cursor allocation walk resolves
every collision at a given price — cancelling just advances that side's cursor, which is exactly a
recompute at the same price — so a walk is only re-run when the *price* moves. Terminates because
each iteration cancels at least one order from a finite book; a configured pass limit is a safety
valve, not part of the algorithm.

**Session lifecycle.** `CLOSED → PRE_OPEN → OPEN_AUCTION → CONTINUOUS`. There is no
`VOLATILITY_HALT` phase — a halt transitions the security to `CLOSED` and recovery is
operator-driven. Orders are **accepted and booked in every phase except `CLOSED`**; only
*matching* is gated on `CONTINUOUS`. A crossed book outside continuous trading is normal and is
resolved by the uncrossing algorithm (§4.2: max executable volume → min imbalance → surplus side →
nearest reference price), which runs on the `OPEN_AUCTION → CONTINUOUS` transition.

**Expiry is date-based only; intraday expiry is not supported.** Orders carry `expireDate`
(YYYYMMDD int32; `0` = GTC) and are expired when `0 < expireDate < currentTradingDate`. The purge is
its own sequenced command run well before `PRE_OPEN`, and it walks the **price ladders**, not the
`Long2IntHashMap` — that map compacts its probe chain on removal, so iterator-based removal can
silently skip entries.

**Capacity policy.** Exceeding the pool high-water mark rejects with `BOOK_CAPACITY`. Never let the
pool drain to a throw.

**Any JVM running this code needs `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` and
`--add-opens java.base/sun.nio.ch=ALL-UNNAMED`** (Agrona 2.x buffer intrinsics, Aeron driver). Already
set on the test and run tasks; without them the first `UnsafeBuffer` throws `IllegalAccessError`.

**Every book event carries a `seqNum` and a `shardId`.** The sequence is stamped by the engine where
the event is generated, so it is deterministic across nodes and survives failover; it is snapshotted
with `nextExchangeOrderId`. This makes L3 a verbatim byte-forward rather than a re-encode.
`OrderRemoved` carries `leavesQty` so the whole L2 aggregate is derivable from the event stream
without shadowing per-order state.

**Feed sequences are namespaced by shard.** Each shard numbers from 1 independently, so several
shards can share one multicast group and subscribers track a sequence per shard —
`FeedSequenceTracker` in `reference` does this, distinguishing a gap from a replay and baselining on
the first message seen. `shardId` must stay on the *book event* (not only the derived feeds) because
L3 is forwarded verbatim. The market-data process also uses it to reject foreign-shard events, which
catches being pointed at the wrong engine.

**Execution reports route by `participantId → clusterSessionId`**, learned from inbound traffic — a
session belongs to a gateway, not a participant, and the maker side of a fill needs a route back.
Undeliverable reports are counted and dropped, never blocked on.

**Agrona has no `Long2IntHashMap`** despite what earlier drafts assumed — the id map is a
`Long2LongHashMap` with the `int` node index widened to a `long`. Check a primitive collection
actually exists before designing around it.

**Prices and quantities** are fixed-point `int64` with 8 implied decimals. Never introduce floating
point into pricing or matching arithmetic.

## Performance budget

100k orders/sec/security × 10 securities = a **1 µs aggregate budget per order**. The design
estimates ~0.5 µs, so roughly 2x headroom at full fan-out. Cache misses dominate — that is why the
order pool is cache-line packed. See §2 for the stage-by-stage breakdown and the ~0.9 GB/shard memory
footprint (which makes huge pages mandatory, not optional).

## Build and deployment notes

- Pin `-march=x86-64-v3`, never `-march=native` — a mismatched build host SIGILLs in production.
- Ship with Serial GC first, prove zero steady-state allocation under load, add an allocation
  assertion to CI, *then* switch to `--gc=epsilon`.
- Run the Aeron `MediaDriver` as a separate process; it allocates.
- Snapshots must serialize only *occupied* orders by walking the ladders, never the whole 1M-slot pool.
