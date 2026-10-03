# The Control Plane

Reference data and shard topology are authored in Postgres, through a REST backend, and
**published** as the same `.properties` artifacts the engine, gateway, market-data and discovery
have always booted from.

It also holds a live link to a running exchange: it sends the three operator commands, watches the
discovery and L3 feeds, drives the trading day from a calendar, and can walk a halted security back
through the reopen sequence. Tick storage is still ahead (`docs/Future.md`).

---

## 1. The rule that shapes everything: the database is not on the boot path

A cluster node reads a file, not a database.

Two reasons, and the second is the one that matters. A database outage would stop a node from
starting, which is bad. But a write landing *between* two nodes' boots would give them different
geometry — different tick sizes, a different ladder floor — and geometry decides how a price maps to
a ladder level. The nodes would not fail; they would diverge, quietly, on the first order. That is
the one class of misconfiguration Raft cannot catch, and it is exactly what `ShardSpec.fingerprint()`
exists to make visible (`docs/Design.md` §7).

So the control plane **authors**, and a release **publishes**. Between the two sits an immutable
artifact that cannot change under a running process.

```
Postgres ──POST /api/releases──> releases/000007/  ──boot──> engine, gateway, market-data, discovery
 (edit)                          shard-0-securities.properties
                                 shard-1-securities.properties
                                 discovery.properties
                                 manifest.json
```

## 2. The second rule: never reimplement a domain rule

`ShardSpec.fingerprint()` and `Universe.version` are ad-hoc hashes over a canonical string with an
exact sort and exact separators. A reimplementation in SQL or TypeScript that drifted by one
separator would produce a control plane confidently reporting agreement between processes that
disagree — worse than not checking at all.

The backend therefore **constructs the real `reference` types from database rows and calls their
methods**. `ShardRow.toRoute()` and `SecurityRow.toSpec()` are the only crossing points, and they
are on the write path, so a bad row is refused at the API rather than at publish. Everything comes
for free and in one implementation:

| Rule | Where it lives |
| --- | --- |
| ISIN format and check digit | `SecuritySpec.isValidIsin` |
| Symbol ≤ 16, ISIN 12, currency 3, name ≤ 48 | `SecuritySpec.init`, mirroring the SBE types |
| Channel URI ≤ 128 | `ShardRoute.init` |
| At most 10 securities per shard | `ShardSpec.init` |
| Exactly one shard per security | `Universe.init` — and the `security` table's primary key |
| Globally unique symbol and ISIN | `Universe.init` — and unique constraints |
| The fingerprint an operator compares | `ShardSpec.fingerprint()` |

What the database adds is enforcement the properties files never had: `security_id` is the primary
key and `shard_id` a plain column, so "one shard per security" is *structural* rather than checked,
and a foreign key stops a shard being deleted while it still serves something.

## 3. Data model

```
shard(shard_id, order_entry_channel, order_entry_stream_id,
      execution_report_channel, execution_report_stream_id)

security(security_id, shard_id, symbol, isin, name, currency,
         price_floor, tick_size, level_count, max_orders,     -- geometry: published
         reference_price, static_collar_bps, dynamic_collar_bps)  -- sent as a command

participant(participant_id, name, smp_id, enabled)

gateway(gateway_id, shard_id, secret_sha256, enabled, operator)   -- published as the registry
gateway_participant(participant_id, gateway_id, cancel_only, is_primary)   -- PK is the pair

spec_release(version, created_at, universe_version, directory, note)
spec_release_shard(version, shard_id, fingerprint, registry_fingerprint)

session_schedule(name, zone, weekdays, purge_time, enabled)
session_schedule_entry(schedule_name, at_time, phase)
market_holiday(schedule_name, holiday_date, description)
schedule_run(id, at, shard_id, action, phase, trading_date, sent, confirmed, detail)
shard.schedule_name                              -- which schedule a shard follows
```

Prices are fixed point with 8 implied decimals throughout, exactly as on the wire: `tick_size =
1000000` is 0.01.

**Reference price and collars are authored but never published.** They are not geometry: they reach
the engine as `SecurityDefinition` commands through the replicated log, because every node must apply
them at the same log position. They are consequently **outside the fingerprint**, which covers only
what processes must agree on at boot — seeding a reference price must not invalidate a release, and
does not. They record what an operator *seeded*, not what the engine currently holds, and cannot: an
executing uncross moves `staticReference` with nobody asking.

