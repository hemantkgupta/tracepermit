# Auth0 reviewer helper: manual qualification

Status: implemented; validation pending. No live tokens or deployments are
part of the focused or repository commands. Okta is preserved.

The coordinator supplies a candidate-pinned private runner and exact local
runtime/cache paths. Run its focused stage once and stop on any failure.
Only after reviewing that receipt run the repository stage separately.
Required gates retain pnpm check, pnpm format:check, documentation checks and
94% line / 75% branch aggregate coverage. Existing Okta and callback suites
remain required. Do not treat earlier receipts as current candidate results.

The helper builds with `:auth0-reviewer-cli:installDist`. Its Java21 entry point
is `io.tracepermit.auth0reviewer.Auth0ReviewerMain`. It supports `check-config`
and explicit future `login`; no live-login command is authorized here.

Input is one unsigned big-endian 32-bit length followed by 1..16384 strict
UTF-8 JSON bytes and mandatory EOF. Total input budget is five seconds.
Only an actual unlinked anonymous stdin pipe is accepted. Configuration has
exactly these fields:

- schemaVersion: 1.0
- issuer: exact HTTPS AU Auth0 default-domain root, including trailing slash
- audience: exact HTTPS TracePermit API identifier, never the Management API
- clientId: separate public reviewer client
- expectedSubject: independently approved human Auth0 user_id
- tenant: exact operator-owned signed tenant mapping
- keyId: pinned RS256 key identifier
- publicKeySpki: canonical base64 RSA public SPKI, 2048..8192 bits
- callbackUri: `http://127.0.0.1:41839/reviewer/callback`

No secrets, actual tenant identities or credentials belong in repository
examples. A key read from an untrusted token is not a key pin. Independent
review of the issuer public key and its custody is required before live use.
There is no discovery or JWKS fallback and no trust-store bypass.

A future explicit login has a 120-second process-held budget, including up to
115 seconds for the callback and a five-second exact-endpoint HTTPS exchange.
The callback binds before opening the browser, accepts one bounded GET,
requires matching state/issuer/host/path, rejects duplicates and closes its
owned listener. The HTTPS transport retains DNS pinning, public-address checks,
platform TLS validation, no proxy, no redirect and no connection retry.
The callback header budget is absolute, so trickled bytes cannot renew it.

The response permits only access_token, token_type, expires_in and optional
scope. Refresh and ID tokens are refused. The returned API token is verified
before an identity-only receipt; no raw token, code, verifier, human identity
or key is printed. An uncertain code exchange is not retried. Java Strings
and browser/OS memory cannot be reliably zeroized; same-user/root compromise
is outside this custody claim.

Remaining separate work: review/register the callback and bind it in the
Auth0 reviewer Action draft; manual Action fixtures and real provider login
(including absent/plain/mutated PKCE, wrong state, issuer and client); deploy
Actions only under a separate instruction; integrate the explicit Auth0
provider with the installed org configuration/ingress and private review
command. This helper does not persist a reviewer credential or submit review.
