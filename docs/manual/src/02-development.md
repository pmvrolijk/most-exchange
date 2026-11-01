# 2. Development deployment and operation

This section brings up the complete system — control plane, database, admin UI and one full shard —
on a single machine, and drives a trade through it. Everything in later sections assumes the
vocabulary established here.

::: warning A development stack is not a small production stack
The stack in `deploy/` runs **one** cluster node, a shared secret committed to the repository, a
fixed admin password, and dynamic MDC in place of multicast. Each of those is listed in 2.9 with
what to do instead. Do not carry them forward.
:::

## 2.1 Prerequisites

| | |
| --- | --- |
| JDK | 21 or later |
| Docker | with Compose v2 (`docker compose`, not `docker-compose`) |
| Node | 20+, only for the admin UI and for building this manual |
| Memory | ~6 GB free; the two Aeron tmpfs volumes are sized 1 GB each |

The JVM needs two module opens for Agrona's buffer intrinsics and the Aeron driver. They are already
set on every Gradle test and run task, and in every start script the build produces:

```
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
```

Without them the first `UnsafeBuffer` allocation throws `IllegalAccessError`.

## 2.2 Build

```sh
./gradlew build          # compile, generate SBE codecs, run the test suite
./gradlew installDist    # start scripts under */build/install, which the images copy
```

`./gradlew build` is npm-free by design; the Vue frontend in `web/` is a normal frontend project and
is built separately.

::: note Warnings are errors
Every module compiles with warnings as errors, `control` included. Do not disable it to get a build
through — a silently un-inlined function would break the engine's zero-allocation profile without
failing anything.
:::

## 2.3 The whole stack at once

```sh
./gradlew installDist
cd deploy
docker compose up -d           # roughly 40 seconds to a trading market
```

| | |
| --- | --- |
| Admin UI | <http://localhost:8081> — `admin` / `most-dev-password` |
| Control API | <http://localhost:8080> |
| Teardown | `docker compose down` — add `-v` to discard the books as well |

`docker compose up` also runs a one-shot `seed` container that authors, over the REST API, what the
processes booted from: shard 0 and its securities (imported from the very file they read),
participants 7, 8 and 20–23, the gateway `gw-0` and the control plane's own operator identity
`control` with the secrets the processes present, and the `equities` calendar. It publishes a
release, seeds reference prices and walks the session to `CONTINUOUS`, so `deploy/init/seed.sh`
doubles as a worked example of driving the exchange the supported way.

```
seed: creating shard 0
seed: importing the shard security file the processes booted from
seed: import said: {"shardId":0,"fingerprint":"3e04cf2b9902f08a","inserted":["AAPL","MSFT"],"updated":[]}
seed: participant 7: 201
...
seed: gateway gw-0 secret: 200
seed: gateway control secret: 200
seed: publishing release
seed: release registry fingerprint for shard 0: 4c9c24c10df3ff56
seed: creating the equities schedule
seed: seeding definition for security 1
seed: seeding definition for security 2
seed: session -> PRE_OPEN
seed: session -> OPEN_AUCTION
seed: session -> CONTINUOUS
seed: done -- shard 0 is CONTINUOUS with AAPL and MSFT at 100.00
```

::: warning Rebuilding the CLI image restarts the shard
`most` and `cluster-host` run the same tools image, and `docker compose up --build` does not rebuild
it — the `most` service sits in the `cli` profile, so it needs `docker compose --profile cli build`.
Once rebuilt, the next `docker compose up` **or** `docker compose run` recreates the cluster host,
and a cluster host recreated within about ten seconds of the old one stopping refuses to start
(`active mark file detected`). Stop the shard, wait out the window, then start it; use
`docker compose run --no-deps` for one-off containers.
:::

### What each container is

