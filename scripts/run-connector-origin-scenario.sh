#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
state_file="$repo_root/.cache/demo/session.json"
grant_type="urn:tracepermit:params:oauth:grant-type:development-profile"
automatic=false

if [[ "${1:-}" == "--auto" ]]; then
  automatic=true
  shift
fi
if [[ "$#" -ne 0 ]]; then
  echo "Usage: pnpm demo:scenario:connector [--auto]" >&2
  exit 2
fi
if [[ ! -f "$state_file" ]]; then
  echo "TracePermit connector scenario: start the system with 'pnpm demo' first." >&2
  exit 2
fi
if ! tty -s; then
  echo "TracePermit connector scenario: the CLI profile requires an attached terminal." >&2
  echo "Run this command from an interactive Terminal; --auto skips pauses only." >&2
  exit 2
fi

read_state() {
  TRACEPERMIT_DEMO_STATE_FILE="$state_file" node --input-type=module -e '
    import { readFileSync } from "node:fs";
    const state = JSON.parse(readFileSync(process.env.TRACEPERMIT_DEMO_STATE_FILE, "utf8"));
    const value = state[process.argv[1]];
    if (typeof value !== "string" || value.length === 0 || /[\r\n]/u.test(value)) process.exit(2);
    process.stdout.write(value);
  ' "$1"
}

gateway_url="$(read_state gatewayUrl)"
token_url="$(read_state tokenUrl)"
control_plane_url="$(read_state controlPlaneUrl)"
private_key="$(read_state endpointPrivateKeyPath)"
node_executable="$(realpath "$(command -v node)")"

endpoint_issuer="$(read_state endpointIssuer)"
endpoint_key_id="$(read_state endpointKeyId)"
endpoint_device_id="$(read_state endpointDeviceId)"

if [[ "$gateway_url" != "http://127.0.0.1:8080/mcp" \
  || "$token_url" != "http://127.0.0.1:8080/dev/oauth/token" \
  || "$control_plane_url" != "http://127.0.0.1:8090" \
  || "$private_key" != "$repo_root/.cache/demo/endpoint-key.pk8" \
  || ! -f "$private_key" ]]; then
  echo "TracePermit connector scenario: unsafe or stale session state." >&2
  exit 2
fi

issue_token() {
  curl --silent --show-error --fail \
    --request POST \
    --header 'content-type: application/json' \
    --data "{\"grant_type\":\"$grant_type\",\"profile\":\"cli-readonly\"}" \
    "$token_url" |
    node --input-type=module -e '
      let body = "";
      process.stdin.setEncoding("utf8");
      process.stdin.on("data", chunk => body += chunk);
      process.stdin.on("end", () => {
        const token = JSON.parse(body).access_token;
        if (typeof token !== "string" || token.length === 0) process.exit(2);
        process.stdout.write(token);
      });
    '
}

tool_call='{"jsonrpc":"2.0","id":"connector-read","method":"tools/call","params":{"name":"aws.logs.filter_log_events","arguments":{"logGroupName":"/app/staging","filterPattern":"ERROR","limit":2}}}'
before="$(curl --silent --show-error --fail "$control_plane_url/api/v1/overview")"
before_total="$(TRACEPERMIT_DEMO_RESPONSE="$before" node --input-type=module -e \
  'console.log(JSON.parse(process.env.TRACEPERMIT_DEMO_RESPONSE).total)')"
bearer_token="$(issue_token)"

echo
echo "TracePermit Live Scenario 0 — Endpoint connector trust construction"
echo "===================================================================="
echo "This scenario shows what the client proposes, what the connector derives,"
echo "and the secret-safe shape sent to the enforcement server."
echo
echo "1. Client / agent -> connector (MCP stdio proposal)"
echo "   The client supplies only the JSON-RPC method, tool name, and arguments:"
TRACEPERMIT_DEMO_TOOL_CALL="$tool_call" node --input-type=module -e \
  'console.log(JSON.stringify(JSON.parse(process.env.TRACEPERMIT_DEMO_TOOL_CALL), null, 2).split("\n").map(line => `   ${line}`).join("\n"))'
