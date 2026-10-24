# Security threat model

**Status:** Normative baseline; revisit for every real adapter  
**Applies to:** All TracePermit deployments

## 1. Security objectives

1. Prevent unauthorized target effects.
2. Prevent authority amplification across agents and services.
3. Bind policy and approval to the exact executed request.
4. Prevent replay and cross-service credential use.
5. Preserve honest, correlated, non-secret evidence.
6. Contain compromise through least privilege and separate trust boundaries.

## 2. Protected assets

- Human and workload identities
- Delegation and approval authority
- Policy documents and activation state
- Permit signing keys and verification configuration
- Target credentials and network routes
- Canonical actions and sensitive arguments
- Audit/evidence history
- Tenant isolation and configuration
- Availability of the enforcement path

## 3. Adversaries and failures

- Malicious or prompt-injected agent
- Compromised client, connector, gateway, executor, or administrator
- Tenant attempting cross-tenant access
- Insider with database or operations access
- Stolen/replayed token, approval, delegation, or permit
- Misconfigured policy or broad target credential
- Network attacker or forged Host/Origin request
- Race, crash, timeout, dependency outage, or stale cache

## 4. Threat register

| ID    | Threat                                                                                             | Primary controls                                                                                                                                                       | Residual concern                                                                            |
| ----- | -------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| T-001 | Client injects `authenticated=true` or a different subject                                         | Strict public schemas; adapter-owned identity; SEC-001                                                                                                                 | Trusted adapter compromise                                                                  |
| T-002 | Tool visible through discovery is called without authority                                         | Per-invocation scope and policy check; FR-004                                                                                                                          | Incorrect scope mapping                                                                     |
| T-003 | Valid token replayed to another service                                                            | Java RS256 validation plus hardened RFC 8705 mTLS certificate binding; SEC-004/016                                                                                     | Live connector/profile and certificate lifecycle pending                                    |
| T-004 | Child delegation expands authority                                                                 | Java Ed25519 complete-chain verification, parent hash, subset/lifetime checks; FR-011                                                                                  | In-memory trust/revocation/replay only                                                      |
| T-005 | Request changes after policy or approval                                                           | Versioned canonical request hash; SEC-005                                                                                                                              | Cross-language canonicalization drift                                                       |
| T-006 | Single-use permit used concurrently                                                                | PostgreSQL tenant/permit unique claim in Java durable profile; SEC-006                                                                                                 | Java development profile remains process-local                                              |
| T-007 | Gateway bug invokes a different target operation                                                   | Executor verification, adapter allowlist, least-privilege credential                                                                                                   | Compromised executor with broad credential                                                  |
| T-008 | Agent bypasses TracePermit                                                                         | Credential custody, restricted egress, target-side policy; SEC-008                                                                                                     | Unmanaged shadow credentials                                                                |
| T-032 | Publisher or attacker forges or mutates an external evidence acknowledgement                       | Exact signed receipt, operator-owned anchor trust, durable binding recheck, append-only storage; SEC-018                                                               | Compromise of both producer and external anchor trust                                       |
| T-033 | Worker crash or race loses or duplicates evidence publication                                      | Transactional outbox, stable idempotency key, bounded lease, `SKIP LOCKED`, retry state; REL-007                                                                       | Non-idempotent external sink can observe duplicate calls                                    |
| T-034 | Reconciler retries an effect or invents a terminal outcome                                         | Lookup-only port, exact durable binding, leases, confirmed-only append; SEC-019/REL-008                                                                                | Target without trustworthy lookup remains unknown                                           |
| T-009 | Secret leaks through evidence or logs                                                              | Fingerprints, redaction, secret scanning; SEC-003/012                                                                                                                  | Sensitive business arguments                                                                |
| T-010 | DBA rewrites audit history                                                                         | External signed checkpoints, immutable storage; SEC-010                                                                                                                | Compromised checkpoint service                                                              |
| T-011 | Compromised app uses KMS as signing oracle                                                         | Separate signer identity/service, constrained signing context, external sequence                                                                                       | Authorized false future events                                                              |
| T-012 | Target succeeds but response is lost                                                               | Durable idempotency, unknown queue, lookup-only reconciliation worker; REL-003/005/008                                                                                 | Production target may lack trustworthy lookup                                               |
| T-013 | Security dependency outage leads to fail-open                                                      | Readiness gate plus component fail-safe behavior; REL-001                                                                                                              | Stale health signal; availability impact                                                    |
| T-014 | Cross-tenant object reference                                                                      | Tenant in identity, keys, queries, claims, and authorization; SEC-009                                                                                                  | Operator/system account scope                                                               |
| T-015 | SSRF through dynamic target or webhook                                                             | Server-owned target registry, egress allowlist, no arbitrary URLs                                                                                                      | Reviewed extensibility mechanisms                                                           |
| T-016 | Policy rollback or stale cache accepts unintended action                                           | Signed/versioned policy, expiry, rollout state, evaluation evidence                                                                                                    | Emergency rollback coordination                                                             |
| T-017 | Development issuer exposed remotely                                                                | Loopback enforcement and deployment gate; SEC-011                                                                                                                      | Packaging/configuration regression                                                          |
| T-018 | Denial of service through large/expensive requests                                                 | Body limits, strict schemas, pre/post-auth rate ports, timeouts; SEC-017                                                                                               | Shared/distributed abuse state and approval flooding                                        |
| T-019 | Client forges device, OS, process, or human-trigger evidence                                       | Enrolled endpoint key, trusted observer, adapter-owned canonical fields; FR-016                                                                                        | Compromised endpoint observer                                                               |
| T-020 | Source attestation is replayed or copied to another request                                        | Request/credential binding, short expiry, atomic token claim; FR-017/SEC-014                                                                                           | Durable multi-replica store not implemented                                                 |
| T-021 | Corporate device is mistaken for enterprise provider login                                         | Separate OS, enterprise, device, and provider-account evidence                                                                                                         | Provider may not expose end-user identity                                                   |
| T-022 | Endpoint connector is redirected or used as an SSRF proxy                                          | Exact loopback `/mcp` URI, no redirects, bounded HTTP transport                                                                                                        | User-controlled local service                                                               |
| T-023 | Stdio framing or diagnostics leak secrets                                                          | Pre-allocation frame bound; protocol-only stdout; code-only stderr; redaction tests                                                                                    | Host process/environment visibility                                                         |
| T-024 | Endpoint executable changes after observation                                                      | Hash/start/path/name observation and immediate pre-dispatch stability check                                                                                            | Same-process compromise; no code-signing policy                                             |
| T-025 | Agent forges, self-grants, mutates, or replays human approval                                      | Trusted reviewer context, exact binding, expiry, separation of duties, atomic PostgreSQL consume+claim                                                                 | No production reviewer adapter/API yet                                                      |
| T-026 | Request self-asserts workload identity or omits/widens a grant                                     | Operator key registry, exact run/action binding, complete chain, leaf mapping, atomic workload replay                                                                  | No production scheduler/durable trust services                                              |
| T-027 | Token, redirect, or DNS sends verification to attacker/internal JWKS                               | Exact operator URI, no token URL, global-address DNS policy/pinning, direct TLS, no redirect/proxy/retry, bounded cache                                                | Egress firewall and production deployment pending                                           |
| T-028 | Remote request bypasses identity through Host/Origin/parser behavior                               | Direct TLS, exact Host/Origin, readiness/rate/sender checks before strict JSON dispatch                                                                                | Reverse proxy and live qualification pending                                                |
| T-029 | Authenticated remote caller injects target/authority or bypasses policy                            | Exact tool schemas, server-owned canonical mapping, application port, policy then request-bound permit and simulator checks                                            | Process-local permit/evidence; anti-bypass egress pending                                   |
| T-030 | Stolen remote token is replayed by a different TLS client                                          | Issuer-signed certificate thumbprint matched to direct peer leaf certificate before parsing                                                                            | Certificate custody/revocation and connector support                                        |
| T-031 | Abuse-control or deployment dependency fails and traffic continues                                 | Exact hardened readiness plus fail-closed limiter errors                                                                                                               | No production probes/exporter or shared limiter yet                                         |
| T-035 | Tokens, arguments, identities, or attacker-controlled values leak through telemetry                | Typed event allowlist, bounded values/cardinality, no payload field, OTel projection tests                                                                             | Production exporter/configuration review pending                                            |
| T-036 | Required telemetry fails and an authorized request still dispatches                                | Pre-dispatch event is required; failure returns 503 with zero dispatch                                                                                                 | Async exporter delivery still needs a live health probe                                     |
| T-039 | Individually allowed reads form a rapid extraction sequence                                        | Gateway-owned tenant/subject/target history, bounded window, higher-priority sequence rule, fail-closed state                                                          | POC store is process-local and resets on restart                                            |
| T-040 | Sanitizer executes the original, widens authority, or bypasses policy                              | Immutable original, allowlisted transformer, distinct event/hash, full re-evaluation, effective-action permit/idempotency                                              | Only one POC transformer; production registry pending                                       |
| T-041 | Lower-trust tool-result text impersonates instructions and induces an IAM action                   | Tool result remains data; signed adapter verifies the new structured proposal; Java classifies, blocks, and issues no permit                                           | POC uses a deterministic emulator, not a real model; unmediated tools remain a risk         |
| T-042 | A mutable MCP registry adds or replaces a tool after security review                               | Trusted registration, duplicate/reserved-name rejection, frozen dispatcher snapshot, per-tool scope and canonical enforcement                                          | Code-defined tool implementation remains part of the trusted computing base                 |
| T-043 | Concurrent new rate-limit keys bypass the configured capacity                                      | Atomic new-key admission, per-key claims, conditional expiry cleanup, contention regression test                                                                       | Process-local limiter is not shared production abuse state                                  |
| T-044 | An integer-format change silently alters signed canonical v1 bytes                                 | Frozen v1 method/vectors; exact-integer helper separate and inactive for signed artifacts                                                                              | A future profile needs explicit versioning and cross-language qualification                 |
| T-045 | Container packaging publishes a local listener, embeds secrets, or silently replaces retained keys | No published ports; Linux host network plus exact loopback binds; non-root/read-only app; private-file allowlist and no-replacement setup; image/runtime qualification | Docker daemon/root or same-operator compromise; unsupported Desktop/bridge/proxy deployment |
| T-046 | Agent attempts pull request creation on unauthorized GitHub repository or non-main base branch     | Strict repository allowlist (`tracepermit/agentcore-pr-pilot`), strict base branch allowlist (`main`), fail-closed validation; FR-002/SEC-002                          | Compromised trusted provider or operator allowlist misconfiguration                         |
| T-047 | Agent attempts to create non-draft pull request or inject unknown API fields                       | Strict `draft: true` invariant enforcement (`DRAFT_REQUIRED_TRUE`), unknown argument and target field rejection; SEC-002                                               | Upstream model intent drift                                                                 |
| T-048 | External PR creation times out or disconnects and re-execution duplicates effect                   | Stable operation key, ambiguous timeout or oversized 201 marked UNKNOWN, unavailable GET retains UNKNOWN, zero automatic re-execution; FR-015/SEC-019/ADR 0069         | Process-local ledger; durable integration and general absence semantics pending             |
| T-049 | GitHub App installation token or credential leaks through evidence, logs, or network redirects     | Trusted credential provider, token masked in memory, exception message redaction, NO_PROXY, disabled redirects, evidence fingerprints only; SEC-003/012/015            | Host machine compromise of trusted secret provider                                          |

