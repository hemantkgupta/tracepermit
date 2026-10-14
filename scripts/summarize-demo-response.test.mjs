import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(
  new URL("./summarize-demo-response.mjs", import.meta.url),
);

function summarize(expected, response) {
  return spawnSync(process.execPath, [script], {
    encoding: "utf8",
    env: {
      ...process.env,
      TRACEPERMIT_DEMO_LABEL: "Fixture",
      TRACEPERMIT_DEMO_EXPECTED: expected,
      TRACEPERMIT_DEMO_RESPONSE: JSON.stringify(response),
    },
  });
}

function toolResponse(outcome, status) {
  return {
    result: {
      content: [
        {
          text: JSON.stringify({
            simulated: true,
            evaluation: { decision: { outcome } },
            execution: { status },
          }),
        },
      ],
    },
  };
}

test("accepts only the expected simulated allow and block outcomes", () => {
  assert.equal(summarize("allow", toolResponse("allow", "executed")).status, 0);
  assert.equal(summarize("block", toolResponse("block", "denied")).status, 0);
  assert.notEqual(
    summarize("allow", toolResponse("block", "denied")).status,
    0,
  );
  assert.notEqual(
    summarize("block", toolResponse("allow", "executed")).status,
    0,
  );
  assert.notEqual(
    summarize("allow", toolResponse("allow", "not_executed")).status,
    0,
  );
  assert.notEqual(
    summarize("block", toolResponse("block", "not_executed")).status,
    0,
  );
  assert.notEqual(
    summarize("allow", { error: { data: { code: "TOOL_NOT_FOUND" } } }).status,
    0,
  );
});

test("unknown tool passes only for the exact expected error", () => {
  assert.equal(
    summarize("tool-not-found", { error: { data: { code: "TOOL_NOT_FOUND" } } })
      .status,
    0,
  );
  assert.notEqual(
    summarize("tool-not-found", {
      error: { data: { code: "INVALID_ARGUMENT" } },
    }).status,
    0,
  );
});

test("runtime summary rejects a marker without bounded build fields", () => {
  assert.notEqual(summarize("runtime", { backend: "java" }).status, 0);
  assert.equal(
    summarize("runtime", {
      backend: "java",
      productVersion: "0.1.0-demo",
      buildCommit: "fixture",
      javaVersion: "21",
      mode: "simulated-development",
    }).status,
    0,
  );
});