**Gateways and what they may do are published, and the gateway enforces them.** `gateway` and
`gateway_participant` render each shard's participant registry (`docs/Design.md` §1, "Enforcement,
at the gateway"): the consensus module authenticates connecting gateways against it, the engine
binds participants from it at session open, and **the gateway refuses** an order or cancel for a
participant it does not list (`UNAUTHORIZED_PARTICIPANT`, before the log), a new order from a
`cancel_only` participant, and an operator command unless the gateway is an `operator`. An
**operator-only** gateway — `operator` and no participants — is how the control plane and the CLI
are named to a node that refuses anonymous sessions; it is published like any other.

A participant may be listed on **several gateways** (V6 made the key the pair; V5's key on
`participant_id` alone had also, less visibly, confined a participant to one gateway on one shard).
Where several of a shard's gateways list it, one must be its primary (`is_primary`), or the draft
view reports the shard's registry as a problem. One primary per participant **per shard** is checked
by the service, because the table cannot see a listing's shard. A primary flag on a participant only
one gateway lists is kept but not published — the registry format would refuse it as a typo, and it
is usually the leftover of a failover since disabled.

**The control plane's live commands need an operator gateway at the shard's advertised endpoint.**
Session transitions, purges, definitions and image requests are sent to the shard's
`order_entry_channel`, like `most` does, and are unacknowledged — so if the gateway behind that
endpoint is not an `operator`, it consumes and counts them (`refusedCommands`) and nothing here can
tell. The database does not know which gateway id serves an endpoint, so this cannot be checked
before sending — and `control.cluster.operatorChannel.<shardId>` (with an optional
`control.cluster.operatorStream.<shardId>`) therefore sends them to a named operator gateway
instead, which is the configuration to prefer whenever the advertised gateway is a participant's
rather than the operator's. Snapshots are the exception: they go straight to the cluster, and a node with a
registry refuses them unless `control.cluster.identity.<shardId>` and
`control.cluster.secretFile.<shardId>` name an operator entry of that shard's registry.

**The secret is stored only as its SHA-256, and that cannot be BCrypt.** The cluster verifies the
digest against what a gateway presents, so it has to be reproducible. The plaintext is returned
exactly once, by `POST /api/gateways` or `PUT /api/gateways/{id}/secret`, and is unrecoverable
afterwards — rotating issues a new one rather than reading the old one back. Create always
*generates* one; a caller that needs a specific value follows with the rotate call, which takes it
in the request body, because a secret in a query string is a secret in an access log.

**One duplication this removes.** A gateway's client endpoints are declared today in
`gateway.properties` *and* again in `discovery.properties`, with nothing checking they agree. They
are one `shard` row here, rendered into both files, so they cannot disagree.

## 4. REST surface

### Authentication

Everything under `/api` requires an authenticated operator, with the sole exception of
`/api/auth/login` and `/api/auth/logout`. There is **one role, `ADMIN`, with full access**; the
`role` column exists so that adding a read-only tier later is a data change rather than a migration.

This module is the only one that needed authenticating, and the reason is worth stating: it is the
only process that *decides* something. The engine, gateway, market-data and discovery processes
apply a replicated log or forward bytes. This one seeds a `SecurityDefinition`, moves a shard's
session, purges, reopens a halted security and runs a calendar that opens a market unattended —
against an engine that acknowledges none of it. Nothing downstream will ever be able to say who sent
a command, so the question has to be settled here.

Two ways in, and they are for different callers:

```sh
# A browser (the Vue frontend). Session cookie, and a CSRF token on anything that mutates.
curl -c jar localhost:8080/api/auth/me            # 401 -- and issues the XSRF-TOKEN cookie
TOKEN=$(grep XSRF-TOKEN jar | awk '{print $7}')
curl -b jar -c jar -X POST localhost:8080/api/auth/login \
  -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"..."}'      # 200 {"username":"admin","roles":["ADMIN"]}

# A script. HTTP Basic, exempt from CSRF, one call.
curl -u admin:... localhost:8080/api/shards
```

