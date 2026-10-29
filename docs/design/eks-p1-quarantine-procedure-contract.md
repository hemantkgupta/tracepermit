# P1 exact-profile quarantine: org procedure contract

Status: **route selected; design packet prepared; org procedure and acceptance pending**.
The human selected an **org-approved authenticated administration procedure**.
This contract does not add an installed admin command, role/scope vocabulary,
token flow, credential source, remote endpoint, grant or schema. Track E and
all existing requester/reviewer decisions remain unchanged.

Source baseline: accepted main
`936ec9724f30eff3e3b2f02c39df8d37c1862ee7`, tree
`f9ef77909cc26cf47419516f39ed2cc17729c308`. PR #146's separate lifecycle
packaging candidate is not a dependency of this contract. The org route choice
is not approval of an as-yet unidentified procedure or a cluster campaign.

The source review pins the existing
[administration helper](../../java/persistence-operations/src/main/java/io/tracepermit/persistence/operations/OperationAuthorityAdministration.java),
[admission boundary](../../java/persistence-operations/src/main/java/io/tracepermit/persistence/operations/JdbcGitHubOperationAdmissionRepository.java),
[full authority profile](../../java/application/src/main/java/io/tracepermit/application/BusinessOperationAuthorityProfile.java)
and [offline acceptance representation](../../java/eks-sandbox-bundle/src/main/java/io/tracepermit/sandbox/eks/EksAcceptanceKit.java).
These are source facts, not fresh test or org acceptance results.

## Fixed administration boundary

The selected procedure must authenticate a named operator using its existing
org-controlled trust root, authorize that identity for the exact P1 writer and
full authority profile/generation, obtain the separately privileged connection
through reviewed custody, and invoke the existing
`OperationAuthorityAdministration.quarantine(DataSource, BusinessOperationAuthorityProfile)`.
The expected profile is reconstructed from independently approved operator
configuration, not from observed business-operation data or supplied booleans.

Private stdin, a kube-context, a database login, a bearer role string, a business
owner, a reviewer, an image digest or a causal parent is not by itself the named
operator's administrative authorization. The procedure must demonstrate the
actual identity-to-policy-to-connection mapping. It cannot accept a caller's
replacement issuer/key/enrollment/policy as its own trust root.

Only the existing conditional ready-to-quarantined transition is in this P1
procedure. Stage/create/activate/replace/recover/upgrade/grant/DDL, generation or
profile changes, lifecycle withdrawal/delivery and generic SQL execution are
excluded. The procedure calls the guarded library; this document supplies no
standalone SQL replacement. Business permits and signing keys are not admin
credentials and must not be reconstructed or requested for quarantine.

The library validates existing authority metadata and the writer connection,
uses READ_COMMITTED with auto-commit disabled, conditions the update on exact
scope hash/profile hash/generation/current ready state, requires one changed row,
and reports success only after COMMIT and connection cleanup acknowledge it.
It neither authenticates the operator nor independently identifies the org's
cluster/PVC/authoritative writer. It cannot prevent an older-backup restore or
fence an already-started sender.

## Non-secret inputs required from the org

Complete the [private worksheet](../../deploy/sandbox/eks/readiness/quarantine-procedure.example.json)
outside Git. Null/unknown values block the stage that depends on them.

