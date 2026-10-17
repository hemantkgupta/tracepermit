import assert from "node:assert/strict";
import { execSync, spawn, spawnSync } from "node:child_process";
import crypto from "node:crypto";
import { createServer } from "node:net";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
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
const repoRoot = join(__dirname, "..");
const distArchive = join(
  repoRoot,
  "java/adapters-http/build/distributions/tracepermit-simulator-approval-0.1.0.tar.gz",
);
const distSha256File = `${distArchive}.sha256`;
let distributionBuilt = false;

function ensureDistributionBuilt() {
  // Ask the build to validate its inputs even when an older archive exists.
  if (!distributionBuilt) {
    const buildResult = spawnSync(
      join(repoRoot, "scripts/build-simulator-approval-distribution.sh"),
      {
        cwd: repoRoot,
        encoding: "utf8",
        timeout: 180_000,
      },
    );
    if (buildResult.status !== 0) {
      throw new Error(
        `Failed to build distribution:\n${buildResult.stdout}\n${buildResult.stderr}`,
      );
    }
    distributionBuilt = true;
  }
}

function command(command, args, cwd) {
  const result = spawnSync(command, args, {
    cwd,
    encoding: "utf8",
    timeout: 180_000,
  });
  assert.equal(
    result.status,
    0,
    `${command} failed: ${result.stdout}\n${result.stderr}`,
  );
  return result.stdout.trim();
}

test("packaging refreshes commit-only provenance and rejects dirty or unavailable source metadata", (t) => {
  // Minimal, synthetic Gradle/Git project: exercise the real packaging task without
  // modifying this checkout's history, compiling the backend again, or using secrets.
  const fixture = mkdtempSync(join(tmpdir(), "tp provenance fixture "));
  t.after(() => rmSync(fixture, { recursive: true, force: true }));
  const modules = [
    "adapters-http",
    "contracts",
    "policy",
    "application",
    "execution",
    "identity",
    "security",
    "simulator",
    "adapters-remote",
    "persistence-postgres",
    "endpoint-connector",
  ];
  for (const module of modules)
    mkdirSync(join(fixture, "java", module), { recursive: true });
  writeFileSync(join(fixture, ".gitignore"), "**/build/\n**/.gradle/\n");
  writeFileSync(
    join(fixture, "java/settings.gradle.kts"),
    modules.map((m) => `include(":${m}")`).join("\n"),
  );
  writeFileSync(
    join(fixture, "java/build.gradle.kts"),
    'allprojects { version = "0.1.0-SNAPSHOT" }\nsubprojects { apply(plugin = "java-library") }\n',
  );
  const buildFile = "java/adapters-http/build.gradle.kts";
  copyFileSync(join(repoRoot, buildFile), join(fixture, buildFile));
  for (const file of [
    "java/adapters-http/src/main/scripts/tracepermit-simulator-approval",
    "config/simulator-approvals.example.json",
    "docs/runbooks/simulator-approval-distribution.md",
    "LICENSE",
    "NOTICE",
  ]) {
    mkdirSync(dirname(join(fixture, file)), { recursive: true });
    copyFileSync(join(repoRoot, file), join(fixture, file));
  }
  mkdirSync(join(fixture, "apps/console/dist"), { recursive: true });
  writeFileSync(
    join(fixture, "apps/console/dist/index.html"),
    "synthetic console fixture\n",
  );
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
    ":adapters-http:packageSimulatorApprovalDistribution",
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
    "java/adapters-http/build/distributions/tracepermit-simulator-approval-0.1.0.tar.gz",
  );
  const manifest = () =>
    JSON.parse(
      command(
        "tar",
        ["-xOf", archive, "tracepermit-simulator-approval-0.1.0/manifest.json"],
        fixture,
      ),
    );
  buildOk();
  assert.equal(manifest().sourceCommit, git("rev-parse", "HEAD"));
  const originalHash = sha256OfFile(archive);
  git("commit", "--allow-empty", "--quiet", "-m", "Synthetic fixture B");
  buildOk();
  assert.equal(
    manifest().sourceCommit,
    git("rev-parse", "HEAD"),
    "Commit-only changes must invalidate cached provenance",
  );
  assert.notEqual(sha256OfFile(archive), originalHash);
  const committedHash = sha256OfFile(archive);
  assert.match(
    buildOk(),
    /:adapters-http:stageSimulatorApprovalDistribution UP-TO-DATE/,
  );
  assert.equal(sha256OfFile(archive), committedHash);
  const sibling = join(
    fixture,
    "java/adapters-http/build/staged-distribution/unrelated-artifact.txt",
  );
  writeFileSync(sibling, "must not enter this archive\n");
  buildOk(":adapters-http:stageSimulatorApprovalDistribution", "--rerun");
  assert.equal(
    sha256OfFile(archive),
    committedHash,
    "Restaging identical inputs must preserve archive bytes and exclude sibling outputs",
  );
  assert.ok(
    !command("tar", ["-tzf", archive], fixture).includes("unrelated-artifact"),
  );

  const originalNotice = readFileSync(join(fixture, "NOTICE"), "utf8");
  writeFileSync(
    join(fixture, "NOTICE"),
    `${originalNotice}\nUncommitted change\n`,
  );
  const assertDirtyBuildRejected = () => {
    const result = build();
    assert.equal(result.status, 1);
    assert.match(
      `${result.stdout}\n${result.stderr}`,
      /requires a clean Git source tree/,
    );
    assert.ok(
      !result.stdout.includes("Distribution SHA-256:"),
      "Failed packaging must not checksum an old archive",
    );
    assert.equal(sha256OfFile(archive), committedHash);
  };
  assertDirtyBuildRejected();
  writeFileSync(join(fixture, "NOTICE"), originalNotice);
  const untracked = join(fixture, "untracked-source.txt");
  writeFileSync(untracked, "uncommitted input\n");
  assertDirtyBuildRejected();
  rmSync(untracked);
  buildOk();
  renameSync(join(fixture, ".git"), join(fixture, ".git-unavailable"));
  assert.notEqual(
    build().status,
    0,
    "Missing Git metadata must fail; never label an archive unknown",
  );
});

