# Interface contracts

**Status:** Current interfaces plus target extensions  
**Applies to:** MCP, internal HTTP, control plane, and signed artifacts

## 1. Contract rules

- External data is untrusted until validated by its adapter.
- Schemas are strict at public boundaries; unknown security-relevant fields are
  rejected rather than silently trusted.
- Public schemas, signed claims, and canonicalization are versioned.
- IDs are opaque. Clients must not infer authority from ID format.
- Errors expose stable codes and safe messages, not tokens, signatures, stack
  traces, policies, or target credentials.
- Tenant and subject context come from authenticated evidence, not body fields.

## 2. Current HTTP surfaces

### Authenticated simulator approval API foundation

Pending-only requester withdrawal implements the Java API slice of
[ADR 0058](../architecture/0058-pending-approval-cancellation-contract.md), after
the [V10 upgrade prerequisite](../architecture/0059-explicit-v9-v10-simulator-upgrade.md).
[ADR 0060](../architecture/0060-authenticated-pending-request-withdrawal.md)
defines `POST /api/v1/approvals/{id}/cancel` and the browser equivalent.
Exactly `requestHash` is accepted; current requester enrollment, explicit verified
`approvals.cancel`, owner and tenant checks are required. Auth/session deadlines
and pending/request/hash checks are repeated under the PostgreSQL request lock.
Success commits cancelled/not_executed plus version3 requester-provenance lifecycle
evidence/outbox, never reviewer/permit/attempt metadata. The optional React
inbox now offers owner-only **Withdraw request** followed by **Confirm withdrawal**
([ADR 0061](../architecture/0061-requester-withdrawal-inbox.md)); **Back** only
dismisses the confirmation. No MCP cancellation tool exists. Normal startup
still validates V1–V10 without migration. Installed withdrawal qualification is
separate from the built-source browser flow.

A separately composed `SimulatorApprovalServer` exposes pending/read/review/
execute routes under `/api/v1/approvals`. It binds only to IPv4 loopback,
verifies the existing RS256 access-token profile, and combines per-call scopes
with operator-owned human/workload enrollment and roles. It accepts only
bounded simulated security-group arguments, never client-authored trusted
identity or a canonical action envelope. Decision/execute bodies bind the
exact returned request hash; execution reloads the immutable stored action.

PostgreSQL V6 commits that operational snapshot with the pending approval and
evidence/outbox metadata. It remains separate from the argument-free audit
ledger. Bearer routes reject Cookie and Origin headers. Neither existing demo
launcher starts these routes. See the
[API runbook](../runbooks/authenticated-approval-api-foundation.md) and
[ADR 0034](../architecture/0034-authenticated-loopback-approval-api.md).

Admission is shared by the separate listener's bearer, browser and MCP routes,
not a new authorization decision. The executor defaults to eight active lanes
and sixteen pending tasks; capacities are constructor-only, validated within
1..64 and 1..1024. Overflow is a JDK transport refusal before handler entry:
clients must not expect a JSON 503, retry contract or rate-limit headers.
An exchange that reaches a handler during close can receive the existing
`503 SERVER_SHUTDOWN`; new connections are otherwise refused. Host/URI/closed
checks run after admission. Task limits do not bound accepted TCP connections
or buffered bytes, and close is bounded rather than a drain or target-outcome
guarantee. No public abuse-control contract is added. See
[ADR 0050](../architecture/0050-bounded-simulator-approval-admission.md).

The collection GET now accepts exact `status`, `toolName`, `approvalRequestId`,
bounded `limit` and unsigned `cursor` parameters. Responses add `pageSize`,
nullable `nextCursor` and per-item `createdAt`. Every page re-derives tenant/owner
authority before a bounded read-only PostgreSQL query and existing redacted
projection. All other approval routes and MCP envelopes retain query rejection.
This is approval history, not full action search or snapshot export. See the
[history contract](../runbooks/approval-history-api.md) and
[ADR 0046](../architecture/0046-authenticated-approval-history-pagination.md).

The optional `pnpm dev:approvals` command starts only this separate local API
from private operator JSON. `--check` validates offline, not IdP/DB readiness.
Its numeric-loopback listener/database, one-client/tenant enrollment, persistent
Ed25519 files, fixed default-block policy and migration behavior are specified
in [ADR 0035](../architecture/0035-local-approval-operator-bootstrap.md), with
schema lifecycle superseded by [ADR 0045](../architecture/0045-explicit-database-initialization.md):
`--check` is offline; `--init-db` installs only fresh empty state without
starting a listener; normal run validates existing state without implicit DDL.
Failures return exit 2/code-only redacted diagnostics; no public init, repair,
upgrade or recovery-resume HTTP/MCP endpoint is added. Local owner-only
`--upgrade-db` supports exactly stopped-service V9→V10, with predecessor
validation, transactional migration and final strict validation, not repair or resume.
Owner-only `--recovery-inspect` emits fixed-format `APPROVAL_RECOVERY_SNAPSHOT`
JSON (`schemaVersion: 2.0`, database `transactionStartedAt`, `recoveryMode`,
execution/approval stored-status counts and `reconciliationQueued`) followed by
an `INSPECTION_ONLY` caveat. It is installation-wide, read-only and non-migrating,
without per-tenant/request data, expiry refresh, target lookup or authority change.
Format2 adds `approvals.cancelled`; it is not database contract10 or an API
version change. Existing approval projections remain format1.0.
Failures emit no partial report. Ordinary runtime SELECT privileges are not
sufficient. See [ADR 0052](../architecture/0052-owner-only-recovery-inspection.md).

