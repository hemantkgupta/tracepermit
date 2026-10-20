# ADR 0060 — Authenticated pending-only requester withdrawal

**Status: Implemented Java simulator API; requester UI and installed withdrawal qualification remain separate.**

Requirements: FR-003/007/009/010; SEC-001/002/003/005/006/009/012;
REL-001/002/004/005; DATA-001; OPS-002/004.

## Context and decision

[ADR 0058](0058-pending-approval-cancellation-contract.md) defines the withdrawal
contract; [ADR 0059](0059-explicit-v9-v10-simulator-upgrade.md) supplied its V10
storage and explicit upgrade prerequisite. This slice implements the shared Java
service and authenticated bearer/browser route, not a stop-execution command.

`POST /api/v1/approvals/{id}/cancel` and the same-origin browser equivalent accept
exactly `requestHash`. A trusted adapter combines verified credentials with
current operator-owned requester enrollment and explicit `approvals.cancel`.
`TrustedRequesterContext` is distinct from reviewer authority; the stable
issuer-qualified principal (pseudonymized by the adapter), authentication method,
credential fingerprint and authority deadline never come from the body. Browser
authority ends at the earlier of its verified access-token and session deadlines.
Requesting a scope during login is not a grant; the configured provider must
actually issue it, and reviewer/viewer membership alone remains insufficient.

The service fails closed on readiness, authorization, expired authority or absent
storage capability. It invokes only `DurableApprovalCancellationState.cancel`.
It neither evaluates the active policy nor calls the signer, claim port or target.
Cancellation under a changed/missing active policy does not reactivate that policy.

## Transaction and race boundary

PostgreSQL takes the recovery-ready shared lock first and the exact tenant/request
row lock second, matching review. Under that lock it rechecks owner, original
hash and pending state, then samples the trusted store clock and checks request
and authentication expiry. There is no ingress timestamp in the storage input.
Finite local lock/statement timeouts limit individual database waits, not the
whole request or a production availability SLO.

The append-only pending→cancelled transition carries separate requester
provenance. Its existing V10 trigger updates the aggregate and enforces owner,
terminal-state, reason and deadline constraints. The same transaction appends
bounded lifecycle evidence, advances the hash-linked stream and inserts the
outbox record. Evidence/outbox failure rolls back cancellation. The transaction
helper does not reset auto-commit after failed rollback, which could otherwise
commit an uncertain transaction on a reusable connection.

Cancellation-first prevents a later review; review-first prevents withdrawal.
Two withdrawals have exactly one committed transition. Approved, denied,
expired, consumed and cancelled requests are not cancellable. No permit, claim
or target operation is created by withdrawal. A committed cancellation remains
terminal across an ordinary process restart, but cannot detect an older backup
that resurrects a pending row.

## Lifecycle payload version

Existing lifecycle payloads remain unchanged. New `approval.cancelled` evidence
uses `schemaVersion: 3.0`, retaining the bounded approval correlation/hash/policy
fields of version2, with `approval.status=cancelled` and
`approval.reasonCode=REQUESTER_CANCELLED`. The required `cancellation` object has
exactly `requesterSubjectId`, `authenticationMethod`, `credentialFingerprint` and
`authorityExpiresAt`; all derive from the validated storage input. There are no
reviewer, decision, permit or attempt fields for this transition, no raw bearer,
canonical action, arguments, free-text reason or target result. This internal
lifecycle version is distinct from the frozen public audit-record-v1 artifact,
the unchanged API projection and database contract10. Consumers must not interpret
unknown versions/kinds as execution authority.

## Failure semantics and limits

Only commit-confirmed cancellation yields HTTP200 and cancelled/not_executed.
Missing owner-visible data is404; scope/role or visible cross-owner access is403;
binding/state/expiry conflicts are409. Authentication expiring during the lock
wait is401. Quarantine, persistence failure and lost commit acknowledgement are
redacted503, never success or automatic retry. Authorized lookup may reveal
cancelled, but cannot identify which indistinguishable concurrent owner call won.

Membership is checked per ingress using the current in-memory registry, not
transactionally coupled to PostgreSQL; in-flight revocation is not instantaneous.
No SQL migration is changed. There is no new MCP tool, React mutation/button,
worker interruption, permit revocation, target rollback, recovery resume,
external-IdP or real-target qualification. Existing source-free installations
need the new backend binary and V10; their installed withdrawal workflow remains
a separate acceptance gate.

## Verification

Application tests use rejecting signer/claim/target ports. PostgreSQL tests use
real row locks in both review schedules, concurrent withdrawal, lock-wait expiry,
evidence/outbox failures, quarantine, restart and lost commit acknowledgement.
Bearer and browser tests exercise verified signatures, roles/scopes, ownership,
strict input and the shared service; the browser tests preserve CSRF/origin
checks and verify the shorter session deadline during a real lock wait.
Existing approval/execution/replay and schema-upgrade suites remain regression
controls. See the [withdrawal runbook](../runbooks/pending-approval-withdrawal.md).
