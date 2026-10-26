# Closed operation admission — opt-in V6 library

This is the separate execution-disabled admission slice of
[ADR0087](../architecture/0087-fully-bound-operation-permit-and-admission.md).
It is not a sender, authenticated endpoint, target adapter composition or live
provider qualification. The complete operation matrix and aggregate checks
passed locally. The scoped evidence below is not a complete sandbox or production
acceptance claim; independent and exact-head hosted results remain separate.

## Explicit installation and upgrade

The default installation stays V2. Existing V1–V4 SQL and profile-v1/v2 recipes
are unchanged. An operator with all writers stopped may explicitly call
`OperationDatabase.initializeAdmissionEmpty` on an empty dedicated database,
or `upgradeAdmissionFromV4` on a validated populated V4 database.

V5 contains exactly one CREATE TABLE for immutable operation-keyed admission
metadata, with unique permit and attempt identities. V6 contains exactly one
CREATE TABLE for its immutable outbox. Each MariaDB migration unit is a single
schema mutation. A partial V5 installation refuses rather than falling back to
V4, repairing, resuming DDL or allowing runtime migration. Populated V4 originals,
bindings, complete facts and current heads are replayed before either DDL unit;
the read transaction and cleanup end before migration/validation borrows.

The new review/issuer build reads supported V4 or V6 protocols. Admission requires
the complete successful V6 protocol at startup and on every command. Unknown,
failed or repeatable latest entries refuse. Older deployed V4-only review owners
must still refuse V6; do not run an old writer through an upgrade. This declared
version guard is not a continuous DBA/DDL or restore fence.

Runtime needs SELECT on the dedicated tables, INSERT on immutable lifecycle
facts/review bindings/admissions/outbox, and UPDATE of only the two head columns.
PostgreSQL also needs the existing control-row `UPDATE(lock_marker)` lock
privilege. It needs no immutable UPDATE/DELETE, control-pin mutation or DDL.
Separate operator and runtime users remain required; no grant broadening solves
metadata visibility. Hidden MariaDB executable-object deployment inspection is
still a separate unimplemented privileged prerequisite.

## One closed command, no dispatch

`JdbcGitHubOperationAdmissionRepository` takes a trusted operator profile and the
existing strict GitHub expected profile. The caller supplies a fresh trusted
exact-owner `BusinessOperationExecution` and a sensitive in-memory serialized
permit. No constructor or request-body flag verifies identity. There is no
public route, arbitrary proposal, clock/signer callback or target port.

The bounded closed Ed25519 codec verifies the signature before database locks.
It is the same new codec used by the [closed issuer](closed-operation-permit-issuance.md),
not a legacy permit or caller-manufactured verified record. The transaction then:

1. Owns one READ COMMITTED writer connection; locks current control first and
   the actual scoped head next, loading complete closed immutable history.
2. Samples writer time and rechecks exact expected approved state, original
   review anchors, current policy/profile/generation, actual strict GitHub
   bindings, fresh owner execute permission and every signed/identity deadline.
   A different fresh credential is allowed, but cannot extend signed expiry.
3. Inserts provisional immutable consumption/initial-attempt metadata. The
   operation key permits at most one attempt even across separately minted
   permits; permit and attempt UNIQUE constraints are additional arbiters.
4. Samples and rechecks time after that INSERT, then writes the claimed fact,
   conditional head and exact immutable outbox in the SAME transaction.
5. Samples and rechecks definitive time AGAIN after all writes, including any
   implicit fact/outbox UNIQUE or FK waits. An expired waiter rolls back all
   provisional rows. No early lock-time sample is treated as definitive.
6. Returns claimed historical metadata only after commit AND connection cleanup
   acknowledge. Commit/cleanup uncertainty returns fixed `OPERATION_COMMIT_UNKNOWN`,
   never dispatch permission or automatic retry.

All bindings reuse the existing claims/strict binding recipes. Fresh capture at
use is compared with the signed approved-parent anchors; current identity may
change, but scope/owner and all original deadline minima remain bound. No signing
or network occurs under database locks. Generic SQL duplicates/FK/CHECK failures
roll back as fixed storage failures, never replay success. A repeated old command
after an acknowledged claim is stale, not a second successful admission.

There is no guarantee that a returned result is a still-live response lease, that
a paused sender is fenced, or that a target was called exactly once. No sender
exists. A later read cannot recreate a lost acknowledgement or authorize resend.

## Closed historical reader