`--recovery-status` and owner-only `--quarantine-db` operate without a listener,
IdP or target against existing V1–V10 state. The latter is installation-wide,
one-way containment of new durable claims/reviews, not automatic rollback
detection or cancellation. Already quarantined state refuses operator `--run`;
already-running replicas retain reads/completion but authority writes return
their existing generic unavailable errors. Internal permit minting is unchanged.
There is no resume command; only the explicit V9→V10 upgrade is supported.
Other predecessors remain offline. See [ADR 0051](../architecture/0051-operator-declared-recovery-quarantine.md)
and the [runbook](../runbooks/operator-recovery-quarantine.md).

The approval listener also exposes exact `GET /health` and `GET /ready` local
projections. Both retain strict numeric-loopback/Host enforcement, require no
authentication, reject query, Authorization, Cookie and Origin, and return only
`{"schemaVersion":"1.0","service":"tracepermit-simulator-approval","status":...}`.
Health returns `live` while the listener handles requests. Readiness returns
`ready` only when the exact PostgreSQL schema validates and recovery state is
`ready`; dependency failure returns HTTP 503 `not_ready` without a reason field.
It does not probe the IdP or grant authority. The `/mcp` surface remains a
separate authenticated route. See [ADR 0062](../architecture/0062-loopback-container-install-profile.md).

### Optional local browser approval boundary

`SimulatorApprovalMain` accepts an optional strict `browser` object with
`authorizationUri`, `tokenUri`, `clientSecretFile` and `consoleDirectory`.
Java serves the built React inbox at `/approvals`. `GET /auth/login` starts
code/S256 PKCE with state, nonce and a browser-binding cookie;
`GET /auth/callback?code=...&state=...&iss=...` consumes the login state and
verifies separate access/ID token profiles before creating an in-memory session.
No reusable token is returned to React. `GET /browser/session` returns opaque
principal, internal tenant, current roles/scopes, expiry and session CSRF value.
`POST /browser/logout` invalidates the local session.

`/browser/api/v1/approvals` and its detail/decision/execute suffixes use the
same bodies/projections and shared authorization/approval logic as the bearer
API, but require a current server-held session. Browser mutations require
exact Origin plus `X-CSRF-Token`; bearer headers are forbidden. Unknown or
duplicate callback parameters fail. No CORS, proxy header trust, refresh,
public TLS profile, administrator endpoint or real target is added.
See [ADR 0036](../architecture/0036-local-browser-approval-boundary.md) and the
[browser runbook](../runbooks/browser-approval-inbox.md).

React's local expiry guidance does not change this wire contract or server
authority. One shared UI clock updates loaded pending/approved expiry and removes
stale confirmation controls; an immediate pre-POST guard rechecks selection,
ID/hash, current role/scope, session lifetime, status and expiry. No timer issues
an approval mutation or automatic refresh. Clock skew/background throttling can
delay the advisory display; Java rechecks every received action independently.

### Local durable approval MCP (separate composition)

The approval listener additionally accepts authenticated `POST /mcp`, independent
of the hosted OAuth/default development MCP listeners below. It exposes only
`tracepermit_request_simulated_change` (groupId/port/cidr),
`tracepermit_get_approval` (approvalRequestId) and
`tracepermit_resume_approval` (approvalRequestId/requestHash). There is no agent
review tool. Current REQUESTER role plus request/read/execute scope respectively
gate discovery and invocation. The API/browser/MCP paths converge on the same
request/execute methods, exact snapshots, application service and PostgreSQL claim.
MCP canonical actions preserve configured `cli` origin and `mcp` protocol, not
an invented API origin, human trigger or endpoint attestation. Local policy v2
explicitly reviews this single known mutation from `api` and `cli` only.

Pending and approved return `not_executed`; explicit owner resume loads the
snapshot, rechecks original client/origin, current reviewer, expiry and policy
before claiming once. A changed hash or alternate HTTP client cannot bypass
binding. JSON tool results contain both text and `structuredContent`; valid
tool failures set `isError=true` and a safe code. Notifications return 202,
responses are stateless JSON; no SSE/GET/DELETE/batch/cursor support is provided.
The bearer-only loopback boundary rejects browser Cookie/Origin, `cnf` without
supported proof, and source-attestation headers. This does not replace the
source-attested CLI or public TLS profiles. See
[ADR 0037](../architecture/0037-mcp-durable-simulator-approval-composition.md) and
the [Codex runbook](../runbooks/codex-durable-approvals.md).

### `GET /health`

Returns service state and active policy identifier/version. It is not a deep
dependency readiness guarantee.

### `GET /ready`

Returns a versioned snapshot for authentication, policy, permit signing, replay
storage, and evidence storage. It returns `200` only when all are ready and
`503` otherwise. The response contains bounded dependency names and states, not
credentials, endpoints, or configuration values.

