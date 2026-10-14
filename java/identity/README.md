# Java identity module

This module verifies identity and authority before a canonical action can
enter enforcement. It does not accept authenticated, verified, or delegated
status merely because request JSON contains those fields.

- The loopback development profile validates short-lived process-local
  credentials and retains only a credential fingerprint in evidence.
- The endpoint-source verifier checks an Ed25519 proof bound to the exact MCP
  request and authenticated connector credential.
- The scheduled adapter verifies an operator-trusted workload signature and
  a complete, monotonically narrowed delegation chain.
- The OAuth/OIDC verifier checks configured issuer, audience, client, tenant,
  scope, token type, time, and JWKS keys. The network JWKS fetcher is owned by
  the remote adapter, not this domain module.

Development keys, registries, replay stores, and test issuers are not managed
enterprise identity controls. Production use requires independent enrollment,
protected signing-key custody, durable revocation/replay state, and qualified
IdP/client integrations. No identity module holds a real target credential.
