# Requirements traceability matrix

**Status:** Living implementation index  
**Applies to:** Current POC and target roadmap

Status values: **Implemented**, **Partial**, **Target**, or **Proposed**.

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
startup refusal, bounded diagnostics and no resume/upgrade command.
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
and static-policy launcher scope are explicit; no upgrade/resume command is added.

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

| Requirements                | Implementation and verification                                                                                                                                       | Boundary                                                                                                        |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| FR-003; SEC-001/004/009     | `ApprovalBrowserAdapter`, `BrowserIdTokenVerifier`, optional operator browser configuration; signed code-flow, session/CSRF/origin, current-role and owner tests      | Implemented local synthetic browser profile; live external IdP/public edge pending                              |
| FR-007/008/009; SEC-005/006 | React `ApprovalInbox`, shared `SimulatorApprovalServer` role/scope/hash checks and existing durable claim; requester/reviewer/viewer, exact hash and replay tests     | Simulator only; existing permit/execution invariant unchanged                                                   |
| REL-002/004                 | Distinct pending/approved/not-executed/consumed/outcome rendering, no mutation retry, logout/session restart with durable approvals retained                          | Local session memory, durable authority in PostgreSQL; no shared browser session service                        |
| SEC-012/015; DATA-001       | Tokens remain in server memory; safe code-only failures; HTTPS token POST inherits exact-URI DNS pinning/no-redirect limits; secret-file and token-substitution tests | No reusable credentials in React persistence/evidence; process compromise and external revocation remain limits |

See [ADR 0036](../architecture/0036-local-browser-approval-boundary.md) and
[browser qualification runbook](../runbooks/browser-approval-inbox.md).

## Source-free simulator approval distribution

| Requirements                     | Implementation and verification                                                                                                                                                                                                                                                                                                                                                                             | Boundary                                                                                                                                                                                        |
| -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-012; SEC-003/011; OPS-002/004 | Dedicated Gradle target `:adapters-http:packageSimulatorApprovalDistribution`, fixed-entry-point launcher, built React inbox, clean-commit manifest `1.1`, complete file/JAR hashes and normalized archive metadata; `scripts/simulator-approval-distribution.test.mjs` verifies commit-only cache invalidation, dirty/missing metadata rejection, isolated staging, source-free offline check and shutdown | Host-native simulator foundation; Java 21, local PostgreSQL and operator IdP required; shared JAR entry points remain; trusted local build, not signed provenance or cross-host reproducibility |

See [ADR 0040](../architecture/0040-source-free-simulator-approval-distribution.md) and
the [distribution runbook](../runbooks/simulator-approval-distribution.md).

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

## Functional requirements

