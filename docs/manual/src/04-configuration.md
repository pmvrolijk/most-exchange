# 4. Configuration in full

This section is a reference. Every key each process reads, every artifact a release contains, and
every environment variable the control plane honours.

## 4.1 How a process is configured

Each of the four core processes takes **one positional argument**: the path to a properties file.

```sh
engine       /etc/most/engine.properties
gateway      /etc/most/gateway.properties
market-data  /etc/most/market-data.properties
discovery    /etc/most/discovery.properties
```

After the file is read, **JVM system properties in the same namespace override individual keys**.
The prefix is the process's own: `engine.`, `gateway.`, `md.`, `discovery.`. This is how a
deployment overrides one value without templating a whole file:

```sh
engine -Dengine.metrics=true /etc/most/engine.properties
```

The cluster host is different: it is a subcommand of the operator CLI and takes flags, not a file.
The control plane is a Spring Boot application and takes environment variables.

::: warning A missing required key stops the process, and that is the design
`engine.securitiesFile` and its equivalents have no default. Nothing in the shard security file has
a default either — a wrong tick size misprices every order in silence, and a missing ISIN would be
published to every downstream adapter.
:::

## 4.2 The shard security file

The single most important artifact. It is read at boot by the engine, the gateway, market-data and
discovery **alike**, so a shard has one definition and one fingerprint rather than four lists kept in
step by hand. In production it is published by the control plane; the path each process is given
should be a `current` symlink into a release directory.

```properties
shard.id=0
shard.securities=1,2

security.1.symbol=AAPL
security.1.isin=US0378331005
security.1.name=Apple Inc.
security.1.currency=USD
security.1.priceFloor=0
security.1.tickSize=1000000
security.1.levelCount=32768
security.1.maxOrders=1000000
```

| Key | Type | Constraint |
| --- | --- | --- |
| `shard.id` | int | Non-negative |
| `shard.securities` | int list | 1 to 10 ids, comma-separated, no duplicates |
| `security.N.symbol` | string | Non-blank, at most 16 characters — the wire field's length |
| `security.N.isin` | string | 12 characters, **check-digit validated at boot** |
| `security.N.name` | string | At most 48 characters |
| `security.N.currency` | string | Exactly 3 characters |
| `security.N.priceFloor` | int64 | The price of ladder level 0, fixed point, 8 decimals |
| `security.N.tickSize` | int64 | Positive. `1000000` is 0.01 |
| `security.N.levelCount` | int | Positive. Ladder levels allocated per side |
| `security.N.maxOrders` | int | Positive. Order pool high-water mark; up to 1,000,000 |

::: term Ladder geometry
`priceFloor`, `tickSize` and `levelCount` define a flat array of price levels per side, indexed by
ticks above the floor. The ladder's range is `priceFloor` to `priceFloor + tickSize × levelCount`.
It must be configured **strictly wider than the static circuit-breaker band**, so band rejection
always fires before the ladder can overflow.
:::

::: warning Geometry changes are enforced against the snapshot
On restart the engine reconciles this file against the snapshot it is restoring and **refuses to
start** rather than lose state: a security removed while it holds resting orders, a changed
`priceFloor`, `tickSize`, `levelCount` or `maxOrders`, an order that falls outside the new ladder, or
resting-order counts that do not add up. A refusal is the system working. The two legitimate cases
are quiet — an *empty* book leaving the shard, and a new security joining — and each is logged.

**Apply a geometry change from `most cluster shutdown`, never after a SIGTERM.** At the start of
every leadership term the leader writes its configuration into the log, and every engine checks it.
The log written since the last snapshot was written under the old file, and an engine that replays
it under a new one refuses with `matching-engine: refused to go on` and exits non-zero. `most cluster
shutdown` takes a snapshot at the end of the log, so there is nothing left to replay. Change the file
on every node at once; a rolling geometry change is refused by construction.
:::

Reference prices and collar widths are deliberately **not** in this file. They change during a
session and every node must apply them at the same log position, so they arrive as
`SecurityDefinition` commands through the replicated log (5.3).

## 4.3 The participant registry

Who may connect, and what each identity may do. Read by **three** processes for three reasons: the
consensus module authenticates every cluster session against it and authorises snapshot requests
from it, the engine resolves each session's principal into the participants it routes reports to,
and the **gateway enforces it** on every client message.