| Required reference                             | Must establish                                                                                                                           |
| ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| Procedure name/version, owner and approval     | An actual supported org procedure invoking the guarded Java helper; approval scope and auditability                                      |
| Operator authentication and authorization root | Verified named identity, enrollment/role mapping, exact allowed writer/profile/generation and independent policy custody                 |
| Administrative session policy                  | Validity, revocation, in-flight expiry, lock-wait behavior and how authorization remains valid at the mutation boundary                  |
| Credential custody and identity mapping        | Separate administrator connection identity; how it is acquired/expired/revoked without becoming agent/runtime material                   |
| Operator-to-writer route                       | Exact cluster/context/PVC/writer/storage binding, allowed management channel and assurance against a clone/replica/wrong database        |
| Full profile and generation                    | Trusted full profile recipe plus canonical fingerprint, scope fingerprint and exact current generation; no hash-only placeholder profile |
| Schema and grants                              | Intended existing V7 store, runtime grant restrictions, admin credential's actual privilege breadth and who controls it                  |
| Execution/cleanup bounds                       | Finite procedure/connection/COMMIT/cleanup limits, interruption/termination behavior and the incident owner for uncertainty              |
| Independent observation and audit              | Readback identity/route, redacted retained evidence, custody/retention and named reviewer                                                |
| Denial-probe/workload approval                 | A separately reviewed admission-only probe with no delivery capability, exact cases/fixtures/window and go/no-go owner                   |

No kubeconfig, password, bearer, key, connection string containing credentials,
raw process environment or reusable credential belongs in this record. Evidence
contains credential fingerprints only. Actual enrollment, topology and access
details remain in the org's private approved artifacts.

P1 remains the dedicated PostgreSQL 16.4 loopback-writer topology. The operator's
management route must actually reach that writer under reviewed custody; do not
silently replace runtime numeric-loopback policy with RDS, a remote JDBC URL,
port-forward/tunnel, extra container or new listener. Any necessary new route
must first be reviewed as part of the org procedure/topology. The agent and
lifecycle runtime receive no administrator credential or added access.

The runtime's narrow PostgreSQL UPDATE(lock_marker) permission is not control
mutation authority. Conversely, a database credential with control_state UPDATE
can be broader than this procedure's single transition. Record that breadth
and the trusted-org-operator assumption; do not claim a quarantine-only database
capability without an independently enforced restriction. No new grants/RLS/
stored procedure are introduced by this packet.

## Freshness, waits and deadlines

The shared statement helper uses a five-second query timeout. That is not a
total bound on schema inspection, connection acquisition, COMMIT, rollback or
cleanup. The org must supply and qualify its own finite overall/cleanup
procedure, preserving that statement timeout and all existing runtime bounds.
The business CLI's 60-second supervision plus five-second cleanup does not
provide an admin wrapper here.

The current helper accepts no operator identity or authorization deadline. A
wrapper's pre-call token check therefore does not prove fresh actor authority
after a database lock wait. The org must explicitly document and approve its
administrative-session lifetime/revocation semantics and demonstrate the
enforcement point. Do not claim instantaneous revocation or that expiring a
login token cancels an established database session. If the required semantics
cannot be established with the selected procedure, R3 remains blocked: return
the precise missing boundary for separate review, without a callback seam,
copied transaction logic or silent production-library change.

## Procedure order and result classification

1. Close new submissions through the named org procedure and bind the exact
   reviewed campaign/release/target. Preserve every already-started operation.
2. Establish the named operator's current authorization and custody/route
   evidence before acquiring administrative application access. Verify the
   expected full profile, exact generation and intended writer/V7/PVC binding.
3. Invoke the guarded helper once. Do not change its pins or retry automatically
   after any failure, interruption, missing reply or deadline.
4. Record acknowledgement separately from subsequent independent writer
   observations. Use the readback only for observation, never for reactivation
   or to manufacture acknowledgement of an earlier uncertain call.
5. Observe a fresh admission denial through the approved non-dispatching probe.
   Account for each started business attempt as terminal or unknown; reconcile
   read-only. Observe owned quiescence without deleting the PVC or evidence.
6. Retain independent audit and obtain the named human disposition. Any new
   mutation or broader containment action requires its own incident direction.

