# MariaDB permit-consumption qualification

This runbook qualifies the unwired MariaDB replay-store foundation from
[ADR 0066](../architecture/0066-mariadb-permit-consumption-foundation.md). It
does **not** install or switch the TracePermit approval runtime to MariaDB.

## Qualified matrix

| Profile      | Exact engine version | Status                                  |
| ------------ | -------------------- | --------------------------------------- |
| Primary      | MariaDB 11.4.13      | Qualified by disposable container tests |
| Transitional | MariaDB 10.6.25      | Qualified by disposable container tests |

Unknown patch versions fail closed until independently tested and recorded.

## Local gate

Prerequisites are Java 21, Docker Engine/Desktop, Node.js 22 or newer, Corepack
and the repository-pinned pnpm version.

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm test:java-mariadb --rerun-tasks
```

The test starts disposable containers for both exact versions, initializes an
empty dedicated database, validates the migration and executes the same
concurrency, restart, isolation and failure-boundary cases. It must leave no
container running.

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
- the version is not exactly 10.6.25 or 11.4.13;
- the database is a system database or contains pre-existing objects;
- the default or TracePermit table engine is not InnoDB;
- the schema guard, object inventory or Flyway checksum differs;
- a concurrency run returns anything other than one committed winner; or
- commit acknowledgement is uncertain.

Do not weaken a check or substitute `INSERT IGNORE` to make qualification pass.
