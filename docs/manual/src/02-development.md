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

`docker compose up` also runs a one-shot `seed` container that creates shard 0 in the control plane,
imports the same security file the processes booted from, publishes a release, seeds reference
prices and walks the session to `CONTINUOUS`. It does all of that over the REST API, so
`deploy/init/seed.sh` doubles as a worked example of driving the exchange the supported way.

```
seed: creating shard 0
seed: importing the shard security file the processes booted from
seed: import said: {"shardId":0,"fingerprint":"3e04cf2b9902f08a","inserted":[],"updated":["AAPL","MSFT"]}
seed: publishing release
seed: seeding definition for security 1
seed: seeding definition for security 2
seed: session -> PRE_OPEN
seed: session -> OPEN_AUCTION
seed: session -> CONTINUOUS
seed: done -- shard 0 is CONTINUOUS with AAPL and MSFT at 100.00
```

### What each container is

| Service | What it is |
| --- | --- |
| `cluster-host` | The shard's media driver, archive and consensus module. Holds the `shard0` network alias. |
| `engine` | The service container only. Attaches to `cluster-host` over shared memory. |
| `gateway` | Order entry, with its cluster identity `gw-0`. |
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

cluster:        participants=/config/shard-0-participants.properties fingerprint=cb7d8bceec5e98b4 gateways=[gw-0] reload=5000ms
matching-engine: shard=0 fingerprint=3e04cf2b9902f08a securities=[AAPL, MSFT] serviceId=0 participantRegistry=cb7d8bceec5e98b4
gateway:        shard=0 fingerprint=3e04cf2b9902f08a securities=[AAPL, MSFT] in=aeron:udp?endpoint=0.0.0.0:20001:20
market-data:    shard=0 fingerprint=3e04cf2b9902f08a securities=[AAPL, MSFT] in=aeron:ipc:12
```

Two distinct fingerprints appear here and both are correct: `3e04cf2b9902f08a` is the **shard's
geometry**, and `cb7d8bceec5e98b4` is the **participant registry's**. They are deliberately separate
values — rotating a gateway's secret is not a change of geometry, and must not invalidate a release.

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

Rest an order, then cross it from a second participant.

```
$ ./most send --symbol MSFT --side sell --price 100.50 --qty 10 --clordid 2001 --participant 7 --follow 2

sending sell 10 MSFT @ 100.50 clOrdId=2001
sent to shard 0 via aeron:udp?endpoint=shard0:20001
  NEW  MSFT  orderId=11 clOrdId=2001  leaves 10 cum 0 of 10

$ ./most send --symbol MSFT --side buy --price 100.50 --qty 4 --clordid 2002 --participant 8 --follow 2

sending buy 4 MSFT @ 100.50 clOrdId=2002
sent to shard 0 via aeron:udp?endpoint=shard0:20001
  NEW    MSFT  orderId=12 clOrdId=2002  leaves 4 cum 0 of 4
  TRADE  MSFT  orderId=12 clOrdId=2002  last 4 @ 100.50 cum 4 leaves 0
```

`orderId` in the `NEW` report is the `exchangeOrderId` and is what a cancel refers to. Cancelling the
partially filled maker shows both quantities the engine states on every report:

```
$ ./most cancel --symbol MSFT --side sell --order-id 11 --orig-clordid 2001 --participant 7 --follow 2

cancelling orderId=11 on MSFT
  CANCELED  MSFT  orderId=11  leaves 0 cum 4 of 10
```

::: term cumQty and origQty
`cumQty` is the quantity filled so far and `origQty` the quantity originally submitted; both are
**stated by the engine on every execution report** and are never derived by a client. A terminal
report carries `leavesQty = 0` whether the order filled or was cancelled, so subtracting
`origQty - leavesQty` would report this cancelled order as fully filled. An `origQty` of `0` means
*unknown*, which reaches a client as an explicit `UNKNOWN` enrichment rather than as a number.
:::

A rejection is an execution report like any other:

```
$ ./most send --symbol AAPL --side buy --price 500.00 --qty 1 --clordid 3001 --participant 7 --follow 2

  REJECTED  AAPL  orderId=0 clOrdId=3001  reason PRICE_OUT_OF_BOUNDS
