# ADR 0063: Explicit public-client code/PKCE browser profile

- Status: Accepted for synthetic local qualification; live Auth0 pending
- Requirements: FR-003/007/012; SEC-002/003/004/009/011/012/015;
  REL-001/004; OPS-001/004/005

## Context

The browser boundary in [ADR 0036](0036-local-browser-approval-boundary.md)
required `clientSecretFile` and `client_secret_basic`. An installable/native
application cannot protect a reusable client secret. Its client ID and issuer
are identifiers, not proof that a caller is authorized. A missing secret must
not silently change a confidential registration into a public client.

## Decision

Preserve operator configuration `schemaVersion: "1.0"` exactly. Add version
`1.1` with a required browser object and explicit `tokenEndpointAuthMethod`:

| Method                | Secret configuration                | Token request                                                                      |
| --------------------- | ----------------------------------- | ---------------------------------------------------------------------------------- |
| `client_secret_basic` | Required private `clientSecretFile` | Existing Basic authentication plus code, verifier and exact redirect               |
| `none`                | Every secret field forbidden        | Code, verifier, exact redirect and configured `client_id`; no Authorization header |

Both require authorization code with S256 PKCE. Unknown versions, methods and
fields fail offline validation. `BrowserCodeExchange` accepts a closed form
schema and exact configured numeric-loopback callback; callers cannot inject
a secret, client ID or alternate redirect. The HTTPS transport omits the
Authorization header only when no authentication was explicitly selected.
Its exact destination, DNS pinning, public-address, TLS, timeout, response-size,
no-redirect/no-proxy/no-retry controls remain unchanged.

The public candidate is deliberately narrow: an exact HTTPS-origin issuer
ending `/`, same-issuer `/authorize`, `/oauth/token`, and
`/.well-known/jwks.json`, and one configured `org_id` value matching
`org_[A-Za-z0-9]{1,128}`. Authorization sends that organization from operator
configuration. Both signed access and ID tokens must bind to it; current
operator membership independently determines human status and role. No URL,
organization, key or role from an HTTP request can establish trust.

The fixed callback remains `http://127.0.0.1:<port>/auth/callback`. The browser
binder, state, nonce and verifier are random per login. State is atomically
removed before exchange, so concurrent or replayed callbacks cannot create
another session. Sessions, CSRF, exact Host/Origin, server-held tokens and the
shared durable simulator approval checks retain ADR0036's boundaries.

### Local rejection diagnostics

The installed process writes one bounded line to local stderr when a callback
passes HTTP parsing but browser login is rejected. The line contains only a
closed stage (`CALLBACK_BINDING`, `CODE_EXCHANGE`, `TOKEN_RESPONSE`,
`ACCESS_TOKEN`, `API_SCOPE`, `SENDER_CONSTRAINT`, `MEMBERSHIP`,
`HUMAN_IDENTITY`, `ID_TOKEN` or `SESSION_CAPACITY`) and either an explicitly
allowlisted access-token reason or `REJECTED`. Unknown reason strings are never
forwarded, even when they match the identity layer's diagnostic-code syntax.

The browser still receives only `BROWSER_LOGIN_FAILED`. Exception messages,
causes, stack traces, callback URLs, codes, verifiers, tokens, claims, cookies,
tenant/subject/client identifiers and token fingerprints are excluded. A
missing or failing diagnostic sink cannot change the rejection, create a
session or grant authority. Diagnostics do not relax or reorder any callback,
token, organization, enrollment or identity validation and are not evidence
that a provider profile is compatible.

### OIDC authentication and API authorization are separate

`openid` is mandatory in the authorization request to request OIDC identity.
It is **not required in the API access token's scope claim**. Public login
requires verified `approvals.read`; each operation separately checks its
specific approval scope and current enrollment. A signed ID token remains
mandatory, with independent issuer, single client audience, nonce, subject,
client, organization and time checks. An access token alone cannot sign in.

Auth0's [OIDC scope documentation](https://auth0.com/docs/get-started/apis/scopes/openid-connect-scopes)
describes the authentication request; its [API-token validation guidance](https://auth0.com/docs/secure/tokens/access-tokens/validate-access-tokens)
requires endpoint permissions. Examples that echo `openid` into access tokens
do not make it an API permission or a universal token-shape requirement.

## Compatibility and qualification limits

The strict access profile remains exact RS256/`at+jwt`, configured issuer,
audience membership, `client_id`, `jti`, tenant and allowlisted scopes, with a
maximum 15-minute lifetime. The ID token remains RS256 with one client audience
and at most 15 minutes. Exact callback `iss` remains mandatory under RFC 9207.
There is no fallback to `JWT` access tokens, `azp` instead of `client_id`,
missing callback issuer, opaque tokens, other algorithms or federated logout.

Auth0 [documents separate default and RFC 9068 token profiles](https://auth0.com/docs/secure/tokens/access-tokens/access-token-profiles).
That documentation is not evidence that this selected native app, organization,
token header and callback profile actually work together. Native organization
availability and RFC 9207 behavior remain live gates. A provider that omits
`iss` or emits an unsupported token fails sign-in; do not manufacture claims.

Automated tests use runtime-generated keys, local HTTP/TLS peers, synthetic
identities and disposable PostgreSQL. No Auth0 tenant, management API, real
target or public ingress is used. No general Auth0/OIDC compatibility or
production readiness is claimed. The [local-Mac runbook](../runbooks/auth0-native-oidc-qualification.md)
records the pending interactive gate. Container preparation is a separate
fixed-file profile and is not qualified for this new operator version here.

The connected tracker labels TP-MVP-04 as a broader pilot-contract task with
customer-evidence dependencies. This explicit engineering slice does not close
that task, customer selection or production identity requirements.