The existing coherent reader supports the new closed admission actor before the
writer records it. It requires exactly one matching admission and outbox for a
claimed fact, none for pre-admission history, and exact operation/scope/attempt,
approved-parent state, review/fact/actor/result hashes and original deadlines.
Authorization/evidence dimensions and original registration remain unchanged.
Historical reconstruction uses fact time, not today's authentication expiry.

The database stores only the serialized permit's fingerprint plus closed claims
and credential fingerprints, never a reusable token, signature or private key.
Outbox metadata is reconstructed from immutable admission/fact/result anchors,
not today's mutable head. It carries no action arguments, target responses or
raw credentials. It proves neither delivery nor external evidence retention.

Stored claims and hashes are integrity metadata within the trusted database,
not historical signature re-verification or proof against an administrator who
can rewrite the entire database. The historical reader does not manufacture
current operator configuration from stored claims or grant current authority.

## Local qualification and retained failures

Use Java AND javac21, Node22+, pnpm11.25.0 and owned numeric-loopback containers
for PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25. One heavy build per physical
machine; `--max-workers=1` does not reduce the internal 32 contenders.

`OperationAdmissionContract` and its three exact-engine wrappers cover actual
admission/reader composition, one attempt across four permits and32 contenders,
signed binding substitutions, future/expired permits and expired fresh owner,
quarantine, each write rollback, commit/close uncertainty, distinct fresh
credentials, missing outbox, administrative-positive privilege denials and
populated pre-DDL validation. Protocol/partial-upgrade cases are separate.

The real UNIQUE-wait case inserts a conflicting permit identity under a different
owned operation in an uncommitted transaction. It observes the actual database
lock wait while authority is still live, holds past signed expiry, then rolls
back the holder. The successful later INSERT must NOT admit. Removing both
post-write authority rechecks made all3 engine cases fail; the production file
was restored byte-identically with SHA-256
`b6dd026d0094e1fac9b3c07eb9149cf2b4fd970a6f7cc1e65072401a3e770eca`.
No deliberate fault remains.

Retain these candidate failures, not production-bypass claims:

- First new-fixture compilation used a nonexistent `quarantineCurrent` name;
  source inspection corrected it to the existing `quarantine` API. No tests ran
  in that failed invocation.
- Initial18 focused cases passed15/failed3 because Jackson integer-node widths
  made equal canonical authorization bytes unequal as Java objects. Canonical
  byte assertions corrected the new fixture; the next21 focused cases passed.
- The expanded33-case run passed30/failed3 because the administrative DELETE
  positive control still had an outbox FK dependent. Deleting that owned
  dependent within the rolled-back administrative control made the exact3
  grant cases pass. Runtime grants/FKs were unchanged. Then36 focused cases
  passed across the three engines, including populated upgrades.
- The next6 protocol cases passed the3 real partial-V5 cases, but the3
  repeatable-history cases mapped a null version to storage-unavailable because
  Java's immutable set rejects `contains(null)`. The candidate already refused;
  an explicit null guard preserves the intended schema-not-ready diagnostic.
  The corrected6 focused protocol cases passed across all3 engines in48s
  (2 tasks executed/16 up-to-date). The complete matrix remains separate evidence.

Initial failure XML is preserved outside Git in
`/private/tmp/tp108-admission-initial.csV8bx` and
`/private/tmp/tp108-admission-grant-fixture.Bcc6kr`; distinguishing-control XML is
in `/private/tmp/tp108-admission-control.zPRbBU`. These are local receipts, not
transferable files or green aggregate evidence. Earlier unrelated failures and
cache/skip disclosures remain in the [issuance](closed-operation-permit-issuance.md)
and [review](durable-operation-review.md) histories.

The final forced/cache-disabled COMPLETE operation matrix passed451 cases across
15 suites, zero failures/errors/skips;18 tasks executed in8m21. This includes all
42 new admission cases,14 per engine, plus the409 pre-existing operation cases.
Final `pnpm check` PASS:124 React and2060 recorded Java cases across159 suites,
zero failures/errors and one existing absent-archive/native-PTY skip. It reused
the fresh451 operation cases and other valid prior outputs; the Gradle portion
took4m15,4 tasks executed/61 up-to-date, NOT all tests fresh. Coverage94.7650%
line/78.2000% branch passed unchanged94/75 thresholds. Final format,1182 links plus3
helper cases and13 flow/drift cases passed after documentation edits.
Exact new-head hosted checks and independent qualification remain separate.
No cross-process admission, live provider,
installed archive, RDS/failover, S3 sink, privileged deployment inspection,
restore isolation or paused-sender qualification is claimed.
