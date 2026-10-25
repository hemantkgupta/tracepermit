# Current-state capability matrix

**As of:** 2026-10-25
**Scope:** public Java-backend/React-console proof of concept

This is the authoritative boundary between running POC behavior and product
design. A passing test proves the named composition, not deployment to an
enterprise network or control over arbitrary agent tools. The detailed
requirement-to-test mapping is in the [traceability matrix](12-traceability-matrix.md).

## Opt-in closed durable operation review

[ADR0085](../architecture/0085-closed-durable-operation-review.md) adds an opt-in
V4 Java/JDBC transaction for policy evaluation, independent-human approve/deny
and pending withdrawal. Current profile/generation control is locked before the
actual operation head, then fresh writer time and exact state/identity/group/
deadline/strict target pins are checked. Immutable evaluation bindings, closed
facts and head changes share one commit; coherent readers replay at fact time.
Default V2 installation and V1-V3 migration bytes remain unchanged. See the
[candidate qualification record](../runbooks/durable-operation-review.md).

This library is not wired into an authenticated endpoint or simulator. It does
not provide permit/admission, attempt/outbox, target dispatch, live IdP/AWS/RDS
qualification, complete operator metadata visibility or restore/sender fencing.
Existing simulator flows below remain separately qualified compositions.

The human-approved next [permit/admission contract](../architecture/0087-fully-bound-operation-permit-and-admission.md)
requires fresh owner execute permission and separately versioned, explicitly
configured Ed25519 authority capped at60 seconds and existing approval/identity
deadlines. This is a proposed implementation contract, not a new running path.

## Approval review export and browser download

The local authenticated simulator approval API provides a bounded per-request
[metadata export](../runbooks/approval-review-export.md), governed by current
read scope/enrollment and the existing owner/workspace visibility rules. It omits
arguments, raw evidence, credentials and target responses. The unsigned payload
checksum is not an original-action, full-history, transactional-snapshot or
authorship proof. It grants no execution authority. The React inbox exposes an
explicit, session-bound download after closed-schema and request-binding checks;
a required browser gate saves the file from the real Java route and passes it to
the installed Java-only offline checker for closed-structure and checksum
validation. See [ADR0064](../architecture/0064-unsigned-approval-review-export.md).

## AgentCore GitHub draft pull request adapter foundation

The Java backend adds `GitHubDraftPullRequestAdapter` and `GitHubPullRequestOutcomeResolver`
in `adapters-remote` for the first AgentCore pilot operation `github.pull_request.create`.
The adapter strictly pins the production origin HTTPS `api.github.com`, repository
`tracepermit/agentcore-pr-pilot`, base branch `main`, and enforces mandatory `draft: true`.
Transport invariants enforce public-only pinned DNS, platform TLS, `Proxy.NO_PROXY`,
disabled redirects/retries, bounded connect/read/write and a ten-second total
preflight/write or lookup budget, and a 65,536-byte decoded
response limit enforced while reading (at most one overflow byte beyond the limit).
GitHub App installation credentials are provided via a trusted provider port and masked in
memory; audit evidence stores only the cryptographic `credentialFingerprint` (`sha256:...`)
and stable installation/repository identifiers. Because GitHub PR creation lacks a native
idempotency key, an explicit stable operation key (`sha256(installId:repo:head:base)`) and
read-only lookup port (`GET /repos/{owner}/{repo}/pulls?head={owner}:{head}&base={base}&state=all`)
support uncertain timeouts and transport disconnects without re-execution
([ADR 0069](../architecture/0069-github-app-draft-pr-adapter-and-reconciliation.md)).
Oversized HTTP 201 confirmations and unavailable lookups (including 401/403/404,
redirects and oversized lookup bodies) retain `unknown`; they do not prove failure.
An explicit operator record pins installation/repository/owner IDs and exact
head/base refs and SHAs. GET installation-access/repository/ref observations
must match before POST. Typed 201 identity and original content must match;
malformed success remains unknown. GET lookup confirms only one complete exact
open draft; empty, duplicate, paginated, unavailable or mismatching results
remain unknown. Legacy unbound construction cannot dispatch/certify. See
[ADR0073](../architecture/0073-github-pilot-target-preflight.md). The token's
installation provenance remains a trusted credential-provider responsibility;
GET visibility alone cannot prove it. Ref races and process-local state remain:
there is no native CAS, exactly-once claim or durable executor. The separate
pure [binding bridge](../architecture/0075-github-business-operation-binding.md)
checks operation scope/policy/artifacts/generation and exact target/precondition
hashes using a strict non-production profile. It returns data only and does not
compose the existing permit, claim transaction or adapter execution.
In-memory simulator target executions (`mode: "simulated"`) remain explicitly distinguished
from live pilot operations (`mode: "pilot"`). See the [operator runbook](../runbooks/agentcore-github-pr-pilot.md).