| Service | What it is |
| --- | --- |
| `cluster-host` | The shard's media driver, archive and consensus module. Holds the `shard0` network alias. |
| `engine` | The service container only. Attaches to `cluster-host` over shared memory. |
| `gateway` | Order entry, with its cluster identity `gw-0`, which it also enforces (4.3). |
| `market-data` | L1/L2/L3 and the snapshot feed. |
| `discovery` | The directory broadcast. |
| `control`, `postgres`, `web` | The control plane, its database, and the SPA behind nginx. |
| `control-driver` | The control plane's *own* media driver, so it reaches the shard over the network the way an adapter will rather than by mounting the engine's shared memory. |
| `most` | A run-only container for the CLI. Started on demand by `deploy/most`. |

::: term Media driver
The Aeron process that owns the shared-memory buffers and the sockets. Every process on a node talks
to one through a directory of memory-mapped files. It allocates, which is why it is never inside the
engine binary.
:::

## 2.4 Verifying the stack came up

Every process prints its geometry fingerprint at startup. They must all agree.

```
$ docker compose logs cluster-host engine gateway market-data discovery | grep -i fingerprint

cluster:         participants=/config/shard-0-participants.properties fingerprint=4c9c24c10df3ff56 gateways=[control, gw-0] reload=5000ms
matching-engine: shard=0 fingerprint=3e04cf2b9902f08a securities=[AAPL, MSFT] serviceId=0 clusterDir=/cluster/cluster participantRegistry=4c9c24c10df3ff56 reload=5000ms
gateway:         shard=0 fingerprint=3e04cf2b9902f08a securities=[AAPL, MSFT] in=aeron:udp?endpoint=0.0.0.0:20001:20 out=...
gateway:         identity=gw-0 registry=4c9c24c10df3ff56 reload=5000ms participants=[7, 8, 20, 21, 22, 23] cancelOnly=[] operator=true
market-data:     shard=0 fingerprint=3e04cf2b9902f08a securities=[AAPL, MSFT] in=aeron:ipc:12 ...
```

Two distinct fingerprints appear here and both are correct: `3e04cf2b9902f08a` is the **shard's
geometry**, and `4c9c24c10df3ff56` is the **participant registry's**. They are deliberately separate
values — rotating a gateway's secret is not a change of geometry, and must not invalidate a release.
The `seed` container prints the registry fingerprint of the release it published
(`seed: release registry fingerprint for shard 0: 4c9c24c10df3ff56`), which is the check that the
control plane's database and the file the shard booted from say the same thing. The gateway's
`identity=` line is what it will **enforce** (4.3): `gw-0` speaks for 7, 8 and the load generator's
20–23, and is the operator gateway the CLI's and the control plane's commands pass through.

The five processes should each print a readiness line:

```
cluster:         started, awaiting shutdown signal
matching-engine: started, awaiting shutdown signal
gateway:         started
market-data:     started
discovery:       started
```

## 2.5 The operator CLI

`most` is the operator command-line tool. Against the dev stack, use the `deploy/most` wrapper — it
runs the CLI in a container with its own media driver, on the outside of the shard, exactly where a
FIX adapter will sit.

Every subcommand begins by waiting for a **discovery broadcast**, because the routing table is the
only thing that maps a symbol to the shard and gateway serving it. A `most` command that appears to
hang is almost always waiting for that.

```
$ ./most securities --verbose

SYMBOL     ISIN           SHARD CCY    NAME
AAPL       US0378331005   0     USD    Apple Inc.
           id=1 tick=0.01 floor=0.00 levels=32768
           orders -> aeron:udp?endpoint=shard0:20001:20   reports <- aeron:udp?...control=shard0:20002...:21
MSFT       US5949181045   0     USD    Microsoft Corporation
           id=2 tick=0.01 floor=0.00 levels=32768
           orders -> aeron:udp?endpoint=shard0:20001:20   reports <- aeron:udp?...control=shard0:20002...:21

2 securities, universe version 9181280125937456696
```

The **universe version** is a 64-bit hash of the whole published directory. It is how a subscriber
detects that routing has changed underneath it.

<!-- pagebreak -->

## 2.6 Driving a trade

Rest an order, then cross it from a second participant. The dev stack's book is rarely empty — a
load run leaves thousands of orders resting — so these orders are priced inside the spread, where
they meet only each other.