### `POST /mcp`

Public MCP Streamable HTTP boundary.

Required controls:

- allowed Host and Origin validation;
- Bearer authentication before MCP dispatch;
- request body size limit;
- tool discovery filtered by trusted scope;
- independent authorization on `tools/call`; and
- strict per-tool argument validation.

The development `cli-readonly` profile additionally requires the
`tracepermit-source-attestation` header. The gateway verifies the compact
Ed25519 JWS against an independently configured endpoint-key registry, binds it
to the authenticated connector fingerprint and canonical MCP method/params,
and atomically consumes its token ID before MCP SDK dispatch. Web profiles must
not send this header and receive no fabricated endpoint evidence.

Current tools:

```text
aws.logs.filter_log_events
aws.ec2.terminate_instances (strictly simulated; policy-blocked for web/CLI)
```

Current arguments:

```json
{
  "logGroupName": "/app/prod",
  "filterPattern": "ERROR",
  "limit": 20
}
```

Only `/app/prod` and `/app/staging` are accepted. The MCP proposal limit is
1–1,000 so policy can observe an oversized request. A value above 100 produces
`OVERSIZED_RESULT_WINDOW`; the reviewed transformer clamps it to 100, creates
a new canonical event, and requires a second explicit allow. The simulator
itself still accepts only 1–100. Identity, target account, assurance, sequence,
sanitization, policy, and approval fields are invalid arguments.

The active policy schema keeps version `1.0` and adds optional
`match.requiredSignals`: every named operational signal must be present for
the rule to match. Semantic-intent labels are explanatory projection data;
policy rules in this slice match stable operational signals rather than model
prose.

An executed sanitized MCP response reports the initial `sanitize` decision,
the final `allow` decision, the allowlisted transformer ID, bounded change
labels, and effective event/hash metadata. It never returns raw credentials or
stores raw tool arguments in evidence. Unavailable, failing, or non-converging
sanitization remains not executed.

The simulated termination tool accepts only 1–10 IDs matching
`i-simulated-*` and `us-east-1` or `us-west-2`. It is normalized as a
destructive production action and reaches policy, whose explicit deny rule
prevents executor invocation. An unregistered tool is instead rejected at the
MCP boundary and does not reach policy.

When dispatcher enforcement capacity is saturated, proposals fail closed
without queueing. The tool call returns an MCP tool error (`isError: true`) with
`error: "ENFORCEMENT_CAPACITY_EXCEEDED"`, `execution.status: "not_executed"`,
and does not invoke the enforcer or target.

### Java MCP stdio connector

The development endpoint executable reads one UTF-8 JSON-RPC object per line
from stdin and writes one JSON-RPC response per line to stdout. It processes
frames sequentially, preserves string or integer request IDs, treats EOF as a
clean shutdown, and supports only:

```text
initialize
notifications/initialized
tools/list
tools/call
```

The maximum input frame is 64 KiB. Blank lines are ignored. CRLF and a final
frame without a newline are accepted. Malformed input, invalid request shape,
unsupported methods, connector failure, and oversized input return bounded
JSON-RPC errors where a response is permitted. Operational diagnostics are
code-only on stderr; stdout never contains startup text or logs.

Every accepted frame is independently observed, credential-bound, signed, and
sent as an HTTP `POST /mcp` with `Authorization: Bearer` and
`tracepermit-source-attestation`. Only `http://127.0.0.1`, `http://[::1]`, or
`http://localhost` with exact path `/mcp` is valid. Userinfo, query, fragment,
HTTPS, non-loopback hosts, redirects, excessive timeouts, requests above 64
KiB, and responses above 256 KiB fail closed.

### `POST /dev/oauth/token`

Loopback-only development endpoint. It accepts a fixed development profile and
returns a short-lived Bearer token. It must not be exposed by a non-loopback or
production runtime.

### Development protected-resource metadata

The loopback MCP listener publishes the same bounded JSON document at:

```text
GET /.well-known/oauth-protected-resource
GET /.well-known/oauth-protected-resource/mcp
```

The path-aware form supports clients that derive RFC 9728 discovery from the
MCP resource path. The document names the exact loopback `/mcp` resource, the
simulated development server, its two read-only discovery scopes, and the
`header` Bearer method. It intentionally omits `authorization_servers`: the
Scenario 2 no-auth plugin uses a short-lived development bearer injected on the
local tunnel-client hop, not an end-user OAuth flow.

This public metadata is compatibility and diagnostic information only. It
does not authenticate the tunnel, identify the signed-in ChatGPT user,
authorize a tool, or replace the normal pre-parse Bearer check.

### `POST /v1/evaluate`

Trusted internal adapter interface accepting a canonical `InterceptedAction`.
It validates and evaluates without execution.

### `POST /v1/execute`

Trusted internal adapter interface accepting a canonical `InterceptedAction`.
It evaluates, issues an execution permit only after allow, and invokes the
permit-verifying simulator.

`/v1/*` is disabled on non-loopback deployments because request-body schema
validation does not authenticate identity evidence.

### Signed scheduled-workload foundation

There is currently no network route for this boundary. The in-process Java
adapter receives an untrusted scheduled action plus compact credentials and a
separately constructed `TrustedScheduledActionContext`.