function sha256OfFile(filePath) {
  const content = readFileSync(filePath);
  return crypto.createHash("sha256").update(content).digest("hex");
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
  if (sanitizedBinDirectory)
    rmSync(sanitizedBinDirectory, { recursive: true, force: true });
});
function createSanitizedPath() {
  if (cachedSanitizedPath) return cachedSanitizedPath;
  const binDir = mkdtempSync(join(tmpdir(), "tp-sanitized-bin-"));
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

function setupSyntheticOperatorConfig(tempDir, consolePath) {
  const privateDir = join(tempDir, "operator-private");
  mkdirSync(privateDir, { recursive: true, mode: 0o700 });

  const { publicKey, privateKey } = crypto.generateKeyPairSync("ed25519");
  const pk8Der = privateKey.export({ type: "pkcs8", format: "der" });
  const spkiDer = publicKey.export({ type: "spki", format: "der" });

  const pk8Path = join(privateDir, "permit.pk8");
  const spkiPath = join(privateDir, "permit.spki");
  writeFileSync(pk8Path, pk8Der);
  chmodSync(pk8Path, 0o600);
  writeFileSync(spkiPath, spkiDer);
  chmodSync(spkiPath, 0o600);

  const dbSecretPath = join(privateDir, "database.secret");
  const dbPassword = "synthetic-operator-db-password-16char";
  writeFileSync(dbSecretPath, dbPassword);
  chmodSync(dbSecretPath, 0o600);

  const browserSecretPath = join(privateDir, "browser.secret");
  const browserSecret = "synthetic-operator-browser-secret-16char";
  writeFileSync(browserSecretPath, browserSecret);
  chmodSync(browserSecretPath, 0o600);

  const configPath = join(privateDir, "operator.json");
  const config = {
    schemaVersion: "1.0",
    port: 8085,
    postgres: {
      host: "127.0.0.1",
      port: 1, // Deliberately unreachable
      database: "tracepermit_approvals",
      user: "tracepermit_approvals",
      passwordFile: "database.secret",
    },
    oidc: {
      issuer: "https://idp.example.invalid",
      audience: "https://approval.example.invalid",
      jwksUri: "https://idp.example.invalid/.well-known/jwks.json",
      clientId: "replace-with-registered-client",
      tenantClaim: "org_id",
      tenantValue: "replace-with-verified-tenant-claim",
      tenantId: "simulated-local",
    },
    memberships: [
      {
        subject: "replace-with-requester-sub",
        human: true,
        roles: ["REQUESTER"],
      },
      {
        subject: "replace-with-reviewer-sub",
        human: true,
        roles: ["REVIEWER"],
      },
    ],
    permit: {
      keyId: "local-simulator-1",
      privateKeyFile: "permit.pk8",
      publicKeyFile: "permit.spki",
    },
    browser: {
      authorizationUri: "https://idp.example.invalid/authorize",
      tokenUri: "https://idp.example.invalid/token",
      clientSecretFile: "browser.secret",
      consoleDirectory: consolePath,
    },
  };

  writeFileSync(configPath, JSON.stringify(config, null, 2));
  chmodSync(configPath, 0o600);

  return { configPath, privateDir, dbPassword, browserSecret };
}

test("distribution archive exists and matches sha256 checksum file", () => {
  ensureDistributionBuilt();
  assert.ok(existsSync(distArchive), "Distribution archive must exist");
  assert.ok(existsSync(distSha256File), "Checksum file must exist");

  const calculatedSha = sha256OfFile(distArchive);
  const checksumFileContent = readFileSync(distSha256File, "utf8").trim();
  assert.equal(
    checksumFileContent,
    `${calculatedSha}  tracepermit-simulator-approval-0.1.0.tar.gz`,
  );
});

test("distribution archive layout, permissions, and clean dependency inventory", (t) => {
  ensureDistributionBuilt();
  // Extract into a path with spaces
  const extractRoot = mkdtempSync(join(tmpdir(), "tp dist test spaces "));
  t.after(() => rmSync(extractRoot, { recursive: true, force: true }));

  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-simulator-approval-0.1.0");
  assert.ok(existsSync(distDir), "Extracted directory must exist");

  // Verify top-level structure
  const launcher = join(distDir, "bin/tracepermit-simulator-approval");
  assert.ok(existsSync(launcher), "Launcher must exist");
  const launcherStat = statSync(launcher);
  assert.equal(
    launcherStat.mode & 0o777,
    0o755,
    "Launcher must have mode 0755",
  );

  assert.ok(
    existsSync(join(distDir, "console/index.html")),
    "console/index.html must exist",
  );
  assert.ok(
    existsSync(join(distDir, "console/assets")),
    "console/assets must exist",
  );
  assert.ok(
    existsSync(join(distDir, "docs/setup-runbook.md")),
    "docs/setup-runbook.md must exist",
  );
  assert.ok(
    existsSync(join(distDir, "docs/LICENSE")),
    "docs/LICENSE must exist",
  );
  assert.ok(existsSync(join(distDir, "docs/NOTICE")), "docs/NOTICE must exist");
  assert.ok(
    existsSync(join(distDir, "examples/simulator-approvals.example.json")),
    "examples/simulator-approvals.example.json must exist",
  );

  // Verify manifest.json
  const manifestPath = join(distDir, "manifest.json");
  assert.ok(existsSync(manifestPath), "manifest.json must exist");
  assert.equal(statSync(manifestPath).mode & 0o777, 0o644);
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  assert.equal(manifest.schemaVersion, "1.1");
  const installedRunbook = readFileSync(
    join(distDir, "docs/setup-runbook.md"),
    "utf8",
  );
  assert.ok(installedRunbook.includes("--init-db"));
  assert.ok(
    installedRunbook.includes(
      `/blob/${manifest.sourceCommit}/docs/architecture/0045-explicit-database-initialization.md`,
    ),
  );
  assert.ok(
    !/\]\((?:\.\.\/|authenticated-approval-api-foundation\.md)/.test(
      installedRunbook,
    ),
  );
  assert.equal(manifest.packageName, "tracepermit-simulator-approval");
  assert.equal(manifest.packageVersion, "0.1.0");
  assert.equal(manifest.requiredJava, "21");
  assert.equal(
    manifest.sourceCommit,
    command("git", ["rev-parse", "HEAD"], repoRoot),
  );
  assert.equal(manifest.sourceTree, "clean");
  assert.equal(
    Date.parse(manifest.sourceCommitTimestamp),
    Number(command("git", ["show", "-s", "--format=%ct", "HEAD"], repoRoot)) *
      1000,
  );
  assert.equal(Object.hasOwn(manifest, "buildTimestamp"), false);
  assert.equal(
    manifest.entryPoint,
    "io.tracepermit.adapters.http.SimulatorApprovalMain",
  );
  assert.equal(manifest.launcher, "bin/tracepermit-simulator-approval");
  assert.ok(Array.isArray(manifest.dependencyInventory));
  assert.ok(manifest.dependencyInventory.length >= 10);
  assert.ok(manifest.integrityNotice.includes("checksums verify"));

  // Verify SHA256SUMS
  const sumsPath = join(distDir, "SHA256SUMS");
  assert.ok(existsSync(sumsPath), "SHA256SUMS must exist");
  const sumsLines = readFileSync(sumsPath, "utf8")
    .split("\n")
    .filter((line) => line && !line.startsWith("#"));
  const filePaths = (directory) =>
    readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
      const path = join(directory, entry.name);
      assert.ok(!entry.isSymbolicLink(), "Archive must not contain symlinks");
      return entry.isDirectory() ? filePaths(path) : [relative(distDir, path)];
    });
  const packagedFiles = filePaths(distDir)
    .filter((path) => path !== "SHA256SUMS")
    .sort();
  const checkedFiles = [];
  for (const line of sumsLines) {
    const [, hash, file] = line.match(/^([0-9a-f]{64})  (.+)$/) ?? [];
    assert.ok(file && !file.startsWith("/") && !file.split("/").includes(".."));
    checkedFiles.push(file);
    const fullPath = join(distDir, file);
    assert.ok(existsSync(fullPath), `File in SHA256SUMS must exist: ${file}`);
    assert.equal(
      sha256OfFile(fullPath),
      hash,
      `Checksum must match for ${file}`,
    );
  }
  assert.deepEqual(
    checkedFiles.sort(),
    packagedFiles,
    "Every packaged file must have exactly one checksum",
  );
  assert.ok(
    !packagedFiles.some((path) =>
      /(^|\/)(node_modules|\.git|src)(\/|$)|\.(pk8|spki|secret|class)$/.test(
        path,
      ),
    ),
    "Archive must exclude source trees, private material, and loose classes",
  );

  // Verify absence of forbidden artifacts:
  // no node_modules, no .git, no .class outside JARs, no test JARs, no test IdP classes
  const libDir = join(distDir, "lib");
  const jars = readdirSync(libDir).filter((f) => f.endsWith(".jar"));
  assert.ok(jars.length >= 10, "lib/ must contain runtime jars");
  assert.deepEqual(
    manifest.dependencyInventory.map((jar) => jar.name),
    [...jars].sort(),
  );
  for (const jar of manifest.dependencyInventory) {
    assert.equal(jar.sizeBytes, statSync(join(libDir, jar.name)).size);
    assert.equal(jar.sha256, sha256OfFile(join(libDir, jar.name)));
  }

  for (const jar of jars) {
    const jarEntries = execSync(`jar -tf "${join(libDir, jar)}"`, {
      encoding: "utf8",
    });
    assert.ok(
      !/org\/testcontainers\/|org\/junit\/|org\/assertj\//.test(jarEntries),
      `JAR ${jar} must not contain Testcontainers`,
    );
    assert.ok(
      !jarEntries.includes("SimulatorApprovalBrowserQualificationMain"),
      `JAR ${jar} must not contain test-classpath IdP fixture`,
    );
    assert.ok(
      !jarEntries.includes("SimulatorApprovalBootstrapTest"),
      `JAR ${jar} must not contain test classes`,
    );
    assert.ok(
      !jarEntries.includes("SemanticQualificationMain"),
      `JAR ${jar} must not contain test qualification mains`,
    );
  }

  // Verify launcher strictly locks to SimulatorApprovalMain and has no selectable main class
  const launcherContent = readFileSync(launcher, "utf8");
  assert.ok(
    launcherContent.includes(
      "io.tracepermit.adapters.http.SimulatorApprovalMain",
    ),
    "Launcher must execute SimulatorApprovalMain",
  );
  assert.ok(
    !launcherContent.includes("DevelopmentBackendMain"),
    "Launcher must not expose DevelopmentBackendMain",
  );
  assert.ok(
    !launcherContent.includes("SimulatorApprovalBrowserQualificationMain"),
    "Launcher must not expose test-classpath IdP fixture",
  );
});

