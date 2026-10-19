import assert from "node:assert/strict";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { chromium } from "playwright";
import { preview } from "../site/node_modules/vite/dist/node/index.js";

const root = fileURLToPath(new URL("../", import.meta.url));
const screenshots = process.env.TRACEPERMIT_SITE_QA_OUTPUT;

async function within(promise) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(
          () => reject(new Error("SITE_QA_CLEANUP_TIMEOUT")),
          5_000,
        );
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

test(
  "verification guide exposes readable historical evidence and contact without page overflow",
  { timeout: 90_000 },
  async (t) => {
    let server;
    let browser;
    try {
      server = await preview({
        root: join(root, "site"),
        configFile: false,
        build: { outDir: ".vitepress/dist" },
        preview: { host: "127.0.0.1", port: 0, strictPort: true, open: false },
      });
      const address = server.httpServer.address();
      assert.equal(address.address, "127.0.0.1");
      const origin = `http://127.0.0.1:${address.port}`;
      browser = await chromium.launch({ headless: true });
      const page = await browser.newPage();
      page.setDefaultTimeout(10_000);
      const errors = [];
      page.on("pageerror", (error) => errors.push(error.message));
      page.on("console", (message) => {
        if (message.type() === "error") errors.push(message.text());
      });
      page.on("response", (response) => {
        if (response.status() >= 400)
          errors.push(`${response.status()} ${response.url()}`);
      });

      for (const width of [390, 768, 1440]) {
        await t.test(
          `${width}px: home → guide → evidence, with correct client-side canonical`,
          async () => {
            await page.setViewportSize({ width, height: 1000 });
            await page.goto(origin, { waitUntil: "networkidle" });
            assert.match(
              await page.locator(".VPHero").innerText(),
              /Request-bound approvals/,
            );
            await page
              .getByRole("link", {
                name: "verification and compatibility guide",
                exact: true,
              })
              .click();
            await page.waitForURL(`${origin}/guide/verification`);
            await page
              .locator(".vp-doc h1")
              .filter({ hasText: "Verification and compatibility" })
              .waitFor();
            assert.equal(await page.locator("vite-error-overlay").count(), 0);
            await page.waitForFunction(
              () =>
                document.querySelector('link[rel="canonical"]')?.href ===
                "https://tracepermit.org/guide/verification",
            );
            assert.equal(
              await page
                .locator('meta[property="og:title"]')
                .getAttribute("content"),
              "Verification and compatibility | TracePermit",
            );
            assert.equal(
              await page.locator('link[rel="canonical"]').count(),
              1,
            );
            if (screenshots) {
              await page.screenshot({
                path: join(screenshots, `verification-guide-${width}.png`),
              });
            }

            const details = page.locator(".vp-doc details");
            assert.equal(await details.count(), 2);
            for (let i = 0; i < 2; i++) {
              const disclosure = details.nth(i);
              const summary = disclosure.locator("summary");
              await summary.focus();
              await summary.press("Enter");
              const region = disclosure.getByRole("region");
              await region.waitFor({ state: "visible" });
              await region.locator("img").evaluate((image) => image.decode());
              assert.ok(
                await region
                  .locator("img")
                  .evaluate((image) => image.naturalWidth > 0),
              );
              await region.focus();
              assert.equal(
                await region.evaluate(
                  (element) => element === document.activeElement,
                ),
                true,
              );
              const overflow = await region.evaluate((element) => ({
                vertical: element.scrollHeight > element.clientHeight,
                horizontal: element.scrollWidth > element.clientWidth,
              }));
              // The browser capture is tall; the CLI capture is shorter. Exercise
              // only a real overflow axis rather than waiting for impossible scroll.
              if (overflow.vertical || overflow.horizontal) {
                await region.press(
                  overflow.vertical ? "ArrowDown" : "ArrowRight",
                );
                await page.waitForFunction(
                  (element) => element.scrollTop > 0 || element.scrollLeft > 0,
                  await region.elementHandle(),
                );
              }
              assert.equal(
                await page.evaluate(
                  () => document.documentElement.scrollWidth <= innerWidth,
                ),
                true,
              );
              if (width === 390)
                assert.ok(
                  await region.evaluate(
                    (element) => element.scrollWidth > element.clientWidth,
                  ),
                  "Wide retained evidence scrolls inside its region, not the page.",
                );
            }
            if (screenshots) {
              const region = details.first().getByRole("region");
              await region.evaluate((element) => {
                element.scrollTop = 0.36 * element.scrollHeight;
                element.scrollIntoView({ block: "center" });
              });
              await page.screenshot({
                path: join(screenshots, `verification-${width}.png`),
              });
            }
            const contact = page.locator(
              '.vp-doc a[href^="mailto:hemant@tracepermit.org?subject="]',
            );
            assert.equal(await contact.count(), 1);
            assert.equal(
              await contact.getAttribute("href"),
              "mailto:hemant@tracepermit.org?subject=TracePermit%20simulator%20demo",
            );
            assert.match(
              await page.locator(".vp-doc").innerText(),
              /not retained/,
            );
          },
        );
      }
      await t.test(
        "local search reaches evidence and share image loads at its declared size",
        async () => {
          await page.setViewportSize({ width: 1440, height: 1000 });
          await page.goto(origin, { waitUntil: "networkidle" });
          await page
            .getByRole("button", { name: "Search", exact: true })
            .click();
          await page
            .locator("#localsearch-input")
            .fill("Verification and compatibility");
          const result = page
            .getByRole("option")
            .filter({ hasText: "Verification and compatibility" })
            .first();
          await result.waitFor({ state: "visible" });
          await result.click();
          await page.waitForURL(
            (url) => url.pathname === "/guide/verification",
          );
          await page
            .locator(".vp-doc h1")
            .filter({ hasText: "Verification and compatibility" })
            .waitFor();
          const size = await page.evaluate(async (url) => {
            const image = new Image();
            image.src = url;
            await image.decode();
            return [image.naturalWidth, image.naturalHeight];
          }, `${origin}/images/tracepermit-social.png`);
          assert.deepEqual(size, [1200, 630]);
        },
      );
      assert.deepEqual(errors, []);
      t.diagnostic(
        `Chromium ${browser.version()}; local built site only; no backend, model or login involved.`,
      );
    } finally {
      try {
        if (browser) {
          for (const context of browser.contexts())
            await within(context.close());
          await within(browser.close());
        }
      } finally {
        if (server) {
          const closed = new Promise((resolve, reject) =>
            server.httpServer.close((error) =>
              error ? reject(error) : resolve(),
            ),
          );
          server.httpServer.closeAllConnections();
          await within(closed);
        }
      }
    }
  },
);
