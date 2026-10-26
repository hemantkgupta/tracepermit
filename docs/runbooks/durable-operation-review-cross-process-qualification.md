# Qualifying durable operation review across real JVM boundaries

Scope: Qualification of [ADR0085](../architecture/0085-closed-durable-operation-review.md),
[ADR0077](../architecture/0077-closed-operation-authority-contracts.md),
[ADR0083](../architecture/0083-writer-locked-operation-authority-control.md), and
[ADR0084](../architecture/0084-durable-review-membership-and-lifetime.md)
across independent operating system process boundaries and process lifetimes on
PostgreSQL 16.4, MariaDB 11.4.13, and MariaDB 10.6.25. Approval metadata remains
strictly distinct from admission and execution.

## Operator lifecycle and isolation

Use PostgreSQL 16.4, MariaDB 11.4.13, or MariaDB 10.6.25 in an isolated, dedicated
container with network access pinned strictly to loopback (`127.0.0.1`).
`OperationDatabase.initializeReviewEmpty` creates the explicit V4 schema with
immutable operations, lifecycle facts, heads, authority controls, and review
bindings.

Operator staging and explicit activation configure the review profile with exact
group name, enrollment-source fingerprint, recovery generation, and positive
approval lifetime through existing `OperationAuthorityAdministration` APIs.
Runtime database roles receive table-scoped permissions: SELECT on all schema
tables, INSERT on lifecycle facts and review bindings, UPDATE of head snapshot
hash/JSON only, and PostgreSQL UPDATE on the control lock-marker column for
`SELECT ... FOR UPDATE`. No binding UPDATE/DELETE, DDL, or trigger grants are
provided to the runtime role.

## Cross-process durability architecture

Testing closed durable review transactions across independent JVM boundaries
evaluates process crashes, process restarts, independent concurrent contenders,
and historical fact-time replay without relying on in-memory shared state:

1. **Child JVM entry point and secret isolation**:
   Child processes execute `OperationReviewChildJvm.main()`. Database connection
   strings, credentials, and command specifications pass exclusively over private,
   line-delimited stdin JSON streams. No credentials, tokens, or database URLs appear
   in command-line arguments (`argv`), environment variables, temporary files, or
   system logs. Both request and response record `toString()` implementations redact
   sensitive parameters (`credentials=[REDACTED]`).

2. **Byte bounds and stream limits**:
   To prevent denial-of-service, memory exhaustion, or hanging on malformed input,
   the harness enforces strict byte limits:
   - Line input/output: maximum `MAX_LINE_BYTES = 65_536` (64 KB), justified by valid
     canonical business operations measuring ~2–4 KB.
   - Stderr capture: maximum `MAX_STDERR_LINE_BYTES = 1024` (1 KB per line),
     `MAX_STDERR_LINES = 100`, and `MAX_STDERR_TOTAL_BYTES = 32_768` (32 KB total).
     `readBoundedLine` reads streams up to these limits with strict UTF-8 report
     decoding, conservatively throwing `ProtocolException` or `CharacterCodingException`
     if exceeded or malformed.

3. **Total supervision deadlines and cleanup**:
   - Immediate ownership: The harness acquires unconditional ownership immediately after
     `ProcessBuilder.start()`. Any failure during request serialization, writing, or
     readiness handshakes forcibly terminates the process and closes streams.
   - Monotonic command budget: The entire command lifecycle (request write, ready wait,
     GO/RELEASE signals, committed wait, response read, process exit, drainer join) is
     governed by a single monotonic deadline (`System.nanoTime() + commandBudget.toNanos()`).
     Phases do not renew the budget.
   - Finite teardown reserve: Cleanup allocates a separate, finite reaping reserve
     (`REAP_RESERVE_MS = 2000`). Owned child processes are forcibly destroyed _before_
     attempting stream closure, ensuring blocking pipe I/O cannot hold cleanup hostage.
   - Interruption preservation: Thread interrupt flags are strictly preserved across
     all blocking operations, futures, and teardown steps.

4. **Unconditional ownership of barrier children**:
   All child processes and barrier contender handles are wrapped with `try-with-resources`
   from acquisition through all assertions. Any assertion failure, readback exception,
   or timeout immediately destroys the owned process, preventing orphaned child processes.