## Uncommitted operation transition proposals

The Java application layer also provides an
[uncommitted transition proposal primitive](../architecture/0071-uncommitted-operation-transition-proposals.md).
It checks exact scope and optimistic state identity, appends one valid v1 fact,
and preserves other fields/history. It neither commits nor authorizes anything;
the registration repository rejects progressed candidates. Atomic lifecycle
persistence exists only for the closed pending-owner withdrawal command in
[ADR 0072](../architecture/0072-durable-pending-operation-withdrawal.md): separate
heads/journal, locked token/authority checks and explicit V2 migration. Generic
transitions, policy/approval decisions and service/API wiring remain pending.

## Assurance vocabulary

- **Implemented:** code exists in the public tree and passes automated tests.
- **Local demo:** a reproducible local script crosses the stated components.
- **Live-qualified:** a named third-party client and identity-provider profile
  was exercised on a stated date; no broader compatibility is implied.
- **Target:** an architectural design or port without a qualified production
  implementation.

## Business-operation contract foundation

Java and the checked-in language-neutral artifacts now validate a strict
business-operation snapshot around an immutable canonical action. Deterministic
tests bind canonical, semantic, resource/precondition and target-generation
digests; keep transport/event/operation/request/target identities separate; and
enforce three independent append-only lifecycle dimensions. The isolated JDBC
registration library additionally commits an initial snapshot and all three
initial facts atomically, returns an original scoped-key retry, rejects changed
bindings and reports commit uncertainty. One shared test suite runs on MariaDB
11.4.13/10.6.25 and PostgreSQL 16.4, including concurrent repository instances,
restart, restricted runtime grants and corruption checks. This library remains
unwired: operation APIs, authority-bearing lifecycle transitions, AgentCore,
GitHub dispatch, S3 delivery, RDS and production deployment remain pending. See
[ADR 0068](../architecture/0068-versioned-business-operation-contract.md),
[ADR 0070](../architecture/0070-durable-business-operation-registration.md) and the
[repository runbook](../runbooks/business-operation-repository.md).

## Current capability by boundary

