# Requirements traceability matrix

## Track A V7 closed synthetic delivery — qualification in progress

Partial FR-007/008/009/010/012, SEC-001/002/003/004/005/006/009/012,
REL-001/002/004/005 and DATA-001/004 evidence is being developed in the
`persistence-operations` V7 journal/reader/synthetic sender and three-engine
`OperationDeliveryContract`. [ADR0090](../architecture/0090-closed-durable-synthetic-delivery.md)
and its [runbook](../runbooks/closed-synthetic-operation-delivery.md) define capped
writer-time leases, exact fencing, acknowledged-start/no-resend and read-only
reconciliation. No whole requirement status, passing qualification, live target,
endpoint/default runtime or final merge readiness is promoted.

## Closed durable numeric-loopback AgentCore registration composition

Requirements: FR-002/003/008/010/011/012;
SEC-001/002/003/004/006/009/012; REL-001/002/003/004/005/009; DATA-001;
OPS-002/004; PORT-004. Partial evidence only; no whole-ID status promotion.

`DurableAgentCoreRegistrationHttpServer` composes the existing concrete durable
factory and numeric-loopback HTTP server without adding a verifier, replay,
repository or authority callback seam. `DurableRegistrationHttpContract` runs on
exact PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25 with real raw loopback
HTTP, cached synthetic JWKS parsing, signed tokens, restricted runtime grants and
both dedicated stores. Tests cover minimal initial metadata, independent-server
single-token races, same-key immutable recovery/conflict/proposal distinctions,
terminated child-JVM replay, identity/body/human-pair rejection, saturation and
unready schemas, replay and registration write/commit/close faults, independent
observer uncertainty and interruption-ignoring timeout/capacity behavior.

The two databases are not one transaction. A network timeout is not a rollback
receipt; a spent credential is never retried automatically; explicit fresh-token
same-key recovery returns metadata only. The composition creates no approval,
permit, admission, attempt, outbox, target credential or execution authority and
has no launcher/default wiring. Live provider compatibility, TLS/private edge,
revocation, database failover/restore and production qualification remain open.
See [ADR0091](../architecture/0091-durable-agentcore-registration-http-composition.md),
the [runbook](../runbooks/durable-agentcore-registration-http.md) and the
[timeout note](../learning/registration-timeout-is-not-rollback.md).

## Source-free local synthetic registration command

Requirements: FR-003/008/011/012; SEC-001/003/006/009/012; REL-001/002/004;
OPS-002/004. Partial evidence only, with no whole-ID status promotion.

`agentcore-registration-cli` adds a source-free Java21 distribution around the
unchanged concrete durable factory. `OperatorConfig`/`Protocol` enforce separate
closed trusted/untrusted pipe frames and exact numeric-loopback driver settings;
`Worker` constructs the offline pinned real verifier and invokes registration
once. `Supervisor` bounds owned worker/read/write/cleanup lifecycle and requires
an acknowledged closed receipt. `BoundaryTest` guards offline DNS/network/JDBC;
`InstalledRegistrationContract` covers installed three-engine restart, signature,
enrollment, schema/capacity/privilege controls, both32-process races and real
commit-reply loss/death/readback denial. `SupervisorTest`/`ArchiveBoundaryTest`
cover positive stalls, pressure/interrupt/exit and source-free inventory/hash.
The [runbook](../runbooks/source-free-synthetic-registration-cli.md) records actual
qualification status and failures; implemented tests are not a pass claim.
See [ADR0089](../architecture/0089-source-free-synthetic-registration-cli.md).
No live enrollment, human delegation, approval, admission, permit, target,
revocation, restore/failover fence or production-readiness claim follows.

## Bounded Okta public/native foundation

FR-003/007/012; SEC-002/003/004/009/011/012/015; REL-001/004/009;
OPS-002/004/005 receive bounded synthetic evidence through
`OidcTokenProfile`, operator schema1.2, `OidcAccessTokenVerifier`,
`BrowserIdTokenVerifier` and attenuating `ApprovalMemberships.resolve`.
`OktaOidcQualificationTest`, `OktaApprovalConfigurationTest` and
`OktaApprovalBrowserTest` cover explicit configuration, generated signatures,
group/tenant/client/audience/scope/time/key negatives, rotation/expiry,
requester/reviewer/viewer isolation, independent enrollment/human status,
fresh-token group removal, current enrollment removal, request-role injection,
mixed authentication, redirect/code/verifier substitution and concurrent
single-exchange callback replay. Existing Auth0, standard OIDC, TLS/SSRF,
diagnostic-redaction and durable simulator tests remain mandatory.

[ADR0067](../architecture/0067-bounded-okta-oidc-qualification.md) and the
[operator runbook](../runbooks/okta-oidc-qualification.md) distinguish the
implemented foundation from the unperformed live gate. Raw groups/claims never
become API role input or stored authority. Only explicitly mapped roles narrow
trusted enrollment; provider removal is not instantaneous old-token revocation.
No whole-ID status is promoted. Okta tenant mutation/login, EKS ingress, real
targets, ADR0065 installed helper compatibility and broad IdP compatibility
remain unqualified.

## Durable execution-disabled AgentCore registration composition

Requirements: FR-003/008/011/012; SEC-001/003/006/009/012; REL-001/002/004;
OPS-002/004. Partial evidence only, with no whole-ID status promotion.

`DurableAgentCoreRegistration` internally joins the concrete verifier/ingress,
`JdbcWorkloadIdentityReplayStore` and `JdbcBusinessOperationRepository`, validating
separate initialized stores without migration or in-memory fallback. Replay and
registration commit separately; uncertainty never authorizes automatic retry.
The minimal original-return receipt is always execution-disabled, not human
delegation, approval, admission or a permit. `DurableRegistrationContract` runs
35 equivalent cases on each exact PostgreSQL16.4/MariaDB11.4.13/MariaDB10.6.25
profile: real signature/enrollment/body rejection, 32-way shared replay and
request-key contention, genuine two-JVM restart and non-durable control,
capacity/schema/permission refusal, partial-write rollback, committed-but-unknown
acknowledgements, real lock/expiry waits, fixed diagnostics and resource cleanup.
Datasource/network bounds, partial schema validation, live provider compatibility,
revocation, restoration/failover fencing and all target authority remain separate.
See [ADR0086](../architecture/0086-durable-agentcore-registration-composition.md)
and the [commands/evidence/limits runbook](../runbooks/agentcore-durable-registration-qualification.md).

## Durable workload-identity replay store

Requirements: FR-008/011; SEC-006; REL-001/004; OPS-004. No whole-ID status promotion.

`io.tracepermit.persistence.identity.JdbcWorkloadIdentityReplayStore` implements
the `WorkloadIdentityReplayStore` port across PostgreSQL 16.4 and MariaDB 11.4.13/10.6.25.
Compound keys (`tenantId`, `issuer`, `tokenId`) are hashed using a versioned length-delimited
domain-separated recipe (`tracepermit:workload-identity-replay:v1\0`) with big-endian length
prefixes, strict field length limits and surrogate validation without echoing values.
Persistence stores only bounded SHA-256 fingerprints (`claim_hash`, `tenant_hash`,
`token_fingerprint`) and microsecond timestamps (`expires_at`, `claimed_at`). Plain unique
insertion ensures exactly one winner under contention; duplicate-key errors represent replay;
expired/equal deadlines fail closed with zero writes; replays never refresh expiry.
Finite capacity enforces fail-closed saturation via agreed operator capacity in schema V2
(`tp_workload_replay_state.capacity`, default `10,000`) and atomic counter updates without evicting live unexpired claims.
Initial verification checks physical column definitions, nullability, collations (`"C"` / `ascii_bin`),
and primary keys directly against database metadata, rejecting drift or unmigrated structures.
Store startup enforces capacity agreement and fails closed with `REPLAY_CAPACITY_MISMATCH` on conflict.
Preserved V1 migration bytes support explicit `upgradeFromV1`.
Finite statement query timeouts are applied throughout. Post-wait validity checks verify caller-trusted
monotonic elapsed time against unchanged `expiresAt`; contention crossing expiry rolls back and returns
`false`, while late commit or cleanup delays past expiry fail closed as `REPLAY_COMMIT_UNKNOWN`.
`WorkloadIdentityReplayException` disables cause retention (`initCause = this`) and exception suppression
(`enableSuppression = false`) with message-less constructors, guaranteeing zero leakage of database
credentials or canary diagnostics. Real-engine test suite `WorkloadIdentityReplayStoreContract` (25 tests each,
75 total) plus `WorkloadIdentityReplayHashTest` (10 tests) qualify PostgreSQL 16.4 and MariaDB 11.4.13/10.6.25 (85 total).
See [ADR 0080](../architecture/0080-durable-workload-identity-replay.md),
[runbook](../runbooks/workload-identity-replay-store.md) and
[learning note](../learning/workload-replay-is-not-admission.md).

This is a standalone persistence library: it does not verify credentials, evaluate policies,
grant execution authority, execute target operations, or wire into AgentCore ingress.

## Numeric-loopback registration HTTP candidate

Requirements: FR-002/003/010/012; SEC-001/002/003/004/009/012;
REL-001/002/003/005/009; DATA-001; OPS-002/004; PORT-004. No whole-ID promotion.
The new `agentcore-registration-http` library binds only numeric127.0.0.1 and
routes one closed HTTP1.1 POST through the existing real registration ingress.
NIO deadlines, stream/frame bounds, fixed worker/pending/connection capacity,
queue cancellation and bounded close preserve metadata uncertainty; receipts
contain only creation status/UUID/execution-disabled flag. Actual local-JWKS
signed HTTP/JDBC tests cover all three exact engines, initial records and
fresh-token retries/conflicts/acknowledgement loss. Socket tests cover slow and
oversized input, duplicate/mixed identity, default saturation and cancellation;
guard-removal evidence and exact-head results belong to the PR handoff.

This is not a default launcher, public/TLS deployment, provider interoperability,
OBO, shared durable replay, revocation, approval/admission/dispatch or production
qualification. In-flight calls can finish metadata after transport timeout and
retain capacity until actual completion. See
[ADR0082](../architecture/0082-loopback-agentcore-registration-http.md),
[runbook](../runbooks/agentcore-loopback-registration-http.md) and
[timeout explanation](../learning/registration-timeout-is-not-rollback.md).

## Ingress-to-JDBC registration qualification

Requirements: FR-002/003/010/012; SEC-001/002/003/004/009/012;
REL-001/002/003/005/009; DATA-001; OPS-002/004; PORT-004. Test-only follow-up,
with no runtime or whole-requirement status change. The new
`agentcore.jdbc.AgentCoreJdbcRegistrationContract` runs the real verifier,
ingress and JDBC repository against required PostgreSQL16.4 and
MariaDB11.4.13/10.6.25 fixtures under restricted runtime grants. Direct reads
check the original, three initial facts, exact head and no lifecycle journal;
scoped golden/retry/reconstruction, actual permission failure, forwarded-JDBC
write/commit/close faults and16-way contention qualify metadata registration.
Rejected identities/body scope/second-token composition stop before JDBC.

Policy/artifact-only profile changes intentionally retain original-return
semantics; workspace derives from operator enrollment, not a provider workspace
claim. Metadata recovery conveys no authority or dispatch. Live interoperability,
shared durable replay, revocation and executor composition remain separate gates.
See the [qualification record](../runbooks/agentcore-jdbc-registration-qualification.md).

## Execution-disabled AgentCore registration ingress

Requirements: FR-002/003/010/012; SEC-001/002/003/004/009/012;
REL-001/002/003/005/009; DATA-001; OPS-002/004; PORT-004.
`AgentCoreRegistrationIngress` verifies the unchanged access-token profile and
exact operator workload enrollment, derives scope/target/canonical metadata,
rejects closed-schema violations and all human/OBO token composition, consumes
an exact credential fingerprint through the existing replay port and registers
through `BusinessOperationRepository`. It returns no execution authority.
`AgentCoreRegistrationIngressTest` uses local RSA tokens and a recording port
for positive controls, wrong trust/scope/time/enrollment, unrelated valid token
pairs, substitutions, malformed/bounded JSON, concurrent replay, original-return
retry and fixed conflict/uncertainty/dependency errors. There is no execution,
target credential or transport dependency. See
[ADR0078](../architecture/0078-agentcore-execution-disabled-registration.md) and
[runbook](../runbooks/agentcore-trusted-ingress.md).

This is an unwired synthetic slice. Live provider claim compatibility, a verified
workload-to-human grant/exchange artifact, transport sender proof, shared durable
replay and composed ingress/JDBC qualification remain separate gates. Existing
repository durability is independently tested on its unchanged fixtures; local
token/repository fixtures are not provider interoperability or durable ingress
proof. No whole-requirement status is promoted.

## Closed durable operation review — opt-in V4 library

[ADR0085](../architecture/0085-closed-durable-operation-review.md) adds the closed
`JdbcGitHubOperationReviewRepository`: one READ COMMITTED connection locks current
control first, then the actual operation head, samples writer time after waits
and rechecks exact state/policy/generation/identity/group/deadline. It reuses the
pure strict GitHub binding rather than accepting a caller's bound flag. A V4
immutable evaluation binding and closed versioned fact are committed with the
conditional head update; no independently committing stores are called atomic.
The coherent reader preserves legacy withdrawal facts, validates closed new
metadata/cardinality and replays historical facts at their own time. Explicit
V4 install/upgrade is separate; default V2 and V1-V3 SQL bytes remain unchanged.

