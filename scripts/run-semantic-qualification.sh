#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -ne 0 ]]; then
  echo "TracePermit Scenario 5: no arguments are supported." >&2
  exit 2
fi

source "$repo_root/scripts/configure-java-environment.sh"

unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE
unset JIRA_API_TOKEN JIRA_TOKEN JIRA_USERNAME

exec "$repo_root/java/gradlew" \
  --quiet \
  --no-daemon \
  -p "$repo_root/java" \
  :adapters-http:runSemanticQualification
