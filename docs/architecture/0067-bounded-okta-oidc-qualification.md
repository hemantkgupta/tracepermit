# ADR 0067: Bounded Okta browser OIDC qualification foundation

- Status: Implemented synthetic foundation; live Okta and EKS pilot qualification pending.
- Requirements: FR-003/007/012; SEC-002/003/004/009/011/012/015;
  REL-001/004/009; OPS-002/004/005.

## Context

ADR0015/0016 bind issuer, keys, audience, clients, tenants and outbound network
destinations to operator configuration. ADR0034 independently enrolls human
requesters/reviewers/viewers. ADR0063's public browser profile is intentionally
Auth0-shaped; merely allowing an Okta issuer would not qualify another token
grammar, organization binding or authorization boundary. ADR0065's installed
requester helper remains a separate profile and is not extended here.

Okta distinguishes org authorization-server access tokens (for Okta resources)
from custom authorization-server tokens for an application's own API. This
foundation accepts only an explicitly selected custom-server candidate; it
neither discovers trust dynamically nor claims general Okta compatibility.
Official source references and operator prerequisites are in the
[qualification runbook](../runbooks/okta-oidc-qualification.md).

## Decision

Add strict operator schema `1.2` with profile
`okta-custom-as-jwt-rs256-v1`. Require a browser, method `none`, configured
HTTPS issuer `https://<operator-host>/oauth2/<server-id>` without trailing slash,
exact issuer-relative `/v1/authorize`, `/v1/token`, `/v1/keys`, a configured
HTTPS API audience distinct from the client ID, custom tenant claim/value/local mapping and
group claim plus explicit group-to-role mapping. Org-server roots, discovery,
unknown fields/versions/profiles, secrets and redirect overrides are refused.
Schemas1.0/1.1 and their existing trust semantics remain unchanged.

The operator selects token grammar before verification. The new access grammar
requires exact RS256/`JWT` header, `kid`, exact single API audience, `cid`, a
bounded unique `scp` array, subject, JTI, configured tenant and existing time
bounds. The standard profile still requires RS256/`at+jwt`, `client_id` and
space-delimited `scope`; there is no automatic fallback between grammars.
Mixed `client_id`/`scope` aliases are rejected in the Okta profile. Signature,
issuer, audience, client, tenant and time validation precede group projection.
Existing operator-pinned JWKS, rotation cooldown, no-stale-key fallback,
SSRF/TLS protections and public-address policy remain unchanged.

Groups must be a nonempty array of at most 100 unique, nonblank, bounded
strings. Exactly one group may match the operator mapping, even when aliases
would map to the same role. Unmapped groups add no authority. Project only the
mapped role (`REQUESTER`, `REVIEWER` or `VIEWER`), never raw groups or claims.
The independently signed ID token must validate its own issuer, single client
audience, subject/client pairing, tenant, nonce, time and optional access hash,
and resolve to the same mapped role. Neither ID nor access token alone signs in.

Mapped groups **attenuate enrollment**: the issuer-qualified subject, client,
tenant, human status and allowed role must independently exist in operator-owned
`ApprovalMemberships`. Dual-role enrollment projects only the one mapped role.
Groups cannot provision users, make workloads human, widen scopes, bypass
distinct review or replace exact-action/owner/single-use execution checks.
Request-body roles and arbitrary token role claims grant nothing.

Authorization requests use code/S256 PKCE, `openid groups` and existing API
scopes; they omit Auth0-specific `audience`/`organization` parameters. The token
request contains configured client ID and exact numeric-loopback callback,
without Authorization or client secret. Browser binding, mandatory callback
`iss`, one-use state/nonce/code binding, cookies/CSRF, mixed-auth refusal and
`127.0.0.1` listener confinement remain unchanged. No proxy/tunnel/public edge
or real target is enabled.

## Freshness and limitations

Immutable tokens do not report later provider group changes. Missing/changed
groups in fresh tokens fail closed; existing sessions retain signed groups
until their bounded expiry (at most ten minutes; API tokens at most fifteen).
Current operator enrollment is checked on each new request. Existing approval
execution rechecks operator reviewer enrollment, not live Okta group state.
Removing enrollment and restarting the installed process is the operator
cutoff; already admitted work is not globally cancelled. No introspection,
SCIM, group polling or instantaneous provider revocation is claimed.

This is a pre-EKS design-partner foundation. Custom-server entitlement,
registration, group/tenant claim inclusion in both tokens, exact header/claim
shape and RFC9207 callback interoperability require a separately approved live
exercise. An incompatible provider response is a stop condition. No live
tenant mutation/login occurred, and no broad IdP, cluster or production claim
follows. Existing Auth0 qualification evidence keeps its original scope.

## Verification and rejected alternatives

`OktaOidcQualificationTest`, `OktaApprovalConfigurationTest` and
`OktaApprovalBrowserTest` cover generated signatures, role boundaries,
malformed/duplicate/ambiguous/missing groups, enrollment mismatch/removal,
claims/scopes/time/key failures, bounded rotation, body-role injection,
redirect/verifier/code substitution, mixed authentication and concurrent
single-exchange callback replay. Existing Auth0, ID/access verifier, TLS/SSRF,
durable approval and diagnostic-redaction regressions remain mandatory.

Rejected: granting membership directly from groups; weakening all issuers to
accept `JWT` or alternate claim names; treating org-server tokens as an API
contract; deriving tenant from email/domain; obtaining keys from a request;
silently falling back on missing group/issuer/ID-token facts; using a client
secret in an installable client; and calling synthetic fixtures a live pass.
