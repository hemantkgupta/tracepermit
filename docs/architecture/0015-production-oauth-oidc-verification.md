# ADR 0015: Operator-bound OAuth access-token verification and resource metadata

- **Status:** Accepted; Java transport-neutral foundation implemented
- **Date:** 2026-10-09
- **Requirements:** FR-003, FR-004, FR-010, FR-014, SEC-001, SEC-002,
  SEC-003, SEC-004, SEC-009, SEC-011, SEC-012, REL-001, REL-006, OPS-002,
  PORT-001, PORT-002, PORT-004

## Context

The loopback POC issues HMAC-signed development tokens. That proves
authentication-before-dispatch and per-tool scope checks, but a shared local
key, fixed profiles, and `/dev/oauth/token` cannot authenticate a remotely
deployed connector. A production verifier also needs key rotation and a
standards-shaped way for MCP clients to discover the protected resource's
authorization servers and scopes.

JWT claims are not trusted merely because they parse. Allowing a token header
or request body to select a JWKS URL, tenant, client display identity, or scope
vocabulary would let the credential define the authority used to verify itself.

## Decision

Define `oauth-access-token-jwt-rs256-v1` as the initial Java verification
profile. It accepts exact `at+jwt`/RS256 headers and validates an RFC 9068-style
claim set against an operator-owned `OidcProviderRegistry`. The registry binds
an exact HTTPS issuer to audience, JWKS URI, authorization servers, known
clients, tenant mapping, origin, scopes, lifetimes, and cache policy.

The `aud` claim may use the JWT string form or a bounded array of one to four
unique strings, but it must contain the exact configured protected resource.
`iat` and `exp` remain required. RFC 9068 does not require `nbf`, so the verifier
accepts its absence and enforces safe-integer and temporal bounds whenever it
is present. This is a bounded standards-compatibility rule, not a relaxation of
resource or lifetime validation.

Use Nimbus JOSE/JWT for JOSE/JWK parsing and RSA verification. An injected JWKS
source keeps network I/O out of the pure identity module. The cache accepts
only public 2048-bit-or-stronger RSA signing keys marked for RS256, rejects
duplicate key IDs, refreshes once for an unknown key after a cooldown, and does
not use a stale key after refresh failure.

The verified projection qualifies subjects by issuer, maps the external tenant
and client through configuration, records authentication profile/key ID and a
token fingerprint, and omits the raw token, JTI, signature, and claims.

Define an RFC 9728-shaped protected-resource metadata contract and bounded RFC
6750 Bearer challenges. ADR 0016 now wires them to an HTTPS handshake adapter;
they grant no authority themselves.

## Consequences

- Unknown issuer, key, algorithm, audience, client, tenant, scope, time state,
  or unavailable verification dependency fails before dispatch.
- Empty, duplicate, oversized, control-character, excessive, or
  expected-resource-missing audience sets fail before dispatch.
- `jku`, embedded keys, and extra JOSE header fields cannot redirect key lookup.
- Bounded JWKS caching supports rotation overlap while making cache staleness
  explicit.
- A public-only golden vector tests signature, projection, metadata, and
  challenge behavior without committing a private key.
- Access-token reuse is not confused with execution-permit replay prevention.

## Rejected alternatives

- Reuse the development HMAC profile remotely: verifier compromise would also
  grant signing authority and the issuer has no production lifecycle.
- Trust a token-supplied `jku` or JWK: the request could establish its own root
  of trust.
- Treat metadata discovery or a valid token as tool authorization: discovery
  and authentication do not grant an invocation scope or policy allow.
- Consume every access-token JTI once: ordinary OAuth access tokens are reused
  across requests; the correct control is sender constraint plus request-bound
  execution permits. ADR 0018 implements an initial injected mTLS profile.
- Continue using hand-written production JOSE parsing: reviewed JOSE/JWT
  primitives reduce cryptographic parsing risk.

## Limitations

ADR 0016 adds the SSRF-safe live JWKS source and
direct-TLS metadata/handshake adapter while preserving this provider boundary.
There is still no deployable production main, OAuth authorization-server
integration, introspection/revocation, DPoP, production mTLS lifecycle, broad
external-client qualification, or real target. One Auth0/ChatGPT/tunnel
simulator profile has been qualified separately. ADR 0017 describes simulator
invocation and ADR 0018 the hardened test composition.
