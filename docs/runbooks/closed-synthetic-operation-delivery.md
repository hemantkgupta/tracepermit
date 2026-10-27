# Closed synthetic operation delivery — explicit V7

Track A implements the approved [ADR0090](../architecture/0090-closed-durable-synthetic-delivery.md)
contract after [V6 admission](closed-operation-admission.md). Qualification is
incomplete; human-authorized draft publication retains known failures and unrun
gates. No passing candidate or final merge readiness is claimed.

## Operator boundary

This is an unwired Java library and a deterministic in-memory target. No HTTP/MCP
endpoint, executable/default wiring, live provider, enrollment or credential
acquisition exists. Constructor inputs are trusted deployment configuration,
never public request fields. AgentCore registration remains execution-disabled.
Track B/ADR0091 remain separate; accepted B-first main is now included by normal
ancestry refresh, without manual B runtime/test changes.

Default installation remains V2. With all writers stopped, a separate privileged
operator may call `initializeDeliveryEmpty` on an empty dedicated store, or
`upgradeDeliveryFromV6` on an exact populated V6 store. Populated closed history,
rollback and connection cleanup precede the single V7 CREATE TABLE. Existing
V1–V6 SQL/checksums remain identical. Partial/unsupported protocols refuse;
runtime never migrates, repairs, resumes or adopts. Old deployed V6 binaries
refuse V7 and must stay stopped; new compatible readers precede new envelopes.

Runtime retains existing narrow V6 grants and adds only SELECT/INSERT on
`tp_operation_delivery_events`: no lease UPDATE, journal rewrite/DELETE, control
pin mutation, DDL, TRIGGER or EVENT. PostgreSQL retains the existing control
lock-marker column privilege. MariaDB filtered metadata cannot certify absence
of hidden executable objects; privileged stopped-writer deployment inspection
remains a separate unimplemented gate.

## Claim, start and observation

`claim` needs the actual original signed permit in memory plus fresh trusted
exact-owner `operations.execute` identity. A fingerprint or historical label is
insufficient. Internal trusted carriers are not authentication/public schemas.
One READ COMMITTED writer locks control first, head next; loads/replays original,
approved and admitted history; and rechecks exact policy/artifact/action/strict
target/precondition/permit anchors, current pins/generation and deadlines.
Claim uses an immediate control-lock contention refusal before the head lock;
losing contenders receive no authority or lease. Dispatch keeps the blocking
control-first/head-next order. Every successful claim retains full revalidation.

Lease expiry is the minimum of fresh writer time plus30 seconds, signed expiry,
original approval/evaluator/reviewer/membership deadlines, admission-owner expiry
and fresh caller expiry. Equality is expired. Fresh time and authority are
rechecked after writes/implicit waits. Expired provisional writes roll back.
Only commit AND cleanup acknowledgement return a private, nonserializable Lease.

Before dispatch, the immutable concrete synthetic target validates intended
preconditions outside locks. A second control-first/head-next transaction
repeats exact state/signature/binding/current authority/lease/fence checks,
appends dispatch_started plus execution fact/head atomically, and rechecks time
after EACH write. Only acknowledged commit AND cleanup can invoke the target;
no connection/lock spans that invocation. Uncertainty grants no capability.
The start envelope retains the actual fresh identity's minimized fingerprint and
deadline separately from the lease's claim identity; replay checks it at start time.

An expired pre-start lease may be recovered with a higher immutable fence only
while all original authority remains live. Recovery cannot renew a permit or
approval, change attempt identity or use an old fence. The closed1024-event
safety bound refuses saturation without eviction or automatic recovery.

Results distinguish succeeded, failed and unknown. Result recording is observed
history, not renewed authority. After dispatch_started, crashes, lost replies,
lease expiry and result-write failures can NEVER authorize another call or
attempt. Read-only reconcile reports durable/simulated observations without
claims, writes, expiry renewal, status correction or resend.
Durable and simulated observations are not one atomic snapshot. Control changes
after acknowledged start may prevent result recording while a paused synthetic
worker still resumes; the result is explicitly unknown and never a retry signal.

## Reader and limits

The reader enforces closed fields, cardinality, contiguous event sequence,
predecessor hashes, exact scope/attempt/admission/outbox/admitted-head anchors,
monotonic fences, historical identity/deadline/lease bounds and fact/head/actor
joins. Missing/orphan/unknown metadata refuses. Store only minimized metadata
and fingerprints, never raw permits, private credentials, arguments or responses.
Hashes are not retained signatures or protection against a coherent DBA rewrite.

