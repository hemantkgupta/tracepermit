#!/usr/bin/env bash
set -euo pipefail

tracepermit_repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$tracepermit_repo_root/scripts/configure-java-environment.sh"

"$tracepermit_repo_root/java/gradlew" \
  -p "$tracepermit_repo_root/java" \
  :review-verifier:installDist

tracepermit_review_verifier="$tracepermit_repo_root/java/review-verifier/build/install/review-verifier/bin/review-verifier"
if [[ ! -x "$tracepermit_review_verifier" ]]; then
  echo "TracePermit approval export browser test: REVIEW_VERIFIER_UNAVAILABLE" >&2
  exit 2
fi

export TRACEPERMIT_REVIEW_VERIFIER_PATH="$tracepermit_review_verifier"
exec node --test "$tracepermit_repo_root/scripts/approval-export-browser.test.mjs"
