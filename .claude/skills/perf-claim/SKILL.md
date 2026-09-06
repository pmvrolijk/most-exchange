---
name: perf-claim
description: Use before stating any latency, throughput or allocation figure for most-exchange — in chat, a commit message, a doc or a report. Also use when running most load, e2e/run-attribution.sh or e2e/run-epsilon-soak.sh. Enforces both-latencies reporting and records provenance so two numbers can be compared later.
---

# Claiming a number

A figure without its conditions cannot be compared against the next one, and a regression becomes
indistinguishable from a change of machine.

## Read both latencies. Always.

`most load` reports **service time** (from the actual send) and **response time** (from the
scheduled send). Quoting only service time is coordinated omission — it hides exactly the queueing
that appears at the rate you are trying to find. Report both, or report neither.

Also read, and quote when they are not zero:

- **`pacing lateness`** — says whether the generator itself was the bottleneck. If it is large, the
  run measured the harness, not the shard.
- **reject counts** — two things silently invalidate a run: a `maxOrders` too small for the rate
  (everything becomes `BOOK_CAPACITY`) and a price band outside the static collar or the ladder.
  The summary prints these for exactly this reason.

## Record the provenance

Every figure that reaches a document appends a row to `docs/Measurements.md`:

| date | commit | machine | cores | idle? | JVM/native | securities | rate | p50/p99 service | p50/p99 response | notes |

Without the row, a number in prose is a number nobody can reproduce or beat. The repo already
carries figures from at least three different machines under three different loads; they are each
individually honest and collectively incomparable.

## Improvements are diffs, not values

`e2e/run-attribution.sh` writes `.hgrm` files. They exist to be diffed — run it either side of a
change to the core and compare, rather than comparing a new number against a remembered one.

## Allocation claims

Zero allocation is proven by three measurements that must **all** stay green: `AllocationTest`
(fakes), `AeronAllocationTest` (real media driver, covers book-event publication and
`onTakeSnapshot`), and `e2e/run-epsilon-soak.sh` (real Epsilon binary, real cluster).

- The criterion is a **strict majority of eight windows reading exactly zero**, plus under one byte
  per operation overall.
- **Do not widen a tolerance to make a failure pass.** If a workload is so light that a JIT blip
  dominates it, add rounds, not tolerance.
- The soak measures a **slope across two runs**, not a total — ~95 MB of startup pools would
  otherwise swamp the per-order figure.

## State the scope with the number

Every measurement on this system to date is **single-node** and **one security**, where the design
claims ten per shard. Say so. A number that omits its scope will be read as the aggregate.