```properties
shard.id=0
registry.gateways=control,gw-0,gw-1

gateway.gw-0.secret=3b2c637a03bcb9c167c9bd5f84de22f6bbdc18169d36526cafc164cd68725367
gateway.gw-0.participants=7,8,20,21,22,23
gateway.gw-0.cancelOnly=9
gateway.gw-0.operator=true

gateway.gw-1.secret=<sha-256 hex>
gateway.gw-1.participants=7,14

gateway.control.secret=310c47787a741d2f320f6e921645a52f305b5446acc38a9f6257b2a39cfd94a8
gateway.control.operator=true

participant.7.primary=gw-0
```

| Key | Constraint |
| --- | --- |
| `shard.id` | Must match the shard of the process reading it, or the process refuses to start |
| `registry.gateways` | At least one id, comma-separated |
| `gateway.<id>.secret` | The **SHA-256 hex digest** of the shared secret, 64 lowercase hex characters |
| `gateway.<id>.participants` | Participants that may **place and cancel** through this gateway. Required unless the gateway is an operator |
| `gateway.<id>.cancelOnly` | Optional. Participants that may **cancel but not place** — revocation made graceful. Not also in `participants`. An operator can withdraw what such a participant leaves resting with `most cancel-all` (5.3), once this is published |
| `gateway.<id>.operator` | Optional, `true` or `false` (default). May send operator commands, and may request a snapshot through consensus. Any other value is refused |
| `participant.<id>.primary` | Required when a participant is listed on **more than one** gateway, and refused otherwise. Names the gateway it is bound to while both are connected; that gateway must list it |

Gateway ids are at most 64 characters from a restricted alphabet. The file holds the digest, never
the secret; the secret itself is delivered only to the process that presents it. A registry that
uses none of the optional keys renders and fingerprints exactly as it did before they existed.

::: term Participant binding
A gateway presents `gatewayId:secret` as its cluster credentials. The consensus module verifies them
and stamps the gateway id on the session as its **encoded principal**. Aeron carries that principal
in the session-open event through the replicated log, so every node derives the identical
participant-to-session map without any snapshot state of its own. A session binds each participant
its gateway lists **if it is that participant's primary or nobody live holds it**, and a closing
session's participants move to another open gateway that lists them, primary first. This is what
makes a participant's fills deliverable when it has said nothing since its gateway last connected.
:::

::: term Operator identity
An entry with `operator=true`. With no participants at all — the only kind of entry allowed to list
nobody — it is how the **control plane and the CLI** are named to the cluster. A gateway may be an
operator as well as speak for participants; the dev stack's `gw-0` is both.
:::

### What the gateway enforces

`UNAUTHORIZED_PARTICIPANT` is raised by the **gateway**, locally, before anything reaches the
cluster:

- an order for a participant not in its `participants` — a `cancelOnly` participant included;
- a cancel for a participant in neither list. Checked, not optional: the engine's own cancel check is
  that the participant matches the order's, which only means something once the gateway has
  established the participant id is one this endpoint may use;
- an operator command — a session transition, purge, definition or image request, or any message
  it does not recognise — through a gateway that is **not** an operator is consumed and counted
  `refusedCommands`. There is no client report for it, so the counter is the only trace.

The client-to-gateway leg is not authenticated, so what this establishes is that *whoever can reach
this gateway's endpoint may act for these participants*. Authenticating an end client is the job of
the FIX and session gateways upstream; a member's connection lands on the gateway assigned to it.

The engine never rejects on the registry — a refusal decided from a file each node reads on its own
schedule would diverge the nodes. It **counts** an order or cancel from a gateway that does not list
the participant (`undeclaredParticipantMessages`), as defence against a misconfigured gateway.

::: note A node with a registry authenticates everything or nothing
A gateway with a bad secret fails to connect; it is never quietly downgraded to an anonymous session.
And a client presenting **no** credentials is refused too: an anonymous session reaches the engine
without passing any gateway, so it could act for any participant and send any operator command. The
control plane (4.9) and the CLI (`--identity`, 5.6) therefore hold operator identities. A node
started without a registry accepts everyone, as before the registry existed.
:::

### Reloading while running

