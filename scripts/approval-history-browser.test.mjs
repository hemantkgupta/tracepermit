import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { chmod, mkdtemp } from "node:fs/promises";
import { createConnection } from "node:net";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { chromium } from "playwright";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const UI_TIMEOUT = 15_000;
const START_TIMEOUT = 240_000;
const OUTPUT_LIMIT = 1_048_576;
const CANARY = "synthetic-private-diagnostic-must-not-render";
const approvalsPath = "/browser/api/v1/approvals";
const signInHeading = "Sign in to your approval workspace";
const toolName = "aws.ec2.update_security_group";

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
  // Test fixtures need host tool configuration, not ambient provider credentials.
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
  // Never forward raw Gradle/Flyway output: it can contain a JDBC URL or path.
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
    // Only this detached, directly spawned process group is eligible for escalation.
    const killOwnedGroup = (signal) => {
      if (child.exitCode === null && child.signalCode === null && child.pid) {
        try {
          process.kill(-child.pid, signal);
        } catch (failure) {
          if (failure.code !== "ESRCH")
            throw new Error("BROWSER_FIXTURE_STOP_FAILED");
        }
      }
    };
    if (child.stdin.writable) child.stdin.end("\n");
    let outcome;
    try {
      outcome = await within(exited, 30_000, "BROWSER_FIXTURE_STOP_TIMEOUT");
    } catch {
      killOwnedGroup("SIGTERM");
      try {
        await within(exited, 5_000, "BROWSER_FIXTURE_TERM_TIMEOUT");
      } catch {
        killOwnedGroup("SIGKILL");
        await within(exited, 5_000, "BROWSER_FIXTURE_KILL_TIMEOUT");
      }
      throw new Error("BROWSER_FIXTURE_UNCLEAN_SHUTDOWN");
    }
    if (outcome.code !== 0) throw new Error("BROWSER_FIXTURE_UNCLEAN_SHUTDOWN");
    if (origin && (await portIsOpen(origin)))
      throw new Error("BROWSER_FIXTURE_PORT_NOT_RELEASED");
    if (bytes > OUTPUT_LIMIT) throw new Error("BROWSER_FIXTURE_OUTPUT_LIMIT");
  }
  try {
    await within(ready, START_TIMEOUT, "BROWSER_FIXTURE_START_TIMEOUT");
    return { origin, stop };
  } catch (failure) {
    // Preserve the original failure even if startup cleanup also fails.
    try {
      await stop();
    } catch {
      /* The enclosing test still fails; no raw child output is emitted. */
    }
    throw failure;
  }
}

test("browser fixture origin parsing rejects remote, ambiguous and out-of-range addresses", () => {
  assert.equal(
    fixtureOrigin("Approval inbox: http://127.0.0.1:54321/approvals"),
    "http://127.0.0.1:54321",
  );
  assert.equal(fixtureOrigin("unrelated child output"), null);
  for (const origin of [
    "https://example.invalid:443",
    "http://localhost:54321",
    "http://127.0.0.1:0",
    "http://127.0.0.1:65536",
    "http://127.0.0.1:54321@evil.invalid",
    "http://127.0.0.1:54321/path",
  ]) {
    assert.throws(
      () => fixtureOrigin(`Approval inbox: ${origin}/approvals`),
      /BROWSER_FIXTURE_ORIGIN_REJECTED/,
    );
  }
});

