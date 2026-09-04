# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository status

`docs/Design.md` is the authoritative specification — read it before implementing anything, and
update it alongside code when the design changes. §8 tracks the open questions.

The Gradle skeleton is in place and green: eight modules (`sbe`, `reference`, `discovery`, `engine`, `market-data`, `gateway`, `tools`, `control`),
SBE codegen wired, 300 tests passing. **Implemented so far:** `Domain.kt` (packed layout, bit-packing
helpers, reusable outcome scratch), `PriceLadder`, and `OrderBook` — booking, cancel validation,
continuous matching with both gates, the auction (price selection, SMP fixed point, allocation), and
the expiry purge; and `MatchingEngineService` — the full `ClusteredService`, message dispatch,
execution-report egress, book-event publication, and snapshot/restore; and `EngineConfig` /
`EngineMain` — the `ClusteredServiceContainer` wiring; `MarketDataService` / `DepthBook` — L1/L2/L3
derivation; `GatewayService` / `OrderStateStore` — validation and `cumQty` reconstruction; and
`reference` / `discovery` — the shared shard security list and the tradable-universe directory; and
`tools` — the `most` operator CLI, including `most load`, the paced load generator and latency
harness; and `control` — the Postgres-backed control plane that authors reference data and publishes
the specs every process boots from. All eight modules are implemented.

## Commands

A node is **two processes**: `io.aeron.cluster.ClusteredMediaDriver` (driver + archive + consensus
module) and this binary (service container only). The driver allocates, so it stays out of the engine
process. Boot config carries only geometry and capacity — reference prices and collars arrive as
`SecurityDefinition` commands through the log. **Every node must boot with identical geometry**;
`EngineConfig.fingerprint()` exists so that is checkable, since it is the one misconfiguration
consensus cannot catch.

`./e2e/run-restart.sh` is the only check that state survives a restart: it rests orders, snapshots,
stops the whole node, and restarts it on the same security file, on one with a security removed
while it holds orders (must refuse, non-zero), on one with an emptied security removed (must start),
and on one with a security added. `run-e2e.sh` wipes everything and starts fresh, so it has never
once shown that anything survives.

`./e2e/run-e2e.sh` (after `./gradlew installDist`) runs every process against a real single-node
cluster, drives a trade through the CLI, then runs a short `most load` to prove the harness still
correlates. Run it after changing anything on the wire: it has already caught five defects unit tests
could not: a clobbered Aeron session header, cumQty derived from a terminal report's leavesQty, an
unenforced ladder-range invariant, a gateway cluster session that died after 10s idle for want of
keepalives, and processes that never exited on SIGTERM. `docs/LocalTesting.md` is the manual
walkthrough, and its §9 is the benchmarking procedure.

**The archive and cluster directories are the shard's only resumption point, and they persist by
default.** `most cluster` wipes them only on `--fresh` (it used to wipe them unless told not to, so
every restart began from an empty book and said nothing about it). The **Aeron directory is
different and is always recreated** — it is memory-mapped IPC buffers and a `cnc.dat`, and keeping
it makes a restart fail with "Active media driver detected" until the previous driver's liveness
timeout expires. The Docker stack draws the same line by making the aeron volume tmpfs and
`cluster-data` a durable named volume.

**Nothing takes a snapshot unless something asks.** Without one a restart replays the log from
genesis, which is what Design.md §1 says the snapshot exists to avoid. `most cluster snapshot`
(`--ingress` through consensus, `--dir` through the local control toggle), `most cluster shutdown`
(snapshot then stop, which SIGTERM does not), and the control plane's scheduler at each session
close. **A node also cannot restart immediately after the previous one stopped** — Aeron's archive
and cluster mark files carry a liveness timestamp and a new process refuses until it ages out,
measured at roughly ten seconds here even after a clean shutdown.

