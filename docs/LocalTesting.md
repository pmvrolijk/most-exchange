# Running the Exchange Locally

How to bring up the whole system on one machine, seed it, trade through it, watch the market data,
measure what it does under load, and take it down again.

This is a development setup: **one cluster node** and **Aeron IPC** rather than multicast. It proves
the components talk to each other, not that the network is configured. Production runs three or five
cluster nodes with multicast feeds — see [Design.md](Design.md) §1 and §5.

For a scripted version of the same thing, `e2e/run-e2e.sh` does it end to end and asserts the
results. This document is the manual walkthrough.

---

## 1. Build

```sh
./gradlew installDist
```

That produces five launchers:

| Binary | Path |
| --- | --- |
| `most` | `tools/build/install/most/bin/most` |
| engine | `engine/build/install/engine/bin/engine` |
| gateway | `gateway/build/install/gateway/bin/gateway` |
| market data | `market-data/build/install/market-data/bin/market-data` |
| discovery | `discovery/build/install/discovery/bin/discovery` |

Set up a run directory and some shorthand. Every path below is absolute because the processes
resolve them independently of the shell that started them.

```sh
export RUN="$PWD/build/local-test"
export MOST="$PWD/tools/build/install/most/bin/most"
mkdir -p "$RUN/logs"
```

---

## 2. The shard security file

One file, read by the engine, the gateway, the market data process **and** discovery. A shard has a
single security list rather than four that must be kept in step by hand.

Three securities on one shard — save as `$RUN/shard-0-securities.properties`:

```properties
shard.id=0
shard.securities=1,2,3

# Prices are fixed-point with 8 implied decimals, so tickSize 1000000 == 0.01.
security.1.symbol=AAPL
security.1.isin=US0378331005
security.1.name=Apple Inc.
security.1.currency=USD
security.1.priceFloor=0
security.1.tickSize=1000000
security.1.levelCount=65536
security.1.maxOrders=100000

security.2.symbol=MSFT
security.2.isin=US5949181045
security.2.name=Microsoft Corporation
security.2.currency=USD
security.2.priceFloor=0
security.2.tickSize=1000000
security.2.levelCount=65536
security.2.maxOrders=100000

security.3.symbol=AIR
security.3.isin=NL0000235190
security.3.name=Airbus SE
security.3.currency=EUR
security.3.priceFloor=0
security.3.tickSize=1000000
security.3.levelCount=65536
security.3.maxOrders=100000
```

Three things bite here if you change the numbers:

* **ISINs are validated including their check digit.** A transposed digit is rejected at boot rather
  than published to every adapter, so invented ISINs will not start.
* **`levelCount` must cover the static collar band.** The ladder is required to be wider than the
  band (Design.md §3.2), and `SecurityDefinition` now rejects a collar that would fall off it. At a
  0.01 tick, 65536 levels reach 655.36 — enough for MSFT at 420.00 with a 20% band, but 4096 levels
  would only reach 40.96 and every order would be rejected `PRICE_OUT_OF_LADDER`.
* **Nothing has a default.** A wrong tick size misprices every order silently, so it must be stated.

Reference prices and collar widths are deliberately *not* here — they change during a session and
arrive as commands through the replicated log (§4.4). That is what `most define` does in step 5.

---

## 2a. The participant registry (optional)

Which gateway speaks for which participant. Skip it and everything below still works — the engine
falls back to learning routes from inbound traffic, which is what shipped before this file existed.
What you lose is the case that traffic cannot cover: a participant that has said nothing since the
gateway last connected has no route at all, so its fills are counted undeliverable and dropped, and
the gateway's `cumQty` for that order silently stops advancing (Design.md §1). A gateway restart
puts every one of its quiet participants in that state at once.

The secret is stored as its SHA-256; the secret itself goes in a file the gateway reads.

```sh
SECRET="local-dev-secret"
printf '%s\n' "$SECRET" > "$RUN/gateway-0.secret"
HASH=$(printf '%s' "$SECRET" | shasum -a 256 | cut -d' ' -f1)   # sha256sum on Linux

cat > "$RUN/shard-0-participants.properties" <<EOF
shard.id=0
registry.gateways=gw-0
gateway.gw-0.secret=$HASH
gateway.gw-0.participants=7,8
EOF
```

