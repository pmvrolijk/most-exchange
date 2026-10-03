# Rationale

Why the rules in [`../CLAUDE.md`](../CLAUDE.md) exist, and what each one cost to learn. CLAUDE.md
states the rules and is read at the start of every session; this file holds the reasoning and is
read on demand, when a rule needs to be understood, questioned or changed.

**Do not change a rule without reading its section here.** Most of them are the residue of a defect
that reached working code, and the reasoning is usually the part that is not obvious from the rule.

For the *history* of a change — what was decided in which session and what the check was — see
[`Handover.md`](Handover.md) §2a–§2h. For normative behaviour, see [`Design.md`](Design.md).

---

## 1. Determinism, and the ban on wall-clock time

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

---

## 2. Zero allocation, and the four load-bearing `inline` keywords

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
  - A fourth `inline` is now load-bearing the same way: `OrderBook.forEachOccupiedLevel`, which the
    book image walks. Removing it boxes the callback's captured state once per level, and
    `AeronAllocationTest` catches it.
  - All three are mutation-validated. Removing `inline` from `matchAggressive`, `offerToSnapshot`
    or `publishBookEvent` compiles in silence — "warnings are errors" says nothing about it — and
    each is caught by the test covering its path and no other. If you touch one of those keywords,
    these tests are the only thing standing between you and Epsilon killing every node at once.
  - `engine.useEpsilonGc` is still off, but no longer for want of a measured path: what is missing
    is a CI to run the assertion in, and a soak measured in hours rather than seconds (the Aeron
    client conductor shares this heap, and a per-duty-cycle allocation would be invisible in 20s).

### Why the data structures are shaped the way they are

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
than `origQty - thisFill`. `origQty` is stored too, but in the parallel `ColdField` array rather
than in the line: the eight words are full and it is not read by matching.

The inline matching and purge functions read the pool and ladders directly, so those members are
`@PublishedApi internal`, not `private` — a public `inline fun` cannot touch private members.

---

## 3. The wire

- **Never hand-write SBE byte offsets.** Edit `sbe/src/main/resources/message-schema.xml`; codecs
  regenerate into `sbe/build/generated/sbe/`. Keep the schema and Design.md §5 in step — the schema
  file was extracted from the doc and the two are meant to stay identical.

**`ClientSession.tryClaim` reserves `AeronCluster.SESSION_HEADER_LENGTH` ahead of the payload.**
Encode at `claim.offset() + SESSION_HEADER_LENGTH`, never at `claim.offset()`. The test fake models
this; keep it that way or the tests validate a layout the cluster rejects.

### Why `cumQty` is stated by the engine and never derived

**`cumQty` is stated by the engine, never subtracted by anyone.** A terminal report carries
`leavesQty = 0` whether the order filled or was cancelled, so `origQty - leavesQty` reports a
cancelled order as fully filled — that is the second row of Handover §6's defect table, and putting
only `origQty` on the wire would have re-opened it one layer along. **`origQty = 0` means unknown**,
which happens for an order restored from a version 2 snapshot, and travels to the client as
`Enrichment.UNKNOWN` rather than as a number nobody can justify. `ExecutionReport` is `blockLength`
80 at schema version 3; `ClientExecutionReport` is unchanged, because it already carried both.

---

## 4. Cluster lifecycle: directories, snapshots, mark files, shutdown

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

**A snapshot through consensus is an admin request, and Aeron's default refuses it.** Aeron 1.53's
consensus module installs `AllowBackupAndStandbyAuthorisationService` unless told otherwise, which
grants backup and standby traffic and nothing else — so every `most cluster snapshot --ingress`,
every control-plane snapshot and the scheduler's session-close snapshot was refused from the day
they were written. Nobody noticed because both requesters checked only that the request was
*offered*: `ClusterAdmin` returned `confirmed: true` beside a comment saying the cluster answers,
and the CLI printed `snapshot requested`. It surfaced when a Docker restart replayed from genesis
after two "confirmed" snapshots, and the recording log held no SNAPSHOT entry at all. The fix is in
two halves and both are load-bearing: `RegistryAuthorisationService` grants a snapshot request to
an `operator=true` registry identity (and a node without a registry allows every admin request), and
`requestSnapshot` waits for the answer on egress so `confirmed` means OK. The e2e check counts
SNAPSHOT entries in the recording log rather than trusting any message, because a message is
exactly what lied. The local toggle — `--dir`, `cluster shutdown` — was never affected: it opens no
session.

