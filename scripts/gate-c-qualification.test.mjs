import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  chmodSync,
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
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const source = dirname(fileURLToPath(import.meta.url));
const scriptPath = join(source, "run-gate-c-qualification.sh");

test("Gate C runner --help outputs usage and expected scenarios with status 0", () => {
  const result = spawnSync("/bin/bash", [scriptPath, "--help"], {
    encoding: "utf8",
    timeout: 5000,
  });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /Usage: pnpm demo:gate-c/);
  assert.match(result.stdout, /Scenario 4:/);
  assert.match(result.stdout, /Scenario 9:/);
  assert.match(result.stdout, /Scenario 11:/);
  assert.match(result.stdout, /Scenario 10:/);
  assert.match(result.stdout, /Scenario 5:/);
  assert.match(result.stdout, /Scenario 7:/);
});

test("Gate C runner rejects unexpected arguments with status 2", () => {
  const result = spawnSync("/bin/bash", [scriptPath, "--extra-arg"], {
    encoding: "utf8",
    timeout: 5000,
  });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /unexpected arguments/);
});

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), "tracepermit-gate-c-test-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const scripts = join(root, "scripts");
  mkdirSync(scripts);
  copyFileSync(scriptPath, join(scripts, "run-gate-c-qualification.sh"));
  chmodSync(join(scripts, "run-gate-c-qualification.sh"), 0o755);

  writeFileSync(
    join(scripts, "configure-java-environment.sh"),
    'export JAVA_HOME="${TRACEPERMIT_TEST_JDK:-}"\n',
  );

  const scenarioCalls = join(root, "scenarios.log");
  const scenarios = [
    "run-signed-scheduled-semantic-scenario.sh",
    "run-synthetic-pii-exfil-scenario.sh",
    "run-sub-agent-delegation-scenario.sh",
    "run-tool-result-prompt-injection-scenario.sh",
    "run-semantic-qualification.sh",
    "run-signed-scheduled-stop-scenario.sh",
  ];

  for (const name of scenarios) {
    writeFileSync(
      join(scripts, name),
      `#!/bin/bash
       printf '%s %s\\n' "${name}" "$*" >> "${scenarioCalls}"
       if [ -n "\${TRACEPERMIT_FAIL_SCENARIO:-}" ] && [ "\${TRACEPERMIT_FAIL_SCENARIO}" = "${name}" ]; then
         echo "Simulated scenario failure: ${name}" >&2
         exit 42
       fi
       echo "[PASS] ${name} simulated pass"
       if [ -n "\${AWS_ACCESS_KEY_ID:-}" ] || [ -n "\${JIRA_TOKEN:-}" ]; then
         echo "LEAKED_CREDENTIALS" >&2
         exit 99
       fi
       exit 0\n`,
      { mode: 0o755 },
    );
  }

  const run = (envOverrides = {}) => {
    return spawnSync(
      "/bin/bash",
      [join(scripts, "run-gate-c-qualification.sh")],
      {
        cwd: root,
        env: {
          ...process.env,
          TRACEPERMIT_TEST_JDK: "/opt/fake-jdk21",
          AWS_ACCESS_KEY_ID: "ambient-leak-attempt",
          JIRA_TOKEN: "ambient-leak-attempt",
          ...envOverrides,
        },
        encoding: "utf8",
        timeout: 10000,
      },
    );
  };

  return { root, scripts, scenarioCalls, scenarios, run };
}

test("Gate C runner requires JAVA_HOME when not configured", (t) => {
  const f = fixture(t);
  const result = f.run({ TRACEPERMIT_TEST_JDK: "" });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /JAVA_HOME_REQUIRED/);
  assert.equal(existsSync(f.scenarioCalls), false);
});

test("Gate C runner executes all 6 scenarios in sequence with un-set ambient credentials", (t) => {
  const f = fixture(t);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.match(
    result.stdout,
    /\[PASS\] Gate C deterministic presenter qualification complete/,
  );

  const calls = readFileSync(f.scenarioCalls, "utf8").trim().split("\n");
  assert.equal(calls.length, 6);
  assert.equal(calls[0], "run-signed-scheduled-semantic-scenario.sh --auto");
  assert.equal(calls[1], "run-synthetic-pii-exfil-scenario.sh --auto");
  assert.equal(calls[2], "run-sub-agent-delegation-scenario.sh --auto");
  assert.equal(calls[3], "run-tool-result-prompt-injection-scenario.sh --auto");
  assert.equal(calls[4], "run-semantic-qualification.sh ");
  assert.equal(calls[5], "run-signed-scheduled-stop-scenario.sh --auto");
  assert.doesNotMatch(result.stdout + result.stderr, /LEAKED_CREDENTIALS/);
});

test("Gate C runner propagates scenario failure immediately without continuing", (t) => {
  const f = fixture(t);
  const result = f.run({
    TRACEPERMIT_FAIL_SCENARIO: "run-synthetic-pii-exfil-scenario.sh",
  });
  assert.equal(result.status, 42);
  assert.match(
    result.stderr,
    /Simulated scenario failure: run-synthetic-pii-exfil-scenario.sh/,
  );

  const calls = readFileSync(f.scenarioCalls, "utf8").trim().split("\n");
  // Only the first scenario and the failed second scenario should have run
  assert.equal(calls.length, 2);
  assert.equal(calls[0], "run-signed-scheduled-semantic-scenario.sh --auto");
  assert.equal(calls[1], "run-synthetic-pii-exfil-scenario.sh --auto");
});
