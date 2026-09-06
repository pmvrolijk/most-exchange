/**
 * Builds docs/OperatorManual.pdf from the chapters in docs/manual/src.
 *
 * Markdown in, one paginated HTML document out, then Chrome's print engine. Chrome rather than a
 * LaTeX or wkhtmltopdf toolchain for one reason: the screenshots in this manual come from the
 * running admin UI, and rendering the manual in the same engine that rendered the UI keeps the
 * type and the colours consistent between a figure and the page around it.
 *
 *   npm install && npm run build
 *
 * CHROME_PATH overrides the browser. --html-only stops after writing build/manual.html, which is
 * the fastest way to iterate on the stylesheet.
 */
import MarkdownIt from 'markdown-it';
import puppeteer from 'puppeteer-core';
import { readFileSync, writeFileSync, readdirSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, 'src');
const BUILD = join(HERE, 'build');
const OUT_PDF = resolve(HERE, '..', 'OperatorManual.pdf');

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean);

const md = new MarkdownIt({ html: true, linkify: false, typographer: true });

/**
 * `::: kind Title` ... `:::` becomes a callout aside.
 *
 * Four kinds, and the manual uses them for four different jobs: `term` defines a piece of the
 * system's vocabulary at the point it is first needed, `note` and `warning` qualify the procedure
 * around them, and `todo` marks something that is specified but not built. Kept as a
 * pre-processing pass rather than a markdown-it plugin because the body has to keep going through
 * the normal renderer -- callouts contain tables and code.
 */
function expandCallouts(text) {
  const lines = text.split('\n');
  const out = [];
  const stack = [];
  for (const line of lines) {
    const open = /^:::\s+(term|note|warning|todo)(?:\s+(.*))?$/.exec(line.trim());
    if (open) {
      const [, kind, title] = open;
      const heading = title && title.trim().length ? title.trim() : defaultTitle(kind);
      out.push(`<aside class="callout callout-${kind}">`);
      out.push(`<p class="callout-title">${escapeHtml(heading)}</p>`);
      out.push('');
      stack.push(kind);
      continue;
    }
    if (line.trim() === ':::' && stack.length) {
      stack.pop();
      out.push('');
      out.push('</aside>');
      continue;
    }
    out.push(line);
  }
  if (stack.length) throw new Error(`unclosed callout: ${stack.join(', ')}`);
  return out.join('\n');
}

const defaultTitle = (kind) =>
  ({ term: 'Term', note: 'Note', warning: 'Warning', todo: 'Not implemented' })[kind];

const escapeHtml = (s) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** `<!-- pagebreak -->` starts a new printed page. */
const expandPageBreaks = (text) =>
  text.replace(/<!--\s*pagebreak\s*-->/g, '<div class="pagebreak"></div>');

/**
 * A standalone image becomes a numbered figure.
 *
 * The alt text is the caption, so a figure reads correctly in the Markdown source too -- these
 * chapters are read directly at least as often as the PDF is.
 */
function numberFigures(html, chapter) {
  let n = 0;
  return html.replace(
    /<p>(<img src="([^"]+)" alt="([^"]*)"[^>]*>)<\/p>/g,
    (_, img, src, alt) => {
      n += 1;
      const caption = alt ? `<figcaption>Figure ${chapter}.${n} &nbsp;${alt}</figcaption>` : '';
      const cls = src.endsWith('.svg') ? 'figure figure-diagram' : 'figure figure-screenshot';
      return `<figure class="${cls}">${img}${caption}</figure>`;
    },
  );
}

