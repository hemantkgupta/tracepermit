#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 0 ]]; then
  echo "Codex qualification accepts no arguments." >&2
  exit 2
fi
cd "$repo_root"
pnpm build
source "$repo_root/scripts/configure-java-environment.sh"
exec "$repo_root/java/gradlew" -p "$repo_root/java" \
  :adapters-http:runApprovalCodexQualification --console=plain