test("offline --check succeeds from unrelated working directory without Node/pnpm/Gradle", (t) => {
  ensureDistributionBuilt();
  const extractRoot = mkdtempSync(
    join(tmpdir(), "tp dist test offline spaces "),
  );
  const unrelatedCwd = mkdtempSync(join(tmpdir(), "tp unrelated cwd "));
  t.after(() => {
    rmSync(extractRoot, { recursive: true, force: true });
    rmSync(unrelatedCwd, { recursive: true, force: true });
  });

  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-simulator-approval-0.1.0");
  const launcher = join(distDir, "bin/tracepermit-simulator-approval");
  const consoleDir = join(distDir, "console");

  const { configPath, dbPassword } = setupSyntheticOperatorConfig(
    extractRoot,
    consoleDir,
  );

  const sanitizedPath = createSanitizedPath();

  // Verify node/pnpm/gradle are absent from sanitizedPath
  const checkNode = spawnSync("which", ["node"], {
    env: { PATH: sanitizedPath },
    encoding: "utf8",
  });
  assert.notEqual(
    checkNode.status,
    0,
    "Node must not be reachable on sanitized PATH",
  );

  const checkPnpm = spawnSync("which", ["pnpm"], {
    env: { PATH: sanitizedPath },
    encoding: "utf8",
  });
  assert.notEqual(
    checkPnpm.status,
    0,
    "pnpm must not be reachable on sanitized PATH",
  );

  const checkGradle = spawnSync("which", ["gradle"], {
    env: { PATH: sanitizedPath },
    encoding: "utf8",
  });
  assert.notEqual(
    checkGradle.status,
    0,
    "Gradle must not be reachable on sanitized PATH",
  );

  // Execute --check from unrelated working directory
  const result = spawnSync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: {
      PATH: sanitizedPath,
      TRACEPERMIT_APPROVAL_CONFIG: configPath,
    },
    encoding: "utf8",
    timeout: 30_000,
  });

  assert.equal(result.status, 0, `Expected status 0, stderr: ${result.stderr}`);
  assert.ok(
    result.stdout.includes(
      "APPROVAL_CONFIGURATION_VALID: offline only; no database, IdP or listener checked.",
    ),
    `Unexpected stdout: ${result.stdout}`,
  );
  assert.equal(result.stderr, "");

  // Verify secret safety: no password or config paths in stdout
  assert.ok(
    !result.stdout.includes(dbPassword),
    "Password must never appear in output",
  );
  assert.ok(
    !result.stdout.includes(configPath),
    "Config path must not appear in valid output",
  );
});

