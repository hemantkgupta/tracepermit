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
  echo "Usage: pnpm demo:scenario:cli [--auto]" >&2
  exit 2
fi
if [[ ! -f "$state_file" ]]; then
  echo "TracePermit live scenario: start the system with 'pnpm demo' first." >&2
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
  echo "TracePermit live scenario: unsafe or stale session state." >&2
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

before="$(curl --silent --show-error --fail "$control_plane_url/api/v1/overview")"
before_total="$(TRACEPERMIT_DEMO_RESPONSE="$before" node --input-type=module -e \
  'console.log(JSON.parse(process.env.TRACEPERMIT_DEMO_RESPONSE).total)')"
bearer_token="$(issue_token)"

echo
echo "TracePermit Live Scenario 1 — Attested CLI allow then block"
echo "============================================================"
echo "Console: http://127.0.0.1:5173"
echo "Target:  local AWS simulator only"
echo
echo "The same verified CLI identity will request a bounded read and a"
echo "destructive termination. Predict the decision before each result."
echo

produce_requests() {
  printf '%s\n' \
    '{"jsonrpc":"2.0","id":"live-init","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"tracepermit-live-demo","version":"1.0.0"}}}' \
    '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}' \
    '{"jsonrpc":"2.0","id":"live-tools","method":"tools/list","params":{}}' \
    '{"jsonrpc":"2.0","id":"live-read","method":"tools/call","params":{"name":"aws.logs.filter_log_events","arguments":{"logGroupName":"/app/staging","filterPattern":"ERROR","limit":2}}}'

  if [[ "$automatic" == true ]]; then
    sleep 1
  elif [[ -r /dev/tty ]]; then
    echo >&2
    echo "  The read should now appear as ALLOW in the console." >&2
    read -r -p "  Press Enter to request simulated instance termination... " </dev/tty
  else
    echo "TracePermit live scenario: no interactive terminal; use --auto." >&2
    return 2
  fi

  printf '%s\n' \
    '{"jsonrpc":"2.0","id":"live-block","method":"tools/call","params":{"name":"aws.ec2.terminate_instances","arguments":{"instanceIds":["i-simulated-live-demo"],"region":"us-east-1"}}}'
}

export TRACEPERMIT_CONNECTOR_BEARER_TOKEN="$bearer_token"
export TRACEPERMIT_GATEWAY_URL="$gateway_url"
TRACEPERMIT_ENDPOINT_ISSUER="$(read_state endpointIssuer)"
TRACEPERMIT_ENDPOINT_KEY_ID="$(read_state endpointKeyId)"
TRACEPERMIT_ENDPOINT_DEVICE_ID="$(read_state endpointDeviceId)"
export TRACEPERMIT_ENDPOINT_ISSUER TRACEPERMIT_ENDPOINT_KEY_ID TRACEPERMIT_ENDPOINT_DEVICE_ID
export TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH="$private_key"

produce_requests \
  | "$node_executable" "$repo_root/scripts/run-demo-endpoint-client.mjs" \
  | node "$repo_root/scripts/summarize-live-cli.mjs"

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
    const live = page.items.slice(0, added);
    const byTool = new Map(live.map(item => [item.target.toolName, item]));
    const read = byTool.get("aws.logs.filter_log_events");
    const block = byTool.get("aws.ec2.terminate_instances");
    const fail = message => {
      console.error(`  [FAIL] ${message}.`);
      process.exit(1);
    };
    if (added !== 2) fail(`Expected two evaluations; observed ${added}`);
    if (read?.trigger?.origin !== "cli" || read?.decision?.outcome !== "allow" || read?.executionStatus !== "succeeded") {
      fail("Read projection did not preserve CLI allow outcome");
    }
    if (block?.trigger?.origin !== "cli" || block?.decision?.outcome !== "block" || block?.executionStatus !== "not_executed") {
      fail("Destructive projection did not preserve CLI block outcome");
    }
    if (JSON.stringify(live).includes(process.env.TRACEPERMIT_DEMO_BEARER)) {
      fail("Bearer token leaked into the read projection");
    }
    console.log("");
    console.log("Live verification");
    console.log("  [PASS] Exactly two final policy evaluations reached the console");
    console.log("  [PASS] Both rows retain CLI origin and endpoint-attributed identity");
    console.log("  [PASS] ALLOW executed once; BLOCK produced no target dispatch");
    console.log("  [PASS] The bearer credential is absent from the read projection");
  '

echo
echo "Scenario complete. Use the console to explain identity, causal lineage,"
echo "risk classification, policy reason, execution status, and latency."
