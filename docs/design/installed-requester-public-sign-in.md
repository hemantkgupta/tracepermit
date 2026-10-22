# Installed requester public sign-in

**Status: implementation contract; automated implementation in progress; live
provider/Codex qualification remains pending.**

Requirements: FR-003/007/012; SEC-002/003/004/009/011/012/015;
REL-001/004/006/009; OPS-001/002/004/005.

This contract defines the smallest proposed way for a human requester to obtain a
short-lived public-client credential for the source-free, local Codex durable-
approval profile. It does not implement a credential issuer, change an identity
provider, add a production deployment, or qualify live Auth0 behavior. The current
synthetic Codex rehearsal token is a test-classpath fixture and **is not** an
installed credential issuer.

The approved implementation packet is
[separate](../planning/packets/installed-requester-public-sign-in-implementation.md).
The runtime must not broaden this contract or relabel automated evidence as live
provider compatibility.

## 1. Source-backed baseline and trust boundary

The current repository establishes these facts:

- `SimulatorApprovalConfiguration` pins exactly one issuer, JWKS URI, API
  audience, client ID, tenant claim/value and local tenant. It constructs one
  trusted client mapping. `ApprovalMemberships` binds authority to the exact
  issuer-qualified subject, that client ID and that tenant. This proposal does
  not widen either mapping.
- The optional browser flow already has an explicit public-client mode: no
  client secret or token-endpoint Authorization header, authorization code with
  S256 PKCE, an exact numeric `127.0.0.1` callback, state, nonce and callback
  `iss`, strict access/ID-token verification and operator enrollment. Its access
  token remains server-held in an in-memory browser session; it is not an
  installed Codex credential.
- The local approval MCP adapter accepts a verified bearer and exposes only
  request, owned-status and explicit-resume tools according to requester role
  and scopes. It has no approve/deny tool. A renewed credential for the same
  subject/client/tenant can resume a durable request; the credential fingerprint
  is evidence, not the stable owner identity.
- The source-free simulator archive contains the Java runtime and React inbox,
  but its supported launcher does not perform installed requester sign-in. The
  synthetic `pnpm demo:approvals:codex` flow depends on test classes and writes a
  five-minute fixture token; it is deliberately outside normal operator config.
- HTTP bearer authentication proves possession of an access token. It does not
  prove which prompt caused a call, which executable sent it, whether a human is
  presently at the keyboard, or endpoint/source attestation. The durable MCP
  profile remains `service` triggered and bearer-only.

The proposed profile remains same-Mac, simulator-only and numeric-loopback. The
approval server and Codex connect through `http://127.0.0.1:<port>/mcp`. Only the
authorization, token and pinned JWKS requests leave the Mac, over exact
operator-owned HTTPS destinations. No listener is exposed through a tunnel,
proxy, LAN address, wildcard address or public host.

## 2. Decision: reuse the existing Native/public application

The first implementation must reuse the same explicitly configured Native/public
application and client ID as the browser approval profile. The operator registers
two **separate exact callbacks** on that application:

1. existing reviewer/inbox callback:
   `http://127.0.0.1:<approval-port>/auth/callback`; and
2. proposed requester helper callback:
   `http://127.0.0.1:<requester-port>/requester/callback`.

Neither registration may use `localhost`, a wildcard port or path, another
loopback address, a LAN/public address, HTTPS termination, or a tunnel URL. The
requester port is an operator-pinned fixed value so the registered callback and
runtime callback are byte-for-byte equal.

Reuse is the smallest compatible proposal because the current backend trusts one
client ID and all enrollment keys include that client ID. A distinct public app
would require a reviewed multi-client provider allowlist, membership/schema
semantics, browser/client selection, migration behavior and cross-client tests.
Silently adding its ID would broaden trust and could break durable request/resume
binding.

This decision is conditional, not a compatibility claim. If the real provider
cannot support both exact callbacks on the selected Native app, the organization
parameter, callback `iss`, strict `at+jwt` access token, signed ID token, or the
required scopes together, implementation stops with `PROVIDER_PROFILE_UNSUPPORTED`.
The owner must then choose between changing the provider registration or designing
the distinct-client backend change. There is no confidential-client, missing-claim
or relaxed-validation fallback.

