import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
const workflow = readFileSync(
  new URL("../.github/workflows/ci.yml", import.meta.url),
  "utf8",
);
const java = readFileSync(
  new URL("../java/build.gradle.kts", import.meta.url),
  "utf8",
);
const headings = [...workflow.matchAll(/^  ([\w-]+):$/gm)].filter(
  (x) => x.index > workflow.indexOf("\njobs:\n"),
);
const jobs = new Map(
  headings.map((h, i) => [
    h[1],
    workflow.slice(h.index, headings[i + 1]?.index),
  ]),
);
test("selection uses complete local Git history and the base policy, never labels or PR prose", () => {
  const changes = jobs.get("changes");
  assert.match(changes, /fetch-depth: 0/);
  assert.match(changes, /git show "\$BASE_SHA:scripts\/ci-policy.mjs"/);
  assert.match(changes, /echo 'mode=full'/);
  assert.doesNotMatch(
    workflow,
    /pull_request_target|paths-ignore:|continue-on-error:/,
  );
});
test("final required gate evaluates selection and every actual job even after failure or skip", () => {
  const gate = jobs.get("verify");
  assert.match(gate, /always\(\)/);
  assert.match(gate, /toJSON\(needs\)/);
  const required = [...gate.matchAll(/^      - ([\w-]+)$/gm)].map((x) => x[1]);
  assert.deepEqual(
    required.sort(),
    [...jobs.keys()].filter((x) => x !== "verify").sort(),
  );
  assert.match(gate, /node scripts\/ci-policy.mjs verify/);
});
test("database shards cannot be restored as cached successes, and coverage retains both thresholds", () => {
  assert.match(java, /outputs\.upToDateWhen \{ false \}/);
  assert.match(java, /outputs\.cacheIf \{ false \}/);
  assert.match(java, /minimum = "0.94"/);
  assert.match(java, /minimum = "0.75"/);
  const coverage = jobs.get("coverage");
  for (const name of ["core", "databases", "operation-repository"])
    assert.match(coverage, new RegExp(`      - ${name}`));
  assert.match(coverage, /bash scripts\/run-java-ci.sh coverage/);
  assert.match(coverage, /merge-multiple: true/);
});
test("every current operation engine is selected once, with all other operation tests in core", () => {
  assert.match(
    java,
    /"core" -> exclude\("\*\*\/Postgres\*Test.class", "\*\*\/MariaDb106\*Test.class", "\*\*\/MariaDb114\*Test.class"\)/,
  );
  for (const engine of ["postgres", "mariadb106", "mariadb114"])
    assert.equal(
      [
        ...jobs
          .get("operation-repository")
          .matchAll(new RegExp(`- operations-${engine}$`, "gm")),
      ].length,
      1,
    );
  assert.match(jobs.get("databases"), /- postgres\n\s+- mariadb/);
  assert.doesNotMatch(workflow, /pnpm check|--rerun-tasks/);
});
test("action versions stay immutable and duplicate Gradle cache managers are removed", () => {
  for (const match of workflow.matchAll(/uses: (.+)/g))
    assert.match(match[1], /@[a-f0-9]{40}(?:\s|$)/);
  assert.doesNotMatch(workflow, /cache: gradle/);
  assert.match(workflow, /gradle\/actions\/setup-gradle@/);
});
test("new runs cancel only the same PR; main pushes remain independent", () => {
  assert.match(workflow, /github.event.pull_request.number \|\| github.run_id/);
  assert.match(
    workflow,
    /cancel-in-progress: \$\{\{ github.event_name == 'pull_request' \}\}/,
  );
});
test("all packaging, demo, browser, documentation and audit commands remain scheduled", () => {
  for (const cmd of [
    "pnpm build:distribution",
    "pnpm test:distribution",
    "pnpm build:container-profile",
    "pnpm test:container-profile",
    "pnpm build:endpoint-distribution",
    "pnpm test:endpoint-distribution",
    "bash scripts/build-pilot-operator-distribution.sh --max-workers=2",
    "bash scripts/test-pilot-operator-distribution.sh",
    "pnpm test:endpoint-launcher",
    "pnpm test:gate-c",
    "pnpm demo:gate-c",
    "pnpm demo:verify",
    "pnpm test:approvals:browser",
    "pnpm test:approval-export:browser",
    "npm --prefix site audit --audit-level=low",
    "pnpm docs:links",
    "pnpm docs:flows:check",
  ])
    assert.ok(workflow.includes(cmd), cmd);
});
