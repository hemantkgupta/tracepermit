import assert from "node:assert/strict";
import { execSync, spawn, spawnSync } from "node:child_process";
import crypto from "node:crypto";
import net, { createServer } from "node:net";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative } from "node:path";
import { after, test } from "node:test";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

if (process.platform === "darwin" && !process.env._TP_ENDPOINT_UNDER_BASH) {
  const nodeReal = realpathSync(process.execPath);
  const args = [...process.execArgv, ...process.argv.slice(1)];
  const child = spawnSync(
    "/bin/bash",
    [
      "-c",
      `"${nodeReal}" ${args.map((a) => JSON.stringify(a)).join(" ")}; exit $?`,
    ],
    {
      stdio: "inherit",
      env: { ...process.env, _TP_ENDPOINT_UNDER_BASH: "1" },
    },
  );
  process.exit(child.status ?? 1);
}

const repoRoot = join(__dirname, "..");
const distArchive = join(
  repoRoot,
  "java/endpoint-connector/build/distributions/tracepermit-endpoint-connector-0.1.0.tar.gz",
);
const distSha256File = `${distArchive}.sha256`;
let distributionBuilt = false;

function ensureDistributionBuilt() {
  if (!distributionBuilt) {
    const buildResult = spawnSync(
      join(repoRoot, "scripts/build-endpoint-connector-distribution.sh"),
      {
        cwd: repoRoot,
        encoding: "utf8",
        timeout: 180_000,
      },
    );
    if (buildResult.status !== 0) {
      throw new Error(
        `Failed to build endpoint connector distribution:\n${buildResult.stdout}\n${buildResult.stderr}`,
      );
    }
    distributionBuilt = true;
  }
}

function command(cmd, args, cwd) {
  const result = spawnSync(cmd, args, {
    cwd,
    encoding: "utf8",
    timeout: 180_000,
  });
  assert.equal(
    result.status,
    0,
    `${cmd} failed: ${result.stdout}\n${result.stderr}`,
  );
  return result.stdout.trim();
}

function sha256OfFile(filePath) {
  const content = readFileSync(filePath);
  return crypto.createHash("sha256").update(content).digest("hex");
}

export async function gracefulTerminate(
  proc,
  name = "child",
  timeoutMs = 2000,
) {
  if (!proc) return;
  if (proc.exitCode !== null && proc.exitCode !== undefined) return;
  if (proc.signalCode !== null && proc.signalCode !== undefined) return;

  try {
    proc.stdin?.end();
  } catch {}
  try {
    proc.kill("SIGTERM");
  } catch {}

  const exitedOnSigterm = await new Promise((resolve) => {
    if (proc.exitCode !== null || proc.signalCode !== null) {
      resolve(true);
      return;
    }
    const onExit = () => {
      cleanup();
      resolve(true);
    };
    const timer = setTimeout(() => {
      cleanup();
      resolve(false);
    }, timeoutMs);
    function cleanup() {
      clearTimeout(timer);
      proc.removeListener("exit", onExit);
    }
    proc.once("exit", onExit);
  });

  if (!exitedOnSigterm && proc.exitCode === null && proc.signalCode === null) {
    try {
      proc.kill("SIGKILL");
    } catch {}
    await new Promise((resolve) => {
      if (proc.exitCode !== null || proc.signalCode !== null) {
        resolve(true);
        return;
      }
      const onExit = () => {
        cleanup();
        resolve(true);
      };
      const timer = setTimeout(() => {
        cleanup();
        resolve(false);
      }, timeoutMs);
      function cleanup() {
        clearTimeout(timer);
        proc.removeListener("exit", onExit);
      }
      proc.once("exit", onExit);
    });
  }
}

export async function checkPortReleased(port, timeoutMs = 2000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const free = await new Promise((resolve) => {
      const s = net.createServer();
      s.once("error", () => resolve(false));
      s.once("listening", () => {
        s.close(() => resolve(true));
      });
      s.listen(port, "127.0.0.1");
    });
    if (free) return true;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  return false;
}

export function runChildAsync(cmd, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, options);
    let stdout = "";
    let stderr = "";
    const timeoutMs = options.timeout || 15_000;
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      reject(
        new Error(
          `Timeout (${timeoutMs}ms) executing: ${cmd} ${args.join(" ")}`,
        ),
      );
    }, timeoutMs);
    if (child.stdout) {
      child.stdout.on("data", (d) => {
        stdout += d;
      });
    }
    if (child.stderr) {
      child.stderr.on("data", (d) => {
        stderr += d;
      });
    }
    child.on("error", (err) => {
      clearTimeout(timer);
      reject(err);
    });
    child.on("close", (code, signal) => {
      clearTimeout(timer);
      resolve({ status: code, signal, stdout, stderr });
    });
  });
}

export function waitForProcessPattern(
  child,
  pattern,
  timeoutMs = 15_000,
  desc = "pattern",
) {
  return new Promise((resolve, reject) => {
    if (!child) {
      return reject(
        new Error(
          `Cannot wait for ${desc}: child process is null or undefined`,
        ),
      );
    }
    if (child.exitCode !== null || child.signalCode !== null) {
      return reject(
        new Error(
          `Child process already exited with code ${child.exitCode} (signal ${child.signalCode}) before waiting for ${desc}`,
        ),
      );
    }

    let buffer = "";
    let timer = null;

    function cleanup() {
      if (timer) clearTimeout(timer);
      child.stdout?.removeListener("data", onData);
      child.stderr?.removeListener("data", onData);
      child.removeListener("error", onError);
      child.removeListener("exit", onExit);
    }

    function onData(chunk) {
      buffer += chunk.toString("utf8");
      const match = pattern.exec(buffer);
      if (match) {
        cleanup();
        resolve(match);
      }
    }

    function onError(err) {
      cleanup();
      reject(
        new Error(
          `Child process emitted error while waiting for ${desc}: ${err.message}`,
        ),
      );
    }

    function onExit(code, signal) {
      cleanup();
      reject(
        new Error(
          `Child process exited early with code ${code} (signal ${signal}) before matching ${desc}. Output was: ${buffer}`,
        ),
      );
    }

    timer = setTimeout(() => {
      cleanup();
      reject(
        new Error(
          `Timeout (${timeoutMs}ms) waiting for ${desc} on child process [pid ${child.pid}]. Accumulated output: ${buffer}`,
        ),
      );
    }, timeoutMs);

    child.stdout?.on("data", onData);
    child.stderr?.on("data", onData);
    child.once("error", onError);
    child.once("exit", onExit);
  });
}

function findJavaBinDir() {
  if (process.env.JAVA_HOME) {
    const candidate = join(process.env.JAVA_HOME, "bin");
    if (existsSync(join(candidate, "java"))) return candidate;
  }
  const whichJava = execSync("which java", { encoding: "utf8" }).trim();
  return dirname(whichJava);
}

let cachedSanitizedPath = null;
let sanitizedBinDirectory;
after(() => {
  if (sanitizedBinDirectory) {
    rmSync(sanitizedBinDirectory, { recursive: true, force: true });
  }
});

function createSanitizedPath() {
  if (cachedSanitizedPath) return cachedSanitizedPath;
  const binDir = mkdtempSync(join(tmpdir(), "tp-endpoint-sanitized-bin-"));
  sanitizedBinDirectory = binDir;
  const javaBin = findJavaBinDir();
  const requiredTools = [
    "bash",
    "sh",
    "env",
    "dirname",
    "sed",
    "head",
    "which",
    "cat",
    "uname",
    "tar",
    "grep",
    "ps",
  ];
  for (const tool of requiredTools) {
    try {
      const toolLocation = execSync(`which ${tool}`, {
        encoding: "utf8",
      }).trim();
      symlinkSync(toolLocation, join(binDir, tool));
    } catch {
      // tool not found on host, continue
    }
  }
  cachedSanitizedPath = [binDir, javaBin].join(":");
  return cachedSanitizedPath;
}

