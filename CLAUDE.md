# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository. **Rules only.** Each one is
the residue of a defect or a decision; the reasoning lives in [`docs/Rationale.md`](docs/Rationale.md),
cited as → R§n. Do not change a rule without reading its section there.

| | |
| --- | --- |
| **Where things stand, what to do next** | [`docs/Status.md`](docs/Status.md) — the session entry point |
| **Normative behaviour** | [`docs/Design.md`](docs/Design.md) — authoritative. §8 is the open list |
| **Why a rule exists** | [`docs/Rationale.md`](docs/Rationale.md) |
| **How a change happened** | [`docs/Handover.md`](docs/Handover.md) — archive, §2a–§2j |

`docs/Design.md` is the specification — read it before implementing anything, and update it in the
same commit when the design changes.

Eight modules — `sbe`, `reference`, `discovery`, `engine`, `market-data`, `gateway`, `tools`,
`control` — all implemented, 463 tests passing. See `docs/Status.md` §1 for what is real.

## Commands

```sh
./gradlew build                                    # compile, generate codecs, test
./gradlew :engine:test --tests '*PriceLadderTest*' # a single test class
./gradlew :sbe:generateSbeCodecs                   # regenerate codecs only
./gradlew :engine:nativeCompile                    # native binary (needs a GraalVM toolchain)
./gradlew installDist && ./e2e/run-e2e.sh          # every process, a real trade, a load run
./e2e/run-restart.sh                               # does the shard come back with its book?
./e2e/run-attribution.sh                           # where a round trip goes, by stage
SECURITIES=10 ./e2e/run-sweep.sh                   # how far a shard goes, and where it stops
./e2e/run-epsilon-soak.sh                          # steady-state allocation
deploy/cloud/linode/bench.sh up|check|sync|run|down  # a 16-core pinned host for the sweep (deploy/cloud/README.md)
```

- **Run `e2e/run-e2e.sh` after changing anything on the wire.** It has caught five defects unit
  tests could not. It wipes everything and starts fresh, so it says nothing about durability.
- **`e2e/run-restart.sh` is the only check that state survives a restart** — and the only one that
  covers the geometry-change refusals and the multi-gateway cases (§4c, §4d, §4e).
- `run-e2e.sh` takes each binary path from `ENGINE`/`GATEWAY`/`MARKETDATA`/`DISCOVERY`, so the same
  run drives native images. A native binary must pass it unchanged, with the same report and fill
  counts. That is the check, not that it started.
- A node is **two processes**: `io.aeron.cluster.ClusteredMediaDriver` (driver + archive + consensus
  module) and this binary (service container only). The driver allocates, so it stays out of the
  engine process.
- `control`'s tests need Docker running (Testcontainers Postgres).
- `docs/LocalTesting.md` is the manual walkthrough; §9 is the benchmarking procedure.

## The four non-negotiables

1. **Determinism.** Each shard is one thread over a Raft-replicated log; every node must produce
   byte-identical state. A crash, an unvalidated index or a pool-exhaustion throw kills *every* node
   at the same log position. Validate defensively; prefer rejecting an order over throwing —
   exceeding the pool high-water mark rejects with `BOOK_CAPACITY`, and the pool is never allowed to
   drain to a throw. → R§1
2. **No wall-clock calls.** `System.currentTimeMillis()`/`nanoTime()` are banned in replicated
   paths. Time comes from `Cluster.time()`, the consensus timestamp, or timestamps on sequenced
   commands. The one exception is metrics — see below. → R§1, R§11
3. **No allocation on the hot path.** Watch boxing through generic views (`Map.Entry`,
   `Array<Price>`) and `@JvmInline value class` parameters on any callback that is not an
   `inline fun`. → R§2
4. **Zero-copy publishing.** Use `Publication.tryClaim` and encode directly into the log buffer;
   never encode into a scratch buffer and `offer` it. → R§2

## The wire

- **Never hand-write SBE byte offsets.** Edit `sbe/src/main/resources/message-schema.xml`; codecs
  regenerate into `sbe/build/generated/sbe/`. Keep the schema and Design.md §5 identical. → R§3
