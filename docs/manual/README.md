# The Operator's Manual

Source for [`../OperatorManual.pdf`](../OperatorManual.pdf). The chapters in `src/` are ordinary
Markdown and are meant to be readable on their own; the PDF is what they are assembled into.

```sh
npm install
npm run build      # writes ../OperatorManual.pdf
npm run html       # stops at build/manual.html — the fast loop for stylesheet work
```

The build renders Markdown to one paginated HTML document and prints it with headless Chrome.
Chrome rather than a LaTeX or wkhtmltopdf toolchain for one reason: the screenshots come from the
running admin UI, so rendering the manual in the same engine keeps a figure and the page around it
consistent. `CHROME_PATH` overrides the browser if it is not in a standard location.

## Layout

```
src/01-introduction.md … src/07-index.md   the chapters, in file-name order
assets/fig-*.svg                           hand-drawn diagrams
assets/ui-*.png                            admin UI screenshots
manual.css                                 the print stylesheet; screen and print are the same design
cover.html                                 the cover page; {{DATE}} is substituted at build time
build.mjs                                  Markdown → HTML → PDF
```

## Markup beyond Markdown

**Callouts.** Four kinds, for four jobs: `term` defines a piece of the system's vocabulary where it
is first needed, `note` and `warning` qualify the procedure around them, and `todo` marks something
specified but not built. The title is optional and defaults to the kind.

```markdown
::: term Fingerprint
A short hash over a shard's published geometry …
:::

::: todo Order-entry authorisation
`UNAUTHORIZED_PARTICIPANT` is raised by nothing …
:::
```

**Figures.** A paragraph containing only an image becomes a numbered figure and the alt text becomes
the caption, so a figure reads correctly in the Markdown source too. Diagrams and screenshots are
styled differently; the distinction is made on the file extension.

**Page breaks.** `<!-- pagebreak -->` starts a new printed page. Chapters always start on one.

## Conventions the text follows

- Section headings carry their own number (`## 4.2 The shard security file`). The number is the
  cross-reference target used throughout, including by the index in section 7, so renumbering a
  section means fixing its references.
- Command transcripts are **real output** from a running system. Regenerate them rather than editing
  them by hand.
- Behaviour that is designed but not built is marked with a `todo` callout at the point an operator
  would expect it to work, and is repeated in the consolidated list in 6.9.

## Refreshing the screenshots

They come from the development stack with its books populated:

```sh
./gradlew installDist
cd deploy && docker compose up -d
# place a few orders so the Books and Status screens have something to show
```

Then drive a headless Chrome through the console at <http://localhost:8081> (`admin` /
`most-dev-password`), signing in and screenshotting each route into `assets/ui-<route>.png` at a
1440×900 viewport and a device scale factor of 2.