| Boundary                    | Implemented and verified                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | Important limit                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Canonical action and policy | Java derives action/sequence risk and evaluates ordered allow/block/sanitize/approval policy. An optional Java lifecycle library with in-memory and PostgreSQL durable stores validates/stages candidates, preserves immutable revisions, simulates decisions, and atomically activates/rolls back revision snapshots under optimistic concurrency. Numeric ingestion is bounded to the current Java `int` profile.                                                                                                                                                                                                               | No learned-model classifier or private-model reasoning. Policy lifecycle is an internal Java/PostgreSQL foundation; authenticated policy administration endpoints and UI wiring remain deferred. Approvals compose with active policy generations via opt-in lifecycle integration. Default launchers retain static policies.                                                                                                                                          |
| Local MCP/CLI origin        | Loopback Java MCP adapter authenticates development credentials. The CLI profile verifies an exact request- and credential-bound Ed25519 source proof from the Java stdio connector.                                                                                                                                                                                                                                                                                                                                                                                                                                              | The development key provider and process observer are not managed-device or hardware-backed attestation. A source proof does not authorize an action.                                                                                                                                                                                                                                                                                                                  |
| Scheduled origin            | Java verifies an operator-trusted signed workload identity and a complete, parent-linked, monotonically narrowed delegation chain.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | The qualified path is a local simulator composition; no production scheduler transport, durable registry/revocation service, or real target credential exists.                                                                                                                                                                                                                                                                                                         |
| Hosted MCP origin           | Java OAuth/JWKS verifier and remote MCP adapter perform issuer/audience/client/tenant/scope/time checks, protected-resource discovery, per-invocation authorization, and server-owned action normalization.                                                                                                                                                                                                                                                                                                                                                                                                                       | One Auth0 + ChatGPT + private-tunnel profile was live-qualified on 2026-10-11. Claude web, other IdPs, general provider compatibility, and a public production runtime are not qualified.                                                                                                                                                                                                                                                                              |
| Hardened remote edge        | Direct-TLS sender-constraint, Host/Origin checks, independent pre/post-auth rate-limit ports, readiness, and bounded telemetry have automated tests.                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | The hosted loopback demo does not turn these ports into a managed distributed edge. Shared abuse state, certificate lifecycle, exporter, and deployment bootstrap remain target work.                                                                                                                                                                                                                                                                                  |
| Permit and execution        | Request-bound permits guard simulator dispatch. Existing PostgreSQL execution/approval atomically claims permit, idempotency, attempt and evidence. Separate PostgreSQL and unwired MariaDB standalone consumption adapters have verified-tenant routing, explicit action hashes and commit-before-success tests; MariaDB 11.4.13 is primary and 10.6.25 transitional. Per-principal lock striping and linearized authorization timestamps in `SecureExecutionService` protect sequence and policy evaluation across distinct principals without global serialization. Dispatcher admission bounds concurrent execution attempts. | Default local stores are process-local. Standalone consumption is not the composed execution transaction. MariaDB has no approval, evidence, policy or recovery composition and is not launcher-wired or RDS-qualified. Production signing keys, target credentials and anti-bypass remain absent. Striping and admission control are process-local; they do not provide distributed concurrency guarantees, per-tenant fair queuing, or an end-to-end throughput SLA. |
| Human approval              | Separate authenticated loopback API plus optional same-origin browser sign-in/React inbox share operator roles, exact PostgreSQL snapshots, distinct review and atomic simulator execution. An opt-in Java factory additionally binds approvals to durable active-policy generations. One Auth0 native/public same-Mac source-free composition was live-qualified on 2026-10-22.                                                                                                                                                                                                                                                  | Default launchers and the host-native source-free distribution use static policies, not the opt-in lifecycle composition. Generation-bound stale claims cannot dispatch; later activation does not cancel a committed claim. The live result covers one synthetic test registration only; no public reviewer edge, arbitrary IdP or real target. Default console remains read-only.                                                                                    |
| Container install profile   | A Linux Docker Engine profile packages the source-free Java/React distribution with digest-pinned PostgreSQL, explicit prepare/check/init/run phases, non-root read-only application runtime, numeric-loopback host networking, safe health/readiness and disposable qualification.                                                                                                                                                                                                                                                                                                                                               | TP-MVP-05 foundation only. Linux host networking, external IdP, TLS/reverse proxy, sender constraint, shared abuse state, signed provenance, backup/restore and public/production deployment remain unqualified.                                                                                                                                                                                                                                                       |
| Evidence and recovery       | Java adds a bounded credential/pattern redaction library and applies its default profile to approval-response action projections (ADR 0043), preserving original request hashes and exact action snapshots. It appends redacted, hash-linked evidence; PostgreSQL outbox publication, independently verified signed receipt, and lookup-only unknown-outcome reconciliation have tests.                                                                                                                                                                                                                                           | No global log/export or before-persistence redaction hook; unkeyed hashes are not secret confidentiality. The development stream is process-local. No production external sink, external target lookup, retention program, or independent audit account is configured.                                                                                                                                                                                                 |
| Read console                | React displays bounded Java action, evidence, lineage, and policy projections over loopback.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | It cannot authorize, approve, execute, or mutate policy. It is not remotely authenticated or tenant-authorized.                                                                                                                                                                                                                                                                                                                                                        |
| Downstream targets          | Deterministic, stateful AWS/Jira simulators support the numbered scenarios.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | No real AWS/Jira SDK, cloud credential, or production side-effect route is present.                                                                                                                                                                                                                                                                                                                                                                                    |

