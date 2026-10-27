import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { classify, plan, verify } from "./ci-policy.mjs";

const names = [
  "core",
  "databases",
  "operation-repository",
  "coverage",
  "packaging",
  "qualification",
  "docs",
  "browser-approvals",
  "site-browser",
];
function results(mode) {
  const optional =
    mode === "docs"
      ? names.filter((n) => !["docs", "site-browser"].includes(n))
      : mode === "frontend"
        ? ["databases", "operation-repository", "coverage", "qualification"]
        : [];
  return {
    changes: { result: "success", outputs: { mode } },
    ...Object.fromEntries(
      names.map((n) => [
        n,
        { result: optional.includes(n) ? "skipped" : "success" },
      ]),
    ),
  };
}
test("only explicit prose/site and console paths permit a lighter plan", () => {
  assert.equal(
    classify([
      "README.md",
      "docs/runbooks/example.md",
      "site/.vitepress/config.mts",
    ]),
    "docs",
  );
  assert.equal(
    classify(["apps/console/src/App.tsx", "docs/readme.md"]),
    "frontend",
  );
  for (const path of [
    "java/contracts/example.java",
    "contracts/vectors/example.json",
    "scripts/ci-policy.mjs",
    ".github/workflows/ci.yml",
    "pnpm-lock.yaml",
    "package.json",
    "apps/console/package.json",
    "apps/console/vite.config.ts",
    "apps/console/tsconfig.json",
    "deploy/container/Dockerfile",
    "docs/fixture.json",
    "new-module/code.rs",
    "AGENTS.md",
    "apps/console/../../java/test.java",
    "site/../java/test.java",
    "site\\config.js",
    "\ufffd",
  ]) {
    assert.equal(classify(["README.md", path]), "full", path);
  }
  for (const paths of [[], null, [null], [""]])
    assert.equal(classify(paths), "full");
});
test("real Git changes include deletions and both sides of a rename", (t) => {
  const root = mkdtempSync(join(tmpdir(), "tracepermit-ci-plan-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const git = (...args) =>
    execFileSync("git", args, {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  git("init");
  git("config", "user.name", "Synthetic CI");
  git("config", "user.email", "ci@example.invalid");
  mkdirSync(join(root, "java"));
  writeFileSync(join(root, "java", "important.java"), "synthetic");
  writeFileSync(join(root, "README.md"), "baseline");
  git("add", ".");
  git("commit", "-m", "baseline");
  const base = git("rev-parse", "HEAD");
  writeFileSync(join(root, "README.md"), "docs");
  git("add", ".");
  git("commit", "-m", "docs");
  assert.equal(plan(base, git("rev-parse", "HEAD"), root), "docs");
  git("mv", "java/important.java", "documentation.md");
  git("commit", "-m", "rename");
  assert.equal(plan(base, git("rev-parse", "HEAD"), root), "full");
  assert.equal(plan("a".repeat(40), "b".repeat(40), root), "full");
  assert.equal(plan("--bad", base, root), "full");
});
for (const mode of ["full", "frontend", "docs"]) {
  test(`${mode}: accepts only expected success/skip results`, () =>
    assert.equal(verify(results(mode)), mode));
  for (const name of names) {
    for (const outcome of ["failure", "cancelled", "missing", "unknown"]) {
      test(`${mode}: rejects ${name} ${outcome}`, () => {
        const needs = results(mode);
        if (outcome === "missing") delete needs[name];
        else needs[name].result = outcome;
        assert.throws(() => verify(needs));
      });
    }
    if (results(mode)[name].result === "success")
      test(`${mode}: required ${name} cannot be skipped`, () => {
        const needs = results(mode);
        needs[name].result = "skipped";
        assert.throws(() => verify(needs));
      });
  }
}
test("missing, failed, or malformed selection never authorizes skipped checks", () => {
  for (const changes of [
    undefined,
    { result: "skipped" },
    { result: "failure" },
    { result: "success", outputs: {} },
    { result: "success", outputs: { mode: "unknown" } },
  ]) {
    assert.throws(() => verify({ ...results("docs"), changes }));
  }
});

test("new or unknown jobs cannot be silently ignored by the gate", () => {
  assert.throws(() =>
    verify({ ...results("full"), extra: { result: "failure" } }),
  );
});
