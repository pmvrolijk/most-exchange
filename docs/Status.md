# Status

Where the project stands, what is open, and what to do next. **This is the session entry point** —
read this, not the handover. [`Handover.md`](Handover.md) is the archive: work records (§2a–§2h),
load-bearing decisions (§3) and lessons learned (§6).

Last updated at the end of the session that fixed the control plane's feed-gap counting and
measured the ten-security aggregate for the first time.

---

## 1. Where things stand

| | |
| --- | --- |
| Branch | **`master`** |
| Modules | 8 — `sbe`, `reference`, `discovery`, `engine`, `market-data`, `gateway`, `tools`, `control` |
| Kotlin | ~22,700 lines — 13,300 main across 64 files, 9,500 test across 49 |
| Frontend | ~3,100 lines of TypeScript and Vue across 24 files, outside the Gradle build |
| Tests | 463, all passing |
| Specification | [`Design.md`](Design.md) — authoritative. §8 is the open list |
| Rules | [`../CLAUDE.md`](../CLAUDE.md) — the traps. [`Rationale.md`](Rationale.md) — why each exists |
| Architecture | [`Architecture.drawio`](Architecture.drawio) — the whole system on one page |
| Control plane | [`ControlPlane.md`](ControlPlane.md) — data model, releases, live control, scheduling, auth (§4) |
| Admin SPA | [`../web/README.md`](../web/README.md) — Vue 3 + Vite, read and write, live books |
| Local setup | [`LocalTesting.md`](LocalTesting.md) — §9 benchmarks, §9a attributes by stage |
| Measurements | [`Measurements.md`](Measurements.md) — every figure with its machine, load and rate |
| CI | [`../.gitlab-ci.yml`](../.gitlab-ci.yml) — build, tests, e2e, native check; still needs a runner |
| Docker | [`../deploy/README.md`](../deploy/README.md) — full dev stack, one command |
| Production | [`ProdDeployment.md`](ProdDeployment.md) — three dedicated machines plus k8s for the rest |
| Operators | [`OperatorManual.pdf`](OperatorManual.pdf) — built from [`manual/`](manual/) |

```sh
./gradlew clean build                        # 457 tests (control's need Docker)
./gradlew installDist && ./e2e/run-e2e.sh    # every process, a real trade, a load run
./e2e/run-restart.sh                         # does the shard come back with its book?
./e2e/run-attribution.sh                     # where a round trip goes, by stage
SECURITIES=10 ./e2e/run-sweep.sh             # rate sweep (aggregate); prints a Measurements.md row
./e2e/run-epsilon-soak.sh                    # steady-state allocation (needs an Epsilon binary)
cd web && npm install && npm run dev         # the admin SPA on :5173
```

### What is real

All eight modules are implemented. A single shard runs end to end: orders in through a gateway,
consensus, matching, execution reports out, L1/L2/L3 derived by a separate process, a control plane
that authors reference data and drives the market over REST, and a console that shows live books.

- **A restart resumes.** Durable cluster directories, a snapshot something actually asks for, and a
  restore that refuses to destroy state when geometry changes (Handover §2d, §2e).
- **Native images build and pass e2e.** All four core processes, plus a `linux/amd64` container
  (§2a). Untested: a trade *through* native containers, which needs an x86-64 host.
- **Zero allocation is proven**, by three measurements that are each mutation-validated (§2b).
  `--gc=epsilon` is still off for want of a runner and an hours-long soak, not for want of a
  measured path — the pipeline that would run the assertion now exists.
- **Latency is attributed.** The exchange's own code is **1.4%** of a client round trip; the rest is
  consensus, the archive write and the wire. Design.md §2's 0.5 µs/order estimate holds at 0.42 µs
  (§2c).
- **Gateways are stateless and disposable.** `origQty` lives in the engine, several gateways can
  serve one shard, and the participant registry is authored by the control plane and re-read while
  the nodes run (§2g, §2h).

### The caveats that still stand