The consensus module, the engine **and the gateway** re-read this file while running. They compare
content by fingerprint rather than mtime — a release is published to a new directory and put in
force by moving a symlink, so mtime says nothing — and swap an immutable registry behind a volatile
reference. The gateway prints its own grants on every swap (`gateway: now participants=[…]
cancelOnly=[…] operator=…`), which is the line to wait for when revoking someone.

A file that cannot be parsed, or one for another shard, is **reported and ignored**; the registry in
force keeps applying. Standing down on a bad file would turn a typo into a shard that authenticates
nobody. Watch `registryReloadFailures` rather than assuming a silent success.

## 4.4 Engine

```properties
engine.securitiesFile=/etc/most/current/shard-0-securities.properties
engine.participantRegistry=/etc/most/current/shard-0-participants.properties
engine.aeronDir=/dev/shm/aeron-most
engine.clusterDir=/var/lib/most/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
```

| Key | Default | Meaning |
| --- | --- | --- |
| `engine.securitiesFile` | *required* | The shard security file (4.2) |
| `engine.participantRegistry` | none | The participant registry (4.3), for routing reports and counting `undeclaredParticipantMessages`. Omit and routes are learned from traffic alone |
| `engine.participantRegistry.reloadMs` | `5000` | Registry poll interval; `0` disables reloading |
| `engine.aeronDir` | driver default | Must be the media driver this node's processes share |
| `engine.clusterDir` | `cluster` | The consensus module's cluster directory, written by the cluster host |
| `engine.serviceId` | `0` | The clustered service id |
| `engine.bookEvent.channel` | `aeron:ipc` | Where book events are published. Keep it IPC: market-data is on this node |
| `engine.bookEvent.streamId` | `12` | |
| `engine.auction.maxPasses` | `64` | Safety valve on the uncross fixed-point loop, not part of the algorithm. Can change an uncross result, so it is the **engine fingerprint** and must be identical on every node |
| `engine.backpressure.alertThreshold` | `1000000` | Consecutive back-pressured publications before alerting |
| `engine.metrics` | `false` | Hot-path timing; two clock reads per message. Also publishes the service thread's duty cycle as the `duty-ns: engine service` counter (5.8) |
| `engine.metrics.stages` | `false` | Adds the admit/match/settle partition of a new order; two more clock reads |
| `engine.metrics.file` | none | Where percentile distributions are written at shutdown |
| `engine.idleStrategy` | `busyspin` | How the service thread waits for work: `busyspin`, `backoff`, `yielding`, `sleeping` or `sleeping:<µs>`. Leave the default on an isolated core (3.4) |

::: note Metrics are node-local and deliberately outside the fingerprint
The engine reads `System.nanoTime()` to record these histograms, which the determinism rules
otherwise prohibit. The rule is that time must not influence **replicated state**, not that it may
not be observed: the histograms are write-only — never read by a branch, never snapshotted, never on
a feed. Enabling metrics on one node and not another must be incapable of changing the log, the
books or a snapshot. That is why `engine.metrics*` is excluded from the fingerprint, and it is the
test any probe added later must pass. `engine.idleStrategy` is outside it too: how a node waits for
work cannot change what it computes, so two members may differ.
:::

## 4.5 Gateway

```properties
gateway.securitiesFile=/etc/most/current/shard-0-securities.properties
gateway.participantRegistry=/etc/most/current/shard-0-participants.properties
gateway.gatewayId=gw-a
gateway.credentialTokenFile=/etc/most/gw-a.secret

gateway.aeronDir=/dev/shm/aeron-gw-a
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=shard0-a:20110,1=shard0-b:20110,2=shard0-c:20110
gateway.egressChannel=aeron:udp?endpoint=gw-a-host:0

gateway.client.inbound.channel=aeron:udp?endpoint=0.0.0.0:20001
gateway.client.inbound.streamId=20
gateway.client.outbound.channel=aeron:udp?endpoint=239.10.0.5:20002
gateway.client.outbound.streamId=21
```