**Restarting only the service container is not a recovery.** The consensus module keeps running and
replays the log to the new service from the beginning, rebuilding the same books by a completely
different route and taking as long as the session is old. `e2e/run-restart.sh` was written asserting
on the rendered book and passed for exactly this wrong reason; the engine now prints
`restored N resting orders ... from a snapshot` so the two are distinguishable, and the test asserts
on that line rather than on depth.

**A geometry change is reapplied by restarting, so `loadSnapshot` is where it is made safe.** It
reconciles the snapshot against the booted `ShardSpec` and **refuses to start** rather than lose
state: a security gone from the shard with resting orders, a changed `priceFloor`/`tickSize`/
`levelCount`/`maxOrders`, an order outside the new ladder, or counts that do not add up. The quiet
cases are the two legitimate ones — a security whose book was **empty** leaving the shard, and a new
security joining — each logged with a line. `SnapshotBook` carries the geometry and a per-book
`restingOrderCount` (schema v2, `sinceVersion="2"`) precisely so every decision can be taken at the
book header, before any of its orders have been booked.

**`Image.poll` swallows an exception from its fragment handler**, hands it to the client error
handler and advances the position anyway. So a bad order during a restore does *not* crash the node
— it is silently dropped and the book comes back quietly wrong. That is why the restore checks
`isLevelInRange` itself instead of letting `OrderBook.book()` index out of bounds, and why the
resting-order counts are reconciled as a backstop. Do not assume a throw on this path is loud.

**A refused restore leaves through the ShutdownSignalBarrier, not `Runtime.halt`.** Halting skips
`ClusteredServiceContainer.close()`, which leaves the service's cluster mark file carrying a live
timestamp — so the operator who fixes the security file and restarts immediately is met with "active
mark file detected" for the next ten seconds instead of a working node.

**After a snapshot recovery the market-data process has no book.** It derives everything from the
book event stream, and a restored engine republishes nothing for the orders it restored. The engine's
state is correct and trades against restored orders correctly; the derived L2 feed simply has no way
to learn it. Open issue — see `docs/Handover.md`.

**A cluster client must send keepalives.** The consensus module closes a session after
`sessionTimeoutNs` (10s default) of silence and every later offer fails silently; polling egress is
not enough. **`ShutdownSignalBarrier` must be closed** — `await()` alone leaves the JVM alive — and
anything printed at shutdown must be inside the barrier block, since closing it releases the signal
and the process exits at once.

**`ClientSession.tryClaim` reserves `AeronCluster.SESSION_HEADER_LENGTH` ahead of the payload.**
Encode at `claim.offset() + SESSION_HEADER_LENGTH`, never at `claim.offset()`. The test fake models
this; keep it that way or the tests validate a layout the cluster rejects.

```sh
./gradlew build                                    # compile, generate codecs, test
./gradlew :engine:test --tests '*PriceLadderTest*' # a single test class
./gradlew :sbe:generateSbeCodecs                   # regenerate codecs only
./gradlew :engine:nativeCompile                    # native binary (needs a GraalVM toolchain)
```

- **Never hand-write SBE byte offsets.** Edit `sbe/src/main/resources/message-schema.xml`; codecs
  regenerate into `sbe/build/generated/sbe/`. Keep the schema and Design.md §5 in step — the schema
  file was extracted from the doc and the two are meant to stay identical.
- **Warnings are errors** in every module, `control` included — it already caught a
  `java.lang.Long` where `kotlin.Long` belonged. This is deliberate: a silent "inline function cannot be
  inlined" would break the zero-allocation profile. Do not disable it to get a build through — fix
  the warning. Reserve `inline` for functions taking callbacks; on plain helpers Kotlin correctly
  warns it buys nothing.
- Native build knobs live in `gradle.properties`: `engine.march` (CI/prod must set it) and
  `engine.useEpsilonGc` (off until zero-allocation is proven). Both are explained in README.md.
