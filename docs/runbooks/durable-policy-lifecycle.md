# Durable policy lifecycle foundation

Requirements: FR-013; REL-006; SEC-002/009; OPS-002.
See [ADR 0039](../architecture/0039-policy-lifecycle-staging-rollback-and-simulation.md) and [ADR 0042](../architecture/0042-durable-policy-generation-binding.md).

This is an optional trusted Java library over PostgreSQL, not an administrative
HTTP/MCP endpoint or a policy editor. Default backend/approval launchers retain
their static policies. All verification below uses disposable local PostgreSQL
and simulated targets. No deployment, real credentials or live client is required.

## Components and data

- `PolicyLifecycleService` delegates staging, queries, activation and rollback
  to `PolicyLifecycleStore`. The no-argument constructor remains in memory.
- `PostgresPolicyLifecycleStore` migrates the configured database and stores
  revisions in `tp_policy_versions`, active pointers in `tp_active_policies`, and
  append-only lifecycle history in `tp_policy_lifecycle_transitions`.
- Workspace plus policy ID plus exact positive Java int version identifies a
  revision. Repeating identical content is idempotent; differing content under
  that identity fails `POLICY_VERSION_IMMUTABLE`. Documents are copied on input
  and output. Versions are 1..2147483647; priorities are signed Java ints.
- Revision identity, display metadata, hash, document and staged time cannot be
  changed through ordinary UPDATE/DELETE. Lifecycle status/timestamps can change;
  reactivation clears obsolete deprecation/rollback times. History contains the
  previous and next version, ordered sequence, timestamp, actor and reason.
- Actor IDs and reasons are bounded caller tracking metadata, not authenticated
  operator provenance. Never pass credentials or sensitive customer data there.

## Transition and concurrency semantics

Stage v1 and v2, activate v1, activate v2 with expected v1, then rollback with
expected v2. Automatic rollback restores the preceding activation; repeating it
toggles to the immediately preceding version, rather than walking backward
through numerical versions. Explicit rollback selects a stored version.

Each durable activation/rollback takes a transaction-scoped workspace/policy
advisory lock **before** checking the active pointer. SELECT FOR UPDATE alone
cannot lock an absent row on first activation. A five-second query timeout bounds
advisory lock acquisition; hash collisions only serialize unrelated namespaces.
Revision state, pointer and history change in one committed transaction.

`expectedActiveVersion` checks the current version while locked. Null requests
an unconditional serialized transition. This is not a generation-based/ETag
precondition: v1 → v2 → v1 can again satisfy expected v1. The monotonic sequence
records transition order but is not accepted as a caller precondition. Do not
describe it as ABA-resistant or authenticated administrative concurrency.

The store owns a fresh/exclusive DataSource lease and READ COMMITTED transaction;
caller-owned/thread-bound transactions are unsupported. It commits before success
and restores original isolation/auto-commit modes after success. SQL and unchecked
failures explicitly roll back, preserving rollback failure as suppressed context.
It never enables auto-commit after failure or nests another lease for idempotent
activation. Application workspace filters do not establish database RLS or grant
a caller permission to select another workspace.

## Schema upgrade and failure handling

Constructing the PostgreSQL store automatically runs Flyway migrations. V7 adds
the policy tables/triggers and V8 adds `tp_approval_policy_bindings`, advancing
the existing enforcement schema guard to 8 without deleting approval, execution,
permit or evidence rows. A real-PostgreSQL regression upgrades a V6 fixture and
verifies its prior permit claim survives. Existing V1–V7 migrations are unchanged.

Use the matching application/schema version; old binaries expecting V6 cannot
use V7. The policy store also rejects an unknown or missing schema guard at
startup. This is not a rolling-deployment compatibility promise. Do not manually
edit Flyway checksums, reset an operator database or delete state to fix an error.
An earlier unmerged V7 development fixture may need recreation **only if it is
explicitly disposable**; preserve any wanted data first.

Database, lock, transaction, commit or connection-close failure must not be
treated as an activation success. A lost commit acknowledgement or a subsequent
mode-restoration/close failure may occur after pointer and history were committed.
Inspect active state and history through a fresh connection, then choose the next
operator action. No automatic transition retry or target effect is performed.

## Reproduce verification

Prerequisites: Java 21, Node 22+, pnpm 11.25.0 and running Docker.

```bash
pnpm install --frozen-lockfile
pnpm check
./scripts/run-java-postgres-tests.sh --rerun-tasks
pnpm test:approval-api --rerun-tasks
pnpm test:endpoint-connector-e2e
pnpm demo:scenario:approval:auto
pnpm demo:scenario:production-deletion:auto
pnpm demo:gate-c
pnpm demo:verify
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
```

`PostgresPolicyLifecycleStoreTest` covers restart, immutable content/hash,
numeric bounds, workspace isolation, 32-way precondition contention, atomic
database failure, rollback and dynamic allow/block/simulator composition.
`PostgresPolicyLifecycleBoundaryTest` adds 16 tests for first-activation
interleaving, unchecked failures, single-lease idempotence, returned/persisted
metadata, transaction modes and ambiguous failures, concurrent restaging,
workspace mutations, SQL immutability, V6-to-V7 upgrade and incompatible/missing
schema-guard rejection. `PostgresPolicyBoundApprovalTest` verifies durable
approval policy generation binding, rejection of stale approvals across activation
and rollback, fail-closed handling of unbound approvals, and concurrent claim/activation
serialization.

The approval and numbered scenario reruns establish compatibility of the existing
static-policy compositions, not durable policy administration wiring. Invalidation
of pending approvals after durable policy activation or rollback is verified for
simulator approvals via generation-bound claim checks (ADR 0042); authenticated policy
roles/API/UI, external evidence anchoring, database-admin protection and
distributed rollout remain unqualified. No production-readiness or latency
claim follows from this library's tests.