| Key | Default | Meaning |
| --- | --- | --- |
| `gateway.securitiesFile` | *required* | The shard security file. Orders for anything else are rejected |
| `gateway.participantRegistry` | none | The registry this gateway **enforces** (4.3), and checks its own id against at boot |
| `gateway.participantRegistry.reloadMs` | `5000` | Registry poll interval; `0` disables reloading |
| `gateway.gatewayId` | none | This gateway's registry id. Unset means an anonymous connection that enforces nothing — and that a node with a registry refuses |
| `gateway.credentialToken` | none | The shared secret as a literal. Prefer the file form |
| `gateway.credentialTokenFile` | none | The shared secret from a file; trailing whitespace trimmed |
| `gateway.aeronDir` | driver default | This gateway's own media driver |
| `gateway.ingressChannel` | `aeron:udp` | Cluster ingress |
| `gateway.ingressEndpoints` | none | Every member's ingress endpoint, so the client can find the leader |
| `gateway.egressChannel` | `aeron:udp?endpoint=localhost:9020` | Cluster egress. The host must be one the cluster can **send to** — the name of the machine whose media driver this gateway uses — never `0.0.0.0`. **`aeron:ipc`** when `gateway.aeronDir` is the *leader's own* media driver: the highest-throughput setting (4.8, "The egress channel") |
| `gateway.client.inbound.channel` | `aeron:ipc` | Where adapters **publish orders**. Must be unique per gateway |
| `gateway.client.inbound.streamId` | `20` | |
| `gateway.client.outbound.channel` | `aeron:ipc` | Where the gateway **publishes execution reports** |
| `gateway.client.outbound.streamId` | `21` | |
| `gateway.metrics` | `false` | Hot-path timing on both legs, and the poll thread's duty cycle as the `duty-ns: gateway <id>` counter (5.8) |
| `gateway.metrics.file` | none | Percentile distributions at shutdown |
| `gateway.idleStrategy` | `busyspin` | How the poll thread waits for work; same values as `engine.idleStrategy` |

`gateway.gatewayId` and one of the two credential keys must be set **together**: an id with no secret
cannot authenticate, and a secret with no id has nothing to authenticate as. The process refuses to
start otherwise, as it does when the id is absent from the registry or the registry is for another
shard.

::: note The gateway has no storage configuration
It holds no per-order state. `origQty` and `cumQty` are stated by the engine on every execution
report, so a gateway can be restarted or replaced at will and several can serve one shard. If you are
migrating a configuration that sets `gateway.journalFile` or `gateway.journalSlots`, delete those
keys; they no longer exist.
:::

## 4.6 Market data

```properties
md.securitiesFile=/etc/most/current/shard-0-securities.properties
md.aeronDir=/dev/shm/aeron-most
md.bookEvent.channel=aeron:ipc
md.bookEvent.streamId=12
```

| Key | Default | Meaning |
| --- | --- | --- |
| `md.securitiesFile` | *required* | Depth geometry comes from here, so ladder level N in the engine is depth level N here by construction |
| `md.aeronDir` | driver default | The node's shared media driver |
| `md.bookEvent.channel` / `.streamId` | `aeron:ipc` / `12` | In from the engine |
| `md.l1.channel` / `.streamId` | `aeron:udp?endpoint=239.10.1.1:40001` / `1` | Top of book and last trade |
| `md.l2.channel` / `.streamId` | `aeron:udp?endpoint=239.10.1.2:40002` / `2` | Aggregated depth increments |
| `md.l3.channel` / `.streamId` | `aeron:udp?endpoint=239.10.1.3:40003` / `3` | Book events forwarded verbatim |
| `md.snapshot.channel` / `.streamId` | `aeron:udp?endpoint=239.10.1.4:40004` / `4` | The L2 recovery feed |
| `md.snapshot.cycleMs` | `1000` | How long a full pass over the shard's books takes: the cycle is sliced one security per slice, so a joining subscriber waits one cycle however many securities there are |
| `md.metrics` | `false` | Publishes the poll thread's duty cycle as the `duty-ns: market-data` counter (5.8). Market data's only metric |
| `md.idleStrategy` | `busyspin` | How the poll thread waits for work; same values as `engine.idleStrategy`. At ~13% busy at full load, the first candidate for `backoff` where cores are short |

::: term L1, L2, L3
**L1** is top of book plus last trade. **L2** is aggregated depth per price level, published as
increments carrying absolute per-level quantities. **L3** is the engine's raw book event stream
forwarded byte for byte — per-order detail, and the **only** feed on which a volatility halt is
visible. A depth subscriber cannot distinguish a halt from a scheduled close, which is why the
control plane subscribes to L3.
:::

