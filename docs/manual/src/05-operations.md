# 5. Daily operations, order flow and monitoring

## 5.1 The trading day

| Time | Action | How |
| --- | --- | --- |
| Before the open | **Purge expired orders** | `PurgeExpiredOrders`, its own sequenced command |
| Before the open | **Seed or re-seed reference prices and collars** if they have moved | `SecurityDefinition` per security |
| Open − 1 hour | `PRE_OPEN` — orders accepted and booked, nothing matches | `SessionTransition` |
| Open − 5 minutes | `OPEN_AUCTION` — still accepting, still not matching | `SessionTransition` |
| Open | `CONTINUOUS` — **the uncross runs on this transition** | `SessionTransition` |
| Close | `CLOSED` — orders rejected, the resting book stays | `SessionTransition` |
| After the close | **Take a snapshot** | Cluster admin request |

All five commands can be issued from the CLI, from the REST API, from the admin console, or by the
control plane's scheduler. In production the scheduler does all of it and an operator intervenes.

::: warning Expiry is date-based only
Orders carry `expireDate` as a `YYYYMMDD` integer, where `0` means good-till-cancelled, and are
expired when `0 < expireDate < currentTradingDate`. There is no intraday expiry. The purge is its own
sequenced command and must run **well before** `PRE_OPEN`: after `PRE_OPEN` the book is accepting
orders and a late sweep would expire orders someone had just placed. The scheduler skips a late purge
rather than running it.
:::

## 5.2 The trading calendar

A **schedule** is a named trading day — local times mapped to phases, plus a purge time — that a
shard follows. It lives in the control plane and fires there, not in the engine.

That costs no determinism: the `SessionTransition` it emits is sequenced through the replicated log
like any other command, so every node applies it at the same log position. What it buys is that
holidays and daylight saving stay outside the deterministic state machine, where a bug kills every
node simultaneously.

```sh
curl -X PUT localhost:8080/api/schedules/equities -u admin:... \
  -H 'Content-Type: application/json' -d '{
  "name": "equities", "zone": "Europe/Amsterdam",
  "weekdays": ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],
  "purgeTime": "07:00",
  "entries": [
    { "at": "08:00", "phase": "pre-open" },
    { "at": "08:55", "phase": "open-auction" },
    { "at": "09:00", "phase": "continuous" },
    { "at": "17:30", "phase": "closed" } ] }'

curl -X PUT localhost:8080/api/shards/0/schedule -u admin:... \
  -H 'Content-Type: application/json' -d '{"scheduleName":"equities"}'

curl -X POST localhost:8080/api/schedules/equities/holidays -u admin:... \
  -H 'Content-Type: application/json' -d '{"date":"2026-12-25","description":"Christmas"}'
```