Application reuse does not merge human identities. Conversely, using two app IDs
would not prove two humans. Exact signed subject plus operator enrollment remains
the human separation control.

## 3. Proposed end-to-end flow

```mermaid
sequenceDiagram
  participant O as "Human requester"
  participant H as "Installed requester helper"
  participant L as "One-shot 127.0.0.1 listener"
  participant B as "External system browser"
  participant I as "Pinned OIDC provider"
  participant G as "Loopback approval server"
  participant C as "Codex child process"
  O->>H: "Run explicit login"
  H->>L: "Bind exact callback or fail"
  H->>B: "Open code + S256 PKCE authorization"
  B->>I: "Human signs in within pinned organization"
  I->>L: "GET exact path with code, state and iss"
  L->>H: "Consume the one flow before exchange"
  H->>I: "Exchange code at exact token endpoint"
  H->>H: "Verify signed access token and ID token independently"
  H->>G: "Qualify requester-only enrollment with bearer"
  G-->>H: "Requester-only qualification succeeds"
  H->>H: "Create finite owner-only credential without overwrite"
  O->>H: "Run explicit Codex launch"
  H->>C: "Inject bearer into child environment; token never enters argv"
  C->>G: "Request or read approval over numeric loopback"
  Note over O,C: "Expiry requires manual re-login and explicit status/resume; never replay request"
```

The helper has no background daemon and no refresh capability. `login` and
`codex` are separate, explicit operator actions. A successful browser redirect
does not start Codex, create an approval, or invoke a target.

### 3.1 One-shot authorization listener

The proposed helper must:

- validate all configuration before opening a socket;
- bind IPv4 `127.0.0.1` and the exact configured port before opening the browser;
- fail `CALLBACK_LISTENER_CONFLICT` if that port is already bound, without trying
  another port;
- allow one authorization flow and at most one callback attempt for 120 seconds;
- accept only `GET` on the exact `/requester/callback` path, exact Host, no body,
  no transfer encoding, no Authorization, no Cookie and a single raw query no
  larger than 8 KiB;
- use a bounded request-head parser with a 16 KiB total head limit, at most 32
  headers and a five-second per-connection read deadline;
- accept exactly one non-empty `code`, `state` and `iss`; reject duplicate,
  missing, additional, malformed-percent-encoded, control-character or over-limit
  values; bound code to 4 KiB, state to 128 characters and issuer to 512;
- atomically remove the flow before any token exchange, return a minimal
  `no-store` success/failure page, close the listener after the first callback
  attempt, and close it at timeout or process interruption; and
- never place a code, state, nonce, verifier, authorization URL or callback query
  in output, logs, exceptions, screenshots or diagnostic files.

An invalid first request can deny this local login attempt. That bounded same-host
denial of service is preferable to continuing an ambiguous flow. The operator
starts a new flow manually after resolving the cause.

### 3.2 Authorization request and code exchange

Each flow generates independent cryptographically random state, nonce and PKCE
verifier values. The authorization request has a closed parameter set:

- `response_type=code`;
- exact configured `client_id`, `redirect_uri`, `audience` and `organization`;
- `code_challenge_method=S256` and the derived challenge;
- exact requested scopes
  `openid approvals.read approvals.request approvals.execute`; and
- the newly generated state and nonce.

`openid` selects OIDC authentication; it grants no approval API operation. The
three `approvals.*` values are the complete installed MCP requester authority.
`approvals.decide` and `approvals.cancel` are not requested. Cancellation is not
an MCP tool in this profile. `offline_access`, refresh tokens and any additional
scope are forbidden.

The token exchange goes only to the exact configured HTTPS token endpoint with a
closed form containing `grant_type=authorization_code`, the returned code, exact
redirect URI, original verifier and configured client ID. It sends no
Authorization header or client secret. Redirects, proxies, automatic retries and
alternate endpoints are disabled. A timeout or uncertain response is terminal for
that flow; the helper does not exchange the code again.

### 3.3 Independent token validation

The helper validates both tokens before creating a credential:

