# Sandbox qualification: evidence boundaries and independent repair

## Scope and pinned provenance

This report corrects the evidence claims in PR110 producer
`0963e8423e7d57a8c98c7aaf281a45317d7a3566`. Its runtime is unchanged from
planning main `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`.
Cloud 1's tests/docs-only repair is isolated on
`codex/sandbox-qualification-evidence-repair`, stacked on the producer branch
in [PR112](https://github.com/hemantkgupta/tracepermit/pull/112).
The producer branch has no published writer release and is not claimed or changed.
PR105 and other lanes' source are untouched. Review date: 25 October 2026
(Asia/Calcutta); final source head and writer release belong to PR112's handoff.

Requirements considered: FR-002/003/007/009/010/012;
SEC-001/002/003/005/006/009/012; REL-001/002/003/004/005/008;
DATA-001; OPS-002/004. These are scoped observations, with no whole-ID promotion.

| Source                                                                                                                                                                         | Exact reviewed revision                                                                  | Extent of evidence                                                                                                                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Merged PR101/PR104 foundations                                                                                                                                                 | `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`, also present unchanged in producer `0963e84` | Binding, adapter, permit, pending-only withdrawal and relevant JDBC test/source inspection; local gates below                                         |
| [PR105](https://github.com/hemantkgupta/tracepermit/pull/105)                                                                                                                  | `c52ba244463ff7d363d8e30a21b76dd2a50892e4`                                               | Separately published credential-provider review and test-only composition, not installed or exercised by this suite                                   |
| [PR107 design](https://github.com/hemantkgupta/tracepermit/blob/ddaefdbe573d9083d3d16a80a31aca9e7e96a584/docs/design/mariadb-authority-transaction.md)                         | `ddaefdbe573d9083d3d16a80a31aca9e7e96a584`                                               | Read-only review of proposal status and clock/lock boundaries; no transaction engine is implemented by that design                                    |
| [PR109 ADR0078](https://github.com/hemantkgupta/tracepermit/blob/bc2486a18558dc14998db840187024fd2a2faac9/docs/architecture/0078-agentcore-execution-disabled-registration.md) | `bc2486a18558dc14998db840187024fd2a2faac9`                                               | Published ingress boundary/qualification record reviewed; no new ingress code audit, adapter execution or ingress/JDBC integration qualification here |

PR105's old `9405c0e` receipt is historical, not its reviewed current head.
The separate [PR105 handoff](https://github.com/hemantkgupta/tracepermit/pull/105#issuecomment-6389275902)
and [PR109 qualification record](https://github.com/hemantkgupta/tracepermit/pull/109)
retain their own results and limitations. No claim is made about a later head.
PR108/application authority work is outside this review. ADR0077 belongs to the
Mac authority lane; it is not the decision record for the PR107 proposal.

## What the ten focused probes establish

[SandboxAdversarialQualificationTest](../../java/adapters-remote/src/test/java/io/tracepermit/adapters/remote/github/SandboxAdversarialQualificationTest.java)
uses pure binding/proposal objects, supplied withdrawal contexts and times,
a real permit verifier with a process-local consumption store, an in-memory
execution ledger, stub credentials and numeric-loopback fake target responses.
It does not instantiate a JDBC repository or concrete App-installation provider.
Constructing an authenticated-looking fixture context is not token verification,
and an in-memory result is not durable authority.

| Probe method                                                                 | Direct assertion and boundary                                                                                                                                                                                                                                                                                        |
| ---------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `scopeAndRehashedActionsAreBoundWithoutGrantingExecution`                    | Pure scope/profile mismatches reject; rehashed changed content has different bound digests. The permit-negative action preserves the original event ID and changes only its title, yielding `PERMIT_ACTION_MISMATCH` before network access. This is not request-key registration conflict evidence.                  |
| `callerAuthoredLabelsBindAsDataButDoNotSupplyATargetPermit`                  | Structurally valid proposed approval labels remain bound data; separately, missing/blank target permits yield `MISSING_PERMIT`. No proposed label is committed to a database.                                                                                                                                        |
| `staleExpectedProfilesFailAtPureBindingBoundary`                             | Supplied generation, policy version/content hash and artifact mismatches yield `GITHUB_OPERATION_BINDING_INVALID`. This is not the JDBC whole-state `OPERATION_STATE_STALE` path.                                                                                                                                    |
| `suppliedStoreTimeExpiryRejectsEqualityAndLaterInstants`                     | Direct calls to `requireAuthorizedAt` accept a supplied pre-expiry instant and reject equality/later/null with the expiry boundary. No lock wait or database clock is involved.                                                                                                                                      |
| `samePermitReplayIsRejectedAndFreshPermitUsesCachedSuccess`                  | The exact consumed token rejects with `EXECUTION_PERMIT_REPLAYED`, with no further target request. A newly issued permit for the same action separately returns cached success, still with one total POST and no additional requests.                                                                                |
| `incompletePostDispatchConfirmationStaysUnknownAndCannotPostAgain`           | Valid JSON missing required confirmation fields yields `TARGET_PREFLIGHT_OR_IDENTITY_REJECTED` after one POST. The ledger is exactly `unknown`; a new permit then yields `EXECUTION_OUTCOME_UNKNOWN`, with no second POST or further target request. This is not a database rollback test or a truncated-JSON probe. |
| `changedExpectedRefOrCommitShaBreaksPureBinding`                             | Changing supplied expected refs/SHAs rejects pure binding, with zero HTTP calls. This probe does not observe a remote ref changing.                                                                                                                                                                                  |
| `uncertainTargetOutcomeBlocksSecondPostAndReconciliationNeverAssumesAbsence` | A fake POST500 yields `OUTCOME_UNCERTAIN` and process-local `unknown`. A later invocation cannot POST again. Empty/duplicate lookups remain `LOOKUP_AMBIGUOUS_OR_ABSENT`; one exact match is a positive observation, not a committed ledger transition.                                                              |
| `withdrawalContextAuthorizationAndPermitAudienceAreSeparateBoundaries`       | Constructor checks reject supplied wrong tenant/subject/authentication/role/scope contexts. A separately wrong permit audience yields `WRONG_PERMIT_AUDIENCE` before network access. No workload-to-human delegation or executor enrollment proof is tested.                                                         |
| `selectedErrorsAndDiagnosticStringsOmitCanaryButSnapshotIsSensitive`         | Selected binding/storage exception and binding/profile/withdrawal string forms omit a synthetic canary. The bound snapshot deliberately retains its body; it is sensitive internal data, not a redacted export. No private-key custody or universal evidence/log redaction audit occurs here.                        |

The original changed-title permit probe generated two different event IDs.
It could fail first on event mismatch and did not isolate the claimed action-hash
guard. Preserving the event and asserting the exact code repairs that evidence.
The producer's replay probe used a fresh permit; the repair adds an explicit
same-token rejection before the fresh-permit positive. The original incomplete
confirmation check asserted only “not succeeded”; the repair requires exact
unknown state, one POST and refusal of another dispatch.

## Separately existing JDBC and target evidence

The [shared operation repository contract](../../java/persistence-operations/src/test/java/io/tracepermit/persistence/operations/OperationRepositoryContract.java)
contains actual disposable-database cases, distinct from these ten probes:

- `changedSemanticResourceAdapterOrRecoveryGenerationConflictsWithoutWriting`
  calls the registration repository and checks `OPERATION_REQUEST_KEY_CONFLICT`.
- `authenticationDeadlineIsRecheckedAfterAnActualHeadRowLockWait` holds a real
  head-row lock, waits for a contender, releases after the authority deadline,
  and checks expiry with zero lifecycle writes.
- `commitOrCleanupAcknowledgementLossIsUnknownRegardlessOfActualCommit`
  injects JDBC acknowledgement faults and checks `OPERATION_COMMIT_UNKNOWN`.
- `withdrawalIsAtomicDurableAndNeverReplacesOriginalRegistrationOrOtherDimensions`
  and the concurrent/read-integrity cases cover the existing pending-only
  withdrawal journal, not an operations claim/execute transaction.

[JdbcBusinessOperationLifecycleRepository](../../java/persistence-operations/src/main/java/io/tracepermit/persistence/operations/JdbcBusinessOperationLifecycleRepository.java)
reads/locks the current head before sampling store time and calling
`requireAuthorizedAt`. The command itself does not sample a database clock.
[ADR0072](../architecture/0072-durable-pending-operation-withdrawal.md) exposes
`findCurrent` and pending-only `withdraw`; proposed `claimed`/`executing`
labels are not durable claim/execute APIs. The existing contract is inherited
by the exact PostgreSQL16.4/MariaDB11.4.13/MariaDB10.6.25 fixtures. Any fresh
execution is reported separately below, never as part of the mock suite.

[GitHubDraftPullRequestAdapterTest](../../java/adapters-remote/src/test/java/io/tracepermit/adapters/remote/github/GitHubDraftPullRequestAdapterTest.java)
separately tests fake remote preflight responses, typed confirmations and unknown
outcomes. The adapter's GET preflight is observational, not an atomic remote
compare-and-swap. No suite here establishes restart-safe target dispatch.

## Confirmed behavior and untested boundaries

No new runtime bypass is substantiated by this review. The repaired defects are
in test specificity and report attribution.

- Equality at the authority deadline is **intended expiry**, not a Medium defect.
- The fixed `main` base, draft-only action and exact repository allowlist are
  deliberate pilot limits in the action/binding source, not defects to relax.
- `ExecutionPermitAuthority` signs `audience`, subject/event/action hash and
  policy metadata. It does not add the report's claimed `tenantId` or authorized
  executor-principal claims. Consumption derives its tenant namespace from the
  verified action; this suite does not qualify a separate caller/delegation join.
- `BoundOperationData.toString()` is redacted; no
  `BoundOperationData.redactedRepresentation()` method exists.
  `snapshot()` returns the full defensive copy, including sensitive arguments.
- PR107 is a proposed composition contract. Its design clock/lock discussion is
  not an implemented transaction engine or a measured clock-drift vulnerability.
- PR105's per-invocation exchange/no-cache choice is documented. No rate-limit
  incident, endpoint-specific quota or safe cache design was established here.
  Clock skew and provider capacity remain operational qualification questions,
  not severity-ranked code findings or permission to change runtime policy.

Live provider/target/customer-network, AWS/RDS, signing-key installation,
human/workload delegation, composed ingress-to-JDBC, durable operation-to-target
authority, failover/backup fencing and production readiness remain untested by
this repair. Passing source checks or merging a proposal cannot close them.

## Repair verification record

The producer report claimed all gates green and ten focused passes; those are
producer statements until independently rerun. Cloud 1's verified prepared
environment uses the saved Java/javac21.0.8 selector, Node24.19.0, pnpm11.25.0,
Gradle8.14.3 and managed Unix Docker28.4.0. Two preflights and nine setup tests
passed without environment reconfiguration. The producer's unchanged focused
suite passed fresh:10 cases, zero failures/errors/skips, all17 Gradle tasks
executed with no build/configuration cache.

- Restored repair: all ten focused cases passed within a fresh complete remote
  run of412 cases/13 suites, zero failures/errors/skips; all17 Gradle tasks
  executed without build/configuration cache in43s. Fresh XML was recorded.
- Replay guard-removal control: replace only the test fixture's consumption
  store with an always-accepting implementation. The one selected permanent
  same-token case failed exactly once, with no errors/skips. The fixture was
  restored byte-for-byte before the passing412 run. No runtime file or assertion
  was changed for this control.
- One full `pnpm check` passed:124 React tests;1,835 recorded Java cases/146
  suites, zero failures/errors and one existing installed-archive/native-PTY
  skip. Gradle4m: five tasks executed, four from cache,56 up-to-date. These are
  not all fresh executions. Coverage94.4404% line/78.375% branch passed unchanged
  thresholds. The skip is archive-absent, not merely a noninteractive terminal.
- Separately forced real operation fixtures passed245 cases:78 each on
  PostgreSQL16.4/MariaDB11.4.13/MariaDB10.6.25 plus11 strict-reader cases, zero
  failures/errors/skips; all14 tasks executed without cache in1m14s. Fresh XML
  confirms the actual lock-wait, acknowledgement-loss and changed-content
  registration cases ran on each engine. They remain JDBC evidence, not new
  remote-suite transactions or integration with the target adapter.
- Separately forced standalone MariaDB qualification passed17 cases including
  both exact MariaDB versions, zero failures/errors/skips; all10 tasks executed
  without cache in21s. Fresh XML/times were captured after each successful
  database runner; all three fixture image IDs matched the prepared manifest.
- Frozen install passed. An initial format check flagged the rewritten Markdown
  tables; scoped Prettier formatting repaired them. Final format, local link,
  flow/drift and whitespace checks are repeated after these evidence-only edits.

Reproduce with the saved selector; bound Gradle workers, never internal test
concurrency, deadlines, assertions or coverage thresholds:

```bash
export TRACEPERMIT_JAVA_HOME=/workspace/.local/jdk-21
export XDG_DATA_HOME=/workspace/.local/share XDG_CACHE_HOME=/workspace/.cache
source scripts/configure-java-environment.sh
/usr/local/bin/docker-init -s -- java/gradlew -p java :adapters-remote:test \
  --offline --no-daemon --max-workers=2 --no-build-cache \
  --no-configuration-cache --rerun-tasks --console=plain
GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.workers.max=2" \
  /usr/local/bin/docker-init -s -- pnpm check
/usr/local/bin/docker-init -s -- bash scripts/run-java-operation-tests.sh \
  --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
/usr/local/bin/docker-init -s -- bash scripts/run-java-mariadb-tests.sh \
  --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

Exact-final-head hosted results and writer release are authoritative in PR112's
final TRACEPERMIT HANDOFF, not an earlier producer/base CI receipt. Historical
PR105 native cleanup failures remain in its separate record; a pass here does
not relabel them. No live/provider/installed-production qualification is claimed.

Only this test and report may change in the repair. Runtime, migration, identity,
application authority, persistence and CI bytes remain identical to the producer.
No private planning file, live provider/target action, key installation, public
listener/tunnel, paid fallback, weakened gate, history rewrite or merge is authorized.
