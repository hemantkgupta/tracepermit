#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backend_log="$repo_root/.cache/demo/oauth-backend.log"
console_log="$repo_root/.cache/demo/oauth-console.log"
backend_pid=""
console_pid=""
mcp_port="${TRACEPERMIT_OAUTH_MCP_PORT:-8080}"
api_port="${TRACEPERMIT_OAUTH_API_PORT:-8090}"

fail() {
  echo "TracePermit hosted-client OAuth demo: $1" >&2
  exit 2
}

cleanup() {
  trap - EXIT HUP INT TERM
  if [[ -n "$console_pid" ]] && kill -0 "$console_pid" >/dev/null 2>&1; then
    kill -TERM "$console_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$backend_pid" ]] && kill -0 "$backend_pid" >/dev/null 2>&1; then
    kill -TERM "$backend_pid" >/dev/null 2>&1 || true
  fi
  [[ -z "$console_pid" ]] || wait "$console_pid" >/dev/null 2>&1 || true
  [[ -z "$backend_pid" ]] || wait "$backend_pid" >/dev/null 2>&1 || true
  echo "TracePermit hosted-client OAuth demo: stopped."
}

wait_for_url() {
  local name="$1"
  local url="$2"
  local pid="$3"
  local attempt=0
  while [[ "$attempt" -lt 120 ]]; do
    if curl --silent --show-error --fail --max-time 1 "$url" >/dev/null 2>&1; then return 0; fi
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      echo "TracePermit hosted-client OAuth demo: $name stopped before readiness." >&2
      return 1
    fi
    attempt=$((attempt + 1))
    sleep 0.5
  done
  return 1
}

# Requested shutdown exits through cleanup instead of resuming child monitoring.
trap cleanup EXIT
trap 'exit 0' HUP INT TERM

for command in curl lsof node pnpm; do
  command -v "$command" >/dev/null 2>&1 || fail "missing required command: $command"
done
for name in \
  TRACEPERMIT_OAUTH_ISSUER \
  TRACEPERMIT_OAUTH_RESOURCE \
  TRACEPERMIT_OAUTH_RESOURCE_METADATA_URI \
  TRACEPERMIT_OAUTH_AUTHORIZATION_SERVER \
  TRACEPERMIT_OAUTH_JWKS_URI \
  TRACEPERMIT_OAUTH_CLIENT_ID \
  TRACEPERMIT_OAUTH_TENANT_VALUE \
  TRACEPERMIT_OAUTH_TENANT_ID; do
  [[ -n "${!name:-}" ]] || fail "$name is required."
done
for port in 5173 "$mcp_port" "$api_port"; do
  [[ "$port" =~ ^[0-9]{1,5}$ && "$port" -ge 1 && "$port" -le 65535 ]] ||
    fail "invalid TCP port: $port"
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    fail "TCP port $port is already in use."
  fi
done

mkdir -p "$repo_root/.cache/demo"
build_commit="$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || true)"
if [[ ! "$build_commit" =~ ^[0-9a-f]{7,40}$ ]]; then build_commit="development"; fi
build_version="$(node -p "require('$repo_root/package.json').version")"

echo "TracePermit hosted-client OAuth demo: starting Java resource server and simulator..."
env \
  -u AWS_ACCESS_KEY_ID \
  -u AWS_SECRET_ACCESS_KEY \
  -u AWS_SESSION_TOKEN \
  -u JIRA_TOKEN \
  TRACEPERMIT_BUILD_VERSION="$build_version" \
  TRACEPERMIT_BUILD_COMMIT="$build_commit" \
  TRACEPERMIT_OAUTH_MCP_PORT="$mcp_port" \
  TRACEPERMIT_OAUTH_API_PORT="$api_port" \
  "$repo_root/scripts/run-java-hosted-client-oauth-backend.sh" >"$backend_log" 2>&1 &
backend_pid=$!

echo "TracePermit hosted-client OAuth demo: starting React read-only console..."
pnpm --dir "$repo_root" --filter @tracepermit/console dev >"$console_log" 2>&1 &
console_pid=$!

if ! wait_for_url "Java OAuth MCP" "http://127.0.0.1:${mcp_port}/ready" "$backend_pid"; then
  tail -40 "$backend_log" >&2 || true
  exit 1
fi
if ! wait_for_url "Java OAuth control plane" "http://127.0.0.1:${api_port}/api/v1/system-info" "$backend_pid"; then
  tail -40 "$backend_log" >&2 || true
  exit 1
fi
if ! wait_for_url "React console" "http://127.0.0.1:5173" "$console_pid"; then
  tail -40 "$console_log" >&2 || true
  exit 1
fi

echo
echo "TracePermit hosted-client OAuth demo is ready"
echo "  Console:       http://127.0.0.1:5173"
echo "  Private MCP:   http://127.0.0.1:${mcp_port}/mcp"
echo "  Java API:      http://127.0.0.1:${api_port}/api/v1"
echo "  Identity:      external OAuth/OIDC provider; no local token issuer"
echo "  Safety:        SIMULATED TARGETS ONLY; cloud credentials stripped"
echo
echo "Run 'pnpm demo:tunnel:oauth' in another terminal. Press Ctrl+C here to stop."

while kill -0 "$backend_pid" >/dev/null 2>&1 && kill -0 "$console_pid" >/dev/null 2>&1; do
  sleep 1
done

echo "TracePermit hosted-client OAuth demo: a child process stopped unexpectedly." >&2
exit 1