5. **Closed bounded response validation**:
   Responses emitted by child JVMs undergo strict, closed validation before being marked
   acknowledged:
   - Strict UTF-8 decoding (`CodingErrorAction.REPORT`).
   - Local Jackson mapper with strict duplicate key detection (`StreamReadFeature.STRICT_DUPLICATE_DETECTION`)
     and trailing token refusal.
   - Standard 8-4-4-4-12 UUID format validation (`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`).
   - Exact allowed field sets for `OK` and `REFUSED` (no unknown fields accepted).
   - Mandatory non-empty `snapshot` validating under `BusinessOperationValidator.validate()`.
   - Exact match between `snapshotHash` and `CanonicalJson.sha256(snapshot)`.
   - Matching `operationId` and `resultStatus` against snapshot contents and request expectations.
   - Required non-blank `bindingDigest` and `factId`.

6. **Continuous stream-bounded stderr drainer**:
   The stderr drainer reads continuously in chunks until EOF, preventing OS pipe buffer
   deadlocks under heavy stderr volume. It enforces exact byte accounting, truncates lines
   exceeding `MAX_STDERR_LINE_BYTES`, and enforces the `MAX_STDERR_TOTAL_BYTES` cap prior to
   retaining lines. Once limits are met, draining continues and discards subsequent bytes.
   Zero raw stderr lines are retained in memory.

7. **End-to-end private diagnostic redaction**:
   Handshake failures and uncaught errors emit fixed generic category markers rather than
   echoing raw stdout or stderr content. Truncated prefixes are not used. Diagnostics
   expose only fixed category strings (`CHILD_SIMULATED_CRASH`, `CHILD_SIMULATED_EARLY_EXIT`,
   `STDERR_PRESSURE`, `UNCLASSIFIED_STDERR`) and numerical counts. Synthetic private canary
   tests verify that exception messages, getters, and `toString()` methods never leak secrets.

## Failure modes and acknowledgement uncertainty

The harness distinguishes three separate failure boundaries:

1. **Pre-command child termination**:
   The child process terminates or is killed before executing the repository command
   (e.g., at the `READY` barrier before releasing `GO`). No transaction is started;
   the database remains in its pre-command state with zero transitions or review bindings.

2. **Post-commit lost acknowledgement**:
   The repository command successfully commits to the database, but the child process
   terminates, crashes, or suffers a broken pipe before emitting the acknowledgement
   result. The parent harness observes `UNACKNOWLEDGED` (`acknowledged == false`).
   An independent reader can inspect and reconcile the committed database state, but the
   harness does not manufacture an artificial acknowledgement. Repeating the original
   command with the stale snapshot is rejected with `OPERATION_STATE_STALE`, preventing
   duplicate transitions.

3. **Repository commit and cleanup uncertainty**:
   At the JDBC driver layer, failures during the commit phase or connection cleanup
   may leave the outcome uncertain (`OPERATION_COMMIT_UNKNOWN`), requiring reconciliation
   by lookup rather than automatic retry.

## Evaluated scenarios across engines

All 10 qualification scenarios execute identically on PostgreSQL 16.4, MariaDB 11.4.13,
and MariaDB 10.6.25:

- **Process restart durability**: JVM A evaluates the initial registered operation
  to `awaiting_approval` and exits. JVM B (fresh process, fresh DataSource, fresh
  repository instance) reads the pending snapshot and binding from the database,
  independently reviews and approves it, and exits. JVM C (fresh reader) verifies
  the final approved state. JVM D (fresh reader) verifies the immutable initial
  registration remains byte-for-byte unchanged.
- **Independent contenders (Approve vs Withdraw)**: Two separate JVMs compete for
  the pending operation at the database lock boundary. Exactly one contender wins
  and commits its transition (`status: "OK"`). The losing contender is safely
  refused with `OPERATION_STATE_STALE` or `OPERATION_NOT_WITHDRAWABLE`. The database
  retains exactly one terminal lifecycle fact and preserves the original review binding.
- **Independent contenders (Approve vs Deny)**: Two separate JVMs compete for the
  pending operation. Exactly one decision wins; the loser is safely refused with
  `OPERATION_STATE_STALE` or `OPERATION_NOT_REVIEWABLE`.
