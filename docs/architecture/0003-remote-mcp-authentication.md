# ADR 0003: Authenticate before MCP dispatch

- **Status:** Accepted for the POC
- **Requirements:** FR-003, FR-004, SEC-001, SEC-004

## Context

MCP tool arguments are untrusted. Tool discovery is useful for client
experience but does not grant invocation authority. A caller that knows a
tool name can still attempt a direct `tools/call`.

## Decision

The Java MCP adapters authenticate protected requests before tool dispatch.
A bounded public discovery/catalog surface may answer only the metadata
needed for OAuth/MCP initialization; it cannot execute a tool. The adapter
derives tenant, subject, scopes, and connector context from verified transport
credentials, not request JSON. Every invocation independently checks scope,
registered tool name, strict arguments, canonical target mapping, policy, and
executor-verifiable permit before a simulator can run.

The loopback development profile uses ephemeral credentials and never
represents a hosted user's identity. The hosted-client profile uses an
operator-configured IdP and verifies the user's access token at the Java
resource server. The outbound tunnel transports requests but does not assert
or replace user identity. Remote direct-TLS tests additionally exercise
sender constraint, Host/Origin checks, abuse controls, and readiness.

## Consequences

- Catalog visibility and invocation permission are separate.
- Unknown tools and client-supplied identity, assurance, or target fields do
  not become trusted authority.
- A blocked action receives no permit and never reaches the simulator.
- The default runtime binds to loopback and has no real AWS/Jira credential.
- A public production edge needs managed IdP, key, state, egress, and
  operations controls beyond this POC.