**A cluster client must send keepalives.** The consensus module closes a session after
`sessionTimeoutNs` (10s default) of silence and every later offer fails silently; polling egress is
not enough. **`ShutdownSignalBarrier` must be closed** — `await()` alone leaves the JVM alive — and
anything printed at shutdown must be inside the barrier block, since closing it releases the signal
and the process exits at once.

---

## 5. Recovery, restore, and the ways it can fail quietly

~~**Restarting only the service container is not a recovery.** The consensus module keeps running and
replays the log to the new service from the beginning, rebuilding the same books by a completely
different route and taking as long as the session is old.~~ **Superseded 2026-10-03 (Aeron 1.53) by
the next paragraph.** The consensus module does not keep running. What survives of this is the
assertion it produced: `e2e/run-restart.sh` was written asserting on the rendered book and passed for
a wrong reason, a replay rather than a restore. The engine now prints `restored N resting orders ...
from a snapshot` so the two are distinguishable, and the test asserts on that line rather than on
depth. That still matters, because a whole node restarted with no snapshot replays from genesis.

**Stopping the service container stops the cluster.** The consensus module watches each service's
Aeron client. When that client closes, as it does on an orderly SIGTERM of the engine, the
module's `onUnavailableCounter` logs "Aeron client in service closed unexpectedly: serviceId=0" and
moves to `CLOSED`. Its next slow tick then terminates the cluster through `unexpectedTermination`,
which closes every client session, gateways included. A service container started afterwards is told
"expected termination" and exits. It is not a timeout, so a quick restart does not escape it.
Found on the dev stack with `docker compose stop engine`, taken only to read a shutdown counter. The
consensus module's trace was `slowTickWork` → `unexpectedTermination("State.CLOSED == state")`, which
matches the source. The recovery was to restart the node's processes together: the engine restored
the last snapshot, replayed the log and resumed with the next order id. **Not established:** how the
superseded observation above arose. It may have been an older Aeron, or a run that restarted the
cluster host too. And whether a SIGKILLed service, whose client is only timed out by the driver
(10 s by default), ends the same way. The source says it should, but nobody has tried it.

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

**The leader announces its configuration in the log, and a node that disagrees refuses.** Printed
fingerprints relied on an operator comparing them. Two nodes on different geometry, or on a different
`auctionMaxPasses`, read an identical log and diverge on the first order that touches the
difference. Consensus cannot see that, because the log they disagree about is the same. The
snapshot check only catches it when a snapshot lies between them. The alternatives were weighed
and refused. A check against the release manifest catches a corrupt file but not two nodes on
different releases. A warning without a refusal lets the node go on diverging. And Aeron's own
`appVersion` is a 32-bit field that Aeron also checks on snapshot load, so it would refuse even the
geometry changes the restore deliberately allows (Design.md §7, "Enforced through the log").

**A service message has to be offered on every node, from a log event.** Aeron numbers each node's
service messages, appends only the leader's, and sweeps a follower's copy when the leader's commits
under the same number. A message offered on one node alone puts that numbering out of step, and
Aeron throws if one is offered from `onRoleChange`. The announcement is offered from
`onNewLeadershipTermEvent`, which every node reads at the same log position, and each node offers
its own values. What reaches the log is the leader's. Service messages arrive at `onSessionMessage`
with a **null** session, so the parameter is nullable. A non-null Kotlin parameter would throw on
the first one.