## 5. Trust-boundary controls

### Lifecycle proposals are not authority

The optional `BusinessOperationTransitionPlanner` returns only candidate data.
Its optimistic state token is not signed authority, and no label in a proposal
proves a policy decision, permit consumption, target result or publication receipt.
Exact scope/state comparisons and v1 append validation reject stale/malformed
plans; they do not authenticate or arbitrate simultaneous writers. The immutable
registration repository rejects progressed proposals on all three test engines.
There is no public planner route or target dependency. A future durable lifecycle
service must recheck current authority and state under its transaction before
committing an append; no executor may trust a caller-supplied proposal. See
[ADR 0071](../architecture/0071-uncommitted-operation-transition-proposals.md).

### GitHub confirmation and lookup bounds

POST confirmation and GET reconciliation read at most 65,536 decoded bytes plus
one overflow byte, including chunked bodies without a length header, before JSON
parsing. Responses close on overflow/read failure. A 201 with an oversized body
may already represent a committed PR, so it records `unknown` and blocks another
POST. A rejected/unavailable lookup (401/403/404, redirect, oversize or transport
failure) cannot prove that earlier effect absent. It returns `unknown` with a
fixed reason, no confirmed result and no effect retry (SEC-015/019; REL-003/008).
ADR0073 additionally requires a separate trusted immutable target/precondition
record, exact production origin/platform TLS/public DNS pinning and GET
installation-access/repository/ref checks before dispatch. Contradictory target
aliases cannot hide behind one allowlisted field. Positive confirmation requires
typed IDs, full repository/owner, exact refs/SHAs/draft/state/content. Empty,
duplicate, paginated, unavailable and incomplete lookup results remain unknown;
no older cached result is exposed after a failed lookup. The installation ID
still depends on trusted credential provenance, and mutable refs can race after
GET. The process-local ledger is not durable or an exactly-once service. See
[ADR0073](../architecture/0073-github-pilot-target-preflight.md).
This is automated fake-server qualification, not production GitHub assurance.