`tracepermit-workload-identity+jws` uses canonical JSON, unpadded base64url,
`alg=EdDSA`, an operator-trusted key ID, a maximum 300-second lifetime, and an
exact `tracepermit-scheduled-action-v1` binding. A delegation uses
`tracepermit-delegation-grant+jws`, a maximum 900-second lifetime, and optional
parent ID plus exact compact-parent SHA-256 hash. The chain maximum is eight.

The adapter rejects unknown fields, versions, algorithms, types, issuers, keys,
audiences, trust/revocation states, injected trusted fields, binding mismatch,
replay, incomplete topology, and authority amplification before canonical
normalization. See [ADR 0014](../architecture/0014-signed-workload-identity-and-delegation.md)
for the trust boundary and `contracts/` for the claim schemas.

### Production OAuth/OIDC and remote transport foundation

The transport-neutral Java authenticator accepts
`Authorization: Bearer <compact-JWT>` and
implements `oauth-access-token-jwt-rs256-v1`: exact `alg=RS256`, `typ=at+jwt`,
safe `kid`, operator-configured issuer/JWKS/audience/client/tenant/scope
mapping, signature and time verification, and fingerprint-only projection. The
audience is either one bounded string or a bounded array of one to four unique
strings containing the exact configured resource. `iat` and `exp` are required;
optional `nbf` is validated when present. Malformed or excessive audience sets
fail closed.

Key resolution preserves the configured issuer/JWKS trust pair. A fresh cached
key avoids network waits; refreshes coalesce per pair with instance-local
capacity and monotonic deadlines. Defaults are four refresh workers, sixteen
pending refreshes and a five-second waiter budget; the default shared DNS
resolver has eight workers and sixteen pending tasks. DNS elapsed time is
deducted from the configured HTTP call budget. Capacity exhaustion,
interruption, timeout, unavailable key material or a coalesced result expired
at the joining caller's trusted time fail closed with
`OIDC_KEY_SET_UNAVAILABLE`; no expired-key fallback is authorized. Failure
cooldowns survive waiter departure, and an older operation cannot reinstall
cooldown over a newer success. Token claim time is re-sampled after key
resolution/signature verification; a token that expires during that work
receives `EXPIRED_ACCESS_TOKEN` rather than identity.

These defaults do not change adapter-specific unavailable projections or expose
upstream payloads/credentials. They are not a universal HTTP-response deadline:
thread scheduling and uninterruptible OS DNS can outlive a waiter within finite
worker capacity. Neither distributed abuse state nor new live-client/IdP
compatibility is established. See the [refresh runbook](../runbooks/jwks-refresh-isolation.md).

The portable protected-resource metadata contract is RFC 9728-shaped and is
intended for the configured `/.well-known/oauth-protected-resource` URL:

```text
resource
resource_name
authorization_servers[]
scopes_supported[]
bearer_methods_supported=[header]
```

The Java direct-TLS adapter publishes this metadata and returns a bounded RFC
6750 Bearer challenge with
`resource_metadata`, exact scopes, and optionally `error=invalid_token`.
Metadata and challenges contain no tokens, tenant mapping, JWKS payload, or
verification internals and do not authorize invocation.

The remote adapter accepts `POST /mcp` only after exact Host/Origin policy and
Bearer verification. Authentication occurs before bounded strict-UTF-8 JSON
decoding. It supports `initialize`, `notifications/initialized`, `ping`, and
scope-gated `tools/list`. The remote OAuth transport implementation `HandshakeOnlyMcpDispatcher` retains
its `REMOTE_TOOL_INVOCATION_NOT_CONFIGURED` response. The remote MCP enforcement implementation
`EnforcingMcpDispatcher` additionally supports `tools/call` through an exact
invocation-scope check, strict per-tool arguments, server-owned canonical
normalization, policy, request-bound permit issue/verification/consumption,
and the simulator. Block, approval, sanitize, unknown, malformed, and
dependency-failure paths do not dispatch. No real target is wired. Its
ChatGPT/Claude-shaped cases are synthetic conformance fixtures, not live
external-product qualification.

### Hosted-client OAuth loopback profile

The simulator-only hosted-client profile publishes the same metadata at both
well-known routes, but the document contains the configured HTTPS resource and
authorization server. `POST /mcp` permits bounded `initialize`, initialized
notification, `ping`, and static `tools/list` responses without identity. The
catalog contains schemas, annotations, and OAuth requirements only; it contains
no user or tenant data and grants no invocation authority. Every `tools/call`
requires a provider-issued access token matching
`oauth-access-token-jwt-rs256-v1` before protected dispatch. `GET /health` and
`GET /ready` report
`mode=simulated-oauth-loopback` and `tokenIssuerPresent=false`.

This profile deliberately returns `404` for `/dev/oauth/token`. A missing or
invalid credential on `tools/call` returns an MCP error result with bounded
`_meta["mcp/www_authenticate"]`, `resource_metadata`, OAuth error, and error
description fields. Other protected requests retain the bounded HTTP `401`
challenge. Provider/JWKS unavailability receives `503`. The tunnel launcher
supplies no static MCP Authorization header.

