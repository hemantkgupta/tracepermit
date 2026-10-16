# Java low-level design

**Status:** Current POC design with explicit production gaps
**Applies to:** Java 21 backend and simulator-only execution

## Optional local browser approval adapter

`ApprovalBrowserAdapter` owns OIDC code exchange, one-use browser-bound login
state, bounded in-memory sessions/CSRF, strict same-origin routing and built
React assets. `BrowserIdTokenVerifier` checks a separate RS256 ID-token profile
and matches nonce/subject/client to the independently verified API token.
`SimulatorApprovalServer` keeps bearer and cookie ingress separate and shares
the same role/scope/owner, exact snapshot and durable claim path after authentication.
`SimulatorApprovalConfiguration` opt-in browser fields are private operator
inputs; `SimulatorApprovalMain` uses DNS-pinned HTTPS token POST and never a
development issuer. The synthetic browser qualifier lives only in test sources.
No database migration or real target is introduced. See
[ADR 0036](../architecture/0036-local-browser-approval-boundary.md).

## Module ownership

| Module                 | Responsibility                                                                                                                     |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `contracts`            | Canonical JSON, strict action and signed-claim validation                                                                          |
| `identity`             | Development token, source proof, workload/delegation, OAuth/OIDC verification                                                      |
| `policy`               | Deterministic classifier, sequence signals, strict policy schema validation, lifecycle staging/activation/rollback, and simulation |
| `security`             | Request-bound permit issue, verification, and consumption                                                                          |
| `execution`            | Attempts, idempotency, unknown outcomes, evidence chain                                                                            |
| `application`          | Fail-closed orchestration and trusted reviewer workflow                                                                            |
| `simulator`            | Bounded, stateful AWS/Jira fakes; no real target SDK                                                                               |
| `adapters-http`        | Combined loopback MCP backend and read-only projections                                                                            |
| `adapters-remote`      | Authenticated MCP edge and SSRF-safe JWKS HTTPS                                                                                    |
| `endpoint-connector`   | MCP stdio origin observation and loopback forwarding                                                                               |
| `persistence-postgres` | Durable claims, approvals, policy revisions/lifecycle, evidence, outbox, reconciliation                                            |
| `observability-otel`   | Bounded telemetry projection                                                                                                       |
| `conformance`          | Checked-in schema, vector, and invariant tests                                                                                     |

Transport code stays in adapters. All origins converge on the canonical
action contract before classification or policy. Application and domain code
receive trusted identity context from adapters; they do not infer it from tool
arguments. The executor has no route to a real AWS or Jira service.

## Policy lifecycle and durable persistence foundation

`PolicyLifecycleService` is a trusted Java library, not a policy administration
API wired into the default backend. Candidate validation operates on a private
snapshot. `PolicyVersionRecord` copies constructor input and every `document()`
result, including records returned by stage/active/query/list/restage. Callers
cannot mutate current or rollback content through these APIs. Dynamic execution
obtains one policy snapshot per action and reuses it for sanitization re-evaluation.

The `PolicyLifecycleStore` SPI in `java/policy` abstracts revision and state storage,
implemented by `InMemoryPolicyLifecycleStore` and `PostgresPolicyLifecycleStore` in
`java/persistence-postgres`:

1. **Durable revisions:** PostgreSQL V7 migration introduces `tp_policy_versions` with
   database trigger `tp_guard_policy_version_mutation()` preventing mutation or deletion.
   Identical restaging is idempotent; different content fails closed.
2. **Transactional activation & rollback:** `tp_active_policies` maintains `activation_sequence`
   and `active_version`. Active updates and `tp_policy_lifecycle_transitions` history commit
   atomically under a transaction-scoped workspace/policy advisory lock followed
   by row locks (`FOR UPDATE`). This also serializes the first activation when
   the active row is absent. A non-null `expectedActiveVersion` mismatch fails
   with `PolicyConcurrencyException`; null is unconditional and the version
   precondition is not an ABA-resistant generation check.
3. **Rollback traversal:** rollback resolves predecessor activations from transition history,
   supporting repeated rollback toggling and explicit target rollbacks.
4. **Workspace isolation:** storage is partitioned by trusted caller-supplied `workspace_id`.
   Unavailability fails closed without silent in-memory fallback.

Writes own a fresh/exclusive READ COMMITTED connection lease, commit before
returning, and restore its original modes after success. SQL and unchecked
failures roll back without enabling auto-commit; rollback failure is suppressed.
Ambiguous commit/restoration/close failure reports failure, not success or an
automatic retry. Inspect durable pointer/history before further operator action.
Idempotent activation uses the same connection; reactivation metadata agrees
between the returned record and a fresh query. Caller-owned transactions and
database-admin tamper resistance are not supported. Advisory lock acquisition
has a five-second query timeout; there is no end-to-end latency guarantee.

The current Java profile accepts versions 1..2147483647 and priorities
-2147483648..2147483647, including negative priorities. Reject unsupported values
before lifecycle insertion or dry-run evaluation rather than silently narrowing them.
Actor tracking IDs are unauthenticated caller metadata, not verified operator provenance;
DB history is not tamper-proof against a DBA; and pending-approval stale-policy integration
and remote administration APIs remain deferred.
See [ADR 0039](../architecture/0039-policy-lifecycle-staging-rollback-and-simulation.md).