Three things it enforces, each with a reason:

* **A participant belongs to at most one gateway.** Two claims would be settled by whichever
  session opened last, which is routing decided by connection timing.
* **Credentials that do not verify are rejected**, never downgraded to an anonymous session. A
  gateway that connected anonymously by accident trades perfectly well and loses only the fills of
  whichever participants have gone quiet — invisible until someone reconciles a `cumQty`.
* **Every node needs an identical copy.** Each process prints the registry's `fingerprint` at
  startup for the same reason it prints the shard's.

It is *not* enforcement: the engine binds routes from it but does not yet refuse an order whose
`participantId` is not bound to the session it arrived on.

---

## 3. Process configuration

Four small files, each pointing at the shared security list.

`$RUN/engine.properties`

```properties
engine.securitiesFile=${RUN}/shard-0-securities.properties
# Optional; see §2a. Omit this line and routes are learned from traffic alone.
engine.participantRegistry=${RUN}/shard-0-participants.properties
engine.aeronDir=${RUN}/aeron
engine.clusterDir=${RUN}/cluster-host/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
```

`$RUN/gateway.properties`

```properties
gateway.securitiesFile=${RUN}/shard-0-securities.properties
gateway.aeronDir=${RUN}/aeron
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=localhost:20110
gateway.egressChannel=aeron:udp?endpoint=localhost:0
gateway.client.inbound.channel=aeron:ipc
gateway.client.inbound.streamId=20
gateway.client.outbound.channel=aeron:ipc
gateway.client.outbound.streamId=21
# Optional; see §2a. All three go together -- an id with no secret refuses to start, because a
# gateway that failed to authenticate as itself would connect anonymously and lose fills quietly.
gateway.participantRegistry=${RUN}/shard-0-participants.properties
gateway.gatewayId=gw-0
gateway.credentialTokenFile=${RUN}/gateway-0.secret
```

`$RUN/market-data.properties`

```properties
md.securitiesFile=${RUN}/shard-0-securities.properties
md.aeronDir=${RUN}/aeron
md.bookEvent.channel=aeron:ipc
md.bookEvent.streamId=12
md.l1.channel=aeron:ipc
md.l1.streamId=31
md.l2.channel=aeron:ipc
md.l2.streamId=32
md.l3.channel=aeron:ipc
md.l3.streamId=33
md.snapshot.channel=aeron:ipc
md.snapshot.streamId=34
md.snapshot.cycleMs=1000
```

`md.snapshot.*` is the L2 recovery feed: a full image of one book per slice, rotating, so a
subscriber that starts after trading began has something to apply increments to. A whole pass takes
`cycleMs` however many securities the shard hosts, and that pass is the longest a joining consumer
waits before its book is trustworthy.

`$RUN/discovery.properties`

```properties
discovery.shards=0
discovery.shard.0.securitiesFile=${RUN}/shard-0-securities.properties
discovery.shard.0.orderEntryChannel=aeron:ipc
discovery.shard.0.orderEntryStreamId=20
discovery.shard.0.executionReportChannel=aeron:ipc
discovery.shard.0.executionReportStreamId=21
discovery.aeronDir=${RUN}/aeron
discovery.channel=aeron:ipc
discovery.streamId=100
discovery.intervalMs=1000
```

Expand `${RUN}` when you write these — the processes read plain properties files and do no variable
substitution. For example:

```sh
sed -i '' "s|\${RUN}|$RUN|g" "$RUN"/*.properties
```

Note what discovery publishes: the **gateway's** order-entry and execution-report channels, not the
cluster's ingress. Adapters talk to the gateway; reaching past it would bypass the validation and
`cumQty` reconstruction the gateway exists to perform.

---

## 4. Start the processes

Order matters: each attaches to what the previous one created. Wait for each to report ready rather
than sleeping a fixed time — the cluster in particular can take several seconds.