| Token          | Required checks                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Access token   | Canonical three-part JWT; exact `RS256` and `typ=at+jwt`; bounded known `kid`; signature from the pinned JWKS URI; exact issuer; configured API audience; exact `client_id`; exact organization/tenant mapping; required safe `jti`, `iat`, optional `nbf` and `exp`; lifetime at most 15 minutes; exact expected subject; no unsupported `cnf`; all three requester permissions present; no API permission beyond `approvals.read`, `approvals.request`, `approvals.execute`. `openid` is tolerated only if the provider echoes that requested non-authorizing scope. |
| ID token       | Canonical signed JWT; exact `RS256`; `typ` absent or exactly `JWT`, matching the existing verifier contract; pinned key; exact issuer; one audience equal to client ID; matching `azp` if present; exact nonce, subject and organization shared with the access token; valid `iat`, optional `nbf` and `exp`; lifetime at most 15 minutes; validate `at_hash` whenever present. It is never used as API authorization.                                                                                                                                                 |
| Token response | Bounded JSON no larger than 64 KiB with duplicate/trailing-token rejection; required `access_token`, `id_token` and exact Bearer `token_type`; optional bounded `expires_in` and `scope`; no other field. Any `refresh_token`, unknown field or non-Bearer type fails closed.                                                                                                                                                                                                                                                                                          |

The operator configuration pins an exact expected requester subject. This is
necessary because an existing browser SSO session may select the wrong human
without showing an account picker. A valid token for any other subject fails
`REQUESTER_IDENTITY_MISMATCH` before storage. Clearing or separating the browser
profile is an operator action; the helper must not extract, clear or reuse browser
cookies.

JWKS, authorization and token endpoints are configuration, never discovery input
from a request or unsigned document. HTTPS egress retains exact-URI allowlisting,
public-address validation, bounded DNS results, DNS pinning, platform TLS, no
redirect, no proxy, no retry, and connect/call/response-size limits. Unknown
issuer, version, algorithm, type, key, audience, client, organization, subject,
scope or state fails closed.

### 3.4 Requester-only enrollment qualification

Signed scopes do not create TracePermit authority. After local token validation
and before storage, the helper makes one authenticated, bodyless request to a new
numeric-loopback qualification route. That proposed route is **not implemented**.
It must reuse the approval server's existing verifier and `ApprovalMemberships`
lookup and return success only when all of these are currently true:

- the issuer-qualified subject, client and tenant have an explicit enrollment;
- the enrollment is human and its role set is exactly `{REQUESTER}`;
- the access token has exactly the installed requester API permissions defined
  above, with optional non-authorizing `openid`; and
- the credential is unexpired and has at least 60 seconds remaining.

The route is bearer-only, loopback-only and read-only; it accepts no Origin,
Cookie, query or body and returns no subject, token, token hash, provider ID,
membership list or operator configuration. `204 No Content` is sufficient. It
does not mint, refresh or revoke credentials and does not replace authorization
on every MCP method. Missing enrollment, reviewer/viewer/combined roles or extra
permissions fail before the credential is written.

This qualification avoids giving the helper access to the backend JSON, database
password, permit private key or other operator secrets. It is also why merely
checking the current MCP tool catalog is insufficient: that catalog does not
prove the member lacks reviewer authority elsewhere.

## 4. Identity continuity and review-time expiry

The stable requester key is the enrolled issuer-qualified subject plus configured
client and tenant. The backend pseudonymizes the subject into the approval owner,
stores the original client/origin binding and reloads the immutable action on
resume. A token's `jti` and credential fingerprint change on re-login and are not
the durable owner key.

If the access token expires while a different human reviews the request:

1. the pending/approved request remains governed by its own server-side expiry;
2. Codex receives authentication failure and must not retry request, status or
   resume automatically;
3. the operator closes that Codex session, explicitly cleans up any credential,
   repeats browser login and launches a new session;
4. the new tokens must resolve to the same expected subject, client,
   organization/tenant and requester-only enrollment; and
5. the operator explicitly reads the saved approval ID, confirms its request
   hash/status, and then chooses whether to resume once.