/** Stable ids from the heading's own section number, so cross-references survive an edit. */
function slug(text) {
  return text
    .toLowerCase()
    .replace(/<[^>]+>/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}

function anchorHeadings(html, chapterIndex) {
  const toc = [];
  const withIds = html.replace(
    /<h([123])>([\s\S]*?)<\/h\1>/g,
    (_, level, inner) => {
      const plain = inner.replace(/<[^>]+>/g, '').trim();
      const id = `s-${chapterIndex}-${slug(plain)}`;
      toc.push({ level: Number(level), text: plain, id });
      return `<h${level} id="${id}">${inner}</h${level}>`;
    },
  );
  return { html: withIds, toc };
}

function renderToc(entries) {
  const items = entries
    .filter((e) => e.level <= 2)
    .map((e) => {
      const [number, ...rest] = e.text.split(/\s+/);
      const numbered = /^[0-9]+(\.[0-9]+)*\.?$/.test(number);
      const num = numbered ? number.replace(/\.$/, '') : '';
      const label = numbered ? rest.join(' ') : e.text;
      return `<li class="toc-l${e.level}"><a href="#${e.id}"><span class="toc-num">${escapeHtml(
        num,
      )}</span><span class="toc-text">${escapeHtml(label)}</span></a></li>`;
    })
    .join('\n');
  return `<nav class="toc"><h1>Contents</h1><ul>${items}</ul></nav>`;
}

function build() {
  if (!existsSync(BUILD)) mkdirSync(BUILD, { recursive: true });

  const chapters = readdirSync(SRC).filter((f) => f.endsWith('.md')).sort();
  if (!chapters.length) throw new Error(`no chapters in ${SRC}`);

  let body = '';
  const toc = [];
  chapters.forEach((file, i) => {
    const raw = readFileSync(join(SRC, file), 'utf8');
    const html = md
      .render(expandPageBreaks(expandCallouts(raw)))
      // The document is written to build/, so asset references in the chapters -- which are
      // relative to docs/manual/ so the Markdown reads correctly on its own -- are absolutised.
      .replace(/src="assets\//g, `src="${pathToFileURL(join(HERE, 'assets')).href}/`);
    const anchored = anchorHeadings(numberFigures(html, i + 1), i);
    toc.push(...anchored.toc);
    body += `<section class="chapter" id="chapter-${i}">${anchored.html}</section>\n`;
  });

  const css = readFileSync(join(HERE, 'manual.css'), 'utf8');
  const cover = readFileSync(join(HERE, 'cover.html'), 'utf8').replace(
    '{{DATE}}',
    new Date().toISOString().slice(0, 10),
  );

  const doc = `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>most-exchange -- Operator's Manual</title>
<style>${css}</style>
</head><body>
${cover}
<div class="pagebreak"></div>
${renderToc(toc)}
<div class="pagebreak"></div>
${body}
</body></html>`;

  const htmlPath = join(BUILD, 'manual.html');
  writeFileSync(htmlPath, doc);
  console.log(`html  ${htmlPath}  (${chapters.length} chapters, ${toc.length} headings)`);
  return htmlPath;
}

async function toPdf(htmlPath) {
  const executablePath = CHROME_CANDIDATES.find((p) => existsSync(p));
  if (!executablePath) {
    throw new Error(
      `no Chrome found. Set CHROME_PATH, or run \`npm run html\` and print build/manual.html by hand.`,
    );
  }
  const browser = await puppeteer.launch({
    executablePath,
    headless: 'new',
    args: ['--no-sandbox', '--font-render-hinting=none'],
  });
  const page = await browser.newPage();
  await page.goto(pathToFileURL(htmlPath).href, { waitUntil: 'networkidle0' });
  await page.pdf({
    path: OUT_PDF,
    format: 'A4',
    printBackground: true,
    displayHeaderFooter: true,
    margin: { top: '18mm', bottom: '20mm', left: '18mm', right: '18mm' },
    headerTemplate: '<div></div>',
    footerTemplate:
      '<div style="width:100%;font-family:-apple-system,Helvetica,sans-serif;font-size:8pt;' +
      'color:#8a8f98;padding:0 18mm;display:flex;justify-content:space-between;">' +
      "<span>most-exchange &mdash; Operator's Manual</span>" +
      '<span class="pageNumber"></span></div>',
  });
  await browser.close();
  console.log(`pdf   ${OUT_PDF}`);
}

const htmlPath = build();
if (!process.argv.includes('--html-only')) await toPdf(htmlPath);