Opt-in endpoint observation capture introduces a local diagnostic-file privacy
boundary (FR-016/017, SEC-003/012). Default-disabled capture uses an existing
canonical owner-only POSIX directory, exclusive 0600 files, file/parent identity
and link/mode checks, bounded records/bytes and allowlisted serialization. It
records no credentials, signed proofs, raw arguments, device ID or executable
path. A requested-capture failure stops forwarding instead of silently dropping
the evidence. These files are editable owner-controlled diagnostics, not gateway
acceptance or independently immutable evidence. Same-user compromise and hostile
ancestor-directory races remain outside the development boundary. See
[ADR 0038](../architecture/0038-private-endpoint-observation-capture.md).

### Client to connector

- TLS, Host/Origin validation, request size limits, rate limits
- OAuth/OIDC or workload authentication
- For the hardened remote profile, issuer-signed mTLS certificate binding
  matched to the direct TLS peer before protocol parsing
- Pre-authentication network and post-authentication principal abuse controls;
  required state failure denies rather than bypasses the limit
- Operator-owned OAuth provider/JWKS/client/tenant/scope mapping; token headers
  cannot select a network URL or embedded verification key
- Exact allowlisted JWKS HTTPS URI, global-address-only DNS, request-pinned
  resolution, no redirect/proxy/retry, and bounded time/response
