---
name: session-open
description: Use at the start of any work session on most-exchange — when the user opens with "shall we continue", "next on the list", "what's next", references docs/Status.md or docs/Handover.md, or names a work item to pick up. Rebuilds context from the written state and verifies the machine is clean before anything is started.
---

# Opening a session

Context comes from the written state, not from memory and not from reading source. Six of the
first nine sessions on this repo opened this way and it is why they resumed cleanly; the other
three cost time re-deriving what was already written down.

## 1. Read the state, in this order

1. **`docs/Status.md`** — where things stand, open issues, to do next, picking this up. This is the
   short file and it is the one that matters. Read all of it.
2. **`CLAUDE.md`** — the rules. Already in context at session start; do not re-read it wholesale,
   but consult the section covering the area about to be touched.
3. **Only if the work needs it:** `docs/Design.md` for the normative clause on the behaviour being
   changed, `docs/Rationale.md` for why a rule exists, `docs/Handover.md` for the work record of a
   past change (§2a–§2h).

Do not read `docs/Handover.md` in full to start a session. It is a 120 KB archive.

## 2. Verify the machine before starting

Inexplicable behaviour is an environment before it is a bug — this cost a long debugging stretch
once and is the third row of `docs/LocalTesting.md`'s troubleshooting table.

```sh
pgrep -f "nl.lamia.most.exchange" || echo "clean"
git status --short && git log --oneline -3
```

- Any surviving process must be stopped **before** deleting an Aeron directory, never after.
- A node cannot restart for ~10 s after the previous one stopped — the archive and cluster mark
  files carry a liveness timestamp.
- Uncommitted work from a previous session is a question for the user, not something to absorb
  silently or to clean up.

## 3. State the scope back

Before writing anything, say in one or two sentences what this session is going to do and which
open item it closes. If the answer is not in `docs/Status.md` §to do next, say that too — it may
be right, but it is a deviation and the user should see it named.

If the work turns out to need a decision that changes a boot path, a wire format, a persistence
guarantee or a failure mode, stop and use the `decision-fork` skill rather than choosing.
