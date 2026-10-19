# ADR 0052: Owner-only recovery inspection

Status: accepted for simulator qualification.

Requirements: FR-015; SEC-003/012/019; REL-002/005/008; OPS-002/004.

## Context

Operator quarantine contains new durable authority but does not explain existing
execution uncertainty. Raw SQL exports can disclose identities and action payloads,
and separate reads can mix different points in time. Inspection must not become
an implicit recovery operation or a cross-tenant runtime endpoint.

## Decision

Current consumer follow-up: [ADR 0059](0059-explicit-v9-v10-simulator-upgrade.md)
requires V1–V10 and advances the inspection report to format `2.0` with a
`cancelled` approval count. This ADR's original format/version evidence below is
historical; the owner-only, read-only and no-safe-resume boundary is unchanged.

Add an explicit local `--recovery-inspect` command to the source and installed
simulator approval launchers. Validate existing V1–V9 schema/history first, without
migration or repair. On a new connection, use a read-only repeatable-read
transaction pinned to `public`. Require ownership or inherited owner authority
on all four inspected tables and reject unexpected row-level security rather
than silently report filtered counts. Ordinary runtime SELECT privileges are
insufficient for this installation-wide command.

The initial schema/ownership reads establish the snapshot. Read quarantine state and aggregate
stored execution statuses, approval statuses and reconciliation queue cardinality
in that snapshot. End the transaction before returning the report; failures
produce only the existing redacted exit-2 diagnostic, never partial success.
Output is fixed-schema JSON with numeric counts, recovery mode and the database
transaction start time. It contains no tenant, requester, reviewer, request,
action, credential fingerprint, lease owner or per-attempt identifier.

Use ordinary MVCC reads, not the authority guard's shared lock. Inspection does
not block quarantine or completion with row locks. Query/statement budgets are
five seconds and the transaction-local lock budget is one second. These are not
an end-to-end command deadline or a performance SLA: connection acquisition and
the separate schema/history validation retain their existing bounds.

## Meaning and limits

- Counts are exact for that database snapshot, not necessarily current at print
  time. The timestamp is transaction start, not a cryptographic snapshot identity,
  independently trusted clock, freshness guarantee or restore witness.
- Approval counts use stored status. They do not lazily expire approvals or
  establish current policy/identity/expiry eligibility.
- `executing` does not mean an effect is currently running, nor that it never
  ran. `unknown` and queue counts do not establish target outcome or worker health.
  Inspection does not classify abandoned executing rows or enqueue them.
- Neither a ready mode nor all-zero counts authorizes resume. A restored ready
  snapshot can still revive old authority; preserve disconnected services after
  restore/failure. This does not add upgrade, resume, cleanup, cancellation,
  authority release, target retry/lookup, background scheduling or HTTP/MCP API.
- Schema validation and ownership are compatibility/access checks, not proof
  against a malicious database owner or forged backup. The supplied operator
  configuration must identify the intended dedicated simulator database.
- Exact aggregation scans stored tables; output size is constant, work is not.
  Large or locked installations may time out without a report. No arbitrary
  performance target or unbounded diagnostic export is introduced.

## Evidence

`PostgresRecoveryInspectionTest` qualifies real PostgreSQL counts across tenants,
all statuses, owner/runtime separation, inherited ownership, RLS refusal,
repeatable-read interleaving with concurrent claims/quarantine, actual read-only
and timeout settings, and failure paths. Bootstrap and installed-distribution
tests exercise redacted command output and listener-free execution. Targets stay
simulated. See the [operator runbook](../runbooks/operator-recovery-inspection.md).

The snapshot contract follows PostgreSQL's
[repeatable-read semantics](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-REPEATABLE-READ)
and [read-only transaction rules](https://www.postgresql.org/docs/16/sql-set-transaction.html).