Every listed tool declares an OAuth `securitySchemes` array and truthful
annotations. `tracepermit.get_profile` accepts `{}` only and returns the
current verified profile in `structuredContent` plus a JSON text item:

```json
{
  "id": "prf_<opaque-stable-value>",
  "name": "<verified-provider-display-name>"
}
```

The caller cannot select an ID, email, tenant, or subject. The profile tool is
read-only and never enters policy, permit issuance, or target dispatch.

The call envelope permits the optional standard `params._meta` object used by
live MCP clients for progress and correlation. TracePermit treats every value
inside it as untrusted transport metadata, ignores it for identity and
authority, and excludes it from canonical actions and evidence. Other unknown
call-parameter fields remain invalid.

The hardened remote-edge implementation hardened profile additionally requires an access token carrying
an exact RFC 8705 `cnf.x5t#S256` confirmation and a matching direct TLS peer
leaf certificate for `POST /mcp`. The token signature is verified before the
confirmation value is trusted, and certificate matching occurs before the MCP
body is read. Protected-resource metadata remains public. The profile applies
a network-peer rate limit before authentication, a verified-principal limit
after sender binding, and an exact fail-closed readiness snapshot. These are
injected library contracts; no production runtime is configured.

## 3. Canonical action contract

The canonical action contains:

```text
schemaVersion
eventId, observedAt, traceId
identity.subject, optional actor/createdBy, evidence[]
trigger
optional declaredIntent
action.protocol, toolName, arguments, target
optional verified source device/process/trigger projection
lineage.hops[], lineage.delegations[]
clientClaims
```

Adapters construct trusted fields. Policy and executors consume the canonical
contract and do not reinterpret raw transport input.

## 3A. Verifier-only simulator executor stdio

The separate one-shot executor accepts exactly one JSON document on stdin:

```text
schemaVersion = 1.0
permit = compact signed execution permit
action = exact canonical action object
```

No other field is accepted. In particular, issuer, audience, algorithm, key,
allowlist, credentials and verified/approved state cannot be supplied by the
request. Operator configuration owns the Ed25519 public key and exact trust
context. Duplicate keys, trailing documents, more than 65,536 input bytes,
more than 16,384 permit characters, unknown fields and any action that changes
during canonical normalization fail closed.

Success returns `schemaVersion`, `status`, `mode`, `eventId`, `permitId`,
`system`, `operation` and `resultHash`. It never returns the compact permit,
raw arguments or raw simulator result. Public failures contain only a stable
code. `--check` is offline; `--execute` opens an existing compatible PostgreSQL
schema and exposes no listener. See
[ADR 0064](../architecture/0064-verifier-only-simulator-executor-foundation.md).

## 3B. Java development control-plane reads

The Java development adapter exposes these loopback-only reads:

```text
GET /health
GET /ready
GET /api/v1/overview
GET /api/v1/actions
GET /api/v1/actions/{eventId}
GET /api/v1/simulator/state
```

The overview counts include all current in-memory projection records. Its
latency fields deliberately describe only actions observed after the current
Java process started serving live requests:

```text
p95LatencyMs
latencySampleSize
latencySampleScope=current_run
```

Seeded historical fixtures are excluded from the current-run latency sample.
A sample size of zero means that no live latency measurement is available; it
must not be rendered as a measured zero-latency result.

The action list accepts only `decision`, `severity`, `origin`, `q`, and `limit`
query parameters. The action projection omits raw arguments, results,
credential fingerprints, delegation token hashes, and client claims. Every
demo response is marked simulated. See
[current-state baseline](03-current-state-baseline.md) for the local
contract and security limitations.

This surface has no remote authentication or tenant authorization. It refuses
non-loopback binding and provides no mutation endpoint. It is not the target
enterprise control-plane API described below.

The initial React console calls only the overview and action-list GET routes.
Its deny and approval controls are disabled and do not construct HTTP requests.

## 4. Current error semantics

