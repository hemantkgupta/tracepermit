# Qualifying closed durable operation review

Scope: [ADR0085](../architecture/0085-closed-durable-operation-review.md), an
opt-in Java/JDBC library only. No listener, public body model, IdP/provider call,
signing, credential minting, permit, admission or target action is enabled.

## Operator lifecycle

Use PostgreSQL 16.4, MariaDB 11.4.13 or MariaDB 10.6.25 in a dedicated writer
database/schema. `OperationDatabase.initializeReviewEmpty` is an explicit empty
V4 install. `upgradeReviewFromV3` is an explicit stopped-writer upgrade which
validates the populated predecessor before its single additive CREATE TABLE.
Default initialization remains V2; V1-V3 SQL bytes are unchanged. Do not run an
upgrade concurrently with writers or treat partial MariaDB DDL as transactional
rollback/recoverable by an automatic retry.

Stage and explicitly activate a trusted review profile with exact group,
enrollment-source fingerprint and positive approval lifetime through five
minutes, with no default. Supply an independently configured strict GitHub
profile. Construct the new repository only after explicit V4 initialization;
runtime constructors validate and never migrate. Internal command/membership
carriers must come from trusted adapters, never public deserialization.

Runtime permissions are table-scoped SELECT on history, original operations and
facts, lifecycle heads/facts, current controls and review bindings; INSERT on
lifecycle facts and review bindings; UPDATE of head snapshot hash/JSON only;
and PostgreSQL UPDATE of the control lock-marker column for FOR UPDATE. No
binding UPDATE/DELETE, authority-pin UPDATE, DDL, TRIGGER or EVENT grant is
needed. Operator staging, activation and migration use a separate user.

## Command and replay contract

- Evaluation separately requires fresh operations.evaluate authority for the
  exact owner/workspace. The copied current operator policy decides block or
  require_approval; allow/sanitize is not synthesized into approval.
- One control-first writer transaction stores the immutable binding, closed
  evaluation fact and new head. Only require_approval has an approval deadline,
  derived from fresh database time after authority locks.
- Both human approval and denial repeat exact independent reviewer identity,
  role/scope/group/provenance and all applicable expiry checks. The captured
  policy/target/settings binding and whole expected state must still match.
- Pending withdrawal uses the same control-first owner. Before evaluation it
  creates no binding; after evaluation it retains the original binding.
  Approved, denied and cancelled operations cannot be withdrawn.
- A coherent current reader verifies original registration, the closed journal,
  binding presence/cardinality and final head. Historical identity/deadline
  checks use fact time, not today's wall clock. Registration retry still returns
  the immutable original, not a new current-authority assertion.
- Expected hashes and returned approved snapshots are metadata, not execution
  capabilities. Unknown commit/cleanup must be reconciled by explicit reads;
  this library never blindly retries a command or dispatches a target.

The separately configured strict GitHub join is pure. It validates intended
resource/precondition/profile pins, not live provider state. No network/signing
work runs under locks. Writer-time sampling and finite statement timeouts are
not a complete pool-acquisition, commit or deployment-deadline guarantee. A
decision is checked at its post-lock writer observation; returned metadata may
already be expired. Later admission must independently recheck fresh authority
and deadline, never dispatch from a returned approved label.

## Reproduction

Use Java 21 with both java/javac major 21, Node 22+, pnpm 11.25.0 and Docker.
Select an existing verified JDK process-locally when discovery selects a JRE.
From the repository root:

```sh
pnpm java:env-check
bash -c 'set -euo pipefail; source scripts/configure-java-environment.sh; exec java/gradlew -p java :persistence-operations:test --tests "*OperationReviewTest" --no-daemon --rerun-tasks --no-build-cache --max-workers=1 --console=plain'
pnpm test:java-operations --rerun-tasks --no-build-cache --max-workers=1 --console=plain
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

One Gradle task writer does not reduce the test's internal32 contenders, finite
waits or deadline margins. Shared real-JDBC cases cover block/review/withdrawal,
strict loaded target binding, exact current controls and identity/group expiry,
one-connection control/head lock order, immutable registration/bindings, genuine
waits, mixed review/withdrawal contention, partial-write rollback, uncertain
commit/cleanup, closed-reader corruption and valid-statement privilege denials.
Populated predecessor tests retain a V3 runtime with no new binding SELECT grant
and require its old writer to refuse after upgrade.

## Candidate qualification history

The initial helper compilation called a package-private target fingerprint.
That unpublished redundant field was removed in favor of the strict binding's
existing public profile fingerprint/digest; no private API was exported and no
hash recipe was copied. A later new-test compile failed because the fixture
called a nonexistent JSON helper; the fixture now uses the actual parser.

The first36-case review run failed24 cases:21 snapshot assertions compared
Jackson node-width implementations instead of canonical JSON, and3 wait cases
replaced the in-memory operation ID without re-registering it. XML showed exact
canonical equality for the21 assertions; the fixture now compares those exact
bytes. The short-lifetime setup retains the registered operation. Production
authorization was not weakened. The corrected nine selected cases passed.

The corrected42 new three-engine cases passed freshly in1m58/18 executed tasks.
The first complete355-case operation run failed one existing PostgreSQL32-task
activation case: stale state was expected but schema-not-ready was returned.
The newly added version routing retained a probe connection while schema/Flyway
validation borrowed additional connections. A bounded-source regression failed
all3 engines before repair; releasing the probe before validation made those3
plus all3 existing contention cases pass in37s. This proves the unnecessary
nested borrow, not the unretained server-side diagnostic behind the original
fixed error. No connection limit, concurrency, deadline or expectation changed.

The restored complete forced/cache-disabled operation matrix passed358 cases
across11 suites, zero failures/errors/skips,18 tasks executed in4m7. This includes
14 review cases per engine and22 control cases per engine, preserving all
existing workloads/concurrency/deadlines. Full-repository gates and exact
pushed-head CI remain pending; previous input/settings green results are not
substituted. All initial failures remain part of this record.

Distinguishing local controls omitted the new current-control and deadline
guards: all6 selected cases failed. With current-control restored, omitting
only the deadline guard made the3 real-head-wait cases fail independently.
Both guards were restored, and the complete writer file matched its pre-control
SHA-256 byte-for-byte before final qualification. No deliberate fault is retained.

A subsequent retained-instance compatibility case changed the declared installed
version after constructing the V4 writer/reader. All3 new cases initially failed:
constructor validation alone did not refuse an unknown later protocol. Explicit
per-call supported-version checks were added to the new owner/current replay
path. This is a declared-version boundary, not proof against arbitrary DBA DDL.
Filtering only successful versioned history also hid a later failed/repeatable
entry; the extended case failed all3 engines before the final guard was changed
to require the latest installed entry to be successful version4. Final results
must cover these additional checks, not the earlier358-case candidate aggregate
pass. The unchanged future-version case first passed after the initial guard;
that narrower pass is not substituted for the final complete matrix.

This qualification does not attest live RDS, Okta, GitHub/AgentCore compatibility,
full database-admin visibility, immediate membership revocation, restore or
paused-sender fencing, exactly-once external effects or production readiness.

## Final local checkpoint

The final declared-version guards passed the complete forced/cache-disabled
361-case/11-suite operation matrix, zero failures/errors/skips,18 executed tasks
in4m22. The new review fixtures then additionally pinned and asserted every owned
database publication to numeric127.0.0.1. Final `pnpm check` freshly reran all361
operation cases including those assertions and passed124 React plus1967 recorded
Java cases/155 suites, zero failures/errors and1 existing absent-archive/native-PTY
skip. Gradle19m49:5 executed/60 up-to-date; other valid outputs were reused, not
all tests fresh. Coverage94.6493% line/78.5678% branch passed unchanged94/75 gates.
During the longer run, one bounded worker stack read showed active PostgreSQL
authentication/control-test work; no hang/clock-skew cause was asserted and no
process, Docker, timeout or workload was restarted or weakened. Final formatting,
documentation and exact pushed-head hosted gates are recorded in the handoff.

## Timestamp compatibility follow-up

Cloud2R's [static review](https://github.com/hemantkgupta/tracepermit/pull/108#issuecomment-6397157388)
identified a compatibility defect at aab5c03: the clock-regression comparison
applied the new journal's strict Instant.toString encoding to an already
validated operation-v1 updatedAt. Valid .000Z and .000000000Z spellings could
therefore register and upgrade successfully but fail evaluation or pending
withdrawal with OPERATION_STORAGE_CORRUPT. This was an availability defect, not
an authority bypass; all reviewer execution was NOT RUN.

The first nine new engine cases had six fixture assertions because the selected
millisecond value already matched Instant.toString, plus three genuine storage
refusals. After making the fixture's final transition and dimension coherently
whole-second, all nine cases reproduced the actual OPERATION_STORAGE_CORRUPT
failure across PostgreSQL16.4 and both MariaDB versions. No production fix had
been applied for that corrected reproduction.

The repair parses only the already validated operation timestamp for comparison;
it preserves original strings, hashes and the frozen timestamp grammar. The
journal's own strict metadata parser remains unchanged. The same nine cases
then passed freshly in1m9/18 executed tasks, including populated V3-to-V4 upgrade,
evaluation/review, pre-evaluation withdrawal and exact immutable-registration
retention. With the additional clock-regression case, all12 selected cases passed
freshly in1m11/18 executed tasks.

The first complete follow-up matrix ran373 cases/11 suites:372 passed, one
failed, zero errors/skips,18 tasks in13m4. All57 review cases passed. The unchanged
MariaDB10.6 sixteen-contender withdrawal case's second repetition expected
OPERATION_STATE_STALE but received OPERATION_STORAGE_UNAVAILABLE. Its raw SQL
cause was not retained; XML/source/one bounded worker observation do not prove a
specific server error, clock skew or a transient environment cause. The original
11 XML receipts remain local and must not be replaced by later outputs.

The existing assertion now includes only fixed SQLState/vendor categories and
clears pre-race categories; its expected outcome,16 contenders,10 repetitions
per engine and all deadlines remain unchanged. A fresh selected run of all30
repetitions passed in52s/18 executed tasks. This is diagnostic evidence, not a
repair or explanation of the earlier failure; an empty category set would not
exclude a connection failure outside the recording proxy. The required aggregate
was a second broad attempt without a diagnosed repair of that failure; no third
automatic broad retry was permitted. Its final result follows.

The required second broad attempt, `pnpm check`, passed124 React and1979 recorded
Java cases/155 suites, zero failures/errors and one existing absent-archive
native-PTY skip. All373 operation cases/11 suites ran freshly and passed in this
aggregate; other valid outputs were reused. Gradle18m15:5 executed/60 up-to-date,
not an all-fresh repository claim. Coverage94.6493% line/78.5804% branch passed
unchanged94/75 gates. No third broad attempt was run, no failing expectation or
deadline was relaxed, and the earlier372/373 failure remains explicitly retained.

Cloud1 separately passed the unchanged30 selected contention repetitions at the
published aab5c03 baseline in48s/18 fresh tasks with no tracked edits. Its
[receipt](https://github.com/hemantkgupta/tracepermit/pull/108#issuecomment-6397891809)
neither qualifies this repair nor establishes the original cause. Read-only
cwd/Git metadata confirmed a concurrent heavy build in Gemini's separate task
checkout on this same Mac. Resource overlap is observed; it is not proof of
the failed SQL outcome's cause. No other worker or Docker was interrupted.