`OperationReviewContract` supplies real three-engine qualification of review,
block and pending withdrawal, one-connection/lock order, strict target checks,
expiry after genuine waits,32 mixed contenders, atomic rollback, unknown
commit/cleanup, populated pre-DDL validation, retained old-writer refusal and
least-privileged immutable-binding grants. See the
[qualification history](../runbooks/durable-operation-review.md) for current
results and limits; previous green input/settings gates are not this candidate's
qualification. Partial FR-003/007/008/012, SEC-001/002/003/005/009/012,
REL-001/002/004/005 and OPS-002/004 evidence only. No authenticated endpoint,
live IdP, permit, admission, attempt/outbox, target, restore fence or whole-ID
completion is claimed.

The timestamp follow-up preserves valid operation-v1 fractional spellings through
evaluation, review, pre-evaluation withdrawal and populated V3 upgrade without
rewriting immutable originals or weakening clock-regression refusal. Its
three-engine cases are in the same contract; new journal metadata remains strict.

[ADR0087](../architecture/0087-fully-bound-operation-permit-and-admission.md)
records the human-approved admission trust contract and separate closed
`JdbcGitHubOperationPermitIssuer` slice. Profile v3 pins exact Ed25519 key
material/issuer/key ID/audience/positive lifetime before evaluation; legacy
v1/v2 recipes remain exact. Fresh exact-owner operations.execute permission,
actual loaded approved state and all original deadline minima are required.
The read transaction/cleanup finish before signing; no DML or consumption occurs.
`BusinessOperationAuthorityProfileTest`, `OperationPermitCodecTest` and new
three-engine `OperationReviewContract` cases cover settings/format/key/type
substitutions, closed actual-history anchors, original evaluator expiry,
real wait expiry, private-key use after cleanup, operator changes and read
uncertainty. See the [qualified boundary and retained failures](../runbooks/closed-operation-permit-issuance.md).
This is partial FR-007/008/009/012, SEC-001/002/003/004/005/006/009/010/012,
REL-001/002/004/005, DATA-001/004 and OPS-002/004 evidence only. Fully bound
single-use admission is a separately qualified boundary, not supplied by the
issuer itself. No whole-ID completion is claimed.

The opt-in V6 `JdbcGitHubOperationAdmissionRepository` library now owns
consumption/initial attempt, execution-claimed fact, conditional head and immutable
outbox in one transaction. `OperationAdmissionJournal` extends coherent closed
historical replay/cardinality without persisting a raw token/signature. New
`OperationAdmissionContract` cases and exact three-engine wrappers exercise
one attempt across32 contenders/four permits, genuine implicit UNIQUE-wait expiry
after holder rollback, binding/time substitutions, rollback/commit/cleanup
uncertainty, valid-admin-positive privilege denials, populated pre-DDL validation
and protocol refusal. See [scoped evidence/retained failures](../runbooks/closed-operation-admission.md).
Local complete matrix/aggregate gates passed; independent/hosted checks remain
separate. No public endpoint, sender,
external evidence sink, restore fence or target is claimed. The requirement IDs
above receive scoped evidence only, not whole-ID status promotion.

## Explicit durable-review rules — input/settings prerequisite

[ADR0084](../architecture/0084-durable-review-membership-and-lifetime.md)
records human-approved exact independent group enrollment, an explicit positive
approval lifetime capped at five minutes with no default, and separate fresh
evaluation authority. `BusinessOperationReviewSettings` and the explicit v2
authority profile bind every settings field; the legacy v1 recipe stays exact.
The new group and evaluation gates require matching scope/generation, fresh
authentication and exact independently derived group/provenance. A cancellation
scope is not evaluation authority. These are internal inputs, not verified body
claims or proof that a transaction committed.

`BusinessOperationReviewSettingsTest` covers exact pins, lifetime bounds, both
review decisions, enrollment substitutions/time, separate evaluation permission
and v1 refusal. The three-engine control contract verifies settings substitutions
cannot match current pins, replacements require a newer generation and explicit
activation, and these controls produce no operation/journal effects. See the
[qualification runbook](../runbooks/operation-review-settings.md).
That input/settings checkpoint alone supplies no durable reviewer transaction,
IdP/provider integration, admission or whole-ID
completion is claimed for FR-003/007/008/012, SEC-001/002/003/005/009/012,
REL-001/002/004/005 and OPS-002/004.

## Writer-locked operation policy/recovery controls — prerequisite only

Requirements: FR-003/007/008/012; SEC-001/002/003/005/009/012; REL-001/002/004/005;
OPS-002/004. No whole-ID or production status promotion.

Opt-in V3 adds an exact owner-scope/profile/generation control row, separately
privileged inactive staging/activation/quarantine and strictly newer-generation
replacement. `OperationAuthorityControl` retains the caller's READ COMMITTED
writer lock without committing. Restricted runtime users can lock but cannot
alter authority columns. The three-engine `OperationAuthorityControlContract`
tests current pins, genuine waits, grants, uncertainty and pre-DDL upgrade checks.
See [ADR0083](../architecture/0083-writer-locked-operation-authority-control.md),
[runbook](../runbooks/operation-authority-control.md) and
[learning note](../learning/current-control-is-not-admission.md).

Additional tests independently change stored generation without changing the
profile hash, race32 activation contenders across4 independent datasources, and
upgrade a real populated withdrawal journal while rejecting a corrupt actor
hash before DDL. Removing the generation and activation-state guards made all6
selected engine cases fail; the runtime was restored byte-identically. These
are prerequisite controls, not approval/admission races or production scaling.

Default V2/migration bytes and existing registration/withdrawal semantics remain.
`OperationPhysicalSchema` additionally checks observable required physical
columns/types/nullability/defaults/collations, primary/foreign keys and exact
CHECKs instead of treating intact migration history as physical proof.
MariaDB's filtered table-scoped CHECK metadata is handled by bounded exact
declaration validation without new grants; PostgreSQL protected-table trigger
inspection includes TRUNCATE and out-of-schema functions. The control contract,
two MariaDB table-scoped regressions and lexical negative vectors cover these
prerequisites. Hidden MariaDB executable-object absence remains a separately
unimplemented operator deployment gate, not runtime attestation. This strengthens
SEC-003/012 and REL-001/004 only within the stated qualification boundary.

No approval/admission/permit/attempt/outbox/target, signed control journal,
instant revocation, older-backup or paused-sender fencing is supplied. The future
single transaction must recheck all deadlines/identity/authority after all locks.

## Closed operation-review authority inputs — first checkpoint

Requirements: FR-002/003/007/008/009/012; SEC-001/002/003/005/009/012;
REL-001/002/004/005; OPS-002/004. No whole-ID status promotion.

`BusinessOperationReview` and `TrustedOperationReviewerContext` close independent
human/tenant/workspace/role/scope/deadline inputs without changing legacy reviewer
contexts. `BusinessOperationAuthorityProfile` copies operator policy and pins exact
scope/adapter/generation/policy/artifacts/resource bindings, with bounded canonical
UTF-8 and actual review-only policy evaluation. Focused application tests cover
negative controls, coherent rehashed substitutions, defensive copies and fixed
cause/suppression-safe rejection. See [ADR0077](../architecture/0077-closed-operation-authority-contracts.md)
and the [learning note](../learning/closed-review-is-not-admission.md).

Independent review found a profile-local malformed-Unicode input alias before
hashing, not a SHA-256 collision or execution bypass. Strict encoder validation
now rejects lone surrogates before policy/snapshot hashing while retaining valid
canonical-v1 bytes. Four regressions include parsed replacement aliases and valid
supplementary/non-normalized Unicode; three failed before repair, then all17
profile/review cases passed freshly. This strengthens SEC-003/012 and REL-001/004
at this unwired boundary only; it is not durable authority qualification.

These are unwired application contracts, **not** durable approval/admission,
current database policy/recovery proof, verified provider enrollment, a permit,
claim, target executor, outbox or new API. V2 storage/withdrawal and migration
bytes remain unchanged; complete transaction and three-engine qualification
remain required. Existing simulator qualifications do not qualify this path.

## Pure GitHub business-operation binding

Requirements: FR-002/010/012; SEC-001/002/003/005/009/012; REL-003/005/008;
PORT-001; OPS-002/004. No whole-ID status promotion.

`GitHubBusinessOperationBinding` joins separately supplied scope and exact
operator/service profile to a copied/revalidated operation and existing target
tuple. Versioned domain-separated identity/precondition/profile/data hashes bind
immutable IDs, both refs/SHAs, policy/artifacts and generation. Strict raw dispatch
interpretation rejects aliases/normalization; the frozen generic vector remains
unchanged. An independent golden recipe and `GitHubBusinessOperationBindingTest`
cover parsed numeric widths, positive profiles/body, valid rehashed mismatches,
tenant/target/policy/generation substitutions, UTF-8 bounds, safe integer neighbors,
defensive copies, lifecycle labels as data and cause/suppression-safe errors.
See [ADR0075](../architecture/0075-github-business-operation-binding.md),
[runbook](../runbooks/github-business-operation-binding.md) and
[learning note](../learning/operation-binding-is-not-authority.md).

This is an unwired, pure data library: no caller authentication, authoritative
database-origin proof, current policy/approval, permit consumption, atomic claim,
target call, signed evidence, AgentCore ingress or restart-safe reconciliation.
Constructors and unkeyed hashes grant no authority; a future executor must join
and recheck those independent boundaries.

## Durable pending-operation withdrawal

The [row-lock expiry fixture repair](../runbooks/operation-lock-wait-expiry-qualification.md)
uses fresh writer-time observations on both sides of a genuine wait instead of
assuming the test-host clock proves writer expiry. The same800ms window/150ms
margin and bounded waits remain, with new pre-expiry arrival/continued-blocking
assertions. A pre-lock-production-time negative control failed all three engines;
restored production passed the complete forced245-case suite. This is test-only
evidence (FR-003/007/012; SEC-002/009; REL-001/004; OPS-004), not a runtime change,
new skew allowance or qualification of unwired operation authority.

Follow-up fail-closed decoding evidence (SEC-003/012; REL-001/004; DATA-001;
OPS-002/004): package-local `StoredOperationJson` and its focused tests reject
duplicate/nested keys, trailing data, malformed/null/oversized UTF-8 values and
parser-diagnostic retention. The shared engine contract exercises originals,
heads, journal facts/actors and populated pre-DDL upgrades. No global canonical
change, DBA authenticity claim or whole-requirement completion follows.

| Requirements                                                                                    | Implementation and evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | Remaining boundary                                                                                                                                                                                                                      |
| ----------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-003/007/009/010/012; SEC-002/003/005/006/009/012; REL-001/002/004/005; DATA-001; OPS-002/004 | Closed `BusinessOperationWithdrawal`/lifecycle port; Java JDBC head/journal transaction, locked whole-state/database-time checks, coherent MVCC reads, immutable registration retry and explicit additive V2 upgrade. `BusinessOperationWithdrawalTest` and shared three-engine `OperationRepositoryContract` cover authority rejection, lock-wait expiry, single winner, rollback/ack loss, corruption/migration/grants. [ADR 0072](../architecture/0072-durable-pending-operation-withdrawal.md), [runbook](../runbooks/business-operation-withdrawal.md). | Internal pending-only withdrawal, not generic lifecycle authority, authenticated API, policy/approval/permit/target integration, global recovery fencing, S3, live RDS or production qualification. Whole requirement status unchanged. |

## CI scheduling and required completion gate

Requirements: OPS-002/004; REL-001 (qualification process only; no whole-ID or
production status promotion). Six independent jobs retain the current console,
Java/coverage, forced PostgreSQL/MariaDB, package/install, simulator, documentation,
browser and registration checks. Final `verify` requires every declared job to
report success, including rejecting failed, cancelled, skipped, missing and
unknown results. `scripts/ci-workflow.test.mjs` checks the complete command
inventory, mandatory database ordering, PR-only cancellation scope and actual
inline gate execution. Each lane builds its own prerequisites; no artifact trust
shortcut or runtime behavior is introduced. Speed is measured per exact-head run,
not guaranteed; repeated setup can increase runner minutes. See
[quality acceptance](11-quality-and-acceptance.md) and the
[workflow](../../.github/workflows/ci.yml).

## MariaDB standalone permit-consumption qualification

Requirements: FR-008; SEC-005/006/009; REL-004; OPS-002; DATA-001. The unwired
`persistence-mariadb` foundation retains exact engine/schema/Flyway validation,
explicit empty initialization, tenant-bound duplicate1062-only replay handling
and fail-closed commit/close uncertainty. `MariaDbPermitConsumptionStoreTest`
runs all seven store cases on both 10.6.25 and 11.4.13, including non-empty/schema
adoption and transaction-fault cases; three engine-profile unit cases complete
the seventeen-invocation matrix. See [ADR0066](../architecture/0066-mariadb-permit-consumption-foundation.md)
and the [qualification runbook](../runbooks/mariadb-permit-consumption-qualification.md).
Store reconstruction is not database crash/restart or failover proof; synthetic
faults do not qualify external diagnostic redaction. No whole-requirement status,
approval/evidence parity, runtime/RDS wiring or production claim is promoted.

## GitHub target preflight and conservative lookup

Requirements: FR-001/002/008/009/012/015; SEC-001/002/003/012/015/019;
REL-001/003/004/005/008/009; OPS-002/004. No whole-ID status promotion.

`GitHubPilotTargetBinding`, `GitHubTargetPreflight` and `GitHubApiTransport`
add explicit immutable operator identity/ref pins, public-only pinned resolution,
fixed production origin/platform TLS, total sequence/read/write time bounds and
deterministic close. Public constructors cannot select loopback/test trust.
Adapter/resolver verify credentials, GET installation-access/repository/refs and
strict positive typed PR identity/content; registration cannot overwrite a
different payload. Lookup never proves absence, follows Link/redirects, changes
execution state or sends POST. Unbound compatibility construction fails closed.

`GitHubDraftPullRequestAdapterTest` replaces permissive legacy fixtures with
positive/adversarial destination, credential, action, preflight, typed response,
pagination, absence, duplicate, key/permit, concurrency, byte/time, redaction and
cleanup controls. `BoundedGitHubResponseTest` retains limit+1 stream evidence;
existing SSRF/TLS tests retain the shared address policy evidence. Guard-removal
controls and exact-head results are recorded in the PR.

