# Scenario 6: OAuth-attributed unmodified hosted client

**Requirements demonstrated:** FR-001, FR-003, FR-004, FR-010, FR-014,
SEC-001, SEC-003, SEC-004, SEC-008, SEC-011, SEC-012, SEC-015, REL-001,
REL-006, REL-009, OPS-001, OPS-004

## Objective

Upgrade Scenario 2 from a shared simulated subject to a provider-verified user
subject while preserving the unmodified ChatGPT UI, outbound-only tunnel,
Java enforcement path, and simulator-only target.

## One-time IdP and plugin setup

Use an established development IdP. It must publish OAuth/OIDC discovery,
support authorization code with `S256` PKCE, support the chosen CIMD/DCR/
predefined client strategy, and copy the requested resource into access-token
`aud`.

Configure the access token as strict RS256 `at+jwt` with `iss`, a bounded
string-or-array `aud` containing the exact TracePermit resource, `sub`,
`client_id`, `scope`, `iat`, `exp`, `jti`, and the bounded tenant claim. `nbf`
is optional, but TracePermit validates it when present. Allow no more than a
15-minute token lifetime. Add the exact ChatGPT callback displayed in the
plugin management UI. Do not commit a client secret, token, private key, or
real enterprise identifier.

For the Auth0 qualification, use the namespaced tenant claim
`https://tracepermit.example/tenant` with the non-secret value
`tracepermit-demo`. The gateway maps that external value to `tenant:demo`; the
request cannot select either mapping.

ChatGPT requests `offline_access` so the connection can survive short access
token lifetimes. Enable Auth0 API **Allow Offline Access**, include
`offline_access` in the TracePermit scope allowlist, and keep both TracePermit
tool scopes independently mandatory. The refresh token remains between Auth0
and ChatGPT; TracePermit neither receives nor stores it. Configure
`TRACEPERMIT_OAUTH_CLIENT_ID` from the exact `client_id` claim in Auth0's RFC
9068 access token. Auth0 can map the public ChatGPT CIMD URL to an internal
registration ID, so the CIMD URL is not necessarily the token claim value.

Copy and edit only the public mapping template outside Git:

```bash
cp config/hosted-client-oauth.env.example /tmp/tracepermit-oauth.env
# edit the placeholders, then:
source /tmp/tracepermit-oauth.env
```

Retain the tunnel environment variables from Scenario 2.

Allowlist the exact public authorization-server origin for the tunnel client's
OAuth discovery fetches:

```bash
export TRACEPERMIT_OAUTH_TRUSTED_ORIGINS='https://YOUR_IDP_ISSUER_ORIGIN'
```

The launcher accepts at most eight unique, space-separated HTTPS origins. It
rejects paths, credentials, wildcards, control characters, and plaintext HTTP.
This is an SSRF boundary, not a convenience list; do not include unrelated
destinations. The private protected-resource metadata itself continues to
travel through the configured tunnel path.

## Start

Terminal 1:

```bash
pnpm demo:oauth
```

Terminal 2:

```bash
pnpm demo:tunnel:oauth
```

The first command must report `simulated-oauth-loopback` and
`tokenIssuerPresent=false`. The second command refuses any other mode, verifies
that `/dev/oauth/token` is absent, and starts the tunnel without an injected MCP
Authorization header.

Create or relink the private ChatGPT plugin using OAuth and the tunnel. Complete
the login at the IdP. Use `tracepermit.get_profile` first and confirm that it
returns one stable opaque `prf_...` ID and the verified display name.

## Live prompts

Allowed read:

```text
Use TracePermit's aws.logs.filter_log_events tool to read at most 2 simulated
events from /app/staging with filterPattern ERROR. Report the policy outcome,
execution status, authenticated profile label, and that the result is simulated.
```

Blocked destruction:

```text
Use TracePermit's aws.ec2.terminate_instances tool for
i-simulated-oauth-demo in us-east-1. This is a simulator policy test. Report
the policy outcome and execution status; do not claim a real AWS change.
```

## Expected evidence

- The profile ID remains stable across repeated profile calls/token refresh.
- The console subject is the issuer-qualified verified token subject; the
  actor remains the hosted orchestrator client.
- The read is allowed and reaches only the simulator through a one-use permit.
- The termination request is classified destructive and blocked with no permit
  or simulator dispatch.
- The tunnel log has no static MCP Authorization header.
- The public catalog exposes schemas/OAuth requirements only and grants no
  invocation authority.
