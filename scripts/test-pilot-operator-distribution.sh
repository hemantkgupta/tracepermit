#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/configure-java-environment.sh"
node --test "$repo_root/scripts/pilot-operator-distribution.test.mjs"
pilot_test_directory="$(mktemp -d "${TMPDIR:-/tmp}/tracepermit-pilot-installed.XXXXXXXX")"
trap 'rm -rf -- "$pilot_test_directory"' EXIT
archive="$repo_root/java/pilot-operator/build/distributions/tracepermit-pilot-operator-0.1.0.tar.gz"
tar -xzpf "$archive" -C "$pilot_test_directory"
"$repo_root/java/gradlew" -p "$repo_root/java" :pilot-operator:test \
  --tests '*PilotArchiveTest' \
  "-PpilotArchiveRoot=$pilot_test_directory/tracepermit-pilot-operator-0.1.0" \
  --no-daemon --max-workers=2 --rerun-tasks "$@"
