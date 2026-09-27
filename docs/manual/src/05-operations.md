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
| `UNAUTHORIZED_PARTICIPANT` | gateway | The participant is not one this gateway may act for — or, for a new order, is only `cancelOnly` here (4.3). Never reaches the cluster |

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
| `most cluster snapshot --ingress 0=HOST:PORT --identity ID --secret-file F` | Through consensus, so **every member** snapshots at the same log position, and the command prints the cluster's **answer** — `snapshot taken, the cluster answered OK`, or the refusal. On a node with a registry the identity must be an `operator=true` entry (4.3). This is the one to use in production. |
| `most cluster snapshot --dir DIR` | The local control toggle, one member only |
| `most cluster shutdown` | Snapshot, then stop — which plain SIGTERM does **not** do |
| The control plane's scheduler | At each session close, as the control plane's operator identity (4.9) |
:::

A snapshot through consensus is only as real as the answer. Aeron's own default authorisation grants
**no** snapshot request, and until the registry authorised operators every one asked for over the
network was refused — while being reported as requested, because only the offer was checked. The
cluster's recording log is the ground truth when in doubt:

```sh
java -cp "/opt/most/tools/lib/*" io.aeron.cluster.ClusterTool <cluster-dir> recording-log \
  | grep -o 'type=SNAPSHOT' | wc -l        # two entries per snapshot: consensus module and service
```

Leave the scheduler enabled in production: a daily snapshot at the close is what bounds restart time.

### Restarting a node

```sh
most cluster shutdown --dir <the node's --dir>      # on the node; or rely on the scheduler's close snapshot
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

### What a shard actually carries

`e2e/run-sweep.sh` drives a series of rates, validates each one before believing it, and prints a
row block for the record. `SECURITIES=n` drives *n* securities, and **`RATES` is the aggregate across
them** — `SECURITIES=10 RATES=1000000` is 100,000/s per security.

Measured, single node, ten securities, development machine with the desktop closed:

| `--driver-threading` | Sustained (aggregate) | Per security |
| --- | --- | --- |
| `SHARED` (default) | ~350,000 orders/s | ~35,000/s |
| `DEDICATED` | ~550,000 orders/s | ~55,000/s |

The `DEDICATED` figure is where the development laptop ran out of performance cores, not a limit of
any stage (5.8, "How full each thread is"). A host with a core for every spinning thread has not yet
been measured; do not assume the knee moves with it by any particular amount.

**Plan capacity against these numbers, not against the design target** of 100,000/s per security
across ten. Two findings behind them are worth carrying into any capacity conversation:

- **The ceiling is aggregate, not per security.** Ten books sustain the same *total* rate one book
  does; fan-out costs 8% of the work of a single order and buys no throughput. A shard is one
  thread's worth of shared path — one ingress, one consensus module, one archive, one log — and
  adding securities divides it rather than multiplying it.
- **Matching is not the constraint.** The engine's own whole-message p50 is 0.38–0.50 µs, about 23% of
  its thread at the ceiling; the rest of that thread is the Aeron plumbing around it. On the
  development machine the ceiling itself was the core count (5.8).

::: warning A rate above the knee is a queue, not a latency
Past the sustainable rate the shard still accepts everything — no rejects, no drops, every order
answered — and the delay becomes a backlog that never drains. The `achieved` figure on such a run is
the rate the *generator offered*, not one the shard sustained, and its p50 is the queue draining.
`run-sweep.sh` marks the row `SATURATED` and names the highest rate the shard kept up with. Never
quote a `SATURATED` row's latency.
:::

::: note Attribution
`e2e/run-attribution.sh` splits a round trip by stage and writes `.hgrm` histograms meant to be
diffed across a change. On an idle development machine the gateway and engine own **0.8 µs of a
38 µs** round trip — 2.0%, falling to 0.9% as the rate rises — and the engine's whole-message p50 is
0.46 µs at one security, 0.50 µs at ten. The number to watch after a change is the *share*, not the
round trip: real network hops add to the denominator and dedicated cores subtract from it. It takes
the same `SECURITIES` and `CLUSTER_HOST` knobs as the sweep.
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
| `registryReloadFailures` | engine, cluster-host, gateway | A registry that could not be read or was for another shard. The one in force still applies, so this is a quiet wrong rather than an outage |
| `rejectedSessions` | cluster-host, at shutdown | Sessions refused at connect: wrong secret, unknown gateway, or no credentials on a node with a registry |
| `unauthorizedRejects` | gateway | Orders and cancels refused `UNAUTHORIZED_PARTICIPANT`. Non-zero means someone reached this gateway as a participant it does not serve |
| `refusedCommands` | gateway | Operator commands consumed unsent because this gateway is not an operator. Non-zero with markets not moving means the control plane or CLI is pointed at the wrong gateway (4.9) |
| `undeclaredParticipantMessages` | engine | Orders and cancels from a gateway that does not list the participant. Counted, never refused; non-zero means a gateway is not enforcing the registry the engine holds |
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
                 "lastSeenAt": "2026-09-26T14:02:07.570Z", "incompleteBroadcasts": 0 },
  "securities": [
    { "securityId": 1, "shardId": 0, "phase": "CLOSED",
      "halt": { "at": "2026-09-26T14:02:05.698Z", "collarReference": 10005000000,
                "attemptedPrice": 12500000000, "breachedBound": 2001000000,
                "aggressorSide": "BUY", "clearedAt": null },
      "lastTradePrice": 10050000000, "lastTradeQty": 60 },
    { "securityId": 2, "shardId": 0, "phase": "CONTINUOUS", "halt": null,
      "lastTradePrice": 9996000000, "lastTradeQty": 36 }
  ],
  "feedGaps": 0, "eventsMissed": 0, "eventsSeen": 35528,
  "routingDrift": []
}
```

