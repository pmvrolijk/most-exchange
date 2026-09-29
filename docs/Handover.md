# Handover — archive

**Not the session entry point.** [`Status.md`](Status.md) is: where things stand, open issues, what
to do next, and how to pick the project up. This file is the record of *how* the system got here —
the work records, the decisions that are load-bearing, and what went wrong along the way. Read a
section of it when you need the reasoning behind a particular change; do not read it to orient.

Section numbers are stable and are cited from `CLAUDE.md`, `docs/Design.md` and `docs/Rationale.md`,
so they are never renumbered. §1, §4, §5 and §7 moved to `Status.md` and their headings are kept
below as pointers.

**The chronology this file records**, most recent first: the session that fixed the control plane's
feed-gap counting, drove ten securities at once for the first time and attributed the result —
fan-out buys no throughput, Design.md §2's 1M/s/shard target is over-stated by about 2.9x, and the
ceiling was the media driver's single shared thread — 1.6x of throughput behind one enum (§2i). Before that,
the session that moved `origQty` into the
engine, deleted the gateway's order journal, and thereby turned gateway HA from an open design
question into a deployment choice (§2h) — along with a participant registry the control plane
authors and both node processes re-read while they run. Before that, the session that bound a
participant to its session at session open, from an authenticated gateway principal, so a maker that
has gone quiet is still sent its own fills (§2g). Before that, the session that gave the gateway an
order journal (§2f) — the thing §2h has now deleted. Before that, the session that closed the two
places recovery left the system confidently wrong: market data with no book, and the gateway
reporting `cumQty = 0` for an order it never saw (§2e). Before that, the session that made a restart
resume — durable cluster directories, a snapshot that something actually asks for, and a restore
that refuses to destroy state when geometry changes (§2d). Before that: hot-path instrumentation and
the round-trip attribution (§2c), native images (§2a) and the zero-allocation proof (§2b).

---

## 1. Where things stand

**Moved to [`Status.md`](Status.md) §1.** Kept as a pointer so the section numbering below is stable.

---

## 2. What is real, and what is a skeleton

### Implemented and tested

| Component | Notes |
| --- | --- |
| `OrderBook` | Packed pool, price ladder, matching, cancel, auction uncross with the SMP fixed point, expiry purge. No stubs. |
| `MatchingEngineService` | Full `ClusteredService`: dispatch, egress, book events, snapshot and restore. |
| `MarketDataService` / `DepthBook` | L1/L2 derivation, L3 verbatim forward, per-shard gap detection, **the periodic L2 snapshot cycle**. |
| `GatewayService` | Validation and translation, and **no per-order state at all** (§2h). Backpressure propagation via `controlledPoll`. |
| `reference` | Shard security list with ISIN check digits, universe registry, directory codec, `FeedSequenceTracker`, `PriceCodec`, `OperatorCommands`, spec renderers, **`AggregatedBook` + `DepthFeedAssembler`: the one consumer-side book and the one snapshot/increment splice**. |
| `discovery` | Universe assembly and broadcast. **Still zero tests of its own** — its logic lives in `reference`, which is tested, but the process is only exercised by the e2e script. |
| `tools` | `most`: securities, send, cancel, book, define, session, purge, cluster, **load**. |
| `ParticipantRegistrySource` | The registry re-read under a running consensus module and engine, swapped behind a volatile reference (§2h). |
| `control` | Postgres schema (5 migrations), REST CRUD, release publishing, Aeron client for operator commands, discovery + L3 monitoring, halt recovery, session scheduling, authentication and the operator audit, **live books rebuilt from L1/L2/snapshot and streamed to the console over SSE**. |
| `deploy` | Docker Compose dev stack: control plane, Postgres, SPA and one full shard, on three networks. Verified end to end — cold start to a completed trade. |
| Native images | `engine`, `gateway`, `market-data` and `discovery` all build with `nativeCompile`, and `e2e/run-e2e.sh` passes with all four substituted for their JVM start scripts. See §2a. |
| Allocation | Zero on every measured path, including the snapshot walk and book-event publication. Three harnesses, all mutation-validated. See §2b. |
| Instrumentation | The engine and gateway time their own hot paths, off by default. `e2e/run-attribution.sh` splits a round trip by stage. See §2c. |
| `web` | Vue 3 + TypeScript SPA: login, session guard, CRUD over shards, securities, participants and operators, release publishing and import, the schedule editor, the operations screen driving the four market-moving commands, and **a live book ladder per security**. Not wired into Gradle. |

### Measured

`most load` drives a shard at a fixed rate and reports round-trip latency and throughput. On one
development machine — single node, IPC, everything co-located, `maxOrders=1000000`:

| Rate | ack p50 | ack p99 | unanswered |
| --- | --- | --- | --- |
| 50k/s | 39 µs | 291 µs | 0 |
| 100k/s | 47 µs | 593 µs | 0 |
| 200k/s | 61 µs | 1.1 ms | 0 |
| 333k/s | collapses | | 123k |

The knee is between 200k and 333k orders/sec and the collapse is abrupt rather than gradual.

The Docker stack was later swept the same way and sustained its target to **200k orders/s** with
nothing dropped and nothing unanswered — throughput is not what a VM costs, latency is (§6). It also
showed p50 *lower* at 100k/s than at 3k/s, which is a fixed per-wakeup cost being amortised rather
than queueing.

**Read all of that as one security, because it is.** Every run to date has driven a single book. The
design's target is 100k/s/security across **ten** — 1M/s per shard — and the knee above is 3–5x short
of that on a path that includes Raft and the archive's disk write.

The round trip is now split by stage (§2c), and for one book it says the ceiling is **not** the
engine: the engine and gateway together own 1.4% of it. That closes open issue 16 and sharpens 17 —
the question is no longer "engine or plumbing" but whether a single-threaded shard still looks like
that at a tenfold fan-out, which nobody has tried. `docs/LocalTesting.md` §9 before quoting any
number here.

### 2a. Native image — built and exercised

All four core processes now compile to native binaries and the full end-to-end suite passes with
every one of them substituted for its JVM start script. The evidence that matters is not that they
started but that they behaved identically: the load run reports **10,944 reports, 5,000 new, 5,142
trades, 802 cancels, 16,368 qty traded, 0 unanswered** — the same numbers, to the order, as the JVM
run against the same seed.

```sh
./gradlew :engine:nativeCompile :gateway:nativeCompile \
          :market-data:nativeCompile :discovery:nativeCompile

ENGINE=$PWD/engine/build/native/nativeCompile/matching-engine \
GATEWAY=$PWD/gateway/build/native/nativeCompile/order-gateway \
MARKETDATA=$PWD/market-data/build/native/nativeCompile/market-data \
DISCOVERY=$PWD/discovery/build/native/nativeCompile/discovery \
  ./e2e/run-e2e.sh
```

Each binary is ~15–16 MB and takes ~15 s to compile. `run-e2e.sh` takes each path from an
environment variable now, precisely so this is a re-runnable check rather than a story about one
afternoon.

**`--install-exit-handlers` was missing, and that was a real defect.** Without it a native image
takes SIGTERM's default disposition and dies on the spot: `ShutdownSignalBarrier` never releases, the
`ClusteredServiceContainer` is never closed, and every process's shutdown counters — the gateway's
`droppedToClient`, market-data's `gaps` and `snapshots` — are lost. All four native binaries were
built this way and `e2e/run-e2e.sh` passed throughout, because it only checks that nothing died
*during* a run. It surfaced only when the Epsilon soak needed a graceful stop to get an allocation
summary out of the process. This is the same defect class as "no process exited on SIGTERM" in §6's
first table, reintroduced by a different toolchain.

**No `reflection-config.json` was needed** — Design.md §7 assumed one and was wrong. What was
actually needed is written up there in full; the two findings worth carrying are in §6.

**Latency is not the reason to do this.** At the e2e's 5k/s the native and JVM runs are
indistinguishable (p50 209 µs against 206 µs), because at that rate the measurement is the plumbing.
Native image buys startup and the absence of JIT warm-up, and it is the precondition for Epsilon —
not, on this evidence, throughput. Nothing here justifies a native-versus-JVM latency claim.

**The production container builds too.** `deploy/Dockerfile.core --target native --platform
linux/amd64` produces a 141 MB image carrying all four binaries at `-march=x86-64-v3`, in 9 minutes
under emulation. Three defects in that stage were found by building it: a missing `findutils` (the
Gradle wrapper died on `xargs is not available`), `:tools` and `:control` not copied in (Gradle
refuses to configure a project whose directory is missing), and `--platform linux/arm64` being
unbuildable at all — that image's aarch64 JVM SIGILLs in `System.registerNatives` before reaching any
of this project's code, on tags 21, 22 and 23 alike, and only tag 24 runs. `deploy/README.md` has the
detail.

**What the container has not done is trade.** A `x86-64-v3` binary will not start on Docker Desktop's
emulated amd64 — it refuses cleanly, listing the AVX2/BMI2 features the emulator lacks, which is the
mismatch Design.md §7 predicts caught by a startup check rather than as the SIGILL the doc expects. A
baseline `--build-arg ENGINE_MARCH=x86-64` image is what makes the containers runnable here, and on
that image all four binaries start, validate their config and print
`fingerprint=3e04cf2b9902f08a` — the same fingerprint as the JVM build and the host arm64 native
binary, across three toolchains and two architectures.

**But a driverless start proves less than it looks like** (see §6), so the container was checked a
second way:

```sh
grep -c 'jdk.internal.misc.Unsafe' /opt/most/bin/matching-engine   # 3 = present, 0 = broken image
```

A binary built without the `--add-exports` flags returns **0** — the class is genuinely absent from
the image — where a correct one returns 3. That distinguishes a working image from one that will die
on its first `UnsafeBuffer`, without needing a media driver, and it is the check to run when the
build host and the run host differ. Driving the compose stack on native containers end to end is the
remaining gap, and it wants an x86-64 host to be worth much.

### 2b. Zero allocation — proven, by three measurements

Design.md §7 stages Epsilon behind "prove zero steady-state allocation empirically". That is done,
and deliberately done three ways, because each is blind to something the next can see: fakes are
precise but cannot touch Aeron, a real driver reaches those paths but only in a test, and the soak
runs the shipped binary but can only bound a slope.

**`engine/src/test/kotlin/.../AllocationTest.kt`** — precise, per call, runs in `./gradlew build`.
It drives `MatchingEngineService` through fakes written to allocate nothing themselves and reads
`ThreadMXBean.getCurrentThreadAllocatedBytes`. Order entry, validation, continuous matching, partial
fills, booking, cancellation, rejection, the **opening uncross** and the **expiry purge** all measure
zero. What it cannot reach is the book-event publication: `bookEventPub` is an Aeron
`ExclusivePublication`, unfakeable without a live media driver.

**`engine/src/test/kotlin/.../AeronAllocationTest.kt`** — the two paths a fake *cannot* reach.
`ExclusivePublication` is a `final` class with no interface, so `publishBookEvent` and
`onTakeSnapshot` cannot be driven by a double at all; this test launches an embedded media driver and
measures the shipped code writing real bytes through real `tryClaim`, draining the streams on a
separate thread so back-pressure does not become the measurement and the drainer's own allocation is
not counted. Both are zero, the snapshot at 2,000 resting orders per walk.

**`e2e/run-epsilon-soak.sh`** — whole system, no fakes, less precise. It runs the real Epsilon-built
binary against a real media driver, Raft cluster and archive. Result: **0 bytes per order across 1.9
million orders**, with SubstrateVM's reporting resolution bounding the true figure below 0.006
bytes/order.

Two things about the soak's method are worth keeping:

* **It measures a slope, not a total.** The order pool and id map are ~95MB before a single order
  arrives. Reported as a total over 200k orders that reads as ~490 bytes/order, which is a fixed cost
  misreported as a rate — the same trap the unit test hits with late JIT. So the stack runs twice at
  two order counts and the per-order figure is the difference over the difference; startup is
  identical in both and cancels.
* **It needs the engine stopped gracefully, not killed.** SubstrateVM prints its allocation summary
  on exit, so `kill -9` loses the measurement. That only works because of `--install-exit-handlers`
  (§6) — which is how that defect was found.

**All three were validated by mutation, and none is trustworthy without that.** Three `inline`
keywords were removed in turn — `OrderBook.matchAggressive`, `offerToSnapshot`, `publishBookEvent` —
each of which compiles in silence, since "warnings are errors" has nothing to say about a function
that is merely no longer inlined, and each of which then boxes its callback's captured state. Every
one is caught, by the test covering its path and by no other: 232 bytes an order on the match path,
~24 bytes per resting order on the snapshot walk, 152 bytes an order in the soak.

**The criterion had to be loosened once, and the reason is worth keeping.** It began as "at most one
of eight windows may be non-zero". That held when a test ran alone and failed when the suite ran
together, because there is more JIT compilation in flight and blips arrive in twos and threes. It is
now "a strict majority of windows must read exactly zero", plus a magnitude backstop of under one
byte per operation. That is not a weaker claim: a rate of even one byte per operation is tens of
thousands of bytes per window and produces **no** clean windows at all, so the majority rule cannot
admit one. Five consecutive full runs of the engine suite pass with no failures.

**What is still not measured, and why Epsilon is still off.** Every steady-state path of the
engine's own code is now covered, the snapshot included. What is not covered is **time**. The soak's
slope comes from runs of 1s and 20s, so it bounds allocation per order *and* per second over that
range — but not over a trading day. The Aeron **client conductor** runs in this process on its own
thread, and under Epsilon a heap is a heap: a conductor allocating a little per duty cycle would be
invisible in 20 seconds and fatal after some hours. Failover and log replay are likewise unmeasured;
`loadSnapshot` walks every order on restore.

So the flag stays off, but the reason has changed. It is no longer an unchecked path in the engine —
it is that §7's step 3, a CI that fails the build on a regression, does not exist yet, and that the
evidence covers seconds rather than hours.

### 2c. Latency, attributed

`most load` has always measured the whole client round trip and nothing smaller, so its ~55 µs p50
could not distinguish a slow engine from slow plumbing. Both are now instrumented (Design.md §7) and
`e2e/run-attribution.sh` does the subtraction. At 100k/s, 2M orders, one security, single node, IPC,
JVM on Serial GC:

