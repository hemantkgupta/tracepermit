# ADR 0035: explicit operator bootstrap for local simulator approvals

**Status:** Accepted for local simulator composition only

**Requirements:** FR-003, FR-007, FR-008, FR-012; SEC-001, SEC-002,
SEC-003, SEC-004, SEC-006, SEC-011, SEC-012, SEC-015; REL-001, REL-004;
OPS-001, OPS-002, OPS-004, OPS-005

## Context

[ADR 0034](0034-authenticated-loopback-approval-api.md) added a tested
authenticated approval factory, but no operator entry point. A runnable
composition must not inherit the demonstration issuer, ephemeral permit keys
or caller-supplied roles. Making it runnable must not imply a public product
profile or turn the read-only console into an authentication boundary.

## Decision

Add a separate `SimulatorApprovalMain` and `pnpm dev:approvals` launcher.
`TRACEPERMIT_APPROVAL_CONFIG` names a strict, versioned private JSON file.
There are no environment fallbacks for identity, credentials, keys or roles.
The adapter binds only numeric IPv4 loopback. Its database must also be numeric
IPv4 loopback; remote PostgreSQL and database TLS are not this profile.

The configuration enrolls one issuer, audience, client and tenant mapping,
with explicit subjects, human/workload type and roles. At least one requester
and a distinct human reviewer must exist. Enrollment is trusted operator
configuration, not proof that two accounts belong to distinct physical humans.
Subject IDs are qualified with the exact configured issuer before resolution.
The token verifier continues to require its strict RS256 `at+jwt` profile and
per-invocation scopes. No local issuer or browser/OAuth login flow is added.

Passwords and Ed25519 keys are referenced by files, not embedded in JSON,
command arguments or startup output. All four input types (configuration,
password, private key and public key) must be regular, current-user-owned,
owner-read-only or owner-read/write files on a POSIX filesystem. Reads are
bounded and reject final-component symlinks. The key formats are PKCS#8 DER
and SubjectPublicKeyInfo DER; signing and verifying a fixed domain-separated
challenge checks the pair. There is no automatic key generation or rotation.

Offline `--check` validates configuration, files, enrollment, key pairing and
JWKS URL syntax without DNS, IdP traffic, database access/migration or a
listener. It is not a readiness/connection/authentication check. On `--run`,
configuration validation precedes non-migrating existing-schema validation and
listener creation. [ADR 0045](0045-explicit-database-initialization.md) supersedes
the original implicit-migration behavior: first installation uses a separate
empty-only `--init-db`; normal run never migrates or repairs the database.
The fixed versioned policy requires approval for the
only exposed simulator mutation and blocks unmatched actions. There is no
operator option for arbitrary target adapters, policy replacement or public bind.

JWKS retrieval remains lazy, exact-destination HTTPS with the existing
SSRF-safe DNS pinning, public-address checks, size/time bounds and no redirects
or ambient proxy. Configuration checks do not establish that an IdP will issue
compatible tokens. Reusing the verifier's CLI metadata slot does not establish
endpoint/process evidence; the normalized HTTP action remains `api`/`service`.

The application emits a code-only startup failure without exception details
and a loopback listening address on success. Runtime approval closure is
idempotent for shutdown-hook/owner overlap. DB consumption—not the in-memory
permit store—continues to enforce single-use approved execution across restart.

## Consequences and limits

- The existing MCP demos, hosted OAuth service and React console are unchanged.
  No approval listener is silently added to them.
- Membership edits and key changes require a controlled stop/restart. Keep the
  same configured key files for ordinary restarts. Managed rotation, immediate
  IdP revocation/introspection and live membership administration remain absent.
- The operator and its same-UID processes control these files and the running
  JVM. File checks do not protect against a compromised account, writable path
  ancestors, malicious local agents sharing that account, OS admins or key
  extraction from process memory. Use a dedicated identity and protected custody
  before any real deployment. Windows/ACL filesystems are not qualified.
- Local PostgreSQL uses password authentication without TLS. The operator must
  enforce its own DB authentication, binding, privileges, backups and at-rest
  protection. This is not a remote database security configuration or HA profile.
- Schema V6 snapshots and approvals persist; simulator resource state does not.
  Persisted outcomes never authorize re-execution after simulator restart.
- No browser session/CORS, public TLS edge, abuse/slow-client qualification,
  production readiness endpoint, real target or customer-ready release is added.
  Do not tunnel/proxy this service as a production approval API.

## Verification

`SimulatorApprovalConfigurationTest` covers strict fields/types, unknown versions,
file bounds/encoding/permissions/symlinks, secret and key rejection, enrollment,
check-only isolation and secret-safe errors. `SimulatorApprovalBootstrapTest`
crosses private operator files, real HTTP and signed synthetic tokens,
PostgreSQL, approvals, permit validation and simulator dispatch across restarts.
It also launches the real main in a separate JVM to prove offline checking,
startup, unauthorized rejection, SIGTERM shutdown/port release and DB-failure
startup. `SimulatorApprovalServerTest` retains the exact-action, isolation,
scope, expiry, stale-policy, concurrency and fail-secure regression coverage.
