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
:::

Reference prices and collar widths are deliberately **not** in this file. They change during a
session and every node must apply them at the same log position, so they arrive as
`SecurityDefinition` commands through the replicated log (5.3).

## 4.3 The participant registry

Which gateway may connect as which identity, and which participants it speaks for. Read by **two**
processes for two different reasons: the consensus module authenticates a connecting gateway against
it, and the engine resolves the resulting principal into a participant list at session open.

```properties
shard.id=0
registry.gateways=gw-0

gateway.gw-0.secret=3b2c637a03bcb9c167c9bd5f84de22f6bbdc18169d36526cafc164cd68725367
gateway.gw-0.participants=7,8
```

| Key | Constraint |
| --- | --- |
| `shard.id` | Must match the shard of the process reading it, or the process refuses to start |
| `registry.gateways` | At least one id, comma-separated |
| `gateway.<id>.secret` | The **SHA-256 hex digest** of the shared secret, 64 lowercase hex characters |
| `gateway.<id>.participants` | At least one positive participant id. A participant belongs to **at most one gateway** |

Gateway ids are at most 64 characters from a restricted alphabet. The file holds the digest, never
the secret; the secret itself is delivered only to the gateway that presents it.

::: term Participant binding
A gateway presents `gatewayId:secret` as its cluster credentials. The consensus module verifies them
and stamps the gateway id on the session as its **encoded principal**. Aeron carries that principal
in the session-open event through the replicated log, so every node derives the identical
participant-to-session map without any snapshot state of its own. This is what makes a participant's
fills deliverable when it has said nothing since the gateway last connected.
:::

::: note Wrong credentials are rejected, never downgraded
A gateway with a bad secret fails to connect. It is never quietly downgraded to an anonymous session
— an anonymous gateway trades perfectly well and loses only the fills of whichever participants have
gone quiet, which is invisible until someone reconciles a `cumQty`. **No** credentials at all still
authenticates anonymously, because the control plane and the CLI connect to send operator commands
and are addressed by nobody.
:::

### Reloading while a node runs

The consensus module and the engine both **re-read this file while running**. They compare content by
fingerprint rather than mtime — a release is published to a new directory and put in force by moving
a symlink, so mtime says nothing — and swap an immutable registry behind a volatile reference.

A file that cannot be parsed, or one for another shard, is **reported and ignored**; the registry in
force keeps authenticating. Standing down on a bad file would turn a typo into a shard that
authenticates nobody. Watch `registryReloadFailures` rather than assuming a silent success.

The gateway does **not** reload; it restarts, which costs nothing because it holds no state.

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
| `engine.participantRegistry` | none | The participant registry (4.3). Omit and routes are learned from traffic alone |
| `engine.participantRegistry.reloadMs` | `5000` | Registry poll interval; `0` disables reloading |
| `engine.aeronDir` | driver default | Must be the media driver this node's processes share |
| `engine.clusterDir` | `cluster` | The consensus module's cluster directory, written by the cluster host |
| `engine.serviceId` | `0` | The clustered service id |
| `engine.bookEvent.channel` | `aeron:ipc` | Where book events are published. Keep it IPC: market-data is on this node |
| `engine.bookEvent.streamId` | `12` | |
| `engine.auction.maxPasses` | `64` | Safety valve on the uncross fixed-point loop, not part of the algorithm |
| `engine.backpressure.alertThreshold` | `1000000` | Consecutive back-pressured publications before alerting |
| `engine.metrics` | `false` | Hot-path timing; two clock reads per message |
| `engine.metrics.stages` | `false` | Adds the admit/match/settle partition of a new order; two more clock reads |
| `engine.metrics.file` | none | Where percentile distributions are written at shutdown |

::: note Metrics are node-local and deliberately outside the fingerprint
The engine reads `System.nanoTime()` to record these histograms, which the determinism rules
otherwise prohibit. The rule is that time must not influence **replicated state**, not that it may
not be observed: the histograms are write-only — never read by a branch, never snapshotted, never on
a feed. Enabling metrics on one node and not another must be incapable of changing the log, the
books or a snapshot. That is why `engine.metrics*` is excluded from the fingerprint, and it is the
test any probe added later must pass.
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
gateway.egressChannel=aeron:udp?endpoint=0.0.0.0:0

