import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import {
  readFileSync,
  readdirSync,
  statSync,
  mkdtempSync,
  rmSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repository = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const archive = path.join(
  repository,
  "java/pilot-operator/build/distributions/tracepermit-pilot-operator-0.1.0.tar.gz",
);
const prefix = "tracepermit-pilot-operator-0.1.0";
const sha = (bytes) => createHash("sha256").update(bytes).digest("hex");
const unpacked = mkdtempSync(path.join(tmpdir(), "tracepermit-pilot-archive-"));
try {
  // A missing archive is a required-gate failure, never a skip or auto-build.
  execFileSync("tar", ["-xzpf", archive, "-C", unpacked], {
    timeout: 10_000,
    stdio: "pipe",
  });
  const root = path.join(unpacked, prefix);
  const manifest = JSON.parse(
    readFileSync(path.join(root, "manifest.json"), "utf8"),
  );
  const files = [];
  function visit(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      assert.ok(!entry.isSymbolicLink());
      if (entry.isDirectory()) visit(file);
      else {
        assert.ok(entry.isFile());
        files.push(path.relative(root, file));
      }
    }
  }
  visit(root);
  test("archive has exact allowlisted source-free contents and no extra roots", () => {
    assert.deepEqual(readdirSync(unpacked), [prefix]);
    const expected = [
      "bin/tracepermit-pilot-operator",
      "docs/LICENSE",
      "docs/NOTICE",
      "docs/setup-runbook.md",
      "manifest.json",
      "SHA256SUMS",
      ...[
        "pilot-operator",
        "adapters-remote",
        "identity",
        "application",
        "contracts",
        "execution",
        "security",
        "policy",
      ].map((name) => `lib/${name}-0.1.0-SNAPSHOT.jar`),
      ...[
        "jackson-annotations-2.18.3",
        "jackson-core-2.18.3",
        "jackson-databind-2.18.3",
        "jackson-datatype-jsr310-2.18.3",
        "nimbus-jose-jwt-10.5",
        "okhttp-jvm-5.5.0",
        "okio-jvm-3.18.1",
        "kotlin-stdlib-2.2.21",
        "annotations-13.0",
      ].map((name) => `lib/${name}.jar`),
    ];
    assert.deepEqual(files.toSorted(), expected.toSorted());
    assert.equal(
      statSync(path.join(root, "bin/tracepermit-pilot-operator")).mode & 0o777,
      0o755,
    );
    for (const jar of files.filter((file) => file.endsWith(".jar"))) {
      const contents = execFileSync("unzip", ["-Z1", path.join(root, jar)], {
        timeout: 10_000,
        encoding: "utf8",
      });
      assert.doesNotMatch(
        contents,
        /PilotCredentialFixture|PilotNoDnsProvider|PilotOfflineProbe|\.java$|junit|mockwebserver/m,
      );
    }
  });
  test("manifest pins actual source provenance and exact dependency bytes", () => {
    const commit = execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: repository,
      encoding: "utf8",
    }).trim();
    const epoch = Number(
      execFileSync("git", ["show", "-s", "--format=%ct", "HEAD"], {
        cwd: repository,
        encoding: "utf8",
      }).trim(),
    );
    assert.equal(manifest.sourceCommit, commit);
    assert.equal(
      manifest.sourceCommitTimestamp,
      new Date(epoch * 1000).toISOString().replace(".000Z", "Z"),
    );
    assert.equal(manifest.sourceTree, "clean");
    assert.equal(manifest.requiredJava, "21");
    assert.equal(
      manifest.entryPoint,
      "io.tracepermit.adapters.remote.github.PilotOperatorMain",
    );
    assert.equal(manifest.intendedOperatorPlatform, "macOS");
    assert.match(manifest.qualification, /macOS host validation pending/);
    assert.equal(manifest.dependencyInventory.length, 17);
    for (const entry of manifest.dependencyInventory) {
      const bytes = readFileSync(path.join(root, "lib", entry.name));
      assert.equal(entry.sha256, sha(bytes));
      assert.equal(entry.sizeBytes, bytes.length);
    }
  });
  test("archive sidecar and every packaged file hash match with full inventory", () => {
    assert.equal(
      readFileSync(`${archive}.sha256`, "utf8"),
      `${sha(readFileSync(archive))}  ${path.basename(archive)}\n`,
    );
    const records = readFileSync(path.join(root, "SHA256SUMS"), "utf8")
      .split("\n")
      .filter((line) => line && !line.startsWith("#"));
    assert.equal(records.length, files.length - 1);
    const hashed = records.map((line) => {
      const match = /^([0-9a-f]{64})  ([A-Za-z0-9/._-]+)$/.exec(line);
      assert.ok(match);
      assert.equal(sha(readFileSync(path.join(root, match[2]))), match[1]);
      return match[2];
    });
    assert.deepEqual(
      hashed.toSorted(),
      files.filter((file) => file !== "SHA256SUMS").toSorted(),
    );
    assert.match(
      readFileSync(path.join(root, "docs/setup-runbook.md"), "utf8"),
      new RegExp(`blob/${manifest.sourceCommit}/docs/architecture/0081`),
    );
  });
} finally {
  // node:test callbacks execute later, so cleanup belongs to the test runner hook.
  test.after(() => rmSync(unpacked, { recursive: true, force: true }));
}
