# Durable policy lifecycle foundation

Requirements: FR-007/008/013; REL-004/006; SEC-001/002/005/006/009; OPS-002.
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
verifies its prior permit claim survives. A populated V7-to-V8 fixture also
checks preservation of existing table row counts. These are bounded migration
regressions, not a production backup/restore qualification. Existing V1–V7
migrations are unchanged.

Use the matching application/schema version; binaries expecting V6 or V7 cannot
use the V8 schema. The policy store also rejects an unknown or missing schema guard at
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
serialization. `PostgresPolicyGenerationBoundaryTest` adds eight forced
interleaving/fault regressions. To run just that class after installing dependencies:

```bash
source scripts/configure-java-environment.sh
java/gradlew -p java :persistence-postgres:test --tests io.tracepermit.persistence.postgres.PostgresPolicyGenerationBoundaryTest
```

Interpret the boundary results precisely:

- **Claim first:** a real PostgreSQL lock wait proves activation cannot commit
  while the claim holds the active-policy share lock. The committed claim is
  not retroactively cancelled by a later activation.
- **Activation first:** the waiting claim sees the new generation and returns
  `STALE_POLICY_GENERATION`; approval, permit consumption, execution attempt and
  approval evidence remain unchanged by the rejected claim.
- **Java precheck race:** one internal permit can be minted after a concurrent
  activation, but the atomic database claim rejects it before consumption or
  target dispatch. Do not report unconditional zero permits minted.
- **Legacy compatibility:** the durable service rejects unbound approvals, and
  a saved binding is checked even if claim context is omitted. An unbound raw
  claim with no policy context retains trusted static-mode compatibility; the
  database does not infer a global durable-mode setting from an active policy.
- **Lost commit acknowledgement:** a committed claim stays consumed while the
  service dispatches nothing after the injected error. Do not retry it as new
  authority or infer a real-world target outcome from this synthetic fault test.

Stale rows keep their pending/approved status; rejection does not automatically
cancel them. Use fresh review under the current generation. No test prints
credentials, enables a real target or requires a model invocation.

The approval and numbered scenario reruns establish compatibility of the existing
static-policy compositions, not durable policy administration wiring. Invalidation
of pending approvals after durable policy activation or rollback is verified for
simulator approvals via generation-bound claim checks (ADR 0042); authenticated policy
roles/API/UI, external evidence anchoring, database-admin protection and
distributed rollout remain unqualified. No production-readiness or latency
claim follows from this library's tests.
