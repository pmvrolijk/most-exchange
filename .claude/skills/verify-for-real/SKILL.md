---
name: verify-for-real
description: Use when a change to most-exchange touches process lifecycle, idle behaviour, startup or shutdown ordering, recovery, back-pressure, or anything a test double stands in for — and when behaviour is inexplicable and the code looks right. The rule behind every defect this project's unit tests missed.
---

# Verifying against the real thing

Five defects reached working code and were caught only by running the system. Every one of them
lived in a category the suite did not cover, or behind a fake that was easier than reality.

## A convenient fake validates nothing

The engine overwrote Aeron's cluster session header **because** the test `ClientSession` did not
reserve `SESSION_HEADER_LENGTH`. The tests validated a layout the real cluster rejects, and passed.

- If a double stands in for something, model the awkward part of it — the header reservation, the
  back-pressure return, the timeout.
- `ExclusivePublication` is `final` with no interface, which is why `AeronAllocationTest` launches
  an embedded media driver rather than faking it. When a fake cannot reach a path, run the real one.
- Prefer one e2e assertion on the real stack over three unit tests on doubles.

## The categories that are absent, not badly tested

Check these explicitly, because nothing else will:

- **idle** — a cluster client must send keepalives; the consensus module closes a session after
  `sessionTimeoutNs` (10 s) of silence and every later offer fails *silently*.
- **shutdown** — `ShutdownSignalBarrier` must be closed, `await()` alone leaves the JVM alive, and
  anything printed at shutdown must be inside the barrier block. A native image additionally needs
  `--install-exit-handlers` or SIGTERM kills it outright and no counter is ever printed.
- **startup ordering** — what happens when a subscriber is not yet listening, which is exactly when
  a recovered engine wants to publish its book image.
- **restart** — `e2e/run-restart.sh` is the only check that state survives one. `run-e2e.sh` wipes
  everything and starts fresh, so it has never once shown that anything survives.

## Silent failure is the expensive kind

The keepalive death, the dropped orders and the rejected `SecurityDefinition` are the same shape:
no log line, no exception, a counter at best. `Image.poll` swallows an exception from its fragment
handler and advances the position anyway — a bad order during a restore does not crash the node, it
is dropped and the book comes back quietly wrong.

**Counters must distinguish causes, not just count.** `liveOrders=0 rejectedLocally=0` looked like a
clean gateway and meant either "never received" or "forwarded but nothing came back". Adding
`forwardedToCluster` / `sentToClient` made the bug obvious in one line.

## When behaviour is inexplicable, verify the environment first

This cost the single longest debugging stretch of the build: commands reporting success while
nothing reached the engine, caused by a teardown where `pkill` silently failed and the Aeron
directory was deleted out from under still-running processes.

```sh
pgrep -f "nl.lamia.most.exchange"   # must be empty first
df -h /aeron        # in the driver's container — a full tmpfs reads as a JVM InternalError
```

Stop processes **before** deleting directories, never after. Check the environment before reading
the stack trace.

## Documentation counts as verification

Every command in `docs/LocalTesting.md` and `docs/ControlPlane.md` was executed before it was
written down, and doing so found two defects and one phantom. Do not write a command into a doc
that you have not run.
