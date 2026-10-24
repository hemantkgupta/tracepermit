# Installation credentials are an issuer boundary, not action approval

A private signing key proves an App identity to GitHub. A short-lived App JWT
requests a narrower installation token. That token authenticates later calls;
it does not prove that TracePermit approved a particular action or replace its
permit, target binding and preflight checks.

The new provider derives credentials only from immutable operator inputs,
private key custody and authenticated provider observations. It first verifies
the installation/App/account, then asks for one repository and an exact permission
profile. Requesting a restriction is not proof it was granted: typed response
confirmation is required. Optional or absent assurance means stop.

An opaque installation token does not carry a locally verified installation
attestation. Binding still trusts GitHub's authenticated endpoint/minting
contract. Decoding a token-looking string cannot manufacture that proof.

Each invocation owns its JWT and exchange lifecycle, with no cache or refresh
thread. Exceptions are replaced with closed codes, including nested/suppressed
key, signing, transport and cleanup failures. A secret cannot become a useful
diagnostic simply because a dependency put it in an exception. Cleanup failure
also prevents returning credentials, and expiry is checked again after cleanup.

Synthetic tests can qualify request restrictions, response validation and
redaction against controlled peers. They cannot establish live App installation,
provider compatibility, key custody, current action authorization or exactly-once
target effects. See [ADR0076](../architecture/0076-github-installation-credential-provider.md)
and the [runbook](../runbooks/github-app-installation-credentials.md).

Requirements: FR-001/008/009/012; SEC-001/002/003/012/015/019;
REL-001/003/005/009; OPS-002/004. No whole-requirement promotion.
