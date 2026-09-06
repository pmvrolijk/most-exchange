# Status

Where the project stands, what is open, and what to do next. **This is the session entry point** —
read this, not the handover. [`Handover.md`](Handover.md) is the archive: work records (§2a–§2h),
load-bearing decisions (§3) and lessons learned (§6).

Last updated at the end of the session that moved `origQty` into the engine and deleted the
gateway's order journal (Handover §2h).

---

## 1. Where things stand

| | |
| --- | --- |
| Branch | **`snaphot-recovery-md-and-gateway`**, ahead of `master` |
| Modules | 8 — `sbe`, `reference`, `discovery`, `engine`, `market-data`, `gateway`, `tools`, `control` |
| Kotlin | ~22,700 lines — 13,300 main across 64 files, 9,500 test across 49 |
| Frontend | ~3,100 lines of TypeScript and Vue across 24 files, outside the Gradle build |
| Tests | 457, all passing |
| Specification | [`Design.md`](Design.md) — authoritative. §8 is the open list |
| Rules | [`../CLAUDE.md`](../CLAUDE.md) — the traps. [`Rationale.md`](Rationale.md) — why each exists |
| Architecture | [`Architecture.drawio`](Architecture.drawio) — the whole system on one page |
| Control plane | [`ControlPlane.md`](ControlPlane.md) — data model, releases, live control, scheduling, auth (§4) |
| Admin SPA | [`../web/README.md`](../web/README.md) — Vue 3 + Vite, read and write, live books |
| Local setup | [`LocalTesting.md`](LocalTesting.md) — §9 benchmarks, §9a attributes by stage |
| Measurements | [`Measurements.md`](Measurements.md) — every figure with its machine, load and rate |
| Docker | [`../deploy/README.md`](../deploy/README.md) — full dev stack, one command |
| Production | [`ProdDeployment.md`](ProdDeployment.md) — three dedicated machines plus k8s for the rest |
| Operators | [`OperatorManual.pdf`](OperatorManual.pdf) — built from [`manual/`](manual/) |

```sh
./gradlew clean build                        # 457 tests (control's need Docker)
./gradlew installDist && ./e2e/run-e2e.sh    # every process, a real trade, a load run
./e2e/run-restart.sh                         # does the shard come back with its book?
./e2e/run-attribution.sh                     # where a round trip goes, by stage
./e2e/run-sweep.sh                           # rate sweep; prints a Measurements.md row block
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
  `--gc=epsilon` is still off for want of CI and an hours-long soak, not for want of a measured path.
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

**Every run has driven one security**, where the design claims ten per shard. §2c shows the engine
owning 1.4% of a round trip for one book; nobody knows what that becomes at ten.

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
   side of the knee a machine lands on, it is the same knee.
2. **`feedGaps` / `eventsMissed` over-report in the control plane, so real loss cannot be seen.**
   `ClusterLink.onBookEvent` calls `accept()` only in the four branches it decodes, but `seqNum`
   counts *every* book event, so each ignored order event reads as a gap. On a busy book these cry
   loss continuously, which is worse than not counting. Fix: call `accept()` for every book event
   before the `when`. Small.
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

13. **The aggregate, which is the number the design actually claims.** One book sustains 200k/s on
    a host at 72 µs p50 and in Docker (run R1, [`Measurements.md`](Measurements.md)); the knee sits
    between 200k and 333k. The target is 100k/s/security across **ten** — 1M/s per shard. Driving
    ten securities at once is cheap and has never been done.
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
1. **Set up CI** — build, test, and `e2e/run-e2e.sh` if a runner can host it. The allocation
   assertion already exists and runs in the ordinary build; what is missing is somewhere to run it.
   This is the stated blocker on three separate items. Note `control`'s tests need Docker.
2. **Drive ten securities at once** (open issue 13). An afternoon's work, and the first measurement
   that tests the number the design actually claims rather than a tenth of it. Then re-run
   `e2e/run-attribution.sh` against it: the same subtraction says whether the shard's ceiling is the
   engine or the archive.
3. **Decide the archive retention policy** (open issue 5). Cheap to establish, expensive to
   discover.
4. **Advertise several gateways per shard** (open issue 6). The gateways are done; the directory
   cannot name them. Small, and it makes the topology in `ProdDeployment.md` §2 self-describing.
5. **Run the Docker stack on native containers, on an x86-64 host.** Both builds are done; a
   `x86-64-v3` binary cannot start under Apple Silicon's amd64 emulation. On a real host this is
   `CORE_TARGET=native docker compose up` and a repeat of the §9 benchmark.
6. **Run a long soak, then enable Epsilon.** Shaped for hours rather than seconds, to bound the
   Aeron client conductor's per-duty-cycle allocation — it shares this heap and would be invisible
   in a 20-second run. With that and CI, `engine.useEpsilonGc=true` is a one-line change backed by
   measurement.
7. **Bring up a three-node cluster.** Expect the fixed single-node member string in
   `ClusterCommand.kt` to need generalising. What is untested is specifically the multi-node part:
   whether a snapshot taken through consensus on one member restores on another, and whether a
   rejoining node catches up from the archive rather than from genesis.
8. **Fingerprint enforcement at boot** (open issue 11) — cheap, and closes a silent-divergence path.
9. **Reconcile `Design.md` §6 with the code**, or cut it. Outstanding for several sessions.
10. **Tests for the `discovery` process** itself. Also outstanding.
11. Roadmap remainder: TimescaleDB ticks; a read-only role now that there is a role column to put it
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
* `e2e/run-attribution.sh` says where a round trip went. The `.hgrm` files exist to be diffed.
* `e2e/run-sweep.sh` finds the knee, validates every rate before believing it, and prints a row
  block to paste into [`Measurements.md`](Measurements.md).
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