**This system has never run multi-node.** Failover, leader election and cross-node snapshot recovery
are the reasons Aeron Cluster was chosen and not one has been exercised. Every result below
describes a single node doing no replication work beyond its own log.

**The design's throughput target is over-stated by about 2.9x.** Ten securities have now been driven
together (runs R2–R6 in [`Measurements.md`](Measurements.md)) and the shard's ceiling is
**~350k orders/sec aggregate** — comfortable at 300k, marginal at 350k, gone by 365k, and the *same
aggregate* one book reached. Fan-out bought no throughput, so the 100k/s/security × 10 = 1M/s/shard
claim in Design.md §2 does not hold on the measured path; the real figure is ~35k/s/security at full
fan-out. **What caps it is the media driver's threading mode**, and it is configuration: `most cluster`
defaults to `ThreadingMode.SHARED`, one thread for the driver's conductor, sender and receiver moving
~190 MB/s of loopback UDP. `--driver-threading DEDICATED` moves the knee to **~550k/s** and cuts p50 at
350k/s from 6410 µs to **79 µs** (R7) — ~55k/s/security, so the shortfall narrows from 2.9x to **1.8x**.
Eliminated by measurement along the way: the archive write (RAM disk, R6) and the ingress term length
(16m moved latency, not the knee). **What binds at ~550k/s is open, and neither of the cheap instruments
can say**: `most counters` in genuine saturation comes back clean — every stage scales exactly with the
offered rate, every duty-cycle and error counter at zero (C1) — and per-process CPU is uninformative
because `engine`, `gateway` and `market-data` all busy-spin and read ~100% of a core either way (C2).
Matching is ~23% of the engine's core at 600k/s, so most of that thread is the service container's Aeron
work, not matching. The per-order matching cost is
not the problem, and that is now measured rather than inferred (attribution runs A1–A3): fan-out costs
**8%** of a whole new order, and when the rate rises 2.5x the engine gets *faster* per order while the
round trip grows 39.4 → 61.2 µs, **all of it outside the gateway and the engine**, whose combined share
falls to 0.9%. The ceiling is the shared path every order crosses whichever book it lands on, and the
Aeron counters named it: **the media driver's single shared thread**. Storage and ingress buffering were
eliminated on the way. What binds once the driver has its own threads is the open question.

---

## 2. Open issues

Ordered by what would block a real deployment first. Full reasoning in `Design.md` §8.

### Blocking

1. **Execution reports are dropped under load.** The gateway's outbound leg has no back-channel —
   egress cannot be left unconsumed or the cluster session stalls — so a slow subscriber loses
   reports. Measured at ~8% (`droppedToClient=267853` of 3.26M) across a sweep reaching 333k/s.
   Retrying was tried and cost 10x on the p99 while still dropping. The remedy is a larger term
   buffer or a faster subscriber, and it needs deciding deliberately. **The failure mode is
   machine-dependent**: run R1 in [`Measurements.md`](Measurements.md) hit the same 333k/s with
   *one* unanswered order and nothing dropped, collapsing into 29 ms of queueing instead. Whichever
   side of the knee a machine lands on, it is the same knee. **Runs R2–R4 land on the queueing side
   decisively**: `droppedToClient=6` of 3,264,416 reports (0.0002%) across a sweep that included a
   saturated 350k/s rate, against 8.2% of the same report volume in the run behind this item, with
   `clusterBackpressure=11` and `untrackedReports=0`. Two of three sweeps now queue rather than drop,
   so the drop is not reproducible on this machine — which sharpens the machine-dependence rather
   than removing the need to decide the remedy.
2. ~~**`feedGaps` / `eventsMissed` over-report in the control plane.**~~ **Fixed.** The L3 decoding
   moved out of `ClusterLink` into `BookEventReader`, which consumes a sequence for every one of the
   seven book events — including the three order events the control plane deliberately ignores — and
   none for a book image, whose `seqNum` is a baseline. `BookEventReaderTest` pins it with the real
   encoders against Design.md §5; five of its six cases fail against the old behaviour. It was
   extracted rather than patched in place because the defect lived on a path that needed a media
   driver to reach, which is why nothing tested it. **Not yet seen against a live control plane** —
   no e2e script runs one, so the confirmation available is the unit proof plus the mutation check.
