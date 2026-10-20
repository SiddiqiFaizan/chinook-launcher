// Renders the README previews. Usage: npm i playwright && node docs/previews/render.js
// (run `node docs/previews/make-icons.js` first if icons.js is missing)
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const dir = __dirname, out = path.join(dir, 'out');
  require('fs').mkdirSync(out, { recursive: true });
  const browser = await chromium.launch();
  const shots = ['home', 'popout', 'cards', 'dark', 'mono', 'settings', 'picker',
    'lock_aurora', 'lock_dusk', 'lock_graphite', 'lock_horizon', 'lock_monowaves'];
  for (const s of [...shots, 'hero']) {
    const hero = s === 'hero';
    const page = await browser.newPage({ viewport: hero ? { width: 1640, height: 800 } : { width: 412, height: 892 },
      deviceScaleFactor: hero ? 2 : 2.5 });
    page.on('pageerror', e => console.log('ERR', s, e.message));
    await page.goto('file://' + path.join(dir, 'screens.html') + '?s=' + s);
    await page.waitForSelector('body[data-ready="1"]');
    await page.waitForTimeout(150);
    await page.screenshot({ path: path.join(out, s + '.png') });
    await page.close();
    console.log('ok', s);
  }
  await browser.close();
})();