| Procedure result        | Exact meaning and next action                                                                                                                                                  |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| NOT_ATTEMPTED           | Operator/target/policy/custody preconditions failed before helper invocation; record the denial and stop                                                                       |
| QUARANTINE_ACKNOWLEDGED | The guarded call returned normally and its result reached the operator after COMMIT and cleanup; still require independent readback and fresh denial                           |
| STATE_STALE_REPORTED    | The helper reported its stale-state rejection; never convert this into acknowledgement of a previous call or implicit idempotent success                                       |
| UNKNOWN                 | COMMIT/cleanup acknowledgement loss, process/transport uncertainty, deadline/interruption or otherwise unestablished completion; keep submissions closed and observe read-only |

Independent readback separately records the exact expected profile/generation
as observed ready, observed quarantined, changed/mismatched, missing or
unavailable. An observed quarantined row may have been changed by another actor;
it is not proof that this invocation acknowledged success. A ready/unavailable
observation does not prove an uncertain write cannot commit later. Storage
errors are not reclassified as successful rollback or absence of mutation.

Quarantine and cleanup do not cancel a target call already started or a paused
sender. The business started-attempt count remains at most one for P1, with
started=terminal+unknown. Unknown remains visible without resend, reconstructed
permit/lease, new attempt or restoration of authority. Admin invocation counts
are separate audit bookkeeping and must not be substituted for business counts.

## Fresh denial probe: no target capability

The probe must call the real admission boundary with otherwise-valid synthetic
owner/operation/review/permit prerequisites and the exact selected profile.
The existing public `JdbcGitHubOperationAdmissionRepository.admit` checks the
current control before admission writes. A reviewed probe may use that boundary
without constructing a delivery repository or target implementation, or calling
a delivery-lease claim or dispatch.
It must not use `deliver-synthetic` to test whether an uncertain stop succeeded:
if authority is still ready, that would permit a fresh target effect.

Use a separately approved disposable probe operation/fixture and independent
database observations. If admission unexpectedly succeeds, record a failed stop
control and stop the probe; never continue to target dispatch or silently delete
the resulting evidence. A profile guard check alone, spent permit, invalid
identity, missing operation or disconnected database is not fresh-admission
denial proof. No installed admission-only probe is shipped by this contract;
its actual org procedure/artifact is a required input before the drill.

## Canonical values and the offline worksheet

For administration and database readback retain the actual canonical Java profile
fingerprint `sha256:H`, where H is 64 lowercase hexadecimal characters. The
existing offline EKS v1 plan's authorityProfileFingerprint and its stop record's
profileFingerprint use the bare H representation. The human procedure must
document that explicit projection, retain the full canonical value alongside H
in the external worksheet, and verify their correspondence. Strip only the
known exact prefix for that worksheet projection; never rehash a fingerprint,
normalize an operator ID, accept another algorithm or substitute H as the
helper's full expected profile. No automatic projection code/schema change is
added here. This representation bridge is an acceptance case, not authority.

The Java profile supports positive generations up to 9,007,199,254,740,991; the
offline EKS v1 worksheet accepts positive 32-bit integers. The selected P1
generation must fit both. Out-of-range values block that worksheet; never clip,
round, cast or silently broaden its parser. Quarantine changes neither profile
hash nor generation. Recovery/activation/replacement remains separately owned.

## Approval and evidence boundary

The route choice is recorded. Procedure/version, identity/custody/route,
authorization-lifetime semantics, deadline/cleanup, actual probe and campaign
approval remain org inputs. A literal "approved" field or filled JSON is not
verified approval. All proposed acceptance cases in the
[review runbook](../runbooks/eks-p1-quarantine-procedure-review.md) are UNRUN.
No existing library test or merged PR qualifies the authenticated org workflow.
The offline assessor still returns REVIEW_REQUIRED/BLOCKED and grants nothing.

Partial requirement mapping: FR-008/009/012/015; SEC-003/006/009/012/019;
REL-002/005/008; OPS-005. No whole-ID promotion. This contract implements no new
trust boundary; an unfulfillable org procedure returns a blocker rather than
silently becoming a new admin tool or production change.
