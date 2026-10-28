#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/configure-java-environment.sh"
if [[ "$(uname -s)" == Linux ]]; then
  node --test "$repo_root/scripts/owned-test-reaper.test.mjs"
  exec perl "$repo_root/scripts/owned-test-reaper.pl" "$repo_root/java/gradlew" -p "$repo_root/java" checkAll
fi
exec "$repo_root/java/gradlew" -p "$repo_root/java" checkAll