- **The native-image flags live in the root `build.gradle.kts`, not per module.** All four native
  binaries link Aeron and Agrona, so the flags are a property of the dependency stack; a module's own
  `graalvmNative` block sets only its image name, main class and (engine alone) the Epsilon switch.
  Two of those flags are non-obvious and Design.md §7 explains why: **a missing
  `--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED` does not fail the build**, it silently
  omits the class and the binary dies on its first `UnsafeBuffer` — and only once a real Aeron CnC
  file exists, so a smoke test against a *missing* driver passes and hides it; and
  `org.agrona.UnsafeApi` must be `--initialize-at-build-time` (it reaches `Unsafe` through an
  `invokedynamic` site that runs its `<clinit>` during analysis) even though `UnsafeBuffer` beside it
  is `--initialize-at-run-time`. Neither can be inferred from the JVM's `--add-opens`.
  **To check an image you cannot run** (a cross-built container, say): `grep -c
  'jdk.internal.misc.Unsafe' <binary>` returns 0 when the exports were missing and 3 when they were
  not. Starting the binary without a media driver does *not* check this — it exits on the expected
  `DriverTimeoutException` before the first `UnsafeBuffer` is ever wrapped, so it passes either way.
- **`--install-exit-handlers` is load-bearing in the native build.** Without it SIGTERM kills a
  native image outright: `ShutdownSignalBarrier` never releases, the cluster service container is
  never closed, and every process's shutdown counters (the gateway's `droppedToClient`,
  market-data's `gaps`) are lost. The JVM start scripts get this for free, and `e2e/run-e2e.sh`
  only checks nothing died *during* a run — so it passed throughout while this was broken.
- **Zero allocation is proven, by three measurements that must all stay green.** `AllocationTest`
  (fakes, fast) covers order entry, matching, cancel, reject, the uncross and the purge.
  `AeronAllocationTest` launches an embedded media driver to reach the two paths a fake *cannot* —
  book-event publication and `onTakeSnapshot` — because `ExclusivePublication` is `final` with no
  interface. `e2e/run-epsilon-soak.sh` runs the real Epsilon binary against a real cluster.
  - The criterion is **a strict majority of eight windows reading exactly zero**, plus under one
    byte per operation overall. A rate produces *no* clean windows; a late JIT blip produces one or
    two. **Do not widen this to make a failure go away** — if a workload is so light that a blip
    dominates it, add rounds, not tolerance.
  - The soak measures a **slope across two runs**, not a total: ~95MB of pools at startup would
    otherwise swamp the per-order figure.
  - All three are mutation-validated. Removing `inline` from `matchAggressive`, `offerToSnapshot`
    or `publishBookEvent` compiles in silence — "warnings are errors" says nothing about it — and
    each is caught by the test covering its path and no other. If you touch one of those keywords,
    these tests are the only thing standing between you and Epsilon killing every node at once.
  - `engine.useEpsilonGc` is still off, but no longer for want of a measured path: what is missing
    is a CI to run the assertion in, and a soak measured in hours rather than seconds (the Aeron
    client conductor shares this heap, and a per-duty-cycle allocation would be invisible in 20s).
- **`e2e/run-e2e.sh` takes each binary path from an environment variable** (`ENGINE`, `GATEWAY`,
  `MARKETDATA`, `DISCOVERY`), so the same run drives native images instead of the JVM start scripts.
  The processes are identical on the wire; a native binary must pass it unchanged, with the same
  report and fill counts. That is the check, not that it started.

## Architecture (from docs/Design.md)

A low-latency deterministic matching engine. The invariants below drive nearly every implementation
decision, so violating one is usually a bug even when the code compiles and passes tests.

**Deterministic single-threaded state machine.** Each shard is one thread consuming Aeron Cluster's
Raft-replicated log. Every node runs the identical service over the identical log and must produce
byte-identical state.

- **No wall-clock calls.** `System.currentTimeMillis()`/`nanoTime()` are banned. Time comes only from
  `Cluster.time()`, the sequenced consensus timestamp, or from timestamps on sequenced commands.
  Cluster timers (`scheduleTimer`) are delivered through the log and are therefore deterministic.
