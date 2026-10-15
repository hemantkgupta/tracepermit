#!/bin/bash
set +x
set -euo pipefail

if [[ $# -eq 1 && "$1" == "--help" ]]; then
  echo "Usage: pnpm demo:endpoint:codex [--check]"
  echo "Start pnpm demo in this checkout first. Simulator-only; no global Codex configuration writes."
  exit 0
fi
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--check" ) ]]; then
  echo "TracePermit native endpoint demo: INVALID_ARGUMENTS" >&2
  exit 2
fi

tracepermit_repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$tracepermit_repo_root"
source "$tracepermit_repo_root/scripts/configure-java-environment.sh"
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "TracePermit native endpoint demo: JAVA_HOME_REQUIRED. Select a Java 21 JDK first." >&2
  exit 2
fi
tracepermit_codex_command="$(command -v "${TRACEPERMIT_CODEX_BIN:-codex}" || true)"
if [[ -z "$tracepermit_codex_command" ]]; then
  echo "TracePermit native endpoint demo: CODEX_EXECUTABLE_UNAVAILABLE" >&2
  exit 2
fi
tracepermit_codex_bin="$(node scripts/codex-endpoint-preflight.mjs "$tracepermit_codex_command")"
echo "TracePermit native endpoint demo: preflight passed (local session, matching build, Java 21, readiness and key metadata)."
if [[ "${1:-}" == "--check" ]]; then exit 0; fi

export TRACEPERMIT_GATEWAY_URL=http://127.0.0.1:8080/mcp
export TRACEPERMIT_ENDPOINT_ISSUER=tracepermit-endpoint:demo-local
export TRACEPERMIT_ENDPOINT_KEY_ID=endpoint-key-demo-local
export TRACEPERMIT_ENDPOINT_DEVICE_ID=device-demo-local
export TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH="$tracepermit_repo_root/.cache/demo/endpoint-key.pk8"
TRACEPERMIT_CONNECTOR_BEARER_TOKEN="$(
  curl --disable --noproxy '*' --proto '=http' --silent --show-error --fail \
    --connect-timeout 2 --max-time 5 --max-filesize 16384 \
    --request POST --header 'content-type: application/json' \
    --data '{"grant_type":"urn:tracepermit:params:oauth:grant-type:development-profile","profile":"cli-readonly"}' \
    http://127.0.0.1:8080/dev/oauth/token |
    node scripts/codex-endpoint-preflight.mjs --token
)"
export TRACEPERMIT_CONNECTOR_BEARER_TOKEN
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN JIRA_TOKEN
tracepermit_connector_toml="$(node -e 'process.stdout.write(JSON.stringify(process.argv[1]))' "$tracepermit_repo_root/scripts/run-java-endpoint-connector.sh")"
echo "Synthetic CLI token lasts five minutes. Prepare prompts first; exit and relaunch after expiry."
echo "Only simulated targets. No automatic tool calls, token refresh or tool retries."

# Keep /bin/bash as the measurable ancestor even when pnpm's Node path is a
# symlink. Codex itself remains the Java connector's immediate parent. The real
# inherited terminal is observed by Java; this wrapper does not assert presence.
tracepermit_status=0
"$tracepermit_codex_bin" --no-daemon --sandbox read-only -m gpt-5.6-sol \
  -c "mcp_servers.tracepermit_endpoint={command=$tracepermit_connector_toml,args=[],env_vars=[\"JAVA_HOME\",\"TRACEPERMIT_GATEWAY_URL\",\"TRACEPERMIT_ENDPOINT_ISSUER\",\"TRACEPERMIT_ENDPOINT_KEY_ID\",\"TRACEPERMIT_ENDPOINT_DEVICE_ID\",\"TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH\",\"TRACEPERMIT_CONNECTOR_BEARER_TOKEN\"],required=true,startup_timeout_sec=30,default_tools_approval_mode=\"prompt\"}" \
  || tracepermit_status=$?
exit "$tracepermit_status"
