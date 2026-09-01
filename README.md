# most-exchange

A low-latency deterministic matching engine. Kotlin on GraalVM Native Image, Aeron Cluster for
replication and sequencing, generated SBE codecs on the wire.

**[`docs/Design.md`](docs/Design.md) is the authoritative specification.** Read it before changing
anything; §8 tracks the open questions.

## Modules

| Module | Role |
| --- | --- |
| `sbe` | `message-schema.xml` and the SBE codecs generated from it. Shared by every process. |
| `reference` | The shard's security list (identity + geometry), the shard registry, and the directory codec adapters embed. |
| `discovery` | Publishes the tradable universe — every security and the shard serving it — so adapters can route. |
| `engine` | `MatchingEngineService` — the single-threaded deterministic state machine. Builds to a native binary. |
| `market-data` | Consumes the Book Event Stream, derives L1 / L2 / L3 and publishes them as SBE over multicast. Separate process so feed fan-out never touches the matching thread. |
| `gateway` | Order entry: `securityId` validation, per-order state for the outbound leg (`cumQty` reconstruction), outbound mapping. |
| `tools` | The `most` operator CLI: browse the universe, send orders, inspect books. |
| `control` | The control plane: Postgres-backed reference data and shard topology, an authenticated REST API, and the published specs every process boots from. Never on a boot path — see [docs/ControlPlane.md](docs/ControlPlane.md). |

`web/` is the admin frontend (Vue 3 + Vite) over the control plane's API. It is **not** a Gradle
module and is deliberately outside the build, so `./gradlew build` needs no npm — see
[`web/README.md`](web/README.md).

Both boundaries speak **binary SBE, not FIX**. Protocol gateways that translate FIX or a proprietary
session protocol sit upstream of `gateway` and downstream of `market-data`, outside this project.

## Build

```sh
./gradlew build                 # compile, generate codecs, run tests
./gradlew :engine:test          # engine tests only
./gradlew :engine:test --tests '*PriceLadderTest*'
./gradlew :sbe:generateSbeCodecs
```

Codecs land in `sbe/build/generated/sbe/com/engine/sbe/` and are regenerated whenever
`message-schema.xml` changes. Never hand-write byte offsets (Design.md §5).

Warnings are errors across all modules. The engine's zero-allocation profile depends on inline
functions actually inlining, so a silent "inline function cannot be inlined" must never reach a
build.

**`control`'s tests need Docker running** — they use Testcontainers to run against a real Postgres,
because most of what they assert is schema behaviour. Without it `./gradlew build` fails in a way
that is easy to mistake for a code problem.

## Native image

```sh
./gradlew :engine:nativeCompile
```

Requires a GraalVM toolchain with `native-image` on the path. Two build knobs, both in
`gradle.properties` and both deliberate (Design.md §7):

- **`engine.march`** — pin the target architecture explicitly. Production and CI must set this
  (`x86-64-v3` or whatever matches the deployment instance type); a `-march=native` build SIGILLs
  when the build host differs from production. Left unset for local builds.
- **`engine.useEpsilonGc`** — off by default. Epsilon is the eventual target, but it turns a slow
  allocation leak into a hard crash, and because the engine is deterministic that crash takes every
  cluster node at the same log position. Ship on Serial GC, prove zero steady-state allocation under
  load, add the CI allocation assertion, then switch this on.

## Status

Implemented: the packed order-pool layout, the price ladder, and `OrderBook` — booking, cancel
validation, continuous matching with the dynamic-collar and SMP gates, the opening auction (price
selection, the SMP fixed-point loop, allocation), and the off-session expiry purge; and
`MatchingEngineService` — the full `ClusteredService`, message dispatch, execution-report egress,
book-event publication, and snapshot/restore. 76 tests.

All eight modules are implemented. 314 tests, plus an end-to-end script.

## End-to-end test

```sh
./gradlew installDist && ./e2e/run-e2e.sh
```

Starts every process — cluster host, engine, gateway, market data, discovery — and drives a real
trade through the CLI. Single node and IPC rather than multicast: it proves the components talk to
each other, not that the network is configured. Logs land in `build/e2e/logs/`.

[`docs/LocalTesting.md`](docs/LocalTesting.md) is the manual walkthrough of the same setup: startup
order, a three-security shard file, seeding, sending orders, inspecting the books, and shutting down
cleanly.

## Running

A node is two processes. The media driver allocates, so it stays out of the engine binary
(Design.md §7):