- **Historical replay at fact time**: Time advances beyond the configured approval
  deadline (`approvalDeadline`). A fresh reader JVM reconstructs the snapshot from
  immutable history via `findCurrent()`; the snapshot correctly reports
  `awaiting_approval` and does not report "expired" or corrupt storage. A fresh
  authority command attempting review after deadline expiry is refused with
  `OPERATION_APPROVAL_EXPIRED`. Fresh evaluation is refused with
  `OPERATION_NOT_REVIEWABLE` (or `OPERATION_STATE_STALE` when holding the stale
  initial head).
- **Genuine operator lifecycle (quarantine, stage, activate)**: Quarantining the
  authority control row via `OperationAuthorityAdministration.quarantine` causes
  subsequent review attempts by child JVMs to be rejected with
  `OPERATION_AUTHORITY_NOT_READY`. Staging a replacement profile with generation 2
  via `stageReplacement` proves that the staged profile remains inactive before
  activation (`OPERATION_AUTHORITY_NOT_READY`). Activating the staged profile via
  `activateStaged` causes child JVMs configured with generation 1 to be rejected
  with `OPERATION_PROFILE_MISMATCH`. Reviewing the generation 1 pending operation
  under generation 2 is also rejected with `OPERATION_PROFILE_MISMATCH` because
  the evaluation binding is cryptographically pinned to generation 1. Existing facts
  and bindings remain intact.
- **Closed outcomes coverage**: Policy block transitions directly to `denied`
  without an approval deadline and cannot be reviewed (`OPERATION_NOT_REVIEWABLE`).
  Human denial transitions to `denied`. Withdrawals before and after evaluation
  record lifecycle facts without consuming or creating approvals.
- **Pre-command termination safety**: Terminating the child at the `READY` barrier
  before command execution produces `UNACKNOWLEDGED` and leaves the database
  completely untouched.
- **Committed evaluation with lost acknowledgement**: The child commits evaluation
  to `awaiting_approval`, signals the post-commit barrier, and is terminated before
  emitting the result JSON. The parent reports `UNACKNOWLEDGED`. An independent
  reader confirms the committed state. Re-running evaluation with the initial head
  is rejected with `OPERATION_STATE_STALE`.
- **Committed review with lost acknowledgement**: The child commits review to
  `approved`, signals the post-commit barrier, and is terminated before emitting
  the result JSON. The parent reports `UNACKNOWLEDGED`. An independent reader confirms
  the committed state. Re-running review with the pending head is rejected with
  `OPERATION_STATE_STALE`, appending zero duplicate transitions.
- **Child crash and dead-pipe resilience**: Simulated process crashes or broken
  pipes before acknowledgement are caught by the harness (`acknowledged == false`),
  preventing false-positive test passes.

## Distinguishing negative controls

Distinguishing negative controls verified that harness safety gates actively catch
defects and are not vacuous:

1. **Unconfirmed child status**: `OperationReviewChildJvm.java` was modified to emit
   `UNCONFIRMED` instead of `OK` upon command completion. 6 of 7 original test cases
   failed immediately in the parent harness.
2. **Lost acknowledgement treated as success**: `ContenderHandle.unacknowledged` was
   mutated to return `acknowledged = true, status = "OK"`. 13 of 20 supervision tests
   failed immediately (`testSilenceChildExitsWithoutResponseReportsUnacknowledged`,
   `testEarlyExitChildReportsUnacknowledgedWithNonZeroExitCode`, `testOversizedResponseLineRejectedConservatively`,
   `testNoNewlineResponseHandledSafely`, `testStderrDrainerEnforcesCapsAndDrainsContinuouslyUnderPressure`,
   `testMalformedUtf8ResponseRejectedConservatively`, `testDuplicateKeyResponseRejectedConservatively`,
   `testTrailingTokenResponseRejectedConservatively`, `testInvalidUuidRegexRejectedConservatively`,
   `testIncompleteOrEmptySnapshotRejectedConservatively`, `testMismatchedSnapshotHashRejectedConservatively`,
   `testUnknownFieldsInOkResponseRejectedConservatively`, `testEndToEndPrivateCanaryRedactionInExceptionsAndDiagnostics`).

All mutations were restored byte-for-byte and verified clean with `git diff --check`.
Subsequent reruns passed all tests.

## Candidate qualification history

