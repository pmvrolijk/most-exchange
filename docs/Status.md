# Status

Where the project stands, what is open, and what to do next. **This is the session entry point** —
read this, not the handover. [`Handover.md`](Handover.md) is the archive: work records (§2a–§2j),
load-bearing decisions (§3) and lessons learned (§6).

Last updated 2026-09-27, in the session that tested whether a second gateway raises the shard's
ceiling. It does not: the gateway is ~19% busy at the knee (Measurements.md R8–R11, A5). Every
thread on the order path now reports a duty cycle, and on this laptop the knee turns out to be the
core count, not a stage (D1–D2; Handover §2k). The participant-enforcement work (Handover §2j) is merged to
`master` and green in CI (pipelines 35–37). The project is now AGPL-3.0-or-later (`LICENSE.md`).

---

## 1. Where things stand

| | |
| --- | --- |
| Branch | `master`, pushed. CI green through pipeline 37; **pipeline 38 (`029e37a`, this session's two commits) was still running at close** — check it first |
| Modules | 8 — `sbe`, `reference`, `discovery`, `engine`, `market-data`, `gateway`, `tools`, `control` |
| Kotlin | ~26,300 lines — 14,660 main across 69 files, 11,670 test across 58 |
| Frontend | ~3,500 lines of TypeScript and Vue across 25 files, outside the Gradle build |
| Tests | 566, all passing |
| Specification | [`Design.md`](Design.md) — authoritative. §8 is the open list |
| Rules | [`../CLAUDE.md`](../CLAUDE.md) — the traps. [`Rationale.md`](Rationale.md) — why each exists |
| Architecture | [`Architecture.drawio`](Architecture.drawio) — the whole system on one page |
| Control plane | [`ControlPlane.md`](ControlPlane.md) — data model, releases, live control, scheduling, auth (§4) |
| Admin SPA | [`../web/README.md`](../web/README.md) — Vue 3 + Vite, read and write, live books |
| Local setup | [`LocalTesting.md`](LocalTesting.md) — §9 benchmarks, §9a attributes by stage |
| Measurements | [`Measurements.md`](Measurements.md) — every figure with its machine, load and rate |
| Baselines | [`baselines/`](baselines/) — the `.hgrm` histograms to diff a core change against |
| CI | [`../.gitlab-ci.yml`](../.gitlab-ci.yml) — build, tests, e2e, native check; green on a self-hosted runner since pipeline 34 |
| Docker | [`../deploy/README.md`](../deploy/README.md) — full dev stack, one command |
| Production | [`ProdDeployment.md`](ProdDeployment.md) — three dedicated machines plus k8s for the rest |
| Operators | [`OperatorManual.pdf`](OperatorManual.pdf) — built from [`manual/`](manual/); §4.3 is the registry and what the gateway enforces, §4.8 and §5.7 the threading and capacity. Screenshots and transcripts regenerated from the dev stack this session |

```sh
./gradlew clean build                        # 542 tests (control's need Docker)
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
  restore that refuses to destroy state when geometry changes (Handover §2d, §2e). **Until this
  session only the local toggle took one** — every snapshot requested through consensus (`--ingress`,
  the control plane, the scheduler's session-close) was refused by Aeron's default authorisation and
  reported as success. Fixed and proven by the recording log, in `run-restart.sh` and in the Docker
  stack, where a full shard restart printed `restored 1 resting orders ... from a snapshot` (§2j).
- **Native images build and pass e2e.** All four core processes, plus a `linux/amd64` container
  (§2a). Untested: a trade *through* native containers, which needs an x86-64 host.
- **Zero allocation is proven**, by three measurements that are each mutation-validated (§2b).
  `--gc=epsilon` is still off for want of an hours-long soak, not for want of a
  measured path — the pipeline that runs the assertion is live since pipeline 34.
- **Latency is attributed, and the ceiling is found.** The exchange's own code is **0.9–2.0%** of a
  client round trip; the rest is consensus, the archive write and the wire, and the shard's throughput
  ceiling is the media driver's threading mode — `--driver-threading DEDICATED` is worth 1.6x (§2i).
  Design.md §2's 0.5 µs/order estimate holds at 0.38–0.50 µs (§2c, A1–A4).
- **Gateways are stateless and disposable.** `origQty` lives in the engine, several gateways can
  serve one shard, and the participant registry is authored by the control plane and re-read while
  the nodes run (§2g, §2h).
- **Who may do what is enforced** (Design.md §1, "Enforcement, at the gateway"; §2j). The gateway
  refuses `UNAUTHORIZED_PARTICIPANT` for orders and cancels, lets a `cancelOnly` participant withdraw
  but not place, and forwards operator commands only if it is an `operator`; it re-reads the
  registry while running. A node with a registry refuses anonymous sessions and grants snapshot
  requests only to operators; the control plane and the CLI hold operator-only identities. A
  participant may be on several gateways with a declared primary. The engine never rejects on the
  registry — it counts `undeclaredParticipantMessages`. No wire change.

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
eliminated on the way. What binds once the driver has its own threads was answered for the laptop by
the duty-cycle counters: **its core count** — no stage is full at ~550k/s, the shard simply has more
busy threads than performance cores (D1–D2). A host with a core per thread is unmeasured.

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
3. ~~**No enforcement of the participant binding.**~~ **Done, at the gateway** (§2j). The standing
   assumption was that a reject had to come through the log; a reject that never *enters* the log
   has no determinism to protect. **Still open underneath it:** the client-to-gateway leg is not
   authenticated, so the check means "whoever can reach this endpoint may act for these
   participants" — per-client authentication is the upstream session gateways' job, and nothing here
   tests one. There is **no bulk cancel** for a revoked participant, so `cancelOnly` relies on it
   withdrawing its own orders. And the control plane cannot *verify* that the gateway it sends
   operator commands through is an operator — `control.cluster.operatorChannel.<shard>` names one,
   but a wrong one is still a silent `refusedCommands` count.
4. **Authorisation is all-or-nothing.** One `ADMIN` role with full access. Also, a command refused
   locally before the send attempt is not audited, because the audit is written on the way out of
   the REST layer.

### Design decisions still open

5. **Archive growth is unbounded** now that the directories persist. Nothing truncates the recorded
   log. Aeron 1.53's post-snapshot behaviour for the consensus log and archive segments needs
   establishing before a retention procedure or a volume size can be written down.
6. **The directory advertises one order-entry endpoint per shard**, so a shard served by several
   gateways cannot name them all. `ShardEntry` carries one channel and `DirectoryClient` keeps one
   `ShardRoute` per shard. **Decided this session: participants learn their gateway out of band**,
   as member connectivity usually works, and the CLI takes `--order-entry-channel` to reach one the
   directory does not name. The throughput case is **closed**: a second gateway raises nothing (R8–R11) and the one gateway is
   ~19% busy at the knee (A5). What remains is convenience, not capacity.
6a. **The release publisher writes into an existing directory.** `Files.createDirectories` does not
   fail on one, while its own KDoc promises a directory a running process points at never changes.
   Latent in production (release numbers repeat only after a database restore); it bit the control
   tests, whose fixture now clears the release directory. Refusing to publish into an existing
   directory would make the promise true.
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
    tuning, not architecture.** *What binds at ~550k/s* was then answered for this laptop by the
    duty-cycle counters (D1–D2): no stage is full — the consensus module stays under 20% — and the
    knee is where the shard's busy threads outnumber the 10 performance cores. The answer on a host
    with a core per spinning thread is **paused until a dedicated 16-core machine exists** (§3 item 3).
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

**Next session: the other open items**, the throughput work being paused (item 3). In the order I
would take them, cheapest-and-most-dangerous first:

1. **Fingerprint enforcement at boot** (item 9, open issue 11) — a node-versus-node geometry mismatch
   still diverges silently on the first order, and `auctionMaxPasses` is in no fingerprint. Cheap, and
   it closes the one silent-divergence path left. Use `decision-fork`: it changes a boot path.
2. **Refuse to publish into an existing release directory** (item 11b, open issue 6a) — small, and
   makes a KDoc promise true.
3. **Bulk cancel of one participant's resting orders** (item 11a, open issue 3) — the operator side of
   revocation. A new operator command through the log, so the `wire-change` skill applies; the
   engine walks the ladders as the purge does (Design.md §4.3).
4. **Archive retention** (item 5, open issue 5) — establish Aeron 1.53's post-snapshot behaviour
   before writing a policy.
5. **Tests for `discovery`** (item 11) and **Design.md §6 against the code** (item 10) — both
   outstanding for several sessions.

Also cheap and outstanding: regenerate the manual's §5.8 `/api/status` transcript and the screenshots
from a running dev stack (item 4). Before the repository goes public: strip or annotate the embedded
icon font in the exported presentation decks, and plan NOTICE files for distributed binaries (§2k).

The full list, in the order it was written:

0. ~~**Merge `gateway-security-multiple-per-shard`.**~~ **Done**, and green in CI on `master`
   (pipelines 35–37, `e2e:restart` included). Re-seed any long-lived dev stack
   (`docker compose down -v && up`) if it predates the merge: V6 changes the gateway tables.
1. ~~**Get a GitLab runner onto the pipeline.**~~ **Done.** A self-hosted Docker-executor runner
   (`clytemnestra`, privileged for `docker:dind`) runs [`.gitlab-ci.yml`](../.gitlab-ci.yml), and
   pipeline 34 on `d198ad6` is green end to end: build, `test:core` — so the allocation proofs now
   hold on a machine that is not this laptop, and a noisy one — `test:control`, `test:web`, `e2e`,
   `e2e:restart` and `native:engine` (3 `jdk.internal.misc.Unsafe` references in the image).
   The first pipeline was the test of the pipeline, as predicted, and failed four jobs for four
   reasons that had nothing to do with the exchange: Docker caps `/dev/shm` at 64 MB, below one
   16 MB-term IPC log, so the three driver-backed test classes now keep `aeron.dir` under `build/`;
   `run-restart.sh` grepped once for restore lines that the engine prints *after* `awaiting shutdown
   signal` (the snapshot loads on the service thread once the container has launched), and now
   waits for them; the GraalVM image lacks `xargs`; and dind needs a privileged runner.
   `measure:attribution` passes. **`measure:sweep` fails, correctly**: every rate was marked
   `INVALID` on pacing lateness (p99.9 11–53 ms, load average 3.1) — the generator stalled, the
   script refused to print rows, and that is the validation working on a machine that cannot be
   quoted from. Both stay manual and `allow_failure`.
2. ~~**Advertise several gateways per shard, as a throughput lever.**~~ **Measured first, and
   refuted — so not built.** `run-sweep.sh` gained `GATEWAYS` and `LOADERS`, and four same-day arms
   (R8–R11, ten securities, `DEDICATED`) put two gateways at a knee no higher than one and 100x slower
   below it. The control arm confounds on report fan-out and core count, so A5 settled it from the
   gateway's own histograms: at 500k/s it spends **~19% of a core** on its 1.3M messages/s (under a
   third with a generous allowance for the untimed poll loop). The directory change stays unbuilt;
   open issue 6 is a convenience question now, and nothing about capacity depends on it.
3. **Find what binds at ~550k/s — answered for this machine: the core count.** Every thread on the
   order path now reports its duty cycle (Design.md §7, "Duty cycle"; `most cluster --duty`, metrics
   on). Measured across 250k–700k/s (Measurements.md D1–D2): the consensus module never exceeds 20%,
   the gateway ~34%, market-data ~13%, the archive ~30%. **The engine's thread is the one that falls
   over**, 20% → 100% in one step at ~550k/s, with its median cost unchanged and its p90 ~10x — and it
   is not egress back-pressure (every Aeron counter sampled; flow-control events *fall*). Freeing the
   two cores the gateway and market-data spin away (`backoff`) moved the knee one step, to ~550–600k/s,
   without touching the engine. **On the 10P+4E laptop the knee is where busy threads outnumber
   performance cores.** Still open, and not answerable on this laptop: where a host with a core for
   every spinning thread knees, and whether the driver's sender — the one loop that never idles — is
   then the limit. That wants a many-core Linux box with pinning, or the driver on its own machine.
   Design.md §2's 100k/s/security target is neither earned nor refuted until then.
   **Paused (decided 2026-09-27) until a dedicated 16-core machine is available.** The instruments are
   ready for it: `run-attribution.sh` with `RATE`, `COUNTERS_MATCH` and the `*_IDLE` knobs, and the
   duty counters. Do not resume it on the laptop.
4. ~~**Update the Operator's Manual for the threading configuration and the measured ceilings.**~~
   *Updated again 2026-09-27* with idle strategies (§3.4, §4.4–4.8) and the duty cycle (§5.8).
   **Done**, and [`OperatorManual.pdf`](OperatorManual.pdf) rebuilt from [`manual/`](manual/). New
   §4.8 "Driver threading is the throughput ceiling" carries the dev-versus-perf/prod split and the
   measured table; §3.4 gives production the launch line and keeps an honest `todo` for the idle
   strategies, which are still constants; §5.7 "What a shard actually carries" states the capacity to
   plan against and warns that a `SATURATED` row's latency is a draining queue; §5.8 "Reading Aeron's
   own counters" documents `most counters` with both traps (a clean sheet is not innocence, and a
   non-linear counter names a place to look); §6.1 gains it as a first move, §6.5 gains two rows for
   the saturated case, and §6.9's capacity entry replaces the resolved threading one.
   **Still outstanding there:** the `/api/status` transcript in §5.8 was captured on the pre-fix build
   and shows `feedGaps` tracking `eventsSeen` — it is marked as such rather than hand-edited, because
   the manual's own convention is that transcripts are regenerated from a running stack. Regenerate it
   (and the screenshots, which are equally old) next time the dev stack is up.
5. **Decide the archive retention policy** (open issue 5). Cheap to establish, expensive to
   discover — and still a housekeeping question rather than a performance one, since R6 showed the
   archive write is not the ceiling.
   (Advertising several gateways moved up to item 2.)
6. **Run the Docker stack on native containers, on an x86-64 host.** Both builds are done; a
   `x86-64-v3` binary cannot start under Apple Silicon's amd64 emulation. On a real host this is
   `CORE_TARGET=native docker compose up` and a repeat of the §9 benchmark.
7. **Run a long soak, then enable Epsilon.** Shaped for hours rather than seconds, to bound the
   Aeron client conductor's per-duty-cycle allocation — it shares this heap and would be invisible
   in a 20-second run. The runner is in place, so with the soak `engine.useEpsilonGc=true` is a
   one-line change backed by measurement.
8. **Bring up a three-node cluster.** Expect the fixed single-node member string in
   `ClusterCommand.kt` to need generalising. What is untested is specifically the multi-node part:
   whether a snapshot taken through consensus on one member restores on another, and whether a
   rejoining node catches up from the archive rather than from genesis.
9. **Fingerprint enforcement at boot** (open issue 11) — cheap, and closes a silent-divergence path.
10. **Reconcile `Design.md` §6 with the code**, or cut it. Outstanding for several sessions.
11. **Tests for the `discovery` process** itself. Also outstanding.
11a. **Bulk cancel of one participant's resting orders** — the operator side of revocation, which
    `cancelOnly` currently leaves to the participant (open issue 3). An operator command, so it goes
    through the log; the engine would walk the ladders as the purge does (Design.md §4.3).
11b. **Refuse to publish into an existing release directory** (open issue 6a).
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
  `.hgrm` files exist to be diffed, and A1–A4's are kept in [`baselines/`](baselines/) — outside
  `build/`, so a `./gradlew clean` cannot take them. Set `ATTRIBUTION_DIR` on a new run and copy its
  histograms in beside them.
* `most counters [--match REGEX] [--interval-ms N]` reads the driver's, the archive's and every cluster
  component's Aeron counters out of the CnC file **with no Aeron client**, so it is safe against a
  shard under load. `--interval-ms` reports the rate of change, which is what finds a saturating stage.
  It identified the driver thread — and then came back clean at the next knee, which is its limit: a
  stage that is merely *full* breaches no counter. Sample it above the knee with a long enough run that
  the window is steady state and not a draining queue.
* `run-sweep.sh` also takes `INGRESS_TERM`, `DRIVER_THREADING`, `ARCHIVE_THREADING`, and `GATEWAYS` /
  `LOADERS` (several gateways, several generators; read the script header for the control arm's
  confound). `run-attribution.sh` takes `DRIVER_THREADING` too. **Set
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
