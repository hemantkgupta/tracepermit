# Scenario 3: request-bound human approval

**Requirements demonstrated:** FR-007, FR-008, FR-010, SEC-005, SEC-006,
SEC-009, SEC-010, SEC-012, REL-001, REL-002, REL-004, DATA-001, DATA-004,
OPS-004, PORT-004

## Scenario objective

Show that `require_approval` is neither a block with a decorative button nor a
broad grant to an agent. TracePermit creates a durable binding for one exact
simulated modification, requires a distinct trusted reviewer, atomically
consumes the approval with the execution claim, invokes the simulator once,
and rejects mutation and replay.

The one-sentence message is:

> Human approval authorizes one exact canonical request for one use; it never
> authorizes the requester or agent generally.

## Honest boundary

This scenario uses the implemented Java application and PostgreSQL approval
path, a development-only reviewer adapter, and a local AWS simulator. It does
not expose a remote approval API and does not enable the React console's
approval buttons.

| Component                          | What is real in the POC                                                     |
| ---------------------------------- | --------------------------------------------------------------------------- |
| Policy evaluation                  | The checked-in policy returns `require_approval` for the simulated mutation |
| Request binding                    | Action, requester, lineage, resource, policy, expiry, and one-use limit     |
| Reviewer authorization             | Server-owned tenant, human, role, scope, and credential-fingerprint context |
| Durable state                      | Disposable real PostgreSQL with the production-shaped Flyway schema         |
| Approval consumption and execution | One transaction claims authority before one simulator invocation            |
| Downstream target                  | In-memory AWS simulator; no SDK, cloud credential, or real account          |

The fixed reviewer represents output from a trusted development adapter. The
presenter's Enter key only controls timing; it is not reviewer authentication
or approval authority. Production still requires an authenticated,
tenant-aware, CSRF/session-safe reviewer API and UI.

## Data flow

```mermaid
sequenceDiagram
  autonumber
  participant A as Simulated agent request
  participant P as Java policy
  participant H as HumanApprovalService
  participant R as Trusted reviewer adapter
  participant D as Disposable PostgreSQL
  participant S as AWS simulator

  A->>P: update_security_group on simulated sg-demo
  P-->>H: require_approval + policy version
  H->>D: Create exact pending binding
  H-->>A: pending; no target call
  A->>H: Requester attempts self-approval
  H-->>A: SELF_APPROVAL_DENIED
  R->>H: Independent authenticated reviewer + role/scope
  H->>D: pending -> approved
  A->>H: Mutated port 8443
  H-->>A: APPROVAL_BINDING_MISMATCH; no target call
  A->>H: Original exact request
  H->>D: TX consume approval + claim permit + create attempt/evidence
  D-->>H: Commit one winner
  H->>S: Invoke exact simulated action once
  S-->>H: Simulated success
  H->>D: Record target success
  A->>H: Replay original request
  H-->>A: APPROVAL_NOT_EXECUTABLE; no second call
```

## Run it

Docker must be running. This scenario is standalone; `pnpm demo` is not
required because the approval HTTP/UI boundary intentionally does not exist.

Presenter mode pauses before the independent reviewer decision:

```bash
pnpm demo:scenario:approval
```

Automated acceptance runs the same checks without the pause:

```bash
pnpm demo:scenario:approval:auto
```

The command starts and removes an ephemeral PostgreSQL 16 container. It strips
common AWS and Jira credential variables before starting Java.

## Expected proof points

1. Policy returns `require_approval`; target dispatch count remains zero.
2. Alice cannot approve her own request.
3. Bob's trusted development reviewer context contains the required tenant,
   human principal, role, and scope independently of the action body.
4. Changing port `443` to `8443` invalidates the approval before dispatch.
5. PostgreSQL consumes the approval and claims execution before the simulator
   is invoked.
6. Exactly one simulated security-group rule is added.
7. Replaying the consumed approval produces no second invocation.
8. The bounded durable evidence chain contains requested, approved, consumed,
   claimed, and succeeded lifecycle records and verifies successfully.

## What to say live

- “The original agent request cannot nominate its approver or assert reviewer
  roles.”
- “The keypress is a presentation pause. Authority comes from the separate
  trusted reviewer context.”
- “Approval is bound to the canonical request and immutable policy version, so
  changing one argument fails.”
- “Approval consumption and the execution claim commit atomically before the
  target call.”
- “The only target is a deterministic local simulator.”

## Code map

| Responsibility          | Implementation                                                                                                     |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Presenter launcher      | `scripts/run-request-bound-approval-scenario.sh`                                                                   |
| Scenario composition    | `java/persistence-postgres/src/test/java/io/tracepermit/persistence/postgres/RequestBoundApprovalDemoMain.java`    |
| Approval orchestration  | `java/application/src/main/java/io/tracepermit/application/HumanApprovalService.java`                              |
| Binding and lifecycle   | `java/execution/src/main/java/io/tracepermit/execution/ApprovalBinding.java`, `ApprovalRequestState.java`          |
| Atomic PostgreSQL state | `java/persistence-postgres/src/main/java/io/tracepermit/persistence/postgres/PostgresDurableEnforcementState.java` |
| Approval-derived permit | `java/security/src/main/java/io/tracepermit/security/ExecutionPermitAuthority.java`                                |
| Simulated effect        | `java/simulator/src/main/java/io/tracepermit/simulator/StatefulTargetSimulator.java`                               |
| Schema migration        | `java/persistence-postgres/src/main/resources/db/migration/V2__request_bound_human_approval.sql`                   |

## Honest limitations

- Reviewer OIDC is simulated by a trusted local adapter; no external IdP is
  contacted.
- There is no notification channel, remote mutation endpoint, or enabled
  approval UI.
- The HMAC permit key is generated ephemerally in memory for this bounded run;
  production requires separated asymmetric/KMS-backed issuance.
- PostgreSQL is disposable and the downstream effect is simulated.
