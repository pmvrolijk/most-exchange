# Dev stack

The whole system on one machine: the control plane with its database and admin UI, and one complete
shard — cluster host, engine, gateway, market data and discovery — with a market open and ready to
take orders.

```sh
./gradlew installDist          # the images copy host builds; see "How the images are built"
cd deploy
docker compose up -d           # ~40s to a trading market

./most securities
./most send --symbol AAPL --side sell --price 100.00 --qty 10 --clordid 1 --participant 7 --follow 2
./most send --symbol AAPL --side buy  --price 100.00 --qty 4  --clordid 2 --participant 8 --follow 2
```

| | |
| --- | --- |
| Admin UI | <http://localhost:8081> — `admin` / `most-dev-password` |
| Control API | <http://localhost:8080> — `curl -u admin:most-dev-password localhost:8080/api/status` |
| Teardown | `docker compose down -v` |

`docker compose up` also runs a one-shot `seed` container that creates shard 0 in the control plane,
imports the same security file the processes booted from, publishes a release, seeds the reference
prices and walks the session to `CONTINUOUS`. It does all of that over the REST API, so
[`init/seed.sh`](init/seed.sh) doubles as a worked example of driving the exchange the supported way.

## The network layout, and why it is drawn this way

```
                    ┌───────── edge ─────────┐         published ports only
   browser ────────▶│  web (nginx)  :8081    │
   curl ───────────▶│  control      :8080    │
                    └───────────┬────────────┘
                                │
   ┌──────── data (internal) ───┴───┐    ┌──────────── aeron (internal) ───────────────┐
   │  postgres ◀──▶ control         │    │                                             │
   └────────────────────┬───────────┘    │   cluster-host  ← driver + archive          │
                        │                │        │          + consensus module        │
                        └────────────────┼────────┤          alias: shard0             │
                                         │        │                                    │
                         control-driver ─┤   shared /dev/shm (tmpfs volume)            │
                          (its own       │        ├── engine        (IPC 12 ──▶ md)    │
                           media driver) │        ├── market-data                      │
                                         │        ├── gateway                          │
                                         │        └── discovery                        │
                                         │                                             │
                                         │   [ later ] fix-adapter ──── also on edge,  │
                                         │             own media driver, TCP exposed   │
                                         └─────────────────────────────────────────────┘
```

**`aeron` and `data` are `internal: true`.** Nothing on them can reach or be reached from outside the
host. The only way in is a container that deliberately joins `edge` as well — today the control
plane and the SPA, tomorrow a FIX adapter. That is the property worth having before adapters exist
rather than after.

**The shard shares one media driver; everything else brings its own.** The five shard processes map
the same tmpfs volume, exactly as five processes on one node share `/dev/shm`, and the engine's book
event stream to market data stays on IPC where it belongs. The control plane does *not* — it has a
media driver of its own and reaches the shard over UDP, which is precisely the role a FIX adapter
will play. That seam is the reason the control plane is not simply given the shard's volume: this
way, the pattern an adapter needs is exercised every time the stack starts.

### One media driver is one network identity

This one is not obvious and it is worth knowing before you add a service.

Every Aeron endpoint a shard process asks for is bound by the **media driver**, which runs in the
`cluster-host` container. A driver can only bind addresses its own container owns, so
`gateway.client.outbound.channel` cannot say `control=gateway:20002` — the gateway container's
address means nothing to the driver that has to bind it. It says `control=shard0:20002`, and
`shard0` is a network alias on `cluster-host`.

So: **the shard has one address for all of its Aeron traffic** — order entry, execution reports,
L1/L2/L3 and the directory alike — and the port is what distinguishes them. That is not a Docker
quirk to work around; it is what "these five processes are one node" means once the processes are in
separate containers.

| Port on `shard0` | Carries |
| --- | --- |
| 20001 | Order entry. The gateway subscribes; adapters publish to it. |
| 20002 | Execution reports (MDC control address). |
| 40000 | The directory broadcast. |
| 40001 / 40002 / 40003 | L1 / L2 / L3. |
| 20110 / 20220 / 20330 / 20440 / 8010 | Cluster ingress, consensus, log, catchup, archive control. |

### Dynamic MDC instead of multicast

Production publishes market data, execution reports and the directory as **multicast**, and must
keep `MaxMulticastFlowControl` so the fastest receiver governs (Design.md §5). A Docker bridge does
not route multicast, so the dev stack uses **dynamic MDC** instead: subscribers announce themselves
on a control address and the publisher adds a destination per subscriber. Unicast fan-out, same
shape, no multicast routing.

This is the one place the dev stack deliberately differs from production, and it shows up as an
asymmetry that is easy to misread as a mistake:

