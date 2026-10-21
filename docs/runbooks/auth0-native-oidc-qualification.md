# Auth0 native/public-client qualification

Status: **live interactive qualification pending**. Automated tests qualify a
synthetic local protocol composition, not an Auth0 tenant. The earlier hosted
Auth0/ChatGPT tunnel evidence is a different profile and cannot qualify this
browser inbox. See [ADR 0063](../architecture/0063-explicit-public-browser-code-pkce.md).

Requirements: FR-003/007/012; SEC-002/003/004/009/011/012/015;
REL-001/004; OPS-001/004/005. Local readiness checks the database/recovery
state, not Auth0 compatibility or production readiness.

## Automated evidence

Use Java 21 JDK, Node 22+, pnpm 11.25.0 and the local Docker daemon:

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm test:approval-api --rerun-tasks
pnpm check
pnpm format:check
pnpm docs:links
```

`BrowserCodeExchangeTest`, `SimulatorApprovalConfigurationTest`,
`BrowserIdTokenVerifierTest`, `SimulatorApprovalPublicClientTest` and
`SsrfSafeHttpsJwkSetSourceTest` cover explicit authentication selection,
public form/header behavior, exact redirects, secret injection, PKCE mutation,
independent signed identity/organization checks, wrong credentials, scopes,
time, state/nonce/code substitution, callback concurrency/replay, TLS and
redirect refusal. Existing browser/API tests retain confidential/synthetic
and durable approval coverage. The public HTTP fixture injects a synthetic
token peer; separate real local-TLS tests qualify the transport. Neither
is a live Auth0 test or rendered-browser qualification.

`BrowserLoginDiagnosticsTest` and the public-client HTTP tests additionally
qualify fixed local rejection stages, the closed access-token reason allowlist,
generic browser errors, secret/canary exclusion and a failing diagnostic sink.
These diagnostics do not inspect or export provider tokens.

### Automated run on 2026-10-21

On branch `codex/auth0-native-oidc-qualification`, based on main
`bbdcc7f14233270cde275d8c3d92284d49c531ad`, frozen installation and the focused
suites passed. The full approval API command passed; final `pnpm check` passed
**1,072 Java tests across 112 suites and 68 React tests**, zero failures,
errors or skips. Aggregate coverage was **94.94% line / 79.69% branch**, above
unchanged 94/75 gates. The final public HTTP suite contains 48 tests; the code
exchange suite contains 19. Formatting and local documentation links passed.
The PR identifies the final committed tree.

The environment used Java/Javac21.0.2, Node24.19.0 and pnpm11.25.0. A JDK was
extracted outside the repository under `/workspace/.local`; no JDK/build output
is part of the change. The four `TRACEPERMIT_AUTH0_*` identifiers were absent.
No Auth0 account, tenant network request or interactive login was used, so live
compatibility remains **pending**, including native organization support,
`at+jwt` output and callback `iss`.

## Exact local-Mac procedure: pending

Run the browser and Java service on the **same Mac**. A phone browser cannot
reach `127.0.0.1` inside a cloud sandbox. Keep the service on numeric loopback;
do not publish it, add a tunnel, use port forwarding or bind a wildcard.

1. Use a dedicated Auth0 test tenant, native test application, test API and
   synthetic users only. In the Auth0 Dashboard, confirm the application is
   native/public, token endpoint authentication is `none`, and authorization
   code with S256 PKCE is supported. Do not create/copy a client secret. Do not
   use the Management API, password grant, client credentials or a production
   identity. Confirm that this tenant/plan supports the selected native
   application plus organization combination; if it does not, record the
   incompatibility and stop qualification.
2. Choose port **8085**, or replace it consistently everywhere. Register exactly:

   | Setting                   | Exact value for port 8085             |
   | ------------------------- | ------------------------------------- |
   | Callback                  | `http://127.0.0.1:8085/auth/callback` |
   | Web origin                | `http://127.0.0.1:8085`               |
   | Allowed logout return URL | `http://127.0.0.1:8085/approvals`     |

   No wildcard callbacks or `localhost` substitutions. Local logout uses the
   CSRF-protected POST `/browser/logout`; no Auth0 logout endpoint is invoked.

3. Configure the dedicated test API for RS256 and the RFC 9068 access-token
   profile, with lifetimes at most 900 seconds. Set ID-token lifetime at most
   900 seconds as well. Define only `approvals.read`, `approvals.request`,
   `approvals.decide`, `approvals.execute`, `approvals.cancel` as API permissions
   for this experiment. Grant the needed permissions to synthetic users;
   a token's role/permissions claims do not replace operator enrollment.
   Request `openid` for OIDC; it need not appear in the API access-token scope.
   Do not request email, profile, offline access or refresh tokens.
