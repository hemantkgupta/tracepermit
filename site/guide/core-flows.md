---
title: Core Action Flows & Use Cases
description: How TracePermit evaluates, governs, and permits AI-agent tool calls in practice.
---

# Core Action Flows & Use Cases

TracePermit sits between AI agents (ChatGPT, Claude, autonomous CLI agents, scheduled jobs) and enterprise tools as an inline enforcement control plane.

::: info Drift Prevention & Developer Guide
This document represents the public, architectural reference for TracePermit's core use cases. For developer runbooks, local `pnpm demo:scenario:...` commands, and internal monorepo navigation, see the [Developer Flows Guide](https://github.com/hemantkgupta/tracepermit/blob/main/docs/flows-guide.md).
:::

---

## The Central Invariant

> **No agent ever touches an enterprise target directly.** Every tool call is intercepted, authenticated, normalized into a canonical action, evaluated by policy, and requires an exact, single-use, cryptographically verified **Execution Permit** before a safe simulator can execute.

![The Central Invariant Flow](/images/flows/central-invariant.svg)

<details>
<summary>View Mermaid Flowchart (for Notion / GitHub)</summary>

```mermaid
flowchart TD
  Agent["AI Agent / CLI / Workload"] -->|1. Structured Tool Proposal| Gateway["Trusted Gateway Adapter<br/>(authenticates caller, derives identity)"]
  Gateway -->|2. Canonical Action (SHA-256 hash)| Policy["Policy Engine<br/>(risk, classification, sliding history)"]

  Policy -->|allow| Permit["Issue Request-Bound<br/>Execution Permit"]
  Policy -->|require_approval| Reviewer["Human Reviewer<br/>Independent Signoff"]
  Policy -->|block| Deny["Deny & Record<br/>Audit Evidence"]

  Reviewer -->|approved| Permit
  Permit --> Claim["Atomic Claim (1x in DB)"]
  Claim --> Simulator["Deterministic Simulator"]
  Deny --> Ledger["Zero Dispatches<br/>(Target Untouched)"]
```

</details>

---

## The 3 Fundamental Data Contracts

The enforcement lifecycle is governed by three canonical JSON contracts:

### 1. Canonical Action (`CanonicalAction`)

The normalized representation of what the agent wants to do. It strips client quirks, sorts keys canonically, and calculates a deterministic SHA-256 hash.

```json
{
  "eventId": "018f47a2-f5ce-7a4b-8f54-80e5d3eaf091",
  "traceId": "8f3a2c1e4b5d67890123456789abcdef",
  "identity": {
    "subject": {
      "id": "user:alice@example.com",
      "type": "human",
      "assurance": "authenticated"
    },
    "actor": {
      "id": "client:chatgpt-web",
      "type": "orchestrator",
      "assurance": "asserted"
    }
  },
  "trigger": { "kind": "human", "origin": "chatgpt-web" },
  "action": {
    "protocol": "mcp",
    "toolName": "aws.logs.filter_log_events",
    "arguments": {
      "logGroupName": "/app/prod",
      "filterPattern": "ERROR",
      "limit": 50
    },
    "target": {
      "system": "aws",
      "environment": "prod",
      "resourceType": "cloudwatch-log-group"
    }
  }
}
```

### 2. Policy Evaluation (`PolicyEvaluation`)

The decision produced by the policy engine after classifying the action's risk and checking server-owned sequence history.

```json
{
  "evaluationId": "018f47a2-f5ce-7a4b-8f54-80e5d3eaf099",
  "classification": {
    "actionClass": "read",
    "severity": "low",
    "operation": "aws.logs.filter_log_events"
  },
  "decision": {
    "outcome": "allow",
    "policyId": "pol-aws-web-001",
    "policyVersion": 2,
    "matchedRuleId": "allow-cloudwatch-read",
    "reasonCode": "BOUNDED_LOG_READ"
  }
}
```

### 3. Execution Permit (`ExecutionPermit`)

A tamper-proof, short-lived JWS minted only when an action is allowed or approved. The executor verifies the cryptographic signature, checks that the `actionHash` matches the request, and atomically claims it so it can never be reused.