1. In the initial contract run, `testRestartedStaleProfileRefusesCommandWhenQuarantinedOrNewGenerationActivated`
   failed with `PSQLException: column "active" does not exist` due to attempting a direct SQL update.
   The test was updated to use `control_state = 'ready', recovery_generation = 2`.
2. `testHistoricalReplayReconstructsRecordAtFactTimeAndRefusesFreshAuthorityAttempts` initially
   failed because it attempted an in-flight review within an 800ms window across separate JVM
   startup times. The test was aligned with the intended contract: advancing past the database
   deadline, verifying fact-time snapshot reconstruction via `findCurrent` without reporting
   expired, and asserting that post-deadline review commands are rejected with
   `OPERATION_APPROVAL_EXPIRED` and fresh evaluations with `OPERATION_NOT_REVIEWABLE` / `OPERATION_STATE_STALE`.
3. In coordinator review, several qualification gaps were identified: unbounded stream reads,
   lack of immediate process cleanup ownership, non-monotonic timeout accumulation, direct SQL
   generation updates, and lack of committed-but-unacknowledged tests.
4. Static review on head `76f0c25b23440af7633495608f526a8b1e7bca8c` identified five specific
   supervision findings:
   - Untimed request writes/flushes and stream closure before process termination.
   - Unconditional cleanup omission on barrier handles.
   - Missing strict UTF-8 decoding, missing duplicate key & trailing token refusal, loose UUID regex,
     and unvalidated snapshot fields.
   - Early stderr drainer termination on overlong lines and character-based size tracking.
   - Diagnostic leaks of unexpected stdout and stderr lines.
5. All five findings were resolved:
   - Monotonic command budgeting established before setup with dedicated `REAP_RESERVE_MS` teardown reserve.
   - Try-with-resources wrapping for all barrier handles.
   - Closed response validation with local strict Jackson parser, standard UUID regex, and validator execution.
   - Continuous chunk-based stderr draining with byte-level cap enforcement.
   - Categorized diagnostics with zero raw line retention and synthetic canary verification.
6. The test suite expanded from 39 to 50 tests (20 supervision unit tests + 30 cross-process database tests).

## Reproduction

Use Java 21 (with both `java` and `javac` major version 21), Node 22+, pnpm 11.25.0,
and Docker:

```sh
# Environment and Java runtime verification
pnpm java:env-check

# Run supervision unit tests
bash -c 'set -euo pipefail; source scripts/configure-java-environment.sh; exec java/gradlew -p java :persistence-operations:test --tests "*OperationReviewChildJvmSupervisionTest" --no-daemon --rerun-tasks --no-build-cache --max-workers=1 --console=plain'

# Run cross-process qualification across all three database engines
bash -c 'set -euo pipefail; source scripts/configure-java-environment.sh; exec java/gradlew -p java :persistence-operations:test --tests "*OperationReviewCrossProcessTest" --no-daemon --rerun-tasks --no-build-cache --max-workers=1 --console=plain'

# Repository lint, documentation, and flow gates
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

## Qualification matrix results

| Database Engine / Suite     | Suite Name                                  | Tests Executed | Passed | Failed | Errors | Skipped | Time  |
| :-------------------------- | :------------------------------------------ | :------------- | :----- | :----- | :----- | :------ | :---- |
| Independent JVM Supervision | `OperationReviewChildJvmSupervisionTest`    | 20             | 20     | 0      | 0      | 0       | 5.0s  |
| PostgreSQL 16.4             | `PostgresOperationReviewCrossProcessTest`   | 10             | 10     | 0      | 0      | 0       | 60.6s |
| MariaDB 11.4.13             | `MariaDb114OperationReviewCrossProcessTest` | 10             | 10     | 0      | 0      | 0       | 39.2s |
| MariaDB 10.6.25             | `MariaDb106OperationReviewCrossProcessTest` | 10             | 10     | 0      | 0      | 0       | 30.6s |
| **Total**                   |                                             | **50**         | **50** | **0**  | **0**  | **0**   |       |

This qualification qualifies the tested closed durable review scenarios across
independent local JVM boundaries and process lifetimes on PostgreSQL 16.4, MariaDB 11.4.13,
and MariaDB 10.6.25. It does not certify the complete transaction model, external platform
behavior, or live cloud provider integrations, AWS, Okta, Jira, or AgentCore actions.
