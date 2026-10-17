# Durable completion uncertainty

Scope: direct durable simulator execution and the separate authenticated durable
approval service. All downstream effects remain simulated. This runbook does not
authorize manual database mutation, target retries or production recovery.

## What happens

If target dispatch occurred but completion recording throws, the service raises
`DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH`, not success or `not_executed`. Existing
wire adapters may map this to an allowlisted generic unknown/unavailable error,
not expose the internal code verbatim. The service makes
at most one additional database completion call for the same tenant/attempt,
recording `unknown / DURABLE_COMPLETION_UNCONFIRMED` only if the attempt is still
`executing`. The repository atomically appends evidence/outbox and queues lookup.

| Actual persisted state  | Meaning and safe interpretation                                                                                                           |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `succeeded` or `failed` | A completion may have committed despite the original exception; the fallback does not overwrite it.                                       |
| `unknown`, queued       | A completion/uncertainty transaction committed. A separately configured lookup-only worker can resolve it from a trustworthy observation. |
| `executing`             | Recording may still be unavailable, or execution may be abandoned. No automatic sweeper or safe target retry is established.              |

Approval/permit consumption stays in place in every case. Replaying the same
canonical event/idempotency identity or consumed approval cannot restore that
authority or create another effect. A new adapter-generated event is a different
request; this is not business-level deduplication of identical arguments. The first response stays
uncertain even if the additional unknown recording succeeded; inspect durable
state through the trusted, tenant-scoped application/repository boundary.

## Operator response

1. Preserve the bounded attempt/idempotency correlation and stop effectful
   retries. An error after dispatch is not proof of failure at the target.
2. Restore database availability without resetting consumption, rewriting
   evidence or replacing the request with a fresh event to evade deduplication.
3. Inspect the same tenant, adapter and idempotency identity. Do not assume the
   database exception establishes whether commit occurred.
4. For queued unknowns, only a separately configured read-only outcome resolver
   may supply a confirmed result. The worker never invokes the original target.
   The default approval launcher does not run this worker automatically.
5. If state remains executing or trustworthy lookup is unavailable, retain that
   uncertainty. A reviewed quiesced recovery/quarantine procedure is still needed;
   do not improvise SQL status changes or redispatch.
6. After backup restoration keep effectful clients disconnected. This change
   does not detect old snapshots or fence revived approvals. Follow the limits in
   [the restore runbook](policy-bound-restore-qualification.md).

## Reproduce the bounded qualification

Use Java 21, frozen workspace dependencies and Docker for the disposable database:

```sh
pnpm install --frozen-lockfile
pnpm check
pnpm format:check
pnpm test:java-postgres --tests io.tracepermit.persistence.postgres.PostgresCompletionFailureRecoveryTest --rerun-tasks
```

The new PostgreSQL suite runs both services through rollback-before-commit,
lost-success acknowledgement, target denial with lost recording, lost-failure
acknowledgement, lost-unknown acknowledgement, acknowledgement loss on the
additional unknown write, and persistent acquisition failure. It checks actual
rows, evidence integrity, queue cardinality, one target call and replay behavior.
Its lookup proof uses a deliberately synthetic confirmed observation, not a real
AWS lookup or a production target's idempotency guarantee.

The application unit suite additionally checks all completion outcomes, exact
tenant/attempt binding and bounded exception handling. See
[ADR 0048](../architecture/0048-bounded-durable-completion-uncertainty.md) for the
transaction contract and the remaining crash, outage and restore boundaries.