- **Determinism cuts both ways.** A crash, an unvalidated array index, or a pool exhaustion throw
  kills *every* cluster node at the same log position simultaneously. Validate defensively and prefer
  rejecting an order over throwing.
- **No allocation on the hot path.** Native image will eventually run `--gc=epsilon`, where any
  steady-state allocation is fatal. Watch for: boxing through generic views (`Map.Entry`,
  `Array<Price>`), and `@JvmInline value class` parameters on any callback that isn't an `inline fun`
  — converting a match callback to a functional interface or object boxes on every fill.
- **Zero-copy publishing.** Use `Publication.tryClaim` and encode directly into the log buffer; do not
  encode into a scratch buffer and `offer` it.

**The control plane is authenticated; nothing else is.** It is the only process that *decides*
something — the others apply a replicated log or forward bytes. Everything under `/api` requires an
`ADMIN` operator (one role, full access) held in `control_user` as a BCrypt hash, with a session
cookie for the SPA and HTTP Basic for scripts. `operator_audit` records who asked for every
market-moving command and, like the REST layer, records **`sent`, not `applied`** — the engine
acknowledges nothing, so sent is all that can be claimed. `/api/auth/login` is itself CSRF-protected,
so the anonymous 401 must carry the `XSRF-TOKEN` cookie or no browser can ever log in;
`SecurityConfig` opts out of Spring Security's deferred token resolution for exactly that reason, and
`AuthBootstrapTest` runs a real Tomcat over the sequence because MockMvc hands every test a token and
so cannot distinguish this working from it being broken for every real browser. **`web/` is the Vue
frontend**, deliberately outside the Gradle build so `./gradlew build` stays npm-free; it is served
same-origin (Vite proxies `/api`) because the session and CSRF cookies are same-origin mechanisms.

**The control plane authors reference data; it is never on a boot path.** `control` owns the
Postgres schema for securities, shards and participants, and *publishes* immutable numbered releases
of the same shard security files and discovery registry the four processes have always read. A node
reads a file, never the database: an outage would stop a node starting, and a write landing between
two nodes' boots would give them different geometry — they would not fail, they would diverge on the
first order. **Never reimplement a domain rule there.** It builds real `SecuritySpec`/`ShardSpec`/
`ShardRoute`/`Universe` objects from its rows and calls their methods, so the ISIN check, the
wire-derived length limits, ten-per-shard, one-shard-per-security and `fingerprint()` have exactly
one implementation; a second that drifted by a separator would report agreement between processes
that disagree. `ShardSpec.render()`/`Universe.render()` are the inverses of the `from(Properties)`
parsers and live beside them so drift fails a round-trip test. Releases are immutable — republishing
allocates the next version — and carry topology only; Aeron dirs, cluster dirs and feed channels stay
in each process's own config. `docs/ControlPlane.md` is the walkthrough (§4 covers authentication), `web/README.md` the frontend,
and `deploy/README.md` the Docker dev stack. Its tests need Docker (Testcontainers Postgres), because most of what they assert is
schema behaviour.

**Operator commands are unacknowledged, and that shapes the control plane.** `SecurityDefinition`,
`SessionTransition` and `PurgeExpiredOrders` go to the gateway's client channel like any other
message and are forwarded into the log untouched; the engine applies or rejects them without
replying, and a rejected definition only increments `rejectedDefinitions`. So a sender may claim
only that bytes were sent. `control` separates `sent` from `confirmed`, confirming a phase from
`SessionChanged` on L3 and never claiming a definition was applied. Anything checkable before the
wire is checked there instead — a static band outside the ladder is refused locally, because the
engine refuses it in silence. **Encoders live in `reference`'s `OperatorCommands`**, shared by the
CLI and the control plane; do not write a second encoding of a wire message.