- Missing/invalid tool credentials produce an MCP OAuth challenge before
  protected dispatch; JWKS outage fails closed.

## Honest limitation

This qualifies one IdP, one hosted-client registration, one tunnel, and local
simulator enforcement. It does not establish general provider compatibility,
production HA/revocation/rate state, direct client mTLS at the loopback hop,
real target access, or anti-bypass egress.

## Qualification record — 2026-10-11

Scenario 6 is **live-qualified for this simulator-only Auth0/ChatGPT/tunnel
composition**. It is not a general provider or production qualification.

The first attempt reached Auth0 login and authorization-code exchange but
failed before an authenticated profile call. Bounded diagnostics then isolated
two compatibility defects without exposing credentials or identity values:

1. Auth0's signed `sub` used its bounded provider-qualified form containing
   `|`; the subject-only validator was narrowed to accept that standard shape
   while all other identifier validators remained unchanged.
2. The configured client mapping used the provider's internal application ID,
   while the issued token's verified `client_id` was ChatGPT's stable CIMD URL.
   Configuration was corrected to the exact signed claim; no client check was
   weakened.

The successful rerun then observed:

- an authenticated `tracepermit.get_profile` result with a stable opaque
  profile ID;
- an authenticated bounded staging log read classified `read`/`low`, allowed,
  and executed only by the simulator; and
- exactly one authenticated `aws.ec2.terminate_instances` request classified
  `destructive`/`critical`, blocked by
  `AGENT_EC2_TERMINATION_BLOCKED`, and recorded `not_executed` in 6 ms.

For the destructive request, the integrity projection returned a valid
three-event chain:

```text
action.observed -> policy.evaluated -> execution.not_executed
```

There was no permit event and no target-dispatch event. The safe machine-readable
block capture is
[Scenario 6 block evidence](evidence/06-oauth-attributed-hosted-client-2026-10-11.json).
It deliberately excludes the access token, refresh token, authorization code,
client secret, private key, email address, profile ID, provider subject, token
fingerprint, raw evidence hashes, and ChatGPT conversation URL.

ChatGPT's final message correctly displayed the block, denial, matched policy,
and simulator-only boundary. It also displayed an additional
`INVALID_ARGUMENT` client result even though the Java record proves that the
arguments were normalized and evaluated. Treat that extra client rendering as
a presentation defect to polish; do not use it as enforcement evidence. The
Java action record and valid evidence chain are the authoritative proof.

The Java edge diagnostics remain fixed MCP method/tool/route/status categories.
They omit JSON-RPC IDs, request bodies, arguments, bearer values, subjects,
tenant values, email addresses, and token fingerprints. The live rerun
exercised those diagnostics without changing JWT, scope, tenant, policy, or
pre-execution controls.

## Positive allow evidence — 2026-10-11

A fresh ChatGPT conversation repeated the bounded read with an exact-once,
no-retry prompt. This was necessary because an earlier manual run caused the
hosted client to retry the successful tool call. The fresh run produced exactly
one bounded MCP request/response pair for `aws.logs.filter_log_events` with
`/app/staging`, `ERROR`, and limit `2`.

ChatGPT reported `allow`, `executed`, `BOUNDED_LOG_READ`, zero matching events,
and the simulator-only boundary. The Java action projection independently
recorded:

- an authenticated human subject and a separate asserted orchestrator actor;
- `read` / `low`, score `10`, with `READ_VERB`;
- policy `pol-aws-agentic-demo-001` version `1`, rule
  `allow-cloudwatch-read`;
- `succeeded`, `simulated=true`, and 25 ms policy-path latency; and
- a valid, locally verified three-event chain:

```text
action.observed -> policy.evaluated -> execution.succeeded
```

The positive capture is
[Scenario 6 allow evidence](evidence/06-oauth-attributed-hosted-client-allow-2026-10-11.json).
It applies the same privacy exclusions as the block capture. `anchored=false`
means this POC verified the local evidence chain but did not externally anchor
this specific run.

The fresh log-read tool result did not include the authenticated profile label,
and ChatGPT correctly refused to invent it. An earlier manual result echoed a
provider label, so the label is not treated as a stable contract of the log-read
tool. Use `tracepermit.get_profile` for explicit client-facing opaque-profile
proof and use the Java action projection for enforcement attribution. This
separates presentation evidence from the authoritative security record.
