# ADR 0044 — Database restore is not an execution-authority recovery fence

- Status: accepted safety limitation; automated recovery fencing not implemented
- Date: 2026-10-17
- Requirements: SEC-005/006; REL-001/003/005; OPS-004

## Context

TracePermit consumes approval/permit authority and claims execution atomically
before a simulated target effect. That transaction protects against concurrent
or repeated use of the current durable state; it cannot recover facts absent
from an older database snapshot. A backup also does not capture target reality
or independent signing-key custody.

The simulator qualification additionally found that a failed restore into an
empty database does not imply application startup rejection. Ordinary repository
construction runs Flyway and can initialize the empty database. A schema created
by that bootstrap is not proof that historical replay/evidence state recovered.

## Decision and observed boundaries

1. A custom archive captured after consumption preserves the exact recorded
   approval/action binding, permit consumption, attempt/idempotency identity and
   evidence. Recreated services reject replay for the tested captured state.
2. A pre-consumption snapshot can revive the same still-live approval after a
   later effect. The synthetic target counter is deliberately retained outside
   the restored database; it reaches two when the restored approval dispatches
   again. A distinct fresh permit does not make the duplicated authority safe.
3. A failed archive restore and a successful empty database bootstrap are
   distinct events. The latter provides no archive-success or history-completeness
   guarantee. An unsupported populated schema guard is rejected, but that is not
   general incomplete-restore detection.
4. Keep effectful application services and agents disconnected after restore
   or restore failure until a separately designed and reviewed recovery procedure
   resolves lost authority/target state. This is an operator safety requirement,
   **not an implemented quarantine flag, automatic revocation or recovery epoch**.
   This ADR does not prescribe ad hoc SQL mutation of approvals or authorize a
   production resume procedure.
5. Do not claim rollback-safe recovery, RPO/RTO, high availability, customer
   backup custody, general tenant isolation, independent evidence authenticity
   or production readiness from these tests. Those remain explicit work.

## Qualification

`PostgresBackupRestoreQualificationTest` uses two fresh databases per case inside
one owned PostgreSQL 16 container. It runs actual `pg_dump -Fc` and `pg_restore`
with single-transaction/exit-on-error options, checks exit codes and validates
owner-only bounded synthetic archives. Generated ephemeral Ed25519 keys stay
outside the archive and are reused across the fixture's service recreation.

It compares complete approval/attempt/evidence records and raw migration rows
(including checksums) before destination constructors can migrate anything.
Policy pointer/generation and revision/transition/rollback records are also
compared. This is same-version capture/restore proof, not a migration strategy.

No production Java, schema, policy, permit rule, launcher or target adapter is
changed. The repository initialization gap is characterized, not repaired by
tests. The hash-linked evidence comparison is local captured-state integrity,
not an independently signed recovery anchor.

See the [qualification runbook](../runbooks/simulator-backup-restore-qualification.md)
for the six cases, exact boundaries and primary PostgreSQL references.