`postBodyMustMatchWithoutJsonTypeCoercion`,
`lookupBodyMustMatchWithoutJsonTypeCoercionOrStaleConfirmation`, and
`exactTextAndExplicitNullForEmptyBodyConfirmPostAndLookup` qualify the shared
typed-body matcher (FR-008/015; SEC-003/019; REL-003/008): scalar/container/missing
acknowledgements cannot become success, invalid POST blocks redispatch, invalid
lookup drops an older receipt, and exact textual/empty-null compatibility remains.
Fourteen cases failed before repair; all twenty-three body-type cases pass after.
See the [learning note](../learning/github-confirmation-types.md).

The operator ID ceiling (`1..9007199254740991`) and regressions
`operatorIdsOutsideFrozenSafeIntegerRangeAreRejected`,
`maximumSafeOperatorIdsRemainExactAndDistinct`, and
`adjacentSafeIdentityCannotBorrowAnActualReceiptAfterResolverReconstruction` ensure
overflow IDs are refused before transport, upper-bound neighbors remain exact,
and a reconstructed adapter cannot borrow another immutable identity's genuine
HTTP-confirmed receipt. Global canonical JSON v1 is unchanged. See
[ADR0073](../architecture/0073-github-pilot-target-preflight.md) and the
[runbook](../runbooks/github-pilot-target-preflight.md).

The provider port still vouches for opaque-token installation provenance. The
single-repository visibility/strict response profile is deliberately narrow.
Positive lookup observes desired-effect presence, not original POST causality.
Refs can race after reads; there is no native ref CAS/idempotency guarantee.
Process-local state and the uncomposed durable executor/authority join remain
limitations; ADR0075 implements a separate pure hash/precondition data bridge.
Durable lifecycle, credential minting, live provider/target and deployment remain
separate qualifications. The lifecycle and persistence files are unchanged by
the binding library.

## Uncommitted operation transition proposals

| Requirements                                                   | Implementation and evidence                                                                                                                                                                                                                                                                                                                          | Remaining boundary                                                                                                                                                                                                          |
| -------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-010/015; SEC-002/005/009/010/012; REL-002/004/005; DATA-004 | `BusinessOperationTransitionPlanner` and fixed-code exception; exact scope/token matching, one append over existing v1 graphs, immutable fields/history, bounded snapshots and defensive copies; unit mutation tests and shared database registration-rejection test; [ADR 0071](../architecture/0071-uncommitted-operation-transition-proposals.md) | Pure candidate data only. No authentication, authorization, atomic compare-and-swap, DB mutation, approval/permit, target observation/dispatch, evidence receipt or public API. Whole requirement status remains unchanged. |

## Durable business-operation registration

| Requirements                                                                                                          | Implementation and evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                              | Remaining limits                                                                                                                                                                                                                                                               |
| --------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| FR-002/010/012; SEC-001/002/003/009/012; REL-001/002/003/004/005/009; DATA-001/002/004; OPS-002/004; PORT-001/002/003 | Application `BusinessOperationRepository` port and separate trusted scope; JDBC plain-insert unique-key arbitration; atomic snapshot and three initial facts; integrity-checked scoped reads; fixed conflict/commit-unknown errors; migration validation; one shared `OperationRepositoryContract` on MariaDB 11.4.13/10.6.25 and PostgreSQL 16.4. [ADR 0070](../architecture/0070-durable-business-operation-registration.md), [runbook](../runbooks/business-operation-repository.md). | Registration only and unwired. No authority-bearing transitions, API authentication/authorization, argument redaction/encryption, retention execution, live RDS/failover, recovery fencing, AgentCore/GitHub dispatch or S3 publication. Requirement-wide status is unchanged. |

## AgentCore GitHub draft pull request adapter foundation

| Requirements                                                                       | Implementation and evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | Remaining limits                                                                                                                                                                                                                                                                                                                                                                          |
| ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-001/002/008/009/012/015; SEC-001/002/003/012/015/019; REL-001/003/008; DATA-001 | `GitHubDraftPullRequestAdapter`, `GitHubDraftPullRequestAction`, `GitHubPullRequestOutcomeResolver`, `GitHubAppCredentials` in `adapters-remote`; `StatefulTargetSimulator` in `simulator`; deterministic `GitHubDraftPullRequestAdapterTest` suite qualifying success, missing/mismatched permit, malformed input, unauthorized repository, non-main base branch, `draft: false`, unknown fields, self-assertion rejection, redirect/retry rejection, call timeout, concurrent blocking, idempotent replay, read-only lookup and token redaction. `BoundedGitHubResponseTest` proves limit-plus-one reads, unknown-length streams, UTF-8 byte accounting and read failure; chunked HTTP fixtures prove exact-limit acceptance, overflow UNKNOWN, blocked repeat POST and later GET recovery. Lookup 401/403/404/302/500 cannot confirm failure. `StatefulTargetSimulatorTest` covers simulation; [ADR 0069](../architecture/0069-github-app-draft-pr-adapter-and-reconciliation.md); [operator runbook](../runbooks/agentcore-github-pr-pilot.md) | Bounded to `github.pull_request.create`, private `tracepermit/agentcore-pr-pilot`, base `main`, draft only. No merge, branch mutation, issues, settings, workflows, admin or destructive tools. Automated tests use fake HTTP servers, not live GitHub. Process-local state only; durable composition, general absence/pagination semantics and live pilot qualification remain separate. |

## Versioned business-operation contract foundation

| Requirements                                                                                                                              | Implementation and evidence                                                                                                                                                                                                                                                                                                                                                                                                          | Remaining limits                                                                                                                                                                                                                                                                                   |
| ----------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-002/007/010/012/015; SEC-001/002/003/005/009/010/019; REL-001/002/003/004/005/008/009; DATA-001/002/004; OPS-002/004; PORT-001/002/003 | Strict `business-operation-v1` schema/vector; `BusinessOperationValidator`; `BusinessOperationValidatorTest`; `PortableContractsTest`; [ADR 0068](../architecture/0068-versioned-business-operation-contract.md). Exact action/payload/resource/target bindings, trusted-context shape, explicit retention, recovery generation and independent append-only authorization/execution/evidence projections fail closed under mutation. | Contract foundation only. No repository uniqueness, database constraints, access control/encryption, retention execution, API, AgentCore transport, human workflow, permit, target adapter, S3 publication, real GitHub effect or production qualification. Whole requirement status is unchanged. |

## Per-request simulator review metadata export

| Requirements                                                                    | Implementation and evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | Remaining limits                                                                                                                                                                                                                                                                                                                                                |
| ------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-007/010/012; SEC-003/005/009/012; DATA-001/003; REL-001/002/009; OPS-002/004 | `ApprovalReviewExport`, authenticated GET export route, closed schema/golden vector and Java HTTP privacy/role/scope/workspace/missing-snapshot tests; React closed-schema/authority/race tests; `review-verifier` fixed-result/bounds/distribution tests; permanent real-Java/rendered-React/Playwright-download/installed-Java-21 gate with before/after file SHA-256, one-request/download assertions, downloaded-byte mutation matrix and listener/process/browser/container/temp cleanup; [ADR0064](../architecture/0064-unsigned-approval-review-export.md) | Unsigned/incomplete sequential-read metadata, not a signed attestation, authority artifact, original-hash proof or immutable history. A verifier success is not current authorization. Arguments/raw evidence are omitted, not redacted after export. Only simulators are exercised; retention/deletion, live IdP and fresh-host qualification remain separate. |

**Status:** Living implementation index  
**Applies to:** Current POC and target roadmap

Status values: **Implemented**, **Partial**, **Target**, or **Proposed**.

## Explicit native/public browser candidate

[ADR0063](../architecture/0063-explicit-public-browser-code-pkce.md), the
[qualification runbook](../runbooks/auth0-native-oidc-qualification.md) and
[learning note](../learning/native-oidc-public-client.md) distinguish the
implemented synthetic matrix from one named live Auth0 qualification. These
mappings refine the corresponding rows below without promoting whole-ID status.

| Requirements                             | Enforcement point and automated evidence                                                                                                                                                                                                                                                                                                          | Remaining boundary                                                                                                                       |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| FR-003/007; SEC-002/004/009; REL-001/004 | Schema1.1 explicit method, `BrowserCodeExchange`, independent `BrowserIdTokenVerifier` organization check; config/exchange/public HTTP tests exercise scope, state/nonce, code/browser substitution, claims/time/key and concurrent replay; [2026-10-22 installed live record](../demo-scenarios/evidence/installed-auth0-approval-2026-10-22.md) | One named native/public Auth0 registration qualified; other tenants/providers and production identity remain unqualified                 |
| FR-012; SEC-003/011/012/015/020          | Exact numeric-loopback callback; no-secret public form and omitted Authorization; local-TLS destination/redirect regressions; fixed-stage, allowlisted-reason local diagnostics with canary-exclusion tests; existing browser/API simulator tests                                                                                                 | Same-UID custody, public edge, real targets and container-version qualification unsupported                                              |
| REL-001/009; OPS-001/004/005             | Generic browser denial plus fixed local failure stage; unknown diagnostic reasons collapse to `REJECTED`; sink-failure regression; offline config failures stay code-only; exact local-Mac procedure, dated live record and compatibility stop conditions documented                                                                              | Local DB readiness and a rejection diagnostic are not IdP probes or production readiness; named live status is not general compatibility |

Schema1.0 confidential Basic remains supported. `openid` selects OIDC in the
authorization request; API-token `approvals.read` is required for public login,
with operation-specific scopes independently enforced. ID tokens remain
mandatory. No Notion task completion or generic Auth0/OIDC compatibility is
claimed by these automated tests.

## Bounded OIDC/JWKS refresh isolation

Requirements: SEC-002/003/004/015/017; REL-001/009; OPS-001/005.

`CachingOidcJwkResolver`, `JwksRefreshCoordinator`, `OidcAccessTokenVerifier`,
`BoundedHostResolver` and `SsrfSafeHttpsJwkSetSource` qualify a bounded local
identity-dependency slice. Fresh keys avoid unrelated provider I/O; same-pair
refreshes coalesce; finite workers/queues, monotonic wait/transport budgets,
caller-specific freshness, late-result discard and ordered success/failure
cooldowns preserve fail-closed behavior. Access-token claims use trusted time
re-sampled after key resolution/signature verification. Existing destination,
pinning, TLS, key/JSON, size and bounded-error controls remain in force.

`CachingOidcJwkResolverTest`, `OidcAccessTokenVerifierTest` and
`SsrfSafeHttpsJwkSetSourceTest` cover warm/independent-provider progress,
coalescing, late/expired results, timeout/interruption, cooldown storms,
old-interrupted-worker/new-success ordering, full active capacity before
queueing, saturation/recovery, combined DNS/HTTP delay, token expiry during
resolution and retained SSRF/TLS/key negatives. See
[ADR 0049](../architecture/0049-bounded-jwks-refresh-isolation.md) and the
[runbook](../runbooks/jwks-refresh-isolation.md).

SEC-017 receives resource-exhaustion defense, not completed pre/post-auth
distributed abuse control. OPS-001/005 remain partial: no new metrics,
production bootstrap/readiness or live IdP qualification. Pools and cooldowns
are instance-local (default DNS is shared across sources); provider fairness,
process-wide cardinality, forced OS-call cancellation and a universal hard
wall-clock SLO are not established. No whole-requirement status is promoted.