test(
  "simulator approval inbox — actual mounted browser regressions",
  { timeout: 600_000 },
  async (t) => {
    let browser;
    let fixture;
    const contexts = [];
    let evidenceDirectory;
    const channel = process.env.TRACEPERMIT_BROWSER_CHANNEL;
    const executablePath = process.env.TRACEPERMIT_BROWSER_EXECUTABLE;
    assert.ok(
      !channel || channel === "chrome",
      "Only the explicit chrome channel override is supported",
    );
    assert.ok(
      !executablePath ||
        (isAbsolute(executablePath) && existsSync(executablePath)),
      "BROWSER_EXECUTABLE_REJECTED",
    );
    assert.ok(
      !(channel && executablePath),
      "BROWSER_LAUNCH_CONFIGURATION_REJECTED",
    );
    let currentStep = "prerequisites";
    let failedBeforeCleanup = false;
    const runtimeFailures = [];
    const unexpectedHttp = [];
    try {
      try {
        browser = await chromium.launch({
          channel,
          executablePath,
          env: fixtureEnvironment(),
          headless: true,
          timeout: 15_000,
        });
      } catch {
        throw new Error(
          "BROWSER_UNAVAILABLE: explicitly install matching Chromium or select installed Chrome; no automatic download",
        );
      }
      fixture = await startFixture();
      const collectionUrl = (url) =>
        url.origin === fixture.origin && url.pathname === approvalsPath;
      if (process.env.TRACEPERMIT_BROWSER_SCREENSHOTS === "1") {
        evidenceDirectory = await mkdtemp(
          join(tmpdir(), "tracepermit-browser-evidence-"),
        );
        await chmod(evidenceDirectory, 0o700);
      }
      async function step(name, action) {
        if (t.signal.aborted) throw new Error("BROWSER_SUITE_CANCELLED");
        currentStep = name;
        let failed = false;
        await t.test(name, { timeout: 90_000 }, async () => {
          try {
            await action();
          } catch (failure) {
            failed = true;
            // Playwright errors may include callback URLs or response bodies. Report only
            // the fixed phase name; never dump errors, cookies, storage or raw traces.
            const line = /approval-history-browser\.test\.mjs:(\d+):\d+/.exec(
              failure.stack ?? "",
            )?.[1];
            const kind = ["AssertionError", "TimeoutError"].includes(
              failure.name,
            )
              ? failure.name
              : "Error";
            throw new Error(
              `BROWSER_ASSERTION_FAILED: ${name}; ${kind}; test line ${line ?? "unknown"}`,
            );
          }
        });
        if (failed) throw new Error(`BROWSER_SUITE_STOPPED: ${name}`);
      }
      async function pageFor(account, approvalClockTest = false) {
        const context = await browser.newContext({
          viewport: { width: 1440, height: 1000 },
        });
        contexts.push(context);
        const page = await context.newPage();
        if (approvalClockTest) {
          // Fixture credentials live 290s, approvals 300s. Isolate the approval
          // timer by extending only the client session projection. Real Java
          // credentials/roles/DB time stay unchanged, and no mutation is expected.
          await page.clock.install({ time: new Date() });
          await page.route("**/browser/session", async (route) => {
            const response = await route.fetch();
            if (!response.ok()) return route.fulfill({ response });
            const session = await response.json();
            session.expiresAt = await page.evaluate(() =>
              new Date(Date.now() + 600_000).toISOString(),
            );
            await route.fulfill({ response, json: session });
          });
        }
        page.setDefaultTimeout(UI_TIMEOUT);
        page.setDefaultNavigationTimeout(UI_TIMEOUT);
        const ownedOrigins = new Set([fixture.origin]);
        page.on("framenavigated", (frame) => {
          if (frame !== page.mainFrame() || frame.url() === "about:blank")
            return;
          const url = new URL(frame.url());
          if (url.pathname === "/authorize")
            ownedOrigins.add(numericLoopback(url.origin));
        });
        const optionalFavicon = (value) => {
          try {
            const url = new URL(value);
            return (
              url.pathname === "/favicon.ico" && ownedOrigins.has(url.origin)
            );
          } catch {
            return false;
          }
        };
        page.on("pageerror", () => runtimeFailures.push(account));
        page.on("console", (message) => {
          // Chromium requests an optional favicon on the fixture/IdP origin.
          // Neither test server supplies one; classify only that exact 404.
          if (
            optionalFavicon(message.location().url) &&
            /^Failed to load resource: the server responded with a status of 404 /.test(
              message.text(),
            )
          )
            return;
          if (
            ["error", "warning"].includes(message.type()) &&
            !/^Failed to load resource: the server responded with a status of (400|401|403|409|503) /.test(
              message.text(),
            )
          ) {
            runtimeFailures.push({
              account,
              phase: currentStep,
              networkCode:
                /net::(ERR_[A-Z_]+)/.exec(message.text())?.[1] ?? null,
              resourceStatus:
                /status of ([0-9]{3})/.exec(message.text())?.[1] ?? null,
              faviconResource: optionalFavicon(message.location().url),
              collectionResource: message
                .location()
                .url.startsWith(`${fixture.origin}${approvalsPath}`),
              category: message.text().startsWith("Failed to load resource")
                ? "RESOURCE_ERROR"
                : message.text().startsWith("Refused")
                  ? "CSP_REJECTION"
                  : "APP_CONSOLE_ERROR",
            });
          }
        });
        page.on("response", (response) => {
          if (response.status() < 400) return;
          if (response.status() === 404 && optionalFavicon(response.url()))
            return;
          const path = new URL(response.url()).pathname;
          if (
            !/^\/browser\//.test(path) ||
            ![400, 401, 403, 409, 503].includes(response.status())
          )
            unexpectedHttp.push(account);
        });
        await page.goto(`${fixture.origin}/approvals`);
        await page
          .getByRole("heading", { name: signInHeading, exact: true })
          .waitFor();
        assert.equal(new URL(page.url()).pathname, "/approvals");
        assert.match(await page.title(), /TracePermit/);
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
          .waitFor();
        await page
          .getByRole("link", { name: `Sign in as ${account}`, exact: true })
          .click();
        await page
          .getByRole("form", { name: "Filter approvals", exact: true })
          .waitFor();
        await page.locator('[aria-label="Current identity"]').waitFor();
        return page;
      }
      async function rows(page, count) {
        await page.waitForFunction(
          (expected) =>
            document.querySelectorAll(".approval-row").length === expected,
          count,
        );
        assert.equal(await page.locator(".approval-row").count(), count);
      }
      async function seed(page, count, offset) {
        const result = await page.evaluate(
          async ({ count, offset, approvalsPath }) => {
            const response = await fetch("/browser/session");
            if (!response.ok) return { ok: false };
            const session = await response.json();
            const ids = [];
            for (let start = 0; start < count; start += 4) {
              const batch = await Promise.all(
                Array.from(
                  { length: Math.min(4, count - start) },
                  async (_, index) => {
                    const response = await fetch(approvalsPath, {
                      method: "POST",
                      headers: {
                        "Content-Type": "application/json",
                        "X-CSRF-Token": session.csrfToken,
                      },
                      body: JSON.stringify({
                        groupId: "sg-demo",
                        port: offset + start + index,
                        cidr: "192.0.2.0/24",
                      }),
                    });
                    if (!response.ok) return null;
                    const item = await response.json();
                    return {
                      id: item.approvalRequestId,
                      hash: item.requestHash,
                    };
                  },
                ),
              );
              if (batch.includes(null)) return { ok: false };
              ids.push(...batch);
            }
            return { ok: true, ids };
          },
          { count, offset, approvalsPath },
        );
        assert.equal(
          result.ok,
          true,
          "Synthetic authenticated seeding must succeed",
        );
        return result.ids;
      }
      async function filterId(page, id) {
        await page.getByLabel("Request ID", { exact: true }).fill(id);
        await page
          .getByRole("button", { name: "Apply filters", exact: true })
          .click();
        await rows(page, 1);
        assert.ok(
          (await page.locator(".approval-row small").innerText()).startsWith(
            id.slice(0, 8),
          ),
        );
      }
      async function screenshot(page, name) {
        if (evidenceDirectory)
          await page.screenshot({
            path: join(evidenceDirectory, `${name}.png`),
          });
      }
      async function assertNoMutation(page) {
        const buttons = page.getByRole("button", {
          name: /^(Request approval|Approve exact action|Deny|Execute approved simulation|Withdraw request|Confirm (approve|deny|execute|withdrawal))$/,
        });
        for (const button of await buttons.all())
          assert.equal(await button.isEnabled(), false);
        assert.equal(await page.locator(".approval-row").count(), 0);
        assert.ok(!(await page.locator("body").innerText()).includes(CANARY));
      }
      let alice;
      let bob;
      let carol;
      let aliceIds;
      let fresh;
      await step(
        "anonymous entry and real synthetic sign-in render a usable inbox",
        async () => {
          alice = await pageFor("alice");
          await rows(alice, 0);
          assert.equal(
            await alice
              .getByRole("button", { name: "Request approval", exact: true })
              .isEnabled(),
            true,
          );
          assert.equal(
            await alice.locator("vite-error-overlay, nextjs-portal").count(),
            0,
          );
        },
      );
      await step(
        "actual authenticated seeding and requester ownership beyond one page",
        async () => {
          aliceIds = await seed(alice, 51, 2000);
          const dave = await pageFor("dave");
          await seed(dave, 201, 3000);
          await dave.context().close();
          await alice.reload();
          await rows(alice, 50);
          await alice
            .getByRole("button", { name: "Load older requests", exact: true })
            .click();
          await rows(alice, 51);
          const prefixes = await alice
            .locator(".approval-row small")
            .allTextContents();
          const expected = new Set(aliceIds.map(({ id }) => id.slice(0, 8)));
          assert.equal(
            new Set(prefixes.map((text) => text.slice(0, 8))).size,
            51,
          );
          assert.ok(prefixes.every((text) => expected.has(text.slice(0, 8))));
          await alice.getByText("End of results.", { exact: true }).waitFor();
        },
      );
      await step(
        "viewer real continuation reaches the cap without claiming completeness",
        async () => {
          carol = await pageFor("carol");
          await rows(carol, 50);
          assert.equal(
            await carol
              .getByRole("button", { name: "Request approval", exact: true })
              .count(),
            0,
          );
          assert.equal(
            await carol
              .getByRole("button", {
                name: "Approve exact action",
                exact: true,
              })
              .count(),
            0,
          );
          for (const count of [100, 150, 200, 250]) {
            await carol
              .getByRole("button", { name: "Load older requests", exact: true })
              .click();
            await rows(carol, count);
          }
          await carol
            .getByText("Maximum 250 requests loaded.", { exact: true })
            .waitFor();
          assert.equal(
            await carol.getByText("End of results.", { exact: true }).count(),
            0,
          );
          assert.equal(
            await carol
              .getByRole("button", { name: "Load older requests", exact: true })
              .count(),
            0,
          );
          assert.equal(
            new Set(
              (
                await carol.locator(".approval-row small").allTextContents()
              ).map((text) => text.slice(0, 8)),
            ).size,
            250,
          );
          await screenshot(carol, "viewer-cap-desktop");
        },
      );
      await step(
        "exact filters apply explicitly, reset traversal, and support keyboard submit",
        async () => {
          await carol
            .getByRole("button", { name: "Reset", exact: true })
            .click();
          await rows(carol, 50);
          let reads = 0;
          const countRead = (request) => {
            if (
              request.method() === "GET" &&
              new URL(request.url()).pathname === approvalsPath
            )
              reads++;
          };
          carol.on("request", countRead);
          await carol
            .getByLabel("Exact tool name", { exact: true })
            .fill("demo.unknown");
          await carol.evaluate(
            () =>
              new Promise((resolve) =>
                requestAnimationFrame(() => requestAnimationFrame(resolve)),
              ),
          );
          assert.equal(reads, 0, "Draft typing must not fetch");
          await carol
            .getByLabel("Exact tool name", { exact: true })
            .press("Enter");
          await rows(carol, 0);
          assert.equal(reads, 1);
          await carol
            .getByRole("button", { name: "Reset", exact: true })
            .click();
          await rows(carol, 50);
          await carol
            .getByLabel("Exact tool name", { exact: true })
            .fill(toolName);
          await carol
            .getByLabel("Status", { exact: true })
            .selectOption("pending");
          await filterId(carol, aliceIds[0].id);
          assert.equal(
            await carol.locator(".approval-row .approval-status").innerText(),
            "pending",
          );
          await carol
            .getByLabel("Status", { exact: true })
            .selectOption("denied");
          await carol
            .getByRole("button", { name: "Apply filters", exact: true })
            .click();
          await rows(carol, 0);
          await carol
            .getByRole("button", { name: "Reset", exact: true })
            .click();
          await rows(carol, 50);
          assert.equal(
            await carol.getByLabel("Request ID", { exact: true }).inputValue(),
            "",
          );
          assert.equal(
            await carol
              .getByLabel("Exact tool name", { exact: true })
              .inputValue(),
            "",
          );
          carol.off("request", countRead);
        },
      );
      await step(
        "request review and execution are separate explicit rendered actions; replay is rejected",
        async () => {
          await alice.getByLabel("Port", { exact: true }).fill("4443");
          await alice
            .getByRole("button", { name: "Request approval", exact: true })
            .click();
          await alice
            .getByText(
              "Exact simulated change submitted for independent review; not executed.",
              { exact: true },
            )
            .waitFor();
          await rows(alice, 50);
          assert.equal(
            await alice
              .getByRole("button", {
                name: "Approve exact action",
                exact: true,
              })
              .count(),
            0,
          );
          fresh = await alice.evaluate(async (path) => {
            const response = await fetch(path);
            if (!response.ok) return null;
            const { items } = await response.json();
            return {
              id: items[0].approvalRequestId,
              hash: items[0].requestHash,
            };
          }, approvalsPath);
          assert.ok(fresh?.id);
          bob = await pageFor("bob");
          await filterId(bob, fresh.id);
          await bob
            .getByRole("button", { name: "Approve exact action", exact: true })
            .click();
          const confirm = bob.getByRole("region", {
            name: "Confirm exact action",
            exact: true,
          });
          await confirm.waitFor();
          assert.equal(await confirm.locator("code").innerText(), fresh.hash);
          await bob
            .getByRole("button", { name: "Confirm approve", exact: true })
            .click();
          await bob
            .getByText(
              "Review decision recorded. Refresh shows durable server state.",
              { exact: true },
            )
            .waitFor();
          await rows(bob, 1);
          const detail = bob.getByRole("region", {
            name: "Exact action detail",
            exact: true,
          });
          assert.match(await detail.innerText(), /approved/);
          assert.match(await detail.innerText(), /not executed/);
          assert.equal(
            await bob
              .getByRole("region", {
                name: "Confirm exact action",
                exact: true,
              })
              .count(),
            0,
          );
          await filterId(alice, fresh.id);
          await alice
            .getByRole("button", {
              name: "Execute approved simulation",
              exact: true,
            })
            .click();
          assert.equal(
            await alice
              .getByRole("region", {
                name: "Confirm exact action",
                exact: true,
              })
              .locator("code")
              .innerText(),
            fresh.hash,
          );
          await alice
            .getByRole("button", { name: "Confirm execute", exact: true })
            .click();
          await alice
            .getByText(
              "Simulator execution completed. Refresh shows durable server state.",
              { exact: true },
            )
            .waitFor();
          await rows(alice, 1);
          const executed = alice.getByRole("region", {
            name: "Exact action detail",
            exact: true,
          });
          assert.match(await executed.innerText(), /consumed/);
          assert.match(await executed.innerText(), /succeeded/);
          assert.equal(
            await alice
              .getByRole("button", {
                name: "Execute approved simulation",
                exact: true,
              })
              .count(),
            0,
          );
          const replay = await alice.evaluate(
            async ({ approvalsPath, fresh }) => {
              const session = await (await fetch("/browser/session")).json();
              const response = await fetch(
                `${approvalsPath}/${fresh.id}/execute`,
                {
                  method: "POST",
                  headers: {
                    "Content-Type": "application/json",
                    "X-CSRF-Token": session.csrfToken,
                  },
                  body: JSON.stringify({ requestHash: fresh.hash }),
                },
              );
              return response.status;
            },
            { approvalsPath, fresh },
          );
          assert.equal(replay, 409);
          await screenshot(alice, "consumed-desktop");
        },
      );
      await step(
        "narrow viewport keeps usable content without document overflow",
        async () => {
          await carol.setViewportSize({ width: 390, height: 844 });
          await carol
            .getByRole("form", { name: "Filter approvals", exact: true })
            .waitFor();
          assert.equal(
            await carol.evaluate(
              () => document.documentElement.scrollWidth <= window.innerWidth,
            ),
            true,
          );
          await screenshot(carol, "viewer-mobile");
          await carol.getByLabel("Request ID", { exact: true }).fill(fresh.id);
          await carol
            .getByRole("button", { name: "Apply filters", exact: true })
            .click();
          await rows(carol, 1);
          assert.match(
            await carol
              .getByRole("region", { name: "Exact action detail", exact: true })
              .innerText(),
            /consumed/,
          );
        },
      );
      for (const status of [400, 401, 403, 503, "malformed200"]) {
        await step(
          `injected ${status} clears stale authority and reveals no raw diagnostic`,
          async () => {
            const page = await pageFor("alice");
            await rows(page, 50);
            let mutationPosts = 0;
            page.on("request", (request) => {
              if (
                request.method() === "POST" &&
                new URL(request.url()).pathname.startsWith(approvalsPath)
              )
                mutationPosts++;
            });
            await page.route(collectionUrl, (route) =>
              route.fulfill({
                status: status === "malformed200" ? 200 : status,
                contentType: "application/json",
                body:
                  status === "malformed200"
                    ? JSON.stringify({
                        mode: "real",
                        items: [],
                        pageSize: 500,
                        nextCursor: 42,
                      })
                    : CANARY,
              }),
            );
            await page
              .getByRole("button", { name: "Refresh", exact: true })
              .click();
            await page.getByRole("alert").waitFor();
            await assertNoMutation(page);
            // A synthetic submit event also exercises the handler guard, not just disabled styling.
            if (await page.locator("form.approval-request").count())
              await page
                .locator("form.approval-request")
                .evaluate((form) => form.requestSubmit());
            await page.evaluate(
              () =>
                new Promise((resolve) =>
                  requestAnimationFrame(() => requestAnimationFrame(resolve)),
                ),
            );
            assert.equal(mutationPosts, 0);
            await page.unroute(collectionUrl);
            if (status === 401 || status === 403) {
              await page
                .getByRole("heading", { name: signInHeading, exact: true })
                .waitFor();
            } else {
              await page
                .getByRole("button", { name: "Restart search", exact: true })
                .click();
              await rows(page, 50);
            }
            await page.context().close();
          },
        );
      }
      await step(
        "changed authority cannot append an older page into an existing traversal",
        async () => {
          const page = await pageFor("carol");
          await rows(page, 50);
          await page.route("**/browser/session", async (route) => {
            const response = await route.fetch();
            const session = await response.json();
            session.principalId = "synthetic-changed-principal";
            await route.fulfill({ response, json: session });
          });
          await page
            .getByRole("button", { name: "Load older requests", exact: true })
            .click();
          await page.getByRole("alert").waitFor();
          await assertNoMutation(page);
          await page.unroute("**/browser/session");
          await page
            .getByRole("button", { name: "Restart search", exact: true })
            .click();
          await rows(page, 50);
          await page.context().close();
        },
      );
      await step(
        "stored pending status and derived expiry stay distinct; local clock removes authority",
        async () => {
          const page = await pageFor("alice");
          await rows(page, 50);
          await page.route(collectionUrl, async (route) => {
            const response = await route.fetch();
            const body = await response.json();
            body.items[0].status = "pending";
            body.items[0].expired = true;
            body.items[0].expiresAt = "2000-01-01T00:00:00Z";
            await route.fulfill({ response, json: body });
          });
          await page
            .getByRole("button", { name: "Refresh", exact: true })
            .click();
          await page
            .getByText("pending (expired)", { exact: true })
            .first()
            .waitFor();
          await page.unroute(collectionUrl);
          await page.clock.install({ time: new Date() });
          await page.route("**/browser/session", async (route) => {
            const response = await route.fetch();
            const session = await response.json();
            session.expiresAt = await page.evaluate(() =>
              new Date(Date.now() + 60_000).toISOString(),
            );
            await route.fulfill({ response, json: session });
          });
          await page
            .getByRole("button", { name: "Refresh", exact: true })
            .click();
          await rows(page, 50);
          await page.clock.fastForward(60_001);
          await page
            .getByRole("heading", { name: signInHeading, exact: true })
            .waitFor();
          await assertNoMutation(page);
          await page.context().close();
        },
      );
      await step(
        "a read resolving after abort and logout cannot restore cached authority",
        async () => {
          const page = await pageFor("alice");
          await rows(page, 50);
          let mutationPosts = 0;
          page.on("request", (request) => {
            if (
              request.method() === "POST" &&
              new URL(request.url()).pathname.startsWith(approvalsPath)
            )
              mutationPosts++;
          });
          await page.evaluate((path) => {
            const native = window.fetch.bind(window);
            window.fetch = async (input, init) => {
              const response = await native(input, init);
              if (
                String(input).startsWith(path) &&
                (!init?.method || init.method === "GET")
              ) {
                const body = await response.text();
                await new Promise((resolve) => {
                  window.__releaseApprovalRead = resolve;
                });
                // Deliberately ignore cancellation after a fully received response.
                return new Response(body, {
                  status: response.status,
                  headers: { "content-type": "application/json" },
                });
              }
              return response;
            };
          }, approvalsPath);
          await page
            .getByRole("button", { name: "Load older requests", exact: true })
            .click();
          await page.waitForFunction(
            () => typeof window.__releaseApprovalRead === "function",
          );
          assert.equal(
            await page
              .getByRole("button", { name: "Request approval", exact: true })
              .isEnabled(),
            false,
          );
          await page
            .locator("form.approval-request")
            .evaluate((form) => form.requestSubmit());
          assert.equal(mutationPosts, 0);
          await page
            .getByRole("button", { name: "Sign out", exact: true })
            .click();
          await page
            .getByRole("heading", { name: signInHeading, exact: true })
            .waitFor();
          await page.evaluate(() => window.__releaseApprovalRead());
          await page.evaluate(
            () =>
              new Promise((resolve) =>
                requestAnimationFrame(() => requestAnimationFrame(resolve)),
              ),
          );
          await assertNoMutation(page);
          assert.equal(mutationPosts, 0);
          await page.context().close();
        },
      );
      await step(
        "pending response cannot cross a navigation unmount and fresh mount",
        async () => {
          const page = await pageFor("alice");
          await rows(page, 50);
          // A fresh document is an explicit unmount/remount. Production builds do not
          // double-run StrictMode effects; this check does not claim development replay.
          const delayed = [];
          await page.route(collectionUrl, (route) => {
            delayed.push(route);
          });
          await page
            .getByRole("button", { name: "Refresh", exact: true })
            .click();
          await page
            .getByText("Loading current authority and approval state…", {
              exact: true,
            })
            .waitFor();
          await page.goto("about:blank");
          await page.unroute(collectionUrl);
          for (const route of delayed) await route.abort().catch(() => {});
          await page.goto(`${fixture.origin}/approvals`);
          await rows(page, 50);
          assert.equal(
            await page
              .getByRole("button", { name: "Request approval", exact: true })
              .isEnabled(),
            true,
          );
          await page.context().close();
        },
      );
      const confirmName = (decision) =>
        decision === "withdraw" ? "Confirm withdrawal" : `Confirm ${decision}`;
      const withdrawalNotice =
        "Pending request withdrawn. Refresh shows durable server state.";
      const confirmationRegion = (page) =>
        page.getByRole("region", { name: "Confirm exact action", exact: true });
      const withdrawalButton = (page) =>
        page.getByRole("button", { name: "Withdraw request", exact: true });
      async function storedState(page, id) {
        return page.evaluate(async (path) => {
          const response = await fetch(path);
          if (!response.ok) return null;
          const item = await response.json();
          return { status: item.status, executionStatus: item.executionStatus };
        }, `${approvalsPath}/${id}`);
      }
      async function pendingWithdrawal() {
        const page = await pageFor("alice");
        const [item] = await seed(page, 1, 5643);
        await filterId(page, item.id);
        await withdrawalButton(page).click();
        await confirmationRegion(page).waitFor();
        return { page, item };
      }
      function watchWithdrawal(page) {
        const requests = [];
        page.on("request", (request) => {
          if (
            request.method() === "POST" &&
            new URL(request.url()).pathname.endsWith("/cancel")
          )
            requests.push(request);
        });
        return requests;
      }

      await step(
        "owner withdrawal is an explicit exact-hash action; Back is non-mutating; duplicate clicks send one POST",
        async () => {
          const { page, item } = await pendingWithdrawal();
          const requests = watchWithdrawal(page);
          const confirm = confirmationRegion(page);
          assert.deepEqual(await confirm.locator("code").allTextContents(), [
            item.id,
            item.hash,
          ]);
          assert.match(await confirm.innerText(), /not an emergency stop/);
          await confirm
            .getByRole("button", { name: "Back", exact: true })
            .click();
          assert.equal(await confirm.count(), 0);
          assert.equal(requests.length, 0);
          assert.deepEqual(await storedState(page, item.id), {
            status: "pending",
            executionStatus: "not_executed",
          });
          await withdrawalButton(page).click();
          await confirm.scrollIntoViewIfNeeded();
          await screenshot(page, "withdrawal-confirm-desktop");
          await page.setViewportSize({ width: 390, height: 1000 });
          await confirm.scrollIntoViewIfNeeded();
          assert.equal(
            await page.evaluate(
              () => document.documentElement.scrollWidth <= innerWidth,
            ),
            true,
          );
          await screenshot(page, "withdrawal-confirm-mobile");
          await confirm
            .getByRole("button", { name: "Confirm withdrawal", exact: true })
            .evaluate((button) => {
              button.click();
              button.click();
            });
          await page.getByText(withdrawalNotice, { exact: true }).waitFor();
          await rows(page, 1);
          assert.equal(requests.length, 1);
          assert.deepEqual(requests[0].postDataJSON(), {
            requestHash: item.hash,
          });
          assert.equal(
            new URL(requests[0].url()).pathname,
            `${approvalsPath}/${item.id}/cancel`,
          );
          assert.deepEqual(await storedState(page, item.id), {
            status: "cancelled",
            executionStatus: "not_executed",
          });
          await page.getByText(/Withdrawn by its requester/).waitFor();
          assert.equal(await withdrawalButton(page).count(), 0);
          assert.equal(await confirmationRegion(page).count(), 0);
          await page
            .getByLabel("Status", { exact: true })
            .selectOption("cancelled");
          await page
            .getByRole("button", { name: "Apply filters", exact: true })
            .click();
          await rows(page, 1);
          assert.equal(
            await page.locator(".approval-row .approval-status").innerText(),
            "cancelled",
          );
          await page
            .getByRole("region", { name: "Exact action detail", exact: true })
            .scrollIntoViewIfNeeded();
          await screenshot(page, "withdrawal-cancelled-mobile");
          assert.equal(requests.length, 1);
          await page.context().close();
        },
      );

      await step(
        "reviewer viewer and combined-role non-owner have no withdrawal control; legacy scope projection offers none",
        async () => {
          const owner = await pageFor("alice");
          const [item] = await seed(owner, 1, 5743);
          for (const account of ["bob", "carol", "dave"]) {
            const page = await pageFor(account);
            await filterId(page, item.id);
            assert.equal(await withdrawalButton(page).count(), 0);
            await page.context().close();
          }
          // Controlled client projection only; not an external-provider scope test.
          await owner.route("**/browser/session", async (route) => {
            const response = await route.fetch();
            const session = await response.json();
            session.scopes = session.scopes.filter(
              (scope) => scope !== "approvals.cancel",
            );
            await route.fulfill({ response, json: session });
          });
          await owner.reload();
          await filterId(owner, item.id);
          assert.equal(await withdrawalButton(owner).count(), 0);
          assert.deepEqual(await storedState(owner, item.id), {
            status: "pending",
            executionStatus: "not_executed",
          });
          await owner.context().close();
        },
      );

      await step(
        "changing selection filters or refreshing discards withdrawal confirmation without a POST",
        async () => {
          const page = await pageFor("alice");
          const items = await seed(page, 2, 5843);
          await page.reload();
          await rows(page, 50);
          const requests = watchWithdrawal(page);
          const select = (item) =>
            page
              .locator(".approval-row")
              .filter({ hasText: item.id.slice(0, 8) })
              .click();
          await select(items[0]);
          await withdrawalButton(page).click();
          await confirmationRegion(page).waitFor();
          await select(items[1]);
          assert.equal(await confirmationRegion(page).count(), 0);
          await withdrawalButton(page).click();
          await page
            .getByLabel("Request ID", { exact: true })
            .fill(items[1].id);
          assert.equal(await confirmationRegion(page).count(), 0);
          await withdrawalButton(page).click();
          await page
            .getByRole("button", { name: "Refresh", exact: true })
            .click();
          await rows(page, 50);
          assert.equal(await confirmationRegion(page).count(), 0);
          assert.equal(requests.length, 0);
          await page.context().close();
        },
      );

      await step(
        "a real reviewer decision winning before withdrawal yields conflict and never claims withdrawal or executes",
        async () => {
          const { page, item } = await pendingWithdrawal();
          const reviewer = await pageFor("bob");
          await filterId(reviewer, item.id);
          await reviewer
            .getByRole("button", { name: "Approve exact action", exact: true })
            .click();
          await reviewer
            .getByRole("button", { name: "Confirm approve", exact: true })
            .click();
          await reviewer
            .getByText(
              "Review decision recorded. Refresh shows durable server state.",
              { exact: true },
            )
            .waitFor();
          const requests = watchWithdrawal(page);
          await page
            .getByRole("button", { name: "Confirm withdrawal", exact: true })
            .click();
          await page.getByRole("alert").waitFor();
          assert.equal(
            await page.getByText(withdrawalNotice, { exact: true }).count(),
            0,
          );
          assert.equal(requests.length, 1);
          assert.deepEqual(await storedState(reviewer, item.id), {
            status: "approved",
            executionStatus: "not_executed",
          });
          await assertNoMutation(page);
          await page.context().close();
          await reviewer.context().close();
        },
      );

      for (const status of [401, 403]) {
        await step(
          `withdrawal transport HTTP ${status} clears stale authority without retry or raw diagnostics`,
          async () => {
            const { page, item } = await pendingWithdrawal();
            const requests = watchWithdrawal(page);
            await page.route(
              `${fixture.origin}${approvalsPath}/${item.id}/cancel`,
              (route) => route.fulfill({ status, body: CANARY }),
            );
            await page
              .getByRole("button", { name: "Confirm withdrawal", exact: true })
              .click();
            await page.getByRole("alert").waitFor();
            await assertNoMutation(page);
            assert.equal(requests.length, 1);
            assert.equal(
              await page.getByText(withdrawalNotice, { exact: true }).count(),
              0,
            );
            assert.deepEqual(await storedState(page, item.id), {
              status: "pending",
              executionStatus: "not_executed",
            });
            await page.context().close();
          },
        );
      }

      await step(
        "a committed withdrawal with a lost-success transport response stays uncertain and is not automatically repeated",
        async () => {
          const { page, item } = await pendingWithdrawal();
          const requests = watchWithdrawal(page);
          await page.route(
            `${fixture.origin}${approvalsPath}/${item.id}/cancel`,
            async (route) => {
              const response = await route.fetch();
              assert.equal(response.status(), 200);
              // Real Java transaction commits; only the client-facing acknowledgement is replaced.
              await route.fulfill({ status: 503, body: CANARY });
            },
          );
          await page
            .getByRole("button", { name: "Confirm withdrawal", exact: true })
            .click();
          await page.getByRole("alert").waitFor();
          await assertNoMutation(page);
          assert.equal(
            await page.getByText(withdrawalNotice, { exact: true }).count(),
            0,
          );
          assert.equal(requests.length, 1);
          assert.deepEqual(await storedState(page, item.id), {
            status: "cancelled",
            executionStatus: "not_executed",
          });
          assert.equal(requests.length, 1);
          await page.context().close();
        },
      );

      await step(
        "session expiry during an open withdrawal clears confirmation and sends no cancellation",
        async () => {
          const page = await pageFor("alice", true);
          const [item] = await seed(page, 1, 5943);
          // Shorten only the client projection. Java credentials remain unchanged.
          await page.route("**/browser/session", async (route) => {
            const response = await route.fetch();
            const session = await response.json();
            session.expiresAt = await page.evaluate(() =>
              new Date(Date.now() + 10_000).toISOString(),
            );
            await route.fulfill({ response, json: session });
          });
          await page.reload();
          await filterId(page, item.id);
          await withdrawalButton(page).click();
          await confirmationRegion(page).waitFor();
          const requests = watchWithdrawal(page);
          await page.clock.fastForward(11_000);
          await page
            .getByRole("heading", { name: signInHeading, exact: true })
            .waitFor();
          await assertNoMutation(page);
          assert.equal(await confirmationRegion(page).count(), 0);
          assert.equal(requests.length, 0);
          assert.deepEqual(await storedState(page, item.id), {
            status: "pending",
            executionStatus: "not_executed",
          });
          await page.context().close();
        },
      );

      async function expiryConfirmation(decision) {
        const requester = await pageFor("alice", true);
        const [item] = await seed(requester, 1, 5543);
        const reviewer = await pageFor("bob", true);
        await filterId(reviewer, item.id);
        if (decision === "execute") {
          await reviewer
            .getByRole("button", { name: "Approve exact action", exact: true })
            .click();
          await reviewer
            .getByRole("button", { name: "Confirm approve", exact: true })
            .click();
          await reviewer
            .getByText(
              "Review decision recorded. Refresh shows durable server state.",
              { exact: true },
            )
            .waitFor();
        }
        const page = ["execute", "withdraw"].includes(decision)
          ? requester
          : reviewer;
        // The clock was installed before navigation and any application timers.
        await filterId(page, item.id);
        await page
          .getByRole("button", {
            name:
              decision === "withdraw"
                ? "Withdraw request"
                : decision === "execute"
                  ? "Execute approved simulation"
                  : decision === "deny"
                    ? "Deny"
                    : "Approve exact action",
            exact: true,
          })
          .click();
        await page
          .getByRole("region", { name: "Confirm exact action", exact: true })
          .waitFor();
        const expiresAt = await page.evaluate(async (path) => {
          const response = await fetch(path);
          if (!response.ok) return null;
          return (await response.json()).expiresAt;
        }, `${approvalsPath}/${item.id}`);
        assert.ok(Number.isFinite(Date.parse(expiresAt)));
        return { page, item, expiresAt, requester, reviewer };
      }

      for (const decision of ["approve", "execute", "withdraw"]) {
        await step(
          `approval lifetime expires during open ${decision} confirmation without any network mutation`,
          async () => {
            const { page, item, expiresAt, requester, reviewer } =
              await expiryConfirmation(decision);
            const counts = { reads: 0, mutations: 0 };
            const count = (request) => {
              if (!new URL(request.url()).pathname.startsWith("/browser/"))
                return;
              if (request.method() === "GET") counts.reads++;
              if (request.method() === "POST") counts.mutations++;
            };
            page.on("request", count);
            // Only the browser clock advances: this is client UX qualification,
            // not a claim that real database time or server authority advanced.
            const untilExpiry = await page.evaluate(
              (expiry) => Date.parse(expiry) - Date.now(),
              expiresAt,
            );
            assert.ok(untilExpiry > 0 && untilExpiry < 300_001);
            await page.clock.fastForward(untilExpiry + 1_001);
            await page
              .getByText(
                `${decision === "execute" ? "approved" : "pending"} (expired)`,
                { exact: true },
              )
              .first()
              .waitFor();
            await page.getByText(/Approval lifetime has elapsed/).waitFor();
            await page
              .getByRole("region", {
                name: "Confirm exact action",
                exact: true,
              })
              .waitFor({ state: "hidden" });
            assert.equal(
              await page
                .getByRole("button", {
                  name: /^(Approve exact action|Deny|Execute approved simulation|Withdraw request|Confirm (approve|deny|execute|withdrawal))$/,
                })
                .count(),
              0,
            );
            assert.deepEqual(counts, { reads: 0, mutations: 0 });
            // Observed expiry is not undone by a backwards local clock adjustment.
            await page.clock.setSystemTime(
              new Date(Date.parse(expiresAt) - 60_000),
            );
            await page.evaluate(() => window.dispatchEvent(new Event("focus")));
            assert.equal(
              await page
                .getByRole("region", {
                  name: "Confirm exact action",
                  exact: true,
                })
                .count(),
              0,
            );
            if (decision === "execute")
              await page.setViewportSize({ width: 390, height: 1000 });
            const detail = page.getByRole("region", {
              name: "Exact action detail",
              exact: true,
            });
            await detail
              .getByText(/Approval lifetime has elapsed/)
              .scrollIntoViewIfNeeded();
            assert.equal(
              await page.evaluate(
                () => document.documentElement.scrollWidth <= innerWidth,
              ),
              true,
            );
            await screenshot(page, `approval-expired-${decision}`);
            page.off("request", count);
            const stored = await page.evaluate(async (path) => {
              const response = await fetch(path);
              if (!response.ok) return null;
              const value = await response.json();
              return {
                status: value.status,
                executionStatus: value.executionStatus,
              };
            }, `${approvalsPath}/${item.id}`);
            assert.deepEqual(stored, {
              status: decision === "execute" ? "approved" : "pending",
              executionStatus: "not_executed",
            });
            await requester.context().close();
            await reviewer.context().close();
          },
        );
      }

      for (const decision of ["approve", "deny", "execute", "withdraw"]) {
        await step(
          `last-moment ${decision} click after expiry but before a timer tick sends no POST`,
          async () => {
            const { page, expiresAt, requester, reviewer } =
              await expiryConfirmation(decision);
            await page.clock.pauseAt(
              await page.evaluate(() =>
                new Date(Date.now() + 1_000).toISOString(),
              ),
            );
            let posts = 0;
            const count = (request) => {
              if (
                request.method() === "POST" &&
                new URL(request.url()).pathname.startsWith("/browser/")
              )
                posts++;
            };
            page.on("request", count);
            await page.clock.setSystemTime(new Date(Date.parse(expiresAt) + 1));
            // The stale DOM still has the button: its handler, not a render/tick,
            // must refuse this attempt before fetch is called.
            await page
              .getByRole("button", { name: confirmName(decision), exact: true })
              .click();
            assert.equal(
              posts,
              0,
              "An expired confirmation must not issue a POST.",
            );
            await page
              .getByText(
                "This confirmation is no longer available. Refresh the request before continuing; nothing was sent or retried.",
                { exact: true },
              )
              .waitFor();
            assert.equal(posts, 0);
            assert.equal(
              await page
                .getByRole("region", {
                  name: "Confirm exact action",
                  exact: true,
                })
                .count(),
              0,
            );
            page.off("request", count);
            await requester.context().close();
            await reviewer.context().close();
          },
        );
      }

      for (const event of ["focus", "visibilitychange"]) {
        await step(
          `${event} resamples approval expiry without an automatic read or POST`,
          async () => {
            const { page, expiresAt, requester, reviewer } =
              await expiryConfirmation("approve");
            await page.clock.pauseAt(
              await page.evaluate(() =>
                new Date(Date.now() + 1_000).toISOString(),
              ),
            );
            const requests = [];
            const count = (request) => {
              if (new URL(request.url()).pathname.startsWith("/browser/"))
                requests.push(request.method());
            };
            page.on("request", count);
            await page.clock.setSystemTime(new Date(Date.parse(expiresAt) + 1));
            assert.equal(
              await page
                .getByRole("region", {
                  name: "Confirm exact action",
                  exact: true,
                })
                .count(),
              1,
            );
            await page.evaluate((name) => {
              if (name === "focus") window.dispatchEvent(new Event(name));
              else {
                if (document.visibilityState !== "visible")
                  throw new Error("VISIBLE_EVENT_PRECONDITION");
                document.dispatchEvent(new Event(name));
              }
            }, event);
            await page
              .getByText("pending (expired)", { exact: true })
              .first()
              .waitFor();
            assert.equal(
              await page
                .getByRole("region", {
                  name: "Confirm exact action",
                  exact: true,
                })
                .count(),
              0,
            );
            assert.deepEqual(requests, []);
            page.off("request", count);
            await requester.context().close();
            await reviewer.context().close();
          },
        );
      }

      await step(
        "page identity meaningful content overlays and console health",
        async () => {
          assert.equal(new URL(alice.url()).pathname, "/approvals");
          assert.match(await alice.title(), /TracePermit/);
          await alice
            .getByRole("heading", { name: "Approval inbox", exact: true })
            .waitFor();
          assert.equal(
            await alice.locator("vite-error-overlay, nextjs-portal").count(),
            0,
          );
          if (runtimeFailures.length)
            t.diagnostic(JSON.stringify(runtimeFailures));
          assert.equal(runtimeFailures.length, 0);
          assert.equal(unexpectedHttp.length, 0);
          assert.equal(
            await alice.evaluate(
              () => localStorage.length === 0 && sessionStorage.length === 0,
            ),
            true,
          );
          assert.ok(
            !(await alice.locator("body").innerText()).includes(CANARY),
          );
        },
      );
      t.diagnostic(
        "Actual Java/PostgreSQL/synthetic-IdP flows and labeled client-fault controls passed; no real target or personal login used.",
      );
      t.diagnostic(
        `Browser version: ${browser.version()}; channel: ${channel ?? "pinned Chromium"}. Optional owned-origin favicon404 and expected API negative controls are classified.`,
      );
      if (evidenceDirectory)
        t.diagnostic(`Private synthetic screenshots: ${evidenceDirectory}`);
    } catch (failure) {
      failedBeforeCleanup = true;
      if (failure.message?.startsWith("BROWSER_")) throw failure;
      throw new Error(
        `BROWSER_SUITE_FAILED: ${currentStep}; sensitive details withheld`,
      );
    } finally {
      try {
        try {
          for (const context of contexts) {
            await within(
              context.close(),
              5_000,
              "BROWSER_CONTEXT_CLOSE_TIMEOUT",
            );
          }
        } finally {
          try {
            if (browser)
              await within(browser.close(), 10_000, "BROWSER_CLOSE_TIMEOUT");
          } finally {
            if (fixture) {
              await fixture.stop();
              t.diagnostic(
                "Owned fixture exited cleanly and its listener port was released.",
              );
            }
          }
        }
      } catch {
        if (failedBeforeCleanup)
          t.diagnostic(
            "BROWSER_CLEANUP_FAILED: original test failure preserved.",
          );
        else throw new Error("BROWSER_CLEANUP_FAILED");
      }
    }
  },
);
