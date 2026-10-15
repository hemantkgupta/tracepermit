#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "$(tr -d '\r\n' < "$repo_root/.java-version")" != "21.0.12.1" ]]; then
  echo "TracePermit demo verification: .java-version must select 21.0.12.1." >&2
  exit 1
fi
if [[ "$(grep -Ec '^  - ' "$repo_root/pnpm-workspace.yaml")" != "1" ]] ||
  ! grep -Fqx '  - "apps/console"' "$repo_root/pnpm-workspace.yaml"; then
  echo "TracePermit demo verification: the pnpm workspace must contain only the React console." >&2
  exit 1
fi

bash -n "$repo_root/scripts/run-demo.sh"
bash -n "$repo_root/scripts/run-demo-scenarios.sh"
bash -n "$repo_root/scripts/run-gate-c-qualification.sh"
bash -n "$repo_root/scripts/run-codex-endpoint-demo.sh"
bash -n "$repo_root/scripts/run-connector-origin-scenario.sh"
bash -n "$repo_root/scripts/run-live-cli-scenario.sh"
bash -n "$repo_root/scripts/run-request-bound-approval-scenario.sh"
bash -n "$repo_root/scripts/run-production-deletion-approval-scenario.sh"
bash -n "$repo_root/scripts/run-signed-scheduled-stop-scenario.sh"
bash -n "$repo_root/scripts/run-signed-scheduled-semantic-scenario.sh"
bash -n "$repo_root/scripts/run-synthetic-pii-exfil-scenario.sh"
bash -n "$repo_root/scripts/run-tool-result-prompt-injection-scenario.sh"
bash -n "$repo_root/scripts/run-sub-agent-delegation-scenario.sh"
bash -n "$repo_root/scripts/run-semantic-qualification.sh"
bash -n "$repo_root/scripts/run-openai-tunnel-demo.sh"
bash -n "$repo_root/scripts/run-hosted-client-oauth-demo.sh"
bash -n "$repo_root/scripts/run-java-hosted-client-oauth-backend.sh"
bash -n "$repo_root/scripts/run-openai-oauth-tunnel-demo.sh"
bash -n "$repo_root/scripts/run-approval-browser-qualification.sh"
bash -n "$repo_root/scripts/run-approval-codex-qualification.sh"
node --check "$repo_root/scripts/summarize-demo-response.mjs"
node --test "$repo_root/scripts/summarize-demo-response.test.mjs"
node --check "$repo_root/scripts/summarize-demo-mcp.mjs"
node --check "$repo_root/scripts/summarize-connector-origin.mjs"
node --check "$repo_root/scripts/summarize-live-cli.mjs"
node --check "$repo_root/scripts/run-demo-endpoint-client.mjs"
node --check "$repo_root/scripts/demo-cleanup-guardian.mjs"
node --test "$repo_root/scripts/demo-cleanup-guardian.test.mjs"
node --test "$repo_root/scripts/demo-shutdown.test.mjs"
node --test "$repo_root/scripts/codex-endpoint-demo.test.mjs"
node --test "$repo_root/scripts/gate-c-qualification.test.mjs"

if ! grep -Fq 'simulated !== true' "$repo_root/scripts/run-openai-tunnel-demo.sh"; then
  echo "TracePermit demo verification: the tunnel launcher must verify simulator-only mode." >&2
  exit 1
fi
if ! grep -Fq 'Authorization: file:' "$repo_root/scripts/run-openai-tunnel-demo.sh"; then
  echo "TracePermit demo verification: the tunnel launcher must use a file-backed MCP credential." >&2
  exit 1
fi
if ! grep -Fq 'mode 400 or 600' "$repo_root/scripts/run-openai-tunnel-demo.sh"; then
  echo "TracePermit demo verification: the tunnel launcher must require an owner-only runtime-key file." >&2
  exit 1
fi
if grep -Fq -- '--mcp.extra-headers' "$repo_root/scripts/run-openai-oauth-tunnel-demo.sh"; then
  echo "TracePermit demo verification: the OAuth tunnel must not inject a shared MCP credential." >&2
  exit 1
fi
if ! grep -Fq 'simulated-oauth-loopback' "$repo_root/scripts/run-openai-oauth-tunnel-demo.sh"; then
  echo "TracePermit demo verification: the OAuth tunnel must verify the OAuth simulator profile." >&2
  exit 1
fi
if ! grep -Fq 'mode 400 or 600' "$repo_root/scripts/run-openai-oauth-tunnel-demo.sh"; then
  echo "TracePermit demo verification: the OAuth tunnel must require an owner-only runtime-key file." >&2
  exit 1
