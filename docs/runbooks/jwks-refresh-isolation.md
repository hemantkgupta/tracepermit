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

2. **In-Flight Coalescing (Single-Flight Refresh)**:
   - Multiple concurrent callers requesting an unknown key or expired key set for the same provider coalesce onto a single in-flight fetch task.
   - Waiters await the task with a monotonic timeout (`waiterTimeout`). Waiters that are interrupted restore thread interrupt status and fail closed.

3. **Worker Capacity Saturation (Fail Closed)**:
   - When refresh worker threads and queue slots are fully occupied, new refresh requests are rejected immediately with `RejectedExecutionException`.
   - Saturated workers fail closed with `AuthenticationException("OIDC_KEY_SET_UNAVAILABLE")` without blocking callers indefinitely.
   - Once running tasks complete, capacity is restored and subsequent refreshes succeed.

4. **Failure Storm Prevention (Cooldown)**:
   - If a key set refresh fails due to network outage, DNS failure, or malformed JWKS payload, a failure cooldown is recorded: `max(provider.forcedRefreshCooldown(), failureCooldown)`.
   - Subsequent requests requiring a refresh during the cooldown fail fast with `OIDC_KEY_SET_UNAVAILABLE` without issuing network requests.
   - Unexpired cached keys continue to serve legitimate traffic during cooldown.

5. **Late Result Discarding**:
   - If a background refresh task exceeds its deadline, the result is marked late and discarded. Late results are never published to the cache and never extend key freshness.
   - Monotonic cache updates ensure older out-of-order results cannot overwrite newer key entries. Expired keys are never accepted as fallback.

6. **Total Transport Deadline**:
   - In `SsrfSafeHttpsJwkSetSource`, pre-call DNS resolution is executed under a bounded worker pool.
   - Elapsed DNS time is subtracted from the total `callTimeout`. The remaining time governs HTTP connect, read, and call timeouts. Total elapsed transport time never exceeds `callTimeout`.

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
