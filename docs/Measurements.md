# Measurements

Every performance figure that reaches a document gets a row here first. A number without its
conditions cannot be compared against the next one, and a regression is then indistinguishable from
a change of machine — the repo already carries figures from three different machines under three
different loads, each individually honest and collectively incomparable.

The `perf-claim` skill (`.claude/skills/perf-claim/`) is the procedure. In short:

- **Report service time and response time.** Quoting only service time is coordinated omission.
- **Check `pacing lateness` before believing anything.** If it is large the run measured the
  harness, not the shard.
- **Check the reject counts.** A `maxOrders` too small for the rate, or a band outside the static
  collar or the ladder, silently invalidates a run.
- **State the scope.** Everything to date is single-node and one security.

---

## Runs

| # | date | commit | machine | cores | idle | build | sec | target | achieved | service p50 | service p90 | service p99 | response p50 | response p99 | pacing p99.9 | unans. | dropped |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R1a | 2026-09-06 | `03a2545` | Apple M4 Pro, macOS 15.7.7 | 14 (10P+4E) | no | JVM 21.0.11 | 1 | 50k/s | 50,000/s | 39.8 µs | 98.5 µs | 2.62 ms | 39.8 µs | 2.62 ms | 10.8 µs | 0 | 0 |
| R1b | 2026-09-06 | `03a2545` | Apple M4 Pro, macOS 15.7.7 | 14 (10P+4E) | no | JVM 21.0.11 | 1 | 100k/s | 99,999/s | 49.5 µs | 110.8 µs | 554 µs | 49.6 µs | 554 µs | 12.5 µs | 0 | 0 |
| R1c | 2026-09-06 | `03a2545` | Apple M4 Pro, macOS 15.7.7 | 14 (10P+4E) | no | JVM 21.0.11 | 1 | 200k/s | 199,997/s | 72.5 µs | 224.4 µs | 1.15 ms | 72.5 µs | 1.15 ms | 18.4 µs | 1 | 0 |
| R1d | 2026-09-06 | `03a2545` | Apple M4 Pro, macOS 15.7.7 | 14 (10P+4E) | no | JVM 21.0.11 | 1 | 333k/s | 333,324/s | 29.3 ms | 39.7 ms | 43.6 ms | 29.3 ms | 43.6 ms | 33.6 µs | 1 | 0 |

**R1 conditions.** Single node, Aeron IPC throughout, JVM start scripts from `./gradlew installDist`.
One security (AAPL), `maxOrders=1000000`, `levelCount=32768`, `tickSize=0.01`. Band 99.90–100.10
inside a 5000 bps static collar; qty 1–10; `--participants 4`. 300,000 orders per rate, 30,000
discarded as warmup by the harness. **`engine.metrics=false` and `gateway.metrics=false`** — this
measures the client round trip, not the instrumented path. A 200,000-order discard pass at 100k/s
ran before the sweep; see "the first run is not a measurement" below. Machine was **not idle**:
load average 6.7–8.0 with an IDE and a browser running, Docker dev stack stopped.

Measured path: `client → gateway → cluster consensus → engine → gateway → client`. Reject counts
were zero at every rate, so no run was invalidated by capacity or band.

---

## What R1 says

**The knee is between 200k and 333k orders/s**, which agrees with the sweep already documented in
`LocalTesting.md` §9 on a different machine. Below it the shard tracks its target rate exactly and
p50 stays double-digit microseconds; above it, latency collapses by a factor of 400 while the
generator still keeps up. The collapse is abrupt rather than gradual, which is the useful part of
the shape: there is no gentle degradation to detect in production before it becomes a stall.

**The design's 100k/s/security target has roughly 2x headroom on this path** — for one book. That
qualifier is the whole caveat: the target is 100k/s across **ten** securities, 1M/s per shard, and
that has never been run. See `Status.md` §2 item 13.

**It collapsed into queueing, not loss.** At 333k/s p50 is 29 ms with **one** unanswered order and
nothing dropped, where the run recorded in `LocalTesting.md` §9 lost 134,271 orders at the same
rate. Same knee, different failure mode on the other side of it — worth knowing before treating
either as the expected behaviour.

**p99 at 50k/s is worse than at 100k/s** (2.62 ms against 554 µs) while p50 is better. That is not
noise: it is the same fixed-per-wakeup-cost-amortised-by-batching shape `LocalTesting.md` §9 already
records for the Docker stack, now visible on the host at the tail. A slower arrival rate means fewer
messages per wakeup and less amortisation, so the median improves and the tail does not.

**Service and response time agree at every rate.** The generator never fell behind, so there is no
coordinated omission hiding in these numbers — which is exactly what `pacing lateness` staying under
40 µs at p99.9 is there to establish.

### The first run is not a measurement

The first attempt at this sweep put 50k/s first in a cold JVM and got **p99 18.9 ms, p99.9 42.4 ms**
— seven times worse than the same rate produced minutes later. The tell was in the harness, not the
shard: `pacing lateness` p99.9 of 10.8 ms and a 14.3 ms max, against ~0 for every subsequent rate.
The generator itself stalled while it was still being JIT-compiled, so the run measured the load
tool warming up.

**Every sweep needs a discard pass before the first measured rate**, or the first rate in the list
silently carries the cost and looks like a property of that rate. The discarded 50k/s row is not in
the table above; it is recorded here so the next person who sees a bad first row knows what it is.

---

## Adding a row

```sh
./gradlew installDist && ./e2e/run-sweep.sh
```

`e2e/run-sweep.sh` runs the discard pass, sweeps the rates, refuses to report a rate whose pacing
lateness or reject count says it measured something other than the shard, and prints a row block to
paste in here. R1 above was produced by the harness that became that script.

1. Stop anything competing for cores, and fill in the `idle` column honestly either way — the
   script cannot see your desktop, which is why it leaves that column as `?`.
2. Paste the block, number the rows, and keep a conditions paragraph beside them.
3. If the run is meant to show an improvement, diff the `.hgrm` files from
   `e2e/run-attribution.sh` rather than comparing against a remembered number.

Not yet recorded here, and worth a row when they happen: a native-binary sweep on x86-64, the
ten-security aggregate, a multi-node cluster, and an Epsilon soak measured in hours.