let cachedTestClasspath = null;
function getEndpointTestClasspath() {
  if (cachedTestClasspath) return cachedTestClasspath;
  const result = spawnSync(
    join(repoRoot, "java/gradlew"),
    [
      "-p",
      join(repoRoot, "java"),
      "-q",
      ":endpoint-connector:printTestClasspath",
    ],
    {
      cwd: repoRoot,
      encoding: "utf8",
      timeout: 60_000,
    },
  );
  if (result.status !== 0) {
    throw new Error(`Failed to get test classpath: ${result.stderr}`);
  }
  cachedTestClasspath = result.stdout.trim();
  return cachedTestClasspath;
}

test("packaging refreshes commit-only provenance and rejects dirty or unavailable source metadata", (t) => {
  const fixture = mkdtempSync(
    join(tmpdir(), "tp endpoint provenance fixture "),
  );
  t.after(() => rmSync(fixture, { recursive: true, force: true }));
  const modules = ["contracts", "identity", "endpoint-connector"];
  for (const module of modules) {
    mkdirSync(join(fixture, "java", module), { recursive: true });
  }
  writeFileSync(join(fixture, ".gitignore"), "**/build/\n**/.gradle/\n");
  writeFileSync(
    join(fixture, "java/settings.gradle.kts"),
    modules.map((m) => `include(":${m}")`).join("\n"),
  );
  writeFileSync(
    join(fixture, "java/build.gradle.kts"),
    'allprojects { version = "0.1.0-SNAPSHOT" }\nsubprojects { apply(plugin = "java-library") }\n',
  );

  const buildFile = "java/endpoint-connector/build.gradle.kts";
  copyFileSync(join(repoRoot, buildFile), join(fixture, buildFile));

  for (const file of [
    "java/endpoint-connector/src/main/scripts/tracepermit-endpoint-connector",
    "docs/runbooks/endpoint-connector-distribution.md",
    "LICENSE",
    "NOTICE",
  ]) {
    mkdirSync(dirname(join(fixture, file)), { recursive: true });
    copyFileSync(join(repoRoot, file), join(fixture, file));
  }

  const git = (...args) => command("git", args, fixture);
  git("init", "--quiet");
  git("config", "user.name", "Synthetic qualification");
  git("config", "user.email", "qualification@example.invalid");
  git("config", "commit.gpgsign", "false");
  git("add", ".");
  git("commit", "--quiet", "-m", "Synthetic fixture A");

  const gradleArgs = [
    "-p",
    join(fixture, "java"),
    "--no-daemon",
    "--offline",
    "--configuration-cache",
    "--build-cache",
    ":endpoint-connector:packageEndpointConnectorDistribution",
  ];
  const build = (...extra) =>
    spawnSync(join(repoRoot, "java/gradlew"), [...gradleArgs, ...extra], {
      cwd: fixture,
      encoding: "utf8",
      timeout: 180_000,
    });
  const buildOk = (...extra) => {
    const result = build(...extra);
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    return result.stdout;
  };
  const archive = join(
    fixture,
    "java/endpoint-connector/build/distributions/tracepermit-endpoint-connector-0.1.0.tar.gz",
  );
  const manifest = () =>
    JSON.parse(
      command(
        "tar",
        ["-xOf", archive, "tracepermit-endpoint-connector-0.1.0/manifest.json"],
        fixture,
      ),
    );

  buildOk();
  const commitA = git("rev-parse", "--verify", "HEAD");
  assert.equal(manifest().sourceCommit, commitA);

  const originalNotice = readFileSync(join(fixture, "NOTICE"), "utf8");
  const assertDirtyBuildRejected = () => {
    const committedHash = sha256OfFile(archive);
    const dirty = build();
    assert.notEqual(
      dirty.status,
      0,
      "Packaging must fail closed when the source tree is dirty",
    );
    assert.match(
      dirty.stderr + dirty.stdout,
      /requires a clean Git source tree/,
    );
    assert.equal(sha256OfFile(archive), committedHash);
  };

  writeFileSync(join(fixture, "NOTICE"), `${originalNotice}\nmodified\n`);
  assertDirtyBuildRejected();
  writeFileSync(join(fixture, "NOTICE"), originalNotice);

  const untracked = join(fixture, "untracked-source.txt");
  writeFileSync(untracked, "uncommitted input\n");
  assertDirtyBuildRejected();
  rmSync(untracked);

  git("commit", "--allow-empty", "--quiet", "-m", "Synthetic fixture B");
  const commitB = git("rev-parse", "--verify", "HEAD");
  assert.notEqual(commitA, commitB);
  buildOk();
  assert.equal(manifest().sourceCommit, commitB);

  renameSync(join(fixture, ".git"), join(fixture, ".git-unavailable"));
  assert.notEqual(
    build().status,
    0,
    "Missing Git metadata must fail; never label an archive unknown",
  );
});

test("distribution archive exists and matches sha256 checksum file", () => {
  ensureDistributionBuilt();
  assert.ok(existsSync(distArchive), "Distribution archive must exist");
  assert.ok(existsSync(distSha256File), "Checksum file must exist");
  const expectedHash = readFileSync(distSha256File, "utf8")
    .trim()
    .split(/\s+/)[0];
  const actualHash = sha256OfFile(distArchive);
  assert.equal(
    actualHash,
    expectedHash,
    "Archive sha256 must match checksum file",
  );
});

test("archive layout, strict allowlist, and permissions in path with spaces", (t) => {
  ensureDistributionBuilt();
  const extractRoot = mkdtempSync(join(tmpdir(), "tp endpoint space path "));
  t.after(() => rmSync(extractRoot, { recursive: true, force: true }));

  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-endpoint-connector-0.1.0");
  assert.ok(existsSync(distDir), "Root distribution directory must exist");

  const expectedAllowlist = new Set([
    "bin/tracepermit-endpoint-connector",
    "docs/setup-runbook.md",
    "docs/LICENSE",
    "docs/NOTICE",
    "manifest.json",
    "SHA256SUMS",
  ]);

  function walk(dir, relPrefix = "") {
    const entries = readdirSync(dir, { withFileTypes: true });
    for (const entry of entries) {
      const rel = relPrefix ? `${relPrefix}/${entry.name}` : entry.name;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full, rel);
      } else if (entry.isFile()) {
        if (rel.startsWith("lib/") && rel.endsWith(".jar")) {
          // Allowed JAR
        } else {
          assert.ok(
            expectedAllowlist.has(rel),
            `File ${rel} is not in the allowlist!`,
          );
        }
      }
    }
  }
  walk(distDir);

  // Check launcher executable bit (0755)
  const launcher = join(distDir, "bin/tracepermit-endpoint-connector");
  const launcherStat = statSync(launcher);
  assert.equal(
    launcherStat.mode & 0o777,
    0o755,
    "Launcher must be executable (0755)",
  );

  // Check file permissions (0644)
  for (const rel of [
    "docs/setup-runbook.md",
    "docs/LICENSE",
    "docs/NOTICE",
    "manifest.json",
    "SHA256SUMS",
  ]) {
    const stat = statSync(join(distDir, rel));
    assert.equal(stat.mode & 0o777, 0o644, `${rel} must have 0644 permissions`);
  }

  // Verify SHA256SUMS
  const sumsFile = readFileSync(join(distDir, "SHA256SUMS"), "utf8");
  const sumLines = sumsFile
    .split("\n")
    .filter((l) => l.trim() && !l.startsWith("#"));
  assert.ok(sumLines.length > 5, "SHA256SUMS must contain entries");
  for (const line of sumLines) {
    const [hash, fileRel] = line.trim().split(/\s+/);
    const targetFile = join(distDir, fileRel);
    assert.ok(existsSync(targetFile), `Target file ${fileRel} must exist`);
    assert.equal(
      sha256OfFile(targetFile),
      hash,
      `Hash for ${fileRel} must match`,
    );
  }

  // Verify manifest.json
  const manifest = JSON.parse(
    readFileSync(join(distDir, "manifest.json"), "utf8"),
  );
  assert.equal(manifest.schemaVersion, "1.1");
  assert.equal(manifest.packageName, "tracepermit-endpoint-connector");
  assert.equal(manifest.packageVersion, "0.1.0");
  assert.equal(manifest.requiredJava, "21");
  assert.deepEqual(manifest.supportedPlatforms, ["macOS"]);
  assert.equal(
    manifest.entryPoint,
    "io.tracepermit.endpoint.EndpointConnectorMain",
  );
  assert.equal(manifest.launcher, "bin/tracepermit-endpoint-connector");
  assert.equal(manifest.sourceTree, "clean");
  assert.ok(Array.isArray(manifest.dependencyInventory));
  assert.ok(manifest.dependencyInventory.length > 0);
  for (const jar of manifest.dependencyInventory) {
    const jarPath = join(distDir, "lib", jar.name);
    assert.ok(existsSync(jarPath), `Dependency jar ${jar.name} must exist`);
    assert.equal(statSync(jarPath).size, jar.sizeBytes);
    assert.equal(sha256OfFile(jarPath), jar.sha256);
  }

  // Check setup-runbook.md has rewritten links
  const runbookContent = readFileSync(
    join(distDir, "docs/setup-runbook.md"),
    "utf8",
  );
  assert.ok(
    !runbookContent.includes("](../architecture/"),
    "Runbook relative architecture links must be relocated",
  );
  assert.ok(
    runbookContent.includes(
      "https://github.com/hemantkgupta/tracepermit/blob/",
    ),
    "Runbook must use absolute GitHub blob URL for relocated links",
  );
});