3. **No enforcement of the participant binding.** The engine binds routes but does not refuse an
   order whose `participantId` is not bound to the sending session, so `UNAUTHORIZED_PARTICIPANT` is
   raised by nothing. Note it cannot simply read the hot-reloaded registry — see §3 item 0.
4. **Authorisation is all-or-nothing.** One `ADMIN` role with full access. Also, a command refused
   locally before the send attempt is not audited, because the audit is written on the way out of
   the REST layer.

### Design decisions still open

5. **Archive growth is unbounded** now that the directories persist. Nothing truncates the recorded
   log. Aeron 1.53's post-snapshot behaviour for the consensus log and archive segments needs
   establishing before a retention procedure or a volume size can be written down.
6. **The directory advertises one order-entry endpoint per shard**, so a shard served by several
   gateways cannot name them all. `ShardEntry` carries one channel and `DirectoryClient` keeps one
   `ShardRoute` per shard. A virtual address in front of the tier works today.
7. **No per-order L3 recovery.** The book image is deliberately level-aggregated, so an MBO consumer
   that joins or reconnects after a restart has nothing to rebuild per-order state from. Nothing
   needs it today; a FIX market data adapter carrying order-level detail would.
8. **No L1 snapshot** and no request-response recovery for a consumer that cannot wait a cycle. A
   top-of-book-only subscriber still has nothing to join to.
9. **A `SecurityDefinition` cannot be confirmed.** Nothing on any feed acknowledges one and a
   rejection only increments `rejectedDefinitions`. An ack message in the schema would close it.
10. **`SecurityDefinition` conflates boot-time geometry with runtime reconfiguration.**
11. **Fingerprints are printed, never compared.** A snapshot-versus-node mismatch is now loud; a
    node-versus-node mismatch with no snapshot between them still diverges silently on the first
    order. Adjacent: **`auctionMaxPasses` is in no fingerprint at all**, though it bounds the SMP
    fixed point and so can change an uncross result. It cannot be folded into
    `ShardSpec.fingerprint()` — that hash is stored by the control plane and published in releases —
    and wants a separate engine-level one.
12. Smaller confirmations: collars in the auction (uncollared, deliberate); a closing auction and
    whether it resets `staticReference`; order modify/replace (unsupported, cancel/new only);
    reference price on a day with no trades.

### Needs measurement

13. ~~**The aggregate, which is the number the design actually claims.**~~ **Measured, and the
    ceiling found.** R5: the ten-security knee is **~350k/s aggregate** on the default configuration —
    the same aggregate as one book, ~35k/s/security against a 100k/s target. A1–A4 showed the engine
    is not the ceiling, R6 eliminated storage, and the Aeron counters then named the cause: the media
    driver's `ThreadingMode.SHARED`. **`--driver-threading DEDICATED` moves the knee to ~550k/s**
    (R7, and Design.md §7 "Driver threading") — 1.8x short of the target rather than 2.9x. **It was
    tuning, not architecture.** What replaces this item is *what binds at ~550k/s*: the consensus
    module's own single thread is the next suspect, `most counters` against a DEDICATED run at its
    knee is the cheap look, and a gateway-stamped ingress timestamp read only under `engine.metrics`
    is the definitive one at the cost of a wire change.
14. **Auction SMP pass limit** — currently 64, still a guess.
15. **Net resting depth** — confirms the 1M order pool and the capacity high-water mark.
16. **`SecurityDefinition` distribution to market data** — it learns reference prices only
    implicitly from trades.