## 4.7 Discovery

```properties
discovery.shards=0
discovery.shard.0.securitiesFile=/etc/most/current/shard-0-securities.properties
discovery.shard.0.orderEntryChannel=aeron:udp?endpoint=gw-a:20001
discovery.shard.0.orderEntryStreamId=20
discovery.shard.0.executionReportChannel=aeron:udp?endpoint=239.10.0.5:20002
discovery.shard.0.executionReportStreamId=21

discovery.aeronDir=/dev/shm/aeron-most
discovery.channel=aeron:udp?endpoint=239.10.0.1:40000
discovery.streamId=100
discovery.intervalMs=2000
```

| Key | Default | Meaning |
| --- | --- | --- |
| `discovery.shards` | *required* | Shard ids to publish |
| `discovery.shard.N.securitiesFile` | *required* | The shard's own security file, so the directory and the running engine cannot describe different geometry |
| `discovery.shard.N.orderEntryChannel` / `StreamId` | *required* | The **gateway's** client inbound endpoint |
| `discovery.shard.N.executionReportChannel` / `StreamId` | *required* | The gateway's client outbound endpoint, in **subscriber** form |
| `discovery.aeronDir` | driver default | |
| `discovery.channel` / `.streamId` | `aeron:udp?endpoint=239.10.0.1:40000` / `100` | The broadcast |
| `discovery.intervalMs` | `5000` | Broadcast cycle |

::: warning The directory publishes gateway endpoints, never cluster ingress
An adapter that connected to the cluster directly would bypass the gateway's `securityId` validation
and the participant binding that decides where a maker's fills go. Channels are limited to 128
characters, which is the wire field's length.
:::

Discovery enforces **one shard per security** across the whole universe and refuses to start
otherwise.

## 4.8 The cluster host

`most cluster` takes flags rather than a properties file.

| Flag | Default | Meaning |
| --- | --- | --- |
| `--dir DIR` | `build/cluster-host` | Base directory; the three below default beneath it |
| `--aeron-dir DIR` | `<dir>/driver` | Media driver directory |
| `--archive-dir DIR` | `<dir>/archive` | Archive directory — **durable** |
| `--cluster-dir DIR` | `<dir>/cluster` | Consensus module directory — **durable** |
| `--host HOST` | `localhost` | Host name used to build default endpoints |
| `--members STRING` | single-node default | Aeron member string (3.6) |
| `--participants FILE` | none | The participant registry (4.3). With it, every cluster session must authenticate — anonymous ones are refused — and a snapshot request through consensus is granted only to an `operator=true` identity. Without it, anyone connects and anyone may snapshot |
| `--participants-reload-ms N` | `5000` | Registry poll interval; `0` disables |
| `--driver-threading MODE` | `SHARED` | Media driver threads: `SHARED`, `SHARED_NETWORK` or `DEDICATED`. **The shard's first throughput ceiling**; the gateway's egress channel is the second (see below) |
| `--archive-threading MODE` | `SHARED` | Archive threads: `SHARED` or `DEDICATED`. Leave it alone unless the cores are isolated |
| `--ingress-term-length LEN` | `64k` | Cluster ingress term length, e.g. `16m`. Buys latency headroom at the edge, not capacity |
| `--duty` | off | Publish a duty-cycle counter for every thread this process runs — the driver's (per threading mode), the archive's and the consensus module's (5.8). Measures around Aeron's idle strategies without changing them |
| `--fresh` | off | **Delete the archive and cluster directories on start** |
| `--keep` | on | Persist them. Contradicts `--fresh` |

### What caps a shard's throughput

Two settings decide what a shard can carry, and they bind in order: the media driver's threading
mode, then the gateway's cluster egress channel. Everything below was measured on one node, with ten
securities, and gives the aggregate rate across all of them.

**Driver threading.** `SHARED` puts the media driver's conductor, sender and receiver on **one**
thread. At ten securities that thread moves ~190 MB/s of loopback traffic and is what caps the shard
first. Measured on a 14-core development machine:

| `--driver-threading` | Sustained | p50 at 350,000/s |
| --- | --- | --- |
| `SHARED` (default) | ~350,000 orders/s | 6,410 µs |
| `DEDICATED` | **~550,000 orders/s** | **79 µs** |

