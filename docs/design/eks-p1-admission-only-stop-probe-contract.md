# P1 admission-only stop probe — proposed contract

Status: **DRAFT; runtime implementation and all probe qualification UNRUN**.
The human authorized preparation of this contract, not its implementation,
validation, publication or use against a cluster. This draft is based on accepted
PR150 main `d9c2657d1cd5e759130d4ebc7c7403d85c9d5698`, tree
`1531e2afc58df260daf27e7c107a3db96e891c5b`. It does not reopen Track E's accepted
identity/key choices or the selected org-owned authenticated admin procedure.

The [quarantine procedure](eks-p1-quarantine-procedure-contract.md) needs a
non-dispatching admission probe for Q16. PR149's
[PostgreSQL component cases](../runbooks/postgres-quarantine-admission-qualification.md)
exercise that library boundary, including a real preissued permit. They do not
ship an installed org probe or qualify an actual EKS stop. Air owns organization
packaging/provenance; Cloud2 owns PR141 integration/verifier work. This draft
changes neither lane nor their shared settings/specification files.

Partial FR-008/009/012/015, SEC-003/006/009/012/019, REL-002/005/008 and OPS-005
are mapped by this contract and its separate qualification plan. No requirement is promoted to accepted.
The existing [ADR0083](../architecture/0083-writer-locked-operation-authority-control.md),
[ADR0087](../architecture/0087-fully-bound-operation-permit-and-admission.md),
[ADR0093](../architecture/0093-source-free-operation-lifecycle.md) and
[ADR0096](../architecture/0096-source-free-organization-profile-synthetic-operator.md)
remain source facts. A new ADR must record approved probe choices before runtime
implementation; no ADR number or shared integration window is reserved here.

## Source facts and the gap

- `OrganizationOperatorRuntime.deliver` issues a permit, admits, constructs a
  synthetic delivery target/repository, claims and dispatches. It cannot be used
  as a stop-denial probe: a still-ready control can produce a target effect.
- `JdbcGitHubOperationPermitIssuer.issue` requires current ready authority and
  an approved operation, releases its read transaction/connection before signing,
  and keeps original captured deadlines. Issuer refusal after quarantine is not
  an admission attempt.
- `JdbcGitHubOperationAdmissionRepository.admit` checks current authority and
  signed/fresh identities, then atomically writes the initial attempt, facts,
  head and outbox. Admission itself is a durable mutation even without dispatch.
- `JdbcGitHubOperationInspector.inspect` also requires ready authority. It is
  unsuitable as the independent post-quarantine readback; no guard bypass is
  proposed. The org supplies a separately reviewed SELECT-only observer.
- Both existing installed parents/workers check `InstalledPrivateInput.anonymousPipe()`.
  Configuration/token/key/command frames require EOF before parsing,
  authentication, JDBC, signing or effects. The existing supervisor consumes
  one final receipt and has a 60-second total, five-second owned cleanup and
  one-second output budget. It cannot silently become a duplex protocol.
- Organization parsing/key verification is package-private. A new launcher
  cannot safely copy it, deserialize TrustedRequesterContext, or assume a
  nonexistent public probe factory. The reuse seam requires a reviewed design.

## Recommended decisions — proposed, not approved

| Decision             | Recommendation                                                                                                                                                                                                                                                       | Alternative / consequence                                                                                                                                                                                                                                                 |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D1: wait/release     | One precommitted timed hold, explicitly 1,000..30,000 milliseconds, after a sanitized PREPARED record. Input is already at EOF. Exactly one admission attempt follows if the parent budget and fresh prerequisites remain valid.                                     | Interactive release needs a separately authenticated/provenanced channel and another protocol review. Do not append a release frame after primary EOF, use a file/socket/signal as hidden authority, or create that channel in v1.                                        |
| D2: reuse/packaging  | A probe-only source set/JAR and distinct distribution in the organization module, using narrow internal shared configuration/key helpers with unchanged accepted validation rules. Existing organization/lifecycle commands stay closed and unchanged.               | Reusing the entire full-operation runtime is forbidden. Copying its trust parser/key logic is forbidden. A new public generic factory or callback would require a new trust review. The small internal factoring and build window must be reviewed before implementation. |
| D3: reported success | Exit 0 means a known expected classification only: calibration admission, or authority-not-ready refusal for an EXPECT_STOP case. It never means STOP_VERIFIED, org acceptance or authorization. Known unexpected admission exits 1 and remains explicitly ADMITTED. | Treating all denial codes or any zero exit as a verified stop would hide expiry, missing controls and unavailable storage.                                                                                                                                                |

