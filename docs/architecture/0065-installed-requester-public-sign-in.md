# ADR 0065: Installed requester public sign-in and one-use Codex handoff

Status: Implemented automated foundation; live provider/Codex qualification pending.

Requirements: FR-003/007/012; SEC-002/003/004/009/011/012/015;
REL-001/004/006/009; OPS-001/002/004/005.

## Decision

Extend the source-free simulator approval distribution with a separate fixed
requester helper. It uses Authorization Code + S256 PKCE as a Native/public
client with no client secret, binds one exact numeric-loopback callback before
opening the browser, and independently verifies signed access and ID tokens
against operator-pinned issuer, endpoints, JWKS, audience, client, organization,
subject, time and scope. Dynamic discovery, refresh/offline scope, confidential,
device, password and client-credentials fallbacks are forbidden.

Add a bodyless `GET /api/v1/requester-qualification` route behind the existing
bearer verifier and membership resolver. It returns 204 only for a current human
membership whose role set is exactly `{REQUESTER}`, whose token has exactly the
request/read/execute API authority (plus optional non-authorizing `openid`), and
whose signed lifetime has at least 60 seconds remaining. It writes no approval,
evidence, permit, execution, target or provider state and grants no new authority.

After both token checks and backend qualification, publish one bounded envelope
containing only the access token and signed expiry to an owner-only directory by
forced temporary write plus atomic no-replace hard-link publication. Existing,
linked, wrong-mode, non-owner, partial and unsafe targets are refused rather than
overwritten. There is no refresh credential.

For an explicit `--codex` operation, revalidate the file and signed token,
consume/unlink the envelope before child creation, and launch only the pinned
absolute Codex executable. The token appears only in the child environment
variable `TRACEPERMIT_REQUESTER_BEARER`; argv contains only the variable name,
exact loopback MCP URL, required-server setting, prompt approval mode and
read-only sandbox. A failed child start never republishes the credential.

## Consequences and limits

The configuration, offline checker, callback parser, qualification route,
credential races and child handoff are separately testable with generated keys,
synthetic identities, owned local peers and simulator targets. Stable public
failure categories omit raw values and nested exception text. No mode retries a
callback, exchange, request, resume, MCP call or child launch.

The helper proves neither prompt authorship nor Codex executable integrity,
endpoint attestation, provider revocation freshness, human isolation within one
OS account, production readiness or general Auth0/OIDC compatibility. Live
registration, account selection, installed Codex catalog/handshake,
expiry/re-login continuity and replay rejection remain an owner-operated Mac
gate against the exact clean artifact. The listener and backend stay numeric
loopback only and must not be tunneled or proxied.

Rollback removes the additional launcher/config/runbook and qualification route;
the existing browser/MCP contracts and durable approval schema remain unchanged.
