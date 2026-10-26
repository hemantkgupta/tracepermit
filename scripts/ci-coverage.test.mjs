import assert from "node:assert/strict";
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  writeFileSync,
  rmSync,
  symlinkSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { lanes } from "./ci-policy.mjs";
import {
  exportCoverage,
  validateCoverage,
  modulesFor,
} from "./ci-coverage.mjs";
const sha = "a".repeat(40),
  run = "123";
function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), "tracepermit-ci-coverage-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const name of [
    "contracts",
    "persistence-postgres",
    "persistence-mariadb",
    "persistence-operations",
  ]) {
    const dir = join(root, "java", name);
    mkdirSync(join(dir, "build/jacoco"), { recursive: true });
    writeFileSync(join(dir, "build.gradle.kts"), "");
    writeFileSync(join(dir, "build/jacoco/test.exec"), "synthetic-data");
  }
  const destination = join(root, "coverage");
  for (const lane of lanes) exportCoverage(root, destination, lane, sha, run);
  return { root, destination };
}
test("complete shards require every module and exact source/run identity", (t) => {
  const { root, destination } = fixture(t);
  validateCoverage(root, destination, sha, run);
  for (const [commit, id] of [
    ["b".repeat(40), run],
    [sha, "124"],
    [undefined, run],
  ])
    assert.throws(() => validateCoverage(root, destination, commit, id));
});
for (const fault of [
  "missing-lane",
  "missing-module",
  "corrupt-data",
  "extra-file",
  "stale-manifest",
  "symlink",
])
  test(`coverage refuses ${fault}`, (t) => {
    const { root, destination } = fixture(t);
    const lane = join(destination, "postgres"),
      file = join(lane, "persistence-postgres.exec");
    if (fault === "missing-lane") rmSync(lane, { recursive: true });
    if (fault === "missing-module") rmSync(file);
    if (fault === "corrupt-data") writeFileSync(file, "corrupt");
    if (fault === "extra-file")
      writeFileSync(join(lane, "extra.exec"), "unexpected");
    if (fault === "stale-manifest") {
      const path = join(lane, "manifest.json");
      const m = JSON.parse(readFileSync(path));
      m.sha = "b".repeat(40);
      writeFileSync(path, JSON.stringify(m));
    }
    if (fault === "symlink") {
      rmSync(file);
      symlinkSync(join(root, "java/contracts/build/jacoco/test.exec"), file);
    }
    assert.throws(() => validateCoverage(root, destination, sha, run));
  });
test("new modules join core automatically and missing execution evidence fails", (t) => {
  const { root, destination } = fixture(t);
  const dir = join(root, "java/new-module");
  mkdirSync(dir);
  writeFileSync(join(dir, "build.gradle.kts"), "");
  assert.ok(modulesFor("core", root).includes("new-module"));
  assert.throws(() => validateCoverage(root, destination, sha, run));
  assert.throws(() => exportCoverage(root, destination, "core", sha, run));
  assert.throws(() => modulesFor("unknown", root));
});