- **`ClientSession.tryClaim` reserves `AeronCluster.SESSION_HEADER_LENGTH`.** Encode at
  `claim.offset() + SESSION_HEADER_LENGTH`. A plain `Publication` does **not** — `most load` encodes
  at `claim.offset()`. The test fake models the reservation; keep it that way. → R§3
- **`cumQty` is stated by the engine, never subtracted by anyone.** A terminal report carries
  `leavesQty = 0` whether the order filled or was cancelled. **`origQty = 0` means unknown** and
  travels to the client as `Enrichment.UNKNOWN`. → R§3
- **Encoders live in `reference`'s `OperatorCommands`**, shared by the CLI and the control plane. Do
  not write a second encoding of a wire message.
- **Both boundaries are binary SBE, not FIX.** FIX and proprietary session protocols live in
  separate gateways upstream of `gateway` and downstream of `market-data`, outside this project.
- **Prices and quantities are fixed-point `int64` with 8 implied decimals.** Never introduce
  floating point into pricing or matching arithmetic.
- The `wire-change` skill is the full checklist.

## Cluster lifecycle

- **The archive and cluster directories are the shard's only resumption point, and they persist by
  default.** `most cluster` wipes them only on `--fresh`. The **Aeron directory is different and is
  always recreated**. → R§4
- **Nothing takes a snapshot unless something asks** — `most cluster snapshot`, `most cluster
  shutdown`, or the control plane's scheduler at session close. Without one, a restart replays from
  genesis. → R§4
- **A snapshot through consensus is an admin request, and Aeron's default authorisation refuses it.**
  `RegistryAuthorisationService` grants it to an `operator=true` identity; report the cluster's
  *answer* (`requestSnapshot`), never the offer, and prove a snapshot by the recording log. → R§4
- **A node cannot restart for ~10 s after the previous one stopped.** Archive and cluster mark files
  carry a liveness timestamp. → R§4
- **A cluster client must send keepalives.** The consensus module closes a session after
  `sessionTimeoutNs` (10 s default) of silence and every later offer fails silently. → R§4
- **`ShutdownSignalBarrier` must be closed** — `await()` alone leaves the JVM alive — and anything
  printed at shutdown must be **inside** the barrier block, since closing it releases the signal and
  the process exits at once. → R§4

## Recovery and restore

- **Restarting only the service container is not a recovery.** The consensus module replays the log
  from the beginning. The engine prints `restored N resting orders ... from a snapshot` so the two
  are distinguishable; assert on that line, not on depth. → R§5
- **A geometry change is reapplied by restarting, so `loadSnapshot` is where it is made safe.** It
  **refuses to start** rather than lose state: a security gone from the shard with resting orders, a
  changed `priceFloor`/`tickSize`/`levelCount`/`maxOrders`, an order outside the new ladder, or
  counts that do not add up. The two quiet, legitimate cases are an **empty** book leaving the shard
  and a new security joining. → R§5
- **`Image.poll` swallows an exception from its fragment handler** and advances the position anyway,
  so a bad order during a restore is silently dropped and the book comes back quietly wrong. The
  restore checks `isLevelInRange` itself and reconciles resting-order counts as a backstop. Do not
  assume a throw on this path is loud. → R§5
- **A refused restore leaves through the `ShutdownSignalBarrier`, not `Runtime.halt`** — halting
  leaves a live mark file and blocks the operator's fixed restart for ten seconds. → R§5
- **Every node compares the leader's configuration from the log and refuses on a difference.** The
  leader's `ConfigurationAnnouncement` (shard fingerprint + engine fingerprint, at each term start)
  is checked by every node; a mismatch stops applying, refuses to snapshot and exits non-zero. A
  setting that can change what the engine computes from a log belongs in
  `EngineConfig.engineFingerprintValue()`. **A geometry change needs a snapshot at the end of the
  log** (`most cluster shutdown`). A log tail written under another file refuses on replay. → R§5
