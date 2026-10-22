# Installed requester public sign-in

This runbook configures the **source-free, same-Mac, simulator-only** requester
helper in the TracePermit simulator approval distribution. It signs a pinned
requester into an operator-configured Native/public OIDC client, verifies both
signed tokens and exact requester-only enrollment, stores one short-lived bearer
in an owner-only file, and consumes it before starting a fixed Codex executable.

It does not prove prompt authorship, Codex binary identity, endpoint posture,
production compatibility, or general Auth0/OIDC compatibility. Never expose the
loopback ports through a tunnel, proxy, wildcard listener, or public interface.

## Prerequisites

- The matching source-free archive, verified using its outer checksum and
  `SHA256SUMS`.
- Java 21 and a separately installed, operator-pinned Codex CLI.
- The simulator approval service running on numeric loopback, configured with the
  same pinned issuer/client/organization and an active human membership whose
  role set is exactly `REQUESTER`.
- A Native/public provider app using Authorization Code + S256 PKCE, token
  authentication method `none`, and the exact callback configured below. Do not
  create or configure a client secret.

The provider must issue independently verifiable access and ID tokens for the
same exact subject. The access token must contain only `approvals.read`,
`approvals.request`, `approvals.execute`, and optionally non-authorizing
`openid`. There is no refresh token or automatic re-login.

## Private configuration

Create a dedicated owner-only directory outside source control:

```bash
umask 077
mkdir -p "$HOME/.config/tracepermit-requester"
chmod 700 "$HOME/.config/tracepermit-requester"
cp examples/installed-requester-sign-in.example.json \
  "$HOME/.config/tracepermit-requester/requester.json"
chmod 600 "$HOME/.config/tracepermit-requester/requester.json"
```

Edit the private file locally. Keep all URLs and paths explicit:

- issuer must end in `/`; authorization, token, and JWKS URLs must be the exact
  pinned endpoints under that issuer;
- callback must be `http://127.0.0.1:<fixed-port>/requester/callback` and must be
  registered exactly in the Native/public app;
- qualification and MCP URLs must share the same numeric-loopback approval
  origin and use `/api/v1/requester-qualification` and `/mcp`;
- `expectedSubject` is the exact dedicated requester subject; and
- `credentialFile` and `codexExecutable` are normalized absolute paths. The
  credential parent must already be mode `0700`.

Never put client secrets, passwords, cookies, authorization codes, PKCE
verifiers, provider responses, access tokens, or ID tokens in this file, Git,
chat, shell history, PR text, or logs.

```bash
export TRACEPERMIT_REQUESTER_CONFIG="$HOME/.config/tracepermit-requester/requester.json"
```

## Offline check

```bash
bin/tracepermit-requester-auth --check
```

Success prints `REQUESTER_CONFIGURATION_VALID` and performs no DNS, socket,
browser, provider, backend, credential, database, or child-process operation.
Any invalid schema, permissions, link, executable, URI, path, or profile exits 2
with a stable category and no raw value.

## Explicit login and one-use Codex launch

Start a new login only when you intend to authenticate the pinned requester:

```bash
bin/tracepermit-requester-auth --login
```

The helper binds the exact numeric-loopback callback before opening the browser.
One callback attempt is accepted during the finite flow. Wrong account, state,
issuer, token, scope, expiry, enrollment, transport, or provider shape fails
closed and creates no credential. A successful login writes one `0600` envelope
containing only the access token and signed expiry.

Launch Codex once:

```bash
bin/tracepermit-requester-auth --codex
```

The helper revalidates the signed credential, unlinks it before child creation,
and passes it only in the child environment variable
`TRACEPERMIT_REQUESTER_BEARER`. The token is absent from argv, persistent Codex
configuration, stdin, output, and evidence. The fixed invocation configures the
`tracepermit` HTTP MCP server with `bearer_token_env_var` and read-only sandboxing.
A child-launch failure never republishes the credential.

Pending approval is not execution. The browser reviewer remains a separate human
and role. If the requester credential expires during review, run an explicit new
login as the same subject/client/organization, then explicitly check the saved
request ID/hash and resume once. Never automatically recreate, retry, or resume a
request.

## Cleanup and recovery

```bash
bin/tracepermit-requester-auth --cleanup
```

Cleanup removes only the exact verified credential. It does not revoke the
provider token. An existing, partial, linked, wrong-mode, or otherwise unsafe
target is never overwritten. Investigate `CREDENTIAL_ALREADY_EXISTS` or
`CREDENTIAL_CLEANUP_REQUIRED` privately before another login.

All public errors use bounded categories such as
`REQUESTER_CONFIGURATION_INVALID`, `REQUESTER_LOGIN_TIMEOUT`,
`REQUESTER_ENROLLMENT_REQUIRED`, `CREDENTIAL_PATH_UNSAFE`, and
`CODEX_LAUNCH_FAILED`. No mode retries provider calls, callbacks, MCP calls,
request creation, approval resumption, or child launch.

## Current qualification boundary

Automated tests use generated keys, synthetic identities, owned local peers, and
simulated targets. Live provider registration, interactive browser selection,
installed Codex catalog/handshake, expiry/re-login continuity, and replay checks
remain a separate owner-operated Mac qualification against the exact clean
archive commit. Same-UID malware, root/OS compromise, provider revocation
latency, and shared-human use of one OS account remain outside this profile.
