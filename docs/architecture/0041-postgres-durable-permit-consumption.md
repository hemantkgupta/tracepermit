# ADR 0041 — PostgreSQL-backed standalone permit consumption

- Status: implemented simulator-only Java adapter; production composition pending
- Date: 2026-10-16
- Requirements: FR-008; SEC-005/006/009; REL-004; DATA-001

## Context

The existing durable execution and human-approval services already verify permits
and atomically insert consumed permits with execution state and evidence using
`PostgresDurableEnforcementState.claim` or `consumeAndClaim`. They do not call
`ExecutionPermitAuthority.verifyAndConsume`. Their previously injected in-memory
consumption port was unused on that path; replacing it is not a runtime replay fix.

A separate caller of `verifyAndConsume` needs a durable implementation of the
security port. It must bind the storage partition to the verified action and
never report a successful claim before transaction commit.

## Decision

1. Add `PostgresPermitConsumptionStore` using the existing V1–V6 schema.
   The existing table has `tenant_id`, `permit_id`, `expires_at`,
   `action_hash` and `claimed_at`. Insert uses
   `ON CONFLICT (tenant_id, permit_id) DO NOTHING`: one inserted row means
   consumed, zero means an existing claim. No migration is added or rewritten.
2. `verifyAndConsume(String tenantId, String permit, JsonNode event, String expectedAudience)`
   verifies signature, audience, time and exact action hash against a private
   action snapshot. An explicit tenant must be valid, non-null and equal the
   snapshot's subject organization. Otherwise `PERMIT_TENANT_MISMATCH` is raised
   before consumption. Tenant identity still originates at the trusted adapter;
   a body field is not authentication.
3. The original authority overload also routes using that verified organization,
   not the store's configured default. Switching overloads or defaults cannot
   create a second namespace for the same action. A missing/invalid organization
   fails closed in the PostgreSQL store. Legacy in-memory implementations retain
   their global replay namespace; the default port methods do not promise
   tenant-aware in-memory isolation.
4. Live durable consumption requires the verified canonical action hash.
   Missing/malformed hashes fail before connection acquisition. The legacy
   no-hash store overloads therefore reject live claims rather than fabricate an
   action hash from a permit ID. Expired permits return false without database
   mutation. Existing canonical contracts and signed permit bytes are unchanged.
5. Consumption and pruning own fresh/exclusive DataSource connection leases.
   Each write explicitly disables auto-commit, performs its bounded SQL, commits
   and restores the original mode before returning a result. This supports
   leases initially configured with either auto-commit mode. It does not join
   caller-owned/thread-bound transactions.
6. SQL or unchecked work failures attempt rollback and propagate failure.
   A rollback failure is suppressed on the original exception; auto-commit is
   not enabled afterward because that could commit pending work. SQL acquisition,
   commit or close errors are wrapped in `PostgresStateException`; none returns
   success. No automatic retry occurs.
7. If commit succeeded but its acknowledgement or connection close failed, the
   row may already exist. Fail closed and inspect state; never infer that the
   claim was absent or blindly redispatch. This may sacrifice availability but
   cannot authorize an effect based on a reported successful return that did not
   occur.
8. `pruneExpiredUnreferenced` commits its deletion and excludes both execution
   and approval references using their tenant/permit foreign keys. It deletes
   only expired rows; it does not remove active replay protection.
9. Durable launchers/fixtures can receive this adapter, but the actual composed
   execution path remains the existing atomic state transaction. Never
   pre-consume through this standalone adapter before that transaction: it would
   either collide with the same permit row or split claim/evidence atomicity.

## Scope and limits

- This is a trusted internal Java adapter over disposable/local PostgreSQL and
  simulator targets, not a new authenticated remote API or a real executor.
- Row uniqueness and tenant-filtered SQL are not a sandbox against a database
  administrator or malicious same-JVM code. No database RLS deployment is claimed.
- Standalone consumption records a claim only, not an execution attempt or
  evidence transaction. The composed durable services remain the authority for
  those atomic workflows.
- Store reconstruction tests reconnect to the same running database; they are
  not OS crash/reboot, replication or production database durability tests.
- Production key custody, downstream credentials, target idempotency and
  anti-bypass remain separate requirements. No deny/approval rule is relaxed.

## Verification

`PostgresPermitConsumptionStoreTest` covers explicit hashes, exact expiry,
row uniqueness, 32 tasks on 16 workers/four store instances, store reconstruction,
tenant-key separation, foreign-key-aware pruning and SQL failure wrapping.

`PostgresPermitConsumptionBoundaryTest` adds real-PostgreSQL regressions for
cross-tenant permit reuse, invalid/missing tenant, overload/default switching,
changed actions, manual-transaction commit, missing action provenance, commit
failure, lost acknowledgement, rollback/close/acquisition failure, unchecked
work failure and pruning commit/rollback. Failed/uncertain claim acknowledgements
produce zero local test effects. These are bounded synthetic tests, not a claim
of complete distributed failure coverage.

The existing durable approval/API tests and simulator Scenarios 3 and 8 must
continue to prove mutation/replay rejection and one approved effect without
changing their atomic execution-claim path.