test("sanitized minimal PATH execution and launcher CLI flags", (t) => {
  ensureDistributionBuilt();
  const extractRoot = mkdtempSync(join(tmpdir(), "tp endpoint cli test "));
  const unrelatedCwd = mkdtempSync(join(tmpdir(), "tp endpoint cwd "));
  t.after(() => {
    rmSync(extractRoot, { recursive: true, force: true });
    rmSync(unrelatedCwd, { recursive: true, force: true });
  });

  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-endpoint-connector-0.1.0");
  const launcher = join(distDir, "bin/tracepermit-endpoint-connector");
  const sanitizedPath = createSanitizedPath();

  // 1. --help / -h
  for (const flag of ["--help", "-h"]) {
    const res = spawnSync(launcher, [flag], {
      cwd: unrelatedCwd,
      env: { PATH: sanitizedPath },
      encoding: "utf8",
    });
    assert.equal(res.status, 0);
    assert.ok(res.stdout.includes("Usage: tracepermit-endpoint-connector"));
    assert.ok(res.stdout.includes("Platform: macOS (ARM64 / x86_64) only"));
    assert.ok(res.stdout.includes("--check"));
    assert.ok(res.stdout.includes("--run"));
    assert.equal(res.stderr, "");
  }

  // 2. Unrecognized flag
  const unknownRes = spawnSync(launcher, ["--unknown-flag"], {
    cwd: unrelatedCwd,
    env: { PATH: sanitizedPath },
    encoding: "utf8",
  });
  assert.equal(unknownRes.status, 2);
  assert.ok(
    unknownRes.stderr.includes(
      "TracePermit endpoint connector: CONFIGURATION_INVALID",
    ),
  );

  // 3. Extra arguments
  const extraRes = spawnSync(launcher, ["--check", "extra-arg"], {
    cwd: unrelatedCwd,
    env: { PATH: sanitizedPath },
    encoding: "utf8",
  });
  assert.equal(extraRes.status, 2);
  assert.ok(
    extraRes.stderr.includes(
      "TracePermit endpoint connector: CONFIGURATION_INVALID",
    ),
  );
});

test("ambient JVM diagnostic options are unset and secret sentinels are not echoed", (t) => {
  ensureDistributionBuilt();
  const extractRoot = mkdtempSync(join(tmpdir(), "tp endpoint sentinel test "));
  const unrelatedCwd = mkdtempSync(join(tmpdir(), "tp endpoint sentinel cwd "));
  const privateDir = realpathSync(
    mkdtempSync(join(tmpdir(), "tp endpoint sentinel priv ")),
  );
  chmodSync(privateDir, 0o700);
  t.after(() => {
    rmSync(extractRoot, { recursive: true, force: true });
    rmSync(unrelatedCwd, { recursive: true, force: true });
    rmSync(privateDir, { recursive: true, force: true });
  });

  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-endpoint-connector-0.1.0");
  const launcher = join(distDir, "bin/tracepermit-endpoint-connector");
  const sanitizedPath = createSanitizedPath();

  const secretSentinel1 = "SENTINEL_SECRET_TOKEN_ALPHA_12345";
  const secretSentinel2 = "SENTINEL_SECRET_TOKEN_BETA_67890";
  const secretSentinel3 = "SENTINEL_SECRET_TOKEN_GAMMA_ABCDE";

  // 1. Help flag invocation (before Java)
  const res = spawnSync(launcher, ["--help"], {
    cwd: unrelatedCwd,
    env: {
      PATH: sanitizedPath,
      JAVA_TOOL_OPTIONS: `-Dtest.secret=${secretSentinel1}`,
      _JAVA_OPTIONS: `-Dtest.secret2=${secretSentinel2}`,
      JDK_JAVA_OPTIONS: `-Dtest.secret3=${secretSentinel3}`,
    },
    encoding: "utf8",
  });

  assert.equal(res.status, 0);
  assert.ok(!res.stdout.includes(secretSentinel1));
  assert.ok(!res.stdout.includes(secretSentinel2));
  assert.ok(!res.stdout.includes(secretSentinel3));
  assert.ok(!res.stderr.includes(secretSentinel1));
  assert.ok(!res.stderr.includes(secretSentinel2));
  assert.ok(!res.stderr.includes(secretSentinel3));
  assert.ok(!res.stderr.includes("Picked up JAVA_TOOL_OPTIONS"));
  assert.ok(!res.stderr.includes("Picked up _JAVA_OPTIONS"));
  assert.ok(!res.stderr.includes("Picked up JDK_JAVA_OPTIONS"));

  // 2. Failure exit before Java
  const failRes = spawnSync(launcher, ["--unknown"], {
    cwd: unrelatedCwd,
    env: {
      PATH: sanitizedPath,
      JAVA_TOOL_OPTIONS: `-Dtest.secret=${secretSentinel1}`,
    },
    encoding: "utf8",
  });
  assert.equal(failRes.status, 2);
  assert.ok(!failRes.stderr.includes(secretSentinel1));
  assert.ok(!failRes.stderr.includes("Picked up JAVA_TOOL_OPTIONS"));

  // 3. Actual valid --check JVM invocation with all three diagnostic variables
  const { privateKey } = crypto.generateKeyPairSync("ed25519");
  const pk8Der = privateKey.export({ type: "pkcs8", format: "der" });
  const keyPath = join(privateDir, "endpoint.pk8");
  writeFileSync(keyPath, pk8Der);
  chmodSync(keyPath, 0o600);

  const validCheckEnv = {
    PATH: sanitizedPath,
    TRACEPERMIT_GATEWAY_URL: "http://127.0.0.1:8080/mcp",
    TRACEPERMIT_ENDPOINT_ISSUER: "tracepermit-endpoint:test",
    TRACEPERMIT_ENDPOINT_KEY_ID: "endpoint-key-test",
    TRACEPERMIT_ENDPOINT_DEVICE_ID: "device-test",
    TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH: keyPath,
    TRACEPERMIT_CONNECTOR_BEARER_TOKEN: "valid-bearer-token",
    JAVA_TOOL_OPTIONS: `-Dtest.secret=${secretSentinel1}`,
    _JAVA_OPTIONS: `-Dtest.secret2=${secretSentinel2}`,
    JDK_JAVA_OPTIONS: `-Dtest.secret3=${secretSentinel3}`,
  };

  if (process.platform === "darwin") {
    const jvmCheckRes = spawnSync(launcher, ["--check"], {
      cwd: unrelatedCwd,
      env: validCheckEnv,
      encoding: "utf8",
    });
    assert.equal(jvmCheckRes.status, 0, jvmCheckRes.stderr);
    assert.ok(
      jvmCheckRes.stdout.includes(
        "ENDPOINT_CONFIGURATION_VALID: offline only; no gateway, IdP, observer or client checked.",
      ),
    );
    assert.ok(!jvmCheckRes.stdout.includes(secretSentinel1));
    assert.ok(!jvmCheckRes.stdout.includes(secretSentinel2));
    assert.ok(!jvmCheckRes.stdout.includes(secretSentinel3));
    assert.ok(!jvmCheckRes.stderr.includes(secretSentinel1));
    assert.ok(!jvmCheckRes.stderr.includes(secretSentinel2));
    assert.ok(!jvmCheckRes.stderr.includes(secretSentinel3));
    assert.ok(!jvmCheckRes.stderr.includes("Picked up JAVA_TOOL_OPTIONS"));
    assert.ok(!jvmCheckRes.stderr.includes("Picked up _JAVA_OPTIONS"));
    assert.ok(!jvmCheckRes.stderr.includes("Picked up JDK_JAVA_OPTIONS"));

    // 4. Negative probe sensitivity control:
    // A private launcher copy with unset removed must leak banners/sentinels.
    // NOTE: The real launcher in distDir is NEVER modified.
    const privateLauncher = join(distDir, "bin/private-launcher-no-unset");
    const originalLauncherContent = readFileSync(launcher, "utf8");
    const brokenLauncherContent = originalLauncherContent.replace(
      /unset (-v )?JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS/g,
      "# unset removed for negative probe sensitivity verification",
    );
    assert.notEqual(
      brokenLauncherContent,
      originalLauncherContent,
      "Sanity check: unset must have been matched and replaced in private copy",
    );
    writeFileSync(privateLauncher, brokenLauncherContent, { mode: 0o755 });

    const brokenRes = spawnSync(privateLauncher, ["--check"], {
      cwd: unrelatedCwd,
      env: validCheckEnv,
      encoding: "utf8",
    });
    const leakedBanner =
      brokenRes.stderr.includes("Picked up JAVA_TOOL_OPTIONS") ||
      brokenRes.stderr.includes("Picked up _JAVA_OPTIONS") ||
      brokenRes.stderr.includes("Picked up JDK_JAVA_OPTIONS") ||
      brokenRes.stderr.includes(secretSentinel1) ||
      brokenRes.stderr.includes(secretSentinel2) ||
      brokenRes.stderr.includes(secretSentinel3);
    assert.ok(
      leakedBanner,
      "Negative probe sensitivity: removing unset from launcher must leak JVM diagnostic banners or secret sentinels to stderr",
    );
  } else {
    const nonMacCheck = spawnSync(launcher, ["--check"], {
      cwd: unrelatedCwd,
      env: validCheckEnv,
      encoding: "utf8",
    });
    assert.equal(nonMacCheck.status, 2);
    assert.ok(
      nonMacCheck.stderr.includes(
        "TracePermit endpoint connector: UNSUPPORTED_PLATFORM",
      ),
    );
    assert.ok(!nonMacCheck.stderr.includes(secretSentinel1));
  }
});