```
  client round trip            55.4 us
  gateway inbound               0.2 us
  engine (whole message)        0.4 us
  gateway outbound              0.2 us
  ------------------------------------
  in this shard's processes      0.8 us  (1.4%)
  everything else              54.6 us  (98.6%)
```

**Two results worth carrying.** The exchange's own code is 1.4% of a round trip — consensus, the
archive write and the IPC hops are the other 98.6%, which means tuning the matching engine would
move almost nothing and open issue 16 is answered. And the engine's whole-message p50 of **0.42 µs**
is inside Design.md §2's **0.5 µs** estimate, which had stood unverified since the first commit.

The stage split (`engine.metrics.stages=true`) gives admit 0.08 µs, match 0.04 µs, settle 0.17 µs at
the median. `settle` costing more than `match` is the reverse of what §2's table implies, and the
reason is that half these orders rest rather than trade — resting is what `settle` does.

**The instrument is inside its own measurement**, and the summary says so: it prints its measured
clock cost (~10 ns here), which is ~1% of a 0.42 µs figure at boundary level and ~5% with stages.
That is also the answer to why §2's finer rows are not timed individually — a 2 ns book lookup
cannot be measured with a 10 ns clock, and pretending otherwise would produce a number made mostly
of `nanoTime`.

**The determinism exception is checked, not argued.** The engine reads `System.nanoTime()`, which
§1 bans. The ban is on time influencing replicated state, so the invariant is: *enabling metrics on
one node and not another must be incapable of changing the log, the books, or a snapshot.*
`MetricsDeterminismTest` drives two engines through an identical sequence, one instrumented at the
deepest level and one not, and compares every execution report, every resting order and both
sequence counters. Mutating the engine to shed load based on a measured latency — the plausible
version of this mistake — fails it. Metrics are excluded from `EngineConfig.fingerprint()` for the
same reason: they are node-local by design, and an operator may turn them on for one node to
diagnose it.

### 2d. A restart that resumes, and a geometry change that cannot destroy state

The engine's snapshot has been complete and allocation-tested since it was written. Three separate
things meant it never did anything, and a fourth meant that when it finally did, a geometry change
would have quietly destroyed the state it restored.

**The resumption point was deleted on every start.** `ClusterCommand` read `val fresh =
!args.has("keep")` and drove `deleteArchiveOnStart`, `deleteDirOnStart` and `dirDeleteOnStart` from
it. Neither the Docker stack nor any e2e script passed `--keep`, so every `docker compose up`
destroyed the recorded log and every snapshot with it — while `cluster-data` sat there as a durable
named volume doing nothing. The default is inverted: persist unless `--fresh`.

The Aeron directory is deliberately **not** included in that. It is memory-mapped IPC buffers and a
`cnc.dat`, and persisting it makes the next start fail with "Active media driver detected" until the
previous driver's liveness timeout expires. The compose file had already drawn this line by making
the aeron volume tmpfs and `cluster-data` a named volume; the first version of this change blurred
it and the e2e failed within a minute.

**Nothing ever asked for a snapshot.** No `ClusterTool`, no admin request, no timer anywhere in the
repo — so even with durable directories, recovery meant replaying the log from genesis, which is
what Design.md §1 says the snapshot exists to avoid. There are now three ways to ask:
`most cluster snapshot` (`--ingress` sends the admin request through consensus so every member
snapshots at the same log position, `--dir` uses the local control toggle), `most cluster shutdown`
(snapshot then stop, which SIGTERM does not), and the control plane's scheduler at each session
close. **Unlike the four operator commands, a snapshot is answered** — so this is the first thing
the control plane can report as `confirmed` rather than merely `sent`.

**`loadSnapshot` shrugged where it should have refused.** It resolved books through
`indexOfSecurity` and dropped anything not in the booted `ShardSpec` behind a bare `if (index >= 0)`.
`SnapshotEnd.restingOrderCount` was written and never decoded — `else -> done = true` treated that
template as the terminator — so nothing reconciled what was restored against what was saved. Schema
version 2 puts the geometry and a per-book `restingOrderCount` into the snapshot, and the restore now
refuses to start on a removed security holding orders, changed geometry, an out-of-range price, or
counts that do not add up; it stays quiet only for the two legitimate cases, an **emptied** security
leaving the shard and a new one joining. Design.md §5 has the table.

**The failure it replaces was worse than a crash, not better.** The expectation going in was that
restoring into a shrunk ladder would throw `ArrayIndexOutOfBoundsException` on every node at the same
log position — bad, but loud. Mutating the range check away to validate the test showed otherwise:
`Image.poll` catches an exception from its fragment handler, hands it to the client error handler and
**advances the position anyway**. The order is silently dropped and the book comes back quietly
wrong. That is why the restore range-checks itself rather than relying on `book()` to throw, and why
the resting-order counts are reconciled as a backstop.

**`loadSnapshot` had no test at all**, which is why both defects survived. `SnapshotRestoreTest`
is fourteen cases and pays for a real embedded media driver, because `ExclusivePublication` and
`Image` are both `final` with no interface — the shipped encoder feeds the shipped decoder, so the
two cannot agree with each other while disagreeing with reality. Three guards were mutated away in
turn to check the tests catch what they exist to catch; each failure landed on the test covering its
path.

**`e2e/run-restart.sh` is the check that matters, and its first two versions passed for the wrong
reason.** Seven steps: rest orders including a partial fill, snapshot, stop the node, restart on the
same file, then on files that remove a security holding orders (must refuse, non-zero), remove an
emptied one, and add one.

Two lessons came out of writing it, both of the "passed for a reason unrelated to its name" kind
this document already collects:

* **Restarting only the service container is not a recovery.** The consensus module keeps running
  and replays the log to the new service from the beginning, rebuilding the same books by a
  completely different route and taking as long as the session is old. The test compared rendered
  depth before and after, and it matched perfectly — from a full replay, with `loadSnapshot` never
  called once. The engine now prints `restored N resting orders ... from a snapshot` so the two are
  distinguishable at all, and the test asserts on that line.
* **A rendered book was the wrong thing to assert on anyway**, because of the finding below. The
  test now asserts the engine's own restore report and then *trades against a restored order*: a
  sell of 10 filled for 4 before the snapshot leaves exactly 6, so a crossing buy of 6 that reports
  `cum 6` proves price, side, `leavesQty` and ladder position all came back right.

**Found by running it: after a snapshot recovery, market data has no book.** `MarketDataService`
derives everything from the book event stream, and a restored engine republishes nothing for the
orders it restored — so a market data process restarting alongside the engine comes back empty and
stays empty until the next event on that security. The engine's state is correct and trades against
restored orders correctly; the derived L2 feed simply has no way to learn it. The script reports this
rather than asserting it, so a future fix does not have to fight a test that enshrines the gap.
Design.md §8 has the three candidate fixes.

**Two smaller things the same work turned up.** A refused restore leaves through the
`ShutdownSignalBarrier` rather than `Runtime.halt`, because halting skips
`ClusteredServiceContainer.close()` and leaves the service's cluster mark file live — so the operator
who fixes the security file and restarts immediately meets "active mark file detected" instead of a
working node. And a node cannot restart within roughly ten seconds of the previous one regardless,
even after a clean shutdown, because the archive and cluster mark files carry a liveness timestamp;
`run-restart.sh` retries rather than pretending otherwise.

### 2e. Recovery that leaves nothing quietly wrong

`e2e/run-restart.sh` ended the previous session by *reporting* two gaps rather than asserting them.
Both are now closed, and both were the same shape: the system was not merely missing information, it
was confidently presenting the wrong answer.

**Market data had no book after a recovery.** It derives every book from the engine's event stream,
a restored engine publishes no events for the orders it restored, and it has no snapshot of its own
— so it came back showing an empty ladder on a market with real depth. The engine now republishes
each book as a **level image**, which is the shape that fell out of asking what is actually
downstream: `DepthBook` keeps no per-order state and every consumer past it rebuilds from L2
aggregates, so one message per occupied ladder level is enough. That bounds an image by `levelCount`
rather than by resting depth — which matters, since a per-order image of a full book would be a
million messages against a figure open issue 14 says nobody has measured — and it needs no new engine
state, because `PriceLadder` already carries `levelQty` and `orderCount` per level.

Two triggers, one path: a snapshot restore sets a pending flag, and so does `RequestBookImage`
(`most image`, `POST /api/shards/{id}/book-image`) for a market data process that restarted while the
engine kept running. Both publish from `doBackgroundWork` once the publication is *connected*,
because both ask at the moment a subscriber is least likely to be listening.

**The image consumes no sequence numbers, and that is what makes it legal.** `nextBookEventSeqNum` is
replicated state and is snapshotted, so an image that advanced it would let a node whose publication
connected a moment later produce a different snapshot. It carries the current sequence as a baseline
instead, exactly as `DepthSnapshotBegin` carries `l2SeqNum`. Same invariant as metrics, checked the
same way.

**The gateway stopped lying about `cumQty`.** It substituted `origQty = 0, cumQty = 0` for any order
it had no record of, silently, and `recordFill` ignored such an order so the value never re-healed —
a client was told a half-filled order had filled nothing, for the rest of that order's life.
`ClientExecutionReport.enrichment` now says `UNKNOWN`, in a field that fits inside the message's
existing block padding so `blockLength` is unchanged and a version 1 reader is unaffected. **This is
not recovery**: the journal that would actually preserve the state across a gateway restart is still
to build. It is the difference between a wrong answer and an honest one.

**Two findings, both from running it rather than reasoning about it.**

*A snapshot alone does not reach a synchronised subscriber, and the whole point was reaching one.*
The first cut installed an image and published a snapshot. The e2e still failed, because a
subscriber that is already synchronised **ignores snapshots** — deliberately, so an image cannot
rewind a book whose later increments were applied straight through. A console that had synchronised
to market data's empty book a moment earlier ignored every image that followed. Installing an image
now republishes increments too, and zeroes the levels the image *vacated*: an increment carries a
level's absolute quantity so a changed level self-corrects, but a removed one would just stop being
mentioned and sit in a consumer's book undisturbed.

*Then the same failure again, one layer down, and it needed a change to `DepthFeedAssembler`.* Even
with increments the CLI came back empty, and the assembler's own diagnostics said why: the first
complete snapshot cycle it received was the **oldest one still in the stream's buffer** — an empty
book from before the recovery — and having synchronised on that it discarded every later image. On a
busy market an increment would have rescued it within milliseconds; on a quiet one nothing ever did.
The rule is now conditional: a synchronised subscriber ignores an image only when installing it would
rewind, which is to say when its `l2SeqNum` is *behind* what the subscriber has already applied. An
image at or ahead of that already contains all of it. Mutating the rule either way fails a different
test, which is the only reason to believe either.

**Four measurements and one keyword.** `OrderBook.forEachOccupiedLevel` joins `matchAggressive`,
`offerToSnapshot` and `publishBookEvent` as an `inline` the compiler will not defend: removing it
boxes the callback's captured state once per level, and only `AeronAllocationTest` catches it —
confirmed by removing it. That test also found a real allocation on its first run, in the harness
rather than the engine: `OperatorCommands` builds a fresh encoder per call, which is right for a
command sent by hand and wrong inside a loop measuring the engine's allocation.

### 2f. The gateway's order journal — superseded by §2h

> **Superseded.** The journal described below no longer exists: `origQty` moved into the engine in
> §2h and the gateway holds no order state at all. Kept because the reasoning is still the right
> reasoning for a durable structure, and because the question that eventually deleted it is the same
> question that shaped it.

The previous session left the gateway *honest* about losing state — `Enrichment.UNKNOWN` instead of
a `cumQty` of zero — and that was the whole of it. This is the half that keeps the number.

**The design decision that mattered was not to write a journal at all.** The obvious shape is an
append-only log beside the in-memory maps, replayed at boot and compacted periodically. It has a
write per fill, needs compaction, and — the part that decided it — is a *second* bookkeeping that
can drift from the first, where the drift appears only after a restart, which is the one moment
nobody is in a position to check it. So the mapping **is** the store: `OrderStateStore` keeps the two
index maps that find a slot and nothing else, and every value is read out of `OrderJournal`. That is
the engine's own packed-pool-plus-id-map idiom one layer out, it is bounded rather than growing, and
it needs no compaction because a released slot is simply reused.

**Crash consistency without putting a disk write on a 0.2 µs leg.** The state word is written last
and read first, so an interrupted write leaves a slot that reads as free — one order lost rather
than one invented from stale bytes. `cumQty` is a single aligned 8-byte store, which cannot tear.
There is no `msync`, deliberately, and the bound is stated rather than hidden: a **process** crash
recovers in full, because the mapped pages belong to the operating system; a **machine** power loss
can lose the last writes, and those orders come back `UNKNOWN`.

**Capacity is derived from the shard, and running out does not reject anything.**
`gateway.journalSlots` defaults to the sum of the shard's `maxOrders`, which makes exhaustion
unreachable for resting orders — the engine answers `BOOK_CAPACITY` first. If it happens anyway the
order is forwarded untracked and reports `UNKNOWN`, because refusing an order the engine would have
accepted, to protect a bookkeeping structure, is the wrong trade. The flag built last session turned
out to be exactly the right escape hatch for this session's failure mode, which was not planned.

**Four mutations, four tests.** Reordering the state word to be written first, dropping the
plausibility guard, adopting a foreign journal, and leaking a slot per retry are each caught by one
test and no other. The ordering one needed a seam: the store order is invisible in the file
afterwards, so the test hands the journal a `UnsafeBuffer` subclass that records what it was asked to
write, and asserts the state word is last. Worth the ten lines — the first attempt at that mutation
was wrong (it *added* a state write without removing the trailing one) and passed, which would have
left the property untested and believed.

**The e2e step took three attempts, and each failure was a fact about the system.** It restarts only
the gateway while an order rests partially filled, then cancels through the replacement and checks
the report says `cum 20 of 26`.

* First the maker's fill never reached the gateway at all. A maker's execution report is routed by
  `participantId` to the session that participant last spoke on, and a `most send` that has stopped
  following has none — the engine counted it undeliverable and dropped it. That is open issue 3
  seen from a new angle: **the gateway cannot journal a fill it is never told about.**
