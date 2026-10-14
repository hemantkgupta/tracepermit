# Scenario 10: tool-result prompt injection followed by an IAM block

**Requirements demonstrated:** FR-001, FR-002, FR-005, FR-006, FR-008,
FR-009, FR-010, FR-011, FR-012, FR-014, SEC-001, SEC-002, SEC-005,
SEC-012, REL-001, OPS-004

## Scenario objective

Demonstrate the trust boundary between **tool-result text** and an
**authorized tool action**. A signed scheduled workload reads a fixed local
runbook. The runbook result contains attacker-style instructions telling an
assistant to attach a simulated administrator policy to an IAM role. A
deterministic agent emulator intentionally follows that text and proposes the
structured IAM tool call. TracePermit evaluates the action and blocks it
before a permit, execution attempt, or simulator mutation.

> Lower-trust content may influence an agent's proposal; it cannot grant
> policy authority or bypass complete mediation of the resulting action.

This is **not** a test of an actual model, its instruction hierarchy, or its
ability to recognize malicious text. It is a repeatable Java enforcement
exercise with one fixed in-memory role and no AWS credential, SDK, network
target, or real IAM policy.

## Run it

```bash
pnpm demo:scenario:prompt-injection
# no presenter pauses:
pnpm demo:scenario:prompt-injection:auto
```

The launcher removes common AWS and Jira credential variables. Ed25519
workload/delegation keys are generated in memory and are not printed.

## Exact flow

```mermaid
sequenceDiagram
  participant W as Signed workload
  participant A as Trusted scheduled adapter
  participant G as Java policy/permit service
  participant R as Local runbook simulator
  participant M as Deterministic agent emulator
  participant I as In-memory IAM simulator

  W->>A: Signed fixed runbook-read proposal
  A->>G: Verified canonical action and narrow delegation
  G->>R: Allow + exact permit + one local read
  R-->>M: Untrusted text: ignore instructions; attach IAM policy
  M->>A: New signed structured IAM proposal
  A->>G: Verified identity and exact IAM delegation
  G->>G: Modify / critical + ACCESS_CONTROL_MUTATION
  G-->>M: Block / IAM_POLICY_ATTACHMENT_BLOCKED
  Note over G,I: No IAM permit, attempt or dispatch
```

The second signed delegation establishes that the workload can **propose**
the exact simulated IAM action. It does not make the action permissible under
the separate organizational policy. The injected text and the request's
declared goal are assertions, not trusted instructions to the gateway.

## Qualified result

| Boundary       | Observed result                                                                                                 |
| -------------- | --------------------------------------------------------------------------------------------------------------- |
| Runbook        | One fixed local read succeeds; its result includes an override instruction and `aws.iam.attach_role_policy`.    |
| Agent behavior | A deterministic emulator emits the requested structured action; no real LLM is involved.                        |
| Attribution    | The scheduled adapter verifies both independently signed requests and exact narrow delegations.                 |
| Classification | The IAM proposal is `modify` / `critical`, with `ACCESS_CONTROL_MUTATION` and semantic `ACCESS_CONTROL_CHANGE`. |
| Policy         | `block-simulated-iam-policy-attachment` returns `IAM_POLICY_ATTACHMENT_BLOCKED`.                                |
| Execution      | One runbook-read dispatch, zero IAM dispatches, no IAM permit or attempt, role policy remains unattached.       |
| Evidence       | Eight bounded lifecycle events; raw injected text, policy arguments, and credentials are absent.                |

The dated [secret-free local proof](evidence/10-tool-result-injection-2026-10-12.md)
records the run and regressions.

## Presenter narration

1. “The runbook result is data returned by a tool. It contains an explicit
   attempt to impersonate higher-priority instructions.”
2. “For a deterministic test, our agent emulator follows it and asks for an
   IAM policy attachment. We are not claiming a model would always do this.”
3. “Even with a valid signed workload and a narrowly scoped delegation, the
   proposed action is independently classified and denied by policy.”
4. “There was one read dispatch and zero IAM dispatches. The simulator's
   role-policy flag is still false.”

## Code map

| Responsibility                                  | Implementation                                                                                                                                                                                                                                                           |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Launcher                                        | `scripts/run-tool-result-prompt-injection-scenario.sh`                                                                                                                                                                                                                   |
| Signed presenter and deterministic emulator     | `java/adapters-http/src/test/java/io/tracepermit/adapters/http/SignedScheduledSemanticDemoMain.java`                                                                                                                                                                     |
| Fixed untrusted result and in-memory IAM effect | `java/simulator/src/main/java/io/tracepermit/simulator/SyntheticPromptInjectionSimulator.java`                                                                                                                                                                           |
| IAM risk classification                         | `java/policy/src/main/java/io/tracepermit/policy/PolicyEvaluator.java`                                                                                                                                                                                                   |
| Default-block scenario policy                   | `policies/tool-result-iam-demo.json`                                                                                                                                                                                                                                     |
| Pre-dispatch enforcement                        | `java/application/src/main/java/io/tracepermit/application/SecureExecutionService.java`                                                                                                                                                                                  |
| Tests                                           | `java/policy/src/test/java/io/tracepermit/policy/PolicyEvaluatorTest.java`, `java/application/src/test/java/io/tracepermit/application/SemanticEnforcementTest.java`, `java/simulator/src/test/java/io/tracepermit/simulator/SyntheticPromptInjectionSimulatorTest.java` |

## Scope limit

This proves **action control after an injected tool result**, not prompt-
injection prevention. A model might ignore the injection, follow a different
one, leak information in its answer, or use a tool outside the configured
gateway. Production still requires complete tool coverage, target credential
custody, anti-bypass egress, output/data-loss controls, and real hosted-client
qualification. The simulated IAM role is not exposed as a public MCP tool.