test("installed launcher rejects unsupported non-macOS platforms fail-closed with code 2", (t) => {
  ensureDistributionBuilt();
  const extractRoot = mkdtempSync(
    join(tmpdir(), "tp endpoint unsupported test "),
  );
  const unrelatedCwd = mkdtempSync(
    join(tmpdir(), "tp endpoint unsupported cwd "),
  );
  const fakeBinDir = mkdtempSync(join(tmpdir(), "tp endpoint fake uname "));
  t.after(() => {
    rmSync(extractRoot, { recursive: true, force: true });
    rmSync(unrelatedCwd, { recursive: true, force: true });
    rmSync(fakeBinDir, { recursive: true, force: true });
  });

  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-endpoint-connector-0.1.0");
  const launcher = join(distDir, "bin/tracepermit-endpoint-connector");
  const sanitizedPath = createSanitizedPath();

  // Create a fake uname script that returns Linux
  const fakeUname = join(fakeBinDir, "uname");
  writeFileSync(fakeUname, '#!/bin/sh\necho "Linux"\n', { mode: 0o755 });
  chmodSync(fakeUname, 0o755);

  const fakePath = `${fakeBinDir}:${sanitizedPath}`;

  // 1. --check with unsupported platform fails immediately with code 2 and UNSUPPORTED_PLATFORM
  const checkRes = spawnSync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: { PATH: fakePath },
    encoding: "utf8",
  });
  assert.equal(checkRes.status, 2);
  assert.equal(checkRes.stdout, "");
  assert.ok(
    checkRes.stderr.includes(
      "TracePermit endpoint connector: UNSUPPORTED_PLATFORM",
    ),
  );

  // 2. --run with unsupported platform fails immediately with code 2 and UNSUPPORTED_PLATFORM
  const runRes = spawnSync(launcher, ["--run"], {
    cwd: unrelatedCwd,
    env: { PATH: fakePath },
    encoding: "utf8",
  });
  assert.equal(runRes.status, 2);
  assert.equal(runRes.stdout, "");
  assert.ok(
    runRes.stderr.includes(
      "TracePermit endpoint connector: UNSUPPORTED_PLATFORM",
    ),
  );

  // 3. default (no args) with unsupported platform fails immediately with code 2 and UNSUPPORTED_PLATFORM
  const defaultRes = spawnSync(launcher, [], {
    cwd: unrelatedCwd,
    env: { PATH: fakePath },
    encoding: "utf8",
  });
  assert.equal(defaultRes.status, 2);
  assert.equal(defaultRes.stdout, "");
  assert.ok(
    defaultRes.stderr.includes(
      "TracePermit endpoint connector: UNSUPPORTED_PLATFORM",
    ),
  );

  // 4. --help on unsupported platform still displays usage honestly with code 0
  const helpRes = spawnSync(launcher, ["--help"], {
    cwd: unrelatedCwd,
    env: { PATH: fakePath },
    encoding: "utf8",
  });
  assert.equal(helpRes.status, 0);
  assert.ok(helpRes.stdout.includes("Platform: macOS (ARM64 / x86_64) only"));

  // 5. Preserved legacy source entry behavior: direct EndpointConnectorMain with zero args does not reject with UNSUPPORTED_PLATFORM
  const javaBin = join(findJavaBinDir(), "java");
  const classpath = join(distDir, "lib", "*");
  const legacyRes = spawnSync(
    javaBin,
    ["-cp", classpath, "io.tracepermit.endpoint.EndpointConnectorMain"],
    {
      cwd: unrelatedCwd,
      env: {
        PATH: fakePath,
        TRACEPERMIT_CONNECTOR_BEARER_TOKEN: "sentinel-invalid",
      },
      encoding: "utf8",
    },
  );
  assert.equal(legacyRes.status, 2);
  assert.ok(
    legacyRes.stderr.includes(
      "TracePermit endpoint connector: CONFIGURATION_INVALID",
    ),
  );
  assert.ok(
    !legacyRes.stderr.includes("UNSUPPORTED_PLATFORM"),
    "Legacy zero-argument source entry must not be blocked by installed platform check",
  );
});

