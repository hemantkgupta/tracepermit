# Learning: native OIDC login and simulator approval authority

An installed application is a public OAuth client: its client ID identifies a
registration, and a secret distributed with it cannot establish safe client
authentication. TracePermit therefore selects method `none` explicitly in
operator schema1.1. Existing schema1.0 keeps confidential Basic authentication.
A missing secret never silently chooses another trust model.

The browser login starts authorization code with a fresh S256 PKCE challenge.
Java retains the verifier; the provider must bind the code to that challenge,
client and exact redirect. State binds the callback to the initiating login,
the HttpOnly binder cookie ties it to the browser, and nonce binds the signed
ID token to that login. Consuming state before exchange prevents two concurrent
callbacks creating two sessions. Access tokens remain reusable within their
bounded session; single-use callback state is different from a one-use execution
permit.

`openid` asks for identity through OIDC. It is not permission to read or approve
a TracePermit action, nor must it be echoed into the API token. A separate
signed ID token proves the configured login bindings. The independently
verified access token must name the exact API audience, client and organization
and carry `approvals.read`; request/review/execute each require their own scope
and current operator enrollment. Neither email, request JSON, client ID nor
an organization parameter can grant a role. Both signed tokens bind to the
configured organization in the public candidate.

After login, tokens stay in Java memory. React receives an opaque session and
bounded role/scope projection, and mutations require same-origin CSRF checks.
An approved request still has no effect until the original requester explicitly
executes its exact stored action and the durable claim wins once. Login,
approval and execution authority are distinct boundaries.

Auth0's default token profile is different from the strict RFC9068-shaped
profile used here. A provider can also omit the required RFC9207 callback
issuer. These are compatibility questions, not reasons to weaken validation.
Synthetic protocol/TLS/PostgreSQL tests prove the implemented controls; they
cannot prove a real tenant emitted the required response. Follow the
[pending local-Mac qualification](../runbooks/auth0-native-oidc-qualification.md)
and [ADR0063](../architecture/0063-explicit-public-browser-code-pkce.md).