`/api/auth/login` is itself CSRF-protected, so a browser arriving with no cookie has to be *given* a
token by an earlier response — which is why the anonymous 401 carries one, and why
`SecurityConfig` opts out of Spring Security's deferred token resolution. Basic auth is exempt
because a browser attaches cookies to a cross-site request automatically and an `Authorization`
header never; a request carrying its own credentials is not a forgery risk.

The first operator is seeded once into an empty table from `CONTROL_ADMIN_USER` /
`CONTROL_ADMIN_PASSWORD`. **With no password configured it generates one and logs it at WARN.** A
known default password on something that can open a market would be worse than the unauthenticated
version it replaces, because that one at least did not look protected.

### Who asked

`operator_audit` records every market-moving command with the operator who issued it, and records
**`sent`, not `applied`** — the same distinction §6 makes, for the same reason. A command the
control plane could not deliver is recorded too, with `sent: false`; that is exactly as interesting
to an investigation as one that went out. Unattended transitions are the scheduler's and stay in its
own richer `schedule_run` log, which also records what it deliberately did *not* do.

```
GET  POST                 /api/auth/login       /api/auth/logout   GET /api/auth/me
GET  POST                 /api/users            PUT /api/users/{u}/password, /enabled   DELETE
GET                       /api/audit?limit=     who asked, what for, and whether it was even sent

GET  POST                 /api/shards           GET PUT DELETE /api/shards/{id}
GET  POST                 /api/securities       GET PUT DELETE /api/securities/{id}
GET  POST                 /api/participants     GET PUT DELETE /api/participants/{id}
GET  POST                 /api/gateways         GET PUT DELETE /api/gateways/{id}
PUT                       /api/gateways/{id}/secret   rotate; the plaintext is returned once
GET                       /api/gateways/registry/{shardId}   the file this shard would publish

GET                       /api/topology         the draft, its fingerprints, and what is wrong
POST                      /api/import           seed from an existing shard security file

POST                      /api/releases         validate, freeze, write the artifacts
GET                       /api/releases         /api/releases/{version}   /api/releases/latest
GET                       /api/releases/{v}/files/{name}
```

A refusal carries the domain's own sentence, because that is the same sentence a process would have
printed at boot:

```
$ curl -u admin:... -X POST .../api/securities -d '{"securityId":1,"isin":"US0378331006",...}'
{"error":"invalid","message":"invalid ISIN for security 1: US0378331006"}
```

`GET /api/topology` reports **every** problem rather than the first — a half-built topology is the
normal state while someone is editing it — and `problems` being empty is exactly the condition for
publishing, so one call answers both "can I publish?" and "what is stopping me?".

## 5. Publishing, and what a release is

`POST /api/releases` builds every `ShardSpec` and the `Universe` from the current rows — which is the
validation — then writes a numbered directory and records the fingerprint per shard.

A release is **immutable**. Republishing allocates the next version rather than rewriting one, so a
directory a running process was pointed at never changes underneath it. That holds even when the
numbering does not: after a database restore or reset, release numbers repeat, and **a version whose
directory already exists is refused** with `409 conflict` rather than written into. No release row is
recorded. Move the old directories aside, or point `control.releaseDir` somewhere empty. Each refused
attempt still uses up a version number, because a Postgres identity value is not returned on
rollback. The rendered files are
deterministic: ordered by id, no timestamp, so two publishes of identical content produce identical
bytes and diffing two releases tells an operator something.

**A release carries topology, not deployment.** Node-local settings — the Aeron directory, the
cluster directory, the discovery channel and interval, the book-event stream — stay in each
process's own config. A deployment composes the two:

```sh
REL=/var/lib/most/releases/000007

# engine.properties, gateway.properties, market-data.properties
engine.securitiesFile=$REL/shard-0-securities.properties

# The participant registry, read by the consensus module, the engine and the gateway. Point at a
# symlink rather than the release: the two node processes re-read this path while they run, so
# moving the symlink is how a new registry comes into force without restarting a node.
engine.participantRegistry=/etc/most/current/shard-0-participants.properties

# discovery.properties: the published registry, plus this node's own runtime settings
cat $REL/discovery.properties         >  discovery.properties
echo "discovery.channel=aeron:udp?endpoint=239.10.0.1:40000" >> discovery.properties
echo "discovery.streamId=100"         >> discovery.properties
echo "discovery.intervalMs=5000"      >> discovery.properties
```