- **A service message is offered on every node, from a log callback, never from `onRoleChange`**
  (Aeron throws there), and it arrives at `onSessionMessage` with a **null** session. An
  announcement on a client session is ignored. → R§5
- **Assert on a node surviving replay, not on a line printed before it.** `loadSnapshot`'s lines
  come before the log tail is replayed. → R§5

## Market data

- **The engine never formats market data.** It publishes a raw book event stream on IPC 12 (leader
  only, muted via `onRoleChange` since it is a plain publication, not egress); `market-data` derives
  L1/L2/L3 as SBE over UDP multicast, unicast as a bounded fallback.
- **A recovered engine republishes its books as a level image** (`BookImageBegin`/`Level`/`End`,
  ids 27–29), bounded by `levelCount`. Two triggers, one path: a snapshot restore, and the
  `RequestBookImage` operator command. Both publish from `doBackgroundWork` once the publication is
  *connected*. → R§6
- **An image carries the sequence as a baseline and consumes none.** A subscriber must not count an
  image's `seqNum` as a gap. → R§6
- **Installing an image publishes increments as well as a snapshot**, and `DepthBook.forEachVacated`
  zeroes a level the image removed. → R§6
- **A synchronised subscriber ignores a snapshot only when installing it would rewind** — an image
  at or ahead of everything applied is allowed. `DepthFeedAssemblerTest` pins both directions. → R§6
- **The L2 snapshot is taken on the poll thread.** Moving it to a timer thread produces a torn image
  no consumer could detect. An empty book still sends a bracketed zero-level cycle. → R§6
- **Consumers use `DepthFeedAssembler` in `reference`** — do not write a second one.
- **Do not change Aeron's multicast flow control.** `MaxMulticastFlowControl` is correct here; the
  `Min` variant would let the slowest subscriber throttle the publisher. Gap detection and snapshot
  re-synchronisation are subscriber responsibilities. → R§6
- **Every book event carries a `seqNum` and a `shardId`**, stamped by the engine and snapshotted
  with `nextExchangeOrderId`, which makes L3 a verbatim byte-forward. `OrderRemoved` carries
  `leavesQty` so the whole L2 aggregate is derivable without shadowing per-order state.
- **Feed sequences are namespaced by shard**, each numbering from 1. `FeedSequenceTracker` in
  `reference` distinguishes a gap from a replay.

## The gateway

- **The gateway is stateless** — it validates `securityId` inbound (the engine range-checks it
  again defensively), translates the report outbound,
  and holds nothing per order. **The engine holds `origQty`**, in a parallel `ColdField` `LongArray`
  beside the packed pool, never in the 64-byte line. A future order attribute that matching does not
  read belongs there too. → R§7
- **Several gateways can serve one shard**, and what must be disjoint between two of them is their
  *client endpoints*. → R§7
- **An order the gateway cannot forward must not be consumed.** `BACK_PRESSURED`/`ADMIN_ACTION` is
  transient: use `controlledPoll` and return `Action.ABORT`, and `Action.COMMIT` — never
  `Action.CONTINUE` — on the handled path. A dead session is not retryable: reject with
  `GATEWAY_UNAVAILABLE`. → R§7
- **The outbound leg cannot do this** — egress must keep being drained or the session dies — so it
  drops and counts. Retrying there was measured and cost 10x on the p99 while still dropping. → R§7
- **A gateway serves exactly one shard** and rejects anything outside its list.
- **The directory publishes the GATEWAY's client endpoints**, not the cluster ingress/egress.

## Participants and the registry

- **A participant is bound to its session at session open, from an authenticated principal.** The
  consensus module verifies `gatewayId:secret` against the shard's `ParticipantRegistry` and stamps
  the gateway id as the encoded principal; the engine binds each participant that gateway lists
  **if it is the participant's primary or nobody live holds it**, and a closing session's routes move
  to another open gateway that lists them, primary first. The map is rebuilt in `onStart` from
  `cluster.clientSessions()` and needs **no snapshot state of its own**. → R§8