test("launcher failure paths enforce exit code 2 and secret redaction", (t) => {
  ensureDistributionBuilt();
  const extractRoot = mkdtempSync(join(tmpdir(), "tp dist test neg "));
  const unrelatedCwd = mkdtempSync(join(tmpdir(), "tp unrelated neg cwd "));
  t.after(() => {
    rmSync(extractRoot, { recursive: true, force: true });
    rmSync(unrelatedCwd, { recursive: true, force: true });
  });

  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-simulator-approval-0.1.0");
  const launcher = join(distDir, "bin/tracepermit-simulator-approval");
  const consoleDir = join(distDir, "console");

  const { configPath, dbPassword } = setupSyntheticOperatorConfig(
    extractRoot,
    consoleDir,
  );
  const sanitizedPath = createSanitizedPath();

  // 1. Missing arguments
  const noArgs = spawnSync(launcher, [], {
    cwd: unrelatedCwd,
    env: { PATH: sanitizedPath, TRACEPERMIT_APPROVAL_CONFIG: configPath },
    encoding: "utf8",
  });
  assert.equal(noArgs.status, 2);
  assert.ok(noArgs.stderr.includes("APPROVAL_STARTUP_FAILED:"));
  assert.ok(!noArgs.stderr.includes("Exception"));

  // 2. Unsupported flag
  const badFlag = spawnSync(launcher, ["--unsupported"], {
    cwd: unrelatedCwd,
    env: { PATH: sanitizedPath, TRACEPERMIT_APPROVAL_CONFIG: configPath },
    encoding: "utf8",
  });
  assert.equal(badFlag.status, 2);
  assert.ok(badFlag.stderr.includes("APPROVAL_STARTUP_FAILED:"));

  // 3. Extra arguments
  const extraArgs = spawnSync(launcher, ["--check", "--run"], {
    cwd: unrelatedCwd,
    env: { PATH: sanitizedPath, TRACEPERMIT_APPROVAL_CONFIG: configPath },
    encoding: "utf8",
  });
  assert.equal(extraArgs.status, 2);
  assert.ok(extraArgs.stderr.includes("APPROVAL_STARTUP_FAILED:"));

  // 4. Missing TRACEPERMIT_APPROVAL_CONFIG
  const missingEnv = spawnSync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: { PATH: sanitizedPath },
    encoding: "utf8",
  });
  assert.equal(missingEnv.status, 2);
  assert.ok(missingEnv.stderr.includes("APPROVAL_STARTUP_FAILED:"));

  // 5. Missing console assets in distribution
  const brokenDistRoot = mkdtempSync(join(tmpdir(), "tp dist broken "));
  t.after(() => rmSync(brokenDistRoot, { recursive: true, force: true }));
  execSync(`tar -xzf "${distArchive}" -C "${brokenDistRoot}"`);
  const brokenDistDir = join(
    brokenDistRoot,
    "tracepermit-simulator-approval-0.1.0",
  );
  rmSync(join(brokenDistDir, "console/index.html"));
  const brokenLauncher = join(
    brokenDistDir,
    "bin/tracepermit-simulator-approval",
  );
  const missingConsole = spawnSync(brokenLauncher, ["--check"], {
    cwd: unrelatedCwd,
    env: { PATH: sanitizedPath, TRACEPERMIT_APPROVAL_CONFIG: configPath },
    encoding: "utf8",
  });
  assert.equal(missingConsole.status, 2);
  assert.ok(missingConsole.stderr.includes("console assets are missing"));

  // 6. Missing Java on PATH
  const missingJava = spawnSync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: { PATH: "/bin:/usr/bin", TRACEPERMIT_APPROVAL_CONFIG: configPath },
    encoding: "utf8",
  });
  assert.equal(missingJava.status, 2);
  assert.ok(missingJava.stderr.includes("Java 21 is required"));

  // 7. Incompatible Java version
  const fakeJavaDir = mkdtempSync(join(tmpdir(), "fake java "));
  t.after(() => rmSync(fakeJavaDir, { recursive: true, force: true }));
  const fakeJava = join(fakeJavaDir, "java");
  writeFileSync(
    fakeJava,
    "#!/usr/bin/env bash\necho 'openjdk version \"17.0.2\" 2022-01-18' >&2\n",
  );
  chmodSync(fakeJava, 0o755);
  const badJava = spawnSync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: {
      PATH: `${fakeJavaDir}:/bin:/usr/bin`,
      TRACEPERMIT_APPROVAL_CONFIG: configPath,
    },
    encoding: "utf8",
  });
  assert.equal(badJava.status, 2);
  assert.ok(badJava.stderr.includes("Java 21 is required"));

  // 8. Offline check failure when config file permissions are insecure (0666)
  chmodSync(configPath, 0o666);
  const badPerms = spawnSync(launcher, ["--check"], {
    cwd: unrelatedCwd,
    env: { PATH: sanitizedPath, TRACEPERMIT_APPROVAL_CONFIG: configPath },
    encoding: "utf8",
  });
  assert.equal(badPerms.status, 2);
  assert.ok(badPerms.stderr.includes("APPROVAL_STARTUP_FAILED:"));
  assert.ok(
    !badPerms.stderr.includes(dbPassword),
    "Password must never appear in error",
  );
  assert.ok(
    !badPerms.stderr.includes(configPath),
    "Config path must never appear in error",
  );
});

