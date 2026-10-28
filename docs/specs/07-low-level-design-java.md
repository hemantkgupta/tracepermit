# Java low-level design

**Status:** Current POC design with explicit production gaps
**Applies to:** Java 21 backend and simulator-only execution

## Source-free closed operation lifecycle composition

`agentcore-operation-lifecycle-cli` is a separate application module over the
existing V7 libraries. `LifecycleProtocol` reads the exact closed anonymous-pipe
frames and EOF before effects. `LifecycleOperatorConfig` constructs only pinned
numeric-loopback operations storage, exact scope/target/authority, offline RS256
providers, requester enrollment and the independent human roster.
`LifecycleIdentityAdapter` converts only verified token results and operator
enrollment into trusted requester/evaluator/reviewer contexts.

`LifecycleRuntime` calls the concrete review, permit, admission and delivery
repositories and one fixed `SyntheticGitHubDeliveryTarget`; it offers no generic
repository, verifier, signer, clock or target injection. The permit private key
is canonical Ed25519 PKCS8 from a separate private frame, proven against the
configured public key and erased from temporary byte arrays. The added
`JdbcGitHubOperationInspector` validates existing V7 storage and current
authority, replays current state plus delivery-start observation in a rollback-
only transaction and returns defensive copies. It performs no DDL.

`LifecycleCli` supervises one environment-cleared `LifecycleWorker` within a
60-second total budget and separate five-second cleanup reserve. Receipt bytes,
stderr and exit must agree. Both layers require anonymous input. Deliberate
`Runtime.halt` makes successful/failed receipt publication finite and prevents
shutdown-hook substitution. Worker death, storage uncertainty or delivery
uncertainty is fixed failure and creates no retry. See
[ADR0093](../architecture/0093-source-free-operation-lifecycle.md).

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

| Module                              | Responsibility                                                                                                                      |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `contracts`                         | Canonical JSON, strict action/business-operation/signed-claim validation, bounded opt-in projection redaction (ADR 0043/0068)       |
| `identity`                          | Development token, source proof, workload/delegation, OAuth/OIDC verification                                                       |
| `policy`                            | Deterministic classifier, sequence signals, strict policy schema validation, lifecycle staging/activation/rollback, and simulation  |
| `security`                          | Request-bound permit issue, verification, and consumption                                                                           |
| `execution`                         | Attempts, idempotency, unknown outcomes, evidence chain                                                                             |
| `application`                       | Fail-closed orchestration and trusted reviewer workflow                                                                             |
| `simulator`                         | Bounded, stateful AWS/Jira fakes; no real target SDK                                                                                |
| `adapters-http`                     | Loopback MCP, read-only projections, and separate authenticated approval/browser adapter                                            |
| `adapters-remote`                   | Authenticated MCP edge, SSRF-safe JWKS HTTPS, and bounded AgentCore GitHub draft pull request adapter with read-only reconciliation |
| `endpoint-connector`                | MCP stdio origin observation and loopback forwarding                                                                                |
| `persistence-postgres`              | Durable claims, approvals, policy revisions/lifecycle, evidence, outbox, reconciliation                                             |
| `persistence-operations`            | Isolated MariaDB/PostgreSQL operation registration, scoped-key uniqueness and immutable initial facts (ADR 0070)                    |
| `agentcore-operation-lifecycle-cli` | Source-free installed composition of the closed V7 lifecycle with offline identity and synthetic delivery (ADR0093)                 |
| `observability-otel`                | Bounded telemetry projection                                                                                                        |
| `conformance`                       | Checked-in schema, vector, and invariant tests                                                                                      |

Transport code stays in adapters. All origins converge on the canonical
action contract before classification or policy. Application and domain code
receive trusted identity context from adapters; they do not infer it from tool
arguments. The executor has no route to a real AWS or Jira service.

## AgentCore GitHub draft pull request adapter and reconciliation

`GitHubDraftPullRequestAdapter` in package `io.tracepermit.adapters.remote.github`
handles the AgentCore pilot operation `github.pull_request.create`. It performs
strict pre-execution canonical validation, repository allowlisting
(`tracepermit/agentcore-pr-pilot`), base branch allowlisting (`main`), and mandatory
`draft: true` enforcement before consuming execution permits. Transport configuration
enforces `Proxy.NO_PROXY`, disabled redirects and retries, bounded timeouts (5s connect,
10s call), and decoded response size limits (65,536 bytes, enforced while reading
with one overflow byte for detection). Credential tokens are derived via
`GitHubAppCredentialProvider`, masked in memory, and stripped from error messages and
evidence records.