Then compare what the processes print against `manifest.json`:

```
$ cat $REL/manifest.json
{ "version": 7, "universeVersion": 5955292213055092758, "securities": 2,
  "shards": [ { "shardId": 0, "fingerprint": "35c6b5d8c0238d8a", ... } ], ... }

matching-engine: shard=0 fingerprint=35c6b5d8c0238d8a ...
gateway:         shard=0 fingerprint=35c6b5d8c0238d8a ...
market-data:     shard=0 fingerprint=35c6b5d8c0238d8a ...
```

The fingerprint is also written into the top of each shard file as a comment, so the artifact and
the release record cannot drift apart.

**A shard with gateways also gets `shard-N-participants.properties`, and its own fingerprint.** That
value is a second column and a second manifest field, never folded into `fingerprint`: the shard
hash is over geometry, is already recorded in every release published so far, and rotating a gateway
secret is not a change of geometry. A shard with **no** gateways publishes no registry file rather
than an empty one — `ParticipantRegistry` requires at least one gateway, and a shard whose gateways
connect anonymously is a legitimate configuration, not a broken registry.

**A registry change does not need a node restart.** The consensus module and the engine poll the
path they were given and swap the registry behind a volatile reference, announcing both
fingerprints; the gateway restarts, which costs nothing because it holds no state.
`docs/ProdDeployment.md` §9.4 is the procedure.

Comparing them is still the operator's job, as it is today. Making the four processes fail fast on a
mismatch is the obvious next step and is deliberately **not** in this slice — it changes four
processes on the boot path, and is worth doing on its own.

## 6. Live cluster control

The backend holds an Aeron client of its own and talks to the **gateway's client channel**, exactly
as `most` does — never to the cluster directly, so an operator command takes the same validated path
an order does and is sequenced through the replicated log.

**The link is optional.** Authoring reference data must work with no media driver anywhere near it,
so a missing driver is reported by `GET /api/status` and nothing else breaks. Set
`control.aeron.enabled=false` to skip it entirely.

```
POST /api/securities/{id}/definition   seed or re-seed reference price and collars
POST /api/shards/{id}/session          move a shard to a phase
POST /api/shards/{id}/purge            the off-session expiry sweep
POST /api/shards/{id}/reopen           the halt-recovery runbook, as one operation
GET  /api/status                       what the exchange is actually doing
```

### Sent is not confirmed

Operator commands are **not acknowledged**. The engine applies or rejects them without replying, and
a rejected `SecurityDefinition` increments a counter and says nothing. Every response therefore
carries both flags, and they mean different things:

```json
{ "command": "session CONTINUOUS shard 0", "sent": true, "confirmed": true,
  "detail": "every book on shard 0 reported CONTINUOUS" }

{ "command": "define AAPL", "sent": true, "confirmed": false,
  "detail": "seeded reference 110.00, static 5000bps, dynamic 100bps -- not confirmable:
             no feed acknowledges a SecurityDefinition, so a rejection would be silent" }
```

`sent` means the bytes reached the gateway. `confirmed` means the control plane afterwards *saw* the
effect on the book event stream — `SessionChanged` for a phase. A definition can never be confirmed,
and reporting a bare 200 for both would claim knowledge the backend does not have.

Since a definition cannot be confirmed, everything checkable is checked **before** it goes on the
wire: an unknown security, a missing reference price, and — the one that matters — a static band
that falls outside the pre-allocated ladder, which the engine refuses in silence:

```
a 5000bp static band around 300.00 spans 150.00..450.00, outside AAPL's ladder
0.00..327.67; the engine would reject it without replying
```

Geometry is never taken from the caller. `priceFloor`, `tickSize` and `levelCount` come from the
database — the same rows the shard booted from — because a definition whose geometry disagrees with
the allocated book is rejected, silently, and the operator has no way to tell.

### Monitoring: discovery and L3

