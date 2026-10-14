import readline from "node:readline";

const expected = new Set([
  "connector-init",
  "connector-tools",
  "connector-read",
]);
const seen = new Set();
const input = readline.createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
});

for await (const line of input) {
  if (!line.trim().startsWith("{")) continue;
  const response = JSON.parse(line);
  const id = String(response.id ?? "");
  if (!expected.has(id)) continue;

  if (id === "connector-init") {
    if (response?.result?.serverInfo?.name !== "TracePermit") {
      fail("connector initialization");
    }
    console.log("  [PASS] MCP initialization crossed the Java connector");
  } else if (id === "connector-tools") {
    if (response?.result?.tools?.length !== 2) fail("bounded tool discovery");
    console.log("  [PASS] Gateway returned exactly two simulated tools");
  } else {
    const result = toolContent(response);
    if (
      result?.evaluation?.decision?.outcome !== "allow" ||
      result?.execution?.status !== "executed"
    ) {
      fail("endpoint-attested bounded read");
    }
    console.log(
      "  [PASS] Server accepted the endpoint proof and allowed the read",
    );
  }
  seen.add(id);
}

for (const id of expected) {
  if (!seen.has(id)) fail(`missing ${id} response`);
}

function toolContent(response) {
  const text = response?.result?.content?.[0]?.text;
  return typeof text === "string" ? JSON.parse(text) : null;
}

function fail(stage) {
  console.error(`  [FAIL] Connector-origin scenario failed at ${stage}.`);
  process.exit(1);
}
