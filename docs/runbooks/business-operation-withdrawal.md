# Qualify the internal durable operation withdrawal library

This developer/operator gate is not an installed customer service or public API.
It adds no listener/live target. See [ADR 0072](../architecture/0072-durable-pending-operation-withdrawal.md).

## Reproduce

Use Java 21 through the repository scripts, Node.js 22+, pnpm 11.25.0 and Docker.
No provider credentials or reusable cloud database are needed:

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm test:java-operations --rerun-tasks
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

Every operation case on PostgreSQL 16.4, MariaDB 11.4.13 and MariaDB 10.6.25 must
execute without environment skips. Tests create/remove disposable containers
with generated credentials. Never substitute a customer/RDS URL or print secrets.

## Explicit V2 upgrade

The Java operator entry points are `OperationDatabase.initializeEmpty` for a new
dedicated store and `OperationDatabase.upgradeFromV1` for the exact V1 store.
No deployment-ready launcher/provisioning is added. Never use an existing
simulator approval or permit-consumption database.

1. Stop all old/new operation writers and verify a backup/recovery plan.
2. Use a separate migration identity; inspect engine/writer settings privately.
3. Invoke the explicit V1 upgrade once; runtime remains validate-only.
4. Grant the new tables below, verify preserved original rows/heads and start
   V2 writers only. Stopping/fencing old processes is an operator responsibility.
5. On partial/unknown/checksum-different state, stop and investigate. MariaDB DDL
   may partially commit. Do not retry DDL blindly, repair history or erase data.

Runtime grants: SELECT/INSERT on `tp_business_operations`,
`tp_business_operation_facts`, `tp_operation_lifecycle_heads`, and
`tp_operation_lifecycle_facts`; SELECT on `tp_operation_schema_history`; and
column-scoped UPDATE `(snapshot_hash, snapshot_json)` on heads only. PostgreSQL
also needs USAGE on public. Never grant UPDATE on original registrations/facts,
head-ID UPDATE, DELETE, DDL or administrative rights. The shared tests exercise
these narrower grants, including PostgreSQL row locks.

## Interpret commands and observations

A trusted future adapter must verify authentication/enrollment and derive exact
workspace scope. Neither constructor nor `authenticated` body flags prove this.
The new domain requires role `operation_requester` and scope `operations.cancel`;
`approvals.cancel` alone does not suffice. The closed internal command accepts
ID, expected state, trusted scope/context, not desired state/proposal/time/reason.
Its checks repeat under the database lock using store time.

| Result                                                                    | Next step                                                                  |
| ------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Returned cancelled snapshot                                               | Commit and cleanup acknowledged; no target/permit/publication occurred     |
| `OPERATION_STATE_STALE`                                                   | Explicitly observe current state; never auto-rebase/retry                  |
| `OPERATION_NOT_WITHDRAWABLE`                                              | Refuse; cancellation is not rollback of admitted work                      |
| `OPERATION_REQUESTER_NOT_AUTHORIZED` / `OPERATION_AUTHENTICATION_EXPIRED` | Refuse; obtain independently verified current authority, never alter flags |
| `OPERATION_NOT_FOUND`                                                     | No record in that trusted scope; no unscoped fallback                      |
| `OPERATION_CLOCK_REGRESSION`                                              | Stop and investigate clock/data privately                                  |
| `OPERATION_STORAGE_CORRUPT`                                               | Stop use; investigate registration/head/chain/actor inconsistency          |
| `OPERATION_COMMIT_UNKNOWN`                                                | No success or automatic retry; explicitly inspect current state            |

Registration `createOrGet`/`find` return the **initial** snapshot. Lifecycle
`findCurrent` returns progress. No status/hash is a signed approval, verified
target result, S3 receipt or restored-backup fence. RDS/TLS/failover, AgentCore,
GitHub/IdP, EKS and production use remain independently gated.

Duplicate-key or trailing-data stored JSON is corruption even when a permissive
parse would reproduce the expected hash. Reads return the fixed corruption code;
an explicit upgrade refuses that predecessor before DDL. Do not strip/normalize
the stored value, recompute hashes or retry migration to make it pass. Investigate
privately and recover through the operator's verified recovery procedure.