echo
echo "2. Pre-server endpoint trust construction (before network egress)"
echo "   When you continue, the real Java connector performs these operations:"
echo "   A. Strictly validates the bounded MCP frame and supported method."
echo "   B. Uses OS process APIs to observe its parent executable, start time,"
echo "      executable hash, device/platform, and terminal attachment."
echo "   C. Canonicalizes the exact MCP method/params and derives request and"
echo "      connector-credential bindings without logging their values."
echo "   D. Signs a short-lived Ed25519 source attestation with the local endpoint key."
echo "   E. Re-observes the process and fails closed if it changed before forwarding."
echo
echo "   This is the connector's operation plan, not a synthetic attestation."
echo "   No measured values are fabricated in this preview. Step 4 is printed only"
echo "   after the real connector runs and Java verifies the proof server-side."
echo
echo "3. Connector -> enforcement server (presenter-safe wire view)"
echo "   POST $gateway_url"
echo "   Authorization: Bearer [REDACTED]"
echo "   tracepermit-source-attestation: [COMPACT JWS REDACTED]"
echo "   Content-Type: application/json"
echo "   Body: the exact MCP request shown in Step 1"
echo
echo "   The real connector will add the compact source proof after Step 2."
echo "   The bearer value, credential fingerprint, request hash, private key,"
echo "   compact proof, and replay token ID remain redacted."

if [[ "$automatic" != true ]]; then
  if [[ ! -r /dev/tty ]]; then
    echo "TracePermit connector scenario: no interactive terminal; use --auto." >&2
    exit 2
  fi
  echo
  read -r -p "   Press Enter to execute these stages in the real Java connector... " </dev/tty
fi

export TRACEPERMIT_CONNECTOR_BEARER_TOKEN="$bearer_token"
export TRACEPERMIT_GATEWAY_URL="$gateway_url"
export TRACEPERMIT_ENDPOINT_ISSUER="$endpoint_issuer"
export TRACEPERMIT_ENDPOINT_KEY_ID="$endpoint_key_id"
export TRACEPERMIT_ENDPOINT_DEVICE_ID="$endpoint_device_id"
export TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH="$private_key"

printf '%s\n' \
  '{"jsonrpc":"2.0","id":"connector-init","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"tracepermit-connector-demo","version":"1.0.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}' \
  '{"jsonrpc":"2.0","id":"connector-tools","method":"tools/list","params":{}}' \
  "$tool_call" \
  | "$node_executable" "$repo_root/scripts/run-demo-endpoint-client.mjs" \
  | node "$repo_root/scripts/summarize-connector-origin.mjs"

unset TRACEPERMIT_CONNECTOR_BEARER_TOKEN

after="$(curl --silent --show-error --fail "$control_plane_url/api/v1/overview")"
actions="$(curl --silent --show-error --fail "$control_plane_url/api/v1/actions?limit=10")"
TRACEPERMIT_DEMO_BEFORE="$before_total" \
TRACEPERMIT_DEMO_OVERVIEW="$after" \
TRACEPERMIT_DEMO_ACTIONS="$actions" \
TRACEPERMIT_DEMO_BEARER="$bearer_token" \
  node --input-type=module -e '
    const before = Number(process.env.TRACEPERMIT_DEMO_BEFORE);
    const overview = JSON.parse(process.env.TRACEPERMIT_DEMO_OVERVIEW);
    const page = JSON.parse(process.env.TRACEPERMIT_DEMO_ACTIONS);
    const added = overview.total - before;
    const action = page.items[0];
    const fail = message => {
      console.error(`  [FAIL] ${message}.`);
      process.exit(1);
    };
    if (added !== 1) fail(`Expected one policy evaluation; observed ${added}`);
    if (action?.trigger?.origin !== "cli" || action?.decision?.outcome !== "allow" || action?.executionStatus !== "succeeded") {
      fail("Trusted projection did not preserve the CLI allow result");
    }
    if (action?.lineage?.length !== 3 || action?.identity?.actor?.assurance !== "authenticated") {
      fail("Verified endpoint process was not preserved in causal lineage");
    }
    if (JSON.stringify(action).includes(process.env.TRACEPERMIT_DEMO_BEARER)) {
      fail("Bearer token leaked into the read projection");
    }
    console.log("");
    console.log("4. Gateway-verified, secret-minimized result");
    console.log(`   Human subject: ${action.identity.subject.id} (${action.identity.subject.assurance})`);
    console.log(`   Endpoint actor: ${action.identity.actor.id} (${action.identity.actor.assurance})`);
    console.log(`   MCP client:     ${action.trigger.clientName} (${action.trigger.origin})`);
    console.log(`   Lineage hops:   ${action.lineage.map(hop => hop.kind).join(" -> ")}`);
    console.log(`   Decision:       ${action.decision.outcome.toUpperCase()} / ${action.executionStatus}`);
    console.log("");
    console.log("   [PASS] One real connector request became one final console projection");
    console.log("   [PASS] Endpoint actor evidence is authenticated; MCP client metadata remains distinct");
    console.log("   [PASS] No bearer credential appears in the projection");
  '

echo
echo "Connector scenario complete. Open the CLI row in the React console and"
echo "explain that source attestation establishes provenance, not action safety."