17. **Whether the console should keep up rather than resynchronise.** Above ~25k/s the control
    plane's depth subscriber takes gaps and rebuilds from snapshots — correct, survivable and
    measured (15 rebuilds through 1.35M orders, both books right at the end). The cost is a book up
    to a snapshot cycle behind after a burst. The knobs are the idle strategy, the fragment limit
    and the publish interval. A decision, not a defect.

---

## 3. To do next

In the order I would tackle them.

0. **Decide the shape of participant enforcement** (open issue 3). A reject is replicated state, so
   it cannot be decided from a file each node re-reads on its own schedule — the binding it would
   refuse on is node-local by design, which is exactly what makes the hot reload safe. Enforcement
   has to come through the log, which probably means a sequenced command that installs a registry
   version rather than a poller. Every existing config, e2e script and Docker stack would also have
   to name its participants. Worth deciding the shape before writing any of it.
1. **Get a GitLab runner onto the pipeline.** [`.gitlab-ci.yml`](../.gitlab-ci.yml) is written and
   validated: build, `test:core` (which is where the allocation proofs live), `test:control`,
   `test:web`, `e2e`, `e2e:restart`, a native build that greps the binary for the exports a missing
   `--add-exports` would have silently dropped, and two manual measurement jobs. What is missing is
   a runner — `test:control` needs one that can run `docker:dind` privileged, and the e2e jobs need
   ~4 GB for five JVMs. Nothing here has ever executed on GitLab; the first pipeline should be
   treated as the test of the pipeline.
2. **Advertise several gateways per shard** — promoted from item 5, because C1–C2 turned it from
   tidying into the cheapest throughput lever the system has. The gateway is one thread carrying every
   order inbound and every report outbound (~1.3M messages/sec at the ceiling), it is the only
   saturating stage that scales sideways **today** (stateless, `origQty` in the engine,
   `run-restart.sh` §4c already covers multi-gateway), and the sole blocker is that `ShardEntry` carries
   one channel and `DirectoryClient` one `ShardRoute` per shard. A wire change — use the `wire-change`
   skill. Measure with a sweep either side; a virtual address in front of the tier works meanwhile.
3. **Find what binds at ~550k/s.** Eliminated: the engine (A1–A4), storage (R6 and the
   archive-threading cell of R7), the ingress term length (16m moved latency, not the knee), the
   driver's threading mode (R7 — that *was* the ~350k ceiling), and now **everything Aeron reports**
   (C1: clean sheet in genuine saturation). **The two cheap instruments are used up**, so:
   1. **Read the loops' own metrics, not CPU%.** `engine.metrics` / `engine.metrics.stages` and
      `gateway.metrics` already report per-stage duty. `ps` cannot help — all three busy-spin (C2).
   2. **Run one measurement with a yielding idle strategy** in the engine, gateway and market-data, so
      CPU% becomes meaningful for that run only. Cheap, and it does not ship.
   3. **A gateway-stamped ingress timestamp**, read by the engine only under `engine.metrics`, which
      measures gateway-offer-to-engine-entry directly. `NewOrderSingle` carries no timestamp, so this
      is a wire change — `wire-change` skill, and keep the field out of `EngineConfig.fingerprint()`
      the way the rest of metrics is.

   Also worth doing cheaply: **re-run the headline sweeps with `DRIVER_THREADING=DEDICATED`**, since
   every row in `Measurements.md` was taken 1.6x below what the shard can do.

   Whatever it says, Design.md §2's 100k/s/security target then either gets restated or gets earned.