```
$ ./most send --symbol MSFT --side sell --price 100.01 --qty 10 --clordid ORD-2001 --participant 7 --follow 2

sending sell 10 MSFT @ 100.01 clOrdId=ORD-2001
sent to shard 0 via aeron:udp?endpoint=shard0:20001
  NEW  MSFT  orderId=20032 clOrdId=ORD-2001  leaves 10 cum 0 of 10  seq 32

$ ./most send --symbol MSFT --side buy --price 100.01 --qty 4 --clordid ORD-2002 --participant 8 --follow 2

sending buy 4 MSFT @ 100.01 clOrdId=ORD-2002
sent to shard 0 via aeron:udp?endpoint=shard0:20001
  NEW  MSFT  orderId=20033 clOrdId=ORD-2002  leaves 4 cum 0 of 4  seq 24
  TRADE  MSFT  orderId=20033 clOrdId=ORD-2002  last 4 @ 100.01 cum 4 leaves 0  seq 25
```

`clOrdId` is the participant's own id, returned on every report exactly as it was sent (§5.4).
`orderId` is the `exchangeOrderId` the engine assigned, and `seq` the participant's report sequence:
each participant numbers separately, which is why participant 8's reports are at 24 while 7's are
at 32. Without `--clordid` the CLI uses the current time in milliseconds.

The maker's own `TRADE` report went to participant 7 after its `send` had stopped listening, and
took sequence 33. Cancelling the partially filled maker shows the next one, and both quantities the
engine states on every report:

```
$ ./most cancel --symbol MSFT --side sell --order-id 20032 --orig-clordid ORD-2001 --clordid CXL-2001 --participant 7 --follow 2

cancelling orderId=20032 on MSFT
sent to shard 0 via aeron:udp?endpoint=shard0:20001
  CANCELED  MSFT  orderId=20032 clOrdId=CXL-2001 origClOrdId=ORD-2001  leaves 0 cum 4 of 10  seq 34
```

A cancel names the order by `--order-id` and `--orig-clordid` together, and the engine cancels only
when both match an order of the same participant. Its report carries the cancel's own `clOrdId` and
the order's as `origClOrdId`; the CLI prints `origClOrdId` only when the two differ.

::: term cumQty and origQty
`cumQty` is the quantity filled so far and `origQty` the quantity originally submitted; both are
**stated by the engine on every execution report** and are never derived by a client. A terminal
report carries `leavesQty = 0` whether the order filled or was cancelled, so subtracting
`origQty - leavesQty` would report this cancelled order as fully filled. An `origQty` of `0` means
*unknown*, which reaches a client as an explicit `UNKNOWN` enrichment rather than as a number.
:::

A rejection is an execution report like any other, and takes a sequence number like any other:

```
$ ./most send --symbol AAPL --side buy --price 500.00 --qty 1 --clordid ORD-3001 --participant 7 --follow 2

sending buy 1 AAPL @ 500.00 clOrdId=ORD-3001
sent to shard 0 via aeron:udp?endpoint=shard0:20001
  REJECTED  AAPL  orderId=0 clOrdId=ORD-3001  reason PRICE_OUT_OF_BOUNDS  seq 35
```

Two mistakes never reach the wire. The CLI resolves a symbol against the directory first, and checks
a client order id against the same rule the gateway enforces:

```
$ ./most send --symbol TSLA --side buy --price 100.00 --qty 1 --clordid ORD-3002
most: unknown symbol 'TSLA' -- try `most securities`

$ ./most send --symbol AAPL --side buy --price 99.00 --qty 1 --clordid ORD#3003 --participant 7
most: not a valid clOrdId (1-20 of A-Z a-z 0-9 -_+;:./): "ORD#3003"
```

## 2.7 Watching the book

`most book` rebuilds order books from the L2 depth feed and its periodic snapshot, using the same
assembler any other consumer would use.

