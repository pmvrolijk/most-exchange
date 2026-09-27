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
- **State the scope.** Everything to date is single-node. R1 is one security; R2–R11 are ten.
- **A counter cannot see a full thread, and `ps` cannot see a busy-spinning one.** Aeron reports
  queues, positions and duty-cycle breaches, so a stage that is merely full breaches none of them; and
  `engine`, `gateway` and `market-data` all busy-spin, so their ~100% CPU carries no information about
  utilisation. Read their own histograms instead — see "What C1–C2 say".
- **Say how many cores the shard had, and what else was spinning.** On the 10P+4E laptop the knee is
  where busy threads outnumber performance cores (D1–D2), so a knee is a property of the machine's
  core count before it is one of the design. Freeing two spinning cores moved it one rate step.
- **Say which driver threading mode.** `SHARED` (the default) and `DEDICATED` differ by 1.6x in
  throughput and 81x in p50 at the edge. A figure without it is not comparable — see "What R7 says".
- **Fill in the `idle` column honestly, and take a same-day baseline.** R2–R4's knee was 15% low
  purely because the desktop was open, and that wrong figure reached four documents before R5 caught
  it. A comparison between two configurations is only a measurement if both were taken under the same
  load, on the same day.

---

## Runs

| # | date | commit | machine | cores | idle | build | sec | target | achieved | service p50 | service p90 | service p99 | response p50 | response p99 | pacing p99.9 | unans. | dropped |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R1a | 2026-09-06 | `03a2545` | Apple M4 Pro, macOS 15.7.7 | 14 (10P+4E) | no | JVM 21.0.11 | 1 | 50k/s | 50,000/s | 39.8 µs | 98.5 µs | 2.62 ms | 39.8 µs | 2.62 ms | 10.8 µs | 0 | 0 |
| R1b | 2026-09-06 | `03a2545` | Apple M4 Pro, macOS 15.7.7 | 14 (10P+4E) | no | JVM 21.0.11 | 1 | 100k/s | 99,999/s | 49.5 µs | 110.8 µs | 554 µs | 49.6 µs | 554 µs | 12.5 µs | 0 | 0 |
| R1c | 2026-09-06 | `03a2545` | Apple M4 Pro, macOS 15.7.7 | 14 (10P+4E) | no | JVM 21.0.11 | 1 | 200k/s | 199,997/s | 72.5 µs | 224.4 µs | 1.15 ms | 72.5 µs | 1.15 ms | 18.4 µs | 1 | 0 |
| R1d | 2026-09-06 | `03a2545` | Apple M4 Pro, macOS 15.7.7 | 14 (10P+4E) | no | JVM 21.0.11 | 1 | 333k/s | 333,324/s | 29.3 ms | 39.7 ms | 43.6 ms | 29.3 ms | 43.6 ms | 33.6 µs | 1 | 0 |
| R2a | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 200k/s | 199,998/s | 96.6 µs | 236.7 µs | 1.63 ms | 96.8 µs | 1.63 ms | 20.8 µs | 0 | 0 |
| R2b | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 500k/s | 499,987/s | 283 ms | 333 ms | 341 ms | 283 ms | 341 ms | 28.8 µs | 0 | 0 |
| R2c | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 800k/s | 799,974/s | 406 ms | 493 ms | 509 ms | 406 ms | 509 ms | 67.8 µs | 0 | 0 |
| R2d | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 1M/s | 999,940/s | 462 ms | 589 ms | 620 ms | 462 ms | 620 ms | 64.3 µs | 0 | 0 |
| R3a | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 200k/s | 199,998/s | 109.3 µs | 257.4 µs | 1.10 ms | 109.5 µs | 1.10 ms | 27.0 µs | 0 | 0 |
| R3b | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 250k/s | 249,997/s | 132.9 µs | 267.5 µs | 844 µs | 133.1 µs | 844 µs | 26.4 µs | 0 | 0 |
| R3c | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 300k/s | 300,026/s | 380.4 µs | 2.91 ms | 5.51 ms | 380.4 µs | 5.51 ms | 30.7 µs | 0 | 0 |
| R3d | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 350k/s | 350,010/s | 35.8 ms | 67.7 ms | 75.4 ms | 35.8 ms | 75.4 ms | 150.7 µs | 0 | 0 |
| R3e | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 400k/s | 399,992/s | 170 ms | 235 ms | 247 ms | 170 ms | 247 ms | 32.0 µs | 0 | 0 |
| R4a | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 250k/s | 249,997/s | 200.4 µs | 1.09 ms | 2.48 ms | 200.6 µs | 2.49 ms | 21.6 µs | 0 | 0 |
| R4b | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 300k/s | 300,024/s | 359.7 µs | 1.31 ms | 2.66 ms | 359.9 µs | 2.66 ms | 25.6 µs | 0 | 0 |
| R4c | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | no | JVM 21.0.11 | 10 | 350k/s | 350,009/s | 57.5 ms | 80.8 ms | 92.8 ms | 57.5 ms | 92.8 ms | 33.7 µs | 0 | 0 |
| R5a | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 250k/s | 249,996/s | 60.6 µs | 371.7 µs | 2.86 ms | 60.7 µs | 2.86 ms | 7.4 µs | 0 | 0 |
| R5b | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 300k/s | 300,024/s | 75.8 µs | 603.1 µs | 1.25 ms | 76.0 µs | 1.25 ms | 8.6 µs | 0 | 0 |
| R5c | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 350k/s | 350,011/s | 392.2 µs | 1.39 ms | 2.51 ms | 392.2 µs | 2.51 ms | 9.4 µs | 0 | 0 |
| R5d | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 365k/s | 365,090/s | 28.0 ms | 35.5 ms | 38.1 ms | 28.0 ms | 38.1 ms | 10.5 µs | 0 | 0 |
| R5e | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 400k/s | 399,991/s | 94.2 ms | 130 ms | 136 ms | 94.2 ms | 136 ms | 15.3 µs | 0 | 0 |
| R5f | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 700k/s | 700,264/s | 298 ms | 324 ms | 328 ms | 298 ms | 328 ms | 13.6 µs | 0 | 0 |
| R6a | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 250k/s | 249,997/s | 58.8 µs | 114.8 µs | 718 µs | 58.9 µs | 718 µs | 7.3 µs | 0 | 0 |
| R6b | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 300k/s | 300,025/s | 73.2 µs | 279.6 µs | 698 µs | 73.3 µs | 698 µs | 8.4 µs | 0 | 0 |
| R6c | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 350k/s | 350,010/s | 701.4 µs | 2.84 ms | 5.89 ms | 701.4 µs | 5.89 ms | 9.3 µs | 0 | 0 |
| R6d | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 400k/s | 399,991/s | 85.0 ms | 126 ms | 135 ms | 85.0 ms | 135 ms | 10.7 µs | 0 | 0 |
| R6e | 2026-09-25 | `b537590`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 700k/s | 700,252/s | 299 ms | 317 ms | 319 ms | 299 ms | 319 ms | 16.5 µs | 0 | 0 |
| R8a | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 450k/s | 450,034/s | 98.9 µs | 285.7 µs | 1.09 ms | 98.9 µs | 1.09 ms | 14.2 µs | 0 | 0 |
| R8b | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 500k/s | 499,985/s | 121.7 µs | 1.68 ms | 8.69 ms | 121.7 µs | 8.69 ms | 15.8 µs | 1 | 0 |
| R8c | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 550k/s | 550,039/s | 2.30 ms | 7.47 ms | 8.64 ms | 2.30 ms | 8.64 ms | 22.3 µs | 1 | 0 |
| R8d | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 600k/s | 600,223/s | 48.9 ms | 63.9 ms | 66.0 ms | 48.9 ms | 66.0 ms | 16.1 µs | 1 | 0 |
| R8e | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 650k/s | 650,156/s | 75.4 ms | 102 ms | 110 ms | 75.4 ms | 110 ms | 21.1 µs | 0 | 0 |
| R8f | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 700k/s | 700,253/s | 102 ms | 131 ms | 137 ms | 102 ms | 137 ms | 17.5 µs | 0 | 0 |
| R9a | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 450k/s | 450,037/s | 574.5 µs | 3.26 ms | 4.84 ms | 574.5 µs | 4.84 ms | 61.3 µs | 1 | 0 |
| R9b | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 500k/s | 499,991/s | 22.7 ms | 33.3 ms | 36.0 ms | 22.7 ms | 36.0 ms | 74.9 µs | 0 | 0 |
| R9c | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 550k/s | 550,040/s | 75.4 ms | 90.0 ms | 95.3 ms | 75.4 ms | 95.3 ms | 198.7 µs | 0 | 0 |
| R9d | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 600k/s | 600,045/s | 53.3 ms | 81.8 ms | 87.6 ms | 53.3 ms | 87.6 ms | 88.2 µs | 1 | 0 |
| R9e | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 650k/s | 650,179/s | 95.6 ms | 106 ms | 112 ms | 95.6 ms | 112 ms | 72.5 µs | 0 | 0 |
| R9f | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 700k/s | 700,015/s | 110 ms | 153 ms | 158 ms | 110 ms | 158 ms | 124.0 µs | 0 | 0 |
| R10a | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 450k/s | 450,037/s | 9.95 ms | 21.9 ms | 31.1 ms | 9.95 ms | 31.1 ms | 96.8 µs | 0 | 0 |
| R10b | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 500k/s | 499,988/s | 8.35 ms | 15.4 ms | 23.8 ms | 8.35 ms | 23.8 ms | 102.3 µs | 0 | 0 |
| R10c | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 550k/s | 550,046/s | 16.8 ms | 22.2 ms | 26.2 ms | 16.8 ms | 26.2 ms | 76.4 µs | 0 | 0 |
| R10d | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 600k/s | 600,046/s | 41.1 ms | 47.5 ms | 52.1 ms | 41.1 ms | 52.1 ms | 281.3 µs | 0 | 0 |
| R10e | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 650k/s | 650,177/s | 84.1 ms | 107 ms | 116 ms | 84.1 ms | 116 ms | 105.6 µs | 0 | 0 |
| R10f | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 700k/s | 700,016/s | 107 ms | 132 ms | 140 ms | 107 ms | 140 ms | 814.1 µs | 0 | 0 |
| R11a | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 450k/s | 450,034/s | 93.4 µs | 225.3 µs | 1.73 ms | 93.5 µs | 1.74 ms | 22.6 µs | 0 | 0 |
| R11b | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 500k/s | 499,986/s | 161.9 µs | 1.30 ms | 2.16 ms | 162.0 µs | 2.16 ms | 14.4 µs | 1 | 0 |
| R11c | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 550k/s | 550,039/s | 26.4 ms | 33.5 ms | 35.4 ms | 26.4 ms | 35.4 ms | 14.8 µs | 1 | 0 |
| R11d | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 600k/s | 600,226/s | 26.9 ms | 43.5 ms | 49.0 ms | 26.9 ms | 49.0 ms | 24.7 µs | 1 | 0 |
| R11e | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 650k/s | 650,175/s | 74.3 ms | 112 ms | 119 ms | 74.3 ms | 119 ms | 30.5 µs | 0 | 0 |
| R11f | 2026-09-27 | `ba5c605`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11 | 10 | 700k/s | 700,254/s | 106 ms | 139 ms | 146 ms | 106 ms | 146 ms | 16.5 µs | 0 | 0 |

