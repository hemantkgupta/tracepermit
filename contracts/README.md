# TracePermit portable contracts

This directory contains language-neutral contracts consumed and validated by
the Java backend.

## Contents

- `json-schema/` — versioned strict JSON Schema snapshots
- `vectors/` — deterministic validation, canonicalization, classification,
  policy, hashing, permit, authentication, source-attestation, signed
  scheduled-workload, and public-key-only OAuth/JWKS vectors
- `json-schema/policy-v1.schema.json` — active language-neutral policy contract; optional
  `match.requiredSignals` requires all named gateway-derived operational
  signals and does not accept client-supplied trust state
- `evidence-anchor-receipt-v1` — the exact secret-free, Ed25519-signed external
  checkpoint receipt structure
- `errors/` — stable public error-code catalog
- `protocols/` — HTTP and MCP compatibility snapshots
- `canonicalization/` — the exact byte-generation profile used before hashing
  or signing

Run:

```bash
pnpm portability:check
```

The command validates the complete checked-in inventory, schema identity and
strictness, stable snapshots, golden behavior, public-key-only signed fixtures,
and security invariants from Java. A contract change requires a reviewed
compatibility decision and corresponding updates to the Java conformance tests.

Schemas describe portable structural constraints. Java domain tests
additionally enforce graph, time, narrowing, lifecycle, and
cross-field invariants that JSON Schema cannot express completely.

All identities, signing material, tokens, and targets in this directory are
deterministic test fixtures. They confer no authority and must never be reused
outside tests.

The source-attestation vector deliberately contains only a public key, claims,
and signature. Its private key was generated ephemerally and is not retained in
the repository.

The scheduled-workload vector follows the same rule: it contains three public
verification keys and signed workload/delegation examples, but no private key
or production credential.

The OAuth vector contains one public RSA JWK and one fixed-time non-production
signed test access token plus the expected secret-free projection, protected-resource
metadata, and Bearer challenges. Its private signing key was generated
ephemerally and is not retained.

The MCP protocol snapshot also pins the Java remote OAuth transport, its
authentication-before-parsing and Host/Origin/TLS controls, and the explicit
simulator-only `tools/call` enforcement chain: invocation scope, server-owned
normalization, policy, permit verification, and redacted evidence. It also pins
the explicit hardened profile's RFC 8705 mTLS binding, two-stage rate controls,
readiness dependencies, durable-composition evidence, and absent production
bootstrap/shared limiter. ChatGPT/Claude-shaped entries are synthetic protocol-
conformance fixtures, not live product certification.

The evidence-anchor receipt schema binds an external signature to the tenant,
stable outbox identity, evidence stream position, event identity, and payload
hash. Runtime tests generate its signing key; no anchor private key is checked
in.

The Java policy and contract tests validate the checked-in active policy
schema and deterministic security vectors directly.