test("online --run fails closed with exit code 2 and redacted error when database unreachable", (t) => {
  ensureDistributionBuilt();
  const extractRoot = mkdtempSync(join(tmpdir(), "tp dist test online fail "));
  const unrelatedCwd = mkdtempSync(join(tmpdir(), "tp unrelated online fail "));
  t.after(() => {
    rmSync(extractRoot, { recursive: true, force: true });
    rmSync(unrelatedCwd, { recursive: true, force: true });
  });

  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-simulator-approval-0.1.0");
  const launcher = join(distDir, "bin/tracepermit-simulator-approval");
  const consoleDir = join(distDir, "console");

  const { configPath, dbPassword } = setupSyntheticOperatorConfig(
    extractRoot,
    consoleDir,
  );
  const sanitizedPath = createSanitizedPath();

  // Run --run with unreachable database (port 1)
  const result = spawnSync(launcher, ["--run"], {
    cwd: unrelatedCwd,
    env: {
      PATH: sanitizedPath,
      TRACEPERMIT_APPROVAL_CONFIG: configPath,
    },
    encoding: "utf8",
    timeout: 30_000,
  });

  assert.equal(result.status, 2);
  assert.ok(
    result.stderr.includes(
      "APPROVAL_STARTUP_FAILED: check private configuration and local dependencies; details redacted.",
    ),
    `Unexpected stderr: ${result.stderr}`,
  );
  assert.ok(
    !result.stderr.includes(dbPassword),
    "Password must never appear in error",
  );
  assert.ok(
    !result.stderr.includes("Exception"),
    "Exception stack trace must be redacted",
  );
  assert.ok(
    !result.stderr.includes("Connection refused"),
    "Driver connection details must be redacted",
  );
});

