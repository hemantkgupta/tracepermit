# ADR 0034: authenticated loopback approval API with exact durable action snapshots

**Status:** Accepted for simulator API foundation, not a production deployment

**Requirements:** FR-002, FR-003, FR-007, FR-008, FR-009, FR-010;
SEC-001, SEC-004, SEC-005, SEC-006, SEC-009, SEC-012;
REL-001, REL-002, REL-004; DATA-001

## Context

The existing durable approval service has no HTTP reviewer authenticator.
The default console/read API is deliberately unauthenticated and loopback-only;
enabling its disabled buttons would not establish reviewer authority.
A resumable approval API must retain the exact reviewed action across restarts
without accepting a new caller-authored canonical envelope at execution time.

## Decision

Add a separate `SimulatorApprovalServer` factory in the Java HTTP adapter.
It is not started by either existing demo main or launcher. It binds only to
IPv4 `127.0.0.1` and accepts an explicitly composed OIDC access-token verifier,
operator-owned membership registry, PostgreSQL state, policy, permit authority
and fixed `StatefulTargetSimulator`. It has no real-target port.

Every request requires the existing strict RS256 `at+jwt` access-token profile.
The resource/audience, issuer, client and tenant must be operator-allowlisted.
Membership is keyed by the verified issuer-qualified subject, client and tenant.
Human/workload type and requester/reviewer/viewer role are independent trusted
enrollment facts, never request fields, email-domain guesses or token role claims.
Required invocation scopes are checked in addition to membership roles.
An opaque principal hash is stable across enrolled clients for the same
issuer-qualified subject, preserving self-approval rejection across clients.

The local API rejects all Origin and Cookie headers. It has no CORS, browser
session, token issuance, forwarding-header trust or login flow. A token with a
sender-constraint claim is rejected: a `cnf` claim without verified transport
proof cannot establish sender constraint. This bearer-only loopback profile
does not satisfy production remote sender-constraint requirements.

Only the fixed simulated security-group modification is accepted, with strict
field/type bounds, `sg-demo`, a valid port and a documentation CIDR. The
adapter constructs identity, target, event, trigger and lineage server-side.
The API records a service/API trigger rather than inferring human initiation
from a human principal. Client metadata remains asserted; it does not become
cryptographic authority delegation or managed endpoint evidence.

PostgreSQL V6 adds an operational action-snapshot table, separate from the
bounded evidence ledger. `createPending(binding, action)` verifies the action
hash and inserts the approval, immutable snapshot, transition and audit/outbox
metadata in one transaction. A snapshot failure rolls back all of them. The
public API never accepts arbitrary canonical snapshots; only its narrow
server-normalized simulator action can reach this store.

Decisions and execution require the exact `requestHash` returned by the
authorized read projection. Execution loads the stored action, rechecks its
hash and the current policy binding, requires the original requester and
checks that the reviewer remains an active enrolled human reviewer. The
existing approval/permit/idempotency atomic claim still precedes the simulator
effect. No replacement action arguments are accepted at execution time.

Approval creation timestamps are truncated to milliseconds **before** hashing.
This gives stable bindings when PostgreSQL timestamps cannot preserve arbitrary
Java nanoseconds. The action snapshot itself is not rewritten or rehashed at
a lower precision.

## Consequences and limits

- Requesters can inspect their own approvals; enrolled reviewers/viewers may
  inspect approvals within their verified tenant. Execution remains owner-only.
- Invalid authentication, missing membership/scope, forged fields, self-approval,
  stale policy, expired authority, hash mismatch and replay cannot dispatch.
- Membership replacement is a trusted in-process operator operation, evaluated
  on each new request. It is not a durable admin/revocation API or IdP token
  introspection service. In-flight authorization is not globally instantaneous.
- Verified subject enrollment does not establish that two different accounts
  represent different physical humans. Enrollment/account linking is an
  operator responsibility; assurance claims must state this limitation.
- Stored canonical snapshots contain bounded operational arguments, identity
  metadata and credential fingerprints. They contain no bearer token/private
  key. Audit events continue to omit raw arguments/results. Snapshot encryption,
  retention, backup/restore and broader schema onboarding require later review.
- V6 advances the schema guard. Older binaries cannot use the upgraded schema;
  back up first and do not assume an in-place binary downgrade is safe.
- The simulator's effects remain in memory. The restart test preserves database
  approval/execution authority; it does not make simulator resources durable.
- No browser login, React approval inbox, notifications, public edge, managed key
  custody, external IdP live qualification or customer-ready packaging is claimed.

## Validation

`SimulatorApprovalServerTest` crosses actual HTTP, RS256 verification, trusted
membership, PostgreSQL, approval-derived Ed25519 permits and the fixed simulator.
It verifies pending/review/execute across reconstructed runtimes, exact binding,
tenant/owner isolation, token/membership/scope failures, reviewer removal,
expiry, stale policy, concurrent single use, snapshot rollback/immutability,
bounded strict parsing and sub-microsecond clock precision.

Run `pnpm test:approval-api --rerun-tasks` with Docker available. Existing
Scenarios 3 and 8 retain their separate development reviewer adapters; their
presentation pause still has no authentication authority.