| Code                                       | Meaning                                                      | Execution state      |
| ------------------------------------------ | ------------------------------------------------------------ | -------------------- |
| `INVALID_ACTION`                           | Schema, identity, or lineage validation failed               | `not_executed`       |
| `MISSING_BEARER_TOKEN`                     | Authentication header missing/invalid form                   | No dispatch          |
| `MALFORMED_ACCESS_TOKEN`                   | Token encoding or JSON cannot be safely parsed               | No dispatch          |
| `INVALID_ACCESS_TOKEN`                     | Token claims or signature invalid                            | No dispatch          |
| `EXPIRED_ACCESS_TOKEN`                     | Token lifetime elapsed                                       | No dispatch          |
| `OIDC_CONFIGURATION_UNAVAILABLE`           | Trusted provider registry could not answer                   | No dispatch          |
| `OIDC_KEY_SET_UNAVAILABLE`                 | Configured OIDC verification keys unavailable                | No dispatch          |
| `AUTHENTICATION_REQUIRED`                  | Remote OAuth credential is missing                           | No dispatch          |
| `AUTHENTICATION_UNAVAILABLE`               | Remote OAuth trust dependency is unavailable                 | No dispatch          |
| `INVALID_SENDER_CONSTRAINT`                | Token certificate binding is absent or does not match        | No dispatch          |
| `RATE_LIMITED`                             | Network or authenticated-principal quota is exhausted        | No dispatch          |
| `ABUSE_CONTROL_UNAVAILABLE`                | Required rate/abuse state cannot be claimed                  | No dispatch          |
| `REMOTE_EDGE_NOT_READY`                    | A hardened deployment prerequisite is unavailable            | No dispatch          |
| `INVALID_HOST`                             | Remote request Host is outside the exact allowlist           | No dispatch          |
| `ORIGIN_NOT_ALLOWED`                       | Present remote request Origin is not allowed                 | No dispatch          |
| `REQUEST_TOO_LARGE`                        | Remote MCP body exceeds its configured bound                 | No dispatch          |
| `INVALID_MCP_REQUEST`                      | Remote MCP JSON/framing is malformed or ambiguous            | No dispatch          |
| `UNSUPPORTED_MEDIA_TYPE`                   | Remote MCP body is not JSON                                  | No dispatch          |
| `REMOTE_TOOL_INVOCATION_NOT_CONFIGURED`    | Remote foundation intentionally has no tools/call path       | No dispatch          |
| `REMOTE_CONNECTOR_ORIGIN_NOT_SUPPORTED`    | Connector origin requires another trusted adapter            | No dispatch          |
| `TOOL_NOT_FOUND`                           | Remote tool is not registered                                | No dispatch          |
| `INVALID_TOOL_ARGUMENTS`                   | Remote tool arguments violate the exact schema               | No dispatch          |
| `REMOTE_ENFORCEMENT_FAILED`                | Remote action failed safely inside enforcement               | Not executed/unknown |
| `MISSING_SOURCE_ATTESTATION`               | Required CLI source proof is absent                          | No dispatch          |
| `MALFORMED_SOURCE_ATTESTATION`             | Source JWS encoding is malformed                             | No dispatch          |
| `INVALID_SOURCE_ATTESTATION`               | Source signature, claims, or binding is invalid              | No dispatch          |
| `EXPIRED_SOURCE_ATTESTATION`               | Source proof lifetime elapsed                                | No dispatch          |
| `SOURCE_ATTESTATION_NOT_ALLOWED`           | A web profile attempted to supply endpoint proof             | No dispatch          |
| `SOURCE_TRIGGER_NOT_INTERACTIVE`           | Current CLI profile lacks interactive evidence               | No dispatch          |
| `SOURCE_ATTESTATION_REPLAYED`              | Source proof token ID was already consumed                   | No dispatch          |
| `SOURCE_ATTESTATION_UNAVAILABLE`           | Required endpoint verifier/replay port is absent             | No dispatch          |
| `INVALID_SCHEDULED_ACTION`                 | Scheduled envelope or trusted-field boundary invalid         | No dispatch          |
| `MALFORMED_WORKLOAD_CREDENTIAL`            | Workload compact encoding is malformed                       | No dispatch          |
| `INVALID_WORKLOAD_CREDENTIAL`              | Workload signature, trust, claims, or binding invalid        | No dispatch          |
| `EXPIRED_WORKLOAD_CREDENTIAL`              | Workload proof lifetime elapsed                              | No dispatch          |
| `WORKLOAD_KEY_REVOKED`                     | Registered workload-verification key is revoked              | No dispatch          |
| `WORKLOAD_CREDENTIAL_REVOKED`              | Workload credential token is revoked                         | No dispatch          |
| `WORKLOAD_REVOCATION_UNAVAILABLE`          | Workload revocation status is unknown                        | No dispatch          |
| `WORKLOAD_CREDENTIAL_REPLAYED`             | Workload token ID was already consumed                       | No dispatch          |
| `MALFORMED_DELEGATION_CREDENTIAL`          | Delegation compact encoding is malformed                     | No dispatch          |
| `INVALID_DELEGATION_CREDENTIAL`            | Delegation signature, trust, claims, or binding invalid      | No dispatch          |
| `EXPIRED_DELEGATION_CREDENTIAL`            | Delegation lifetime elapsed                                  | No dispatch          |
| `DELEGATION_KEY_REVOKED`                   | Registered delegation-signing key is revoked                 | No dispatch          |
| `DELEGATION_REVOKED`                       | Delegation grant is revoked                                  | No dispatch          |
| `DELEGATION_REVOCATION_UNAVAILABLE`        | Delegation revocation status is unknown                      | No dispatch          |
| `INVALID_DELEGATION_CHAIN`                 | Complete chain/topology/narrowing/leaf check failed          | No dispatch          |
| `ENDPOINT_OBSERVATION_UNAVAILABLE`         | Required process/session facts could not be read             | No dispatch          |
| `ENDPOINT_EXECUTABLE_CHANGED`              | Observed executable changed before forwarding                | No dispatch          |
| `ENDPOINT_KEY_UNAVAILABLE`                 | Development signing provider is unavailable                  | No dispatch          |
| `CONNECTOR_CREDENTIAL_UNAVAILABLE`         | Connector credential is absent or unsafe                     | No dispatch          |
| `GATEWAY_UNAVAILABLE`                      | Loopback gateway could not be reached safely                 | No dispatch          |
| `AUTHORIZATION_DENIED`                     | Authenticated identity lacks invocation authority            | `not_executed`       |
| `SANITIZER_NOT_CONFIGURED`                 | Policy required unavailable transformation                   | `not_executed`       |
| `EXECUTION_PERMIT_ISSUANCE_FAILED`         | Allow could not become execution authority                   | `not_executed`       |
| `TARGET_EXECUTION_FAILED`                  | Executor or target path failed                               | `failed`             |
| `EXECUTION_ALREADY_IN_PROGRESS`            | Same logical event is currently running                      | `executing`          |
| `EXECUTION_PREVIOUSLY_FAILED`              | Prior attempt has a confirmed failure                        | `failed`             |
| `EXECUTION_OUTCOME_UNKNOWN`                | Prior target outcome requires reconciliation                 | `unknown`            |
| `DURABLE_STATE_UNAVAILABLE`                | Required claim/evidence transaction failed                   | Not dispatched       |
| `DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH` | Outcome could not be durably recorded after dispatch         | `executing`/unknown  |
| `SECURITY_DEPENDENCY_UNAVAILABLE`          | A required enforcement dependency is unavailable             | `not_executed`       |
| `ENFORCEMENT_CAPACITY_EXCEEDED`            | Saturated enforcement capacity fails closed without queueing | `not_executed`       |