4. Prepare the dedicated local PostgreSQL database, private password file and
   runtime-generated persistent Ed25519 simulator keys using the
   [operator setup](authenticated-approval-api-foundation.md#operator-setup-macoslinux-only).
   Reuse existing installation keys; never overwrite them to make a restart
   pass. Start with `config/simulator-approvals.example.json` in a private
   directory outside Git. Keep owner-only 0600/0400 files and a 0700 directory.
   In a private editor, set root `schemaVersion` to `1.1`, port to `8085`, the
   exact issuer (including trailing slash), API audience, client ID, JWKS URI,
   `tenantClaim: "org_id"`, exact organization ID as `tenantValue`, and internal
   simulator tenant ID. Enroll the exact synthetic `sub` values for distinct
   human requester/reviewer and an optional viewer. No email-domain inference.
5. Build React with `pnpm build`. Add this browser object using the exact
   approved issuer host and absolute built console directory:

   ```json
   {
     "browser": {
       "authorizationUri": "https://test-tenant.example.invalid/authorize",
       "tokenUri": "https://test-tenant.example.invalid/oauth/token",
       "tokenEndpointAuthMethod": "none",
       "consoleDirectory": "/absolute/tracepermit/apps/console/dist"
     }
   }
   ```

   This fragment uses nonworking placeholders. The issuer must be
   `https://test-tenant.example.invalid/` and JWKS URI
   `https://test-tenant.example.invalid/.well-known/jwks.json` after consistent
   private replacement. Do not include `clientSecretFile`, `clientSecret` or
   any other secret field. The runtime consumes `TRACEPERMIT_APPROVAL_CONFIG`,
   not the `TRACEPERMIT_AUTH0_*` variables directly. If those variables supply
   identifiers, transfer them into private configuration without printing them;
   their presence is not readiness or authority.

6. Set the private configuration path and validate offline:

   ```bash
   export TRACEPERMIT_APPROVAL_CONFIG="/private/local-approvals/operator.json"
   pnpm dev:approvals --check
   # New dedicated EMPTY database only; never after restore or for retained state:
   pnpm dev:approvals --init-db
   pnpm dev:approvals
   ```

   Existing compatible installations skip initialization. Visit
   `http://127.0.0.1:8085/approvals` on that Mac. If egress is restricted, allow
   only the exact selected tenant/custom-domain host needed by authorization,
   token and JWKS endpoints; do not request a broad Auth0 allowlist.

7. Complete synthetic requester login. Verify pending/not-executed after
   creating the fixed `sg-demo`, port443, `192.0.2.0/24` simulated request.
   Sign out locally; use a separate browser profile if the IdP's own SSO
   session would otherwise select the same user. Sign in as the distinct
   reviewer, confirm the displayed exact action/hash, and approve. Verify
   approved/not-executed. Sign in as the original requester, explicitly confirm
   execution, verify consumed/succeeded and rejection of an explicit replay.
   Check viewer mutation denial and combined-role self-review denial. No real
   AWS/Jira/cloud/LLM service is connected.
8. Privately check that the actual callback supplies the exact `iss` and that
   signed tokens satisfy the strict header/claim profiles. Use code-only
   outcomes; never export HAR, tokens, cookies, complete callback URLs or test
   credentials. If callback `iss` is absent, token type is `JWT`, organization
   claims are absent, or another required claim is incompatible, **record
   failed/incompatible and stop**. Do not append `iss`, rewrite a token, loosen
   validation or call the run passed. Automated negative tests are separate
   evidence for mutation/replay controls, not live-provider observations.
   When the browser returns only `BROWSER_LOGIN_FAILED`, read the local Java
   process stderr for exactly one line shaped as:

   ```text
   BROWSER_LOGIN_DIAGNOSTIC stage=<FIXED_STAGE> reason=<FIXED_REASON>
   ```

   Use the stage to locate the compatibility boundary. `ACCESS_TOKEN` may add
   an allowlisted token-profile reason; every other unclassified failure is
   `REJECTED`. Do not enable stack traces, print exception messages, decode
   tokens, export a HAR or copy a complete callback URL. Make at most one fresh
   interactive retry after correcting a confirmed operator/provider mismatch.
   A diagnostic line records a rejection only; it is never a passing result.

9. Check local logout and service restart invalidate sessions while durable
   requests remain. Stop with Ctrl+C and verify the loopback port is released.
   Keep the test database/configuration private; retire synthetic users and
   the dedicated registration through the operator's normal test cleanup.

Record date, exact TracePermit commit, OS/JDK/browser versions, native method,
token profile, each role/status outcome and failures as sanitized facts.
Record only reviewed identifiers or redacted configuration shape, never tenant
dumps or credentials. A successful future run qualifies **only that named
test composition**. Until then, native Auth0 login, organization availability,
`at+jwt` output and RFC9207 callback compatibility remain pending.
