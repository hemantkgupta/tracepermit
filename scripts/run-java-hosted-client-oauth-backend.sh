#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/configure-java-environment.sh"

export TRACEPERMIT_REPOSITORY_ROOT="$repo_root"

exec "$repo_root/java/gradlew" -p "$repo_root/java" --no-daemon :adapters-http:runHostedClientOAuth
