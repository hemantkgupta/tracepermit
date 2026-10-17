# JWKS Refresh Isolation and Transport Safety

Requirements (bounded slice): SEC-002, SEC-003, SEC-015, SEC-017; REL-001, REL-009; OPS-001, OPS-005. See [ADR 0049](../architecture/0049-bounded-jwks-refresh-isolation.md).

This runbook documents the operational controls, concurrency boundaries, failure modes, and recovery behaviors for TracePermit's OIDC verification key resolver and SSRF-safe HTTPS JWKS transport.

## Concurrency and Transport Bounds

The OIDC key resolution path separates cache lookups from network I/O, isolates independent identity providers, coalesces concurrent refreshes for the same provider, and enforces strict bounded-resource ceilings.

| Parameter                | Default    | Component                   | Description                                                                        |
| ------------------------ | ---------- | --------------------------- | ---------------------------------------------------------------------------------- |
| `maxConcurrentRefreshes` | 4          | `JwksRefreshCoordinator`    | Maximum active background JWKS refresh tasks across providers.                     |
| `maxPendingRefreshes`    | 16         | `JwksRefreshCoordinator`    | Maximum queued refresh tasks before fail-closed rejection.                         |
| `waiterTimeout`          | 5 seconds  | `JwksRefreshCoordinator`    | Monotonic deadline budget for caller threads awaiting in-flight refresh.           |
| `failureCooldown`        | 10 seconds | `JwksRefreshCoordinator`    | Minimum suppression window after fetch failure before retrying upstream.           |
| `maxDnsWorkers`          | 8          | `BoundedHostResolver`       | Maximum active worker threads executing pre-call DNS resolution.                   |
| `maxDnsPending`          | 16         | `BoundedHostResolver`       | Maximum queued DNS resolution tasks before fail-closed rejection.                  |
| `callTimeout`            | Up to 10s  | `SsrfSafeHttpsJwkSetSource` | Total monotonic budget spanning DNS pre-resolution, connection, and HTTP response. |

## Failure Modes and Fail-Closed Behavior

1. **Warm Key Cache Hits (Zero Serialization)**:
   - Known unexpired keys resolve directly from the in-memory cache without locking or blocking behind unrelated provider network I/O.
   - Provider A experiencing network degradation does not block or slow Provider B resolution.

2. **In-Flight Coalescing and Caller Freshness Validation**:
   - Multiple concurrent callers requesting an unknown key or expired key set for the same provider coalesce onto a single in-flight fetch task.
   - Waiters await the task with a monotonic timeout (`waiterTimeout`). Waiters that are interrupted restore thread interrupt status and fail closed.
   - Joining callers validate the coalesced entry against their own trusted time (`now`); if the result has expired relative to the joining caller, the request fails closed (`OIDC_KEY_SET_UNAVAILABLE`) rather than accepting expired keys or resetting the waiter timeout.

3. **Direct Active-Worker Admission and Capacity Saturation (Fail Closed)**:
   - Worker pools configure `corePoolSize` equal to maximum workers (4 for JWKS refresh, 8 for DNS) so all active capacity is utilized before tasks queue behind running operations; idle threads time out via `allowCoreThreadTimeOut(true)`.
   - The default DNS host resolver (`BoundedHostResolver.defaultResolver()`) is shared across source instances. While independent providers do not globally serialize, they share the finite worker and queue pools of their coordinator and DNS resolver; slow or degraded providers can consume available worker slots until their respective deadlines expire.
   - When refresh worker threads and queue slots are fully occupied, new refresh requests are rejected immediately with `RejectedExecutionException`.
   - Saturated workers fail closed with `AuthenticationException("OIDC_KEY_SET_UNAVAILABLE")` without blocking callers indefinitely.
   - Once running tasks complete, capacity is restored and subsequent refreshes succeed without process restart.

