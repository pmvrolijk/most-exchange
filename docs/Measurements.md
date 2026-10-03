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
- **State the scope.** Everything to date is single-node. R1 is one security; R2–R11, L0–L4, E1–E14, I1–I3, U1
  and K1–K5 are ten. **Say which egress channel** (see "What E1–E14 say"): UDP egress knees near 0.5M/s,
  IPC egress near 1.5M/s.
- **Before calling a thread full, find out what it is waiting on.** The engine's duty cycle and its
  `newOrder` histograms both count spinning on a full egress publication as work. Read the egress
  publication's headroom (`pub-lmt − pub-pos`, stream 102; ≤ 0 is back-pressured) and how far the
  driver's sender is behind it. `snd-bpe` is the sender's *receiver-window* limit, not the engine's,
  and D1 read the wrong one — see "What E1–E14 say".
- **A counter cannot see a full thread, and `ps` cannot see a busy-spinning one.** Aeron reports
  queues, positions and duty-cycle breaches, so a stage that is merely full breaches none of them; and
  `engine`, `gateway` and `market-data` all busy-spin, so their ~100% CPU carries no information about
  utilisation. Read their own histograms instead — see "What C1–C2 say".
- **Say how many cores the shard had, and what else was spinning.** On the 10P+4E laptop the knee is
  where busy threads outnumber performance cores (D1–D2), so a knee is a property of the machine's
  core count before it is one of the design. Freeing two spinning cores moved it one rate step. ~~On a
  host with a core for every agent (L0–L4) the engine thread still steps from half-busy to full across
  one rate step, so the step is the engine's, and the core count only decides where it lands.~~
  **Reversed (E1–E14):** on both machines the step is the driver's UDP sender falling behind egress,
  and the engine spinning on the full publication. Cores matter to that sender's speed, not to the
  engine's.
