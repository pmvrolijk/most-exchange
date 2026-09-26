# most-exchange — Building with AI — speaker notes

Source artifact: https://claude.ai/artifact/3iCccCmksZuR3fFwpz8bSr

The HTML export drops speaker notes, so they are kept here. This file is generated from
the artifact's slides: regenerate it after changing a slide, never edit it by hand.

## 1. Building most-exchange with AI

The companion to the architecture tour. That one covered what most-exchange is; this one covers how it was built. The whole exchange was built in conversation with Claude Code, over about ten days of sessions. The prompts on the right are real ones from the transcripts. What made that work was not any single prompt but a method: start from a design, keep the state written down, and run every session through the same cycle.

## 2. What ten days of sessions produced

First, the scale. Ten days with sessions. About twenty-five thousand lines of Kotlin across eight modules, a Vue console, and 463 tests. Forty-one commits, every one of them made at the human's decision. The number I find most telling is the tool mix: in the first nine days, over two thousand shell commands against fewer than a hundred file reads and writes. The system was run constantly — compiled, started, loaded, measured — not written and hoped over. And seven project skills now describe how a session runs.

## 3. It started with a design, not with code

The project did not start with "write me an exchange". It started with a technical design that already existed, and a request to review it against a hard target. The first two and a half hours were specification: ten open questions answered in one prompt, then the volatility halt, collars and self-match prevention pinned down. Only then did the first code appear. The human steered throughout — at 19:50 a correction that the gateway speaks binary SBE and FIX belongs in separate adapters, which is still the architecture today. Design.md remains the authority: behaviour changes there first, in the same commit as the code.

## 4. Five documents, five jobs

An AI session starts with no memory. What carries the project from one session to the next is written state, and here it is split into five documents with different jobs and different voices. Design.md is the specification. CLAUDE.md is terse rules, each one the residue of a defect, and each points to a section of Rationale.md that explains why. Status.md is short and says where things stand — it is what every session reads first. Handover.md is the archive: how each change happened, never rewritten. Keeping these apart is what stops the context from going stale.

## 5. Every session runs the same five steps

This is the loop, and it is the same every session. Open by reading the written state and checking the machine is clean. Decide anything architectural explicitly, with options and a recommendation. Build in small steps — the human reviews and commits each one. Verify against the real system: the build, the end-to-end scripts, a measurement. Close by writing the state back down and proposing a commit message. The next session starts from exactly what this one wrote. On 26 September a whole feature went through as seven steps on a branch, each committed by the human before the next began.

## 6. A session reads the state, then writes it back

The two rituals at either end of the cycle are skills now, and they fire on ordinary phrases. "Shall we continue" triggers session-open: read Status.md, check there are no stray engine processes or uncommitted work, and say back what the session will do. "Let's wrap up" triggers session-close: update Status.md, add a rule to CLAUDE.md only if a future session would otherwise fall into the same trap, and propose a commit message that states what was measured rather than just what was written. It never commits. The habit came first — six of the first nine sessions opened this way — and the skill made it reliable.

## 7. The AI proposes; the human decides and commits

The division of labour is explicit. Where a choice would change a boot path, a wire format, a persistence guarantee or a failure mode, Claude stops and asks, with options and a recommendation. Thirty-six such questions in the first nine days; most were answered by taking the recommendation, and that is fine — the value is that the decision is recorded before the code exists. The big architectural turns came from the human, like moving participant enforcement wholly into the gateways on 26 September. And commits are the human's: that was a deliberate choice when the skills were written, and it is a rule in CLAUDE.md.

## 8. Seven procedures, loaded when they apply

The method lives in seven project skills, checked into the repository. Claude loads one when its trigger applies. Two are the session rituals. Decision-fork makes architectural choices explicit. Three are about honesty in testing and verification: tests take their expected values from the specification, lifecycle changes are checked on the real system, and any wire change runs a checklist and the end-to-end script. Perf-claim makes every number carry both latencies and its conditions. None of them was invented from scratch; each one was lifted from a habit the transcripts showed already working.

## 9. Five habits that keep the claims honest

The failure mode of AI-assisted development is an agent that looks productive while the ground truth drifts away from the story. Five habits guard against that. Run the real system — the end-to-end script caught five defects that unit tests missed. Treat warnings as errors everywhere. Write test expectations from the specification, because a test written after the code once asserted the bug. Give every number its conditions, which is how a knee measured with a desktop open was caught as 15% low. And state claims at the precision they were earned: sent is not applied, builds is not runs.

## 10. The method was analysed, then improved

After nine days the build was going well, and the question was why. On 6 September Claude analysed all twelve session transcripts and the git history, and produced a retrospective. It named the seven-step method that had been running from memory, and ranked the gaps. Most recommendations were adopted the same evening: CLAUDE.md split into rules and rationale, a short Status.md as the entry point, the seven skills, a measurements table, and a CI pipeline on GitLab. One was declined — the human kept control of commits. And the analysis prompt itself became a user-level skill that can be run on other projects.

## 11. The last two sessions, step by step

Here is the method in the two most recent sessions. On 25 September the question was where time goes at high load. Each step was a measurement that ruled something in or out: attribution showed the exchange's own code is one to two percent of a round trip; a RAM-disk run ruled out storage; Aeron's counters pointed at the media driver's shared thread; and dedicated driver threads bought 1.6 times the throughput. On 26 September the first CI pipeline was fixed through the GitLab connection, then a design change proposed by the human went in as seven committed steps, was tested live on the Docker stack — which found missing seed data — and the manual's screenshots were retaken by driving Chrome.

## 12. The model writes the code. The method is written down.

To sum up: the steadiness did not come from the model or from any clever prompt. It came from a method that is written down. Start from a technical design and keep it the authority. Make every session both consume and produce written state, so the next one resumes from prose rather than memory. Verify against the running system, not a mock. And keep decisions and commits with the human. All of it is in the repository — the skills, Status.md, the design, and the rules with their reasons.