| ID     | Design/source evidence                                                                                                                                                                                                                                                                       | Test evidence                                                                                                                                                                                                    | Status                                                                                                                          |
| ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| FR-001 | HLD complete mediation; Java-only development/remote enforcement                                                                                                                                                                                                                             | Java MCP allow/block/no-executor, live projection, and connector E2E tests                                                                                                                                       | Partial: configured POC paths only                                                                                              |
| FR-002 | Canonical contracts; MCP/source/scheduled/remote Java adapters; separate `SimulatorApprovalServer` normalization                                                                                                                                                                             | Contract, injection, strict normalization, framing and `SimulatorApprovalServerTest` body/identity rejection                                                                                                     | Partial: current development/simulator adapters                                                                                 |
| FR-003 | Java MCP/OAuth/tunnel boundaries, hardened mTLS and separate loopback approval API with verified access tokens plus operator-owned membership; `SimulatorApprovalConfiguration` explicit OIDC/enrollment bootstrap                                                                           | Existing hosted/TLS/source qualification plus `SimulatorApprovalServerTest` signature/scope/membership-before-dispatch checks                                                                                    | Partial: local API foundation and one hosted composition qualified; production deployment pending                               |
| FR-004 | Java MCP handlers, static OAuth-declared hosted discovery/catalog, and frozen trusted tool registry with per-tool scopes                                                                                                                                                                     | Public discovery grants no invocation; discovery-only credentials cannot execute; custom scope, duplicate-name, and snapshot tests                                                                               | Implemented POC boundary; production tool onboarding pending                                                                    |
| FR-005 | Java action/argument classifier and gateway-owned sequence context                                                                                                                                                                                                                           | Policy, exfiltration, injection, and tenant-isolation tests                                                                                                                                                      | Implemented POC vocabulary; learned slow path pending                                                                           |
| FR-006 | Policy schema `requiredSignals`, four outcomes, resolved derived-action sanitization, Scenario 7 deny, Scenario 8 approval, Scenario 9 exfil deny, and Scenario 10 IAM deny                                                                                                                  | Allow/block/approval/sanitize, stop no-dispatch, deletion approval, exfil zero-send, injected IAM zero-dispatch, sanitizer and behavior-drift tests                                                              | Implemented for current Java policy path                                                                                        |
| FR-007 | ADRs 0013/0034/0042, approval service, PostgreSQL V2/V6/V8, immutable action snapshot, durable policy-generation binding, separate authenticated loopback API and Scenarios 3/8; `SimulatorApprovalBootstrapTest` operator-file restart/replay composition                                   | Reviewer/owner/tenant, hash mutation, expiry, stale policy/generation, v1->v2->v1 rollback invalidation, replay, concurrency, rollback, restart, migration V9 guard, and nanosecond precision tests              | Implemented local simulator API/browser foundation; external IdP qualification pending                                          |
| FR-008 | Direct/v2 permits, optional Ed25519 issuer/verifier-only separation, remote Java path, approval, and derived-action permit after sanitize; pre-issuance active policy generation validation in `HumanApprovalService`; local bootstrap loads matching persistent Ed25519 file keys           | HMAC/vector compatibility, Ed25519 issue/verify, algorithm-confusion, verifier-only, approval, stale policy generation permit suppression, PostgreSQL claim, one-effect, and replay tests                        | Partial: production key custody and deployment isolation pending                                                                |
| FR-009 | Trusted executor verifies and claims permit before invoking simulator behind Java development/remote paths                                                                                                                                                                                   | Permit mutation/audience/replay and remote allow/block tests                                                                                                                                                     | Implemented for simulator                                                                                                       |
| FR-010 | Durable evidence/outbox, signed receipts, bounded edge telemetry, approval lifecycle, and live POC projection                                                                                                                                                                                | Chain, receipt, approval-scenario integrity, redaction, timezone/latency, observer-isolation, OTel, and no-dispatch tests                                                                                        | Partial: real anchor/exporter pending                                                                                           |
| FR-011 | ADR 0014, signed grant schemas, Java complete-chain verifier, signed-origin Scenario 8, and Scenario 11 sub-agent presenter                                                                                                                                                                  | Signature, parent, narrowing, topology, revocation/replay tests; one narrow read and four signed amplification rejections before policy                                                                          | Implemented Java foundation; production pending                                                                                 |
| FR-012 | Stateful Java AWS/Jira simulator plus fixed customer/outbox and runbook/IAM simulators, including one in-memory security-group deletion; operator approval main has one fixed simulator mutation and default-block policy; dedicated source-free host distribution packages this composition | Java MCP, simulator state, fixed target/argument bounds, denial, zero-outbox, zero-IAM-mutation tests, and distribution archive qualification                                                                    | Implemented for current simulated adapters                                                                                      |
| FR-013 | In-memory and PostgreSQL durable version staging, isolated dry-run, atomic activation, optimistic concurrency, and previous-activation rollback; optional dynamic execution constructor; active policy generation snapshot and approval binding                                              | `PolicySnapshotIsolationTest`, `PolicyNumericRangeTest`, lifecycle tests, policy store/boundary tests (28 tests), application zero-dispatch mutation regression, and `PostgresPolicyBoundApprovalTest` (7 tests) | Partial: tested Java/PostgreSQL foundation and simulator approval binding; authenticated durable control-plane workflow pending |
| FR-014 | Java MCP/CLI/scheduled, ChatGPT tunnel profiles, hosted-client OAuth, OAuth TLS adapters, and signed-workload Scenarios 4/7/8/9/10/11                                                                                                                                                        | MCP, stdio, tunnel, OAuth, live Auth0/ChatGPT evidence, scheduled/semantic/cross-target/injection/delegation tests                                                                                               | Partial: tested hosted profile qualified; broader connectors pending                                                            |
| FR-015 | Durable unknown queue plus leased lookup-only Java reconciliation worker                                                                                                                                                                                                                     | PostgreSQL contention, deferral, restart, and append-only tests                                                                                                                                                  | Partial: production target lookup adapters pending                                                                              |
| FR-016 | Verified source projection, Java observer, and endpoint-key boundary                                                                                                                                                                                                                         | Projection, observer/stability, framing, and subprocess tests                                                                                                                                                    | Partial: managed observer/enrollment pending                                                                                    |
| FR-017 | Request- and credential-bound Ed25519 source attestation                                                                                                                                                                                                                                     | Mutation, time, key, replay, and pre-dispatch tests                                                                                                                                                              | Implemented for development CLI profile                                                                                         |