The poller consumes two feeds. **Discovery** gives the routing table and its version. **L3** — not
L1 or L2 — gives live per-security phase, auction uncrosses, trades and, critically, halts: a
`VolatilityHalted` event is forwarded only to L3, so a depth subscriber cannot distinguish a halt
from a scheduled close. Recovery cannot begin from a halt you cannot see.

`GET /api/status` also reports **routing drift** — where the database and what discovery is actually
broadcasting disagree:

```json
"routingDrift": ["shard 0 is broadcast by discovery but absent from the database"]
```

Commands route from the database, not the directory: the control plane is the authority on topology,
and a command must still be sendable when discovery is down. Where the two disagree, that is said
out loud rather than one silently winning.

### Reopening a halted security

A halt sets **one security** to `CLOSED` and leaves the resting book intact. There is no halt phase;
the `VolatilityHalted` event is the only thing distinguishing it from a scheduled close.

```sh
curl -X POST localhost:8080/api/shards/0/reopen -H 'Content-Type: application/json' \
  -d '{"securityId": 1, "referencePrice": 11000000000}'
```

That performs, in this order:

1. `SecurityDefinition`, re-seeding `staticReference` at the new level.
2. `SessionTransition(PRE_OPEN)` — orders accepted and booked, no matching.
3. `SessionTransition(OPEN_AUCTION)`.
4. `SessionTransition(CONTINUOUS)` — the uncross runs on *this* transition.

**Both orderings are load-bearing.** Re-seeding must come first because `staticReference` is only
reset by an *executing* uncross, while orders are accepted from `PRE_OPEN` onward: if the halt moved
price outside the old static band, the very orders needed to reopen are rejected by the stale collar
before the auction that would have fixed it gets any — a deadlock that looks like nothing happening.
And the phases must be walked in full, because the uncross runs only on `OPEN_AUCTION → CONTINUOUS`;
jumping straight to `CONTINUOUS` is accepted and silently skips the auction.

**A session transition is shard-wide.** The engine applies the phase to every book it hosts — there
is no per-security session command — so reopening one halted security reopens everything else on the
shard. The response says so every time, not only when it bites:

```json
"warning": "a session transition is shard-wide: this moved AAPL, MSFT, not only AAPL"
```

### Configuration

| Variable | Default | |
| --- | --- | --- |
| `CONTROL_AERON_ENABLED` | `true` | Set `false` for a database-only deployment |
| `CONTROL_AERON_DIR` | driver default | Must be a driver this host can reach |
| `CONTROL_DISCOVERY_CHANNEL` / `_STREAM` | `aeron:udp?endpoint=239.10.0.1:40000` / `100` | |
| `CONTROL_L3_CHANNEL` / `_STREAM` | `aeron:udp?endpoint=239.10.1.3:40003` / `3` | L3, not L1 |

## 6a. Books: the depth feed, terminated here

The console shows live order books, and the control plane is an ordinary L2 subscriber to get them.
It uses `reference`'s `DepthFeedAssembler` — the same code the operator CLI uses, and the same code a
FIX market data adapter would — so a book on the screen cannot disagree with a book anywhere else.
Nothing about depth is re-derived in this module.

```
GET /api/books              every book the feed is carrying
GET /api/books/{id}         one book; 404 means the feed has never carried it
GET /api/books/status       feed health: snapshots applied, gaps, books dropped and rebuilt
GET /api/books/stream       the live feed, as server-sent events
```

**Three subscriptions, not one.** L2 carries the increments, the snapshot stream carries the images
that make them applicable, and L1 carries `LastTrade`. Take L2 without the snapshot and a subscriber
can never synchronise; take the snapshot without L1 and the book trades on screen while its last
price stays empty, because a *synchronised* subscriber ignores snapshots and the last trade rides
only on L1.

**The browser gets a conflated image, never the protocol.** The feed can carry 100k updates a second
and an operator can read about four. Images are built on the feed thread at a fixed interval with
everything in between folded in, which is what a person wants and what keeps a browser off the feed's
critical path: there is no back-pressure path from a console to the market data thread, by
construction. A slow console costs a dropped image and nothing else.

Two consequences worth stating:

* **The browser holds no sequence numbers, no gap detection and no snapshot splicing.** Decoding SBE
  in TypeScript would mean a second `DepthFeedAssembler` and a second `FeedSequenceTracker` in a
  language where neither can be tested against the publisher. A real consumer takes the SBE feed and
  uses the same assembler this backend does; **this endpoint is for the operator console, not a
  client-facing market data product.**
