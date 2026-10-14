import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const source = dirname(fileURLToPath(import.meta.url));
const profiles = [
  { script: "run-demo.sh", ready: "TracePermit Demo++ is ready", key: true },
  {
    script: "run-hosted-client-oauth-demo.sh",
    ready: "TracePermit hosted-client OAuth demo is ready",
    key: false,
  },
];

async function waitUntil(condition) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    if (condition()) return;
    await delay(25);
  }
  throw new Error(
    "The isolated launcher fixture did not reach its expected state.",
  );
}

function alive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error.code === "ESRCH") return false;
    throw error;
  }
}

function fixture(profile, busyPort = false) {
  const root = mkdtempSync(join(tmpdir(), "tracepermit-shutdown-test-"));
  const scripts = join(root, "scripts");
  const bin = join(root, "bin");
  mkdirSync(scripts);
  mkdirSync(bin);
  copyFileSync(join(source, profile.script), join(scripts, profile.script));
  copyFileSync(
    join(source, "demo-cleanup-guardian.mjs"),
    join(scripts, "demo-cleanup-guardian.mjs"),
  );
  writeFileSync(join(root, "package.json"), '{"version":"0.1.0"}');
  writeFileSync(join(scripts, "configure-java-environment.sh"), ":\n");
  writeFileSync(
    join(root, "child.mjs"),
    `import {writeFileSync} from "node:fs";
     import {join} from "node:path";
     writeFileSync(join(process.env.TRACEPERMIT_TEST_ROOT, process.argv[2]+".pid"), String(process.pid));
     process.on("SIGTERM", () => process.exit(0));
     setInterval(() => {}, 1000);`,
  );
  const child =
    'exec "$TRACEPERMIT_TEST_NODE" "$TRACEPERMIT_TEST_ROOT/child.mjs" backend\n';
  for (const name of [
    "run-java-development-backend.sh",
    "run-java-hosted-client-oauth-backend.sh",
  ]) {
    writeFileSync(join(scripts, name), `#!/usr/bin/env bash\n${child}`, {
      mode: 0o700,
    });
  }
  const commands = {
    curl: "exit 0",
    lsof: `exit ${busyPort ? 0 : 1}`,
    git: "printf 'abcdef0\\n'",
    java: "printf 'openjdk version 21\\n'",
    pnpm: `if [[ "$1" == "--version" ]]; then printf '11.25.0\\n'; exit 0; fi
      if [[ "$*" == *build:java-endpoint* ]]; then exit 0; fi
      exec "$TRACEPERMIT_TEST_NODE" "$TRACEPERMIT_TEST_ROOT/child.mjs" console`,
  };
  for (const [name, command] of Object.entries(commands)) {
    writeFileSync(join(bin, name), `#!/usr/bin/env bash\n${command}\n`, {
      mode: 0o700,
    });
  }
  const oauth = Object.fromEntries(
    [
      "ISSUER",
      "RESOURCE",
      "RESOURCE_METADATA_URI",
      "AUTHORIZATION_SERVER",
      "JWKS_URI",
      "CLIENT_ID",
      "TENANT_VALUE",
      "TENANT_ID",
    ].map((name) => [`TRACEPERMIT_OAUTH_${name}`, "synthetic-fixture"]),
  );
  const launcher = spawn("bash", [join(scripts, profile.script)], {
    cwd: root,
    detached: true,
    env: {
      ...process.env,
      ...oauth,
      PATH: `${bin}:${process.env.PATH}`,
      TRACEPERMIT_TEST_ROOT: root,
      TRACEPERMIT_TEST_NODE: process.execPath,
      TRACEPERMIT_OAUTH_MCP_PORT: "8080",
      TRACEPERMIT_OAUTH_API_PORT: "8090",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  launcher.stdout.on("data", (data) => {
    stdout += data;
  });
  launcher.stderr.on("data", (data) => {
    stderr += data;
  });
  const exited = new Promise((resolve, reject) => {
    launcher.on("error", reject);
    launcher.on("close", (code, signal) => resolve({ code, signal }));
  });
  return {
    root,
    launcher,
    exited,
    get stdout() {
      return stdout;
    },
    get stderr() {
      return stderr;
    },
    async ready() {
      await waitUntil(() => stdout.includes(profile.ready));
      await waitUntil(() =>
        ["backend", "console"].every((name) =>
          existsSync(join(root, `${name}.pid`)),
        ),
      );
    },
    async assertCleaned() {
      for (const name of ["backend", "console"]) {
        const pid = Number(readFileSync(join(root, `${name}.pid`), "utf8"));
        await waitUntil(() => !alive(pid));
      }
      if (profile.key) {
        assert.equal(
          existsSync(join(root, ".cache/demo/endpoint-key.pk8")),
          false,
        );
        assert.equal(existsSync(join(root, ".cache/demo/session.json")), false);
      }
    },
    async dispose() {
      if (launcher.exitCode === null && launcher.signalCode === null) {
        process.kill(-launcher.pid, "SIGKILL");
      }
      await exited;
      rmSync(root, { recursive: true, force: true });
    },
  };
}

for (const profile of profiles) {
  for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"]) {
    test(
      `${profile.script}: ${signal} stops successfully and cleans children`,
      { timeout: 15000 },
      async () => {
        const run = fixture(profile);
        try {
          await run.ready();
          run.launcher.kill(signal);
          assert.deepEqual(await run.exited, { code: 0, signal: null });
          assert.equal((run.stdout.match(/stopped[.;]/g) ?? []).length, 1);
          assert.doesNotMatch(run.stderr, /unexpectedly/);
          await run.assertCleaned();
        } finally {
          await run.dispose();
        }
      },
    );
  }

  test(
    `${profile.script}: actual child exit remains a failure`,
    { timeout: 15000 },
    async () => {
      const run = fixture(profile);
      try {
        await run.ready();
        const backendPid = Number(
          readFileSync(join(run.root, "backend.pid"), "utf8"),
        );
        process.kill(backendPid, "SIGTERM");
        assert.deepEqual(await run.exited, { code: 1, signal: null });
        assert.match(run.stderr, /child process stopped unexpectedly/);
        await run.assertCleaned();
      } finally {
        await run.dispose();
      }
    },
  );

  test(
    `${profile.script}: startup validation retains its error status`,
    { timeout: 15000 },
    async () => {
      const run = fixture(profile, true);
      try {
        assert.deepEqual(await run.exited, { code: 2, signal: null });
        assert.match(run.stderr, /TCP port 5173 is already in use/);
        assert.equal(existsSync(join(run.root, "backend.pid")), false);
        assert.equal(
          existsSync(join(run.root, ".cache/demo/endpoint-key.pk8")),
          false,
        );
      } finally {
        await run.dispose();
      }
    },
  );
}