## Public-client browser candidate

Schema1.1 explicitly selects `none` for a native/public client, with code/S256
PKCE, no secret or Authorization header, exact loopback redirect and configured
organization binding in both signed tokens. Baseline API read scope is required;
`openid` is requested for OIDC and need not be echoed into API-token scope.
Schema1.0 confidential behavior remains compatible. Synthetic protocol,
local-TLS and PostgreSQL tests exercise these boundaries. One exact Auth0
native/public registration completed live sign-in, organization binding,
distinct review, explicit execution and restart durability on 2026-10-22.
A missing/unsupported response still fails closed. The
[local-Mac procedure](../runbooks/auth0-native-oidc-qualification.md) and
[sanitized record](../demo-scenarios/evidence/installed-auth0-approval-2026-10-22.md)
bound that result. It does not qualify the container preparation profile,
a public edge, arbitrary OIDC providers or production identity.

## OIDC refresh isolation

The Java key resolver serves fresh cached keys without waiting for unrelated
provider network work. Same issuer/JWKS refreshes coalesce; default coordinator
capacity is four active workers plus sixteen pending tasks and a five-second
monotonic waiter budget. Default DNS capacity is eight active workers plus
sixteen pending tasks, shared across sources using the default resolver. DNS
elapsed time reduces the remaining HTTP transport budget. Saturation and
dependency failure refuse authentication rather than using expired keys.

Joining callers check key freshness against their own trusted time. Late
results cannot extend cache freshness; operation-generation bookkeeping prevents
an older failure from reinstating cooldown after newer success. Access-token
claim verification re-samples trusted time after key resolution and signature
verification. These are tested local-instance controls, not distributed abuse
state, provider fairness, a universal wall-clock deadline or additional live
IdP qualification. An uninterruptible DNS syscall can retain a bounded worker
after its waiter stops. See [ADR 0049](../architecture/0049-bounded-jwks-refresh-isolation.md)
and the [refresh runbook](../runbooks/jwks-refresh-isolation.md).

## Runnable paths

### Named native endpoint rehearsal

The [2026-10-15 native Codex proof](../demo-scenarios/evidence/codex-native-endpoint-2026-10-15.md)
correlates CLI 0.162.1/`gpt-5.6-sol` model-selected simulated read/block with Java
CLI-origin action-feed events. `pnpm demo:endpoint:codex` adds development-only
preflight and invocation-scoped stdio configuration alongside `pnpm demo`.
It forwards selected Java 21, resolves the native executable and issues one
five-minute synthetic credential; it neither refreshes nor retries tool calls.
This is separate from bearer-only durable approvals. Full retained process facts,
manual proof-negative rehearsal, measured live dispatch counts, protected keys,
managed enrollment and host-wide anti-bypass are not established by this pair.

An optional `--capture-observations` launch now prepares a private, bounded Java
process-fact/request-hash transcript for a future native rehearsal. It is off
by default and records after stability checking but before gateway forwarding;
it is not acceptance/execution proof. The four-case HTTP rejection test uses a
synthetic observation and measures no extra dispatches. See the
[capture runbook](../runbooks/native-endpoint-observation-rehearsal.md); no new
native/model qualification is implied.