**A refused node has to stop applying the log itself.** The throw that reports the mismatch is
swallowed by the image like any other (above), and the log keeps arriving. So the service sets a
flag, ignores everything after it, and refuses to snapshot.

**A line printed before replay says nothing about surviving it.** `run-restart.sh` step 7 passed
while its engine refused. It waited for "joined the shard", which `loadSnapshot` prints before the
log tail is replayed, and the refusal came a moment later. The step now asserts on the shutdown
line's `configurationAnnouncementsAgreed`, and step 8 covers the refusal itself.

---

## 6. Market data: the book image, the recovery feed, and the splice rules

**A recovered engine republishes its books as a level image, and market data rebuilds from it.**
Market data keeps no per-order state, so the image is one message per *occupied ladder level*
(`BookImageBegin`/`Level`/`End`, ids 27-29) rather than per order — bounded by `levelCount` instead
of by a resting depth nobody has measured. Two triggers, one path: a snapshot restore sets a pending
flag, and so does the `RequestBookImage` operator command (`most image`, `POST /api/shards/{id}/book-image`)
for a market-data process that restarted while the engine kept running. Both publish from
`doBackgroundWork` once the book-event publication is *connected*, because both ask at the moment a
subscriber is least likely to be listening.

**An image carries the sequence as a baseline and consumes none.** `nextBookEventSeqNum` is
replicated state and is snapshotted, so an image that advanced it would let a node whose publication
connected later produce a different snapshot. Same invariant as metrics, and
`MetricsDeterminismTest` checks it. A subscriber must not count an image's `seqNum` as a gap.

**Installing an image publishes increments as well as a snapshot, and that is not belt-and-braces.**
A subscriber that is already synchronised *ignores* snapshots (below), so a console that synchronised
to market-data's empty book a moment before the image landed would ignore every snapshot after it.
Increments carry absolute per-level quantities, so a replaced level self-corrects — but a level the
image *removed* would simply stop being mentioned, which is why `DepthBook.forEachVacated` zeroes it
explicitly.

**A synchronised subscriber ignores a snapshot only when installing it would rewind.** The rule used
to be unconditional; it now allows an image whose `l2SeqNum` is at or ahead of everything applied,
because such an image already contains all of it. Without the exception a subscriber handed a stale
image from the buffer on join — the oldest one still there — discards every later one and can only be
rescued by an increment happening to arrive. On a quiet book none does. `DepthFeedAssemblerTest`
pins both directions; mutating either way fails a different test.

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

---

## 7. The gateway: stateless, and what it must not consume

**The engine holds `origQty`, and the gateway holds nothing.** `origQty` lives in a parallel
`LongArray` beside the packed pool — `ColdField`, stride 1 — not in the 64-byte line, which stays
exactly 64 bytes. It is read only where an execution report is generated or the book is walked for
a snapshot, never inside the matching loop, so it costs a miss on paths already taking one. That is
also where a future order attribute matching does not read belongs (an at-open validity, say)
rather than evicting a field or doubling the stride.

This reversed a documented decision. `origQty` used to live only in the gateway, which is what made
the gateway the one component that could not be restarted or replaced without losing something —
and therefore what made gateway HA an open question. `OrderJournal` and `OrderStateStore` are
deleted, `gateway.journalFile` and `gateway.journalSlots` no longer exist, and several gateways can
serve one shard. What must be disjoint between two of them is their *client endpoints*: two
subscribed to one inbound channel each receive every order and forward both, which is duplicate
orders rather than redundancy. `e2e/run-restart.sh` §4d is the check — an order placed through one
gateway and cancelled through another.