D1 deliberately does not wait for human input after PREPARED. Initial input
preauthorizes one bounded synthetic admission attempt after the selected delay.
The org must approve that workload even if quarantine fails or happens late.
Unexpected admission is a failed-stop case with a claimed attempt/outbox, never
permission to continue. If that consequence is unacceptable, do not select v1;
return the need for a separate interactive-channel design instead.

## Proposed product and authority

Profile: `tracepermit-local-synthetic-admission-stop-probe-v1`.
Distribution/launcher: `tracepermit-synthetic-admission-stop-probe`.
Commands: `check-config` and `probe` only. No register/evaluate/review/withdraw,
admin/quarantine/activate/replace/recover, delivery/reconcile, arbitrary command,
callback, plugin or target selector. Existing approved state is prepared through
its already-qualified route before launching this probe.

The expected target is the existing organization synthetic operation profile,
not a caller-selected live GitHub adapter. The same pinned RS256 verification,
tenant/client/subject enrollment, exact operation owner/workspace, policy,
artifact, target and recovery bindings remain required. No supplied identity,
role, membership, authenticated/verified flag or approval is accepted as proof.
Registration remains execution-disabled and does not start the probe.

A per-run Ed25519 private key enters only through its dedicated anonymous frame
and must match the independently pinned admission public key using the existing
proof. It is used to issue one in-memory original permit before the hold; no
permit/key is returned, persisted, passed to a subprocess or reconstructed.
Transient byte buffers are erased where possible; Java key/String memory cannot
be guaranteed zeroized. Do not claim otherwise.

The runtime database identity is admission-only: SELECT on required existing
V7 tables; INSERT only on lifecycle facts, admissions and admission outbox; UPDATE(snapshot_hash,snapshot_json) on lifecycle
heads; PostgreSQL UPDATE(lock_marker) with CHECK(0) on controls. No authority
column UPDATE, control INSERT/DELETE, review-binding INSERT, delivery-event INSERT, DDL or admin role.
The exact minimal applicable grants must be demonstrated with otherwise-valid
controls. This document installs no role or grants and does not broaden the
org's existing runtime identity. There is no admin credential frame.

## Closed private input

Keep existing installed protocols/profile bytes unchanged. A new profile uses
big-endian signed 32-bit positive length framing:

- `check-config`: one probe configuration frame, then mandatory EOF; offline
  syntax/pin construction only, no authentication/JDBC/signing.
- `probe`: probe configuration, requester bearer, binary Ed25519 PKCS8 key,
  command JSON, then mandatory EOF. All are read completely before any effects.

Proposed limits retain configuration 65,536 bytes, bearer 16,384, key 128,
command 1,024; JSON depth 12, field-name 64, string 16,384, number 32. Reject
unknown/duplicate fields, trailing tokens/bytes, malformed UTF-8, scalar coercion,
noncanonical UUIDs, overflows, omitted bounds and unknown profiles/versions.
The child environment is cleared; argv contains only the fixed command.
Parent and worker independently prove the actual anonymous descriptor. Files,
TTYs, named FIFOs and sockets do not become acceptable merely by claiming privacy.

The new minimal configuration view reuses the accepted organization authority,
requester enrollment/provider, scope, target and operations database validation.
It must not request replay-store, reviewer, registration or admin credentials
that the probe never uses. Some non-secret profile/review pins are still needed
for the exact approved authority recipe. Existing OrganizationOperatorConfig
requires more fields, so a narrow internal factoring is an explicit implementation
gate. No grammar/defaults or new issuer/key root is invented in this draft.