### Authenticated approval API foundation

The separate approval listener bounds executor tasks before any handler work:
eight active lanes and sixteen pending tasks by default. Constructor-only
capacity validation permits 1..64 active and 1..1024 pending tasks; launchers
use defaults. Overflow refuses the connection before authentication, protected
body parsing, database work or simulator dispatch, without a guaranteed JSON
503 response. Queued exchanges still retain connections and buffered bytes.
Host/URI/closed checks run inside admitted lanes; malformed requests therefore
consume capacity until handled. Close stops the listener, waits up to two
seconds for workers, then interrupts them; blocked daemon workers can linger.
This is local task-count control, not TCP/byte bounds, a drain, forced target/
JDBC cancellation, public abuse protection or production readiness. Approval,
permit and replay semantics are unchanged. See
[ADR 0050](../architecture/0050-bounded-simulator-approval-admission.md) and the
[admission runbook](../runbooks/simulator-approval-admission.md).

The authenticated approval collection supports exact metadata filters and
bounded keyset pagination, retaining current enrollment/scope/tenant/owner
checks and response redaction. Cursors are unsigned positions, not authority;
reads do not change approval/effect state. This is not a complete action-history,
retention or export feature. The React inbox has exact filters, explicit older
pages and a 250-row cap, with response validation, abort/generation guards and
read-error/logout invalidation. Mutation controls require fresh current authority.
Actual synthetic browser review/request/execute and client fault tests qualify
this local profile only; no external provider compatibility is implied. See the
[history API](../runbooks/approval-history-api.md).

Loaded pending/approved rows have shared local-clock expiry guidance: controls
and confirmations become unavailable without polling, and the confirmation
handler rechecks current selection/binding/eligibility before POST. Stored status
is not rewritten. Clock skew and background throttling remain UI limits; this
does not change Java time/authorization or the durable claim boundary.

`SimulatorApprovalServer` is a separate, loopback-only Java HTTP factory,
not part of either current demo launcher. `SimulatorApprovalMain` now supplies
the optional `pnpm dev:approvals` startup with strict operator-owned JSON,
one OIDC trust/enrollment configuration, local PostgreSQL and persistent Ed25519
file references. `--check` validates offline, without binding, migration or IdP
traffic. First-install `--init-db` explicitly initializes an empty dedicated
public schema; normal run uses strict non-migrating repository opens and rejects
empty/partial/incompatible state. See [ADR 0045](../architecture/0045-explicit-database-initialization.md).
Strict operator startup now requires V1–V10. A separate owner-only `--upgrade-db`
accepts exactly V9→V10 while services are stopped; ready/unresolved-work, ownership,
history/checksum and lock failures refuse migration. V10 adds cancelled storage
compatibility. [ADR 0060](../architecture/0060-authenticated-pending-request-withdrawal.md)
adds the Java owner-only pending withdrawal route with explicit requester scope,
locked deadline/binding checks and atomic version3 cancellation evidence/outbox.
The local React inbox adds an eligible-owner **Withdraw request** confirmation,
exact-hash POST, cancelled history filter and no automatic retry, with mounted
synthetic-browser regression coverage ([ADR 0061](../architecture/0061-requester-withdrawal-inbox.md)).
No new MCP tool is enabled. A separate named installed Auth0 profile exercised
owner withdrawal on 2026-10-21; the 2026-10-22 release-candidate rerun did not
repeat that case. See
[ADR 0059](../architecture/0059-explicit-v9-v10-simulator-upgrade.md) for upgrade limits.
This does not detect a valid older restored snapshot or add a recovery fence.
The five [generation-bound restore cases](../runbooks/policy-bound-restore-qualification.md)
compare populated current-schema state (V8 policy bindings plus the V9 recovery
row) before/after service recreation and exercise actual
opt-in review/resume. Captured consumed state and stale lifecycle generations
reject replay; an old approved snapshot still revives authority and produces a
second synthetic effect despite strict schema validation. This is a documented
recovery gap, not a safe-restore or production-readiness qualification.
It is not a readiness or external-login qualification. Its integration tests
combine strict RS256 access-token verification, operator-owned human/workload
memberships and per-call scopes with PostgreSQL V6/V8 exact action snapshots,
durable approval policy generation bindings and execution state. They exercise
independent review, owner-only execution, restart, expiry, stale policy/generation,
replay, concurrency and snapshot rollback without a real target. Run `pnpm test:approval-api --rerun-tasks`.

