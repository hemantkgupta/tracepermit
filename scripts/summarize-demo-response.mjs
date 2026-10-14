const label = process.env.TRACEPERMIT_DEMO_LABEL ?? "Request";
const raw = process.env.TRACEPERMIT_DEMO_RESPONSE;
const expected = process.env.TRACEPERMIT_DEMO_EXPECTED;
if (!raw) throw new Error("Missing bounded demo response.");
if (!["runtime", "allow", "block", "tool-not-found"].includes(expected)) {
  throw new Error("A known expected demo outcome is required.");
}
const response = JSON.parse(raw);

if (expected === "runtime") {
  if (
    response.backend !== "java" ||
    !["productVersion", "buildCommit", "javaVersion", "mode"].every(
      (field) =>
        typeof response[field] === "string" &&
        response[field].length > 0 &&
        response[field].length <= 128,
    )
  ) {
    throw new Error(`${label} did not report the expected Java runtime.`);
  }
  console.log(
    `  [PASS] ${label}: TracePermit ${response.productVersion} (${response.buildCommit}), ` +
      `${response.backend}, Java ${response.javaVersion}, ${response.mode}`,
  );
  process.exit(0);
}

if (expected === "tool-not-found") {
  const code = response.error?.data?.code ?? response.error?.code ?? "ERROR";
  if (code !== "TOOL_NOT_FOUND") {
    throw new Error(`${label} did not reject the unknown tool as expected.`);
  }
  console.log(`  [PASS] ${label} -> rejected at MCP boundary (${code})`);
  process.exit(0);
}

if (response.error)
  throw new Error(`${label} returned an unexpected MCP error.`);

const text = response.result?.content?.[0]?.text;
if (typeof text !== "string")
  throw new Error(`${label} returned an unexpected response.`);
const content = JSON.parse(text);
const outcome = content.evaluation?.decision?.outcome;
const status = content.execution?.status;
const expectedStatus = expected === "allow" ? "executed" : "denied";
if (
  content.simulated !== true ||
  outcome !== expected ||
  status !== expectedStatus
) {
  throw new Error(
    `${label} did not return the expected simulated enforcement result.`,
  );
}
console.log(
  `  [PASS] ${label} -> ${outcome.toUpperCase()}, execution=${status}`,
);