**The trading calendar lives in the control plane, and `onTimerEvent` stays unused.** Scheduling
outside the engine costs no determinism — the `SessionTransition` it emits is sequenced through the
log — and keeps holidays and DST out of the state machine. Each tick **reconciles** the phase the
calendar wants against the phase L3 reports, so it is idempotent and self-healing. Two rules it must
keep: **the difference between phases is a path, not a destination** (the uncross runs only on
`OPEN_AUCTION → CONTINUOUS`, so catching up walks the intermediate phases; sending `CONTINUOUS`
directly silently skips the auction), and **a halted security is never reconciled back open** —
recovery is operator-driven, and doing it automatically would also skip the auction. A cold cluster
has emitted no `SessionChanged`, so its phase is unknown; the scheduler waits rather than assuming
`CLOSED`, since a control-plane restart mid-session looks identical and assuming would shut a live
market. One session command establishes the baseline.

**A halt is visible only on L3.** `VolatilityHalted` is forwarded verbatim and nothing derives it
onto L1 or L2, so a depth subscriber cannot tell a halt from a scheduled close — which is why
`control` subscribes to L3. **Reopening is ordered and both orderings matter:** re-seed the
definition *before* `PRE_OPEN` (`staticReference` is only reset by an executing uncross, so a stale
collar rejects the very orders needed to reopen), then walk `PRE_OPEN → OPEN_AUCTION → CONTINUOUS`
in full (the uncross runs only on the last transition; jumping to `CONTINUOUS` silently skips the
auction). **A session transition is shard-wide** — there is no per-security session command, so
reopening one halted security reopens every book on the shard.

**In Docker, one media driver is one network identity.** The five shard processes share a media
driver through a tmpfs volume, and that driver runs in the `cluster-host` container — so every UDP
endpoint the shard exposes is bound there and must name `shard0` (an alias on that container), never
the individual process's container. `control=gateway:20002` fails with "Cannot assign requested
address". The dev stack also swaps multicast for **dynamic MDC**, because a Docker bridge does not
route multicast: a publication says `control=shard0:PORT|control-mode=dynamic` with no endpoint, and
what discovery hands a *subscriber* additionally carries `endpoint=0.0.0.0:0`. That asymmetry is
correct, not a typo. `deploy/README.md` has the rest.

**One security list per shard.** `reference`'s `ShardSpec` is read by the engine, gateway,
market-data and discovery alike — do not reintroduce per-process security lists. It carries identity
(`symbol`, `isin`, `name`, `currency`) and geometry; ISINs are check-digit validated at boot. Every
process prints `ShardSpec.fingerprint()` at startup, which is how a geometry mismatch is caught
before it silently diverges the books.

**An order the gateway cannot forward must not be consumed.** `AeronCluster.offer` returning
`BACK_PRESSURED`/`ADMIN_ACTION` is transient; treating it as a drop loses an order the client
believes it placed, with no ack and no reject. The gateway uses `controlledPoll` and returns
`Action.ABORT` so the fragment is offered again — and `Action.COMMIT`, never `Action.CONTINUE`, on
the handled path, since `CONTINUE` commits only at the end of the poll and a later `ABORT` would
rewind past fragments already forwarded and duplicate them. The pending `origQty` recorded before
the offer must be unwound when the offer does not send. A dead session is not retryable, so it
rejects with `GATEWAY_UNAVAILABLE`. **The outbound leg cannot do this** — egress must keep being
drained or the session dies — so it drops and counts; retrying there was measured and cost 10x on
the p99 while still dropping.

**The directory publishes the GATEWAY's client endpoints**, not the cluster ingress/egress — an
adapter connecting to the cluster directly would bypass the gateway's validation and `cumQty`
reconstruction.

**A gateway serves exactly one shard** — one cluster connection, and it rejects anything outside its
list. `discovery` publishes the universe (security → shard → ingress channel) on a repeating
multicast cycle so upstream adapters can route; `DirectoryClient` is what they embed. Discovery
enforces one shard per security, and stages each broadcast so a truncated cycle never replaces a good
routing table.

**Sharding.** Max **10 securities per shard**, max **1M orders per book**. `securityId` is validated
at the gateway against the shard map (and range-checked defensively in the engine). Books are fully
independent — no cross-instrument matching — so scale by adding shards, not securities per shard.