![Schedules. The calendar, its holidays, which shard follows which schedule, and the reconciler's own log of what it did and what it refused to do.](assets/ui-schedules.png)

Times are **local**, and the zone is stored with them: a market opens at 09:00 local whatever the
offset is that week. Storing UTC instants would move the open by an hour twice a year.

### Every tick is a reconciliation

The scheduler does not fire timers. On each tick it compares the phase the calendar says a shard
should be in against the phase **the L3 feed says it is in**, and sends the difference. One choice,
three consequences: it is idempotent, it recovers by itself after an outage, and there is no
missed-timer state to keep anywhere.

::: warning The difference between two phases is a path, not a destination
The uncross runs only on `OPEN_AUCTION → CONTINUOUS`. A control plane that was down through the open
and catches up at 09:15 walks `PRE_OPEN → OPEN_AUCTION → CONTINUOUS`. Sending `CONTINUOUS` straight
from `CLOSED` would be accepted by the engine and would **silently skip the auction**, leaving a
crossed resting book crossed until an aggressor happened to arrive. Closing is a single step —
nothing is computed on the way down.
:::

What the scheduler refuses to do, and says so rather than doing quietly:

- **It never reopens a halted security.** Recovery is operator-driven by design, and reconciling back
  to `CONTINUOUS` would do it *without an auction*, since from the scheduler's point of view the
  shard is already past `OPEN_AUCTION`.
- **It waits rather than guessing an unknown phase.** See 3.9.
- **It skips a late purge** rather than running it.
- **It stands down** when a shard's books disagree on their phase, and when another control-plane
  instance holds its Postgres advisory lock. A duplicated transition is survivable; a duplicated
  purge is not something to leave to luck.

```
GET  /api/scheduler/runs                          what it did, and what it deliberately did not
POST /api/scheduler/tick                          reconcile now instead of waiting
GET  /api/schedules/{name}/preview?date=2026-12-25
```

A skip is recorded when its **reason changes**, not on every tick, so a halted security appears once
rather than every five seconds all weekend.

## 5.3 Operator commands

Five commands reach a running exchange. `SecurityDefinition`, `SessionTransition`,
`PurgeExpiredOrders` and `RequestBookImage` go to the **gateway's client channel** like any other
message and are forwarded into the log untouched. A snapshot request is different: it is a cluster
admin request and goes to cluster ingress.

::: warning Operator commands are not acknowledged
The engine applies or rejects them without replying. A rejected `SecurityDefinition` increments a
counter and says nothing else. A sender may therefore claim only that **bytes were sent** — never
that a command was applied. The control plane separates `sent` from `confirmed`, and confirms a
phase only by afterwards seeing `SessionChanged` on the L3 feed.
:::

### Seeding a definition

```
$ most define --symbol AAPL --reference 101.30 --static-collar 5000 --dynamic-collar 100
defined AAPL on shard 0: reference 101.30, static 5000bps, dynamic 100bps
```

::: term Static and dynamic reference
`staticReference` is seeded here and is reset only by an **executing** uncross. Its collar gates
order *acceptance*: an order outside the band is rejected with `PRICE_OUT_OF_BOUNDS`.
`dynamicReference` is seeded the same way but tracks **every** trade, and its collar gates
*execution* per price level. An uncross that produces no trade leaves both untouched.
:::

Everything checkable is checked **before** the bytes go out, because nothing will report a rejection.
The control plane refuses a definition whose static band falls outside the pre-allocated ladder:

```
a 5000bp static band around 300.00 spans 150.00..450.00, outside AAPL's ladder
0.00..327.67; the engine would reject it without replying
```

Geometry in a definition is never taken from the caller — `priceFloor`, `tickSize` and `levelCount`
come from the same rows the shard booted from, because a definition whose geometry disagrees with the
allocated book is rejected in silence.

### Moving the session

```
$ most session --phase continuous --shard 0
shard 0 -> continuous (trading date 20260906)
```

```json
{ "command": "session CONTINUOUS shard 0", "sent": true, "confirmed": true,
  "detail": "every book on shard 0 reported CONTINUOUS" }
```

::: warning A session transition is shard-wide
The engine applies the phase to every book it hosts. There is no per-security session command, so
moving one security moves every other book on that shard. The REST response says so every time, not
only when it bites.
:::

### Purging expired orders

```
$ most purge --shard 0
shard 0 purged for trading date 20260906
```

The purge walks the **price ladders**, not the order id map — that map compacts its probe chain on
removal, so iterator-based removal can silently skip entries.

### Republishing books as an image

For a market-data process that restarted while the engine kept running, and therefore has no book and
no way to learn one from a live feed:

```
$ most image --shard 0
shard 0 asked to republish its books
```

::: term Book image
One message per **occupied ladder level** — bounded by `levelCount` rather than by a resting depth
nobody has measured — bracketed by begin and end messages. It carries the current sequence number as
a **baseline and consumes none**, so a subscriber must not count an image's `seqNum` as a gap.
Installing an image publishes increments as well as a snapshot, because a subscriber that is already
synchronised ignores snapshots.
:::

A snapshot restore also sets this pending, so both triggers use one path: publication happens from
background work once the book-event publication is *connected*, because both ask at the moment a
subscriber is least likely to be listening.

## 5.4 Order flow

### The order entry message

| Field | Notes |
| --- | --- |
| `participantId` | The trading firm |
| `clOrdId` | Client order id, echoed on every report for this order |
| `price` | Fixed point, 8 implied decimals |
| `qty` | Fixed point, 8 implied decimals |
| `smpId` | Self-match prevention id; `0` means use `participantId` |
| `securityId` | Validated by the gateway against its shard's list |
| `expireDate` | `YYYYMMDD`, or `0` for good-till-cancelled |
| `side` | `BUY` or `SELL` |
| `smpStrategy` | `CANCEL_AGGRESSOR` (default) or `CANCEL_RESTING` |

A cancel carries `participantId`, `origClOrdId`, a new `clOrdId`, the `exchangeOrderId` to cancel,
`securityId` and `side`.

![One client round trip through the stages that own it. The gateway and the engine are the two smallest.](assets/fig-roundtrip.svg)

### What happens to an order

1. **Gateway.** `securityId` is validated against the shard's list. Anything else is rejected
   locally with `UNKNOWN_SECURITY` and never reaches the cluster. The message is then forwarded into
   cluster ingress **unchanged**.
2. **Consensus.** The message is replicated and sequenced. Every node will apply it at the same log
   position.
3. **Engine — admit.** Phase gate (`MARKET_CLOSED` outside the four phases below `CLOSED`), static
   collar (`PRICE_OUT_OF_BOUNDS`), ladder range (`PRICE_OUT_OF_LADDER`), capacity
   (`BOOK_CAPACITY`), expiry (`ORDER_EXPIRED`).
4. **Engine — match**, only in `CONTINUOUS`. Per price level the dynamic collar is checked first, as
   a level gate; self-match prevention is checked inside the level, as an order gate.
5. **Engine — settle.** The remainder is booked, execution reports are generated, and book events are
   published on the leader's IPC stream.

::: warning An order the gateway cannot forward is not consumed
A cluster offer returning back-pressure is **transient**, and treating it as a drop would lose an
order the client believes it placed, with no acknowledgement and no rejection. The gateway leaves the
fragment unconsumed and offers the same bytes on the next poll, so congestion propagates back to the
client's publication. A dead cluster session is not retryable and rejects with
`GATEWAY_UNAVAILABLE`.

The **outbound** leg cannot do this — egress must keep being drained or the session dies — so it
drops and counts. A rising `droppedToClient` means a subscriber is gone or too slow; the fix is a
larger term buffer or a faster subscriber, not a busier gateway.
:::

### Rejection reasons

| Reason | Raised by | Meaning |
| --- | --- | --- |
| `UNKNOWN_SECURITY` | gateway, engine | `securityId` is not on this shard |
| `MARKET_CLOSED` | engine | The security is `CLOSED`. A volatility halt looks like this |
| `PRICE_OUT_OF_BOUNDS` | engine | Outside the static collar around `staticReference` |
| `PRICE_OUT_OF_LADDER` | engine | Outside the allocated ladder. Should be unreachable: the ladder is configured strictly wider than the band |
| `BOOK_CAPACITY` | engine | The order pool's high-water mark for this book |
| `ORDER_EXPIRED` | engine | `expireDate` is before the current trading date |
| `UNKNOWN_ORDER` | engine | A cancel for an order that is not resting |
| `SELF_MATCH_PREVENTED` | engine | The aggressor's remainder was cancelled by SMP |
| `VOLATILITY_HALT` | engine | The aggressor's remainder was cancelled by a dynamic collar breach |
| `GATEWAY_UNAVAILABLE` | gateway | The cluster session is closed |
| `UNAUTHORIZED_PARTICIPANT` | — | Defined on the wire and raised by nothing (1.7) |

### Self-match prevention

The effective SMP id is `smpId` if non-zero, otherwise `participantId`; it is resolved once at order
entry and stored on the order. The **aggressor's** strategy governs.

| Strategy | Behaviour |
| --- | --- |
| `CANCEL_AGGRESSOR` (default) | Stop matching, cancel the aggressor's remainder, leave the resting order |
| `CANCEL_RESTING` | Cancel the resting order and continue matching |

A self-match never prints a trade, so it never moves either reference price.

SMP applies in the opening auction too, which makes the uncross a **fixed-point loop**: cancelling
removes volume, which moves the uncross price, which crosses a different set of orders. An auction
has no aggressor, so the later-arriving order of a self-matching pair plays that role and its
strategy governs. The loop terminates because each iteration cancels at least one order from a finite
book; `engine.auction.maxPasses` is a safety valve, not part of the algorithm.

### The auction

The uncross runs on `OPEN_AUCTION → CONTINUOUS` and selects a price by, in order: maximum executable
volume, then minimum imbalance, then the surplus side, then the price nearest the reference.

::: note The auction is uncollared
Neither collar constrains the uncross price. That is what lets a halted security reprice and reopen.
It is not unbounded in practice, since every participating order passed the static collar when it was
accepted.
:::

### A volatility halt

The dynamic collar is measured against a **snapshot** of `dynamicReference` taken when the aggressing
order arrived — never the live value, which that order's own fills are advancing. On breach:

- the breaching fill is suppressed, and prior fills stand;
- the aggressor's remainder is cancelled with `VOLATILITY_HALT` and is not booked;
- the security moves to `CLOSED` with its resting book intact;
- a `VolatilityHalted` event is emitted on L3, and nowhere else.

There is no automatic recovery and no `VOLATILITY_HALT` phase. Recovery is 6.4.

## 5.5 Market data operations

Four feeds, on four channels, plus the recovery feed. A subscriber's responsibilities are fixed by
the flow-control choice (3.7): detect gaps by sequence number, and re-synchronise from the snapshot
feed.

::: term Feed sequence
Every book event carries a `seqNum` and a `shardId`. The sequence is stamped by the engine where the
event is generated, so it is deterministic across nodes and survives failover, and it is snapshotted
alongside the next order id. **Sequences are namespaced by shard** — each shard numbers from 1
independently — so several shards can share one multicast group and a subscriber tracks a sequence
per shard.
:::

Consumers should use `DepthFeedAssembler` and `FeedSequenceTracker` from the `reference` module. The
operator CLI, the control plane's console and any FIX market data adapter all use the same two, so a
book on one screen cannot disagree with a book on another.

::: warning A synchronised subscriber ignores snapshots, with one exception
Installing an image over an already-synchronised book would **rewind** it, because increments past
that sequence were applied directly and never buffered. The exception is an image whose sequence is
at or ahead of everything applied, which already contains all of it — without that exception, a
subscriber handed a stale image on join discards every later one and can only be rescued by an
increment happening to arrive. On a quiet book, none does.
:::

An empty book still sends a bracketed zero-level cycle, because "no liquidity" and "I cannot yet
know" are different answers.

## 5.6 Snapshots and restarts

::: warning Nothing takes a snapshot unless something asks
Without one, a restart replays the log from genesis — which is precisely what the snapshot exists to
avoid. Four things ask:

| | |
| --- | --- |
| `most cluster snapshot --ingress 0=HOST:PORT` | Through consensus, so **every member** snapshots at the same log position and the request is answered. This is the one to use in production. |
| `most cluster snapshot --dir DIR` | The local control toggle, one member only |
| `most cluster shutdown` | Snapshot, then stop — which plain SIGTERM does **not** do |
| The control plane's scheduler | At each session close |
:::

Leave the scheduler enabled in production: a daily snapshot at the close is what bounds restart time.

### Restarting a node

```sh
most cluster shutdown --ingress 0=shard0-a:20110    # or rely on the scheduler's close snapshot
systemctl stop most-engine most-cluster-host
sleep 15                                            # mark-file liveness is ~10s; 15 for margin
systemctl start most-cluster-host most-engine
journalctl -u most-engine | grep 'restored'
```

That last line is the check. The engine prints `restored N resting orders ... from a snapshot`
specifically so that a real recovery is distinguishable from the consensus module replaying the log
into a freshly started service container.

::: warning Restarting only the service container is not a recovery
If you stop the engine and leave the cluster host running, the consensus module replays the log to
the new service from the beginning. It rebuilds the same books by a completely different route and
takes as long as the session is old — and it says nothing about whether the snapshot works.
:::

## 5.7 Measuring

`most load` drives a shard at a fixed rate and measures the round trip. The shard must already be
defined and `CONTINUOUS`.

```
$ most load --symbol AAPL,MSFT --price-min 99.00 --price-max 103.00 \
      --rate 20000 --count 60000 --warmup 10000 --participant 7 --participants 2

load: sending 60,000 orders at 20,000/s (50 µs apart)
     1.0s  sent 20,001 (20,000/s)  reports 29,986  answered 19,941  in-flight 60  ack p99 7094.3
     2.0s  sent 40,001 (20,000/s)  reports 59,452  answered 39,902  in-flight 99  ack p99 6991.9
     3.0s  sent 60,000 (19,966/s)  reports 88,676  answered 60,000  in-flight  0  ack p99 6975.5

load: AAPL,MSFT  60,000 orders in 3.00s -- 20,000/s achieved (target 20,000/s)
  measured path  client -> gateway -> cluster consensus -> engine -> gateway -> client
  band           99.00..103.00  qty 1..100  participants 7..8  warmup 10000
  offers         ok=60,000  backpressure-retries=0  dropped=0
  reports        88,676   new=30,546 trade=19,080 canceled=9,596 expired=0 rejected=29,454
  fills          528,026 qty traded, 7,695 orders filled on arrival (12.8%)
  unanswered     0 orders never saw a report
  REJECTED       29,454 x MARKET_CLOSED
  pacing         lateness n=50,000  p50=0.0 p90=0.0 p99=0.1 p99.9=22.3 max=1132.5 (µs)
  ack  response  n=50,000  p50=5275.6 p90=6537.2 p99=6975.5 p99.9=7622.7 max=8028.2 (µs)
  ack  service   n=50,000  p50=5275.6 p90=6537.2 p99=6975.5 p99.9=7622.7 max=8028.2 (µs)
  fill service   n=7,695   p50=5238.8 p90=6512.6 p99=6983.7 p99.9=7721.0 max=7913.5 (µs)
```

Read it in this order:

1. **`pacing lateness` first.** If it is not small, the generator was the bottleneck and the run says
   nothing about the exchange. Its schedule is absolute — `start + i × delay` — because a relative
   sleep drifts and then catches up in bursts.
2. **The reject counts.** The run above is a worked example of a run that must be discarded: 29,454
   `MARKET_CLOSED` rejections mean one of the two securities halted partway through, so half the
   orders never reached a book. Two things silently invalidate a run in exactly this way — a
   `maxOrders` too small for the rate, which turns everything into `BOOK_CAPACITY`, and a price band
   outside the static collar or the ladder. That is why the summary prints them.
3. **Both latencies.** *Service time* is measured from the actual send; *response time* from the
   scheduled send. Quoting only the first is coordinated omission and hides exactly the queueing that
   appears at the rate you are trying to find.

Run the generator on a separate machine, never on a cluster node.

::: note Attribution
`e2e/run-attribution.sh` splits a round trip by stage and writes `.hgrm` histograms meant to be
diffed across a change. On a development machine the gateway and engine own **0.8 µs of a 55 µs**
round trip — 1.4% — and the engine's whole-message p50 is 0.42 µs. The number to watch after a change
is the *share*, not the round trip: real network hops add to the denominator and dedicated cores
subtract from it.
:::

## 5.8 Monitoring

### What to watch

| Signal | Where | Meaning |
| --- | --- | --- |
| `droppedToClient` | gateway | Undeliverable execution reports. The outbound leg drops and counts by design; a rising rate means a subscriber is gone |
| `clusterBackpressure` | gateway | Transient and retried, so not a loss — but a sustained rate means ingress is saturated |
| `untrackedReports` | gateway | Reports the engine could not state an `origQty` for. Only orders restored from an older snapshot should produce these |
| `gaps`, `eventsMissed` | market-data | Feed sequence gaps. Occasional under `MaxMulticastFlowControl`; sustained means a subscriber cannot keep up |
| `foreignShard` | market-data | Book events for another shard. Means this process is pointed at the wrong engine |
| `imagesApplied` | market-data | A restarted process expects this to be non-zero. Zero with a book that stayed empty means the engine never sent one |
| `rejectedDefinitions` | engine | A `SecurityDefinition` was refused. Commands are unacknowledged, so this counter is the only signal |
| `registryReloadFailures` | engine, cluster-host | A registry that could not be read or was for another shard. The one in force still applies, so this is a quiet wrong rather than an outage |
| `authenticatedGateways` = 0 | cluster-host, at shutdown | Every gateway connected anonymously despite a registry being configured |
| Leader changes | consensus | Any unexplained one is worth a look |

::: todo There is no health or metrics endpoint on the core processes
Everything above is a log line or a counter printed at shutdown. A scrape endpoint per process is
what would make this mechanisable.
:::

### The exchange's own view of itself

`GET /api/status` is what the exchange is actually doing, as opposed to what the database says it
should be. It is derived from L3 and from the discovery broadcast.

```json
{
  "connected": true,
  "directory": { "version": "9181280125937456696", "securities": 2, "shards": [0],
                 "lastSeenAt": "2026-09-06T14:44:00.827Z", "incompleteBroadcasts": 0 },
  "securities": [
    { "securityId": 1, "shardId": 0, "phase": "CLOSED",
      "halt": { "at": "2026-09-06T14:43:46.966Z", "collarReference": 10064000000,
                "attemptedPrice": 10167000000, "breachedBound": 100640000,
                "aggressorSide": "BUY", "clearedAt": null },
      "lastTradePrice": 10161000000, "lastTradeQty": 29 },
    { "securityId": 2, "shardId": 0, "phase": "CONTINUOUS", "halt": null,
      "lastTradePrice": 10122000000, "lastTradeQty": 4 }
  ],
  "feedGaps": 9543, "eventsMissed": 26075, "eventsSeen": 9552,
  "routingDrift": []
}
```

`routingDrift` reports where the database and what discovery is actually broadcasting disagree.
Commands route from the **database**, not the directory — the control plane is the authority on
topology and a command must still be sendable when discovery is down — so where the two disagree,
that is said out loud rather than one silently winning.

### Who asked

`operator_audit` records every market-moving command against the operator who issued it, and records
**`sent`, not `applied`**. A command the control plane could not deliver is recorded too, with
`sent: false`; that is exactly as interesting to an investigation as one that went out.

![Audit. Every market-moving command, the operator who asked for it, and whether the bytes were sent.](assets/ui-audit.png)

Unattended transitions stay in the scheduler's own richer `schedule_run` log, which also records what
it deliberately did *not* do.

### Operator accounts

There is one role, `ADMIN`, with full access; the audit is what distinguishes who did what. Two ways
in, for two different callers: a session cookie plus a CSRF token for the browser, and HTTP Basic —
exempt from CSRF, because a request carrying its own credentials is not a forgery risk — for scripts.

![Operators. Accounts and their state. The `role` column exists so that adding a read-only tier later is a data change rather than a migration.](assets/ui-operators.png)