An optional browser configuration now serves the built React inbox and
code/PKCE/state/nonce sign-in on the same numeric-loopback origin. Server-held
tokens, opaque expiring cookies, per-mutation CSRF and per-request token/role
checks protect requester/reviewer/viewer flows. `pnpm demo:approvals:browser`
uses a clearly synthetic test-classpath-only IdP and disposable PostgreSQL.
The same local listener now composes authenticated Codex-candidate MCP
request/status/resume tools with the durable approval service and browser inbox.
See the [MCP approval runbook](../runbooks/codex-durable-approvals.md).
The [2026-10-15 model-driven rehearsal](../demo-scenarios/evidence/codex-model-driven-approval-2026-10-15.md)
qualifies Codex CLI 0.162.1/`gpt-5.6-sol` request → synthetic Bob browser review →
explicit resume, replay/approval-expiry rejection and rejected resume after
explicit Bob denial for this local profile. No dispatch counter was captured
in that manual run. Existing automated claim/concurrency tests are separate proof.
It does not compose hosted OAuth MCP approvals, qualify an external browser IdP,
authenticate the default read API, or provide a public product bootstrap.
The default demo/hosted behavior and limitations are unchanged. See the
[browser runbook](../runbooks/browser-approval-inbox.md) and
[ADR 0036](../architecture/0036-local-browser-approval-boundary.md).
Snapshots are operational state with bounded simulated arguments and credential
fingerprints; the audit ledger still omits raw arguments and reusable secrets.
See the [API runbook](../runbooks/authenticated-approval-api-foundation.md).

### Source-free simulator approval distribution

A host-native archive target (`tracepermit-simulator-approval-0.1.0.tar.gz`) packages
the authenticated loopback approval API, built React inbox assets, runtime libraries,
and thin bash launcher `bin/tracepermit-simulator-approval`. The bundle requires
Java 21, local PostgreSQL on `127.0.0.1`, and external OIDC provider configuration on the
host; it does not require Node.js, pnpm, Gradle, or the source repository checkout.
`--check` validates configuration, permissions, Ed25519 keypair math, and bundled assets
completely offline without network, database, or IdP traffic. Build manifests
(`manifest.json`) and SHA-256 integrity checksums are included; checksums are explicitly
distinguished from third-party code signing. Manifest schema `1.1` identifies the
clean source commit and commit timestamp; packaging tracks these as cache inputs
and rejects dirty or missing source metadata. Shared runtime JARs retain other
main entry points, but the supported launcher selects only `SimulatorApprovalMain`.
All credentials and private keys remain
operator-managed outside the bundle. Automated tests (`scripts/simulator-approval-distribution.test.mjs`,
`pnpm test:distribution`) verify archive layout, offline checking without build tools on `PATH`,
redacted fail-closed errors, clean SIGTERM shutdown with port release, commit-only
cache invalidation, exact file/JAR inventories and repeated staging stability.
See [ADR 0040](../architecture/0040-source-free-simulator-approval-distribution.md) and
the [distribution runbook](../runbooks/simulator-approval-distribution.md).

### Loopback container install profile foundation

