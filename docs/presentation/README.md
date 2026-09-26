# Presentations

Two five-minute decks, made on 26 September 2026. The `.html` files here are standalone exports.
The editable source of each deck is a claude.ai Slides artifact.

| Deck | Slides | Export | Source artifact |
| --- | --- | --- | --- |
| **Architecture Tour** — what the exchange is, how it runs in production, what has been measured | 14 | [`most-exchange — Architecture Tour.html`](most-exchange%20—%20Architecture%20Tour.html) | https://claude.ai/artifact/9b3UrvxHuQ8yurQJCRKUDZ |
| **Building with AI** — how it was built with Claude Code: design first, written state, the session cycle | 12 | [`most-exchange — Building with AI.html`](most-exchange%20—%20Building%20with%20AI.html) | https://claude.ai/artifact/3iCccCmksZuR3fFwpz8bSr |

The second deck builds on the 6 September retrospective, *Why This Build Stayed Steady*:
https://claude.ai/artifact/7D23zbak98bxKhtsVVtqGk.

The artifacts are private to their owner until they are shared from the page's Share menu. The
exports need no access.

## Changing a deck

**Edit the artifact, then export again. Never edit an export by hand.** An export is generated
output: a change made to it is lost at the next export, and it never reaches the artifact. Every
slide carries speaker notes; keep them in step with the slide.

**Update the figures from the documents they came from, not from memory.** Each number in a deck
has a source here (below), and those documents move.

## Design decisions

### Look

- **Every slide is dark** (`#0E131A`, cards `#17202B`). This matches the operator console in the
  screenshots and keeps the deck continuous. An earlier version alternated light and dark slides,
  and it read as two different presentations.
- **Two typefaces:** IBM Plex Sans for text and JetBrains Mono for labels, identifiers and times.
  Anything in monospace is a real name: a process, a file, a field, a timestamp.
- **Three accent colours.** In the Architecture Tour each has a meaning; Building with AI uses
  the same palette without the meanings.

  | Colour | Hex | Meaning in the Architecture Tour |
  | --- | --- | --- |
  | Teal | `#3CC7A6` | most-exchange itself: the cluster, the engine, the hot path. Bids in the ladder |
  | Blue | `#6AA8F0` | the gateways, and the phases before continuous trading |
  | Orange | `#F2A052` | outside the project or off the normal path: the FIX tier, asks, the halt path |

- **Colour never carries a meaning alone.** Text says it too: the production diagram's footer
  reads "Orange: FIX tier, outside this project", and every box and step is labelled.
- **A stat row uses fixed-width flex cells, not a `1fr` grid.** In the Slides renderer the grid
  let captions run into the neighbouring column. A fixed `width` with `flex:none`, wide gaps and a
  `<br>` placed by hand keep six stats apart. Do the same for any row of numbers with captions.

### Content: Architecture Tour

- **The shard comes before the rules** (slide 1). Determinism, the no-wall-clock rule and zero
  allocation only make sense once the audience knows it is one log, applied on three machines. The
  slide also says why Aeron Cluster was chosen: it replaced a hand-rolled primary and hot spare
  that could double-publish after a bad failover (Design.md §1).
- **The engine gets two slides:** the order pool (one order, one cache line) and the price ladder
  (a price is an array index). These are the two data structures the 1 µs budget depends on.
- **The production slide includes FIX adapters, labelled as outside this project.** Design.md §1
  and README say both boundaries are binary SBE. The adapters are shown because a participant
  cannot trade without them, and the legend states the boundary.
- **FIX adapters are not called stateless.** They keep FIX session state for sequence numbers and
  resends. Only the gateway and market-data are claimed to be replaceable at will.
- **The production topology comes from `ProdDeployment.md` §2 and has not been run.** The deck
  does not claim it has.
- **Figures follow the `perf-claim` rules.** Every rate states its conditions in the footer:
  single node, Apple M4 Pro, JVM 21, ten securities, and the driver threading mode. The ~550k/s
  ceiling (DEDICATED) is given next to ~350k/s (SHARED). The missed 1M/s target is on the slide
  as an open question, not left out.
- **UI images are the Operator's Manual screenshots** from `docs/manual/assets/`. The dev stack
  was not driven for them. The books screenshot shows AAPL halted.

### Content: Building with AI

- **Every quote is a real prompt from the session transcripts.** Some are shortened. None is
  paraphrased into something that was not said.
- **The first-session timeline is the argument for design first.** It shows two and a half hours
  of specification before the first code, and the 19:50 correction that the gateway speaks SBE.
- **The human's role is on its own slide:** decisions, direction and commits. The recommendation
  the user declined in the retrospective, automatic commits, is shown as declined.
- **Figures for the first nine days are the retrospective's and were not recounted:** 36
  questions, 2,017 shell commands against 92 file operations, 12 transcripts. The counts for the
  whole project come from the repository on 26 September: 41 commits, ~25,600 lines of Kotlin,
  ~3,500 of Vue, 463 tests, document sizes.

## Sources

| Deck | Drawn from |
| --- | --- |
| Architecture Tour | `docs/Design.md` §1–§4, `docs/ProdDeployment.md` §2 and §7.3, `docs/Status.md`, `docs/Measurements.md`, `CLAUDE.md`, `docs/manual/assets/ui-*.png` |
| Building with AI | the session transcripts, `git log`, the retrospective artifact, `.claude/skills/*/SKILL.md`, `CLAUDE.md`, `docs/Status.md` |
