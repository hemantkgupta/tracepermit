---
title: Core Action Flows & Use Cases
description: Nine implemented control paths, their trust boundaries, and their qualification limits.
aside: false
---

# Core Action Flows & Use Cases

TracePermit is an Agentic Visibility & Control (AVC) proof of concept with a Java
enforcement data plane and a React console. It evaluates supported tool proposals
before execution against safe simulators. It is not a production AWS integration.

The [developer flow guide](https://github.com/hemantkgupta/tracepermit/blob/main/docs/flows-guide.md)
provides source maps, test links and runnable scenarios. These diagrams and that
guide are generated from one checked-in diagram catalog.

## Choose a flow

- Origins: [endpoint source proof](#flow-6-endpoint-source-attribution) or
  [hosted OAuth](#flow-7-hosted-oauth-through-a-tunnel).
- Immediate decisions: [bounded read](#flow-1-bounded-read-allowed) or
  [destructive block](#flow-2-destructive-proposal-blocked).
- Human control: [durable review and explicit resume](#flow-3-human-review-then-explicit-resume).
- Semantic controls: [sanitization](#flow-4-sanitize-and-re-evaluate) and
  [read-then-send denial](#flow-5-read-then-send-denied).
- Authority and recovery: [narrow delegation](#flow-8-delegation-must-narrow) and
  [uncertain outcomes](#flow-9-uncertainty-is-not-permission-to-retry).

## The central invariant

Every supported simulator action routed through TracePermit must pass the required
identity, schema, policy and authority checks before dispatch. A block means no
permit and no simulator invocation. Approval alone never executes an action.

::: warning Scope of the control
This is configured-path enforcement, not universal interception of every agent,
host-wide anti-bypass or a real target credential broker. All downstream effects
shown here are simulated. This public site serves documentation, not the gateway.
:::

<div class="flow-diagram" tabindex="0" role="region" aria-label="Central enforcement sequence, horizontally scrollable">

![Authorize before simulator execution](/images/flows/central-invariant.svg)

</div>

[Open full-size diagram](/images/flows/central-invariant.svg)

<details>
<summary>View Mermaid source</summary>

<!-- diagram:central-invariant -->

```mermaid
sequenceDiagram
  autonumber
  participant C as Configured client
  participant G as Trusted adapter
  participant E as Policy / executor
  participant D as Security state
  participant S as Safe simulator
  C->>G: Propose a registered tool action, request data is untrusted
  G->>G: Verify identity and required origin evidence, validate and normalize
  G->>E: Evaluate current tool, arguments, target and available sequence context
  Note over C,S: BLOCK: no permit or dispatch. SANITIZE: derive a new action and evaluate again.
  Note over C,S: APPROVAL: persist pending, require distinct review, then explicit owner resume. ALLOW: continue.
  E->>D: Verify the exact permit and claim single use before any target effect
  E->>S: Invoke only the fixed simulator after authorization and claim
  E->>D: Record outcome separately, the read-only console queries bounded projections
  Note over C,S: Configured-path control only. No real target credentials, universal agent interception or host-wide anti-bypass.
```

<!-- /diagram:central-invariant -->

</details>

The trusted executor verifies and claims the permit; the simulator does not
authorize itself. Server-side evidence feeds read-only console views outside the
synchronous path. The policy/reviewer control plane and the enforcement data
plane have different responsibilities.

## Identity is profile-specific

| Path               | Verified boundary                                              | What it does not prove                                   |
| ------------------ | -------------------------------------------------------------- | -------------------------------------------------------- |
| Endpoint connector | Bearer plus enrolled-key, request-bound source proof           | Managed-device assurance or human approval of every call |
| Hosted OAuth       | Configured issuer, audience, client, tenant mapping and scopes | Provider subscription type or endpoint process identity  |
| Signed workload    | Action-bound credential plus narrowing delegation chain        | Authority from a scheduled label alone                   |
| Durable approval   | Enrolled requester and distinct scoped browser reviewer        | Automatic execution when the reviewer approves           |

These are separate compositions, not one universally qualified client setup. A
valid token establishes a principal; organizational action policy can still deny
that principal's tool proposal. Causal lineage is not authority delegation.

## Three concepts to keep separate

1. **Canonical action:** the adapter derives identity after verification and
   validates tool, arguments, target and context. Hashing uses the frozen
   `tracepermit-canonical-json-v1` profile, not an arbitrary JSON serializer.
2. **Policy evaluation:** deterministic rules classify `read`, `modify`,
   `destructive` or `orchestration`, assign severity, and consider available
   server-owned sequence context. This is not private model reasoning inspection.
3. **Execution permit:** a short-lived signed authorization for the exact action.
   The executor verifies its bindings and claims single use before invoking the
   simulator. Signatures and replay state are both needed; durability depends on
   the selected deployment profile.

## Flow 1: bounded read allowed

A configured client proposes `aws.logs.filter_log_events` with a bounded result
limit. After identity/schema checks, policy allows the read with `BOUNDED_LOG_READ`.
The executor verifies and claims a request-bound permit, then returns up to the
requested number of matching simulated log events.

<div class="flow-diagram" tabindex="0" role="region" aria-label="Safe read sequence, horizontally scrollable">

![Flow 1: bounded read](/images/flows/flow-1-safe-read.svg)

</div>

[Open full-size diagram](/images/flows/flow-1-safe-read.svg)

<details>
<summary>View Mermaid source</summary>

<!-- diagram:flow-1-safe-read -->

```mermaid
sequenceDiagram
  autonumber
  participant C as Agent / client
  participant G as Trusted adapter
  participant E as Policy / executor
  participant S as Log simulator
  participant D as Evidence / view
  C->>G: Propose aws.logs.filter_log_events with a bounded limit
  G->>G: Authenticate and verify the source proof when the CLI profile requires it
  G->>E: Construct the canonical action and evaluate the active policy
  E->>E: ALLOW: issue and verify an exact short-lived permit, claim single use
  E->>S: Invoke the simulator with the verified action, not a credential-bearing cloud API
  S-->>E: Return up to the requested number of matching simulated log events
  E->>D: Persist bounded decision and outcome evidence in the selected profile
  E-->>C: Return the MCP result, the console is outside the execution path
```

<!-- /diagram:flow-1-safe-read -->

</details>

**Proof boundary:** deterministic Java tests and a previously qualified hosted
OAuth read. No real CloudWatch service is contacted; no production latency SLO is
implied. [Scenario and setup](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/01-attested-cli-allow-block.md).

## Flow 2: destructive proposal blocked

For `aws.ec2.terminate_instances`, the explicit rule returns
`AGENT_EC2_TERMINATION_BLOCKED`, class `destructive`, severity `critical`.
The response is `block` / `not_executed`. Authentication does not override the
deny. The simulator is never invoked.

<div class="flow-diagram" tabindex="0" role="region" aria-label="Destructive block sequence, horizontally scrollable">

![Flow 2: destructive block](/images/flows/flow-2-destructive-block.svg)

</div>

[Open full-size diagram](/images/flows/flow-2-destructive-block.svg)

<details>
<summary>View Mermaid source</summary>

<!-- diagram:flow-2-destructive-block -->

```mermaid
sequenceDiagram
  autonumber
  participant C as Agent / client
  participant G as Trusted adapter
  participant P as Classifier / policy
  participant D as Evidence state
  participant S as Safe simulator
  C->>G: Propose aws.ec2.terminate_instances on a simulated instance
  G->>P: Authenticate, validate and evaluate the canonical action
  P-->>G: BLOCK: destructive / critical, AGENT_EC2_TERMINATION_BLOCKED
  G->>D: Record the policy denial without an execution permit or target attempt
  G-->>C: Return block and not_executed
  Note over C,S: The simulator is not invoked. Valid identity does not authorize every action.
```

<!-- /diagram:flow-2-destructive-block -->

</details>

**Proof boundary:** the allow/block simulator path is tested. Scheduled EC2 stop
is a separate signed-workload scenario, not this termination rule.
[Policy](https://github.com/hemantkgupta/tracepermit/blob/main/policies/aws-web-readonly.json).

## Flow 3: human review, then explicit resume

In the separate durable approval profile, Codex requests a fixed `sg-demo`
configuration change. PostgreSQL stores the exact snapshot and request hash.
A distinct, enrolled reviewer signs in to the role-scoped React inbox and approves
or denies that same hash. Approved still means **not executed**.

<div class="flow-diagram" tabindex="0" role="region" aria-label="Durable human approval sequence, horizontally scrollable">

![Flow 3: request, review and explicit resume](/images/flows/flow-3-human-approval.svg)

</div>

[Open full-size diagram](/images/flows/flow-3-human-approval.svg)

<details>
<summary>View Mermaid source</summary>

<!-- diagram:flow-3-human-approval -->

```mermaid
sequenceDiagram
  autonumber
  participant C as Codex MCP client
  participant G as Java approval API
  participant D as PostgreSQL
  participant R as Browser reviewer
  participant S as Safe simulator
  C->>G: tracepermit_request_simulated_change: sg-demo, port, documentation CIDR
  G->>D: After token, enrollment, scope and policy checks, store pending plus exact snapshot
  G-->>C: Return approvalRequestId, requestHash, pending and not_executed
  R->>G: Sign in separately, review exact arguments and submit hash-bound decision with CSRF
  G->>D: Verify distinct current human reviewer and atomically record approve or deny
  G-->>R: Approved is still not_executed, there is no automatic dispatch
  C->>G: tracepermit_resume_approval: original ID plus requestHash, no replacement arguments
  G->>G: Recheck owner, client, origin, reviewer, policy, expiry and immutable action
  G->>D: Verify permit, atomically consume approval and claim execution
  G->>S: Invoke the approved simulator action only after the claim commits
  G->>D: Commit the confirmed completion separately
  G-->>C: Return consumed / succeeded, replay cannot dispatch again
  Note over C,S: Self-review, wrong hash/owner/client, revoked reviewer, expiry or stale policy: reject before dispatch.
```

<!-- /diagram:flow-3-human-approval -->

</details>

Only the original requester can explicitly resume with the ID and hash, not
replacement tool arguments. Owner, client, origin, current reviewer authority,
policy and expiry are rechecked. Approval consumption and the execution claim
commit before dispatch. Self-review, stale policy, wrong hash and replay cannot
authorize execution.

**Proof boundary:** native app-server MCP calls and a synthetic browser reviewer
have been qualified. The separate [model-driven record](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/evidence/codex-model-driven-approval-2026-10-15.md)
qualifies Codex 0.162.1/`gpt-5.6-sol` request/review/resume, replay, expiry and
explicit reviewer-denial rejection for this local profile. Native source-attested
connector qualification and production browser IdP/HTTPS remain separate work.
The loopback approval fixture must not be tunnel-exposed. This is at-most-one
dispatch, not exactly-once external effects; screenshots are not dispatch counters.
[Runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/codex-durable-approvals.md).

## Flow 4: sanitize and re-evaluate

A signed workload proposes a log read with `limit: 500`: valid schema, but an
oversized result window. Policy selects the reviewed `bounded-log-read/v1`
transformer, which derives `limit: 100`. The derived action must pass a fresh
evaluation and receive its own exact permit before dispatch.

<div class="flow-diagram" tabindex="0" role="region" aria-label="Sanitization sequence, horizontally scrollable">

![Flow 4: derive and re-evaluate](/images/flows/flow-4-sanitization.svg)

</div>

[Open full-size diagram](/images/flows/flow-4-sanitization.svg)

<details>
<summary>View Mermaid source</summary>

<!-- diagram:flow-4-sanitization -->

```mermaid
sequenceDiagram
  autonumber
  participant C as Verified workload
  participant E as Enforcement service
  participant P as Policy evaluator
  participant T as Reviewed transformer
  participant S as Log simulator
  C->>E: Propose a simulated log read with limit 500 (valid schema, oversized window)
  E->>P: Evaluate original action, observe OVERSIZED_RESULT_WINDOW
  P-->>E: SANITIZE: LOG_RESULT_WINDOW_REQUIRES_SANITIZATION
  E->>T: Run only bounded-log-read/v1, clamp limit to 100
  T-->>E: Return new event identity and original/effective hash linkage
  E->>P: Re-evaluate the effective action, sanitization is not permission
  P-->>E: ALLOW the bounded derived request under current policy
  E->>S: Issue and verify effective-action permit, claim once, then read
  E-->>C: Return up to 100 matching simulated events plus sanitization summary
  Note over C,S: Unknown transformer, invalid output or failed re-evaluation: no dispatch. Limit 50000 fails schema validation.
```

<!-- /diagram:flow-4-sanitization -->

</details>

**Proof boundary:** a deterministic signed-workload fixture, not deployed cron.
The summary links original/effective hashes and the new event identity; it does
not invent causal ancestry. `limit: 50000` fails schema validation rather than
being sanitized. The fixture returns up to 100 matches, not necessarily 100.
[Scenario](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/04-signed-workload-semantic-sequence.md).

## Flow 5: read-then-send denied

The synthetic tools are `demo.customer.bulk_read` and `demo.egress.send_external`.
External send is already denied without history. After an allowed synthetic PII
read, server-owned tenant/subject history adds a critical sequence-specific denial.
Neither send reaches the simulated egress outbox.

<div class="flow-diagram" tabindex="0" role="region" aria-label="Synthetic exfiltration sequence, horizontally scrollable">

![Flow 5: synthetic read then send](/images/flows/flow-5-sequence-exfiltration.svg)

</div>

[Open full-size diagram](/images/flows/flow-5-sequence-exfiltration.svg)

<details>
<summary>View Mermaid source</summary>

<!-- diagram:flow-5-sequence-exfiltration -->

```mermaid
sequenceDiagram
  autonumber
  participant W as Signed workload
  participant E as Gateway / policy
  participant H as Owned history
  participant C as Synthetic CRM
  participant O as Local egress outbox
  W->>E: Baseline demo.egress.send_external without read history
  E-->>W: BLOCK: EXTERNAL_SEND_NOT_AUTHORIZED, no target dispatch
  W->>E: Independently signed demo.customer.bulk_read
  E->>H: After allow, record authorized read proposal for tenant plus verified subject
  E->>C: Execute permit-bound fixed read, receive 20 invented records
  W->>E: Independently signed demo.egress.send_external after the read
  E->>H: Load cross-target history, derive SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND
  E-->>W: BLOCK: SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED, critical severity
  Note over W,O: Neither send reaches the outbox. Sequence history adds a specific reason, not permission to send without history.
```

<!-- /diagram:flow-5-sequence-exfiltration -->

</details>

**Proof boundary:** fixed invented records and a local outbox, not real PII
inspection or network exfiltration. Bounded in-memory history records authorized
read proposals before dispatch; it is not proof of successful reads or durable
global data lineage. [Scenario](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/09-synthetic-pii-read-external-send-block.md).

## Flow 6: endpoint source attribution

The local Java connector receives strict MCP over stdio, observes its parent
process, and signs short-lived source evidence. The proof binds the exact MCP
request and bearer fingerprint. The loopback gateway independently verifies the
token, enrolled key, audience, freshness, bindings and single use before policy.

<div class="flow-diagram" tabindex="0" role="region" aria-label="Endpoint attribution sequence, horizontally scrollable">

![Flow 6: request and credential bound source evidence](/images/flows/flow-6-endpoint-attribution.svg)

</div>

[Open full-size diagram](/images/flows/flow-6-endpoint-attribution.svg)

<details>
<summary>View Mermaid source</summary>

<!-- diagram:flow-6-endpoint-attribution -->

```mermaid
sequenceDiagram
  autonumber
  participant A as Local MCP client
  participant C as Java connector
  participant O as OS / process observer
  participant G as Loopback gateway
  participant E as Policy / executor
  A->>C: Send strict MCP JSON-RPC over stdio, no trusted identity fields in arguments
  C->>O: Observe immediate parent, executable hash/start time, device and terminal facts
  C->>C: Sign short-lived Ed25519 proof bound to canonical MCP request and bearer fingerprint
  C->>O: Check observed process stability immediately before forwarding
  C->>G: POST exact loopback /mcp with bearer and separate source-attestation header
  G->>G: Authenticate, verify enrolled key, audience, time, request/credential bindings and single use
  G->>E: Build canonical action with bounded verified source facts, then evaluate policy
  Note over A,E: Invalid or replayed proof stops before protected tool dispatch. A valid proof still does not authorize an action.
  Note over A,E: Development origin evidence only. This is not the separate bearer-only Codex approval profile or managed-device assurance.
```

<!-- /diagram:flow-6-endpoint-attribution -->

</details>

**Proof boundary:** development process/device evidence and file keys, not
hardware-backed managed endpoints. An attached terminal does not prove each call
was human-authorized. Scripted tests may observe Node, not Codex, as the parent.
Native Codex source-attested connector qualification remains to be done; the
separate bearer-only approval profile does not establish it.
[Connector scenario](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/00-connector-origin-and-egress.md).

## Flow 7: hosted OAuth through a tunnel

The human prompt reaches the provider backend first. Its hosted model proposes a
configured MCP tool call. OAuth connection setup obtains a token for the
TracePermit resource; subsequent tool calls carry it through the private tunnel.
The Java edge verifies the token and applies tool policy before simulated execution.

<div class="flow-diagram" tabindex="0" role="region" aria-label="Hosted OAuth sequence, horizontally scrollable">

![Flow 7: hosted model, OAuth and private tunnel](/images/flows/flow-7-hosted-oauth.svg)

</div>

[Open full-size diagram](/images/flows/flow-7-hosted-oauth.svg)

<details>
<summary>View Mermaid source</summary>

<!-- diagram:flow-7-hosted-oauth -->

```mermaid
sequenceDiagram
  autonumber
  participant H as Human / hosted UI
  participant P as Provider backend
  participant I as Configured IdP
  participant G as Tunnel / Java edge
  participant E as Policy / simulator
  H->>P: Send prompt to provider backend, the hosted model proposes a configured MCP tool
  P->>I: During app connection, obtain a TracePermit resource access token through OAuth
  I-->>P: Return issuer-signed access token, TracePermit does not receive the IdP password
  P->>G: Send MCP proposal and bearer through the configured private tunnel
  G->>G: Verify issuer, signature, audience, time, client, tenant mapping and scopes before dispatch
  G->>E: Normalize authenticated subject and asserted provider actor, evaluate the tool action
  E-->>P: Return allowed simulated read or blocked/not_executed destruction
  P-->>H: Display the tool result in the unmodified hosted client
  Note over H,E: One Auth0 + ChatGPT + tunnel profile qualified. Reachability is not identity, endpoint proof or provider-plan attribution.
```

<!-- /diagram:flow-7-hosted-oauth -->

</details>

**Proof boundary:** one Auth0 + unmodified ChatGPT + private-tunnel profile was
qualified, not every provider/client. Reachability is not user identity, endpoint
attestation or personal/enterprise provider-plan attribution. Evidence stores
credential fingerprints, not bearer tokens. TracePermit does not obtain an IdP
password, Gmail token or mailbox access from this flow.
[Dated qualification](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/QUALIFICATION-EVIDENCE-2026-10-11.md).

## Flow 8: delegation must narrow

An action-bound workload credential and signed parent/child grants establish
authority. Each child references the signed parent hash and may not widen its
scope, resources, audience or lifetime. A valid narrowed chain still needs an
organizational policy allow and permit.

<div class="flow-diagram" tabindex="0" role="region" aria-label="Delegation narrowing sequence, horizontally scrollable">

![Flow 8: signed child authority](/images/flows/flow-8-delegation.svg)

</div>

[Open full-size diagram](/images/flows/flow-8-delegation.svg)

<details>
<summary>View Mermaid source</summary>

<!-- diagram:flow-8-delegation -->

```mermaid
sequenceDiagram
  autonumber
  participant W as Workload signer
  participant A as Sub-agent signer
  participant V as Trusted adapter
  participant E as Policy / permit
  participant S as Safe simulator
  W->>V: Supply action-bound workload credential and signed parent grant
  A->>V: Supply child grant linked to the exact signed parent hash
  V->>V: Verify trusted issuers/keys, signatures, action context, audience, expiry and chain links
  V->>V: Require child scopes/resources to be subsets and lifetime not to exceed parent
  V->>E: Valid narrowed chain: construct canonical action, then evaluate organizational policy
  E->>S: Only a separate allow plus verified permit enables one simulated read
  Note over W,S: Separate negative cases: widen scope, resource, audience or lifetime. Reject before canonical policy evaluation.
  Note over W,S: These are identity-boundary failures, not policy-block results. Causal lineage alone grants no authority.
```

<!-- /diagram:flow-8-delegation -->

</details>

**Proof boundary:** fixture signers, not a deployed scheduler or autonomous child
agent. Four correctly signed amplification cases are rejected at the identity
boundary before canonical action construction and policy evaluation. These are
not policy-block results. Causal ancestry alone grants no authority.
[Scenario](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/11-sub-agent-delegation-amplification-rejected.md).

## Flow 9: uncertainty is not permission to retry

The execution claim commits before dispatch; the completion write is separate.
A lost completion can leave consumed approval authority and an `executing` attempt.
Restart must not restore that authority or dispatch the effect again.

<div class="flow-diagram" tabindex="0" role="region" aria-label="Unknown outcome recovery sequence, horizontally scrollable">

![Flow 9: prevent redispatch and reconcile only by lookup](/images/flows/flow-9-unknown-outcome.svg)

</div>

[Open full-size diagram](/images/flows/flow-9-unknown-outcome.svg)

<details>
<summary>View Mermaid source</summary>

<!-- diagram:flow-9-unknown-outcome -->

```mermaid
sequenceDiagram
  autonumber
  participant C as Original requester
  participant E as Durable executor
  participant D as PostgreSQL
  participant S as Safe simulator
  participant W as Lookup-only worker
  E->>D: Commit approval consumption and executing claim before dispatch
  E->>S: Invoke the approved simulator action
  E->>D: Attempt separate completion write, a fault can leave consumed/executing
  E-->>C: Return safe error, not invented failure or success, inspect durable status
  C->>E: After restart, attempt resume of the same approval
  E-->>C: Reject redispatch because the authority is already consumed
  Note over C,W: Separate tested recovery foundation: only persisted UNKNOWN attempts enter the leased lookup worker.
  W->>D: Lease unknown candidates bound to tenant, adapter and idempotency identity
  W->>S: Perform reviewed read-only outcome lookup, never invoke the original effect
  W->>D: Append terminal outcome only when confirmed, otherwise defer and remain unknown
  Note over C,W: No automatic executing-to-unknown sweep or worker is wired into the browser approval launcher, real-target lookup remains future work.
```

<!-- /diagram:flow-9-unknown-outcome -->

</details>

**Proof boundary:** approval failure/restart rejection and a lookup-only worker
are separately tested. The worker leases persisted `UNKNOWN` attempts and records
a terminal outcome only when confirmed; otherwise it defers. No automatic
`executing` → `UNKNOWN` sweeper or worker is wired into the browser launcher.
Real-target outcome lookup remains future work. This is not a claim of exactly-once
external effects. [Recovery decision](https://github.com/hemantkgupta/tracepermit/blob/main/docs/architecture/0020-reconcile-by-lookup-never-reexecution.md).

## More controls and qualification

- [Tool-result injection → IAM denial](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/10-tool-result-injection-iam-block.md):
  untrusted content cannot grant authority; the deterministic fixture is not a
  universal model-resistance claim.
- [Evidence outbox and verified receipts](https://github.com/hemantkgupta/tracepermit/blob/main/docs/architecture/0019-leased-outbox-and-external-evidence-receipts.md):
  retriable publication is separate from synchronous execution; production anchor
  integration is not claimed.
- [Fail-secure and local latency checks](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/05-latency-and-fail-secure-qualification.md):
  pre-dispatch failures mean no dispatch; failures after dispatch may mean an
  uncertain outcome. Local measurements are not production SLOs.

For the next manual qualifications, use the
[rehearsal plan](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/flow-rehearsal-plan.md).
Documentation and automated fixtures explain the controls; they do not substitute
for a model-driven client rehearsal or qualify a production deployment.
