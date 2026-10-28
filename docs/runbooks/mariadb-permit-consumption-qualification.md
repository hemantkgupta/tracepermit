# MariaDB permit-consumption qualification

This runbook qualifies the unwired MariaDB replay-store foundation from
[ADR 0066](../architecture/0066-mariadb-permit-consumption-foundation.md). It
does **not** install or switch the TracePermit approval runtime to MariaDB.

## Current qualification matrix

| Profile    | Exact engine version | Status                              |
| ---------- | -------------------- | ----------------------------------- |
| Primary    | MariaDB 11.4.13      | Active disposable-container tests   |
| Historical | MariaDB 10.6.25      | Automated integration tests retired |

Unknown patch versions fail closed in the existing runtime profile. The runtime
still recognizes 10.6.25, but the current tree has no fresh 10.x integration
qualification. See [ADR0094](../architecture/0094-mariadb-10-qualification-retirement.md).

## Local gate

Prerequisites are Java 21, Docker Engine/Desktop, Node.js 22 or newer, Corepack
and the repository-pinned pnpm version.

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm test:java-mariadb --rerun-tasks
```

The test starts a disposable container for MariaDB 11.4.13, initializes an
empty dedicated database, validates the migration and executes the same
concurrency, restart, isolation and failure-boundary cases. It must leave no
container running.

The active matrix contains seven store cases on 11.4.13, plus three
engine-profile unit cases. Non-empty/schema adoption, commit/acknowledgement
uncertainty and acquisition/close faults remain active for that image. Store reconstruction
uses the same running database: it is not a database restart, crash-recovery or
failover test. Synthetic transaction faults do not qualify an external error
redaction boundary or production driver diagnostics.

## Mac requalification — 2026-10-24

The normal reconciliation with main (including merged PR100/102) retained all
production MariaDB code, migration bytes, engine pins and assertions. Three
previously primary-only schema/transaction-fault cases now run on both profiles.
`pnpm test:java-mariadb --rerun-tasks` passed all 17 invocations in 33 seconds,
with zero failures/errors/skips; both disposable containers were removed.

One `pnpm check` passed in 9m25s: 124 React tests and 1,584 recorded Java cases
across 141 suites, zero failures/errors and one existing archive-absent native-PTY
skip. Gradle reused valid unchanged outputs (65 tasks: 36 executed, 29 up-to-date);
the MariaDB test output came from the preceding forced run, not a second fresh
run. Coverage was 94.27% line/78.01% branch under unchanged 94%/75% gates.
Formatting, actionlint 1.7.12, runner syntax, 1,064 documentation links and
13 synchronized flow tests also passed. Exact-head hosted checks are recorded
separately in PR94's final handoff; these local results do not imply hosted or
production success.

## Deployment candidate constraints

For a later private Amazon RDS qualification, use a dedicated TracePermit
database and separate migration/runtime users. Use an encrypted private
MariaDB 11.4.13 instance with InnoDB and the writer endpoint. Disable automatic
minor upgrades during qualification. A sandbox may be Single-AZ; a production
candidate needs Multi-AZ, backup/restore and failover evidence. Read replicas
must not answer authoritative enforcement or claim queries.

Never place database passwords, connection URLs, AWS identifiers or customer
identifiers in Git, test output, PR text or handoffs. Record only the engine
profile and sanitized pass/fail evidence.

## Stop conditions

Stop without repair, retry or runtime wiring when:

- the product is not MariaDB;
- the version under current qualification is not exactly 11.4.13;
- the database is a system database or contains pre-existing objects;
- the default or TracePermit table engine is not InnoDB;
- the schema guard, object inventory or Flyway checksum differs;
- a concurrency run returns anything other than one committed winner; or
- commit acknowledgement is uncertain.

Do not weaken a check or substitute `INSERT IGNORE` to make qualification pass.
