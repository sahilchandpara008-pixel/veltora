const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ executablePath: process.env.CHROME });
  const p = await b.newPage({ viewport: { width: 512, height: 512 } });
  await p.goto('file://' + __dirname + '/icon.svg');
  await p.screenshot({ path: __dirname + '/icon.png' });
  await p.setViewportSize({ width: 1024, height: 500 });
  await p.goto('file://' + __dirname + '/feature.html'); await p.waitForTimeout(500);
  await p.screenshot({ path: __dirname + '/feature-graphic.png' });
  await b.close();
})();