The helper never reconstructs or replays the original request. If the initial
request response was lost and no approval ID was captured, it reports an
uncertain outcome; it does not create a replacement automatically. If the
approval itself expired, was denied, cancelled, consumed, changed policy binding
or has an unknown execution outcome, ordinary fail-closed server behavior wins.

## 5. Finite local credential custody

The access token is the only token persisted. The ID token, code, verifier,
nonce, state, browser cookies and token response are never written. No refresh
token is requested or accepted.

The credential directory and file contract is:

- absolute canonical paths on a local POSIX filesystem;
- existing directory owned by the current OS user, not a symlink, mode `0700`,
  with a stable file identity during the operation;
- target absent before login; an existing path, symlink or hard-linked file is
  `CREDENTIAL_ALREADY_EXISTS`, even when expired;
- a same-directory random temporary regular file created with create-new,
  no-follow semantics and mode `0600`, fully bounded, written and forced before
  publication;
- atomic no-replace publication. The macOS implementation may use an atomic
  create-new hard link from the verified temporary inode, then remove the
  temporary name. If the platform cannot guarantee no-replace publication, it
  fails instead of using a check-then-replace move;
- exact small JSON envelope containing only schema version, access token and its
  signed expiry, with a maximum 20 KiB file size; and
- wrapper revalidation of ownership, mode, regular-file/no-symlink status, link
  count, shape, token signature/claims and current expiry before use.

The issuer already bounds the token to at most 15 minutes. The helper does not
extend that lifetime and refuses to store a token with less than 60 seconds left.
The Codex launcher consumes the credential explicitly, removes the file before
starting the child, and injects the bearer only into the child environment. On
failure before safe consumption, it leaves a bounded error and requires explicit
cleanup; it never overwrites the path. Expired/crash residue must be removed by a
separate strict `cleanup` command that rechecks the same path invariants and never
follows a link.

Deletion is not revocation. The provider token can remain valid until signed
expiry, and copies held by another process are unaffected. Java strings and a
child process environment cannot be guaranteed to be zeroized. The same OS user,
debugger, malware, swap/crash collection or a compromised browser can steal a
bearer. Full-disk encryption, OS account separation and endpoint security remain
operator controls. This profile is not safe for mutually untrusted humans sharing
one OS login.

## 6. Manual Codex setup — proposed, not implemented

The helper must launch Codex directly with `ProcessBuilder` or an equivalent
argument-vector API. It must not print a shell command, use command substitution,
place a token in argv/config/stdin, or accept an arbitrary child executable. The
operator pins an absolute Codex executable in the private config. The wrapper
sets a fixed child-only environment variable and passes only its **name** in the
Codex MCP configuration override. No token value appears below.

Proposed commands and configuration are illustrative and **do not work in the
current repository**:

```bash
TRACEPERMIT_REQUESTER_CONFIG=/private/operator/requester-sign-in.json \
  bin/tracepermit-requester-auth --check

TRACEPERMIT_REQUESTER_CONFIG=/private/operator/requester-sign-in.json \
  bin/tracepermit-requester-auth --login

TRACEPERMIT_REQUESTER_CONFIG=/private/operator/requester-sign-in.json \
  bin/tracepermit-requester-auth --codex
```

```json
{
  "schemaVersion": "1.0-proposed-not-implemented",
  "profile": "installed-requester-public-sign-in",
  "oidc": {
    "issuer": "https://idp.example.invalid/",
    "authorizationUri": "https://idp.example.invalid/authorize",
    "tokenUri": "https://idp.example.invalid/oauth/token",
    "jwksUri": "https://idp.example.invalid/.well-known/jwks.json",
    "audience": "https://approval-api.example.invalid",
    "clientId": "native-client.example.invalid",
    "organization": "org_exampleinvalid",
    "expectedSubject": "provider|requester.example.invalid"
  },
  "local": {
    "callbackUri": "http://127.0.0.1:41837/requester/callback",
    "qualificationUri": "http://127.0.0.1:8085/api/v1/requester-qualification",
    "mcpUri": "http://127.0.0.1:8085/mcp",
    "credentialFile": "/private/operator/runtime/requester-credential.json",
    "codexExecutable": "/absolute/path/to/codex"
  }
}
```