* **An unsynchronised book publishes no depth at all** — not the last good ladder. `synchronised` says
  which, and the console renders "waiting for a snapshot" rather than an empty book, because waiting
  and having no liquidity are different answers.

**Server-sent events rather than a WebSocket.** The traffic is one-way; the browser has nothing to
say back that a URL cannot carry. `EventSource` brings its own reconnection, needs no dependency and
no protocol upgrade, and is authenticated by the same session cookie as everything else — it cannot
set headers, which is one more reason the SPA and the API sit behind one origin. A version on each
image changes only when that book's content changes, so an idle book is sent once and then not again.

## 7. Scheduling

A schedule is a named trading day — local times mapped to phases, plus a purge time — that a shard
follows. It lives here and fires here, not in the engine.

**That costs no determinism.** The `SessionTransition` the scheduler emits is sequenced through the
replicated log like any other command, so every node applies it at the same log position. What
keeping it out of the engine buys is that a trading calendar — weekends, holidays, daylight saving —
stays outside the deterministic state machine, where a bug kills every node simultaneously.

```sh
curl -X PUT localhost:8080/api/schedules/equities -H 'Content-Type: application/json' -d '{
  "name": "equities", "zone": "Europe/Amsterdam",
  "weekdays": ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],
  "purgeTime": "07:00",
  "entries": [
    { "at": "08:00",  "phase": "pre-open" },
    { "at": "08:55",  "phase": "open-auction" },
    { "at": "09:00",  "phase": "continuous" },
    { "at": "17:30",  "phase": "closed" } ] }'

curl -X PUT localhost:8080/api/shards/0/schedule -d '{"scheduleName":"equities"}' \
     -H 'Content-Type: application/json'
curl -X POST localhost:8080/api/schedules/equities/holidays \
     -H 'Content-Type: application/json' -d '{"date":"2026-12-25","description":"Christmas"}'
```

**Times are local and the zone is stored with them.** A market opens at 09:00 local whatever the
offset is that week; storing UTC instants would move the open by an hour twice a year.

### Every tick is a reconciliation, not a trigger

The scheduler does not fire timers. On each tick it compares the phase the calendar says a shard
should be in against the phase the **L3 feed says it is in**, and sends the difference. One choice,
three consequences: it is idempotent, it recovers by itself after an outage, and there is no
missed-timer state to keep anywhere.

**The difference between two phases is a path, not a destination.** The uncross runs only on
`OPEN_AUCTION → CONTINUOUS`, so a backend that was down through the open and catches up at 09:15
walks `PRE_OPEN → OPEN_AUCTION → CONTINUOUS`. Sending `CONTINUOUS` straight from `CLOSED` would be
accepted by the engine and would skip the auction, leaving any crossed resting book crossed until an
aggressor happened to arrive. Closing is a single step — nothing is computed on the way down.

### What it refuses to do

**It never reopens a halted security.** Recovery is operator-driven by design, and reconciling back
to `CONTINUOUS` would not merely override that — it would do it *without an auction*, since from the
scheduler's point of view the shard is already past `OPEN_AUCTION`. A halt makes the shard skip,
with the remedy in the message:

```
skipped  CONTINUOUS  AAPL halted; recovery is operator-driven (POST /api/shards/0/reopen)
```

**It waits rather than guessing an unknown phase.** A freshly booted engine has made no transition,
so it has emitted no `SessionChanged` and its phase is genuinely unknown here. Guessing `CLOSED`
would be right after a cold boot and catastrophic after a control-plane restart mid-session — which
looks identical from the feed. So it waits, and says what unblocks it: **send any session command
once to establish a baseline**, after which the feed keeps it current. That is the one manual step
in bringing a cold cluster up.

**A late purge is skipped, not run.** The sweep is due only between its time and the first
transition of the day. After `PRE_OPEN` the book is accepting orders, and a late sweep would expire
orders someone had just placed.

It also stands down when a shard's books disagree on their phase, and when another control-plane
instance holds the scheduler's Postgres advisory lock — a duplicated transition is survivable, a
duplicated purge is not something to leave to luck.

