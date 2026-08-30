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
| `control` | The control plane: Postgres-backed reference data and shard topology, a REST API, and the published specs every process boots from. Never on a boot path — see [docs/ControlPlane.md](docs/ControlPlane.md). |

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

All eight modules are implemented. 251 tests, plus an end-to-end script.

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

## JVM flags

Agrona 2.x reaches `jdk.internal.misc.Unsafe` and the Aeron driver uses `sun.nio.ch`, neither of
which JDK 17+ exports by default. Every JVM running this code needs:

```
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
```

These are already set on the `test` and `run` tasks. Without them the first `UnsafeBuffer`
construction throws `IllegalAccessError`.