The eventual wrapper supplies Codex an invocation-scoped equivalent of:

```toml
[mcp_servers.tracepermit]
url = "http://127.0.0.1:8085/mcp"
bearer_token_env_var = "TRACEPERMIT_REQUESTER_BEARER"
required = true
default_tools_approval_mode = "prompt"
```

Only the variable name and loopback URL may appear in persistent or command-line
configuration. The wrapper must not refresh a token, restart Codex, repeat an MCP
call, or interpret Codex errors as permission to retry. Codex's local tool prompt
is not the independent TracePermit reviewer.

## 7. Threat and abuse matrix

| Threat or mistaken assumption                         | Required control                                                                                                | Residual limit / fail-closed result                                                           |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| Browser SSO selects a different human                 | Pin and verify exact expected `sub` in both signed tokens; backend requires matching enrollment                 | Operator must choose/clear the correct provider session; wrong subject produces no file       |
| Two app IDs are treated as two humans                 | Human separation uses signed subject and enrollment, never client ID alone                                      | One human can access multiple apps; app separation is only client isolation                   |
| Callback injection, duplicate or replay               | Random state, exact `iss`, one-shot listener, consume flow before exchange, exact query schema                  | A local attacker can deny one login attempt; no ambiguous continuation                        |
| Stolen/substituted authorization code                 | S256 PKCE, exact redirect/client/token endpoint, no redirect or retry                                           | Compromised browser/OS remains outside bearer confidentiality guarantee                       |
| Token from wrong issuer/key/audience/client/org       | Independent strict signature and claims validation from pinned trust inputs                                     | Unknown or unavailable trust fails login; no dynamic discovery fallback                       |
| Reviewer authority reaches Codex                      | Exact requester scopes; backend qualification requires role set exactly `{REQUESTER}`; no approve tool          | A separate browser login may hold reviewer authority, but its cookie/token is never extracted |
| Public listener becomes remote ingress                | Bind exact IPv4 `127.0.0.1`, exact Host/path/method/body, no tunnel/proxy/wildcard                              | Same-host processes can reach the listener                                                    |
| Port squatting or callback flood                      | Bind before browser, fixed port, one flow/attempt, finite 120-second lifetime and size bounds                   | Local denial of one attempt is possible; helper fails visibly                                 |
| SSRF, DNS rebinding or redirect during egress         | Exact operator URLs, public-address policy, bounded/pinned DNS, TLS, no proxy/redirect/retry, finite time/bytes | Provider/DNS outage blocks login                                                              |
| Symlink/overwrite race steals credential              | Owner-only directory, no-follow checks, create-new temp, atomic no-replace publication, refuse existing target  | Same-UID privileged malware and filesystem/kernel compromise remain                           |
| Token leaks through shell/history/process arguments   | Java reads file directly; child-only environment; no shell interpolation, argv token, output, HAR or log        | Same-UID process inspection and child compromise remain                                       |
| Expiry during human review triggers duplicate request | No refresh/retry; manual re-login; same identity; explicit status then resume using saved ID/hash               | Lost initial response can remain uncertain; never auto-create replacement                     |
| Shared laptop is assumed to separate people           | Require separate OS accounts for untrusted humans; state the same-UID limitation                                | This profile does not isolate humans sharing one OS account                                   |
| Bearer is claimed as prompt/executable/source proof   | Preserve `service` trigger and bearer-only assurance language; do not emit endpoint attestation                 | Prompt authorship, Codex binary identity and endpoint posture remain unproven                 |
| Provider returns a nearly compatible token            | No relaxed algorithm/type/org/callback/claim handling; stop with provider-profile blocker                       | Live compatibility remains pending until exact Mac qualification                              |

## 8. Failure categories and retry semantics

These are proposed bounded categories, not current runtime codes. They may include
safe remediation text but never raw provider responses, URLs with query strings,
identifiers, paths containing private data, tokens or nested exception messages.

