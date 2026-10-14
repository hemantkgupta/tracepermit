#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/configure-java-environment.sh"

exec "$repo_root/java/gradlew" -p "$repo_root/java" --no-daemon \
  :adapters-http:test \
  --tests 'io.tracepermit.adapters.http.JavaBackendEndpointConnectorE2eTest' \
  --rerun-tasks