test("offline --check verifies configuration with zero network calls and zero file creations", async (t) => {
  ensureDistributionBuilt();
  const extractRoot = mkdtempSync(join(tmpdir(), "tp endpoint offline test "));
  const unrelatedCwd = mkdtempSync(join(tmpdir(), "tp endpoint offline cwd "));
  const privateDir = realpathSync(
    mkdtempSync(join(tmpdir(), "tp endpoint private ")),
  );
  chmodSync(privateDir, 0o700);

  let probeNetworkRequests = 0;
  const mockServer = createServer((socket) => {
    probeNetworkRequests++;
    socket.destroy();
  });
  await new Promise((resolve) => mockServer.listen(0, "127.0.0.1", resolve));

  t.after(() => {
    mockServer.close();
    rmSync(extractRoot, { recursive: true, force: true });
    rmSync(unrelatedCwd, { recursive: true, force: true });
    rmSync(privateDir, { recursive: true, force: true });
  });

  const mockPort = mockServer.address().port;
  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-endpoint-connector-0.1.0");
  const launcher = join(distDir, "bin/tracepermit-endpoint-connector");
  const sanitizedPath = createSanitizedPath();

  // Generate synthetic Ed25519 PKCS#8 private key
  const { privateKey } = crypto.generateKeyPairSync("ed25519");
  const pk8Der = privateKey.export({ type: "pkcs8", format: "der" });
  const keyPath = join(privateDir, "endpoint.pk8");
  writeFileSync(keyPath, pk8Der);
  chmodSync(keyPath, 0o600);

  // Optional observation dir
  const obsDir = join(privateDir, "obs");
  mkdirSync(obsDir, { mode: 0o700 });
  chmodSync(obsDir, 0o700);

  const validEnv = {
    PATH: sanitizedPath,
    TRACEPERMIT_GATEWAY_URL: `http://127.0.0.1:${mockPort}/mcp`,
    TRACEPERMIT_ENDPOINT_ISSUER: "tracepermit-endpoint:test",
    TRACEPERMIT_ENDPOINT_KEY_ID: "endpoint-key-test",
    TRACEPERMIT_ENDPOINT_DEVICE_ID: "device-test",
    TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH: keyPath,
    TRACEPERMIT_CONNECTOR_BEARER_TOKEN: "synthetic-offline-token",
    TRACEPERMIT_ENDPOINT_OBSERVATION_DIRECTORY: obsDir,
  };

  if (process.platform !== "darwin") {
    const nonMacCheck = spawnSync(launcher, ["--check"], {
      cwd: unrelatedCwd,
      env: validEnv,
      encoding: "utf8",
    });
    assert.equal(nonMacCheck.status, 2);
    assert.ok(
      nonMacCheck.stderr.includes(
        "TracePermit endpoint connector: UNSUPPORTED_PLATFORM",
      ),
    );
    assert.equal(probeNetworkRequests, 0);
    return;
  }

  // 1. Valid offline --check (async execution preserves Node event loop for mockServer)
  const validCheck = await runChildAsync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: validEnv,
  });
  // Finite settle barrier ensuring any asynchronous network callbacks drain
  await new Promise((resolve) => setTimeout(resolve, 50));

  assert.equal(validCheck.status, 0, validCheck.stderr);
  assert.ok(
    validCheck.stdout.includes(
      "ENDPOINT_CONFIGURATION_VALID: offline only; no gateway, IdP, observer or client checked.",
    ),
  );
  assert.equal(validCheck.stderr, "");

  // PROVE: zero network requests made during check
  assert.equal(
    probeNetworkRequests,
    0,
    "Offline check must make zero network calls",
  );

  // PROVE: zero files created in observation directory
  assert.equal(
    readdirSync(obsDir).length,
    0,
    "Offline check must create zero files in observation directory",
  );

  // 2. Failure: missing required variable (async execution preserves event loop)
  const missingVar = await runChildAsync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: { ...validEnv, TRACEPERMIT_CONNECTOR_BEARER_TOKEN: "" },
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(missingVar.status, 2);
  assert.ok(
    missingVar.stderr.includes(
      "TracePermit endpoint connector: CONFIGURATION_INVALID",
    ),
  );

  // 3. Failure: non-loopback gateway URL
  const nonLoopback = await runChildAsync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: { ...validEnv, TRACEPERMIT_GATEWAY_URL: "http://example.com/mcp" },
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(nonLoopback.status, 2);
  assert.ok(
    nonLoopback.stderr.includes(
      "TracePermit endpoint connector: CONFIGURATION_INVALID",
    ),
  );

  // 4. Failure: HTTPS gateway URL
  const httpsUrl = await runChildAsync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: {
      ...validEnv,
      TRACEPERMIT_GATEWAY_URL: `https://127.0.0.1:${mockPort}/mcp`,
    },
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(httpsUrl.status, 2);
  assert.ok(
    httpsUrl.stderr.includes(
      "TracePermit endpoint connector: CONFIGURATION_INVALID",
    ),
  );

  // 5. Failure: non-/mcp path
  const nonMcpPath = await runChildAsync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: {
      ...validEnv,
      TRACEPERMIT_GATEWAY_URL: `http://127.0.0.1:${mockPort}/api`,
    },
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(nonMcpPath.status, 2);
  assert.ok(
    nonMcpPath.stderr.includes(
      "TracePermit endpoint connector: CONFIGURATION_INVALID",
    ),
  );

  // 6. Failure: symlink key
  const symlinkKey = join(privateDir, "symlink-key.pk8");
  symlinkSync(keyPath, symlinkKey);
  const symlinkCheck = await runChildAsync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: {
      ...validEnv,
      TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH: symlinkKey,
    },
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(symlinkCheck.status, 2);
  assert.ok(
    symlinkCheck.stderr.includes(
      "TracePermit endpoint connector: CONFIGURATION_INVALID",
    ),
  );

  // 7. Failure: insecure key permissions (0666)
  const insecureKey = join(privateDir, "insecure-key.pk8");
  writeFileSync(insecureKey, pk8Der);
  chmodSync(insecureKey, 0o666);
  const insecureCheck = await runChildAsync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: {
      ...validEnv,
      TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH: insecureKey,
    },
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(insecureCheck.status, 2);
  assert.ok(
    insecureCheck.stderr.includes(
      "TracePermit endpoint connector: CONFIGURATION_INVALID",
    ),
  );

  // PROVE: zero network calls across all 7 monitored test cases
  assert.equal(
    probeNetworkRequests,
    0,
    "Total network requests during check tests must be zero across all cases",
  );

  // SENSITIVITY CONTROL:
  // Prove that a deliberately broken launcher that connects when token is empty
  // (as tested in private audit) is genuinely detected and causes the zero-network regression to FAIL.
  const privateBrokenLauncher = join(
    distDir,
    "bin/private-broken-launcher-token-connect",
  );
  const launcherText = readFileSync(launcher, "utf8");
  const rogueScript = `
if [ -z "\${TRACEPERMIT_CONNECTOR_BEARER_TOKEN:-}" ]; then
  (exec 3<>/dev/tcp/127.0.0.1/${mockPort}; exec 3>&-) 2>/dev/null || true
fi
`;
  const brokenLauncherText = launcherText.replace(
    'exec "$JAVA_BIN"',
    `${rogueScript}\nexec "$JAVA_BIN"`,
  );
  writeFileSync(privateBrokenLauncher, brokenLauncherText);
  chmodSync(privateBrokenLauncher, 0o755);

  const preBrokenProbeCount = probeNetworkRequests;
  await runChildAsync(privateBrokenLauncher, ["--check"], {
    cwd: unrelatedCwd,
    env: { ...validEnv, TRACEPERMIT_CONNECTOR_BEARER_TOKEN: "" },
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
  const rogueRequests = probeNetworkRequests - preBrokenProbeCount;
  assert.ok(
    rogueRequests > 0,
    "Deliberately broken launcher with empty token MUST trigger network probe detection (> 0)",
  );

  // Prove that the actual zero-network assertion FAILS on rogueRequests:
  assert.throws(
    () =>
      assert.equal(
        rogueRequests,
        0,
        "Offline check must make zero network calls",
      ),
    /Offline check must make zero network calls/,
    "Zero-network regression check MUST fail when broken launcher makes network calls",
  );

  // Deliberate connect-on-check private control:
  // Prove that a direct deliberate network connection to mockServer is also detected
  const preProbeCount = probeNetworkRequests;
  await new Promise((resolve, reject) => {
    const probeSocket = net.connect(
      { host: "127.0.0.1", port: mockPort },
      () => {
        probeSocket.destroy();
        resolve();
      },
    );
    probeSocket.on("error", reject);
    setTimeout(() => {
      probeSocket.destroy();
      reject(new Error("Timeout in sensitivity network probe"));
    }, 2000);
  });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.ok(
    probeNetworkRequests > preProbeCount,
    "Deliberate network connection to mockServer must be detected by probe (> 0)",
  );
});