**An order the gateway cannot forward must not be consumed.** `AeronCluster.offer` returning
`BACK_PRESSURED`/`ADMIN_ACTION` is transient; treating it as a drop loses an order the client
believes it placed, with no ack and no reject. The gateway uses `controlledPoll` and returns
`Action.ABORT` so the fragment is offered again — and `Action.COMMIT`, never `Action.CONTINUE`, on
the handled path, since `CONTINUE` commits only at the end of the poll and a later `ABORT` would
rewind past fragments already forwarded and duplicate them. A dead session is not retryable, so it
rejects with `GATEWAY_UNAVAILABLE`. **The outbound leg cannot do this** — egress must keep being
drained or the session dies — so it drops and counts; retrying there was measured and cost 10x on
the p99 while still dropping.

---

## 8. Participants, the registry, and the directory

**A participant is bound to its session at session open, from an authenticated principal.** A
gateway presents `gatewayId:secret` as its cluster credentials; the **consensus module** (`most
cluster --participants`) verifies them against the shard's `ParticipantRegistry` and stamps the
gateway id on the session as its **encoded principal**; the engine turns that back into a
participant list and binds every one. Aeron carries the principal in the session-open event through
the log, so every node derives the identical map — and because `ServiceSnapshotLoader` restores
sessions *without* replaying `onSessionOpen`, the engine rebuilds the bindings in `onStart` from
`cluster.clientSessions()` and the map needs **no snapshot state of its own**.

- **Traffic still wins for the order it arrived on.** The original reason was that the forwarding
  gateway held the order's `origQty`; the engine holds that now, so the surviving reason is plainer:
  a client following an order listens to the gateway it sent it through, and a report delivered
  elsewhere is one it never sees. Correspondingly `onSessionClose` drops only routes that session
  **still owns** — a route
  traffic has moved to a live gateway stays there. Both directions are mutation-tested in
  `ParticipantBindingTest`.
- **Wrong credentials are rejected, never downgraded to anonymous.** A gateway that connected
  anonymously by accident trades perfectly well and loses only the fills of whichever participants
  went quiet — invisible until someone reconciles a `cumQty`. **No** credentials still authenticate
  anonymously, because the control plane and the CLI connect to send operator commands and are
  addressed by nobody.
- **The registry is not in `ShardSpec.fingerprint()`** and must not be folded into it: that hash is
  recorded by the control plane and published in every release, and rotating a gateway secret is not
  a change of geometry. It has its own `fingerprint()`, printed by the engine, the gateway and the
  cluster host.
- **Everything here is optional and off by default.** Unset, the engine learns routes from traffic
  exactly as before. What is *not* built: enforcement — `UNAUTHORIZED_PARTICIPANT` is still raised
  by nothing, so a gateway may still trade for a participant that is not its own, and see the
  reload note above for why that one cannot simply read the file.
- `e2e/run-restart.sh` §4c is the check: rest an offer, restart the gateway, cross the offer, and
  the maker's cancel must report `cum 4 of 10`. Before the binding it read `cum 0 of 10`, because
  the engine counted the maker's fill undeliverable and never sent it anywhere.

**The participant registry is re-read while a node runs, and that is legal for one specific reason.**
`ParticipantRegistrySource` polls the file, compares content by fingerprint (a release is published
to a new directory and put in force by moving a symlink, so mtime says nothing) and swaps an
immutable registry behind a volatile reference. The consensus module and the engine both read it
through that, and so does the gateway, which enforces it. **A file that
cannot be parsed, or one for another shard, is reported and ignored** — standing down on a bad file
would turn a typo into a shard that authenticates nobody.

The reason it is legal in the engine: what it feeds is node-local *egress routing*. The map is
rebuilt in `onStart` from `cluster.clientSessions()`, is deliberately not snapshotted, and only the
leader's egress reaches anyone, so two nodes holding different versions can disagree about where to
send a report and cannot diverge the log, the books or a snapshot. **That stops being true the
moment the engine rejects an order on a binding.** Same argument that keeps metrics out of
`EngineConfig.fingerprint()`, and `engine.participantRegistry.reloadMs` is excluded from it for the
same reason.

