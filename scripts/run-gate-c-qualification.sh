#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 1 && "$1" == "--help" ]]; then
  echo "Usage: pnpm demo:gate-c"
  echo "Runs the full Gate C deterministic presenter qualification suite:"
  echo "  - Scenario 4: Signed scheduled semantic sanitization & sequence control"
  echo "  - Scenario 9: Synthetic PII exfiltration sequence blocked"
  echo "  - Scenario 11: Sub-agent authority narrowing & amplification rejections"
  echo "  - Scenario 10: Tool-result prompt injection cannot authorize IAM mutation"
  echo "  - Scenario 5: Local latency & fail-secure qualification"
  echo "  - Scenario 7: Signed scheduled EC2 stop blocked"
  echo "Simulator-only; no real cloud targets or credentials."
  exit 0
fi

if [[ $# -gt 0 ]]; then
  echo "TracePermit Gate C qualification: unexpected arguments." >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

source "$repo_root/scripts/configure-java-environment.sh"
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "TracePermit Gate C qualification: JAVA_HOME_REQUIRED. Select a Java 21 JDK first." >&2
  exit 2
fi

unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE
unset JIRA_API_TOKEN JIRA_TOKEN JIRA_USERNAME

echo
echo "TracePermit Gate C — Deterministic Scenario Presenter Refresh"
echo "=============================================================="
echo "Scope: simulator-only presenters using synthetic cryptographic signers."
echo "No external network, model inference, or production credentials."
echo

start_time="$(date +%s)"

echo ">>> [1/6] Scenario 4: Signed scheduled workload & semantic sequence"
/bin/bash "$repo_root/scripts/run-signed-scheduled-semantic-scenario.sh" --auto

echo
echo ">>> [2/6] Scenario 9: Synthetic PII read then external send blocked"
/bin/bash "$repo_root/scripts/run-synthetic-pii-exfil-scenario.sh" --auto

echo
echo ">>> [3/6] Scenario 11: Sub-agent authority narrowing & amplification rejections"
/bin/bash "$repo_root/scripts/run-sub-agent-delegation-scenario.sh" --auto

echo
echo ">>> [4/6] Scenario 10: Tool-result prompt injection cannot authorize IAM mutation"
/bin/bash "$repo_root/scripts/run-tool-result-prompt-injection-scenario.sh" --auto

echo
echo ">>> [5/6] Scenario 5: Local latency objectives & fail-secure qualification"
/bin/bash "$repo_root/scripts/run-semantic-qualification.sh"

echo
echo ">>> [6/6] Scenario 7: Signed scheduled EC2 stop blocked"
/bin/bash "$repo_root/scripts/run-signed-scheduled-stop-scenario.sh" --auto

end_time="$(date +%s)"
duration=$((end_time - start_time))

echo
echo "=============================================================="
echo "[PASS] Gate C deterministic presenter qualification complete."
echo "All 6 scenarios verified against local deterministic simulators in ${duration}s."
echo "=============================================================="
