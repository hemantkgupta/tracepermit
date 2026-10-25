# ADR0087: fully bound operation permit and atomic admission

Status: proposed implementation contract. The human approved the strict identity,
signature and lifetime choices on 25 October 2026. No operation permit, admission,
attempt/outbox or dispatch implementation is claimed by this decision record.

Requirements: FR-007/008/009/010/012; SEC-001/002/003/004/005/006/009/010/012;
REL-001/002/004/005; DATA-001/004; OPS-002/004. Requirement-wide completion is not
claimed. This follows the opt-in [V4 review owner](0085-closed-durable-operation-review.md)
and preserves its distinction between committed history and fresh authority.

## Approved trust contract

Admission requires a fresh trusted identity for the exact operation owner,
tenant and workspace, with `operation_requester` and the distinct
`operations.execute` permission. Evaluation, cancellation or reviewer permission
alone is insufficient. The adapter must independently verify identity; these
facts must never be deserialized as trusted fields from an action or public body.

A new, domain-separated permit uses only Ed25519, with an explicit operator-owned
issuer, key ID, public key and audience. There is no algorithm negotiation,
legacy permit promotion, request-selected key or key URL, implicit audience,
default lifetime or unverified authority flag. The explicitly configured positive
lifetime is at most 60 seconds. Expiry is also bounded by the original approval
deadline and all identity deadlines required for the command. Equality is expired.
No live calls or dispatch are enabled by approval of this contract.

The concrete bound includes the recorded reviewer authentication and membership
expiries and the fresh owner-execute identity expiry. Fresh exact-owner
authentication at use cannot extend the already signed expiry. A same-credential
requirement or live reviewer re-enrollment is not part of the approved contract.
Whether the original evaluator authentication expiry also limits admission is
an explicit pending human applicability choice, not an implementation default.

## Versioned binding and issuance boundary

The new permit vocabulary must be distinct from the legacy execution permit's
schema1/schema2 action-and-approval profile. Its exact closed claims must bind:

- Trusted scope/owner, operation ID and adapter/audience.
- The actual approved snapshot hash and whole expected state, including its
  authorization version and recovery generation.
- Canonical action/semantic payload, policy release/content, artifact digests,
  resource/precondition binding and target idempotency key hashes.
- Current operator authority/admission settings, strict target-profile hash and
  the strict GitHub digest at the approved snapshot anchor.
- Immutable evaluation-binding hash and the exact approved decision fact/actor
  hashes, not merely a caller-supplied approval label or identifier.
- Single-use permit and attempt identities, exact issue/expiry instants and
  `maxUses=1`.

Names and wire bytes remain to be implemented with independent golden vectors.
The frozen operation and canonical JSON contracts are not rewritten. The strict
GitHub digest is reused rather than copied; it legitimately changes when the
snapshot advances from approved to claimed. Captured claims stay anchored to the
approved parent, while the current loaded state is checked separately.

A closed issuer must load actual authoritative review state and trusted current
configuration itself; it must not sign arbitrary proposed claims or a request's
approved JSON. Any state-read transaction ends, including connection cleanup,
before private-key signing. Issuance is not consumption, admission or permission
to dispatch. State may change after that read; the subsequent admission must
independently reject a stale permit. Raw permits/private keys remain in memory
and never enter stored evidence or diagnostics.

Signing configuration and lifetime must participate in an explicit versioned
operator profile before use. Adding these settings cannot silently promote a
legacy review profile or replace same-generation authority. Exact final profile
and key-rotation behavior need source review and tests before runtime use. Pin
the actual public-key fingerprint as well as key ID; a reused key label is not
unchanged key material. New admission settings must already be present when the
operation is evaluated. Old approvals remain readable but ineligible; obtain a
new operation under explicitly activated newer-generation settings rather than
rewriting its old review binding or inventing an approved-to-pending transition.

## One admission transaction

Verify bounded closed permit syntax and signature without a database lock.
Verification returns privately constructed checked data, not a public
`verified=true` record, caller clock or callback that can manufacture authority.
Time checks performed before locking are preliminary only.

One READ COMMITTED connection owns the complete authority operation:

1. Lock the current profile/recovery control first, then the scoped operation
   head. Load immutable originals, review binding and complete closed history
   on that same connection. New rows must not introduce a conflicting lock
   order with review, withdrawal or operator control changes.