1.6x the throughput and 81x the median at the edge. **The default is `SHARED` on purpose:**
`DEDICATED` busy-spins three threads, which starves a development machine that is already running
five JVMs, and it needs the isolated cores of 3.4 to be worth having. So:

- **Development** — leave both defaults alone. 2.3 and the e2e scripts are correctness checks, not
  capacity ones.
- **Performance measurement and production** — `--driver-threading DEDICATED`, with the core
  allocation of 3.4 in place.

**The egress channel.** With dedicated driver threads, the next cap is the stream that carries
execution reports back to the gateway. Each order produces about 2.2 of them, ~350 bytes of egress
against ~128 bytes of ingress. Over UDP, Aeron's sender puts out **at most one datagram of the
channel's MTU (1,408 bytes by default) per publication per pass**. So egress is limited by datagrams
per second, not by bytes. When it overflows, the engine's egress publication fills and the engine
waits on it, and waiting reads as 100% busy (5.8). Measured on the same machine, same day:

| `gateway.egressChannel` | Sustained | p50 at 650,000/s |
| --- | --- | --- |
| `aeron:udp?endpoint=host:0` (1,408 B datagrams, the default shape) | ~500,000 orders/s | 441 ms (a queue) |
| `aeron:udp?endpoint=host:0\|mtu=8192` (8 KB datagrams) | at least 650,000/s; higher not measured | 82 µs |
| **`aeron:ipc`** | **~1,500,000 orders/s**; 1,000,000/s held at an 83 µs median | 66–72 µs |

**`aeron:ipc` is the highest-throughput setting there is**, and above ~1,500,000/s it's the engine's
own thread that fills (5.7). It has one requirement: **the gateway must use the leader's media
driver**, with `gateway.aeronDir` pointing at that node's driver directory. IPC does not cross from one
media driver to another. The development stack and the e2e scripts already run their gateway on the
shard's driver. So:

- **A single-node shard** (development, performance measurement): `gateway.egressChannel=aeron:ipc`.
  It always holds, because the one node always leads. The scripts take it as
  `EGRESS_CHANNEL=aeron:ipc`.
- **A multi-node cluster**: IPC egress holds only while the gateway's node leads. After a failover the
  new leader cannot reach it, and that gateway's reports stop. The separate gateway tier of 3.2 uses
  UDP egress. There, fewer and larger datagrams (`|mtu=8192`) are the lever that removed the same step
  on loopback. On a real network an MTU above the link's needs jumbo frames end to end on the trading
  VLAN, and that combination has not been measured.

::: warning A 100% engine is not always a busy one
The engine retries a full publication inside its work, so behind a full egress publication it reads
99.9% busy and its `admit` and `match` stages look dearer, while doing nothing useful. Before
concluding that the engine is the limit, read the egress publication's headroom (5.8, "A publication
at its limit").
:::

::: warning Do not dedicate the archive's threads on their own
`--archive-threading DEDICATED` behind a `SHARED` driver is measurably **worse than both shared** —
28.4 ms against 6,410 µs at 350,000/s, and saturated where the default was not — because it takes a
core from the component that needed it. It belongs with dedicated driver threads and isolated cores,
or not at all. `--ingress-term-length` is the same shape of trap: raising it from `64k` to `16m`
improves p50 at the edge 2.9x and moves the sustainable rate **not at all**.
:::

::: note What "sustained" means here
The figures above are the rate at which acknowledgements keep up. Above it the shard still accepts
everything — nothing is rejected and nothing is dropped — and the latency becomes a queue that never
drains: at 1,000,000 orders/s offered with UDP egress, every order was still answered, at a median
of 462 ms. A rate
above the knee therefore reports an *offer* rate and not a throughput, which is why `e2e/run-sweep.sh`
marks such a row `SATURATED` (5.7).
:::

::: warning The archive and cluster directories are the shard's only resumption point
They persist by default and `--fresh` is how you ask to lose them. The **Aeron directory is
different** and is always recreated: it is memory-mapped IPC buffers and a `cnc.dat`, and keeping it
makes a restart fail with `Active media driver detected` until the previous driver's liveness
timeout expires.
:::

## 4.9 The control plane

Configured entirely by environment variables.

### Database and web