- No trusted identity fields in the request body

### Endpoint agent to source connector

- Independent enterprise identity and endpoint-key enrollment
- OS/process facts derived by a trusted observer, not MCP arguments
- Request-bound, short-lived asymmetric source attestation
- Connector-credential fingerprint binding and atomic replay prevention
- Unknown provider-account context remains unknown
- Endpoint keys and target credentials are never exposed to the agent process
- Development transport is loopback-only, no-redirect, size/time bounded, and
  cannot select a target URL from MCP input

### Connector to policy

The optional in-memory/PostgreSQL policy lifecycle library validates a private candidate
snapshot, rejects numbers outside its explicit Java `int` version/priority
profile, and copies every returned revision document. API callers cannot change
active decisions or future rollback content through a mutable JSON reference,
or alias a large version into an existing catalog key (FR-013, REL-006, SEC-002).
Regression tests preserve block decisions, stored hashes and zero simulator
dispatch on attempted mutation. These are trusted in-process APIs, not an
authenticated operator surface or a sandbox against compromised JVM code.
PostgreSQL V7 persists immutable revision identity/content and transition history.
First activation is serialized before any active row exists; SQL/unchecked
failures roll back pointer/history, and commit/close failures cannot report
success or automatically retry. Workspace filters require trusted caller
selection; they are not RLS or authorization. Current-version preconditions
are not ABA-resistant generations. Actor/reason metadata is caller supplied,
and database triggers are not protection against a privileged administrator.
Authenticated rollout and pending-approval integration remain separate work. See
[ADR 0039](../architecture/0039-policy-lifecycle-staging-rollback-and-simulation.md).

