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

| Boundary                    | Implemented and verified                                                                                                                                                                                                                                                                                                                                                                                                                              | Important limit                                                                                                                                                                           |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Canonical action and policy | Java validates the checked-in `contracts/` artifacts, derives action class, severity, argument intent, and bounded gateway-owned sequence signals, then evaluates ordered allow/block/sanitize/approval policy.                                                                                                                                                                                                                                       | No private-model reasoning is observed. Unknown tool/schema and missing sequence context are conservative. No learned-model classifier is in the synchronous path.                        |
| Local MCP/CLI origin        | Loopback Java MCP adapter authenticates development credentials. The CLI profile verifies an exact request- and credential-bound Ed25519 source proof from the Java stdio connector.                                                                                                                                                                                                                                                                  | The development key provider and process observer are not managed-device or hardware-backed attestation. A source proof does not authorize an action.                                     |
| Scheduled origin            | Java verifies an operator-trusted signed workload identity and a complete, parent-linked, monotonically narrowed delegation chain.                                                                                                                                                                                                                                                                                                                    | The qualified path is a local simulator composition; no production scheduler transport, durable registry/revocation service, or real target credential exists.                            |
| Hosted MCP origin           | Java OAuth/JWKS verifier and remote MCP adapter perform issuer/audience/client/tenant/scope/time checks, protected-resource discovery, per-invocation authorization, and server-owned action normalization.                                                                                                                                                                                                                                           | One Auth0 + ChatGPT + private-tunnel profile was live-qualified on 2026-10-11. Claude web, other IdPs, general provider compatibility, and a public production runtime are not qualified. |
| Hardened remote edge        | Direct-TLS sender-constraint, Host/Origin checks, independent pre/post-auth rate-limit ports, readiness, and bounded telemetry have automated tests.                                                                                                                                                                                                                                                                                                  | The hosted loopback demo does not turn these ports into a managed distributed edge. Shared abuse state, certificate lifecycle, exporter, and deployment bootstrap remain target work.     |
| Permit and execution        | A policy-approved canonical action receives a short-lived request-bound permit verified before safe simulator dispatch. Local stores enforce process-local single use; PostgreSQL composition atomically claims permits and idempotency keys across tested contention/restart cases.                                                                                                                                                                  | The local demo is not durable across restarts. Production signing-key custody, downstream credential isolation, and real-target anti-bypass are absent.                                   |
| Human approval              | Separate authenticated loopback API plus optional same-origin browser sign-in/React inbox share operator roles, exact PostgreSQL snapshots, distinct review and atomic simulator execution.                                                                                                                                                                                                                                                           | Synthetic browser profile tested; no live external browser IdP qualification, public reviewer edge or real target. Default console remains read-only.                                     |
| Evidence and recovery       | Java implements a formal field-level argument and evidence redaction framework (`EvidenceRedactor`, ADR 0040) supporting masking, irreversible SHA-256 fingerprinting, and pattern scrubbing across logs, projections, and audit streams, while preserving canonical action hashes. It appends hash-linked evidence; PostgreSQL outbox publication, independently verified signed receipt, and lookup-only unknown-outcome reconciliation have tests. | The development stream is process-local. No production external sink, external target lookup, retention program, or independent audit account is configured.                              |
| Read console                | React displays bounded Java action, evidence, lineage, and policy projections over loopback.                                                                                                                                                                                                                                                                                                                                                          | It cannot authorize, approve, execute, or mutate policy. It is not remotely authenticated or tenant-authorized.                                                                           |
| Downstream targets          | Deterministic, stateful AWS/Jira simulators support the numbered scenarios.                                                                                                                                                                                                                                                                                                                                                                           | No real AWS/Jira SDK, cloud credential, or production side-effect route is present.                                                                                                       |

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
