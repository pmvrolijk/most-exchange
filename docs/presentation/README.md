# Presentations

Three five-minute decks, made on 26 September 2026. The `.html` files here are standalone exports,
and each has a Markdown file of speaker notes beside it. The editable source of each deck is a
claude.ai Slides artifact.

| Deck | Slides | Export | Speaker notes | Source artifact |
| --- | --- | --- | --- | --- |
| **Architecture Tour** — what the exchange is, how it runs in production, what has been measured | 14 | [`most-exchange — Architecture Tour.html`](most-exchange%20—%20Architecture%20Tour.html) | [notes](most-exchange%20—%20Architecture%20Tour%20—%20speaker%20notes.md) | https://claude.ai/artifact/9b3UrvxHuQ8yurQJCRKUDZ |
| **Building with AI** — how it was built with Claude Code: design first, written state, the session cycle | 12 | [`most-exchange — Building with AI.html`](most-exchange%20—%20Building%20with%20AI.html) | [notes](most-exchange%20—%20Building%20with%20AI%20—%20speaker%20notes.md) | https://claude.ai/artifact/3iCccCmksZuR3fFwpz8bSr |
| **Inside the Code** — the module layout, then short code extracts from the core processes, the Docker stack, the e2e scripts and the build | 13 | [`most-exchange — Inside the Code.html`](most-exchange%20—%20Inside%20the%20Code.html) | [notes](most-exchange%20—%20Inside%20the%20Code%20—%20speaker%20notes.md) | https://claude.ai/artifact/2ChRmiw7ktcktJ93EGgArJ |

The second deck builds on the 6 September retrospective, *Why This Build Stayed Steady*:
https://claude.ai/artifact/7D23zbak98bxKhtsVVtqGk.

The artifacts are private to their owner until they are shared from the page's Share menu. The
exports need no access.

## Changing a deck

**Edit the artifact, then export again. Never edit an export by hand.** An export is generated
output: a change made to it is lost at the next export, and it never reaches the artifact.

**The export drops the speaker notes, so each deck's notes are kept in a Markdown file.** The
notes live in the artifact, one per slide; the `— speaker notes.md` file is generated from the
artifact's slide files, slide by slide under each slide's title. After changing a slide, change its
notes in the artifact, export, and regenerate the notes file. Never edit the notes file by hand:
like the export, it is output. Only a Claude session can read an artifact's slide files, so ask one
to regenerate it. The file takes each slide's `<h1>`/`<h2>` as the heading and its `<aside>` as the
text, in the order `project/deck.json` gives.

**Update the figures from the documents they came from, not from memory.** Each number in a deck
has a source here (below), and those documents move.

## Design decisions

### Look

- **Every slide is dark** (`#0E131A`, cards `#17202B`). This matches the operator console in the
  screenshots and keeps the deck continuous. An earlier version alternated light and dark slides,
  and it read as two different presentations.
- **Two typefaces:** IBM Plex Sans for text and JetBrains Mono for labels, identifiers and times.
  Anything in monospace is a real name: a process, a file, a field, a timestamp.
- **Three accent colours.** In the Architecture Tour each has a meaning; the other two decks use
  the same palette without the meanings, except that teal marks the key token in a code extract.

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

### Content: Inside the Code

- **Every extract is quoted from the source, never written for the slide.** Where one is shortened
  the footer says "condensed" and the cuts are marked `…`. Two renamings were made for width only:
  the feed sinks' parameters read `b, o, n`, and the matching loop reads `BUY` for `Side.BUY`.
- **Each extract was chosen for a design decision, not for being central.** The field layout rather
  than the order-book class; `COMMIT` against `CONTINUE` rather than the gateway's main loop; the
  sequence number read before the first snapshot level rather than the whole snapshot cycle.
- **Code is set as text, not as images,** at 24px in JetBrains Mono, with indentation as
  non-breaking spaces. The Slides renderer collapses ordinary spaces and has no `<pre>`. A code
  panel is 1160px wide, which holds about 76 characters a line: keep an extract inside that.
- **The multicast slide shows the dev stack's channels**, which use dynamic MDC. The footer says
  that production names multicast groups in the same properties.

## Sources

| Deck | Drawn from |
| --- | --- |
| Architecture Tour | `docs/Design.md` §1–§4, `docs/ProdDeployment.md` §2 and §7.3, `docs/Status.md`, `docs/Measurements.md`, `CLAUDE.md`, `docs/manual/assets/ui-*.png` |
| Building with AI | the session transcripts, `git log`, the retrospective artifact, `.claude/skills/*/SKILL.md`, `CLAUDE.md`, `docs/Status.md` |
| Inside the Code | `engine/`, `gateway/`, `market-data/` and `reference/` sources, `sbe/src/main/resources/message-schema.xml`, `deploy/docker-compose.yml`, `deploy/config/`, `e2e/*.sh`, `build.gradle.kts`, `gradle.properties`, `.gitlab-ci.yml` |

## Note

The exported HTML contains third-party assets the AGPL doesn't cover.