test("live stdio client execution against ephemeral local Java simulator gateway", async (t) => {
  if (process.platform !== "darwin") {
    t.skip(
      "macOS-only distribution: live macOS process observation and stdio connector execution is skipped on non-macOS (Linux).",
    );
    return;
  }
  ensureDistributionBuilt();
  const extractRoot = mkdtempSync(join(tmpdir(), "tp endpoint live test "));
  const unrelatedCwd = mkdtempSync(join(tmpdir(), "tp endpoint live cwd "));
  const privateDir = realpathSync(
    mkdtempSync(join(tmpdir(), "tp endpoint live priv ")),
  );
  chmodSync(privateDir, 0o700);

  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-endpoint-connector-0.1.0");
  const launcher = join(distDir, "bin/tracepermit-endpoint-connector");
  const sanitizedPath = createSanitizedPath();
  const testClasspath = getEndpointTestClasspath();

  // Generate Ed25519 keypair
  const { publicKey, privateKey } = crypto.generateKeyPairSync("ed25519");
  const pk8Der = privateKey.export({ type: "pkcs8", format: "der" });
  const spkiDer = publicKey.export({ type: "spki", format: "der" });
  const spkiBase64 = spkiDer.toString("base64");

  const keyPath = join(privateDir, "endpoint.pk8");
  writeFileSync(keyPath, pk8Der);
  chmodSync(keyPath, 0o600);

  // Start EphemeralSimulatorGatewayMain
  const javaBin = join(findJavaBinDir(), "java");
  let gatewayProcess = null;
  let connector = null;
  let expiredConnector = null;

  t.after(async () => {
    await gracefulTerminate(connector, "connector");
    await gracefulTerminate(expiredConnector, "expiredConnector");
    if (gatewayProcess) {
      try {
        gatewayProcess.stdin?.write("SHUTDOWN\n");
      } catch {}
      await gracefulTerminate(gatewayProcess, "gatewayProcess");
    }
    if (gatewayPort) {
      const released = await checkPortReleased(gatewayPort);
      assert.ok(released, `Gateway port ${gatewayPort} must be released`);
    }
    rmSync(extractRoot, { recursive: true, force: true });
    rmSync(unrelatedCwd, { recursive: true, force: true });
    rmSync(privateDir, { recursive: true, force: true });
  });

  // Start EphemeralSimulatorGatewayMain (mock protocol smoke server for transport wire-framing)
  gatewayProcess = spawn(
    javaBin,
    [
      "-cp",
      testClasspath,
      "io.tracepermit.endpoint.EphemeralSimulatorGatewayMain",
    ],
    {
      env: {
        ...process.env,
        PATH: sanitizedPath,
        TRACEPERMIT_ENDPOINT_ISSUER: "tracepermit-endpoint:test",
        TRACEPERMIT_ENDPOINT_KEY_ID: "endpoint-key-test",
        TRACEPERMIT_ENDPOINT_DEVICE_ID: "device-test",
        TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64: spkiBase64,
      },
      stdio: ["pipe", "pipe", "pipe"],
    },
  );

  // Wait for gateway ready
  let gatewayPort = null;
  const gatewayMatch = await waitForProcessPattern(
    gatewayProcess,
    /GATEWAY_READY port=(\d+)/,
    15_000,
    "gateway ready",
  );
  gatewayPort = parseInt(gatewayMatch[1], 10);

  assert.ok(gatewayPort > 0, `Gateway port must be positive: ${gatewayPort}`);

  // Base client environment
  const clientEnv = {
    PATH: sanitizedPath,
    TRACEPERMIT_GATEWAY_URL: `http://127.0.0.1:${gatewayPort}/mcp`,
    TRACEPERMIT_ENDPOINT_ISSUER: "tracepermit-endpoint:test",
    TRACEPERMIT_ENDPOINT_KEY_ID: "endpoint-key-test",
    TRACEPERMIT_ENDPOINT_DEVICE_ID: "device-test",
    TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH: keyPath,
    TRACEPERMIT_CONNECTOR_BEARER_TOKEN: "valid-bearer-token",
  };

  // Launch connector process
  connector = spawn(launcher, ["--run"], {
    cwd: unrelatedCwd,
    env: clientEnv,
    stdio: ["pipe", "pipe", "pipe"],
  });

  // PROTECT: process parentage check.
  // Because launcher uses `exec java ...`, connector.pid IS replaced by the Java process directly.
  assert.ok(connector.pid > 0);
  let psOutput = null;
  let osInspectionError = null;
  const psDeadline = Date.now() + 3000;
  while (Date.now() < psDeadline) {
    try {
      psOutput = execSync(`ps -o ppid=,comm= -p ${connector.pid}`, {
        encoding: "utf8",
      }).trim();
      const parts = psOutput.split(/\s+/);
      if (
        parts.length >= 2 &&
        parts.slice(1).join(" ").toLowerCase().includes("java")
      ) {
        break;
      }
    } catch (err) {
      osInspectionError = err;
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }

  if (psOutput != null) {
    // Assertions are outside of try-catch; mismatches will fail the test!
    const parts = psOutput.trim().split(/\s+/);
    assert.ok(parts.length >= 2, `ps output format invalid: "${psOutput}"`);
    const observedPpid = parseInt(parts[0], 10);
    const observedComm = parts.slice(1).join(" ").toLowerCase();
    assert.equal(
      observedPpid,
      process.pid,
      `Connector PPID must equal native spawning client PID (${process.pid}), observed: ${observedPpid}`,
    );
    assert.ok(
      observedComm.includes("java"),
      `Connector process command must be java, observed: ${observedComm}`,
    );
  } else {
    // Explicitly record skipped proof with reason rather than silently counting as passed
    t.diagnostic(
      `SKIPPED OS process parentage verification: ps command unavailable (${osInspectionError?.message})`,
    );
  }

  // Communication helper: send JSON line, read response line
  let responseBuffer = "";
  let pendingLines = [];
  let lineWaiters = [];
  let connectorFailure = null;

  connector.stdout.setEncoding("utf8");
  connector.stdout.on("data", (chunk) => {
    responseBuffer += chunk;
    const lines = responseBuffer.split("\n");
    responseBuffer = lines.pop(); // incomplete line
    for (const l of lines) {
      if (l.trim()) {
        if (lineWaiters.length > 0) {
          const waiter = lineWaiters.shift();
          waiter.resolve(l.trim());
        } else {
          pendingLines.push(l.trim());
        }
      }
    }
  });

  const onConnectorExit = (code, signal) => {
    connectorFailure = new Error(
      `Connector process exited unexpectedly with code ${code} (signal ${signal}) while waiting for response`,
    );
    while (lineWaiters.length > 0) {
      const waiter = lineWaiters.shift();
      waiter.reject(connectorFailure);
    }
  };
  const onConnectorError = (err) => {
    connectorFailure = new Error(
      `Connector process emitted error: ${err.message}`,
    );
    while (lineWaiters.length > 0) {
      const waiter = lineWaiters.shift();
      waiter.reject(connectorFailure);
    }
  };
  connector.once("exit", onConnectorExit);
  connector.once("error", onConnectorError);

  function nextResponse(timeoutMs = 10_000) {
    if (connectorFailure) {
      return Promise.reject(connectorFailure);
    }
    if (pendingLines.length > 0) {
      return Promise.resolve(pendingLines.shift());
    }
    return new Promise((resolve, reject) => {
      let waiterObj = null;
      const timer = setTimeout(() => {
        const idx = lineWaiters.indexOf(waiterObj);
        if (idx !== -1) lineWaiters.splice(idx, 1);
        reject(
          new Error(`Timeout (${timeoutMs}ms) waiting for connector response`),
        );
      }, timeoutMs);
      waiterObj = {
        resolve: (line) => {
          clearTimeout(timer);
          resolve(line);
        },
        reject: (err) => {
          clearTimeout(timer);
          reject(err);
        },
      };
      lineWaiters.push(waiterObj);
    });
  }

  // 1. Initialize
  connector.stdin.write(
    JSON.stringify({
      jsonrpc: "2.0",
      id: "init-1",
      method: "initialize",
      params: {
        protocolVersion: "2025-06-18",
        capabilities: {},
        clientInfo: { name: "test-client", version: "1.0.0" },
      },
    }) + "\n",
  );
  const initRes = JSON.parse(await nextResponse());
  assert.equal(initRes.id, "init-1");
  assert.equal(initRes.result.serverInfo.name, "TracePermit");

  // 2. Initialized notification
  connector.stdin.write(
    JSON.stringify({
      jsonrpc: "2.0",
      method: "notifications/initialized",
      params: {},
    }) + "\n",
  );

  // 3. Tools list
  connector.stdin.write(
    JSON.stringify({
      jsonrpc: "2.0",
      id: 2,
      method: "tools/list",
      params: {},
    }) + "\n",
  );
  const listRes = JSON.parse(await nextResponse());
  assert.equal(listRes.id, 2);
  const toolNames = listRes.result.tools.map((t) => t.name);
  assert.ok(toolNames.includes("aws.logs.filter_log_events"));
  assert.ok(toolNames.includes("aws.ec2.terminate_instances"));

  // 4. Tools call: read allowed (aws.logs.filter_log_events)
  connector.stdin.write(
    JSON.stringify({
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: {
        name: "aws.logs.filter_log_events",
        arguments: {
          logGroupName: "/app/staging",
          filterPattern: "WARN",
          limit: 2,
        },
      },
    }) + "\n",
  );
  const readRes = JSON.parse(await nextResponse());
  assert.equal(readRes.id, 3);
  const readPayload = JSON.parse(readRes.result.content[0].text);
  assert.equal(readPayload.evaluation.decision.outcome, "allow");
  assert.equal(readPayload.execution.status, "executed");

  // 5. Tools call: termination blocked by policy (aws.ec2.terminate_instances)
  connector.stdin.write(
    JSON.stringify({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: {
        name: "aws.ec2.terminate_instances",
        arguments: { instanceIds: ["i-simulated-1"] },
      },
    }) + "\n",
  );
  const terminateRes = JSON.parse(await nextResponse());
  assert.equal(terminateRes.id, 4);
  assert.equal(terminateRes.result.isError, true);
  const terminatePayload = JSON.parse(terminateRes.result.content[0].text);
  assert.equal(terminatePayload.evaluation.decision.outcome, "block");
  assert.equal(terminatePayload.execution.status, "denied");

  // 6. Tools call: unknown tool rejected
  connector.stdin.write(
    JSON.stringify({
      jsonrpc: "2.0",
      id: 5,
      method: "tools/call",
      params: {
        name: "aws.ec2.unknown_operation",
        arguments: {},
      },
    }) + "\n",
  );
  const unknownRes = JSON.parse(await nextResponse());
  assert.equal(unknownRes.id, 5);
  assert.equal(unknownRes.error.code, -32602);

  // 7. Expired credential rejection test
  expiredConnector = spawn(launcher, ["--run"], {
    cwd: unrelatedCwd,
    env: {
      ...clientEnv,
      TRACEPERMIT_CONNECTOR_BEARER_TOKEN: "expired-test-token",
    },
    stdio: ["pipe", "pipe", "pipe"],
  });

  let expiredResp = "";
  expiredConnector.stdout.setEncoding("utf8");
  const expiredPromise = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error("Timeout waiting for expired connector response line"));
    }, 5000);
    const onData = (chunk) => {
      expiredResp += chunk;
      if (expiredResp.includes("\n")) {
        cleanup();
        resolve(expiredResp.trim());
      }
    };
    const onError = (err) => {
      cleanup();
      reject(err);
    };
    const onExit = (code) => {
      cleanup();
      if (!expiredResp.includes("\n")) {
        reject(new Error(`Expired connector exited early with code ${code}`));
      }
    };
    function cleanup() {
      clearTimeout(timer);
      expiredConnector.stdout.removeListener("data", onData);
      expiredConnector.removeListener("error", onError);
      expiredConnector.removeListener("exit", onExit);
    }
    expiredConnector.stdout.on("data", onData);
    expiredConnector.on("error", onError);
    expiredConnector.on("exit", onExit);
  });

  expiredConnector.stdin.write(
    JSON.stringify({
      jsonrpc: "2.0",
      id: "exp-1",
      method: "tools/list",
      params: {},
    }) + "\n",
  );

  const expRes = JSON.parse(await expiredPromise);
  assert.equal(expRes.id, "exp-1");
  assert.equal(expRes.error.code, -32000);
  assert.equal(expRes.error.data.tracepermitCode, "EXPIRED_BEARER_TOKEN");
  expiredConnector.stdin.end();

  // 8. Clean EOF / shutdown on primary connector
  connector.removeListener("exit", onConnectorExit);
  connector.removeListener("error", onConnectorError);
  connector.stdin.end();
  const exitCode = await new Promise((resolve) => {
    connector.once("exit", (code) => resolve(code));
    setTimeout(() => resolve("TIMEOUT"), 5000);
  });
  assert.equal(exitCode, 0, "Connector must exit cleanly with code 0 on EOF");

  // 9. Fetch gateway counters and assert exact values with bounded fetch timeout!
  const countersRes = await fetch(`http://127.0.0.1:${gatewayPort}/counters`, {
    signal: AbortSignal.timeout(5000),
  });
  assert.equal(countersRes.status, 200);
  const counters = await countersRes.json();

  assert.ok(
    counters.requestsReceived >= 6,
    `requestsReceived: ${counters.requestsReceived}`,
  );
  assert.equal(counters.initializeCount, 1, "initializeCount must be 1");
  assert.ok(counters.toolsListCount >= 1, "toolsListCount must be >= 1");
  assert.equal(counters.readAllowedCount, 1, "readAllowedCount must be 1");
  assert.equal(counters.policyBlockedCount, 1, "policyBlockedCount must be 1");
  assert.equal(
    counters.unknownToolRejectedCount,
    1,
    "unknownToolRejectedCount must be 1",
  );
  assert.equal(
    counters.expiredCredentialRejectedCount,
    1,
    "expiredCredentialRejectedCount must be 1",
  );

  // CRITICAL REQUIREMENT: policy block and unknown tool must NOT invoke target executor!
  // Only the single allowed read invokes the executor!
  assert.equal(
    counters.targetExecutorInvocations,
    1,
    "Target executor must ONLY be invoked for the allowed read (not for blocks or unknown tools)",
  );

  // 10. Clean shutdown of gateway
  gatewayProcess.stdin.write("SHUTDOWN\n");
  gatewayProcess.stdin.end();
  const gatewayExit = await new Promise((resolve) => {
    gatewayProcess.once("exit", (code) => resolve(code));
    setTimeout(() => resolve("TIMEOUT"), 5000);
  });
  assert.equal(gatewayExit, 0, "Gateway must exit cleanly with code 0");
});