gateway.client.inbound.channel=aeron:udp?endpoint=0.0.0.0:20001
gateway.client.inbound.streamId=20
gateway.client.outbound.channel=aeron:udp?endpoint=239.10.0.5:20002
gateway.client.outbound.streamId=21
```

| Key | Default | Meaning |
| --- | --- | --- |
| `gateway.securitiesFile` | *required* | The shard security file. Orders for anything else are rejected |
| `gateway.participantRegistry` | none | Checked at boot so a typo in the id stops the process here |
| `gateway.gatewayId` | none | This gateway's registry id. Unset means an anonymous connection |
| `gateway.credentialToken` | none | The shared secret as a literal. Prefer the file form |
| `gateway.credentialTokenFile` | none | The shared secret from a file; trailing whitespace trimmed |
| `gateway.aeronDir` | driver default | This gateway's own media driver |
| `gateway.ingressChannel` | `aeron:udp` | Cluster ingress |
| `gateway.ingressEndpoints` | none | Every member's ingress endpoint, so the client can find the leader |
| `gateway.egressChannel` | `aeron:udp?endpoint=localhost:9020` | Cluster egress |
| `gateway.client.inbound.channel` | `aeron:ipc` | Where adapters **publish orders**. Must be unique per gateway |
| `gateway.client.inbound.streamId` | `20` | |
| `gateway.client.outbound.channel` | `aeron:ipc` | Where the gateway **publishes execution reports** |
| `gateway.client.outbound.streamId` | `21` | |
| `gateway.metrics` | `false` | Hot-path timing on both legs |
| `gateway.metrics.file` | none | Percentile distributions at shutdown |

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
| `md.snapshot.cycleMs` | `1000` | One security's image per cycle, so a full pass is `cycleMs × securities` |

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
| `--participants FILE` | none | The participant registry to authenticate gateways against |
| `--participants-reload-ms N` | `5000` | Registry poll interval; `0` disables |
| `--driver-threading MODE` | `SHARED` | Media driver threads: `SHARED`, `SHARED_NETWORK` or `DEDICATED`. **The shard's throughput ceiling** — see below |
| `--archive-threading MODE` | `SHARED` | Archive threads: `SHARED` or `DEDICATED`. Leave it alone unless the cores are isolated |
| `--ingress-term-length LEN` | `64k` | Cluster ingress term length, e.g. `16m`. Buys latency headroom at the edge, not capacity |
| `--fresh` | off | **Delete the archive and cluster directories on start** |
| `--keep` | on | Persist them. Contradicts `--fresh` |

### Driver threading is the throughput ceiling

`--driver-threading` is the single setting with the largest measured effect on what a shard can carry.
`SHARED` puts the media driver's conductor, sender and receiver on **one** thread; at ten securities
that thread moves ~190 MB/s of loopback traffic and is what caps the shard. Measured on a 14-core
development machine, ten securities, aggregate rate across all of them:

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
drains: at 1,000,000 orders/s offered, every order was still answered, at a median of 462 ms. A rate
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
| `CONTROL_CLUSTER_EGRESS_CHANNEL` | `aeron:udp?endpoint=0.0.0.0:0` | |
| `CONTROL_CLUSTER_INGRESS_<shardId>` | none | Member endpoints, e.g. `0=shard0-a:20110,1=shard0-b:20110` |

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
| `gateway`, `gateway_participant` | Gateway identity, secret digest, and participant claims |
| `spec_release`, `spec_release_shard` | Published releases and the fingerprints frozen into each |
| `session_schedule`, `session_schedule_entry`, `market_holiday` | The trading calendar |
| `control_user`, `operator_audit` | Operators and who asked for every market-moving command |

![Securities. Identity and ladder geometry, validated by the same domain objects a process uses at boot — the ISIN check and the length limits have exactly one implementation.](assets/ui-securities.png)

![Gateways. Identity, participant claims, and the rendered registry a shard would publish. Creating a gateway returns its secret exactly once.](assets/ui-gateways.png)

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