To address the absence of client-specified idempotency in GitHub's REST API,
`GitHubDraftPullRequestAdapter.computeOperationKey(...)` derives a deterministic
SHA-256 operation key bound to installation ID, repository, head branch, and base branch.
Ambiguous socket timeouts and network failures transition attempts to `unknown` without
automatic retries. `GitHubPullRequestOutcomeResolver` implements `TargetOutcomeResolver`
to query `GET /repos/{owner}/{repo}/pulls?head={owner}:{head}&base={base}&state=all`
during scheduled reconciliation, transitioning to `succeeded` only upon observing a
matching pull request, or `failed` under the existing successful-empty-lookup
contract. Oversized HTTP 201 confirmation and unavailable/rejected/oversized GET
lookup retain `unknown`, not a confirmed failure. State and registration are
process-local; durable composition and general absence semantics remain separate.
See [ADR 0069](../architecture/0069-github-app-draft-pr-adapter-and-reconciliation.md).

## Uncommitted business-operation lifecycle proposals

The application module's `BusinessOperationTransitionPlanner` additionally builds
uncommitted v1 transition proposals against exact scope and a token covering the
whole snapshot, recovery generation, sequence and all dimension versions. It
revalidates one append and returns defensive copies, with no repository or target
port. Tokens/proposals are not authentication, authority, atomic CAS or observed
outcome evidence. The registration repository still refuses progressed candidates;
future trusted durable lifecycle composition is separate. See
[ADR 0071](../architecture/0071-uncommitted-operation-transition-proposals.md).

`JdbcBusinessOperationLifecycleRepository` adds only a closed pending-owner
withdrawal command and coherent current reads, using a separate head/journal.
Locked READ COMMITTED mutation checks exact whole-state identity and database-time
authority; REPEATABLE READ inspection validates one consistent history. Explicit
V1→V2 migration preserves initial rows and adds column-scoped head update grants.
It is not an arbitrary JSON transition port or a policy/approval/target executor.
See [ADR 0072](../architecture/0072-durable-pending-operation-withdrawal.md).

## OIDC refresh and transport resource boundaries

`CachingOidcJwkResolver` keys its cache by the operator-owned issuer and exact
JWKS URI. Fresh-key reads do not acquire a resolver-wide network monitor.
`JwksRefreshCoordinator` coalesces a provider's in-flight work, bounds active and
queued tasks, uses monotonic waiter deadlines, and records operation-ordered
failure cooldowns even after waiters leave. Successful generation markers
prevent late older failures from regressing a newer success. Cache publication
preserves freshness order; each joining caller independently rejects an entry
expired at its trusted time.

`BoundedHostResolver` owns finite DNS worker/queue capacity;
`SsrfSafeHttpsJwkSetSource` subtracts DNS elapsed time from the HTTP budget while
retaining exact-URI trust, public-address validation, address pinning, TLS and
no-proxy/no-redirect/no-retry behavior. `OidcAccessTokenVerifier` re-samples its
trusted clock after key resolution/signature verification before validating
claims. Interruption, timeout, saturation and source failure cannot grant
identity. Instance-local pools are shared finite capacity, not per-provider
reservations or a distributed ingress limit; OS calls may outlive a waiter.
See [ADR 0049](../architecture/0049-bounded-jwks-refresh-isolation.md).

## Approval listener admission ownership

`ApprovalAdmissionExecutors` supplies `SimulatorApprovalServer` with a fixed
daemon pool and bounded task queue, using rejection rather than an unbounded
fallback executor. Defaults are eight active lanes plus sixteen pending tasks;
constructor bounds are 1..64 and 1..1024 respectively. Executor admission comes
before all handler statements, including Host/URI/closed checks. Only an admitted
handler can authenticate, parse a protected body, use PostgreSQL or dispatch.
Queueing runs no handler code but retains the connection and received buffers;
the bound counts tasks per server, not sockets or bytes.

Close stops the listener, calls worker shutdown, awaits at most two seconds and
then interrupts remaining workers. Daemon workers can outlive close if a
dependency ignores interruption; the caller-owned datasource is not closed.
This is not a drain, target-outcome guarantee or forced JDBC/OS cancellation.
No public pre/post-attribution abuse-control or fairness/SLO claim follows.
Exact approval binding and atomic durable execution claims are unchanged. See
[ADR 0050](../architecture/0050-bounded-simulator-approval-admission.md).

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

