# ADR 0041 — PostgreSQL-backed durable permit consumption store

- Status: Accepted for the simulator-only POC
- Date: 2026-10-16
- Requirements: FR-008, SEC-006, REL-004, OPS-005

## Context

TracePermit requires that every policy-approved canonical action receives a short-lived,
request-bound execution permit that can be consumed at most once before dispatching to an
execution target (SEC-006, FR-008).

While PostgreSQL durable enforcement state (`DurableApprovalActionState` and Flyway V1–V6)
persisted approval states, execution attempts, and audit events, the permit consumption
boundary (`PermitConsumptionStore`) in runtime servers (`SimulatorApprovalMain` and
`DevelopmentBackendMain`) historically used `InMemoryPermitConsumptionStore`.

In an in-memory store, permit consumption state is process-local and volatile:

1. **Replay Across Restarts:** If an unexpired permit was consumed and the process restarted
   prior to permit expiration, the in-memory state was lost, permitting replay of the same
   unexpired execution permit.
2. **Multi-Instance Contention:** In multi-replica or multi-threaded scenarios, a process-local
   store cannot guarantee single-use consumption across distributed instances.

The PostgreSQL schema already established the table `tp_consumed_execution_permits` with columns:
`(tenant_id, permit_id, action_hash, consumed_at, expires_at)`.
However, there was no first-class `PermitConsumptionStore` implementation backed by this table,
leaving a durability gap in PostgreSQL-backed runtimes.

## Decision

1. **Implement `PostgresPermitConsumptionStore`:**
   Create `PostgresPermitConsumptionStore` in `io.tracepermit.persistence.postgres`, implementing
   the `PermitConsumptionStore` interface from `io.tracepermit.security`.
   - Backed by `tp_consumed_execution_permits`.
   - Uses `INSERT INTO tp_consumed_execution_permits (tenant_id, permit_id, action_hash, consumed_at, expires_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id, permit_id) DO NOTHING`.
   - Returns `true` only if exactly 1 row was inserted; returns `false` (indicating already consumed or race loss) if 0 rows were inserted.
   - Rejects expired permits fail-closed: if `!expiresAt.isAfter(now)`, returns `false` immediately without database modification.

2. **Interface Extension with Backward Compatibility:**
   Extend `PermitConsumptionStore` with overloaded default methods:
   - `boolean consume(String permitId, Instant expiresAt, String actionHash, Instant now)`
   - `boolean consume(String tenantId, String permitId, Instant expiresAt, Instant now)`
   - `boolean consume(String tenantId, String permitId, Instant expiresAt, String actionHash, Instant now)`
     `InMemoryPermitConsumptionStore` remains available for lightweight ephemeral unit tests and
     inherits these default methods cleanly.

3. **Authority Forwarding of Action Hash & Tenant:**
   Update `ExecutionPermitAuthority`:
   - In `verifyAndConsume`, extract the verified `action_hash` claim from the permit and pass it
     to `PermitConsumptionStore.consume(permitId, expiresAt, actionHash, now)`.
   - Add tenant-aware overload `verifyAndConsume(String tenantId, ExecutionPermit permit, ActionEvent event, String expectedAudience)`.

4. **Foreign Key-Aware Safe Pruning:**
   Provide `int pruneExpiredUnreferenced(Instant now)` on `PostgresPermitConsumptionStore`.
   Because `tp_consumed_execution_permits` is referenced as a foreign key by:
   - `tp_execution_attempts (tenant_id, permit_id)`
   - `tp_approval_requests (tenant_id, consumed_permit_id)`
     The pruning query deletes only rows where `expires_at < now` AND neither `tp_execution_attempts`
     nor `tp_approval_requests` reference the permit.

5. **Runtime and Fixture Wiring:**
   Replace `InMemoryPermitConsumptionStore` with `PostgresPermitConsumptionStore` in:
   - `SimulatorApprovalMain` (the production-like durable loopback approval server)
   - `DevelopmentBackendMain` (when configured with PostgreSQL)
   - `SimulatorApprovalServerTest` and `SimulatorApprovalBrowserFixture`
   - Production deletion and request-bound approval demo mains and durable state tests.

## Interpretation and Limits

- Single-use consumption in `tp_consumed_execution_permits` prevents replay across process
  restarts and ensures atomic concurrency isolation under heavy multi-threaded contention (verified up to 32 concurrent threads).
- Multi-tenant isolation is enforced via the `(tenant_id, permit_id)` primary key: consumption of a permit in one tenant has zero effect on other tenants.
- This store provides durable persistence for execution permits in PostgreSQL runtimes; it does
  not eliminate the separate requirement for downstream target idempotency keys or physical target anti-bypass.
- Expired permits are rejected fail-closed without an INSERT.
- All database errors are caught and wrapped in `PostgresStateException` to maintain fail-closed semantics across storage faults.

## Verification

- `PostgresPermitConsumptionStoreTest`:
  - Atomic single consumption and replay rejection.
  - 32-way concurrent race where exactly one thread succeeds and 31 threads receive `false`.
  - Durability across store instance restarts (simulating process crash/restart).
  - Expired permit rejection without database insertion.
  - Action hash derivation and format validation (`^sha256:[a-f0-9]{64}$`).
  - Multi-tenant isolation: identical permit IDs under different tenants are independent.
  - Safe pruning of expired unreferenced permits while preserving foreign-key-referenced permits.
  - End-to-end integration with `ExecutionPermitAuthority`.
  - Input validation (null/blank tenant, permit ID format, invalid action hashes).
  - Database failure handling wrapped in `PostgresStateException`.
- Existing durable approval, browser fixture, and integration tests continue to pass without regression.
