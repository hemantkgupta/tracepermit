#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 1 ]]; then
  echo "Expected exactly one TracePermit CI lane." >&2
  exit 2
fi
source "$repo_root/scripts/configure-java-environment.sh"

case "${1:-}" in
  core|postgres|mariadb|operations-postgres|operations-mariadb114)
    exec "$repo_root/java/gradlew" -p "$repo_root/java" ciTest "-PtracepermitCiLane=$1"
    ;;
  coverage)
    node "$repo_root/scripts/ci-coverage.mjs" validate
    exec "$repo_root/java/gradlew" -p "$repo_root/java" \
      jacocoAggregateCoverageVerification jacocoAggregateReport \
      "-PtracepermitCiCoverageDir=$repo_root/java/build/ci-coverage"
    ;;
  *) echo "Unknown TracePermit CI lane." >&2; exit 2 ;;
esac