```sh
cd "$RUN"
L="$RUN/logs"

# 1. cluster host: media driver + archive + consensus module
# --participants is optional (§2a). It is this process, not the engine, that verifies a gateway's
# credentials: the consensus module stamps the gateway id on the session as its encoded principal,
# and the engine only turns that principal back into a participant list.
$MOST cluster --fresh --dir "$RUN/cluster-host" --aeron-dir "$RUN/aeron" \
  --participants "$RUN/shard-0-participants.properties" > "$L/cluster.log" 2>&1 &

# wait for: "cluster: started, awaiting shutdown signal"

# 2. engine (the cluster service container only)
engine/build/install/engine/bin/engine "$RUN/engine.properties" > "$L/engine.log" 2>&1 &
# wait for: "matching-engine: started, awaiting shutdown signal"

# 3. gateway
gateway/build/install/gateway/bin/gateway "$RUN/gateway.properties" > "$L/gateway.log" 2>&1 &
# wait for: "gateway: started"

# 4. market data
market-data/build/install/market-data/bin/market-data "$RUN/market-data.properties" \
  > "$L/market-data.log" 2>&1 &
# wait for: "market-data: started"

# 5. discovery
discovery/build/install/discovery/bin/discovery "$RUN/discovery.properties" \
  > "$L/discovery.log" 2>&1 &
# wait for: "discovery: started"
```

A helper that waits properly:

```sh
waitfor() {  # waitfor <logfile> <pattern>
  for _ in $(seq 1 120); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 0.5; done
  echo "timed out waiting for '$2' in $1"; tail -20 "$1"; return 1
}
```

**The engine hosts only the cluster service.** The media driver allocates, and keeping it out of the
engine binary is what lets the zero-allocation profile hold (§7). If you start the engine without a
cluster host it says so and exits rather than stack-tracing.

Every process prints a **fingerprint** of the shard security file at startup. They must all match:

```
matching-engine: shard=0 fingerprint=32a3ec55e9e2b45a securities=[AAPL, MSFT, AIR] ...
gateway:         shard=0 fingerprint=32a3ec55e9e2b45a securities=[AAPL, MSFT, AIR] ...
market-data:     shard=0 fingerprint=32a3ec55e9e2b45a securities=[AAPL, MSFT, AIR] ...
```

Geometry decides how a price maps to a ladder level, so a mismatch would diverge the books rather
than fail loudly. Comparing one hex string is the check.

---

## 5. Connection options for `most`

Every `most` subcommand starts by listening for a discovery broadcast, because the routing table is
the only thing mapping a symbol to the shard and gateway serving it. Since this setup uses IPC
rather than the multicast defaults, pass the channels each time:

```sh
CONN="--aeron-dir $RUN/aeron
      --discovery-channel aeron:ipc --discovery-stream 100
      --l1-channel aeron:ipc --l1-stream 31
      --l2-channel aeron:ipc --l2-stream 32
      --snapshot-channel aeron:ipc --snapshot-stream 34"
```

### Check the universe is discoverable

```sh
$MOST securities --verbose $CONN
```

```
SYMBOL     ISIN           SHARD CCY    NAME
AAPL       US0378331005   0     USD    Apple Inc.
           id=1 tick=0.01 floor=0.00 levels=65536
           orders -> aeron:ipc:20   reports <- aeron:ipc:21
AIR        NL0000235190   0     EUR    Airbus SE
           id=3 tick=0.01 floor=0.00 levels=65536
           orders -> aeron:ipc:20   reports <- aeron:ipc:21
MSFT       US5949181045   0     USD    Microsoft Corporation
           id=2 tick=0.01 floor=0.00 levels=65536
           orders -> aeron:ipc:20   reports <- aeron:ipc:21

3 securities, universe version 5018094885691953163
```

If this hangs, discovery is not running or the channel is wrong. It broadcasts on a cycle, so allow
one interval (1s here) before assuming failure.

---

## 6. Seed the securities and open the session

A freshly started engine has every book `CLOSED` with no reference price. Two steps to trade.

### Seed reference prices and collars

```sh
$MOST define --symbol AAPL --reference 100.00 --static-collar 2000 --dynamic-collar 500 $CONN
$MOST define --symbol MSFT --reference 420.00 --static-collar 2000 --dynamic-collar 500 $CONN
$MOST define --symbol AIR  --reference  85.00 --static-collar 2000 --dynamic-collar 500 $CONN
```