**So enforcement lives in the gateway, and the engine never rejects on the registry.** For a long
time the line above was read as "enforcement must come through the log" — a sequenced command
installing a registry version, a snapshot field, a wire change. The simpler reading is that a
refusal which never *enters* the log has no determinism to protect: the gateway rejects
`UNAUTHORIZED_PARTICIPANT` locally, exactly as it rejects `UNKNOWN_SECURITY`, and may therefore
decide from a file it re-reads on its own schedule. It rejects earlier and cheaper, too. Two things
make it enforcement rather than a filter: **cancels are checked as well as orders** (the engine's
own cancel check is participant equality, which is only as good as the participant id), and
**operator commands need `operator=true`** — before this, any client of any gateway could halt the
market. The side door is closed as well: an anonymous cluster session skips every gateway, so a
node with a registry refuses one, and the control plane and the CLI — the two things that reach the
cluster directly — hold operator-only registry identities instead. That last step had to wait for
those identities, or it would have locked the control plane out of its own snapshots. And because
the control plane's *operator commands* go through a gateway and are unacknowledged, it can be
pointed at a dedicated operator gateway (`control.cluster.operatorChannel.<shard>`) rather than
trusting that the shard's advertised one is an operator. The engine keeps one thing: it *counts* a message for a participant the sending
gateway does not list, as defence in depth against a misconfigured gateway, and never branches on it.

**The control plane authors the registry.** `gateway` and `gateway_participant` (V5) hold gateway
identity, the SHA-256 of its secret and its participant claims; `ReleasePublisher` renders
`shard-N-participants.properties` beside the security file. The digest cannot be BCrypt — the
cluster verifies it against what a gateway presents, so it must be reproducible — and the plaintext
is returned exactly once. A shard with **no** gateways publishes no registry rather than an empty
one; `ParticipantRegistry` requires at least one, and a shard whose gateways connect anonymously is
a legitimate configuration. The registry fingerprint gets its own column and its own manifest field,
never folded into the shard's.

**The directory publishes the GATEWAY's client endpoints**, not the cluster ingress/egress — an
adapter connecting to the cluster directly would bypass the gateway's `securityId` validation and
the participant binding that decides where a maker's fills go.

**A gateway serves exactly one shard** — one cluster connection, and it rejects anything outside its
list. `discovery` publishes the universe (security → shard → ingress channel) on a repeating
multicast cycle so upstream adapters can route; `DirectoryClient` is what they embed. Discovery
enforces one shard per security, and stages each broadcast so a truncated cycle never replaces a good
routing table.

---

## 9. The control plane

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

**One security list per shard.** `reference`'s `ShardSpec` is read by the engine, gateway,
market-data and discovery alike — do not reintroduce per-process security lists. It carries identity
(`symbol`, `isin`, `name`, `currency`) and geometry; ISINs are check-digit validated at boot. Every
process prints `ShardSpec.fingerprint()` at startup, which is how a geometry mismatch is caught
before it silently diverges the books.

---

## 10. The native image: two flags that fail silently

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

---

## 11. Metrics, and why the engine may read `nanoTime`

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

---

## 12. Docker: one media driver is one network identity

**In Docker, one media driver is one network identity.** The five shard processes share a media
driver through a tmpfs volume, and that driver runs in the `cluster-host` container — so every UDP
endpoint the shard exposes is bound there and must name `shard0` (an alias on that container), never
the individual process's container. `control=gateway:20002` fails with "Cannot assign requested
address". The dev stack also swaps multicast for **dynamic MDC**, because a Docker bridge does not
route multicast: a publication says `control=shard0:PORT|control-mode=dynamic` with no endpoint, and
what discovery hands a *subscriber* additionally carries `endpoint=0.0.0.0:0`. That asymmetry is
correct, not a typo. `deploy/README.md` has the rest.

**A cluster client's egress must name its media driver's host.** `0.0.0.0` is where a client
*listens*, not an address the consensus module can send to, so a client that uses it connects and
then times out at `POLL_RESPONSE` — which is why the control plane could never take a snapshot in the
dev stack even before authorisation was considered. The name is the driver's container, not the
client's: the control plane's egress is `control-driver:0`.

