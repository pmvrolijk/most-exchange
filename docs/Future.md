# Roadmap

## Near term
- [ ] Metrics — `most load` measures the client round trip and throughput; still no instrumentation
      inside the gateway or engine, so a latency cannot yet be split by stage
- [x] Referential data and topology in Postgres — the `control` module authors them and publishes
      the shard security files and discovery registry each process boots from (docs/ControlPlane.md)
- [ ] Single backend control plane (rest) — CRUD for shards, securities and participants is done;
      still no live cluster control (SecurityDefinition, SessionTransition, halt recovery) and no
      shard monitoring through the discovery feed
- [ ] Scheduling from control plane backend
- [ ] Store TimescaleDB ticks from trades, integrate with market-data, buckets, queries  
- [ ] Admin frontend for backend control plane, Vue