- Versioned canonical action
- Explicit tenant, subject, actor, trigger, target, and assurance
- Deterministic validation before classification
- Prior tool-result text and declared goals remain lower-trust data; any
  resulting structured action crosses the same adapter and policy boundary

### Policy/approval to executor

The standalone Java consumption authority uses a private, hash-verified action
snapshot for tenant routing and rejects an explicit tenant mismatch before
storage. The PostgreSQL adapter requires action provenance and commits its own
bounded consumption/pruning transaction before success. The unwired MariaDB
adapter provides the same standalone claim boundary for two exact qualified
engine versions but deliberately omits pruning until referenced state exists.
Commit/close uncertainty is failure, not retry authority; a row may already
exist. Tests cover partition switching, concurrent claims and transaction
failures with no reported-success effect. The existing durable
execution/approval claim path is unchanged and remains atomic with evidence;
the standalone adapter must not pre-consume its permit. These trusted Java APIs
are not remote authentication or database-admin isolation. See
[ADR 0041](../architecture/0041-postgres-durable-permit-consumption.md) and
[ADR 0066](../architecture/0066-mariadb-permit-consumption-foundation.md)
(FR-008, SEC-005/006/009, REL-004).

The separate authenticated loopback approval API adds verified access tokens
plus operator-owned membership to this boundary. It never accepts requester,
tenant, reviewer role or canonical action claims from HTTP bodies. Only one
bounded simulator operation is normalized. Immutable action snapshots commit
with pending approval and are checked again before execution. The original
requester and a still-enrolled distinct human reviewer are required. Browser
sessions, public-edge protection, durable identity administration and real
targets remain unqualified; see [ADR 0034](../architecture/0034-authenticated-loopback-approval-api.md).

The optional operator bootstrap adds a local file/configuration boundary:
strict fields and enrollment, bounded owner-only regular files, no final-component
symlinks, matching persistent Ed25519 keys, explicit OIDC trust and loopback-only
listener/database. Offline validation performs no network or DB operation.
These checks do not protect keys from a compromised operator/same-UID process,
writable ancestors, OS administrators or JVM memory inspection. Local DB transport
is not encrypted; public TLS, managed custody, live enrollment/revocation and
production readiness remain target work. See
[ADR 0035](../architecture/0035-local-approval-operator-bootstrap.md).

The Linux container profile preserves that same boundary rather than adding a
new remote one. Application and database have no published ports and still bind
`127.0.0.1` in the host network namespace. The application image contains no
private input and runs non-root/read-only without capabilities. A one-shot setup
container has narrowly retained ownership capabilities, rejects group/world-
accessible input, copies only fixed file names and refuses different bytes over
retained keys/secrets. The image build binds its OCI inventory to the verified
archive hash and embedded source commit. Image digests and those checks identify
inputs but are not signed provenance. Docker daemon/root, same-operator and
hostile-parent compromise remain possible; Docker Desktop, bridge ingress,
reverse proxies and TLS are unsupported. See
[ADR 0062](../architecture/0062-loopback-container-install-profile.md).

- Signed request-bound capability
- Audience, subject, policy version, expiry, and use limit
- Executor recomputes action hash and atomically consumes authority
- Reviewer identity/tenant/roles/scopes come from a trusted adapter, not the
  action or approval body