- **Traffic still wins for the order it arrived on**, and `onSessionClose` drops only routes that
  session **still owns**. Both directions are mutation-tested in `ParticipantBindingTest`. → R§8
- **A node with a registry authenticates everything or nothing.** Wrong credentials are rejected,
  never downgraded to anonymous, and *no* credentials are rejected too — an anonymous session skips
  every gateway check. The control plane (`control.cluster.identity.<shard>`) and the CLI
  (`--identity`/`--secret-file`) hold operator-only entries: `operator=true`, no participants.
  The control plane's operator commands go through a gateway, which must be an operator
  (`control.cluster.operatorChannel.<shard>` names one). → R§8
- **The registry is re-read while a node runs**, by fingerprint, not mtime. A file that cannot be
  parsed, or one for another shard, is reported and ignored. The gateway re-reads it the same way,
  because it is where the registry is enforced. → R§8
- **This is legal only because what it feeds is node-local egress routing.** It stops being legal
  the moment the engine *rejects* an order on a binding, **so the engine never does.**
  `UNAUTHORIZED_PARTICIPANT` is raised by the **gateway**, before the log, where a node-local file
  is exactly what a refusal may rest on. The engine may only *count* an undeclared participant.
  `engine.participantRegistry.reloadMs` is excluded from `EngineConfig.fingerprint()` for the same
  reason metrics are. → R§8
- **The gateway checks cancels as well as orders**, and refuses operator commands unless its entry
  has `operator=true`. The engine's cancel check is participant equality, which means nothing if the
  participant id was never checked. → R§8
- **The registry is not in `ShardSpec.fingerprint()`** and must not be folded into it. It has its
  own. → R§8
- **Execution reports route by `participantId → clusterSessionId`.** Undeliverable reports are
  counted and dropped, never blocked on.
- **All of this is optional and off by default.** A gateway without an identity enforces nothing.

## The control plane

- **The control plane is the only authenticated process**, and the only one that *decides* anything.
  Everything under `/api` requires an `ADMIN` operator; `operator_audit` records **`sent`, not
  `applied`**. → R§9
- **It authors reference data and is never on a boot path.** A node reads a file, never the
  database. → R§9
- **Never reimplement a domain rule there.** It builds real `SecuritySpec`/`ShardSpec`/`ShardRoute`/
  `Universe` objects and calls their methods. `render()` and `from(Properties)` are inverses and a
  round-trip test enforces it. → R§9
- **Operator commands are unacknowledged.** A sender may claim only that bytes were sent. Anything
  checkable before the wire is checked locally. → R§9
- **The trading calendar lives in the control plane and `onTimerEvent` stays unused.** Each tick
  *reconciles* the wanted phase against the phase L3 reports. **The difference between phases is a
  path, not a destination** — sending `CONTINUOUS` directly silently skips the auction — and **a
  halted security is never reconciled back open**. A cold cluster's phase is unknown; wait rather
  than assume `CLOSED`. → R§9
- **A halt is visible only on L3.** Reopening is ordered: re-seed the definition *before*
  `PRE_OPEN`, then walk `PRE_OPEN → OPEN_AUCTION → CONTINUOUS` in full. **A session transition is
  shard-wide.** → R§9
