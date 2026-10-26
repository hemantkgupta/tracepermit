# Workload replay protection is not execution admission

The durable workload-identity replay store ([ADR 0080](../architecture/0080-durable-workload-identity-replay.md)) guarantees that an authenticated workload credential (such as an OIDC access token fingerprint presented at an ingress boundary) can be consumed at most once across all nodes and restarts.

Consuming a token at this boundary is necessary, but it does **not** grant execution admission or establish authorization. Three fundamental non-equivalences govern this boundary:

## 1. Single-use token consumption is not application authority

- Calling `consume(tenantId, issuer, tokenId, expiresAt, now)` proves only that the specific token fingerprint has not been presented previously within its valid lifetime.
- It does **not** evaluate access policy, verify human approval, grant an execution permit, or authorize target side effects.
- A caller presenting a fresh token must still undergo policy evaluation, exact parameter binding, and independent authorization before any action is permitted.

## 2. Replay consumption and operation registration remain separate transactions

- The replay claim transaction and the subsequent business-operation registration transaction are deliberately separate:
  - If a subsequent registration transaction fails (e.g. due to payload conflict, schema invalidity, or storage timeout), the presented token has already been consumed and cannot be reused.
  - A client wishing to retry a failed registration with identical business parameters must obtain and present a fresh, unconsumed workload token.
- This decoupling prevents token reuse across distinct registration attempts and ensures that replay state is not held open across long-running or distributed operation workflows.

## 3. Finite capacity must fail closed, never evict live claims

- In caching systems, reaching capacity typically triggers Least Recently Used (LRU) or Least Frequently Used (LFU) eviction.
- In a security replay store, evicting an unexpired claim to admit new work would open a critical vulnerability: an attacker could flood the store to evict a previously used token, and then successfully replay the old token.
- Therefore, the replay store enforces a strict invariant: **an unexpired entry is never evicted**.
- When the store reaches its configured capacity ceiling, fresh submissions fail closed (`return false`), preserving all existing replay protections until an operator archives or resets the store.

## 4. Wait-induced expiry invalidates consumption

- Even if a token is valid when `consume(...)` is initially invoked, downstream lock contention (e.g. waiting for the counter row lock) or storage latency can cause real elapsed time to cross token expiry before commit.
- The replay store enforces post-wait validity checks using caller-trusted monotonic elapsed time against the unchanged token `expiresAt`.
- If lock wait or query delays push time to or past expiry before transaction commit, the transaction rolls back and consumption fails closed (`return false`), ensuring an expired token is never recorded as freshly consumed.
- If commit acknowledges after expiry or cleanup delays past expiry, the store raises `REPLAY_COMMIT_UNKNOWN` rather than returning affirmative consumption, preventing caller admission on ambiguous state.

## 5. Agreed capacity and physical schema attestation

- The store does not dynamically resize or negotiate capacity per-node: capacity is persisted in the database schema (`capacity` column in `tp_workload_replay_state`) and agreed across all instances. Any mismatch between local configuration and persisted schema capacity fails closed on store startup (`REPLAY_CAPACITY_MISMATCH`).
- Flyway history table integrity alone does not attest schema safety. Physical table columns, types, nullability, collations (`"C"` on PostgreSQL, `ascii_bin` on MariaDB), and primary keys are attested directly from physical database metadata to prevent schema drift or silent corruption from allowing unauthorized admission.
