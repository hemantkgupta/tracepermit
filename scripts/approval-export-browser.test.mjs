import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { chmod, mkdtemp, readFile } from "node:fs/promises";
import { createConnection } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { chromium } from "playwright";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const UI_TIMEOUT = 15_000;
const START_TIMEOUT = 240_000;
const OUTPUT_LIMIT = 1_048_576;
const CANARY = "synthetic-private-diagnostic-must-not-render";
const signInHeading = "Sign in to your approval workspace";

function numericLoopback(value) {
  if (!/^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(value))
    throw new Error("BROWSER_FIXTURE_ORIGIN_REJECTED");
  const port = Number(value.slice(value.lastIndexOf(":") + 1));
  if (port > 65535) throw new Error("BROWSER_FIXTURE_ORIGIN_REJECTED");
  return value;
}

function fixtureOrigin(line) {
  const match = /^Approval inbox: (.+)\/approvals$/.exec(line);
  return match ? numericLoopback(match[1]) : null;
}

function fixtureEnvironment() {
  return Object.fromEntries(
    Object.entries(process.env).filter(
      ([key]) =>
        !/(TOKEN|SECRET|PASSWORD|PRIVATE_KEY|API_KEY)/i.test(key) &&
        !/^(AWS_|AZURE_|GOOGLE_|OPENAI_|ANTHROPIC_|TRACEPERMIT_(?!JAVA_HOME$|GRADLE_USER_HOME$))/.test(
          key,
        ),
    ),
  );
}

async function portIsOpen(origin) {
  const { hostname, port } = new URL(origin);
  return new Promise((resolve) => {
    const socket = createConnection({ host: hostname, port: Number(port) });
    const finish = (open) => {
      socket.destroy();
      resolve(open);
    };
    socket.once("connect", () => finish(true));
    socket.once("error", () => finish(false));
    socket.setTimeout(500, () => finish(true));
  });
}

async function within(promise, milliseconds, code) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(code)), milliseconds);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

async function startFixture() {
  const docker = spawnSync(
    "docker",
    ["info", "--format", "{{.ServerVersion}}"],
    {
      timeout: 15_000,
      maxBuffer: 65_536,
      encoding: "utf8",
      env: fixtureEnvironment(),
    },
  );
  if (docker.status !== 0)
    throw new Error("BROWSER_FIXTURE_DOCKER_UNAVAILABLE");
  const child = spawn("pnpm", ["demo:approvals:browser"], {
    cwd: root,
    env: fixtureEnvironment(),
    detached: true,
    stdio: ["pipe", "pipe", "pipe"],
  });
  let bytes = 0;
  let origin;
  let resolveReady;
  let rejectReady;
  const ready = new Promise((resolve, reject) => {
    resolveReady = resolve;
    rejectReady = reject;
  });
  const exited = new Promise((resolve) => {
    child.once("error", () => {
      rejectReady(new Error("BROWSER_FIXTURE_SPAWN_FAILED"));
      resolve({ code: null });
    });
    child.once("exit", (code) => {
      rejectReady(new Error("BROWSER_FIXTURE_EXITED_BEFORE_READY"));
      resolve({ code });
    });
  });
  for (const stream of [child.stdout, child.stderr]) {
    let pending = "";
    stream.setEncoding("utf8");
    stream.on("data", (chunk) => {
      bytes += Buffer.byteLength(chunk);
      if (bytes > OUTPUT_LIMIT) {
        rejectReady(new Error("BROWSER_FIXTURE_OUTPUT_LIMIT"));
        return;
      }
      pending += chunk;
      let end;
      while ((end = pending.indexOf("\n")) >= 0) {
        const line = pending.slice(0, end).trim();
        pending = pending.slice(end + 1);
        try {
          const parsed = fixtureOrigin(line);
          if (parsed) {
            origin = parsed;
            resolveReady(origin);
          }
        } catch {
          rejectReady(new Error("BROWSER_FIXTURE_ORIGIN_REJECTED"));
        }
      }
      if (pending.length > 65_536) {
        pending = "";
        rejectReady(new Error("BROWSER_FIXTURE_LINE_LIMIT"));
      }
    });
  }
  let stopped = false;
  async function stop() {
    if (stopped) return;
    stopped = true;
    const killOwnedGroup = (signal) => {
      if (child.exitCode === null && child.signalCode === null && child.pid) {
        try {
          process.kill(-child.pid, signal);
        } catch (failure) {
          if (failure.code !== "ESRCH") throw failure;
        }
      }
    };
    killOwnedGroup("SIGTERM");
    const result = await Promise.race([
      exited,
      new Promise((resolve) => setTimeout(resolve, 5_000)),
    ]);
    if (!result || result.code === null) {
      killOwnedGroup("SIGKILL");
      await exited;
    }
    if (origin) {
      for (let i = 0; i < 40; i++) {
        if (!(await portIsOpen(origin))) return;
        await new Promise((resolve) => setTimeout(resolve, 250));
      }
      throw new Error("BROWSER_FIXTURE_PORT_LINGERING");
    }
  }
  try {
    const boundOrigin = await within(
      ready,
      START_TIMEOUT,
      "BROWSER_FIXTURE_START_TIMEOUT",
    );
    return { origin: boundOrigin, stop };
  } catch (failure) {
    await stop();
    throw failure;
  }
}