```
defined AAPL on shard 0: reference 100.00, static 2000bps, dynamic 500bps
```

Collars are basis points: 2000 = 20% acceptance band, 500 = 5% execution band. `define` may be
re-issued at any time — it is also the lever for reopening a security after a volatility halt whose
price sits outside the static band (§4.6).

### Move the shard to continuous trading

```sh
$MOST session --phase continuous --shard 0 $CONN
```

```
shard 0 -> continuous (trading date 20260830)
```

Session transitions are **shard-wide**: the engine applies the phase to every book it hosts.

To exercise the opening auction instead, walk the phases and rest orders in between — the uncross
runs on the transition into `continuous`:

```sh
$MOST session --phase pre-open     --shard 0 $CONN
$MOST session --phase open-auction --shard 0 $CONN
$MOST send --symbol AAPL --side buy  --price 100.50 --qty 20 --participant 7 --clordid 11 $CONN
$MOST send --symbol AAPL --side sell --price  99.50 --qty 15 --participant 8 --clordid 21 $CONN
$MOST session --phase continuous   --shard 0 $CONN   # uncrosses here
```

---

## 7. Send orders

```sh
$MOST send --symbol AAPL --side sell --price 100.00 --qty 10 \
  --participant 7 --clordid 1001 --follow 3 $CONN
```

```
sending sell 10 AAPL @ 100.00 clOrdId=1001
sent to shard 0 via aeron:ipc
  NEW  AAPL  orderId=1 clOrdId=1001  leaves 10 cum 0 of 10
```

Cross it from another participant:

```sh
$MOST send --symbol AAPL --side buy --price 100.00 --qty 4 \
  --participant 8 --clordid 2001 --follow 3 $CONN
```

```
  NEW    AAPL  orderId=2 clOrdId=2001  leaves 4 cum 0 of 4
  TRADE  AAPL  orderId=2 clOrdId=2001  last 4 @ 100.00 cum 4 leaves 0
```

`--follow N` is how many seconds to keep printing execution reports; the order is asynchronous, so
without it the tool exits before the reports arrive. `--participant` is who you trade as — use
different ids for the two sides or self-match prevention will cancel your own order, which is
exactly what it is for.

Arguments are validated before the network is touched, so a typo reports the typo:

```
$ most send --symbol AAPL --side buy --price 1.2.3 --qty 10
most: price '1.2.3' is not a decimal number
```

Tick alignment is checked once discovery has supplied the geometry. Phase, collars and capacity are
the engine's business — the CLI does not duplicate them.

### Cancel

```sh
$MOST cancel --symbol AAPL --side sell --order-id 1 --orig-clordid 1001 \
  --participant 7 --follow 3 $CONN
```

```
  CANCELED  AAPL  orderId=1 clOrdId=1788044152160  leaves 0 cum 4 of 10
```

`cum 4 of 10` is the point: 4 filled, 6 cancelled. `--order-id` is the engine's `exchangeOrderId`
from the `NEW` report, `--orig-clordid` the client id of the order being cancelled.

---

## 8. Inspect market data

```sh
$MOST book --symbol AAPL --depth 5 --refresh 500 $CONN
```

```
── AAPL (id 1) ────────────────────────────────────
       qty ords          bid │ ask        qty        ords
        25 (1)         99.50 │ 100.50     12         (1)
        40 (1)         99.00 │
  spread 1.00   updates 3
```

Omit `--symbol` to watch every security in the universe, or pass a comma-separated list. Ctrl-C to
stop.

**Start the inspector before the depth you want to see.** There is no market data snapshot: a
subscriber that joins late sees only subsequent updates, so a book opened after trading began will
show a partial picture — and, because it is missing levels, a wider spread than the engine actually
has. This is a known gap (§8), not a display bug.

The inspector rebuilds books from the L2 depth feed and reports gaps as a staleness warning:

```
!! 1 gaps, 7 messages missed -- depth shown may be stale; resubscribe to resynchronise
```

Under `MaxMulticastFlowControl` — the correct setting for a market data feed — a slow subscriber
takes an unrecoverable gap by design, so the warning means what it says.

