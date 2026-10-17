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
   - Joining callers validate the resolved key entry against their own trusted time (`now`); if the coalesced refresh result has already expired at the joining caller's trusted time, the caller fails closed (`OIDC_KEY_SET_UNAVAILABLE`) rather than reusing expired keys or resetting the waiter budget.
   - Before executing network fetch, the coordinator re-checks the cache to avoid redundant network calls if a concurrent in-flight refresh completed immediately prior.

3. **Bounded Refresh Capacity and Direct Active-Worker Admission**:
   - Bounded concurrent refresh workers (default 4 workers) and pending queue capacity (default 16 tasks) using a dedicated daemon `ThreadPoolExecutor`.
   - Configured active workers (`corePoolSize == maxConcurrentRefreshes == 4`) so intended active capacity is fully used before tasks queue behind running operations; idle threads are reaped via `allowCoreThreadTimeOut(true)`.
   - Saturated refresh capacity immediately rejects new refresh submissions with `RejectedExecutionException` and fails closed with `AuthenticationException("OIDC_KEY_SET_UNAVAILABLE")`.
   - When running tasks complete, capacity is restored, allowing subsequent refreshes to proceed without process restart.

4. **Operation-Level Failure Cooldown and Late-Result Discarding**:
   - Failure cooldown bookkeeping belongs directly to the refresh operation itself, tracked race-safely via monotonic operation sequence IDs.
   - Successful operations record their sequence ID atomically (`lastSuccessfulOperationId`); an older operation completing late (such as an interrupted or queued task) is strictly recognized as obsolete and rejected, preventing stale cooldown reinstallation after newer success.
   - Refreshes that fail (due to network error, parse error, queue timeout, or late completion exceeding the monotonic deadline budget) record a provider failure cooldown (`max(provider.forcedRefreshCooldown(), failureCooldown)`, minimum 10 seconds), keyed from the latest joining caller's trusted time.
   - Failure cooldowns are recorded even if all waiting caller threads timed out or were interrupted prior to task completion.
   - Older late-failing operations cannot clobber newer successful operations or newer cooldowns. Transient capacity rejections (`RejectedExecutionException`) recover immediately and do not install cooldowns.
   - Refreshes that exceed their monotonic deadline budget discard late results: late results are marked exceptional, never published into cache, and never extend cache lifetime.
   - Monotonic cache updates (`updateCacheIfMonotonic`) prevent an older or out-of-order fetch from overwriting a newer cache entry. Expired keys are never accepted as fallback.

5. **Bounded Pre-Call DNS and Transport Stage Deadlines**:
   - Introduced `BoundedHostResolver` in `io.tracepermit.adapters.remote` with bounded workers (default 8) and bounded queue (default 16), configured with `corePoolSize == maxWorkers` to utilize full worker capacity before queueing. The default DNS host resolver (`BoundedHostResolver.defaultResolver()`) is shared across source instances unless an explicit resolver instance is supplied.
   - If DNS resolution is attempted while worker capacity is saturated, it immediately fails closed with `JwksFetchException`.
   - In `SsrfSafeHttpsJwkSetSource`, monotonic elapsed time (`System.nanoTime()`) tracks the overall transport budget (`callTimeout`).
   - The DNS resolution stage runs with a deadline equal to the remaining call timeout budget. The elapsed DNS time is subtracted from the total budget, and the remaining duration is applied as the `callTimeout`, `readTimeout`, and effective `connectTimeout` for OkHttp. Total transport time across DNS and HTTP stages is bounded by the configured `callTimeout`.

6. **Time Re-sampling in Access Token Verification**:
   - In `OidcAccessTokenVerifier`, the trusted wall clock (`clock.instant()`) is sampled before calling `keys.resolve` and re-sampled after `keys.resolve` completes.
   - Claims validation (`validateClaims`) checks token expiration (`exp`) against the re-sampled post-resolution timestamp. Tokens that expire during a bounded key resolution wait fail closed with `EXPIRED_ACCESS_TOKEN`.
   - The resulting `AuthenticatedConnector` records the true post-resolution verification timestamp.

## Security and Invariants Preserved

- **SSRF and Pinning**: Operators must explicitly allowlist exact HTTPS URIs. Resolution prohibits private, local, reserved, loopback, multicast, or documentation IP addresses; validated addresses are pinned through connection establishment; proxies and redirects remain disallowed.
- **Strict Key Validation**: JWKS payload size is capped at 64 KB; JSON duplicate key detection and trailing token rejection are enforced; private key parameters are forbidden; RSA modulus must be at least 2048 bits; algorithm must be strictly RS256; key usage must be `sig`.
- **Zero Secret Leakage**: No bearer token, credential, key material, hostname, or upstream raw exception message is included in logs or exception messages.

## Consequences and Limitations

- **Instance-Local Resource Scopes**: Refresh coalescing, worker pools, and failure cooldowns are bounded per `CachingOidcJwkResolver`, `JwksRefreshCoordinator`, and `BoundedHostResolver` instance (the default DNS host resolver is shared across sources); multi-instance deployments do not share an in-memory refresh lock or distributed cache. Claims apply strictly to the configured local resolver and executor instances.
- **Shared Finite Capacity Across Providers**: While independent providers do not globally serialize on an instance monitor, background refreshes still share the coordinator's finite worker pool (4 workers, 16 queue slots) and DNS worker pool (8 workers, 16 queue slots). Multiple concurrently degraded providers can consume available worker slots until their respective deadlines expire, at which point saturated workers fail closed.
- **Timed Transport Stages**: The measured transport deadline strictly bounds pre-call DNS execution and the OkHttp connect/read/call exchange via monotonic `System.nanoTime()`. These timed stages bound the specific network transport operations; they do not constitute a universal hard wall-clock or real-time OS scheduling guarantee. Furthermore, standard Java threads cannot forcibly abort underlying un-interruptible operating system host resolution syscalls (`InetAddress.getAllByName`); if the OS resolver hangs, caller threads unblock at their monotonic deadline while the DNS worker completes or times out in the background within the bounded thread pool limit.