Captured from the development stack (fields trimmed for width) after a 20,000-order load run across
both securities and a buy swept AAPL towards 125.00 against a reference of 100.05: the dynamic
collar (±20%) halted it at the breaching level, so security 1 is `CLOSED` with a `halt` block whose
`clearedAt` stays null until an operator reopens it (6.4). **35,528 book events and not one gap** is
what a healthy feed looks like. An older capture read `"feedGaps": 9543` against `"eventsSeen":
9552` — not a lossy feed but a defect in the control plane, which counted a sequence only for the
four book events it interprets while the engine numbers all seven, so every order event it ignored
read as a gap and real loss was invisible.

The three feed counters, once they mean what they say:

| Field | Healthy | What a non-zero value means |
| --- | --- | --- |
| `eventsSeen` | climbing | Book events the control plane has read off L3 |
| `feedGaps` | 0, or rare | Jumps forward in the sequence: the subscriber missed messages. Occasional under `MaxMulticastFlowControl` is by design; sustained is a real problem, and the fix is not changing flow control (6.6) |
| `eventsMissed` | 0 | How many messages those gaps accounted for |

`routingDrift` reports where the database and what discovery is actually broadcasting disagree.
Commands route from the **database**, not the directory — the control plane is the authority on
topology and a command must still be sendable when discovery is down — so where the two disagree,
that is said out loud rather than one silently winning.

### Reading Aeron's own counters

Every Aeron component — the media driver, the archive and each cluster component — publishes counters
into the driver's `cnc.dat`. `most counters` reads them **with no Aeron client**, so it is safe to
point at a shard under load and cannot perturb what it is measuring.

```sh
most counters --aeron-dir /dev/shm/aeron-most            # a snapshot of everything non-zero
most counters --aeron-dir … --interval-ms 4000 --samples 3   # rates of change, busiest first
most counters --aeron-dir … --all --match 'luster|archive' # including the still ones, filtered
```

`--interval-ms` is the mode that finds things. A counter's value is rarely interesting; its slope
usually is, and the shape to look for is **a position counter that stops advancing while the one
feeding it does not**. Reach for this before theorising about where time goes — it is what identified
the driver threading mode as the shard's ceiling (4.8).