## Security requirements

| ID      | Current evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | Status                                                                                                                                                                                                                                                      |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SEC-001 | Strict MCP/scheduled/OAuth schemas and approval API; operator-owned enrollment/roles, server-normalized action; body/token role claims cannot self-authorize; startup enrollment is operator-owned, never HTTP self-enrollment; policy choice and generation derived from trusted bootstrap, never request body                                                                                                                                                                                                                | Implemented for current adapters; internal API restricted                                                                                                                                                                                                   |
| SEC-002 | Conservative defaults and source/workload/delegation/OAuth checks; `PolicyValidator` rejects unknown fields/enums, duplicate IDs and unsupported Java version/priority ranges; PostgreSQL triggers guard immutable fields, append-only history, and immutable approval policy bindings (`tp_deny_approval_policy_bindings_mutation`) against ordinary SQL; first-activation, transaction-failure, stale-generation, and unknown-schema regressions; numeric overflow, collision, and DB unavailability fail-closed regressions | Implemented for supported Java profiles; frozen wider language-neutral schema unchanged                                                                                                                                                                     |
| SEC-003 | Fingerprint-only projections, public-key-only vectors, and Java artifact scan; owner-only bounded config/secret/key files and secret-safe bootstrap tests; distribution archive dependency inventory and sanitization check                                                                                                                                                                                                                                                                                                    | Implemented baseline                                                                                                                                                                                                                                        |
| SEC-004 | Exact permit/auth/source/workload, RS256/OAuth/mTLS foundations plus local approval API access-token and per-call scope checks; explicit one-issuer/client/tenant approval configuration                                                                                                                                                                                                                                                                                                                                       | Partial: local membership replacement checked; production IdP revocation and broader connectors pending                                                                                                                                                     |
| SEC-005 | Versioned canonical binding, immutable approval action snapshots, exact API request hash, stale-policy/generation rejection, V9 schema guard, millisecond timestamps; creation-race and legacy-context-omission regressions verify coherent stored binding and claim revalidation                                                                                                                                                                                                                                              | Implemented for tested Java direct/approval/sanitize and loopback API compositions; generation binding is opt-in, not default-launcher behavior                                                                                                             |
| SEC-006 | PostgreSQL atomic approval/execution claims with active-policy share-locking; forced claim-first/activation-first schedules observe actual lock waits; standalone permit adapter verified-tenant/action-hash/fault regressions; mutation/deletion scenarios preserve one simulated effect and replay denial; precheck race rejects stale claim without permit consumption, attempt or approval-evidence delta                                                                                                                  | Implemented for tested Java durable paths; internal permit minting can precede stale rejection. Legacy context-free static claims are not globally fenced; standalone adapter is not the composed claim; production durability/target isolation unqualified |
| SEC-007 | No target SDK/credential in Java simulator; least privilege specified                                                                                                                                                                                                                                                                                                                                                                                                                                                          | Partial: real adapters remain target                                                                                                                                                                                                                        |
| SEC-008 | All supported backend calls reach fixed Java simulators, including the separate non-network synthetic outbox; no target SDK/credential                                                                                                                                                                                                                                                                                                                                                                                         | Partial: managed egress/target controls pending                                                                                                                                                                                                             |
| SEC-009 | Trusted tenant contexts plus approval API membership, requester-only execution, reviewer separation and tenant/owner-isolated snapshot reads                                                                                                                                                                                                                                                                                                                                                                                   | Partial: authenticated local API tested; durable production tenant administration pending                                                                                                                                                                   |
| SEC-010 | Durable execution/approval and allowed-remote evidence/outbox composition                                                                                                                                                                                                                                                                                                                                                                                                                                                      | Partial: edge-denial stream/external publisher/anchor pending                                                                                                                                                                                               |
| SEC-011 | Java development backend and endpoint transport stay direct numeric loopback-only, with no ambient proxy or redirect; production profile stays separate; separate approval main fixes listener and database to numeric IPv4 loopback; distribution launcher preserves loopback binding and zero-listener offline check                                                                                                                                                                                                         | Implemented POC foundation                                                                                                                                                                                                                                  |
| SEC-012 | Read/stored/OAuth/remote/sanitization evidence omits tokens, internals, raw arguments, and results; Scenario 10 omits injected tool text; `SimulatorApprovalMain.execute` code-only redacted startup errors                                                                                                                                                                                                                                                                                                                    | Partial: bounded redaction library and approval-response projection tested; global logs/export/storage coverage pending                                                                                                                                     |
| SEC-013 | Injected registry/signing port, public-only vector, restrictive dev key file                                                                                                                                                                                                                                                                                                                                                                                                                                                   | Partial: protected key provider/enrollment pending                                                                                                                                                                                                          |
| SEC-014 | Ed25519 verifier checks trust context, bindings, time, and atomic single use; endpoint transport rejects proxy/remote destination                                                                                                                                                                                                                                                                                                                                                                                              | Implemented for development CLI profile                                                                                                                                                                                                                     |
| SEC-015 | Exact JWKS URI, public-address DNS/pinning, TLS, no redirects/proxies/retries, time/size bounds; approval startup reuses lazy exact-URI SSRF-safe JWKS source                                                                                                                                                                                                                                                                                                                                                                  | Implemented Java outbound adapter; deployment egress pending                                                                                                                                                                                                |
| SEC-016 | Verified RFC 8705 thumbprint projection plus direct TLS peer-certificate equality; permissive test factory rejects non-loopback binding                                                                                                                                                                                                                                                                                                                                                                                        | Implemented hardened Java profile; live connector support pending                                                                                                                                                                                           |
| SEC-017 | Pre-auth network and post-auth principal limiter ports with process-local per-key atomic claims; test-only allow-all factory restricted to loopback                                                                                                                                                                                                                                                                                                                                                                            | Partial: shared/distributed production state pending                                                                                                                                                                                                        |
| SEC-018 | Exact receipt binding plus operator-trusted Ed25519 verification and immutable PostgreSQL anchor                                                                                                                                                                                                                                                                                                                                                                                                                               | Implemented Java/PostgreSQL foundation; production sink pending                                                                                                                                                                                             |
| SEC-019 | Worker exposes only target lookup and rechecks tenant/adapter/idempotency lease binding                                                                                                                                                                                                                                                                                                                                                                                                                                        | Implemented Java/PostgreSQL foundation; production lookup pending                                                                                                                                                                                           |
| SEC-020 | Strict telemetry envelope plus required pre-dispatch hardened-edge emission; discarded test telemetry cannot bind publicly                                                                                                                                                                                                                                                                                                                                                                                                     | Implemented Java foundation; exporter health/deployment pending                                                                                                                                                                                             |

