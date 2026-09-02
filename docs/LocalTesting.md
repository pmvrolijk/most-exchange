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

## 3. Process configuration

Four small files, each pointing at the shared security list.

`$RUN/engine.properties`

```properties
engine.securitiesFile=${RUN}/shard-0-securities.properties
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
$MOST cluster --dir "$RUN/cluster-host" --aeron-dir "$RUN/aeron" > "$L/cluster.log" 2>&1 &

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
fresh, `rm -rf "$RUN"` and repeat from step 2. The cluster host also deletes its own directories on
start unless you pass `--keep`, so a stale cluster state does not usually survive a restart on its
own.

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
| Fingerprints differ between processes | They are reading different security files. |
| `most send` prints no execution report | Increase `--follow`; or the gateway lost its cluster session — check `gateway.log`. |
| `most load` reports every order `BOOK_CAPACITY` | `maxOrders` is too small for the rate, or the band is too wide to cross so nothing ever leaves the book. |
| `most load` reports many `unanswered` | The rate is past what the client subscriber sustains; check the gateway's `droppedToClient` counter at shutdown. Orders are not lost — `clusterBackpressure` shows those being retried. |
| `REJECTED ... GATEWAY_UNAVAILABLE` | The gateway's cluster session is gone. Check `gateway.log`; the order never reached the engine. |