**Data flow.** Gateways → Aeron Cluster ingress → `MatchingEngineService` → execution reports via
cluster egress (leader only, automatic) **and** a raw *book event stream* on IPC 12 (leader only,
muted explicitly via `onRoleChange` since it is a plain publication, not egress). A separate **Market
Data Process** consumes stream 12 and derives L1/L2/L3, published as **SBE over UDP multicast**
(unicast as a bounded fallback) — the engine never formats market data.

**The L2 snapshot is taken on the poll thread, and a synchronised subscriber ignores one.** The
recovery feed (`DepthSnapshotBegin`/`Level`/`End` on its own stream) is what lets a consumer join
mid-session or recover from a gap. Two rules carry it: the image and the `l2SeqNum` stamped on it are
consistent only because no book event can land between reading the sequence and walking the ladders —
moving it to a timer thread produces a torn image no consumer could detect; and installing an image
over an already-synchronised book **rewinds** it, because increments past that sequence were applied
directly and never buffered. Consumers use `DepthFeedAssembler` in `reference` — do not write a second
one. `market-data` walks only occupied levels via the occupancy bitset, and an empty book still sends
a bracketed zero-level cycle, because "no liquidity" and "I cannot yet know" are different answers.

**Do not change Aeron's multicast flow control.** It defaults to `MaxMulticastFlowControl` (fastest
receiver governs), which is correct here; `MinMulticastFlowControl` would let the slowest subscriber
throttle the publisher and reintroduce exactly the coupling that moving market data out of the engine
was meant to remove. The trade-off is that slow subscribers take unrecoverable gaps, so gap detection
and snapshot re-synchronisation are subscriber responsibilities.

**Both boundaries are binary SBE, not FIX.** The `gateway` module is protocol-agnostic; FIX and
proprietary session protocols are handled by separate gateways upstream of it (and downstream of
`market-data`) that are outside this project. The gateway is nonetheless **stateful**: it holds the
`origQty` it forwarded so it can restore `cumQty = origQty - leavesQty` on the outbound leg, which is
precisely what lets the engine omit `origQty` and keep an order at one cache line.

**Two data structures carry the performance.**
- *Packed order pool*: a single `LongArray` with a stride of 8 longs, so each order is exactly one
  64-byte cache line and costs one cache miss. Field offsets are in `OrderField`; `next`/`prev` and
  `expireDate`/`side` are bit-packed into words 6 and 7.
- *Price ladder*: per side, a flat array of levels indexed by ticks from a floor, each holding a FIFO
  head/tail plus aggregate qty, with a `LongArray` occupancy bitset so next-best-price is a
  `countLeadingZeroBits`/`countTrailingZeroBits` word scan. The ladder range is configured strictly
  wider than the static circuit breaker band, so band rejection always fires before ladder overflow.

`leavesQty` is a **stored field on the order**, not derived at publish time — partially filled
resting orders match on their true remainder, and fill reports must track a running remainder rather
than `origQty - thisFill`. `origQty` is deliberately *not* stored (the eight words are full); the
gateway derives `cumQty` from the `origQty` it sent and the `leavesQty` the engine reports.

The inline matching and purge functions read the pool and ladders directly, so those members are
`@PublishedApi internal`, not `private` — a public `inline fun` cannot touch private members.

**Two reference prices, not one.** `staticReference` is seeded by `SecurityDefinition` and reset by
every *executing* uncross (including the reopening auction after a halt — otherwise the security
could not reopen, since the collar would reject orders at the new price level). `dynamicReference` is
seeded the same way but tracks **every** trade. The static collar anchors on the former and gates
order *acceptance* (`PRICE_OUT_OF_BOUNDS`); the dynamic collar anchors on the latter and gates
*execution* per price level. An uncross producing no trade leaves both untouched.