```json
{
  "permitId": "018f47a2-f5ce-7a4b-8f54-80e5d3eaf080",
  "issuer": "https://execution-permits.tracepermit.invalid",
  "audience": "urn:tracepermit:executor:aws",
  "subjectId": "user:alice@example.com",
  "actionHash": "sha256:97efc87ece3fb5188e998b012a0c19eeadbadf5228bc89d07591cfaca0ec4476",
  "decision": { "outcome": "allow", "policyId": "pol-aws-web-001" },
  "maxUses": 1,
  "expiresAt": "2026-10-08T10:16:01.000Z"
}
```

---

## 5 Core Flows in Action

---

### Flow 1: Safe Read (Autonomous Allow)

#### The Scenario

An engineer asks ChatGPT or a local CLI assistant: _"Check the last 50 error logs for the production app."_ The agent invokes `aws.logs.filter_log_events`. TracePermit verifies the caller's identity, evaluates that bounded reads on log groups are permitted by organizational policy, issues a request-bound permit, and executes against the safe simulator.

#### Sequence Diagram

![Flow 1: Safe Read (Autonomous Allow)](/images/flows/flow-1-safe-read.svg)

<details>
<summary>View Mermaid Syntax (for Notion / GitHub)</summary>

```mermaid
sequenceDiagram
  autonumber
  participant Agent as Agent / Client
  participant Gateway as Trusted Adapter and Gateway
  participant Policy as Classifier and Policy
  participant Issuer as Permit Authority
  participant Simulator as AWS Simulator
  participant Console as React Console (Audit)

  Agent->>Gateway: tools/call aws.logs.filter_log_events (Bearer token)
  Gateway->>Gateway: Authenticate token and build Canonical Action
  Gateway->>Policy: evaluate(canonical action)
  Policy-->>Gateway: allow (low risk, BOUNDED_LOG_READ)
  Gateway->>Issuer: issuePermit(actionHash, subject, audience)
  Issuer-->>Gateway: signed single-use permit
  Gateway->>Simulator: execute(canonical action, permit)
  Simulator->>Simulator: Verify permit signature, hash and claim single-use
  Simulator-->>Gateway: Simulated log results
  Gateway->>Console: Append audit evidence (ALLOW, executed)
  Gateway-->>Agent: MCP tool result
```

</details>

#### Security Takeaways

