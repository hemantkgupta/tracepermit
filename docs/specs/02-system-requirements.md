# System requirements

**Status:** Mixed; see traceability matrix for implementation state  
**Applies to:** Current POC and target enterprise product

## Functional requirements

| ID     | Requirement                                                                                                                                                               |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-001 | TracePermit SHALL intercept every configured side-effecting action before target execution.                                                                               |
| FR-002 | A trusted adapter SHALL normalize every origin into the versioned canonical action contract.                                                                              |
| FR-003 | Public adapters SHALL authenticate before protected protocol dispatch; a bounded public metadata route MAY parse only what is required for standards-compliant discovery. |
| FR-004 | Public tool discovery and protected tool invocation SHALL remain separate capabilities; discovering a tool MUST NOT authorize its invocation.                             |
| FR-005 | TracePermit SHALL classify action class, severity, and risk signals from normalized action data.                                                                          |
| FR-006 | Policy SHALL return exactly one of allow, block, sanitize, or require approval, with policy version and reason evidence.                                                  |
| FR-007 | Human approval SHALL bind to the exact canonical request, subject/delegation, audience, expiry, policy context, and use limit.                                            |
| FR-008 | An allow or approved decision SHALL produce executor-verifiable, request-bound execution authority.                                                                       |
| FR-009 | Executors SHALL verify authority and adapter allowlists immediately before invoking a target.                                                                             |
| FR-010 | TracePermit SHALL preserve correlated evidence for observation, identity, lineage, classification, policy, approval, execution, and outcome.                              |
| FR-011 | Delegation SHALL be parent-linked, verifiable, and monotonically narrowed across scope, resource, audience, and lifetime.                                                 |
| FR-012 | New effectful adapters SHALL first run against deterministic simulators.                                                                                                  |
| FR-013 | The control plane SHALL support policy versioning, validation, simulation, staged activation, and rollback.                                                               |
| FR-014 | The product SHALL support MCP, API, CLI, and scheduled-workload origins through transport-specific adapters.                                                              |
| FR-015 | The product SHALL reconcile executions whose final outcome is unknown.                                                                                                    |
| FR-016 | A managed endpoint connector SHALL derive device, OS-session, process, and trigger evidence from trusted adapters rather than client claims.                              |
| FR-017 | Source attestations SHALL bind the authenticated connector credential and exact intercepted request before protected CLI dispatch.                                        |

## Security requirements

| ID      | Requirement                                                                                                                                                                                              |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SEC-001 | Request bodies and tool arguments MUST NOT self-assert authenticated, verified, delegated, approved, or sanitized state.                                                                                 |
| SEC-002 | Unknown tools, schemas, algorithms, issuers, audiences, decision values, and permit states MUST fail closed.                                                                                             |
| SEC-003 | Bearer tokens, private keys, client secrets, API keys, and reusable target credentials MUST NOT enter source control or evidence records.                                                                |
| SEC-004 | Tokens and permits MUST validate signature, issuer, audience, time constraints, type, and required scopes before use.                                                                                    |
| SEC-005 | Request-bound approvals and permits MUST include a versioned canonical request hash.                                                                                                                     |
| SEC-006 | Single-use authority MUST be consumed atomically before the target effect.                                                                                                                               |
| SEC-007 | Target credentials MUST be short-lived and least-privilege where the target supports it.                                                                                                                 |
| SEC-008 | Agents MUST NOT retain an alternate credential or network route that bypasses the enforcement point.                                                                                                     |
| SEC-009 | Tenant, subject, policy, approval, permit, and evidence identifiers MUST be isolated across tenants.                                                                                                     |
| SEC-010 | Security-relevant state changes MUST produce append-only evidence and support external integrity anchoring.                                                                                              |
| SEC-011 | Development issuers and credentials MUST NOT be enabled on non-loopback or production deployments.                                                                                                       |
| SEC-012 | Logs, metrics, traces, errors, and audit events MUST redact credentials and sensitive arguments according to policy.                                                                                     |
| SEC-013 | Endpoint signing keys MUST be enrolled independently, protected from the agent process, and referenced only by key ID or fingerprint in evidence.                                                        |
| SEC-014 | Required source attestations MUST validate signature, version, algorithm, issuer, key, audience, time, request binding, credential binding, and single use before dispatch.                              |
| SEC-015 | Identity/key network adapters MUST use operator-owned exact destinations, prevent redirects and private-address access, pin validated resolution through connect, and bound time and response size.      |
| SEC-016 | Production remote connector profiles MUST sender-constrain access tokens and reject missing or mismatched proof before protocol parsing and dispatch.                                                    |
| SEC-017 | Public remote adapters MUST enforce fail-closed abuse controls before expensive authentication and again after authenticated tenant/principal attribution.                                               |
| SEC-018 | External evidence publication MUST verify an independently trusted signed receipt bound to the exact tenant, outbox identity, stream position, event, and payload hash before marking delivery complete. |
| SEC-019 | Automated reconciliation MUST use read-only lookup bound to the durable tenant, adapter, and idempotency identity and MUST NOT retry the original target effect.                                         |
| SEC-020 | Security telemetry MUST use bounded low-cardinality fields, exclude credentials and sensitive payloads, and fail closed before authorized remote dispatch when required telemetry is unavailable.        |

## Local public-client browser interpretation

