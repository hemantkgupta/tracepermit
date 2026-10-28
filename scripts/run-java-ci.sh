#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 1 ]]; then
  echo "Expected exactly one TracePermit CI lane." >&2
  exit 2
fi
source "$repo_root/scripts/configure-java-environment.sh"

tracepermit_ci_gradle() {
  if [[ "$(uname -s)" == Linux ]]; then
    exec perl "$repo_root/scripts/owned-test-reaper.pl" "$repo_root/java/gradlew" -p "$repo_root/java" "$@"
  fi
  exec "$repo_root/java/gradlew" -p "$repo_root/java" "$@"
}

case "${1:-}" in
  core|postgres|mariadb|operations-postgres|operations-mariadb106|operations-mariadb114)
    if [[ "$1" == core && "$(uname -s)" == Linux ]]; then
      node --test "$repo_root/scripts/owned-test-reaper.test.mjs"
    fi
    tracepermit_ci_gradle ciTest "-PtracepermitCiLane=$1"
    ;;
  coverage)
    node "$repo_root/scripts/ci-coverage.mjs" validate
    tracepermit_ci_gradle \
      jacocoAggregateCoverageVerification jacocoAggregateReport \
      "-PtracepermitCiCoverageDir=$repo_root/java/build/ci-coverage"
    ;;
  *) echo "Unknown TracePermit CI lane." >&2; exit 2 ;;
esac