The dynamic collar is measured against a **snapshot** of `dynamicReference` taken when the aggressing
order arrived — never the live value, which that order's own fills are advancing; re-reading it would
let one order ratchet arbitrarily far. On breach: the breaching fill is suppressed, prior fills stand,
the aggressor's remainder is canceled (`VOLATILITY_HALT`) and not booked, the phase goes to `CLOSED`
with the resting book intact, and a `VolatilityHalted` event is emitted. No automatic recovery.

**Self-match prevention.** The effective SMP id is `smpId != 0 ? smpId : participantId`, resolved
once at order entry and stored (word 3). The **aggressor's** `smpStrategy` governs, defaulting to
`CANCEL_AGGRESSOR` (stop matching, cancel the aggressor's remainder, leave the resting order);
`CANCEL_RESTING` cancels the resting order and continues. Only these two strategies exist. Within
matching, the dynamic collar is a *level* gate checked first; SMP is an *order* gate inside the
level. A self-match never prints a trade, so it never moves either reference.

**The auction is uncollared** — neither collar constrains the uncross price. That is what lets a
halted security reprice and reopen. It is not unbounded in practice, since every participating order
passed the static collar at acceptance. **Recovery caveat:** `staticReference` is not reset until the
reopening uncross *executes*, but orders are accepted from `PRE_OPEN` onward, so a halt that moved
price outside the static band deadlocks — the orders needed to reopen get rejected before the auction
that would reset the anchor can run. `SecurityDefinition` may be re-issued at any time to re-seed the
references; doing so before `PRE_OPEN` is a required halt-recovery step, not an exception.

**SMP applies in the opening auction too, which makes the uncross a fixed-point loop** rather than a
single computation: cancelling removes volume → the uncross price moves → a different set of orders
crosses. An auction has no aggressor, so the **later-arriving** order of a self-matching pair (higher
`exchangeOrderId`) plays that role and its strategy governs. One two-cursor allocation walk resolves
every collision at a given price — cancelling just advances that side's cursor, which is exactly a
recompute at the same price — so a walk is only re-run when the *price* moves. Terminates because
each iteration cancels at least one order from a finite book; a configured pass limit is a safety
valve, not part of the algorithm.

**Session lifecycle.** `CLOSED → PRE_OPEN → OPEN_AUCTION → CONTINUOUS`. There is no
`VOLATILITY_HALT` phase — a halt transitions the security to `CLOSED` and recovery is
operator-driven. Orders are **accepted and booked in every phase except `CLOSED`**; only
*matching* is gated on `CONTINUOUS`. A crossed book outside continuous trading is normal and is
resolved by the uncrossing algorithm (§4.2: max executable volume → min imbalance → surplus side →
nearest reference price), which runs on the `OPEN_AUCTION → CONTINUOUS` transition.

**Expiry is date-based only; intraday expiry is not supported.** Orders carry `expireDate`
(YYYYMMDD int32; `0` = GTC) and are expired when `0 < expireDate < currentTradingDate`. The purge is
its own sequenced command run well before `PRE_OPEN`, and it walks the **price ladders**, not the
`Long2IntHashMap` — that map compacts its probe chain on removal, so iterator-based removal can
silently skip entries.

**Capacity policy.** Exceeding the pool high-water mark rejects with `BOOK_CAPACITY`. Never let the
pool drain to a throw.

**Any JVM running this code needs `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` and
`--add-opens java.base/sun.nio.ch=ALL-UNNAMED`** (Agrona 2.x buffer intrinsics, Aeron driver). Already
set on the test and run tasks; without them the first `UnsafeBuffer` throws `IllegalAccessError`.

**Every book event carries a `seqNum` and a `shardId`.** The sequence is stamped by the engine where
the event is generated, so it is deterministic across nodes and survives failover; it is snapshotted
with `nextExchangeOrderId`. This makes L3 a verbatim byte-forward rather than a re-encode.
`OrderRemoved` carries `leavesQty` so the whole L2 aggregate is derivable from the event stream
without shadowing per-order state.

**Feed sequences are namespaced by shard.** Each shard numbers from 1 independently, so several
shards can share one multicast group and subscribers track a sequence per shard —
`FeedSequenceTracker` in `reference` does this, distinguishing a gap from a replay and baselining on
the first message seen. `shardId` must stay on the *book event* (not only the derived feeds) because
L3 is forwarded verbatim. The market-data process also uses it to reject foreign-shard events, which
catches being pointed at the wrong engine.

**Execution reports route by `participantId → clusterSessionId`**, learned from inbound traffic — a
session belongs to a gateway, not a participant, and the maker side of a fill needs a route back.
Undeliverable reports are counted and dropped, never blocked on.

**Agrona has no `Long2IntHashMap`** despite what earlier drafts assumed — the id map is a
`Long2LongHashMap` with the `int` node index widened to a `long`. Check a primitive collection
actually exists before designing around it.

**Prices and quantities** are fixed-point `int64` with 8 implied decimals. Never introduce floating
point into pricing or matching arithmetic.

**The engine and gateway time their own hot paths, and the engine reads `nanoTime` to do it.**
That is allowed against §1's ban because the ban is on time *influencing replicated state*, not on
observing it. The rule, and the test for any probe added later: **enabling metrics on one node and
not another must be incapable of changing the log, the books, or a snapshot.** It holds only while
the histograms stay write-only — never read by a branch, never snapshotted, never on a feed. Metrics
are therefore excluded from `EngineConfig.fingerprint()` on purpose (they are node-local), and
`MetricsDeterminismTest` compares an instrumented engine against an uninstrumented one report for
report. Recording allocates nothing, and `AllocationTest` covers the instrumented path — instrumentation
that allocated would break the zero-allocation property on exactly the runs being measured.
`engine.metrics` / `engine.metrics.stages` / `gateway.metrics`, off by default, on in `deploy/` and
`e2e/`. Summaries print at shutdown, so they need an **orderly** one: in `EngineMain` the prints must
stay *inside* the `ShutdownSignalBarrier` block, because closing it releases the signal and the
process exits at once.

**`e2e/run-attribution.sh` splits a round trip by stage.** First result: the gateway and engine own
**0.8 µs of a 55 µs** round trip (1.4%); the rest is consensus, the archive write and the wire. The
engine's whole-message p50 of 0.42 µs is inside Design.md §2's 0.5 µs estimate, which had never been
checked against a running binary. Re-run it either side of a change to the core; the `.hgrm` files
exist to be diffed.

**Measure with `most load`, and read both latencies.** It reports *service time* (from the actual
send) and *response time* (from the scheduled send); quoting only the first is coordinated omission
and hides exactly the queueing that appears at the rate one is trying to find. `pacing lateness` says
whether the generator itself was the bottleneck. Its schedule is absolute — `start + i * delay` —
because a relative sleep drifts and then catches up in bursts. Two things silently invalidate a run:
a `maxOrders` too small for the rate (everything becomes `BOOK_CAPACITY`) and a price band outside
the static collar or the ladder. Both show as reject counts in the summary, which is why the summary
prints them. `docs/LocalTesting.md` §9 is the walkthrough.

## Performance budget

100k orders/sec/security × 10 securities = a **1 µs aggregate budget per order**. The design
estimates ~0.5 µs, so roughly 2x headroom at full fan-out. Cache misses dominate — that is why the
order pool is cache-line packed. See §2 for the stage-by-stage breakdown and the ~0.9 GB/shard memory
footprint (which makes huge pages mandatory, not optional).

## Build and deployment notes

- Pin `-march=x86-64-v3`, never `-march=native` — a mismatched build host SIGILLs in production.
- Ship with Serial GC first, prove zero steady-state allocation under load, add an allocation
  assertion to CI, *then* switch to `--gc=epsilon`.
- Run the Aeron `MediaDriver` as a separate process; it allocates.
- Snapshots must serialize only *occupied* orders by walking the ladders, never the whole 1M-slot pool.