`deploy/container/compose.yaml` assembles the same source-free Java/React archive
with digest-pinned PostgreSQL 16 for a Linux Docker Engine host. Application and
database use host networking without published ports and retain their exact
`127.0.0.1` binds. The app runs non-root with a read-only root filesystem, no
capabilities and a read-only private configuration volume. A separate one-shot
setup container copies only named private files and refuses changed retained
keys/secrets. Setup, offline check, empty-only initialization and run remain
explicit operator steps.

The approval listener now exposes bounded local `/health` and `/ready` documents.
Readiness validates the exact PostgreSQL schema and requires non-quarantined
recovery state; it does not contact or qualify an IdP. The container qualification
generates keys/secrets at runtime, verifies source/build-tool exclusion, Compose
hardening, React serving, loopback-only sockets, no remote bind and database-outage
readiness behavior. No target is invoked. See [ADR 0062](../architecture/0062-loopback-container-install-profile.md)
and the [container runbook](../runbooks/container-install-profile.md). Docker
Desktop, bridge/public ingress, TLS/reverse proxy, sender constraint, shared
abuse state, external IdP compatibility and production readiness remain target work.

- `pnpm demo` starts the combined loopback Java backend and read-only React
  console. [Scenarios 0–11](../demo-scenarios/README.md) cover source proof,
  allow/block, secure tunnel, exact approval, scheduled identity, semantic
  sequence, and fail-secure behavior. They use simulators only.
- `pnpm demo:gate-c` executes the automated Gate C deterministic presenter
  qualification suite (Scenarios 4, 5, 7, 9, 10, 11) with verified assertions.
- `pnpm demo:oauth` starts a separate hosted-client OAuth profile and requires
  external IdP configuration. It is not part of normal CI.
- `pnpm check` builds/types/tests the console and runs the Java suite. CI also
  builds the static site and exercises simulator/PostgreSQL qualifications.
- Java 21 is the sole backend. TypeScript is limited to the React console and
  its tooling; the static site is documentation, not an enforcement service.

### Opt-in simulator policy templates

Two default-block candidate templates have a pinned 19-case synthetic corpus
covering 38 dry-run evaluations and 14 qualification tests. Read/write scope
negatives, exact classification/decision fields, mutation sensitivity, input/
catalog isolation and unknown schemas are tested through Java simulation.
See the [runbook](../runbooks/simulator-policy-templates.md).

These candidates are not installed or active by default. Simulation validates
canonical structure, not authentication, tool argument schemas or runtime
authority. It creates no pending approval, permit, target effect or evidence
record. Log-window classification now compares integral limits exactly without
numeric narrowing; 26 delivered boundary tests and five independent regressions
cover that fix. Malformed raw arguments still require trusted-adapter validation.
No broad requirement or production claim is upgraded by these qualifications.

## Post-dispatch completion uncertainty

Direct durable execution and authenticated approval execution now make one
additional same-attempt unknown recording if outcome completion throws. If that
write commits, its evidence/outbox and lookup queue are atomic; a previously
committed succeeded/failed/unknown outcome cannot be overwritten. The original
call remains uncertain and never retries the target. Real PostgreSQL fault tests
distinguish rollback from lost commit acknowledgement in both services. See
[the runbook](../runbooks/durable-completion-uncertainty.md).

A persistent outage or process crash may still leave executing state. There is
no new automatic sweeper, installed worker or rollback fence;
the extra write count is not a new request-latency guarantee.

## Operator-declared containment

PostgreSQL V9 adds an installation-wide, owner-declared, one-way quarantine.
`--quarantine-db` serializes against new durable claims, standalone permit
consumption and approval writes; already-running replicas fail those writes
closed. Completion/evidence and lookup-only reconciliation remain available for
previously admitted work. Owner-only `--recovery-inspect` adds a read-only
repeatable-read snapshot of aggregate stored execution/approval statuses and
reconciliation queue count, without per-request data, expiry refresh or target
lookup. It is not quiescence or recovery-success proof and cannot authorize
resume; see [ADR 0052](../architecture/0052-owner-only-recovery-inspection.md).
`--recovery-status` inspects without migration;
effectful operator startup refuses already quarantined state. Internal permit
minting and process-local demo paths are not fenced. There is no resume or
in-place upgrade command. See [ADR 0051](../architecture/0051-operator-declared-recovery-quarantine.md).

