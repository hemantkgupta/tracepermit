# Bounded Okta OIDC qualification

**Status: synthetic foundation only. No live tenant/login or EKS deployment
has been qualified.** Requirements: FR-003/007/012;
SEC-002/003/004/009/011/012/015; REL-001/004/009; OPS-002/004/005.
See [ADR0067](../architecture/0067-bounded-okta-oidc-qualification.md).

## Provider facts and evidence sources

Reviewed official Okta documentation at repository commit
`9ae26597a7053082f210865011cddbd762a5d887` and OpenID Connect source at
`ba6bbf1dc37d7ce207028d1dc7b14f5082f7c471`:

- [Okta authorization servers](https://developer.okta.com/docs/concepts/auth-servers/):
  org-server access tokens are for Okta; a custom authorization server is the
  resource-server integration boundary. Confirm the chosen tenant has the
  required API Access Management entitlement and an applicable access policy;
  no free-plan, licensing or tenant-availability assumption is made.
- [Okta code/PKCE guide](https://developer.okta.com/docs/guides/implement-grant-type/authcodepkce/main/):
  choose Native Application/public authentication, S256, no secret; register
  the exact loopback redirect. Its documented token exchange omits Basic auth.
- [Okta groups claim guide](https://developer.okta.com/docs/guides/customize-tokens-groups-claim/main/):
  custom-server claims can support API authorization. Org-server groups only
  in an ID token do not satisfy this profile. Configure a narrow managed-group
  allowlist, rather than exporting the directory's entire group list.
- [Okta access-token validation](https://developer.okta.com/docs/guides/validate-access-tokens/main/)
  and [OIDC ID-token validation](https://openid.net/specs/openid-connect-core-1_0.html#IDTokenValidation):
  verify signatures and trusted issuer/audience/time/client bindings. Parsed
  JSON or a group name is not authority. Documentation does not establish
  interoperability with a particular registration.

These sources were read through their official repositories because the cloud
network does not allow the documentation hosts directly:
[Okta source](https://github.com/okta/okta-developer-docs/tree/9ae26597a7053082f210865011cddbd762a5d887/packages/@okta/vuepress-site/docs)
and [OIDC source](https://github.com/openid/connect/blob/ba6bbf1dc37d7ce207028d1dc7b14f5082f7c471/openid-connect-core-1_0.xml).
No network policy or provider setting was changed.

## Implemented contract

Only operator schema `1.2`, profile `okta-custom-as-jwt-rs256-v1`, is enabled
for this candidate. Schemas1.0/1.1 preserve their confidential/Auth0 behavior.
Copy the existing private simulator operator configuration described by the
[distribution runbook](simulator-approval-distribution.md), retaining its local
database, runtime-generated permit keys and explicit subject memberships.
Change only the following non-secret-shaped template **inside the private
operator file**; all values below are synthetic and unusable against Okta:

```json
{
  "schemaVersion": "1.2",
  "oidc": {
    "profile": "okta-custom-as-jwt-rs256-v1",
    "issuer": "https://idp.example.invalid/oauth2/synthetic",
    "audience": "https://approval.example.invalid",
    "jwksUri": "https://idp.example.invalid/oauth2/synthetic/v1/keys",
    "clientId": "synthetic-native-client",
    "tenantClaim": "tracepermit_tenant",
    "tenantValue": "pilot-synthetic",
    "tenantId": "simulated-alpha",
    "groupsClaim": "groups",
    "groupRoleMappings": {
      "pilot-requesters": "REQUESTER",
      "pilot-reviewers": "REVIEWER",
      "pilot-viewers": "VIEWER"
    }
  },
  "browser": {
    "tokenEndpointAuthMethod": "none",
    "authorizationUri": "https://idp.example.invalid/oauth2/synthetic/v1/authorize",
    "tokenUri": "https://idp.example.invalid/oauth2/synthetic/v1/token",
    "consoleDirectory": "console"
  }
}
```

This fragment is not a complete runnable operator file. Keep the required
`port`, `postgres`, `permit` and `memberships` fields from the existing private
configuration. There is no bind-host, redirect override or secret field.
Claim names and group mappings are required, closed and operator-owned;
requester and reviewer mappings are mandatory. Each group maps to one of
REQUESTER/REVIEWER/VIEWER. One to 100 unique groups may be present in a token,
but **exactly one** may match the mapping. Both signed tokens must resolve to
the same role. Membership must independently enroll that role for the exact
issuer-qualified subject/client/tenant; no user is created by a group claim.

The selected access grammar requires RS256/`JWT`, a key ID, exact single HTTPS
API audience distinct from the client ID, `cid`, unique bounded `scp` array, JTI, subject, custom tenant
and temporal claims. Standard `client_id`/`scope` aliases are refused in this
profile. ID tokens independently require single client audience, nonce,
subject/client/tenant pairing, time and matching mapped group. Both signatures
use only the operator's exact JWKS destination and existing bounded rotation
rules. Unknown/incompatible shapes fail; there is no provider fallback.

## Synthetic qualification

Use Node 22+, Corepack/pnpm 11.25.0, Java 21 and Docker. Test signing keys are
generated at runtime; PostgreSQL is disposable. HTTP IdP peers/resolver
injection exist only on the test classpath. The actual Java browser API and
durable simulator approval boundary are exercised. No tenant is contacted.

```bash
corepack enable
pnpm install --frozen-lockfile
source scripts/configure-java-environment.sh
java/gradlew -p java :identity:test --tests '*OktaOidcQualificationTest' :adapters-http:test --tests '*Okta*'
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

Expected: requester can request and explicitly execute only after distinct
review; reviewer can decide but not execute/request; viewer cannot mutate;
dual enrollment narrows to one signed mapped role. Invalid identity/group,
scope, enrollment, callback, replay and mixed-auth inputs produce bounded
denials and no unauthorized durable/target effect. Existing Auth0 tests must
still pass. Record counts and any cached/skipped cases in the handoff.

## Live operator gate — requires separate interactive approval

Do not mutate an Okta tenant, assign groups, change a registration or initiate
a live login without explicit interactive owner approval. No such approval or
live exercise is assumed here. After approval, a local operator must:

1. Select one dedicated synthetic Native/public registration and custom
   authorization server, with code/S256 and token authentication `none`.
   Privately record exact issuer/client/audience and endpoint facts. Use an
   explicit HTTPS API audience; the common `api://default` example is outside
   this existing TracePermit audience constraint.
2. Register exactly `http://127.0.0.1:<configured-port>/auth/callback`, with no
   wildcard/localhost/alternate host. Configure only the required `openid`,
   `groups`, `approvals.read/request/decide/execute/cancel` vocabulary and
   access/ID lifetimes no longer than fifteen minutes. Do not request offline
   access or a refresh credential.
3. Configure the same AS-controlled tenant claim/value and narrow managed-group
   claim in **both** access and ID tokens. Tenant binding must not be a
   self-editable user attribute or email/domain inference. Use distinct
   requester, reviewer and viewer synthetic accounts and explicit matching
   local memberships. The signed group only narrows that local membership.
4. Privately prepare the owner-only operator/secret/key files and dedicated
   loopback PostgreSQL schema using the existing runbook. Keep configuration
   mode 0600. Build the console, then run offline validation before any login:

   ```bash
   pnpm --filter @tracepermit/console build
   TRACEPERMIT_APPROVAL_CONFIG=/absolute/private/operator.json pnpm dev:approvals --check
   ```

   Expected: `APPROVAL_CONFIGURATION_VALID`, explicitly offline only. Initialize
   only a verified-empty dedicated schema using the existing lifecycle guide;
   never reuse a production database. Then run the same command with `--run`.
   Observe only the exact `127.0.0.1:<port>` listener and open `/approvals`
   locally. Do not expose it through EKS ingress, port forwarding or a tunnel.

5. Stop if the selected server omits exact callback `iss`, emits an unsupported
   token header/claim shape, omits either tenant/group binding, cannot enforce
   the required lifetime, or supplies different signed groups. Do not edit,
   manufacture, decode in an external service, or normalize a token to pass.
   Use only TracePermit's fixed local rejection stage/reason as diagnostics.
6. Exercise login for all three roles, requester pending action, independent
   reviewer decision, owner-only single execution/replay denial, missing scope,
   wrong/missing/multiple mapped group and mismatched tenant/client cases.
   Check group removal using fresh tokens. Also remove local enrollment and
   restart to verify old sessions are invalid and approvals remain durable.
   Use separate browser profiles; never export cookies, credentials or traces.
7. Stop the process normally, observe listener release, restart and verify
   re-login is required. Retire synthetic accounts/database/keys through the
   approved operator process; provider cleanup itself needs the same explicit
   approval boundary. Do not mark this guide's live gate passed from synthetic
   results or a successful offline check.

## Freshness, evidence and untested boundaries

An issued token retains its signed groups. Provider group removal does not
invalidate an old session immediately: sessions expire within ten minutes and
API tokens within fifteen. Missing groups in fresh tokens deny sign-in. Local
enrollment removal is rechecked on each request; changing the installed private
membership configuration requires restart. Approval execution rechecks local
reviewer enrollment, not current Okta groups. Already admitted work may finish.
No provider introspection, refresh, push revocation or account linking exists.

Retain only a private, bounded evaluation record: source/artifact commit and
checksum, profile/schema versions, role labels, case names, fixed pass/fail
codes, simulator result, shutdown/port-release observations and whether an
interactive approval was obtained. Exclude tokens/hashes, codes, PKCE material,
cookies, raw claims, group names, provider/customer identifiers, passwords,
keys, callback URLs, private paths and operator JSON. Never dump environments.

No live Okta tenant, real target, EKS listener/ingress, cross-device workflow,
installed Okta archive, ADR0065 requester/Codex helper or broad Okta/OIDC
compatibility is qualified. Existing Auth0 evidence remains separately scoped.