FR-003/007/012 and SEC-002/003/004/009/011/012/015 apply to the optional
schema1.1 native/public candidate. Method `none` SHALL require code/S256 PKCE,
exact numeric-loopback callback and one-use browser-bound state/nonce. It MUST
NOT accept a client secret or infer public behavior from a missing confidential
secret. Signed ID and API tokens SHALL independently validate their issuer,
audience, subject, client, organization, time and trusted key bindings.
`openid` selects OIDC in the authorization request; the API token SHALL carry
`approvals.read` for public login and the operation-specific approval scopes
for API use. It need not echo `openid`. Existing schema1.0 confidential behavior
SHALL remain compatible. See [ADR0063](../architecture/0063-explicit-public-browser-code-pkce.md).

OPS-001/004/005 do not turn offline validation or local DB readiness into live
IdP or production readiness. Auth0 `at+jwt`, callback `iss` and native
organization interoperability remain pending qualification.

Rejected browser login callbacks SHALL keep the HTTP response generic while
the local operator process MAY emit one fixed stage and one allowlisted reason.
That diagnostic MUST NOT include exception text, URLs, identifiers, claims,
credentials or sensitive payloads; a diagnostic-sink failure MUST NOT change
the authentication result. These local signals are troubleshooting evidence,
not an authorization input or a successful IdP qualification.

## Reliability requirements

| ID      | Requirement                                                                                                                                         |
| ------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| REL-001 | Authentication, policy, approval, revocation, replay, and executor-verification failures SHALL have explicit fail-safe behavior.                    |
| REL-002 | TracePermit SHALL distinguish denied, pending, not executed, executing, succeeded, failed, and unknown outcomes.                                    |
| REL-003 | Retries of effectful operations SHALL use stable idempotency keys when supported.                                                                   |
| REL-004 | Permit consumption and execution-state transitions SHALL remain correct under concurrency.                                                          |
| REL-005 | Recovery SHALL reconcile uncertain outcomes rather than assume failure.                                                                             |
| REL-006 | Policy and key rollouts SHALL support rollback without accepting artifacts under unintended versions.                                               |
| REL-007 | Evidence publication SHALL use stable idempotency identities, bounded durable leases, and retry-safe state transitions across workers and restarts. |
| REL-008 | Unknown executions SHALL enter a durable, lease-safe reconciliation queue and reach a terminal state only from a confirmed target observation.      |
| REL-009 | Every security dependency and telemetry failure stage SHALL have an explicit tested fail-safe outcome that cannot convert a denial into an allow.   |

## Performance and scale requirements

The following are proposed initial objectives and require product confirmation:

| ID       | Proposed objective                                                                                                                   |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| PERF-001 | Cached deterministic classification and policy evaluation SHOULD add no more than 25 ms p95 inside the service boundary.             |
| PERF-002 | End-to-end authorization excluding target latency SHOULD remain below 100 ms p95 for non-approval paths.                             |
| PERF-003 | The initial enterprise deployment SHOULD support at least 500 authorization decisions per second per region with horizontal scaling. |
| PERF-004 | Approval and audit paths MAY be asynchronous, but enforcement decisions MUST remain synchronous before execution.                    |

## Data and privacy requirements

| ID       | Requirement                                                                                                     |
| -------- | --------------------------------------------------------------------------------------------------------------- |
| DATA-001 | Evidence SHALL store credential fingerprints rather than reusable credentials.                                  |
| DATA-002 | Retention SHALL be configurable by tenant, evidence type, and regulatory policy.                                |
| DATA-003 | Sensitive tool arguments SHALL support field-level redaction or irreversible transformation before persistence. |
| DATA-004 | Evidence corrections SHALL append reconciliation events rather than overwrite history.                          |
| DATA-005 | Data residency and encryption keys SHALL be configurable for enterprise deployment models.                      |

## Operability requirements

| ID      | Requirement                                                                                                                                                                                                        |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| OPS-001 | Services SHALL expose health, readiness, metrics, structured logs, and distributed traces without leaking secrets.                                                                                                 |
| OPS-002 | Operators SHALL be able to identify active policy, key, schema, and adapter versions for an evaluation.                                                                                                            |
| OPS-003 | Alerts SHALL cover authentication failures, denial spikes, replay attempts, approval backlog, unknown outcomes, audit failures, and latency.                                                                       |
| OPS-004 | Operational runbooks SHALL cover key compromise, policy rollback, target outage, replay-store outage, and audit-sink outage.                                                                                       |
| OPS-005 | Production remote readiness SHALL require TLS identity, OAuth trust, sender constraint, shared abuse state, durable enforcement/evidence, security telemetry, target egress controls, and connector qualification. |

## Portability requirements

| ID       | Requirement                                                                                                    |
| -------- | -------------------------------------------------------------------------------------------------------------- |
| PORT-001 | Canonical contracts SHALL have versioned language-neutral artifacts.                                           |
| PORT-002 | Canonicalization SHALL be versioned and backed by deterministic golden vectors.                                |
| PORT-003 | Java conformance tests SHALL validate checked-in contract artifacts and behavior.                              |
| PORT-004 | Supported origin adapters SHALL preserve the canonical action and enforcement behavior through contract tests. |

## Installed requester public-client interpretation

The source-free requester helper applies FR-003/007/012,
SEC-002/003/004/009/011/012/015, REL-001/004/006/009 and
OPS-001/002/004/005 to one same-Mac simulator profile. It SHALL use a fixed
numeric-loopback callback, public Code + S256, independently verified tokens,
exact requester-only enrollment and a no-replace owner-only credential. The
bearer SHALL be consumed before a fixed Codex child starts and SHALL appear only
in a child environment variable, never argv, persistent configuration, output or
evidence. No refresh, retry, replacement request or automatic resume is allowed.

Automated qualification does not establish live provider interoperability,
prompt/executable identity, endpoint posture, production isolation or a public
deployment. See [ADR0065](../architecture/0065-installed-requester-public-sign-in.md).
