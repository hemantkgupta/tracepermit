# ADR 0025: Treat Secure MCP Tunnel as reachability, not end-user identity

- **Status:** Accepted for the simulator-only live POC
- **Date:** 2026-10-10
- **Requirements:** FR-001, FR-003, FR-010, FR-014, SEC-001, SEC-003,
  SEC-008, SEC-011, SEC-012, REL-001, REL-009, OPS-001, OPS-004

## Context

TracePermit can enforce a web-origin MCP action only when an unmodified hosted
agent can reach a controlled MCP boundary. Publishing the development listener
or installing a browser interception certificate would add unnecessary ingress
and TLS-interception risk. OpenAI Secure MCP Tunnel supplies an outbound-only
path from ChatGPT to a private MCP server.

Tunnel reachability does not prove the ChatGPT human's identity. The tunnel
runtime key authenticates `tunnel-client` to the OpenAI control plane; it is not
an MCP caller credential and is not forwarded to TracePermit. A ChatGPT account,
workspace, or provider login also does not become a verified TracePermit subject
unless a separately configured application OAuth/OIDC flow supplies and verifies
that evidence.

## Decision

Use the official `tunnel-client` as a transport adapter in the live POC. It
long-polls the OpenAI control plane and forwards MCP JSON-RPC only to the
loopback Java `/mcp` endpoint. No inbound port is opened.

For the first no-auth ChatGPT plugin qualification:

1. `pnpm demo` must already be running and must report `backend=java`,
   `mode=simulated-loopback`, and `simulated=true`.
2. `pnpm demo:tunnel` obtains the existing short-lived
   `chatgpt-readonly` development credential from the loopback token endpoint.
3. The credential is stored in an owner-only temporary file and injected by
   `tunnel-client` only on its final HTTP hop to the exact loopback MCP origin.
4. TracePermit verifies the credential before MCP parsing and constructs all
   trusted action fields server-side. The call then crosses normalization,
   classification, policy, request-bound permit verification, and the simulator.
5. The temporary credential is removed when the tunnel process exits. The
   launcher strips common AWS and Jira credential variables and refuses a
   backend that does not prove simulator-only mode.
6. The loopback listener serves bounded protected-resource metadata at both
   `/.well-known/oauth-protected-resource` and the path-aware
   `/.well-known/oauth-protected-resource/mcp`. It identifies the exact
   loopback resource and supported read scopes but intentionally omits
   `authorization_servers` because this profile does not run end-user OAuth.

The canonical identity in this profile is the fixed simulated development
subject carried by the locally issued credential. It demonstrates connector
attribution and enforcement, not the identity of the person signed into
ChatGPT. The model remains an asserted orchestrator actor. The normal ChatGPT
web UI is unmodified.

A production profile must replace the local development credential with
application OAuth/OIDC or another independently verified provider identity.
That profile must use the production verifier and configured issuer, audience,
client, tenant, scope, and sender constraints. It must not infer identity from
the tunnel ID, browser headers, prompt text, or ChatGPT display name.

## Consequences

- The optional local demonstration can show an unmodified ChatGPT tool proposal crossing
  an outbound tunnel and the complete Java enforcement path.
- The local MCP server remains private and loopback-only.
- MCP request and response content necessarily traverse OpenAI. The control-
  plane runtime key and local injected MCP credential remain separate.
- Static header injection is short-lived demo plumbing, not token refresh or
  end-user authentication. Restarting `pnpm demo:tunnel` renews the five-minute
  credential.
- Successful protected-resource discovery proves client compatibility only.
  The public metadata authenticates nobody and cannot authorize a tool call.
- Console rows may truthfully say `chatgpt-web` and show the simulated
  development subject, but the presenter must not describe that subject as the
  signed-in ChatGPT user.
- An allowed bounded log read reaches only the simulator. The known EC2
  termination tool is normalized and blocked before permit or simulator
  dispatch.

## Rejected alternatives

- **Treat the tunnel runtime key as caller identity:** it authenticates the
  daemon to the transport control plane, not a human or tool invocation.
- **Trust ChatGPT account details from prompt text or tool arguments:** those
  values are attacker-controlled assertions.
- **Expose the Java development server publicly:** it weakens the loopback
  boundary and still does not solve application identity.
- **Use browser TLS interception:** it is unnecessary for MCP integration and
  would create a much larger endpoint trust and privacy boundary.
- **Bypass Java authentication because the tunnel is authenticated:** transport
  reachability is not MCP authorization or policy authority.

## Limitations

This decision qualifies one live ChatGPT developer-mode transport with a fixed
simulated identity. It does not qualify Claude, production OAuth, ChatGPT
workspace attribution, Compliance API correlation, shared rate/replay state,
high availability, or a real downstream target. The official tunnel client
uses a static MCP header in this profile, so the short-lived credential is
intentionally suitable only for a bounded live demonstration.
