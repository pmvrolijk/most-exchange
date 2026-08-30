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

spec_release(version, created_at, universe_version, directory, note)
spec_release_shard(version, shard_id, fingerprint)

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

**Participants are authored but not yet enforced.** The engine has an `UNAUTHORIZED_PARTICIPANT`
reject reason that nothing currently raises, and binding a participant to an authenticated session
is an open item (`docs/Design.md` §8). The table is where that binding will be looked up.

**One duplication this removes.** A gateway's client endpoints are declared today in
`gateway.properties` *and* again in `discovery.properties`, with nothing checking they agree. They
are one `shard` row here, rendered into both files, so they cannot disagree.

## 4. REST surface

```
GET  POST                 /api/shards           GET PUT DELETE /api/shards/{id}
GET  POST                 /api/securities       GET PUT DELETE /api/securities/{id}
GET  POST                 /api/participants     GET PUT DELETE /api/participants/{id}

GET                       /api/topology         the draft, its fingerprints, and what is wrong
POST                      /api/import           seed from an existing shard security file

POST                      /api/releases         validate, freeze, write the artifacts
GET                       /api/releases         /api/releases/{version}   /api/releases/latest
GET                       /api/releases/{v}/files/{name}
```

A refusal carries the domain's own sentence, because that is the same sentence a process would have
printed at boot:

```
$ curl -X POST .../api/securities -d '{"securityId":1,"isin":"US0378331006",...}'
{"error":"invalid","message":"invalid ISIN for security 1: US0378331006"}
```

`GET /api/topology` reports **every** problem rather than the first — a half-built topology is the
normal state while someone is editing it — and `problems` being empty is exactly the condition for
publishing, so one call answers both "can I publish?" and "what is stopping me?".

## 5. Publishing, and what a release is

`POST /api/releases` builds every `ShardSpec` and the `Universe` from the current rows — which is the
validation — then writes a numbered directory and records the fingerprint per shard.

A release is **immutable**. Republishing allocates the next version rather than rewriting one, so a
directory a running process was pointed at never changes underneath it. The rendered files are
deterministic: ordered by id, no timestamp, so two publishes of identical content produce identical
bytes and diffing two releases tells an operator something.

**A release carries topology, not deployment.** Node-local settings — the Aeron directory, the
cluster directory, the discovery channel and interval, the book-event stream — stay in each
process's own config. A deployment composes the two:

```sh
REL=/var/lib/most/releases/000007

# engine.properties, gateway.properties, market-data.properties
engine.securitiesFile=$REL/shard-0-securities.properties

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
`CONTROL_PORT`, `CONTROL_RELEASE_DIR`.

Bootstrapping from what you already run — every deployment has hand-written files, and retyping them
into an API is the obvious place to introduce the tick-size typo this module exists to prevent:

```sh
curl -X POST localhost:8080/api/shards -H 'Content-Type: application/json' -d '{
  "shardId": 0,
  "orderEntryChannel": "aeron:ipc", "orderEntryStreamId": 20,
  "executionReportChannel": "aeron:ipc", "executionReportStreamId": 21 }'

curl -X POST localhost:8080/api/import -H 'Content-Type: text/plain' \
  --data-binary @/etc/most-exchange/shard-0-securities.properties

curl -X POST 'localhost:8080/api/releases?note=imported'
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

The test that carries the argument is in `SpecImporterTest`: it imports the shard security file
checked into `reference`, publishes it back out, and asserts the fingerprint is unchanged. Since the
fingerprint is what every process prints and what an operator compares across nodes, an equal
fingerprint over a round trip through Postgres is the strongest available statement that a generated
file is a drop-in replacement rather than a second dialect.
