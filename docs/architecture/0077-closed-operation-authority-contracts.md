# ADR 0077: closed operation-review inputs before durable authority

Status: first internal application-contract checkpoint implemented; durable
policy/review/admission transactions and integration **not implemented**.

Requirements: FR-002/003/007/008/009/012; SEC-001/002/003/005/009/012;
REL-001/002/004/005; OPS-002/004. No whole-ID status promotion.

## Decision

Keep the current V2 registration/withdrawal library, migration bytes, canonical
contract and simulator unchanged. Start the authority lane with separate closed
Java inputs and an immutable operator-owned static review profile. These classes
have no persistence, signing, callback, transport or target port. They cannot
commit an approval or authorize dispatch. There is no new route or default wiring.

`TrustedOperationReviewerContext` adds independently derived workspace enrollment
and a mandatory authority deadline to the existing reviewer facts, without
silently inventing a deadline for legacy approval users. A trusted adapter must
verify the identity, derive tenant/principal/workspace/roles/scopes and bound the
deadline before constructing it. Constructors, JSON labels, hashes and booleans
are not provider verification. Never deserialize these types from a public body.

`BusinessOperationReview` accepts only an operation ID, owner scope, expected
whole-state token, the trusted operation reviewer context and the closed
`APPROVE`/`DENY` decision. Require an authenticated **human**, exact tenant and
workspace, a principal distinct from the requester, `operation_reviewer` and
`operations.decide`. Legacy `approval_reviewer`/`approvals.decide` alone do not
grant operation-review authority. There is no arbitrary proposed JSON, status,
reason, approval deadline or request-supplied clock. Identity is checked at
construction and again by `requireAuthorizedAt` using a future store's fresh
post-lock time; equality is expired. The actor record is defensive metadata with
a credential fingerprint, never a credential or committed-review receipt.

`BusinessOperationAuthorityProfile` copies and validates an enabled policy from
**operator configuration**, bounds it to 64KiB of canonical UTF-8 and pins exact
scope, adapter, recovery generation, policy ID/version/content hash, the complete
ordered artifact-array hash and resource/precondition hash. The profile
fingerprint is domain-separated as `tracepermit-operation-review-v1`. This is an
internal profile identifier, not a new public contract or signed token. The full
operation is copied/revalidated and bounded to 256KiB before comparison. Policy
is actually evaluated on the canonical action rather than trusting an existing
`approved` label or a caller-constructed `PolicyDecision`.

This first profile supports only `block` and `require_approval`; `allow` or
`sanitize` is a fixed rejection, not automatic approval or action rewriting.
Its result is decision data only. A future store must call its own trusted
profile and cannot accept a decision supplied by a request. Static configuration
does not establish current database policy/recovery state or instantaneous
membership revocation. Expected pins must not be derived from the same operation
being checked. This class is not the strict GitHub target-preflight bridge in
ADR0075 and does not replace its immutable repository/installation/ref checks.

Invalid inputs use fixed diagnostic codes without retained causes/suppressed
exceptions. Default string rendering of the new types is redacted. This does
not make full snapshots, existing context accessors or policy decisions redacted
exports; callers must not log them indiscriminately.

## Required next transaction boundary

The remaining service must use **one** transaction owner, not separately
committing registration, approval, replay and attempt stores. The proposed
[MariaDB authority transaction](https://github.com/hemantkgupta/tracepermit/pull/107)
is a design input, not an implemented schema or parity claim.

Before any durable approval can be reported, load immutable registration and the
current lifecycle/approval bindings; lock current policy/recovery control before
operation/approval/admission/journal heads in one fixed order; then sample fresh
writer time. Repeat whole-state, exact binding, authentication, pending state,
approval deadline, reviewer-group enrollment and policy/generation checks under
those locks. The current command checks identity facts only; it does **not**
complete those independent approval checks. Commit state and append-only facts
together. Report uncertainty after lost commit or cleanup acknowledgement and
never dispatch from uncertainty or a later lookup alone. No external network,
IdP, signing or target operation may run under database locks.

Admission additionally needs a separate versioned permit binding operation,
scope, policy/artifacts/resource/generation and single-use attempt identity.
The legacy permit profile does not contain that full binding and must not be
silently promoted. No claim, execution attempt, outbox publication or synthetic
dispatch is added by this checkpoint. Explicit additive migration and restricted
runtime grants, genuine lock-wait expiry, rollback/ack-loss races and restart
qualification on PostgreSQL16.4 and MariaDB11.4.13/10.6.25 remain required.

## Verification and limits

`BusinessOperationReviewTest` covers the two decisions, independent human,
tenant/workspace/role/scope/self-review rejection, strict deadline, malformed
identity/token/decision, defensive metadata and diagnostic suppression. It
explicitly demonstrates that a fabricated expected token is still only data.
`BusinessOperationAuthorityProfileTest` covers real policy rule/default outcomes,
copied operator policy, coherent rehashed binding substitutions, unsupported
outcomes, disabled/invalid/oversized configuration and fingerprints. All fixtures
are synthetic; no provider, target or customer input is used.

Passing these application tests is not a durable approval/admission test, provider
qualification, RDS/replica/failover test or production-readiness claim. Runtime
storage APIs and original withdrawal behavior remain unchanged. Execution stays
disabled until the complete closed transaction and admission gates are reviewed.

Initial local qualification is incomplete:13 new contract cases passed and two
deliberate guard-removal controls failed as expected before restoration. The
first broad run failed the unchanged PostgreSQL genuine head-row-lock expiry
case, which unexpectedly accepted the command;244/245 operation cases passed.
Later aggregate gates did not complete. A later bounded Docker observation
showed a large server/Mac clock offset; its relation to the database failure still
requires diagnosis. This failure is retained, not relabeled by focused passes.
