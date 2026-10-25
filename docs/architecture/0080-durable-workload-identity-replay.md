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
4. Finite capacity with fail-closed saturation rather than unexpired entry eviction.
5. Strict acknowledgment semantics: `consume(...)` returns `true` only after durable commit and connection cleanup.

This store is strictly a credential replay boundary for verified workload tokens (such as ingress token fingerprints). It does **not** evaluate business-operation policies, grant execution permits, execute target actions, or verify human approvals.

## Decision

1. **Dedicated Module and Engine Parity**:
   Introduce a standalone `java/persistence-identity/` library module. It provides independent migrations for PostgreSQL (`16.4`) and MariaDB (`11.4.13` and `10.6.25`). It does not reuse, modify, or couple to existing application authority, permit consumption, or business-operation persistence schemas.

2. **Collision-Safe, Domain-Separated Namespace**:
   Compound keys (`tenantId`, `issuer`, `tokenId`) are hashed using a versioned, length-delimited recipe:
   $$\text{claim\_hash} = \text{sha256}("tracepermit:workload-identity-replay:v1\backslash0" \parallel \text{len}(t) \parallel t \parallel \text{len}(i) \parallel i \parallel \text{len}(k) \parallel k)$$
   All fields are length-prefixed as 4-byte big-endian integers over UTF-8 bytes. This prevents boundary-shifting collisions, delimiter concatenation vulnerabilities, and MariaDB `PADSPACE` collation anomalies. Inputs are strictly validated against maximum lengths (`tenantId` $\le 128$, `issuer` $\le 256$, `tokenId` $\le 256$) and invalid surrogate Unicode sequences without echoing raw input values.

3. **Privacy and Credential Boundary**:
   The store persists only cryptographic digests (`claim_hash`, `tenant_hash`, `token_fingerprint`) and microsecond wall-clock timestamps (`expires_at`, `claimed_at`). Raw authorization headers, bearer tokens, and private keys never touch the database.

4. **Plain Unique Insertion and Replay Refusal**:
   Consumption executes a plain SQL `INSERT` into `tp_workload_replay_claims` where `claim_hash` is the primary key. Only expected duplicate-key errors (PostgreSQL `23505`, MariaDB `1062` / `23000`) are treated as replay refusals (`return false`). Unrelated constraint, permission, or connection errors fail closed with `WorkloadIdentityReplayException`.

5. **Strict Expiry and No Expiry Refresh**:
   Submissions where $\text{expiresAt} \le \text{now}$ fail closed (`return false`) and write zero database rows. An identical duplicate submitted with a later expiration date is rejected and does not update or refresh the existing claim's `expires_at`.

6. **Finite Capacity and Fail-Closed Saturation**:
   To prevent unbounded storage growth and denial-of-service, the store enforces a configured capacity ceiling via a singleton counter row in `tp_workload_replay_state`. An atomic `UPDATE` condition (`total_claims = total_claims + 1 WHERE singleton = 1 AND total_claims < capacity`) ensures that when capacity is reached, the transaction rolls back the claim insert and refuses admission (`return false`) without evicting live entries.

7. **Commit and Connection Cleanup Acknowledgement**:
   A consumption attempt returns `true` only after successful database `commit()` **and** successful connection cleanup (`close()`). If a commit is attempted but acknowledgement is lost or fails during socket closure, the operation fails closed with `REPLAY_COMMIT_UNKNOWN`. All thrown exceptions discard JDBC causes and suppressed exceptions to prevent sensitive connection credentials or diagnostics from leaking.

8. **Strict Schema Lifecycle and Restricted Runtime**:
   Installation requires explicit `initializeEmpty(source)`. Runtime initialization is validate-only (`validateExisting(source)`): it verifies the exact three-table inventory (`tp_identity_replay_schema_history`, `tp_workload_replay_claims`, `tp_workload_replay_state`), checks for zero stored routines/triggers, confirms InnoDB table engines on MariaDB, and asserts writable database endpoints. Restricted runtime user roles are validated separately from migration owners.

## Rationale

- In-memory replay stores leave multi-instance ingress open to token replay across restarts and replica boundaries.
- Storing cryptographic fingerprints instead of raw credentials ensures compliance with security invariants and zero-leak requirements.
- Length-delimited domain separation eliminates delimiter injection and collation whitespace vulnerabilities.
- Finite capacity with fail-closed saturation preserves the invariant that unexpired claims are never evicted to admit new work.
- Dedicated schema isolation prevents unintended coupling between workload identity ingress and business-operation review state.

## Consequences

- Workload identity ingress (such as AgentCore pilot registration) can bind to a durable, shared replay store.
- Replay state survives service restarts and is shared across concurrent ingress instances.
- Replay cleanup and archival remain explicit operator runbook activities; the runtime store remains strictly append-only up to its configured capacity.