| Variable | Default | |
| --- | --- | --- |
| `CONTROL_DB_URL` | `jdbc:postgresql://localhost:5432/most_control` | Flyway migrates on start |
| `CONTROL_DB_USER` / `CONTROL_DB_PASSWORD` | `most` / `most` | |
| `CONTROL_PORT` | `8080` | |
| `CONTROL_RELEASE_DIR` | `./build/releases` | Where numbered release directories are written |
| `CONTROL_COOKIE_SECURE` | `false` | **Set `true` behind TLS** |

### Authentication

| Variable | Default | |
| --- | --- | --- |
| `CONTROL_ADMIN_USER` | `admin` | The first operator, seeded once into an empty table |
| `CONTROL_ADMIN_PASSWORD` | *unset* | **With no password set, one is generated and logged at WARN on first boot** |

::: warning Leave `CONTROL_ADMIN_PASSWORD` unset outside development
A known default password on something that can open a market would be worse than no authentication
at all, because it would look protected. Passwords are stored as BCrypt hashes in `control_user`.
:::

### The live link to a running exchange

| Variable | Default | |
| --- | --- | --- |
| `CONTROL_AERON_ENABLED` | `true` | `false` for a database-only deployment |
| `CONTROL_AERON_DIR` | driver default | Must be a media driver this host can reach |
| `CONTROL_DISCOVERY_CHANNEL` / `_STREAM` | `aeron:udp?endpoint=239.10.0.1:40000` / `100` | The routing table |
| `CONTROL_L3_CHANNEL` / `_STREAM` | `aeron:udp?endpoint=239.10.1.3:40003` / `3` | Phase, trades and **halts** |
| `CONTROL_L2_CHANNEL` / `_STREAM` | `aeron:udp?endpoint=239.10.1.2:40002` / `2` | Depth increments for the console |
| `CONTROL_SNAPSHOT_CHANNEL` / `_STREAM` | `aeron:udp?endpoint=239.10.1.4:40004` / `4` | Depth images |
| `CONTROL_L1_CHANNEL` / `_STREAM` | `aeron:udp?endpoint=239.10.1.1:40001` / `1` | Last trade |
| `CONTROL_DEPTH_PUBLISH_MS` | `250` | How often a conflated book image is pushed to browsers |
| `CONTROL_DEPTH_MAX_LEVELS` | `25` | Depth per side in that image |
| `CONTROL_CLUSTER_OPERATORCHANNEL_<shardId>` / `…OPERATORSTREAM_<shardId>` | the shard's order-entry endpoint | Where operator commands go (below) |

**Operator commands go through a gateway that must be an operator.** Session transitions, purges,
definitions and image requests — the scheduler's included — are sent to a gateway like any client
message, and only one whose registry entry has `operator=true` forwards them (4.3). They are
unacknowledged, so a refusal is invisible here: the gateway consumes and counts it
(`refusedCommands`). By default they go to the shard's advertised order-entry endpoint; when that
gateway is a participant's rather than the operator's, name a dedicated operator gateway with
`CONTROL_CLUSTER_OPERATORCHANNEL_<shardId>` and, optionally, `…OPERATORSTREAM_<shardId>`.

The link is **optional**. Authoring reference data must work with no media driver anywhere near it,
so a missing driver is reported by `GET /api/status` and nothing else breaks.

L2, the snapshot feed and L1 go together: increments cannot be applied without an image, an image
goes stale without the increments, and a synchronised subscriber ignores snapshots — so without L1 a
book would trade on screen while its last price stayed empty.

### Cluster ingress, for snapshots

A snapshot request is a **cluster admin request**, not a message for the log, so it is the one thing
the control plane does not send through a gateway.

| Variable | Default | |
| --- | --- | --- |
| `CONTROL_CLUSTER_INGRESS_CHANNEL` | `aeron:udp` | |
| `CONTROL_CLUSTER_EGRESS_CHANNEL` | `aeron:udp?endpoint=0.0.0.0:0` | **Set it.** The host must be one the cluster can send to — the machine of this process's media driver. With the default the cluster's answer has nowhere to go and every request times out |
| `CONTROL_CLUSTER_INGRESS_<shardId>` | none | Member endpoints, e.g. `0=shard0-a:20110,1=shard0-b:20110` |
| `CONTROL_CLUSTER_IDENTITY_<shardId>` | none | The registry identity to present. Required when the shard runs with a registry, which refuses anonymous sessions |
| `CONTROL_CLUSTER_SECRETFILE_<shardId>` | none | Its secret, from a file only. Set with the identity or not at all |