```properties
# what the gateway PUBLISHES on -- an MDC publication must not name an endpoint
gateway.client.outbound.channel=aeron:udp?control=shard0:20002|control-mode=dynamic

# what discovery tells a SUBSCRIBER to use -- the same control address, plus one of its own
discovery.shard.0.executionReportChannel=aeron:udp?endpoint=0.0.0.0:0|control=shard0:20002|control-mode=dynamic
```

`endpoint=0.0.0.0:0` lets Aeron pick the port and announce it over the control channel, which is
what lets a client join without being configured into anything on the shard side.

## Adding the FIX adapters

Everything a FIX↔SBE adapter needs is already in place. It joins `aeron` and `edge`, brings a media
driver of its own the way `control-driver` does, and publishes its FIX listener on `edge`:

```yaml
  fix-adapter:
    build: { context: .., dockerfile: deploy/Dockerfile.fix }
    depends_on:
      fix-driver: { condition: service_healthy }
    volumes: [fix-aeron:/aeron]
    networks: [aeron, edge]
    ports: ["9876:9876"]        # FIX TCP, the only thing exposed
```

It learns which shard serves which symbol from the directory broadcast on `shard0:40000` rather than
being configured with it — `DirectoryClient` in `reference` is what it embeds. It must talk to the
**gateway's** endpoints and never to the cluster ingress: connecting to the cluster directly bypasses
the validation and `cumQty` reconstruction the gateway exists to perform.

`deploy/most` is the working reference for what such a client's connection flags look like.

## How the images are built

| Image | Contents | Runtime |
| --- | --- | --- |
| `Dockerfile.core` | engine, gateway, market-data, discovery | JVM by default, native opt-in |
| `Dockerfile.tools` | the `most` CLI, the media driver, the cluster host | always JVM |
| `Dockerfile.control` | the Spring Boot control plane | JVM |
| `Dockerfile.web` | the SPA, built with Vite and served by nginx | nginx |

The JVM images **copy `build/install` from the host**, so `./gradlew installDist` is a prerequisite.
That keeps the edit-run loop in seconds, which matters when what you are iterating on is Aeron
channel configuration.

**The media driver stays on the JVM deliberately.** It allocates by design, which is exactly why it
is kept out of the engine process (Design.md §7); compiling it under the engine's constraints would
be solving a problem it does not have.

### The native target has never been run

```sh
CORE_TARGET=native docker compose build engine
```

`Dockerfile.core` has a GraalVM builder stage and every core module now registers a `nativeCompile`
task, but **no native image of this system has ever been built**, in Docker or out of it. Expect
reflection-config gaps around Aeron and Agrona the first time, and expect it to be slow. It is
opt-in for that reason — the dev stack should not be blocked on an unproven build.

`ENGINE_MARCH` is pinned to `x86-64-v3` rather than `native`: a container image is by definition a
binary built somewhere else, and a `-march=native` build SIGILLs when the CPU differs.

## What is dev-only

Do not carry these into anything real:

- **One cluster node.** No redundancy; failover, leader election and snapshot recovery are exactly
  what a single node cannot exercise.
- **A fixed, published admin password.** Unset `CONTROL_ADMIN_PASSWORD` and the control plane
  generates one and logs it.
- **`CONTROL_COOKIE_SECURE` is false**, because everything here is plain HTTP on localhost.
- **Dynamic MDC in place of multicast**, as above.
- **The scheduler is off** (`CONTROL_SCHEDULER_ENABLED=false`). It opens markets unattended, which
  is a surprising thing for a dev stack to do while you are reading its logs. Turn it on to exercise
  it.
- **Ephemeral cluster and archive data.** `most cluster` deletes its directories on start unless
  given `--keep`.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `could not create archive directory` | A named volume landed root-owned. The mount points are created and chowned in the images so the volumes inherit that; `docker compose down -v` and rebuild. |
| `channel error - Cannot assign requested address` | A shard channel naming a container other than `shard0`. The driver binds these and it lives in `cluster-host`. |
| A book viewer shows nothing | A live feed has no replay — a subscriber sees only updates after it joined. There is no market data snapshot yet (handover §4, open issue 2). Send an order and it appears. |
| `most` hangs | It waits for the directory broadcast. Check `discovery` is up and that the control address matches. |
| `/api/status` shows `routingDrift` | The channels in the database and the ones discovery broadcasts disagree. They are set in [`init/seed.sh`](init/seed.sh) and [`config/discovery.properties`](config/discovery.properties) and must match exactly. |

Logs are per service: `docker compose logs -f engine`, and `docker compose logs seed` for what the
one-shot seeding actually did.
