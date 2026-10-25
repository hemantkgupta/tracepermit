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
     `readBoundedLine` reads streams up to these limits, conservatively throwing
     `ProtocolException` if exceeded.

3. **Process ownership and monotonic budgeting**:
   - Immediate ownership: The harness wraps `ProcessBuilder.start()` in try-finally
     handling to guarantee that any failure during request serialization, writing,
     or readiness handshake forcibly terminates and closes the child process.
   - Monotonic time budgeting: Timeouts are measured against a single monotonic deadline
     (`System.nanoTime() + timeout.toNanos()`). Each phase (response reading, exit
     waiting, drainer joining) decrements remaining nanoseconds without renewing the
     budget.
   - Executor shutdown: Single-thread executors used for bounded blocking reads are
     shut down and awaited, preserving thread interrupt status upon interruption.

4. **Process identity markers**:
   Each child JVM initializes a cryptographically random UUID marker (`jvmId`) at
   startup and includes it in structured responses. This UUID serves as a test
   harness correlation marker proving distinct process instances; it is not an
   OS-enforced PID, hardware-bound identity, or cryptographic platform attestation.

5. **Contender barrier synchronization**:
   To test true concurrent contention at the database lock boundary without in-memory
   thread synchronization, child processes initialize their DataSource and prepare
   the command, emit `READY\n` on stdout, and await `GO\n` on stdin. The parent
   waits for both contenders to reach readiness before simultaneously signaling
   both pipes, releasing real OS processes against the database row locks.

6. **Acknowledgement boundary and conservative validation**:
   Child JVMs execute commands under transactional isolation, flush their structured
   JSON response to stdout, and exit with code 0. Parent harnesses verify process exit
   code and strictly validate the JSON payload (`jvmId` format, `status` enum,
   `snapshot` object, `snapshotHash`, and known error codes). Any malformed,
   unrecognized, or unacknowledged execution is classified as `UNACKNOWLEDGED`
   (`acknowledged == false`), never as a successful command or a safe stale loser.

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
  and commits its transition (`status: "OK"`). The losing contender is rejected
  with `OPERATION_STATE_STALE`. The database retains exactly one terminal lifecycle
  fact and preserves the original review binding.
- **Independent contenders (Approve vs Deny)**: Two separate JVMs compete for the
  pending operation. Exactly one decision wins; the loser is rejected with
  `OPERATION_STATE_STALE`.
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

Two separate distinguishing negative controls were executed:

1. **Unconfirmed child status**: `OperationReviewChildJvm.java` was modified to emit
   `UNCONFIRMED` instead of `OK` upon command completion. 6 of 7 original test cases
   failed immediately in the parent harness.
2. **Lost acknowledgement treated as success**: `ContenderHandle.awaitResult` was
   mutated to return `acknowledged = true, status = "OK"` on unacknowledged or
   abnormal child exit. All 4 unacknowledged tests failed immediately
   (`testResourceAndAcknowledgementBehaviorFailsOnChildCrashOrDeadPipe`,
   `testPreCommandChildTerminationProducesNoStateTransition`,
   `testCommittedEvaluationWithWithheldAcknowledgementReportsUnacknowledgedAndReconcilesCleanly`,
   `testCommittedReviewWithWithheldAcknowledgementReportsUnacknowledgedAndPreventsDuplicateTransition`).

Both modifications were cleanly restored byte-for-byte and verified with `git diff --check`.
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
4. `OperationReviewChildJvm` was hardened with `MAX_LINE_BYTES = 65_536`, `MAX_STDERR_LINE_BYTES = 1024`,
   monotonic deadline budgeting, immediate try-finally cleanup ownership, and conservative JSON
   validation.
5. The direct SQL generation shortcut was replaced with real `OperationAuthorityAdministration`
   APIs (`quarantine`, `stageReplacement`, `activateStaged`).
6. Dedicated pre-command crash and post-commit lost acknowledgement tests were added and qualified
   across all three database engines.

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

# Run full operations test suite
pnpm test:java-operations --rerun-tasks --no-build-cache --max-workers=1 --console=plain

# Repository lint, documentation, and flow gates
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

## Qualification matrix results

| Database Engine / Suite     | Suite Name                                  | Tests Executed | Passed | Failed | Errors | Skipped |
| :-------------------------- | :------------------------------------------ | :------------- | :----- | :----- | :----- | :------ |
| Independent JVM Supervision | `OperationReviewChildJvmSupervisionTest`    | 9              | 9      | 0      | 0      | 0       |
| PostgreSQL 16.4             | `PostgresOperationReviewCrossProcessTest`   | 10             | 10     | 0      | 0      | 0       |
| MariaDB 11.4.13             | `MariaDb114OperationReviewCrossProcessTest` | 10             | 10     | 0      | 0      | 0       |
| MariaDB 10.6.25             | `MariaDb106OperationReviewCrossProcessTest` | 10             | 10     | 0      | 0      | 0       |
| **Total**                   |                                             | **39**         | **39** | **0**  | **0**  | **0**   |

This qualification qualifies the tested closed durable review scenarios across
independent local JVM boundaries and process lifetimes on PostgreSQL 16.4, MariaDB 11.4.13,
and MariaDB 10.6.25. It does not certify the complete transaction model, external platform
behavior, or live cloud provider integrations, AWS, Okta, Jira, or AgentCore actions.
