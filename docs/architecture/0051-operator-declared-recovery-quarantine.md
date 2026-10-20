# ADR 0051 — Operator-declared recovery quarantine

- Status: implemented for PostgreSQL simulator authority and the local approval operator profile
- Date: 2026-10-17
- Requirements: FR-007/008/012; SEC-002/005/006/012; REL-001/003/004/005/009; OPS-002/004 (bounded scope)

## Context

An old, internally valid backup can revive a consumed approval. Startup schema
validation is not recovery proof (ADR 0044). A startup flag alone also leaves
already-running replicas able to claim authority. The first containment slice
must work at the shared database without inventing an automatic rollback witness
or a safe-resume protocol.

## Decision

Current version follow-up: [ADR 0059](0059-explicit-v9-v10-simulator-upgrade.md)
extends strict startup to V1–V10 and adds a stopped-service V9→V10-only upgrade
that refuses quarantined state. The V9 provenance and historical slice below
remain; the one-way recovery/no-resume boundary is unchanged.

Flyway V9 installs one installation-wide `tp_recovery_quarantine` row, initially
`ready`. The table owner (or a role inheriting that owner's authority) may declare
`quarantined`, recording one random recovery UUID and trusted operator-process
time. Repeated declarations return the original record. Ordinary SQL cannot
delete, truncate, overwrite or return the row to ready. This is not protection
against an administrator who can alter triggers, rewrite schema or restore data.

| Operation                                                          | Ready                         | Quarantined                                             |
| ------------------------------------------------------------------ | ----------------------------- | ------------------------------------------------------- |
| Create durable claim / consume standalone permit                   | Existing checks               | Refused                                                 |
| Create pending approval / review / consume-and-claim               | Existing checks               | Refused, including denial transitions                   |
| Inspect requests, attempts, evidence                               | Existing authorization        | Still permitted                                         |
| Complete an admitted attempt; reconcile a queued unknown by lookup | Existing transition checks    | Still permitted                                         |
| Publish evidence/outbox                                            | Existing lease/receipt checks | Still permitted                                         |
| Mint an internal permit                                            | Existing signer checks        | Not gated; cannot pass durable consumption/claim        |
| Start effectful `SimulatorApprovalMain`                            | Strict schema validation      | Refused before listener creation if already quarantined |

### Linearization and lock ordering

Cooperating authority transactions take a shared lock on the singleton **first**,
before approval, policy and evidence locks. It lasts through the atomic claim
commit. Declaration takes a conflicting update lock and holds it through commit.
Database insertion triggers independently gate consumed permits, attempts,
requests and review transitions. Trigger lookup is pinned to the affected table's
schema, not the caller's search path. The installed operator schema remains
`public`; existing trusted library fixtures can use separate schemas.

Therefore, once quarantine commits, no subsequent gated transaction can commit
new authority. A claim admitted **before** quarantine may still dispatch or finish
afterward: target calls occur outside the transaction. This is not cancellation
or an in-flight drain. A concurrent declaration can also follow startup's ready
check; a listener may then exist, but its authority writes fail closed. Existing
HTTP/MCP adapters return their generic `SECURITY_STATE_UNAVAILABLE` error, not a
new remote recovery command. Reads retain all existing authorization checks.

Quarantine and guard reads use five-second JDBC statement timeouts, not an
end-to-end recovery SLO. Timed-out or ambiguous commits are not reported as
confirmed. A fresh status check can establish the currently visible row;
repeating the one-way declaration cannot clear it. Runtime row locking requires
SELECT and UPDATE privilege on the guard, but its invoker-rights trigger rejects
non-owner transitions even with broad DML grants. No security-definer function
or remote operator privilege is introduced.

### Operator surface and schema lifecycle

Source and source-free launchers add `--recovery-status` and `--quarantine-db`.
Both validate existing V1–V9 state, never migrate, never open a listener or contact
an IdP/target, and emit only bounded code/status text. `--check` stays offline;
`--init-db` stays empty-only; `--run` stays non-migrating. Strict startup refuses
V8 as pending upgrade; this slice has **no in-place upgrade or resume command**.
Do not initialize, drop or reset an existing database to satisfy that check.
Legacy Java development constructors retain their explicit auto-migration behavior.

## Qualification

Real disposable PostgreSQL tests cover ready/one-way/idempotent state, owner
versus broadly granted runtime DML, direct and approval claims, standalone
consumption, SQL bypass attempts, all-tenant containment, rollback, restart,
completion/lookup after quarantine, and forced claim-commit/declaration races.
Races use commit barriers and observable database lock waits, bounded futures
and daemon-executor cleanup rather than a throughput/sleep assertion.
Bootstrap tests exercise signed HTTP against an already-running service, zero
new permit/attempt rows, read inspection, real child-main commands, restart
refusal and port release. Packaged tests execute the same commands without
Node/pnpm/Gradle/source on runtime PATH.

## Deliberate limits

- This is **operator-declared containment**, not automatic restore detection.
  A valid old V9 snapshot may contain `ready` and can erase a later quarantine.
  Same-database UUID/time/history are not an independent monotonic witness.
- Stop clients and effectful services before restore. After restore or failure,
  keep them disconnected. A matching V9 database can be explicitly quarantined
  for inspection; older/incomplete state requires separate offline investigation.
- No resume procedure, authority re-enrollment, automatic key rotation, executing
  sweeper, new reconciliation scheduler or external target lookup is added.
  Elapsed time alone never establishes that an effect failed.
- Process-local/in-memory development paths and non-durable target adapters are
  not fenced by this PostgreSQL mechanism. It enables no real target integration.
- This record is operational state, not a signed independent audit receipt;
  preserve operator incident evidence privately. Whole recovery/operations
  requirements remain partial.

See the [operator runbook](../runbooks/operator-recovery-quarantine.md).