A worker paused immediately before invocation is NOT atomically fenced by a DB
lease. After start, another owner cannot recover/resend even if zero calls occur
or the first worker resumes after expiry. This is deterministic synthetic-only
evidence, never an exactly-once external-effect, instantaneous revocation,
physical restore/failover, target-side fence or live-provider claim. Recreated
synthetic memory may have no prior observation; unknown never authorizes resend.

## Qualification receipts

Use only prepared Java/javac21, Node22+, offline pnpm11.25, cached Gradle8.14.3,
and exact cached PostgreSQL16.4-alpine/MariaDB11.4.13/MariaDB10.6.25. Serialize
heavy execution, max-workers1 without reducing32 contenders. No installs,
fallback downloads, Docker restart/prune or other-worker cleanup.

Production compilation passed8 executed tasks in1m14. The first positive subset
stopped before execution: a new AssertJ Instant assertion used an unavailable
method name. Correcting that name preserved the before-or-equal comparison.
The failed log is retained. Subsequent focused receipts below do not replace the
complete engine matrix, broad check, coverage, format/docs/flows or hosted gates.

The initial expanded matrix stopped in a new JDBC probe: an administrative
head-hash corruption UPDATE was incorrectly parsed as head JSON with no selected
head fault. Narrowing that test-support parser preserved all production guards;
the nine populated/partial/corrupt-upgrade cases then passed on all three engines.
The first wait group next stopped when a MariaDB10.6 head probe signalled the
earlier control lock and did not observe the intended head wait within two seconds.
The probe now signals the exact control/head/INSERT phase. Its focused three cases
passed on10.6; all nine unchanged-deadline wait cases subsequently passed across
the engines with private PID/phase/wait-row receipts. This supports the probe
repair, not a generalized flakiness or environment diagnosis.

Static review added fresh execute-identity metadata to the start envelope before
publication. Three additional coherent-hash/expired/wrong-owner replay cases passed
with the nine wait cases:12 fresh cases,18 executed tasks in5m41. These and the
earlier nine positive lifecycle/nine upgrade cases are focused receipts, not full
qualification. Initial failed XML and fail-fast skips remain private and retained.

The write/rollback/commit/cleanup/grant group passed87 fresh cases across three
suites, zero failures/errors/skips,18 executed tasks in16m15. Actual forwarded
writes, uncertain acknowledgement, least-privilege denials and administrative
positive controls were exercised. These precede the claim-contention repair.

The32-contender group exposed actual control-query timeouts (MariaDB70100/1969)
while still recording one winner/one target call. Immediate V7 control NOWAIT
refusal avoids serializing losing contenders through expensive binding reads;
only recognized lock-contention categories map to lease busy. The repaired group
passed15 fresh cases across all engines, zero failures/errors/skips,18 executed
tasks in10m38. Twelve retained actual wait observations each refused specifically
with OPERATION_PERMIT_EXPIRED; generic SQL/storage failure is not accepted.

The next lifecycle/crash group stopped after29 recorded cases:27 passed, one
failed and one fail-fast skip. MariaDB10.6 passed all20 selected cases. MariaDB11.4
failed actual child-JVM halt/restart at after_target_before_result with a generic
test-support error; PostgreSQL was not reached. The error originally suppressed
its stack. Fixed phase/category diagnostics and an own test-support stack now
retain useful failure location without private frames or driver messages.
One bounded MariaDB11.4 diagnostic then passed all five actual-JVM halt/restart
cases, zero failures/errors/skips,18 executed tasks in4m7. The original cause is
unestablished; this is non-reproduction, not a claimed repair or flakiness finding.
Each successful receipt records halt79, expected pre-halt target count, zero
restart invocations and both owned children reaped. A bounded owned-child stack
snapshot observed a MariaDB metadata query during dispatch; it does not establish
the prior failure's cause. All stopped-run logs/XML/source hashes remain retained.

Previously unreached lifecycle cases and deadline controls are still being
qualified. Full A, guard-removal controls, repository check, aggregate coverage,
format/docs/flows and exact-head CI remain outstanding.

