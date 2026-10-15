#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--check" ) ]]; then
  echo 'Usage: pnpm dev:approvals [--check]' >&2
  exit 2
fi
if [[ -z "${TRACEPERMIT_APPROVAL_CONFIG:-}" ]]; then
  echo 'TRACEPERMIT_APPROVAL_CONFIG must name a private operator configuration file.' >&2
  exit 2
fi
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Resolve before Gradle changes the working directory; never echo the configured path.
if [[ "$TRACEPERMIT_APPROVAL_CONFIG" != /* ]]; then
  export TRACEPERMIT_APPROVAL_CONFIG="$PWD/$TRACEPERMIT_APPROVAL_CONFIG"
fi
source "$repo_root/scripts/configure-java-environment.sh"
exec "$repo_root/java/gradlew" -p "$repo_root/java" :adapters-http:runSimulatorApprovals \
  --args="${1:---run}" --console=plain
