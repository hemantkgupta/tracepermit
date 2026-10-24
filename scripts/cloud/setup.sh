#!/usr/bin/env bash
# Explicit dependency setup only. Toolchain/service provisioning is operator-owned.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "${1:-}" != "--install" || "$#" -ne 1 ]]; then
  echo 'SETUP_EXPLICIT_INSTALL_REQUIRED'
  exit 2
fi
cd "$repo_root"
export COREPACK_ENABLE_NETWORK=0 COREPACK_ENABLE_AUTO_PIN=0
export npm_config_manage_package_manager_versions=false
export TRACEPERMIT_CACHE_DIR="${TRACEPERMIT_CACHE_DIR:-$repo_root/.cache/cloud}"
export TRACEPERMIT_GRADLE_USER_HOME="${TRACEPERMIT_GRADLE_USER_HOME:-$TRACEPERMIT_CACHE_DIR/gradle}"
if ! command -v timeout >/dev/null; then
  echo 'SETUP_TIMEOUT_TOOL_REQUIRED'
  exit 1
fi
if ! timeout --kill-after=2s 15 node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 22 ? 0 : 1)' >/dev/null 2>&1; then
  echo 'SETUP_NODE22_REQUIRED'
  exit 1
fi
if ! timeout --kill-after=2s 15 bash -c '[[ "$(pnpm --version)" == 11.25.0 ]]' >/dev/null 2>&1; then
  echo 'SETUP_PNPM11250_REQUIRED'
  exit 1
fi
if ! timeout --kill-after=2s 15 bash -c 'set -euo pipefail; source "$1/scripts/configure-java-environment.sh"; [[ "$(javac -version 2>&1)" == javac\ 21* ]]; java -version 2>&1 | head -1 | grep -Eq '\''version "21[."]'\''' _ "$repo_root" >/dev/null 2>&1; then
  echo 'SETUP_JDK21_REQUIRED'
  exit 1
fi
if ! timeout --kill-after=2s 300 pnpm install --frozen-lockfile --store-dir "$TRACEPERMIT_CACHE_DIR/pnpm-store" --reporter silent >/dev/null 2>&1; then
  echo 'SETUP_FROZEN_INSTALL_FAILED_OR_TIMEOUT'
  exit 1
fi
if ! timeout --kill-after=2s 180 bash -c 'set -euo pipefail; source "$1/scripts/configure-java-environment.sh"; "$1/java/gradlew" -p "$1/java" --no-daemon --max-workers=2 --version' _ "$repo_root" >/dev/null 2>&1; then
  echo 'SETUP_WRAPPER_FAILED_OR_TIMEOUT'
  exit 1
fi
if ! timeout --kill-after=2s 30 bash scripts/test-java-environment.sh >/dev/null 2>&1; then
  echo 'SETUP_JAVA_ENV_CHECK_FAILED_OR_TIMEOUT'
  exit 1
fi
echo 'SETUP_DEPENDENCIES_READY_DOCKER_GATE_SEPARATE'
