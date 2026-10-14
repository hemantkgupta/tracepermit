#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
automatic=false

if [[ $# -gt 1 || ($# -eq 1 && "$1" != "--auto") ]]; then
  echo "TracePermit Scenario 8: expected no arguments or --auto." >&2
  exit 2
fi
if [[ $# -eq 1 ]]; then
  automatic=true
fi
if [[ "$automatic" == false && (! -t 0 || ! -t 1) ]]; then
  echo "TracePermit Scenario 8: presenter mode requires an attached terminal." >&2
  echo "Use 'pnpm demo:scenario:production-deletion:auto' for automated acceptance." >&2
  exit 2
fi
if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "TracePermit Scenario 8: a running Docker daemon is required for disposable PostgreSQL." >&2
  exit 2
fi

source "$repo_root/scripts/configure-java-environment.sh"

unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE
unset JIRA_API_TOKEN JIRA_TOKEN JIRA_USERNAME

gradle_arguments=(
  --quiet
  --no-daemon
  -p "$repo_root/java"
  :persistence-postgres:runProductionDeletionApprovalDemo
)
if [[ "$automatic" == true ]]; then
  gradle_arguments+=(--args=--auto)
fi

exec "$repo_root/java/gradlew" "${gradle_arguments[@]}"
