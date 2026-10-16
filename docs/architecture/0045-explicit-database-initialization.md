# ADR 0045 — Explicit database initialization and strict operator startup

- Status: implemented for the local simulator operator profile
- Date: 2026-10-17
- Requirements: SEC-002/003/006/012; REL-001/005; OPS-001/002/004 (bounded scope)

## Context

Automatic Flyway migration in repository constructors can initialize an empty
database after a failed restore. That makes successful startup ambiguous: it
may mean a fresh installation, not recovered consumption/history. Source-free
operators also should not need schema ownership merely to run an initialized
installation. This decision separates first-install intent from normal startup.

## Decision

`SimulatorApprovalMain`, the source launcher and the packaged launcher share:

| Command     | Database behavior                                                                                   | Listener / IdP / target |
| ----------- | --------------------------------------------------------------------------------------------------- | ----------------------- |
| `--check`   | None; existing offline configuration/key/file validation                                            | None                    |
| `--init-db` | Explicit empty-only public-schema installation, packaged V1–V8 migrations, then validation          | None                    |
| `--run`     | Validate existing schema, then use strict repository opens; no migration, baseline, clean or repair | Loopback simulator API  |

Commands accept exactly one argument. Invalid input or failure returns exit 2
and a bounded `APPROVAL_STARTUP_FAILED` message. Successful initialization emits
`APPROVAL_DATABASE_INITIALIZED` and exits, without starting a service. Flyway's
logger selection is empty so JDBC URLs, identifiers and SQL diagnostics are not
printed by these lifecycle operations. This is not global driver/JVM logging
redaction; keep operator diagnostics private.

### Initialization authority and emptiness

The operator supplies a dedicated, already created local database. No command
creates/drops a database, role, secret file or key. Initialization permits the
normal `public`, `information_schema` and PostgreSQL internal namespaces and
the default `plpgsql` extension. It refuses other user namespaces; public
relations, routines, types, collations, conversions, operators/operator classes
and families, text-search objects; non-default extensions; event triggers,
foreign servers and large-object metadata. Normal schema ACLs/ownership and
cluster roles are setup concerns, not evidence that an application was recovered.

The operator connection is pinned to `public`; strict opening also rejects an
unexpected current schema. The initializer holds a database-local session
advisory lock on one owned connection through the empty-state check, Flyway
migration and final validation. Another cooperating initializer fails fast with
`DATABASE_INITIALIZATION_BUSY`, rather than waiting indefinitely. The session
lock is explicitly released in `finally`; physical connection closure also
releases it if the session fails. A retained-session lease regression tests
explicit release rather than assuming every lease close destroys its session.
Metadata queries have a 5-second JDBC timeout; Flyway connections set a 10-second statement timeout,
5-second lock timeout and five lock retries. These are per-operation bounds,
not a measured total recovery-time promise.

Migration may commit individual versions before a later failure. Partial state
is retained for private investigation: no automatic rollback of earlier
migrations, reset, retry, history repair or adoption of populated state. A later
init refuses it, and normal run refuses incomplete state. Never rerun init to
recover an old installation or convert a failed restore into a fresh one.

This lock coordinates this initializer, not DB administrators, legacy Java
callers, arbitrary DDL or another program that ignores it. Initialize only
with the application stopped and exclusive operational ownership of the DB.

### Strict existing-state opening

`PostgresDatabaseLifecycle.validateExisting` checks the public ordinary-table
inventory, exactly one supported guard row, and enabled invariant triggers on
the expected tables/functions. It runs packaged Flyway validation (including
checksums with no ignored future migrations), and requires exactly successful
versions 1–8: no pending, failed, future, baseline or additional migration rows.
It does not call migrate/repair/clean/baseline. The pinned Flyway API and a
non-owner fixture role with no history-write/schema-create rights qualify the
non-migrating path; this is not inferred just from the method's name.

`PostgresDurableEnforcementState.openExisting` and
`PostgresPermitConsumptionStore.openExisting` are used by the operator runtime.
Checking once and then calling an auto-migrating constructor is intentionally
not the implementation. Both strict paths validate before use. Existing public
constructors retain trusted development/test auto-migration compatibility;
`PostgresPolicyLifecycleStore` is not newly wired into this static-policy
launcher. This is a profile-specific boundary, not a global JVM mode fence.

Initialization may use a migration-owner config, then runtime a separate
non-owner config/password file with application DML grants. The process does
not retain initialization credentials after the command exits. Role provisioning
and complete production least-privilege grant administration are not added.

## Compatibility, upgrade and recovery limits

- Fresh operator installations require an extra explicit `--init-db` step.
  Already initialized matching-schema installations continue with `--run`.
- Existing older populated schemas fail startup. This slice deliberately adds
  no in-place upgrade command; do not reset them, edit migration checksums,
  baseline or use init as an upgrade. An explicit upgrade workflow needs its
  own reviewed design and backup/rollback qualification.
- Strict schema/history checks are not full physical-schema equivalence,
  archive authenticity, row completeness or DBA-tamper detection. A disabled
  required trigger is detected; arbitrary trigger-function/constraint/column
  rewriting is not claimed comprehensively detected.
- A complete old snapshot can pass every check while reviving consumed
  approval authority. Its policy generation and hash-linked evidence roll back
  with the database. No external epoch/witness, automatic restore detection,
  quarantine, approval revocation or safe effectful resume is implemented.
- Keep agents/effectful services disconnected after a restore or restore
  failure. Validation success is not recovery authorization. Local targets here
  remain simulators; no real target or remote/public deployment is enabled.
- Browser configuration, policy, auth, permits and exactly-once limitations are
  unchanged. Installing V8 still does not opt into generation-bound mode.

## Verification

`PostgresDatabaseLifecycleTest` uses fresh generated databases in one owned
disposable PostgreSQL 16 container. It covers empty strict opens, refusal of
existing objects, full history preservation, changed/failed/pending/future
migration records, missing tables/guard, disabled triggers, wrong search path,
connection failure, competing init and held-lock failure/release, plus strict
opening with a non-owner read-only fixture role.

`SimulatorApprovalBootstrapTest` uses the actual operator configuration/main,
child JVMs, signed synthetic HTTP identities and PostgreSQL. Fresh empty run
fails without creating tables/listening; explicit init starts no listener;
repeated init fails. Initialized non-owner runtime performs independent review,
one simulated execution, restart and replay rejection. Missing invariant triggers
fail before binding or JWKS retrieval. The source-free package test repeats
empty-run/init/repeated-init/run/SIGTERM with Node/pnpm/Gradle absent from PATH.

All DBs, accounts, keys and resources are synthetic fixture-owned material.
Neither these tests nor schema validation establish production recovery safety.

## Primary references

- [Flyway validate](https://documentation.red-gate.com/flyway/reference/commands/validate)
- [Flyway migrate](https://documentation.red-gate.com/flyway/reference/commands/migrate)
- [PostgreSQL 16 advisory locks](https://www.postgresql.org/docs/16/explicit-locking.html#ADVISORY-LOCKS)
