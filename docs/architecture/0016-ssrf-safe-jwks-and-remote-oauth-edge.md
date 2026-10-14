# ADR 0016: Pin outbound JWKS destinations and authenticate before remote MCP parsing

- **Status:** Accepted; Java handshake/discovery foundation implemented
- **Date:** 2026-10-09
- **Requirements:** FR-003, FR-004, FR-010, FR-014, SEC-001, SEC-002,
  SEC-003, SEC-004, SEC-008, SEC-011, SEC-012, SEC-015, REL-001, REL-006,
  OPS-001, OPS-002, PORT-004

## Context

The OAuth verification implementation separated OAuth verification from network I/O. A naive HTTP JWKS
implementation would reintroduce attacker influence through redirects, private
addresses, DNS rebinding, unbounded responses, or a misconfigured dynamic URL.
Likewise, publishing metadata over HTTP without an authenticated dispatch
order could let malformed or unauthenticated requests consume parser and
application behavior before identity is known.

Host and Origin checks are useful routing/browser controls, but neither is
identity. TLS authenticates a network endpoint, but it does not establish that
TracePermit intended to contact that endpoint.

## Decision

Add a separate `adapters-remote` Java module. Keep the identity module pure.

Use OkHttp for the outbound adapter because it permits a request-scoped pinned
DNS implementation while retaining normal TLS hostname verification. Require
an exact full-URI operator allowlist, global-unicast-only DNS answers, at most
eight answers, direct connections without system proxies, no redirects or
automatic retry, bounded time, accepted JSON media types, strict UTF-8, and a
64 KiB response cap.

Expose a direct-TLS JDK `HttpsServer` adapter created with operator-supplied
TLS context and provider configuration. Validate exact Host/Origin policy,
authenticate before body parsing and dispatch, publish RFC 9728-shaped
metadata, and return bounded Bearer challenges.

Implement only stateless MCP initialization, initialized notification, ping,
and scope-gated tool discovery. Keep remote tool invocation explicitly
disabled until the Java policy/permit/executor path is wired and tested.

ADR 0017 subsequently accepts and implements that simulator-only wiring as a
separate dispatcher; this ADR's handshake-only composition remains available.

## Consequences

- A token cannot redirect JWKS retrieval, and DNS cannot silently replace the
  validated address set between policy evaluation and HTTP-client lookup.
- A mixed public/private DNS response fails as a whole instead of attempting a
  preferred address.
- Authentication dependency failure is distinguishable from invalid
  credentials without exposing internal causes.
- The adapter can be live-tested with runtime-generated keys while source
  control contains no private key or reusable credential.
- Direct TLS avoids ambiguous forwarded-header trust, but a future reverse-
  proxy deployment needs its own authenticated proxy boundary.
- Handshake success does not imply tool invocation, policy allow, or target
  authority.

## Rejected alternatives

- Use Java `HttpClient` with preflight DNS checks: it does not expose a
  request-scoped DNS resolver, leaving a check/connect rebinding gap.
- Follow redirects only within the same host: redirect parsing and repeated
  resolution add unnecessary trust paths for a fixed JWKS endpoint.
- Allow private IPs when the hostname is allowlisted: a poisoned or changed DNS
  answer could reach metadata, loopback, or internal services.
- Trust `X-Forwarded-Host` or `X-Forwarded-Proto`: no authenticated reverse-
  proxy boundary exists in this slice.
- Wire `tools/call` directly to the simulator: authentication is not policy or
  execution authority, and bypassing the Java enforcement path would violate
  complete mediation.

## Limitations

No production listener configuration, real identity provider, live external
connector qualification, shared rate state, production certificate lifecycle,
durable edge evidence, or real target is included. ADR 0017 adds simulator
execution and ADR 0018 adds a separate hardened test composition.
