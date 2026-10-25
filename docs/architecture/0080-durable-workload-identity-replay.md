# ADR 0080 — Durable workload-identity replay store

- Status: implemented and qualified across PostgreSQL 16.4 and MariaDB 11.4.13/10.6.25
- Date: 2026-10-25
- Requirements: FR-008, FR-011; SEC-006; REL-001, REL-004; OPS-004

## Context

The initial workload identity ingress foundation relied on `InMemoryWorkloadIdentityReplayStore`, a process-local in-memory store. In a distributed multi-node deployment or across service restarts, in-memory replay state does not survive: an attacker or rogue workload could replay a previously consumed credential against a restarted instance or across separate ingress replicas.

TracePermit requires a durable, shared implementation of `io.tracepermit.identity.WorkloadIdentityReplayStore` that operates across PostgreSQL and MariaDB database engines while strictly maintaining security invariants:

1. Atomic single-use consumption across racing store instances.
2. Collision-safe compound namespacing that resists delimiter-injection and PADSPACE text equality bugs.
3. Zero storage of raw credentials, bearer tokens, or headers: bounded cryptographic fingerprints only.
4. Finite capacity with agreed operator-persisted ceiling and fail-closed saturation rather than unexpired entry eviction.
5. Strict post-wait token validity checking against caller-trusted time to prevent acknowledging authority after lock waits cross expiry.
6. Strict acknowledgment semantics: `consume(...)` returns `true` only after durable commit and connection cleanup.
7. Zero diagnostic leakage: suppressed exceptions and causes are permanently disabled on runtime exceptions.

This store is strictly a credential replay boundary for verified workload tokens (such as ingress token fingerprints). It does **not** evaluate business-operation policies, grant execution permits, execute target actions, or verify human approvals.

## Decision

1. **Dedicated Module and Engine Parity**:
   Introduce a standalone `java/persistence-identity/` library module. It provides independent migrations for PostgreSQL (`16.4`) and MariaDB (`11.4.13` and `10.6.25`). It does not reuse, modify, or couple to existing application authority, permit consumption, or business-operation persistence schemas.

2. **Collision-Safe, Domain-Separated Namespace**:
   Compound keys (`tenantId`, `issuer`, `tokenId`) are hashed using a versioned, length-delimited recipe:
   $$\text{claim\_hash} = \text{sha256}("tracepermit:workload-identity-replay:v1\backslash0" \parallel \text{len}(t) \parallel t \parallel \text{len}(i) \parallel i \parallel \text{len}(k) \parallel k)$$
   All fields are length-prefixed as 4-byte big-endian integers over UTF-8 bytes. This prevents boundary-shifting collisions, delimiter concatenation vulnerabilities, and MariaDB `PADSPACE` collation anomalies. Inputs are strictly validated against maximum lengths (`tenantId` $\le 128$, `issuer` $\le 256$, `tokenId` $\le 256$) and invalid surrogate Unicode sequences without echoing raw input values.

3. **Privacy, Credential Boundary, and Suppressed Error Prevention**:
   The store persists only cryptographic digests (`claim_hash`, `tenant_hash`, `token_fingerprint`) and microsecond wall-clock timestamps (`expires_at`, `claimed_at`). Raw authorization headers, bearer tokens, and private keys never touch the database.
   All runtime exceptions throw `WorkloadIdentityReplayException`, which explicitly sets `enableSuppression = false`, overrides `initCause` to disallow causes, and retains only fixed code names. When DML errors and socket close failures coincide, sensitive error details and canaries are completely discarded.

4. **Plain Unique Insertion and Replay Refusal**:
   Consumption executes a plain SQL `INSERT` into `tp_workload_replay_claims` where `claim_hash` is the primary key. Only expected duplicate-key errors (PostgreSQL `23505`, MariaDB `1062` / `23000`) are treated as replay refusals (`return false`). Unrelated constraint, permission, or connection errors fail closed with `WorkloadIdentityReplayException`.

5. **Monotonic Post-Wait Expiry and Finite Statement Budgets**:
   Submissions where $\text{expiresAt} \le \text{now}$ fail closed (`return false`) and write zero database rows.
   To eliminate bypasses where prolonged lock waits cross token expiration, validity is rechecked using the caller's trusted `now` plus elapsed monotonic time ($\Delta t = \text{nanoTime}() - \text{startNanos}$). This check occurs after connection acquisition, after claim insertion, after counter update, after database commit, and after connection close.
   Statements are bounded with a finite `queryTimeout` derived from remaining time until expiry and a configured DML budget.
   If a wait crosses expiry before commit, the transaction rolls back cleanly with zero rows written and returns `false`. If commit completes after expiry or cleanup delays past expiry, the operation fails closed with `REPLAY_COMMIT_UNKNOWN`, ensuring authority never follows late execution.

6. **Agreed Operator-Persisted Capacity and Fail-Closed Saturation**:
   Capacity is persisted directly in the schema (`tp_workload_replay_state.capacity` in V2) and operator-agreed. Store instances validate that local configuration matches the persisted capacity; disagreement fails immediately with `REPLAY_CAPACITY_MISMATCH`.
   Atomic counter update (`UPDATE tp_workload_replay_state SET total_claims = total_claims + 1 WHERE singleton = 1 AND capacity = ? AND total_claims < capacity`) ensures that when capacity is reached, the transaction rolls back the claim insert and refuses admission (`return false`) without evicting live entries.

7. **Commit and Connection Cleanup Acknowledgement**:
   A consumption attempt returns `true` only after successful database `commit()` **and** successful connection cleanup (`close()`). If a commit is attempted but acknowledgement is lost or fails during socket closure, the operation fails closed with `REPLAY_COMMIT_UNKNOWN`.

8. **Strict Physical Schema Lifecycle and Restricted Runtime**:
   Installation requires explicit `initializeEmpty(source, capacity)`. Runtime initialization is validate-only (`validateExisting(source, capacity)`): it verifies the exact three-table inventory, checks every required column, data type, nullability, primary key, and collation (`"C"` on PostgreSQL, `ascii_bin` on MariaDB), confirms InnoDB table engines on MariaDB, and asserts writable database endpoints.
   Existing V1 schemas must be upgraded using explicit `upgradeFromV1(source, capacity)`. No automatic migration, adoption, or repair is permitted at runtime. Restricted runtime user roles are validated separately from migration owners.

## Rationale

- In-memory replay stores leave multi-instance ingress open to token replay across restarts and replica boundaries.
- Storing cryptographic fingerprints instead of raw credentials ensures compliance with security invariants and zero-leak requirements.
- Length-delimited domain separation eliminates delimiter injection and collation whitespace vulnerabilities.
- Rechecking monotonic validity after lock waits ensures that tokens expiring during contention are rejected and rolled back.
- Persisting and verifying agreed capacity prevents silent boundary resizing across disparate client configurations.
- Comprehensive physical schema validation guarantees that dropped or altered columns cannot silently degrade runtime enforcement.
- Finite capacity with fail-closed saturation preserves the invariant that unexpired claims are never evicted to admit new work.
- Dedicated schema isolation prevents unintended coupling between workload identity ingress and business-operation review state.

## Consequences

- Workload identity ingress (such as AgentCore pilot registration) can bind to a durable, shared replay store.
- Replay state survives service restarts and is shared across concurrent ingress instances.
- Replay cleanup and archival remain explicit operator runbook activities; the runtime store remains strictly append-only up to its configured capacity.
