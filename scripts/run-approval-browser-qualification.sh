#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 0 ]]; then
  echo "Browser qualification accepts no arguments." >&2
  exit 2
fi
tracepermit_temp_base="${TMPDIR:-/tmp}"
tracepermit_run_directory="$(mktemp -d "$tracepermit_temp_base/tracepermit-browser-run.XXXXXX")"
tracepermit_cleanup() {
  case "$tracepermit_run_directory" in
    "$tracepermit_temp_base"/tracepermit-browser-run.*)
      rm -rf -- "$tracepermit_run_directory"
      ;;
    *)
      echo "Browser qualification cleanup target rejected." >&2
      return 125
      ;;
  esac
}
trap tracepermit_cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$repo_root"
pnpm build
source "$repo_root/scripts/configure-java-environment.sh"
export TRACEPERMIT_BROWSER_QUALIFICATION_TEMP="$tracepermit_run_directory"
"$repo_root/java/gradlew" -p "$repo_root/java" \
  :adapters-http:runApprovalBrowserQualification --console=plain
