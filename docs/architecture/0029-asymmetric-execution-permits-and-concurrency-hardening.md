# ADR 0029: Staged Java backend hardening without weakening current contracts

- **Status:** Accepted for the simulator-only POC
- **Date:** 2026-10-14
- **Requirements:** FR-001, FR-004, FR-008, SEC-002, SEC-004, SEC-005,
  SEC-006, SEC-017, REL-004, OPS-005

## Context

The older `codex/java-backend-hardening` branch proposed five improvements, but
predated hosted-client OAuth and semantic sequence enforcement. Integrating it
wholesale would have overwritten current tool metadata and documentation. The
implementation is therefore reconciled on top of current `main` with explicit
compatibility and fail-closed gates:

1. **Symmetric execution permits:** HMAC signing requires issuer and verifier to
   share a secret. An executor compromise could expose that secret. The POC
   retains HMAC for frozen vectors and existing development compositions, but
   needs a verifier-only asymmetric option.
2. **Integer precision:** The frozen `tracepermit-canonical-json-v1` profile
   follows its JavaScript-compatible numeric behavior, which loses precision
   above the safe-integer range. Silently changing its signed bytes under the
   same profile name would be a compatibility and signature risk.
3. **Local rate-limiter contention:** A synchronized method serializes all
   principals. The older concurrent rewrite could admit more than `maximumKeys`
   under simultaneous distinct-key misses; correctness must precede throughput.
4. **Hardcoded MCP tools:** The dispatcher could not register another trusted
   simulated tool with its own scope while preserving existing hosted OAuth
   discovery and the separate profile tool.
5. **Storage composition:** The loopback development backend used only memory,
   while the PostgreSQL durable service had separate integration tests.

## Decision

### 1. Asymmetric Ed25519 Permit Signing with Verifier-Only Separation

`ExecutionPermitAuthority` supports Ed25519 (`EdDSA`) alongside `HS256`:

- The issuer holds an Ed25519 private key; a verifier-only instance holds only
  the public key and cannot issue permits.
- The permit header algorithm is pinned to the configured authority. Existing
  issuer, audience, lifetime, action-hash, and single-use checks still apply.
- This is an API capability, not a claim that the development bootstrap has
  production key custody or uses Ed25519 by default.

### 2. Exact integers without silently changing v1 signatures

`CanonicalJson.canonicalize()` and its `PROFILE` remain frozen. A separate
`canonicalizeExactIntegers()` candidate preserves `long` and `BigInteger`
precision recursively. It is **not** used to sign or verify v1 artifacts. Any
activation requires a new named profile, vectors, and migration window.

### 3. Concurrent local rate limiting with strict capacity

`InMemoryFixedWindowRateLimiter` uses `ConcurrentHashMap` with atomic per-key
claims and a lock for new-key admission:

- Existing-key claims do not acquire the global admission lock.
- New-key insertion and capacity reservation occur under the same lock.
- Expired entries are conditionally removed so a concurrent renewal is not
  discarded. Distinct-key contention tests enforce the cap.
- This remains process-local test/demo state, not distributed abuse protection.

### 4. Extensible MCP Tool Registry with Per-Tool Scopes

We introduced `McpToolDefinition` and `McpToolRegistry`:

- Trusted composition registers tool names, discovery schemas, required scopes,
  argument normalizers, and canonical action builders before server startup.
- The dispatcher freezes a registry snapshot, checks the tool's scope, and
  still sends every action to the injected enforcer before any simulator effect.
- Unknown, duplicate, malformed, and reserved profile tool registrations fail
  closed. Existing hosted OAuth profile discovery and metadata are preserved.

### 5. Unified Runtime Storage Selection

`DevelopmentBackendMain` has an explicit loopback-only storage selection:

- `TRACEPERMIT_STORAGE=postgres`: Requires an explicit PostgreSQL JDBC URL,
  applies Flyway migrations through `PostgresDurableEnforcementState`, and wires
  `DurableExecutionService` with the restrictive web-readonly policy. It does
  not silently inherit the in-memory semantic-sanitization demo profile.
- `TRACEPERMIT_STORAGE=memory` (default): Runs loopback in-memory ledgers for
  local lightweight development and testing.
- Unknown storage modes fail at startup rather than falling back to memory.

## Consequences

- A verifier-only executor cannot forge Ed25519 permits, but production key
  custody, rotation, and deployment isolation remain future work.
- Exact integers are available for a future profile without changing current
  signed bytes or portable vectors.
- Local concurrent claims preserve the configured key cap; no production
  throughput or distributed-rate claim is made.
- A code-defined tool can be added with its own scope without bypassing the
  canonical action, policy, permit, and simulator path.
- PostgreSQL is an explicit development option, not a production-ready public
  runtime or a substitute for shared abuse state and egress controls.