::: warning A clean counter sheet does not mean nothing is saturated
Aeron reports queues, positions, duty-cycle breaches and errors. A stage that is simply **full**
breaches none of them, so "every counter scaled with the offered rate and nothing exceeded a
threshold" narrows an answer to *not a buffer, a window, a disk or a stall* and no further. Nor can
`top` finish the job: the engine, gateway and market-data busy-spin and read ~100% of a core whether
working or idling (3.4). The next instrument after the counters is the duty cycle, below.
:::

::: note A counter that moves non-linearly names a place to look, not a cause
Sender flow-control back-pressure on the cluster ingress channel rose 89-fold at the knee, which
looked conclusive and pointed at the ingress term length. Raising it 256-fold moved the sustainable
rate not at all — back-pressure on a channel is what a slow *consumer* looks like from the
publisher's side, so a closing window is as likely to be the symptom as the cause. Change it and
re-measure before believing it.
:::

### How full each thread is

Every loop on the order path can publish its **duty cycle** — the share of wall time it spends in
iterations that found work — as an Aeron counter labelled `duty-ns: <thread>`. It is the one reading
that tells a busy thread from a spinning one. Turn it on with each process's metrics switch
(`engine.metrics`, `gateway.metrics`, `md.metrics`) and `most cluster --duty` for the driver, archive
and consensus module; all are on in the development stack. Then:

```
$ most counters --aeron-dir build/attr-duty-check/aeron --match '^duty-ns' --all --interval-ms 3200
counters: build/attr-duty-check/aeron/cnc.dat  pid=55343

  sample 1 of 1 over 3.20s
     id                value         per second  label
    166           4706046275        918,693,319  duty-ns: driver sender   = 91.9% of a core
    167           3523624174        688,061,980  duty-ns: driver receiver   = 68.8% of a core
    168           1117142847        203,768,413  duty-ns: archive   = 20.4% of a core
    165           1006793108        178,289,139  duty-ns: driver conductor   = 17.8% of a core
    170            899559798        161,337,009  duty-ns: gateway anonymous   = 16.1% of a core
    169            780158699        134,305,447  duty-ns: engine service   = 13.4% of a core
     94            705590536        119,773,332  duty-ns: consensus-module   = 12.0% of a core
    171            362722381         65,071,360  duty-ns: market-data   = 6.5% of a core

  A duty-ns counter's rate is busy nanoseconds per second: the share of one core its
  thread spent working, which CPU% cannot show for a thread that spins while idle.
  A position counter that stops advancing while its feeder keeps going is the
  saturating stage. Sorted by absolute change, so the busiest is at the top.
```

That is `e2e/run-attribution.sh` sampling mid-load: one node at 250,000 orders/s across ten
securities, `DRIVER_THREADING=DEDICATED`, with a gateway that connected without an identity — hence
`gateway anonymous`; a registered one is labelled with its id, `gateway gw-0`. The duty cycle costs
two clock reads per loop iteration and changes nothing about how a thread idles, so it can stay on.

Read it like this:

- **A thread that climbs with the rate and reaches ~100% is full.** That is how the engine's service
  thread looks past the development machine's knee: 20% at 500,000/s, 99.9% one step later.
- **The driver's sender and receiver read high from the start and are not full.** They drain whatever
  has accumulated on each pass, so ~90% at a quarter of the knee means *never idle*, not *at capacity*.
  Judge them by whether the reading keeps rising with the rate, not by its value.
- **A process spinning at a low duty is wasting a core.** On a machine without a core per thread that
  core is taken from somebody who needed it; set that process to `backoff` (3.4).

::: warning On a machine with fewer cores than busy threads, the knee is the core count
On the development laptop (10 performance cores) the shard stops keeping up at ~550,000/s not because
a stage is full — the consensus module is under 20%, the gateway ~34%, market data ~13% — but because
its busy threads outnumber the cores, and the engine, whose cost is cache misses, loses its core first.
Its duty jumps to 100% while its median cost per order stays put. Setting the gateway and market data
to `backoff` moved that knee up a step. A knee measured on such a machine describes the machine.
:::

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