```sh
# 1. driver + archive + consensus module
java io.aeron.cluster.ClusteredMediaDriver

# 2. the engine
./gradlew :engine:installDist
./engine/build/install/engine/bin/engine my-shard.properties

# 3. market data, and 4. the gateway
./gradlew :market-data:installDist :gateway:installDist
./market-data/build/install/market-data/bin/market-data my-md.properties
./gateway/build/install/gateway/bin/gateway my-gateway.properties
```

Each module ships a commented sample config under `src/main/resources/`. All three processes attach
to an already-running media driver rather than embedding one, and report clearly if it is absent.

`engine/src/main/resources/engine-sample.properties` is a commented starting point. Any `engine.*`
system property overrides the corresponding file entry.

Boot config carries **only geometry and capacity**; reference prices and collar widths arrive as
`SecurityDefinition` commands through the log. Every node must boot with identical geometry — the
engine prints a fingerprint of it at startup so nodes can be compared at a glance rather than by
diffing files.

## Control plane and admin UI

The control plane authors reference data and publishes the release artifacts every other process
boots from. It is a normal Spring Boot service with a Postgres behind it, and it is the only process
here that needs a database, an admin account, or a browser.

### 1. Postgres

```sh
docker run -d --name most-control-db -p 5432:5432 \
  -e POSTGRES_DB=most_control -e POSTGRES_USER=most -e POSTGRES_PASSWORD=most postgres:16-alpine

docker start most-control-db     # on later runs
```

Flyway migrates on startup, so there is nothing to load by hand. **This database is deliberately not
on any node's boot path**: the engine, gateway, market-data and discovery processes read a published
file. An outage here must not stop a node starting — and, the reason that actually matters, a write
landing between two nodes' boots would give them different geometry. They would not fail; they would
diverge on the first order.

### 2. The control plane

```sh
./gradlew :control:installDist
CONTROL_ADMIN_PASSWORD=... control/build/install/control/bin/control
```

Listens on `:8080`. Environment: `CONTROL_DB_URL`, `CONTROL_DB_USER`, `CONTROL_DB_PASSWORD`,
`CONTROL_PORT`, `CONTROL_RELEASE_DIR`, `CONTROL_ADMIN_USER`, `CONTROL_ADMIN_PASSWORD`,
`CONTROL_COOKIE_SECURE`, and `CONTROL_AERON_*` / `CONTROL_SCHEDULER_*` for the live link and the
session calendar.

The first operator account is seeded once into an empty table. **With no password configured it
generates one and logs it at WARN on first boot** — grep the log for `seeded operator`. A known
default password on something that can open a market would be worse than no authentication at all,
because it would look protected.

With no media driver running, add `CONTROL_AERON_ENABLED=false`: the REST API still serves and
`/api/status` says why the live link is down. Authoring reference data must never require an Aeron
driver.

Everything under `/api` requires an authenticated operator — one `ADMIN` role, full access. Scripts
use HTTP Basic, which is exempt from CSRF because a browser attaches cookies to a cross-site request
automatically and an `Authorization` header never:

```sh
curl -u admin:... localhost:8080/api/topology
curl -u admin:... -X POST 'localhost:8080/api/releases?note=first'
```

Browsers use a session cookie plus a CSRF token, which the frontend handles.
[`docs/ControlPlane.md`](docs/ControlPlane.md) §4 has the full surface and the cookie handshake.

### 3. The admin UI

```sh
cd web
npm install
npm run dev          # http://localhost:5173
```

Sign in with the operator above. The first slice is read-only: shards, securities, participants,
releases, live exchange status, and the audit of who asked for what. Anything that *changes*
something is still done over REST.

The dev server proxies `/api` to `:8080` so the browser sees **one origin**. That is not
convenience — the session is a cookie and the CSRF defence is a cookie copied into a header, and
both are same-origin mechanisms. A second origin would mean CORS, credentialed cross-origin requests
and `SameSite=None` on the session cookie: three loosened settings to work around a problem a proxy
removes. Serve the built `dist/` from behind the same host in production, and set
`CONTROL_COOKIE_SECURE=true` wherever TLS is terminated.

## JVM flags

Agrona 2.x reaches `jdk.internal.misc.Unsafe` and the Aeron driver uses `sun.nio.ch`, neither of
which JDK 17+ exports by default. Every JVM running this code needs:

```
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
```

These are already set on the `test` and `run` tasks. Without them the first `UnsafeBuffer`
construction throws `IllegalAccessError`.