Production evidence should retain a more precise internal reason while public
responses stay bounded.

## 5. Execution permit contract

Current claims:

```text
schemaVersion, permitId
issuer, audience, subjectId
eventId, evaluationId, actionHash
decision.outcome=allow, policyId, policyVersion
issuedAt, expiresAt, maxUses=1
```

The current compact token uses an HS256 header with an ephemeral key. The
enterprise format must add an explicit canonicalization profile and use a
reviewed asymmetric/KMS-backed signing configuration.

### Standalone Java consumption adapter

`ExecutionPermitAuthority.verifyAndConsume` snapshots the action before verifying
its hash. The explicit-tenant overload rejects null, invalid or different tenant
IDs with `PERMIT_TENANT_MISMATCH` before a claim. Both overloads route to the
organization in that same verified action, not a configured default namespace.
The PostgreSQL adapter rejects a missing/invalid organization and requires the
verified canonical action hash for live claims; no-hash overloads fail closed.
Legacy in-memory ports retain global replay tracking, not tenant-aware isolation.

Standalone consumption and pruning own fresh/exclusive DataSource leases and
commit before returning. SQL/commit/close failures never return success; an
ambiguous acknowledgement can leave a consumed row and is not permission to
retry or dispatch. Caller-owned/thread-bound transactions are unsupported.
These Java ports do not authenticate body claims or expose a new remote API.
The existing durable execution/approval services instead consume permits with
execution state and evidence in their established atomic transaction; do not
pre-consume through this adapter. See
[ADR 0041](../architecture/0041-postgres-durable-permit-consumption.md).

### Bounded process-local replay ports

The no-arg `InMemorySourceAttestationReplayStore`,
`InMemoryWorkloadIdentityReplayStore` and `InMemoryPermitConsumptionStore` use
65,536 retained-entry slots and a 64-removal expiry budget per valid unexpired
offer. Positive constructor overrides support smaller local profiles/tests;
non-positive values are rejected. The boolean SPI and caller wiring are unchanged.
An expired offer is refused without inserting authority state. A duplicate
neither extends expiry nor adds index metadata. Saturation returns `false`
without evicting live records, so the existing `SOURCE_ATTESTATION_REPLAYED`,
`WORKLOAD_CREDENTIAL_REPLAYED` and `EXECUTION_PERMIT_REPLAYED` refusals can also
mean local capacity refusal, not only a previously seen ID.

Expired records still behind the budget can conservatively refuse reuse; a
genuinely new key can use reclaimed space before the whole backlog drains.
Cleanup requires subsequent valid unexpired offers, not a timer. Source keys
are issuer/token, workload keys tenant/issuer/token, and permit IDs remain global
across legacy overloads. These per-instance ports trust the supplied time and
do not provide byte quotas, bounded lock waits, restart/durable recovery,
distributed replay state, revocation or public saturation metrics. See
[ADR 0054](../architecture/0054-bounded-local-replay-stores.md).

## 6. Audit evidence contract

The current internal evidence record contains:

```text
payload:
  schemaVersion, evidenceEventId, streamId, sequence, kind
  occurredAt, recordedAt, producer
  trace/event/evaluation/permit/attempt/idempotency correlation
  subjectId, actionHash, policy, decision, execution status/reason
  credentialFingerprints[]
integrity:
  algorithm=sha256, previousEventHash, eventHash
```

The contract intentionally has no arbitrary payload field. Raw action
arguments, target results, access tokens, and reusable credentials are not
accepted. A valid local chain does not establish semantic truth or prove that
the chain was not wholly replaced; production verification requires an
independently retained signed checkpoint.

### Durable Java persistence projection

The Java `DurableEnforcementState` port accepts a trusted tenant separately
from the canonical action. Its PostgreSQL adapter stores bounded permit,
attempt, idempotency, policy, transition, evidence-hash, and outbox fields.
There is no raw-action, raw-result, compact-token, source-attestation, private-
key, or reusable-credential persistence field. This is an internal application
port, not a remotely callable control-plane API.