| Category                          | Meaning / operator action                                                                                 | Automatic retry |
| --------------------------------- | --------------------------------------------------------------------------------------------------------- | --------------- |
| `REQUESTER_CONFIGURATION_INVALID` | Closed config, trust input, path or command is invalid; repair offline                                    | No              |
| `PROVIDER_PROFILE_UNSUPPORTED`    | Exact public-client/token/callback contract is not met; owner decision required                           | No              |
| `CALLBACK_LISTENER_CONFLICT`      | Exact callback port cannot be exclusively bound; stop the conflicting process or change registered config | No              |
| `REQUESTER_LOGIN_TIMEOUT`         | No valid callback arrived within 120 seconds                                                              | No              |
| `REQUESTER_CALLBACK_INVALID`      | Method/path/Host/query/body/size/encoding contract failed                                                 | No              |
| `REQUESTER_STATE_MISMATCH`        | State or callback issuer did not match the one flow                                                       | No              |
| `TOKEN_EXCHANGE_FAILED`           | Exact bounded exchange failed or outcome is uncertain                                                     | No              |
| `ACCESS_TOKEN_INVALID`            | Signature, type, issuer, audience, client, organization, subject, time or scope failed                    | No              |
| `ID_TOKEN_INVALID`                | Independent ID-token, nonce or token-binding validation failed                                            | No              |
| `REQUESTER_IDENTITY_MISMATCH`     | Valid tokens identify a human other than the pinned requester                                             | No              |
| `REQUESTER_ENROLLMENT_REQUIRED`   | Backend has no exact active requester-only enrollment                                                     | No              |
| `OIDC_TRUST_UNAVAILABLE`          | Pinned JWKS/DNS/TLS dependency cannot be safely used                                                      | No              |
| `CREDENTIAL_PATH_UNSAFE`          | Ownership, mode, link, filesystem or file-shape invariant failed                                          | No              |
| `CREDENTIAL_ALREADY_EXISTS`       | No-replace target exists; run strict cleanup after investigation                                          | No              |
| `CREDENTIAL_EXPIRED`              | Signed expiry passed or less than the safe launch floor remains                                           | No              |
| `CODEX_LAUNCH_FAILED`             | Pinned child could not start; credential is cleaned or left for explicit safe cleanup                     | No              |
| `CREDENTIAL_CLEANUP_REQUIRED`     | Crash residue could not be safely identified/removed                                                      | No              |

The operator may deliberately start a **new login flow** after a terminal failure.
That is not permission to repeat a request, token exchange, approval resume or
target operation.

## 9. Exact acceptance and negative-test matrix

The implementation must automate every row except those explicitly marked
manual. Runtime-generated signing keys and synthetic identities are mandatory;
no private provider IDs or credentials enter fixtures.

