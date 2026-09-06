---
name: decision-fork
description: Use when a choice while working on most-exchange would change a boot path, a wire format, a persistence guarantee, a failure mode, or where state lives. Presents the fork with options and a recommendation instead of guessing, and records the answer where the next session will find it.
---

# Escalating a decision

Thirty-six questions across this build were asked this way, and nearly all were answered by taking
the recommendation. That is the point: the question is not a request for help, it is a decision
record being written before the code exists.

## When to stop and ask

Ask when the answer changes one of:

- **a boot path** — "does Postgres go on the engine's boot path?" (answer: no, DB authors, file boots)
- **a wire format** — a field added, a message split, a version bump other processes must handle
- **a persistence guarantee** — "what should a snapshot do when the ladder geometry changed?"
  (answer: refuse to start)
- **a failure mode** — what gets dropped, what gets retried, what refuses
- **where state lives** — the decision that moved `origQty` from the gateway into the engine, and
  with it made gateway HA a deployment choice instead of an open question

Do **not** ask about things with a conventional default, or facts checkable in the codebase. Check
them and proceed.

## How to ask

- Two to four **concrete** options. Not "how should we do X" — name the alternatives.
- Mark one **(Recommended)** and put it first. A recommendation is not a formality; it is the
  analysis, and the user is mostly confirming it.
- State the trade-off each option makes, in the terms of this system: determinism, allocation,
  latency, what a node does on restart, what an operator has to do.
- Say what is **out of scope** of the option you recommend, so the answer does not imply more than
  it settles. "Enforcement (`UNAUTHORIZED_PARTICIPANT`) is out of scope" was written into the plan
  before the work began and stayed true.

## After the answer

The answer is not recorded by having been said in chat.

- Write it into **`docs/Design.md`** if it is normative, with the reasoning.
- Write the rule it implies into **`CLAUDE.md`** if a future session could violate it unknowingly,
  and its reasoning into **`docs/Rationale.md`**.
- Note what it left open in **Design.md §8** and `docs/Status.md`.

## When a decision reverses

Mark the old record **superseded by §x**; do not rewrite it away. `origQty` lived in the gateway,
then in the engine, and both the handover and CLAUDE.md say so outright — "This reversed a
documented decision." A reversal that is edited out of history looks like a decision nobody took,
and the reasons it was reversed are the most valuable part.
