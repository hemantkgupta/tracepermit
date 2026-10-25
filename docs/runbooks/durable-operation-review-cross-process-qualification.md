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
approval lifetime. Runtime database roles receive table-scoped permissions: SELECT
on all schema tables, INSERT on lifecycle facts and review bindings, UPDATE of
head snapshot hash/JSON only, and PostgreSQL UPDATE on the control lock-marker
column for `SELECT ... FOR UPDATE`. No binding UPDATE/DELETE, DDL, or trigger
grants are provided to the runtime role.

## Cross-process durability architecture

Testing closed durable review transactions across independent JVM boundaries
evaluates process crashes, process restarts, independent concurrent contenders,
and historical fact-time replay without relying on in-memory shared state:

1. **Child JVM entry point and secret isolation**:
   Child processes execute `OperationReviewChildJvm.main()`. Database connection
   strings, credentials, and command specifications pass exclusively over private,
   line-delimited stdin JSON streams. No credentials, tokens, or database URLs appear
   in command-line arguments (`argv`), environment variables, temporary files, or
   system logs.

2. **Process identity proof**:
   Each child JVM initializes a cryptographically random UUID (`jvmId`) at startup
   and includes it in structured stdout responses. The test harness asserts that
   evaluator, reviewer, reader, and contender processes possess provably distinct
   JVM identities without capturing host-identifying system data or process IDs.

3. **Stderr isolation**:
   Parent harnesses concurrently drain child stderr streams in background threads to
   prevent OS pipe buffer exhaustion, deadlocks, and silent hangs.

4. **Contender barrier synchronization**:
   To test true concurrent contention at the database lock boundary without in-memory
   thread synchronization, child processes initialize their DataSource and prepare
   the command, emit `READY\n` on stdout, and await `GO\n` on stdin. The parent
   waits for both contenders to reach readiness before simultaneously signaling
   both pipes, releasing real OS processes against the database row locks.

5. **Acknowledgement boundary**:
   Child JVMs execute commands under transactional isolation, flush their structured
   JSON response to stdout, and exit with code 0. Parent harnesses verify process exit
   code and require `acknowledged == true` before evaluating assertions. Abrupt child
   termination or dead pipes immediately fail the acknowledgement boundary.

## Evaluated scenarios across engines

All 7 qualification scenarios execute identically on PostgreSQL 16.4, MariaDB 11.4.13,
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
- **Stale profile and quarantine controls**: Quarantining the authority control row
  via `OperationAuthorityAdministration.quarantine` causes subsequent review attempts
  by child JVMs to be rejected with `OPERATION_AUTHORITY_NOT_READY`. Advancing the
  control recovery generation causes child JVMs configured with the predecessor
  generation to be rejected with `OPERATION_PROFILE_MISMATCH`.
- **Closed outcomes coverage**: Policy block transitions directly to `denied`
  without an approval deadline and cannot be reviewed (`OPERATION_NOT_REVIEWABLE`).
  Human denial transitions to `denied`. Withdrawals before and after evaluation
  record lifecycle facts without consuming or creating approvals.
- **Child crash and dead-pipe resilience**: Simulated process crashes or broken
  pipes before acknowledgement are caught by the harness (`acknowledged == false`),
  preventing false-positive test passes.

## Distinguishing negative control

A distinguishing negative control was executed by modifying `OperationReviewChildJvm.java`
to emit `UNCONFIRMED` instead of `OK` upon command completion.

- **Observed behavior**: 6 of 7 test cases failed immediately with assertion errors
  in the parent harness. The only passing test was the crash/pipe failure test which
  specifically verifies absence of acknowledgement.
- **Restoration**: The modification was cleanly restored, and `git diff --check`
  verified byte-identical restoration with zero remaining diff or whitespace errors.
  Subsequent reruns passed all 7 tests.

## Reproduction

Use Java 21 (with both `java` and `javac` major version 21), Node 22+, pnpm 11.25.0,
and Docker:

```sh
# Environment and Java runtime verification
pnpm java:env-check

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

| Database Engine | Suite Name                                  | Tests Executed | Passed | Failed | Errors | Skipped |
| :-------------- | :------------------------------------------ | :------------- | :----- | :----- | :----- | :------ |
| PostgreSQL 16.4 | `PostgresOperationReviewCrossProcessTest`   | 7              | 7      | 0      | 0      | 0       |
| MariaDB 11.4.13 | `MariaDb114OperationReviewCrossProcessTest` | 7              | 7      | 0      | 0      | 0       |
| MariaDB 10.6.25 | `MariaDb106OperationReviewCrossProcessTest` | 7              | 7      | 0      | 0      | 0       |
| **Total**       |                                             | **21**         | **21** | **0**  | **0**  | **0**   |

This qualification certifies the closed durable review transaction across
independent JVM boundaries on PostgreSQL 16.4, MariaDB 11.4.13, and MariaDB 10.6.25.
It does not attest live cloud provider integrations, AWS, Okta, or AgentCore actions.