```

An unknown symbol never reaches the wire — the CLI resolves symbols against the directory first:

```
$ ./most send --symbol TSLA --side buy --price 100.00 --qty 1 --clordid 3002
most: unknown symbol 'TSLA' -- try `most securities`
```

## 2.7 Watching the book

`most book` rebuilds order books from the L2 depth feed and its periodic snapshot, using the same
assembler any other consumer would use.

```
$ ./most book --symbol AAPL,MSFT --depth 5 --refresh 500

── AAPL (id 1) ─────────────────────────────
       qty ords          bid │ ask        qty        ords
       128    2       100.64 │ 101.00      40           1
        54    1       100.59 │ 101.10      40           1
        50    1       100.55 │ 101.20      40           1
       103    2       100.51 │ 101.67     103           2
        43    1       100.50 │ 101.68       1           1
  spread 0.36   last 101.61 x 29   updates 41

── MSFT (id 2) ─────────────────────────────
       qty ords          bid │ ask        qty        ords
                             │ 100.50       6           1
  spread n/a   last 100.50 x 4   updates 1
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

![Operations. Session transitions, definition seeding, the expiry purge, a snapshot request, a book image request, and halt recovery.](assets/ui-operations.png)

The remaining screens are covered where they are used: Shards, Securities, Participants, Gateways
and Releases in section 4; Schedules, Audit and Operators in section 5.

## 2.9 What is development-only

| Development stack | Production |
| --- | --- |
| One cluster node | Three or five members on separate machines (3.2) |
| `config/gateway-0.secret` committed to the repository | A secret issued by the control plane, delivered out of band (4.3, 4.10) |
| A fixed, published admin password | `CONTROL_ADMIN_PASSWORD` unset, so one is generated and logged once (4.9) |
| `CONTROL_COOKIE_SECURE=false` | `true`, behind TLS |
| Dynamic MDC for market data and the gateway's outbound leg | UDP multicast with an IGMP querier (3.7) |
| The scheduler disabled | Enabled — it is what bounds restart time by snapshotting at each close (5.6) |
| Hand-written `shard-0-securities.properties` | A published release, pointed at by a `current` symlink (4.2) |
| `ThreadingMode.SHARED`, no core isolation | Dedicated threading and isolated cores (3.4) |

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

Three scripts exercise things unit tests cannot. Run the first two after changing anything that
touches the wire or process lifecycle.

| Script | What it proves |
| --- | --- |
| `e2e/run-e2e.sh` | Every process against a real single-node cluster: a trade driven through the CLI, then a short `most load` to confirm the harness still correlates reports to orders. Takes each binary path from an environment variable (`ENGINE`, `GATEWAY`, `MARKETDATA`, `DISCOVERY`) so the same run can drive native images. |
| `e2e/run-restart.sh` | That state survives a restart. Rests orders, snapshots, stops the whole node, and restarts it on the same security file; on one with a security removed while it holds orders (must refuse); on one with an emptied security removed (must start); and on one with a security added. Also places an order through one gateway and cancels it through another. |
| `e2e/run-attribution.sh` | Splits a client round trip into gateway, engine and everything else, writing `.hgrm` histograms that are meant to be diffed across changes. |
| `e2e/run-epsilon-soak.sh` | Runs the Epsilon-GC binary against a real cluster and measures the allocation slope across two runs. |

`run-e2e.sh` wipes everything and starts fresh, so it says nothing about whether state survives —
that is `run-restart.sh` and only `run-restart.sh`.

## 2.12 Shutting down

```sh
cd deploy
docker compose down        # keeps the books: cluster-data is a durable named volume
docker compose down -v     # discards the archive, the cluster directory and the books
```

::: warning A node cannot restart immediately after the previous one stopped
Aeron's archive and cluster **mark files** carry a liveness timestamp, and a new process refuses to
start until it ages out — roughly ten seconds, even after a clean shutdown. An automatic restart
policy must wait longer than that or it will fail on `active mark file detected` and back off.
:::