- **One security list per shard** (`reference`'s `ShardSpec`), read by all four processes. Every one
  prints `ShardSpec.fingerprint()` at startup. → R§9
- **`web/` is the Vue frontend**, deliberately outside the Gradle build, served same-origin.

## Domain rules

**All matching and session semantics live in [`docs/Design.md`](docs/Design.md) §4** — the session
lifecycle and what each phase accepts, books and matches (§4.1); the uncrossing algorithm (§4.2);
date-based expiry and the off-session purge (§4.3); the two reference prices and both collars
(§4.4); self-match prevention, including the auction's multi-pass fixed point (§4.5); and the
volatility halt and its operator-driven recovery (§4.6). Capacity and sharding limits are §2,
the packed order pool and price ladder are §3.

Read the relevant §4 clause before changing matching behaviour, and write the test's expected value
from it rather than from the code — that is the `spec-first-test` skill, and it exists because a
test written the other way round once asserted a defect.

Four of those clauses are load-bearing in a way that is easy to undo by accident, so they are named
here as well:

- **The ladder range is configured strictly wider than the static collar** (§3.2, §4.4), so band
  rejection always fires before ladder overflow. `PRICE_OUT_OF_LADDER` is a defensive backstop only.
- **The dynamic collar is measured against a snapshot of `dynamicReference` taken when the
  aggressing order arrived** (§4.4), never the live value that the order's own fills are advancing.
- **The purge walks the price ladders, not the id hash map** (§4.3) — `Long2LongHashMap` compacts
  its probe chain on removal, so iterator-based removal can silently skip entries.
- **`staticReference` is not reset until the reopening uncross executes** (§4.6), so a halt that
  moved price outside the static band deadlocks. Re-issuing `SecurityDefinition` before `PRE_OPEN`
  is a required halt-recovery step, not an exception.

## Metrics

- **The engine may read `nanoTime` only for metrics, and the histograms must stay write-only** —
  never read by a branch, never snapshotted, never on a feed. The test for any probe added later:
  enabling metrics on one node and not another must be incapable of changing the log, the books or a
  snapshot. `MetricsDeterminismTest` checks it, and metrics are excluded from
  `EngineConfig.fingerprint()` for the same reason. → R§11
- Recording allocates nothing, and `AllocationTest` covers the instrumented path.
- `engine.metrics` / `engine.metrics.stages` / `gateway.metrics` / `md.metrics`, off by default, on
  in `deploy/` and `e2e/`. Each also publishes its poll loop's duty cycle; the `duty-ns` counter is
  metrics under the same rule. Summaries print at shutdown, so they need an orderly one.

## Measurement

- **Read both latencies.** `most load` reports service time and response time; quoting only the
  first is coordinated omission. `pacing lateness` says whether the generator was the bottleneck.
  → R§13
- **Two things silently invalidate a run:** a `maxOrders` too small for the rate, and a price band
  outside the static collar or the ladder. Both show as reject counts in the summary.
- **The `.hgrm` files from `run-attribution.sh` exist to be diffed.** Re-run it either side of a
  change to the core. Set `ATTRIBUTION_DIR` — a run wipes its directory — and compare against
  `docs/baselines/`, which is where the A1–A4 histograms live so a `./gradlew clean` cannot take them.
- Every measurement to date is **single-node**. Say so when quoting one, and say how many
  securities: the fan-out result is that **the shard's ceiling is aggregate, not per-security** —
  ~350k/s across ten is the same aggregate one book reached, so Design.md §2's 1M/s/shard target is
  over-stated by ~2.9x (Measurements.md R5), or ~1.8x with a `DEDICATED` driver (R7). Both figures
  are with UDP egress in 1,408 B datagrams. With IPC egress one node carries 1M/s at an 83 µs median
  and knees at ~1.5M/s (K1–K5). Say which egress channel a rate was taken with.
- **Above ~1.5M/s one `most load` is at its own limit.** Its pacing p99.9 passes 1 ms, so a rate
  there measures the harness too. Check pacing before believing a knee up there.
- **State the driver threading mode with any rate.** `most cluster` defaults to `ThreadingMode.SHARED`,
  which caps the shard ~1.6x below `--driver-threading DEDICATED` and costs 81x on p50 at the edge. A
  benchmark or a production node sets `DEDICATED`; leave `--archive-threading` alone, since a dedicated
  archive thread behind a shared driver thread is measurably worse than both shared. → Design.md §7
- **`most counters` reads the driver's, archive's and cluster's counters with no Aeron client**, so it
  is safe to point at a shard under load. It is what named the driver thread; use it before theorising
  about where time goes. Sample **above** the knee with a run long enough that the window is steady
  state, not a draining queue.
- **A clean counter sheet does not mean nothing is saturated, and CPU% cannot fill the gap.** Aeron
  reports queues, positions and stalls, so a stage that is merely *full* breaches nothing; and the
  engine, gateway and market-data all use `BusySpinIdleStrategy`, so each reads ~100% of a core whether
  working or idling. Read their utilisation from the **`duty-ns` counters** (`most counters --match duty
  --interval-ms 1000`; metrics on, `most cluster --duty` for the driver, archive and consensus module),
  never from a run switched to a yielding strategy — that moves the knee it measures. A batching loop
  (the driver's sender and receiver) reads near 100% long before it is full. → R§13, Design.md §7
- **Before calling the engine full, read the egress publication's headroom.** The engine spins on
  `tryClaim` inside `onNewOrder`, so a full egress publication reads as 100% duty and as dearer
  `admit`/`match` stages. What binds is the driver's UDP sender, one ≤MTU datagram per publication per
  duty cycle. Read `pub-lmt − pub-pos` on stream 102 (≤ 0 is back-pressured), not `snd-bpe`, which is the
  receiver's window. `EGRESS_CHANNEL=aeron:ipc` or `…|mtu=8192` removes the step (Measurements.md E1–E14).
- **A figure holds at the rate it was measured.** "The sender is ~2% busy" was true at 100k/s and
  reversed at the knee. Measure a stage *at* the rate you draw a conclusion about.
- **An unpinned run on an `isolcpus` host measures nothing** — unpinned, the whole shard shares the
  few non-isolated cores. Pin (`PIN=`, `e2e/pin.sh`) or boot without `isolcpus`.
- **Corroborate a cluster-host `duty-ns` reading against `/proc/<pid>/task/<tid>/stat`** before
  quoting it. The driver's sender read 92–97% where the kernel charged ~2% (Design.md §8).
- **Fill in `Measurements.md`'s `idle` column honestly, and never compare two configurations across
  two machine states.** A sweep taken with a desktop open read the knee 15% low and put that figure
  in four documents; the storage experiment that followed looked like an 82x win until an idle
  baseline on the other medium was taken the same day, when it became nothing. → R§13
- **`run-sweep.sh`'s `RATES` are aggregate across `SECURITIES`.** A rate the shard could not keep up
  with is marked `SATURATED`: its `achieved` figure is an *offer* rate and its latency is a draining
  queue, never a round trip. Do not quote one as a latency.
- **Append a row to [`docs/Measurements.md`](docs/Measurements.md)** for any figure that reaches a
  document — it carries the machine, load, build and rate that make two numbers comparable.
- **Run a discard pass before the first measured rate.** A cold JVM stalls the *generator*, and the
  first rate in a sweep then wears a cost that is not a property of that rate. `e2e/run-sweep.sh`
  does this, and marks any rate whose pacing lateness or reject count says it measured something
  other than the shard.
- The `perf-claim` skill is the full procedure.

## Zero allocation

- **Proven by three measurements that must all stay green:** `AllocationTest` (fakes),
  `AeronAllocationTest` (embedded media driver — reaches book-event publication and `onTakeSnapshot`,
  which a fake cannot), and `e2e/run-epsilon-soak.sh` (real Epsilon binary, real cluster). → R§2
- **The criterion is a strict majority of eight windows reading exactly zero**, plus under one byte
  per operation. **Do not widen this to make a failure go away** — add rounds, not tolerance.
- **The inline matching and purge functions read the pool and ladders directly**, so those
  members are `@PublishedApi internal`, not `private` — a public `inline fun` cannot touch private
  members.
- **Check a primitive collection actually exists before designing around it.** Agrona has no
  `Long2IntHashMap`; the id map is a `Long2LongHashMap` with the node index widened (Design.md §2).
- **Five `inline` keywords are load-bearing:** `matchAggressive`, `offerToSnapshot`,
  `publishBookEvent`, `OrderBook.forEachOccupiedLevel` and `OrderBook.cancelParticipant`. Each can be
  removed without a compiler warning, and each is caught by the test covering its path and no other
  (`cancelParticipant`'s by `AllocationTest`'s bulk-cancel case, 8 of 8 windows).

## Performance budget

100k orders/sec/security × 10 securities = a **1 µs aggregate budget per order**. The engine meets it
— 0.42 µs measured for a whole new order — and cache misses dominate that figure, which is why the
order pool is cache-line packed. Design.md §2 has the stage-by-stage breakdown and the ~1.0 GB/shard
memory footprint, which makes huge pages mandatory, not optional.

**The budget is met; the target is met only with IPC egress, on one node.** With UDP egress the
measured ten-security ceiling is **~350k/s aggregate** with the default driver threading and **~550k/s
with `DEDICATED`**, not 1M/s, and it is the *same* aggregate one book reached. Attribution puts the gateway and engine at 0.9–2.0% of a
round trip and shows the engine getting *faster* per order as the rate rises, so **the constraint is
the shared path every order crosses** and not matching. **Do not treat a per-order improvement as a
throughput improvement**; prove it with a sweep. Eliminated by measurement: the archive write (RAM
disk, twice), the ingress term length (16m moved latency, not the knee) and huge pages (~3%). **What
binds is the media driver's UDP sender on the egress stream**, and the engine is back-pressured behind
it. With egress on IPC one node carries 1M/s for 8 s at an 83 µs median, and knees at ~1.5M/s. By
2.1M/s the engine thread itself is full at 0.43 µs a new order (Design.md §2; Measurements.md K1–K5).
IPC egress needs the gateway on the leader's media driver.

## Build and deployment

- **Warnings are errors** in every module, `control` included. Do not disable it to get a build
  through — fix the warning. Reserve `inline` for functions taking callbacks. → R§14
- **Any JVM running this code needs `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` and
  `--add-opens java.base/sun.nio.ch=ALL-UNNAMED`** (Agrona 2.x, Aeron driver). Already set on the
  test and run tasks; without them the first `UnsafeBuffer` throws `IllegalAccessError`.
- **The native-image flags live in the root `build.gradle.kts`, not per module.** Two of them fail
  *silently* when missing — `--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED` and
  `--initialize-at-build-time` for `org.agrona.UnsafeApi`. To check an image you cannot run:
  `grep -c 'jdk.internal.misc.Unsafe' <binary>` returns 0 when the exports were missing and 3 when
  they were not. → R§10
- **`--install-exit-handlers` is load-bearing in the native build.** Without it SIGTERM kills the
  process outright and no shutdown counter is ever printed. → R§10
- **Pin `-march=x86-64-v3`, never `-march=native`** — a mismatched build host SIGILLs in production.
- Native knobs live in `gradle.properties`: `engine.march` (CI/prod must set it) and
  `engine.useEpsilonGc` (off until a long soak runs and a GitLab runner is attached to
  `.gitlab-ci.yml`, whose `test:core` job is where the allocation proofs run).
- Run the Aeron `MediaDriver` as a separate process; it allocates.
- Snapshots must serialize only *occupied* orders by walking the ladders, never the whole 1M-slot
  pool.
- **In Docker, one media driver is one network identity.** Every UDP endpoint the shard exposes is
  bound in the `cluster-host` container and must name `shard0`, never the individual process's
  container. The dev stack swaps multicast for **dynamic MDC**; the publisher/subscriber channel
  asymmetry is correct, not a typo. → R§12
- **A cluster client's egress names its media driver's host, never `0.0.0.0`** — otherwise it
  connects and times out at `POLL_RESPONSE`. → R§12
- **Rebuilding the `most` image recreates `cluster-host`** (same image); inside the ~10 s mark-file
  window that takes the shard down. `up --build` skips it (`--profile cli build`); use
  `run --no-deps`. → R§12
- `deploy/`'s `client-aeron` volume is a **1 GB** tmpfs and needs to be. A full mount surfaces as
  `InternalError: a fault occurred in an unsafe memory access operation`.

## Skills

`.claude/skills/` holds the procedures this project runs on: `session-open`, `session-close`,
`wire-change`, `perf-claim`, `spec-first-test`, `decision-fork`, `verify-for-real`.

**Commits are the user's call.** Do not run `git commit`, `git add` or `git push` unless asked in
that turn — propose the message instead.