4. **Operation-Level Failure Storm Prevention (Cooldown)**:
   - Failure cooldowns are recorded directly on the refresh operation itself, tracked race-safely via monotonic operation sequence IDs.
   - Successful operations atomically record their sequence ID (`lastSuccessfulOperationId`); any older operation completing late (such as an interrupted or queued task) is recognized as obsolete and rejected, preventing stale cooldown reinstallation after newer success.
   - If a key set refresh fails due to network outage, DNS failure, timeout, or malformed JWKS payload, a failure cooldown is recorded: `max(provider.forcedRefreshCooldown(), failureCooldown)` from the latest joining caller's trusted time.
   - Failure cooldowns are installed even if all waiting caller threads timed out or were interrupted before the background task completed.
   - Subsequent requests requiring a refresh during the cooldown fail fast with `OIDC_KEY_SET_UNAVAILABLE` without issuing network requests. Older late-failing operations cannot overwrite newer success or cooldown states. Transient capacity rejections do not install cooldowns.
   - Unexpired cached keys continue to serve legitimate traffic during cooldown.

5. **Late Result Discarding**:
   - If a background refresh task exceeds its deadline, the result is marked late and discarded. Late results are never published to the cache and never extend key freshness.
   - Monotonic cache updates ensure older out-of-order results cannot overwrite newer key entries. Expired keys are never accepted as fallback.

6. **Timed Transport Stage Deadlines**:
   - In `SsrfSafeHttpsJwkSetSource`, pre-call DNS resolution is executed under a bounded worker pool.
   - Monotonic elapsed DNS time (`System.nanoTime()`) is subtracted from the total `callTimeout`. The remaining budget governs HTTP connect, read, and call timeouts for OkHttp. Total transport elapsed time across the DNS and HTTP stages is bounded by `callTimeout`.
   - Operating bounds and pools are process-local to each JVM instance; timed stages bound specific network transport operations rather than providing a universal hard wall-clock or real-time OS scheduling guarantee. OS-level DNS syscalls that ignore thread interruption unblock callers at their deadline while the bounded background worker completes.

7. **Time Re-sampling on Wait**:
   - Trusted time is sampled before key resolution and re-sampled immediately after key resolution completes.
   - If a bearer token expires while the caller was waiting for key resolution, token claims validation rejects the token with `AuthenticationException("EXPIRED_ACCESS_TOKEN")`.

## Operator Troubleshooting

### Symptom: `OIDC_KEY_SET_UNAVAILABLE`

- **Cause 1: Upstream IdP Outage or Network Failure**
  - Verify network reachability and health of the provider's `jwksUri`.
  - Check whether a failure cooldown is currently active. Once the failure cooldown expires (default 10s to 30s), a fresh fetch will be attempted automatically.
- **Cause 2: Saturated Refresh or DNS Workers**
  - High concurrency against slow upstream IdP endpoints may temporarily exhaust bounded worker pools.
  - Saturated workers fail closed to protect system memory and threads. Capacity recovers automatically as soon as in-flight requests finish.
- **Cause 3: SSRF or DNS Policy Rejection**
  - The provider's hostname resolved to a non-public, loopback, private, or documentation IP address, or returned more than 8 addresses.
  - Verify that the IdP hostname resolves exclusively to globally routable public IPv4 or IPv6 addresses.

### Symptom: `INVALID_ACCESS_TOKEN`

- **Cause 1: Untrusted Key ID (`kid`)**
  - The access token header contains a `kid` not present in the provider's allowlisted keys.
  - If a rotation recently occurred, verify that the IdP has published the new key and that the rotation cooldown (`forcedRefreshCooldown`) has elapsed.
- **Cause 2: Unsupported Algorithm or Key Size**
  - Verify the signature algorithm is RS256 and the RSA key modulus is at least 2048 bits.

### Symptom: `EXPIRED_ACCESS_TOKEN`

- **Cause: Token Expiration Crossed During Resolution**
  - The client token expired either before arrival or during the bounded wait for verification key resolution.
  - The client must request a refreshed bearer token from the identity provider.