4. **Update [`OperatorManual.pdf`](OperatorManual.pdf) (built from [`manual/`](manual/)) for the
   threading configuration and the measured ceilings.** Outstanding and explicitly asked for. The
   manual predates all of R2–R7 and tells an operator nothing about the one setting that costs 1.6x of
   throughput. It needs: a **dev versus perf/prod configuration** split — dev leaves
   `ThreadingMode.SHARED` (the `most cluster` default, right for a laptop running five JVMs),
   perf/prod sets `--driver-threading DEDICATED` and leaves `--archive-threading` alone because a
   dedicated archive thread behind a shared driver thread is measurably *worse* than both shared; the
   **measured capacity** an operator should expect (~350k/s aggregate over ten securities on the
   default, ~550k/s with `DEDICATED`, against Design.md §2's 100k/s/security target — so plan capacity
   on the measured figure, not the design one); **`most counters`** as the first diagnostic when a
   shard is slow, with the warning that a non-linear counter names a place to look and not a cause;
   and the `SATURATED` marker in `run-sweep.sh`, so nobody quotes a draining queue as a latency.
   Cross-reference Design.md §7 "Driver threading" rather than restating it.
5. **Decide the archive retention policy** (open issue 5). Cheap to establish, expensive to
   discover — and still a housekeeping question rather than a performance one, since R6 showed the
   archive write is not the ceiling.
   (Advertising several gateways moved up to item 2.)
6. **Run the Docker stack on native containers, on an x86-64 host.** Both builds are done; a
   `x86-64-v3` binary cannot start under Apple Silicon's amd64 emulation. On a real host this is
   `CORE_TARGET=native docker compose up` and a repeat of the §9 benchmark.
7. **Run a long soak, then enable Epsilon.** Shaped for hours rather than seconds, to bound the
   Aeron client conductor's per-duty-cycle allocation — it shares this heap and would be invisible
   in a 20-second run. With that and a runner on the pipeline, `engine.useEpsilonGc=true` is a
   one-line change backed by measurement.
8. **Bring up a three-node cluster.** Expect the fixed single-node member string in
   `ClusterCommand.kt` to need generalising. What is untested is specifically the multi-node part:
   whether a snapshot taken through consensus on one member restores on another, and whether a
   rejoining node catches up from the archive rather than from genesis.
9. **Fingerprint enforcement at boot** (open issue 11) — cheap, and closes a silent-divergence path.
10. **Reconcile `Design.md` §6 with the code**, or cut it. Outstanding for several sessions.
11. **Tests for the `discovery` process** itself. Also outstanding.
12. Roadmap remainder: TimescaleDB ticks; a read-only role now that there is a role column to put it
    in; serving the built SPA from the control jar rather than a dev proxy.

---

## 4. Picking this up