function getFreePort() {
  return new Promise((resolve, reject) => {
    const srv = createServer();
    srv.listen(0, "127.0.0.1", () => {
      const port = srv.address().port;
      srv.close(() => resolve(port));
    });
    srv.on("error", reject);
  });
}

function isDockerAvailable() {
  try {
    const res = spawnSync("docker", ["info"], { stdio: "ignore" });
    return res.status === 0;
  } catch {
    return false;
  }
}

test("online --run starts, binds loopback port, and terminates cleanly on SIGTERM", async (t) => {
  if (!isDockerAvailable()) {
    t.skip("Docker is not available; skipping live PostgreSQL --run test");
    return;
  }

  ensureDistributionBuilt();
  const extractRoot = mkdtempSync(join(tmpdir(), "tp dist live test "));
  const unrelatedCwd = mkdtempSync(join(tmpdir(), "tp unrelated live cwd "));
  t.after(() => {
    rmSync(extractRoot, { recursive: true, force: true });
    rmSync(unrelatedCwd, { recursive: true, force: true });
  });

  execSync(`tar -xzf "${distArchive}" -C "${extractRoot}"`);
  const distDir = join(extractRoot, "tracepermit-simulator-approval-0.1.0");
  const launcher = join(distDir, "bin/tracepermit-simulator-approval");
  const consoleDir = join(distDir, "console");

  // Start disposable Postgres container
  const pgPassword = "synthetic-disposable-pg-password-16";
  const containerId = execSync(
    `docker run -d --rm -e POSTGRES_PASSWORD=${pgPassword} -e POSTGRES_DB=tracepermit_approvals -e POSTGRES_USER=tracepermit_approvals -p 127.0.0.1::5432 postgres:16.4-alpine`,
    { encoding: "utf8" },
  ).trim();
  t.after(() => {
    try {
      execSync(`docker stop ${containerId}`, { stdio: "ignore" });
    } catch {}
  });

  // Wait for postgres ready
  let ready = false;
  for (let i = 0; i < 30; i++) {
    const readyRes = spawnSync(
      "docker",
      ["exec", containerId, "pg_isready", "-U", "tracepermit_approvals"],
      { stdio: "ignore" },
    );
    if (readyRes.status === 0) {
      ready = true;
      break;
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  assert.ok(ready, "Postgres container must be ready");

  // Get mapped port
  const portOutput = execSync(`docker port ${containerId} 5432`, {
    encoding: "utf8",
  });
  const pgPort = parseInt(portOutput.trim().split(":").pop(), 10);
  assert.ok(pgPort > 0, "Mapped PG port must be positive");

  const apiPort = await getFreePort();

  // Setup operator config
  const { configPath } = setupSyntheticOperatorConfig(extractRoot, consoleDir);
  // Update config with real pgPort, apiPort, and pgPassword
  const config = JSON.parse(readFileSync(configPath, "utf8"));
  config.port = apiPort;
  config.postgres.port = pgPort;
  writeFileSync(configPath, JSON.stringify(config, null, 2));
  const dbSecretPath = join(dirname(configPath), "database.secret");
  writeFileSync(dbSecretPath, pgPassword);

  const sanitizedPath = createSanitizedPath();

  // Source-free commands must not confuse an empty DB with successful restore.
  const commandOptions = {
    cwd: unrelatedCwd,
    env: { PATH: sanitizedPath, TRACEPERMIT_APPROVAL_CONFIG: configPath },
    encoding: "utf8",
    timeout: 25_000,
  };
  const emptyRun = spawnSync(launcher, ["--run"], commandOptions);
  assert.equal(emptyRun.status, 2);
  assert.ok(emptyRun.stderr.includes("APPROVAL_STARTUP_FAILED"));
  assert.equal(
    execSync(
      `docker exec ${containerId} psql -U tracepermit_approvals -d tracepermit_approvals -Atc "SELECT count(*) FROM pg_tables WHERE schemaname='public'"`,
      { encoding: "utf8" },
    ).trim(),
    "0",
  );
  const initialize = spawnSync(launcher, ["--init-db"], commandOptions);
  assert.equal(initialize.status, 0, initialize.stderr);
  assert.ok(initialize.stdout.includes("APPROVAL_DATABASE_INITIALIZED"));
  assert.ok(!initialize.stdout.includes("API listening"));
  const duplicateInit = spawnSync(launcher, ["--init-db"], commandOptions);
  assert.equal(duplicateInit.status, 2);
  for (const result of [emptyRun, initialize, duplicateInit]) {
    const output = result.stdout + result.stderr;
    assert.ok(!output.includes(pgPassword));
    assert.ok(!output.includes(configPath));
    assert.ok(!output.includes("jdbc:"));
  }

  // Spawn --run
  const child = spawn(launcher, ["--run"], {
    cwd: unrelatedCwd,
    env: {
      PATH: sanitizedPath,
      TRACEPERMIT_APPROVAL_CONFIG: configPath,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });

  let childStdout = "";
  let childStderr = "";
  child.stdout.on("data", (d) => {
    childStdout += d.toString();
  });
  child.stderr.on("data", (d) => {
    childStderr += d.toString();
  });

  // Wait until server is listening
  const deadline = Date.now() + 20_000;
  while (
    !childStdout.includes("API listening on http://127.0.0.1:") &&
    Date.now() < deadline
  ) {
    if (child.exitCode !== null) break;
    await new Promise((r) => setTimeout(r, 100));
  }

  assert.ok(
    childStdout.includes(`API listening on http://127.0.0.1:${apiPort}`),
    `Expected API listening output, got stdout: ${childStdout}, stderr: ${childStderr}`,
  );

  // Verify unauthenticated GET returns 401
  const probeRes = await fetch(`http://127.0.0.1:${apiPort}/api/v1/approvals`);
  assert.equal(probeRes.status, 401);

  // Send SIGTERM to test clean shutdown
  child.kill("SIGTERM");

  // Wait for clean exit
  const exitCode = await new Promise((resolve) => {
    child.on("exit", (code, signal) => {
      resolve(code ?? signal);
    });
    setTimeout(() => resolve("TIMEOUT"), 10_000);
  });

  assert.ok(
    exitCode === 0 || exitCode === 143 || exitCode === "SIGTERM",
    `Expected clean shutdown, got ${exitCode}`,
  );

  // Verify port is immediately unbound
  const rebindServer = createServer();
  await new Promise((resolve, reject) => {
    rebindServer.listen(apiPort, "127.0.0.1", () => {
      rebindServer.close(() => resolve());
    });
    rebindServer.on("error", reject);
  });

  // Verify no secret leakage
  assert.ok(
    !childStdout.includes(pgPassword),
    "Password must not appear in stdout",
  );
  assert.ok(
    !childStderr.includes(pgPassword),
    "Password must not appear in stderr",
  );

  // Packaged containment commands require neither a source checkout nor an IdP connection.
  for (const argument of [
    "--recovery-status",
    "--quarantine-db",
    "--quarantine-db",
    "--recovery-status",
    "--run",
  ]) {
    const result = spawnSync(launcher, [argument], commandOptions);
    assert.equal(result.status, argument === "--run" ? 2 : 0, result.stderr);
    const output = result.stdout + result.stderr;
    assert.ok(!output.includes("API listening"));
    assert.ok(!output.includes(pgPassword));
    assert.ok(!output.includes(configPath));
    assert.ok(!output.includes("jdbc:"));
    if (argument === "--quarantine-db")
      assert.ok(output.includes("APPROVAL_DATABASE_QUARANTINED"));
    if (argument === "--run")
      assert.ok(output.includes("APPROVAL_STARTUP_FAILED"));
  }
});
