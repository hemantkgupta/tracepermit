// Maintainer-only render: node scripts/render-site-social-card.mjs.
// Uses installed Playwright Chromium and the site's bundled font; no network.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const asset = new URL(
  "../site/public/images/tracepermit-social.svg",
  import.meta.url,
);
const font = await readFile(
  new URL(
    "../site/node_modules/vitepress/dist/client/theme-default/fonts/inter-roman-latin.woff2",
    import.meta.url,
  ),
);
const svg = (await readFile(asset, "utf8")).replace(
  "<rect ",
  `<style>@font-face{font-family:Inter;font-style:normal;font-weight:100 900;src:url(data:font/woff2;base64,${font.toString("base64")})}</style><rect `,
);
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({
    viewport: { width: 1200, height: 630 },
    deviceScaleFactor: 1,
  });
  page.setDefaultTimeout(10_000);
  await page.route("**/*", (route) => route.abort());
  await page.setContent(`<html><body style="margin:0">${svg}</body></html>`);
  await page.evaluate(() => document.fonts.ready);
  assert.equal(
    await page.evaluate(() => document.fonts.check("24px Inter")),
    true,
  );
  await page.locator("svg").screenshot({
    path: fileURLToPath(
      new URL("../site/public/images/tracepermit-social.png", import.meta.url),
    ),
  });
} finally {
  await browser.close();
}