### Java approval application contracts

The request-bound approval implementation adds version-2 language-neutral schemas for an approval request, a
trusted reviewer decision, and an approval-derived execution permit. The
request binds tenant, request/action hashes, event/evaluation, subject,
actor-lineage hash, immutable policy ID/version, original
`require_approval`, tool/resource hash, expiry, and `maxUses=1`.

The permit retains `decision.outcome=allow` for executor compatibility and
adds this signed object:

```text
authorization.kind=human_approval
authorization.approvalRequestId
authorization.approvalDecisionId
authorization.originalPolicyOutcome=require_approval
```

These are application/domain and persistence contracts. No HTTP request may
construct `TrustedReviewerContext` directly. The separate authenticated
approval API derives that context after verification and role/scope checks.
The optional browser inbox can review only through that boundary; the default
development read console remains GET-only.

The Scenario 3 development harness composes these contracts directly inside a
local Java process and supplies a fixed trusted reviewer from a separate
development adapter. Its terminal keypress is only a presentation pause. The
harness creates no network approval contract and therefore does not implement
any of the target endpoints below.

### Evidence anchor receipt v1

The portable `evidence-anchor-receipt-v1` schema contains only
`schemaVersion`, tenant/outbox/stream/sequence/event binding, `payloadHash`,
provider/key/anchor identifiers, `anchoredAt`, exact `Ed25519` algorithm, and
an unpadded base64url signature. The signing input is UTF-8 lines prefixed by
`tracepermit-evidence-anchor-v1` in that exact field order. Unknown fields,
versions, algorithms, malformed hashes/signatures, or unconfigured
provider/key pairs fail closed.

## 7. Target control-plane APIs

Exact OpenAPI shapes will be specified with their implementation slice. The
target resources are:

```text
POST   /api/v1/actions/evaluate
POST   /api/v1/actions/{eventId}/execute
GET    /api/v1/actions/{eventId}
GET    /api/v1/actions

POST   /api/v1/approval-requests
GET    /api/v1/approval-requests/{id}
POST   /api/v1/approval-requests/{id}/decisions

POST   /api/v1/policies/{policyId}/versions
POST   /api/v1/policy-versions/{id}/simulate
POST   /api/v1/policy-versions/{id}/activate

GET    /api/v1/execution-attempts/{id}
POST   /api/v1/execution-attempts/{id}/reconcile
GET    /api/v1/evidence
```

Control-plane APIs require tenant-aware authorization distinct from an agent's
tool-execution authority.

The Java `PolicyLifecycleService` delegates to an in-memory or optional PostgreSQL
store and does not implement the policy mutation endpoints listed above. Its current runtime profile requires
policy versions 1..2147483647 and rule priorities -2147483648..2147483647;
negative priorities are allowed. Unsupported numeric values fail closed before
catalog mutation; they are not truncated to a different identity or precedence.
The frozen policy JSON schema retains its wider language-neutral safe-integer
envelope. Widening the Java profile requires an end-to-end compatibility decision,
not a local integer cast. Returned lifecycle documents are defensive copies,
not writable references to active/staged/rollback state.

The durable store uses trusted caller workspace names and optional expected
active-version preconditions, not HTTP authentication or ETags. Null means an
unconditional serialized transition; comparison is by version, not an
ABA-resistant activation generation. First activation is serialized even before
an active row exists. Immutable revision identity/content and lifecycle history
persist through Flyway V7; lifecycle state/timestamps can change. Store writes
own exclusive transactions, including on auto-commit-disabled leases; idempotent
activation does not acquire a nested connection. Transaction/commit/close errors
do not report success or automatically retry. A failure can follow a durable
commit, so read the pointer/history before deciding what to do next. See the
[lifecycle runbook](../runbooks/durable-policy-lifecycle.md).

The listed approval endpoints remain target APIs. The request-bound approval implementation deliberately does
not expose them until production reviewer authentication, tenant roles, CSRF/
session behavior, concurrency/idempotency responses, and mutation audit are
specified and implemented.

## 8. Pagination and concurrency

- List APIs use opaque cursor pagination.
- Mutable control-plane resources use version/ETag or explicit expected-version
  checks.
- Approval decisions and permit claims are idempotent by client request ID.
- Duplicate idempotent requests return the original result or a conflict with
  a safe reference.

## 9. Durable outcome-recording uncertainty

After dispatch, a completion exception produces
`DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH` even if commit may have succeeded.
The direct and approval services make one additional same-tenant/attempt unknown
recording; the PostgreSQL operation accepts only executing state and atomically
records evidence/outbox and a lookup queue entry. Already committed succeeded,
failed or unknown state is not overwritten. No target retry, replacement claim
or restored approval authority is implied. A persistent outage/crash can still
leave executing state; no automatic worker or restore fence is added. See
[the completion runbook](../runbooks/durable-completion-uncertainty.md).

## 10. Protocol compatibility

The current MCP implementation supports the 2026-07-28 protocol and a stateless
request/response fallback for 2025-era clients. Legacy session GET/DELETE,
resumability, and server-initiated notifications are outside the POC.

Connector compatibility tests must pin observed handshake behavior before a
remote deployment is declared supported.
