# ADR 0018: Harden the direct-TLS remote edge with mTLS token binding and two-stage abuse controls

- **Status:** Accepted; Java foundation implemented
- **Date:** 2026-10-10
- **Requirements:** FR-003, FR-010, FR-014, SEC-003, SEC-004, SEC-008,
  SEC-009, SEC-012, SEC-015, SEC-016, SEC-017, REL-001, REL-004, OPS-001,
  OPS-003, OPS-005, PORT-004

## Context

Remote MCP enforcement provides complete mediation after OAuth authentication, but a Bearer
token copied during its lifetime can still be replayed by another sender. An
unbounded public edge can also spend CPU, JWKS, policy, database, and simulator
capacity before a request is rejected. The basic remote composition also
uses process-local enforcement/evidence state despite an available durable
PostgreSQL boundary.

## Decision

Add an explicit hardened server constructor. It uses the existing direct TLS
socket identity, requires an issuer-signed RFC 8705 `cnf.x5t#S256` token claim
to match the peer leaf certificate, and performs that comparison after OAuth
verification but before reading MCP JSON.

Apply a network-peer limiter before authentication and a verified-principal
limiter after authentication/sender binding. Both are injected ports and fail
closed. Keep the bounded in-memory implementation limited to tests and local
demonstration; shared production state is a deployment prerequisite.

Add an exact readiness aggregate for TLS identity, OAuth trust, sender
constraint, shared abuse state, durable enforcement/evidence, telemetry,
target egress, and live connector qualification. Do not add a production main
that can claim those dependencies are ready.

Pass authenticated tenant context separately through `TenantActionEnforcer`
so the remote dispatcher can compose with `DurableExecutionService`. Extend
the PostgreSQL identifier constraint only enough to persist issuer-qualified
OAuth subjects containing `#`.

## Consequences

- A stolen access token is insufficient without possession of the bound TLS
  private key.
- Authentication and sender possession remain separate checks.
- Abuse is bounded before expensive authentication and again after attribution.
- Readiness failure does not reveal dependency details and cannot become allow.
- Allowed remote simulator calls can use durable claims, evidence, and outbox
  identities without storing request arguments or results.
- Current external connector support is not implied; mTLS compatibility must
  be established in an authorized staging qualification.

## Rejected alternatives

- **Treat TLS alone as token binding:** any client can establish TLS; the
  issuer-signed token must name the permitted certificate key.
- **Key only by source IP:** NAT can group unrelated tenants, while a valid
  principal can rotate addresses. Both stages are required.
- **Key only by token fingerprint:** it stores a replay-correlatable token
  derivative in the abuse-control boundary and does not express tenant/client
  fairness.
- **Trust forwarded client-certificate headers:** the direct-TLS adapter has no
  reviewed trusted-proxy boundary.
- **Enable DPoP simultaneously:** supporting two sender profiles would expand
  algorithm, canonical URI, clock, nonce, and replay-state decisions before a
  connector requirement is known.
- **Package a deployable main now:** no production certificate custody,
  shared limiter, telemetry, egress enforcement, or connector qualification is
  available.

## Limitations

The local limiter is process-local, TLS certificate revocation is not designed,
remote-edge denials are not durably audited, and no real connector or target is
configured. DPoP remains a possible later profile rather than implemented
behavior.