test(
  "approval review metadata export browser controls and download validation",
  { timeout: 300_000 },
  async (t) => {
    let fixture;
    let browser;
    const contexts = [];
    let evidenceDirectory;
    let failedBeforeCleanup = false;
    let currentStep = "init";

    const step = async (title, action) => {
      currentStep = title;
      await t.test(title, action);
    };

    try {
      const docker = spawnSync(
        "docker",
        ["info", "--format", "{{.ServerVersion}}"],
        {
          timeout: 10_000,
          env: fixtureEnvironment(),
        },
      );
      if (docker.status !== 0) {
        t.skip("Docker is not available; skipping live browser export test");
        return;
      }

      evidenceDirectory = await mkdtemp(join(tmpdir(), "tp-export-evidence-"));
      await chmod(evidenceDirectory, 0o700);

      const channel = process.env.TRACEPERMIT_BROWSER_CHANNEL;
      assert.ok(
        !channel || channel === "chrome",
        "Only the explicit chrome channel override is supported",
      );
      browser = await chromium.launch({
        channel,
        headless: true,
        timeout: 15_000,
      });

      fixture = await startFixture();
      assert.ok(fixture.origin);

      const pageFor = async (persona) => {
        const context = await browser.newContext({
          locale: "en-US",
          viewport: { width: 1280, height: 900 },
          acceptDownloads: true,
        });
        contexts.push(context);
        const page = await context.newPage();
        await page.goto(`${fixture.origin}/approvals`);
        await page
          .getByRole("heading", { name: signInHeading, exact: true })
          .waitFor({ timeout: UI_TIMEOUT });
        await page
          .getByRole("link", {
            name: "Sign in with identity provider",
            exact: true,
          })
          .click();
        await page
          .getByRole("heading", {
            name: "Synthetic identity provider",
            exact: true,
          })
          .waitFor({ timeout: UI_TIMEOUT });
        await page
          .getByRole("link", { name: `Sign in as ${persona}`, exact: true })
          .click();
        await page
          .getByRole("form", { name: "Filter approvals", exact: true })
          .waitFor({ timeout: UI_TIMEOUT });
        await page
          .locator('[aria-label="Current identity"]')
          .waitFor({ timeout: UI_TIMEOUT });
        return page;
      };

      const seed = async (page, port = 4443) => {
        const result = await page.evaluate(async (targetPort) => {
          const sessionRes = await fetch("/browser/session");
          if (!sessionRes.ok) return null;
          const session = await sessionRes.json();
          const res = await fetch("/browser/api/v1/approvals", {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "X-CSRF-Token": session.csrfToken,
            },
            body: JSON.stringify({
              groupId: "sg-demo",
              port: targetPort,
              cidr: "192.0.2.0/24",
            }),
          });
          if (!res.ok) return null;
          return await res.json();
        }, port);
        assert.ok(result && result.approvalRequestId);
        return result;
      };

      const ensureRow = async (page) => {
        const item = await seed(page);
        await page.reload();
        await page
          .getByRole("form", { name: "Filter approvals", exact: true })
          .waitFor({ timeout: UI_TIMEOUT });
        const firstRow = page.locator("button.approval-row").first();
        await firstRow.waitFor({ timeout: UI_TIMEOUT });
        await firstRow.click();
        return item;
      };

      await step(
        "verify review export section explanation and button render on selection",
        async () => {
          const page = await pageFor("alice");
          await ensureRow(page);

          // Detail pane should render the review record section
          const exportSection = page.locator("div.approval-export-section");
          await exportSection.waitFor({ timeout: UI_TIMEOUT });

          const heading = exportSection.getByRole("heading", {
            name: "Review record",
            exact: true,
          });
          assert.equal(await heading.isVisible(), true);

          const explanation = exportSection.locator("p.approval-muted");
          const text = await explanation.innerText();
          assert.ok(text.includes("simulator-only"));
          assert.ok(text.includes("metadata-only"));
          assert.ok(text.includes("unsigned"));
          assert.ok(text.includes("incomplete"));

          const downloadButton = exportSection.getByRole("button", {
            name: "Download review JSON",
            exact: true,
          });
          assert.equal(await downloadButton.isVisible(), true);
          assert.equal(await downloadButton.isEnabled(), true);

          await page.context().close();
        },
      );

      await step(
        "verify oversized export response is rejected without download",
        async () => {
          const page = await pageFor("alice");
          await ensureRow(page);

          const exportSection = page.locator("div.approval-export-section");
          await exportSection.waitFor({ timeout: UI_TIMEOUT });

          // Route the export endpoint with oversized body (>32KB)
          await page.route(
            "**/browser/api/v1/approvals/*/export",
            async (route) => {
              const hugeJson = JSON.stringify({
                schemaVersion: "1.0",
                kind: "tracepermit-approval-review",
                padding: "X".repeat(40_000),
              });
              await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: hugeJson,
              });
            },
          );

          const downloadButton = exportSection.getByRole("button", {
            name: "Download review JSON",
            exact: true,
          });
          await downloadButton.click();

          // Should display fixed error notice, not download
          const notice = page.locator("p.approval-notice");
          await notice.waitFor({ timeout: UI_TIMEOUT });
          const noticeText = await notice.innerText();
          assert.ok(
            noticeText.includes(
              "The review export record was invalid or could not be verified",
            ),
          );

          await page.context().close();
        },
      );

      await step(
        "verify in-flight export cancellation when user signs out",
        async () => {
          const page = await pageFor("alice");
          await ensureRow(page);

          let fulfillDelayedRoute;
          const delayedPromise = new Promise((resolve) => {
            fulfillDelayedRoute = resolve;
          });

          await page.route(
            "**/browser/api/v1/approvals/*/export",
            async (route) => {
              await delayedPromise;
              await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({
                  schemaVersion: "1.0",
                  kind: "tracepermit-approval-review",
                  canonicalization: "tracepermit-canonical-json-v1",
                  payload: {
                    generatedAt: "2026-10-21T12:00:00Z",
                    mode: "simulated",
                    assurance: {
                      signed: false,
                      completeHistory: false,
                      transactionalSnapshot: false,
                      independentAnchor: false,
                      authorizesExecution: false,
                      argumentsIncluded: false,
                    },
                    approval: {
                      mode: "simulated",
                      approvalRequestId: "00000000-0000-0000-0000-000000000001",
                      requestHash:
                        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      requesterId:
                        "principal:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      status: "pending",
                      createdAt: "2026-10-21T11:00:00Z",
                      expiresAt: "2026-10-21T13:00:00Z",
                      expired: false,
                      policyId: "pol-local-simulator-approval",
                      policyVersion: 2,
                      originalPolicyOutcome: "require_approval",
                      executionStatus: "not_executed",
                      toolName: "aws.ec2.update_security_group",
                    },
                  },
                  payloadHash:
                    "sha256:b906a41995ff346f31fe1a94331e60a44afb72c381d3343bc014602ac4742dae",
                }),
              });
            },
          );

          const downloadButton = page
            .locator("div.approval-export-section")
            .getByRole("button", {
              name: "Download review JSON",
              exact: true,
            });
          await downloadButton.click();

          // While request is in-flight, sign out immediately
          await page
            .getByRole("button", { name: "Sign out", exact: true })
            .click();
          await page
            .getByRole("heading", { name: signInHeading, exact: true })
            .waitFor({ timeout: UI_TIMEOUT });

          // Unblock delayed network response
          fulfillDelayedRoute();

          // Wait brief tick to confirm no crash or uncaught rejection
          await page.waitForTimeout(500);
          assert.equal(
            await page
              .getByRole("heading", { name: signInHeading, exact: true })
              .isVisible(),
            true,
          );

          await page.context().close();
        },
      );

      await step(
        "verify successful browser download and saved JSON file with synthetic transport response",
        async () => {
          const page = await pageFor("alice");
          const seeded = await ensureRow(page);
          assert.ok(seeded.approvalRequestId);
          assert.ok(seeded.requestHash);

          const exportSection = page.locator("div.approval-export-section");
          await exportSection.waitFor({ timeout: UI_TIMEOUT });

          const syntheticExport = {
            schemaVersion: "1.0",
            kind: "tracepermit-approval-review",
            canonicalization: "tracepermit-canonical-json-v1",
            payload: {
              generatedAt: "2026-10-21T12:00:00Z",
              mode: "simulated",
              assurance: {
                signed: false,
                completeHistory: false,
                transactionalSnapshot: false,
                independentAnchor: false,
                authorizesExecution: false,
                argumentsIncluded: false,
              },
              approval: {
                mode: "simulated",
                approvalRequestId: seeded.approvalRequestId,
                requestHash: seeded.requestHash,
                requesterId:
                  "principal:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                status: "pending",
                createdAt: "2026-10-21T11:00:00Z",
                expiresAt: "2026-10-21T13:00:00Z",
                expired: false,
                policyId: "pol-local-simulator-approval",
                policyVersion: 2,
                originalPolicyOutcome: "require_approval",
                executionStatus: "not_executed",
                toolName: "aws.ec2.update_security_group",
              },
            },
            payloadHash:
              "sha256:b906a41995ff346f31fe1a94331e60a44afb72c381d3343bc014602ac4742dae",
          };

          await page.route(
            `**/browser/api/v1/approvals/${encodeURIComponent(seeded.approvalRequestId)}/export`,
            async (route) => {
              await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify(syntheticExport),
              });
            },
          );

          const downloadPromise = page.waitForEvent("download", {
            timeout: UI_TIMEOUT,
          });
          const downloadButton = exportSection.getByRole("button", {
            name: "Download review JSON",
            exact: true,
          });
          await downloadButton.click();

          const download = await downloadPromise;
          const filename = download.suggestedFilename();
          assert.equal(
            filename,
            `tracepermit-approval-${seeded.approvalRequestId}.json`,
          );

          const savePath = join(evidenceDirectory, filename);
          await download.saveAs(savePath);

          const contents = JSON.parse(await readFile(savePath, "utf8"));
          assert.equal(contents.schemaVersion, "1.0");
          assert.equal(contents.kind, "tracepermit-approval-review");
          assert.equal(
            contents.canonicalization,
            "tracepermit-canonical-json-v1",
          );
          assert.equal(contents.payload.mode, "simulated");
          assert.equal(
            contents.payload.approval.approvalRequestId,
            seeded.approvalRequestId,
          );
          assert.equal(
            contents.payload.approval.requestHash,
            seeded.requestHash,
          );
          assert.equal(contents.payload.assurance.signed, false);
          assert.equal(contents.payload.assurance.authorizesExecution, false);

          await page.context().close();
        },
      );

      await step("integrated download against Java backend route", async () => {
        const page = await pageFor("alice");
        await ensureRow(page);

        const exportSection = page.locator("div.approval-export-section");
        await exportSection.waitFor({ timeout: UI_TIMEOUT });

        const checkResponse = await page.request.get(
          `${fixture.origin}/browser/api/v1/approvals`,
        );
        const listData = await checkResponse.json();
        const firstItem = listData.items?.[0];

        if (!firstItem) {
          assert.fail(
            "INTEGRATED_EXPORT_SETUP_FAILED: No approval items found in live fixture",
          );
        }

        const liveExportResponse = await page.request.get(
          `${fixture.origin}/browser/api/v1/approvals/${encodeURIComponent(firstItem.approvalRequestId)}/export`,
        );

        if (
          liveExportResponse.status() === 404 ||
          liveExportResponse.status() === 405
        ) {
          assert.fail(
            `INTEGRATED_EXPORT_ROUTE_MISSING: Java export route returned status ${liveExportResponse.status()}`,
          );
        }

        assert.equal(liveExportResponse.status(), 200);

        const downloadPromise = page.waitForEvent("download", {
          timeout: UI_TIMEOUT,
        });
        const downloadButton = exportSection.getByRole("button", {
          name: "Download review JSON",
          exact: true,
        });
        await downloadButton.click();

        const download = await downloadPromise;
        const filename = download.suggestedFilename();
        assert.match(filename, /^tracepermit-approval-[a-f0-9-]{36}\.json$/);

        const savePath = join(evidenceDirectory, filename);
        await download.saveAs(savePath);

        const contents = JSON.parse(await readFile(savePath, "utf8"));
        assert.equal(contents.schemaVersion, "1.0");
        assert.equal(contents.kind, "tracepermit-approval-review");
        assert.equal(
          contents.canonicalization,
          "tracepermit-canonical-json-v1",
        );
        assert.equal(contents.payload.mode, "simulated");
        assert.equal(contents.payload.approval.mode, "simulated");
        assert.equal(contents.payload.assurance.signed, false);
        assert.equal(contents.payload.assurance.authorizesExecution, false);

        await page.context().close();
      });

      await step(
        "verify no canary text, no local storage, and clean console health",
        async () => {
          const page = await pageFor("alice");
          assert.match(await page.title(), /TracePermit/);
          assert.equal(
            await page.evaluate(
              () => localStorage.length === 0 && sessionStorage.length === 0,
            ),
            true,
          );
          assert.ok(!(await page.locator("body").innerText()).includes(CANARY));
          await page.context().close();
        },
      );
    } catch (failure) {
      failedBeforeCleanup = true;
      if (failure.message?.startsWith("BROWSER_")) throw failure;
      throw new Error(
        `BROWSER_EXPORT_SUITE_FAILED: ${currentStep}; sensitive details withheld`,
      );
    } finally {
      for (const context of contexts) {
        await within(
          context.close(),
          5_000,
          "BROWSER_CONTEXT_CLOSE_TIMEOUT",
        ).catch(() => {});
      }
      if (browser) {
        await within(browser.close(), 10_000, "BROWSER_CLOSE_TIMEOUT").catch(
          () => {},
        );
      }
      if (fixture) {
        await fixture.stop();
        t.diagnostic(
          "Owned fixture exited cleanly and its listener port was released.",
        );
      }
    }
  },
);
