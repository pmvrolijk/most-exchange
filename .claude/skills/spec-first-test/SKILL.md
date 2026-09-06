---
name: spec-first-test
description: Use when writing or changing a test for behaviour docs/Design.md specifies, and always when writing a test for code that already exists. Counters the defect class this project diagnosed and never fixed — a test written after the implementation encodes the implementation, including its bugs.
---

# Writing the test from the specification

Recorded in `docs/Handover.md` §6, from this repo's own history:

> The cancel test was named "reports the filled portion" and asserted the buggy value. Writing the
> expected value from the specification, before looking at what the code returns, would have caught
> it.

That defect — `cumQty` derived as `origQty - leavesQty`, reporting a cancelled order as fully
filled — reached working code and was found by running the system, not by the suite that covered it.

## The procedure

1. **Find the clause first.** Locate the `docs/Design.md` section that specifies the behaviour.
   Cite it in the test name or in a comment on the assertion: `// Design.md §4.4 — a self-match
   never prints a trade`.
2. **Write the expected value from the clause.** Compute it by hand from the specification. Do not
   run the code first, and do not read the implementation to find out what it produces.
3. **If the code disagrees, the code is the suspect** until the clause is shown to be wrong. Say
   which one you concluded is at fault and why — do not silently change the expectation.
4. **No clause covering it? That is the finding.** Say so. The missing specification is more
   valuable than the test; write the clause into Design.md, get it confirmed if it is a real
   decision, then write the test against it.

## What this catches that coverage does not

A test written from the implementation passes forever and proves nothing. The suite's blind spots
on this project were never badly-written tests — Handover §6 names them as **absent, not tested
badly**:

- idle behaviour (the cluster session that died after 10 s of silence)
- shutdown (nothing exited on SIGTERM)
- startup ordering
- anything a convenient test double stood in for

When a test needs a fake, check the fake models the awkward part of reality. `ClientSession.tryClaim`
reserves `SESSION_HEADER_LENGTH`; the fake that did not reserve it validated a layout the real
cluster rejects.

## Naming

Name the test for the specified behaviour, not for the observed one. "reports the filled portion"
described what the code did. "a cancelled order reports cumQty of the part that filled, not origQty"
describes what the specification requires, and would have failed.