Command JSON exact fields: schemaVersion, profile, case, probeRunId,
operationId, waitMillis. `case` is CALIBRATE_READY or EXPECT_STOP; it is only a
test expectation, not authority. UUIDs are canonical. waitMillis is an explicit
integer 1,000..30,000 with no default. No token/key, expected denial boolean,
observed quarantine flag, synthetic target outcome or caller deadline override
is accepted in command JSON.

## State machine and timed attempt

1. Establish parent/worker provenance and complete EOF. Parse the closed input,
   verify the requester and key match, validate the existing V7 store and exact
   profile, load current approved/unstarted state and construct the admission
   repository. Reject already admitted/started/unknown or unapproved work.
2. Issue exactly one original signed permit through the real issuer. Any
   preparation error emits only a terminal failure; no PREPARED record and no
   admission-denial claim. Close/rollback the preparation connection before
   signing, as the accepted issuer already does.
3. Emit one sanitized PREPARED record within the original total budget. The
   record proves this process reached preparation, not quarantine, operator
   acknowledgement, remaining permit lifetime or a guarantee that admission
   will happen. If sending fails, stop; parent/cleanup uncertainty stays UNKNOWN.
4. Wait the exact requested interval using a monotonic clock, without any held
   JDBC connection/transaction/lock. No polling, new token, key, permit, approval
   refresh, restart or lifetime extension is allowed. The interval begins at
   worker emission, not human receipt; parent forwarding can be delayed.
5. If still within the total budget, reverify the same retained requester
   bearer without extending its expiry. Call the real admission method once
   using the captured approved expectation and original in-memory permit.
   All writer-time/identity/permit/approval checks and five-second SQL bounds
   remain in the accepted libraries. No client timestamp substitutes for them.
6. Emit one terminal classification and end with owned cleanup. Never construct
   a delivery repository, target or lease, call claim/dispatch, inspect through
   a bypass, or launch another command. No mutation or retry follows UNKNOWN.

The parent retains 60 seconds overall, reserving the final one-second receipt
send inside that bound, plus the existing five-second cleanup allowance.
Reading, JVM startup, parsing, authentication, schema checks, signing, PREPARED,
timed hold, admission, receipt and supervision all consume the same total.
The hold is not an extra 30 seconds outside it. No new longer query, connection,
socket, identity, review or permit bound is supplied. If the original permit
expires during preparation/hold/wait, report that failure as inconclusive for
stop qualification. No automatic repeat or shorter substituted hold.

The existing supervisor assumes one record. A probe-specific bounded parser/
supervisor must accept only an optional PREPARED followed by exactly one final
record, with no extra/trailing output and no stderr. PREPARED is capped at 1,024
bytes, final at 2,048; at most 3,072 stdout bytes per run, bounded by the same
parent deadline. This is a supervision/result budget, not an atomic fence on
an in-flight JDBC mutation. Killing a timed-out process does not prove rollback
or absence of a later commit; retain UNKNOWN and use independent observation.
The worker/parent scheduling-bound implementation must be reviewed explicitly;
no client time or caller-supplied budget becomes admission authority.
Existing supervisor/protocol budgets are not changed. A failed
or missing terminal record, extra output, interruption or cleanup failure
produces parent UNKNOWN, even after observing PREPARED or an admitted state.

## Proposed receipts and exits

Common exact fields: schemaVersion, profile, command, phase, case, probeRunId,
operationId, authorityProfileFingerprint, expectedProfileFingerprint,
targetExecutionEnabled, automaticRetryAllowed. Last two are always false.
Identity fields may be null only when unavailable because input/parent state is
unestablished; never echo unvalidated strings. These receipts are observations,
not authority tokens or evidence attestations.

PREPARED adds waitMillis and has phase PREPARED. It contains no raw permit,
permit id/key, token, operation action, SQL, database password, provider payload,
remaining-validity assertion or claimed quarantine status.

