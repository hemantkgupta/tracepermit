# ADR 0032: Endpoint source proof never traverses an ambient proxy

- **Status:** Accepted for the local POC
- **Date:** 2026-10-14
- **Requirements:** SEC-011, SEC-014

## Context

The Java endpoint connector sends a development Bearer credential and exact
source-attestation proof to its loopback MCP gateway. The URI validator
allowed `localhost` and the JDK HTTP client inherited an ambient system proxy
selector. A configured proxy could receive the headers despite a loopback
hostname. DNS resolution of `localhost` also weakens the direct-loopback
claim relative to a numeric literal.

## Decision

The development transport accepts only `http://127.0.0.1:<explicit-port>/mcp`
with the exact raw path and no userinfo, query, or fragment. It sets the JDK
client's `NO_PROXY` selector and disables redirects. Negative tests reject
other hosts, unresolved names, encoded paths, and missing ports; a regression
test installs an ambient proxy and verifies that the connector never selects
it. Remote production transport is a separate profile, not a relaxation of
this development path.

## Consequences

The local connector is intentionally IPv4-loopback only. This reduces
configuration convenience in exchange for a simple, reviewable route for
credential- and proof-bearing requests. It does not prevent a same-user local
process from attacking an unprotected development machine; managed endpoint
identity and anti-bypass remain future work.

## Development launcher note (2026-10-15)

`pnpm demo:endpoint:codex` keeps this route unchanged: fixed numeric-loopback
metadata/token URLs, curl configuration/proxy/redirect/retry disabled, ephemeral
credential forwarded only through the environment, and Java source verification
still required. Matching build/session/key preflight is a convenience check,
not backend authentication. Resolving the native client's final symlink and
forwarding selected Java 21 avoid setup failures without weakening the Java
observer. The launcher retains a measurable shell ancestor and never asserts
terminal/human facts. See the [bounded native proof](../demo-scenarios/evidence/codex-native-endpoint-2026-10-15.md).
