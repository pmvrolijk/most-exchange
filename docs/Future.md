# Roadmap

## Near term
- [x] Metrics — the engine and gateway time their own hot paths, off by default and on in the dev
      stack; `e2e/run-attribution.sh` splits a client round trip into gateway, engine and
      everything else. First result: the engine owns 0.42 µs of a 55 µs round trip, and Design.md
      §2's 0.5 µs estimate holds (Design.md §2 "Measured", §7 "Instrumentation")
- [x] Duty cycle — every thread on the order path, Aeron's included, reports how much of its core it
      spends working; configurable idle strategies. On the laptop the knee is the core count
      (Design.md §7 "Duty cycle"; Measurements.md D1–D2; Handover §2k)
- [x] Referential data and topology in Postgres — the `control` module authors them and publishes
      the shard security files and discovery registry each process boots from (docs/ControlPlane.md)
- [x] Single backend control plane (rest) — CRUD for shards, securities and participants, plus
      live cluster control: operator commands, discovery and L3 monitoring, and halt recovery
      (docs/ControlPlane.md). Not yet: authentication, and a definition cannot be confirmed because
      nothing on any feed acknowledges one
- [x] Scheduling from control plane backend — session schedules with timezones, holidays and the
      daily purge, reconciled against the L3 feed rather than fired from timers (docs/ControlPlane.md)
- [x] Gateway high availability — `origQty` moved into the engine, the gateway's order journal
      deleted, and several stateless gateways can now serve one shard with disjoint client
      endpoints (docs/Design.md §3.1, docs/ProdDeployment.md §2)
- [x] Participant registry authored by the control plane and re-read by the nodes while they run,
      so onboarding a participant or rotating a gateway secret restarts a gateway and no node
      (docs/ControlPlane.md §3, docs/ProdDeployment.md §9.4)
- [x] Participant enforcement — at the gateway, before the log: `UNAUTHORIZED_PARTICIPANT` for
      orders and cancels, `cancelOnly` for graceful revocation, operator-only commands, several
      gateways per participant with a primary, anonymous sessions refused, and snapshot requests
      granted to operators. The gateway re-reads the registry too, so nothing restarts (docs/Design.md
      §1 "Enforcement, at the gateway", docs/Handover.md §2j)
- [x] Bulk cancel of one participant's resting orders — the operator side of revocation: one
      operator command, from the CLI, REST and the Operations page (docs/Design.md §4.8,
      docs/Handover.md §2m)
- [x] Configuration enforced through the log — the leader announces the shard and engine
      fingerprints at each term start and a node that disagrees refuses (docs/Design.md §7
      "Enforced through the log", docs/Handover.md §2m)
- [ ] Store TimescaleDB ticks from trades, integrate with market-data, buckets, queries  
- [ ] Admin frontend for backend control plane, Vue