* Then the aggressor traded nothing, because the offer it crossed belonged to the same participant
  and self-match prevention cancelled it.
* Then it worked, and removing `gateway.journalFile` makes it fail with `cum unknown`, which is the
  only reason to believe the step measures the journal rather than the weather.

**Unverified here: the native image.** The gateway is one of the four native binaries and this
machine has no GraalVM toolchain, so `:gateway:nativeCompile` could not be run. Memory-mapped files
through Agrona's `IoUtil` are what Aeron itself uses for the CnC file and every log buffer, so the
mechanism is already exercised in those images — but that is an argument, not a check.

### 2g. Participant-to-session binding

The oldest blocking issue (§4.3), and the one §2f ran into from a new direction: **a gateway cannot
journal a fill it is never told about.** A maker's execution report was routed by `participantId` to
the session that participant last spoke on, so a participant that had said nothing since its gateway
last connected had no route at all — its fills were counted undeliverable and dropped, and the
gateway's `cumQty` for that order silently stopped advancing with nothing downstream able to notice.
A gateway restart put every one of its quiet participants into that state at once.

**Aeron already carries the answer, which is why this needed no message of its own.** A cluster
client can present credentials; the consensus module authenticates them and stamps an **encoded
principal** on the session, and that principal travels to every node in the session-open event
through the replicated log. So a binding derived from it is identical on every node by construction
— no new wire message, no new snapshot state, no determinism argument to make. Two facts from
reading the 1.53 sources decided the shape:

* `ClusteredServiceAgent.onSessionOpen` passes the principal through to the service, and
  `ContainerClientSession` retains it — so `session.encodedPrincipal()` is available whenever the
  engine wants it.
* `ServiceSnapshotLoader` restores sessions by calling `agent.addSession(...)` directly and **does
  not** replay `service.onSessionOpen`. So a restarted service would silently come back with no
  bindings. The fix is four lines in `onStart` — rebuild from `cluster.clientSessions()` — and it is
  also the reason the map needs no snapshot of its own: the principal is already consensus state,
  and every node re-derives the same map from it.

**What was built.** `ParticipantRegistry` in `reference` (a published per-shard file: gateway id,
the SHA-256 of its shared secret, the participants it speaks for), a `RegistryAuthenticator` the
cluster host installs (`most cluster --participants`), a `CredentialsSupplier` on the gateway, and
the binding in the engine at `onStart`, `onSessionOpen` and `onSessionClose`. All of it optional and
off by default: unset, the engine learns routes from traffic exactly as it always did.

**Three decisions worth keeping.**

* **Traffic still wins for the order it arrived on.** The registry says who a participant *belongs*
  to; the gateway that forwarded an order is the one holding its `origQty`, and therefore the only
  one that can restore `cumQty` on the way back. A report has to follow the order, not the file.
  Symmetrically, closing a session drops only the routes it *still owns* — one that has since moved
  to a live gateway stays there. Both directions are mutation-tested, each caught by one test.
* **Wrong credentials are rejected, never downgraded to anonymous.** The tempting lenient branch is
  the exact failure this removes: a gateway that connected anonymously by accident trades perfectly
  well and loses only the fills of whichever participants have gone quiet, which nobody sees until
  someone reconciles a `cumQty`. **No** credentials still authenticate anonymously, because the
  control plane and the CLI connect to send operator commands and are addressed by nobody.
* **The registry has its own fingerprint, and is not folded into `ShardSpec.fingerprint()`.** That
  hash is recorded by the control plane and published in every release; widening what it covers
  would invalidate every value already written down, and rotating a gateway secret is not a change
  of shard geometry. Same reasoning that keeps `auctionMaxPasses` out of it (§4.12) — except this
  one actually got its second fingerprint rather than staying a note.

**`e2e/run-restart.sh` §4c is the proof, and it is the case §2f had to avoid.** A maker rests an
offer and stops listening; the gateway is restarted, so the session the engine learned that
participant on is gone; an aggressor crosses the offer. Cancelling the remainder afterwards reports
`cum 4 of 10` — a number the gateway can only have accumulated from a fill report it was actually
sent, on a session the maker had never spoken to. The comment in §4b that documented the old
workaround now points at §4c instead of at an open issue.

**The first version of that step passed with the registry removed**, and finding out why was the
most useful hour of the session. `most send` exits and closes its publication, but the media driver
keeps it for the **linger period, five seconds** — so the subscription the *restarted* gateway
creates gets an image starting at that publication's initial position and **re-forwards the order**.
The engine re-learns the route from the replay, and the fill is delivered whether or not anything
was bound at session open. An instrumented run showed it plainly: `p=12 NEW -> session=3` and then
`p=12 NEW -> session=4`, the same order reported twice on two different sessions. Waiting seven
seconds before the restart makes the step measure what it claims, and the negative control now fails
with `cum 0 of 10` exactly as predicted. Two things fall out of it worth keeping: an e2e assertion
nobody has run with the mechanism disabled is a guess, and **a restarted gateway can re-forward
orders still lingering on its inbound stream** — visible in the same trace as a duplicate
`REJECTED`, harmless here because the engine rejects the second one, and not something anybody has
thought about on purpose.

**What is deliberately not done, in the order it matters.** *Enforcement:* the engine binds routes
but does not refuse an order whose `participantId` is not bound to the sending session, so
`UNAUTHORIZED_PARTICIPANT` is still raised by nothing and a gateway can still trade for a
participant that is not its own. *Authoring:* the registry is hand-written; the control plane owns
the `participant` table but does not render or publish this file, so nothing checks that the two
agree. *Granularity:* the identity is the **gateway's**, not the end participant's — this
authenticates a process, and the participant ids it claims are trusted because a published file says
so, not because any client proved anything.

### 2h. `origQty` into the engine, and a gateway that holds nothing

`docs/ProdDeployment.md` §2.1 called gateway HA "the weakest part of the design" and §11 listed it
as blocking. The reason was one field. `origQty` lived only in the gateway, because the packed order
slot is one cache line and had no room for it, so a standby on another machine had no copy and a
failover marked every in-flight order `UNKNOWN`. Every option for fixing that in place — a shared
journal, a replicated one, partitioning adapters so each failure loses only half — was a way of
carrying the field around rather than of not needing to.

**So the field moved.** `origQty` is now a parallel `LongArray` beside the packed pool — `ColdField`,
stride 1 — read only where an execution report is generated or the book is walked for a snapshot,
never inside the matching loop. Eight bytes an order: 8 MB per million-order book, 80 MB per shard
against §2's ~0.9 GB, and the measurement below says it costs nothing at the median. `OrderJournal`
and `OrderStateStore` are deleted, and `gateway.journalFile` and `gateway.journalSlots` no longer
exist.

**`cumQty` is stated by the engine too, and that is not belt-and-braces.** A terminal report carries
`leavesQty = 0` whether the order filled or was cancelled, so `origQty - leavesQty` reports a
cancelled order as fully filled — the second row of §6's first defect table, shipped once already in
the gateway. Putting only `origQty` on the wire would have re-opened it one layer along. The engine
knows the true remainder at the moment it terminates an order, so it says so.

**What it buys, in the order it matters.**

* **A gateway is disposable.** Restarting one loses nothing, so restarting one to pick up a new
  participant registry is an ordinary operation rather than a cost.
* **Several gateways can serve one shard.** What has to be disjoint between them is their
  client-facing endpoints — two subscribed to one inbound channel would each receive every order and
  forward both, which is duplicate orders rather than redundancy — and nothing else. `run-restart.sh`
  §4d is the check, and **nothing in this repo had ever run two gateways** before it.
* **Two open items close by deletion.** No `msync` on the hot path, so a machine power loss could
  lose the last writes; and nothing reaping a pending order whose acknowledgement never arrived. The
  structure they were about is gone.

**A version 2 snapshot has no `origQty`, and that is where the honesty flag earns its keep again.**
It restores as 0, meaning unknown, and travels to the client as `Enrichment.UNKNOWN` rather than as
a number nobody can justify. The plausible mistake — filling it in from `leavesQty` — is right only
for an order that never traded and silently understates every order that did.
`SnapshotRestoreTest` pins it.

**The measurement says the cold line is free.** `e2e/run-attribution.sh`, same shape as §2c:

```
  client round trip            50.3 us      (was 55.4)
  gateway inbound               0.1 us      (was 0.2)
  engine (whole message)        0.4 us      (was 0.4)
  gateway outbound              0.1 us      (was 0.2)
  ------------------------------------
  in this shard's processes      0.7 us  (1.4%)
```

`newOrder.settle` — the stage that now reads and writes the cold word — is **0.17 µs at the median,
identical to §2c's figure**. The gateway's two legs halved, which is what dropping a journal write
and two hash lookups per message looks like. `AllocationTest` and `AeronAllocationTest` are both
unchanged and green, the latter covering the snapshot walk that now carries one more field per
order.

**A registry both node processes re-read, and the argument that makes it legal.**
`ParticipantRegistrySource` polls the configured path, compares content **by fingerprint** — a
release is published to a new directory and put in force by moving a symlink, so mtime says nothing
— and swaps an immutable registry behind a volatile reference, announcing both fingerprints. The
consensus module's authenticator and the engine's principal resolution both read it through that.
The gateway does not reload; it restarts, which now costs nothing.

The reason that is safe in the engine is specific and worth keeping: what it feeds is node-local
*egress routing*. The map is rebuilt in `onStart` from `cluster.clientSessions()`, is deliberately
not snapshotted, and only the leader's egress reaches anyone — so two nodes briefly holding
different versions can disagree about where to send a report and cannot diverge the log, the books
or a snapshot. **That stops being true the moment the engine rejects an order on a binding**, which
is why `UNAUTHORIZED_PARTICIPANT` cannot simply be built on top of this and has to come through the
log. Same shape as the metrics argument in §2c, and the reload interval is excluded from
`EngineConfig.fingerprint()` for the same reason.

Two refusals carry it, and both are the conservative half of a choice: **a file that cannot be
parsed is reported and ignored**, because standing down on a bad one would turn a typo in an editor
into a shard that authenticates nobody; and **a registry for another shard is refused**, for the
reason the boot-time check exists.

**The control plane authors it.** `gateway` and `gateway_participant` (migration V5) hold gateway
identity, the SHA-256 of its secret and its participant claims, and `ReleasePublisher` renders
`shard-N-participants.properties` beside the security file. Three decisions:

* **`participant_id` is the join table's primary key**, so "a participant belongs to at most one
  gateway" is structural rather than checked — the same shape as `security_id` being the primary key
  of `security`.
* **The digest cannot be BCrypt.** The cluster verifies it against what a gateway presents, so it
  must be reproducible. The plaintext is returned exactly once and is unrecoverable; rotating issues
  a new one.
* **A shard with no gateways publishes no registry**, rather than an empty one.
  `ParticipantRegistry` requires at least one gateway, and a shard whose gateways connect
  anonymously is a legitimate configuration. `publish()` skips rather than throws, and the test says
  so.

The registry fingerprint gets its own column and its own manifest field, never folded into the
shard's — §2g made that argument and this is where it was actually needed twice.

**Two e2e steps, and one of them was run with the mechanism disabled.** §4d is the two-gateway
proof. §4e rotates gw-1's secret while the node runs, waits for both processes to announce the
swap, checks that a gateway presenting the **superseded** secret is refused, and then that the new
one authenticates and trades. Running the whole script with `--participants-reload-ms 0` fails at
§4e with "the consensus module did not reload the registry", which is the only reason to believe the
step measures the reload rather than the weather.

### Not built

* **Epsilon GC, enabled by default.** The zero-allocation claim is *proven* on every path including
  the snapshot walk, and `--gc=epsilon` builds and runs — but `engine.useEpsilonGc` stays `false`.
  Two things are missing and both are about time rather than code: a CI to fail a build on a
  regression, and evidence measured in hours rather than the 20 seconds the soak covers (the Aeron
  client conductor shares this heap, and a per-duty-cycle allocation would be invisible at that
  scale). See §2b.
* **Live metrics.** The instrumentation reports at shutdown and writes `.hgrm` files; nothing can
  be read from a *running* process. Aeron's counters file is the idiomatic answer and would suit
  counts and maxima, though not percentiles.
* **JMH.** `most load` measures the system; nothing microbenchmarks `matchAggressive` or the ladder
  scans.
* **CI.** No workflow. Design.md §7 wants an allocation assertion in CI to gate Epsilon; the
  assertion exists and runs in `./gradlew build`, but there is no CI to run it in — which is half
  of why the flag is still off.
* **Multi-node cluster.** Only the single-node dev host (`most cluster`) has ever run — in Docker
  too. Failover, leader election and snapshot recovery across nodes are entirely unexercised.
* **Multicast.** The Docker stack uses dynamic MDC because a bridge network does not route
  multicast. Multicast feeds and `MaxMulticastFlowControl` remain untested anywhere.
* **The FIX↔SBE adapters.** The dev stack's networks and the control plane's own Aeron client are
  shaped for them, and nothing more.
* **Authorisation beyond one role.** The control plane is now authenticated — one `ADMIN` role with
  full access — which means any operator can create another operator and reopen a halted security.
  A read-only tier is a data change, not a migration (the `role` column is already there), but
  nothing distinguishes an observer from someone who can move a market.
* **Authentication anywhere else.** The control plane has session-cookie and Basic auth, and the
  **gateway authenticates to the cluster** as a named identity (§2g, authored in the control plane
  since §2h). What still has none is the leg in front of it: an upstream protocol adapter publishes
  onto the gateway's inbound stream with nothing to prove, and the `participantId` on the order is
  taken at face value. The registry says which gateway a participant belongs to; nothing yet says
  which client is that participant.
* **A read-only tier in the UI.** The frontend can now do everything the API can, which for one
  ADMIN role means every operator sees every button. There is nothing to hide behind until the role
  column is used.
* **Any *automated* browser test of the SPA.** It is typechecked (`vue-tsc`), every endpoint it
  calls has been driven against the running Docker stack with a cookie jar, and the Books screen has
  been clicked through by hand in Chrome under live load. The CRUD forms, the operations screen and
  the schedule editor have only ever been exercised through their APIs, never through their own
  markup. A Playwright pass over login → publish → reopen is still the obvious gap.
