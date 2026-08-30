# Roadmap

## Near term
- [ ] Metrics — `most load` measures the client round trip and throughput; still no instrumentation
      inside the gateway or engine, so a latency cannot yet be split by stage
- [x] Referential data and topology in Postgres — the `control` module authors them and publishes
      the shard security files and discovery registry each process boots from (docs/ControlPlane.md)
- [x] Single backend control plane (rest) — CRUD for shards, securities and participants, plus
      live cluster control: operator commands, discovery and L3 monitoring, and halt recovery
      (docs/ControlPlane.md). Not yet: authentication, and a definition cannot be confirmed because
      nothing on any feed acknowledges one
- [ ] Scheduling from control plane backend
- [ ] Store TimescaleDB ticks from trades, integrate with market-data, buckets, queries  
- [ ] Admin frontend for backend control plane, Vue

