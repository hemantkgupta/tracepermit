#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/configure-java-environment.sh"

demo_cache="$repo_root/.cache/demo"
state_file="$demo_cache/session.json"
backend_log="$demo_cache/backend.log"
console_log="$demo_cache/console.log"
private_key="$demo_cache/endpoint-key.pk8"
backend_pid=""
console_pid=""
created_key=0
created_state=0

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "TracePermit demo: missing required command: $1" >&2
    exit 2
  fi
}

port_available() {
  ! lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

wait_for_url() {
  local name="$1"
  local url="$2"
  local pid="$3"
  local attempts=0
  while [[ "$attempts" -lt 120 ]]; do
    if curl --silent --show-error --fail --max-time 1 "$url" >/dev/null 2>&1; then
      return 0
    fi
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      echo "TracePermit demo: $name stopped before becoming ready." >&2
      return 1
    fi
    attempts=$((attempts + 1))
    sleep 0.5
  done
  echo "TracePermit demo: timed out waiting for $name." >&2
  return 1
}

cleanup() {
  trap - EXIT HUP INT TERM
  if [[ -n "$console_pid" ]] && kill -0 "$console_pid" >/dev/null 2>&1; then
    kill -TERM "$console_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$backend_pid" ]] && kill -0 "$backend_pid" >/dev/null 2>&1; then
    kill -TERM "$backend_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$console_pid" ]]; then wait "$console_pid" >/dev/null 2>&1 || true; fi
  if [[ -n "$backend_pid" ]]; then wait "$backend_pid" >/dev/null 2>&1 || true; fi
  if [[ "$created_state" -eq 1 && -f "$state_file" ]]; then rm -f -- "$state_file"; fi
  if [[ "$created_key" -eq 1 && ( -f "$private_key" || -L "$private_key" ) ]]; then
    rm -f -- "$private_key"
  fi
  echo "TracePermit demo: stopped; ephemeral token and endpoint keys are no longer usable."
}

trap cleanup EXIT HUP INT TERM

for command in curl lsof node nohup openssl pnpm; do require_command "$command"; done

node_major="$(node -p 'Number(process.versions.node.split(".")[0])')"
pnpm_version="$(pnpm --version)"
if [[ "$node_major" -lt 22 ]]; then
  echo "TracePermit demo: Node.js 22 or newer is required." >&2
  exit 2
fi
if [[ "$pnpm_version" != "11.25.0" ]]; then
  echo "TracePermit demo: pnpm 11.25.0 is required; found $pnpm_version." >&2
  exit 2
fi
for port in 5173 8080 8090; do
  if ! port_available "$port"; then
    echo "TracePermit demo: TCP port $port is already in use." >&2
    exit 2
  fi
done

umask 077
if [[ -L "$repo_root/.cache" || -L "$demo_cache" ]]; then
  echo "TracePermit demo: cache path must not be a symbolic link." >&2
  exit 2
fi
mkdir -p "$demo_cache"
chmod 700 "$demo_cache"
if [[ -e "$private_key" || -L "$private_key" || -e "$state_file" || -L "$state_file" ]]; then
  echo "TracePermit demo: stale session state exists; inspect and remove it before restarting." >&2
  exit 2
fi
created_key=1
openssl genpkey -algorithm ED25519 -outform DER -out "$private_key" 2>/dev/null
chmod 600 "$private_key"
nohup node "$repo_root/scripts/demo-cleanup-guardian.mjs" \
  </dev/null >/dev/null 2>&1 &
public_key="$(
  openssl pkey -inform DER -in "$private_key" -pubout -outform DER 2>/dev/null |
    base64 | tr -d '\n'
)"
build_commit="$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || true)"
if [[ ! "$build_commit" =~ ^[0-9a-f]{7,40}$ ]]; then build_commit="development"; fi
build_version="$(node -p "require('$repo_root/package.json').version")"

echo "TracePermit demo: validating and building the Java endpoint connector..."
pnpm --dir "$repo_root" --silent build:java-endpoint

echo "TracePermit demo: starting Java-only simulated backend..."
env \
  -u AWS_ACCESS_KEY_ID \
  -u AWS_SECRET_ACCESS_KEY \
  -u AWS_SESSION_TOKEN \
  -u JIRA_TOKEN \
  TRACEPERMIT_BUILD_VERSION="$build_version" \
  TRACEPERMIT_BUILD_COMMIT="$build_commit" \
  TRACEPERMIT_ENDPOINT_ISSUER="tracepermit-endpoint:demo-local" \
  TRACEPERMIT_ENDPOINT_KEY_ID="endpoint-key-demo-local" \
  TRACEPERMIT_ENDPOINT_DEVICE_ID="device-demo-local" \
  TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64="$public_key" \
  "$repo_root/scripts/run-java-development-backend.sh" >"$backend_log" 2>&1 &
backend_pid=$!

echo "TracePermit demo: starting React read-only console..."
pnpm --dir "$repo_root" --filter @tracepermit/console dev >"$console_log" 2>&1 &
console_pid=$!

if ! wait_for_url "Java MCP readiness" "http://127.0.0.1:8080/ready" "$backend_pid"; then
  tail -40 "$backend_log" >&2 || true
  exit 1
fi
if ! wait_for_url "Java control plane" "http://127.0.0.1:8090/api/v1/system-info" "$backend_pid"; then
  tail -40 "$backend_log" >&2 || true
  exit 1
fi
if ! wait_for_url "React console" "http://127.0.0.1:5173" "$console_pid"; then
  tail -40 "$console_log" >&2 || true
  exit 1
fi

created_state=1
TRACEPERMIT_DEMO_STATE_FILE="$state_file" \
TRACEPERMIT_DEMO_PRIVATE_KEY="$private_key" \
node --input-type=module -e '
  import { writeFileSync } from "node:fs";
  const state = {
    schemaVersion: "1.0",
    mode: "simulated-loopback",
    gatewayUrl: "http://127.0.0.1:8080/mcp",
    tokenUrl: "http://127.0.0.1:8080/dev/oauth/token",
    controlPlaneUrl: "http://127.0.0.1:8090",
    consoleUrl: "http://127.0.0.1:5173",
    endpointIssuer: "tracepermit-endpoint:demo-local",
    endpointKeyId: "endpoint-key-demo-local",
    endpointDeviceId: "device-demo-local",
    endpointPrivateKeyPath: process.env.TRACEPERMIT_DEMO_PRIVATE_KEY,
  };
  writeFileSync(process.env.TRACEPERMIT_DEMO_STATE_FILE, JSON.stringify(state), { mode: 0o600 });
'

echo
echo "TracePermit Demo++ is ready"
echo "  Console:       http://127.0.0.1:5173"
echo "  MCP readiness: http://127.0.0.1:8080/ready"
echo "  Java API:      http://127.0.0.1:8090/api/v1"
echo "  Build:         $build_version ($build_commit), Java $(java -version 2>&1 | head -1)"
echo "  Safety:        SIMULATED TARGETS ONLY; cloud credentials stripped"
echo
echo "Run 'pnpm demo:run' in another terminal. Press Ctrl+C here to stop."

while kill -0 "$backend_pid" >/dev/null 2>&1 && kill -0 "$console_pid" >/dev/null 2>&1; do
  sleep 1
done

echo "TracePermit demo: a child process stopped unexpectedly." >&2
exit 1
