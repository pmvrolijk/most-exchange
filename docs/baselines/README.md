# Latency baselines

The `.hgrm` histograms `e2e/run-attribution.sh` writes, kept **outside `build/`** so they survive a
`./gradlew clean` and so a later run has something to diff against. They exist to be diffed, not read
(`.claude/skills/perf-claim`): re-run the attribution either side of a change to the core and compare,
rather than comparing a new number against a remembered one.

Each name is `<run directory>--<process>-latency.hgrm`. The runs correspond to rows in
[`../Measurements.md`](../Measurements.md):

| File prefix | Run | Scope |
| --- | --- | --- |
| `attr-1sec-100k` | A1 | 1 security, 100k/s, idle machine — the re-baseline |
| `attr-10sec-100k` | A2 | 10 securities, 100k/s aggregate |
| `attr-10sec-250k` | A3 | 10 securities, 250k/s aggregate |
| `attr-10sec-250k-repeat` | A3′ | A3 repeated, for the variance estimate (0.7%) |
| `attr-10sec-250k-ramdisk` | A4 | A3 with the consensus log and archive on a RAM disk |
| `attribution`, `e2e` | — | Older captures, kept for continuity |
| `linode-pinned-10sec-100k` | A6 | Cloud host, 16 cores, pinned, `DEDICATED`, 100k/s aggregate |
| `linode-pinned-10sec-200k` / `-250k` / `-300k` | A8 / A9 / A10 | Same host, stages on, every counter sampled |
| `linode-pinned-10sec-200k-thp` / `-thp-control` | — | The huge-pages A/B at 200k/s (engine `-XX:+UseTransparentHugePages` vs not; last repeat of each) |
| `laptop-egress-{udp,ipc,mtu8k}-{550k,650k}` | E3/E4/E5, E6/E7/E8 | Laptop, `DEDICATED`, 10 securities, the gateway's egress on UDP 1,408 B / IPC / UDP 8 KB datagrams |

The `attr-*`, `attribution` and `e2e` files are single-node, JVM, metrics on, `ThreadingMode.SHARED`
for the driver — so they are **not** comparable to a `--driver-threading DEDICATED` run (Design.md
§7). The `linode-*` files are `DEDICATED`, on a different machine (AMD EPYC 7713 VM at 2.0 GHz,
every agent pinned to its own core), and are comparable only with each other. The `laptop-egress-*` files
are `DEDICATED` on the laptop, and the UDP ones are saturated runs whose engine figures include spinning
on a full egress publication (Measurements.md E1–E14). Conditions in full beside the rows
in `Measurements.md`.

Regenerate with `ATTRIBUTION_DIR=... SECURITIES=n ./e2e/run-attribution.sh`, then copy the `.hgrm`
files here with a name that says which run they came from.
