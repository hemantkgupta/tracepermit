# ADR 0049 — Bounded OIDC/JWKS Refresh Isolation and Total Transport Deadlines

- Status: implemented in Java identity and remote adapters
- Requirements: SEC-002, SEC-003, SEC-015, SEC-017, REL-001, REL-009, OPS-001, OPS-005

## Context

Prior to this change, `CachingOidcJwkResolver.resolve` synchronized on the resolver instance monitor (`public synchronized RSAPublicKey resolve(...)`) across all providers while `OidcJwkSetSource.fetch` performed network I/O. A slow or hanging network or DNS operation for Provider A globally blocked resolution for Provider B, even when Provider B's verification keys were warm, valid, and unexpired in memory.

Additionally, in `SsrfSafeHttpsJwkSetSource`, pre-call DNS resolution and validation (`resolveAndValidate(uri.getHost())`) occurred synchronously before the `OkHttpClient` instance was built and executed. Consequently, OkHttp's `callTimeout` governed only the HTTP transmission and did not cover the pre-call DNS resolution stage, leaving total transport elapsed time unbounded if the system resolver hung or was delayed. Furthermore, operating system DNS resolution (`InetAddress.getAllByName`) does not respect Java thread interruption; unconstrained asynchronous worker creation under slow or hanging DNS could exhaust JVM threads and system resources.

## Decision

1. **Per-Provider Concurrency and Lock-Free Warm Resolution**:
   - Removed the instance-wide `synchronized` monitor from `CachingOidcJwkResolver.resolve`.
   - Stored cached keys in a `ConcurrentHashMap` keyed by `issuer + "\u0000" + jwksUri`.
   - Requests for warm, unexpired keys resolve immediately without acquiring locks or awaiting network operations. Different provider configurations sharing a resolver do not globally serialize.

2. **In-Flight Refresh Coalescing and Monotonic Waiter Budgets**:
   - Introduced `JwksRefreshCoordinator` in `io.tracepermit.identity` to manage background key set refreshes.
   - Concurrent requests for the same provider configuration coalesce onto a single in-flight `CompletableFuture`.
   - Waiters await the shared in-flight refresh using a monotonic deadline budget (`waiterTimeout`, default 5 seconds). Waiters handle interruption cleanly (`InterruptedException` restores the thread interrupt flag) and fail closed with `AuthenticationException("OIDC_KEY_SET_UNAVAILABLE")`.
   - Before executing network fetch, the coordinator re-checks the cache to avoid redundant network calls if a concurrent in-flight refresh completed immediately prior.

3. **Bounded Refresh Capacity and Fail-Closed Saturated Admission**:
   - Bounded concurrent refresh workers (default 4 workers) and pending queue capacity (default 16 tasks) using a dedicated daemon `ThreadPoolExecutor`.
   - Saturated refresh capacity immediately rejects new refresh submissions with `RejectedExecutionException` and fails closed with `AuthenticationException("OIDC_KEY_SET_UNAVAILABLE")`.
   - When running tasks complete, capacity is restored, allowing subsequent refreshes to proceed without process restart.

4. **Failure Cooldown and Late-Result Discarding**:
   - Refreshes that fail (e.g., network unavailability or malformed key set) record a provider failure cooldown (`max(provider.forcedRefreshCooldown(), failureCooldown)`, minimum 10 seconds). Subsequent requests needing refresh during cooldown fail fast with `OIDC_KEY_SET_UNAVAILABLE` without hammering upstream endpoints.
   - Refreshes that exceed their monotonic deadline budget discard late results: late results are marked exceptional, never published into cache, and never extend cache lifetime.
   - Monotonic cache updates (`updateCacheIfMonotonic`) prevent an older or out-of-order fetch from overwriting a newer cache entry. Expired keys are never accepted as fallback.

5. **Bounded Pre-Call DNS and Total Transport Elapsed Deadlines**:
   - Introduced `BoundedHostResolver` in `io.tracepermit.adapters.remote` with bounded workers (default 8) and bounded queue (default 16).
   - If DNS resolution is attempted while worker capacity is saturated, it immediately fails closed with `JwksFetchException`.
   - In `SsrfSafeHttpsJwkSetSource`, monotonic elapsed time (`System.nanoTime()`) tracks the overall transport budget (`callTimeout`).
   - The DNS resolution stage runs with a deadline equal to the remaining call timeout budget. The elapsed DNS time is subtracted from the total budget, and the remaining duration is applied as the `callTimeout`, `readTimeout`, and effective `connectTimeout` for OkHttp. Total transport time across DNS and HTTP stages is strictly bounded by the configured `callTimeout`.

6. **Time Re-sampling in Access Token Verification**:
   - In `OidcAccessTokenVerifier`, the trusted wall clock (`clock.instant()`) is sampled before calling `keys.resolve` and re-sampled after `keys.resolve` completes.
   - Claims validation (`validateClaims`) checks token expiration (`exp`) against the re-sampled post-resolution timestamp. Tokens that expire during a bounded key resolution wait fail closed with `EXPIRED_ACCESS_TOKEN`.
   - The resulting `AuthenticatedConnector` records the true post-resolution verification timestamp.

## Security and Invariants Preserved

- **SSRF and Pinning**: Operators must explicitly allowlist exact HTTPS URIs. Resolution prohibits private, local, reserved, loopback, multicast, or documentation IP addresses; validated addresses are pinned through connection establishment; proxies and redirects remain disallowed.
- **Strict Key Validation**: JWKS payload size is capped at 64 KB; JSON duplicate key detection and trailing token rejection are enforced; private key parameters are forbidden; RSA modulus must be at least 2048 bits; algorithm must be strictly RS256; key usage must be `sig`.
- **Zero Secret Leakage**: No bearer token, credential, key material, hostname, or upstream raw exception message is included in logs or exception messages.

## Consequences and Limitations

- **Process-Local Scope**: Refresh coalescing, worker pools, and failure cooldowns are bounded per JVM instance; multi-instance deployments do not share an in-memory refresh lock or distributed cache.
- **Un-interruptible OS Resolver**: Because Java's underlying OS host resolution does not abort on thread interruption, a worker thread executing `InetAddress.getAllByName` may remain blocked until the OS-level resolver timeout expires. However, because worker counts are strictly bounded (`maxWorkers`), thread accumulation is prevented, and caller threads unblock at their monotonic deadline.
