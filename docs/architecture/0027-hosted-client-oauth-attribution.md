# ADR 0027: Attribute hosted-client actions with application OAuth, not tunnel identity

- **Status:** Accepted for simulator-only qualification
- **Date:** 2026-10-11
- **Requirements:** FR-003, FR-014, SEC-001, SEC-004, SEC-011, SEC-015,
  REL-001, REL-009, OPS-004

## Context

The Secure MCP Tunnel establishes private reachability and authenticates the
local tunnel process to OpenAI. Its runtime key and workspace association do
not identify the human represented by an individual MCP call. The earlier
Scenario 2 therefore used a fixed simulated subject and could not claim
signed-in ChatGPT-user attribution.

## Decision

Add a separate hosted-client OAuth runtime profile. TracePermit is the resource
server, an established provider is the authorization server, and ChatGPT or
another hosted MCP client performs authorization code with PKCE for the user.
The hosted client sends the resulting access token on each MCP request.

The Java loopback edge parses only a bounded MCP envelope before
authentication so that hosted clients can obtain static `server/discover`,
handshake, and tool metadata and receive the standard tool-level OAuth
challenge. The modern discovery response advertises only supported protocol
versions, tool capability, server identity, and public cache metadata. Public
discovery contains no identity or user data and grants no invocation authority. Every
`tools/call` still requires token verification before profile resolution,
canonical action construction, policy, permit issuance, or simulator dispatch.
Trust roots and mappings are operator configuration, never request fields. The
tunnel launcher injects no shared bearer. The profile has no local token
endpoint and can call only the fixed simulator through the existing
complete-mediation path.

The same profile publishes protected-resource metadata, per-tool OAuth schemes,
truthful read/destructive annotations, and an authenticated profile tool whose
opaque stable ID derives only from verified issuer-qualified subject identity.

External OAuth discovery is a separate egress boundary. The tunnel launcher
therefore requires each additional authorization-server destination to be an
operator-supplied, exact HTTPS origin and passes it through the tunnel client's
native trusted-origin allowlist. It rejects paths, credentials, wildcards,
duplicates, control characters, plaintext HTTP, and lists longer than eight;
there is no implicit trust of arbitrary metadata URLs.

## Consequences

- A successful live test can attribute an action to a provider-verified user
  subject while keeping the model/orchestrator as a separate actor.
- Tunnel identity continues to prove transport-process authority only.
- The IdP must be browser/publicly reachable even though the MCP resource
  server remains private behind the outbound tunnel.
- The operator mapping follows the exact provider-issued RFC 9068 `client_id`;
  a provider's internal registration ID can differ from the public CIMD URL.
- Hosted-client `offline_access` is allowlisted only for refresh continuity. It
  is not tool authority, and TracePermit never receives or stores the refresh
  token.
- Provider-specific tenant claims may use a bounded HTTPS namespace, but the
  namespace and value-to-tenant mapping remain operator configuration.
- The loopback hop may use HTTP only inside the tunnel-client host boundary;
  direct public ingress still requires reviewed TLS and client controls.
- Public MCP discovery is a metadata capability, not an authenticated user
  session. Keeping it separate is required for hosted-client tool-level OAuth.
- Secure MCP Tunnel uses MCP `2026-07-28` `server/discover` before authenticated
  action discovery, so that method must remain static and non-executing rather
  than falling through to the protected dispatcher.
- Malformed public envelopes are rejected by strict size, media-type, duplicate
  key, trailing-token, and schema checks before any protected dispatch.
- The strict JWT profile can reject a convenient provider default. Provider
  claims must be configured to the contract. Standards-compatible issuer
  variation is bounded: `aud` can be a string or a small unique array only when
  it still contains the configured resource, and optional `nbf` is checked when
  present. TracePermit will not weaken issuer, resource, client, tenant,
  lifetime, type, algorithm, or scope checks to make a demo pass.
- The 2026-10-11 Scenario 6 run qualified one simulator-only
  Auth0/ChatGPT/tunnel composition: authenticated profile, allowed bounded
  read, and destructive termination blocked before permit or dispatch.
- The qualification required accepting Auth0's bounded provider-qualified
  subject shape only in the signed `sub` validator and configuring the exact
  verified stable CIMD `client_id`. Neither change broadens issuer, audience,
  tenant, key, time, type, algorithm, or scope trust.
- The live action's valid evidence chain ended at
  `execution.not_executed`; no permit or dispatch event was present. Full
  request or credential logging remains rejected.
- The result is evidence for the tested development composition, not a claim
  of universal hosted-client compatibility or production readiness.

## Rejected alternatives

- **Treat the tunnel runtime key or tunnel ID as the human:** wrong principal.
- **Copy a ChatGPT display name from prompt/tool arguments:** unverified input.
- **Keep injecting one shared development bearer:** useful for transport
  qualification but cannot distinguish users.
- **Require authentication before reading any MCP bytes:** prevents the hosted
  client from learning per-tool OAuth requirements and makes tool-level OAuth
  undiscoverable. The selected bounded public catalog exposes no execution
  capability.
- **Implement an authorization server in the POC:** unnecessary identity risk;
  an established provider is the safer, recommended boundary.
- **Accept arbitrary provider JWT shapes:** creates algorithm/claim confusion.
  The selected compatibility is limited to RFC 9068 audience and `nbf` forms
  and is covered by positive and fail-closed tests.