**R1 conditions.** Single node, Aeron IPC throughout, JVM start scripts from `./gradlew installDist`.
One security (AAPL), `maxOrders=1000000`, `levelCount=32768`, `tickSize=0.01`. Band 99.90–100.10
inside a 5000 bps static collar; qty 1–10; `--participants 4`. 300,000 orders per rate, 30,000
discarded as warmup by the harness. **`engine.metrics=false` and `gateway.metrics=false`** — this
measures the client round trip, not the instrumented path. A 200,000-order discard pass at 100k/s
ran before the sweep; see "the first run is not a measurement" below. Machine was **not idle**:
load average 6.7–8.0 with an IDE and a browser running, Docker dev stack stopped.

Measured path: `client → gateway → cluster consensus → engine → gateway → client`. Reject counts
were zero at every rate, so no run was invalidated by capacity or band.

**R2–R4 conditions.** The **ten-security aggregate**, which is the number Design.md §2 actually
claims and which nothing had ever driven (Status.md §2 item 13, now closed by these rows).
The commit reads `b537590`+ because the build is that commit plus this session's uncommitted
changes — the `SECURITIES` knob these runs needed, and a control-plane gap-counting fix that is not
on the measured path. `SECURITIES=10 e2e/run-sweep.sh`: ten securities on one shard — AAPL, MSFT, GOOGL, AMZN, NVDA, META,
TSLA, JPM, JNJ, XOM — each `maxOrders=1000000`, `levelCount=32768`, `tickSize=0.01`, reference
100.00, 5000 bps static collar. **The `target` column is the aggregate across all ten**, so R2d is
the design's 100k/s/security at full fan-out and R4b is 30k/s/security. Orders are drawn uniformly
over the ten securities by `most load --symbol A,B,…`. Single node, Aeron IPC, JVM start scripts,
metrics off, 400–500k orders per rate, 300,000-order discard pass at 100k/s first. Machine **not
idle**: load average 5.7–7.8, a browser taking ~1.5 cores throughout. Reject counts, unanswered
orders and generator-side drops were **zero at every rate**, and `pacing lateness` p99.9 stayed
under 155 µs, so no row measured the harness.