test("fixture failure bounds and graceful termination: silent child, early exit, ignored SIGTERM, stalled response, failed setup", async (t) => {
  // 1. Test waitForProcessPattern on genuinely silent child: alive and producing no output, times out boundedly
  const silentChild = spawn(
    process.execPath,
    ["-e", "setInterval(() => {}, 1000)"],
    {
      stdio: ["pipe", "pipe", "pipe"],
    },
  );
  // Register cleanup before waiting
  t.after(() => gracefulTerminate(silentChild, "silentChild"));

  const silentStart = Date.now();
  await assert.rejects(
    waitForProcessPattern(silentChild, /READY/, 300, "silentChild ready"),
    /Timeout \(300ms\) waiting for silentChild ready/,
    "Genuinely silent child must timeout within bound",
  );
  assert.ok(
    Date.now() - silentStart < 1500,
    "Silent child timeout must be bounded",
  );
  await gracefulTerminate(silentChild, "silentChild", 1000);
  assert.ok(
    silentChild.exitCode !== null || silentChild.signalCode !== null,
    "Silent child must be terminated by gracefulTerminate",
  );

  // 2. Test waitForProcessPattern on early-exited child: rejects immediately with exit code
  const earlyExitChild = spawn(process.execPath, ["-e", "process.exit(42)"], {
    stdio: ["pipe", "pipe", "pipe"],
  });
  // Register cleanup before waiting
  t.after(() => gracefulTerminate(earlyExitChild, "earlyExitChild"));

  const earlyStart = Date.now();
  await assert.rejects(
    waitForProcessPattern(
      earlyExitChild,
      /READY/,
      5000,
      "earlyExitChild ready",
    ),
    /Child process exited early with code 42/,
    "Early exit child must reject immediately before timeout",
  );
  assert.ok(
    Date.now() - earlyStart < 1000,
    "Early-exited child must reject promptly without waiting for timeout",
  );
  await gracefulTerminate(earlyExitChild, "earlyExitChild", 1000);

  // 3. Test waitForProcessPattern on child spawn error (e.g. non-existent command)
  const errorChild = spawn(
    "tp-nonexistent-command-probe-for-failure-bounds",
    [],
    {
      stdio: ["pipe", "pipe", "pipe"],
    },
  );
  t.after(() => gracefulTerminate(errorChild, "errorChild"));
  await assert.rejects(
    waitForProcessPattern(errorChild, /READY/, 5000, "errorChild ready"),
    /Child process emitted error/,
    "Spawn error must reject immediately",
  );

  // 4. Test gracefulTerminate on already-exited child: returns immediately without hanging
  const alreadyExited = spawn(process.execPath, ["-e", "process.exit(0)"], {
    stdio: ["pipe", "pipe", "pipe"],
  });
  await new Promise((resolve) => alreadyExited.once("exit", resolve));
  const termStart = Date.now();
  await gracefulTerminate(alreadyExited, "alreadyExited", 2000);
  assert.ok(
    Date.now() - termStart < 100,
    "gracefulTerminate on already-exited child must return immediately without waiting for timeout",
  );

  // 5. Test gracefulTerminate on ignored SIGTERM: escalates to SIGKILL and observes exit
  const stubbornChild = spawn(
    process.execPath,
    [
      "-e",
      "process.on('SIGTERM', () => {}); console.log('READY'); setInterval(() => {}, 1000)",
    ],
    { stdio: ["pipe", "pipe", "pipe"] },
  );
  // Register cleanup before waiting
  t.after(() => {
    try {
      stubbornChild.kill("SIGKILL");
    } catch {}
  });
  await waitForProcessPattern(
    stubbornChild,
    /READY/,
    2000,
    "stubborn child ready",
  );
  assert.ok(stubbornChild.pid > 0);
  await gracefulTerminate(stubbornChild, "stubbornChild", 400);
  assert.ok(
    stubbornChild.exitCode !== null || stubbornChild.signalCode !== null,
    "Stubborn child ignoring SIGTERM must be terminated by escalated SIGKILL",
  );
  assert.equal(
    stubbornChild.signalCode,
    "SIGKILL",
    "Stubborn child must have been terminated with SIGKILL",
  );

  // 4. Stalled counter response: fetch aborts on timeout, sockets destroyed, server closed, port release verified
  const sockets = new Set();
  const stalledServer = createServer((_req, _res) => {
    // Deliberately hold connection open without responding
  });
  stalledServer.on("connection", (s) => {
    sockets.add(s);
    s.on("close", () => sockets.delete(s));
  });
  await new Promise((resolve) => stalledServer.listen(0, "127.0.0.1", resolve));
  const stalledPort = stalledServer.address().port;
  assert.ok(stalledPort > 0);

  const abortSignal = AbortSignal.timeout(500);
  await assert.rejects(
    fetch(`http://127.0.0.1:${stalledPort}/counters`, { signal: abortSignal }),
    (err) => err.name === "TimeoutError" || err.name === "AbortError",
    "Stalled counter response must abort within finite deadline rather than hang",
  );

  for (const s of sockets) {
    try {
      s.destroy();
    } catch {}
  }
  stalledServer.closeAllConnections?.();
  await new Promise((resolve) => stalledServer.close(resolve));

  const stalledPortReleased = await checkPortReleased(stalledPort, 2000);
  assert.ok(
    stalledPortReleased,
    `Stalled server port ${stalledPort} must be released after socket cleanup and server close`,
  );

  // 5. Failed setup with port release: resource acquired during setup is freed and port release verified
  let failedSetupPort = null;
  const setupError = await (async () => {
    let tempServer = null;
    try {
      tempServer = createServer();
      await new Promise((resolve) =>
        tempServer.listen(0, "127.0.0.1", resolve),
      );
      failedSetupPort = tempServer.address().port;
      assert.ok(failedSetupPort > 0);
      throw new Error("Simulated setup initialization failure");
    } catch (err) {
      if (tempServer) {
        await new Promise((resolve) => tempServer.close(resolve));
      }
      return err;
    }
  })();
  assert.equal(setupError.message, "Simulated setup initialization failure");
  const failedSetupPortReleased = await checkPortReleased(
    failedSetupPort,
    2000,
  );
  assert.ok(
    failedSetupPortReleased,
    `Port ${failedSetupPort} must be released after failed setup cleanup`,
  );
});