2. After all authority waits, sample fresh writer time in a separate statement.
   Recheck exact expected state, approved/not-started dimensions, policy/profile,
   generation, strict loaded target/action/resource joins, independent approved
   review, requester permission and every required deadline. Reject future issue
   time, equality-expired values and clock regression.
3. Atomically consume single use, allocate the one bound attempt, append the
   execution-to-claimed fact, update the conditional current head and insert
   immutable secret-minimized outbox evidence. No independently committing store
   call may be represented as part of this transaction.
4. Acknowledged success requires both commit and connection cleanup. Commit or
   cleanup uncertainty returns a fixed unknown outcome, never a target
   capability or automatic retry. A later read can reconcile history but cannot
   recreate a lost acknowledgement or authorize resend.

UNIQUE and foreign-key writes can introduce additional implicit waits. The
implementation must either establish that no authority-relevant successful
write waits remain or sample and recheck definitive writer time after those
waits before recording the definitive admission fact. Early explicit-lock time
alone is insufficient; a conflicting holder that rolls back must not allow an
expired later insert to become a valid admission. This obligation requires a
real two-transaction distinguishing test, not a timing comment.

An immutable operation-keyed admission row may combine consumed-permit and
initial-attempt metadata, with unique permit and attempt identities. One operation
still admits at most one attempt even when several permits were issued. The
separate immutable outbox event must bind exact operation/scope, admission,
attempt, fact sequence/hash, actor and resulting snapshot. Its historical
payload derives from those immutable anchors, not today's mutable head. Outbox
presence means neither target dispatch nor evidence delivery. Exact tables and
unique-key conflict classification remain to be implemented: unrelated unique,
FK or CHECK failures must not become replay success or disclose another scope.

The exact additive tables and migration versions require an implementation
checkpoint. Every MariaDB migration unit must be a single reviewed schema
mutation, with explicit stopped-writer upgrade and pre-DDL validation of existing
history. Partial upgrades refuse; runtime never migrates or repairs them.
Default V2 and existing V1-V4 SQL bytes remain unchanged. Required runtime grants
must be table/column scoped, without control-pin mutation, immutable-content
rewrite, DELETE or DDL. Outbox delivery privileges are not admission privileges.

## Reader and execution boundaries

Extend the closed historical reader before writing admission facts. Reconstruct
each fact at its original fact time, enforce binding/attempt/outbox cardinality
and exact hashes, and preserve the immutable registration. Unknown envelopes or
unsupported installed protocols refuse, including retained old owners. Historical
signature fingerprints and database integrity checks are not proof against a
database administrator who can rewrite the complete trusted database. A permit
fingerprint without the signature cannot support a claim of historical signature
re-verification. Persisted checked claims are hash-linked metadata, not a retained
reusable token or an external signed attestation.

The generic execution state becomes `claimed`, not proof of a target call or
success. No target/provider/credential port exists in this slice. A later sender
must independently verify authority and exact preconditions without holding a
database lock. Target acknowledgement, conservative unknown outcomes and read-only
reconciliation remain separate. Approval, a signed permit, an existing attempt,
an outbox row or a successful historical read alone does not prove dispatch.

No admission-time check can atomically fence a worker paused immediately before
an external target call. Recovery isolation, older-backup resurrection, live
enrollment/revocation, privileged deployment inspection and target-side fencing
remain separate qualification gates. No exactly-once target claim is made.

## Required qualification before implementation claims

Use synthetic PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25 with numeric-loopback
owned containers and real least-privilege connections. Cover positive issue and
admission, every immutable binding substitution, wrong signature/type/algorithm/
issuer/key/audience, strict parser bounds, expired and future time, missing fresh
execute permission, pending/denied/cancelled refusal, exact stale token/current
generation, actual control/head wait expiry, 32 independent admission contenders,
one attempt across separately minted permits, and no reversed withdrawal.

Inject each write/rollback/commit/cleanup failure and prove no partial known-
rollback state, no acknowledgement on uncertainty and no reusable permit in
storage/errors. Add compatible current/history reads, populated explicit upgrades,
real permission denials with valid administrative controls and restored
distinguishing regressions. Cross-process tests and live-provider qualification
remain separate evidence; a subset pass must not be reported as complete.
