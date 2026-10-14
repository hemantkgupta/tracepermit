# ADR 0013: Request-bound human approval and atomic consumption

**Status:** Accepted and implemented for the Java PostgreSQL simulator profile
**Date:** 2026-10-09

## Context

Policy can return `require_approval`, but the policy outcome alone cannot
resume execution. A browser click, approval identifier, requester-supplied
reviewer claim, or causal lineage record is not authorization. A safe workflow
must prove an independently authenticated reviewer was allowed to decide the
exact request, prevent self-approval, expire the decision, and consume it once
across replicas before any simulator effect.

Approval cannot be consumed in one transaction and the execution claim in a
later transaction. A crash between those commits could burn valid authority
without an attempt, while reversing the order could leave an executable claim
without consumed approval.

## Decision

The Java approval path uses four distinct boundaries:

1. `TrustedReviewerContext` is supplied by a trusted adapter and remains
   separate from action and approval-request data. It contains tenant, human
   subject, authenticated state, explicit reviewer role and scope,
   authentication method, and only a credential fingerprint.
2. `ApprovalBinding` records tenant, request/action hashes, event/evaluation,
   requester, actor-and-lineage hash, immutable policy ID/version, original
   `require_approval` result, tool, resource-shaped hash, group, creation,
   expiry, and `maxUses=1`.
3. `DurableApprovalState` owns pending, approved, denied, expired, and consumed
   transitions. PostgreSQL V2 persists the aggregate and append-only transition
   history under composite tenant keys.
4. `consumeAndClaim` atomically transitions an approved request to consumed,
   claims the short-lived execution permit and idempotency key, creates the
   attempt/initial transition, and appends approval/execution evidence plus
   outbox identities. The simulator is called only after commit.

Reviewer authorization requires an authenticated human with both
`approval_reviewer` and `approvals.decide`, in the same tenant, different from
the requester. Unknown decisions and all non-pending states fail closed.

The approved path revalidates the canonical action and re-evaluates the frozen
policy document. Every stored binding must still match. It then issues a
version-2 approval-derived execution permit. The permit retains an effective
`allow` for compatibility with executor verification but adds signed
`authorization.kind=human_approval`, request/decision identifiers, and
`originalPolicyOutcome=require_approval`. Durable evidence also retains the
original policy result and reviewer provenance.

The target call remains outside the database transaction. Durable execution outcome
and reconciliation semantics remain unchanged.

The presenter-facing Scenario 3 composes this boundary directly inside a
development Java process backed by disposable PostgreSQL. Its fixed reviewer
is supplied by a separate development adapter, and its terminal pause has no
authority. The composition exposes no approval HTTP endpoint and leaves the
React mutation controls disabled, preserving this ADR's reviewer trust
boundary while making the implemented invariants observable.

## Consequences

- One of many concurrent reviewers can finalize a pending request; later
  decisions cannot create a contradictory state.
- One of many concurrent consumers can create the execution attempt and reach
  the simulator.
- Mutation of arguments, target, subject, actor/lineage, action hash, tenant,
  policy, tool, or bounded resource hash invalidates approval.
- A database failure before the atomic commit produces no simulator call and
  leaves no partial permit, attempt, transition, evidence, or outbox row.
- Approval and execution evidence contain bounded identifiers, hashes,
  timestamps, reviewer provenance, policy provenance, reason codes, and
  credential fingerprints; they omit prompts, raw arguments/results, bearer
  tokens, compact permits, private keys, source attestations, and reusable
  credentials.
- Flyway V1 remains immutable; V2 advances the schema guard to contract version
  2 and adds the approval state machine.

## Rejected alternatives

- Trust an approval ID or request-body reviewer fields: identifiers and claims
  are replayable data, not verified authority.
- Enable the existing console buttons: there is no production reviewer
  authenticator or tenant-authorized mutation transport yet.
- Convert `require_approval` to an ordinary allow and discard provenance: this
  makes later evidence unable to distinguish policy allow from human override.
- Consume approval before or after the execution claim in separate commits:
  either ordering has a crash window.
- Hold the transaction across target I/O: PostgreSQL cannot roll back an
  independent target effect and the lock would only increase contention.

## Limitations

This slice is a Java application/persistence boundary exercised only with safe
simulators. There is no remote approval API, approval UI, production reviewer
authentication, notification channel, policy administration service, real
target credential, external evidence anchor, or automatic reconciliation
worker. The HMAC execution signer remains a compatibility POC; production
requires separated asymmetric/KMS-backed issuance and verification.