---

## 9. Measure throughput and latency

`most load` drives the shard at a fixed rate and reports what came back. It measures the **whole
client round trip** — gateway, cluster consensus and archive write, engine, egress, gateway, client
publication — so on a single node the archive's disk write dominates and the figure is an end-to-end
capacity number, not the engine's internal budget.

Two things must be right first, or the run measures something else:

* **`maxOrders` must be realistic.** A book that fills rejects everything after it with
  `BOOK_CAPACITY`. The `security.N.maxOrders=1000000` of §2 is the number to benchmark against; a
  small value produces a run that measures the reject path and says so.
* **The band must sit inside the static collar** seeded by `most define`, and inside the ladder.
  `most load` warns about the ladder, which it can see in the directory; the collar it cannot, so a
  band outside it shows up as a `PRICE_OUT_OF_BOUNDS` count in the summary.

```sh
most load --symbol AAPL --price-min 99.90 --price-max 100.10 --qty-min 1 --qty-max 10 \
  --count 300000 --delay-us 10 --participant 20 --participants 4 $CONN
```

```
load: AAPL  300,000 orders in 3.00s -- 100,000/s achieved (target 100,000/s)
  measured path  client -> gateway -> cluster consensus -> engine -> gateway -> client
  band           99.90..100.10  qty 1..10  participants 20..23  warmup 30000
  offers         ok=300,000  backpressure-retries=0  dropped=0
  reports        647,190   new=299,997 trade=296,798 canceled=50,395 expired=0 rejected=0
  fills          930,024 qty traded, 135,948 orders filled on arrival (45.3%)
  unanswered     3 orders never saw a report
  pacing         lateness n=270,000  p50=0.0 p90=0.0 p99=0.2 p99.9=7.7 max=98.2 (µs)
  ack  response  n=269,997  p50=46.8 p90=77.0 p99=592.9 p99.9=1839.1 max=2689.0 (µs)
  ack  service   n=269,997  p50=46.8 p90=76.9 p99=592.9 p99.9=1839.1 max=2689.0 (µs)
  fill service   n=135,948  p50=47.8 p90=79.1 p99=630.8 p99.9=1890.3 max=2680.8 (µs)
```

### The Docker stack measures the VM, not the engine

The same sweep against `deploy/`'s container stack sustains its target rate to **200,000 orders/s**
into one book with nothing dropped and nothing unanswered, and `pacing lateness` p50 at 0.0 µs
throughout — so throughput is not what a VM costs. Latency is: p50 ack sits in the milliseconds
against the 47 µs above, and is *lower* at 100k/s than at 3k/s, which is the shape of a fixed
per-wakeup cost being amortised by batching rather than of queueing. Quote container numbers as
container numbers.

Two things that stack needs before a run like that is meaningful:

* **`/aeron` must be big enough for every subscription image.** Each is a log buffer of three terms.
  At 256 MB the client-side driver ran out of space at 100k/s and died with
  `InternalError: a fault occurred in an unsafe memory access operation` — SIGBUS on a mapped file,
  not a JVM bug. It is 1 GB now.
* **The control plane will fall behind and resynchronise, and that is correct.** Through 1.35M
  orders its depth subscriber took 9 gaps and rebuilt its books 15 times from snapshots, while the
  exchange stayed at rate. A dashboard taking a gap instead of throttling the publisher is the whole
  point of `MaxMulticastFlowControl`.

### Reading it

**`ack response` versus `ack service`.** Service time is measured from when the order was actually
sent; response time from when it was *due* to be sent. While the generator keeps up they agree.
When it cannot, response time includes the queueing the client would really have suffered, and it is
the honest number — reporting only service time is the coordinated-omission mistake. `pacing
lateness` says which case you are in: while it stays in single-digit microseconds the generator is
not the bottleneck.

**`canceled`** with no cancel requests sent is self-match prevention. With `--participants 4` roughly
one aggressor in four meets its own resting order and is cancelled under the default
`CANCEL_AGGRESSOR`. Raise `--participants` to reduce it.

