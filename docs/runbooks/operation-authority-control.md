# Internal operation authority-control qualification

This is a developer/operator library prerequisite, not an installed production
workflow. Do not run schema or authority administration against a live/customer
database based on this page. The simulator and existing service do not wire it.

## What exists

[ADR0083](../architecture/0083-writer-locked-operation-authority-control.md)
adds opt-in V3 alongside supported V2, without rewriting V1/V2. A missing or
newly staged control cannot pass the transaction guard. A quarantined generation
cannot be reactivated; replacement requires a newer generation and explicit
activation. This is not human approval or admission.

For a separately approved deployment, stop all writers, obtain/review a backup,
and validate the exact writer profile before an explicit `initializeAuthorityEmpty`
or `upgradeAuthorityFromV2`. The latter checks populated heads/journals before
DDL. Unknown/partial schemas fail closed. MariaDB DDL is not transactionally
rolled back; investigate partial upgrades rather than calling repair/clean.
Startup remains validate-only. Keep operator and runtime users separate.

Runtime controls grant only SELECT; PostgreSQL also needs
`UPDATE(lock_marker)` for its row-lock privilege, not UPDATE of authority
columns. The marker CHECK permits zero only. Other registration/withdrawal
grants remain independently documented in the
[withdrawal runbook](business-operation-withdrawal.md). Never pass an administrator
datasource to a runtime service. Control administration itself does not verify
operator identity or provide an audit/publication pipeline.

## Required transaction shape — still unfinished

1. Validate the exact V3 datasource/engine at startup.
2. Begin one READ COMMITTED writer transaction and lock current control first.
3. Lock operation/approval/single-use/admission/journal rows in a reviewed order.
4. Sample fresh writer time after **all** waits; repeat identity, enrollment,
   group, state, policy/artifact/resource/generation and strict deadline checks.
5. Commit the closed state/facts/admission atomically and acknowledge cleanup
   before any separate synthetic dispatch. Uncertainty never authorizes a send.

Only step2's control prerequisite is added here. Do not compose independently
committing repositories or treat a successful control read as execution authority.

## Focused verification

Use Java21 through repository setup, Node22+, pnpm11.25.0 and Docker. Fixtures
are disposable Testcontainers databases with runtime-generated passwords; no
provider, GitHub, AWS or enterprise values are needed or displayed.

```bash
pnpm test:java-operations --tests '*OperationAuthorityControlTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --max-workers=1 --console=plain
pnpm test:java-operations --rerun-tasks --no-build-cache \
  --no-configuration-cache --max-workers=1 --console=plain
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
```

Serialize aggregate test/coverage writers. Report initial fixture/compiler
failures, deliberate guard-removal controls and restored results separately.
Focused passing results do not substitute for full or exact-head hosted gates.
The shared suite must run all three exact engines, not skip unavailable Docker.
No restart/prune of another worker's Docker or weakened concurrency/expiry gate.

At the first focused checkpoint all33 control cases passed,11 per engine; the
populated/corrupt-upgrade assertions were subsequently added and all33 passed
again. Removing only profile/ready checks made all6 selected three-engine cases
fail; the guards were restored immediately. Earlier fixture failures
were an unhandled Callable exception, an invalid policy fixture and a synthetic
close fault applied to schema-validation connections instead of the intended
mutating transaction; none was repaired by relaxing a production assertion.

An intermediate full check passed, then review found a raw unchecked datasource
exception escaping the new schema-version detector. All3 new engine regressions
failed before mapping that path to fixed `OPERATION_SCHEMA_NOT_READY`. After
repair the complete forced/cache-disabled suite passed281 cases across7 suites,
zero failures/errors/skips:245 existing operation/reader cases plus36 control
cases (12 per engine). All14 tasks executed in1m50. Additional activation-ack
assertions passed the complete forced281 cases again in1m52: the database may
be ready even though the operator received `OPERATION_COMMIT_UNKNOWN`. That
lookup is still not admission or dispatch authority. The subsequent aggregate
`pnpm check` passed:124 React cases,1878 recorded Java cases/150 suites, zero
failures/errors and one existing absent-archive/native-PTY skip. Final Gradle4s:
3executed/62up-to-date; the forced operation suite ran immediately beforehand,
but this is not an all-fresh aggregate run. Coverage94.50% line/78.63% branch,
unchanged thresholds. Formatting,1132 links/3 helpers,13 flows/drift and diff
checks passed. Exact-final-head hosted checks remain a separate gate.

## Limits

No approval/admission/permit/outbox, signed control history, provider verification,
older-backup fence or exactly-once execution. Real lock schedules and injected
acknowledgement faults do not qualify RDS topology, failover, crashes or paused
workers. Store-time/deadline enforcement for the future composed transaction
remains mandatory, not supplied by this guard.