- Requester and reviewer are distinct; PostgreSQL finalizes one decision and
  consumes approval with the execution claim in one transaction

The local approval MCP surface adds request/status/resume, never review. It
shares API/browser authorization and durable claim logic. Original MCP client
and origin are checked on both execution ingresses to prevent an alternate API
route from weakening actor binding. Cookie/Origin/source-proof headers are
rejected on bearer MCP; no endpoint evidence is inferred. Tool metadata and
annotations grant no authority. A leaked bearer can act as its enrolled subject;
host-wide anti-bypass, managed device assurance and approval flooding remain
unresolved. Lost completion leaves consumed/executing authority, never a safe
automatic retry. See [ADR 0037](../architecture/0037-mcp-durable-simulator-approval-composition.md).

### Executor to target

- Fixed adapter operation mapping
- Least-privilege, short-lived credential
- Restricted outbound destinations
- Stable idempotency key and target audit correlation

### Application to evidence

- Redaction and non-secret fingerprints
- Append-only lifecycle events
- External checkpoint/anchor beyond application and DBA authority

### Operator database lifecycle

Normal simulator operator startup rejects absent/incomplete/incompatible schema
without implicitly creating, migrating or repairing it. First-install authority
is a separate empty-only `--init-db`; both state and permit stores use strict
opens in this profile. Metadata/history read and non-owner DML fixtures separate
runtime needs from migration ownership. See
[ADR 0045](../architecture/0045-explicit-database-initialization.md).

This prevents accidental empty bootstrap on that path, not all restore threats.
An older complete snapshot can pass validation and revive authority. Local
generation/evidence roll back with the DB; no external witness or effectful
safe-resume workflow exists. Legacy trusted development constructors retain
auto-migration. DBA tampering, arbitrary schema rewrites and concurrent DDL by
noncooperating actors are not comprehensively detected or fenced.

## 6. Fail-safe matrix

| Condition                          | Read-only operation                        | Sensitive write/destructive operation |
| ---------------------------------- | ------------------------------------------ | ------------------------------------- |
| Authentication invalid             | Deny                                       | Deny                                  |
| Authentication service unavailable | Deny or explicit cached policy if designed | Deny/unavailable                      |
| Revocation unavailable             | Optional bounded staleness by policy       | Deny/unavailable                      |
| Unknown tool/schema                | Deny or approval by explicit policy        | Deny                                  |
| Policy unavailable/invalid         | Deny                                       | Deny                                  |
| Approval unavailable/expired       | Remain pending/deny                        | Remain pending/deny                   |
| Permit signer unavailable          | Not executed                               | Not executed                          |
| Replay store unavailable           | Not executed for single-use path           | Not executed                          |
| Sanitizer unavailable              | Not executed                               | Not executed                          |
| Audit store unavailable            | Explicit risk-tier policy                  | Normally not executed                 |
| Target timeout after send          | Unknown, reconcile                         | Unknown, reconcile; never blind retry |

No cell defaults to allow merely to improve availability.

## 7. Real-target release gates

Before any real AWS or Jira credential is introduced:

1. Production identity and tenant isolation are implemented and tested.
2. Target adapter operations and resources are explicitly allowlisted.
3. Credential permissions are reviewed and demonstrably least-privilege.
4. Direct agent egress/credentials cannot bypass TracePermit.
5. Request-bound permit verification is separated from permit signing.
6. Replay state is durable and atomic across replicas.
7. Idempotency and unknown-outcome reconciliation are defined.
8. Evidence persistence, secret redaction, and incident telemetry are active.
9. Threat model and abuse tests are reviewed for the exact adapter.
10. A non-production target and rollback/kill switch are validated first.