## Reliability requirements

| ID      | Current evidence                                                                                                                                                                                                                                                                                                                                                                              | Status                                                                                                                                      |
| ------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| REL-001 | Runtime gates plus remote readiness, abuse, sender, approval, sequence, and sanitizer fail-closed tests; invalid config and DB startup failure cannot open approval listener                                                                                                                                                                                                                  | Partial: live production probes/shared sequence pending                                                                                     |
| REL-002 | Durable approval/execution transitions plus Scenario 3 lifecycle evidence                                                                                                                                                                                                                                                                                                                     | Partial: production adapter/runtime pending                                                                                                 |
| REL-003 | Tenant/adapter/idempotency unique key and duplicate no-dispatch test                                                                                                                                                                                                                                                                                                                          | Partial: real target propagation pending                                                                                                    |
| REL-004 | Existing permit/approval/workload/rate/API contention tests; standalone 32-task/four-store contention and committed-row replay; commit/acknowledgement/rollback/close/pruning failures; eight policy-generation boundary tests cover both lock schedules, creation/precheck races, binding-insert rollback, legacy compatibility and claim commit with lost acknowledgement and zero dispatch | Implemented in selected Java compositions; bounded synthetic fault coverage, not all distributed schedules or exactly-once target execution |
| REL-005 | Durable append-only unknown queue and lookup-only worker foundation (not wired into browser launcher)                                                                                                                                                                                                                                                                                         | Implemented simulator foundation                                                                                                            |
| REL-006 | In-memory and PostgreSQL policy rollback with defensive revision documents, predecessor transition resolution, 32-way current-version contention, first-activation interleaving, failure rollback, restart survival, and zero-dispatch mutation regressions; bounded OAuth claim/JWKS rotation foundation                                                                                     | Partial: durable authenticated rollout and production key inventory pending                                                                 |
| REL-007 | PostgreSQL outbox leases, stable IDs, retry schedule, and 32-way claim test                                                                                                                                                                                                                                                                                                                   | Implemented Java/PostgreSQL foundation                                                                                                      |
| REL-008 | PostgreSQL reconciliation queue, leases, retry, restart, and 32-way test                                                                                                                                                                                                                                                                                                                      | Implemented Java/PostgreSQL foundation                                                                                                      |
| REL-009 | Edge matrix plus startup/auth/telemetry/restart/sequence/sanitizer tests and fail-closed tunnel launcher                                                                                                                                                                                                                                                                                      | Implemented for current Java edges                                                                                                          |
| REL-010 | Count/diversity/window gate with zero tolerated behavior differences                                                                                                                                                                                                                                                                                                                          | Implemented library gate; production thresholds pending                                                                                     |

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