* **A control plane that survives its own restart.** Sessions are in memory, so a restart signs
  every operator out; and because it subscribes to L3 rather than querying, its observed phase
  returns to `unknown` until the next `SessionChanged`. Both are correct-by-design rather than bugs
  — the phase one especially, since guessing would be catastrophic — but they make a rolling restart
  of `control` visible to whoever is watching.

### Known divergence

`Design.md` §6 still shows `resolveSelfMatches` and `executeAllocation` as skeletons with "cursor
mechanics elided" and `return 0`. The real implementation in `OrderBook.kt` is complete. The
document's code section has drifted behind the code and should either be trimmed to signatures and
commentary or removed in favour of pointing at the source. **Unchanged from the last handover.**

### 2i. The aggregate — measured, attributed, one ceiling found and the next one cornered

Two items off the open list: the control plane's over-reported feed gaps, and the ten-security
aggregate nobody had ever driven. They are in the same record because the first is what makes the
second legible: a loss counter that always reads "loss" tells you nothing about a run.

**The gap counter.** `ClusterLink.onBookEvent` called `accept()` in the four branches it decodes —
`SessionChanged`, `VolatilityHalted`, `AuctionUncrossed`, `TradeExecuted` — while the engine stamps a
sequence on all **seven** book events. The three order events the control plane deliberately ignores
(depth is market-data's job, and shadowing a book in the control plane would be a second
implementation of it) therefore advanced the engine's sequence without advancing the subscriber's
expectation, and every one of them read as a gap. On a busy book `feedGaps` and `eventsMissed` cried
loss continuously, which is strictly worse than not counting: real loss was indistinguishable from
the noise.

**It was extracted rather than patched.** The one-line fix is to call `accept()` before the `when`,
and the reason the defect survived to be found by reading is that `onBookEvent` was a private method
of a `@Component` that cannot be constructed without a media driver. Nothing tested it because
nothing could. The decoding now lives in `BookEventReader`, a plain class over `ExchangeState` and a
`FeedSequenceTracker`, and `ClusterLink` holds one and delegates. `BookEventReaderTest` drives it with
the real SBE encoders and writes its expected values from Design.md §5: a mixed contiguous stream is
zero gaps, a jump of three is one gap of three, a book image is a baseline and consumes nothing, two
shards interleaved on one feed are tracked apart, a lower sequence is a replay rather than loss.
Removing the three new `accept()` calls fails five of its six cases.

**The image branch matters as much as the order branches.** Market-data does not forward
`BookImageBegin`/`Level`/`End` onto L3 today — it returns before `publishL3` — but an image's `seqNum`
is a baseline and consumes no sequence, so counting one would report a gap the size of the book's
whole history. The `else -> Unit` branch is deliberate and the test pins it, against a change in
market-data rather than against today's traffic.

**Then ten securities, for the first time.** `e2e/run-sweep.sh` grew a `SECURITIES` knob (1–10, drawn
in order from a table of ten real symbols and ISINs, because `reference` validates check digits and a
generated ISIN is refused at boot) and `most load` already accepted `--symbol A,B,C`. `RATES` stayed
the **aggregate**, which is the only reading that lets a fan-out sweep be compared against the
one-book sweeps already in `Measurements.md`. Three runs, R2–R4.

**The result is that fan-out buys nothing.** The knee sits between 300k/s and 350k/s aggregate
(reproduced across two runs) — the *same aggregate* at which one security broke on this machine in
R1, 200k–333k. Ten books did not multiply throughput; per security the ceiling is ~30k/s against a
100k/s target, so Design.md §2's 1M orders/sec/shard is over-stated by about 3x on the measured path.
(That knee figure is revised below: it was taken on a loaded machine and the idle number is ~350k/s,
a 2.9x shortfall. The shape of the finding did not change.)

That is not a matching result. §2c put the exchange's own code at 1.4% of a round trip, the engine
finished every run with `droppedBookEvents=0` and `backpressureStalls=0`, and per-order matching cost
is precisely what fan-out divides. What fan-out does not divide is the shared path every order
crosses whichever book it lands on: one cluster ingress, one consensus module, one archive write, one
replicated log, one service thread. **Which of those binds is not separated**, and that is the open
item this closed one turned into — `run-attribution.sh` at ten securities is the measurement, and the
answer is the difference between tuning the cluster and adding shards.

**Above the knee it queues, and the harness now says so.** At 1M/s aggregate — 3x what the shard can
serve — all 500,000 orders were answered, nothing rejected, nothing dropped, and the median was
462 ms. The generator was not the problem: pacing lateness p99.9 stayed under 155 µs at every rate.
So `achieved` above the knee is an **offer** rate, and the latency beside it is a queue draining after
the offers stopped. The sweep's two validity checks (rejects, pacing) both passed on those rows and it
printed `PASS`, which is how a 462 ms figure could have been quoted as a latency. A third check now
marks such a row `SATURATED` — not invalid, because it is the measurement the sweep exists to find,
but never a round trip — and the summary names the highest rate the shard actually kept up with.

**A data point for the execution-report drops, which is what was asked for.** R4's gateway stopped
with `droppedToClient=6` of `sentToClient=3,264,416` — 0.0002% — where the sweep behind open issue 1
lost 267,853 of 3.26M reports, 8.2%, at a comparable report volume. `clusterBackpressure=11`,
`rejectedLocally=0`, `untrackedReports=0`. Two of three sweeps on this machine now queue rather than
drop. That strengthens the machine-dependence already recorded rather than resolving it: the remedy
still has to be decided, but it cannot be decided from a machine that will not reproduce the failure.

**One counter in those logs means nothing and should not be quoted.** Market-data finished R4 with
`droppedL1=1,548,468 droppedL2=1,611,249 droppedL3=2,367,241`. The sweep attaches no depth
subscriber, so every feed offer returns `NOT_CONNECTED` and is counted as a drop. `gaps=0 missed=0
foreignShard=0` are the counters that mean something there, and they were clean.

**Then the attribution, on an idle machine.** `run-attribution.sh` took the same `SECURITIES` knob,
and three runs (A1–A3 in `Measurements.md`) turned R2–R4's shape into a cause. A1 re-baselined one
security at 100k/s on a quiet machine: 38.1 µs round trip against the 55 µs in §2c, same 2% share, so
the earlier figure was a loaded machine and not a different system. A2 spread the *same* 100k/s over
ten books: a whole new order went 0.46 µs → 0.50 µs and the round trip 38.1 → 39.4 µs. **Fan-out costs
8% of an order and nothing that matters.** A3 then raised the rate 2.5x to 250k/s aggregate, and this
is the run that settles it: the engine got **faster** per order, 0.38 µs, because a busier poll
amortises better — while the round trip grew to 61.2 µs and the gateway-plus-engine share fell to
**0.9%**. Every microsecond the higher rate added landed outside the instrumented processes. A fixed
cost does not grow with arrival rate, so something in the shared path saturates, and it is not
matching.

**Then the RAM disk, which eliminated a suspect and caught a wrong number.** Both scripts took a
`CLUSTER_HOST` knob — `--dir` places the archive and consensus log, `--aeron-dir` places the media
driver's buffers, so pointing the first at a RAM disk moves the durable writes and only those. A 4 GB
**APFS** RAM disk, not the default HFS+, so the filesystem was not a second variable.

The first comparison was A3 against A4 at 250k/s and it found 2.6% — the right answer reached
invalidly, because 250k/s is a rate the shard serves comfortably and storage was never under pressure
there. The experiment that decides it is a *throughput* comparison at the knee, and the first attempt
at that one was worse: against R4's numbers the RAM disk appeared to take 350k/s from a saturated
57.5 ms to 701 µs, an 82x win. **It was the browser.** R4 was taken with IntelliJ and Firefox open; an
SSD sweep run the same hour, idle, did 350k/s in 392 µs. The RAM disk had done nothing.

So two things came out of it. **The archive write is not the ceiling**: R6 saturates at exactly the
rate R5 does — both comfortable at 300k, both gone at 400k — and the sub-knee figures are inside each
other's noise. Put the durable writes in RAM and the shard stops in the same place. **And the knee
itself was 15% low**: ~350k/s aggregate on an idle machine, comfortable at 300k, marginal at 350k
(measured three times at 392 µs, 701 µs and 1.66 ms — an order of magnitude of spread, which is what
the edge looks like), gone by 365k. R2–R4's 300k–350k had already reached four documents. The
conclusion never changed — fan-out buys nothing, the target is over-stated, now by 2.9x rather than 3x
— but the figure did, and `Measurements.md` now carries the R2–R4 analysis with a superseded note
rather than a silent edit.

**What the attribution cannot do, stated rather than glossed.** "Everything else" is Raft consensus,
the archive write, the IPC hops and the poller wake-ups, and the subtraction lumps all four. Storage is now out by
measurement, which leaves the single-threaded consensus module, the IPC hops and the poller wake-ups.
The back-of-envelope that made storage an unlikely culprit in the first place — ~20 MB/s of log append
at 250k/s, which no SSD notices — turned out to be right, which is pleasing and also exactly why it
needed checking rather than asserting. What is left to try: Aeron's own driver and archive counters,
exposed and read by nothing here; a gateway-stamped ingress timestamp read only under
`engine.metrics`, which separates the hop from the consensus module and is a wire change because
`NewOrderSingle` carries no timestamp; and the consensus module's idle strategy and
`ingressFragmentLimit`, which are configuration — if the ceiling moves with them, this is tuning
rather than architecture.

**Then the counters, which found the ceiling.** `most counters` is new: it maps the driver's CnC file
and reads what the driver, the archive and every cluster component already publish, with **no Aeron
client**, so it can be pointed at a saturated shard without changing the answer. `--interval-ms`
reports the rate of change, which is the mode that matters — a counter's value is rarely interesting
and its slope usually is.

One pass found it. At 400k/s against 300k/s every counter in the durable chain scaled **exactly 1.33x**
with the offered rate — ingress publication, the log, `Cluster commit-pos`, `rec-pos`, archive write
bytes, all 38.4 → 51.2 MB/s — so nothing there was capping in bytes. The archive's write *time* went
*down*, 213 ms/s to 123 ms/s, while its byte rate rose by a third: a third independent statement that
storage is not the constraint. The counter that exploded was sender flow-control back-pressure, ×89, on
the cluster ingress channel — whose term length is `64k`, hard-coded in `ClusterCommand.kt`.

**That was a decoy, and it is the most useful thing in this record.** Back-pressure on a channel is
what a slow *consumer* looks like from the publisher's side, so a closing window is as likely to be
symptom as cause. Tested: at `16m` the knee did not move at all — 350k/s either way — though p50 at
350k/s improved 2.9x, from 6410 µs to 2226 µs. **256x more ingress buffer buys latency headroom at the
edge and no capacity.** A counter that moves non-linearly names a place to look, not a cause.

**What was the cause sat one layer down: `ThreadingMode.SHARED` on the media driver** — conductor,
sender and receiver on one thread, moving ~190 MB/s of loopback UDP on a 14-core machine. A 2×2 over
driver and archive threading (R7), `ack response` p50:

| driver | archive | 350k/s | 450k/s | 500k/s | 550k/s | knee |
| --- | --- | --- | --- | --- | --- | --- |
| SHARED | SHARED | 6410 µs | saturated | — | — | ~350k/s |
| SHARED | DEDICATED | 28.4 ms sat | saturated | — | — | <350k/s |
| DEDICATED | SHARED | **79 µs** | **90.5 µs** | **118 µs** | 2029 µs | ~500–550k/s |
| DEDICATED | DEDICATED | **74 µs** | **102 µs** | **114 µs** | 694 µs | ~550k/s |

**1.6x the throughput and 81x the median at the edge, from one enum.** Per security ~55k/s rather than
~35k/s, so the gap to Design.md §2's 100k/s narrows from 2.9x to 1.8x. **It was tuning, not
architecture** — which is the opposite of what a 3x shortfall against a design target usually means,
and worth remembering before anyone proposes sharding to fix a number.

The second row is why the factorial was worth running rather than one cell. A `DEDICATED` archive
behind a `SHARED` driver is *worse than both shared* — it takes a core from the component that needed
it. Tested alone it would have read as "dedicated threads make things worse" and closed off the
setting that mattered.

**The default stays `SHARED`,** asked and answered 2026-09-25 and written into Design.md §7 "Driver
threading". Three busy-spinning threads are wrong for a laptop already running five JVMs, and flipping
the default would have silently re-based every figure in `Measurements.md`. `--driver-threading` and
`e2e/run-sweep.sh`'s `DRIVER_THREADING` are the knob, and a benchmark or a production node sets it.
The consequence, stated rather than buried: **every row in R1–R6 was taken 1.6x below what the shard
can do.** No ratio drawn from them changes, each having been measured within one configuration.

**Pointed at the next knee, the same tool came back empty — and that is a result.** `most counters`
against a `DEDICATED` driver in genuine saturation (a 15M-order run, so the window is steady state and
not a draining queue) shows every stage scaling *exactly* with the offered rate: ingress, log,
`commit-pos`, `rec-pos`, archive bytes, egress and both IPC streams all ×1.20 for a ×1.20 rate rise, the
archive's write time *halving* while its bytes rose a fifth, and every duty-cycle and error counter —
driver conductor, sender, receiver, archive conductor, Cluster, Cluster container — at **zero**. Beside a
478 ms median. Aeron reports queues, positions, stalls and errors; **a stage that is merely full
produces none of those**, so a clean sheet narrows the answer to "not a buffer, a window, a disk or a
stall" and no further. The counters also confirmed R7 from their own side: the back-pressure that
exploded ×89 under `SHARED` is gone from the moving set entirely.

**Then the CPU sample, and the mistake it nearly produced.** With the counters exhausted, the remaining
shape is a thread at 100% of a core, which no counter can see — so per-process CPU. `engine`, `gateway`
and `market-data` each read ~100%, and the first reading of that was "three single-threaded stages
pinned at once", written up as the finding. **It was wrong.** All three use `BusySpinIdleStrategy` and
read ~100% whether they are working or idling. The control that caught it was running the same sample
under `SHARED`, where the same three read ~100% at a rate 1.7x lower — a number that cannot be
saturation in both places. The one signal that *is* real: the cluster-host takes ~1.6 cores under
`SHARED` and ~3.7 under `DEDICATED`, which is R7's conclusion arrived at by a second instrument.
`Measurements.md` records the wrong reading beside the corrected one rather than only the answer,
because the trap is reusable and the answer is not.

