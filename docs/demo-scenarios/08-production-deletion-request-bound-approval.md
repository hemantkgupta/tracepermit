# Scenario 8: production-labelled deletion requires exact human approval

**Requirements demonstrated:** FR-001, FR-002, FR-005, FR-006, FR-007,
FR-008, FR-009, FR-010, FR-011, FR-012, FR-014, SEC-001, SEC-002,
SEC-003, SEC-005, SEC-006, SEC-008, REL-001, REL-002, OPS-004

## Scenario objective

Prove that verified automation and narrow delegation can propose a destructive
action without being allowed to execute it. A policy returns
`require_approval`; an independent, authorized human reviewer approves one
exact canonical request; durable state is consumed with the execution claim
before one simulated deletion. A changed request or replay does not execute.

The word “production” is a **risk label** on account `simulated-123`. The
only effect is removal of `sg-demo` from a fresh in-memory simulator. No AWS
SDK, credential, endpoint, account, or real security group is involved.

## Run it

Docker must be running. The launcher unsets common AWS and Jira credential
variables and starts disposable PostgreSQL 16. It cleans up the container on
exit.

```bash
pnpm demo:scenario:production-deletion
```

The presenter run pauses before the trusted development reviewer submits its
approval. The keypress is **not** approval authority. For repeatable acceptance
without pauses:

```bash
pnpm demo:scenario:production-deletion:auto
```

This is standalone: it does not require `pnpm demo`, the React console, a
hosted client, or a tunnel. It uses the same durable Java approval service and
PostgreSQL boundary as Scenario 3, with a newly signed scheduled origin.

## Exact flow

```mermaid
sequenceDiagram
  participant S as Local scheduler
  participant V as Signed-workload verifier
  participant P as Java policy + approval service
  participant R as Development reviewer adapter
  participant DB as Disposable PostgreSQL
  participant T as In-memory AWS simulator

  S->>V: Exact delete_security_group proposal + signed identity and grant
  V->>V: Verify keys, action hash, scope/resource, time and replay
  V->>P: Canonical scheduled workload action
  P->>P: destructive / critical + PRODUCTION_TARGET
  P->>DB: Persist exact request-bound pending approval
  P-->>S: require_approval; no target dispatch
  R->>P: Unauthorized review attempt
  P-->>R: REVIEWER_NOT_AUTHORIZED
  R->>P: Authenticated separate reviewer with role and scope
  P->>DB: Persist approval
  S->>P: Changed groupId + approval ID
  P-->>S: APPROVAL_BINDING_MISMATCH; no dispatch
  S->>P: Original exact action + approval ID
  P->>DB: Atomically consume approval and claim execution
  P->>T: Permit-backed deletion of fixed simulated sg-demo
  T-->>P: deleted=true
  S->>P: Replay original action
  P-->>S: APPROVAL_NOT_EXECUTABLE; no second dispatch
```

## What the verified run shows

| Boundary            | Observed result                                                                                                                  |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| Origin              | Runtime-generated Ed25519 scheduler and workload keys; signed identity and one narrow delegation verified by the trusted adapter |
| Classification      | `destructive` / `critical`, with `DESTRUCTIVE_VERB` and `PRODUCTION_TARGET`                                                      |
| Policy              | Explicit `require_approval` rule in a separate default-block policy, version 1                                                   |
| Pending             | Durable approval exists; simulator dispatch count `0`; `sg-demo` still exists                                                    |
| Reviewer            | Human without role/scope rejected; distinct development reviewer with `approval_reviewer` and `approvals.decide` approves        |
| Binding             | Changing `groupId` to `sg-other` fails before dispatch; approved request remains intact                                          |
| Execution           | Exact request consumes approval and claims execution atomically, then removes `sg-demo` once in memory                           |
| Replay and evidence | Replay rejected; dispatch count remains `1`; durable evidence chain verifies                                                     |

The [secret-free local qualification record](evidence/08-production-deletion-approval-2026-10-12.md)
contains the observed output and regression results.

## Presenter narration

1. “The scheduler signs the deletion proposal and run context. The adapter
   verifies it, then creates the canonical workload identity and delegation.”
2. “The classifier says destructive and critical. Policy says **require
   approval**, which means no permit and no simulator call yet.”
3. “The separate reviewer adapter supplies authenticated identity, role, and
   scope. Merely knowing an approval ID or pressing Enter is not authority.”
4. “Changing one argument breaks the request binding. On the exact request,
   PostgreSQL consumes the approval and claims execution in one transaction.”
5. “Only then does the fixed simulator remove its one fake group. Replay cannot
   remove it again, and the evidence chain records the lifecycle.”

## Revision questions

1. **Why not simply allow this valid workload?** Identity and delegation
   establish provenance and an upper bound on authority. The organization can
   impose a stricter policy for a critical production-labelled deletion.
2. **What does the approval bind?** Tenant, exact canonical action hash,
   subject/lineage, policy ID/version, tool/resource, expiry, reviewer decision,
   and one allowed use.
3. **Why consume approval before calling the simulator?** It prevents a race
   from sending two effects for the same one-use authority. The transaction
   also records the execution claim.
4. **Does the keypress approve the action?** No. It only pauses the presenter.
   A separate development reviewer context supplies role, scope, and identity.
5. **Is this production OIDC or a real AWS deletion?** Neither. Reviewer
   identity is a development-only trusted adapter, and the target is an
   in-memory simulator.

## Java code map

| Responsibility                                 | Implementation                                                                                                                                                                                                                                                  |
| ---------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Presenter launcher                             | `scripts/run-production-deletion-approval-scenario.sh`                                                                                                                                                                                                          |
| Signed action, reviewer, and durable presenter | `java/persistence-postgres/src/test/java/io/tracepermit/persistence/postgres/ProductionDeletionApprovalDemoMain.java`                                                                                                                                           |
| Signed workload and delegation verification    | `java/identity/src/main/java/io/tracepermit/identity/ScheduledWorkloadVerifier.java` and `ScheduledActionAdapter.java`                                                                                                                                          |
| Explicit approval policy                       | `policies/aws-production-deletion-demo.json`                                                                                                                                                                                                                    |
| Exact approval binding and dispatch order      | `java/application/src/main/java/io/tracepermit/application/HumanApprovalService.java`                                                                                                                                                                           |
| Atomic approval/claim and evidence             | `java/persistence-postgres/src/main/java/io/tracepermit/persistence/postgres/PostgresDurableEnforcementState.java`                                                                                                                                              |
| Fixed simulated deletion                       | `java/simulator/src/main/java/io/tracepermit/simulator/StatefulTargetSimulator.java`                                                                                                                                                                            |
| Focused tests                                  | `java/policy/src/test/java/io/tracepermit/policy/PolicyEvaluatorTest.java`, `java/application/src/test/java/io/tracepermit/application/HumanApprovalServiceTest.java`, `java/simulator/src/test/java/io/tracepermit/simulator/StatefulTargetSimulatorTest.java` |

## Scope limit

This scenario does not expose a public approval API or enable React mutation
controls. The policy is not activated for the hosted-client MCP profile; that
profile remains read-only for AWS mutations. Production still needs a real
reviewer authenticator, protected scheduler keys, shared replay/rate state,
credential custody, anti-bypass egress, and a separately reviewed real-target
adapter. The local approval expires after one minute, shorter than the
runtime-generated two-minute workload credential; this is a demo bound, not a
general revalidation of credential freshness or revocation at execution.
Production must recheck continuing delegated authority before a delayed
approved execution. No real destructive integration is planned for this demo.
