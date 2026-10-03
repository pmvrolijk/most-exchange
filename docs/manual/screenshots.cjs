// Manual screenshots, per docs/manual/README.md: the dev stack's console at 1440x900, scale 2.
const puppeteer = require('puppeteer-core');
const OUT = require('path').join(__dirname, 'assets');
const BASE = 'http://localhost:8081';
const ROUTES = ['status', 'operations', 'books', 'shards', 'securities', 'participants',
                'gateways', 'releases', 'schedules', 'operators', 'audit'];
// ONLY=operations-cancel,books retakes just those, leaving every other image as it was.
const ONLY = process.env.ONLY ? process.env.ONLY.split(',') : null;
const wanted = (name) => !ONLY || ONLY.includes(name);
const beat = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: 'new', args: ['--no-sandbox'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 2 });
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle0' });
  await beat(500);
  if (wanted('login')) await page.screenshot({ path: `${OUT}/ui-login.png` });
  await page.type('#u', 'admin');
  await page.type('#p', 'most-dev-password');
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }).catch(() => {}),
                     page.click('button[type=submit]')]);
  for (const route of ROUTES.filter(wanted)) {
    await page.goto(`${BASE}/${route}`, { waitUntil: 'domcontentloaded' });
    // Books and status stream over SSE, so networkidle never settles on data; give them a beat.
    await new Promise((r) => setTimeout(r, route === 'books' ? 3000 : 1200));
    await page.screenshot({ path: `${OUT}/ui-${route}.png` });
    console.log(`ui-${route}.png  ${page.url()}`);
  }
  // Dialogs, which no route shows on its own. Filled in the way an operator would fill them.
  if (wanted('operations-cancel')) {
    await page.goto(`${BASE}/operations`, { waitUntil: 'domcontentloaded' });
    await beat(1200);
    await page.evaluate(() => [...document.querySelectorAll('button')]
      .find((b) => b.textContent.includes("Cancel a participant's orders")).click());
    await beat(400);
    await page.type('#cancel-participant', '7');
    await page.select('#cancel-sec', '1');
    await beat(400);
    await page.screenshot({ path: `${OUT}/ui-operations-cancel.png` });
    console.log('ui-operations-cancel.png');
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
