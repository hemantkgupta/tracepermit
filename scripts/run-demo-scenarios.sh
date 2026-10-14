#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
state_file="$repo_root/.cache/demo/session.json"
grant_type="urn:tracepermit:params:oauth:grant-type:development-profile"

if [[ ! -f "$state_file" ]]; then
  echo "TracePermit demo: no running session. Start it with 'pnpm demo'." >&2
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

if [[ "$gateway_url" != "http://127.0.0.1:8080/mcp" \
  || "$token_url" != "http://127.0.0.1:8080/dev/oauth/token" \
  || "$control_plane_url" != "http://127.0.0.1:8090" \
  || "$private_key" != "$repo_root/.cache/demo/endpoint-key.pk8" \
  || ! -f "$private_key" ]]; then
  echo "TracePermit demo: unsafe or stale session state." >&2
  exit 2
fi

issue_token() {
  local profile="$1"
  curl --silent --show-error --fail \
    --request POST \
    --header 'content-type: application/json' \
    --data "{\"grant_type\":\"$grant_type\",\"profile\":\"$profile\"}" \
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

web_call() {
  local label="$1"
  local expected="$2"
  local request="$3"
  local response
  response="$(
    curl --silent --show-error --fail \
      --request POST \
      --header 'content-type: application/json' \
      --header "authorization: Bearer $web_token" \
      --data "$request" \
      "$gateway_url"
  )"
  TRACEPERMIT_DEMO_LABEL="$label" TRACEPERMIT_DEMO_EXPECTED="$expected" TRACEPERMIT_DEMO_RESPONSE="$response" \
    node "$repo_root/scripts/summarize-demo-response.mjs"
}

echo
echo "TracePermit Demo++ — Java-only AVC enforcement"
echo "================================================"
system_info="$(curl --silent --show-error --fail "$control_plane_url/api/v1/system-info")"
TRACEPERMIT_DEMO_RESPONSE="$system_info" TRACEPERMIT_DEMO_LABEL="Runtime" TRACEPERMIT_DEMO_EXPECTED="runtime" \
  node "$repo_root/scripts/summarize-demo-response.mjs"

before="$(curl --silent --show-error --fail "$control_plane_url/api/v1/overview")"
before_total="$(TRACEPERMIT_DEMO_RESPONSE="$before" node --input-type=module -e 'console.log(JSON.parse(process.env.TRACEPERMIT_DEMO_RESPONSE).total)')"

echo
echo "1. Web-profile requests"
web_token="$(issue_token chatgpt-readonly)"
web_call "Bounded log read" "allow" \
  '{"jsonrpc":"2.0","id":101,"method":"tools/call","params":{"name":"aws.logs.filter_log_events","arguments":{"logGroupName":"/app/staging","filterPattern":"WARN","limit":2}}}'
web_call "Destructive termination" "block" \
  '{"jsonrpc":"2.0","id":102,"method":"tools/call","params":{"name":"aws.ec2.terminate_instances","arguments":{"instanceIds":["i-simulated-demo"],"region":"us-east-1"}}}'
web_call "Unknown tool" "tool-not-found" \
  '{"jsonrpc":"2.0","id":103,"method":"tools/call","params":{"name":"aws.ec2.unknown_operation","arguments":{}}}'

unauthenticated_status="$(
  curl --silent --output /dev/null --write-out '%{http_code}' \
    --request POST \
    --header 'content-type: application/json' \
    --data 'not-json' \
    "$gateway_url"
)"
if [[ "$unauthenticated_status" != "401" ]]; then
  echo "  [FAIL] Authentication-before-parsing returned HTTP $unauthenticated_status" >&2
  exit 1
fi
echo "  [PASS] Authentication before parsing -> HTTP 401, not executed"

echo
echo "2. CLI endpoint-attestation requests"
TRACEPERMIT_CONNECTOR_BEARER_TOKEN="$(issue_token cli-readonly)"
export TRACEPERMIT_CONNECTOR_BEARER_TOKEN
export TRACEPERMIT_GATEWAY_URL="$gateway_url"
TRACEPERMIT_ENDPOINT_ISSUER="$(read_state endpointIssuer)"
TRACEPERMIT_ENDPOINT_KEY_ID="$(read_state endpointKeyId)"
TRACEPERMIT_ENDPOINT_DEVICE_ID="$(read_state endpointDeviceId)"
export TRACEPERMIT_ENDPOINT_ISSUER
export TRACEPERMIT_ENDPOINT_KEY_ID
export TRACEPERMIT_ENDPOINT_DEVICE_ID
export TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH="$private_key"

"$node_executable" "$repo_root/scripts/run-demo-endpoint-client.mjs" <<'EOF' \
  | node "$repo_root/scripts/summarize-demo-mcp.mjs"
{"jsonrpc":"2.0","id":"cli-init","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"tracepermit-demo","version":"1.0.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
{"jsonrpc":"2.0","id":201,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":202,"method":"tools/call","params":{"name":"aws.logs.filter_log_events","arguments":{"logGroupName":"/app/staging","filterPattern":"ERROR","limit":2}}}
{"jsonrpc":"2.0","id":203,"method":"tools/call","params":{"name":"aws.ec2.terminate_instances","arguments":{"instanceIds":["i-simulated-cli-demo"],"region":"us-east-1"}}}
EOF

unset TRACEPERMIT_CONNECTOR_BEARER_TOKEN

after="$(curl --silent --show-error --fail "$control_plane_url/api/v1/overview")"
actions="$(curl --silent --show-error --fail "$control_plane_url/api/v1/actions?limit=50")"
TRACEPERMIT_DEMO_BEFORE="$before_total" \
TRACEPERMIT_DEMO_OVERVIEW="$after" \
TRACEPERMIT_DEMO_ACTIONS="$actions" \
  node --input-type=module -e '
    const before = Number(process.env.TRACEPERMIT_DEMO_BEFORE);
    const overview = JSON.parse(process.env.TRACEPERMIT_DEMO_OVERVIEW);
    const page = JSON.parse(process.env.TRACEPERMIT_DEMO_ACTIONS);
    const added = overview.total - before;
    const live = page.items.slice(0, added);
    const latencies = live.map(item => item.latencyMs).sort((a, b) => a - b);
    const p50 = latencies.length === 0 ? 0 : latencies[Math.floor((latencies.length - 1) * 0.5)];
    const p95 = latencies.length === 0 ? 0 : latencies[Math.ceil(latencies.length * 0.95) - 1];
    if (added !== 4) {
      console.error(`  [FAIL] Expected four live policy evaluations; observed ${added}.`);
      process.exit(1);
    }
    console.log("");
    console.log("3. Live Java read projection");
    console.log(`  [PASS] Four evaluated actions appended; rejected boundary calls were not projected`);
    console.log(`  [PASS] Current totals: ${overview.allowed} allowed, ${overview.blocked} blocked, ${overview.approvalRequired} approval`);
    console.log(`  [INFO] Enforcement latency sample: p50=${p50} ms, p95=${p95} ms`);
    console.log("  [PASS] Open http://127.0.0.1:5173 — the console refreshes within two seconds");
  '

echo
echo "Demo complete: every target effect was simulated and evaluated before execution."
