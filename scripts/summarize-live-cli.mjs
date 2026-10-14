import readline from "node:readline";

const seen = new Set();
const input = readline.createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
});

for await (const line of input) {
  if (!line.trim().startsWith("{")) continue;
  const response = JSON.parse(line);
  const id = String(response.id ?? "");

  if (id === "live-init") {
    if (response?.result?.serverInfo?.name !== "TracePermit") {
      fail("connector initialization");
    }
    seen.add(id);
    console.log(
      "  [PASS] Java stdio connector initialized against TracePermit",
    );
  } else if (id === "live-tools") {
    if (response?.result?.tools?.length !== 2) fail("bounded tool discovery");
    seen.add(id);
    console.log("  [PASS] MCP discovery exposed exactly two simulated tools");
  } else if (id === "live-read") {
    const result = content(response);
    if (
      result?.evaluation?.decision?.outcome !== "allow" ||
      result?.execution?.status !== "executed"
    ) {
      fail("attested bounded read");
    }
    seen.add(id);
    console.log("  [ALLOW] Bounded log read reached only the local simulator");
  } else if (id === "live-block") {
    const result = content(response);
    if (
      result?.evaluation?.decision?.outcome !== "block" ||
      result?.execution?.status !== "denied"
    ) {
      fail("attested destructive denial");
    }
    seen.add(id);
    console.log(
      "  [BLOCK] Simulated instance termination stopped before dispatch",
    );
  }
}

for (const expected of ["live-init", "live-tools", "live-read", "live-block"]) {
  if (!seen.has(expected)) fail(`missing ${expected} response`);
}

function content(response) {
  const text = response?.result?.content?.[0]?.text;
  return typeof text === "string" ? JSON.parse(text) : null;
}

function fail(stage) {
  console.error(`  [FAIL] Live CLI scenario failed at ${stage}.`);
  process.exit(1);
}
