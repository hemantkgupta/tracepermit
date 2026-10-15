# Current-state capability matrix

**As of:** 2026-10-15
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

| Boundary                    | Implemented and verified                                                                                                                                                                                                                                                             | Important limit                                                                                                                                                                           |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Canonical action and policy | Java validates the checked-in `contracts/` artifacts, derives action class, severity, argument intent, and bounded gateway-owned sequence signals, then evaluates ordered allow/block/sanitize/approval policy.                                                                      | No private-model reasoning is observed. Unknown tool/schema and missing sequence context are conservative. No learned-model classifier is in the synchronous path.                        |
| Local MCP/CLI origin        | Loopback Java MCP adapter authenticates development credentials. The CLI profile verifies an exact request- and credential-bound Ed25519 source proof from the Java stdio connector.                                                                                                 | The development key provider and process observer are not managed-device or hardware-backed attestation. A source proof does not authorize an action.                                     |
| Scheduled origin            | Java verifies an operator-trusted signed workload identity and a complete, parent-linked, monotonically narrowed delegation chain.                                                                                                                                                   | The qualified path is a local simulator composition; no production scheduler transport, durable registry/revocation service, or real target credential exists.                            |
| Hosted MCP origin           | Java OAuth/JWKS verifier and remote MCP adapter perform issuer/audience/client/tenant/scope/time checks, protected-resource discovery, per-invocation authorization, and server-owned action normalization.                                                                          | One Auth0 + ChatGPT + private-tunnel profile was live-qualified on 2026-10-11. Claude web, other IdPs, general provider compatibility, and a public production runtime are not qualified. |
| Hardened remote edge        | Direct-TLS sender-constraint, Host/Origin checks, independent pre/post-auth rate-limit ports, readiness, and bounded telemetry have automated tests.                                                                                                                                 | The hosted loopback demo does not turn these ports into a managed distributed edge. Shared abuse state, certificate lifecycle, exporter, and deployment bootstrap remain target work.     |
| Permit and execution        | A policy-approved canonical action receives a short-lived request-bound permit verified before safe simulator dispatch. Local stores enforce process-local single use; PostgreSQL composition atomically claims permits and idempotency keys across tested contention/restart cases. | The local demo is not durable across restarts. Production signing-key custody, downstream credential isolation, and real-target anti-bypass are absent.                                   |
| Human approval              | A separate authenticated loopback HTTP API combines operator-enrolled identity/roles, exact PostgreSQL snapshots, distinct review and atomic execution; strict local bootstrap is available.                                                                                         | No browser login, React approval inbox, public production reviewer edge or real target. The default console remains read-only.                                                            |
| Evidence and recovery       | Java appends redacted, hash-linked evidence; PostgreSQL outbox publication, independently verified signed receipt, and lookup-only unknown-outcome reconciliation have tests.                                                                                                        | The development stream is process-local. No production external sink, external target lookup, retention program, or independent audit account is configured.                              |
| Read console                | React displays bounded Java action, evidence, lineage, and policy projections over loopback.                                                                                                                                                                                         | It cannot authorize, approve, execute, or mutate policy. It is not remotely authenticated or tenant-authorized.                                                                           |
| Downstream targets          | Deterministic, stateful AWS/Jira simulators support the numbered scenarios.                                                                                                                                                                                                          | No real AWS/Jira SDK, cloud credential, or production side-effect route is present.                                                                                                       |

## Runnable paths

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

This adds a tested authenticated local API boundary; it does not turn the
read-only console into an approval UI, compose the hosted MCP path into it,
qualify an external IdP login, or provide a public product bootstrap. The
default demo/hosted behavior and limitations in the matrix above are unchanged.
Snapshots are operational state with bounded simulated arguments and credential
fingerprints; the audit ledger still omits raw arguments and reusable secrets.
See the [API runbook](../runbooks/authenticated-approval-api-foundation.md).

- `pnpm demo` starts the combined loopback Java backend and read-only React
  console. [Scenarios 0–11](../demo-scenarios/README.md) cover source proof,
  allow/block, secure tunnel, exact approval, scheduled identity, semantic
  sequence, and fail-secure behavior. They use simulators only.
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
tests, the static website, or the one live-qualified hosted-client profile.

## Trust limitation

`/v1/*` accepts an internal normalized contract and is safe only behind a
trusted adapter. Its schema cannot turn a client-provided `verified` flag into
proof. The supported local composition binds development HTTP surfaces to
loopback; authenticated remote MCP is the external trust boundary. The read
API is also loopback-only and has no remote tenant authorization.
