# ADR 0028: Treat tool-result instructions as data, then mediate the next action

- **Status:** Accepted for the simulator-only POC
- **Date:** 2026-10-12
- **Requirements:** FR-001, FR-002, FR-005, FR-006, FR-008, FR-009,
  FR-010, FR-012, SEC-001, SEC-002, SEC-005, SEC-012, REL-001

## Context

An agent may receive a lower-trust tool result that contains text written to
look like system or developer instructions. The model may follow it and emit a
new tool call. Scanning the text for suspicious words is not a reliable
authorization boundary; neither a safe-looking tool result nor an agent's
declared goal proves that its next action is permitted.

## Decision

Tool-result bytes are data. They are not identity evidence, a delegation, a
policy instruction, an approval, or an execution permit. If an agent proposes
a new effectful action after reading them, the trusted adapter must normalize
that structured action and verify its actual credentials/delegation. Java then
classifies and evaluates it against the active policy before issuing any
request-bound permit or invoking a target.

Scenario 10 demonstrates this boundary with a fixed local runbook result
containing an instruction override. A deterministic agent emulator follows
the text and emits `aws.iam.attach_role_policy`. The signed scheduled adapter
verifies the exact proposal, but a separate default-block policy explicitly
denies the critical access-control mutation. The IAM target is an in-memory
flag with a fixed simulated account/role/policy; zero IAM dispatches and an
unchanged flag prove the proposed effect was not executed. Bounded evidence
omits the raw tool-result text and IAM arguments.

## Consequences and limits

- A valid workload signature or narrow IAM delegation permits a **proposal**,
  not the effect. Policy can still deny it.
- The demo does not claim that a real LLM would follow or ignore the text, nor
  that TracePermit detects arbitrary prompt injection or controls final-answer
  exfiltration.
- A production gateway must completely mediate configured tools and keep
  target credentials and egress outside agent reach. Otherwise a model could
  choose an unmediated path despite this policy.
- The local simulator is not a real AWS adapter and has no network client or
  credential provider.

## Rejected alternatives

- **Trust a tool result that claims to be a higher-priority instruction:** a
  data source cannot promote itself across the trust boundary.
- **Accept `declaredIntent` or `clientClaims` as authorization:** both are
  request assertions and can be attacker-influenced.
- **Depend only on detecting malicious phrasing:** wording is mutable and
  authorization must hold even when detection misses or a model follows it.
- **Claim model robustness from a scripted emulator:** the emulator proves
  enforcement for a proposed action, not model behavior.
