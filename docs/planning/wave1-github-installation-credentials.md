# Wave 1 — GitHub installation credential foundation

Status: exclusive cloud writer claim and bounded implementation plan; no runtime
or live-provider qualification yet.

- Frozen main/base: `f23dc22f056058d7977715e0878c284728d10d17`.
- Branch: `codex/github-app-installation-credentials`.
- Packet/protocol source: `codex/github-operation-binding` at
  `f239d89a49d0f5b150684175c9a2128a42445ed1`.
- Reserved decision: ADR0076. Runtime/tests belong in the new GitHub
  `credentials` subpackage; existing ports/action/adapter behavior stay intact.
- Exclusive writer: this cloud lane until its final explicit release. Other
  lanes and private `agentcore-work/**` remain untouched/unmaterialized.

## Plan and trust decisions

1. Preserve immutable operator App/installation/repository/owner configuration,
   an explicit private signing-key source and the existing repository allowlist.
   Use existing Java/OkHttp/signing/SSRF facilities; no dependency addition.
2. Sign bounded RS256 App JWTs. Verify App/installation/account identity with
   the JWT-authenticated installation GET before one restricted token POST.
   Require typed response permissions and exactly one pinned repository rather
   than treating the POST request as provider confirmation. Missing optional
   assurance fields fail closed; no universal GitHub response compatibility claim.
3. Production transport pins GitHub's fixed HTTPS origin, platform TLS, bounded
   public DNS, no redirects/retries, byte bounds and a total exchange budget.
   Synthetic HTTP injection remains package-private and numeric-loopback only.
4. No cache/background refresh, public endpoint, target dispatch, operation
   bridge, credential installation or live call. Fixed errors have no dependency
   causes/suppression; key/JWT/token material never appears in diagnostics/files.
5. Test positive/exact exchange, identity/permission/expiry/type/byte failures,
   key/sign/transport/cleanup failures, concurrent isolation and sentinel
   redaction. Run existing GitHub regressions, one broad gate, format/docs/flows
   and diff checks. Preserve actual failures/skips/cache reuse; do not retry green.
6. Publish coherent checkpoints and exact-head draft-PR evidence, then release
   ownership. Eight-hour ceiling; stop on missing trust decisions or three
   consecutive no-progress attempts. No merge/rebase/force-push or paid fallback.

## Official facts reviewed before implementation

GitHub docs at `02c25d16369fe27f9bdb28d1201f6b452f0d21dd` specify RS256,
`iat` recommended sixty seconds in the past, `exp` no more than ten minutes in
the future, and App ID or client ID as issuer. The installation-token exchange
requires App-JWT authentication and supports explicit repository IDs/permissions;
tokens expire after one hour. OpenAPI at
`755ee33b4a81d51f6f07a902cce26cbec3b822f2` exposes installation identity via
`GET /app/installations/{installation_id}`. Token permissions/repositories are
optional in the schema: missing confirmation is a compatibility stop, not proof.

- [JWT contract](https://docs.github.com/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app)
- [Token exchange](https://docs.github.com/rest/apps/apps#create-an-installation-access-token-for-an-app)
- [Installation identity](https://docs.github.com/rest/apps/apps#get-an-installation-for-the-authenticated-app)

## Coordination

Open PR104 currently contains coordination packets; PR101 owns operation
lifecycle and PR95 owns OIDC. Potential shared documentation overlap includes
HANDOFF, specifications index/quality/traceability; append scoped claims only and
report overlap in the final handoff. No other runtime lane is edited.
