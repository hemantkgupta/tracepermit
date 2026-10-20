# Local browser approval inbox

Requirements: FR-003/007/008/009; SEC-004/005/006/009/012/015; REL-002/004.
See [ADR 0036](../architecture/0036-local-browser-approval-boundary.md).
All targets are fixed simulators. This is not a public deployment profile.

## Quick synthetic browser qualification

Prerequisites: Node 22+, pnpm 11.25.0, Java 21 and running Docker.

```bash
pnpm install --frozen-lockfile
pnpm demo:approvals:browser
```

Open the printed numeric-loopback `/approvals` URL. The command builds React,
launches a **test-classpath-only** synthetic IdP/account picker and disposable
PostgreSQL, then starts the real Java browser/API/approval/simulator composition.
It does not use Google, another personal account, a real tenant or AWS credentials.
It injects synthetic token exchange/key resolution for qualification; the normal
operator runtime cannot select this issuer or account picker from JSON.

1. Sign in as `alice` (requester). Request the default `sg-demo`, port 443,
   `192.0.2.0/24` change. Show **pending** and **not executed** with exact arguments.
2. Sign out. Sign in as `carol` (viewer). Inspect the same request; there are
   no request, decision or execution controls.
3. Sign out. Sign in as `bob` (reviewer). Inspect target, arguments, requester,
   policy/version, expiry and request hash. Click **Approve exact action**, then
   **Confirm approve**. Show **approved**, still **not executed**.
4. Sign out. Sign in as `alice`. Click **Execute approved simulation**, then
   **Confirm execute**. Show **consumed** and **succeeded**. Refresh; execution
   does not become available again. The server atomically claims single use.
5. Optional: sign in as `dave` (requester + reviewer), request a new change.
   Self-review is unavailable and is rejected by the API even if called directly.
6. For a separate fresh pending request, Alice can select **Withdraw request**,
   inspect ID/hash, and choose **Back** without a mutation or **Confirm withdrawal**
   for terminal `cancelled` / `not_executed`. This requires `approvals.cancel`;
   reviewer/viewer access alone is insufficient. It cannot stop approved work.
   Use the [withdrawal walkthrough](pending-approval-withdrawal.md) for details.
7. Press Enter in the launcher terminal to stop and discard its database and
   synthetic keys. Do not use qualification data as retained audit evidence.

Signing in a second tab replaces the host cookie for all tabs on that origin.
Use sequential sign-out/sign-in or separate browser profiles for concurrent roles.
Pending approvals expire after five minutes; do not approve an old recording run.

The inbox updates loaded pending/approved expiry labels without pressing Refresh.
When a lifetime elapses, review/withdraw/execute controls and an open confirmation become
unavailable. Stored `pending` or `approved` status remains visible with an expired
label, not silently replaced by a new durable status. A click between UI clock
ticks also rechecks eligibility before POST. No expiry timer sends a decision,
executes a target or automatically reloads a request. Local clock checks are
advisory, may be delayed in background tabs, and do not replace Java checks;
focus/visibility changes resample time. Refresh explicitly to inspect server state.

## Operator-owned IdP configuration

The existing [approval API setup](authenticated-approval-api-foundation.md)
still applies. Build React with `pnpm build`. Add this optional object to your
private operator JSON, using your approved provider endpoints, dedicated
confidential client secret file and **absolute** built console directory:

```json
{
  "browser": {
    "authorizationUri": "https://idp.example.invalid/authorize",
    "tokenUri": "https://idp.example.invalid/oauth/token",
    "clientSecretFile": "browser-client.secret",
    "consoleDirectory": "/absolute/checkout/apps/console/dist"
  }
}
```

This fragment is not a standalone configuration; the other fields remain
required. The client secret must be a private current-user-owned POSIX regular
file (400/600), 16+ characters, no control characters, at most 4,096 UTF-8 bytes;
one terminal LF is allowed. Relative references resolve from the JSON directory.
Never commit it. The built assets are trusted operator input, not user uploads.

Register exactly `http://127.0.0.1:<configured-port>/auth/callback` with the
dedicated IdP client. The provider must support authorization code, S256 PKCE,
client_secret_basic and `iss` authorization response (RFC 9207). Configure its
API audience, `client_id`, tenant claim and enrolled `sub` values to match the
existing strict access-token verifier. Access tokens must be signed RS256
`at+jwt`, no more than 15 minutes; only the five approval scopes plus `openid`
are supported when browser mode is enabled. ID tokens require signed RS256,
one client audience, matching subject and nonce, and no more than 15 minutes.
No email/profile/Gmail scope or refresh token is requested. A provider account
is identity evidence, not an enrollment or reviewer grant.

```bash
export TRACEPERMIT_APPROVAL_CONFIG="/private/local-approvals/operator.json"
pnpm dev:approvals --check
pnpm dev:approvals
```

Open `http://127.0.0.1:<configured-port>/approvals`. Do not use Vite's 5173 origin
for this path: Java serves built assets and browser APIs on one exact origin.
The old development console still uses its separate Vite/read-only path.
Offline check verifies files/configuration, not DB or provider connectivity.
External IdP compatibility is **not yet live-qualified** by this slice.

## Failure and security expectations

- Signed out, expired/restarted session: sign-in required; no anonymous approval.
- Invalid state/issuer/nonce/token/code: code-only failure, no browser session.
  Retry from `/approvals`; never paste tokens or callback URLs into issue reports.
- Missing/wrong CSRF or Origin, mixed bearer+cookie, cross-site requests: reject.
- Missing current role/scope/enrollment: reject. Membership file changes need
  a restart; trusted in-process registry replacement is tested but not an admin API.
- Changed hash, expired/decided/consumed approval, current-policy mismatch:
  conflict, no automatic retry. Refresh state before deciding what to do next.
- Unknown execution/network completion: inspect durable state; never blindly
  repeat the action. The UI intentionally does not retry mutations.
- Logout invalidates the local session only; it does not log out of the IdP
  globally or revoke the underlying provider token. No refresh is performed.
- IdP/JWKS failures deny authentication; PostgreSQL failures prevent authority
  changes/dispatch. The browser session is not itself an execution permit.

## Verification

```bash
pnpm check
pnpm test:approval-api --rerun-tasks
pnpm format:check
pnpm docs:links
```

`SimulatorApprovalBrowserTest` covers real HTTP cookies, synthetic signatures,
PostgreSQL and safe simulator behavior. `BrowserIdTokenVerifierTest` covers
the separate ID-token profile, and the HTTPS transport test covers token POST
encoding, exact destination and credential-preserving redirect rejection.
Rendered browser checks must exercise the steps above; API-only tests do not
prove the UI renders or submits the correct action.