This contains explicitly declared recovery, not an undetected valid old snapshot.
Claims committed before quarantine may still dispatch afterward. Restoring an
older ready row can undo quarantine; no independent monotonic witness is added.

## Database restore boundary

Same-version simulator tests preserve captured consumed approval/permit,
action/hash, attempt/idempotency, evidence and policy/migration state through
actual PostgreSQL custom archives. They also demonstrate two recovery gaps:
an older still-live approval snapshot can dispatch again, and ordinary Flyway
bootstrap can initialize an empty destination after a failed archive restore.
Startup is not successful recovery proof. No automatic quarantine/recovery
epoch, external witness or validated effectful resume procedure is implemented.
See [ADR 0044](../architecture/0044-database-restore-safety-limits.md).

## Bounded process-local replay and consumption

The default source-attestation, workload-identity and execution-permit stores
each retain at most 65,536 entries, with up to 64 expired-record removals per
valid unexpired consume. One monitor makes duplicate/capacity/insertion atomic;
live records are never evicted to admit work. Expired duplicate records still
behind the cleanup budget are conservatively refused until reclaimed. An idle
store retains bounded expired entries; credential lifetime is not a cleanup
deadline. The existing boolean/refusal codes do not distinguish saturation from
replay. Compound source/workload keys are collision-safe; legacy permit IDs
remain globally consumed across tenant-aware overloads.

The three focused store suites and real verifier/authority callback harnesses
qualify these local bounds and single-use behavior. They do not measure target
dispatch, add a wall-clock/fairness guarantee, survive restart, share state
across replicas, fence restores or establish revocation by cleanup. Production
store code is separate from the unchanged durable PostgreSQL coordinator. See
[ADR 0054](../architecture/0054-bounded-local-replay-stores.md) and the
[qualification runbook](../runbooks/bounded-local-replay-stores.md).

## Controls not yet established

The repository is a security-control POC, **not** a deployable enterprise
security product. It does not establish managed endpoint enrollment and key
custody, production multi-tenant control-plane authorization, shared rate and
replay state for a deployed edge, real target credential brokerage/egress
isolation, a reviewed external evidence sink, production SLOs, or broad
third-party client qualification. These require separate threat modeling,
implementation, and release evidence; they must not be inferred from local
tests, the static website, or the named live-qualified client profiles.

## Trust limitation

`/v1/*` accepts an internal normalized contract and is safe only behind a
trusted adapter. Its schema cannot turn a client-provided `verified` flag into
proof. The supported local composition binds development HTTP surfaces to
loopback; authenticated remote MCP is the external trust boundary. The read
API is also loopback-only and has no remote tenant authorization.

## Installed requester sign-in and Codex handoff

The Java approval runtime has a read-only, bearer-authenticated requester
qualification route, and the source-free archive packages a separate requester
helper. It validates an exact owner-only configuration offline, performs one
public Code + S256 login through a fixed numeric-loopback callback, verifies
access and ID tokens, confirms exact requester-only enrollment, publishes one
short-lived no-replace credential and consumes it before starting the pinned
Codex executable with an environment-variable-referenced MCP bearer.

Automated tests cover closed configuration, callback/state/issuer parsing,
public authorization/token shape, role/scope/expiry rejection, credential
permission/link/concurrency behavior, no-body qualification and token-free child
argv. Live provider registration, browser selection, installed Codex catalog,
expiry/re-login continuity and replay remain a separate Mac gate. This is a
same-UID simulator boundary, not production identity or endpoint attestation.
