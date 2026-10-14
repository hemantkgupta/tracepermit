#!/usr/bin/env bash
set -euo pipefail

tunnel_client="${TRACEPERMIT_TUNNEL_CLIENT_BIN:-$(command -v tunnel-client || true)}"
tunnel_id="${TRACEPERMIT_OPENAI_TUNNEL_ID:-}"
organization_id="${TRACEPERMIT_OPENAI_ORGANIZATION_ID:-}"
api_key_file="${TRACEPERMIT_OPENAI_TUNNEL_API_KEY_FILE:-}"
mcp_port="${TRACEPERMIT_OAUTH_MCP_PORT:-8080}"
api_port="${TRACEPERMIT_OAUTH_API_PORT:-8090}"
oauth_trusted_origins="${TRACEPERMIT_OAUTH_TRUSTED_ORIGINS:-}"
oauth_trusted_origin_args=()
health_url_file=""
tunnel_pid=""

fail() {
  echo "TracePermit OpenAI OAuth tunnel demo: $1" >&2
  exit 2
}

if [[ "$oauth_trusted_origins" == *$'\n'* || "$oauth_trusted_origins" == *$'\r'* || "$oauth_trusted_origins" == *$'\t'* ]]; then
  fail "TRACEPERMIT_OAUTH_TRUSTED_ORIGINS must be one space-separated line."
fi
read -r -a configured_oauth_trusted_origins <<<"$oauth_trusted_origins"
trusted_origin_count=0
trusted_origin_seen=" "
for trusted_origin in "${configured_oauth_trusted_origins[@]}"; do
  if [[ ! "$trusted_origin" =~ ^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$ ]]; then
    fail "TRACEPERMIT_OAUTH_TRUSTED_ORIGINS contains an invalid HTTPS origin."
  fi
  if [[ "$trusted_origin_seen" == *" $trusted_origin "* ]]; then
    fail "TRACEPERMIT_OAUTH_TRUSTED_ORIGINS contains a duplicate origin."
  fi
  trusted_origin_count=$((trusted_origin_count + 1))
  if [[ "$trusted_origin_count" -gt 8 ]]; then
    fail "TRACEPERMIT_OAUTH_TRUSTED_ORIGINS exceeds the limit of 8 origins."
  fi
  trusted_origin_seen+="$trusted_origin "
  oauth_trusted_origin_args+=(--mcp.oauth-trusted-origin "$trusted_origin")
done

cleanup() {
  trap - EXIT HUP INT TERM
  if [[ -n "$tunnel_pid" ]] && kill -0 "$tunnel_pid" >/dev/null 2>&1; then
    kill -TERM "$tunnel_pid" >/dev/null 2>&1 || true
    wait "$tunnel_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$health_url_file" && "$health_url_file" == "${TMPDIR:-/tmp}"/tracepermit-oauth-tunnel-health.* ]]; then
    rm -f -- "$health_url_file"
  fi
  echo "TracePermit OpenAI OAuth tunnel demo: stopped."
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
  fail "TRACEPERMIT_OPENAI_TUNNEL_API_KEY_FILE must be owner-only (mode 400 or 600)."
fi

curl --silent --show-error --fail --max-time 2 \
  "http://127.0.0.1:${mcp_port}/ready" >/dev/null ||
  fail "the hosted-client OAuth backend is not ready; run 'pnpm demo:oauth' first."
if ! curl --silent --show-error --fail --max-time 2 \
  "http://127.0.0.1:${api_port}/api/v1/system-info" |
  node --input-type=module -e '
    let input = "";
    for await (const chunk of process.stdin) input += chunk;
    const info = JSON.parse(input);
    if (info.backend !== "java" || info.simulated !== true || info.mode !== "simulated-oauth-loopback") process.exit(1);
  '; then
  fail "the loopback runtime did not prove the Java OAuth simulator-only profile."
fi
token_endpoint_status="$(
  curl --silent --output /dev/null --write-out '%{http_code}' --max-time 2 \
    "http://127.0.0.1:${mcp_port}/dev/oauth/token" || true
)"
if [[ "$token_endpoint_status" != "404" ]]; then
  fail "a development token endpoint is unexpectedly present."
fi

health_url_file="$(mktemp "${TMPDIR:-/tmp}/tracepermit-oauth-tunnel-health.XXXXXX")"
arguments=(
  run
  --control-plane.tunnel-id "$tunnel_id"
  --control-plane.api-key "file:${api_key_file}"
  --mcp.server-url "url=http://127.0.0.1:${mcp_port}/mcp,channel=main"
  --mcp.startup-wait-timeout 10s
  --harpoon.allow-plaintext-http
  --health.listen-addr 127.0.0.1:0
  --health.url-file "$health_url_file"
  --log.level info
)
if [[ -n "$organization_id" ]]; then
  arguments+=(--control-plane.organization-id "$organization_id")
fi
arguments+=("${oauth_trusted_origin_args[@]}")

echo "TracePermit OpenAI OAuth tunnel demo: starting without an injected MCP bearer token."
echo "  MCP target:  http://127.0.0.1:${mcp_port}/mcp"
echo "  Identity:    access token supplied per request by the hosted OAuth client"
echo "  OAuth fetch: ${trusted_origin_count} explicitly trusted external origin(s)"
echo "  Enforcement: Java verify -> normalize -> classify -> policy -> permit -> simulator"
echo "  Safety:      simulator-only backend verified; no inbound public listener"

env \
  -u AWS_ACCESS_KEY_ID \
  -u AWS_SECRET_ACCESS_KEY \
  -u AWS_SESSION_TOKEN \
  -u JIRA_TOKEN \
  "$tunnel_client" "${arguments[@]}" &
tunnel_pid=$!
wait "$tunnel_pid"
