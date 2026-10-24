import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
  copyFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { command, inspect, root } from "./preflight.mjs";

function execute(cmd, args, env) {
  return new Promise((done) => {
    const child = spawn(cmd, args, { env });
    let output = "";
    child.stdout.on("data", (data) => (output += data));
    child.stderr.on("data", (data) => (output += data));
    child.on("close", (code) => done({ code, output }));
  });
}

function fixture(t, options = {}) {
  const dir = mkdtempSync(join(tmpdir(), "tracepermit-cloud-test-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const bin = join(dir, "bin");
  const jdk = join(dir, "jdk");
  mkdirSync(bin);
  mkdirSync(join(jdk, "bin"), { recursive: true });
  const script = (path, body) =>
    writeFileSync(path, `#!/bin/bash\n${body}\n`, { mode: 0o755 });
  script(join(bin, "node"), "echo v24.19.0");
  script(join(bin, "pnpm"), `echo ${options.pnpm ?? "11.25.0"}`);
  script(
    join(jdk, "bin/java"),
    `echo 'openjdk version "${options.java ?? 21}.0.12"' >&2`,
  );
  if (!options.noCompiler)
    script(join(jdk, "bin/javac"), `echo 'javac ${options.java ?? 21}.0.12'`);
  script(
    join(bin, "docker"),
    options.noDocker
      ? "exit 127"
      : `case "$1" in
--version) echo 'Docker version 28.0.0, build synthetic' ;;
version) ${options.daemonFailure ? "echo SENTINEL_PRIVATE_DIAGNOSTIC >&2; exit 1" : "echo 28.0.0"} ;;
image) echo 'sha256:${"a".repeat(64)}' ;;
*) echo UNEXPECTED_MUTATION >&2; exit 99 ;;
esac`,
  );
  const env = {
    ...process.env,
    PATH: `${bin}:/usr/bin:/bin`,
    TRACEPERMIT_JAVA_HOME: jdk,
    TRACEPERMIT_CACHE_DIR: join(dir, "untouched-cache"),
    TRACEPERMIT_GRADLE_USER_HOME: join(dir, "untouched-gradle"),
  };
  return { dir, bin, env, script };
}

async function preflight(env) {
  const result = await execute(
    process.execPath,
    [join(root, "scripts/cloud/preflight.mjs")],
    env,
  );
  return { ...result, report: JSON.parse(result.output) };
}

test("two positive controls use the existing Java selector without creating caches", async (t) => {
  const f = fixture(t);
  const first = await preflight(f.env);
  const second = await preflight(f.env);
  assert.equal(first.code, 0);
  assert.equal(second.code, 0);
  assert.deepEqual(first.report.checks, second.report.checks);
  assert.equal(first.report.ready, true);
  assert.equal(existsSync(f.env.TRACEPERMIT_CACHE_DIR), false);
  assert.equal(existsSync(f.env.TRACEPERMIT_GRADLE_USER_HOME), false);
});

for (const [name, options, id, code] of [
  ["Java17", { java: 17 }, "JAVA", "JDK21_REQUIRED"],
  ["no compiler", { noCompiler: true }, "JAVA", "JDK21_REQUIRED"],
  ["wrong pnpm", { pnpm: "10.0.0" }, "PNPM", "VERSION_REQUIRED"],
  [
    "unresponsive daemon",
    { daemonFailure: true },
    "DOCKER_DAEMON",
    "UNRESPONSIVE",
  ],
]) {
  test(`fail closed: ${name}, with raw diagnostics redacted`, async (t) => {
    const f = fixture(t, options);
    const result = await preflight(f.env);
    assert.equal(result.code, 1);
    assert.equal(result.report.ready, false);
    assert.equal(
      result.report.checks.find((check) => check.id === id).code,
      code,
    );
    assert.equal(result.output.includes("SENTINEL_PRIVATE_DIAGNOSTIC"), false);
  });
}

test("absent Docker client is distinct from a failed daemon and triggers no daemon probe", async () => {
  const calls = [];
  const result = await inspect({
    wrapper: () => true,
    run: async (cmd, args) => {
      calls.push([cmd, args]);
      if (cmd === "docker") return { state: "MISSING" };
      return {
        state: "OK",
        output:
          cmd === "node"
            ? "v24.19.0"
            : cmd === "pnpm"
              ? "11.25.0"
              : 'javac 21.0.12\nopenjdk version "21.0.12"',
      };
    },
  });
  assert.equal(result.ready, false);
  assert.equal(
    result.checks.find((check) => check.id === "DOCKER_CLIENT").code,
    "MISSING",
  );
  assert.equal(
    result.checks.find((check) => check.id === "DOCKER_DAEMON").code,
    "CLIENT_REQUIRED",
  );
  assert.deepEqual(
    calls.filter(([cmd]) => cmd === "docker"),
    [["docker", ["--version"]]],
  );
});

test("a real bounded child timeout becomes a fixed diagnostic", async () => {
  const result = await inspect({
    wrapper: () => true,
    run: async (cmd, args) => {
      if (cmd === "docker" && args[0] === "version") {
        return command(
          process.execPath,
          [
            "-e",
            "console.error('SENTINEL_PRIVATE_DIAGNOSTIC'); setInterval(() => {}, 1000)",
          ],
          100,
        );
      }
      return {
        state: "OK",
        output:
          cmd === "node"
            ? "v24.19.0"
            : cmd === "pnpm"
              ? "11.25.0"
              : cmd === "bash"
                ? 'javac 21.0.12\nopenjdk version "21.0.12"'
                : "Docker version 28.0.0, build synthetic",
      };
    },
  });
  assert.equal(result.ready, false);
  assert.equal(
    result.checks.find((check) => check.id === "DOCKER_DAEMON").code,
    "TIMEOUT",
  );
  assert.equal(
    JSON.stringify(result).includes("SENTINEL_PRIVATE_DIAGNOSTIC"),
    false,
  );
});

test("missing image and invalid wrapper remain requirements; only read-only Docker verbs execute", async () => {
  const calls = [];
  const result = await inspect({
    wrapper: () => false,
    run: async (cmd, args) => {
      calls.push([cmd, args]);
      const output =
        cmd === "node"
          ? "v24.19.0"
          : cmd === "pnpm"
            ? "11.25.0"
            : cmd === "bash"
              ? 'javac 21.0.12\nopenjdk version "21.0.12"'
              : args[0] === "--version"
                ? "Docker version 28.0.0, build synthetic"
                : "28.0.0";
      return { state: args[0] === "image" ? "FAILED" : "OK", output };
    },
  });
  assert.equal(result.ready, false);
  assert.equal(
    result.checks.find((check) => check.id === "GRADLE_WRAPPER").code,
    "INVALID",
  );
  assert.equal(
    result.checks.filter((check) => check.code === "IMAGE_REQUIRED").length,
    3,
  );
  assert.deepEqual(
    calls
      .filter(([cmd]) => cmd === "docker")
      .map(([, args]) => args.slice(0, 2)),
    [
      ["--version"],
      ["version", "--format"],
      ["image", "inspect"],
      ["image", "inspect"],
      ["image", "inspect"],
    ],
  );
});

test("explicit setup is repeatable, retains the selected JDK, isolates stores and redacts failures", async (t) => {
  const f = fixture(t);
  const repo = join(f.dir, "repo");
  mkdirSync(join(repo, "scripts/cloud"), { recursive: true });
  mkdirSync(join(repo, "java"));
  for (const path of [
    "scripts/cloud/setup.sh",
    "scripts/configure-java-environment.sh",
  ])
    copyFileSync(join(root, path), join(repo, path));
  f.script(
    join(repo, "scripts/test-java-environment.sh"),
    '[[ "$JAVA_HOME" == "$TRACEPERMIT_JAVA_HOME" ]] || source "$(dirname "$0")/configure-java-environment.sh"',
  );
  f.script(
    join(repo, "java/gradlew"),
    '[[ "$JAVA_HOME" == "$TRACEPERMIT_JAVA_HOME" ]]',
  );
  f.script(join(f.bin, "node"), "exit 0");
  const log = join(f.dir, "install-log");
  f.script(
    join(f.bin, "pnpm"),
    `if [[ "$1" == --version ]]; then echo 11.25.0; else printf '%s\\n' "$*" >> '${log}'; fi`,
  );
  const args = [join(repo, "scripts/cloud/setup.sh")];
  assert.equal((await execute("bash", args, f.env)).code, 2);
  for (let i = 0; i < 2; i++) {
    const result = await execute("bash", [...args, "--install"], f.env);
    assert.equal(result.code, 0, result.output);
    assert.equal(
      result.output.trim(),
      "SETUP_DEPENDENCIES_READY_DOCKER_GATE_SEPARATE",
    );
  }
  const installs = readFileSync(log, "utf8").trim().split("\n");
  assert.equal(installs.length, 2);
  assert.equal(installs[0], installs[1]);
  assert.ok(
    installs[0].includes(
      `--frozen-lockfile --store-dir ${f.env.TRACEPERMIT_CACHE_DIR}/pnpm-store`,
    ),
  );
  f.script(
    join(f.bin, "pnpm"),
    'if [[ "$1" == --version ]]; then echo 11.25.0; else echo SENTINEL_PRIVATE_DIAGNOSTIC >&2; exit 1; fi',
  );
  const failed = await execute("bash", [...args, "--install"], f.env);
  assert.equal(failed.code, 1);
  assert.equal(failed.output.trim(), "SETUP_FROZEN_INSTALL_FAILED_OR_TIMEOUT");
});
