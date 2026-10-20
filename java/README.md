# TracePermit Java backend

This Gradle multi-module build is the sole supported TracePermit backend. It
consumes repository-level JSON Schemas, fixtures, policies, and frozen golden
vectors in `../contracts` as a language-neutral compatibility boundary.

Modules:

- `contracts`: canonical JSON and action/identity/delegation validation;
- `policy`: deterministic classification and ordered policy evaluation;
- `security`: short-lived, action-bound, audience-bound, single-use permits;
- `execution`: idempotency, unknown-outcome reconciliation, and evidence chains;
- `identity`: strict development authentication, connector attribution,
  exact-scope authorization, Ed25519 endpoint-source/workload verification,
  and operator-bound OAuth/OIDC/JWKS verification;
- `application`: fail-closed orchestration around a simulator port; and
- `adapters-http`: the combined loopback-only development MCP backend plus
  action, evidence-integrity, policy-version, lineage, health, and simulator
  read projections; a separate operator-configured loopback approval main with durable exact
  snapshots, independently authenticated reviewer membership and simulator execution;
- `adapters-remote`: SSRF-safe live JWKS HTTPS plus a direct-TLS authenticated
  MCP boundary with handshake/discovery and an explicit simulator-only
  enforcement composition;
- `endpoint-connector`: bounded MCP stdio, endpoint observation, source-proof
  signing ports, and loopback gateway transport; and
- `executor-simulator`: strict one-shot stdio execution with operator-owned
  Ed25519 verifier trust, PostgreSQL permit consumption, fixed simulator routes,
  and bounded receipts; and
- `persistence-postgres`: Flyway/JDBC durable permit claims, attempts,
  transitions, evidence streams, leased outbox identities, and immutable
  external-anchor receipts; and
- `observability-otel`: bounded security-event metrics and trace projection;
  and
- `conformance`: language-neutral artifact and security-invariant tests.

Run from the repository root:

```bash
pnpm java:check
pnpm test:endpoint-connector-e2e
pnpm dev:backend
pnpm test:java-postgres --rerun-tasks
pnpm demo:scenario:approval:auto
pnpm demo:scenario:semantic:auto
pnpm demo:scenario:qualification
pnpm test:approval-api --rerun-tasks
```

The Java implementation is the supported local POC backend, not a production
service. Its default runnable main binds only to loopback and composes ephemeral
development credentials and the simulator. The separate `pnpm dev:approvals`
entry point uses strict private operator configuration, a local PostgreSQL DB,
external OIDC verification and persistent Ed25519 file keys. See the
[approval runbook](../docs/runbooks/authenticated-approval-api-foundation.md);
optional same-origin browser sign-in and React inbox are described in the
[browser runbook](../docs/runbooks/browser-approval-inbox.md). No main includes a real AWS/Jira
SDK, target credential, Spring runtime or deployable public bootstrap. The
direct-TLS remote edge is configured only by tests/library composition; the
separate hosted OAuth demo has its own loopback main. PostgreSQL qualifications
use disposable real Testcontainers and never fall back to memory. Default local
stores and symmetric execution-permit test signing are not production-grade
custody or multi-replica state.

The separate `executor-simulator` application accepts only a compact permit and
exact canonical action. It holds no permit-signing key, performs no schema
migration, exposes no listener and includes no provider SDK/credential. Its
standalone permit claim is intentionally not described as the approval path's
atomic execution/evidence transaction. See the
[verifier-only executor runbook](../docs/runbooks/verifier-only-simulator-executor.md).

Scenario 3 uses that test-runtime composition to start disposable PostgreSQL
and exercise the real request-bound approval, permit, evidence, and simulator
path. It is a development presenter harness, not a deployable reviewer service;
it exposes no HTTP mutation route and uses no real target credentials.

Scenario 4 uses the `adapters-http` test-runtime composition to generate
ephemeral Ed25519 workload/delegation credentials and cross the real identity,
policy, sanitizer, permit, evidence, and simulator boundaries. Scenario 5
measures the warmed deterministic and full local paths and proves unavailable
sequence state or sanitization cannot dispatch. Neither is a deployable
runtime or production performance claim.

The source-attestation verifier uses asymmetric Ed25519 keys, but its current
key registry and replay store are injected, process-local development
implementations. The portable vector contains no private key. The Java MCP
stdio connector signs through a non-exporting provider interface and the
development adapter loads a restrictive runtime PKCS#8 file. Managed
enrollment, protected platform key custody, durable replay/revocation, native
macOS qualification, and anti-bypass controls remain future work. See the
[development runbook](../docs/runbooks/java-mcp-endpoint-connector.md).

The remote OAuth adapter accepts only exact operator JWKS URIs, rejects
private/reserved DNS answers, pins the approved addresses through TLS connect,
publishes protected-resource metadata, and authenticates before MCP parsing.
Runtime tests generate all TLS/RSA private keys. Live IdP credentials, external
connector certification, shared abuse state, production certificate lifecycle,
production telemetry export/alerting, and a deployable production runtime
remain future work. The hardened profile requires an issuer-signed RFC 8705
mTLS thumbprint to match the direct peer, applies two injected rate-limit
stages, checks exact readiness, and can compose with PostgreSQL durable
enforcement. An injected outbox publisher verifies independently signed
Ed25519 receipts. A leased unknown-outcome worker performs lookup-only
reconciliation and has no simulator invocation port. The hardened remote
edge records bounded authorization telemetry before dispatch; failure returns
503 without dispatch. No production anchor, collector, or target-audit
integration is configured.
The enforcing dispatcher requires independent invocation scope, strict tool schemas,
server-owned normalization, policy, a request-bound permit, and simulator
verification; the handshake-only dispatcher remains available.
