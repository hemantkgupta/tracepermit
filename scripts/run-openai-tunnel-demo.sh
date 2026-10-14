#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tunnel_client="${TRACEPERMIT_TUNNEL_CLIENT_BIN:-$(command -v tunnel-client || true)}"
tunnel_id="${TRACEPERMIT_OPENAI_TUNNEL_ID:-}"
organization_id="${TRACEPERMIT_OPENAI_ORGANIZATION_ID:-}"
api_key_file="${TRACEPERMIT_OPENAI_TUNNEL_API_KEY_FILE:-}"
mcp_port="${TRACEPERMIT_JAVA_MCP_PORT:-8080}"
api_port="${TRACEPERMIT_JAVA_API_PORT:-8090}"
runtime_dir=""
tunnel_pid=""

fail() {
  echo "TracePermit OpenAI tunnel demo: $1" >&2
  exit 2
}

cleanup() {
  trap - EXIT HUP INT TERM
  if [[ -n "$tunnel_pid" ]] && kill -0 "$tunnel_pid" >/dev/null 2>&1; then
    kill -TERM "$tunnel_pid" >/dev/null 2>&1 || true
    wait "$tunnel_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$runtime_dir" && "$runtime_dir" == "${TMPDIR:-/tmp}"/tracepermit-openai-tunnel.* ]]; then
    [[ -f "$runtime_dir/mcp-authorization" ]] && rm -f -- "$runtime_dir/mcp-authorization"
    [[ -f "$runtime_dir/health-url" ]] && rm -f -- "$runtime_dir/health-url"
    rmdir "$runtime_dir" >/dev/null 2>&1 || true
  fi
  echo "TracePermit OpenAI tunnel demo: stopped; the temporary MCP credential was removed."
}

trap cleanup EXIT HUP INT TERM

[[ -n "$tunnel_client" && -x "$tunnel_client" ]] || fail "tunnel-client is not installed or executable."
[[ "$tunnel_id" =~ ^tunnel_[0-9a-f]{32}$ ]] || fail "TRACEPERMIT_OPENAI_TUNNEL_ID is missing or invalid."
if [[ -n "$organization_id" && ! "$organization_id" =~ ^org-[A-Za-z0-9]{8,128}$ ]]; then
  fail "TRACEPERMIT_OPENAI_ORGANIZATION_ID is invalid."
fi
[[ -n "$api_key_file" && -f "$api_key_file" && ! -L "$api_key_file" ]] ||
  fail "TRACEPERMIT_OPENAI_TUNNEL_API_KEY_FILE must name a regular, non-symlink file."
api_key_mode="$(stat -f '%Lp' "$api_key_file" 2>/dev/null || stat -c '%a' "$api_key_file" 2>/dev/null || true)"
if [[ "$api_key_mode" != "400" && "$api_key_mode" != "600" ]]; then
  fail "TRACEPERMIT_OPENAI_TUNNEL_API_KEY_FILE must be readable only by its owner (mode 400 or 600)."
fi
[[ "$mcp_port" =~ ^[0-9]{1,5}$ && "$mcp_port" -ge 1 && "$mcp_port" -le 65535 ]] ||
  fail "TRACEPERMIT_JAVA_MCP_PORT must be a valid TCP port."
[[ "$api_port" =~ ^[0-9]{1,5}$ && "$api_port" -ge 1 && "$api_port" -le 65535 ]] ||
  fail "TRACEPERMIT_JAVA_API_PORT must be a valid TCP port."

for command in curl node; do
  command -v "$command" >/dev/null 2>&1 || fail "missing required command: $command"
done

if ! curl --silent --show-error --fail --max-time 2 \
  "http://127.0.0.1:${mcp_port}/ready" >/dev/null; then
  fail "the Java MCP backend is not ready on loopback port ${mcp_port}; run 'pnpm demo' first."
fi

if ! curl --silent --show-error --fail --max-time 2 \
  "http://127.0.0.1:${api_port}/api/v1/system-info" |
  node --input-type=module -e '
    let input = "";
    for await (const chunk of process.stdin) input += chunk;
    const info = JSON.parse(input);
    if (info.backend !== "java" || info.simulated !== true || info.mode !== "simulated-loopback") {
      process.exit(1);
    }
  '; then
  fail "the loopback runtime did not prove Java simulator-only mode."
fi

runtime_dir="$(mktemp -d "${TMPDIR:-/tmp}/tracepermit-openai-tunnel.XXXXXX")"
authorization_file="$runtime_dir/mcp-authorization"
health_url_file="$runtime_dir/health-url"
chmod 700 "$runtime_dir"

if ! curl --silent --show-error --fail --max-time 3 \
  -H 'Content-Type: application/json' \
  --data '{"grant_type":"urn:tracepermit:params:oauth:grant-type:development-profile","profile":"chatgpt-readonly"}' \
  "http://127.0.0.1:${mcp_port}/dev/oauth/token" |
  node --input-type=module -e '
    let input = "";
    for await (const chunk of process.stdin) input += chunk;
    const response = JSON.parse(input);
    if (response.token_type !== "Bearer" || typeof response.access_token !== "string" || response.access_token.length < 32) {
      process.exit(1);
    }
    process.stdout.write(`Bearer ${response.access_token}`);
  ' >"$authorization_file"; then
  fail "the Java backend did not issue the bounded web-profile development credential."
fi
chmod 600 "$authorization_file"

arguments=(
  run
  --control-plane.tunnel-id "$tunnel_id"
  --control-plane.api-key "file:${api_key_file}"
  --mcp.server-url "url=http://127.0.0.1:${mcp_port}/mcp,channel=main"
  --mcp.extra-headers "Authorization: file:${authorization_file}"
  --mcp.startup-wait-timeout 10s
  --harpoon.allow-plaintext-http
  --health.listen-addr 127.0.0.1:0
  --health.url-file "$health_url_file"
  --log.level info
)
if [[ -n "$organization_id" ]]; then
  arguments+=(--control-plane.organization-id "$organization_id")
fi

echo "TracePermit OpenAI tunnel demo: starting outbound tunnel to the real Java MCP runtime."
echo "  MCP target:  http://127.0.0.1:${mcp_port}/mcp"
echo "  Identity:    fixed simulated chatgpt-readonly profile; not the ChatGPT login identity"
echo "  Enforcement: Java normalize -> classify -> policy -> permit -> simulator"
echo "  Lifetime:    restart this command before a live demo to renew the five-minute credential"
echo "  Safety:      simulator-only backend verified; no inbound public listener"

env \
  -u AWS_ACCESS_KEY_ID \
  -u AWS_SECRET_ACCESS_KEY \
  -u AWS_SESSION_TOKEN \
  -u JIRA_TOKEN \
  "$tunnel_client" "${arguments[@]}" &
tunnel_pid=$!
wait "$tunnel_pid"