**Rebuilding the CLI image recreates the cluster host.** `most` and `cluster-host` run the same tools
image, and `most` sits in the `cli` profile, so `docker compose up --build` never rebuilds it. After
`docker compose --profile cli build`, the next `up` or `run` sees a new image for `cluster-host` and
recreates it; a cluster host recreated inside the ~10 s mark-file window refuses to start, and the
shard is down. Stop the shard, wait, start it — and use `docker compose run --no-deps` for one-off
containers.

---

## 13. Measurement

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

**A rate above the knee is not a slow round trip; it is a queue draining.** Above the shard's
ceiling the generator empties its schedule into a buffer and the acknowledgements arrive later, so
the sweep's `achieved` figure becomes an *offer* rate and its latency measures the drain. Both
existing validity checks pass on such a row — no rejects, and the generator was perfectly on
schedule, which is precisely why the queue formed elsewhere — so the sweep once printed `PASS` beside
a 462 ms median that could have been quoted as a latency. `run-sweep.sh` therefore has a third check:
a response p50 above `SATURATION_US` with pacing healthy marks the row `SATURATED`. It is not invalid
— it is the measurement the sweep exists to find, and the knee is only visible because some row is on
the far side of it — but it is never a round trip, and the summary names the highest rate the shard
actually kept up with so the ceiling is a number rather than an inference.

**Read the counters before theorising, and then distrust the first counter that moves.** `most counters`
maps the driver's CnC file and reads what the driver, the archive and every cluster component already
publish — no Aeron client, so it can be pointed at a saturated shard without changing the answer. It
found the ceiling in one pass, and it also found a decoy. At 400k/s every counter in the durable chain
scaled exactly 1.33x with the offered rate; the one that exploded was sender flow-control
back-pressure, ×89, on the cluster ingress channel whose term length is `64k`. That looked like the
answer and was not: at `16m` the knee did not move at all. Back-pressure on a channel is what a slow
*consumer* looks like from the publisher's side, so a saturated window is as likely to be the symptom
as the cause, and the only way to tell is to change it and re-measure. What was the cause sat one layer
down — the media driver's `ThreadingMode.SHARED`, one thread for conductor, sender and receiver moving
190 MB/s of loopback UDP. **A counter that moves non-linearly names a place to look, not a cause.**

**A counter cannot see a full thread, and `ps` cannot see a busy-spinning one.** Pointed at the next
knee, `most counters` came back clean: every stage scaling exactly with the offered rate, the archive's
write time halving, every duty-cycle and error counter at zero — beside a 478 ms median. Aeron reports
queues, positions, stalls and errors, and a stage that is merely *full* produces none of those, so a
clean sheet narrows the answer to "not a buffer, a window, a disk or a stall" and no further. The
obvious next instrument misleads in the opposite direction: `engine`, `gateway` and `market-data` all
use `BusySpinIdleStrategy`, so each reads ~100% of a core whether it is working or idling, and the first
reading of that sample wrongly concluded "three stages pinned". The control that caught it was running
the same sample under `SHARED`, where the same three read ~100% at a rate 1.7x lower. **Utilisation of a
busy-spinning loop has to come from the loop's own metrics** — which the engine and gateway already
publish — or from a run deliberately switched to a yielding strategy for the measurement. The
corollary: a CPU sample of this system needs a second configuration to compare against before any figure
in it means anything.

**Measure the factorial, not one cell of it.** Dedicating the archive's thread *helps nothing and hurts
on its own*: with the driver still `SHARED`, a `DEDICATED` archive was worse than both shared — 28.4 ms
against 6410 µs at 350k/s — because it takes a core from the component that needed it. Had that been
tested as a single change it would have read as "dedicated threads make things worse" and closed off
the setting that actually mattered.