- **No Blanket Access**: The agent is never handed an AWS IAM credential.
- **Single-Use Binding**: The permit is bound to the exact parameters (`logGroupName: "/app/prod"`, `limit: 50`) and cannot be used for any other request.
- **Reference Code**: Handled by [`OidcAccessTokenVerifier.java`](https://github.com/hemantkgupta/tracepermit/blob/main/java/identity/src/main/java/io/tracepermit/identity/OidcAccessTokenVerifier.java) and [`ActionNormalizer.java`](https://github.com/hemantkgupta/tracepermit/blob/main/java/contracts/src/main/java/io/tracepermit/contracts/ActionNormalizer.java).
- **Demo Scenario**: [Scenario 1: Attested CLI Allow then Block](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/01-attested-cli-allow-block.md).

---

### Flow 2: Destructive Side Effect (Policy Block)

#### The Scenario

An agent is misled by an adversarial prompt injection or a hallucinated instruction and proposes terminating an EC2 instance: `aws.ec2.terminate_instances`. TracePermit verifies the caller's identity, but **authentication is not blanket authorization**. The classifier flags the operation as `critical` severity. Policy returns `block`. TracePermit records `not_executed` evidence and halts. **Zero permits are minted and no target API is called.**

#### Sequence Diagram

![Flow 2: Destructive Side Effect (Policy Block)](/images/flows/flow-2-destructive-block.svg)

<details>
<summary>View Mermaid Syntax (for Notion / GitHub)</summary>

```mermaid
sequenceDiagram
  autonumber
  participant Agent as Agent / Client
  participant Gateway as Trusted Adapter and Gateway
  participant Policy as Classifier and Policy
  participant Simulator as AWS Simulator
  participant Console as React Console (Audit)

  Agent->>Gateway: tools/call aws.ec2.terminate_instances
  Gateway->>Gateway: Authenticate and build Canonical Action
  Gateway->>Policy: evaluate(canonical action)
  Policy-->>Gateway: block (critical risk, UNAUTHORIZED_DESTRUCTION)
  Gateway->>Console: Append audit evidence (BLOCK, not_executed)
  Gateway-->>Agent: Error: Action blocked by policy (no permit issued)
  Note over Simulator: Simulator is NEVER called
```

</details>

#### Decision Payload

```json
{
  "evaluationId": "018f47a2-f5ce-7a4b-8f54-80e5d3eaf102",
  "classification": {
    "actionClass": "delete",
    "severity": "critical",
    "operation": "aws.ec2.terminate_instances"
  },
  "decision": {
    "outcome": "block",
    "policyId": "pol-aws-web-001",
    "matchedRuleId": "deny-destructive-ops",
    "reasonCode": "UNAUTHORIZED_INFRASTRUCTURE_DELETION"
  }
}
```

#### Security Takeaways

- **Fail-Closed Guardrails**: Unknown tools and high-risk unapproved mutations fail closed.
- **Immutable Evidence**: Even though no side effect occurred, the attempt and its exact provenance are recorded in the audit trail.
- **Reference Code**: Evaluated by [`PolicyEvaluator.java`](https://github.com/hemantkgupta/tracepermit/blob/main/java/policy/src/main/java/io/tracepermit/policy/PolicyEvaluator.java) and [`Classification.java`](https://github.com/hemantkgupta/tracepermit/blob/main/java/policy/src/main/java/io/tracepermit/policy/Classification.java).
- **Demo Scenario**: [Scenario 7: Signed Scheduled EC2 Stop Block](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/07-signed-scheduled-ec2-stop-block.md).

---

### Flow 3: Dual-Control Human Approval (Two-Man Rule)

#### The Scenario

An autonomous agent attempts a high-impact mutation, such as modifying a Security Group rule or deleting a database. The policy dictates `require_approval`.

TracePermit freezes the exact canonical action hash and registers a pending approval.

- **No Self-Approval**: The requesting principal or agent cannot approve its own proposal.
- **Exact-Request Binding**: An independent human approver with the requisite security role reviews the frozen SHA-256 hash.
- **Explicit Resume and Atomic Claim**: Approval is still not execution. The original requester explicitly resumes with ID/hash; current owner/client/reviewer/policy/expiry checks precede the PostgreSQL single-use claim and simulator dispatch. Replay cannot dispatch again. This is at-most-one dispatch per approval, not distributed exactly-once effects.

The local Codex path supports only a fixed `sg-demo` simulator mutation, not
arbitrary database/cloud changes. The payload below is a compact projection,
not the complete stored binding. Follow the
[Codex approval runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/codex-durable-approvals.md).

#### Sequence Diagram

![Flow 3: Dual-Control Human Approval (Two-Man Rule)](/images/flows/flow-3-human-approval.svg)

<details>
<summary>View Mermaid Syntax (for Notion / GitHub)</summary>

```mermaid
sequenceDiagram
  autonumber
  participant Agent as Agent (Workload)
  participant Gateway as Gateway and Approval Service
  participant DB as PostgreSQL State
  participant Approver as Human Reviewer
  participant Simulator as AWS Simulator

  Agent->>Gateway: tools/call tracepermit_request_simulated_change (sg-demo)
  Gateway->>Gateway: Policy evaluation (outcome: require_approval)
  Gateway->>DB: Store pending approval bound to exact actionHash
  Gateway-->>Agent: pending + approvalRequestId + requestHash + not_executed

  Note over Agent,Gateway: Self-approval rejected (SELF_APPROVAL_DENIED)

  Approver->>Gateway: POST /browser/api/v1/approvals/{id}/decision (approve + requestHash + CSRF)
  Gateway->>Gateway: Verify approver != requester, verify role and tenant
  Gateway->>DB: Atomically mark approval APPROVED
  Gateway-->>Approver: approved, still not_executed
  Agent->>Gateway: tools/call tracepermit_resume_approval (id + hash)
  Gateway->>Gateway: Recheck owner, client, reviewer, expiry and current policy
  Gateway->>DB: Atomically consume approval and claim permit (single use)
  Gateway->>Simulator: execute(canonical action, approved permit)
  Simulator-->>Gateway: Simulated update successful
  Gateway->>DB: Record outcome evidence
```

</details>

#### Approval Binding Payload

```json
{
  "mode": "simulated",
  "approvalRequestId": "c24d2efb-7f69-4cc6-b45c-ec82e55d9149",
  "requestHash": "sha256:4c373ad2aadad911a33a4964c10c425ac8a748dc3d3ff841fbc164c1cf0f6f07",
  "status": "approved",
  "executionStatus": "not_executed",
  "originalPolicyOutcome": "require_approval"
}
```

#### Security Takeaways

- **No Agent Broadening**: Human approval never grants general authority to an agent; it authorizes **one single execution** of one exact request hash.
- **Reference Code**: Defined in [`ApprovalBinding.java`](https://github.com/hemantkgupta/tracepermit/blob/main/java/execution/src/main/java/io/tracepermit/execution/ApprovalBinding.java) and [`ApprovalExecutionClaim.java`](https://github.com/hemantkgupta/tracepermit/blob/main/java/execution/src/main/java/io/tracepermit/execution/ApprovalExecutionClaim.java).
- **Demo Scenario**: [Scenario 3: Request-Bound Human Approval](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/03-request-bound-human-approval.md).

---

### Flow 4: Action Sanitization (Self-Healing Guardrails)

#### The Scenario

An agent requests log records with `limit: 50000`. If passed through directly, this would trigger downstream rate-limits and cause high latency. Instead of an uninformative hard deny, TracePermit's policy specifies `sanitize`.

An allowlisted transformer clamps `limit` down to `100`, creates a **derived canonical action** referencing the original event in its lineage, and re-submits it to policy. The derived action passes, a permit is minted for the modified hash, and the agent receives the bounded result without error.

#### Sequence Diagram

![Flow 4: Action Sanitization (Self-Healing Guardrails)](/images/flows/flow-4-sanitization.svg)

<details>
<summary>View Mermaid Syntax (for Notion / GitHub)</summary>

```mermaid
sequenceDiagram
  autonumber
  participant Agent as Agent / Workload
  participant Gateway as Trusted Adapter
  participant Policy as Policy Engine
  participant Sanitizer as Allowlisted Sanitizer
  participant Simulator as Target Simulator

  Agent->>Gateway: tools/call filter_log_events (limit: 50000)
  Gateway->>Policy: evaluate(original action)
  Policy-->>Gateway: sanitize (OVERSIZED_RESULT_WINDOW)
  Gateway->>Sanitizer: transform(original action)
  Sanitizer->>Sanitizer: Clamp limit: 100, generate derived actionHash
  Sanitizer-->>Gateway: Derived Canonical Action (parentEventId = original)
  Gateway->>Policy: evaluate(derived action)
  Policy-->>Gateway: allow (BOUNDED_LOG_READ)
  Gateway->>Simulator: execute(derived action, permit)
  Simulator-->>Gateway: 100 log lines
  Gateway-->>Agent: Result with 100 events + sanitization notice
```

</details>

#### Derived Action Payload

```json
{
  "eventId": "018f47a2-f5ce-derived-0002",
  "parentEventId": "018f47a2-f5ce-original-0001",
  "arguments": {
    "logGroupName": "/app/prod",
    "filterPattern": "ERROR",
    "limit": 100
  },
  "sanitization": {
    "transformerId": "clamp-query-limit",
    "changes": ["limit clamped from 50000 to 100"],
    "finalOutcome": "allow"
  }
}
```

#### Security Takeaways

- **Explicit Provenance**: Sanitization never silently edits an existing event. It mints a fresh event ID and action hash while recording the parent link in causal lineage.
- **Reference Code**: Handled by [`PolicyEvaluator.java`](https://github.com/hemantkgupta/tracepermit/blob/main/java/policy/src/main/java/io/tracepermit/policy/PolicyEvaluator.java).
- **Demo Scenario**: [Scenario 4: Signed Workload and Semantic Sequence](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/04-signed-workload-semantic-sequence.md).

---

### Flow 5: Semantic Sequence & Multi-Step Defense (Anti-Exfiltration)

#### The Scenario

Traditional tool firewalls evaluate each tool call in isolation. Consider an agent that executes two steps:

1. Calls `crm.get_customer_records` to read 20 customer contact details (Allowed: benign internal read).
2. Proposes calling `messaging.send_external_webhook` with the retrieved records (Data exfiltration attempt).

Neither tool call is inherently forbidden on its own. TracePermit maintains **server-owned sliding-window history** per subject. When the external send arrives, the classifier detects the toxic sequence: `SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND`. Policy immediately blocks the transmission.

#### Sequence Diagram

![Flow 5: Semantic Sequence & Multi-Step Defense](/images/flows/flow-5-sequence-exfiltration.svg)

<details>
<summary>View Mermaid Syntax (for Notion / GitHub)</summary>

```mermaid
sequenceDiagram
  autonumber
  participant Workload as Workload Sub-Agent
  participant Gateway as Gateway and History Store
  participant Policy as Classifier and Policy
  participant CRM as CRM Simulator
  participant Outbox as External Webhook Simulator

  Note over Workload,CRM: Step 1: Bulk Customer Read
  Workload->>Gateway: tools/call crm.get_customer_records
  Gateway->>Policy: evaluate read
  Policy-->>Gateway: allow
  Gateway->>Gateway: Record observation in server-owned history
  Gateway->>CRM: execute read
  CRM-->>Workload: 20 synthetic customer records

  Note over Workload,Outbox: Step 2: Immediate External Send Attempt
  Workload->>Gateway: tools/call messaging.send_external_webhook
  Gateway->>Gateway: Load recent history for subject
  Gateway->>Policy: evaluate send, historyContext
  Policy->>Policy: Detect sequence: PII read followed by external send
  Policy-->>Gateway: block SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED
  Gateway-->>Workload: Error: Blocked by sequence policy
  Note over Outbox: Outbox is NEVER called - zero data leaves
```

</details>

#### Sequence Block Payload

```json
{
  "classification": {
    "actionClass": "send",
    "severity": "high",
    "signals": ["EXTERNAL_EGRESS", "SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND"]
  },
  "decision": {
    "outcome": "block",
    "policyId": "pol-sequence-rules",
    "matchedRuleId": "block-pii-exfiltration-chain",
    "reasonCode": "SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED"
  }
}
```

#### Security Takeaways

- **Stateful Protection**: The gateway owns the sequence history; the client or agent cannot manipulate, reset, or forge recent action state.
- **Reference Code**: Defined in [`ClassificationContext.java`](https://github.com/hemantkgupta/tracepermit/blob/main/java/policy/src/main/java/io/tracepermit/policy/ClassificationContext.java) and [`Classification.java`](https://github.com/hemantkgupta/tracepermit/blob/main/java/policy/src/main/java/io/tracepermit/policy/Classification.java).
- **Demo Scenario**: [Scenario 9: Synthetic Customer Read Followed by External-Send Denial](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/09-synthetic-pii-read-external-send-block.md).

---

## Architectural Mapping

| Control Point           | Responsibility                                                                      | Code Reference                                                                                     |
| :---------------------- | :---------------------------------------------------------------------------------- | :------------------------------------------------------------------------------------------------- |
| **Ingress & Identity**  | Validates OAuth/OIDC JWTs, Ed25519 source attestations, and delegation chains.      | [`io.tracepermit.identity`](https://github.com/hemantkgupta/tracepermit/tree/main/java/identity)   |
| **Canonical Contracts** | Validates schemas and produces deterministic RFC 8785 canonical hashes.             | [`io.tracepermit.contracts`](https://github.com/hemantkgupta/tracepermit/tree/main/java/contracts) |
| **Policy Engine**       | Classifies action risk and evaluates ordered rules against server sequence context. | [`io.tracepermit.policy`](https://github.com/hemantkgupta/tracepermit/tree/main/java/policy)       |
| **Execution & Permits** | Issues single-use permits and verifies atomic consumption before simulation.        | [`io.tracepermit.execution`](https://github.com/hemantkgupta/tracepermit/tree/main/java/execution) |
| **Safe Simulators**     | Deterministic AWS and Jira mocks with zero real cloud credentials.                  | [`io.tracepermit.simulator`](https://github.com/hemantkgupta/tracepermit/tree/main/java/simulator) |
| **Console & Audit**     | Read-only visibility console for action feeds, lineage trees, and evidence graphs.  | [`apps/console`](https://github.com/hemantkgupta/tracepermit/tree/main/apps/console)               |