| ID      | Test                                                                                                          | Expected result                                                                                    |
| ------- | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| IPS-001 | Offline check of the exact proposed config and private-path attributes                                        | Success with zero sockets, DNS, browser or database access                                         |
| IPS-002 | Unknown schema/field/method; secret field; `offline_access`; discovery URL; relative path                     | `REQUESTER_CONFIGURATION_INVALID`; no socket/file                                                  |
| IPS-003 | `localhost`, IPv6, wildcard/LAN/public callback; wrong path/port; mismatched same-issuer endpoints            | Fail offline                                                                                       |
| IPS-004 | Callback port already held                                                                                    | `CALLBACK_LISTENER_CONFLICT`; no alternate bind/browser                                            |
| IPS-005 | Inspect live listener address, path and 120-second shutdown                                                   | Only exact IPv4 loopback; listener closes once/timeout                                             |
| IPS-006 | Wrong method/path/Host; body/transfer encoding/Auth/Cookie; duplicate/missing/extra query; malformed encoding | One bounded failure, flow consumed, no token exchange                                              |
| IPS-007 | Oversized query/code/state/issuer/header or slow/incomplete callback                                          | Bounded failure and listener closure                                                               |
| IPS-008 | Valid callback raced by 8 replays                                                                             | Exactly one callback can proceed; all others fail/no listener                                      |
| IPS-009 | Capture authorization request                                                                                 | Exact params/scopes/org/callback; S256; no secret, refresh, device or password grant               |
| IPS-010 | Capture token request                                                                                         | Exact destination/form/client ID; no Authorization header; no redirect/proxy/retry                 |
| IPS-011 | Wrong/missing state or callback `iss`; reused code                                                            | No credential                                                                                      |
| IPS-012 | Access token wrong key/alg/typ/kid/issuer/audience/client/org/sub/jti/time/scope or unsupported `cnf`         | `ACCESS_TOKEN_INVALID`; no qualification/storage                                                   |
| IPS-013 | ID token wrong key/alg/typ/issuer/audience/azp/org/sub/nonce/time or bad `at_hash`                            | `ID_TOKEN_INVALID`; no qualification/storage                                                       |
| IPS-014 | Duplicate/trailing/oversized token JSON; wrong token type; missing token; refresh token/unknown credential    | Fail closed with redacted category                                                                 |
| IPS-015 | JWKS/token DNS private/many addresses, redirect, proxy attempt, TLS/content-type/size/timeout failure         | `OIDC_TRUST_UNAVAILABLE` or exchange failure; no fallback                                          |
| IPS-016 | Correct expected subject and exact requester-only enrollment/scopes                                           | Qualification 204, credential may be created                                                       |
| IPS-017 | Missing, reviewer-only, viewer-only, non-human or combined requester/reviewer enrollment                      | `REQUESTER_ENROLLMENT_REQUIRED`; no credential                                                     |
| IPS-018 | Token has decide/cancel/unknown permission or lacks read/request/execute                                      | Qualification refused                                                                              |
| IPS-019 | Browser SSO returns another valid enrolled human                                                              | `REQUESTER_IDENTITY_MISMATCH`; no file                                                             |
| IPS-020 | Symlink/non-owner/wrong-mode/non-regular parent or target; multi-link file; unstable identity                 | `CREDENTIAL_PATH_UNSAFE`; outside file unchanged                                                   |
| IPS-021 | Two concurrent publishers target one absent credential                                                        | Exactly one no-replace publication succeeds; the other reports existing                            |
| IPS-022 | Existing valid/expired/partial target; crash residue                                                          | Never overwrite; explicit strict cleanup required                                                  |
| IPS-023 | Inspect stored envelope and distribution/log/process output                                                   | Only access token+expiry in 0600 file; no other credential material or token/hash in output        |
| IPS-024 | Expired, tampered or less-than-60-second token at Codex launch                                                | No child starts; no automatic login                                                                |
| IPS-025 | Codex child process argument/config inspection                                                                | Token absent from argv/config/stdin; only fixed environment-variable name and loopback URL         |
| IPS-026 | Codex exits, launch fails or helper is interrupted                                                            | Credential path safely consumed/removed or a bounded cleanup-required failure                      |
| IPS-027 | Authentication failure during request/status/resume                                                           | No automatic token refresh, MCP retry, request recreation or Codex restart                         |
| IPS-028 | Re-login with new `jti` for same subject/client/org after review                                              | Explicit owned status and one explicit resume work while approval remains valid                    |
| IPS-029 | Re-login as different subject/client/org                                                                      | Existing approval cannot be read/resumed as owner                                                  |
| IPS-030 | Source-free archive inventory                                                                                 | New supported launcher/runtime only; no test classes, synthetic issuer, key, secret or source tree |
| IPS-031 | All error and interruption paths seeded with secret sentinels                                                 | Stable categories only; zero sentinels/raw exceptions                                              |
| IPS-032 | Manual Mac: correct requester login, native Codex handshake/catalog and pending request                       | Exactly three requester tools; pending is not execution                                            |
| IPS-033 | Manual Mac: distinct browser reviewer, token expiry, re-login, status/resume/replay                           | Same owner resumes once; replay rejected; no automatic request                                     |
| IPS-034 | Manual Mac: provider omits strict callback/token/org requirement                                              | Qualification stops and records unsupported profile, never a compatibility pass                    |

Concurrency tests must use barriers/latches rather than timing assumptions.
Network tests use owned local TLS peers and injected resolvers; the production
address policy remains unchanged.

## 10. Package and runtime prerequisites

The proposed implementation targets the existing source-free simulator approval
archive, not the endpoint-attestation connector archive. Required runtime inputs:

- macOS with one trusted OS user and an interactive external browser;
- Java 21, the clean versioned source-free approval archive and its verified
  checksum/inventory;
- a separately running numeric-loopback simulator approval service and its
  existing PostgreSQL/permit-key/operator configuration;
- one explicitly configured Native/public provider app satisfying this exact
  profile, one exact organization, the two exact callbacks, API permissions and
  distinct requester/reviewer subjects;
- an installed, operator-pinned Codex CLI version that supports HTTP MCP
  `bearer_token_env_var`; and
- private local directories for the requester config and ephemeral credential.

Node.js, pnpm and Gradle are build-time/test-time dependencies, not installed
runtime dependencies. No AWS, Jira, OpenAI API, Anthropic, provider-management or
real target credential is used. A Codex user login is separate from the MCP
requester bearer and proves neither provider identity nor endpoint integrity.

## 11. Manual Mac qualification gate

Only the Mac owner may perform the future live qualification. Use the exact
implementation commit and a freshly built source-free artifact; keep all real
provider identifiers, passwords, subjects and local paths outside Git and reports.

1. Verify archive checksum/inventory, Java 21, pinned Codex version and a clean
   dedicated PostgreSQL instance. Run the approval launcher and requester helper
   offline checks before any network or database initialization.
2. In the existing Native/public app, privately confirm no client secret, strict
   API token profile, exact organization membership/permissions and both exact
   numeric-loopback callbacks. Do not enable refresh/offline access, new grants,
   a tunnel or public listener.
3. Identify the requester and a different reviewer by their actual signed
   subjects. Configure the requester row with exactly `REQUESTER` and the reviewer
   row separately. Human operators enter passwords; no agent receives them.
4. Initialize only an empty dedicated database, start the installed approval
   service, and run requester `--login`. First exercise wrong-account SSO and
   verify no file; then deliberately select the expected requester.
5. Run requester `--codex`, inspect `/mcp`, and confirm the exact three-tool
   catalog. Request one fixed simulated change once and preserve only its opaque
   approval ID/hash and bounded status.
6. Review from the browser as the distinct reviewer. Exercise approval and a
   separate denial/withdrawal case without giving any reviewer cookie/token to
   Codex.
7. Let the requester credential expire during a pending/approved request. Confirm
   no automatic retry, explicitly re-login as the same requester, read the saved
   status, resume once, and observe replay rejection. Also confirm another subject
   cannot continue it.
8. Stop services, run strict credential cleanup, remove only the dedicated test
   database/local files, and record sanitized versions, commit, results and known
   limits. Never save HAR/network logs or screenshots containing browser URLs,
   callbacks, tokens, cookies or provider identifiers.

Missing callback `iss`, signed ID token, exact organization, strict `at+jwt`,
required scope or exact callback behavior is a stop, not a waived result. One
successful provider/Mac/Codex composition does not establish general provider,
platform or production compatibility.

## 12. Explicit non-goals and remaining limits

This design does not add client secrets, device authorization, resource-owner
password, client credentials, refresh tokens, dynamic client registration,
public discovery, remote ingress, target credentials, real mutations, prompt
attestation, executable signing, endpoint posture proof, OS-user isolation,
central revocation, hardware-backed custody, managed audit retention or HA abuse
controls. It does not modify PRs 70/72/73/74 or claim those open producer slices
are merged.

The standards basis for the proposal is
[OAuth 2.0 for Native Apps (RFC 8252)](https://www.rfc-editor.org/rfc/rfc8252),
[PKCE (RFC 7636)](https://www.rfc-editor.org/rfc/rfc7636),
[authorization-response issuer identification (RFC 9207)](https://www.rfc-editor.org/rfc/rfc9207),
[OAuth security best current practice (RFC 9700)](https://www.rfc-editor.org/rfc/rfc9700),
and [OpenID Connect Core](https://openid.net/specs/openid-connect-core-1_0.html).
Provider behavior remains a live gate under the existing
[Auth0 qualification runbook](../runbooks/auth0-native-oidc-qualification.md).
The child setup relies on the official
[Codex MCP configuration contract](https://developers.openai.com/codex/mcp),
but installed-version compatibility must be tested rather than inferred from the
documentation.