- **A duty-ns counter on an Aeron agent is not CPU.** On the cloud host the driver's sender read 92–97%
  of a core while the kernel charged it ~2%, parked in `BackoffIdleStrategy`. Check an Aeron agent's
  duty against `/proc/<pid>/task/<tid>/stat` before reading it as fullness — see "What L0–L4 say".
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
| L0a | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 300k/s | 300,014/s | 39.3 ms ✗ | 58 ms | 60.4 ms | 39.3 ms ✗ | 60.4 ms | 236.9 µs | 0 | 0 |
| L0b | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 400k/s | 399,954/s | 202 ms ✗ | 243 ms | 248 ms | 202 ms ✗ | 248 ms | 348.4 µs | 1 | 0 |
| L0c | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 500k/s | 499,961/s | 296 ms ✗ | 331 ms | 335 ms | 296 ms ✗ | 335 ms | 217.0 µs | 0 | 0 |
| L1a | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 150k/s | 150,012/s | 46.3 µs | 158.6 µs | 1.69 ms | 46.5 µs | 1.7 ms | 125.9 µs | 0 | 0 |
| L1b | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 200k/s | 199,993/s | 49.8 µs | 433.9 µs | 2.23 ms | 50.1 µs | 2.23 ms | 227.1 µs | 1 | 0 |
| L1c | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 225k/s | 225,015/s | 55.9 µs | 1.36 ms | 2.91 ms | 56.1 µs | 2.91 ms | 203.6 µs | 0 | 0 |
| L1d | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 250k/s | 249,990/s | 66.0 µs | 2.03 ms | 4.56 ms | 66.2 µs | 4.56 ms | 191.7 µs | 0 | 0 |
| L1e | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 275k/s | 275,012/s | 98.3 µs | 2.5 ms | 6.06 ms | 100.5 µs | 6.06 ms | 197.1 µs | 0 | 0 |
| L1f | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 300k/s | 300,012/s | 3.39 ms | 10.8 ms | 14.6 ms | 3.39 ms | 14.6 ms | 199.4 µs | 0 | 0 |
| L2a | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 150k/s | 150,012/s | 46.8 µs | 177.7 µs | 1.8 ms | 47.0 µs | 1.8 ms | 165.6 µs | 0 | 0 |
| L2b | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 200k/s | 199,993/s | 75.8 µs | 17.9 ms | 30.2 ms | 76.6 µs | 30.2 ms | 192.3 µs | 1 | 0 |
| L2c | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 225k/s | 225,015/s | 59.2 µs | 1.57 ms | 3.3 ms | 59.5 µs | 3.32 ms | 207.0 µs | 0 | 0 |
| L2d | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 250k/s | 249,989/s | 76.0 µs | 2.22 ms | 6.02 ms | 76.7 µs | 6.02 ms | 366.3 µs | 0 | 0 |
| L2e | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 300k/s | 300,015/s | 1.61 ms | 4.27 ms | 6.48 ms | 1.61 ms | 6.48 ms | 204.3 µs | 0 | 0 |
| L3a | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | native (GraalVM CE 21.0.2) | 10 | 150k/s | 150,012/s | 53.4 µs | 163.5 µs | 1.71 ms | 53.7 µs | 1.71 ms | 154.4 µs | 0 | 0 |
| L3b | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | native (GraalVM CE 21.0.2) | 10 | 200k/s | 199,992/s | 64.2 µs | 473.6 µs | 2.21 ms | 64.5 µs | 2.21 ms | 190.2 µs | 1 | 0 |
| L3c | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | native (GraalVM CE 21.0.2) | 10 | 225k/s | 225,014/s | 76.6 µs | 1.34 ms | 3.14 ms | 77.0 µs | 3.15 ms | 190.7 µs | 0 | 0 |
| L3d | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | native (GraalVM CE 21.0.2) | 10 | 250k/s | 249,988/s | 210.9 µs | 6.23 ms | 22 ms | 220.7 µs | 22 ms | 196.5 µs | 0 | 0 |
| L3e | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | native (GraalVM CE 21.0.2) | 10 | 275k/s | 275,016/s | 110.4 µs | 2.14 ms | 5.19 ms | 111.6 µs | 5.19 ms | 175.6 µs | 0 | 0 |
| L3f | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | native (GraalVM CE 21.0.2) | 10 | 300k/s | 300,015/s | 8.98 ms | 19.3 ms | 24.2 ms | 8.98 ms | 24.2 ms | 189.2 µs | 0 | 0 |
| L4a | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12, log on disk | 10 | 200k/s | 199,998/s | 59.1 µs | 3.67 ms | 15.4 ms | 59.6 µs | 15.4 ms | 171.1 µs | 2 | 0 |
| L4b | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12, log on `/dev/shm` | 10 | 200k/s | 199,999/s | 52.3 µs | 10.1 ms | 21.6 ms | 52.6 µs | 21.6 ms | 183.0 µs | 2 | 0 |
| L4c | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 200k/s | 199,998/s | 47.6 µs | 202.4 µs | 2.44 ms | 47.9 µs | 2.44 ms | 172.7 µs | 2 | 0 |
| L4d | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 200k/s | 199,999/s | 47.4 µs | 151.6 µs | 2.53 ms | 47.6 µs | 2.53 ms | 148.6 µs | 2 | 0 |
| L4e | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | native (GraalVM CE 21.0.2) | 10 | 200k/s | 199,998/s | 58.9 µs | 291.6 µs | 2.72 ms | 59.3 µs | 2.72 ms | 186.4 µs | 2 | 0 |
| L4f | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 200k/s | 199,998/s | 53.0 µs | 1.82 ms | 11.7 ms | 53.3 µs | 11.9 ms | 181.6 µs | 2 | 0 |
| L4g | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | native (GraalVM CE 21.0.2) | 10 | 200k/s | 199,999/s | 55.3 µs | 163.8 µs | 2.36 ms | 55.5 µs | 2.36 ms | 173.4 µs | 2 | 0 |
| L4h | 2026-09-29 | `cf6e302`+ | AMD EPYC 7713 VM (Linode g7-dedicated-64-32), Linux 6.8 | 16 (SMT off, pinned) | **yes** | JVM 21.0.12 | 10 | 200k/s | 199,998/s | 49.7 µs | 661.5 µs | 5.01 ms | 50.0 µs | 5.01 ms | 170.1 µs | 2 | 0 |
| I1a | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 700k/s | 700,275/s | 53.2 µs | 84.7 µs | 283.4 µs | 53.3 µs | 285.7 µs | 16.1 µs | 0 | 0 |
| I1b | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 800k/s | 799,992/s | 55.7 µs | 88.6 µs | 496.1 µs | 55.8 µs | 514.3 µs | 18.4 µs | 1 | 0 |
| I1c | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 900k/s | 900,081/s | 58.8 µs | 94.1 µs | 332.3 µs | 58.9 µs | 333.3 µs | 17.1 µs | 1 | 0 |
| I1d | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 1M/s | 999,990/s | 62.7 µs | 99.1 µs | 263.7 µs | 62.8 µs | 265.7 µs | 26.2 µs | 2 | 0 |
| I1e | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 1.2M/s | 1,200,465/s | 68.3 µs | 120.1 µs | 479.7 µs | 68.5 µs | 554.0 µs | 267.3 µs | 3 | 0 |
| U1a | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, UDP egress | 10 | 500k/s | 499,997/s | 178.9 µs | 2.23 ms | 15 ms | 179.1 µs | 15 ms | 29.6 µs | 2 | 0 |
| U1b | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, UDP egress | 10 | 600k/s | 600,236/s | 228 ms ✗ | 359 ms | 391 ms | 228 ms ✗ | 391 ms | 17.0 µs | 0 | 0 |
| U1c | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, UDP egress | 10 | 700k/s | 700,275/s | 381 ms ✗ | 411 ms | 413 ms | 381 ms ✗ | 413 ms | 19.0 µs | 1 | 0 |
| I2a | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 600k/s | 600,236/s | 52.5 µs | 91.9 µs | 360.4 µs | 52.6 µs | 361.5 µs | 16.0 µs | 2 | 0 |
| I2b | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 800k/s | 799,992/s | 55.9 µs | 93.8 µs | 803.3 µs | 56.0 µs | 803.3 µs | 18.5 µs | 1 | 0 |
| I2c | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 900k/s | 900,082/s | 57.5 µs | 95.1 µs | 1.75 ms | 57.6 µs | 1.75 ms | 32.3 µs | 1 | 0 |
| I2d | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 1M/s | 999,989/s | 59.1 µs | 92.1 µs | 259.3 µs | 59.2 µs | 264.2 µs | 28.5 µs | 2 | 0 |
| I2e | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 1.2M/s | 1,200,464/s | 70.9 µs | 131.3 µs | 15 ms | 71.1 µs | 15 ms | 266.8 µs | 3 | 0 |
| I3a | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 1.2M/s | 1,200,461/s | 75.4 µs | 165.0 µs | 3.17 ms | 75.7 µs | 3.17 ms | 241.9 µs | 2 | 0 |
| I3b | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 1.5M/s | 1,501,474/s | 81.3 µs | 149.5 µs | 462.1 µs | 81.7 µs | 468.5 µs | 364.8 µs | 0 | 0 |
| I3c | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 1.8M/s | 1,801,762/s | 114.4 µs | 742.9 µs | 9.68 ms | 114.9 µs | 9.68 ms | 229.2 µs | 1 | 0 |
| I3d | 2026-09-29 | `2021b61`+ | Apple M4 Pro, macOS 15.7.9 | 14 (10P+4E) | **yes** | JVM 21.0.11, IPC egress | 10 | 2.5M/s | 2,499,235/s | 51.5 ms ✗ | 68.3 ms | 78.7 ms | 51.5 ms ✗ | 78.7 ms | 964.1 µs | 2 | 0 |

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
retransmission rises. **Reversed (E1–E3):** those flow-control events (`snd-bpe`) are the sender
meeting the *receiver's* window. The engine's limit is the egress publication's `pub-lmt`, and at
550k/s the engine is sitting on it with the sender a full 8 MB window behind.

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