## Data and privacy requirements

| ID       | Current evidence                                                                                                                                              | Status                                                               |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| DATA-001 | Fingerprint-only identity evidence                                                                                                                            | Implemented baseline                                                 |
| DATA-002 | Retention specified, no store                                                                                                                                 | Target                                                               |
| DATA-003 | Bounded MASK/HASH/DROP projection library; original canonical action/hash unchanged; tests and [ADR 0043](../architecture/0043-bounded-evidence-redaction.md) | Partial: before-persistence integration and secret lifecycle pending |
| DATA-004 | Durable approval/execution transitions, executable lifecycle evidence, and reconciliation evidence                                                            | Implemented for Java durable profile                                 |
| DATA-005 | Deployment/data-residency question recorded                                                                                                                   | Target                                                               |

## Operability requirements

| ID      | Current evidence                                                                                                                                                                                                                                                                                                                                                                                                                                 | Status                                                                                    |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------- |
| OPS-001 | Combined Java health/readiness, bounded runtime info/resource discovery, safe logs, and remote OTel; approval bootstrap emits bounded startup codes/address, no production health claim                                                                                                                                                                                                                                                          | Partial: production logs/exporter pending                                                 |
| OPS-002 | Java policy/runtime queries, in-memory and PostgreSQL version/transition/reason records, active policy snapshot generation, remote policy/classifier and OAuth key provenance; fixed approval policy v2, configured Ed25519 key ID and V9 schema guard; distribution manifest `1.1` with clean commit/commit time as cache-tracked inputs, package version, required Java and exact dependency hashes; commit-only/dirty/missing Git regressions | Partial: durable authenticated policy administration and production key inventory pending |
| OPS-003 | Bounded denial/authorization/completion events plus OTel projection                                                                                                                                                                                                                                                                                                                                                                              | Partial: production alert routing pending                                                 |
| OPS-004 | POC lifecycle, successful INT/TERM/HUP shutdown and negative child/validation subprocess tests, web/CLI/approval/signed-workload qualification, fixed-path and key-identity-checked cleanup, numbered Scenarios 0–11; `SimulatorApprovalBootstrapTest` real-JVM offline check, startup, SIGTERM and port release; simulator approval distribution runbook and host-native offline-check/shutdown qualification                                   | Partial: one hosted OAuth profile/read/block passed; production operations remain target  |
| OPS-005 | Exact readiness aggregate plus pre-auth and pre-dispatch telemetry failures                                                                                                                                                                                                                                                                                                                                                                      | Partial: no production bootstrap/live checks                                              |

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
