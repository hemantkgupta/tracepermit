# ADR 0066 — MariaDB permit-consumption foundation

- Status: implemented and qualified as an unwired foundation
- Date: 2026-10-23
- Requirements: FR-008; SEC-005/006/009; REL-004; OPS-002; DATA-001

**Qualification scope update:** [ADR0094](0094-mariadb-10-qualification-retirement.md)
retires automated MariaDB 10.x container qualification. The 10.6 results and
decisions below are historical; existing version recognition remains in code,
but it does not constitute fresh 10.x qualification. MariaDB 11.4 remains active.

## Context

The durable TracePermit approval, execution, evidence, policy and recovery
composition is PostgreSQL-specific. A design-partner environment requires
Amazon RDS for MariaDB, but changing a JDBC URL cannot preserve PostgreSQL
locking, trigger, JSON, transaction or recovery semantics. Claiming database
parity before those behaviors are ported would weaken the enforcement boundary.

The first independently useful slice is the standalone execution-permit replay
store. It has a small schema and one security-critical guarantee: among all
concurrent callers, exactly one tenant-bound claim may commit for an exact
permit ID. It does not include an execution attempt, approval transition or
evidence event.

## Decision

1. Add a separate `persistence-mariadb` Java module and migration location. It
   does not reuse or translate PostgreSQL migrations.
2. Qualify exactly MariaDB `11.4.13` as the primary profile and `10.6.25` as a
   transitional compatibility profile. Other engines and versions fail closed.
   The profile reports only product, version, database and storage-engine facts;
   it never exposes credentials or a connection URL.
3. Require a dedicated non-system database whose default storage engine is
   InnoDB. TracePermit tables explicitly declare InnoDB, binary ASCII identity
   columns, microsecond UTC wall-clock fields, and checks for IDs, hashes and
   claim-before-expiry.
4. Installation is an explicit `initializeEmpty` operation. A session-scoped,
   database-derived named lock serializes cooperating initializers; any existing
   table, view, routine, trigger or event prevents adoption. The lock is not an
   enforcement lock and is never used after installation.
5. Runtime construction is validate-only. It verifies the exact object set,
   guard row, table engines, qualified engine profile and Flyway checksums. It
   never migrates, repairs, cleans or baselines automatically.
6. Consumption validates tenant, permit, expiry and verified canonical action
   hash before SQL. It issues a plain `INSERT` into the tenant/permit primary key
   and treats only MariaDB duplicate-key error 1062 as replay. `INSERT IGNORE`
   and broad integrity-error handling are prohibited because they can hide
   unrelated constraint or storage failures.
7. Each write owns a fresh connection, commits before returning success and
   rolls back failures. A failed or lost commit acknowledgement is uncertainty,
   never retry authority. Reconciliation may inspect the claim; target dispatch
   must not follow a failed return.
8. No pruning is implemented in this slice. Replay rows must not be removed
   until MariaDB execution and approval references exist and their retention
   transaction is qualified.
9. The module remains unwired from launchers and application services. The
   existing PostgreSQL transaction remains the only composed durable approval,
   execution and evidence path.

## Rationale

This creates testable MariaDB capability without suggesting that SQL syntax
equivalence establishes enforcement equivalence. Exact versions make the
qualified baseline reproducible. The primary key supplies the InnoDB
serialization point, while commit-before-success preserves the caller's
single-use decision under concurrency and restart.

## Verification

`MariaDbPermitConsumptionStoreTest` runs the same behavior against real
`mariadb:10.6.25` and `mariadb:11.4.13` containers. It covers exact-once and
tenant isolation, 32 contending claim attempts across four store instances using
16 workers, store-reconstruction persistence, exact expiry, malformed bindings,
validate-only startup, non-empty
schema rejection, and commit/acquisition/close uncertainty. Engine-profile unit
tests reject unknown engines, versions, storage engines, system databases and
malformed version strings.

All seven store cases, including non-empty-schema and injected transaction-fault
cases, run on each exact image; the three profile unit cases bring the matrix to
seventeen invocations. Store reconstruction observes the existing database and
does not establish database crash/restart or failover recovery. Synthetic fault
assertions are not qualification of an external diagnostic-redaction boundary.

## Limits and next steps

- This is not RDS, replication, failover, TLS, backup/restore or production
  qualification.
- It is not a MariaDB implementation of `PostgresDurableEnforcementState`.
- No approval, policy lifecycle, evidence/outbox, recovery quarantine,
  reconciliation, retention or GitHub target execution is wired to MariaDB.
- A privileged database administrator remains outside the trust boundary.
- The eventual RDS deployment must use the writer endpoint for every
  authorization and mutation. Replicas may serve non-authoritative reporting
  only.
