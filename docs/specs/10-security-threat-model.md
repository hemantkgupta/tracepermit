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

| ID    | Threat                                                                              | Primary controls                                                                                                              | Residual concern                                                                    |
| ----- | ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| T-001 | Client injects `authenticated=true` or a different subject                          | Strict public schemas; adapter-owned identity; SEC-001                                                                        | Trusted adapter compromise                                                          |
| T-002 | Tool visible through discovery is called without authority                          | Per-invocation scope and policy check; FR-004                                                                                 | Incorrect scope mapping                                                             |
| T-003 | Valid token replayed to another service                                             | Java RS256 validation plus hardened RFC 8705 mTLS certificate binding; SEC-004/016                                            | Live connector/profile and certificate lifecycle pending                            |
| T-004 | Child delegation expands authority                                                  | Java Ed25519 complete-chain verification, parent hash, subset/lifetime checks; FR-011                                         | In-memory trust/revocation/replay only                                              |
| T-005 | Request changes after policy or approval                                            | Versioned canonical request hash; SEC-005                                                                                     | Cross-language canonicalization drift                                               |
| T-006 | Single-use permit used concurrently                                                 | PostgreSQL tenant/permit unique claim in Java durable profile; SEC-006                                                        | Java development profile remains process-local                                      |
| T-007 | Gateway bug invokes a different target operation                                    | Executor verification, adapter allowlist, least-privilege credential                                                          | Compromised executor with broad credential                                          |
| T-008 | Agent bypasses TracePermit                                                          | Credential custody, restricted egress, target-side policy; SEC-008                                                            | Unmanaged shadow credentials                                                        |
| T-032 | Publisher or attacker forges or mutates an external evidence acknowledgement        | Exact signed receipt, operator-owned anchor trust, durable binding recheck, append-only storage; SEC-018                      | Compromise of both producer and external anchor trust                               |
| T-033 | Worker crash or race loses or duplicates evidence publication                       | Transactional outbox, stable idempotency key, bounded lease, `SKIP LOCKED`, retry state; REL-007                              | Non-idempotent external sink can observe duplicate calls                            |
| T-034 | Reconciler retries an effect or invents a terminal outcome                          | Lookup-only port, exact durable binding, leases, confirmed-only append; SEC-019/REL-008                                       | Target without trustworthy lookup remains unknown                                   |
| T-009 | Secret leaks through evidence or logs                                               | Fingerprints, redaction, secret scanning; SEC-003/012                                                                         | Sensitive business arguments                                                        |
| T-010 | DBA rewrites audit history                                                          | External signed checkpoints, immutable storage; SEC-010                                                                       | Compromised checkpoint service                                                      |
| T-011 | Compromised app uses KMS as signing oracle                                          | Separate signer identity/service, constrained signing context, external sequence                                              | Authorized false future events                                                      |
| T-012 | Target succeeds but response is lost                                                | Durable idempotency, unknown queue, lookup-only reconciliation worker; REL-003/005/008                                        | Production target may lack trustworthy lookup                                       |
| T-013 | Security dependency outage leads to fail-open                                       | Readiness gate plus component fail-safe behavior; REL-001                                                                     | Stale health signal; availability impact                                            |
| T-014 | Cross-tenant object reference                                                       | Tenant in identity, keys, queries, claims, and authorization; SEC-009                                                         | Operator/system account scope                                                       |
| T-015 | SSRF through dynamic target or webhook                                              | Server-owned target registry, egress allowlist, no arbitrary URLs                                                             | Reviewed extensibility mechanisms                                                   |
| T-016 | Policy rollback or stale cache accepts unintended action                            | Signed/versioned policy, expiry, rollout state, evaluation evidence                                                           | Emergency rollback coordination                                                     |
| T-017 | Development issuer exposed remotely                                                 | Loopback enforcement and deployment gate; SEC-011                                                                             | Packaging/configuration regression                                                  |
| T-018 | Denial of service through large/expensive requests                                  | Body limits, strict schemas, pre/post-auth rate ports, timeouts; SEC-017                                                      | Shared/distributed abuse state and approval flooding                                |
| T-019 | Client forges device, OS, process, or human-trigger evidence                        | Enrolled endpoint key, trusted observer, adapter-owned canonical fields; FR-016                                               | Compromised endpoint observer                                                       |
| T-020 | Source attestation is replayed or copied to another request                         | Request/credential binding, short expiry, atomic token claim; FR-017/SEC-014                                                  | Durable multi-replica store not implemented                                         |
| T-021 | Corporate device is mistaken for enterprise provider login                          | Separate OS, enterprise, device, and provider-account evidence                                                                | Provider may not expose end-user identity                                           |
| T-022 | Endpoint connector is redirected or used as an SSRF proxy                           | Exact loopback `/mcp` URI, no redirects, bounded HTTP transport                                                               | User-controlled local service                                                       |
| T-023 | Stdio framing or diagnostics leak secrets                                           | Pre-allocation frame bound; protocol-only stdout; code-only stderr; redaction tests                                           | Host process/environment visibility                                                 |
| T-024 | Endpoint executable changes after observation                                       | Hash/start/path/name observation and immediate pre-dispatch stability check                                                   | Same-process compromise; no code-signing policy                                     |
| T-025 | Agent forges, self-grants, mutates, or replays human approval                       | Trusted reviewer context, exact binding, expiry, separation of duties, atomic PostgreSQL consume+claim                        | No production reviewer adapter/API yet                                              |
| T-026 | Request self-asserts workload identity or omits/widens a grant                      | Operator key registry, exact run/action binding, complete chain, leaf mapping, atomic workload replay                         | No production scheduler/durable trust services                                      |
| T-027 | Token, redirect, or DNS sends verification to attacker/internal JWKS                | Exact operator URI, no token URL, global-address DNS policy/pinning, direct TLS, no redirect/proxy/retry, bounded cache       | Egress firewall and production deployment pending                                   |
| T-028 | Remote request bypasses identity through Host/Origin/parser behavior                | Direct TLS, exact Host/Origin, readiness/rate/sender checks before strict JSON dispatch                                       | Reverse proxy and live qualification pending                                        |
| T-029 | Authenticated remote caller injects target/authority or bypasses policy             | Exact tool schemas, server-owned canonical mapping, application port, policy then request-bound permit and simulator checks   | Process-local permit/evidence; anti-bypass egress pending                           |
| T-030 | Stolen remote token is replayed by a different TLS client                           | Issuer-signed certificate thumbprint matched to direct peer leaf certificate before parsing                                   | Certificate custody/revocation and connector support                                |
| T-031 | Abuse-control or deployment dependency fails and traffic continues                  | Exact hardened readiness plus fail-closed limiter errors                                                                      | No production probes/exporter or shared limiter yet                                 |
| T-035 | Tokens, arguments, identities, or attacker-controlled values leak through telemetry | Typed event allowlist, bounded values/cardinality, no payload field, OTel projection tests                                    | Production exporter/configuration review pending                                    |
| T-036 | Required telemetry fails and an authorized request still dispatches                 | Pre-dispatch event is required; failure returns 503 with zero dispatch                                                        | Async exporter delivery still needs a live health probe                             |
| T-039 | Individually allowed reads form a rapid extraction sequence                         | Gateway-owned tenant/subject/target history, bounded window, higher-priority sequence rule, fail-closed state                 | POC store is process-local and resets on restart                                    |
| T-040 | Sanitizer executes the original, widens authority, or bypasses policy               | Immutable original, allowlisted transformer, distinct event/hash, full re-evaluation, effective-action permit/idempotency     | Only one POC transformer; production registry pending                               |
| T-041 | Lower-trust tool-result text impersonates instructions and induces an IAM action    | Tool result remains data; signed adapter verifies the new structured proposal; Java classifies, blocks, and issues no permit  | POC uses a deterministic emulator, not a real model; unmediated tools remain a risk |
| T-042 | A mutable MCP registry adds or replaces a tool after security review                | Trusted registration, duplicate/reserved-name rejection, frozen dispatcher snapshot, per-tool scope and canonical enforcement | Code-defined tool implementation remains part of the trusted computing base         |
| T-043 | Concurrent new rate-limit keys bypass the configured capacity                       | Atomic new-key admission, per-key claims, conditional expiry cleanup, contention regression test                              | Process-local limiter is not shared production abuse state                          |
| T-044 | An integer-format change silently alters signed canonical v1 bytes                  | Frozen v1 method/vectors; exact-integer helper separate and inactive for signed artifacts                                     | A future profile needs explicit versioning and cross-language qualification         |

