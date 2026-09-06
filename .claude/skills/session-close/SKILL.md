---
name: session-close
description: Use when a piece of work on most-exchange is finished and verified, or when the user signals the session is ending ("nice work", "let's wrap up", "anything to add to the handover?", "update the status"). Writes the state back down so the next session can resume from prose. Never commits.
---

# Closing a session

The build's resumability comes from every session producing a written state, not just consuming
one. This is the step that pays the next session.

## Never commit

**Do not run `git commit`, `git push`, or `git add` unless the user explicitly asks in that turn.**
Commits are the user's call. Prepare the work and hand it over:

- Summarise what changed, in files.
- Propose a commit message (see below) as text for the user to take or edit.
- Then stop.

A proposed message states what was **measured**, not only what was written — this repo's history
reads `Local run measures p50 at 50 micros, p99 at 650 micros for 100k/s`, not `add load test`.

## What to write, in this order

### 1. `docs/Status.md` — always

The next session's entry point. Keep it short; it earns its value by being short.

- **Where things stand** — module/test/line counts if they moved, branch, what changed since last
  time in a paragraph.
- **Open issues** — move anything now closed to struck-through with **Done**, and say what is
  *still* open underneath it. A half-closed item that reads as closed is worse than an open one.
- **To do next** — in the order you would tackle them, with the reason for the order.
- **The standing caveats** — never silently drop these: still single-node, still one security.

### 2. `CLAUDE.md` — only for load-bearing traps

Append a rule only if a future session that did not know it would write a defect. Then:

- Imperative voice, second person, one or two sentences.
- State the rule and the failure it prevents. Nothing else.
- **No history.** "It used to wipe them unless told not to" is a war story — that goes in
  `docs/Rationale.md`, linked from the rule.

If the reasoning is longer than two sentences, put the reasoning in `docs/Rationale.md` under the
matching section and leave a `→ Rationale §x` pointer in `CLAUDE.md`.

### 3. `docs/Design.md` — if behaviour changed

Design.md is normative and is edited in the **same commit** as the code. Strike through what §8
closed rather than deleting it; add what opened. Present tense, no history.

### 4. `docs/Handover.md` — for a substantial piece of work

A new `§2x` work record: what the problem was, what was decided, what was built, what the check is.
This is the archive — narrative belongs here, and only here.

### 5. `docs/Future.md` — tick the checkbox with its doc reference.

### 6. `docs/Measurements.md` — if a number was produced

See the `perf-claim` skill. A figure with no row is a figure nobody can compare against later.

## The honesty rules

- Say **sent** where only sent is known, **applied** only where something acknowledged.
- An unverified claim is written as unverified. "Builds" is not "runs"; "runs" is not "measured".
- Record reversals as reversals — mark the superseded section, do not rewrite it away.
- Write the embarrassing open item down. `auctionMaxPasses is in no fingerprint` sitting in
  Design §8 is worth more than a tidy document.
