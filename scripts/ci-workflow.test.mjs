import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const workflow = readFileSync(
  new URL("../.github/workflows/ci.yml", import.meta.url),
  "utf8",
);
const jobSource = workflow.split("\njobs:\n")[1];
const headings = [...jobSource.matchAll(/^  ([\w-]+):$/gm)];
const jobs = new Map(
  headings.map((heading, index) => [
    heading[1],
    jobSource.slice(heading.index, headings[index + 1]?.index),
  ]),
);
const gate = jobs.get("verify");
const required = [...gate.matchAll(/^      - ([\w-]+)$/gm)].map(
  (match) => match[1],
);
const runBlock = gate.match(/        run: \|\n([\s\S]+)/)[1];
const command = runBlock.replace(/^ {10}/gm, "");

function runGate(results) {
  const result = spawnSync(
    "/bin/bash",
    ["-e", "-o", "pipefail", "-c", command],
    {
      encoding: "utf8",
      env: { PATH: process.env.PATH, NEEDS_JSON: JSON.stringify(results) },
      timeout: 5000,
    },
  );
  assert.ifError(result.error);
  return result;
}

function successfulResults() {
  return Object.fromEntries(
    required.map((job) => [job, { result: "success" }]),
  );
}

test("verify always evaluates every independent CI job without tolerating errors", () => {
  assert.match(gate, /if: \$\{\{ always\(\) \}\}/);
  assert.match(gate, /NEEDS_JSON: \$\{\{ toJSON\(needs\) \}\}/);
  assert.deepEqual(
    [...required].sort(),
    [...jobs.keys()].filter((job) => job !== "verify").sort(),
  );
  assert.equal(required.length, 6);
  assert.doesNotMatch(workflow, /continue-on-error:/);
  for (const job of required) {
    assert.doesNotMatch(jobs.get(job), /^    (?:if|needs):/m);
  }
});

test("verify succeeds only after all required jobs succeed", () => {
  assert.equal(runGate(successfulResults()).status, 0);
});

test("core retains exactly one aggregate check followed by the forced PostgreSQL pass", () => {
  assert.equal([...workflow.matchAll(/run: pnpm check$/gm)].length, 1);
  assert.equal(
    [...workflow.matchAll(/run: pnpm test:java-postgres --rerun-tasks$/gm)]
      .length,
    1,
  );
  assert.match(
    jobs.get("core"),
    /run: pnpm check[\s\S]*run: pnpm test:java-postgres --rerun-tasks/,
  );
});

test("core retains forced MariaDB qualification exactly once after PostgreSQL", () => {
  assert.equal(
    [...workflow.matchAll(/run: pnpm test:java-mariadb --rerun-tasks$/gm)]
      .length,
    1,
  );
  assert.match(
    jobs.get("core"),
    /run: pnpm check[\s\S]*run: pnpm test:java-postgres --rerun-tasks[\s\S]*run: pnpm test:java-mariadb --rerun-tasks/,
  );
});

test("parallel scheduling preserves every current verification command and the workflow tests", () => {
  const commands = workflow
    .split("\n")
    .map((line) => line.trim().replace(/^run: /, ""));
  const expected = [
    ["pnpm check", 1],
    ["pnpm test:java-postgres --rerun-tasks", 1],
    ["pnpm test:java-mariadb --rerun-tasks", 1],
    ["pnpm test:endpoint-launcher", 1],
    ["npm --prefix site ci", 2],
    ["npm --prefix site audit --audit-level=low", 1],
    ["pnpm site:build", 2],
    ["node --test scripts/site-search-compatibility.test.mjs", 1],
    ["node --test scripts/site-verification.test.mjs", 1],
    ["node --test scripts/intro-visuals.test.mjs", 1],
    ["node --test scripts/security-domain-guide.test.mjs", 1],
    ["pnpm test:gate-c", 1],
    ["pnpm demo:gate-c", 1],
    ["pnpm demo:verify", 1],
    ["pnpm build:distribution", 1],
    ["pnpm test:distribution", 1],
    ["pnpm build:container-profile", 1],
    ["pnpm test:container-profile", 1],
    ["pnpm build:endpoint-distribution", 1],
    ["pnpm test:endpoint-distribution", 1],
    ["pnpm format:check", 1],
    ["pnpm docs:links", 1],
    ["pnpm docs:flows:check", 1],
    [
      'TRACEPERMIT_GRADLE_USER_HOME="$HOME/.gradle" bash scripts/run-java-operation-tests.sh --rerun-tasks',
      1,
    ],
    ["pnpm exec playwright install --with-deps chromium", 1],
    ["pnpm test:approvals:browser", 1],
    ["pnpm test:approval-export:browser", 1],
    ["node --test scripts/site-onboarding-browser.test.mjs", 1],
    ["node --test scripts/site-verification-browser.test.mjs", 1],
    ["node --test scripts/ci-workflow.test.mjs", 1],
  ];
  for (const [command, count] of expected) {
    assert.equal(
      commands.filter((line) => line === command).length,
      count,
      command,
    );
  }
});

for (const job of required) {
  for (const outcome of [
    "failure",
    "cancelled",
    "skipped",
    "missing",
    "unknown",
  ]) {
    test(`verify rejects ${job} when its result is ${outcome}`, () => {
      const results = successfulResults();
      if (outcome === "missing") {
        delete results[job];
      } else {
        results[job].result = outcome;
      }
      const result = runGate(results);
      assert.equal(result.status, 1);
      assert.match(result.stderr, new RegExp(job));
    });
  }
}

test("verify rejects empty or malformed job evidence", () => {
  for (const results of [{}, null, [], "invalid"]) {
    assert.equal(runGate(results).status, 1);
  }
});

test("only newer runs for the same PR can cancel an in-progress run", () => {
  assert.match(
    workflow,
    /group: \$\{\{ github.workflow \}\}-\$\{\{ github.event_name \}\}-\$\{\{ github.event.pull_request.number \|\| github.run_id \}\}/,
  );
  assert.match(
    workflow,
    /cancel-in-progress: \$\{\{ github.event_name == 'pull_request' \}\}/,
  );
});
