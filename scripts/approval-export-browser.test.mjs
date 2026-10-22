import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import {
  chmod,
  mkdtemp,
  readFile,
  readdir,
  rm,
  writeFile,
} from "node:fs/promises";
import { createConnection } from "node:net";
import { tmpdir } from "node:os";
import { basename, delimiter, dirname, isAbsolute, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { chromium } from "playwright";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const UI_TIMEOUT = 15_000;
const START_TIMEOUT = 240_000;
const OUTPUT_LIMIT = 1_048_576;
const CANARY = "synthetic-private-diagnostic-must-not-render";
const signInHeading = "Sign in to your approval workspace";
const invalidRecordNotice =
  "The review export record was invalid or could not be verified. Server state remains authoritative.";
const REVIEW_VERIFIER_SUCCESS =
  "STRUCTURE_AND_CHECKSUM_VALID UNSIGNED SIMULATOR_ONLY NOT_AUTHORITY\n";
const REVIEW_VERIFIER_PREFIX = "TRACEPERMIT_APPROVAL_REVIEW_CHECK: ";

function verifierEnvironment() {
  const javaHome = process.env.JAVA_HOME;
  const javaDirectory =
    typeof javaHome === "string" && javaHome.length > 0
      ? join(javaHome, "bin")
      : (process.env.PATH ?? "")
          .split(delimiter)
          .find((entry) => existsSync(join(entry, "java")));
  assert.ok(
    typeof javaDirectory === "string" && javaDirectory.length > 0,
    "REVIEW_VERIFIER_JAVA_UNAVAILABLE",
  );
  const environment = {
    PATH: `${javaDirectory}:/usr/bin:/bin`,
    LANG: "C.UTF-8",
  };
  if (typeof javaHome === "string" && javaHome.length > 0) {
    environment.JAVA_HOME = javaHome;
  }
  const version = spawnSync(join(javaDirectory, "java"), ["-version"], {
    env: environment,
    encoding: "utf8",
    timeout: 5_000,
    maxBuffer: 16_384,
  });
  assert.equal(version.error, undefined, "REVIEW_VERIFIER_JAVA_UNAVAILABLE");
  assert.equal(version.status, 0, "REVIEW_VERIFIER_JAVA_UNAVAILABLE");
  assert.match(
    `${version.stdout}${version.stderr}`,
    /version "21(?:\.|\")/,
    "REVIEW_VERIFIER_REQUIRES_JAVA_21",
  );
  return environment;
}

function runInstalledVerifier(path, workingDirectory) {
  const launcher = process.env.TRACEPERMIT_REVIEW_VERIFIER_PATH;
  assert.ok(
    typeof launcher === "string" && launcher.length > 0,
    "REVIEW_VERIFIER_LAUNCHER_UNAVAILABLE",
  );
  assert.match(
    launcher,
    /[/\\]build[/\\]install[/\\]review-verifier[/\\]bin[/\\]review-verifier$/,
    "REVIEW_VERIFIER_INSTALLED_LAUNCHER_REQUIRED",
  );
  return spawnSync(launcher, [path], {
    cwd: workingDirectory,
    env: verifierEnvironment(),
    encoding: "utf8",
    timeout: 15_000,
    maxBuffer: 65_536,
  });
}

function assertVerifierOutcome(
  path,
  workingDirectory,
  expectedStatus,
  expectedCategory,
) {
  const result = runInstalledVerifier(path, workingDirectory);
  assert.equal(result.error, undefined, "REVIEW_VERIFIER_LAUNCH_FAILED");
  assert.equal(result.status, expectedStatus, "REVIEW_VERIFIER_EXIT_MISMATCH");
  if (expectedStatus === 0) {
    assert.equal(result.stdout, REVIEW_VERIFIER_SUCCESS);
    assert.equal(result.stderr, "");
  } else {
    assert.equal(result.stdout, "");
    assert.equal(
      result.stderr,
      `${REVIEW_VERIFIER_PREFIX}${expectedCategory}\n`,
    );
  }
  const boundedOutput = `${result.stdout}${result.stderr}`;
  assert.ok(Buffer.byteLength(boundedOutput) <= 256);
  assert.ok(!boundedOutput.includes(CANARY));
  assert.ok(!boundedOutput.includes(path));
  assert.ok(!boundedOutput.includes(basename(path)));
}

async function fileSha256(path) {
  return createHash("sha256")
    .update(await readFile(path))
    .digest("hex");
}

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

function dockerContainerIds() {
  const result = spawnSync("docker", ["ps", "--all", "--format", "{{.ID}}"], {
    timeout: 15_000,
    maxBuffer: 65_536,
    encoding: "utf8",
    env: fixtureEnvironment(),
  });
  assert.equal(result.status, 0, "BROWSER_FIXTURE_DOCKER_UNAVAILABLE");
  return new Set(
    result.stdout
      .split("\n")
      .map((value) => value.trim())
      .filter(Boolean),
  );
}

async function fixtureDirectoryNames() {
  return new Set(
    (await readdir(tmpdir(), { withFileTypes: true }))
      .filter(
        (entry) =>
          entry.isDirectory() &&
          (entry.name.startsWith("tracepermit-browser-qualification-") ||
            entry.name.startsWith("tracepermit-browser-run.")),
      )
      .map((entry) => entry.name),
  );
}

async function assertNoNewContainers(baseline) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const current = dockerContainerIds();
    if ([...current].every((id) => baseline.has(id))) return;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error("BROWSER_FIXTURE_CONTAINER_LINGERING");
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
    env: {
      ...fixtureEnvironment(),
      TRACEPERMIT_BROWSER_EXPORT_QUALIFICATION: "1",
    },
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
    child.once("exit", (code, signal) => {
      rejectReady(new Error("BROWSER_FIXTURE_EXITED_BEFORE_READY"));
      resolve({ code, signal });
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
    let result = await Promise.race([
      exited,
      new Promise((resolve) => setTimeout(() => resolve(null), 5_000)),
    ]);
    if (result === null) {
      killOwnedGroup("SIGKILL");
      result = await within(exited, 5_000, "BROWSER_FIXTURE_PROCESS_LINGERING");
    }
    assert.ok(result, "BROWSER_FIXTURE_PROCESS_LINGERING");
    let portReleased = !origin;
    if (origin) {
      for (let attempt = 0; attempt < 40; attempt++) {
        if (!(await portIsOpen(origin))) {
          portReleased = true;
          break;
        }
        await new Promise((resolve) => setTimeout(resolve, 250));
      }
    }
    if (!portReleased) throw new Error("BROWSER_FIXTURE_PORT_LINGERING");
    if (child.pid) {
      for (let attempt = 0; attempt < 40; attempt++) {
        try {
          process.kill(-child.pid, 0);
        } catch (failure) {
          if (failure.code === "ESRCH") return;
          throw failure;
        }
        await new Promise((resolve) => setTimeout(resolve, 250));
      }
      throw new Error("BROWSER_FIXTURE_PROCESS_GROUP_LINGERING");
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

function isExportUrl(url) {
  return /\/browser\/api\/v1\/approvals\/[a-f0-9-]{36}\/export$/.test(
    new URL(url).pathname,
  );
}

test(
  "approval review browser download is verified byte-for-byte by the installed checker",
  { timeout: 480_000 },
  async (t) => {
    let fixture;
    let browser;
    const contexts = new Set();
    let evidenceDirectory;
    let baselineContainers;
    let baselineFixtureDirectories;
    let positiveSavePath;
    let positiveRecord;
    let positiveDigest;
    let currentStep = "initialization";

    const step = async (title, action) => {
      currentStep = title;
      await action();
      t.diagnostic(`PASS: ${title}`);
    };

    const closePage = async (page) => {
      assert.ok(!(await page.locator("body").innerText()).includes(CANARY));
      const context = page.context();
      await within(context.close(), 5_000, "BROWSER_CONTEXT_CLOSE_TIMEOUT");
      contexts.delete(context);
    };

    try {
      baselineContainers = dockerContainerIds();
      baselineFixtureDirectories = await fixtureDirectoryNames();
      evidenceDirectory = await mkdtemp(
        join(tmpdir(), "tp-export-qualification-"),
      );
      await chmod(evidenceDirectory, 0o700);

      const channel = process.env.TRACEPERMIT_BROWSER_CHANNEL;
      const executablePath = process.env.TRACEPERMIT_BROWSER_EXECUTABLE;
      assert.ok(!channel || channel === "chrome", "BROWSER_CHANNEL_REJECTED");
      assert.ok(
        !executablePath ||
          (isAbsolute(executablePath) && existsSync(executablePath)),
        "BROWSER_EXECUTABLE_REJECTED",
      );
      assert.ok(
        !(channel && executablePath),
        "BROWSER_LAUNCH_CONFIGURATION_REJECTED",
      );
      browser = await chromium.launch({
        channel,
        executablePath,
        env: fixtureEnvironment(),
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
        contexts.add(context);
        const page = await context.newPage();
        const unsafeBrowserEvents = [];
        page.on("pageerror", () => unsafeBrowserEvents.push("pageerror"));
        page.on("console", (message) => {
          if (
            message.text().includes(CANARY) ||
            (message.type() === "error" &&
              !message
                .text()
                .startsWith(
                  "Failed to load resource: the server responded with a status of",
                ))
          ) {
            unsafeBrowserEvents.push("console");
          }
        });
        page.assertHealthy = () =>
          assert.deepEqual(unsafeBrowserEvents, [], "BROWSER_CONSOLE_FAILURE");
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
          const sessionResponse = await fetch("/browser/session");
          if (!sessionResponse.ok) return null;
          const session = await sessionResponse.json();
          const response = await fetch("/browser/api/v1/approvals", {
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
          return response.ok ? await response.json() : null;
        }, port);
        assert.ok(result?.approvalRequestId, "BROWSER_SEED_FAILED");
        return result;
      };

      const selectSeededRow = async (page, port = 4443) => {
        const item = await seed(page, port);
        await page.reload();
        await page
          .getByRole("form", { name: "Filter approvals", exact: true })
          .waitFor({ timeout: UI_TIMEOUT });
        const row = page.locator("button.approval-row").filter({
          hasText: item.approvalRequestId.slice(0, 8),
        });
        await row.waitFor({ timeout: UI_TIMEOUT });
        await row.click();
        return item;
      };

      const exportButton = (page) =>
        page.getByRole("button", {
          name: "Download review JSON",
          exact: true,
        });

      await step(
        "real Java route to browser download to exact installed-verifier file",
        async () => {
          const page = await pageFor("alice");
          const seeded = await selectSeededRow(page);
          assert.match(
            seeded.approvalRequestId,
            /^[a-f0-9-]{36}$/,
            "BROWSER_APPROVAL_ID_INVALID",
          );

          const exportSection = page.locator("div.approval-export-section");
          await exportSection.waitFor({ timeout: UI_TIMEOUT });
          const explanation = await exportSection
            .locator("p.approval-muted")
            .innerText();
          for (const limitation of [
            "simulator-only",
            "metadata-only",
            "unsigned",
            "incomplete",
          ]) {
            assert.ok(explanation.includes(limitation));
          }

          let requestCount = 0;
          let downloadCount = 0;
          page.on("request", (request) => {
            if (isExportUrl(request.url())) requestCount++;
          });
          page.on("download", () => downloadCount++);
          const responsePromise = page.waitForResponse(
            (response) => isExportUrl(response.url()),
            { timeout: UI_TIMEOUT },
          );
          const downloadPromise = page.waitForEvent("download", {
            timeout: UI_TIMEOUT,
          });
          await exportButton(page).click();
          const [response, download] = await Promise.all([
            responsePromise,
            downloadPromise,
          ]);
          assert.equal(response.status(), 200);
          assert.equal(requestCount, 1, "EXPORT_ROUTE_RETRY_DETECTED");
          assert.equal(downloadCount, 1, "SECOND_BROWSER_DOWNLOAD_DETECTED");
          assert.equal(await download.failure(), null);
          const filename = download.suggestedFilename();
          assert.equal(
            filename,
            `tracepermit-approval-${seeded.approvalRequestId}.json`,
          );

          positiveSavePath = join(evidenceDirectory, filename);
          await download.saveAs(positiveSavePath);
          const downloadedBytes = await readFile(positiveSavePath);
          positiveRecord = JSON.parse(downloadedBytes.toString("utf8"));
          assert.equal(positiveRecord.schemaVersion, "1.0");
          assert.equal(positiveRecord.kind, "tracepermit-approval-review");
          assert.equal(
            positiveRecord.canonicalization,
            "tracepermit-canonical-json-v1",
          );
          assert.equal(
            positiveRecord.payload.generatedAt,
            "2030-01-01T00:00:00Z",
          );
          assert.equal(positiveRecord.payload.mode, "simulated");
          assert.equal(positiveRecord.payload.approval.mode, "simulated");
          assert.equal(
            positiveRecord.payload.approval.approvalRequestId,
            seeded.approvalRequestId,
          );
          assert.equal(
            positiveRecord.payload.approval.requestHash,
            seeded.requestHash,
          );
          assert.equal(positiveRecord.payload.assurance.signed, false);
          assert.equal(
            positiveRecord.payload.assurance.authorizesExecution,
            false,
          );
          const serialized = downloadedBytes.toString("utf8");
          for (const forbidden of [
            '"arguments"',
            '"target"',
            "access_token",
            "id_token",
            "credential",
            "privateKey",
          ]) {
            assert.ok(!serialized.includes(forbidden));
          }

          positiveDigest = await fileSha256(positiveSavePath);
          assertVerifierOutcome(positiveSavePath, evidenceDirectory, 0);
          assert.equal(
            await fileSha256(positiveSavePath),
            positiveDigest,
            "DOWNLOADED_BYTES_CHANGED_DURING_VERIFICATION",
          );
          t.diagnostic(`Downloaded file SHA-256: ${positiveDigest}`);
          page.assertHealthy();
          await closePage(page);
        },
      );

      await step(
        "installed verifier rejects downloaded-byte negative controls once with bounded categories",
        async () => {
          assert.ok(positiveSavePath && positiveRecord && positiveDigest);
          const clone = () => structuredClone(positiveRecord);
          const cases = [
            {
              name: "malformed-json",
              bytes: Buffer.from(`{"canary":"${CANARY}"`),
              status: 5,
              category: "MALFORMED_DOCUMENT",
            },
            {
              name: "oversized-input",
              bytes: Buffer.alloc(32_769, 0x58),
              status: 4,
              category: "INPUT_TOO_LARGE",
            },
            {
              name: "unsupported-schema-version",
              mutate(record) {
                record.schemaVersion = "2.0";
              },
              status: 6,
              category: "CONTRACT_INVALID",
            },
            {
              name: "unknown-top-level-field",
              mutate(record) {
                record.untrusted = CANARY;
              },
              status: 6,
              category: "CONTRACT_INVALID",
            },
            {
              name: "incorrect-mode",
              mutate(record) {
                record.payload.mode = "production";
              },
              status: 6,
              category: "CONTRACT_INVALID",
            },
            {
              name: "incorrect-assurance",
              mutate(record) {
                record.payload.assurance.signed = true;
              },
              status: 6,
              category: "CONTRACT_INVALID",
            },
            {
              name: "wrong-checksum",
              mutate(record) {
                record.payloadHash = `sha256:${"0".repeat(64)}`;
              },
              status: 7,
              category: "CHECKSUM_MISMATCH",
            },
            {
              name: "mutated-downloaded-payload",
              mutate(record) {
                record.payload.approval.policyId = "mutated-policy";
              },
              status: 7,
              category: "CHECKSUM_MISMATCH",
            },
            {
              name: "missing-required-evidence",
              mutate(record) {
                delete record.payload.approval.requestHash;
              },
              status: 6,
              category: "CONTRACT_INVALID",
            },
          ];

          for (const control of cases) {
            const record = clone();
            control.mutate?.(record);
            const bytes = control.bytes ?? Buffer.from(JSON.stringify(record));
            const path = join(evidenceDirectory, `${control.name}.json`);
            await writeFile(path, bytes, { mode: 0o600, flag: "wx" });
            const before = await fileSha256(path);
            assertVerifierOutcome(
              path,
              evidenceDirectory,
              control.status,
              control.category,
            );
            assert.equal(
              await fileSha256(path),
              before,
              "NEGATIVE_CONTROL_BYTES_CHANGED",
            );
          }

          const missingPath = join(
            evidenceDirectory,
            "absent-verifier-input.json",
          );
          assertVerifierOutcome(
            missingPath,
            evidenceDirectory,
            3,
            "UNSUPPORTED_INPUT",
          );
          assert.equal(await fileSha256(positiveSavePath), positiveDigest);
        },
      );

      await step(
        "browser parser rejects malformed, oversized, version, shape, mode, assurance and evidence controls",
        async () => {
          const page = await pageFor("alice");
          await selectSeededRow(page, 4444);
          let totalDownloads = 0;
          page.on("download", () => totalDownloads++);

          const cases = [
            {
              name: "malformed JSON",
              body: `{"canary":"${CANARY}"`,
            },
            {
              name: "oversized input",
              body: JSON.stringify({ padding: "X".repeat(40_000) }),
            },
            {
              name: "unsupported schema version",
              mutate(record) {
                record.schemaVersion = "2.0";
              },
            },
            {
              name: "unknown top-level field",
              mutate(record) {
                record.untrusted = CANARY;
              },
            },
            {
              name: "incorrect mode",
              mutate(record) {
                record.payload.mode = "production";
              },
            },
            {
              name: "incorrect assurance metadata",
              mutate(record) {
                record.payload.assurance.authorizesExecution = true;
              },
            },
            {
              name: "missing required evidence",
              mutate(record) {
                delete record.payload.approval.requestHash;
              },
            },
          ];

          for (const control of cases) {
            currentStep = `browser rejection: ${control.name}`;
            let requests = 0;
            let markRequestStarted;
            const requestStarted = new Promise((resolve) => {
              markRequestStarted = resolve;
            });
            const routeHandler = async (route) => {
              requests++;
              markRequestStarted();
              let body = control.body;
              if (body === undefined) {
                const upstream = await route.fetch();
                const record = await upstream.json();
                control.mutate(record);
                body = JSON.stringify(record);
              }
              await route.fulfill({
                status: 200,
                contentType: "application/json",
                body,
              });
            };
            await page.route(
              "**/browser/api/v1/approvals/*/export",
              routeHandler,
            );
            const downloadsBefore = totalDownloads;
            await exportButton(page).waitFor({ timeout: UI_TIMEOUT });
            assert.equal(await exportButton(page).isEnabled(), true);
            await exportButton(page).click();
            await within(
              requestStarted,
              UI_TIMEOUT,
              "BROWSER_NEGATIVE_REQUEST_NOT_STARTED",
            );
            const notice = page.locator("p.approval-notice");
            await notice.waitFor({ timeout: UI_TIMEOUT });
            await exportButton(page).waitFor({ timeout: UI_TIMEOUT });
            assert.equal(
              await notice.innerText(),
              invalidRecordNotice,
              "BROWSER_NEGATIVE_NOTICE_MISMATCH",
            );
            assert.equal(requests, 1, "BROWSER_NEGATIVE_RETRY_DETECTED");
            assert.equal(
              totalDownloads,
              downloadsBefore,
              "BROWSER_NEGATIVE_FALSE_DOWNLOAD",
            );
            assert.ok(
              !(await notice.innerText()).includes(CANARY),
              "BROWSER_NEGATIVE_DIAGNOSTIC_LEAK",
            );
            await page.unrouteAll({ behavior: "wait" });
            t.diagnostic(`PASS: browser rejection ${control.name}`);
          }
          page.assertHealthy();
          await closePage(page);
        },
      );

      await step(
        "controlled API and browser download failures are single-shot and fail closed",
        async () => {
          const apiPage = await pageFor("alice");
          await selectSeededRow(apiPage, 4445);
          let apiRequests = 0;
          let apiDownloads = 0;
          apiPage.on("download", () => apiDownloads++);
          const apiHandler = async (route) => {
            apiRequests++;
            await route.fulfill({
              status: 503,
              contentType: "application/json",
              body: JSON.stringify({ error: { code: CANARY } }),
            });
          };
          await apiPage.route(
            "**/browser/api/v1/approvals/*/export",
            apiHandler,
          );
          await exportButton(apiPage).click();
          const apiNotice = apiPage.locator("p.approval-notice");
          await apiNotice.waitFor({ timeout: UI_TIMEOUT });
          assert.equal(
            await apiNotice.innerText(),
            "The approval service could not complete this export. Check its state before retrying.",
          );
          assert.equal(apiRequests, 1);
          assert.equal(apiDownloads, 0);
          apiPage.assertHealthy();
          await closePage(apiPage);

          const downloadPage = await pageFor("alice");
          await selectSeededRow(downloadPage, 4446);
          let downloadRequests = 0;
          let failedDownloads = 0;
          downloadPage.on("request", (request) => {
            if (isExportUrl(request.url())) downloadRequests++;
          });
          downloadPage.on("download", () => failedDownloads++);
          await downloadPage.evaluate(() => {
            URL.createObjectURL = () => {
              throw new Error("controlled-browser-download-failure");
            };
          });
          await exportButton(downloadPage).click();
          const downloadNotice = downloadPage.locator("p.approval-notice");
          await downloadNotice.waitFor({ timeout: UI_TIMEOUT });
          assert.equal(await downloadNotice.innerText(), invalidRecordNotice);
          assert.equal(downloadRequests, 1);
          assert.equal(failedDownloads, 0);
          downloadPage.assertHealthy();
          await closePage(downloadPage);
        },
      );

      await step(
        "refresh, filter and logout invalidate one in-flight export without a download",
        async () => {
          for (const transition of ["refresh", "filter", "logout"]) {
            const page = await pageFor("alice");
            await selectSeededRow(
              page,
              transition === "refresh"
                ? 4447
                : transition === "filter"
                  ? 4448
                  : 4449,
            );
            let requests = 0;
            let downloads = 0;
            let release;
            let started;
            const startedPromise = new Promise((resolve) => {
              started = resolve;
            });
            const releasePromise = new Promise((resolve) => {
              release = resolve;
            });
            let handlerFinished;
            const handlerFinishedPromise = new Promise((resolve) => {
              handlerFinished = resolve;
            });
            page.on("download", () => downloads++);
            const routeHandler = async (route) => {
              requests++;
              try {
                const upstream = await route.fetch();
                started();
                await releasePromise;
                await route.fulfill({ response: upstream });
              } catch {
                // An abort is the expected browser behavior for these invalidations.
              } finally {
                handlerFinished();
              }
            };
            await page.route(
              "**/browser/api/v1/approvals/*/export",
              routeHandler,
            );
            await exportButton(page).click();
            await within(
              startedPromise,
              UI_TIMEOUT,
              "STALE_EXPORT_ROUTE_NOT_STARTED",
            );

            if (transition === "refresh") {
              await page
                .getByRole("button", { name: "Refresh", exact: true })
                .click();
            } else if (transition === "filter") {
              await page.locator("#filter-status").selectOption("pending");
              await page
                .getByRole("button", { name: "Apply filters", exact: true })
                .click();
            } else {
              await page
                .getByRole("button", { name: "Sign out", exact: true })
                .click();
              await page
                .getByRole("heading", { name: signInHeading, exact: true })
                .waitFor({ timeout: UI_TIMEOUT });
            }
            release();
            await within(
              handlerFinishedPromise,
              UI_TIMEOUT,
              "STALE_EXPORT_ROUTE_LINGERING",
            );
            await page.waitForTimeout(250);
            assert.equal(requests, 1, `${transition}: retry detected`);
            assert.equal(
              downloads,
              0,
              `${transition}: stale download occurred`,
            );
            page.assertHealthy();
            await closePage(page);
          }
        },
      );

      await step(
        "real Java boundary denies stale sessions and cross-workspace requester visibility",
        async () => {
          const ownerPage = await pageFor("dave");
          const privateApproval = await seed(ownerPage, 4450);
          ownerPage.assertHealthy();
          await closePage(ownerPage);

          const requesterPage = await pageFor("alice");
          let requesterDownloads = 0;
          requesterPage.on("download", () => requesterDownloads++);
          const invisible = await requesterPage.request.get(
            `${fixture.origin}/browser/api/v1/approvals/${encodeURIComponent(privateApproval.approvalRequestId)}/export`,
          );
          assert.equal(invisible.status(), 404);
          assert.equal(requesterDownloads, 0);

          const ownApproval = await seed(requesterPage, 4451);
          await requesterPage
            .getByRole("button", { name: "Sign out", exact: true })
            .click();
          await requesterPage
            .getByRole("heading", { name: signInHeading, exact: true })
            .waitFor({ timeout: UI_TIMEOUT });
          const stale = await requesterPage.request.get(
            `${fixture.origin}/browser/api/v1/approvals/${encodeURIComponent(ownApproval.approvalRequestId)}/export`,
          );
          assert.equal(stale.status(), 401);
          assert.equal(requesterDownloads, 0);
          requesterPage.assertHealthy();
          await closePage(requesterPage);
        },
      );

      await step(
        "downloaded positive bytes remain unchanged until qualification cleanup",
        async () => {
          assert.equal(await fileSha256(positiveSavePath), positiveDigest);
        },
      );
    } catch (failure) {
      if (
        typeof failure?.message === "string" &&
        /^(BROWSER_|REVIEW_VERIFIER_|DOWNLOADED_|NEGATIVE_|EXPORT_|SECOND_|STALE_)/.test(
          failure.message,
        )
      ) {
        throw failure;
      }
      throw new Error(
        `BROWSER_EXPORT_QUALIFICATION_FAILED: ${currentStep}; sensitive details withheld`,
      );
    } finally {
      for (const context of [...contexts]) {
        await within(context.close(), 5_000, "BROWSER_CONTEXT_CLOSE_TIMEOUT");
        contexts.delete(context);
      }
      assert.equal(contexts.size, 0, "BROWSER_CONTEXT_LINGERING");
      if (browser) {
        await within(browser.close(), 10_000, "BROWSER_CLOSE_TIMEOUT");
      }
      if (fixture) {
        await fixture.stop();
      }
      if (baselineContainers) {
        await assertNoNewContainers(baselineContainers);
      }
      if (baselineFixtureDirectories) {
        const currentFixtureDirectories = await fixtureDirectoryNames();
        assert.ok(
          [...currentFixtureDirectories].every((name) =>
            baselineFixtureDirectories.has(name),
          ),
          "BROWSER_FIXTURE_DIRECTORY_LINGERING",
        );
      }
      if (evidenceDirectory) {
        await rm(evidenceDirectory, { recursive: true });
        assert.equal(
          existsSync(evidenceDirectory),
          false,
          "BROWSER_EVIDENCE_DIRECTORY_LINGERING",
        );
      }
      t.diagnostic(
        "Owned server, browser, containers, processes and qualification artifacts were cleaned up.",
      );
    }
  },
);
