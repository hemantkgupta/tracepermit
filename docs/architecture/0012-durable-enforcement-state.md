# ADR 0012 — Keep durable enforcement state around, not across, target dispatch

- Status: accepted; Java PostgreSQL simulator slice implemented
- Date: 2026-10-09

## Context

The process-local permit store, execution ledger, and evidence chain demonstrate
the correct states but lose them on restart and cannot coordinate replicas. A
single-use permit can therefore be consumed once per process rather than once
per deployment. Holding a database transaction open around an AWS, Jira, or
other target request would not solve the atomicity gap: PostgreSQL cannot roll
back an unrelated external effect, and the long lock would increase contention.

The durable store is security-critical. If it is unavailable before dispatch,
the executor has no safe proof that the permit was claimed once or that the
initial evidence is durable.

## Decision

Introduce an application/domain `DurableEnforcementState` port and a focused
`persistence-postgres` adapter using PostgreSQL and Flyway. The deterministic
classifier and policy evaluator remain persistence-free. JDBC, Flyway, and
Testcontainers do not enter the application or domain modules.

The execution boundary is:

1. validate the canonical action and verify the request-bound permit;
2. in one short transaction, insert the tenant-scoped permit claim, execution
   attempt, initial transition, evidence event, and outbox record;
3. commit and return `STARTED` before invoking the simulator;
4. invoke the simulator without a database transaction;
5. in a second short transaction, append a confirmed success, confirmed denial
   (`failed` in the frozen execution-attempt vocabulary), or unknown outcome;
6. reconcile an unknown attempt by appending a new transition and evidence
   event without changing the earlier unknown record.

The application supplies a `TrustedTenantContext` separately from the action
body. The adapter includes tenant ID in permit, attempt, transition, evidence,
and outbox keys and foreign keys. Request-body client claims cannot select the
storage partition.

PostgreSQL uniqueness on `(tenant_id, permit_id)` is the permit replay
linearization point. Uniqueness on `(tenant_id, adapter_id, idempotency_key)`
prevents a second logical execution. Conflicting inserts roll back the whole
claim transaction before the adapter reports replay or an idempotent duplicate.
There is no production-shaped fallback to the process-local stores.

Evidence contains only bounded IDs, hashes, policy provenance, reason/status,
timestamps, and credential fingerprints. It does not contain compact permits,
source attestations, tokens, raw arguments, raw results, private keys, or
reusable credentials. Each append advances a tenant stream sequence and hash,
and creates an outbox identity in the same transaction.

Flyway migration validation plus a repository schema-contract guard fail
startup for malformed or incompatible state. Unknown lifecycle values fail
Java validation and PostgreSQL constraints.

## Database enforcement

The initial migration provides:

- tenant-scoped primary/unique keys and composite foreign keys;
- bounded identifier and SHA-256 checks;
- a transition ledger allowing only `executing -> succeeded|failed|unknown`
  and `unknown -> succeeded|failed`;
- a database trigger that serializes and applies monotonic transitions;
- append-only triggers for transitions and evidence rows;
- a locked evidence-stream head for sequence and previous-hash ordering; and
- one tenant-scoped outbox record per evidence event.

Database constraints are a second enforcement layer, not authenticated
identity evidence and not proof that an application-produced event is true.

## Failure semantics

- Claim/migration/schema failure before dispatch: fail closed; target not called.
- Target confirms rejection/no effect: append `failed` with a bounded reason.
- Target response is not trustworthy or is lost: append `unknown`; do not retry
  blindly.
- Completion persistence fails after dispatch: do not report success. The
  caller receives `DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH`; later operational
  recovery is required.
- Reconciliation appends; it never rewrites the original uncertainty.

## Rejected alternatives

### Check then insert

Rejected because concurrent replicas can both observe an unused permit. The
unique insert is the atomic claim.

### Keep the transaction open during target execution

Rejected because it cannot make a PostgreSQL transaction atomic with an
external API and would retain locks during unpredictable I/O.

### Fall back to memory when PostgreSQL fails

Rejected because each replica would regain independent replay state precisely
when the durable control is unavailable.

### Persist raw requests or target responses for recovery

Rejected because durability is not permission to create a second sensitive
data store. Recovery uses identifiers, hashes, bounded projections, and
target-side idempotency/lookup where available.

## Consequences and limits

The Java simulator path now has restart- and replica-safe permit claims,
idempotency, transitions, evidence, and outbox state when explicitly composed
with PostgreSQL. Existing in-memory adapters remain for the local POC only.

The outbox publisher, stale-attempt reconciler, external integrity anchor,
retention/partitioning, encryption operations, production credential custody,
live OAuth transport/tenant authorization and real target adapters remain future
work. A locally valid database hash chain still does not prove semantic truth
or resist an administrator who can replace the whole database and its head.