**`unanswered`** counts orders that never saw any report. The gateway no longer drops orders on
cluster backpressure — it leaves them unconsumed and retries, which its `clusterBackpressure` counter
records — so unanswered orders now mean **execution reports** were lost on the way back, which the
gateway's `droppedToClient` counter confirms. That leg has no back-channel: a subscriber that falls
behind loses reports.

### Finding the knee

Sweep the delay and watch where response time stops tracking service time and `unanswered` climbs:

```sh
for delay in 100 20 10 5 3; do
  most load --symbol AAPL --price-min 99.90 --price-max 100.10 --qty-min 1 --qty-max 10 \
    --count 300000 --delay-us $delay --participant 20 --participants 4 --interval-ms 0 $CONN
done
```

On one development machine (single node, IPC, everything on one host) that gives roughly:

| Rate | ack p50 | ack p99 | unanswered |
| --- | --- | --- | --- |
| 50k/s | 39 µs | 291 µs | 2 |
| 100k/s | 47 µs | 593 µs | 3 |
| 200k/s | 61 µs | 1.1 ms | 3 |
| 333k/s | 35 ms | 53 ms | 134,271 |

The knee is between 200k and 333k orders/sec, so the design's 100k/s/security target has headroom on
this path. Numbers from a laptop are not a capacity plan — the point of the sweep is the shape, and
the shape says the collapse is abrupt rather than gradual.

Use `--seed` to repeat a run exactly, `--histogram FILE` to write the distribution for plotting, and
`--rate` instead of `--delay-us` when it reads better. `--delay-us 0` sends unpaced, which finds the
drop-off point quickly but has no schedule, so it reports no response time.

### 9a. Splitting the round trip by stage

`most load` measures the whole client round trip and nothing smaller, so a p50 of 55 µs cannot tell
you whether the engine is slow or the plumbing is. The engine and gateway can time their own hot
paths (Design.md §7), and one script drives the whole thing:

```sh
./gradlew installDist
./e2e/run-attribution.sh                 # 2M orders at 100k/s, then subtracts
ORDERS=500000 DELAY_US=20 ./e2e/run-attribution.sh   # smaller and slower
STAGES=false ./e2e/run-attribution.sh    # boundary timing only, ~1% instrument cost
```

It prints each process's percentiles and then the subtraction:

```
== attribution at the median
  client round trip            55.4 us
  gateway inbound               0.2 us
  engine (whole message)        0.4 us
  gateway outbound              0.2 us
  ------------------------------------
  in this shard's processes      0.8 us  (1.4%)
  everything else              54.6 us  (98.6%)
```

**That is the headline: the exchange's own code is 1.4% of a round trip.** The other 98.6% is Raft
consensus, the archive's disk write, the IPC hops and poller wake-ups — the cost of being a
replicated log, not idle time. It is also why the throughput knee in the sweep above is where it is:
tuning the matching engine would move almost none of it.

To do this by hand instead — against a stack you already have running from §2 — put these in the
engine and gateway property files before starting them, then stop the processes normally (§10) and
read the summaries:

```ini
engine.metrics=true
engine.metrics.stages=true
engine.metrics.file=/tmp/engine-latency.hgrm

gateway.metrics=true
gateway.metrics.file=/tmp/gateway-latency.hgrm
```

The summaries appear **only on an orderly shutdown**, so use `SIGTERM` and not `kill -9`. The
`.hgrm` files are the format `most load --histogram-file` writes, so a run recorded before a change
to the core and one recorded after can be compared directly — which is the point of writing them at
all.

Two things to keep in mind when reading the numbers. The clock read is inside them: the summary
prints its own measured cost (~10 ns here), which is ~1% of a 0.42 µs figure with boundary timing
and ~5% with stages. And on the JVM the tail is the garbage collector — a p99.9 in the tens of
microseconds and millisecond maxima are collection pauses, not matching.

---

## 10. Shut down

Stop in reverse start order, with `SIGTERM` so each process prints its counters:

```sh
for name in DiscoveryMainKt MarketDataMainKt GatewayMainKt EngineMainKt "ToolsMainKt cluster"; do
  pid=$(pgrep -f "com.engine.*$name" | head -1)
  [ -n "$pid" ] && kill -TERM "$pid" && sleep 1
done
```