**What is known about the engine comes from its own histograms.** 0.38 µs per order × 600k/s = 0.23 s
per second, so **matching is ~23% of the engine's core** and roughly three quarters of that thread is the
`ClusteredServiceContainer`'s Aeron work — polling the log, publishing egress, publishing book events.
The engine process can be busy while the matching engine this project tuned is three-quarters idle.

**And one item changed status because of it.** The gateway is a single thread carrying every order
inbound and every report outbound — ~1.3M messages/sec at the measured ceiling. Whether or not it is
*the* cap, it has the least headroom by construction and it is the only saturating stage that scales
sideways **today**: gateways are stateless, `origQty` lives in the engine (§2h), and `run-restart.sh`
§4c already exercises the multi-gateway case. The sole blocker is that `ShardEntry` carries one channel
and `DirectoryClient` one `ShardRoute` per shard. That was filed as small tidying; it is now the
cheapest throughput lever the system has, and `Status.md` promotes it accordingly.

**What is open is a narrower question than when the session started**: what binds at ~550k/s, with the
engine, storage, ingress buffering, the driver's threads and every Aeron counter eliminated. The next
instruments are the loops' own metrics, or one run deliberately switched to a yielding idle strategy so
that CPU% means something.

**What the check is.** 463 tests pass (457 + 6). `most counters` has no test — it is a read-only
diagnostic whose output was validated by the experiment it enabled, and that is worth saying out loud
rather than implying. Two figures in this record were wrong before they were right and are kept both
ways: R2–R4's knee (a loaded machine) and the "three stages pinned" reading of the CPU sample (busy-spin
mistaken for saturation). Both were caught the same way — by running the control that should have been
run first. The gap fix is unit-proven and mutation-checked and
has **not** been seen against a live control plane — no e2e script runs one, which is itself worth
noting. R2–R4 were taken on a machine that was **not idle**, with a browser on ~1.5 of 14 cores; that
cannot explain a 3x shortfall but it can move a knee, so 300k/s is the shape and not the constant.
A1–A4 and R5–R6 were taken after closing the IDE and the browser and stopping Docker, at load average
1.4–3.4, which is why A1 exists at all: without it the 38.1 µs would have read as an improvement rather
than as a quieter machine. The same discipline applied one step later is what caught the 82x that
wasn't. **Every sweep in R2–R4 should be read as a loaded machine**, and the `idle` column in
`Measurements.md` is not bookkeeping.

---

### 2j. Participant enforcement at the gateway — and the snapshots that never happened

Branch `gateway-security-multiple-per-shard`, seven commits, not yet merged. It closes open issue 3,
the oldest security gap in the system, and on the way found that a durability path everyone
believed in had never worked.

**The runner first.** The GitLab pipeline had never executed. Its first run (pipeline 33) failed four
jobs for four reasons unrelated to the exchange: Docker's 64 MB `/dev/shm` could not hold a 16 MB-term
IPC log (the three driver-backed test classes now keep `aeron.dir` under `build/`); `run-restart.sh`
grepped once for restore lines the engine prints *after* `awaiting shutdown signal`; the GraalVM
image lacks `xargs`; and dind needs a privileged runner. Pipeline 34 was green. `measure:sweep` fails
on the runner, correctly — its validation refused every rate on pacing lateness.

**The decision.** For several sessions the plan was that `UNAUTHORIZED_PARTICIPANT` had to come
through the log, because an *engine* reject decided from a node-local registry would diverge the
nodes — a sequenced registry-install command, snapshot state, a wire change. The user's proposal
removed all of it: reject at the **gateway**. A refusal that never enters the log has no determinism
to protect, so the gateway may decide from a file it re-reads on its own schedule, and it rejects
earlier and cheaper. `UNAUTHORIZED_PARTICIPANT` already existed in the schema. Four choices were made
explicitly: refuse anonymous sessions; allow a participant on several gateways with a declared
primary; hot-reload the registry in the gateway, with revocation made graceful by a `cancelOnly`
list; and keep gateway discovery out of band rather than change the directory.

**What was built**, in the order it was committed, each step green on its own:

1. **Registry model** (`reference`): `cancelOnly`, `operator`, `participant.<id>.primary`; an
   operator-only entry is the only kind allowed to list nobody; allocation-free `mayPlace`/
   `mayCancel`. A registry using none of the new keys renders and fingerprints exactly as before —
   pinned at `dbbaaeaf18f6598f`, computed from the pre-change jar with `jshell`, because releases
   already record that value.
