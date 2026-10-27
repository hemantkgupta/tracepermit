# ADR0090: closed durable synthetic delivery and read-only reconciliation

Status: reserved by Track A; implementation and qualification in progress.

Requirements: FR-007/008/009/010/012; SEC-001/002/003/004/005/006/009/012;
REL-001/002/004/005; DATA-001/004; OPS-002/004. No requirement-wide promotion.

## Approved boundary and allocation

Track A starts at accepted main `8d5a5d784fdb9adce632e2d418c702940c13d2a0`,
tree `84f9f0b6b84f437d11521c38743d53cd20223fd9`. It owns operations delivery,
its reader/migration/tests and these docs. Track B owns HTTP plus durable
registration composition and ADR0091; none of B's runtime/tests are modified.
Existing V1–V6 SQL and default V2 behavior remain unchanged.

Necessary shared compatibility edits are limited to V7 protocol/schema routing,
delivery-aware historical replay, additive fixed diagnostics and the V7-only
claim lock mode. Existing control callers keep the blocking implementation.
Permit recapture retains its approved-head comparison for existing issuance and
admission callers; only delivery, which already checked the locked admitted head,
uses the internal recapture variant. All signed/history/binding checks remain.

The human approved a writer-time lease through 30 seconds, capped by all original
permit/approval/identity deadlines, with equality expired. A lease never renews
authority. After `dispatch_started`, no automatic retry, new attempt or resend
is allowed, including lost acknowledgements, crashes and expired leases.

## Design checkpoint

An explicit V7 adds one append-only delivery-event table per engine. Claims
advance a monotonically increasing fence under control-first/head-next locks;
an expired pre-dispatch lease may be recovered while original authority remains
valid. Immutable events bind scope, operation, admission, outbox, attempt,
admitted snapshot, predecessor, fence and exact lifecycle facts/heads.

The closed historical reader must understand all new envelopes before the writer
can record them. Runtime only validates V7. Explicit stopped-writer upgrades
validate populated V6 history before DDL; partial/unsupported protocols refuse.
MariaDB V7 is one reviewed CREATE TABLE; runtime requires INSERT/SELECT only on
the new journal, never UPDATE/DELETE/DDL or control-pin privileges.

Claim/start revalidate actual stored approved/admitted history, signatures and
fingerprints, current operator pins/generation, exact strict target/action/
policy/artifact/precondition bindings, fresh owner execute identity and original
deadlines. Fresh writer time is checked after every authority-relevant wait and
write. Start records `dispatch_started` plus fact/head atomically; commit AND
connection cleanup must acknowledge before any target invocation. No database
lock spans a target call. Uncertainty returns no dispatch capability.

Only a concrete deterministic synthetic target is accepted, with immutable
operator-selected expected target pins and no provider, credential, clock,
signer or authority callback. Success, rejection and unknown remain distinct.
Read-only reconciliation inspects durable and simulated metadata and cannot
create authority, alter history, renew a permit or trigger another invocation.

## Distinguishing verification and limits

Required fresh tests cover all three exact engines with restricted users;
32 independent contenders; separately minted permits; stale fences/recovery;
explicit and implicit wait expiry; crash points; every write/rollback/commit/
cleanup fault; populated upgrade and closed-reader corruption; actual privilege
denials with administrative positive controls. Guard-removal controls must fail
before the original code is restored. Qualification is not yet claimed.

A worker paused immediately before a target call is not atomically fenced by a
database lease. This synthetic slice makes no exactly-once external-effect,
instant revocation, older-backup, physical-schema deployment-attestation or live
GitHub/AWS/Jira/AgentCore/IdP compatibility claim. No endpoint or default runtime
is enabled. Raw permits/bearer values/private credentials never enter evidence.
The human has merged B into main221be9f599c2ee558041466ac5132a40bf540a2c.
The stopped A test cycle remains original-base evidence. A later human-authorized
publication refresh normally merges that exact main, preserving both sides of
four documentation conflicts and all accepted B runtime/test bytes. Those old
tests do not qualify the refreshed composition; known failures, skips, unrun gates
and the pending metadata-type review are disclosed. A draft is not finally
merge-ready and does not authorize check bypass or final merge.

## Current-main historical principal-type review

The next normal ancestry refresh imports main02a7f27, retaining A/V7 and
C/ADR0092 documentation. One bounded real restricted MariaDB10.6 diagnostic
accepted coherently rehashed V7 lease/start identities containing numeric123 or
booleantrue when the actual scoped owner was the legitimate String123 or
Stringtrue. Typed-review, wrong-owner and stale-hash controls refused; target
invocations remained zero. The mutation required administrative fixture writes
to immutable history; no request or execution-authority bypass was demonstrated.

The narrow repair requires principalId.isTextual before the existing exact owner
comparison. It does not normalize, repair or rewrite stored history. Positive
String owners and coherently linked matching/wrong scalar mutations are covered
by the existing three-engine delivery fixture. No new authority, grant, lease,
deadline, migration or parser policy is introduced. Admission/legacy actor
asText patterns remain source-review observations without a dynamic proof here.
Focused evidence and current CI belong to the exact-head public handoff; original
local/hosted failures, skips and unestablished aggregate coverage remain disclosed.
The human authorized this scoped repair and known-failure checkpoint; root owns
the separate final-merge disposition, with no green or check-bypass claim.