**Superseded by E1–E14** (the paragraph is kept as it was written). What binds at ~550k/s is the
driver's UDP sender, one ≤1,408 B datagram per publication per duty cycle, and the engine is
back-pressured behind it. Freeing two cores (D2) most plausibly helped that sender.

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

## What L0–L4 say: a core for every thread — and the step turned out to be egress

The question D1–D2 left: where does the shard knee when core count is not the limit, and is the
driver's sender then what binds? Asked on a cloud host (`deploy/cloud/`) with **16 physical cores and
one per agent thread**: a Linode `g7-dedicated-64-32` (32 vCPU of an AMD EPYC 7713), SMT siblings
offlined, the agents' cores isolated (`isolcpus`, `nohz_full`, `rcu_nocbs`, `idle=poll`), and
`PIN=/etc/most-cpus.env` moving each named agent thread — driver conductor, sender, receiver, archive,
consensus module, the engine's `matching-engine` service thread, gateway, market-data — to a core of
its own, the load generators on four more. The hypervisor shows a flat topology; the SMT pairing was
recovered by measurement (`bench.sh probe`: all 496 vCPU pairs, 16 disjoint pairs at 0.80–0.82 of
solo throughput and every other pair at ~1.0) and one CPU of each pair kept.

**The knee is ~275–300k/s aggregate** — p50 under ~100 µs through 275k/s in both JVM repeats (L1, L2)
and in the native one (L3), 1.6–9 ms at 300k/s. That is **half the laptop's**, and the reason is the
core, not the design: the guest reports 2.0 GHz, and a whole new order costs the engine 1.2–1.4 µs
here against 0.38–0.58 µs on the M4 Pro (A6, A8–A10 against A1–A5). **Huge pages are not it**: the
engine with `-XX:+UseTransparentHugePages` (its pool is an on-heap `LongArray`) was ~3% cheaper per
order at 200k/s (1.3 vs 1.4 µs; duty 47.5% vs 49.0%, two interleaved repeats each) and saturated at
300k/s exactly as the control did. **Storage is not it** either, again: L4a/L4b put the log and
archive on the cloud disk and on `/dev/shm` and the RAM arm was no better. **Corrected the same day
(see "What E1–E14 say"):** the knee is where the driver's sender stops keeping up with egress. That's
142 MB/s of loopback UDP here, against ~250–310 MB/s on the laptop. The engine's per-order cost is also
slower here, but it doesn't set the knee.

