# ADR 0011 — Add an endpoint connector and request-bound source attestation

- Status: accepted; portable verification and Java stdio development slice implemented
- Date: 2026-10-09

## Context

The existing TracePermit gateway authenticates a remote connector and derives a
canonical action immediately before a simulated target. That boundary cannot by
itself prove which managed device, operating-system session, or local process
started a CLI invocation. It also cannot stop a separately credentialed agent
from taking another network path.

The product must support real Codex, Claude Code, and other MCP-capable clients
without pretending that a fixture is the client. CLI, API, and scheduled
origins can provide richer provenance than an unmodified web application, but
only when a trusted source component observes those facts and protects its
signing authority.

OS identity, enterprise identity, provider-account identity, device identity,
process identity, and agent identity are different claims. None may be silently
promoted into another.

## Decision

Introduce a **TracePermit Endpoint Connector** as an optional source-side data
plane component. The first implementation target is a Java 21 MCP stdio shim
started by a real CLI agent. The shim observes its parent process, obtains
authenticated enterprise and device context through ports, signs a short-lived
attestation bound to the exact outbound MCP request, and forwards that request
to the existing gateway.

The gateway remains the policy enforcement point. It authenticates the
connector credential, verifies the endpoint attestation independently, checks
that the request hash and credential fingerprint match, consumes the
attestation identifier once, and constructs trusted canonical identity and
trigger fields. The MCP request body cannot assert verified endpoint fields.

The source attestation will be:

- versioned and algorithm identified;
- asymmetrically signed by an enrolled endpoint key;
- bound to issuer, key ID, audience, issue/expiry time, and a unique token ID;
- bound to a canonical hash of the exact MCP request;
- bound to the fingerprint of the connector credential used upstream;
- explicit about observed device, OS-session, process, and interactivity
  evidence; and
- stored only as non-secret fingerprints and bounded projections in evidence.

The first POC will use injected development key providers and an in-memory
public-key/replay registry. Runtime keys must be generated outside source
control. A macOS Keychain/Secure Enclave adapter, MDM enrollment, hardware
attestation, persistent replay storage, and egress enforcement remain later
production work.

The portable Slice A protocol resolves the initial format choices as follows:

- compact JWS with an exact `EdDSA`/source-attestation protected header and an
  Ed25519 signature;
- canonical header and payload bytes under
  `tracepermit-canonical-json-v1`;
- `tracepermit-mcp-method-params-v1`, which hashes canonical `method` and
  `params` while excluding the transport request ID;
- maximum lifetime of 60 seconds and five seconds of future-issue tolerance;
- an injected issuer/key/device registry and atomic replay port; and
- a dedicated safe canonical `source` projection after verification.

The repository golden vector retains only the enrolled public key and a valid
signature. Runtime sign/verify tests generate keys in memory; no endpoint
private key is committed.

Slice B composes the gateway verifier only when loopback development runtime
configuration supplies the exact issuer, key ID, expected device ID, and
Ed25519 public SPKI key. Configuration is all-or-nothing, public-key-only, and
cannot be changed through a request. Invalid or remote composition fails
closed. The endpoint side uses explicit observer, credential-provider,
key-provider, and gateway-transport ports. Its local file-key adapter is a
development convenience behind the signing-only port, not a production key
custody decision.

The stdio transport is newline-delimited UTF-8 JSON-RPC, sequential, bounded,
and restricted to the current MCP subset. Its gateway transport is locked to
loopback HTTP `/mcp`, never follows redirects, and never contacts a target
directly. This runtime boundary is intentionally narrower than the later
managed/remote design.

An endpoint attestation enriches attribution; it does not prove the account
logged into an encrypted SaaS browser. Provider-account identity requires an
independent provider OAuth/SSO, connector, managed-browser, or identity-provider
signal. Unknown account context remains unknown.

## Trust and enforcement consequences

- A CLI-origin connector profile may require valid endpoint attestation before
  MCP parsing or dispatch.
- Missing, malformed, expired, replayed, wrong-audience, wrong-key, wrong-
  credential, or request-mismatched attestations fail closed.
- Web connector profiles remain governed by their remote connector evidence;
  the system must not fabricate endpoint provenance for them.
- Interactivity is an observed signal, not proof that a human chose every
  downstream tool action.
- The endpoint signer must not receive target credentials and must not make the
  final policy decision.
- The agent should not receive the endpoint private key. Production key use
  belongs behind a daemon or platform key store.
- Preventing direct target bypass still requires credential custody, restricted
  egress, or target-side controls.

## Rejected alternatives

### Trust identity fields sent by the CLI

Rejected because a compromised or prompt-injected client could label itself as
an authenticated enterprise human or managed process.

### Treat OS username as enterprise identity

Rejected because local account names are mutable and do not prove an identity-
provider subject or provider account.

### Use a transparent network tunnel as the semantic control

Rejected because encrypted traffic normally exposes destination and process
metadata, not a reliable structured tool action or provider login.

### Put the final allow/block decision only on the endpoint

Rejected because the endpoint may be stale, user-controlled, or offline. It
may perform an early local denial, but the gateway and executor remain
authoritative.

## Consequences

- Real CLI clients can contribute stronger, explicitly scoped provenance.
- The architecture gains a new device-key enrollment and replay-protection
  lifecycle.
- Canonicalization must cover the signed source-attestation request hash.
- The Java connector and gateway verify the same exact MCP request binding.
- Browser attribution remains intentionally weaker unless a separately trusted
  provider or managed-browser signal is available.
