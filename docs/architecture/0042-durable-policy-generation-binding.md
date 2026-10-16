# ADR 0042 — Durable policy-generation binding for simulator approvals

- Status: implemented simulator-only Java foundation; operational control plane and remote mutation pending
- Date: 2026-10-16
- Requirements: FR-007; FR-008; FR-013; SEC-001; SEC-002; SEC-005; SEC-006; OPS-002

## Context

TracePermit simulator approvals previously snapshotted static policy rules in
memory or relied on static `policyId` and `policyVersion` comparisons during
approval validation. PR #28 introduced a durable policy lifecycle with
restart-safe revision storage, atomic activation/rollback, and monotonic
activation sequences (`activation_sequence`). However, approvals were not yet
bound to durable lifecycle generations.

Comparing only `policyId` and `policyVersion` is vulnerable to cyclical revision
activation: in a sequence where version 1 is active (`gen 1`), version 2 is
activated (`gen 2`), and version 1 is restored via rollback or reactivation
(`gen 3`), an approval created under `gen 1` would share the same version
number and canonical content as `gen 3`. If checked by version and content hash
alone, an old approval evaluated under an obsolete authorization epoch could
become executable again.

Furthermore, reading policy state in Java prior to durable claim introduces a
time-of-check to time-of-use (TOCTOU) race: an operator activation or rollback
could commit between the Java read and the database claim transaction, allowing
an approval created under an old policy to execute against a new active policy.

## Decision

1. **Active policy snapshot abstraction:**
   `PolicyLifecycleStore` and `PolicyLifecycleService` expose `activePolicySnapshot(workspaceId, policyId)`
   returning `Optional<ActivePolicySnapshot>`. The snapshot is an immutable
   value record containing `workspaceId`, `policyId`, `version`,
   `activationGeneration` (mapping to the monotonic `activation_sequence`),
   `contentHash`, `document` (defensive copy of the JSON policy document), and
   `activatedAt`. Monotonic activation generations advance on every successful
   activation and rollback; idempotent activations do not advance generation.
   Workspace and policy identity are configured by trusted Java bootstrap and
   are never accepted from client request bodies.

2. **Immutable approval policy binding:**
   `ApprovalPolicyBinding` captures `workspaceId`, `policyId`, `policyVersion`,
   `activationGeneration`, `policyContentHash`, and `boundAt`. In durable policy
   mode, `HumanApprovalService.requestApproval` queries the active snapshot,
   evaluates the action against its policy document, and writes the binding
   atomically with the pending approval request via
   `DurablePolicyBoundApprovalState.createPendingBound`. If no active policy
   exists for the configured workspace and policy ID, the request fails closed
   with `ACTIVE_POLICY_UNAVAILABLE` before any pending approval is created.

3. **Atomic claim-time revalidation and lock ordering:**
   In `PostgresDurableEnforcementState.consumeAndClaim`, the claim transaction:
   - Locks the approval request row `FOR UPDATE`.
   - Reads the approval's bound policy metadata from `tp_approval_policy_bindings`.
   - Acquires a `FOR SHARE OF a` lock on `tp_active_policies a` joined with
     `tp_policy_versions v`.
   - Verifies that `a.activation_sequence == binding.activationGeneration()`,
     `a.active_version == binding.policyVersion()`, and
     `v.content_hash == binding.policyContentHash()`.
   - If any field mismatches or no active policy exists, the claim returns
     `ApprovalConsumptionStatus.STALE_POLICY_GENERATION` and performs zero state
     mutations: no permit consumption, no approval status transition to `consumed`,
     no execution record, and no evidence event emitted.
   - The row-level `FOR SHARE` lock on `tp_active_policies` serializes against
     concurrent `tp_active_policies` updates (which take `FOR UPDATE`), preventing
     activation transitions from straddling the claim decision.

4. **Pre-permit issuance defense:**
   Before invoking `ExecutionPermitAuthority.issueApproved`,
   `HumanApprovalService.executeApproved` checks `activePolicySnapshot` against
   the approval's recorded binding. If a generation change is observed in Java,
   it throws `IllegalStateException("STALE_POLICY_GENERATION")` immediately,
   guaranteeing that no execution permit is minted. If a concurrent activation
   commits between this check and database claim, the PostgreSQL transaction
   rejects the claim with `STALE_POLICY_GENERATION`. In that interleaving, an
   internal permit may already have been minted; it is not consumed or returned
   as a successful execution result, and no target is dispatched. The guarantee
   is **no stale claim or dispatch**, not unconditional zero permit minting.

5. **Fail-closed on unbound legacy approvals:**
   When running in durable policy mode, an approval lacking a row in
   `tp_approval_policy_bindings` is rejected with `STALE_POLICY_GENERATION`.
   Unbound approvals created outside durable lifecycle mode cannot execute
   through the durable-mode service. A saved binding is also revalidated when
   a trusted Java caller uses the legacy four-argument claim constructor.
   However, the raw persistence port retains static compatibility when both the
   saved binding and claim policy context are absent. This is not a
   database-global durable-mode fence or a defense against arbitrary in-process
   Java callers; transports must use the correctly configured service.

6. **Database schema and immutability:**
   Migration `V8__policy_bound_approvals.sql` advances the schema contract
   version to 8 and creates `tp_approval_policy_bindings` with foreign keys to
   `tp_approval_requests` and `tp_policy_versions`. PostgreSQL trigger
   `tp_deny_approval_policy_bindings_mutation` rejects any `UPDATE` or `DELETE`
   statements on binding records.

7. **Opt-in composition and transport mapping:**
   Existing static-policy constructors and default launchers remain intact for
   backward compatibility and unit test fixtures. Opt-in factory methods in
   `SimulatorApprovalServer` enable durable policy lifecycle binding. In HTTP
   endpoints, `STALE_POLICY_GENERATION` maps to HTTP 409 Conflict. In MCP
   tool execution (`ApprovalMcpAdapter`), it maps to a structured tool error
   response with `errorCode: "STALE_POLICY_GENERATION"`.

## Scope and limits

- This foundation secures simulator approvals against policy activation races
  and cyclic rollback reuse. It does not introduce remote policy administration
  endpoints or operator UI editors.
- Claim commit is the ordering boundary. If the claim holds the share lock
  first, activation waits for that transaction to commit; the already-claimed
  simulator operation may finish after the new generation becomes active.
  Activation does not retroactively cancel a committed claim, and the row lock
  is not held across target dispatch. If activation commits first, the waiting
  stale claim is rejected without consuming authority.
- Stale pending/approved rows remain stored with their existing status; the
  claim fails rather than automatically cancelling or relabeling them.
- Durable lifecycle integration requires the opt-in Java factory. Default
  launchers and the source-free distribution still use static policies. This
  change does not add runtime configuration for durable policy administration.
- Foreign keys and database triggers enforce relational consistency within
  PostgreSQL; they do not defend against an administrative database operator.

## Regression evidence

`PostgresPolicyGenerationBoundaryTest` adds eight independent real-PostgreSQL
tests: forced claim-first and activation-first row-lock schedules, creation
racing activation, legacy claim-context omission, atomic rollback on binding
insert failure, lost claim-commit acknowledgement, explicit static compatibility,
and activation between Java precheck and permit issuance. Lock tests observe
actual PostgreSQL lock waits, not elapsed time alone. Service tests count only
synthetic target invocations; no real downstream system is contacted.

The lost-acknowledgement test commits the claim before injecting the connection
failure: dispatch stays at zero, authority remains consumed, and a subsequent
execution is not treated as fresh. This is bounded fault coverage, not a claim
of distributed exactly-once execution or automatic reconciliation.