**Reversed (E1–E14; A10's own counters already showed it). The paragraph below is kept as written.**
The engine's thread reads 100% because it spins on a full egress publication. Its *mean* `newOrder`
(3.19 µs at 300k/s against 2.38 at 200k/s) × rate accounts for the whole duty cycle, so the rise is
inside the handler, in the two stages that publish execution reports.

~~**The thread that saturates is still the engine's**~~, with a core to itself. Its duty cycle is 26% at
100k/s, 49% at 200k/s and **100% at 300k/s** (A6–A10, THP control) — not the ~73% the per-order cost
predicts — while its median `newOrder` holds at 1.3–1.4 µs. That is D1's shape, the same one-step
collapse, on a machine where nothing competes for the core. **So D1–D2's reading was incomplete**: core
starvation on the laptop moved *where* the step happens (freeing two cores moved it one rate step),
but the step itself is a property of the engine thread. What fills it past the step is open. The
stage histograms show multi-millisecond maxima in `admit`, `match` and `settle` alike (A8–A10, max
2.7–7.8 ms at every rate), which says stalls on the thread rather than dearer work; the service
container's own Aeron work — polling the log, publishing egress — is untimed and is the first suspect.

~~**The driver's sender is not the limit.**~~ **Reversed: at the knee it is.** The ~2% below is a
100k/s figure, far below the knee, and it was generalised to the knee without being measured there.
The kernel charges it **~2% of a core** at 100k/s (2 ticks in
a second from `/proc/<pid>/task/<tid>/stat`), and five kernel-stack samples and a `jstack` all found it
parked in `BackoffIdleStrategy` — Aeron's default, since `most cluster` sets no driver idle strategy.
The conductor, archive and consensus module are the same: ~1,200 voluntary context switches a second
each, cores 97–99% idle by `mpstat`.

**Which exposes an instrument problem.** Those same agents' `duty-ns` counters read **92–97% of a
core** for the sender and receiver (A6–A10), against the kernel's ~2%. `DutyCycleIdleStrategy` is
specified to exclude time inside the delegate. The engine's reading is at least consistent with its
own histograms (26% at 100k/s against 25% from the *mean* `newOrder` × rate; the first version of
this sentence used the median and read ~14%), though for a busy-spinning thread nothing in the kernel can corroborate it; the cluster host's
agents park, so for them the kernel can, and it disagrees. It is **unexplained**, and it bears on D1: "the driver's sender reads ~98%
throughout" was read as a batching loop that never idles, and may instead be this. Until it is
resolved, a cluster-host duty reading is corroborated against kernel CPU time or not quoted.

**Sustained is not the same as a burst.** The sweep's 300,000-order rates last ~1 s. At 200k/s for
8 s (L4, 1.6M orders) the median holds at 47–60 µs but p99 ranges **2.4–22 ms across eight
identical-condition runs**, and the metrics-on attribution at 200k/s (A8) reached a 146 ms p90. This
host's run-to-run spread in the tail is larger than any tail difference measured on it: native
against JVM (L4e/L4g against L4c/L4d/L4f/L4h) is 55–59 µs against 47–53 µs at p50 and inside the
spread at p99, so **native changes the median by a few µs and the knee not at all**. L3 also
confirms the native build end to end on x86-64: `run-e2e.sh` with all four native services produced
the JVM run's counts exactly — 10,944 reports, 5,142 trades, 802 cancels, 16,368 traded, 2,315
filled on arrival, 0 rejected, 0 unanswered — and each image carries the three
`jdk.internal.misc.Unsafe` references.

**What does not count.** Two unpinned sweeps were run and are not recorded: on a host booted with
`isolcpus`, an unpinned process may only run on the six non-isolated CPUs, so ~12 spinning threads
shared six cores and every row was `INVALID` on pacing. The comparison they were meant to make —
pinned against the scheduler's own placement on the same cores — needs a boot without `isolcpus`.
L0 is the first pinned sweep and is kept because it is valid: it saturated from 300k/s (p50 39 ms)
where L1/L2 read 1.6–3.4 ms, the widest run-to-run difference in the series.

**Conditions (L0–L4, A6–A10).** Linode `g7-dedicated-64-32`, `nl-ams`, Ubuntu 24.04, kernel
6.8.0-134-generic, AMD EPYC 7713 reported at 2.0 GHz, 16 of 32 vCPUs online (one per SMT pair), 64 GB,
local disk (`/dev/sda`, ext4) under the log and archive unless stated; `tuned` `latency-performance`,
THP `madvise`, no huge pages reserved; `/etc/most-cpus.env`: housekeeping 0,1; driver conductor 3,
sender 5, receiver 6, archive 7, consensus module 8, engine service 9, gateway 10, market-data 11;
loaders 13–16 (not isolated). Nothing else ran on the box (load average 2–6 is the runs' own
residue). OpenJDK 21.0.12 (JVM) and GraalVM CE 21.0.2 `-march=x86-64-v3` (native: engine, gateway,
market-data, discovery; the cluster host and `most load` stayed JVM). Commit `cf6e302`+: the `+` is
`e2e/pin.sh`'s thread-name fixes and the sweep's `cores` column, none on the measured path. Ten
securities, `DRIVER_THREADING=DEDICATED`, archive `SHARED`, band 99.90–100.10 inside a 5000 bps
static collar, `maxOrders=1000000` per security. Sweeps: metrics off, 300,000 orders per rate
(1,600,000 for L4), 200,000-order discard pass first; the L2 275k/s row is omitted as `INVALID`
(pacing p99.9 1.0 ms). Attribution: metrics and stages on, 8 s of load per rate, A8–A10 with every
Aeron counter sampled. L4a/b are each the second of two interleaved repeats (the first repeats'
figures were not captured); L4c–h are in time order. 2026-09-29, 19:45–20:25 UTC.

---

## What E1–E14 say: the step is the driver's UDP sender, and the engine only looks full

**The evidence was already in A8–A10.** Two things in the files L0–L4 produced reverse its reading.

| | 200k/s (A8) | 300k/s (A10) |
| --- | --- | --- |
| `newOrder` mean / admit / match / settle | 2.38 / 0.68 / 1.00 / 0.55 µs | **3.19 / 1.12 / 1.40** / 0.54 µs |
| mean × rate against duty cycle | 47.7% against 49.0% | 96% against 100% |
| egress headroom, `pub-lmt − pub-pos` (stream 102) | 8.39 MB | **−128 B** |
| driver's sender behind the engine | 160 B | **8.39 MB**, a full window |
| gateway behind the sender | 160 B | 0 (`rcv-pos` = `rcv-hwm` = `sub-pos` = `snd-pos`) |
| sender's receiver-window room / limit events | — | 118 KB / 23 per s |
| engine's log subscription behind the log | 256 B | **17.5 MB** (the ~0.5 s client latency) |
| short sends, loss-gap fills, NAKs per s | 0 | 0 |

The engine's time is inside `onNewOrder` (mean × rate accounts for the duty cycle), and the rise at
300k/s is in `admit` (the NEW report) and `match` (the fill reports), not in `settle`, whose only
routine publication is `OrderAdded` to market-data over IPC. At the same moment the engine's egress
publication is at its limit, with the driver's sender a full 8 MB window behind it. The receiving
side has caught up with everything sent, and the sender has flow-control room, so the backlog sits in
the sender. `session.tryClaim` (`MatchingEngineService.sendExecutionReport`) retries in a tight loop
with no idle, so the spin is counted as work by the duty cycle and by the stage histograms alike.
`backpressureStalls` counts one stall per million *consecutive* retries and read 0 throughout.

**Why the sender.** In Aeron 1.53, `NetworkPublication.sendData` scans at most
`min(senderLimit − senderPosition, mtuLength)`. So the sender puts out one datagram of at most
1,408 B per publication per duty cycle, and on loopback the sending core also pays for the receive
path. Execution reports are ~2.2 per order and ~350 B of egress per order, against ~128 B of ingress.
Egress is what outgrows the sender.

**E1–E14 test it on the laptop**, where D1 saw the same step at ~550k/s. They are
`run-attribution.sh` runs, and the new `EGRESS_CHANNEL` knob sets the gateway's cluster egress channel:

| # | egress | rate | newOrder / admit / match / settle (µs) | headroom | sender behind | engine duty | bytes sent | client p50 / p90 / p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| E1 | UDP, 1,408 B | 500k/s | 0.97 / 0.32 / 0.40 / 0.21 | 7.50 MB | 885 KB | 47.0% | 241 MB/s | 16.9 / 73.0 / 102 ms |
| E2 | UDP, 1,408 B | 550k/s | 1.81 / 0.70 / 0.80 / 0.28 | 10 KB | 8.38 MB | 99.9% | 254 MB/s | 304 / 425 / 456 ms |
| E3 | UDP, 1,408 B | 550k/s | 1.78 / 0.69 / 0.79 / 0.27 | −32 B | 8.38 MB | 99.9% | 257 MB/s | 215 / 279 / 284 ms |
| E4 | **IPC** | 550k/s | **0.52** / 0.14 / 0.19 / 0.15 | 31.4 MB | — | **29.8%** | 70 MB/s | **66 µs** / 135 µs / 27 ms |
| E5 | **UDP, 8 KB** | 550k/s | **0.45** / 0.11 / 0.15 / 0.15 | 8.39 MB | **0** | **26.3%** | 262 MB/s | **80 µs** / 144 µs / 65 ms |
| E6 | UDP, 1,408 B | 650k/s | 1.53 / 0.57 / 0.67 / 0.26 | 832 B | 8.39 MB | 99.9% | 309 MB/s | 441 / 442 / 449 ms |
| E7 | **IPC** | 650k/s | **0.51** / 0.15 / 0.19 / 0.14 | 30.3 MB | — | **35.2%** | 83 MB/s | **66 µs** / 135 µs / 24 ms |
| E8 | **UDP, 8 KB** | 650k/s | **0.44** / 0.11 / 0.15 / 0.15 | 8.39 MB | **0** | **29.4%** | 310 MB/s | **83 µs** / 149 µs / 72 ms |
| E9 | UDP, 1,408 B | 550k/s | 1.82 / 0.70 / 0.80 / 0.28 | 2.5 KB | 8.38 MB | 99.9% | 245 MB/s | 415 / 522 / 522 ms |
| E10 | **IPC** | 550k/s | **0.55** / 0.16 / 0.21 / 0.15 | 32.2 MB | — | **31.6%** | 70 MB/s | **67 µs** / 155 µs / 22 ms |
| E11 | **UDP, 8 KB** | 550k/s | **0.47** / 0.11 / 0.17 / 0.17 | 8.39 MB | **0** | **26.1%** | 262 MB/s | **84 µs** / 195 µs / 50 ms |
| E12 | UDP, 1,408 B | 650k/s | 1.53 / 0.56 / 0.67 / 0.27 | 672 B | 8.37 MB | 99.9% | 310 MB/s | 441 / 443 / 449 ms |
| E13 | **IPC** | 650k/s | **0.52** / 0.15 / 0.19 / 0.14 | 32.8 MB | — | **34.8%** | 83 MB/s | **72 µs** / 184 µs / 54 ms |
| E14 | **UDP, 8 KB** | 650k/s | **0.45** / 0.11 / 0.15 / 0.16 | 8.39 MB | **0** | **30.5%** | 310 MB/s | **82 µs** / 144 µs / 39 ms |

Means from each run's `engine-latency.hgrm` footer. Headroom and "sender behind" are stream 102's
`pub-lmt − pub-pos` and `pub-pos − snd-pos`, sampled over the middle 40% of the load. "Bytes sent"
is the driver-wide counter (egress plus ingress; ingress only under IPC egress). Client latency is
`most load`'s response time, which equals its service time in every row. Duty is the engine service
thread's `duty-ns`.

**The prediction held in every run.** Every UDP run with 1,408 B datagrams at or above 550k/s (E2, E3,
E6, E9, E12) sits on a full egress window, with the sender 8.4 MB behind and the engine at 99.9%. The
cost jump is in `admit` and `match`. At 500k/s (E1) the sender is already 885 KB behind but the
window isn't full. **Egress over IPC** (E4, E7, E10, E13) takes execution reports off the sender
altogether. The engine then costs 0.51–0.55 µs a new order and runs at 30–35% of its core at 550k and
**650k/s**, with a p50 of 66–72 µs. **8 KB datagrams over the same UDP** (E5, E8, E11, E14) move the
same 262–310 MB/s with the sender caught up (0 behind) and the engine at 26–31%. So **the ceiling is
datagrams per second, not bytes**.

**What this re-reads.** D1's step at ~550k/s is this back-pressure. Its refutation read `snd-bpe`,
which is the receiver-window limit, not the engine's. D2's "freeing two cores moved the knee" fits a
sender short of CPU. R7's SHARED→DEDICATED gain is the send path getting a thread of its own. L0–L4's
~275–300k/s is the same thing at 142 MB/s on a slower core. A saturated UDP run's latency is a
standing queue: E6 and E12 read 441–449 ms at p50, p90 and p99 alike, every buffer from the log back
to the gateway full. In such a run, `newOrder`'s mean is 1 s divided by the orders drained, which is
why it reads *lower* at 650k/s than at 550k/s.

**What it does not settle.**
- ~~**Where the knee is with egress on IPC or 8 KB datagrams.** 650k/s was comfortable. The next ceiling
  is unmeasured, and so is Design.md §2's 1M/s at full fan-out.~~ **Measured for IPC egress: see
  "What I1–I3, U1 and K1–K5 say".** Not measured for 8 KB datagrams beyond 650k/s.
- **The tails.** Every unsaturated arm has a p99 of 22–72 ms against a p50 near 70 µs. That's a
  separate question, perhaps pauses in a JVM or the laptop's core count.
- **The lever for production.** IPC egress needs the gateway on the cluster node's own media driver,
  which holds only while that node leads. The MTU is a channel setting, but on a real NIC the receive
  path is no longer charged to the sender's core as it is on loopback.
- **CPU-bound or cadence-bound.** Whether the sender is short of CPU for its syscalls, or limited by
  the one-datagram-per-cycle structure, would take a `/proc` read of the sender at the knee.
- **The duty-cycle disagreement for the cluster host's agents** (Design.md §8). The sender reads 95–98%
  in every arm here, E4's ingress-only 70 MB/s included, so that counter is insensitive to what the
  sender actually does.
- **A second-order effect, unproven:** the engine is cheapest with 8 KB UDP (0.44–0.47 µs), cheaper than
  with IPC (0.51–0.55 µs). Under IPC the busy-spinning gateway reads the engine's publication tail
  directly, so contended cache lines are a candidate explanation.

**Conditions (E1–E14).** Apple M4 Pro, 14 cores (10P+4E), macOS 15.7.9, OpenJDK 21.0.11, commit
`2021b61`+ (the `+` is the `EGRESS_CHANNEL` knob only). Single node, `DRIVER_THREADING=DEDICATED`,
archive `SHARED`, ten securities, `run-attribution.sh` with metrics and stages on, `ORDERS` = rate ×
8 (8 s of load), band 99.90–100.10, `maxOrders=1000000` per security. Counters sampled mid-load
(`COUNTERS_MATCH='duty|pub-pos|pub-lmt|snd-pos|snd-lmt|sub-pos|rcv-pos|Bytes sent'`). `EGRESS_CHANNEL` is
`aeron:udp?endpoint=localhost:0` (UDP, 1,408 B), `aeron:ipc`, or `aeron:udp?endpoint=localhost:0|mtu=8192`
(lo0's MTU is 16384 and `net.inet.udp.maxdgram` 9216). Firefox was closed first and the Docker
containers were idle (<0.3%). The load average of 3.8–7.1 is the runs' own residue, back to back.
E1–E2 23:25–23:27, E3–E14 interleaved 23:27–23:36 (local time), 2026-09-29.

---

## What I1–I3, U1 and K1–K5 say: with egress on IPC the knee is ~1.5M/s, and at 2.1M/s the engine is full

**The question.** E1–E14 showed that IPC egress removes the step at 550k and 650k/s. Where does the
shard stop now? `run-sweep.sh` was run with `EGRESS_CHANNEL=aeron:ipc`, twice from 600k/s to 1.2M/s
(I1, I2) and once from 1.2M/s to 2.5M/s (I3), with a same-day UDP-egress control sweep between them
(U1). Each used 2,000,000 orders per rate rather than the default 300,000, because the E-series showed
that a ~0.5 s burst can hide saturation that a longer run exposes. Then `run-attribution.sh` held
single rates for 8 s at 1.0M and 1.2M/s and for 4 s at 1.5M, 1.8M and 2.1M/s (K1–K5), with every
duty cycle and the stream positions sampled.

**The sweeps.** The UDP control knees between 500k/s (U1a: p50 179 µs, p99 15.0 ms) and 600k/s (U1b,
`SATURATED`). With IPC egress, every valid rate up to **1.8M/s aggregate** kept up. The p50 ran from
53 µs at 700k/s to 115 µs at 1.8M/s, and **1M/s held a 59–63 µs p50 and a 259–266 µs p99** (I1d, I2d).
The first IPC rate to saturate was 2.5M/s (I3d: p50 51.5 ms). One rate per sweep was `INVALID` on the
generator's pacing and is not recorded: 600k/s in I1, 700k/s in I2, and 2.1M/s in I3 (pacing p99.9
1.09 ms, p50 557 µs).

**Held for seconds rather than a burst:**

| # | rate | load | newOrder mean | engine / gateway / md duty | egress headroom | engine behind on log | client p50 / p90 / p99 | pacing p99.9 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| K1 | 1.0M/s | 8 s | 0.49 µs | 52% / 62% / 23% | 31.6 MB | 5 KB | **83 µs / 177 µs** / 44 ms | 133 µs |
| K2 | 1.2M/s | 8 s | 0.48 µs | 60% / 70% / 27% | 30.9 MB | 2 KB | **98 µs / 278 µs** / 58 ms | 469 µs |
| K3 | 1.5M/s | 4 s | 0.48 µs | 73% / 82% / 33% | 32.5 MB | 0 | 150 µs / 76 ms / 87 ms | 1.41 ms ✗ |
| K4 | 1.8M/s | 4 s | 0.45 µs | 88% / 92% / 40% | 31.8 MB | 1 KB | 491 µs / 111 ms / 126 ms | 3.74 ms ✗ |
| K5 | 2.1M/s | 4 s | 0.43 µs | **99.7%** / 93% / 48% | 32.7 MB | **20.9 MB** | 99.5 ms (saturated) | 2.27 ms ✗ |

✗ = the generator's pacing broke the 1 ms limit that `run-sweep.sh` enforces. Those rows measured
the harness as well as the shard. There were no rejects in any row, and 2–3 of 6–9.6M orders went
unanswered in each. Ingress headroom was 8.39 MB at every sample. Duty and headroom were sampled over
the middle 40% of the load; latency is the response time, which equals service time throughout.

**What binds.** At 2.1M/s it's the **engine's service thread, and this time genuinely.** Egress and
ingress both have megabytes of headroom, and the engine is 20.9 MB behind on its input log. Its mean
cost of 0.43 µs a new order × 2.1M/s fills the core (stages on; four clock reads of ~37 ns are inside
that figure). The gateway is right behind it at 93%. Between 1.5M and 2.1M/s three things overlap and
this machine can't separate them:
- the one `most load` process is past its own pacing limit;
- the shard's busy threads outnumber the 10 performance cores again, the D1–D2 regime this time for
  real;
- the engine and gateway are approaching full.

K3's p90 of 76 ms at 1.5M/s, against I3b's 150 µs in a 1.3 s burst, is the difference between a burst
and a held rate. The gateway's `clusterBackpressure` (offers refused by the cluster ingress) is 61 at
1.0M/s, 92k at 1.2M/s, 548k at 1.5M/s and 2.4M at 2.1M/s. That's ingress refusing in bursts, sampled
headroom notwithstanding, and it grows with the rate.

**So, on this laptop, single node, with egress on IPC:** 1.0M/s aggregate (Design.md §2's 100k/s per
security at full fan-out) is carried for 8 s with a p50 of 83 µs and a p90 of 177 µs, and 1.2M/s with
98 µs and 278 µs. The knee is ~1.5M/s held, ~1.8M/s in a burst. By 2.1M/s the engine thread itself is
full. **With UDP egress the same machine knees at ~0.5M/s** (U1, E1–E3).

**What it does not say.**
- **This is not multi-node.** A 3-node cluster sends the log to each follower over UDP (~128 B per
  order per follower) through the same driver sender. With 1,408 B datagrams, the datagram-rate limit
  of E1–E14 should return there. That's a prediction, not a measurement.
- **Where production's gateway is.** IPC egress needs the gateway on the leader's media driver, which
  the production topology in the Operator's Manual (§3.2) doesn't have.
- **The p99 tails** of 44–58 ms in K1–K2 are the same unexplained tails as E1–E14's.
- **A host with a core per thread and a second generator** is what would separate the engine, the
  gateway and the harness above 1.5M/s.

**Conditions (I1–I3, U1, K1–K5).** As E1–E14: Apple M4 Pro, 14 cores (10P+4E), macOS 15.7.9, OpenJDK
21.0.11, commit `2021b61`+ (the `+` is the `EGRESS_CHANNEL` knob and uncommitted documents; no source
change). Single node, `DRIVER_THREADING=DEDICATED`, archive `SHARED`, ten securities, band
99.90–100.10, `maxOrders=1000000` per security, one `most load` process through one gateway. The
sweeps ran metrics off, 2,000,000 orders per rate, 200,000-order discard pass first. K1–K5 ran
`run-attribution.sh`, metrics and stages on, `ORDERS` = rate × seconds. Firefox closed, the Docker
containers idle. The load average of 1–6 is the runs' own residue, back to back. Sweeps 23:50–23:55,
K3–K5 23:56–23:58, K1–K2 23:58–00:00 local time, 2026-09-29/30.

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
| A6 | 2026-09-29 | `cf6e302`+ | 10 | 100k/s | 10k/s | 42.1 µs | 0.2 µs | 1.4 µs | 0.3 µs | 1.9 µs (**4.5%**) | 40.2 µs (95.5%) |
| A7 | 2026-09-29 | `cf6e302`+ | 10 | 300k/s | 30k/s | 536 ms ✗ | 0.2 µs | 1.3 µs | 0.2 µs | 1.8 µs | a draining queue |
| A8 | 2026-09-29 | `cf6e302`+ | 10 | 200k/s | 20k/s | 54.6 µs | 0.2 µs | 1.4 µs | 0.3 µs | 1.9 µs (**3.5%**) | 52.7 µs (96.5%) |
| A9 | 2026-09-29 | `cf6e302`+ | 10 | 250k/s | 25k/s | 90.9 µs | 0.2 µs | 1.2 µs | 0.3 µs | 1.7 µs (**1.9%**) | 89.2 µs (98.1%) |
| A10 | 2026-09-29 | `cf6e302`+ | 10 | 300k/s | 30k/s | 503 ms ✗ | 0.2 µs | 1.4 µs | 0.2 µs | 1.8 µs | a draining queue |

Engine stage split, same runs (`newOrder` p50, and its three stages):

| # | newOrder | admit | match | settle | client p90 | client p99 | client p99.9 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 0.46 µs | 0.13 µs | 0.04 µs | 0.17 µs | 56.4 µs | 1.32 ms | 16.1 ms |
| A2 | 0.50 µs | 0.13 µs | 0.04 µs | 0.17 µs | 56.9 µs | 277 µs | 3.02 ms |
| A3 | 0.38 µs | 0.08 µs | 0.04 µs | 0.17 µs | 348 µs | 35.1 ms | 46.0 ms |
| A8 | 1.40 µs | 0.56 µs | 0.11 µs | 0.42 µs | 146 ms | 163 ms | 166 ms |
| A9 | 1.23 µs | 0.54 µs | 0.09 µs | 0.34 µs | 189 ms | 223 ms | 227 ms |
| A10 | 1.35 µs | 0.60 µs | 0.09 µs | 0.35 µs | 546 ms ✗ | 555 ms | 558 ms |

A6–A10 are on the cloud host of L0–L4 and are comparable only with each other: a 2.0 GHz Zen 3 core
costs ~3x an M4 Pro P-core per order, so their engine columns cannot be set against A1–A5's.

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

## Failover by gateway placement (F1–F2)

**Not a latency or throughput measurement.** These are what a client lost when the leader's node was
stopped under steady load, one run per gateway placement (Design.md §7, "Gateway placement").
`e2e/run-failover.sh`, three members on **one machine**, so they are about how a failover behaves.

Conditions: M4 Pro laptop, working tree on `2eb790e` plus this session's changes, **not idle** (load
average ~6.5, a desktop and an IDE open), JVM, `--driver-threading SHARED`. One security, `most load`
at **2,000 orders/s** paced for 20 s, four participants, band 99.90–100.10. The leader's node (engine,
cluster host and, co-located, its gateway) was SIGTERMed 5 s in.
`aeron.cluster.leader.heartbeat.timeout=2s` on every process; Aeron's default is 10 s, which would
lengthen every window below. Client publication connection timeout at Aeron's default (5 s).