**A storage experiment needs a same-day baseline on the other medium, and a rate at the knee.** The
question "is the archive write the ceiling?" was first asked by moving the consensus log and archive to
a RAM disk and comparing the *median round trip* at a rate the shard was comfortably serving. That
found 2.6% and would have answered "no" from a rate at which storage was never stressed — the right
answer, reached invalidly. Compared instead against a sweep taken days-of-desktop earlier, the same
RAM disk appeared to move the knee by 82x at 350k/s, which was entirely the browser that had been
closed in between. What settled it was a *throughput* comparison at and above the knee against an
SSD baseline taken the same hour: the knee did not move at all, so storage is out. Two rules come out
of that, and both are cheap: compare configurations only at the rate where the resource is actually
under pressure, and take both sides of the comparison under the same load on the same day.
`run-sweep.sh` and `run-attribution.sh` therefore both take `CLUSTER_HOST`, which moves the durable
writes without moving the Aeron buffers, so the two runs differ in one variable and not two.

**`RATES` are aggregate across `SECURITIES`.** The alternative — a per-security rate multiplied by the
count — would make a one-book sweep and a ten-book sweep incomparable, and comparing them is the
entire finding: the ten-security knee (~350k/s) is the *same aggregate* as the one-security knee
(200k–333k) on the same machine, which is what identifies the ceiling as the shared path rather than
the books. A per-security reading would have hidden that behind a factor of ten.

---

## 14. Warnings are errors

- **Warnings are errors** in every module, `control` included — it already caught a
  `java.lang.Long` where `kotlin.Long` belonged. This is deliberate: a silent "inline function cannot be
  inlined" would break the zero-allocation profile. Do not disable it to get a build through — fix
  the warning. Reserve `inline` for functions taking callbacks; on plain helpers Kotlin correctly
  warns it buys nothing.

---

## 15. Gateway placement, and why the gateway reads its node's role

IPC egress roughly triples what one node carries (Design.md §2, K1–K5), and it requires the gateway
to share the leader's media driver. ProdDeployment.md had argued that co-locating a gateway "buys
nothing", which was true until egress was found to be the knee. Neither placement is right for every
operator. One costs throughput, the other costs a gateway switch on every failover. So it is a key,
`gateway.placement`, and both are kept honest by `e2e/run-failover.sh` (Design.md §7).

- **A co-located gateway cannot learn of a new leader from its cluster client.** Read in Aeron 1.53's
  source: a client whose egress image closes enters `AWAIT_NEW_LEADER` and waits `newLeaderTimeoutNs`
  (2 × the leader heartbeat timeout, ~20 s by default) for a `NewLeaderEvent`. The new leader sends
  that event on its own egress, and with an IPC egress channel that is the *new* leader's driver,
  which the old leader's gateway never sees. For those ~20 s its offers go to a node that is now a
  follower. The `Cluster node role` counter on its own driver changes the moment the role does, so
  the gateway polls it (`LeaderWatch`, every 10 ms) and stands down on anything but LEADER.
- **The per-node gateways share one identity** because the engine already does the right thing with
  it. A primary gateway binds its participants at session open even beside a live holder, so the
  newly active gateway takes every route at once. The ex-leader's session stays open on the new
  leader until the session timeout and owns nothing. An identity per node would leave routes on that
  dead session for up to 10 s, and every maker fill in that window would be undeliverable.
- **Standby refuses; it does not drop or hold.** Holding an order on a standby gateway would wait for
  an election that may never make this node leader. Dropping it would be silent. A
  `GATEWAY_UNAVAILABLE` costs the client one round trip and tells it exactly what to do.
- **The client also moves on a disconnect**, because the case that matters most, the leader's
  machine dying, takes its gateway with it, and a dead gateway sends nothing. That blind window
  (Aeron's publication connection timeout, 5 s) was found by running the failover, not by reasoning
  about it. Reject-and-retry alone had been the plan.
- **The directory is not the failover signal.** Discovery broadcasts on an interval (5 s), so a
  client following it learns later than one that is refused. An "active gateway" entry would still
  serve a client that is starting up.

