#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/configure-java-environment.sh"
connector="$repo_root/java/endpoint-connector/build/install/endpoint-connector/bin/endpoint-connector"

if [[ ! -x "$connector" ]]; then
  echo "TracePermit endpoint connector: BUILD_REQUIRED" >&2
  exit 2
fi

exec "$connector"
