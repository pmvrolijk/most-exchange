# most-exchange

A low-latency deterministic matching engine. Kotlin on GraalVM Native Image, Aeron Cluster for
replication and sequencing, generated SBE codecs on the wire.

**[`docs/Design.md`](docs/Design.md) is the authoritative specification.** Read it before changing
anything; §8 tracks the open questions.

**[`docs/Status.md`](docs/Status.md) is where things stand** — what is real, what is open, and
what to do next. It is the shortest path into the project; [`docs/Handover.md`](docs/Handover.md)
is the archive behind it, and [`docs/Rationale.md`](docs/Rationale.md) explains why each rule in
`CLAUDE.md` exists.

**[`docs/OperatorManual.pdf`](docs/OperatorManual.pdf) is the Operator's Manual** — installing,
configuring and operating the exchange, with the full configuration reference and the daily
procedures. Its source is in [`docs/manual/`](docs/manual/) and it is rebuilt with `npm run build`
there.

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
./gradlew :engine:nativeCompile :gateway:nativeCompile \
          :market-data:nativeCompile :discovery:nativeCompile
```

Requires a GraalVM toolchain with `native-image` on the path (`JAVA_HOME`/`GRAALVM_HOME` pointing at
it). All four core processes build, and `e2e/run-e2e.sh` passes with every one of them substituted
for its JVM start script — same trade, same report and fill counts as the JVM run:

```sh
ENGINE=$PWD/engine/build/native/nativeCompile/matching-engine \
GATEWAY=$PWD/gateway/build/native/nativeCompile/order-gateway \
MARKETDATA=$PWD/market-data/build/native/nativeCompile/market-data \
DISCOVERY=$PWD/discovery/build/native/nativeCompile/discovery \
  ./e2e/run-e2e.sh
```

The `--add-exports` and class-initialization flags Aeron and Agrona need live in the **root**
`build.gradle.kts`, not per module — they belong to the dependency stack, and four copies is how one
drifts. Design.md §7 explains each one; the short version is that a missing export does not fail the
build, it produces a binary that starts and then dies on its first `UnsafeBuffer`.

Two build knobs, both in `gradle.properties` and both deliberate (Design.md §7):

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

## Allocation and the Epsilon soak

The engine claims no allocation on its hot path (Design.md §3.3), and under `--gc=epsilon` that
claim is load-bearing rather than aspirational. Two measurements keep it honest, and both run
against the shipped code:

```sh
./gradlew :engine:test --tests '*AllocationTest*'      # per-call, fakes only
./gradlew :engine:test --tests '*AeronAllocationTest*' # book events and snapshots, real driver
./e2e/run-epsilon-soak.sh                              # whole system, real cluster and archive
```

`AllocationTest` attributes precisely: it drives the service through allocation-free fakes and reads
the thread's allocation counter across eight windows, requiring a strict majority to read exactly
zero and under one byte per operation overall. A steady-state cost appears in every window, so this
cannot admit a rate while still absorbing the late JIT blip that lands in one or two.

`AeronAllocationTest` reaches the two paths a fake cannot — publishing book events, and
`onTakeSnapshot` walking 2,000 resting orders — by launching an embedded media driver, because
`ExclusivePublication` is a `final` class with no interface to implement.

`run-epsilon-soak.sh` runs the Epsilon-built binary against a real media driver, cluster and archive
twice at different order counts and reports the **slope**, so the ~95MB of pools allocated at startup
cancels instead of swamping the figure. It currently reports **0 bytes per order across 1.9M orders**.

All three are validated by mutation, not trust. Removing `inline` from `OrderBook.matchAggressive`,
`offerToSnapshot` or `publishBookEvent` compiles cleanly and silently boxes a callback's captured
state; each is caught by the test covering its path and by no other.

## Where the latency goes

`most load` measures a client round trip and nothing smaller. The engine and gateway can time their
own hot paths (Design.md §7), and one script drives the load and does the subtraction:

```sh
./gradlew installDist && ./e2e/run-attribution.sh
```

```
  client round trip            55.4 us
  gateway inbound               0.2 us
  engine (whole message)        0.4 us
  gateway outbound              0.2 us
  ------------------------------------
  in this shard's processes      0.8 us  (1.4%)
  everything else              54.6 us  (98.6%)
```

The exchange's own code is 1.4% of the round trip; the rest is Raft consensus, the archive's disk
write and the IPC hops — the cost of being a replicated log. The engine's whole-message p50 of
0.42 µs is inside the 0.5 µs estimate Design.md §2 has carried unverified since the beginning, and
`engine.metrics.stages=true` splits it further into admit / match / settle.

It is off by default and on in the dev stack. The engine reading a clock at all is a deliberate
exception to the determinism rules, bounded by one invariant — enabling metrics on one node and not
another must be incapable of changing the log — which `MetricsDeterminismTest` checks rather than
asserts.

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

## Docker: the whole thing at once

[`deploy/`](deploy/README.md) runs everything on one machine — control plane, Postgres, admin UI and
one complete shard — and leaves you with an open market:

```sh
./gradlew installDist
cd deploy && docker compose up -d      # ~40s

./most send --symbol AAPL --side sell --price 100.00 --qty 10 --clordid 1 --participant 7 --follow 2
./most send --symbol AAPL --side buy  --price 100.00 --qty 4  --clordid 2 --participant 8 --follow 2
```

The admin UI is on <http://localhost:8081> and the control API on <http://localhost:8080>, both
`admin` / `most-dev-password`.

The network layout is the part worth reading about: `aeron` and `data` are internal networks with no
route in or out, the five shard processes share one media driver the way processes on one node share
`/dev/shm`, and everything outside the shard — the control plane today, a FIX adapter tomorrow —
reaches it over UDP with a driver of its own. [`deploy/README.md`](deploy/README.md) explains that
seam, why one media driver means one network identity, and what is dev-only.

The core services are built JVM by default. The native target (`CORE_TARGET=native`) now builds —
a `linux/amd64` image with all four binaries at `-march=x86-64-v3` — and stays opt-in because it
costs minutes per build where copying a host `installDist` costs seconds. It has not yet traded on
an x86-64 host; `deploy/README.md` says why and what is left.

## Control plane and admin UI, without Docker

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

## License and copyright

    Copyright (C) 2026  P.M.Vrolijk

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