### Seeing what it did

```
GET  /api/scheduler/runs        what it did, and what it deliberately did not do
POST /api/scheduler/tick        reconcile now instead of waiting for the next tick
GET  /api/schedules/{name}/preview?date=2026-12-25
```

A skip is recorded when its **reason changes**, not on every tick — deduplicated against the audit
log itself, so it also survives a restart without re-logging conditions that were already there. A
halted security appears once, not every five seconds all weekend.

```
skipped  CONTINUOUS    no phase seen yet for AAPL, MSFT: send any session command once...
session  PRE_OPEN      sent=True  confirmed=True  every book on shard 0 reported PRE_OPEN
session  OPEN_AUCTION  sent=True  confirmed=True  every book on shard 0 reported OPEN_AUCTION
session  CONTINUOUS    sent=True  confirmed=True  every book on shard 0 reported CONTINUOUS
```

`CONTROL_SCHEDULER_ENABLED` (default `true`) and `CONTROL_SCHEDULER_INTERVAL_MS` (default `5000`)
configure it.

## 8. Getting started

```sh
docker run -d --name most-control-db -p 5432:5432 \
  -e POSTGRES_DB=most_control -e POSTGRES_USER=most -e POSTGRES_PASSWORD=most postgres:16-alpine

./gradlew :control:installDist
CONTROL_RELEASE_DIR=/var/lib/most/releases control/build/install/control/bin/control
```

Flyway migrates on startup. Environment: `CONTROL_DB_URL`, `CONTROL_DB_USER`, `CONTROL_DB_PASSWORD`,
`CONTROL_PORT`, `CONTROL_RELEASE_DIR`, `CONTROL_ADMIN_USER`, `CONTROL_ADMIN_PASSWORD`,
`CONTROL_COOKIE_SECURE`.

Set `CONTROL_ADMIN_PASSWORD` or read the generated one out of the first boot's log. Set
`CONTROL_COOKIE_SECURE=true` anywhere TLS is terminated in front of this — it is off by default only
so that the Vite dev server and the API can both be plain HTTP on localhost.

Bootstrapping from what you already run — every deployment has hand-written files, and retyping them
into an API is the obvious place to introduce the tick-size typo this module exists to prevent:

```sh
curl -u admin:... -X POST localhost:8080/api/shards -H 'Content-Type: application/json' -d '{
  "shardId": 0,
  "orderEntryChannel": "aeron:ipc", "orderEntryStreamId": 20,
  "executionReportChannel": "aeron:ipc", "executionReportStreamId": 21 }'

curl -u admin:... -X POST localhost:8080/api/import -H 'Content-Type: text/plain' \
  --data-binary @/etc/most-exchange/shard-0-securities.properties

curl -u admin:... -X POST 'localhost:8080/api/releases?note=imported'
```

The import parses through `ShardSpec.from`, so a file that would not have booted is not accepted,
and the fingerprint it reports is recomputed from what was *stored* — an import that silently lost a
field reports a different fingerprint rather than echoing the input's.

A shard security file carries no endpoints, so the shard must exist before importing into it.

## 9. Testing

`./gradlew :control:test` needs Docker: the tests run against a real Postgres via Testcontainers,
because half of what this module relies on is schema behaviour — `CHAR(12)` padding an ISIN on the
way out, a unique constraint catching a reused symbol, a foreign key refusing to orphan a security —
and none of that survives substitution.

`AuthBootstrapTest` is the one test here that runs a real Tomcat rather than MockMvc, and it exists
because MockMvc cannot see the defect it guards. `SecurityMockMvcRequestPostProcessors.csrf()` hands
every test a token unconditionally, so a filter chain that never issued a token to *anybody* would
pass the whole MockMvc suite while no real browser could ever log in. This is the project's recurring
lesson in a new place: a test double easier than reality validates nothing.

The test that carries the argument is in `SpecImporterTest`: it imports the shard security file
checked into `reference`, publishes it back out, and asserts the fingerprint is unchanged. Since the
fingerprint is what every process prints and what an operator compares across nodes, an equal
fingerprint over a round trip through Postgres is the strongest available statement that a generated
file is a drop-in replacement rather than a second dialect.
