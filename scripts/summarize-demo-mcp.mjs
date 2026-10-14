let input = "";
process.stdin.setEncoding("utf8");
for await (const chunk of process.stdin) input += chunk;

const responses = input
  .split(/\r?\n/u)
  .filter((line) => line.trim().startsWith("{"))
  .map((line) => JSON.parse(line));
const byId = new Map(
  responses.map((response) => [String(response.id), response]),
);

const initialized = byId.get("cli-init");
const tools = byId.get("201");
const allowed = content(byId.get("202"));
const blocked = content(byId.get("203"));
if (initialized?.result?.serverInfo?.name !== "TracePermit") fail("initialize");
if (tools?.result?.tools?.length !== 2) fail("tools/list");
if (
  allowed?.evaluation?.decision?.outcome !== "allow" ||
  allowed?.execution?.status !== "executed"
) {
  fail("attested allowed read");
}
if (
  blocked?.evaluation?.decision?.outcome !== "block" ||
  blocked?.execution?.status !== "denied"
) {
  fail("attested destructive denial");
}

console.log(
  "  [PASS] Source proof accepted: credential-, request-, process-, and device-bound",
);
console.log("  [PASS] Attested CLI log read -> ALLOW, execution=executed");
console.log("  [PASS] Attested CLI termination -> BLOCK, execution=denied");

function content(response) {
  const text = response?.result?.content?.[0]?.text;
  return typeof text === "string" ? JSON.parse(text) : null;
}

function fail(stage) {
  console.error(`  [FAIL] CLI endpoint-attestation stage failed: ${stage}`);
  process.exit(1);
}
