# ADR0083: writer-locked operation policy and recovery control

Status: internal prerequisite implemented; composed approval/admission and
executor wiring remain unimplemented. Qualification is recorded separately.

Requirements: FR-003/007/008/012; SEC-001/002/003/005/009/012; REL-001/002/004/005;
OPS-002/004. No whole-requirement or production-readiness promotion.

## Decision

Keep one transaction owner in `persistence-operations`. Add an explicitly opt-in
V3 control table, not an independently committing runtime replay/approval store.
The existing V1/V2 migrations remain byte-identical. Default `initializeEmpty`
still installs V2. `initializeAuthorityEmpty` explicitly installs V3 in an empty
dedicated database; `upgradeAuthorityFromV2` validates the exact V2 schema and
populated registration/head/journal before additive DDL. Startup validation
accepts exact V2 or V3 inventories without migration, repair or adoption.
Authority administration requires V3. Older binaries must be stopped before
upgrade; they do not acquire compatibility through this newer validator.

The control row is keyed by the exact computed owner-scope fingerprint and pins
the trusted operator review-profile fingerprint and recovery generation. Existing
strict profile input validation supplies fixed lowercase hashes and bounded
positive generations; MariaDB PAD SPACE behavior is not an identity-normalization
mechanism. Scope still includes requester, tenant and workspace. This first
profile has one active control per owner scope, not a general policy catalog.

Only explicit, separately privileged operator administration can change controls:

- `stageNew`: insert an inactive `staged` profile. Missing/staged is never ready.
- `activateStaged`: exact profile/generation/state conditional update to `ready`.
- `quarantine`: exact ready profile moves to `quarantined`.
- `stageReplacement`: same exact scope, strictly greater generation, exact old
  profile/generation conditional update. The new row is `staged`, not ready.

A quarantined generation cannot simply be activated again. Any policy/profile
replacement also advances generation. This is conservative invalidation of old
pins; no automatic migration/re-enrollment/retry occurs. Commit and connection
cleanup must both acknowledge success. Lost acknowledgements yield fixed
`OPERATION_COMMIT_UNKNOWN`, not an activation receipt. No raw causes/suppressed
driver diagnostics escape. These Java methods do not authenticate an operator;
the privileged datasource is a trusted deployment input, never a public body.
An activation may actually have committed despite that error. A later ready-row
lookup is not proof that the earlier caller received acknowledgement, and is
never a substitute for approval/admission or permission to dispatch.

## Physical structure and metadata visibility

Flyway history/checksums are not proof that current physical tables still match
the migrations. Read-only startup/pre-DDL validation additionally requires the
known columns, types, lengths, nullability, defaults, character sets/collations;
exact primary-key order and required foreign-key targets/rules; and known CHECK
expressions. MariaDB sessions must enable both foreign-key and CHECK enforcement.
No DDL, grant expansion or automatic repair occurs.

PostgreSQL uses catalog CHECK definitions and validated/nondeferrable constraints.
Trigger inspection uses `pg_trigger` on protected relations, including TRUNCATE
triggers whose functions live outside `public`.

On both qualified MariaDB versions the table-scoped runtime gets no
CHECK_CONSTRAINTS rows despite SELECT on required tables. The implementation
therefore reads bounded `SHOW CREATE TABLE` declarations with those existing
SELECT grants and a closed lexer, then compares exact known CHECK expressions.
It does not normalize arbitrary expressions, count quoted-name decoys, skip
missing checks or adopt unknown output. Comments, quote escapes, unsupported
quoting/malformed output and declarations over65,536 characters refuse readiness.
Other physical properties and actual keys retain separate metadata checks:
SHOW CREATE is not general physical-index attestation. The
[official documentation](https://mariadb.com/docs/server/reference/sql-statements/administrative-sql-statements/show/show-create-table)
describes its SELECT requirement, quoting/mode caveats and declaration limits.

Limited runtime metadata **cannot prove complete absence** of MariaDB routines,
events or hidden triggers. Zero visible forbidden objects is not an inventory
certificate. A future deployment must separately qualify operator-controlled,
fully visible pre-start inspection with writers stopped, keep that credential
outside runtime and revalidate controlled DDL changes. That deployment gate or
attestation is not implemented here; no request receipt is trusted. Startup is
not a continuous fence against a privileged DBA. Do not grant runtime
TRIGGER/EVENT/DDL/admin to hide this limitation.

## Lock and privilege boundary

`OperationAuthorityControl.requireCurrent` takes the future transaction owner's
already-open READ COMMITTED writer connection. It locks the exact control row
`FOR UPDATE`, then checks stored profile, generation and `ready` state. It never
opens, commits, rolls back or closes the caller's connection, and returns no
claim/permit/dispatch capability. The caller must validate the V3 datasource at
startup. Passing a Java connection is not database-origin attestation.

This control lock is **first**, before operation/approval/permit/attempt/journal
locks. The complete future transaction must sample fresh writer time only after
all later lock waits, recheck actor/group/deadlines/state/bindings, and commit
approval/admission facts atomically. A guard call followed by a separately
committing store is not that transaction. No network/provider/signing/target
call runs under a database lock. Exclusive scope locking is conservative and may
serialize different operations in the same scope; no throughput claim follows.

Runtime gets SELECT on controls, no INSERT/DELETE/DDL or UPDATE of scope,
profile, generation or state. PostgreSQL additionally needs column-scoped
`UPDATE(lock_marker)` to take a row lock. The marker is constrained to zero and
is not authority data. MariaDB runtime uses SELECT only. Real restricted-user
tests prove locking works and authority-column/DDL mutations are refused.
Operators use separate reviewed privileges; the runtime must never receive the
administration datasource. The library cannot prevent misconfigured credentials.

## Migration and qualification boundary

V3 has one additive CREATE TABLE per engine, including InnoDB and a fixed
control-state/generation vocabulary. There is no data activation in DDL. Writers
must be stopped and operator backup/recovery reviewed. MariaDB interrupted DDL
requires investigation; there is no silent resume/repair or downgrade operation.
Pre-DDL validation cannot protect against concurrent writers if the operator
ignores that stopped-writer prerequisite.

The shared three-engine contract uses PostgreSQL16.4, MariaDB11.4.13 and
MariaDB10.6.25. It exercises inactive/ready/reopened controls; exact scope/profile/
generation; quarantine/replacement; real lock blocking and committed-row recheck;
restricted grants; commit/cleanup/write/rollback faults; explicit populated V2
upgrade and corrupt-head rejection before V3 DDL. Deliberately removing profile/
ready checks must fail selected tests before restoration. Record exact executed
counts and broad gates in [the runbook](../runbooks/operation-authority-control.md).

This adds no approval request, reviewer decision, deadline, fully bound operation
permit, admission claim/attempt, outbox or target call. Registration/withdrawal
remain their existing limited contracts. Mutable controls are not a signed audit
journal or DBA-proof attestation. Older-backup resurrection, paused-worker send
fencing, live RDS/replicas/failover and production identity remain unqualified.
