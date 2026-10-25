# Operation metadata visibility qualification

This is a bounded observation of disposable databases at application baseline
`d6b7b9b4f954c08e61357420912d05fa91c3a684`. It corrects the SELECT-only CHECK
visibility inference in the [static PR108 audit](https://github.com/hemantkgupta/tracepermit/pull/108#issuecomment-6394784254).
That audit was source analysis, not executed evidence. This qualification does
not change the production validator, physical schema implementation, grants,
authority semantics, or security decisions. It is not a second canonical contract.

## Fixtures and role boundaries

The new tests use the existing public `OperationDatabase.initializeAuthorityEmpty`
API, real JDBC drivers and raw JDBC metadata queries. Required engines are
`postgres:16.4-alpine`, `mariadb:11.4.13`, and `mariadb:10.6.25`; PostgreSQL matches
the repository's existing fixture tag. Drivers are PostgreSQL JDBC 42.7.14 and
MariaDB Connector/J 3.5.7. Missing engines cause failures, not successful skips.

All accounts, generated passwords, rows, extra objects and containers are owned
by these fixtures. MariaDB uses the fixture root account for owner observations;
PostgreSQL uses the fixture database owner. Runtime roles are deliberately separate:

| Role           | Exact fixture grants                                                                                                                                                                                                                      |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `mvq_select`   | Table-level SELECT on the six required V3 tables, including controls and history; PostgreSQL public-schema USAGE. No INSERT, UPDATE, EXECUTE, TRIGGER, EVENT or DDL.                                                                      |
| `mvq_register` | Existing registration/withdrawal grants: SELECT and INSERT on the four registration/lifecycle tables, UPDATE only on head `snapshot_hash` and `snapshot_json`, SELECT on history; PostgreSQL public-schema USAGE. No control-table grant. |

The SELECT-only control is narrower than the existing authority test fixture:
that older MariaDB fixture grants database-wide SELECT, and its PostgreSQL
fixture also grants control `lock_marker` UPDATE. Neither broader grant is copied
into this qualification. The database-wide SELECT profile itself is NOT RUN
here; the CHECK conclusion is specific to the table-scoped grants above.
In particular, the registration role's inaccessible
controls are recorded as an observability failure; no grant is added to make
its results resemble an authority runtime. Extra FK controls receive only the
same table-specific SELECT or SELECT/INSERT pattern; the deliberately denied
child receives no runtime grant.

## Executed required-structure findings

The corrected and final restored-source focused matrices each executed four
tests per engine, twelve total, with zero failures, errors or skips. JDBC reported
server versions `16.4`, `10.6.25-MariaDB-ubu2204` and `11.4.13-MariaDB-ubu2404`.
These are characterization tests: a passing
test that proves metadata is hidden does **not** qualify the runtime as ready.

Both MariaDB versions returned zero direct `CHECK_CONSTRAINTS` rows for both
table-scoped runtimes, even on tables with complete table SELECT. The owner saw
the required checks. On every granted table, runtime `SHOW CREATE TABLE` retained
the CHECK declarations. SELECT alone therefore does not establish direct
CHECK visibility under these table-scoped grants. This independently reproduces
the distinction reported by Mac; Mac's observation is not used as our pass.

The exact per-table counts follow. Each tuple is
`COLUMNS / direct declared CHECKs / TABLE_CONSTRAINTS CHECKs / TABLE_CONSTRAINTS keys`.
Keys here mean PRIMARY KEY, UNIQUE and FOREIGN KEY declarations; exact physical
FK metadata is qualified separately below. PostgreSQL's table-constraint CHECK
count includes NOT NULL entries. Its direct CHECK query is restricted to the
explicit `pg_constraint` CHECK names for the exact table, so it excludes those
NOT NULL entries.

| Required table                    | MariaDB owner, both versions | PostgreSQL owner |
| --------------------------------- | ---------------------------- | ---------------- |
| `tp_business_operations`          | 6 / 1 / 1 / 2                | 6 / 1 / 7 / 2    |
| `tp_business_operation_facts`     | 3 / 2 / 2 / 2                | 3 / 2 / 5 / 2    |
| `tp_operation_lifecycle_heads`    | 3 / 1 / 1 / 2                | 3 / 1 / 4 / 2    |
| `tp_operation_lifecycle_facts`    | 7 / 3 / 3 / 2                | 7 / 3 / 10 / 2   |
| `tp_operation_authority_controls` | 5 / 3 / 3 / 1                | 5 / 3 / 8 / 1    |
| `tp_operation_schema_history`     | 10 / 0 / 0 / 1               | 10 / 0 / 8 / 1   |

For all three engines, `mvq_select` sees the same column names, types and
nullability as owner on all six tables, but zero direct CHECKs and zero
TABLE_CONSTRAINTS CHECK/key rows. `mvq_register` sees owner-equivalent columns
on its five granted tables, zero direct CHECKs everywhere, and owner-equivalent
TABLE_CONSTRAINTS counts on the four tables with INSERT. Its history counts
are `10 / 0 / 0 / 0`, and its control counts are `0 / 0 / 0 / 0`.
MariaDB SHOW CREATE on registration's controls is permission denied, rather than
an empty declaration. Owner CHECK definitions include marker equality, generation
bounds through 9007199254740991, and the staged/ready/quarantined state set.
Declared checks do not prove their enforcement settings or physical key shape.

## Executed hidden-object positive controls

Owner creates harmless control/head UPDATE triggers, an uncalled routine with
no runtime EXECUTE, and, on MariaDB, an event explicitly created DISABLED.
No routine/event is invoked, no scheduler is enabled, and no target/business
operation is executed. Objects are removed in finally; owned containers are also
stopped with a bounded wait.

Counts below are `control trigger / head trigger / uncalled routine / disabled event`.

| Engine          | Owner           | Table SELECT runtime | Registration runtime |
| --------------- | --------------- | -------------------- | -------------------- |
| MariaDB 10.6.25 | 1 / 1 / 1 / 1   | 0 / 0 / 0 / 0        | 0 / 0 / 0 / 0        |
| MariaDB 11.4.13 | 1 / 1 / 1 / 1   | 0 / 0 / 0 / 0        | 0 / 1 / 0 / 0        |
| PostgreSQL 16.4 | 1 / 1 / 1 / N/A | 0 / 0 / 0 / N/A      | 0 / 1 / 0 / N/A      |

The two MariaDB trigger results are independently executed, not assumed equal.
For PostgreSQL, the uncalled function and trigger function live in `mvq_private`,
outside public, with PUBLIC EXECUTE revoked. An additional protected-control-table
TRUNCATE trigger is not visible through `information_schema.triggers`, including
to owner. All three roles see its single `pg_catalog.pg_trigger` row and the
uncalled function's single `pg_proc` row in the private namespace. Thus the
catalog control detects what a public-schema routine count and an information-
schema trigger count can miss. The TRUNCATE trigger is never fired.

Runtime zero counts cannot certify absence of hidden objects. Successful owner
positive detection is essential evidence that the objects actually existed.

## Executed imported-key routes

The real supported Connector/J 3.5.7 option is
`getImportedKeysUsingIs=true`, read from non-mapped options by
[`DatabaseMetaData.getImportedKeys`](https://github.com/mariadb-corporation/mariadb-connector-j/blob/3.5.7/src/main/java/org/mariadb/jdbc/DatabaseMetaData.java).
The pinned official source artifact was inspected, rather than inventing an
option such as `useInformationSchema`. Separate DataSources preserve the same
user, password and grants while changing only the route option. The default
implementation parses SHOW CREATE, with an information-schema fallback on error.

| Both MariaDB versions                       | Owner       | Table SELECT runtime                        | Registration runtime                        |
| ------------------------------------------- | ----------- | ------------------------------------------- | ------------------------------------------- |
| Default route, valid FK                     | 1 exact row | 1 exact row                                 | 1 exact row                                 |
| Explicit information-schema route, valid FK | 1 exact row | 0, required metadata hidden                 | 1 exact row                                 |
| Missing FK on accessible child              | 0           | 0                                           | 0                                           |
| Denied child with owner-proven FK           | 1 exact row | SHOW CREATE denied; JDBC fallback returns 0 | SHOW CREATE denied; JDBC fallback returns 0 |

The three required operation FKs are checked for exact child/parent tables,
`operation_id` columns, catalog, schema, key sequence, update/delete rules and
non-deferrability. Fixture default rules are reported as RESTRICT by MariaDB and
NO ACTION by PostgreSQL. The extra valid child references `mvq_parent.id` with
UPDATE CASCADE / DELETE RESTRICT. An invalid-for-expectation child instead
references `mvq_parent.alternate`; it is a valid installed FK, and the exact-key
assertion rejects it for the expected `id` relation. The missing-key assertion
also rejects its zero-row result. Under hidden information-schema metadata,
zero is recorded as an observability failure, not an assertion of missing DDL.
PostgreSQL's catalog-backed JDBC route sees the exact granted-table FK relations
for all three roles; the MariaDB route option is not applied to PostgreSQL.

## Executed authority-denial controls

Owner demonstrates a valid, nonconflicting CREATE TABLE, ALTER TABLE, CREATE
TRIGGER, CREATE routine and, on MariaDB, CREATE disabled EVENT, then removes each
owned object/change before the identical runtime statement is attempted.
Owner updates to a synthetic control row's profile, generation and state each
affect one row and are rolled back. Both runtime roles must fail those identical
updates with actual permission errors. Duplicate-key, syntax and lock errors
cannot satisfy the denial assertion.

Observed PostgreSQL denials use SQLState `42501` and vendor code 0. Observed
MariaDB denials use SQLState `42000`, with vendor 1142 for table/column/trigger
operations, 1044 for routine/event creation, and 1143 for an unauthorized head
column UPDATE. The existing two-column head UPDATE remains permitted in a
zero-row statement. No runtime privilege is broadened as a repair.

## Reproduction and evidence boundaries

Use the existing prepared runtime, Java 21 **JDK**, Node 22+, pnpm 11.25.0 and
Docker. Select an already verified JDK through process-local
`TRACEPERMIT_JAVA_HOME` when needed. The initial launch failed before compilation
because PATH exposed Java 21 but no javac; it establishes no qualification pass.
The existing prepared JDK was subsequently verified for both Java/javac 21 and
the repository environment script passed. No JDK installation or environment
repair was performed.

From the repository root, with prepared dependency artifacts available:

```sh
bash scripts/run-java-operation-tests.sh --offline --no-build-cache --no-configuration-cache --rerun-tasks --tests '*MetadataVisibilityTest'
```

The first executed matrix ran twelve tests, with three failures caused by the
test draft expecting six control columns instead of the actual five. The other
nine tests passed; the initial failing XML is preserved separately. The corrected
matrix passed twelve tests without skips. Build and configuration caches were
disabled and all focused Gradle tasks were executed, using existing dependency
artifacts and local pinned images. A fixture-only omission of the MariaDB 10.6
head trigger produced exactly one intentionally failed test, zero errors/skips:
`VERSION_SPECIFIC_HEAD_TRIGGER_DETECTION`, expected 1, actual 0. Its failure and
verified byte-identical source restoration are retained separately from passing
results; the final passing matrix used restored fixtures.

JDBC connects have five-second timeouts, socket reads ten seconds, queries five
seconds and locks three seconds. Container startup is bounded with one attempt;
JUnit test/fixture and container cleanup waits are finite. Owned database ports
bind to loopback. Diagnostics contain synthetic labels, aggregate counts, exact
versions and fixed failure categories, not passwords, credential URLs or raw
JDBC causes. The focused per-engine XML and initial/negative-control logs remain
local evidence and are not committed as generated reports.

The full local `pnpm check` failed: 1,896 Java tests produced 1,892 passes,
three failures and one skip. All three failures are in the unchanged
`JavaBackendEndpointConnectorE2eTest` process-cleanup assertions; the four
referenced processes were observed as zombies adopted by PID 1. The unchanged
installed-archive native-PTY test was skipped. No clean-baseline rerun or runtime
repair is claimed. Operation persistence passed 299 tests and PostgreSQL
persistence passed 239, both with zero failures/errors/skips. Console build,
typecheck and all 124 console tests passed. Aggregate coverage verification/report
did not run because the full check failed in a prerequisite test task.

The full run reused 13 cached compilation tasks and 13 up-to-date tasks; no
test task was cached or up-to-date. It recorded no dependency downloads. Two
earlier pnpm launches stopped on shared-dependency path/state checks before
verification began. Existing prepared artifacts were then selected through
local real module directories with isolated task state, with byte-identical
manifests/lockfile and process-local `verifyDepsBeforeRun=warn` to prevent an
automatic install. No install completed. Formatting, documentation links,
flow checks and tracked/staged diff whitespace checks passed.

NOT RUN boundaries: no live database, customer, provider, RDS, target execution,
physical-schema repair, privileged deployment gate, attestation, or continuous
DBA-fencing qualification. These tests do not exercise current validator
acceptance of hidden objects as a desired enforcement outcome. Hosted checks
are reported separately on the exact PR head; source analysis is not substituted
for execution receipts.

A separately privileged operator pre-start/deployment gate remains a **future
prerequisite**, not implemented attestation. Its credential must stay separate
from runtime and its complete visibility must be demonstrated with positive
controls. Runtime required-metadata errors/omissions must not authorize skipping
validation. Neither a startup observation nor a database-local receipt fences
a privileged DBA continuously. This qualification is not a reason to grant the
runtime administrator, TRIGGER, EVENT, CREATE or routine privileges.
