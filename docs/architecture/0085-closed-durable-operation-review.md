# ADR0085: closed durable GitHub operation review

Status: opt-in V4 library; local synthetic qualification passed, hosted gates
tracked at the exact pushed head. Not wired to
an endpoint, the simulator, a live IdP, admission or a target.

Requirements: FR-003/007/008/012; SEC-001/002/003/005/009/012;
REL-001/002/004/005; OPS-002/004. No whole-requirement completion is claimed.

## Decision

`JdbcGitHubOperationReviewRepository` owns a closed evaluation, independent-human
approve/deny or pending-only withdrawal command. It receives separately trusted
operator policy/review and strict GitHub profiles. It never accepts proposed
JSON, a policy result, an approval deadline, a clock callback, a verified flag or
a transport/provider/target port.

Each call owns one READ COMMITTED writer connection. The exact current-control
row is locked first; the operation head is locked next. Immutable registration,
the closed journal and any review binding are loaded on that same connection.
Fresh writer time is sampled after all those waits. Whole expected state,
current policy/generation, identity, exact group/provenance, membership and
authentication expiry, pending state and approval deadline are rechecked.
Equality is expired; a regressing writer observation refuses.

The existing pure strict GitHub binding is reused on the actual loaded snapshot.
The persistence module depends on the remote adapter module for this closed pure
join; the application module has no reverse adapter dependency. No transport is
constructed or called, and no callback supplies the binding or decision.

## Additive storage and replay

Explicit V4 adds only `tp_operation_review_bindings`: one immutable row per
evaluated operation, including a policy block. It pins the evaluating parent,
profile/settings, exact group/provenance, lifetime and writer evaluation time,
policy result, strict target profile and strict binding digest. Only an
approval-requiring result has a derived pending deadline. No row exists for
withdrawal before evaluation. Bindings remain after review or withdrawal.

The frozen canonical operation authorization still contains only status,
version and updatedAt. V1-V3 migration bytes and the default V2 installation are
unchanged. Explicit empty V4 install and stopped-writer V3-to-V4 upgrade are
separate operator actions. The upgrade validates populated original/current
state before DDL; it does not repair or resume partial MariaDB DDL.

Versioned closed evaluation/review actor envelopes fit the existing actor bound;
the existing exact withdrawal actor remains readable. The coherent MVCC reader
reconstructs each fact at its historical fact time, checks binding cardinality,
closed fields, actor/fact/result hashes and the final head. Unknown vocabulary,
missing or orphan bindings and malformed metadata refuse. History is integrity
data in a trusted database, not cryptographic proof against an administrator.

A strict target digest binds the whole snapshot, so the evaluating and pending
digests legitimately differ. The writer checks the captured digest against its
immutable evaluating anchor, separately binds the current snapshot and repeats
the current policy/profile checks. It never compares two legitimate lifecycle
digests as though they were the same snapshot.

The old head-first withdrawal owner refuses V4 on every command, including a
retained pre-upgrade instance. Detection includes readable migration history:
MariaDB can hide the new table from an old user's metadata when it has no new
SELECT grant. Only the new control-first owner writes V4 commands.
Retained V4 review owners and current readers also recheck the supported installed
version per call, rather than treating a once-valid constructor as permission to
operate after an unknown schema-protocol upgrade. This is not continuous physical
DDL or database-administrator fencing.

## Atomicity and limits

Evaluation's binding, closed fact and conditional head update share one commit.
Review and withdrawal append their fact and update the head in that same owner.
No independently committing repository is composed as though it were atomic.
Success is returned only after commit and connection cleanup acknowledge. A
commit attempt or successful commit followed by uncertain cleanup returns a
fixed unknown outcome; it does not automatically retry or dispatch. Raw driver
causes and suppressed cleanup exceptions are discarded.

Runtime grants add only binding SELECT/INSERT to the existing scoped reads,
lifecycle-fact INSERT, head-column UPDATE and control SELECT (plus PostgreSQL
lock-marker column UPDATE). Binding UPDATE/DELETE, control-pin mutation and DDL
remain operator-only. Limited MariaDB metadata still cannot attest absence of
hidden executable objects; the separate full-visibility operator deployment
prerequisite remains unimplemented.

This is committed review metadata, not a permit or execution capability. Fully
bound single-use admission, an atomic attempt/outbox, evidence publication,
synthetic dispatch composition, live enrollment verification, deployment/restore
and paused-sender fences remain separate gates. See the
[qualification runbook](../runbooks/durable-operation-review.md).