fi
if ! grep -Fq -- '--mcp.oauth-trusted-origin' "$repo_root/scripts/run-openai-oauth-tunnel-demo.sh"; then
  echo "TracePermit demo verification: the OAuth tunnel must explicitly allowlist discovery origins." >&2
  exit 1
fi
if TRACEPERMIT_TUNNEL_CLIENT_BIN=/usr/bin/true \
  TRACEPERMIT_OAUTH_TRUSTED_ORIGINS='https://idp.example.invalid/path' \
  "$repo_root/scripts/run-openai-oauth-tunnel-demo.sh" 2>&1 |
  grep -Fvq 'contains an invalid HTTPS origin'; then
  echo "TracePermit demo verification: an OAuth trusted origin accepted a path." >&2
  exit 1
fi
if ! grep -Fq 'No measured values are fabricated in this preview.' \
  "$repo_root/scripts/run-connector-origin-scenario.sh"; then
  echo "TracePermit demo verification: Scenario 0 must distinguish its preview from measured evidence." >&2
  exit 1
fi
if grep -Eq 'connectorCredentialFingerprint|process\.pid|ATTACHED_TTY' \
  "$repo_root/scripts/run-connector-origin-scenario.sh"; then
  echo "TracePermit demo verification: Scenario 0 must not fabricate or print proof claims." >&2
  exit 1
fi
approval_demo="$repo_root/java/persistence-postgres/src/test/java/io/tracepermit/persistence/postgres/RequestBoundApprovalDemoMain.java"
for invariant in SELF_APPROVAL_DENIED APPROVAL_BINDING_MISMATCH APPROVAL_NOT_EXECUTABLE; do
  if ! grep -Fq "$invariant" "$approval_demo"; then
    echo "TracePermit demo verification: Scenario 3 is missing $invariant." >&2
    exit 1
  fi
done
if ! grep -Fq 'new PostgreSQLContainer<>("postgres:16.4-alpine")' "$approval_demo"; then
  echo "TracePermit demo verification: Scenario 3 must use disposable PostgreSQL." >&2
  exit 1
fi

semantic_demo="$repo_root/java/adapters-http/src/test/java/io/tracepermit/adapters/http/SignedScheduledSemanticDemoMain.java"
for invariant in WORKLOAD_CREDENTIAL_REPLAYED RAPID_REPEATED_READ_SEQUENCE 'dispatches.get() == 2'; do
  if ! grep -Fq "$invariant" "$semantic_demo"; then
    echo "TracePermit demo verification: Scenario 4 is missing $invariant." >&2
    exit 1
  fi
done

qualification_demo="$repo_root/java/adapters-http/src/test/java/io/tracepermit/adapters/http/SemanticQualificationMain.java"
for invariant in 'POLICY_SAMPLES = 10_000' SEQUENCE_STATE_UNAVAILABLE 'failSecureDispatches.get() == 0'; do
  if ! grep -Fq "$invariant" "$qualification_demo"; then
    echo "TracePermit demo verification: Scenario 5 is missing $invariant." >&2
    exit 1
  fi
done

for invariant in SCHEDULED_EC2_STOP_BLOCKED PRODUCTION_TARGET; do
  if ! grep -Fq "$invariant" "$semantic_demo"; then
    echo "TracePermit demo verification: Scenario 7 is missing $invariant." >&2
    exit 1
  fi
done

deletion_demo="$repo_root/java/persistence-postgres/src/test/java/io/tracepermit/persistence/postgres/ProductionDeletionApprovalDemoMain.java"
for invariant in EXPLICIT_APPROVAL_RULE_MISSING APPROVAL_BINDING_MISMATCH APPROVAL_NOT_CONSUMED APPROVAL_NOT_EXECUTABLE; do
  if ! grep -Fq "$invariant" "$deletion_demo"; then
    echo "TracePermit demo verification: Scenario 8 is missing $invariant." >&2
    exit 1
  fi
done

for invariant in SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED; do
  if ! grep -Fq "$invariant" "$semantic_demo"; then
    echo "TracePermit demo verification: Scenario 9 is missing $invariant." >&2
    exit 1
  fi
done

if ! grep -Fq 'IAM_POLICY_ATTACHMENT_BLOCKED' "$semantic_demo"; then
  echo "TracePermit demo verification: Scenario 10 is missing IAM_POLICY_ATTACHMENT_BLOCKED." >&2
  exit 1
fi

for invariant in INVALID_DELEGATION_CHAIN INVALID_DELEGATION_CREDENTIAL; do
  if ! grep -Fq "$invariant" "$semantic_demo"; then
    echo "TracePermit demo verification: Scenario 11 is missing $invariant." >&2
    exit 1
  fi
done

echo "TracePermit demo verification: Java 21 pin, React-only pnpm workspace, and demo scripts verified."
