# TracePermit implementation handoff

GitHub is authoritative. Credentials, private customer/provider planning and local
operator values remain outside Git. Historical receipts are not fresh results.

## Timestamp compatibility repair and next admission contract

Mac retains sole PR108 writer ownership. The preceding aab5c03 checkpoint has all
eight exact-head checks green in CI40188482042. Cloud2R's bounded static actual
V4 review is consumed: one timestamp compatibility defect, all reviewer execution
NOT RUN, [review](https://github.com/hemantkgupta/tracepermit/pull/108#issuecomment-6397157388).
The owned follow-up fixes the clock-regression comparison's operation timestamp
parser without changing stored bytes/hashes, frozen validation or new journal
metadata strictness. Three-engine positive/upgrade/withdrawal tests first
reproduced the defect, then all12 selected cases passed after repair, including
clock-regression refusal. The first complete373-case follow-up ran372pass/1fail
in an unchanged MariaDB10.6 contention repetition; no raw SQL cause is proven.
Original XML receipts remain in /private/tmp/tp108-timestamp-matrix.q6glOv.
An unchanged-workload30-repetition diagnostic passed after fixed-category-only
assertion reporting was added, not a runtime repair. Required aggregate is now
complete as the second broad attempt: PASS124 React/1979 recorded Java cases,
155 suites, zero failures/errors and one existing absent-archive native-PTY skip.
All373 operation cases ran freshly in that aggregate; other valid outputs were
reused. Gradle18m15,5 executed/60 up-to-date, coverage94.6493% line/78.5804% branch
passed unchanged94/75 gates. The first failure remains unresolved history; no
third retry was run. See the detailed
[history](docs/runbooks/durable-operation-review.md#timestamp-compatibility-follow-up).

The human approved the strict admission contract: fresh exact owner identity
with operation_requester/operations.execute and a separately versioned Ed25519
permit with explicit issuer/key/audience, no default positive lifetime, maximum
60 seconds and approval/identity deadline bounds. [ADR0087](docs/architecture/0087-fully-bound-operation-permit-and-admission.md)
records this next implementation design only. No operation permit/admission,
attempt/outbox or target is implemented/enabled by this follow-up.

The human confirms Gemini received the new cross-process review qualification
packet; Gemini uses GitHub-only handoff, not Space read/write. Its new branch is
separate and frozen at aab5c03; no automatic repin is authorized. Mac Air retains
PR120: producer reports105 fresh module cases passed, but its first aggregate
failed two existing HTTP timeouts; a human-approved second attempt is running.
Feature changes remain local/unpublished, without a final handoff/release.
Original Cloud2/PR115's unpushed files/session/log remain preserved. All PRs stay
draft/unmerged.

Cloud1 independently qualified all30 unchanged contention repetitions at the
published aab5c03 baseline in48s/18 fresh tasks, no failures/errors/skips and no
tracked edits. Its [receipt](https://github.com/hemantkgupta/tracepermit/pull/108#issuecomment-6397891809)
does not qualify this repair or explain the earlier failure. Root observed a
separate active Gradle build on the same physical Mac in Gemini's reserved
qualification checkout; only cwd/Git metadata was inspected, not its unpublished
source. This confirms shared compute, not causality. No other process or Docker
was interrupted; future heavy builds on this Mac should be serialized.

## Closed durable review — current V4 Mac checkpoint

Mac retains sole PR108 writer ownership. The opt-in
[ADR0085](docs/architecture/0085-closed-durable-operation-review.md) implementation
adds the actual one-connection/control-first evaluation, independent-human review
and pending-withdrawal transaction, immutable evaluation binding and closed
versioned journal reader. It reuses the strict pure GitHub join on loaded state;
V1-V3 migration bytes and default V2 remain unchanged. No endpoint, permit,
admission, attempt/outbox, IdP/provider call or target is enabled. This candidate
passed local qualification; exact pushed-head hosted checks remain separate.
The prior 2dee hosted green checks do not cover it. See the
[runbook](docs/runbooks/durable-operation-review.md).

After a new-fixture compile error and24/36 new-fixture assertion failures were
diagnosed and corrected without changing authorization, nine selected cases
passed, then all42 new three-engine review cases passed freshly in1m58 with18
executed tasks. The first complete355-case operation run then failed one existing
PostgreSQL32-activation test: it expected stale state but observed schema-not-ready.
The new version-routing helper unnecessarily held its probe connection while
validation/Flyway borrowed more. A deterministic bounded-source test failed on
all3 engines before repair; closing the probe before validation made those3 plus
the existing3 contention cases pass in37s. That proves the nested-borrow defect;
the original fixed-code failure did not retain a raw PostgreSQL diagnostic, so
its exact server-side cause is not claimed proven. At that intermediate
checkpoint the final complete operation, aggregate, formatting/docs and
pushed-head gates were still pending; the local results below supersede that
intermediate status.

Later declared-version cases exposed retained V4 instances accepting an unknown
installed version and success-only history filtering hiding a failed/repeatable
later entry. Both new three-engine regressions first failed3/3; the owner and
current reader now require the latest installed entry to be successful version4.
The complete forced/cache-disabled matrix then passed361 cases/11 suites,
zero failures/errors/skips,18 tasks in4m22. New review fixtures additionally pin
and assert their owned database publications to numeric127.0.0.1. The final
`pnpm check` including that test-only loopback assertion passed124 React and1967
recorded Java cases/155 suites, zero failures/errors and1 existing absent-archive
native-PTY skip. The361 operation cases ran freshly in this final aggregate;
other valid outputs were reused. Gradle19m49:5 executed/60 up-to-date, not all
tests fresh. Coverage94.6493% line/78.5678% branch passed unchanged94/75 gates.
An earlier358-case candidate aggregate is not substituted for the final source.
Formatting,1161 links/3 helpers and13 flow/drift cases passed before results-only
updates and are checked again for publication. One bounded worker stack sample
showed active PostgreSQL authentication/control-case work during the longer
final run, not a proved hang or reason to restart Docker/change test limits.

Cloud1's PR119 atddcfbec2 had all8 hosted checks green and a clean released
three-path tooling handoff. Root read both scripts, the runbook and handoff, then
native macOS/Bash3.2 failed shell case58. A minimal conditional-source reproduction
exits early under errexit rather than returning to the caller's else branch.
Cloud1 reacquired only its tooling branch and published3e92aa6104914706d749f1cd07b6105a8c05ab5d.
Root read the complete follow-up/handoff, verified all8 exact-head green checks,
then independently passed all152 unchanged native macOS/Bash3.2 scenarios and
actual selected-JDK/cache/precedence checks. Root's focused
[intake](https://github.com/hemantkgupta/tracepermit/pull/119#issuecomment-6396596462)
does not claim a coordinator full-repository or automatic macOS-discovery run.
The branch is clean/released by Cloud1; root acquired no writer ownership.
The stopped physical-schema audit remains stopped. The clean detached Mac
review tree is separate from this authority checkout.

Mac Air's fixed handoff Page now reports IN_PROGRESS and draft PR120; GitHub
confirms integration-only head eb0fdb30a163e4aed36adf54f207b0c939e891ab stacked on
PR118. Its new registration module is not qualified yet, and that lane retains
its independent writer reservation. Original Cloud2/PR115's unpushed repair
files/session/log stay preserved; Cloud2R is idle until a useful new checkpoint.
All PRs remain draft/unmerged.

## Explicit review input/settings — current Mac checkpoint

Mac retains sole PR108 writer ownership. This slice implements the approved
[ADR0084](docs/architecture/0084-durable-review-membership-and-lifetime.md)
input/settings prerequisites: explicit exact group/enrollment provenance and
positive approval lifetime through five minutes, all pinned by a new v2 profile
recipe; exact fresh group checks for both review decisions; and separate fresh
evaluation permission for the exact owner/workspace. Legacy v1 hashes, six-field
review actors, migration bytes, grants, runtime wiring and simulator behavior
remain unchanged. The new gates refuse missing explicit settings rather than
invent a default. Internal carriers are trusted-adapter data, not verified tokens.

Focused application qualification first used an incorrectly selected JDK through
a nested login shell:26 cases passed but the unchanged build gate failed, so the
command is not a qualifying pass. Explicit selection of the installed JDK21 and
strict setup passed26 fresh cases/11 executed tasks. The first new three-engine
test expected the wrong error for mismatching pins and failed all3 cases; only
the new expectations changed to the existing `OPERATION_PROFILE_MISMATCH`.
Matching inactive pins still require `OPERATION_AUTHORITY_NOT_READY`; all3
corrected engine cases passed freshly in22s/14 executed tasks.

Temporarily omitting the v2 settings pins and exact membership-source check made
both selected distinguishing cases fail. Both production files were restored
byte-identically before final verification. The restored26 selected application
cases passed freshly in7s/11 executed tasks. The complete fresh/cache-disabled
operation suite passed313 cases/8 suites, zero failures/errors/skips,14 tasks
executed in2m29. Full `pnpm check` passed124 React and1919 recorded Java cases/
152 suites, zero failures/errors and1 existing absent-archive/native-PTY skip.
Gradle3m7:10executed/55up-to-date, reusing the fresh operation suite and other
unchanged results; not all tests fresh. Coverage94.5760% line/78.9952% branch
passed unchanged94/75 thresholds. Format,1149 links/3 helpers,13 flows/drift and
diff checks passed. Exact-final-head hosted checks remain pending; the previous
d85459f hosted checks do not cover these new files.

The actual immutable pending-binding/deadline writer, closed decision-journal
vocabulary/reader, strict loaded target join, and single durable evaluation/review
transaction remain unimplemented. Separate fully bound permit, atomic admission,
attempt/evidence/outbox and synthetic dispatch remain later gates. No endpoint,
network/provider enrollment verification, credential minting or target call is
enabled. See the [qualification runbook](docs/runbooks/operation-review-settings.md).

The human has launched the independent Mac Air PR113 repair qualification on its
own reserved branch; this Mac has no verified Air thread/host or writer takeover.
Cloud2R's tests/docs-only [PR117](https://github.com/hemantkgupta/tracepermit/pull/117)
is clean/released at8c020c6aaa134e7f2d0a9d9a94b448f7d73e1714 and all8 exact-head
hosted checks passed. Root read all five new files and published a scoped static
[intake](https://github.com/hemantkgupta/tracepermit/pull/117#issuecomment-6395555849),
not independent Mac execution. Producer's12 focused metadata cases passed; its
local full check failed3 existing endpoint cleanup cases and retained1 existing
PTY skip. Those failures are not waived by hosted green checks. Original Cloud2
PR115's four unpushed repair files/session/log remain preserved and reserved.
Cloud1's interrupted PR108 audit remains incomplete: its already-completed310-case
module pass does not turn the stopped review into a no-findings certification.
The stopped audit is not being retried or reassigned.

## Physical-schema repair — preceding pushed checkpoint

Mac retains sole PR108 writer ownership. The physical-readiness follow-up to
Cloud2's static review now checks actual required structure in addition to
migration history. It does not change migration bytes/grants or implement durable
review/admission. See [ADR0083](docs/architecture/0083-writer-locked-operation-authority-control.md)
and the [qualification history](docs/runbooks/operation-authority-control.md).

The original reproduction had8 accepted-drift assertion failures plus2 invalid
MariaDB inline-CHECK-removal fixture errors, corrected without skipping tests.
All60 focused control cases passed. Removing the new physical guard and restoring
the old PostgreSQL trigger query made13/13 selected cases fail; guards restored.
The first full305-case run then failed6 restricted-runtime MariaDB cases.
Owned probes proved the table-scoped runtime sees zero CHECK rows while the
owner sees7 on both exact MariaDB versions; SHOW CREATE works under the same
grants. Temporary diagnostic printing was removed. A bounded closed declaration
validator preserves exact CHECK requirements and grants; two table-scoped
positive/removal/restoration cases and three lexical tests qualify that path.
The final forced/cache-disabled310-case operation suite passed across8 suites,
zero failures/errors/skips,14 tasks executed in3m50. Full `pnpm check` then
passed124 React and1907 recorded Java cases/151 suites, zero failures/errors,
one existing absent-archive/native-PTY skip. Gradle3m41:4executed/61up-to-date,
not all fresh. Coverage94.5532% line/78.7399% branch passed unchanged thresholds.
Format,1144 links/3 helpers,13 flow/drift and diff checks passed; final
results-only documentation and exact-final-head hosted gates remain separate.

Limited MariaDB metadata cannot establish complete absence of hidden routines,
events or triggers. Operator-controlled full-visibility pre-start inspection is
a future deployment prerequisite, not an implemented attestation/DBA-proof fence.
No runtime admin/DDL/TRIGGER/EVENT grant, live action or new authority is added.

The human sent Gemini the PR113 repair packet; a new repaired head remains to
be independently consumed. Cloud1's PR116 is a released tests/docs handoff at
5a09deafd7c8bc3b8199e2add70542852f681213 with all8 exact-head checks green.
TP-Cloud-2R has a distinct new tests/docs metadata-visibility assignment from
the prior pushed d6b7b9b baseline, not ownership of this implementation or of
old PR115. The old unavailable Cloud2's uncommitted files/log/session stay
preserved. Replacement runtime readiness is separate from dispatch delivery.

## Approved next authority slice and independent replay repair

The human approved the strict group/lifetime/evaluation contract in
[ADR0084](docs/architecture/0084-durable-review-membership-and-lifetime.md).
This is an approved design, not a newly implemented durable reviewer transaction.
Mac retains sole PR108 writer ownership. Cloud2's
[design critique](https://github.com/hemantkgupta/tracepermit/pull/108#issuecomment-6394413528)
is consumed; every reviewer execution gate was NOT RUN. Closed journal-reader
compatibility and the strict target-join reuse remain implementation prerequisites.

Current additional test-only qualification passed42 focused cases and a complete
fresh/cache-disabled287-case operation suite in2m21,14 tasks executed, zero
failures/errors/skips. It isolates stored generation, races32 operator activations
across4 datasources on each engine, and upgrades a real withdrawal journal while
rejecting corrupt actor linkage before DDL. Removing generation/staged-state
guards failed all6 selected cases, then both production files were restored
byte-identically. This does not qualify complete reviewer/admission contention.
The subsequent full `pnpm check` passed124 React and1884 recorded Java cases/
150 suites, zero failures/errors and the one existing absent-archive/native-PTY
skip. Gradle2m54:4executed/61up-to-date, reusing the freshly forced operation
suite and unchanged outputs; not all fresh. Coverage94.50% line/78.64% branch,
unchanged thresholds. Final docs/format gates are reported with the checkpoint.

Cloud1's [PR113 independent audit](https://github.com/hemantkgupta/tracepermit/pull/113#issuecomment-6394443819)
reported four substantiated repair areas despite61 passing producer cases and
green CI: suppressed private cleanup diagnostics, unbounded DML/post-wait expiry,
conflicting local capacities and accepted physical-schema drift. PostgreSQL
post-wait/missing-column observations must not be attributed to MariaDB; the
auditor's MariaDB wait-view probe and diagnostic retry were inconclusive.
Gemini keeps exclusive producer ownership. The human-coordinated
[repair packet](docs/planning/packets/wave4-gemini-durable-replay-repair.md)
requires distinguishing controls and exact-head qualification, not certification
or integration of the unrepaired head.

Cloud1's next separate tests/docs branch qualifies real synthetic HTTP credential
exchange→target-preflight composition, using existing restricted test seams, not
live providers. Cloud2's next GitHub-only static review compares actual physical
schema checks with claimed V3/V2 readiness, while its unavailable PR115 runtime
and four uncommitted repair files/reservation remain untouched. The human will
inspect that existing runtime; this is not permission to recreate it.

## Current Mac authority-control prerequisite

Mac retains sole writer ownership of `codex/durable-operation-authority`/PR108.
[ADR0083](docs/architecture/0083-writer-locked-operation-authority-control.md)
adds opt-in V3 current policy/profile/recovery control, not durable reviewer
approval or admission. Default initialization remains V2; V1/V2 bytes are
unchanged. Explicit populated upgrade validates immutable/current journals before
DDL. Runtime startup validates exact V2/V3 without auto-migrate or repair.

New profiles/replacements start inactive. Quarantined generations cannot be
reactivated; replacements need a greater generation. A restricted runtime can
lock/recheck current control on the caller's READ COMMITTED writer transaction,
but cannot mutate authority columns. Only a separately privileged operator
datasource can stage/activate/quarantine/replace. There is no endpoint, permit,
attempt/outbox, target call or complete authority transaction. The next owner
must retain this lock, acquire remaining authority locks, sample fresh writer
time and atomically recheck/commit the whole closed approval/admission boundary.

All33 initial control cases passed,11 per exact engine. Added populated/corrupt
upgrade checks also passed all33. Removing profile/readiness guards made all6
selected cases fail; guards were restored, then the full forced/cache-disabled
operation suite passed278 cases,14tasks executed in1m50. An intermediate full
`pnpm check` passed after that run. Subsequent review found unchecked datasource
diagnostics escaping the new schema-version detection. Three new engine cases
failed before the fixed-code catch repair. The final forced/cache-disabled
operation suite then passed281 cases/7 suites, zero failures/errors/skips,
14tasks executed in1m50. Additional activation-acknowledgement assertions also
passed the complete281-case forced suite in1m52: an activation can commit while
the caller receives unknown, never proof of rollback. The final aggregate gate
passed afterward:124 React cases and1878 recorded Java cases/150 suites,
zero failures/errors and one existing archive-dependent native-PTY skip.
The final incremental Gradle gate took4s (3 tasks executed/62 up-to-date), reusing
the freshly forced operation result and previously qualified unchanged suites;
these are not all fresh executions. Coverage94.50% line/78.63% branch passed
unchanged thresholds. Formatting,1132 links/3 helpers,13 flows/drift and diff
checks passed; final results-only documentation is checked separately. Hosted
checks must match the final pushed head, not the earlier green `c3f2b2d`.

Cloud2's [static control audit](https://github.com/hemantkgupta/tracepermit/pull/108#issuecomment-6394291995)
found an INSERT-denial test gap: duplicate keys could satisfy both old negative
assertions even with an incorrect INSERT grant. The shared case now inserts a
valid unused scope under the administrator in a rolled-back positive control,
requires the engine's actual permission SQLState/vendor code for the runtime,
uses the unused scope through the administration API, and verifies no new row.
All3 selected engine cases passed fresh; temporarily granting only INSERT in
the disposable fixtures made all3 fail because the insert succeeded. Grants
were restored before complete qualification: the fresh forced281-case suite
passed in1m57 (14 executed), then full `pnpm check` passed124 React and1878
recorded Java cases with one existing archive-dependent skip, no failures or
errors. Gradle2m46:4executed/61up-to-date, not all fresh tests. This is test-only
hardening, not a demonstrated runtime privilege bypass. The static reviewer ran
no tests. All8 hosted checks passed at331911c; a new checkpoint requires new CI.

Cloud1's [PR114](https://github.com/hemantkgupta/tracepermit/pull/114) handoff at
`b1a88473573efbd9dd942ef265c3a757985d93ad` was consumed with its green exact-head
checks/released writer. Independent Mac default-temp qualification failed3/56
configuration cases; the identical source passed56/56 with a canonical-temp
diagnostic control. [The review receipt](https://github.com/hemantkgupta/tracepermit/pull/114#issuecomment-6394125895)
records this limit. Cloud1's tests/docs-only repair at
`76aa4fe19b290fda7d0813aa9f65dc85d96506ac` was subsequently consumed with all8
exact-head checks green and writer release. Independent default-temp Mac checks
passed58 configuration,3 archive inventory/hash and8 installed-process cases;
[the receipt](https://github.com/hemantkgupta/tracepermit/pull/114#issuecomment-6394270217)
preserves the historical failures and diagnostic-only override. Production
link/private-mode gates stay intact. The clean detached Mac review worktree
never acquired producer writer ownership. Cloud1 now independently audits
Gemini's replay implementation without acquiring its branch.

Cloud2's [PR115](https://github.com/hemantkgupta/tracepermit/pull/115) is still
producer-owned. Its new HTTP runtime was read at
`928d5ca796888178f661ab3fcf229fb9ccbbf8cc`; a provisional accepted-socket setup
cleanup finding was sent for investigation/regression before release. Its four
repair files remain local, uncommitted and unpushed in the unavailable prepared
Cloud2 runtime; the second broad run's final outcome is unknown. The published
head still contains the setup leak. Preserve the files/logs and reservation;
no takeover, rebuild or automatic third broad run. Cloud2 can perform bounded
GitHub-only static reviews meanwhile. No duplicate assignment or PR merge is
authorized. Gemini's persistence-identity remains
exclusive. The standing coordinator records exact heads and gates, then assigns
subsequent non-overlapping tasks only after review/release.

## Wave 2 Mac ownership checkpoint

The operator authorized the eight-hour durable authority/admission lane and two
separate cloud lanes, with every PR left unmerged. Mac has sole writer ownership
of `codex/durable-operation-authority` from main
`0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f` in the isolated
`tracepermit-durable-authority` checkout. The [wave-two board](docs/planning/wave2-durable-authority-batch.md)
records exclusive paths, design prerequisites and bounded completion gates.
The initial documentation checkpoint claimed no runtime change or new test pass.
The generic cloud launches stopped at environment selection before editing;
their creation is not evidence that either approved cloud lane is running.

The first implementation checkpoint adds unwired application contracts only:
closed independent-human review inputs and a copied/pinned static review policy
profile; see [ADR0077](docs/architecture/0077-closed-operation-authority-contracts.md).
No V2 database schema, withdrawal behavior, permit, admission, API or target wiring
changes. Durable transaction implementation and multi-engine qualification remain
pending. Mac retains exclusive writer ownership of this draft branch; it is not
released for another environment to edit.

### Current repair and standing coordination

The operator extended Cloud1/Cloud2 coordination until explicitly stopped. The
Mac keeps this branch; every PR remains draft/unmerged and each new cloud slice
has a separate branch. GitHub packets describe the
[operator diagnostics lane](docs/planning/packets/wave3-cloud1-github-operator-diagnostics.md)
and [loopback registration lane](docs/planning/packets/wave3-cloud2-loopback-registration-http.md).
Gemini retains the separate [durable replay lane](docs/planning/packets/wave3-gemini-durable-workload-replay.md).
The local recurring coordinator is not a guarantee of uninterrupted execution;
it stops/escalates for missing authority, unsafe prerequisites or repeated
no-progress failures rather than manufacturing work or retrying to green.

Both preceding cloud reviews were consumed. Cloud1 independently passed174 real
HTTP-ingress/JDBC-composition test cases across the three required engines on
[PR111](https://github.com/hemantkgupta/tracepermit/pull/111), with no substantiated
finding and no whole-product certification. Cloud2's
[PR108 review](https://github.com/hemantkgupta/tracepermit/pull/108#issuecomment-6393630502)
found one P2 malformed-Unicode policy/snapshot alias. The local regression first
failed three of eleven profile cases. Strict UTF-8 encoding before hashing then
passed17 profile/review cases with caches disabled, without altering frozen
canonical-v1 behavior or valid-input hashes. The subsequent full `pnpm check`
passed:124 React cases and1842 recorded Java cases across147 suites, zero
failures/errors and one existing installed-archive/native-PTY skip. Gradle6m50:
23 tasks executed and42 up-to-date, so these are not all fresh executions.
Coverage94.48% line/78.56% branch, with unchanged thresholds. Exact-final-head
hosted checks remain separate. Final documentation passed formatting,1123 links/
three link-helper cases,13 flow/drift cases and `git diff --check`.

The [database fixture repair](docs/runbooks/operation-lock-wait-expiry-qualification.md)
is pushed at `6b86be959e9b649a995557929dbd510e4cce06a0`. It measures fresh writer
time during the genuine lock wait; it does not widen expiry, concurrency or wait
bounds and leaves production lifecycle code unchanged. All three engine cases
failed when production time sampling was deliberately moved before the lock;
after restoration the complete forced/cache-disabled operation suite passed245
cases. The original broad failure below is retained. A fresh container clock and
bounded writer-time observation did not establish current clock skew; the old
Docker-info observation is not a reason to restart Docker. The original
transient cause remains unproven. The later aggregate pass is a separate result,
not erasure or relabeling of that first failed run.

First contract checkpoint evidence:13 focused application tests passed. Removing
only the independent-human and resource-binding guards made both selected
negative-control cases fail; both guards were restored, and the application
tests passed in the subsequent broad run. Formatting,1121 documentation links,
three link helpers and13 flow/drift cases passed. The first `pnpm check` is a
**failure**, not qualification:124 React cases passed, but the unchanged real
PostgreSQL head-lock expiry case unexpectedly succeeded (244/245 operation cases
passed). Gradle stopped after30m44; later aggregate/coverage gates did not finish.
No test or timing assertion was weakened and no retry-to-green was performed.
An independent bounded Docker health observation later showed its server clock
approximately9852seconds behind the Mac clock. This is an environmental lead,
not yet an independently verified database-clock diagnosis or runtime fix.
No Docker restart, clock change or unrelated cleanup is authorized/performed.
The broad failure must be diagnosed/resolved before this checkpoint is qualified.
A subsequent owned, network-disabled container clock probe agreed with the Mac
within its one-second measurement resolution. The earlier Docker-info timestamp
therefore does not establish a current VM/database clock skew or justify a
restart. Fresh database-time observations and the original failure still need
investigation; do not widen expiry margins or rerun solely to get green.

## Current PR104 reconciliation

The user requested a normal merge of current main
`6b7de90bf9f07b0307ff61a6038fac3383404d7f` (merged PR101) into clean published
PR104 `1650c4b41d36d7f5fc8f919888ee8f8e397a2618`. Mac has explicitly reclaimed
exclusive writer ownership for this repair and releases it after the final
normal commit/push. The single text conflict was two
independent sections at the top of the traceability matrix; both are preserved.
No production or test behavior is manually changed; byte comparisons preserve
the feature's bridge/vector/inventory tests and main's lifecycle/database/CI code.

Fresh merged-tree qualification passed: 102 binding cases; 245 forced/cache-disabled
operation cases (78 each on PostgreSQL16.4 and MariaDB11.4.13/10.6.25, plus11 strict
reader cases), zero failures/errors/skips. Full `pnpm check` passed:124 React tests,
1825 recorded Java cases/145 suites, zero failures/errors and one existing
archive-absent native-PTY skip. Gradle5m40s:9 executed/4 from cache/52 up-to-date;
these are not all fresh executions. Coverage94.43% line/78.29% branch, unchanged
thresholds. Formatting,1108 links/3 helper tests,13 flows/drift and diff checks
passed; final results-only documentation is checked separately.

The existing non-draft PR state is preserved and the PR remains unmerged. New
exact-final-head hosted checks and operator review remain separate gates. Earlier
receipts below are historical, not merged-tree results or current ownership.
The final GitHub handoff records the exact pushed SHA and writer release; another
environment must verify that SHA/clean tree and explicitly claim ownership.

## Prior PR104 implementation checkpoint — historical receipt

TRACEPERMIT HANDOFF

- Task: Implement a pure business-operation-to-GitHub target binding bridge and distribute isolated wave-one packets; leave all PRs unmerged.
- Repository: https://github.com/hemantkgupta/tracepermit; isolated Mac `/Users/hemantkgupta/code-all/tracepermit-operation-binding`.
- Branch: `codex/github-operation-binding`.
- Base commit: `f23dc22f056058d7977715e0878c284728d10d17` (merged PR103).
- Latest pushed commit: packet checkpoint `f239d89a49d0f5b150684175c9a2128a42445ed1`; the implementation checkpoint follows. Verify exact final SHA in PR104 and its final GitHub handoff before another writer resumes.
- PR URL: https://github.com/hemantkgupta/tracepermit/pull/104 — draft/open/unmerged.
- Branch owner now: released to user/review owner after the final normal push; this Mac thread stops editing the branch. Separate PR101 is already pushed/released at `ceb3ae565f6e1cab531ceab9d06a141f49ecf4d4` with all exact-head checks green.
- Working tree status: clean and synced after the final implementation push; exact SHA is in the final GitHub handoff. No other checkout reset, discarded or cleaned; no rebase/force-push/PR merge.
- Completed: pure scope/profile/target/precondition join, defensive data result, fixed no-cause/no-suppression rejection, 256KiB UTF-8 ceiling; independent language-neutral golden recipe; strict non-production exact dispatch profile. Generic canonical schema/vector and existing adapter/permit/repository behavior unchanged. ADR0075/runbook/learning/specifications/traceability and isolated Gemini/credential/cloud-setup/read-only-audit packets added. Shared contract inventory explicitly adds the single new vector without weakening its exact assertion.
- Remaining: exact-final-head hosted checks and independent/operator integration review; leave PR104 draft/unmerged. No execution/API/AgentCore/MariaDB authority wiring belongs to this bridge.
- Files materially changed: new GitHubBusinessOperationBinding and its tests; new bridge vector; one conformance inventory entry; ADR0075/runbook/learning/specifications/traceability/HANDOFF and public coordination packets/board. No existing production adapter/transport/permit/lifecycle/database/CI behavior changed.
- Tests passed: 102 focused binding cases and complete 402-case/12-suite remote adapter tests, zero failures/errors/skips. Golden hashes independently calculated/checked; removing scope/generation/resource guards made 11 of43 selected cases fail, restored guards pass. Focused inventory and repaired pnpm check passed:124 React tests,1686 recorded Java cases/142 suites, zero failures/errors, one existing archive-absent native-PTY skip. Repaired Gradle run3m7s:26 executed/10 from cache/29 up-to-date; final13s incremental gate after tightening golden-input verification:4 executed/61 up-to-date. These are not all fresh test executions. Coverage94.33% line/78.16% branch, unchanged thresholds. Frozen install, formatting, documentation links,13 flows/drift and diff checks passed; final result-only document edits checked separately.
- Tests failed/not run: real parsed policy-version positive failed before exact IntNode/LongNode comparison repair (97/98, then98/98). Initial broad check failed because the new vector lacked its exact inventory entry; explicitly adding that single filename preserved the strict assertion and repaired gates. Eleven deliberate guard-removal failures are expected controls, not current failures. Hosted exact-final-head checks are pending at this documentation checkpoint. Native-PTY skip is archive-absent, not merely noninteractive. No live provider/target or deployment qualification.
- Exact next command: `gh pr checks 104 --repo hemantkgupta/tracepermit`.
- Required environment: Node22+, pnpm11.25.0, Java21 via repository setup, Docker for broad database fixtures. One local Gradle worker; no reduction of test-internal concurrency.
- Local-only/manual steps: ignored logs/build artifacts under `/tmp/tracepermit-pr104-*` and local caches. Cloud sessions must verify their own published environment; Mac files/sign-ins/Docker do not transfer. Reuse a suitable existing TracePermit environment; shared publication/settings changes require owner review and a new-task check.
- Security constraints: synthetic data only; no private customer planning access, credentials, live provider/target/AWS, public listener/tunnel, paid API fallback, automatic retry, gate weakening or PR merge. Public/native identity clients stay secretless. Full operation snapshots are sensitive data, not redacted exports.
- Known limitations: bound data is not authentication, database-origin proof, current policy/approval, a permit, claim, fencing token or execution authority. Generic and process-local target keys remain distinct; no exactly-once or authoritative absence claim. Trusted ingress, atomic executor/MariaDB authority, durable reconciliation, credentials, S3/EKS/RDS/live sandbox and older-backup protection remain separate gates.
- Safe for another environment to continue: yes, after fetching/verifying the final pushed SHA and clean tree, reading the handoff and explicitly claiming exclusive ownership. No automatic merge or live action is authorized.

## Historical PR101 reconciliation receipt — now merged into main

The following lifecycle receipt is retained for provenance, not current branch
ownership or fresh binding qualification. PR101 was subsequently merged; its
[final public handoff](https://github.com/hemantkgupta/tracepermit/pull/101#issuecomment-6385799517)
and [final hosted result](https://github.com/hemantkgupta/tracepermit/pull/101#issuecomment-6385869759)
contain the exact qualification. PR103 scheduling evidence remains separately
linked in the preserved merged-main evidence below.

TRACEPERMIT HANDOFF

- Task: Reconcile PR101 durable pending-operation withdrawal with merged main, preserving its internal-only authority limits and all incoming qualification gates. Leave the PR unmerged.
- Repository: https://github.com/hemantkgupta/tracepermit; Mac checkout `/Users/hemantkgupta/code-all/tracepermit-operation-lifecycle`.
- Branch: codex/durable-operation-lifecycle.
- Base commit: pinned main `f23dc22f056058d7977715e0878c284728d10d17` (merged PR94/100/102/103); original PR101 work started at `2883eb6dc9c40f88c231ddc6cec7df5f016b2add`.
- Latest pushed commit: normal merge checkpoint `3a0a380153176c20d42461276fd483bcb3b7a01a`, followed by a documentation-only results checkpoint. Resolve the exact final pushed head from PR101 and its GitHub handoff before transfer; the previous published head was `d35e6c285440428513c8d4864b661e71d66633cd`.
- PR URL: https://github.com/hemantkgupta/tracepermit/pull/101 — draft, open, unmerged.
- Branch owner now: released to user/review owner after the final normal push; the Mac coordinator stops editing this branch. The separate binding bridge remains Mac-owned on its own branch.
- Working tree status: clean and synced at the final push. A normal merge preserves both parents; only three documentation conflicts needed manual resolution. No reset, discard, rebase, force-push or other-checkout edits.
- Completed:
  - Prior PR101 checkpoint: pending-only withdrawal; locked expected-state and trusted owner/role/scope/deadline checks using database time; coherent current reads; no generic desired-state/proposal write.
  - Separate current heads/hash-linked actor journal; atomic head/fact commit and explicit commit uncertainty; original snapshot/three facts and original-return retries preserved.
  - Explicit additive V1→V2 upgrade, validate-only runtime and column-scoped head update grants. Database-review guidance informed short single-row transactions and least privilege.
  - Fixed exceptions cannot retain JDBC causes or suppressed close diagnostics. Package-local strict stored-JSON decoding rejects duplicate/nested keys, trailing data, malformed/null/empty/oversized values before hash/semantic checks or populated upgrade DDL. Global JSON/canonical behavior, valid-value hashes, V1 migration bytes, locks and grants are unchanged by that repair.
  - ADR0072, runbooks, learning/specifications/traceability and public parallel packets describe the internal library and its remaining integration boundary.
  - Incoming main contains merged GitHub preflight/typed-confirmation repairs, standalone MariaDB permit consumption, bounded listener dispatch and parallel fail-closed CI. Their original evidence is linked below; this reconciliation does not requalify them by inheritance.
- Remaining: exact-final-head hosted checks and independent/operator review; leave PR101 draft and unmerged. Operation-to-GitHub binding and executor composition remain separate. Local passing checks do not qualify RDS, failover, live identity, targets or production deployment.
- Files materially changed: prior PR101 application lifecycle command/port/exception/tests; JDBC registration/database/lifecycle journal and strict reader; additive PostgreSQL/MariaDB V2 migrations/shared tests; ADR0072, runbooks, learning, specs and packets. Manual reconciliation is limited to HANDOFF, the specification index and traceability; incoming main code is preserved.
- Tests passed: fresh forced `pnpm test:java-operations --rerun --no-build-cache --max-workers=1`: 245 cases, comprising 78 each on PostgreSQL16.4/MariaDB11.4.13/MariaDB10.6.25 plus 11 strict-reader cases, zero failures/errors/skips. Then one Java21/one-worker `pnpm check` passed: 124 React tests and 1,723 Java cases recorded across 144 suites, zero failures/errors, one existing installed-archive/native-PTY skip. Gradle completed in 10m44s: 49 tasks executed, 2 from cache, 14 up-to-date; the freshly forced operation result was reused. Aggregate coverage 94.37% line/78.16% branch, unchanged thresholds. Frozen install, formatting, 1,081 documentation links/3 tests, 13 flow tests/drift and diff checks passed. Final results-only document edits are checked separately and do not change runtime bytes.
- Tests failed/not run: no failure in this reconciliation's focused or broad run. Exact-final-head hosted checks remain pending at the documentation checkpoint. Historical negative controls and failures remain evidence, not current passes: two removed-deadline failures; three pre-repair close-diagnostic failures; ten ambiguous-JSON failures; the initial MissingNode rejection repair; an unsuitable-JDK probe; older host-load contention and pre-PR102 latency failures. The existing native-PTY test skipped because its installed archive was absent, not merely because this was noninteractive. No live IdP/GitHub/RDS/AgentCore/S3/EKS or production qualification.
- Exact next command: gh pr checks 101 --repo hemantkgupta/tracepermit
- Required environment: Java21 through scripts/configure-java-environment.sh (set TRACEPERMIT_JAVA_HOME privately if discovery fails); Node.js22+, pnpm11.25.0; Docker. Bound Gradle workers on a shared Mac without weakening internal concurrency, deadlines or assertions.
- Local-only/manual steps: ignored build/test reports, caches and CLI helpers stay local. Obtain fresh frozen-head independent review after reconciliation. Stop all old/new writers and verify backup before any separately approved real-store upgrade; MariaDB partial DDL requires operator recovery.
- Security constraints: no public route, target/permit/approval/publication call, private customer file access, sensitive output, billed API fallback, shared writer or automatic merge. No missing/skipped gate, lower coverage, reduced workload, artifact-trust shortcut or branch-protection change. A future trusted adapter must verify enrollment and derive workspace scope; constructor flags/state tokens are not identity proofs.
- Known limitations: closed initial-evaluating→cancelled composition only; no generic trusted lifecycle service, instant revocation, restored-backup fence, signed journal/DBA attestation, encryption/redaction, retention, live deployment or production readiness. Durable operation-to-GitHub authority integration remains separate. CI scheduling is not a guaranteed speedup and may increase runner minutes.
- Safe for another environment to continue: yes, after fetching/verifying the final GitHub head, a clean tree, this handoff and the explicit writer release. No concurrent writer or automatic PR merge is authorized.

## Preserved merged-main evidence

- [PR94 final handoff](https://github.com/hemantkgupta/tracepermit/pull/94#issuecomment-6384587169)
  records the standalone MariaDB matrix and full-check evidence. It does not
  qualify approval/evidence parity, live RDS, crash recovery or runtime wiring.
- [PR100 final handoff](https://github.com/hemantkgupta/tracepermit/pull/100#issuecomment-6384195456)
  records GitHub preflight, strict typed confirmations, synthetic target checks
  and their limits. Its provider port and process-local state are not a composed
  durable executor or credential-minting implementation.
- [Merged PR102](https://github.com/hemantkgupta/tracepermit/pull/102),
  [ADR0074](docs/architecture/0074-reserved-lane-bounded-dispatch.md) and the
  [qualification record](docs/runbooks/latency-qualification-transport-diagnostics.md)
  preserve bounded dispatch and latency failure/repair evidence. The eight-lane,
  sixteen-pending defaults, workload and assertions remain unchanged; this is not
  a production-load or distributed-admission claim.
- [PR103 final handoff](https://github.com/hemantkgupta/tracepermit/pull/103#issuecomment-6384860027)
  and [exact-head workflow](https://github.com/hemantkgupta/tracepermit/actions/runs/40097821467)
  supersede its pre-publication checkpoint: all six required jobs/final verify
  passed at `6df3101e9073863727691390d88ada0b62d9e181`. That complete workflow took
  10m22s, not the five-second final gate. Six independent clean-runner jobs retain
  the aggregate suite and both forced PostgreSQL/MariaDB gates; final verify
  rejects failed, cancelled, skipped, missing and unknown results. The 37 workflow
  regressions and independent inventory retained 29 distinct protected commands/
  31 occurrences, with only the workflow-regression step added. Triggers,
  permissions, action pins and browser/registration jobs were preserved.
  The prior local checkpoint reported actionlint1.7.12, frozen install,
  formatting, 1,067 documentation links, 13 synchronized flow tests/drift and
  diff checks. The frozen `365d5dd` negative control gave 35 workflow passes and
  exactly two expected failures for required MariaDB gate/inventory checks,
  then all 37 passed after reconciliation. These remain historical receipts,
  not checks of the current PR101 merge.
- [Earlier producer CI](https://github.com/hemantkgupta/tracepermit/actions/runs/40093825438)
  passed at `365d5dd` before MariaDB integration, with an observed 8m39s completion.
  It is historical only and does not qualify the reconciled PR103 or PR101 head.

These historical receipts retain their original scope; fresh merged-head PR101
local checks are recorded above, with exact-final-head hosted checks separate.