```
$ ./most book --symbol AAPL,MSFT --depth 5 --refresh 500

── AAPL (id 1) ────────────────────────────────────
       qty ords          bid │ ask        qty        ords
        89 (1)         99.98 │
        79 (1)         99.97 │
        65 (1)         99.95 │
        81 (1)         99.94 │
        28 (1)         99.89 │
  spread n/a   updates 387

── MSFT (id 2) ────────────────────────────────────
       qty ords          bid │ ask        qty        ords
        54 (1)         99.96 │ 100.02     98         (1)
       148 (2)         99.91 │ 100.04     17         (1)
        94 (1)         99.87 │ 100.12     31         (1)
       130 (3)         99.86 │ 100.14     29         (1)
       335 (9)         99.85 │ 100.17     74         (1)
  spread 0.06   last 100.01 x 4   updates 760
```

If it prints `waiting for the depth feed...` it has not yet received a snapshot for that security.
The snapshot feed cycles one security per interval, so a joiner waits at most one full pass.

::: term Synchronised
A depth subscriber is *synchronised* once it has installed a snapshot image and can apply increments
to it. Before that it publishes **no depth at all** rather than a partial ladder: "waiting for a
snapshot" and "no liquidity" are different answers and must never render the same way.
:::

## 2.8 The admin UI

The console is at <http://localhost:8081>. It is a Vue SPA over the control plane's REST API, served
same-origin — the session and CSRF cookies are same-origin mechanisms, so the SPA and the API sit
behind one host.