The [OkHttp 5 transport qualification](../runbooks/jwks-refresh-isolation.md#okhttp-5-transport-qualification)
adds SEC-003/011/012/015 and REL-001/009 regression evidence through generated-key
untrusted-certificate/wrong-hostname failures and peer-observed response closure
for status/media/size rejection before the call deadline. Existing pinned TLS,
redirect, timeout, DNS, redaction and simulator-only remote MCP tests remain in
force. OPS-002/004 gain the selected JVM artifact, upstream migration references
and reproducible commands. Production behavior and whole-ID status are unchanged;
live-provider, public-edge and external binary compatibility remain unqualified.

## Bounded simulator approval listener admission

Requirements: FR-003/007/009; SEC-002/003/017; REL-001/004/009; OPS-001/005.

`ApprovalAdmissionExecutors` and `SimulatorApprovalServer` bound one loopback
listener's executor to eight active lanes plus sixteen pending tasks by default.
Admission precedes every handler statement; overflow refuses the transport
without a guaranteed JSON error. Constructor validation, lane reuse after
failures, approved-execute saturation, repeated cycles, close races and default
approval/replay regression are covered by `SimulatorApprovalAdmissionTest` and
`SimulatorApprovalServerTest`. Two close probes use daemon tasks, finite waits
and release-finally coverage including setup; released-handler and owned-task
termination are checked. The interruption-ignoring resolver establishes that
bounded close does not depend on cooperative cancellation. See
[ADR 0050](../architecture/0050-bounded-simulator-approval-admission.md) and the
[runbook](../runbooks/simulator-approval-admission.md).

[ADR0074](../architecture/0074-reserved-lane-bounded-dispatch.md) replaces the
queue-first mechanism with monitor-serialized lane reservation and a bounded
pending FIFO, retaining defaults 8/16 and all pre-handler/overflow/close gates.
`BoundedDispatchExecutorTest` adds concurrent 24-admitted/overflow controls,
pending promotion, startup failure, cancellation, interruption and shutdown
accounting; `ApprovalAdmissionExecutorsTest` adds a delayed-worker regression
that failed on the old factory. A reserved lane includes handoff before worker
wakeup; it is not an additional pending slot or authority to execute an action.
The subsequent recorded candidate passed 34 focused cases and three independent
complete two-CPU qualification runs (16 cases each, unchanged workload/gates).
Worker startup precedes binding and bind failure stops the owned workers.
Independent review's checked-throwable task/observer findings and the Mac's
startup variant each failed a permanent pre-repair control; all three invocation
boundaries now contain every throwable without retry, raw diagnostic or false
termination. The earlier green candidate receipt is retained as history.
This does not promote PERF-003, SEC-017 or a production/public-edge SLO.

Counting key-resolution/database probes plus unchanged simulator state qualify
no expensive handler work during overflow. Simulator rule cardinality is not
an invocation count because identical rules deduplicate; application-level
invocation-counter tests are separate evidence. Approval, permit consumption
and execution-claim semantics are unchanged. Bounds are per-server task counts,
not TCP/byte limits; close is not a drain, forced cancellation or target-outcome
guarantee. SEC-017 public pre/post-attribution abuse controls and OPS-005 remote
readiness are not satisfied. OPS-001 gains no metrics surface; fairness,
distributed capacity and a wall-clock SLO remain unqualified. No whole-ID status
is promoted.

## JWKS regression publication ordering

SEC-002/003/015/017, REL-001/009 and OPS-001/005 retain their existing status.
`CachingOidcJwkResolverTest` now waits for actual in-flight publication before
inspecting an interrupted operation, with a second deterministic
worker-before-publication case. The original 400 ms operation deadline,
late-result refusal and obsolete-cooldown guard remain unchanged. Bounded
cleanup, exact supplier counts and interrupt-status checks strengthen test
evidence; they add no runtime authorization behavior. See
[ADR 0049](../architecture/0049-bounded-jwks-refresh-isolation.md) and its
[fixture explanation](../runbooks/jwks-refresh-isolation.md#concurrency-regression-fixture-ordering).

The late-older-failure regressions also assert the retained newer-success
cooldown state directly, without assuming future completion makes a zero-queue
worker available for another submission. Deadlines, publication scheduling,
interruption and cleanup assertions are not relaxed; capacity/isolation tests
remain separate. This changes test evidence, not runtime behavior or status.

## Public introduction visuals

FR-005/007/008/009, SEC-005/006/014, REL-002/005 and OPS-002/004 are explained,
not newly implemented, by the [public introduction](../../site/guide/what-is-tracepermit.md).
Its request-flow and core-pillars SVGs distinguish block, sanitize, pending
approval, explicit resume, permit verification/claim and simulator dispatch.
Equivalent semantic HTML lists preserve readable mobile and assistive access.
`scripts/intro-visuals.test.mjs` checks the static assets, text/branch boundaries,
profile limits and responsive wiring; rendered browser QA is separate evidence.
No runtime, trust boundary, assurance level or requirement status changes.

## Bounded process-local replay and consumption

Requirements: SEC-002/003/004/006/009/014; REL-001/004/009; OPS-004.

The three `InMemory*ReplayStore` / `InMemoryPermitConsumptionStore` implementations
preserve their boolean ports and no-arg callers while adding 65,536-entry default
caps, 64-removal expiry budgets, atomic duplicate/capacity/insertion checks,
no live eviction and one expiry-index entry per retained key. Compound source
and workload keys preserve issuer and tenant namespaces; legacy permit IDs remain
global across overloads. `InMemorySourceAttestationReplayStoreTest`,
`InMemoryWorkloadIdentityReplayStoreTest` and `InMemoryPermitConsumptionStoreTest`
cover saturation, live replay retention, expiry/equality, no refresh, budgets,
expired duplicates behind the budget, bounded metadata and task contention.
Race waits and checked teardown are bounded. `BoundedReplayVerifierIntegrationTest`
and `ExecutionPermitAuthorityBoundedStoreTest` exercise actual verification or
consumption followed by a gated test callback, with healthy post-expiry controls.

This is bounded per-instance entry/removal state, not a target-dispatch count,
wall-clock/byte/fairness bound, distributed or durable replay, restore fencing,
revocation, or a public saturation metric. Existing replay codes also represent
capacity refusal. Idle entries can remain retained after expiry; an expired
duplicate can be refused until reclaimed. The durable coordinator and broad
requirement statuses are unchanged. See
[ADR 0054](../architecture/0054-bounded-local-replay-stores.md) and the
[runbook](../runbooks/bounded-local-replay-stores.md).

## Owner-only read-only recovery inspection

Requirements: FR-015; SEC-003/012/019; REL-002/005/008; OPS-002/004.

`PostgresRecoveryInspection` and source/installed `--recovery-inspect` qualify
non-migrating installation-wide stored-state counts under one read-only
repeatable-read snapshot. Ownership/inherited ownership of all inspected tables
is required; ordinary runtime SELECT and unexpected RLS are refused. No
per-request data or partial failure report is emitted. Real PostgreSQL snapshot,
all-status, read-only, authorization and failure tests plus bootstrap/installed
commands provide bounded evidence. See [ADR 0052](../architecture/0052-owner-only-recovery-inspection.md).

This is not whole-requirement completion: no evidence-chain verification,
target lookup, automatic abandoned-executing handling, rollback detection,
upgrade, authority release or safe resume is added. Output size is bounded;
table-scan work is timed, not constant. Stored approval counts are not current
eligibility decisions. Operator ownership is not an authenticated remote admin
product or proof against a malicious database owner.

## Operator-declared recovery quarantine

Requirements: FR-007/008/012; SEC-002/005/006/012; REL-001/003/004/005/009; OPS-002/004.

Flyway V9, `PostgresRecoveryQuarantine`, authority insertion triggers and guard-first
store transactions serialize owner-declared quarantine against new direct/approval
claims, pending/review operations and standalone permit consumption. Existing
completion/lookup/evidence paths remain available. `SimulatorApprovalMain` and both
launchers expose non-migrating status/quarantine commands, with already-quarantined
startup refusal, bounded diagnostics and no resume command. The later
stopped-service V9→V10-only upgrade is separately qualified in ADR0059 below.
`PostgresRecoveryQuarantineTest` adds real transaction/SQL/role/race tests;
database lifecycle, bootstrap HTTP/child-main and packaged source-free tests cover
metadata refusal, live containment, restart and unchanged offline checks.
See [ADR 0051](../architecture/0051-operator-declared-recovery-quarantine.md).

This is one-way declared containment, not automatic old-snapshot detection,
pre-quarantine claim cancellation, prevention of internal permit minting,
in-memory-path fencing or independently anchored recovery. A restored ready row
can revive authority. Recovery/operations requirement statuses remain partial.

## Bounded post-dispatch completion uncertainty

Requirements: FR-010/015; SEC-006/010/019; REL-001/002/004/005/008/009; OPS-004.

Both durable execution services share `DurableOutcomeRecorder`: after a completion
exception, one additional same-tenant/attempt `unknown` operation may atomically
record uncertainty, evidence/outbox and a lookup-only queue entry. Existing
PostgreSQL executing-only transitions reject overwriting committed succeeded,
failed or unknown outcomes. The original call still reports
`DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH`; no target retry or new authority is
created. `DurableOutcomeRecorderTest`, existing service regressions and real
`PostgresCompletionFailureRecoveryTest` cover rollback versus lost commit
acknowledgement, permanent outage, approval consumption, exact counts, replay,
evidence and lookup-only resolution. See [ADR 0048](../architecture/0048-bounded-durable-completion-uncertainty.md).

That slice repairs the recoverable completion-write gap, not process-crash recovery,
abandoned-executing sweeping, database rollback fencing, automatic worker
scheduling, pool/ingress bounds or a wall-clock SLO. Broad requirements remain
partial where those gates apply.

## Striped authorization concurrency, trusted clock placement, and admission bounds

Requirements: FR-005/006/009; SEC-002/005/006/009/010; REL-001/004/009; OPS-002.

Java `SecureExecutionService` replaces its instance-wide execute monitor with per-principal
`ReentrantLock` striping (`StripedMonitors`, default 64 stripes), keyed strictly
by `organizationId + "\u0000" + subject.id` to match the sequence store's conflict
domain. Trusted timestamp capture (`clock.instant()`) is placed strictly _inside_ the
stripe lock before `contextFor` and evaluation, ensuring linear sequence observation
and eliminating pre-lock timestamp stale races. `EnforcingMcpDispatcher` bounds concurrent
enforcement (1–1,024, default 64) with non-blocking admission, failing closed immediately
with `ENFORCEMENT_CAPACITY_EXCEEDED` (`not_executed`) and zero enforcer invocations on
saturation.

`SecureExecutionServiceConcurrencyTest` (13 tests), `StripedMonitorsTest` (6 tests),
and `EnforcingMcpDispatcherCapacityTest` (4 tests) verify temporal order under shipped
and test-only baseline-allow policies, serialized send-before-read ordering, service
lock release under `contextFor`, `record`, and sanitizer faults from distinct threads,
cross-tool principal lock alignment, safe hash collision over-serialization, racing
idempotency, distinct-principal target overlap, capacity release on exceptions, and
constructor bounds. Fault-recovery tests explicitly assert distinct worker threads
and use bounded executor cleanup that fails on non-termination. See
[ADR 0047](../architecture/0047-striped-authorization-sequence-linearization.md)
and [interface contracts](08-interface-contracts.md).

This is bounded simulator-path concurrency and admission control, not process-wide
ingress rate limiting, per-tenant fair queuing, an end-to-end throughput SLO,
or production certification. Clock rollback and multi-instance shared-history
serialization are not qualified by this slice. No whole-ID requirement is upgraded
to production-qualified.

## Authenticated approval-history collection

FR-007/010; SEC-001/003/005/009/012; DATA-003; OPS-002 receive a bounded
read-side slice: strict exact metadata filters, limit+1 keyset pagination,
per-page authorization/tenant/owner predicates, opaque unsigned context/position,
and unchanged exact-snapshot/redaction projection. `ApprovalHistoryQueryTest`,
`ApprovalHistoryParametersTest`, `PostgresApprovalHistoryTest`,
`SimulatorApprovalHistoryTest` and the browser/redaction boundary suites cover
validation, colliding tenant IDs, ties, more than 50 rows, restart/newer inserts,
roles/ownership, expired display, authentication changes and unchanged state.
See [ADR 0046](../architecture/0046-authenticated-approval-history-pagination.md)
and the [API contract](../runbooks/approval-history-api.md).

React `ApprovalInbox`, API validation and history-state tests add exact filters,
explicit continuation/cap, coherent initial generation, logout invalidation,
context checks and mutation error gating. Actual synthetic multi-role browser
QA covers sign-in, >50/250 rows, review versus execution, replay, and controlled
client fault/late-response cases; see the [browser history runbook](../runbooks/browser-approval-history.md).
This is not full action search, customer retention/export,
constant-cost paging, an SLO or production tenant isolation certification. No
schema/authority/permit behavior changes, and no whole-ID status is upgraded.

FR-007; SEC-004/005; REL-002 receive additional **UI-only** expiry guidance:
`ApprovalInbox` shares one local clock across live rows, invalidates expired
confirmations and rechecks current selection/ID/hash/eligibility before POST.
`approval-confirmation.test.ts` covers exact boundaries, current status/session,
role/scope, stale snapshots and invalid inputs. The mounted browser suite advances
only client time for pending/approved confirmations and paused-timer clicks
(with a labelled client-only session-expiry override to isolate approval expiry),
asserting no POST/read and unchanged server state. Existing real review, explicit
execution and replay cases remain controls. No new server enforcement, claim
semantics, requirement completion, clock SLO or external-provider qualification
is implied. See [ADR 0036](../architecture/0036-local-browser-approval-boundary.md).

## Explicit operator database initialization

Requirements: SEC-002/003/006/012; REL-001/005; OPS-001/002/004.

`PostgresDatabaseLifecycle`, strict `openExisting` state/permit factories and
`SimulatorApprovalMain` separate empty-only `--init-db` from non-migrating run.
`PostgresDatabaseLifecycleTest` covers metadata/history/trigger rejection,
non-owner validation, concurrency/lock release and unchanged state on refusal.
`SimulatorApprovalBootstrapTest` proves actual child-main empty refusal,
explicit initialization, no-listener failure, non-owner signed HTTP approval,
restart and replay. The packaged test covers the same commands without build
tools on runtime PATH. See [ADR 0045](../architecture/0045-explicit-database-initialization.md).

This is bounded startup/installation hardening, not full physical-schema or
restore validation, externally anchored replay safety, production least privilege
or a completed recovery/operations requirement. Legacy development auto-migration
and static-policy launcher scope are explicit; this initialization slice added
no upgrade/resume command. ADR0059 later adds only the bounded V9→V10 upgrade.

## Flow documentation mapping

The [nine-flow developer guide](../flows-guide.md), corresponding public guide
and [single diagram catalog](../design/flow-diagrams.json) explain these existing
controls. The guides add no runtime behavior; dated qualification records below
distinguish protocol tests from actual client/model and reviewer observations.

| Flows                    | Requirements                             | Explicit qualification boundary                                                                           |
| ------------------------ | ---------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| 1–2: read/block          | FR-001/002/005/006/008/009/012           | Configured simulator paths, not universal interception                                                    |
| 3: request/review/resume | FR-007/008/009; SEC-005/006; REL-002/004 | Native protocol plus named local Codex model-driven success/replay/expiry/explicit-denial cases qualified |
| 4–5: sanitize/sequence   | FR-005/006; SEC-001/002                  | Derived action re-evaluated; bounded in-memory proposal history, not global data-flow tracking            |
| 6–7: source/OAuth        | FR-003/014; SEC-001/013/014              | Development source evidence and one hosted OAuth composition; profiles not interchangeable                |
| 8: delegation            | FR-011; SEC-002                          | Narrowing verified before policy; fixture signers, not deployed sub-agents                                |
| 9: uncertainty           | REL-002/005/008; SEC-019                 | Failure/restart and lookup worker separately tested; no automatic sweeper/worker in browser launcher      |

See the [rehearsal plan](../runbooks/flow-rehearsal-plan.md) for remaining manual gates.

## Endpoint connector I/O completion budgets

Requirements: FR-016/017; SEC-003/011/014; REL-001/002/005/009; OPS-004.

`LoopbackHttpGatewayTransport` now waits for a bounded subscriber's complete
body under one monotonic exchange budget, cancels on timeout/interruption and
preserves existing public error codes. `LoopbackGatewayDeadlineTest` records
one owned-loopback request and connection closure for stalled headers/body,
partial error/redirect responses and interruption. Subscriber tests cover byte
caps, EOF, cancellation and subscription lifecycle. `TerminalProbeDeadlineTest`
covers bounded output/exit waiting, failure and cleanup through declared
synthetic process adapters; the fixed macOS command/client PID is unchanged.
Existing source-proof and simulator E2E gates remain required. No live model,
Linux/Windows, protected-key or archive qualification is added. A timeout after
send is not proof of no target effect and does not trigger tool retry. See
[ADR 0057](../architecture/0057-endpoint-io-deadlines.md). Broad statuses below
are unchanged.

## Named native endpoint rehearsal

The [public connector guide](../../site/guide/endpoint-connector.md) documents
the Java application versus source/launcher prerequisites, same-checkout local
backend connection, Codex setup, allow/block prompts and safe reconnect rules.
This is documentation support for SEC-003/011/014 and OPS-002/004, not new
runtime qualification or promotion of the existing requirement statuses.
The guide regression checks keep commands, configuration names and expected
outcomes aligned with the launcher and recorded evidence. Remote transport and
an independently qualified connector installer remain outside this profile.

The [2026-10-15 native Codex evidence](../demo-scenarios/evidence/codex-native-endpoint-2026-10-15.md)
adds one CLI 0.162.1/`gpt-5.6-sol` macOS model-selected simulator read/block pair,
correlated with Java action-feed events at runtime revision `50937a7`.

| Requirements                                             | Evidence added                                                                                                                                                                                                                  | Boundary                                                                                                                                               |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| FR-001/003/006/009/010/014/016/017; SEC-004/014; REL-002 | Native model calls returned `allow / executed` and `block / denied`; fresh server projections agreed; expired-token rejection recorded separately                                                                               | Named development profile only; full process-fact transcript and independent live invocation counter not captured                                      |
| SEC-003/011; OPS-004                                     | `run-codex-endpoint-demo.sh`, `codex-endpoint-preflight.mjs`, isolated launcher tests and CI step; same-checkout session/key, matching build/readiness, explicit Java forwarding, symlink resolution and environment-only token | Ergonomic preflight, not backend authentication; no global config, token persistence, automatic refresh or tool retry; Java trust boundaries unchanged |

The remaining source-proof negatives have separate automated verifier/E2E
coverage, not manual native-model proof from this pair. Gate B, production key
custody, enrollment and anti-bypass remain partial; no existing status below is
upgraded to production-qualified.

## Policy lifecycle staging, rollback, and simulation

| Requirements                      | Implementation and verification                                                                                                                                                                                                                                                                                                                                                                         | Boundary                                                                                                                                                                                                             |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-013; REL-006; SEC-002; OPS-002 | Java `PolicyLifecycleService`, in-memory and PostgreSQL `PolicyLifecycleStore` foundations (Flyway V7), private validation snapshots, defensive revision copies, DB immutability triggers, serialized first activation and current-version preconditions, and explicit 32-bit numeric bounds; optional direct execution and generation-bound approval integration; lifecycle/store/boundary regressions | Trusted Java library/store only; default launchers remain static; authenticated remote administration and UI wiring deferred. Opt-in approval claim revalidation is covered below, not a global database mode fence. |

See [ADR 0039](../architecture/0039-policy-lifecycle-staging-rollback-and-simulation.md).

## Simulator policy-template qualification

Requirements: FR-005/006/013; SEC-001/002/003; OPS-002.

Two opt-in default-block candidates and a pinned 19-case corpus exercise
`PolicyLifecycleService.simulate`; `PolicyTemplateQualificationTest` has
14 tests and 38 matrix evaluations. Exact classification/decision/provenance,
isolated read/write scope negatives, predicate/priority mutations, both-template
input/catalog isolation and unknown policy/action schemas are covered.

| Added evidence                                                                                                  | Qualification boundary                                                                                             |
| --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| FR-005/006; SEC-002: exact class, score, severity, signals, outcome, reason, rule and approval-group assertions | Candidate simulation only; not runtime authentication or general argument validation                               |
| FR-013: no catalog method call, revision, active pointer or history change during candidate simulation          | Not authenticated policy administration or installed runtime activation                                            |
| SEC-001/003; OPS-002: canonical-identity characterization, synthetic fixture hygiene, candidate ID/version      | No authentication or complete secret-retention proof; result has no schemaVersion or runtime key/adapter inventory |

See the [template qualification runbook](../runbooks/simulator-policy-templates.md).
Default policies/launchers and enforcement code remain unchanged.

## Exact integral log-window classification

Requirements: FR-005/006; SEC-002; REL-001; OPS-002.

`PolicyEvaluator` retains its integral-node guard but compares the original
integer value with 100 using `BigInteger`, without int/long narrowing or floating
conversion. `LogWindowNumericBoundaryTest` has 26 tests; five independent
`LogWindowClassificationReviewTest` regressions add wide signed/parsed families,
short nodes, exact operation scope, context deduplication/score behavior and
both-template forbidden-catalog/input isolation. The old comparison independently
reproduces 12 failures in the original 26-test class.

This repairs raw classification, not authentication or tool-argument validation.
Missing/non-integral limits retain existing behavior; no real target is contacted.
Candidate ID/version assertions do not qualify active key/schema/adapter inventory
or complete broad SEC/REL/OPS requirements. See the
[numeric boundary runbook](../runbooks/log-window-classification.md).

## Private endpoint observation capture

| Requirements                     | Implementation and verification                                                                                                                                                                                                                      | Boundary                                                                                                                                                        |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-016/017; SEC-003/012; OPS-004 | Default-disabled `EndpointObservationCapture`, private bounded Java `FileProcessObservationRecorder`, explicit launcher flag, redaction/filesystem/lifecycle/order tests and [capture runbook](../runbooks/native-endpoint-observation-rehearsal.md) | Records measured facts and request hash after stability checking, before forwarding; not gateway acceptance, immutable evidence or a completed native rehearsal |
| SEC-014; REL-001                 | `fourSourceProofRejectionsCannotDispatchAnOtherwiseAllowedRead`: actual loopback HTTP, missing proof, changed arguments, different valid credential and replay; invocation and ledger assertions                                                     | Synthetic process observation; zero rejected-case increments, not model-selected/native negative evidence                                                       |

See [ADR 0038](../architecture/0038-private-endpoint-observation-capture.md).
No production or Gate B status below is upgraded by this preparation slice.

## Deterministic presenter qualification (Gate C)

The [2026-10-15 Gate C evidence](../demo-scenarios/evidence/gate-c-deterministic-presenters-2026-10-15.md)
refreshes and automates the deterministic scenario suite across Scenarios 4, 5, 7, 9, 10, and 11.

| Requirements                                                    | Evidence added                                                                                                                                                       | Boundary                                                                                               |
| --------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| FR-005/006/011; SEC-001/002; REL-001/009; PERF-001/002; OPS-004 | Unified `run-gate-c-qualification.sh` (`pnpm demo:gate-c`), unit tests `gate-c-qualification.test.mjs`, expanded `verify-demo-qualification.sh`, and CI verification | Deterministic local simulators; no real cloud endpoints, live LLM inference, or production credentials |

## Local MCP to durable approval composition

| Requirements                                 | Implementation and verification                                                                                                                                                                                      | Boundary                                                                                                       |
| -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| FR-002/003/004/014; SEC-001/002/004/009      | `ApprovalMcpAdapter` strict three-tool protocol and current role/scope catalog; `SimulatorApprovalServer` authenticates before parsing, preserves configured origin and asserted actor; protocol/HTTP negative tests | Local bearer-only Codex candidate, not managed executable/source proof or hosted OAuth composition             |
| FR-007/008/009/012; SEC-005/006; REL-002/004 | Shared request/execute methods and existing `HumanApprovalService`/PostgreSQL snapshot/claim; real HTTP MCP to browser review to MCP resume, restart and eight-way contention tests                                  | Simulator only, no agent review tool; pending/approved never execute automatically                             |
| REL-001/005; SEC-012; DATA-001               | Cross-client and alternate-ingress binding, tenant/owner/hash/expiry/reviewer/policy negatives; DB outage and lost-completion restart tests, code-only MCP failures                                                  | At-most-one dispatch per approval, not exactly-once target effects; repeated request creation not deduplicated |

See [ADR 0037](../architecture/0037-mcp-durable-simulator-approval-composition.md)
and the [Codex qualification runbook](../runbooks/codex-durable-approvals.md).
The [2026-10-15 model-driven evidence](../demo-scenarios/evidence/codex-model-driven-approval-2026-10-15.md)
adds one Codex 0.162.1/`gpt-5.6-sol` synthetic-browser profile: successful explicit
resume, rejected replay, rejected expired approval and rejected resume after
Bob's explicit denial, corroborated by a fresh read-only server projection.
Screenshots are not measured dispatch counts, production identity
proof or an exactly-once claim; automated claim tests remain separate evidence.

## Local browser approval slice

This additive mapping refines the rows below, not their production status.

| Requirements                | Implementation and verification                                                                                                                                                                                                                                         | Boundary                                                                                                        |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| FR-003; SEC-001/004/009     | `ApprovalBrowserAdapter`, `BrowserIdTokenVerifier`, optional operator browser configuration; signed code-flow, session/CSRF/origin, current-role and owner tests; one [named installed Auth0 record](../demo-scenarios/evidence/installed-auth0-approval-2026-10-22.md) | Synthetic profile plus one live native/public Auth0 registration; other IdPs and public edge pending            |
| FR-007/008/009; SEC-005/006 | React `ApprovalInbox`, shared `SimulatorApprovalServer` role/scope/hash checks and existing durable claim; requester/reviewer/viewer, exact hash and replay tests                                                                                                       | Simulator only; existing permit/execution invariant unchanged                                                   |
| REL-002/004                 | Distinct pending/approved/not-executed/consumed/outcome rendering, no mutation retry, logout/session restart with durable approvals retained                                                                                                                            | Local session memory, durable authority in PostgreSQL; no shared browser session service                        |
| SEC-012/015; DATA-001       | Tokens remain in server memory; safe code-only failures; HTTPS token POST inherits exact-URI DNS pinning/no-redirect limits; secret-file and token-substitution tests                                                                                                   | No reusable credentials in React persistence/evidence; process compromise and external revocation remain limits |

See [ADR 0036](../architecture/0036-local-browser-approval-boundary.md) and
[browser qualification runbook](../runbooks/browser-approval-inbox.md).

## Source-free simulator approval distribution

| Requirements                     | Implementation and verification                                                                                                                                                                                                                                                                                                                                                                             | Boundary                                                                                                                                                                                        |
| -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-012; SEC-003/011; OPS-002/004 | Dedicated Gradle target `:adapters-http:packageSimulatorApprovalDistribution`, fixed-entry-point launcher, built React inbox, clean-commit manifest `1.1`, complete file/JAR hashes and normalized archive metadata; `scripts/simulator-approval-distribution.test.mjs` verifies commit-only cache invalidation, dirty/missing metadata rejection, isolated staging, source-free offline check and shutdown | Host-native simulator foundation; Java 21, local PostgreSQL and operator IdP required; shared JAR entry points remain; trusted local build, not signed provenance or cross-host reproducibility |

See [ADR 0040](../architecture/0040-source-free-simulator-approval-distribution.md) and
the [distribution runbook](../runbooks/simulator-approval-distribution.md).

The [fresh Mac evaluation checklist](../runbooks/fresh-macbook-evaluation.md)
and grouped public navigation are checked by
`scripts/site-onboarding-browser.test.mjs` against the built static site,
including phone/tablet/desktop breakpoints, keyboard/mobile menu navigation,
canonical walkthrough content, home workflow links and full-size flow assets.
This rendered regression runs alongside the approval browser job; it does not
qualify installation on a second machine or a future connector archive.

The checklist
documents the separate source/synthetic and installed/operator paths, exact
commands, expected role/status transitions and credential-safe result collection
(OPS-002/004). Its [public rendering](../../site/guide/fresh-macbook-evaluation.md)
includes that same canonical walkthrough. The synchronized
[action/operator flows](../flows-guide.md) add browser history, explicit package
startup and declared containment. These are documentation/traceability additions,
not proof of a new laptop, external IdP, published release or production readiness;
requirement statuses remain unchanged.

Day 3 **preparation only** adds a third explicit live-provider/manual path,
owner-writable Corepack setup, clean-checkout/archive commit checks, trusted outer
and inner checksums, new extraction directory, missing-browser-config warning,
offline-before-init/run ordering, empty-only initialization, finite observation
budgets, loopback/port-release checks, disposable versus retained restart semantics
and private fact-only evidence. FR-007/012; SEC-003/011/012; REL-002; OPS-002/004
map to documentation conformance in `scripts/flow-diagrams.test.mjs` and built
phone/desktop canonical-page checks in `scripts/site-onboarding-browser.test.mjs`.
These tests compare supported command/output/archive/fixture facts to merged code;
they do not execute the founder's installation. Day 3, fresh-Mac, live external-IdP
and installed requester/Codex qualification remain pending; no runtime or whole-ID
status is changed.

The [public verification index](../../site/guide/verification.md) adds dated,
revision-pinned client evidence, named compatibility boundaries and two retained
screenshots of the same synthetic approval/replay case. It supports OPS-002/004
documentation discoverability, not new enforcement or client qualification.
`scripts/site-verification.test.mjs` checks built canonical/share metadata,
sitemap, evidence-source links and protocol identifier consistency;
`scripts/site-verification-browser.test.mjs` checks responsive evidence disclosure,
image loading, navigation/search and the approved public contact link. The
[MCP guide](../../site/guide/mcp-gateway.md) distinguishes optional protocol
authorization from action policy and limits action hashes to their actual scope.
No requirement status, runtime trust boundary, release status or external-IdP /
fresh-machine qualification is changed by this documentation slice.

## Durable policy-generation binding for simulator approvals

| Requirements                                          | Implementation and verification                                                                                                                                                                                                                                                 | Boundary                                                                                                                                                                                                                                                                                                                |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-007/008/013; SEC-001/002/005/006; REL-004; OPS-002 | `ActivePolicySnapshot` and `ApprovalPolicyBinding` capture version, content hash and monotonic `activation_sequence`; atomic `consumeAndClaim` share-lock revalidation; `PostgresPolicyGenerationBoundaryTest` adds eight real-PostgreSQL forced-interleaving/fault regressions | Opt-in simulator service only; stale claims cannot dispatch, but the post-precheck race can mint an unconsumed internal permit. Claim commit precedes dispatch; later activation is not cancellation. Legacy context-free raw static claims remain supported; no remote policy admin API/UI or database-admin boundary. |

See [ADR 0042](../architecture/0042-durable-policy-generation-binding.md).

## Bounded approval-response redaction

| Requirements                  | Implementation and verification                                                                                                                                                                                                                                                                | Boundary                                                                                                                                                                                            |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SEC-012; DATA-003; FR-007/010 | `EvidenceRedactor` validates all subtrees before hashing/copying, applies configured diagnostic text strategies, and masks explicit credential aliases in `SimulatorApprovalServer` response projections; `RedactionBoundaryReviewTest`, `EvidenceRedactionBoundsTest`, `EvidenceRedactorTest` | Exact canonical action snapshots/hashes stay authoritative and unchanged; no global logging, export or before-persistence hook; unkeyed fingerprints are guessable, not a confidentiality guarantee |

`SimulatorApprovalRedactionBoundaryTest` adds six authenticated HTTP/browser/MCP
boundary cases: masked list/detail and both MCP payload forms, original snapshot
and request-hash preservation, reader authorization/ownership before projection,
depth/node failure with no raw fallback or read-side effects, strict credential
argument rejection, and a clean review/execute/replay control. SEC-001/005/009/012
and FR-007/010 receive bounded evidence; DATA-003 remains partial because exact
operational snapshots are intentionally unredacted. Canary snapshots are seeded
only through internal test APIs, never accepted by public ingress.

See [ADR 0043](../architecture/0043-bounded-evidence-redaction.md) and the
[API qualification runbook](../runbooks/approval-response-redaction-qualification.md).

## Simulator database backup/restore qualification

`PostgresPolicyBoundRestoreQualificationTest` adds five opt-in generation-bound
service cases: pending/review/execute, consumed replay, activation staleness,
cyclic-rollback staleness with a fresh gen3 control, and pre-consumption snapshot
authority revival. All 17 captured tables (including full Flyway rows/checksums)
match before/after destination construction. Original request/action, binding,
attempt and hash-linked evidence are preserved; the stale-snapshot hazard keeps
an external effect counter and proves a second effect with fresh permit/attempt
IDs. Strict existing-state validation accepts the complete older snapshot.
FR-007/008/013, SEC-005/006, REL-001/003/005 and OPS-004 receive bounded evidence;
no broad status or tenant/credential-isolation claim is upgraded. See the
[generation-bound qualification](../runbooks/policy-bound-restore-qualification.md).

`PostgresBackupRestoreQualificationTest` adds six same-version PostgreSQL cases:
complete captured approval/action/permit/attempt/evidence and policy/migration
parity, no automatic dispatch, exclusive expiry, unsupported guard rejection,
and explicit stale-restore/empty-bootstrap gap characterization. Raw restored
metadata is checked before application constructors can apply migrations.

SEC-005/006, REL-001/003/005 and OPS-004 receive bounded evidence, not whole-ID
completion. Old snapshots can revive authority; a failed empty restore can be
initialized as a new database. Recovery safety remains **Partial**: no automated
fence, recovery-success gate, external target witness, safe resume procedure or
RPO/RTO qualification. SEC-009 and DATA-001 are context, not new broad claims.
See [ADR 0044](../architecture/0044-database-restore-safety-limits.md) and the
[runbook](../runbooks/simulator-backup-restore-qualification.md).

## Source-free local endpoint connector distribution

| Requirements                         | Implementation and verification                                                                                                                                                                                                                                                                                                                                                                                                                                                          | Boundary                                                                                                                                                                                                                                                                                                                             |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| FR-012; SEC-003/011/014; OPS-002/004 | Dedicated Gradle target `:endpoint-connector:packageEndpointConnectorDistribution`, thin host launcher `bin/tracepermit-endpoint-connector`, schema 1.1 manifest, complete file/JAR checksums, ambient JVM option unsetting, process parentage preservation, and fail-closed platform checking; `scripts/endpoint-connector-distribution.test.mjs` and `JavaBackendEndpointConnectorE2eTest` qualify offline checks, sensitive probe controls, and fail-closed non-interactive execution | Host-native macOS qualification foundation; requires host Java 21; Linux/Windows unsupported; same-UID POSIX key custody; numeric loopback only; mock protocol smoke gateway and real backend testing distinct; trusted local build integrity metadata, not reproducible release archive or third-party cryptographic code signature |

See [ADR 0055](../architecture/0055-source-free-endpoint-connector-distribution.md) and
the [distribution runbook](../runbooks/endpoint-connector-distribution.md).

## Proposed real-target credential and egress boundary

[ADR 0056](../architecture/0056-enterprise-credentials-and-egress-boundary.md)
adds design-review material for SEC-007/008 and OPS-005, not implementation or
qualification evidence. It separates one-use execution authority from provider
credential lifetime, defines operator-owned egress/credential responsibilities,
and lists future adapter-specific bypass, outage and uncertain-outcome probes.
Related controls are FR-001/008/009/012, SEC-003/006/010/012/013/014/019 and
REL-002/005/008. No new target, broker, network policy or production checkpoint
is enabled; all requirement statuses below remain unchanged. The
[current source anchors](../architecture/0056-enterprise-credentials-and-egress-boundary.md#current-source-anchors-not-enterprise-acceptance)
identify existing implementations and regressions without claiming a new run.
The [delivery gates](../architecture/0056-enterprise-credentials-and-egress-boundary.md#delivery-sequence-and-decision-gates)
keep local artifact acceptance, future remote simulator qualification and any
future real-target release separate.

## Functional requirements

| ID     | Design/source evidence                                                                                                                                                                                                                                                                                                                                                                                                                               | Test evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                    | Status                                                                                                                                          |
| ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-001 | HLD complete mediation; Java-only development/remote enforcement                                                                                                                                                                                                                                                                                                                                                                                     | Java MCP allow/block/no-executor, live projection, and connector E2E tests                                                                                                                                                                                                                                                                                                                                                                                       | Partial: configured POC paths only                                                                                                              |
| FR-002 | Canonical contracts; MCP/source/scheduled/remote Java adapters; separate `SimulatorApprovalServer` normalization                                                                                                                                                                                                                                                                                                                                     | Contract, injection, strict normalization, framing and `SimulatorApprovalServerTest` body/identity rejection                                                                                                                                                                                                                                                                                                                                                     | Partial: current development/simulator adapters                                                                                                 |
| FR-003 | Java MCP/OAuth/tunnel boundaries, hardened mTLS and separate loopback approval API with verified access tokens plus operator-owned membership; `SimulatorApprovalConfiguration` explicit OIDC/enrollment bootstrap                                                                                                                                                                                                                                   | Existing hosted/TLS/source qualification plus `SimulatorApprovalServerTest` signature/scope/membership-before-dispatch checks                                                                                                                                                                                                                                                                                                                                    | Partial: local API foundation and one hosted composition qualified; production deployment pending                                               |
| FR-004 | Java MCP handlers, static OAuth-declared hosted discovery/catalog, and frozen trusted tool registry with per-tool scopes                                                                                                                                                                                                                                                                                                                             | Public discovery grants no invocation; discovery-only credentials cannot execute; custom scope, duplicate-name, and snapshot tests                                                                                                                                                                                                                                                                                                                               | Implemented POC boundary; production tool onboarding pending                                                                                    |
| FR-005 | Java action/argument classifier and gateway-owned sequence context                                                                                                                                                                                                                                                                                                                                                                                   | Policy, exfiltration, injection, and tenant-isolation tests                                                                                                                                                                                                                                                                                                                                                                                                      | Implemented POC vocabulary; learned slow path pending                                                                                           |
| FR-006 | Policy schema `requiredSignals`, four outcomes, resolved derived-action sanitization, Scenario 7 deny, Scenario 8 approval, Scenario 9 exfil deny, and Scenario 10 IAM deny                                                                                                                                                                                                                                                                          | Allow/block/approval/sanitize, stop no-dispatch, deletion approval, exfil zero-send, injected IAM zero-dispatch, sanitizer and behavior-drift tests                                                                                                                                                                                                                                                                                                              | Implemented for current Java policy path                                                                                                        |
| FR-007 | ADRs 0013/0034/0042, approval service, PostgreSQL V2/V6/V8, immutable action snapshot, durable policy-generation binding, separate authenticated loopback API and Scenarios 3/8; `SimulatorApprovalBootstrapTest` operator-file restart/replay composition                                                                                                                                                                                           | Reviewer/owner/tenant, hash mutation, expiry, stale policy/generation, v1->v2->v1 rollback invalidation, replay, concurrency, rollback, restart, migration V9 guard, and nanosecond precision tests                                                                                                                                                                                                                                                              | Implemented local simulator API/browser foundation; external IdP qualification pending                                                          |
| FR-008 | Direct/v2 permits, optional Ed25519 issuer/verifier-only separation, remote Java path, approval, and derived-action permit after sanitize; pre-issuance active policy generation validation in `HumanApprovalService`; local bootstrap loads matching persistent Ed25519 file keys; standalone MariaDB permit replay-store foundation; ADR 0080 standalone durable workload-identity replay store across PostgreSQL 16.4 and MariaDB 11.4.13/10.6.25 | HMAC/vector compatibility, Ed25519 issue/verify, algorithm-confusion, verifier-only, approval, stale policy generation permit suppression, PostgreSQL claim, one-effect and replay tests; exact MariaDB 10.6.25/11.4.13 single-use/restart/concurrency tests; `WorkloadIdentityReplayStoreContract` real-engine suite across PostgreSQL 16.4, MariaDB 11.4.13, and MariaDB 10.6.25 (85 tests total including unit tests)                                         | Partial: MariaDB and workload-identity replay stores are unwired standalone state only; production key custody and deployment isolation pending |
| FR-009 | Trusted executor verifies and claims permit before invoking simulator behind Java development/remote paths                                                                                                                                                                                                                                                                                                                                           | Permit mutation/audience/replay and remote allow/block tests                                                                                                                                                                                                                                                                                                                                                                                                     | Implemented for simulator                                                                                                                       |
| FR-010 | Durable evidence/outbox, signed receipts, bounded edge telemetry, approval lifecycle, and live POC projection                                                                                                                                                                                                                                                                                                                                        | Chain, receipt, approval-scenario integrity, redaction, timezone/latency, observer-isolation, OTel, and no-dispatch tests                                                                                                                                                                                                                                                                                                                                        | Partial: real anchor/exporter pending                                                                                                           |
| FR-011 | ADRs 0014/0080, signed grant schemas, Java complete-chain verifier, signed-origin Scenario 8, Scenario 11 sub-agent presenter, and standalone durable workload-identity replay store across PostgreSQL 16.4 and MariaDB 11.4.13/10.6.25                                                                                                                                                                                                              | Signature, parent, narrowing, topology, revocation/replay tests; one narrow read and four signed amplification rejections before policy; `WorkloadIdentityReplayStoreContract` single-use, namespace-isolation, surrogate-rejection, agreed-capacity saturation, physical-schema validation, wait-induced expiry rollback, post-commit uncertainty, and exception diagnostic redaction tests across PostgreSQL 16.4 and MariaDB 11.4.13/10.6.25 (85 tests total) | Implemented Java foundation; durable workload-identity replay store is unwired standalone library; production pending                           |
| FR-012 | Stateful Java AWS/Jira simulator plus fixed customer/outbox and runbook/IAM simulators, including one in-memory security-group deletion; operator approval main has one fixed simulator mutation and default-block policy; dedicated source-free host distributions and the Linux loopback container profile package the approval composition                                                                                                        | Java MCP, simulator state, fixed target/argument bounds, denial, zero-outbox, zero-IAM-mutation tests, distribution archive qualification, and `scripts/container-install-profile.test.mjs` with no target invocation                                                                                                                                                                                                                                            | Implemented for current simulated adapters                                                                                                      |
| FR-013 | In-memory and PostgreSQL durable version staging, isolated dry-run, atomic activation, optimistic concurrency, and previous-activation rollback; optional dynamic execution constructor; active policy generation snapshot and approval binding                                                                                                                                                                                                      | `PolicySnapshotIsolationTest`, `PolicyNumericRangeTest`, lifecycle tests, policy store/boundary tests (28 tests), application zero-dispatch mutation regression, and `PostgresPolicyBoundApprovalTest` (7 tests)                                                                                                                                                                                                                                                 | Partial: tested Java/PostgreSQL foundation and simulator approval binding; authenticated durable control-plane workflow pending                 |
| FR-014 | Java MCP/CLI/scheduled, ChatGPT tunnel profiles, hosted-client OAuth, OAuth TLS adapters, and signed-workload Scenarios 4/7/8/9/10/11                                                                                                                                                                                                                                                                                                                | MCP, stdio, tunnel, OAuth, live Auth0/ChatGPT evidence, scheduled/semantic/cross-target/injection/delegation tests                                                                                                                                                                                                                                                                                                                                               | Partial: tested hosted profile qualified; broader connectors pending                                                                            |
| FR-015 | Durable unknown queue plus leased lookup-only Java reconciliation worker                                                                                                                                                                                                                                                                                                                                                                             | PostgreSQL contention, deferral, restart, and append-only tests                                                                                                                                                                                                                                                                                                                                                                                                  | Partial: production target lookup adapters pending                                                                                              |
| FR-016 | Verified source projection, Java observer, and endpoint-key boundary                                                                                                                                                                                                                                                                                                                                                                                 | Projection, observer/stability, framing, and subprocess tests                                                                                                                                                                                                                                                                                                                                                                                                    | Partial: managed observer/enrollment pending                                                                                                    |
| FR-017 | Request- and credential-bound Ed25519 source attestation                                                                                                                                                                                                                                                                                                                                                                                             | Mutation, time, key, replay, and pre-dispatch tests                                                                                                                                                                                                                                                                                                                                                                                                              | Implemented for development CLI profile                                                                                                         |

## Security requirements

| ID      | Current evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | Status                                                                                                                                                                                                                                                                                                                                                         |
| ------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SEC-001 | Strict MCP/scheduled/OAuth schemas and approval API; operator-owned enrollment/roles, server-normalized action; body/token role claims cannot self-authorize; startup enrollment is operator-owned, never HTTP self-enrollment; policy choice and generation derived from trusted bootstrap, never request body                                                                                                                                                                                                                                     | Implemented for current adapters; internal API restricted                                                                                                                                                                                                                                                                                                      |
| SEC-002 | Conservative defaults and source/workload/delegation/OAuth checks; `PolicyValidator` rejects unknown fields/enums, duplicate IDs and unsupported Java version/priority ranges; PostgreSQL triggers guard immutable fields, append-only history, and immutable approval policy bindings (`tp_deny_approval_policy_bindings_mutation`) against ordinary SQL; first-activation, transaction-failure, stale-generation, and unknown-schema regressions; numeric overflow, collision, and DB unavailability fail-closed regressions                      | Implemented for supported Java profiles; frozen wider language-neutral schema unchanged                                                                                                                                                                                                                                                                        |
| SEC-003 | Fingerprint-only projections, public-key-only vectors, and Java artifact scan; owner-only bounded config/secret/key files and secret-safe bootstrap tests; distribution inventories; container image excludes source/build tools/private input, uses file-backed secrets and runtime-generated keys, rejects group/world-accessible input plus changed retained files/default secret values, and binds OCI inventory to the verified archive hash/manifest commit                                                                                   | Implemented baseline; signed image provenance/managed custody pending                                                                                                                                                                                                                                                                                          |
| SEC-004 | Exact permit/auth/source/workload, RS256/OAuth/mTLS foundations plus local approval API access-token and per-call scope checks; explicit one-issuer/client/tenant approval configuration                                                                                                                                                                                                                                                                                                                                                            | Partial: local membership replacement checked; production IdP revocation and broader connectors pending                                                                                                                                                                                                                                                        |
| SEC-005 | Versioned canonical binding, immutable approval action snapshots, exact API request hash, stale-policy/generation rejection, V10 schema guard, millisecond timestamps; creation-race and legacy-context-omission regressions verify coherent stored binding and claim revalidation                                                                                                                                                                                                                                                                  | Implemented for tested Java direct/approval/sanitize and loopback API compositions; generation binding is opt-in, not default-launcher behavior                                                                                                                                                                                                                |
| SEC-006 | PostgreSQL atomic approval/execution claims with active-policy share-locking; forced claim-first/activation-first schedules observe actual lock waits; standalone PostgreSQL plus unwired MariaDB permit adapters have verified-tenant/action-hash/fault regressions; MariaDB 10.6.25/11.4.13 exercise 32-way primary-key contention and acknowledgement uncertainty; mutation/deletion scenarios preserve one simulated effect and replay denial; precheck race rejects stale claim without permit consumption, attempt or approval-evidence delta | Implemented for tested Java durable paths; MariaDB is standalone replay state only, not approval/evidence composition or RDS qualification. Internal permit minting can precede stale rejection. Legacy context-free static claims are not globally fenced; standalone adapters are not the composed claim; production durability/target isolation unqualified |
| SEC-007 | No target SDK/credential in Java simulator; least privilege specified                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | Partial: real adapters remain target                                                                                                                                                                                                                                                                                                                           |
| SEC-008 | All supported backend calls reach fixed Java simulators, including the separate non-network synthetic outbox; no target SDK/credential                                                                                                                                                                                                                                                                                                                                                                                                              | Partial: managed egress/target controls pending                                                                                                                                                                                                                                                                                                                |
| SEC-009 | Trusted tenant contexts plus approval API membership, requester-only execution, reviewer separation and tenant/owner-isolated snapshot reads                                                                                                                                                                                                                                                                                                                                                                                                        | Partial: authenticated local API tested; durable production tenant administration pending                                                                                                                                                                                                                                                                      |
| SEC-010 | Durable execution/approval and allowed-remote evidence/outbox composition                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | Partial: edge-denial stream/external publisher/anchor pending                                                                                                                                                                                                                                                                                                  |
| SEC-011 | Java development backend and endpoint transport stay direct numeric loopback-only, with no ambient proxy or redirect; approval/endpoint launchers preserve loopback and offline checks; Linux container profile uses host networking with zero published ports and verifies both service sockets bind only `127.0.0.1`                                                                                                                                                                                                                              | Implemented POC foundation; Docker Desktop/bridge/public deployment unsupported                                                                                                                                                                                                                                                                                |
| SEC-012 | Read/stored/OAuth/remote/sanitization evidence omits tokens, internals, raw arguments, and results; Scenario 10 omits injected tool text; `SimulatorApprovalMain.execute` code-only redacted startup errors                                                                                                                                                                                                                                                                                                                                         | Partial: bounded redaction library and approval-response projection tested; global logs/export/storage coverage pending                                                                                                                                                                                                                                        |
| SEC-013 | Injected registry/signing port, public-only vector, restrictive dev key file                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | Partial: protected key provider/enrollment pending                                                                                                                                                                                                                                                                                                             |
| SEC-014 | Ed25519 verifier checks trust context, bindings, time, and atomic single use; endpoint transport rejects proxy/remote destination; automated/non-interactive execution fails closed (`SOURCE_TRIGGER_NOT_INTERACTIVE`) against real backend before target dispatch; mock protocol gateway qualifies wire framing                                                                                                                                                                                                                                    | Implemented for development CLI profile                                                                                                                                                                                                                                                                                                                        |
| SEC-015 | Exact JWKS URI, public-address DNS/pinning, TLS, no redirects/proxies/retries, time/size bounds; approval startup reuses lazy exact-URI SSRF-safe JWKS source; container profile does not add proxy trust or weaken destination validation                                                                                                                                                                                                                                                                                                          | Implemented Java outbound adapter; deployment egress and container IdP qualification pending                                                                                                                                                                                                                                                                   |
| SEC-016 | Verified RFC 8705 thumbprint projection plus direct TLS peer-certificate equality; permissive test factory rejects non-loopback binding; container profile explicitly makes no sender-constraint compatibility claim                                                                                                                                                                                                                                                                                                                                | Implemented hardened Java profile; live connector/container edge support pending                                                                                                                                                                                                                                                                               |
| SEC-017 | Pre-auth network and post-auth principal limiter ports with process-local per-key atomic claims; test-only allow-all factory restricted to loopback; container profile adds no public ingress and no replacement for shared abuse state                                                                                                                                                                                                                                                                                                             | Partial: shared/distributed production state pending                                                                                                                                                                                                                                                                                                           |
| SEC-018 | Exact receipt binding plus operator-trusted Ed25519 verification and immutable PostgreSQL anchor                                                                                                                                                                                                                                                                                                                                                                                                                                                    | Implemented Java/PostgreSQL foundation; production sink pending                                                                                                                                                                                                                                                                                                |
| SEC-019 | Worker exposes only target lookup and rechecks tenant/adapter/idempotency lease binding                                                                                                                                                                                                                                                                                                                                                                                                                                                             | Implemented Java/PostgreSQL foundation; production lookup pending                                                                                                                                                                                                                                                                                              |
| SEC-020 | Strict telemetry envelope plus required pre-dispatch hardened-edge emission; discarded test telemetry cannot bind publicly                                                                                                                                                                                                                                                                                                                                                                                                                          | Implemented Java foundation; exporter health/deployment pending                                                                                                                                                                                                                                                                                                |

## Reliability requirements

| ID      | Current evidence                                                                                                                                                                                                                                                                                                                                                                                                                                   | Status                                                                                                                                                                                                         |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| REL-001 | Runtime gates plus remote readiness, abuse, sender, approval, sequence, and sanitizer fail-closed tests; invalid config and DB startup failure cannot open approval listener                                                                                                                                                                                                                                                                       | Partial: live production probes/shared sequence pending                                                                                                                                                        |
| REL-002 | Durable approval/execution transitions plus Scenario 3 lifecycle evidence                                                                                                                                                                                                                                                                                                                                                                          | Partial: production adapter/runtime pending                                                                                                                                                                    |
| REL-003 | Tenant/adapter/idempotency unique key and duplicate no-dispatch test                                                                                                                                                                                                                                                                                                                                                                               | Partial: real target propagation pending                                                                                                                                                                       |
| REL-004 | Existing permit/approval/workload/rate/API contention tests; PostgreSQL and exact-version MariaDB standalone 32-task/four-store contention and committed-row replay; commit/acknowledgement/rollback/close failures plus PostgreSQL pruning; eight policy-generation boundary tests cover both lock schedules, creation/precheck races, binding-insert rollback, legacy compatibility and claim commit with lost acknowledgement and zero dispatch | Implemented in selected Java compositions; MariaDB is unwired and omits pruning/composed state. Bounded synthetic fault coverage, not RDS/failover, all distributed schedules or exactly-once target execution |
| REL-005 | Durable append-only unknown queue and lookup-only worker foundation (not wired into browser launcher)                                                                                                                                                                                                                                                                                                                                              | Implemented simulator foundation                                                                                                                                                                               |
| REL-006 | In-memory and PostgreSQL policy rollback with defensive revision documents, predecessor transition resolution, 32-way current-version contention, first-activation interleaving, failure rollback, restart survival, and zero-dispatch mutation regressions; bounded OAuth claim/JWKS rotation foundation                                                                                                                                          | Partial: durable authenticated rollout and production key inventory pending                                                                                                                                    |
| REL-007 | PostgreSQL outbox leases, stable IDs, retry schedule, and 32-way claim test                                                                                                                                                                                                                                                                                                                                                                        | Implemented Java/PostgreSQL foundation                                                                                                                                                                         |
| REL-008 | PostgreSQL reconciliation queue, leases, retry, restart, and 32-way test                                                                                                                                                                                                                                                                                                                                                                           | Implemented Java/PostgreSQL foundation                                                                                                                                                                         |
| REL-009 | Edge matrix plus startup/auth/telemetry/restart/sequence/sanitizer tests and fail-closed tunnel launcher                                                                                                                                                                                                                                                                                                                                           | Implemented for current Java edges                                                                                                                                                                             |
| REL-010 | Count/diversity/window gate with zero tolerated behavior differences                                                                                                                                                                                                                                                                                                                                                                               | Implemented library gate; production thresholds pending                                                                                                                                                        |

## Performance requirements

| ID       | Evidence                                                                                              | Status                                                   |
| -------- | ----------------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| PERF-001 | Warmed 10,000-sample deterministic classifier/policy p50/p95 harness plus console current-run samples | Implemented local POC objective; production load pending |
| PERF-002 | Warmed 1,000-sample local authorization/permit/simulator p50/p95 harness                              | Implemented local POC objective; production load pending |
| PERF-003 | No scale/load validation                                                                              | Proposed objective                                       |
| PERF-004 | Synchronous execution plus durable pending approval; no remote async workflow                         | Partial design                                           |

The simulator latency harness retains nonzero failure/timeout/cancellation and
exact state-delta gates while adding bounded, fixed-category operation and
test-container log diagnostics. `LatencyFailureDiagnosticsTest` qualifies safe
projection, category cardinality and failure accounting (SEC-003; REL-001/005;
OPS-003). It does not establish PERF-003 scale validation, a latency SLO, an
authoritative database-error count or successful qualification of a failed run.
See the [latency runbook](../runbooks/simulator-approval-latency-qualification.md#reading-a-failed-qualification).

The [transport/admission diagnostic checkpoint](../runbooks/latency-qualification-transport-diagnostics.md)
adds test-only fixed cause categories, a delegating admission observer, bounded
final-client cleanup and a controlled negative barrier/HTTP probe.
`LatencyTransportDiagnosticsTest` covers redaction, bounded/cyclic causes,
terminal accounting, downstream missing-ID categories, saturation and unchanged
rejection/handler restoration (OPS-002/003/004; SEC-003/005/006/012;
REL-001/005/009). The unchanged frozen runtime can report barrier completion
before workers are released; the controlled 20-attempt probe observed16 handled
and four refused/reset exchanges. The narrowly authorized package-private
qualification helper now waits for worker release under its original total
deadline; `ApprovalAdmissionExecutorsTest` covers the held-after-Future gap,
timeout, interrupt and shutdown. No request enforcement or admission changes.
Hosted causality/reliability remain unproved. No PERF-001/002/003 completion or promotion.
The three-independent-pass qualification was not achieved: a post-repair full
run still failed with one status-phase reset and one actual exchange rejection.
The helper-only checkpoint remains historical. Subsequent explicit approval
authorizes the bounded dispatch repair in ADR0074; current qualification results
are recorded separately in the diagnostic runbook without erasing these failures.

## Data and privacy requirements

| ID       | Current evidence                                                                                                                                              | Status                                                               |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| DATA-001 | Fingerprint-only identity evidence                                                                                                                            | Implemented baseline                                                 |
| DATA-002 | Retention specified, no store                                                                                                                                 | Target                                                               |
| DATA-003 | Bounded MASK/HASH/DROP projection library; original canonical action/hash unchanged; tests and [ADR 0043](../architecture/0043-bounded-evidence-redaction.md) | Partial: before-persistence integration and secret lifecycle pending |
| DATA-004 | Durable approval/execution transitions, executable lifecycle evidence, and reconciliation evidence                                                            | Implemented for Java durable profile                                 |
| DATA-005 | Deployment/data-residency question recorded                                                                                                                   | Target                                                               |

## Operability requirements

| ID      | Current evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | Status                                                                                                                    |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------- |
| OPS-001 | Combined Java health/readiness, bounded runtime info/resource discovery, safe logs, and remote OTel; approval bootstrap emits bounded startup codes/address, no production health claim                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | Partial: production logs/exporter pending                                                                                 |
| OPS-002 | Java policy/runtime queries, in-memory and PostgreSQL version/transition/reason records, active policy snapshot generation, remote policy/classifier and OAuth key provenance; MariaDB inspect/validate reports exact qualified engine version, support level, database and storage engine without credentials; fixed approval policy v2, configured Ed25519 key ID and schema guards; distribution manifests `1.1` with clean commit/commit time as cache-tracked inputs, package version, required Java and exact dependency hashes; exact-HEAD archive requirement for installed E2E qualification with Gradle multi-input invalidation; fail-closed Git provenance and stale-archive regressions                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | Partial: MariaDB RDS/failover inventory, durable authenticated policy administration and production key inventory pending |
| OPS-003 | Bounded denial/authorization/completion events plus OTel projection                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | Partial: production alert routing pending                                                                                 |
| OPS-004 | POC lifecycle, successful INT/TERM/HUP shutdown and negative child/validation subprocess tests, web/CLI/approval/signed-workload qualification, fixed-path and key-identity-checked cleanup, numbered Scenarios 0–11; `SimulatorApprovalBootstrapTest` real-JVM offline check, startup, SIGTERM and port release; simulator approval and endpoint connector distribution runbooks, confirmed graceful termination (SIGTERM -> SIGKILL) rejecting on unconfirmed exit, port release, and failure bounds qualification; bounded Node fixture readiness waits (error, early exit, silent child) with registered cleanup; bounded Java tar/Linux process waits/drains; ProcessTreeTracker owned descendant tracking, reparenting tolerance without host-wide process-name kills, confirmed grandchild startup, monotonic elapsed time, and explicit unconfirmed cleanup failure; primary test failure preservation via suppressed cleanup exceptions; documented host OS orphan discovery limits; public failure diagnostics omitting stdout/stderr and error message text with stable error codes (ERR_READINESS_*), memory sliding window, flood controls, and synthetic canary timeout/early-exit regressions | Partial: one hosted OAuth profile/read/block passed; production operations remain target                                  |
| OPS-005 | Exact readiness aggregate plus pre-auth and pre-dispatch telemetry failures                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | Partial: no production bootstrap/live checks                                                                              |

## Portability requirements

| ID       | Current evidence                                                                                | Status                               |
| -------- | ----------------------------------------------------------------------------------------------- | ------------------------------------ |
| PORT-001 | Versioned language-neutral schemas for action, policy, identity, permit, approval, and evidence | Implemented artifact set             |
| PORT-002 | Named canonicalization/signature profiles and deterministic golden vectors                      | Implemented POC profiles             |
| PORT-003 | Java validates checked-in schemas, fixtures, and vectors                                        | Implemented conformance suite        |
| PORT-004 | MCP, CLI, scheduler, and hosted-client adapters converge on canonical action contract tests     | Partial: named POC origins qualified |

## Maintenance rule

Every implementation pull request citing a requirement must update the
corresponding row when status or evidence changes. “Implemented” requires a
linked enforcement point and a test capable of failing when the behavior is
removed.

## Proposed pending-request cancellation and upgrade gate

FR-003/007/008/009/010; SEC-001/002/003/005/006/009/012;
REL-001/002/004/005; DATA-001; OPS-002/004; PORT-001/003 are mapped to the
[proposed contract](../design/pending-approval-cancellation.md) and
[ADR 0058](../architecture/0058-pending-approval-cancellation-contract.md).
The design itself is not implementation evidence. The Java withdrawal slice is
recorded below; local requester UI is covered by ADR0061, while source-free
installed withdrawal qualification remains a future acceptance gate. No
cancellation MCP tool exists.

### Authenticated pending-only Java withdrawal

[ADR 0060](../architecture/0060-authenticated-pending-request-withdrawal.md)
and the [withdrawal runbook](../runbooks/pending-approval-withdrawal.md) define the bounded slice.

| Requirements                                   | Enforcement point                                                                                                                                                         | Regression evidence                                                                                                                                                                                    |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| FR-003/007/009; SEC-001/002/005/006/012        | Shared bearer/browser cancel route, current enrollment plus explicit scope, owner visibility, distinct trusted requester context and post-lock deadline/hash/state checks | `PendingApprovalCancellationTest`; `SimulatorApprovalServerTest` cancellation cases; `SimulatorApprovalBrowserTest` owner/CSRF/shorter-session-deadline cases                                          |
| FR-010; REL-001/002/004; DATA-001; SEC-003/009 | `PostgresDurableEnforcementState.cancel`, recovery→request→evidence locks, V10 append-only transition and version3 bounded evidence/outbox transaction                    | `PostgresApprovalCancellationTest`: both review schedules, duplicate cancellation, expiry during lock wait, evidence/outbox rollback, quarantine, restart, uncertain commit and zero claim/permit rows |

These are local Java/synthetic-authentication simulator boundaries, not external
IdP, installed withdrawal or production-target qualification.

### Explicit withdrawal in the local requester inbox

[ADR 0061](../architecture/0061-requester-withdrawal-inbox.md) and
`approval-withdrawal.test.ts` / `approval-history-browser.test.mjs` add bounded
client evidence for FR-003/007/009/010; SEC-004/005/006/009/012; REL-002/004/005.
Owner/requester/explicit-scope/pending/expiry guidance, exact ID/hash and authority
confirmation binding, cookie/CSRF exact-hash POST, Back without mutation, duplicate
click suppression and cancelled history are covered. Mounted real-Java/DB tests
exercise review-first conflict and a committed cancellation whose client-facing
acknowledgement is replaced with503. The UI clears stale authority, claims no
success and does not automatically retry. Controlled client scope/session/time
projections are UX controls, not live-provider or server-time qualification.
Backend authorization/atomicity remains ADR0060's boundary; installed withdrawal,
fresh-host and external-IdP gates remain open.

### Implemented storage/upgrade prerequisite

[ADR 0059](../architecture/0059-explicit-v9-v10-simulator-upgrade.md) maps the
following bounded evidence; no whole requirement or production-readiness status
is promoted by this foundation.

| Requirements                                      | Enforcement/compatibility point                                                                                                                                 | Regression evidence and limits                                                                                                                                                                                                                                                    |
| ------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-007/009/010; SEC-005/006; DATA-001             | V10 pending→cancelled-only SQL provenance/terminal guards; Java state/history, React parser and inspection compatibility                                        | `PostgresDatabaseUpgradeTest` owner/null/deadline/terminal SQL cases; `approval-api.test.ts` reads cancelled but offers no authority. SQL fixture is not authenticated cancellation or atomic lifecycle evidence proof.                                                           |
| SEC-003/009/012; REL-001/002/004/005; OPS-002/004 | `PostgresDatabaseLifecycle.upgradeFromV9`, immutable V1–V9, exact predecessor, owner/ready/unresolved-work gates, advisory unlock, transaction/final validation | Genuine V9 populated row/history preservation, unsupported/tampered/empty/RLS/trigger/schema/connection failures, competing and retained leases, DDL rollback/timeout, post-commit validation acknowledgement loss; metadata checks are not malicious-DBA attestation.            |
| FR-008; SEC-003; PORT-001/003                     | Local command dispatcher, source/installed launchers; normal run validate-only, init empty-only; inspection report2.0 includes cancelled                        | `SimulatorApprovalBootstrapTest` no runtime runner/listener/claim; installed-distribution V9 fixture → run/init refusal → explicit upgrade → repeat refusal → inspection with zero claims/permits. JDK/source used only for test fixture setup, not installed operator execution. |

## Installed requester public sign-in

| Requirements                            | Implementation and automated evidence                                                                                                                                                                                                        | Boundary                                                                                                                              |
| --------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| FR-003/007/012; SEC-002/004/009/011/015 | `InstalledRequesterAuthMain`, strict config/token verifiers, one-shot callback, bodyless requester qualification route, existing membership resolver and per-MCP authorization; generated-key/unit and PostgreSQL-backed qualification tests | Same-Mac numeric-loopback simulator only; live provider/Codex qualification pending; no prompt/executable/source proof                |
| SEC-003/012; REL-001/004/009            | Owner-only atomic no-replace `RequesterCredentialStore`, one-winner publication race, bounded callback/response/file parsers, stable failures, consume-before-child `CodexLauncher`, secret-sentinel and argv/environment tests              | Raw access token exists briefly in one `0600` file and child environment; same-UID/root compromise outside boundary; no refresh/retry |
| REL-006; OPS-001/002/004/005            | Separate removable launcher/route/config; simulator archive manifest 1.2 identifies both entry points and exact clean source/JAR inventory; installed requester runbook and distribution tests                                               | Trusted local build integrity, not signed provenance or production readiness; manual Mac gate remains explicit                        |

See [ADR 0065](../architecture/0065-installed-requester-public-sign-in.md), the
[design contract](../design/installed-requester-public-sign-in.md), and the
[installed runbook](../runbooks/installed-requester-public-sign-in.md).

## Unwired GitHub App installation credential-provider foundation

Requirements: FR-001/008/009/012; SEC-001/002/003/012/015/019;
REL-001/003/005/009; OPS-002/004. No whole-requirement status promotion.

`github.credentials.GitHubInstallationConfiguration`, `PrivateAppKeyFile`,
`GitHubInstallationCredentialProvider`, `GitHubCredentialTransport` and the fixed
`GitHubCredentialException` implement operator-only immutable scope, private
bounded RSA source, RS256 JWT, restricted App-authenticated GET/POST, explicit
provider assurance, expiry-after-cleanup, public pinned DNS/platform TLS and
closed diagnostics. Existing ports/target action/permit/preflight code is unchanged.

`GitHubInstallationCredentialProviderTest` and `CredentialBoundaryTest` cover
positive and adversarial identity/scope/clock/JSON/byte/status, private-source,
crypto/redaction/cleanup, no follow-up/unrelated request and concurrent isolation.
Missing optional response assurance fails closed; opaque installation provenance
remains dependent on GitHub's authenticated minting contract. Exact results and
limits belong in the [runbook](../runbooks/github-app-installation-credentials.md),
[ADR0076](../architecture/0076-github-installation-credential-provider.md) and
final handoff. This library is unwired; no live App/target or production claim.

Wave 2 evidence for the same IDs: `CredentialReviewRegressionTest` adds 105
typed/omitted assurance, identity, clock/cleanup, owned-buffer and production
client-policy cases; `CredentialTransportBudgetTest` adds eight decoded-byte,
compression, total-deadline and one-exchange cases. `GitHubCredentialCompositionTest`
adds 15 concrete-provider/adapter cases through the test-source
`SyntheticInstallationCredentialFixture`: permit/request/allowlist failures
stop before credential acquisition, provider/expiry failures stop before target
access, successful minting retains exact target preflight, and ambiguous writes
remain unknown without another dispatch. The fixture uses only fake exchanges
and a numeric-loopback fake target. The process-local ledger is test state;
durable business-operation authority and shipped-runtime integration remain
unimplemented by this lane. No other requirement status or lane claim changes.

## GitHub pilot operator diagnostics (ADR0081)

| Requirements                 | Evidence                                                                                                        | Boundary                                                                                                     |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| SEC-001/002/003/012          | `pilot-operator` private bounded strict JSON/key validation; `PilotConfigurationTest`; `PilotArchiveTest`       | Operator pins only; no identity/authority claims or secret rendering                                         |
| SEC-007/015; REL-001/005/009 | Concrete existing credential provider + exact existing target GET preflight; `PilotPreflightTest`               | Synthetic only; no dispatch, reconciliation, live compatibility or absence proof                             |
| OPS-002/004                  | Clean-source manifest, dependency hashes, source-free launcher/archive and operator runbook; distribution tests | Integrity metadata is not current policy, a signature or production authority; macOS host validation pending |

## Selective CI and cancellation determinism (ADR0088)

- REL-001/009: `PilotOperatorMain.bounded` rejects an already-interrupted caller
  before worker/provider construction; `PilotPreflightTest` retains exact
  cancellation/interrupt assertions and additionally proves zero provider calls.
  Synthetic diagnostics only; no new target or authority.
- OPS-002: base-branch change selection, explicit planned skips, fresh database
  shards, exact-source/run coverage manifests and unchanged 94%/75% coverage gates
  are covered by `scripts/ci-*.test.mjs`, local Java checks and exact-head PR CI.
  A selective pass is not full backend or production qualification.

See [ADR0088](../architecture/0088-selective-ci-and-complete-coverage.md) and the
[learning note](../learning/ci-selection-is-not-qualification.md). No existing
requirement status or simulator-only boundary is promoted.
