// Manual screenshots, per docs/manual/README.md: the dev stack's console at 1440x900, scale 2.
const puppeteer = require('puppeteer-core');
const OUT = require('path').join(__dirname, 'assets');
const BASE = 'http://localhost:8081';
const ROUTES = ['status', 'operations', 'books', 'shards', 'securities', 'participants',
                'gateways', 'releases', 'schedules', 'operators', 'audit'];
(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: 'new', args: ['--no-sandbox'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 2 });
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle0' });
  await new Promise((r) => setTimeout(r, 500));
  await page.screenshot({ path: `${OUT}/ui-login.png` });
  await page.type('#u', 'admin');
  await page.type('#p', 'most-dev-password');
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }).catch(() => {}),
                     page.click('button[type=submit]')]);
  for (const route of ROUTES) {
    await page.goto(`${BASE}/${route}`, { waitUntil: 'domcontentloaded' });
    // Books and status stream over SSE, so networkidle never settles on data; give them a beat.
    await new Promise((r) => setTimeout(r, route === 'books' ? 3000 : 1200));
    await page.screenshot({ path: `${OUT}/ui-${route}.png` });
    console.log(`ui-${route}.png  ${page.url()}`);
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
