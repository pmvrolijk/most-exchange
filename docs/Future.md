# Roadmap

## Near term
- [ ] Metrics — `most load` measures the client round trip and throughput; still no instrumentation
      inside the gateway or engine, so a latency cannot yet be split by stage
- [ ] Referential data and topology managed through a Postgres database in Reference.
- [ ] Single backend control plane (rest) to manage the exchange, participants, securities and shards
- [ ] Scheduling from control plane backend
- [ ] Store TimescaleDB ticks from trades, integrate with market-data, buckets, queries  
- [ ] Admin frontend for backend control plane, Vue