The remaining-case run next stopped at MariaDB11.4 recovered dispatch with
OPERATION_PERMIT_EXPIRED (two recorded cases: one failure/one fail-fast skip).
Its test constructed validating owners inside the original60-second signed
lifetime as well as waiting for the30-second lease. Moving both independent
owners' setup before issuance preserves every deadline and subsequent claim,
stale-fence refusal and dispatch assertion; actual new-JVM restart remains a
separate test. One focused verification passed,18 fresh tasks in3m25, with writer
timestamps proving recovery after first-lease expiry, fence2 and one dispatch
before original-permit expiry. The stopped run and safe phase/timing receipt are
retained. This does not qualify the unreached cases or justify longer authority.

The first forced/cache-disabled COMPLETE operations-module invocation then
stopped at an unchanged existing review test:
`MariaDb106OperationReviewTest.populatedV3WithEquivalentTimestampSpellingUpgradesAndCanEvaluate`.
Its unchanged class-level90-second timeout expired; XML contains no operation-phase
stack. No cause, environment flakiness or safe repair is established. No retry,
assertion/deadline/workload change or issue124 investigation followed.

Actual XML:221 cases across7 suites,219 passed/1 failed/0 errors/1 fail-fast skip;
all18 tasks executed in27m55. The complete new MariaDB10.6 delivery suite passed
all61 cases. The remaining MariaDB11.4/PostgreSQL suites were not reached and
cannot be qualified from earlier focused subsets. A bounded earlier owned-worker
snapshot observed a different review/permit deadline case; it does not identify
the failing case's phase or cause. All source hashes matched runtime9072586.

Separately, the isolated removed-guard controls recorded21 cases:6 intended
failures/15 passes/0 errors/skips,18 executed tasks6m48. Across all three engines,
removing post-write authority/live checks incorrectly acknowledged the expired
claim; removing start rechecks actually invoked target1 after expiry instead of0.
Control/head expiry cases still refused and9 pure deadline controls passed.
Both guard sites were restored byte-identically and the owned temporary checkout
removed; the correct feature branch was never weakened.

Lightweight documentation checks passed on runtime9072586:1331 links/3 helpers,
all13 flow tests and drift synchronization. The full pnpm check, full
format:check, aggregate coverage94/75 and hosted CI/Cloudflare were NOT RUN after
the module stop. Partial execution data is retained privately, not a coverage
result. Results-only Markdown is scoped-formatted separately, not a full gate.

The observed test worker and all10 actual crash/restart receipt PIDs exited;
there were zero running Testcontainers at stop. No other-worker process action,
Docker restart/prune or repair occurred. The known exited PID1 subreaper zombie
remains disclosed. That cycle released Track A with source local/unpublished,
and no PR or merge readiness was claimed. A historical-principal field-type source
review note remains for a bounded distinguishing proof, not a claimed bypass or
a diagnosis of this timeout.

The human has now merged B/PR125 into main `221be9f599c2ee558041466ac5132a40bf540a2c`.
This was the planned B-first advance. The stopped A cycle used its original
accepted base; none of those results qualifies the newer main composition.
The later human-authorized publication refresh below records its separate scope.

The package-internal observation-only crash seam supplies no time, identity,
target, claims or decision. Actual restricted JDBC/forwarded faults exercise
transaction boundaries; these are not physical service/DB crash certification.
Original-base tests cannot qualify the accepted B-first main composition.

## Human-authorized publication with known qualification gaps

The human subsequently instructed moving ahead with the known failed/skipped
cases and providing a PR targeting main. A normal ancestry merge of exact
accepted main221be9f resolves four documentation conflicts as a union:
HANDOFF, baseline, traceability and specification README. The interface contract
auto-merges. No production/test/build conflict occurs. A runtime/test bytes remain
9072586; B runtime/tests and all paths outside A's feature exactly retain accepted
main. Manual changes are documentation-only.

This permits a guarded normal push and draft PR with disclosed qualification
gaps; it does not make the stopped gate pass or authorize check bypass,
ready/final merge, weakened tests or source repair.
The skipped review case is
`issuerRechecksFreshExecuteIdentityAfterActualHeadLockWait`, skipped after the
unchanged90-second timeout described above. The original logs/XML/partial
execution data and all earlier stopped/negative-control receipts remain retained.

Publication-time lightweight checks and exact-head hosted results belong in the
PR's full handoff. Original-base tests must not be relabelled current-main
qualification. The refreshed-composition engine/full repository/coverage gates
and historical-principal field-type review remain open. The draft is available
for review; it is not a green merge recommendation.
