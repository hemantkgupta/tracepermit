# Current-state capability matrix

**As of:** 2026-10-16
**Scope:** public Java-backend/React-console proof of concept

This is the authoritative boundary between running POC behavior and product
design. A passing test proves the named composition, not deployment to an
enterprise network or control over arbitrary agent tools. The detailed
requirement-to-test mapping is in the [traceability matrix](12-traceability-matrix.md).

## Assurance vocabulary

- **Implemented:** code exists in the public tree and passes automated tests.
- **Local demo:** a reproducible local script crosses the stated components.
- **Live-qualified:** a named third-party client and identity-provider profile
  was exercised on a stated date; no broader compatibility is implied.
- **Target:** an architectural design or port without a qualified production
  implementation.

## Current capability by boundary

| Boundary                    | Implemented and verified                                                                                                                                                                                                                                                                                                                                                                                            | Important limit                                                                                                                                                                                                                                                                   |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Canonical action and policy | Java derives action/sequence risk and evaluates ordered allow/block/sanitize/approval policy. An optional Java lifecycle library with in-memory and PostgreSQL durable stores validates/stages candidates, preserves immutable revisions, simulates decisions, and atomically activates/rolls back revision snapshots under optimistic concurrency. Numeric ingestion is bounded to the current Java `int` profile. | No learned-model classifier or private-model reasoning. Policy lifecycle is an internal Java/PostgreSQL foundation; authenticated policy administration endpoints, UI wiring, and pending-approval stale-policy checks remain deferred. Default launchers retain static policies. |
| Local MCP/CLI origin        | Loopback Java MCP adapter authenticates development credentials. The CLI profile verifies an exact request- and credential-bound Ed25519 source proof from the Java stdio connector.                                                                                                                                                                                                                                | The development key provider and process observer are not managed-device or hardware-backed attestation. A source proof does not authorize an action.                                                                                                                             |
| Scheduled origin            | Java verifies an operator-trusted signed workload identity and a complete, parent-linked, monotonically narrowed delegation chain.                                                                                                                                                                                                                                                                                  | The qualified path is a local simulator composition; no production scheduler transport, durable registry/revocation service, or real target credential exists.                                                                                                                    |
| Hosted MCP origin           | Java OAuth/JWKS verifier and remote MCP adapter perform issuer/audience/client/tenant/scope/time checks, protected-resource discovery, per-invocation authorization, and server-owned action normalization.                                                                                                                                                                                                         | One Auth0 + ChatGPT + private-tunnel profile was live-qualified on 2026-10-11. Claude web, other IdPs, general provider compatibility, and a public production runtime are not qualified.                                                                                         |
| Hardened remote edge        | Direct-TLS sender-constraint, Host/Origin checks, independent pre/post-auth rate-limit ports, readiness, and bounded telemetry have automated tests.                                                                                                                                                                                                                                                                | The hosted loopback demo does not turn these ports into a managed distributed edge. Shared abuse state, certificate lifecycle, exporter, and deployment bootstrap remain target work.                                                                                             |
| Permit and execution        | Request-bound permits guard simulator dispatch. Existing PostgreSQL execution/approval atomically claims permit, idempotency, attempt and evidence. A separate standalone consumption adapter has verified-tenant routing, explicit action hashes and commit-before-success tests.                                                                                                                                  | Default local stores are process-local. Standalone consumption is not the composed execution transaction; production signing keys, target credentials and anti-bypass remain absent.                                                                                              |
| Human approval              | Separate authenticated loopback API plus optional same-origin browser sign-in/React inbox share operator roles, exact PostgreSQL snapshots, distinct review and atomic simulator execution. A host-native source-free distribution packages this composition with a thin launcher and offline check.                                                                                                                | Synthetic browser profile tested; host-native archive qualified without source checkout; no live external browser IdP qualification, public reviewer edge or real target. Default console remains read-only.                                                                      |
| Evidence and recovery       | Java adds a bounded credential/pattern redaction library and applies its default profile to approval-response action projections (ADR 0043), preserving original request hashes and exact action snapshots. It appends redacted, hash-linked evidence; PostgreSQL outbox publication, independently verified signed receipt, and lookup-only unknown-outcome reconciliation have tests.                             | No global log/export or before-persistence redaction hook; unkeyed hashes are not secret confidentiality. The development stream is process-local. No production external sink, external target lookup, retention program, or independent audit account is configured.            |
| Read console                | React displays bounded Java action, evidence, lineage, and policy projections over loopback.                                                                                                                                                                                                                                                                                                                        | It cannot authorize, approve, execute, or mutate policy. It is not remotely authenticated or tenant-authorized.                                                                                                                                                                   |
| Downstream targets          | Deterministic, stateful AWS/Jira simulators support the numbered scenarios.                                                                                                                                                                                                                                                                                                                                         | No real AWS/Jira SDK, cloud credential, or production side-effect route is present.                                                                                                                                                                                               |

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

`SimulatorApprovalServer` is a separate, loopback-only Java HTTP factory,
not part of either current demo launcher. `SimulatorApprovalMain` now supplies
the optional `pnpm dev:approvals` startup with strict operator-owned JSON,
one OIDC trust/enrollment configuration, local PostgreSQL and persistent Ed25519
file references. `--check` validates offline, without binding, migration or IdP
traffic. It is not a readiness or external-login qualification. Its integration tests
combine strict RS256 access-token verification, operator-owned human/workload
memberships and per-call scopes with PostgreSQL V6 exact action snapshots and
durable approval/execution state. They exercise independent review, owner-only
execution, restart, expiry, stale policy, replay, concurrency and snapshot
rollback without a real target. Run `pnpm test:approval-api --rerun-tasks`.

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