```
discovery:       stopped. droppedFragments=6
market-data:     stopped. gaps=0 missed=0 foreignShard=0 droppedL1=1 droppedL2=1 droppedL3=4
gateway:         stopped. forwardedToCluster=3 droppedToCluster=0 sentToClient=1 \
                 droppedToClient=0 liveOrders=1 rejectedLocally=0 untrackedReports=0
matching-engine: shutdown signal received
cluster:         shutdown signal received
```

Worth reading rather than skipping:

* **`forwardedToCluster` vs `droppedToCluster`** distinguishes "nothing arrived" from "forwarded but
  nothing came back". Order state alone cannot tell them apart.
* **`droppedL1/L2/L3`** counts feed messages published with nothing listening. Non-zero is normal
  here — the L3 feed usually has no subscriber in a local setup.
* **`foreignShard`** should be zero. Non-zero means the market data process is pointed at another
  shard's engine.

### Verify everything actually stopped

```sh
pgrep -f "com.engine" | wc -l    # must be 0
```

**Do not skip this.** If a process survives, the next run inherits its Aeron directory and cluster
state, and the symptoms are baffling: commands report success while nothing reaches the engine,
because they are talking to a half-dead stack. If anything remains:

```sh
for p in $(pgrep -f "com.engine"); do kill -9 "$p"; done
```

### Clean up

Only after the check above returns 0 — deleting the Aeron directory under a running process is what
produces those baffling symptoms:

```sh
rm -rf "$RUN/aeron" "$RUN/cluster-host" "$RUN/logs"
```

The properties and the security file can stay; they hold no runtime state. To start completely
fresh, `rm -rf "$RUN"` and repeat from step 2.

**The cluster host now keeps its archive and cluster directory across a restart**, which is what
makes the shard resumable — it wipes them only when you pass `--fresh`. So a stale cluster state
*does* survive a restart, deliberately, and the walkthrough above passes `--fresh` for that reason.
The Aeron directory is the exception and is always recreated: it holds memory-mapped IPC buffers
rather than state, and keeping it would block the next start until the previous driver's liveness
timeout expired.

### The gateway keeps nothing, and you can prove it

There is no order journal and no state file to configure. `origQty` lives in the engine, in the cold
word beside the order's cache line, and the engine states it and `cumQty` on every execution report.
So a gateway is disposable:

```sh
# Rest a partially filled order, then replace the gateway entirely.
kill "$GATEWAY_PID"
$GATEWAY "$RUN/gateway.properties" > "$RUN/gateway-2.log" 2>&1 &
most cancel --symbol AAPL --side buy --order-id "$ID" --orig-clordid 3002 --participant 11 \
  --follow 3 $CONN            # still prints `cum 20 of 26`
```

Two gateways at once work the same way, and the only thing that has to differ between them is their
client-facing endpoints — two subscribed to *one* inbound channel would each receive every order and
forward both:

```
gateway.client.inbound.streamId=40
gateway.client.outbound.streamId=41
```

`e2e/run-restart.sh` §4b and §4d do both of these.

### Changing who speaks for whom, without restarting a node

The consensus module and the engine re-read `participants.properties` while they run, every five
seconds by default. Edit it — add a participant to a gateway, rotate a secret — and watch the swap:

```
cluster: reloaded .../participants.properties: fingerprint 374f3ab39eab8714 -> 8c1d02aa4f10b933, gateways=[gw-0, gw-1]
```

Then restart the gateway, which costs nothing, so its new session re-derives its bindings. A file
that cannot be parsed, or one for another shard, is reported and **ignored**: the registry in force
keeps authenticating, because standing down on a bad file would leave a shard that authenticates
nobody. `--participants-reload-ms 0` and `engine.participantRegistry.reloadMs=0` turn the poll off.

`pendingOrders` at shutdown is worth a glance: nothing reaps an order whose acknowledgement never
came, so a figure that only ever grows is a leak rather than traffic.

### Snapshots, and restarting with state

Nothing takes a snapshot unless you ask, and without a recent one a restart replays the log from the
last one — which after a session's trading is the difference between seconds and a very long time.