* [`Architecture.drawio`](Architecture.drawio) is the fastest way to see the shape of the system.
  Open it at [app.diagrams.net](https://app.diagrams.net) or in the draw.io desktop app / VS Code
  extension. Edges bind to shapes by id, so boxes can be dragged without detaching anything. There is
  deliberately **no committed PNG or SVG**: draw.io inlines fonts and both come out at ~2.6 MB.
  Regenerate one when needed:
  `"/Applications/draw.io.app/Contents/MacOS/draw.io" -x -f png -s 1 -o /tmp/arch.png docs/Architecture.drawio`
* [`Design.md`](Design.md) is authoritative — read it before changing behaviour. §8 is the open list.
* [`../CLAUDE.md`](../CLAUDE.md) holds the rules; [`Rationale.md`](Rationale.md) holds why each one
  exists and what it cost to learn.
* [`ControlPlane.md`](ControlPlane.md) covers the database, releases, live cluster control and
  scheduling, and explains why the database is not on the boot path.
* [`LocalTesting.md`](LocalTesting.md) gets the system running on one machine.
* `e2e/run-e2e.sh` is the fastest confidence check after a wire-format change.
* `e2e/run-attribution.sh` says where a round trip went, and takes the same `SECURITIES` knob as the
  sweep (`--delay-us` sets the **aggregate** rate). Both scripts also take `CLUSTER_HOST`, which moves
  the consensus log and archive without moving the Aeron buffers — that is how R6 tested storage. The
  `.hgrm` files exist to be diffed; A1–A4's are kept under `build/attr-*`, so set `ATTRIBUTION_DIR`
  rather than letting a new run wipe them.
* `most counters [--match REGEX] [--interval-ms N]` reads the driver's, the archive's and every cluster
  component's Aeron counters out of the CnC file **with no Aeron client**, so it is safe against a
  shard under load. `--interval-ms` reports the rate of change, which is what finds a saturating stage.
  It identified the driver thread — and then came back clean at the next knee, which is its limit: a
  stage that is merely *full* breaches no counter. Sample it above the knee with a long enough run that
  the window is steady state and not a draining queue.
* `run-sweep.sh` also takes `INGRESS_TERM`, `DRIVER_THREADING` and `ARCHIVE_THREADING`. **Set
  `DRIVER_THREADING=DEDICATED` for any benchmark**; leave `ARCHIVE_THREADING` alone (Design.md §7).
* `e2e/run-sweep.sh` finds the knee, validates every rate before believing it, and prints a row
  block to paste into [`Measurements.md`](Measurements.md). **`SECURITIES=n` drives n securities
  (max 10) and `RATES` stays the aggregate across them**, so `SECURITIES=10 RATES=1000000` is the
  design's full fan-out. A rate the shard could not keep up with is marked `SATURATED`: its latency
  is a draining queue, not a round trip, and the `achieved` figure beside it is an offer rate.
* `e2e/run-epsilon-soak.sh` measures steady-state allocation against a real cluster.

**Writing a market data consumer** — a FIX adapter, a recorder, a screen? Everything needed is in
`reference` and is already used by two independent consumers: `DepthFeedAssembler` (the snapshot and
increment splice, and the recovery state machine), `DepthFeedDecoder` (which template ids matter and
which fields carry the splice) and `AggregatedBook` (the price-keyed book itself). Subscribe to
**all three** feeds — L2 for increments, the snapshot stream for the images that make them
applicable, L1 for `LastTrade` — and call `book(securityId)`, which returns null until the book can
be trusted. `tools/BookCommand.kt` and `control/DepthMonitor.kt` are the two worked examples, and
they produce identical books level for level, which is the point of sharing the code.

### Things that will waste time if unknown

1. Any JVM running this needs `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` and
   `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` (Agrona 2.x, Aeron driver). Already set on the
   `test` and `run` tasks, and on `control` since it embeds an Aeron client.
2. `ClientSession.tryClaim` reserves the cluster session header — encode at
   `claim.offset() + AeronCluster.SESSION_HEADER_LENGTH`. **A plain `Publication` does not**, which
   is why `most load` encodes at `claim.offset()`; applying the cluster rule there would shift every
   message by 32 bytes.
3. Byte offsets are never hand-written. Edit `sbe/src/main/resources/message-schema.xml`; the codecs
   regenerate. Keep it in step with `Design.md` §5.
4. **Anything printed at shutdown must be inside the `ShutdownSignalBarrier` block.** Closing the
   barrier releases the signal and the process exits at once. In a native image this additionally
   needs `--install-exit-handlers`, without which SIGTERM kills the process outright.
5. **The engine may read `nanoTime` only for metrics, and the histograms must stay write-only.**
   `MetricsDeterminismTest` checks it. If a probe you add would fail that test, it is not
   instrumentation.
6. **Do not widen a tolerance in `AllocationTest` to make it pass.** The fix is more rounds. The
   three `inline` keywords those tests guard — `matchAggressive`, `offerToSnapshot`,
   `publishBookEvent` — can each be removed without a compiler warning.
7. `control`'s tests need Docker running (Testcontainers Postgres). `./gradlew build` fails without
   it, which is easy to mistake for a code problem.
8. `deploy/`'s `client-aeron` volume is a **1 GB** tmpfs and needs to be. Every Aeron subscription
   image is a log buffer of three terms, and the control plane alone holds five subscriptions. At
   256 MB a 100k/s load run exhausted it and the media driver died with `InternalError: a fault
   occurred in an unsafe memory access operation` — SIGBUS on a mapped file, which reads like a JVM
   bug and is a full mount. Check `df -h /aeron` inside the driver's container before reading the
   stack trace.
