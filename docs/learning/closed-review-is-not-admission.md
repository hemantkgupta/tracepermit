# Why a valid review command is not execution authority

An authenticated independent reviewer can express approve or deny for one exact
observed operation. That input still does not prove the operation is pending,
the approval is unexpired, policy/recovery generation is current, or a database
transaction committed. A valid expected hash is an optimistic state identity,
not a capability: any caller can calculate or copy it.

The new [closed review contracts](../architecture/0077-closed-operation-authority-contracts.md)
separate three responsibilities:

1. The trusted adapter verifies identity and enrollment and supplies a bounded
   workspace-specific context. Request fields never verify themselves.
2. The transaction owner loads and locks authoritative state, samples database
   time **after** lock acquisition, rechecks all bindings and commits approval
   with its append-only facts. This step is not yet implemented for operations.
3. Separate single-use admission must consume fully bound execution authority
   and create an attempt atomically. Even a committed approval is not execution.

A static copied policy/profile can detect an operation differing from configured
expectations. It cannot detect an unseen policy change in another process or an
older restored database. Its fingerprint is not a signature or current permit.
The review-only profile rejects `allow`/`sanitize`, so a permissive policy result
cannot accidentally bypass human review in this unfinished composition.

The [approved next design](../architecture/0084-durable-review-membership-and-lifetime.md)
also requires exact independently verified group membership, a positive explicit
approval lifetime of at most five minutes with no default, and separate fresh
evaluation permission. All settings need current policy/generation pins. These
rules are not silently implemented by the existing role/scope constructor, and
their approval does not enable live provider calls or a durable decision route.

An exact content hash also needs exact input bytes. Replacement UTF-8 encoding
can map malformed Unicode and a valid question mark to the same bytes before
hashing; the review profile rejects malformed strings first. Valid Unicode is
not normalized. This prevents an input alias, not a cryptographic hash collision,
and does not turn a fingerprint into an authorization capability.

Database readiness has a similar distinction: migration checksums are not
physical-schema proof, and a limited user's zero metadata rows are not proof of
absence. Required columns/keys/CHECKs must be observed through a qualified path
or startup must refuse. MariaDB's table-scoped CHECK inventory is filtered on
the qualified engines; exact bounded declaration validation preserves the same
grants instead of skipping checks. Hidden routines/events/triggers still need a
separate operator deployment prerequisite, not a user-supplied attestation.
See [ADR0083](../architecture/0083-writer-locked-operation-authority-control.md).
None of these checks supplies reviewer authority or continuous DBA-proof fencing.

The current command/profile classes have no database or target port. The
[wave-two board](../planning/wave2-durable-authority-batch.md) names the remaining
durability, admission and integration gates. Existing local simulator approval
flows remain a separate qualified composition, not evidence for this new path.