## 5. Trust-boundary controls

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

- Versioned canonical action
- Explicit tenant, subject, actor, trigger, target, and assurance
- Deterministic validation before classification
- Prior tool-result text and declared goals remain lower-trust data; any
  resulting structured action crosses the same adapter and policy boundary

### Policy/approval to executor

The separate authenticated loopback approval API adds verified access tokens
plus operator-owned membership to this boundary. It never accepts requester,
tenant, reviewer role or canonical action claims from HTTP bodies. Only one
bounded simulator operation is normalized. Immutable action snapshots commit
with pending approval and are checked again before execution. The original
requester and a still-enrolled distinct human reviewer are required. Browser
sessions, public-edge protection, durable identity administration and real
targets remain unqualified; see [ADR 0034](../architecture/0034-authenticated-loopback-approval-api.md).

- Signed request-bound capability
- Audience, subject, policy version, expiry, and use limit
- Executor recomputes action hash and atomically consumes authority
- Reviewer identity/tenant/roles/scopes come from a trusted adapter, not the
  action or approval body
- Requester and reviewer are distinct; PostgreSQL finalizes one decision and
  consumes approval with the execution claim in one transaction

### Executor to target

- Fixed adapter operation mapping
- Least-privilege, short-lived credential
- Restricted outbound destinations
- Stable idempotency key and target audit correlation

### Application to evidence

- Redaction and non-secret fingerprints
- Append-only lifecycle events
- External checkpoint/anchor beyond application and DBA authority

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

## 8. Security testing expectations

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