R2 sweeps the top end, R3 narrows the knee, R4 repeats the bracket on a fresh cluster.

---

## What R2–R4 say

> **Superseded on one point, by R5.** The knee figure below — 300k–350k/s — was measured on a machine
> that was not idle, and it is too low. On the same build with the desktop closed the shard sustains
> 350k/s and breaks at 365k/s (R5). **The conclusion is unchanged and the number moved by ~15%**: read
> the knee from R5, and read R2–R4 as what a shard does when it is sharing its cores. That the `idle`
> column is worth filling in honestly is the lesson, and it cost a wrong figure in four documents to
> learn.

**The shard's ceiling is aggregate, not per-security.** The knee sits between **300k/s and 350k/s
across ten securities** (R3c/R3d, reproduced by R4b/R4c) — which is the same aggregate rate at which
*one* security broke on this machine (R1c/R1d, 200k–333k). Ten books did not buy ten times the
throughput; they bought roughly none. Per security the ceiling is therefore ~30k/s, not the 100k/s
the design targets, and **the design's 1M/s/shard claim is over-stated by about 3x on this path.**

That is not a matching problem. `run-attribution.sh` put the exchange's own code at 1.4% of a round
trip (Handover §2c), the engine finished these runs with `droppedBookEvents=0` and
`backpressureStalls=0`, and the per-book work is what fan-out divides. What fan-out does *not*
divide is the shared path every order crosses regardless of which book it lands in — one cluster
ingress, one consensus module, one archive write, one log, one service thread. These rows say that
path, not the books, is the ceiling, and they say it without needing an attribution run to guess.

**Above the knee it queues; it does not lose.** At 1M/s aggregate — 3x the rate the shard can serve
— every one of 500,000 orders was answered, nothing was rejected and nothing was dropped. Latency
went to 462 ms p50 instead. `achieved` above the knee is the **offer** rate, not a throughput the
shard sustained: the generator emptied its schedule into a buffer and the acknowledgements arrived
half a second later. `run-sweep.sh` now marks such a row `SATURATED` for exactly that reason — it is
a queue draining, not a round trip, and it must never be quoted as a latency.

**Execution report loss was four orders of magnitude lower than the run behind open issue 1.** R4's
gateway stopped with `droppedToClient=6` of `sentToClient=3,264,416` — 0.0002% — where the sweep
recorded against issue 1 lost 267,853 of 3.26M reports (8.2%) at a comparable report volume.
`clusterBackpressure=11`, `rejectedLocally=0`, `untrackedReports=0`. This is the second run to land
on the **queueing** side of that knee rather than the dropping side, after R1d, which strengthens
the machine-dependence already noted in Status.md §2 item 1 rather than resolving it: the remedy
still needs deciding, but the drop is not reproducible here.