test("process parentage verification helper detects PPID and command mismatches", () => {
  function verifyParentage(psOutput, expectedPpid) {
    if (!psOutput) throw new Error("OS inspection unavailable");
    const parts = psOutput.trim().split(/\s+/);
    if (parts.length < 2)
      throw new Error(`Invalid ps output format: ${psOutput}`);
    const observedPpid = parseInt(parts[0], 10);
    const observedComm = parts.slice(1).join(" ").toLowerCase();
    assert.equal(
      observedPpid,
      expectedPpid,
      `Connector PPID must equal native spawning client PID (${expectedPpid}), observed: ${observedPpid}`,
    );
    assert.ok(
      observedComm.includes("java"),
      `Connector process command must be java, observed: ${observedComm}`,
    );
  }

  // 1. Matching ppid and java comm succeeds
  assert.doesNotThrow(() => verifyParentage(`12345 /usr/bin/java`, 12345));

  // 2. PPID mismatch throws AssertionError
  assert.throws(
    () => verifyParentage(`99999 /usr/bin/java`, 12345),
    /Connector PPID must equal native spawning client PID \(12345\)/,
  );

  // 3. Command mismatch throws AssertionError
  assert.throws(
    () => verifyParentage(`12345 /bin/bash`, 12345),
    /Connector process command must be java/,
  );
});

test("Gradle test task tracks connector archive input and invalidates cached green result when archive is replaced", async (t) => {
  ensureDistributionBuilt();
  assert.ok(
    existsSync(distArchive),
    `Distribution archive must exist at ${distArchive}`,
  );

  // Backup original archive
  const backupArchive = `${distArchive}.cache-test-backup`;
  copyFileSync(distArchive, backupArchive);
  let restored = false;
  const restoreArchive = () => {
    if (!restored && existsSync(backupArchive)) {
      copyFileSync(backupArchive, distArchive);
      rmSync(backupArchive, { force: true });
      restored = true;
    }
  };
  t.after(restoreArchive);

  // 1. Initial run: execute targeted Gradle test against current valid archive
  const firstRun = spawnSync(
    join(repoRoot, "java/gradlew"),
    [
      "-p",
      join(repoRoot, "java"),
      "--no-daemon",
      ":adapters-http:test",
      "-Dtracepermit.endpointConnector.requireInstalledArchive=true",
      "--tests",
      "io.tracepermit.adapters.http.JavaBackendEndpointConnectorE2eTest.installedArchiveConnectorAgainstRealBackendWithNativePty",
    ],
    {
      cwd: repoRoot,
      encoding: "utf8",
      timeout: 180_000,
    },
  );
  assert.equal(
    firstRun.status,
    0,
    `Initial Gradle test run must succeed: ${firstRun.stdout}\n${firstRun.stderr}`,
  );

  // 2. Corrupt/replace the tracked distribution archive with broken bytes
  writeFileSync(
    distArchive,
    "CORRUPTED_NON_TAR_CONTENT_FOR_CACHE_INVALIDATION_PROOF\n",
  );

  // 3. Re-run Gradle test for installed archive qualification requiring installed archive
  // Note: NO --rerun-tasks flag is passed!
  // If inputs were NOT tracked, Gradle would return UP-TO-DATE / FROM-CACHE.
  // Because inputs.files(endpointDistributionArchive) is tracked, Gradle detects the change,
  // re-runs the task, and fails on extracting/validating the corrupt archive.
  const rerunBroken = spawnSync(
    join(repoRoot, "java/gradlew"),
    [
      "-p",
      join(repoRoot, "java"),
      "--no-daemon",
      ":adapters-http:test",
      "-Dtracepermit.endpointConnector.requireInstalledArchive=true",
      "--tests",
      "io.tracepermit.adapters.http.JavaBackendEndpointConnectorE2eTest.installedArchiveConnectorAgainstRealBackendWithNativePty",
    ],
    {
      cwd: repoRoot,
      encoding: "utf8",
      timeout: 180_000,
    },
  );

  assert.notEqual(
    rerunBroken.status,
    0,
    "Gradle must fail when archive is replaced with a corrupted one; cached green result must not occur",
  );
  const rerunOutput = `${rerunBroken.stdout}\n${rerunBroken.stderr}`;
  assert.ok(
    !rerunOutput.includes(":adapters-http:test UP-TO-DATE") &&
      !rerunOutput.includes(":adapters-http:test FROM-CACHE"),
    "Task :adapters-http:test must be executed out-of-date, not served from cache",
  );

  // 4. Restore valid archive
  restoreArchive();
});