The separate `persistence-mariadb` module implements only this standalone claim
boundary. Its explicit empty-database initializer and validate-only runtime
accept exact MariaDB 11.4.13 (primary) and 10.6.25 (transitional), require
InnoDB, and reject unknown versions or schema objects. A plain tenant/permit
primary-key insert is the serialization point; only duplicate-key error 1062
means replay. Commit acknowledgement uncertainty fails closed. The module is
not wired into a launcher and does not implement approval, evidence, policy or
recovery state. See [ADR 0066](../architecture/0066-mariadb-permit-consumption-foundation.md).

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
action snapshot and tenant-scoped reads. PostgreSQL V6/V8 implements this port;
`HumanApprovalService.requestApprovalWithSnapshot` requires it. Execution uses
the original stored canonical action rather than a new client submission.
In durable policy mode, approvals are bound to the active policy version,
canonical content hash, and monotonic activation generation via
`ApprovalPolicyBinding` and `tp_approval_policy_bindings` (migration V8).
`HumanApprovalService` re-verifies generation prior to permit issuance, and
`PostgresDurableEnforcementState.consumeAndClaim` locks `tp_active_policies` `FOR SHARE`,
failing closed with `STALE_POLICY_GENERATION` if generation, version, or content
hash differs from the binding snapshot. The durable service rejects unbound
approvals. The raw claim port retains static compatibility only when both
saved binding and claim policy context are absent; a saved binding cannot be
bypassed by omitting claim context. This is not a database-global mode fence.
Rollback to an identical prior policy version advances generation and rejects
previous approvals. Stale pending/approved rows are retained, not automatically
cancelled. See [ADR 0042](../architecture/0042-durable-policy-generation-binding.md).

Activation after the Java precheck may allow an internal permit to be minted,
but the atomic claim still prevents stale consumption and dispatch. Claim
commit is the ordering point: later activation does not cancel a committed
claim, and locks are released before target invocation.
`PostgresPolicyGenerationBoundaryTest` forces both row-lock schedules and
checks creation/precheck races, legacy compatibility, insert rollback and lost
commit acknowledgement. Default launchers and the source-free distribution
remain static-policy compositions; only the opt-in Java factory wires this
durable lifecycle integration.

`SimulatorApprovalConfiguration` validates private bounded POSIX files,
explicit single-client/tenant OIDC trust, independent requester/reviewer enrollment,
numeric loopback DB configuration and matching Ed25519 keys. The separate
`SimulatorApprovalMain` separates explicit empty-only `--init-db` from normal
run. `PostgresDatabaseLifecycle` checks existing tables, invariant triggers,
schema guard and complete migration history/checksums; strict state/permit-store
factories never migrate. Legacy development constructors remain auto-migrating.
See [ADR 0045](../architecture/0045-explicit-database-initialization.md).
`upgradeFromV9` is a separate owner-only, cooperating-session-locked path:
validate exact predecessor9, transactionally apply V10, strictly validate10,
then report success. It is never invoked by runtime open. SQL cancellation
provenance/terminal guards support `DurableApprovalCancellationState`; the
`HumanApprovalService.cancel` path accepts a distinct `TrustedRequesterContext`
and never calls policy, signer, claim or target. PostgreSQL rechecks deadlines
after recovery/request locks and atomically appends transition/evidence/outbox.
Bearer/browser routes share this service; no MCP tool or React mutation is added.
See [ADR 0059](../architecture/0059-explicit-v9-v10-simulator-upgrade.md) and
[ADR 0060](../architecture/0060-authenticated-pending-request-withdrawal.md).
The runtime validates before binding the
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
`manifest.json` schema `1.1` recording the clean source Git commit and commit
timestamp, package version, required Java 21, and SHA-256 dependency inventory,
alongside `SHA256SUMS`. Commit/time/cleanliness are declared Gradle inputs;
dirty or unavailable metadata fails packaging instead of emitting stale or
`unknown` provenance. Tar metadata is normalized and sibling build outputs are
excluded. Shared runtime JARs are not class-stripped; only the launcher entry
point is fixed. Checksums provide file
integrity verification, not third-party code signing. All credentials and private keys
remain operator-owned outside the bundle. See
[ADR 0040](../architecture/0040-source-free-simulator-approval-distribution.md).

`scripts/build-container-install-profile.sh` turns that exact archive into a
source-free Java 21 OCI image through a minimal temporary build context. The
image records source commit and archive hash labels, defaults to UID/GID 10001,
declares no exposed port and contains neither build tools nor private input.
`deploy/container/compose.yaml` uses Linux host networking so both Java and
PostgreSQL can preserve their `127.0.0.1` constraints without a port mapping.
The application runtime is read-only/capability-free; a limited one-shot root
container stages only the fixed private-file allowlist and cannot replace
different retained bytes. `/health` is listener liveness; `/ready` calls an
injected fail-closed probe that validates existing PostgreSQL state and the
non-quarantined recovery row. Existing test factories inject an explicit local
ready probe and cannot select a public bind. See
[ADR 0062](../architecture/0062-loopback-container-install-profile.md).

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