![The sign-in screen. Every route but this one requires an authenticated operator; the router guard exists so an operator sees a form rather than empty tables, and the server's 401 is what actually protects the data.](assets/ui-login.png)

**Status** is the first screen and answers a question no other screen does: what the exchange is
actually doing, as opposed to what the database says it should be. It is derived from the L3 book
event feed.

![Status. Observed phase per book from the L3 feed, the directory the shard is broadcasting, and the draft topology's readiness to publish.](assets/ui-status.png)

<!-- pagebreak -->

**Books** renders live depth, streamed over server-sent events, conflated to four images a second.
A book that is not synchronised draws no ladder at all.

![Books. Depth rebuilt by the same assembler the CLI uses, conflated on the feed thread so a browser is never on the market data critical path.](assets/ui-books.png)

**Operations** is the only screen that touches a running market.

![Operations. Session transitions, definition seeding, the expiry purge, a snapshot request, a book image request, the bulk cancel of one participant's orders, and halt recovery.](assets/ui-operations.png)

The remaining screens are covered where they are used: Shards, Securities, Participants, Gateways
and Releases in section 4; Schedules, Audit and Operators in section 5.

## 2.9 What is development-only

| Development stack | Production |
| --- | --- |
| One cluster node | Three or five members on separate machines (3.2) |
| `config/gateway-0.secret` and `config/control.secret` committed to the repository | Secrets issued by the control plane, delivered out of band (4.3, 4.10) |
| A fixed, published admin password | `CONTROL_ADMIN_PASSWORD` unset, so one is generated and logged once (4.9) |
| `CONTROL_COOKIE_SECURE=false` | `true`, behind TLS |
| Dynamic MDC for market data and the gateway's outbound leg | UDP multicast with an IGMP querier (3.7) |
| The scheduler disabled | Enabled — it is what bounds restart time by snapshotting at each close (5.6) |
| Hand-written `shard-0-securities.properties` | A published release, pointed at by a `current` symlink (4.2) |
| `ThreadingMode.SHARED`, no core isolation | Dedicated threading and isolated cores (3.4) |
| The gateway on the shard's own media driver, so IPC egress (`gateway.egressChannel=aeron:ipc`) is available — the highest-throughput setting (4.8) | A separate gateway tier on UDP egress (3.2), until a gateway can follow the leader |
| Engine, gateway and market data set to `backoff` (`*.idleStrategy`) | `busyspin`, the default, on isolated cores (3.4) |
| Duty-cycle counters on (`*.metrics`, `most cluster --duty`) | Optional; cheap enough to leave on (5.8) |

::: note One media driver is one network identity
In the development stack the five shard processes share a media driver that runs in the
`cluster-host` container, so **every UDP endpoint the shard exposes is bound there** and must name
`shard0`, never the individual process's container name. A channel naming `gateway:20002` fails with
`Cannot assign requested address`. This is not a Docker workaround; it is what "these five processes
are one node sharing `/dev/shm`" means once the processes are in separate containers.
:::

## 2.10 Running without Docker

Useful when iterating on one process. Five terminals, started in this order — the engine attaches to
a consensus module that must already exist.

```sh
# 1. cluster host: media driver + archive + consensus module
./tools/build/install/tools/bin/most cluster --dir build/cluster-host \
    --participants config/shard-0-participants.properties
# wait for: cluster: started, awaiting shutdown signal

# 2. engine — the cluster service container only
./engine/build/install/engine/bin/engine config/engine.properties
# wait for: matching-engine: started, awaiting shutdown signal

# 3. gateway
./gateway/build/install/gateway/bin/gateway config/gateway.properties

# 4. market data
./market-data/build/install/market-data/bin/market-data config/market-data.properties

# 5. discovery
./discovery/build/install/discovery/bin/discovery config/discovery.properties
```

The control plane needs Postgres and one environment variable:

```sh
docker run -d --name most-control-db -p 5432:5432 \
  -e POSTGRES_DB=most_control -e POSTGRES_USER=most -e POSTGRES_PASSWORD=most postgres:16-alpine

./gradlew :control:installDist
CONTROL_ADMIN_PASSWORD=... ./control/build/install/control/bin/control
```

And the frontend, which proxies `/api` to the control plane so both are one origin:

```sh
cd web && npm install && npm run dev     # http://localhost:5173
```

## 2.11 Automated end-to-end checks

These scripts exercise things unit tests cannot. Run the first two after changing anything that
touches the wire or process lifecycle.

| Script | What it proves |
| --- | --- |
| `e2e/run-e2e.sh` | Every process against a real single-node cluster: a trade driven through the CLI, then a short `most load` to confirm the harness still correlates reports to orders. Takes each binary path from an environment variable (`ENGINE`, `GATEWAY`, `MARKETDATA`, `DISCOVERY`) so the same run can drive native images. |
| `e2e/run-restart.sh` | That state survives a restart. Rests orders, snapshots, stops the whole node, and restarts it on the same security file; on one with a security removed while it holds orders (must refuse); on one with an emptied security removed (must start); and on one with a security added. Also places an order through one gateway and cancels it through another. |
| `e2e/run-upgrade.sh` | That a version 7 shard upgrades to schema version 8: orders rest on version 7, `most cluster shutdown`, then version 8 restores them, a cancel names an old integer id as its decimal string, `most cluster shutdown` on version 8 exits both processes by itself, and a version 7 log tail with no snapshot at its end is refused. Takes a version 7 build as `OLD`. |
| `e2e/run-attribution.sh` | Splits a client round trip into gateway, engine and everything else, writing `.hgrm` histograms that are meant to be diffed across changes. |
| `e2e/run-epsilon-soak.sh` | Runs the Epsilon-GC binary against a real cluster and measures the allocation slope across two runs. |

`run-e2e.sh` wipes everything and starts fresh, so it says nothing about whether state survives —
that is `run-restart.sh` and only `run-restart.sh`.

## 2.12 Shutting down

```sh
cd deploy
docker compose exec -T cluster-host most cluster shutdown --dir /cluster
                           # snapshot, then cluster-host and engine exit 0 by themselves
docker compose down        # keeps the books: cluster-data is a durable named volume
docker compose down -v     # discards the archive, the cluster directory and the books
```

The snapshot is what makes the next start quick. Without one, `docker compose down` still keeps
the books, but the next start replays the log from genesis to rebuild them (5.6).

::: warning A node cannot restart immediately after the previous one stopped
Aeron's archive and cluster **mark files** carry a liveness timestamp, and a new process refuses to
start until it ages out — roughly ten seconds, even after a clean shutdown. An automatic restart
policy must wait longer than that or it will fail on `active mark file detected` and back off.
:::