The identity must be an **operator** entry of that shard's registry — `operator=true`, usually with
no participants. The consensus module grants a snapshot request only to an operator (4.3), and the
control plane reports **what the cluster answered**: `confirmed` means it answered OK, which it does
once the snapshot has been taken; a refusal comes back with the cluster's message, and silence as a
timeout. (Aeron's own default grants no snapshot request at all. Until the registry authorised
operators, every snapshot asked for over the network was refused, and reported as confirmed because
only the offer was checked.)

A shard with no ingress entry simply cannot be snapshotted from the control plane, and the API says
so rather than failing obscurely. This is deliberately per-process configuration rather than
database or directory data: keeping cluster ingress out of the broadcast directory is what stops an
upstream adapter finding the cluster and skipping the gateway.

### Scheduler

| Variable | Default | |
| --- | --- | --- |
| `CONTROL_SCHEDULER_ENABLED` | `true` | |
| `CONTROL_SCHEDULER_INTERVAL_MS` | `5000` | |

## 4.10 Authoring configuration in the control plane

The database holds a **draft topology** that nothing reads until it is published.

| Table | Holds |
| --- | --- |
| `shard` | Shard id and the gateway client endpoints published in the directory |
| `security` | Identity and geometry (published), plus reference price and collar widths (sent as commands, never published) |
| `participant` | Participant id, name, SMP id, enabled |
| `gateway`, `gateway_participant` | Gateway identity, secret digest, `operator`; and per listing, `cancel_only` and `is_primary` |
| `spec_release`, `spec_release_shard` | Published releases and the fingerprints frozen into each |
| `session_schedule`, `session_schedule_entry`, `market_holiday` | The trading calendar |
| `control_user`, `operator_audit` | Operators and who asked for every market-moving command |

![Securities. Identity and ladder geometry, validated by the same domain objects a process uses at boot — the ISIN check and the length limits have exactly one implementation.](assets/ui-securities.png)

![Gateways. What each identity may do: participants that place (P marks a primary), those that may only cancel, and whether it is an operator. Creating a gateway returns its secret exactly once.](assets/ui-gateways.png)

A participant may be listed on several gateways; where several of a shard's gateways list it, one
must be marked its primary, or the draft view reports the shard's registry as a problem and the
release cannot be published. One primary per participant per shard is refused at the point of the
mistake. An operator-only gateway (operator, no participants) is published like any other — it is
the control plane's and the CLI's identity.

::: warning A gateway secret is returned exactly once
`POST /api/gateways` generates one and returns the plaintext in that response and nowhere else; the
database stores only its SHA-256. The digest cannot be BCrypt, because the cluster must reproduce it
to verify what a gateway presents. Rotating with `PUT /api/gateways/{id}/secret` issues a new one —
there is no way to read the old one back.
:::

`GET /api/topology` reports **every** problem rather than the first, because a half-built topology is
the normal state while someone is editing it. An empty `problems` list is exactly the condition for
publishing, so one call answers both "can I publish?" and "what is stopping me?".

## 4.11 Publishing a release

```sh
curl -u admin:... -X POST localhost:8080/api/releases \
  -H 'Content-Type: application/json' -d '{"note":"add TSLA to shard 1"}'
```

A release directory contains the rendered artifacts every process boots from, plus a manifest
recording each shard's geometry fingerprint and registry fingerprint separately.

![Releases. Immutable and numbered; the exact bytes of any artifact can be read back, and an existing shard security file can be imported to seed the draft.](assets/ui-releases.png)

::: note Releases are immutable
Republishing allocates the next version rather than overwriting. A release carries **topology only** —
Aeron directories, cluster directories and feed channels stay in each process's own configuration.
:::

Deploying a release is: fetch it to every machine, move the `current` symlink, and restart what needs
restarting. What needs restarting depends on what changed — see 6.6.

::: todo Processes do not verify they read the release you think they did
Every process prints its fingerprint and the control plane records the fingerprint it published, but
comparing them is the operator's job. Making the four processes fail fast on a mismatch changes four
boot paths and is deliberately a separate change.
:::