```sh
$MOST cluster snapshot --dir "$RUN/cluster-host"          # local, via the control toggle
$MOST cluster snapshot --ingress 0=localhost:20110        # through consensus; every member snapshots
$MOST cluster shutdown --dir "$RUN/cluster-host"          # snapshot, then stop
```

To restart with the books intact, stop **the whole node** — the cluster host included — and start it
again without `--fresh`. Restarting only the engine is not a recovery: the consensus module keeps
running and replays the log to the new service from the beginning, which rebuilds the same books by
a completely different route and takes as long as the session is old. The engine prints which
happened:

```
matching-engine: restored 3 resting orders across 2 books from a snapshot, nextExchangeOrderId=5 ...
```

**Give the previous node about ten seconds.** Aeron's archive and cluster mark files carry a
liveness timestamp and a new process refuses with `active mark file detected` until it ages out,
even after a clean shutdown.

**Changing a security file changes geometry, and the engine will refuse to start** if the snapshot
holds state the new geometry cannot hold — a security removed while its book has orders, or a
changed `priceFloor`, `tickSize`, `levelCount` or `maxOrders`. It prints what is wrong and exits
non-zero. Removing a security whose book is *empty* is allowed and is how a security leaves a shard;
so is adding one. `./e2e/run-restart.sh` exercises all of it.

**Market data comes back with the book too**, because a restored engine republishes each one as a
level image. If market data restarts *on its own* while the engine keeps running, it misses that
image and has no snapshot of its own — ask for another:

```sh
$MOST image --shard 0                     # republish every book on the shard
```

Safe to repeat, changes no book, and moves no market. `market-data: stopped.` prints
`imagesApplied=` at shutdown; zero, on a process whose ladder stayed empty, means no image ever
arrived — a different problem from a quiet feed.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `no Aeron media driver found` | The cluster host is not running, or `--aeron-dir` disagrees with it. |
| `no directory received on ...` | Discovery is not running, or the discovery channel differs. It broadcasts on a cycle — allow one interval. |
| Commands succeed but nothing reaches the engine | A process from an earlier run is still alive. Check `pgrep -f com.engine`. |
| `REJECTED ... MARKET_CLOSED` | The shard is not in a trading phase. Run `most session --phase continuous`. |
| `REJECTED ... PRICE_OUT_OF_LADDER` | `levelCount` does not reach the price. At a 0.01 tick, level N is price N/100. |
| `REJECTED ... PRICE_OUT_OF_BOUNDS` | Outside the static collar band, or `most define` was never run for that security. |
| `REJECTED ... SELF_MATCH_PREVENTED` | Both sides used the same `--participant`. Use different ids. |
| `active mark file detected` | A node was restarted too soon after the previous one. Aeron's archive and cluster mark files carry a liveness timestamp; wait about ten seconds. |
| `Active media driver detected` | The Aeron directory from a previous run is still there and still live. It is always recreated on start, so this means a driver is genuinely still running — check `pgrep -f com.engine`. |
| `refused to restore its snapshot` | The security file changed in a way that would destroy state. The report names the security and what it holds. Restart on the previous file to restore it, or empty the book first. |
| `most book` shows nothing after a restart | Market data restarted without the engine and missed its book image. Run `most image --shard 0`. |
| A client sees `cum unknown` | The engine could not state an `origQty`. Only an order restored from a pre-v3 snapshot should do this; otherwise the engine and gateway are built from different schema versions. Check `untrackedReports` at gateway shutdown. |
| Fingerprints differ between processes | They are reading different security files. |
| `most send` prints no execution report | Increase `--follow`; or the gateway lost its cluster session — check `gateway.log`. |
| `most load` reports every order `BOOK_CAPACITY` | `maxOrders` is too small for the rate, or the band is too wide to cross so nothing ever leaves the book. |
| `most load` reports many `unanswered` | The rate is past what the client subscriber sustains; check the gateway's `droppedToClient` counter at shutdown. Orders are not lost — `clusterBackpressure` shows those being retried. |
| `REJECTED ... GATEWAY_UNAVAILABLE` | The gateway's cluster session is gone. Check `gateway.log`; the order never reached the engine. |
