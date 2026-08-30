# The Control Plane

Reference data and shard topology are authored in Postgres, through a REST backend, and
**published** as the same `.properties` artifacts the engine, gateway, market-data and discovery
have always booted from.

This is the first slice of the control plane. It does not yet talk to a running cluster: sending
`SecurityDefinition` and `SessionTransition`, monitoring shards through the discovery feed, storing
ticks and scheduling are still ahead (`docs/Future.md`).

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
         price_floor, tick_size, level_count, max_orders)

participant(participant_id, name, smp_id, enabled)

spec_release(version, created_at, universe_version, directory, note)
spec_release_shard(version, shard_id, fingerprint)
```

Prices are fixed point with 8 implied decimals throughout, exactly as on the wire: `tick_size =
1000000` is 0.01.

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

## 6. Getting started

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

## 7. Testing

`./gradlew :control:test` needs Docker: the tests run against a real Postgres via Testcontainers,
because half of what this module relies on is schema behaviour — `CHAR(12)` padding an ISIN on the
way out, a unique constraint catching a reused symbol, a foreign key refusing to orphan a security —
and none of that survives substitution.

The test that carries the argument is in `SpecImporterTest`: it imports the shard security file
checked into `reference`, publishes it back out, and asserts the fingerprint is unchanged. Since the
fingerprint is what every process prints and what an operator compares across nodes, an equal
fingerprint over a round trip through Postgres is the strongest available statement that a generated
file is a drop-in replacement rather than a second dialect.