| run | date | placement | egress | blind window | sent | unanswered | `GATEWAY_UNAVAILABLE` | switches | new leader `undeliverableReports` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F1 | 2026-10-03 | independent | UDP, 1,408 B | ~3.5 s (5.0 → ~8.5 s) | 40,000 | 6,412 | 12 | — | 0 |
| F2 | 2026-10-03 | colocated | IPC | ~5 s (5.0 → ~10.0 s) | 40,000 | 9,978 | 19 | 3 on reject, 1 on disconnect | 0 |

### What F1–F2 say

- **The co-located blind window is the client's, not the cluster's.** The gateway dies with its
  node and sends no reject, so the client keeps publishing into the dead endpoint until Aeron's
  publication connection timeout declares it gone (5 s). Then it switches, is refused by a standby
  gateway or two, and lands on the new leader's. The independent gateway survives the node, and its
  window is the election itself. **With the default 10 s heartbeat the election is the longer of the
  two**, and the placements should come out about even. That is unmeasured.
- **Routes followed the active gateway.** `undeliverableReports=0` on the leader that served between
  the two failovers. In the co-located run that leader's gateway was a different process on a
  different node from the one the participants had spoken to.
- **Nothing tells a client what became of an unanswered order** (Design.md §8).
- **One order in each run was answered ~13.6 s after it was sent** (`ack service max=13.6 s`).
  Unexplained.

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

Not yet recorded here, and worth a row when they happen: a multi-node cluster *across machines*
(F1–F2 are three members on one), an Epsilon soak
measured in hours, an unpinned sweep on a many-core host booted *without* `isolcpus` (the only
unpinned arm that means anything — see "What L0–L4 say"), and a host whose cores are as fast as the
laptop's. A native-binary sweep on x86-64 is L3.