2. **Gateway**: refuses orders and **cancels** for unlisted participants (a cancel check is not
   optional — the engine's own is participant equality, which is only as good as the id), refuses
   operator commands and unknown templates unless it is an operator, and re-reads the registry via
   `ParticipantRegistrySource`. `RegistryAccess` re-resolves its entry only when the registry object
   changes; the allocation test caught my own harness allocating encoders before it measured the
   gateway.
3. **Engine**: binds a participant at session open only if the gateway is its primary or nobody live
   holds it; rebinds a closing session's routes to another listing session; counts
   `undeclaredParticipantMessages` and never branches on it — a test drives two nodes with
   disagreeing registries to identical reports, books and sequences.
4. **CLI**: `--order-entry-channel`/`--report-channel` (and streams) override the directory's route
   per part, four options because channel URIs are full of colons; `cluster snapshot --ingress
   --identity --secret-file`.
5. **Control plane**: V6 (`gateway.operator`; `gateway_participant` keyed by the pair, with
   `cancel_only` and `is_primary` — V5's key on the participant alone had also confined a participant
   to one gateway on one shard); operator-only gateways are published; one primary per participant
   per shard. It surfaced a test-isolation bug: `RESTART IDENTITY` reused release numbers while the
   release directory kept earlier tests' files.
6. **Anonymous sessions refused**, last, because it needed the identities first — the Docker stack's
   control plane connected anonymously. The control plane gained
   `control.cluster.operatorChannel.<shard>`: its operator commands go through a gateway and are
   unacknowledged, so pointing them at a non-operator gateway would be a silent `refusedCommands`.
7. **Snapshots** — below.

**The snapshots that never happened.** Testing the Docker stack interactively, a control-plane
snapshot timed out: its egress endpoint defaulted to `0.0.0.0:0`, which the consensus module cannot
answer (fixed: `control-driver:0`, the media driver's container). With that fixed it returned
`confirmed: true` — and after a restart the engine printed no restore line. The recording log held
**no SNAPSHOT entry**. Aeron 1.53's default admin authorisation, `AllowBackupAndStandbyAuthorisation
Service`, grants backup and standby traffic and nothing else, so **every** snapshot requested through
consensus since the feature was written — `--ingress`, the control plane's button, the scheduler's
session-close snapshot — had been refused, and both requesters reported success because they checked
only the offer. `RegistryAuthorisationService` now grants a snapshot request to an `operator=true`
identity (a node without a registry allows all), and `requestSnapshot` waits for the answer on
egress; `ClusterAdmin` connects per request, since its cached session sent no keepalives. The dev
stack then took a confirmed snapshot (recording log 0 → 2 entries), and a full shard restart printed
`restored 1 resting orders across 2 books from a snapshot`.

**The dev stack now tells the truth about itself.** The seed authors participants 7, 8, 20–23, `gw-0`
and `control` with the secrets the processes present, and the `equities` calendar, and prints the
release's registry fingerprint: `4c9c24c10df3ff56`, identical to what `cluster-host`, `engine` and
`gateway` printed. Before, the database had no gateways at all while the cluster enforced two.

**What the check is.** 542 tests pass (463 before the session). Every new behaviour was mutation-
checked — a removed cancel check, an allocating check, an ignored primary, a skipped rebind, a
deciding counter, a non-operator allowed to snapshot — and each failed the tests meant to catch it.
`e2e/run-restart.sh` §4f exercises it live: an unlisted participant refused through gw-1 (reached via
the CLI override), a hot reload to `cancelOnly` that blocks placing but not cancelling, a refused
operator command, `undeclaredParticipantMessages=0` in the engine, an anonymous cluster session
refused with the reason logged, and a snapshot proven by the recording log — granted to `control`,
refused `UNAUTHORISED_ACCESS` to gw-1. **Not yet run in CI**: the branch has not been pushed.

**Still open**: no bulk cancel for a revoked participant; the client-to-gateway leg is not
authenticated (by design — the upstream session gateways' job); the control plane cannot verify its
operator gateway is one; the release publisher writes into an existing directory.

### 2k. The ceiling question, asked directly — and the laptop's answer is its core count

Merged to `master` as `dd5039f` and `029e37a`, after `ba5c605` made the project AGPL-3.0-or-later.
Measurements.md R8–R11, A5 and D1–D2 carry every figure; this is the narrative.

**The licence.** The dependency tree was checked before the relicensing was committed: 60 runtime
artifacts across the eight modules, licences read from their POMs, parents followed. All are
Apache-2.0, MIT, BSD or CC0 except logback (EPL-1.0 **or** LGPL-2.1 — used under the LGPL) and
jakarta.annotation (EPL-2.0 with a GPL secondary licence); the SPA's production tree is MIT/ISC/BSD.
Two things to act on before the repository goes public: the exported presentation decks embed a
third-party icon font (`mc-anthropicons`) that the AGPL does not cover, and Apache NOTICE files must
travel with any distributed binary. A "source" link in the SPA footer is deferred to the public move.

**Second gateway: measured before building, and refuted.** To-do item 2 proposed advertising several
gateways per shard as the cheapest throughput lever, which is a wire change. The question was put
first: `run-sweep.sh` gained `GATEWAYS` and `LOADERS`, and four same-day arms (R8–R11) put two
gateways at a knee no higher than one, and 100x slower below it. The control arm is confounded (each
generator decodes every report through one gateway, and more processes contend for cores), so A5
settled it from the gateway's own histograms: ~19% of a core at the knee. The directory change was
not built.

**The duty cycle.** Nothing could say how full a busy-spinning thread is — `ps` reads 100% either way
and Aeron's counters report stalls, not fullness. `DutyCycleIdleStrategy` (`reference`) wraps any idle
strategy and times the stretches between `idle` calls that closed on work, publishing busy ns as an
Aeron counter that `most counters` shows as a share of a core. Because every Aeron agent takes an
injected strategy, it reaches the consensus module, archive and driver threads without touching
Aeron (`most cluster --duty`), as well as the engine, gateway and market-data behind their metrics
switches (`md.metrics` is new). The idle strategies themselves became configuration
(`*.idleStrategy`, `busyspin` default); the dev stack backs off. Specified in Design.md §7 first; the
tests were written from the clause and mutation-checked — four correctness mutations each caught by
the test for its clause, an escaping allocation caught, a non-escaping one not (the JIT removes it,
the same blind spot `AllocationTest` has).

**Two defects only a live run could find.** Aeron calls some idle-strategy suppliers twice and runs
its agent on one result, and the consensus module runs its spare through start-up before dropping it:
the first version published a permanent 0% beside the real thread. Counters are now attached from a
short-lived background thread, only to a wrapper still looping across a full second of polls. And
the first live run used stale jars — `installDist -q` had not refreshed them — so the installed jar
is now checked for the new code before a run is believed.

**The answer, on this machine (D1–D2).** Across 250k–700k/s the consensus module never exceeds 20%;
the gateway, market-data and archive stay well under half. The engine's service thread goes from 20%
to 100% in one step at ~550k/s with its median cost unchanged and its p90 ~10x. The egress
back-pressure hypothesis was tested and refuted — the engine's `backpressureStalls` counts one stall per
million retries, so its zero proved nothing, but every Aeron counter was sampled and flow-control
events *fell*. Setting gateway and market-data to `backoff` moved the knee up one step without
touching the engine. The 10P+4E laptop runs out of performance cores and the cache-bound engine loses
first. **Paused** until a dedicated 16-core machine exists.

**What the check is.** 566 tests pass (542 before). `run-e2e.sh` and `run-restart.sh` pass with the
new start-up paths; `run-attribution.sh` fails if any thread on the order path lacks a duty counter.
The Operator's Manual carries the settings (§3.4, §4.4–4.8) and how to read the counters (§5.8).

**Still open**: the knee on a host with a core per spinning thread, and whether the driver's sender —
which never idles, so its duty reading cannot say "full" — is then the limit.

### 2l. A core for every thread, on a rented host — and the engine still steps

Branch `cloud-deploy`: tooling in `cf6e302`, the write-up and four harness fixes uncommitted at close.
Measurements.md L0–L4 and A6–A10 carry every figure; this is the narrative.

**The problem.** D1–D2 put the laptop's knee at its core count and left the question that mattered:
where does a shard knee when no thread shares a core, and is the driver's sender — the loop that never
idles — then the limit? Status §3 item 3 was paused until a 16-core machine existed.

**What was built** (`deploy/cloud/`, `e2e/pin.sh`). A cloud-init Ubuntu 24.04 host on Linode, created
and torn down by `bench.sh` (`up`, `check`, `probe`, `sync`, `run` inside tmux, `tail`, `fetch`,
`down`), firewalled to the caller's SSH. A boot-time `most-cpus` service offlines SMT siblings, writes
a thread-per-core map to `/etc/most-cpus.env`, and on first boot writes `isolcpus`/`nohz_full`/
`rcu_nocbs`/`idle=poll` for the agents' cores. Both measurement scripts gained **`PIN=`**: every process
launches on the housekeeping cores, then each named agent thread is moved to a core of its own; the
load generators get four *non*-isolated cores, because an isolated CPU is outside load balancing and a
multi-CPU isolated mask would stack every thread on its first CPU. With `PIN` unset the scripts are
unchanged, which a local sweep confirmed. Size was a decision: Linode's vCPUs are hardware threads, so
`g7-dedicated-32-16` is most likely eight cores; `64-32` with siblings offlined is sixteen.

**What the first boot said.** The hypervisor shows a flat topology — 32 cores, one thread each — so
nothing could be offlined from the guest's own view. `bench.sh probe` spins two loops on every vCPU
pair (496 of them) and found a clean perfect matching: 16 disjoint pairs at 0.80–0.82 of solo
throughput, every other pair at ~1.0. One CPU per pair went into `/etc/most-cores` and a second boot
applied it. A pairing the hypervisor hides can be measured; that probe is now part of `bench.sh`.

**Four harness defects, each caught by the pinning guard refusing to run** rather than by a wrong
number. `pin.sh` fails if an order-path thread cannot be found by name, precisely so that a run with an
agent left unpinned cannot be labelled pinned, and it fired four times: the engine's service thread is
`matching-engine` (`EngineMain` sets `serviceName`, and the agent thread takes it), not Aeron's default
`clustered-service-*`; a **native image truncates a thread name to its *last* 15 characters** where
HotSpot keeps the first (`ket-data-poller`); a native image's **main thread carries the image name**,
so `matching-engine`'s main thread matched the service pattern and was pinned beside it — the main
thread is now never pinned; and the sweep's `cores` column counted `nproc`, which under `isolcpus` is
the six CPUs the shell may use, not the sixteen online. Also a design error, not caught by anything:
**an unpinned arm on an `isolcpus` host is meaningless** — unpinned, the whole shard shares six
cores — so both unpinned sweeps were `INVALID` and are not recorded.

**The answer.** The knee is ~275–300k/s aggregate, half the laptop's, and the per-order cost says why:
1.2–1.4 µs for a whole new order on a 2.0 GHz Zen 3 against 0.38–0.58 µs on the M4 Pro. The engine's
service thread is the one that binds, with a core of its own: 26% at 100k/s, 49% at 200k/s, 100% at
300k/s, median `newOrder` flat. That is D1's one-step collapse **without** core starvation, so D2's
reading needs correcting — freeing cores moved where the step lands; it did not cause it. The driver's
sender is not the limit: ~2% of a core by `/proc`, parked in `BackoffIdleStrategy`, as are the
conductor, archive and consensus module. Huge pages for the engine's on-heap pool (THP, ~3% per order)
and the log on `/dev/shm` instead of disk moved nothing. Native images knee in the same place; their
median is a few µs higher and their tail inside a run-to-run spread (p99 2.4–22 ms at a sustained
200k/s across eight runs) that is wider than any configuration difference on this host.

**Two things this opened.** What fills the engine thread past the step is unexplained: every stage
shows multi-millisecond maximum stalls at every rate, and the service container's Aeron work is
untimed. And **the duty counters of the cluster host's agents disagree with the kernel** — the sender
reads 92–97% of a core where `/proc` says ~2% — which contradicts Design.md §7's statement that time
inside the wrapped strategy is never counted, and puts D1's "sender ~98%" in doubt.

**What the check is.** `bench.sh check` shows the topology, the command line and the map before any
run; `pin_threads` prints every thread it moved and refuses on a missing one; `run-e2e.sh` with native
binaries matches the JVM's counts exactly (10,944 reports, 5,142 trades, 802 cancels, 0 rejected, 0
unanswered). The attribution histograms are in `docs/baselines/linode-*`. The host ran about an hour
and was deleted with its firewall (`bench.sh down`, verified empty by tag).

## 3. Decisions that are load-bearing

Change any of these and something breaks in a way that is hard to trace back.

* **An order is exactly one cache line.** Eight longs, bit-packed, and full. Anything else an order
  needs goes in the parallel `ColdField` array rather than evicting a field or doubling the stride —
  `origQty` lives there, and so should a future validity that matching does not read.
* **`cumQty` is stated by the engine, never subtracted.** A terminal report carries `leavesQty = 0`
  either way, so `origQty - leavesQty` calls a cancelled order fully filled. `origQty = 0` means
  *unknown* and travels as `Enrichment.UNKNOWN`.
* **The gateway holds no per-order state.** That is what makes it disposable, and therefore what
  makes gateway HA a deployment choice. Anything that puts state back in it takes that away.
* **The engine's participant map is node-local egress routing, not replicated state.** It is what
  lets the registry be re-read under a running node. It stops being true if the engine ever rejects
  an order on a binding — which is why **enforcement lives in the gateway** (§2j): a refusal that
  never enters the log may rest on a node-local file. The engine only counts.
* **A snapshot through consensus needs an operator identity, and its result is the cluster's
  answer.** Aeron's default authorisation refuses every snapshot request; a requester that reads the
  offer reports success anyway (§2j). Prove a snapshot by the recording log.
* **The dynamic collar uses a snapshot** taken at the aggressor's arrival, never the live reference.
* **`seqNum` and `shardId` live on the book event**, not only the derived feeds, because L3 is
  forwarded verbatim. Stamped where the event is *generated*, so they survive failover.
* **One security file per shard**, read by all four processes, with a fingerprint printed at startup.
* **Discovery publishes the gateway's endpoints**, not the cluster's.
* **A throw is deterministic and therefore fatal to every node at once.** Bad input is rejected.
  With one exception worth knowing: inside a snapshot restore it is not even that, because
  `Image.poll` catches a handler exception and advances the position anyway. There the order is
  silently dropped instead.
* **The archive and cluster directories persist; the Aeron directory never does.** The first pair
  are the shard's only resumption point. The third is IPC buffers, and keeping it blocks the next
  start with "Active media driver detected".
* **A restore refuses rather than degrades.** A security may leave a shard only once its book is
  empty; changed geometry, an out-of-range price or counts that do not add up stop the node with a
  report an operator can act on. Every node boots the same geometry and reads the same snapshot, so
  "no node starts" is the correct outcome rather than a split brain.
* **An order the gateway cannot forward is not consumed.** `controlledPoll` returns `ABORT` on
  cluster backpressure so the fragment is offered again. On the handled path it returns **`COMMIT`,
  never `CONTINUE`** — `CONTINUE` commits the position only at the end of the whole poll, so a later
  `ABORT` would rewind past fragments already forwarded and send them to the cluster twice.
* **The database authors reference data; it is never on a boot path.** Processes read a published
  file. A DB outage must not stop a node starting, and — the reason that actually matters — a write
  landing between two nodes' boots would give them different geometry. They would not fail; they
  would diverge on the first order.
* **The control plane never reimplements a domain rule.** It builds real `SecuritySpec` /
  `ShardSpec` / `ShardRoute` / `Universe` objects from its rows and calls their methods, so the ISIN
  check, the wire-derived length limits and `fingerprint()` have exactly one implementation.
* **The trading calendar lives outside the engine.** `onTimerEvent` is deliberately still unused.
  Scheduling outside costs no determinism — the emitted `SessionTransition` is sequenced through the
  log — and keeps holidays and DST out of the state machine.
* **A phase difference is a path, not a destination.** The uncross runs only on
  `OPEN_AUCTION → CONTINUOUS`, so catching up walks the intermediate phases. Sending `CONTINUOUS`
  directly is accepted and silently skips the auction.
* **A halted security is never reopened automatically**, by the scheduler or anything else.
* **A depth snapshot is taken on the feed's own poll thread**, between two messages. The image and
  the `l2SeqNum` stamped on it are consistent only because nothing can be applied in between. A
  timer thread would produce a torn image that no consumer could detect: every message individually
  valid, describing a book that never existed. The control plane builds its conflated images under
  the same rule.
* **A synchronised subscriber ignores snapshots.** The image is consistent at its own sequence, but
  increments past that sequence were applied straight to the book and never buffered — installing it
  would silently rewind the book to an older state.
* **Every consumer rebuilds books with `reference`'s `DepthFeedAssembler`.** The CLI, the control
  plane and any future FIX market data adapter share one splice and one aggregation, so two
  consumers cannot disagree about a book and have no way to notice. This is the L2 form of the rule
  above it about domain logic.
* **The browser is given conflated images, never the market data protocol.** No sequence numbers, no
  gap detection, no snapshot splicing in TypeScript — that would be a second assembler in a language
  where it cannot be tested against the publisher. It also means a slow console cannot reach back
  into the feed: there is no back-pressure path from a browser to the market data thread.
* **Metrics are write-only, and that is what makes reading a clock in the engine legal.** Design.md
  §1 bans `nanoTime()` there; the ban is on time *influencing replicated state*, not on observing it.
  The rule, and the test for any probe added later: **enabling metrics on one node and not another
  must be incapable of changing the log, the books, or a snapshot.** It holds only while no histogram
  is ever read by a branch, snapshotted, or put on a feed a consumer acts on. Metrics are excluded
  from `EngineConfig.fingerprint()` for the same reason — they are node-local, and an operator may
  reasonably enable them on one node to diagnose it. `MetricsDeterminismTest` is the check; the
  plausible way to break it is adaptive load-shedding based on a measured latency.
* **The three `inline` keywords on `matchAggressive`, `offerToSnapshot` and `publishBookEvent` are
  load-bearing and the compiler will not defend them.** Removing any one compiles without a warning
  and silently boxes its callback's captured state — 232 bytes an order on the match path. The
  allocation tests are the only thing that catches it, which is why their tolerances must not be
  widened to make a failure go away.

---

## 4. Open issues

**Moved to [`Status.md`](Status.md) §2.**

---

## 5. To do next

**Moved to [`Status.md`](Status.md) §3.**

---

## 6. Lessons learned

### From the first sessions

Five defects reached working code and were caught only by running the system.

| Defect | Why tests missed it |
| --- | --- |
| Engine overwrote Aeron's cluster session header | The fake `ClientSession` did not reserve `SESSION_HEADER_LENGTH`, so tests validated a layout the real cluster rejects. |
| Gateway reported a cancelled order as fully filled | `cumQty` derived as `origQty - leavesQty`; a terminal report carries `leavesQty = 0` either way. |
| Ladder-range invariant unenforced | §3.2 stated it; nothing checked it. |
| Gateway's cluster session died after 10s idle | No test idles. A cluster client must send keepalives. |
| No process exited on `SIGTERM` | No test shuts anything down. |

**A fake that is easier than reality validates nothing.** The session-header bug existed *because*
the test double was convenient.

**Tests written after the implementation tend to encode it.** The cancel test was named "reports the
filled portion" and asserted the buggy value. Writing the expected value from the specification,
before looking at what the code returns, would have caught it.

**The categories the tests never covered at all** — idle behaviour, shutdown, startup ordering. Not
tested badly; absent.

### From measurement and the control plane

**You cannot find a silent drop without generating enough load to cause one.** Both gateway defects
in this session were found by `most load` on its first real run, not by any test:
`droppedToCluster=11` — orders the gateway accepted, never forwarded, and never told the client
about — and ~8% of execution reports lost outbound (`droppedToClient=267853` of 3.26M). Both had
been in the code since the gateway was written, both were invisible at the one-order-at-a-time rate
every other test used, and one of them
loses a client's order with no acknowledgement and no rejection, which is the single outcome a client
cannot recover from.

**A plausible fix that is not measured is a guess.** Having fixed the inbound drop, I applied the
same idea outbound: a bounded spin-retry to absorb bursts. It measured badly — `clientRetries` fired
5.8 million times, the spin burned the poller thread that also drives ingress and keepalives, and
round-trip p99 went from 0.3 ms to **4 ms** while still dropping 22k reports. I reverted it. The fix
felt obviously right and was obviously wrong, and only running it said so.

**Coordinated omission is not an academic concern.** `most load` reports service time (from the
actual send) and response time (from the scheduled send) separately. In the very e2e run that
verified it, a scheduler hiccup showed p99.9 service 690 µs against response 4114 µs — a 6x
difference that reporting only the first would have hidden entirely.

**A benchmark will silently measure the wrong thing.** The first sweep reported `BOOK_CAPACITY` on
83% of orders: the e2e config sets `maxOrders=10000`, the book filled in seconds, and the run was
measuring the reject path at full speed. It looked like a successful benchmark. The summary now
prints reject reasons with counts precisely so that failure is loud.

**`CONTINUE` would have duplicated orders.** In the controlled-poll fix, the natural-looking choice
commits the read position only at the end of the whole poll, so one `ABORT` rewinds past fragments
already sent to the cluster. `COMMIT` per fragment is what makes leaving a fragment unconsumed safe.
This is the kind of thing that passes every test and corrupts a book in production.

**Never reimplement a hash whose only job is agreement.** `ShardSpec.fingerprint()` is an ad-hoc hash
over a canonical string with an exact sort and exact separators. A SQL or TypeScript reimplementation
that drifted by one separator would produce a control plane confidently reporting agreement between
processes that disagree — worse than not checking at all. The control plane constructs the real
domain objects and calls the real method, and the test that carries the argument imports the
checked-in shard file, round-trips it through Postgres, and asserts the fingerprint is unchanged.

**Process state leaks between tests long after the database is clean.** Two failures came from a
shared Spring context: observed feed state (a halt from one test made the next one skip) and an
in-memory dedupe map. The database truncate did not touch either. The dedupe fix was the better one —
deduplicating against the audit log instead of a field made it stateless, removed the need for a
reset hook, *and* meant a restart no longer re-logs conditions that were already there.

**Reconcile, do not trigger.** The scheduler compares the phase the calendar wants against the phase
the feed reports and sends the difference. That one choice made it idempotent, self-healing after an
outage, and free of any missed-timer state — three properties that would each have needed separate
machinery in a timer-based design.

**When two states are indistinguishable, refuse to guess and say what unblocks you.** A cold engine
has emitted no `SessionChanged`, so its phase is unknown. Assuming `CLOSED` is right after a cold
boot and catastrophic after a control-plane restart mid-session — and the two look identical from the
feed. The scheduler waits and names the remedy in the skip message. The first version just said
"waiting for a phase", which is true and useless.

### From building the write half of the console

**A media driver that runs out of space does not fail politely.** The first 100k/s run died with
`InternalError: a fault occurred in an unsafe memory access operation` in the load generator *and*
in the client-side driver — which reads like a JVM bug and is actually SIGBUS on a mapped file whose
tmpfs is full. `/aeron` was 256 MB and 100% used. The cause was partly this session's own work: every
subscription image is a log buffer of three terms, and adding L2, L1 and the snapshot stream took the
control plane from two subscriptions to five before the CLI added its own. The volume is now 1 GB,
matching the shard's. **Check the mount before believing the stack trace** — the general form of the
lesson already in this document.

**A number that is an identity must not be a JSON number.** `universeVersion` is a 64-bit hash, and
JSON numbers are IEEE 754 doubles in every browser: `9181280125937456696` came back from
`JSON.parse` as `9181280125937457000`. The read-only slice had been displaying that for as long as
it existed, and nothing in Kotlin could ever have noticed — the DTO, the database and the manifest
are all correct. It is fixed with `ToStringSerializer` on the three identity fields, and the test
that guards it asserts on the JSON *text*, because that is the only place the defect exists. The
same reasoning is why a price is parsed out of an input field by string manipulation rather than by
multiplying a float: `1.1 * 1e8` is `110000000.00000001`, and a tick size one unit out is not
noticed until a book misprices.

**A confirmation dialog that asks "are you sure?" trains an operator to click through.** The four
market-moving commands each spell out the specific consequence instead — a session transition is
shard-wide, so reopening one halted security reopens every book on the shard; going to `CONTINUOUS`
from anywhere but `OPEN_AUCTION` is accepted and silently skips the auction. Those are the facts
that are hard to recover from getting wrong, and they belong on the screen at the moment of the
decision rather than in `Design.md`.

**`sent` without `confirmed` is a state, not a notification.** The outcomes on the operations screen
are a list that stays, not toasts that fade. An unacknowledged command whose effect was never seen
on the feed is precisely the thing an operator has to keep looking at.

### From building the recovery feed

**A torn image is the failure mode you cannot detect downstream.** The snapshot is taken on the poll
thread, between two book events, because the `l2SeqNum` stamped on it and the levels walked for it
are only consistent while nothing can be applied in between. Move it to a timer thread and every
message stays individually valid while the book they describe never existed — nothing about it looks
wrong at the receiver. The same reasoning is why Begin and End repeat the sequence and the level
count: a cycle that lost its middle must be discarded, not installed.

**Recovery has a direction, and the wrong one rewinds the book.** Installing a snapshot over an
already-synchronised book looks harmless — it is a consistent image — but increments past its
sequence have already been applied straight to the book and were never buffered, so it silently
rewinds to an older state. The assembler ignores snapshots while synchronised, and that is the one
ordering mistake in the class that would produce a plausible, wrong book.

**A test can pass for a reason that has nothing to do with its name.** "An update the image already
contains is discarded rather than replayed" originally sent the update *before* any snapshot, where
it was dropped for an entirely different reason — no image to apply it to. It passed, it would have
kept passing with the splice removed, and it tested nothing. Rewriting it to deliver the update
mid-cycle, then flipping `>` to `>=` in the assembler to watch it fail, is what turned it into a
test. The generic version of that check is worth doing to any test guarding an off-by-one.

**The fake had to go before the test was worth writing.** The strongest test here — a subscriber
reassembling a book and it equalling the publisher's — is only meaningful if both ends are the
shipped code. That meant hoisting the one line of Aeron in the feed publisher behind a `FeedSink`
so the real encoders could be driven in a test, rather than writing a second encoder for the test to
speak to. Same lesson as the cluster session header, applied before the bug instead of after it.

### From the first native images

**A missing `--add-exports` does not fail a native build; it produces a binary that dies later.**
Agrona 2.x reaches `jdk.internal.misc.Unsafe`. Without the export at image-build time the analysis
cannot see the class, silently omits it, and native-image reports success. The binary then starts,
loads its config, prints its fingerprint — and throws `NoClassDefFoundError: jdk.internal.misc.Unsafe`
on the first `UnsafeBuffer`. **The smoke test that hid it was the careful one:** running the binary
with no media driver exercised config, book allocation and the fingerprint, and exited cleanly on the
expected `DriverTimeoutException` — which is thrown *before* the CnC file is ever wrapped. Only a run
against a real driver reaches the defect. A startup check that stops at the first expected failure
proves less than it appears to.

**The measurement's own warm-up looked exactly like the bug.** The first allocation run reported 648
bytes over 200,000 rejected orders. That is 0.003 bytes an order — a *fixed* cost, reported as a
rate, because it landed in the first window after the warmup. Windows two through eight read zero,
including at 800,000 orders each. The fix was to stop asserting on one window: a steady-state cost
appears in **every** window, so asserting "at most one of eight is non-zero" cannot admit a rate
while absorbing a one-off. Tuning warm-up counts per test was the alternative and it is the wrong
shape — the test that is "not warmed up enough" is indistinguishable from the test that found a bug.
The soak has the same trap in a different costume: 95MB of pools allocated at startup reads as ~490
bytes/order over a 200k run, which is why it measures a slope across two runs instead of a total.

**A measurement that has never been seen to fail is not a measurement.** Both harnesses were
validated by deliberately breaking the thing they exist to catch: `inline` removed from
`matchAggressive`, which compiles without a murmur and boxes the fill callback's captured
`remainder`. 232 bytes an order in 8 of 8 windows, 152 in the soak. Doing this took ten minutes and
is the only reason the zeros above mean anything — the same lesson as flipping `>` to `>=` in the
depth assembler, applied to a measurement rather than a test.

**The Epsilon work found a bug that had nothing to do with allocation.** Getting a summary out of
SubstrateVM required stopping the engine gracefully, which is how it emerged that no native binary
handled SIGTERM at all. Chasing a measurement through a system tends to walk paths no test does —
here, shutdown, which §6's first table already records as a category the tests never covered.

**A one-line grep is a better check than the smoke test was.**
`grep -c 'jdk.internal.misc.Unsafe' <binary>` returns 0 on an image built without the exports and 3
on a correct one. It needs no media driver, no cluster and no config, and unlike starting the binary
it cannot give a false pass — which makes it the thing to run against an image built somewhere you
cannot execute it.

**The JVM's `--add-opens` and the image's `--add-exports` are not the same knob, and both forms of the
export are needed.** The run-time flag on the start scripts told us nothing about the image. The bare
`--add-exports` applies to the image; the `-J` form opens the package to the builder JVM, which is
what actually loads the class during analysis.

**Design.md §7 specified a `reflection-config.json` that turned out to be unnecessary, and omitted
the flags that were.** The section was written from expectation. Building the thing replaced four
lines of guesswork with what the compiler actually requires — including that `org.agrona.UnsafeApi`
*cannot* be deferred to run time, because Agrona reaches `Unsafe` through an `invokedynamic` site
whose resolution during analysis runs the `<clinit>`. It sits one line below
`--initialize-at-run-time=...UnsafeBuffer`, which looks like a contradiction and is not.

**The same flags belonged in one place from the start.** Four modules carried four copies of the
`graalvmNative` block. Fixing Agrona meant fixing it four times, which is the shape of a bug that
survives in whichever process nobody rebuilt — the same argument this project already makes for one
security list and one depth assembler. They now live in the root build.

**Native bought no latency here, and saying so is the point.** At the e2e's 5k/s the native and JVM
runs are within noise (p50 209 µs against 206 µs), because at that rate the measurement is the
plumbing. The reasons to do this are startup, no JIT warm-up, and Epsilon. Reporting the p50s as a
native-versus-JVM result would have been a number with no content.

### From instrumenting the engine

**Instrumenting the engine found a latent shutdown race that had nothing to do with metrics.**
`EngineMain` printed its shutdown line *after* the `ShutdownSignalBarrier` block, and CLAUDE.md
already warned that closing the barrier releases the signal and the process exits at once. The
single short line won that race every time, so nothing ever looked wrong. Adding a histogram write
to the same place lost it, and the summaries silently vanished. The gateway had it right — its
counters are printed inside the barrier, with a comment saying why — which is the tell: when two
processes solve the same problem differently, one of them is wrong even if both currently work.

**The instrument has to report its own cost or its numbers are quietly wrong.** The first
calibration measured the clock at shutdown and reported ~69 ns, then ~167 ns from an un-JIT'd loop,
against a real cost of ~10. Measuring it at construction, when the process is quiet, and as an
average over many reads rather than a median of back-to-back deltas — `nanoTime` granularity on
Apple silicon is ~41 ns, so consecutive reads often return the *same* value and the median says more
about the timebase than the call — gives a stable figure that both processes now agree on. At 0.42 µs
medians a 10 ns probe is a few percent, which is worth printing beside the number rather than
leaving a reader to assume the probe is free.

**One decimal place hid the range the engine works in.** The shared formatter printed microseconds
to one decimal, which is right for a round trip measured in tens of microseconds and useless for a
match step that costs 40 ns — it read as "0.0". Two decimals is 10 ns, which is exactly the clock's
resolution and no finer, so the last digit means something.

### From putting the books on a screen

**Throughput holds; latency is the thing Docker taxes.** A saturation sweep against the container
stack, band and parameters identical to the native run in `LocalTesting.md` §9:

| Target | Achieved | Reports | Unanswered | Dropped | ack p50 | ack p99 |
| --- | --- | --- | --- | --- | --- | --- |
| 10k/s | 10,000/s | 65k | 0 | 0 | 5.3 ms | 32.8 ms |
| 50k/s | 50,000/s | 326k | 0 | 0 | 5.5 ms | 31.2 ms |
| 100k/s | 100,000/s | 651k | 0 | 0 | 3.0 ms | 29.4 ms |
| 150k/s | 150,014/s | 978k | 0 | 0 | 3.2 ms | 58.5 ms |
| 200k/s | 199,998/s | **1.30M** | 0 | 0 | 7.0 ms | 56.5 ms |

`pacing lateness` p50 stayed at 0.0 µs throughout, so the generator was never the bottleneck, and no
knee was found: **200k orders/s into one book, 2x the design's per-book target, with nothing dropped
and nothing unanswered.** Throughput is not where this system is short.

**Latency at 3k/s was worse than at 100k/s** (5.8 ms against 3.0 ms), which is the shape of a fixed
per-wakeup cost rather than queueing — batching under load amortises it. That is a strong hint the
millisecond-scale figures here are the VM's networking, not the exchange: the same code on a
host-native IPC path reports 47 µs p50 at 100k/s. Never quote the two together.

**The console's subscriber fell behind, and the snapshot is the only reason that was survivable.**
Through 1.35M orders it took 9 gaps, missed 50,564 depth messages and desynchronised **15 times** —
and finished with both books correct, having rebuilt them from 17 snapshots. That is exactly the
bargain `MaxMulticastFlowControl` makes: a slow subscriber takes an unrecoverable gap instead of
throttling the publisher, and the exchange sustained 200k/s while the web backend was drowning.
Before the recovery feed existed, the first of those 15 gaps would have left the console showing a
wrong book permanently. A `SleepingIdleStrategy` poller is the right trade for a dashboard, but if
the console is ever expected to keep up rather than resynchronise, that is the knob.

**A version that ticks on a timer is not a version.** The first cut of the conflated image
incremented one counter for every book on every interval, so an idle book looked new four times a
second and every open console received it for ever. Watching the SSE stream with `curl` on a market
where nothing was happening is what showed it — the tests all passed, because each asserted what one
publish did rather than what two identical publishes did. The version is now per book and changes
only when the content does.

**Three feeds, and taking two of them is a plausible mistake.** L2 carries the increments, the
snapshot stream carries the images that make them applicable, and L1 carries the last trade. I
subscribed to the first two, everything worked, books were correct — and `lastTradePrice` stayed
null through a trade, because a *synchronised* subscriber ignores snapshots and the last trade rides
only on L1. It took driving a real trade through the running stack to see it; nothing about the book
itself was wrong.

**A test that asserts a gap must first establish a baseline.** `FeedSequenceTracker` treats the first
message from a shard as the baseline rather than counting everything before it as lost, which is
correct and which made one of my tests wrong: a single update with a high sequence is not a gap. The
test was asserting my assumption rather than the behaviour, and the behaviour was right.

### From containerising it

**One media driver is one network identity, and nothing says so until it fails.** The five shard
processes share a media driver, which lives in one container; a driver can only bind addresses that
container owns. So `control=gateway:20002` dies with "Cannot assign requested address" even though
`gateway` resolves perfectly — the process asking for the endpoint is not the process that binds it.
Every Aeron endpoint a shard exposes belongs to the driver's container, and the ports are what
distinguish them. Obvious in hindsight and invisible in advance.

**A named volume inherits the ownership of the mount point in the image.** The first failure of the
stack was `could not create archive directory`: a volume mounted onto a path that did not exist in
the image lands root-owned, and a non-root process then cannot write to its own working directory.
Creating and chowning the mount points in the Dockerfile fixes it — and the uid has to be identical
in every image that shares the volume.

**I nearly reported a feed bug that was my own misreading.** `eventsSeen` on `/api/status` sat at 7
while orders flowed, which looked exactly like a stalled L3 subscription. It is not: `ClusterLink`
only decodes `SessionChanged`, `VolatilityHalted`, `AuctionUncrossed` and `TradeExecuted`, and only
those call `accept()`. Resting orders that never trade correctly count nothing. The lesson is the
older one in a new place — read what the counter counts before believing what it implies.

**But that reading did surface a real one** (below, open issue 5): the same `else -> Unit` means
the sequence tracker never sees the seqNums of order events, so it reports a gap for every one of
them. On a busy book those counters would cry loss continuously.

### From adding authentication

**A security test double that is more permissive than reality is bad; one that is more *convenient*
is worse.** `SecurityMockMvcRequestPostProcessors.csrf()` attaches a valid token to every MockMvc
request unconditionally. That is exactly what you want for testing the endpoints *behind* the door —
and it means a filter chain that never issued a CSRF token to anybody would pass the entire MockMvc
suite while no real browser could log in, because `/api/auth/login` is itself CSRF-protected and a
fresh browser has no token to present. I only found it by driving the running server with `curl`,
where my own first attempt at signing in failed. `AuthBootstrapTest` now runs a real Tomcat over the
whole sequence. Same lesson as the Aeron session header, in a new place.

**Then the fix I reached for first was not the fix.** Suspecting deferred token resolution, I added
Spring's documented `CsrfCookieFilter` alongside the eager-resolution setting. The MockMvc test
still failed — and once the real-server test existed, removing the filter changed nothing: the
one-line `setCsrfRequestAttributeName(null)` was doing all the work. The filter would have shipped
as permanent dead code with a confident comment explaining the mechanism it was not providing.
Deleting it needed a test that could tell the difference, which the MockMvc one could not.

**Sent is not applied, and the audit inherits that.** The REST layer already answered 202 rather
than 200 because an operator command is unacknowledged; the audit records `sent` for the same
reason, including rows with `sent: false` for commands that never left. Flattening either to
"success" would have made the audit less true than the API it records.

**The first account is where a security model quietly fails.** Seeding a known default password
would have left the control plane looking protected while being open to anyone who read the docs.
It generates one and logs it instead, which is mildly annoying exactly once.

### From making a restart resume

**A test that passes can still be measuring nothing, and the second version of the same test can
pass for a second wrong reason.** `run-restart.sh` first compared rendered book depth before and
after a restart. It matched perfectly — from a full log replay, with `loadSnapshot` never called,
because only the service container had been restarted and the consensus module kept running. Fixing
that surfaced the next one: after a genuine snapshot recovery the rendered book is *empty*, because
market data derives it from the event stream and a restored engine republishes nothing. So the thing
the test had been asserting on was never going to be evidence either way. It now asserts on the
engine's own restore line and then trades against a restored order, which is the only assertion here
that cannot be satisfied by accident.

**The generic form: when a test passes, ask what else would also have made it pass.** Both wrong
versions were caught by asking that, not by the test failing.

**Chasing a measurement walked into a defect it was not looking for.** Mutating the ladder range
check away to validate the new tests was meant to show an `ArrayIndexOutOfBoundsException`. It showed
a count mismatch instead — because `Image.poll` catches an exception from its fragment handler,
reports it to the client error handler and advances the position anyway. The order is silently
dropped and the book comes back quietly wrong. The whole design had been reasoned about on the
premise that a throw on this path would be loud; it is the opposite. Same shape as the Epsilon work
finding the SIGTERM bug: pushing on a measurement walks paths nothing else does.

**A default that destroys data must be spelled, not implied.** `val fresh = !args.has("keep")` meant
every caller that simply did not think about the flag wiped the archive — which was every caller.
Inverting it to `args.has("fresh")` is the same line of code and the opposite policy, and the
process now prints which mode it is in so it is never inferable from an absence.

**The right line to draw was already drawn somewhere else.** The first cut persisted the Aeron
directory along with the archive and cluster directories, and the next restart died on "Active media
driver detected". The compose file had been making exactly that distinction for months — aeron on
tmpfs, `cluster-data` a durable named volume — and reading it first would have saved the round trip.

**A refusal has to leave cleanly or it punishes the person fixing it.** The refused restore first
called `Runtime.halt(1)`, which skips `ClusteredServiceContainer.close()` and leaves the service's
cluster mark file live. The operator who reads the report, corrects the security file and restarts
immediately then meets "active mark file detected" for ten seconds — a second, unrelated,
inexplicable failure at the exact moment they have done the right thing.

**A snapshot is the first operator command that can honestly say `confirmed`.** Aeron answers the
admin request, unlike the four commands the engine applies or rejects in silence. It was worth
saying so on the operations screen rather than reusing the `sent`-without-`confirmed` wording, which
exists precisely because it is usually the truth.

### From closing the recovery gaps

**The same mistake twice, one layer apart, and neither was visible from the code.** Publishing an
image and a snapshot looked obviously sufficient both times. It was not, because a synchronised
subscriber ignores snapshots — a rule this document already lists as load-bearing, and which I had
read, and which still did not occur to me until the e2e failed. Then, having added increments, it
failed again for the *same reason at the subscriber*: the assembler had synchronised on the oldest
image still in the buffer and was discarding the good ones. The general form: **a rule you wrote
down as load-bearing is one you will still walk into**, and the only thing that catches it is a test
that drives the real consumer.

**"Works when the market is busy" is not working.** Both failures would have been invisible under
load — a stray increment would have corrected the book within milliseconds. They only appeared on an
idle book, which is precisely the state a system is in just after a recovery. A quiet market is the
adversarial case for anything that depends on the next message arriving.

**Asking what is downstream turned a hard design into an easy one.** The first framing of the image
was per order, which raises real questions: a million messages on a full book, against a resting
depth nobody has measured. `DepthBook` keeps no per-order state and every consumer past it rebuilds
from L2 aggregates — so one message per occupied *level* is enough, bounded by `levelCount`, and
`PriceLadder` already had the aggregates. The expensive version was solving a problem nothing had.

**A determinism constraint decided the protocol.** The image is published when a publication happens
to connect, which is a node-local moment, so it must not touch anything a snapshot carries. That
ruled out stamping fresh sequence numbers and pointed straight at the pattern already in the system:
carry the sequence as a baseline, as `DepthSnapshotBegin` does. The constraint made the design rather
than fighting it.

**An allocation test found a real allocation on its first run — in the harness.** `OperatorCommands`
constructs a fresh encoder per call, which is correct for a command an operator sends by hand and
wrong inside a loop measuring the engine. Worth knowing before reading such a failure as an engine
regression.

### From the gateway journal

**The best version of a durable structure was the one that replaced the in-memory one rather than
shadowing it.** The obvious design — an append-only log beside the maps — has a write per fill,
needs compaction, and can drift from the thing it mirrors. Making the mapping *be* the store removed
all three at once, and it is the idiom the engine already uses. The question that got there was not
"how do I persist this?" but "why are there two copies?"

**A mutation that passes has to be looked at twice.** The first attempt at breaking the write
ordering *added* a state write without removing the trailing one, so the ordering was unchanged and
the test passed — which reads exactly like a test that does not work. Checking why is what turned it
into a real check; taking the pass at face value would have left the property untested and believed.

**Ordering is invisible in the artefact, so it needs a seam.** Nothing about a journal file says in
what order its words were stored, and that order is the whole crash-consistency argument. The test
hands the journal a buffer that records what it was asked to write. Ten lines, and the only reason
the argument is more than a comment.

**A flag built for one failure turned out to be the escape hatch for another.** `Enrichment.UNKNOWN`
was added last session to stop the gateway inventing a `cumQty`. It is what makes journal exhaustion
survivable this session: an order with no slot is forwarded untracked rather than rejected, because
refusing an order the engine would have accepted to protect a bookkeeping structure is the wrong
trade. Honest reporting is load-bearing infrastructure, not decoration.

**Three failed e2e attempts, three facts about the system.** The maker's fill never reaching the
gateway is now open issue 5d and raised issue 3's priority; the aggressor cancelled by self-match
prevention was the test's own fault; the third worked. Removing `gateway.journalFile` and watching
the step fail with `cum unknown` is the only reason to believe it measures the journal.

### From binding the participant

* **Read the framework's source before designing around it.** Two facts decided the whole shape of
  §2g and neither is in the Javadoc: the encoded principal reaches the service through
  `onSessionOpen`, and `ServiceSnapshotLoader` restores sessions *without* replaying that callback.
  The first meant no new wire message was needed; the second is a bug that would only have appeared
  after a restart, on a shard whose participants had all been quiet — the least observable
  combination there is. Twenty minutes in `aeron-cluster` sources, against a feature that would
  otherwise have shipped a new SBE message and a snapshot field it did not need.
* **The lenient branch is the bug.** Credentials that fail to verify could plausibly fall back to an
  anonymous session — the client still connects, still trades, nothing visibly breaks. That is
  precisely why it must not: the only symptom is the quiet loss of exactly the fills this mechanism
  exists to deliver. Refusing to start on a half-configured identity (an id with no secret, an id
  the registry has never heard of) is the same rule one layer up.
* **A second source of truth deserves a second fingerprint, not a wider first one.**
  `ShardSpec.fingerprint()` is recorded by the control plane and published in every release. Folding
  the registry into it would have invalidated every value already written down, and would have made
  rotating a gateway secret indistinguishable from a change of shard geometry. §4.12 has been asking
  for exactly this distinction about `auctionMaxPasses`; this is the first place it was actually
  made.
* **An e2e assertion nobody has run with the mechanism disabled is a guess.** §4c passed the first
  time it ran, and passed again with the participant registry taken out — because Aeron's five
  second publication linger meant the restarted gateway re-forwarded the maker's order and the
  engine re-learned the route from the replay. The step was green and measured nothing. The same
  discipline the unit tests already get from mutation testing, applied to the e2e.
* **A workaround in a test is a bug report.** §2f's e2e comment explained at length why the
  partially filled order *had* to be the aggressor's remainder. That paragraph was the issue, in the
  one place someone was guaranteed to read it. Removing a workaround is a good way to know a fix is
  real: §4c is the test §4b could not be written as.

### From moving origQty into the engine

* **The best fix for a hard problem was to delete the thing that made it hard.** Gateway HA had
  three plausible designs — a shared journal, a replicated one, partitioned adapters — and every one
  of them was a way of carrying `origQty` between machines. Asking why it was only in the gateway
  gave a fourth: eight bytes in a parallel array in the engine, and the problem stops existing. Same
  question as §2f's ("why are there two copies?"), asked one layer out and answered the other way
  round.
* **A reversed decision is not a wrong one, and the record should say which.** Design.md argued
  clearly that `origQty` belonged in the gateway, and the argument was sound: the eight words were
  full and the boundary could reconstruct the value. What changed is not the arithmetic but what it
  cost — being the only component that could not be replaced without losing something. The doc now
  says both, because a reader who finds only the new answer cannot tell whether the old one was
  considered.
* **Half a fix re-opens an old bug one layer along.** The obvious wire change is to add `origQty`
  and let the gateway subtract `cumQty`. That is precisely the defect in §6's first table — a
  terminal report carries `leavesQty = 0` either way — moved from the gateway into whoever reads the
  report next. Stating `cumQty` costs eight more bytes on an internal message and removes the
  class.
* **The e2e that proves a deletion is the one you could not have written before.** §4b used to prove
  the journal recovered `origQty`; it now makes the *same* assertion with no state file configured
  at all, which is a stronger claim from a shorter test. And §4d — two gateways, place through one,
  cancel through the other — is the assertion the old design made impossible to write, so its
  absence was not an oversight.
* **Fixing a config key nobody was reading.** `run-restart.sh` set `gateway.clientInboundChannel`
  where `GatewayConfig` reads `gateway.client.inbound.channel`. Four lines, silently ignored for as
  long as they existed, and the test passed because the values happened to equal the defaults. A
  test that would pass with its configuration deleted is configuring nothing.
* **A hot reload is a determinism question before it is a feature.** Re-reading a file under a
  replicated state machine is exactly the sort of thing that looks harmless and diverges a cluster.
  It is safe here for one specific reason — the map it feeds is node-local egress routing, not
  replicated state — and writing that reason down in three places was most of the work. It also
  produced the more useful result: **enforcement cannot be built on this**, which is a design
  constraint discovered by asking rather than by shipping.
* **The negative control is the test.** §4e passed the first time it ran. So did §4c last session,
  for a reason that had nothing to do with what it claimed. Running the whole script with
  `--participants-reload-ms 0` and watching §4e fail on the right line took three minutes and is the
  only reason to believe it measures the reload.

### Operational lessons

* **Counters must distinguish causes, not just count.** `liveOrders=0 rejectedLocally=0` looked like
  a clean gateway; it meant "never received" *or* "forwarded but nothing came back". Adding
  `forwardedToCluster` / `sentToClient` made the keepalive bug obvious in one line. The same
  principle later separated `clusterBackpressure` (retried, fine) from `droppedToClient` (real loss).
* **Silent failure is the expensive kind.** The keepalive bug, the dropped orders and the rejected
  `SecurityDefinition` are all the same shape: no log line, no exception, a counter at best.
* **A skip must be visible but must not flood.** The scheduler records a skip when its *reason*
  changes rather than on every tick, or a halted security would write the same row every five
  seconds all weekend.

### A mistake of mine, recorded because it cost the most time

While writing `LocalTesting.md` I lost a long stretch to a phantom: commands reporting success while
nothing reached the engine. The cause was my own teardown. `pkill` silently failed, I deleted the
Aeron directory out from under still-running processes, and every subsequent run talked to a
half-dead stack.

`LocalTesting.md` now insists on verifying `pgrep -f "com.engine"` returns zero *before* cleanup, and
that symptom is the third row of its troubleshooting table. The general lesson: **when behaviour is
inexplicable, verify the environment before debugging the code.**

### From the enforcement session (§2j)

* **"Confirmed" that only checks the offer is worse than "sent".** `ClusterAdmin` said `confirmed:
  true` next to a comment claiming the cluster answers, and for the whole life of the feature no
  snapshot requested over the network was taken. The engine's `restored ... from a snapshot` line —
  printed precisely so a replay cannot pass for a restore — is what exposed it, and only because a
  restart happened to be watched. Check the durable effect (the recording log), not a message.
* **A library default is a decision someone else made.** `AllowBackupAndStandbyAuthorisationService`
  was never chosen here; nobody knew it was there. The fix began with `javap` on the jar.
* **The interactive stack found what three test layers could not**: an unreachable egress address,
  a database that disagreed with the running registry, and the snapshot refusal. Each was invisible
  to unit tests and to e2e scripts that grep for a success message.
* **Shared images have shared consequences.** Rebuilding the CLI image recreated the cluster host
  inside its mark-file window and took the dev shard down.
* **The simplest framing won.** Enforcement had been "must go through the log" for several
  sessions; asking where a refusal *needs* to be deterministic removed a wire change, snapshot state
  and a sequenced command in one step.

### What worked

* **Deriving the SBE schema from `Design.md`** rather than retyping it — they cannot drift.
* **`allWarningsAsErrors`.** It flagged `inline` on functions taking no lambdas on the first compile,
  and later caught a `java.lang.Long` where `kotlin.Long` belonged in the control module. The flag
  applies to `control` too, which is right: it exists to protect the engine, but a module that opts
  out is a module that drifts.
* **Checking the library before designing around it.** `Long2IntHashMap` does not exist in Agrona.
* **Writing the doc by running it.** Every command in `LocalTesting.md` and `ControlPlane.md` was
  executed first.
* **Verifying against the real thing, not a mock.** The control plane's claims were checked by
  booting the whole e2e stack from a generated release (identical fingerprint and universe version to
  the hand-written run), then driving a market open, a genuine dynamic-collar halt, and its recovery
  entirely over REST. Every one of those steps found something a unit test had not.

---

## 7. Picking this up

**Moved to [`Status.md`](Status.md) §4**, together with the list of things that waste time if
unknown.