**Ignore market-data's `droppedL1/L2/L3` counters in these runs.** The sweep attaches no depth
subscriber, so every feed offer returns `NOT_CONNECTED` and is counted as a drop —
`droppedL3=2,367,241` in R4 is an artefact of the sweep's topology, not loss. `gaps=0 missed=0
foreignShard=0` are the counters that mean something there, and they were clean.

### What this does not say

The machine was not idle and this is still **one node**. A browser taking 1.5 of 14 cores cannot
explain a 3x shortfall against the design, but it can move a knee by tens of thousands of orders
per second, so treat 300k/s as the shape and not the constant. The next measurement worth having is
`run-attribution.sh` at ten securities: the same subtraction that produced the 1.4% figure will say
how much of the shared path is consensus and how much is the archive write, and that is the
difference between a tuning problem and a sharding one.

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

## What R5–R6 say: the knee on an idle machine, and where it is not

R5 repeats the fan-out sweep with IntelliJ, Firefox and Docker Desktop closed (load average 3.4
against R2–R4's 5.7–7.8). R6 repeats it with the **consensus log and archive on a 4 GB APFS RAM
disk**, leaving the Aeron media-driver buffers on the SSD — the experiment that "What R2–R4 say"
listed as the cheapest way to find out whether the archive write is the ceiling.

**R5 conditions.** As R2–R4 except: machine **idle**, `SECURITIES=10`, 400,000 orders per rate,
rates from three sweeps (250/300/350, 350/365/380/395, 400/500/600/700) against one cluster each.
**R6 conditions.** Identical to R5 plus `CLUSTER_HOST=/Volumes/ramdisk/cluster-host`, an APFS RAM
disk chosen over the default HFS+ so the filesystem is not a second variable.

**The knee is ~350k/s aggregate, not 300k–350k.** Comfortable at 250k (60.6 µs) and 300k (75.8 µs),
**marginal at 350k**, and gone by 365k (28 ms). 350k was measured three times and came out 392 µs,
701 µs and 1.66 ms — an order of magnitude of spread at one rate, which is what being on the edge
looks like and is why the knee is quoted as a rate and not a boundary. Per security that is ~35k/s
against a 100k/s target: **the design's 1M/s/shard is over-stated by about 2.9x**, which is the same
conclusion R2–R4 reached with a number 15% lower.

**The archive write is not the ceiling, and this is the useful part.** R6 saturates at *exactly* the
same rate as R5 — both comfortable at 300k, both gone at 400k — and the sub-knee figures are inside
each other's noise (58.8 vs 60.6 µs at 250k; 73.2 vs 75.8 µs at 300k). Above the knee the RAM disk is
~10% faster at draining a queue (85 ms against 94 ms at 400k), which is a drained queue, not a moved
ceiling. Attribution runs A3/A3′/A4 say the same thing at the median: 61.2, 60.8 and 59.2 µs, where
A4 is the RAM disk and the run-to-run variance on the SSD alone is 0.7%.

**So one of four suspects is eliminated by measurement.** "Everything else" was Raft consensus, the
archive write, the IPC hops and the poller wake-ups. Storage is out: put the durable writes in RAM and
the ceiling does not move. What remains is the single-threaded consensus module, the IPC hops and the
poller wake-ups — and the rate-scaling in A3 (the engine gets *faster* per order while the round trip
grows, all of it outside the instrumented processes) says whatever it is saturates rather than costing
a fixed amount.

**A methodological note worth more than the numbers.** The first version of this experiment compared
A3 against A4 at 250k/s — below the knee — and found 2.6%, and would have concluded "storage barely
matters" from a rate at which storage was never stressed. It was only the *throughput* comparison at
and above the knee that was decisive, and only because an idle-machine SSD baseline was taken first:
against R4's loaded-machine numbers the RAM disk appeared to move the knee by 82x at 350k, which was
entirely the desktop. **A storage experiment needs a same-day, same-load baseline on the other
medium**, or it measures the day.

---

## What R7 says: the ceiling is the media driver's thread

The Aeron counters named it. `most counters` reads the driver's, the archive's and every cluster
component's counters straight out of the CnC file with no Aeron client, so it can be pointed at a
shard under load without perturbing it. Sampled at 300k/s (below the knee) and 400k/s (above it),
every counter in the durable chain scaled **exactly 1.33x with the offered rate** — ingress
publication, the log, `Cluster commit-pos`, `rec-pos`, archive write bytes, all 38.4 MB/s → 51.2 MB/s
— so nothing in that chain was capping in bytes. Two counters did not scale linearly:

| counter | 300k/s | 400k/s | |
| --- | --- | --- | --- |
| Sender flow control limits (back-pressure events) | 3/s | **266/s** | ×89 |
| `snd-bpe` on cluster ingress (stream 101) | 1/s | **266/s** | ×266 |
| `archive-recorder total write time` | 213 ms/s | **123 ms/s** | **down**, while bytes rose 33% |
| Bytes sent / received (loopback UDP) | 142.9 MB/s | 190.5 MB/s | ×1.33 |

The archive's write *time* falling while its byte rate rose is the third independent statement that
storage is not the constraint. The back-pressure explosion pointed at the cluster ingress channel,
whose term length is `64k`, hard-coded in `ClusterCommand.kt` — so that was tested first, and **it was
a symptom, not the cause**: at `16m` the knee did not move at all (350k either way), though p50 at
350k improved 2.9x (6410 µs → 2226 µs). 256x more ingress buffer buys latency headroom at the edge
and no capacity.

**What was the cause is the media driver's `ThreadingMode.SHARED`** — conductor, sender and receiver
on one thread, moving 190 MB/s of loopback UDP on a 14-core machine. A full 2×2 over driver and
archive threading, ten securities, idle machine, `ORDERS=400000` per rate (`ack response` p50):

| driver | archive | 350k/s | 450k/s | 500k/s | 550k/s | 600k/s | knee |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SHARED | SHARED | 6410 µs | 126 ms ✗ | — | — | — | **~350k/s** |
| SHARED | DEDICATED | 28.4 ms ✗ | 137 ms ✗ | — | — | — | **<350k/s** |
| DEDICATED | SHARED | **79 µs** | **90.5 µs** | **118 µs** | 2029 µs | — | **~500–550k/s** |
| DEDICATED | DEDICATED | **74 µs** | **102 µs** | **114 µs** | 694 µs | 55.5 ms ✗ | **~550k/s** |

✗ = `SATURATED`. **R7 conditions** as R5, plus `DRIVER_THREADING` / `ARCHIVE_THREADING`.

**Three things this settles.**

1. **The driver's threading mode is the whole effect.** Giving the driver its own conductor, sender
   and receiver threads moves the knee from ~350k/s to ~550k/s aggregate — **1.6x** — and cuts p50 at
   350k/s from 6410 µs to 79 µs, **81x**. Per security the ceiling goes from ~35k/s to ~55k/s, so the
   gap to Design.md §2's 100k/s target narrows from 2.9x to **1.8x**.
2. **The archive's threading mode contributes nothing, and hurts on its own.** `SHARED` driver with a
   `DEDICATED` archive is *worse* than both shared — 28.4 ms against 6410 µs at 350k, saturated where
   the baseline was not — because dedicating an archive thread while the driver is still sharing one
   takes a core from the component that needed it. A knob that helps in one combination and harms in
   another is the argument for measuring the factorial rather than one cell of it.
3. **The remaining gap is not yet attributed.** At ~550k/s something else binds, and it is not the
   engine (0.9–2.0% of a round trip), not storage (R6, and row 2 above), and not ingress buffering.
   The next suspects are the consensus module's own single thread and the driver even with dedicated
   threads; `most counters` against a DEDICATED run at 550k/s is the cheap next look.

**The default stays `SHARED`** (decided 2026-09-25, Design.md §7): three busy-spinning threads are the
wrong default for a laptop running five JVMs, and changing it would invalidate every figure above it
in this file. `--driver-threading DEDICATED` / `DRIVER_THREADING=DEDICATED` is the documented knob, and
it is what a benchmark and a production deployment should set. **Every figure in R1–R6 was therefore
taken on a shard running 1.6x below what it can do**, which does not change any conclusion drawn from
them — the engine's share, fan-out being free, storage not binding — because each is a ratio measured
within one configuration.

---

## What C1–C2 say: nothing Aeron can see is pinned, and CPU% cannot tell you why

`most counters` found the previous ceiling in one pass, so it was pointed at a `DEDICATED` driver at
its own knee. **It came back empty, and that is the result.** Sampled below (500k/s) and in genuine
saturation (600k/s, a 15M-order run so the sample lands while the shard is behind rather than while
its queue drains):

| counter | 500k/s | 600k/s | ratio | offered ratio |
| --- | --- | --- | --- | --- |
| Bytes sent / received | 238.2 MB/s | 285.6 MB/s | ×1.20 | ×1.20 |
| ingress, log, `commit-pos`, `rec-pos`, archive bytes | 64.0 MB/s | 76.8 MB/s | ×1.20 | ×1.20 |
| egress to client | 174.2 MB/s | 208.8 MB/s | ×1.20 | ×1.20 |
| gateway outbound (IPC 21) | 139.3 MB/s | 167.0 MB/s | ×1.20 | ×1.20 |
| book events (IPC 12) | 75.8 MB/s | 90.9 MB/s | ×1.20 | ×1.20 |
| `archive-recorder total write time` | 200.6 ms/s | **97.2 ms/s** | **÷2.1** | ×1.20 |

**Every stage scaled exactly with the offered rate**, five of them to three significant figures, and
the archive's write *time* halved while its byte rate rose a fifth. Every `work cycle exceeded
threshold` counter — driver Conductor, Sender and Receiver, archive conductor, Cluster, Cluster
container — read **0**, as did every error counter. The sender back-pressure that exploded ×89 under
`SHARED` is gone from the moving set entirely, which is the counters confirming from their own side
that the driver thread was the previous ceiling.

So the durable chain is not capping, no buffer is closing, no duty cycle is breaching, and yet p50 at
600k/s is 478 ms. **A stage at 100% of one core is invisible to every counter above**, so the next
instrument was per-process CPU, three rounds during the same saturated window (C2):

| process | CPU | note |
| --- | --- | --- |
| cluster-host (driver + archive + consensus) | 361–378% | ~3.7 cores; three of them busy-spinning by design |
| `most load` (the generator) | ~197% | not the bottleneck |
| **market-data** | **100.0%** | single-threaded, **pinned** |
| **engine** (service container) | **99.3–100.0%** | single-threaded, **pinned** |
| **gateway** | **98.2–98.9%** | single-threaded, **pinned** |

14 cores. **C1–C2 conditions** as R7 with `DRIVER_THREADING=DEDICATED`, `ARCHIVE_THREADING=SHARED`,
ten securities, 15,000,000 orders at 600k/s aggregate, idle machine. `ps` CPU is a decaying average
over a sustained load, so read these as ±a few points, not to the decimal.

### What this settles, and what it does not

**The counters are exhausted as a tool here, and that is itself worth recording.** Aeron reports
queues, positions, duty-cycle breaches and errors. None of them breached. A stage that is simply
*full* shows up in none of those, so a clean counter sheet beside a 478 ms median means "not a buffer,
a window, a disk or a stall" — and nothing more specific.

**The CPU sample answers less than it appears to, and the SHARED control is why.** `engine`, `gateway`
and `market-data` all read ~100% of a core — but all three use `BusySpinIdleStrategy`, so they read
100% whether they are working or idling. The same three read ~100% under `SHARED` too, at a rate 1.7x
lower, which proves the reading carries no information about their utilisation. **Those three numbers
must not be quoted as saturation.** Recorded here because the first reading of this table concluded
"three stages pinned" from them, which was wrong.

| process | SHARED | DEDICATED | what it means |
| --- | --- | --- | --- |
| cluster-host | 158–161% | 361–378% | **real signal** — the shared thread was capped at one core |
| engine, gateway, market-data | ~100% each | ~100% each | busy-spin; no information either way |
| `most load` | ~199% | ~197% | not the bottleneck |

**The one real signal confirms R7 from the CPU side.** Under `SHARED` the cluster-host uses ~1.6 cores
and cannot use more, because its conductor, sender and receiver share a single thread that is itself
the ceiling; `DEDICATED` lets the same process take 3.7 cores and the knee moves with it. Two
independent instruments, the same conclusion.

**What is known about the engine's real utilisation comes from its own histograms, not from `ps`.**
0.38 µs per order (A3) × 600k/s = **0.23 s per second, ~23% of a core of actual matching**. So even if
the engine's thread were the binding stage, roughly three quarters of that core is the
`ClusteredServiceContainer`'s Aeron work — polling the log, publishing egress, publishing book events —
rather than the matching this project spent its effort on. That is where the remaining headroom is not.

**So what caps ~550–600k/s is still open, and the next instrument is not a counter.** The three
candidate loops busy-spin, so measuring their headroom needs either their own in-process metrics
(`engine.metrics` already reports per-stage duty; the gateway reports inbound and outbound) or a run
with a yielding idle strategy purely for the measurement. The shard sustains 600k/s of *ingress* at
478 ms — arrival rate equals service rate with a standing backlog that never drains — so this is a
service limit, not a cliff.

**One actionable item comes out of it regardless.** The gateway is single-threaded and handles 600k
orders inbound plus ~1.3M reports outbound per second on that one thread; whether or not it is *the*
cap, it is the stage with the least headroom by construction and the only one that scales sideways
today. Gateways are stateless, `origQty` lives in the engine, and `run-restart.sh` §4c already covers
the multi-gateway case — the only gap is that **the directory cannot advertise more than one** (open
issue 6), filed as a small tidying job. It should be re-read as the cheapest throughput lever available.

**Tested in R8–R11 and A5, and refuted:** a second gateway buys no throughput, and at the knee the one
gateway spends about a fifth of its core on messages. See the next section.

---

## What R8–R11 and A5 say: the gateway is not the ceiling

The hypothesis from C1–C2 was that the single-threaded gateway, the stage with the least headroom by
construction, was what capped the shard at ~550k/s, so a second one would raise it. Before building
the directory change that would advertise a second gateway, `run-sweep.sh` gained `GATEWAYS` and
`LOADERS` and the question was put directly. Four arms on the same afternoon, idle machine, in this
order:

| run | gateways | load generators | 450k/s | 500k/s | 550k/s | 600k/s | knee |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R8 | 1 | 1 | **98.9 µs** | **122 µs** | 2.30 ms | 48.9 ms ✗ | ~500–550k/s |
| R9 | 1 | 2 | 575 µs | 22.7 ms ✗ | 75.4 ms ✗ | 53.3 ms ✗ | ~450–500k/s |
| R10 | 2 | 2 | 9.95 ms | 8.35 ms | 16.8 ms ✗ | 41.1 ms ✗ | ~450–500k/s, and slow below it |
| R11 | 1 | 1 | **93.5 µs** | **162 µs** | 26.4 ms ✗ | 26.9 ms ✗ | ~500–550k/s |

`ack response` p50; service time agrees to the digit in every cell because pacing held (p99.9 lateness
14–814 µs, under the 1 ms limit in every row). ✗ = `SATURATED`. **R8–R11 conditions**: as R7 with
`DRIVER_THREADING=DEDICATED`, `ARCHIVE_THREADING=SHARED`, ten securities, `ORDERS=400000` per rate
split evenly across the generators, load average ~2 at the start of R8 and ~4.4 at the start of R11
(the runs' own residue). With several generators, latency is the **worst** generator's percentile.
Generator *j* sends as participants 20+4*j*..23+4*j* through gateway *j* mod `GATEWAYS`.

**R8 and R11 bracket the experiment and agree with R7**, so the machine did not drift: the one-gateway
knee is ~500–550k/s either side of the two treatments.

**R10 is worse than R8, not better.** Two gateways carrying two generators' traffic knee no higher,
and are 100x slower *below* the knee (9.95 ms against 98.9 µs at 450k/s). R9, the control, is worse
again at 500–550k, but it is not a clean control and must not be read as one: with one gateway both
generators subscribe to the same report stream, so each decodes every report on the shard, its
neighbour's included (`ignored ~46k reports from outside this run` per generator in the smoke run).
What R9 and R10 share, and R8 does not, is two more busy-spinning threads per extra generator plus
R10's extra gateway, on a machine with 14 cores of which four are efficiency cores. The shard already
runs ~10 busy threads at R8. **What these runs establish is that adding a gateway process does not buy
throughput on this machine.** On their own they cannot say whether that is because the gateway is not
busy, or because its gain is swallowed by core contention.

**A5 answers that, from the gateway's own histograms.** `run-attribution.sh` (which gained
`DRIVER_THREADING` for this) at 500k/s aggregate, ten securities, `DEDICATED`, 4,000,000 orders over
8.0 s, one gateway; the run was at its knee (client p50 319 µs, p90 53.6 ms, p99 107 ms):

| stage | messages | mean | busy time | share of 8.0 s |
| --- | --- | --- | --- | --- |
| gateway inbound | 4,000,041 | 0.129 µs | 0.52 s | 6.5% |
| gateway outbound | 8,706,939 | 0.116 µs | 1.01 s | 12.6% |
| **gateway, both legs** | | | **1.53 s** | **~19%** |
| engine `newOrder` | 4,000,000 | 0.579 µs | 2.32 s | ~29% |

The means include ~10 ns of clock read per message, which is ~0.25 s of the gateway's 1.53 s. What
they exclude is the poll loop itself — Aeron's fragment dispatch and the egress adapter — which no
histogram times. At a generous 50 ns per fragment that adds ~0.6 s, and the gateway is still under a
third of its core. **The gateway is not the stage that is full at ~550k/s**, and the ~1.3M messages per
second it carries cost it about a fifth of a core. This is the same arithmetic that put matching at
~23% of the engine's core in C1–C2, and it is the per-message cost, not `ps`, that is quotable.

**What this settles.** The second-gateway directory change (Status.md open issue 6, to-do item 2) is
not a throughput lever and should not be built as one; addressing was already settled out of band.
Of the five processes on the order path, the engine and the gateway now have measured headroom, the
archive and the driver's threading were eliminated in R6–R7, and what remains is the **consensus
module** (a single thread in the cluster-host, never instrumented) and **market-data** (single
thread, busy-spin, consuming every book event). The consensus module is the next suspect, and it
still has to be measured rather than inferred. **Measured in D1–D2: it is not** — ≤20% busy — and on
this machine no stage is; the knee is the core count.

**One oddity worth keeping.** A5's p50 is 319 µs where R8's at 500k/s is 122 µs. A5 ran 8 s rather
than R8's 0.8 s with metrics on, so it sat at the knee long enough to build a queue; that is why it is
the right run for asking what is full, and the wrong one to quote as a latency.

---

## What D1–D2 say: on this machine the knee is the core count, not a stage

With the duty-cycle counters (Design.md §7, "Duty cycle") every thread on the order path reports how
much of its core it spends working, so the question "which stage is full at ~550k/s" could finally be
asked directly. `run-attribution.sh` at six rates, ten securities, `DRIVER_THREADING=DEDICATED`,
archive `SHARED`, `engine.metrics=true` with stages off, 8 s of load per rate, counters sampled over the
middle 40% of it (D1):

| thread | 250k/s | 400k/s | 500k/s | 550k/s | 600k/s | 700k/s |
| --- | --- | --- | --- | --- | --- | --- |
| **engine service** | 12.2% | 17.6% | 20.4% | **99.9%** | **99.9%** | **99.9%** |
| driver sender | 91.5% | 97.5% | 98.2% | 98.4% | 98.4% | 98.1% |
| driver receiver | 69.3% | 70.9% | 71.2% | 69.9% | 71.6% | 72.6% |
| gateway | 16.4% | 24.5% | 31.4% | 33.8% | 34.4% | 39.7% |
| archive | 26.1% | 31.1% | 30.2% | 29.3% | 19.3% | 14.6% |
| driver conductor | 19.4% | 22.0% | 22.4% | 21.8% | 20.9% | 21.2% |
| consensus module | 14.3% | 17.4% | 18.2% | 17.4% | 11.7% | 8.5% |
| market-data | 6.5% | 9.6% | 11.4% | 12.5% | 13.4% | 16.4% |
| engine `newOrder` p50 / p90 | 0.33 / 0.63 µs | 0.33 / 0.63 µs | 0.33 / 0.58 µs | 0.38 / **5.50 µs** | 0.33 / **4.92 µs** | 0.38 / **3.50 µs** |
| client response p50 | 78.8 µs | 98.4 µs | 383 µs | 213 ms ✗ | 477 ms ✗ | 410 ms ✗ |

Service time equals response time to the digit in every row; pacing p99.9 18–145 µs.

**The consensus module is not it.** It never exceeds 20%, and it *falls* past the knee because the
engine stops keeping up with the log it feeds. Nor is the gateway (A5 already said so), market-data, the
archive or the conductor. **The driver's sender reads ~98% throughout**, but it is a batching loop: it
reads 91.5% at 250k/s, where the shard is nowhere near full, so its figure means "never idle", not
"full" (Design.md §7).

**The thread that goes over is the engine's**, and it goes from 20% to 100% in one 50k/s step. That
is not matching getting dearer: the median `newOrder` holds at ~0.35 µs while **p90 jumps ~10x**. The
first suspect was egress back-pressure — the engine's report publish spins on `tryClaim`, and
`backpressureStalls` counts only one stall per **1,000,000** consecutive retries, so a zero there
proves little. Every Aeron counter was sampled at 500k and 550k/s to test it, and it is refuted: the
sender's flow-control events *fall*, 16/s → 1/s, and nothing else that measures back-pressure, loss or
retransmission rises.

**What is left is where the thread runs.** The machine is 10 performance cores plus 4 efficiency
cores. At 550k/s the shard wants the driver's sender and receiver near full, the engine, gateway and
market-data spinning a full core each whatever their duty, two load-generator threads, and the rest
partially — about ten cores. A thread that loses its core, or is moved to another cluster, finds its
caches cold, and the engine's cost is cache misses (Design.md §2), which is a tail effect exactly like the one
measured. **D2 tests it** by freeing the two cores the gateway and market-data spin away while mostly
idle (`GATEWAY_IDLE=backoff MD_IDLE=backoff`), the engine still busy-spinning, interleaved with
controls:

| run | gateway + market-data | rate | engine duty | `newOrder` p50 / p90 / p99 | response p50 | response p99 | pacing p99.9 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D2a | busyspin | 550k/s | 99.9% | 0.38 / 5.25 / 23.2 µs | 222 ms ✗ | 292 ms | **3764 µs** |
| D2b | **backoff** | 550k/s | **33.8%** | 0.29 / **0.54** / 6.67 µs | **272 µs** | 130 ms | 125 µs |
| D2c | busyspin | 600k/s | 99.9% | 0.38 / 4.63 / 22.2 µs | 478 ms ✗ | 479 ms | 38.4 µs |
| D2d | **backoff** | 600k/s | 99.9% | 0.33 / 3.63 / 12.4 µs | 66.0 ms ✗ | 98.4 ms | 16.0 µs |
| D2e | backoff | 500k/s | 19.6% | 0.29 / 0.54 / 1.25 µs | 104 µs | 55.4 ms | 18.4 µs |
| D2f | backoff | 650k/s | 99.9% | 0.33 / 4.75 / 14.1 µs | 291 ms ✗ | 435 ms | 298 µs |

**Freeing two cores moved the knee from ~500–550k/s to ~550–600k/s**, and at 550k/s it turned the
engine from saturated (99.9%, p90 5.25 µs) to comfortable (33.8%, p90 0.54 µs) without touching the
engine. D2a's pacing lateness (3.8 ms, which `run-sweep.sh` would mark `INVALID`) says the load
generator was starved of CPU in the same run, which is the same symptom seen from outside the shard.
D2d/D2f then saturate again, one step further up, the same way.

**What this settles.** On this laptop the knee is **where the shard's busy threads outnumber the
performance cores**, and the engine is the thread that falls over first because its cost is
cache-bound — not a stage of the design running out of capacity. Every knee in this file from R7 on
(~550k/s) is therefore a property of a 10+4-core laptop running the whole shard *and* its load
generator, and should be quoted as such. The design's own single-threaded stages have measured
headroom at that rate: consensus module ≤20%, gateway ~34%, market-data ~13%, archive ~30%.

**What it does not settle.** Where a machine with enough cores for every spinning thread — the
deployment `ProdDeployment.md` describes, with isolated cores — would knee, and whether the driver's
sender, the one loop that never idles, is then the limit. That needs either a many-core Linux host with
thread pinning or the driver on a second machine; this laptop cannot answer it. Also a caution: each
cell above is one 8 s run, and R8/R11 showed the 550k/s cell moving between runs, so D2b's knee shift
is one step of the rate ladder, not a precise figure.

**Conditions (D1–D2).** Apple M4 Pro, 14 cores (10P+4E), macOS 15.7.9, JVM 21.0.11, commit
`ba5c605`+ (the duty-cycle code, uncommitted), single node, Aeron IPC between client and gateway,
`run-attribution.sh` with `engine.metrics=true`, stages off, `gateway.metrics=true`, `md.metrics=true`,
`most cluster --duty`; ten securities, band 99.90–100.10, `maxOrders=1000000`; load average 2.0–4.8
across the series (the runs' own residue), two idle Docker containers (<0.3% CPU). D1 17:33–17:39, the
back-pressure counter sample 17:40–17:43, D2 17:43–17:48, 2026-09-27.

---

## Attribution runs

`e2e/run-attribution.sh` turns the in-process instrumentation on and subtracts: whatever the client
round trip did not spend in the gateway or the engine, it spent in consensus, the archive write and
the wire. These three runs answer the question runs R2–R4 opened — the ceiling is aggregate, but
*which* stage.

| # | date | commit | sec | aggregate rate | per security | client p50 | gateway in | engine | gateway out | shard's own code | everything else |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 2026-09-25 | `b537590`+ | 1 | 100k/s | 100k/s | 38.1 µs | 0.17 µs | 0.46 µs | 0.13 µs | 0.8 µs (**2.0%**) | 37.3 µs (98.0%) |
| A4 | 2026-09-25 | `b537590`+ | 10 | 250k/s | 25k/s | 59.2 µs | 0.08 µs | 0.38 µs | 0.08 µs | 0.5 µs (**0.9%**) | 58.7 µs (99.1%) |
| A2 | 2026-09-25 | `b537590`+ | 10 | 100k/s | 10k/s | 39.4 µs | 0.17 µs | 0.50 µs | 0.13 µs | 0.8 µs (**2.0%**) | 38.6 µs (98.0%) |
| A3 | 2026-09-25 | `b537590`+ | 10 | 250k/s | 25k/s | 61.2 µs | 0.08 µs | 0.38 µs | 0.08 µs | 0.5 µs (**0.9%**) | 60.7 µs (99.1%) |
| A3′ | 2026-09-25 | `b537590`+ | 10 | 250k/s | 25k/s | 60.8 µs | 0.08 µs | 0.38 µs | 0.08 µs | 0.5 µs (**0.9%**) | 60.3 µs (99.1%) |
| A5 | 2026-09-27 | `ba5c605`+ | 10 | 500k/s | 50k/s | 319 µs | 0.13 µs | 0.38 µs | 0.08 µs | 0.6 µs (**0.2%**) | 318.4 µs (99.8%) |

Engine stage split, same runs (`newOrder` p50, and its three stages):

| # | newOrder | admit | match | settle | client p90 | client p99 | client p99.9 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 0.46 µs | 0.13 µs | 0.04 µs | 0.17 µs | 56.4 µs | 1.32 ms | 16.1 ms |
| A2 | 0.50 µs | 0.13 µs | 0.04 µs | 0.17 µs | 56.9 µs | 277 µs | 3.02 ms |
| A3 | 0.38 µs | 0.08 µs | 0.04 µs | 0.17 µs | 348 µs | 35.1 ms | 46.0 ms |

**A1–A3 conditions.** Single node, Aeron IPC, JVM start scripts, `engine.metrics=true`,
`engine.metrics.stages=true`, `gateway.metrics=true`. 2,000,000 orders per run, 1,000 warmup, band
99.90–100.10 inside a 5000 bps static collar, `maxOrders=1000000` per security, same ten-symbol table
as R2–R4. `--delay-us` is the gap between sends for the run as a whole, so the rate is the
**aggregate**. Machine **idle** this time: load average 1.4 at the start, IntelliJ and the browser
closed, Docker Desktop stopped. Cluster and archive directories on the internal APFS SSD. A1 is the
re-baseline of the figure recorded in Handover §2c, which was taken on a loaded machine — 38.1 µs
against the 55 µs there, same 2% share.

### What A1–A3 say

**The engine is not the ceiling, and fan-out is not what costs.** Spreading the same 100k/s over ten
books instead of one moved a whole new order from 0.46 µs to 0.50 µs — 8%, which is the cache
pressure of ten ladders and ten pools instead of one — and moved the round trip 38.1 µs to 39.4 µs.
Ten books cost essentially nothing per order. Whatever limits the shard at 300k/s aggregate is not
the books, and this is now measured rather than inferred from R2–R4's shape.

**The proof is what happens when the rate rises.** At 2.5x the rate (A3) the engine got *faster* per
order — 0.38 µs, better amortisation per poll — while the client round trip grew from 39.4 µs to
61.2 µs. **Every microsecond of that increase is outside the instrumented processes**, and the shard's
own share fell to 0.9%. A fixed cost — an IPC hop, a poller wake-up — does not grow with arrival rate.
Something in the shared path is saturating.

**It is still a lump, and this is the honest boundary of the measurement.** "Everything else" is Raft
consensus, the archive write, the IPC hops and the poller wake-ups, and the subtraction cannot tell
them apart. What can be said from these numbers: it grows with rate, so it is a throughput limit and
not a latency floor; and A3's p99 of 35 ms against a p50 of 61 µs is queueing already visible at the
tail one rate below the knee — which R5 later pinned at ~350k/s aggregate.

**What would split it**, in increasing cost:

1. ~~**Move the cluster and archive directories to a RAM disk and re-run A3.**~~ **Run** — as A4
   above and as R6 — and the answer is that **the archive write is not the ceiling**: the round trip
   moved 2.6% at the median and the knee did not move at all. See "What R5–R6 say".
2. **A timestamp on the ingress message**, stamped by the gateway and read by the engine under
   `engine.metrics` only, would measure gateway-offer-to-engine-entry directly. That is a wire change
   (`NewOrderSingle` carries no timestamp) and is governed by the `wire-change` skill.
3. **Aeron's own driver and archive counters**, which are exposed and currently read by nothing here.

Back-of-envelope, and explicitly *not* a measurement: at 250k/s the log append is roughly
250k × ~80 B ≈ 20 MB/s, which no SSD notices, so raw write bandwidth is an unlikely explanation and
the single-threaded consensus module or its fsync behaviour is the better first suspect. Experiment 1
is what would turn that sentence into a fact.

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

`SECURITIES=n` drives n securities (max 10) and the rates stay the aggregate, so a one-book sweep
and a fan-out sweep are the same script and the same row format.

Not yet recorded here, and worth a row when they happen: a native-binary sweep on x86-64, a
`most counters` sample against a DEDICATED driver at its own knee, a multi-node cluster, and an
Epsilon soak measured in hours.
