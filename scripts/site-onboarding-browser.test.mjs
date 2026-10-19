import assert from "node:assert/strict";
import { access } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { chromium } from "playwright";
import { preview } from "../site/node_modules/vite/dist/node/index.js";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const screenshots = process.env.TRACEPERMIT_SITE_QA_OUTPUT;

async function within(promise, milliseconds) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(
          () => reject(new Error("SITE_QA_CLEANUP_TIMEOUT")),
          milliseconds,
        );
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

test(
  "built site onboarding remains usable across navigation breakpoints",
  { timeout: 120_000 },
  async (t) => {
    await access(join(root, "site/.vitepress/dist/index.html"));
    let server;
    let browser;
    try {
      // Vite's preview API honors the exact host; no wildcard test listener.
      server = await preview({
        root: join(root, "site"),
        configFile: false,
        build: { outDir: ".vitepress/dist" },
        preview: { host: "127.0.0.1", port: 0, strictPort: true, open: false },
      });
      const address = server.httpServer.address();
      assert.equal(address.address, "127.0.0.1");
      const origin = `http://127.0.0.1:${address.port}`;
      browser = await chromium.launch({
        headless: true,
        ...(process.env.TRACEPERMIT_SITE_BROWSER_CHANNEL
          ? { channel: process.env.TRACEPERMIT_SITE_BROWSER_CHANNEL }
          : {}),
      });
      const page = await browser.newPage({
        viewport: { width: 1440, height: 1000 },
      });
      page.setDefaultTimeout(10_000);
      const errors = [];
      const benignWarnings = [];
      page.on("pageerror", (error) => errors.push(error.message));
      page.on("console", (message) => {
        if (
          message.type() === "warning" &&
          message.text().includes(`${origin}/assets/`) &&
          /\.woff2 was preloaded using link preload but not used/.test(
            message.text(),
          )
        ) {
          benignWarnings.push(
            "Built theme font preload unused during navigation",
          );
          return;
        }
        if (
          ["error", "warning"].includes(message.type()) &&
          !message.location().url.endsWith("/favicon.ico")
        ) {
          errors.push(message.text());
        }
      });

      for (const width of [
        390, 639, 640, 767, 768, 820, 959, 960, 1024, 1440,
      ]) {
        await t.test(
          `${width}px: no page overflow and evaluation menu navigates`,
          async () => {
            await page.setViewportSize({ width, height: 1000 });
            await page.goto(`${origin}/guide/what-is-tracepermit`, {
              waitUntil: "networkidle",
            });
            assert.match(await page.title(), /What is TracePermit/);
            assert.match(
              await page.locator(".vp-doc h1").innerText(),
              /What is TracePermit/,
            );
            assert.equal(await page.locator("vite-error-overlay").count(), 0);
            const dimensions = await page.evaluate(() => ({
              viewport: innerWidth,
              document: document.documentElement.scrollWidth,
              navRight: document
                .querySelector(".VPNavBar .content-body")
                .getBoundingClientRect().right,
            }));
            if (screenshots && width === 768) {
              await page.screenshot({
                path: join(screenshots, "tablet-navigation.png"),
              });
            }
            assert.ok(
              dimensions.document <= dimensions.viewport,
              JSON.stringify(dimensions),
            );

            let navigation;
            if (width < 768) {
              await page
                .getByRole("button", { name: "mobile navigation", exact: true })
                .click();
              navigation = page.locator(".VPNavScreen");
              await navigation
                .getByRole("button", { name: "Evaluate locally", exact: true })
                .click();
            } else {
              navigation = page.locator(".VPNavBarMenu");
              await navigation
                .getByRole("button", { name: "Evaluate locally", exact: true })
                .focus();
              await navigation
                .getByRole("button", { name: "Evaluate locally", exact: true })
                .press("Enter");
            }
            const freshMac = navigation.getByRole("link", {
              name: "Fresh MacBook Evaluation",
              exact: true,
            });
            await freshMac.waitFor({ state: "visible" });
            if (screenshots && width === 768) {
              await page.waitForFunction(() => {
                const menu = document.querySelector(
                  '.VPNavBarMenu button[aria-expanded="true"] + .menu',
                );
                return menu && getComputedStyle(menu).opacity === "1";
              });
              await page.screenshot({
                path: join(screenshots, "tablet-evaluation-menu.png"),
              });
            }
            await freshMac.click();
            await page.waitForURL(`${origin}/guide/fresh-macbook-evaluation`);
            await page
              .locator(".vp-doc h1")
              .filter({ hasText: "Fresh MacBook evaluation" })
              .waitFor();
            if (width < 768) {
              await page.locator(".VPNavScreen").waitFor({ state: "hidden" });
              assert.equal(
                await page
                  .getByRole("button", {
                    name: "mobile navigation",
                    exact: true,
                  })
                  .getAttribute("aria-expanded"),
                "false",
              );
            }
            assert.match(
              await page.locator(".vp-doc h1").innerText(),
              /Fresh MacBook evaluation/,
            );
            assert.ok(
              (await page.locator(".vp-doc").innerText()).includes(
                "pnpm demo:approvals:browser",
              ),
            );
            assert.equal(
              await page.evaluate(
                () => document.documentElement.scrollWidth <= innerWidth,
              ),
              true,
            );
            if (screenshots && width === 390) {
              await page.screenshot({
                path: join(screenshots, "mobile-fresh-mac.png"),
              });
            }
          },
        );
      }

      await t.test(
        "home workflow links and approval cards survive reconciliation",
        async () => {
          await page.setViewportSize({ width: 1440, height: 1000 });
          await page.goto(origin, { waitUntil: "networkidle" });
          assert.equal(
            await page.locator(".approval-preview > div").count(),
            4,
          );
          assert.equal(
            await page
              .locator(".approval-preview")
              .evaluate(
                (e) =>
                  getComputedStyle(e).gridTemplateColumns.split(" ").length,
              ),
            2,
          );
          await page
            .getByRole("link", {
              name: "Explore the approval workflow",
              exact: true,
            })
            .click();
          await page.waitForURL(`${origin}/guide/working-today`);
          await page
            .locator(".vp-doc h1")
            .filter({ hasText: "What works today" })
            .waitFor();
          assert.match(
            await page.locator(".vp-doc h1").innerText(),
            /What works today/,
          );
          await page.setViewportSize({ width: 390, height: 1000 });
          await page.goto(origin, { waitUntil: "networkidle" });
          assert.equal(
            await page
              .locator(".approval-preview")
              .evaluate(
                (e) =>
                  getComputedStyle(e).gridTemplateColumns.split(" ").length,
              ),
            1,
          );
          await page
            .getByRole("link", { name: "Try on a clean Mac", exact: true })
            .click();
          await page.waitForURL(`${origin}/guide/fresh-macbook-evaluation`);
          await page
            .locator(".vp-doc h1")
            .filter({ hasText: "Fresh MacBook evaluation" })
            .waitFor();
        },
      );

      await t.test(
        "connector guide remains reachable from desktop and mobile evaluation menus",
        async () => {
          for (const width of [390, 1440]) {
            await page.setViewportSize({ width, height: 1000 });
            await page.goto(origin, { waitUntil: "networkidle" });
            const navigation = page.locator(
              width < 768 ? ".VPNavScreen" : ".VPNavBarMenu",
            );
            if (width < 768) {
              await page
                .getByRole("button", { name: "mobile navigation", exact: true })
                .click();
            }
            await navigation
              .getByRole("button", { name: "Evaluate locally", exact: true })
              .click();
            await navigation
              .getByRole("link", { name: "Connector for Codex", exact: true })
              .click();
            await page.waitForURL(`${origin}/guide/endpoint-connector`);
            await page
              .locator(".vp-doc h1")
              .filter({ hasText: "Endpoint connector for Codex" })
              .waitFor();
            if (width < 768)
              await page.locator(".VPNavScreen").waitFor({ state: "hidden" });
            assert.equal(
              await page.evaluate(
                () => document.documentElement.scrollWidth <= innerWidth,
              ),
              true,
            );
          }
        },
      );

      await t.test(
        "corrected intro visuals and new operator flow assets load",
        async () => {
          await page.setViewportSize({ width: 1440, height: 1000 });
          await page.goto(`${origin}/guide/what-is-tracepermit`, {
            waitUntil: "networkidle",
          });
          assert.equal(await page.locator(".intro-visual img").count(), 2);
          assert.equal(await page.locator("ol.intro-text").count(), 2);
          await page
            .getByRole("link", {
              name: "Open the request-flow SVG at full size",
              exact: true,
            })
            .click();
          await page.waitForURL(`${origin}/images/intro-request-flow.svg`);
          assert.ok(
            (await page.locator("svg").textContent()).includes(
              "No permit. No dispatch.",
            ),
          );
          await page.goto(`${origin}/guide/core-flows`, {
            waitUntil: "networkidle",
          });
          const recovery = page.locator(
            'a[href="/images/flows/flow-12-recovery-quarantine.svg"]',
          );
          await recovery.click();
          await page.waitForURL(
            `${origin}/images/flows/flow-12-recovery-quarantine.svg`,
          );
          assert.equal(await page.locator("svg").count(), 1);
        },
      );
      await t.test(
        "built local search reaches the canonical Mac walkthrough",
        async () => {
          await page.goto(origin, { waitUntil: "networkidle" });
          await page
            .getByRole("button", { name: "Search", exact: true })
            .click();
          await page
            .locator("#localsearch-input")
            .fill("Fresh MacBook evaluation");
          const result = page
            .getByRole("option")
            .filter({ hasText: "Fresh MacBook evaluation" })
            .first();
          await result.waitFor({ state: "visible" });
          await result.click();
          await page.waitForURL(
            (url) => url.pathname === "/guide/fresh-macbook-evaluation",
          );
          await page
            .locator(".vp-doc h1")
            .filter({ hasText: "Fresh MacBook evaluation" })
            .waitFor();
        },
      );
      assert.deepEqual(errors, []);
      if (benignWarnings.length)
        t.diagnostic(
          `${benignWarnings.length} benign built-theme font-preload warning(s); no application error.`,
        );
      t.diagnostic(
        `Browser ${browser.version()}; built static output; numeric loopback; no relevant runtime errors.`,
      );
    } finally {
      try {
        if (browser) {
          for (const context of browser.contexts())
            await within(context.close(), 5_000);
          await within(browser.close(), 5_000);
        }
      } finally {
        if (server) {
          const closed = new Promise((resolve, reject) =>
            server.httpServer.close((error) =>
              error ? reject(error) : resolve(),
            ),
          );
          server.httpServer.closeAllConnections();
          await within(closed, 5_000);
        }
      }
    }
  },
);