[ADR 0056](../architecture/0056-enterprise-credentials-and-egress-boundary.md)
is a **proposed**, unqualified decomposition of credential custody and egress
gates (SEC-007/008; OPS-005). It distinguishes provider credentials from
TracePermit permits, requires deployment-specific bypass probes, and does not
promote simulator or connector checks into proof of host-wide containment.
Its [source anchors](../architecture/0056-enterprise-credentials-and-egress-boundary.md#current-source-anchors-not-enterprise-acceptance)
identify the implemented controls and their residual limits; the
[delivery gates](../architecture/0056-enterprise-credentials-and-egress-boundary.md#delivery-sequence-and-decision-gates)
do not authorize a remote route or a real-target deployment.

## 8. Security testing expectations

The optional browser approval profile adds login-CSRF/code-injection, token
substitution, session theft/fixation, cross-origin mutation and unsafe-rendering
threats. Code/PKCE/state/nonce plus required response issuer, separate signed
ID/access-token verification, browser binder and one-use login state constrain
sign-in. HttpOnly SameSite cookies, exact Host/Origin, session CSRF, no CORS,
CSP and React text rendering constrain browser requests. Every operation
rechecks current token/enrollment/scope and the shared approval boundary.
Tokens stay in bounded process memory; sessions expire/logout/restart invalidates.
This is local HTTP, not public TLS: same-UID compromise and another local port
can defeat cookie/token custody. Do not tunnel this listener. External provider
revocation, managed token storage, shared sessions/abuse controls and independent
security review remain release gates. See
[ADR 0036](../architecture/0036-local-browser-approval-boundary.md).

### Public-client downgrade and organization confusion

Schema1.1 explicitly selects `none`; missing confidential secrets never trigger
fallback. Closed configuration/form schemas reject secret injection and redirect
substitution. S256 PKCE binds the code to its initiating flow; atomic state
consumption and signed nonce checks constrain concurrent/replayed callbacks.
The public profile sends configured organization and requires its exact signed
`org_id` in both tokens. Client ID, authorization parameters and token roles
cannot create operator enrollment. API permissions, not an echoed `openid`,
gate use; a separate ID token remains mandatory. Local-TLS tests verify the
public POST omits Authorization while destination/redirect controls remain.
See [ADR0063](../architecture/0063-explicit-public-browser-code-pkce.md).

Native Auth0 organization availability, `at+jwt` and RFC9207 issuer-response
compatibility remain pending. Unsupported provider output denies login, with
no broader compatibility claim or weakened parser. Same-UID compromise, local
HTTP cookie limits, provider revocation and shared session state remain outside
this qualification. No public tunnel, Management API or real target is added.

- Mutation and property-based tests for schemas and canonicalization
- Forged, expired, future, wrong-issuer, wrong-audience, and wrong-type tokens
- Identity, tenant, target, approval, and policy injection attempts
- Delegation cycles, missing parents, amplification, revocation, and expiry
- Concurrent permit/approval replay
- SSRF and arbitrary target tests
- Authorization matrix tests for every control-plane role
- Secret scanning and log/evidence redaction tests
- Dependency, container, and infrastructure configuration scanning
- Penetration test before a real connector or target pilot

### Installed requester threats

| ID    | Threat                                                                       | Current control                                                                                                     | Residual limit                                                          |
| ----- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| T-046 | Browser SSO authenticates a different human for an installed requester       | Exact signed subject/client/org in both tokens plus requester-only backend enrollment                               | Operator must select the dedicated account; same-UID compromise remains |
| T-047 | Local callback injection, replay or port squatting steals a code             | Bind-before-browser exact IPv4 loopback, one flow/attempt, state/issuer/PKCE, strict bounded request                | Local denial of one login attempt remains possible                      |
| T-048 | Requester bearer leaks through shell, argv, config or output                 | Owner-only no-replace envelope; consume before fixed child; child-only environment variable; stable redacted errors | Same-UID process inspection and child compromise remain                 |
| T-049 | Expiry or launch uncertainty causes an automatic duplicate request or effect | No refresh/retry/restart; explicit re-login, status and one bound resume; every MCP call reauthenticated            | Lost initial response may remain uncertain                              |