## Authorization and execution

1. Authenticate transport credentials and, where required, verify source or
   workload proof before protected dispatch. Host/Origin, size, replay, and
   scope failures stop before a target-capable application call.
2. Validate the exact registered tool and arguments, derive tenant, subject,
   actor, target, and action from trusted context, and compute a versioned
   canonical hash.
3. Classify intent and severity from the action and bounded server-owned
   sequence history. Evaluate ordered policy. Unknown tools, schemas,
   identities, policies, or state fail closed.
4. For sanitize, construct a new bounded action and re-evaluate it. For
   approval, require independently authenticated reviewer context and an
   exact request binding. A request-body approval flag is never authority.
5. On allow or valid approval, issue a short-lived request-bound permit.
   The executor checks signature, audience, hash, expiry, and single-use
   claim before invoking the simulator. Durable execution/approval already
   inserts the permit with execution state and evidence in the existing
   `PostgresDurableEnforcementState` transaction; it does not call standalone
   `verifyAndConsume`. A denial issues no permit.
6. Record only bounded, redacted evidence and expose read-only projections
   to the React console.

## State and transaction boundaries

The optional standalone `PostgresPermitConsumptionStore` supports a separate
consumption caller. Both authority overloads route by the organization in the
private, hash-verified canonical action snapshot; an explicit tenant mismatch
fails before storage. Missing/invalid tenant fails closed in the durable store.
The port's legacy in-memory defaults retain global rather than tenant-aware
replay tracking. Live durable claims require the verified action hash, never a
hash fabricated from the permit ID.

Consumption and foreign-key-aware pruning each own a fresh/exclusive database
lease and commit before returning. Initially manual-transaction connections are
supported; caller-owned/thread-bound transactions are not. SQL/commit/close
failures never return success, rollback failures are preserved, and an ambiguous
commit may leave a consumed row without dispatch. No automatic retry occurs.
This standalone claim must not precede the composed durable claim transaction.
See [ADR 0041](../architecture/0041-postgres-durable-permit-consumption.md).

The default loopback demo uses process-local stores. Those stores are not
safe for multi-replica or restart-persistent enforcement. The optional
PostgreSQL composition uses short transactions to atomically claim a permit
and idempotency identity, append an execution attempt and evidence/outbox
event, and commit **before** simulator dispatch. A second transaction records
the observed outcome. External calls never run inside the claim transaction.

An uncertain attempt enters a durable queue. A leased worker may query an
outcome through a read-only resolver, then append a terminal reconciliation
only after confirmed observation; it cannot reissue the target operation.
The evidence publisher likewise uses leased outbox rows and independently
verifies signed anchor receipts before storing immutable delivery evidence.

## Cryptographic and data boundaries

The separate loopback `SimulatorApprovalServer` derives reviewer authority
from verified access tokens plus operator-owned `ApprovalMemberships`.
`DurableApprovalActionState` extends approval persistence with an atomic exact
action snapshot and tenant-scoped reads. PostgreSQL V6 implements this port;
`HumanApprovalService.requestApprovalWithSnapshot` requires it. Execution uses
the original stored canonical action rather than a new client submission.
The existing console and demo launchers do not use this foundation yet.

`SimulatorApprovalConfiguration` validates private bounded POSIX files,
explicit single-client/tenant OIDC trust, independent requester/reviewer enrollment,
numeric loopback DB configuration and matching Ed25519 keys. The separate
`SimulatorApprovalMain` runs migrations/schema verification before binding the
loopback approval service with a fixed default-block/explicit-approval policy.
Offline check mode performs no DB, DNS, IdP or listener operation. See
[ADR 0035](../architecture/0035-local-approval-operator-bootstrap.md).

`packageSimulatorApprovalDistribution` packages the loopback approval API,
runtime JARs, built React inbox assets, and a thin bash launcher
(`tracepermit-simulator-approval`) into a host-native archive
(`tracepermit-simulator-approval-0.1.0.tar.gz`). The launcher requires only
Java 21 and forwards strictly to `SimulatorApprovalMain` without requiring a source
checkout, Node, pnpm, or Gradle on the running host. Offline `--check` validates
configuration, file permissions (0600/0400 owner-only), Ed25519 keypair math, and
bundled assets without network, database, or IdP traffic. The archive includes
`manifest.json` recording the source Git commit, package version, required Java 21,
and SHA-256 dependency inventory, alongside `SHA256SUMS`. Checksums provide file
integrity verification, not third-party code signing. All credentials and private keys
remain operator-owned outside the bundle. See
[ADR 0040](../architecture/0040-source-free-simulator-approval-distribution.md).

Signed artifacts use named canonicalization and algorithm profiles with
allowlisted keys. An endpoint source proof binds the exact MCP method/params
and the authenticated connector credential fingerprint. Delegation grants
must narrow parent authority across scope, resource, audience, and lifetime.
Source evidence proves origin facts, not permission or safety. Evidence stores
fingerprints and bounded projections, never bearer tokens or private keys.

Development signing keys and replay stores are local POC mechanisms. Managed
endpoint enrollment, production permit key custody, independent target
credentials and egress control, shared edge state, reviewer authentication,
and a production bootstrap remain outside the implemented runtime. See the
[current-state baseline](03-current-state-baseline.md) and [threat
model](10-security-threat-model.md).
