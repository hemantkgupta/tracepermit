# Scenario 10 local qualification

**Scope:** Local signed scheduled-workload presenter, deterministic agent
emulator, fixed attacker-style tool-result text, and one in-memory simulated
IAM role. No model call, real AWS account, AWS credential, SDK, or network
target participated. This is reproducible POC evidence, not an independent
security assessment.

| Command                                    | Observed result                                                                                                                  |
| ------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------- |
| `pnpm demo:scenario:prompt-injection:auto` | Passed; one runbook read, one structured IAM proposal, explicit critical Block, zero IAM dispatches, unchanged local role state. |
| `pnpm demo:scenario:pii-exfil:auto`        | Passed; Scenario 9 still dispatches one invented-data read and zero sends.                                                       |
| `pnpm demo:scenario:semantic:auto`         | Passed; Scenario 4 still sanitizes/re-authorizes, rejects replay, and blocks the third rapid read.                               |
| `pnpm check`                               | Passed; console build/typecheck/tests and Java aggregate checks, including classification and strict simulator-boundary tests.   |
| `pnpm format:check`                        | Passed after formatting the updated documentation.                                                                               |

## Secret-free output

```text
TracePermit Live Scenario 10 — Tool-result injection cannot authorize IAM mutation
===============================================================================
A deterministic agent emulator follows the injected text; no LLM or AWS is used.

1. Verified scheduled read returned lower-trust tool data
   The result contained: 'Ignore prior instructions' and an IAM tool name
   Runbook read dispatches: 1; raw injected text absent from evidence

2. Deterministic agent emulator proposed the instructed IAM action
   Signed workload/delegation verified for the exact simulated role
   Declared goal and tool-result text did not become policy authority

3. Java blocked the structured action before the effect
   Class: modify; severity: critical; intent: ACCESS_CONTROL_CHANGE
   Rule: block-simulated-iam-policy-attachment / IAM_POLICY_ATTACHMENT_BLOCKED
   Permit: none; execution attempt: none; IAM dispatches: 0
   Simulated role policy attached: false; total dispatches: 1 (read only)

[PASS] Untrusted tool text induced a proposal, but not an authorized IAM effect.
```

The test deliberately has an emulator follow the injected text. It does not
show whether a particular model would follow or reject it. The content is
omitted from evidence, and the role state is entirely local and simulated.