Terminal adds outcome and admissionAttempted (true/false/null). Closed outcomes:
CONFIG_VALID; INPUT_INVALID; CONFIG_INVALID; PREPARATION_REFUSED;
AUTHENTICATION_EXPIRED; PERMIT_EXPIRED; REFUSED_AUTHORITY_NOT_READY;
REFUSED_OTHER; ADMITTED; UNKNOWN; DEADLINE; USAGE. Exact code mapping requires
reviewed implementation tests; no catch-all exception text or cause is emitted.
An adapter that returns a generic rejection must not be diagnosed as expiry
without a typed boundary result; use a non-stop-refusal classification and
keep admissionAttempted=false. A failure before admission is never labelled
REFUSED_AUTHORITY_NOT_READY merely
because the issuer reported that code. DEADLINE is only for known pre-attempt
budget exhaustion; uncertain in-flight completion is UNKNOWN.

Exit0: CONFIG_VALID, CALIBRATE_READY+acknowledged ADMITTED, or
EXPECT_STOP+actual admission REFUSED_AUTHORITY_NOT_READY. Exit64: invalid argv
usage only. Exit1: every other case, including EXPECT_STOP+ADMITTED. A known
unexpected admission remains ADMITTED with attempt/outbox observation, not a
false rollback, denial or generic UNKNOWN. Parent transport/cleanup uncertainty
can still obscure completion; independent observation does not rewrite that
parent acknowledgement. No exit/outcome is STOP_VERIFIED or P1_ACCEPTED.

## Independent stop evidence

The org-owned procedure remains separately authenticated, approved and bounded;
this probe never calls it or receives its credentials. To support Q16, a named
independent observer must establish all of:

- A distinct ready positive calibration and exact source/artifact/input pins.
- Legitimate approval/unspent permit preparation before the stop, followed by
  acknowledged exact quarantine and the actual attempt inside original bounds.
- Exact independently observed quarantined scope/profile/generation, excluding
  missing/staged/mismatched/unavailable controls as alternative explanations.
- Before/after scoped operation/admission/outbox/fact/head/delivery observations;
  actual expected refusal and no changed per-operation data for that invocation.
- Actual timestamps/window, observer route/identity, audit custody and reviewer;
  no timestamp freshening or treating a hash/filled worksheet as proof.

Use the reviewed SELECT-only org observer, not the ready-only product inspector.
The probe does not attest those external observations. Other actors' writes,
expired prerequisites, late quarantine, missing evidence and unclear ordering
remain failed/inconclusive stop cases. A running admission before quarantine can
win its transaction; neither this probe nor the helper cancels a prior attempt.
Already-started work remains terminal or unknown with no resend.

## Packaging, ownership and implementation gate

Proposed new probe-only source set lives under
`java/agentcore-organization-lifecycle-cli/src/admissionProbe/`, with a separate
JAR/distribution/Java8 early-rejection bootstrap and Java21 worker. It must not
add a command to existing organization/lifecycle launchers or put probe classes
in their main JAR. Internal package reuse/classloader and shared minimal config/
key helper factoring must be demonstrated rather than assumed. No arbitrary
configuration/provider factory or delivery runtime is exposed. If safe factoring
cannot preserve accepted behavior, stop with the exact gap before copying rules.

Future changes to the org Gradle file/internal helpers require a separately
allocated integration window after Air's custody handoff. Cloud2's verifier,
archive-input manifests, shared settings/specs/ADR union and legacy negatives
remain separate. The accepted JDK-only USTAR producer may be reused build-only;
no parser widening or old-digest reuse. Both existing and new distribution
configuration-cache/format behavior need human qualification after implementation.

PR150's existing image/archive does not contain an implemented probe. The
current closed EKS bundle also has no reviewed probe launcher/job binding.
A later separately reviewed, digest-pinned probe image and closed suspended-Job
profile are prerequisites for any EKS drill. Do not smuggle a shell/script,
replace the organization command, widen the existing plan grammar or deploy
this design as if it were an installed artifact.

This draft changes only separate proposal/learning/runbook/template files.
Shared HANDOFF/specification/ADR integration is supplied as an unapplied private
proposal while Cloud2 owns that window. See the
[qualification plan](../runbooks/eks-p1-admission-only-stop-probe-qualification.md)
and [blank review record](../../deploy/sandbox/eks/readiness/admission-only-probe-review.example.json).
