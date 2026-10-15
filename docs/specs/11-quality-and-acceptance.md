# Quality and acceptance

**Status:** Current POC gates and separate production targets

Security invariants need executable positive and negative tests. A passing
local suite proves the named composition, not arbitrary-client coverage or
production readiness.

## Repository gates

```bash
pnpm check
pnpm format:check
pnpm docs:links
pnpm portability:check
pnpm test:endpoint-connector-e2e
pnpm test:approval-api --rerun-tasks
npm --prefix site ci
npm --prefix site audit --audit-level=low
pnpm site:build
node --test scripts/site-search-compatibility.test.mjs
pnpm demo:verify
```

`pnpm check` builds, typechecks, and tests the React console, verifies the
Node/Java environment, and runs the Java aggregate test and coverage gates.
PostgreSQL integration tests use disposable Testcontainers; Docker is needed
for those checks. Runtime-affecting changes also need the closest numbered
simulator scenario or an equivalent protocol smoke test.

The site uses a separate npm lockfile. CI installs it with `npm ci`, rejects
reported dependency advisories at low severity or higher, and builds the static
output. A compatibility test compiles the real search component and verifies
its canonical hot-update binding and generated index in an isolated site fixture.
See the [site dependency runbook](../runbooks/site-dependency-maintenance.md)
for the scoped Vite override and the conditions for removing it.

Demo lifecycle acceptance (OPS-004, SEC-013) requires `SIGINT`, `SIGTERM`, and
`SIGHUP` to exit successfully through cleanup exactly once. A real child exit
must still fail, startup validation must keep its error status, and normal
shutdown must stop children and remove the local endpoint key and session.
`pnpm demo:verify` runs subprocess regressions for both launcher profiles.

## Behavior acceptance

| Boundary         | Required evidence                                                                                                                  |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Authentication   | Invalid, expired, wrong-issuer/audience, replayed, or sender-mismatched credentials stop before protected dispatch                 |
| Canonicalization | Unknown tools/schemas and client-asserted security fields do not become trusted action context                                     |
| Policy           | Allow, block, sanitize, and approval each have positive and negative tests; block and unresolved states never invoke the simulator |
| Sequence         | History is gateway-owned and tenant/subject scoped; injected client history cannot create authority                                |
| Approval         | Independently authenticated reviewer, exact request binding, expiry, and atomic single-use claim; self-approval and mutation fail  |
| Permit           | Signature, audience, canonical hash, time, and one-time claim checked before simulated effect                                      |
| Delegation       | Parent-linked scope, resource, audience, and lifetime narrowing; broadening fails                                                  |
| Evidence         | Credentials are redacted; integrity/outbox/receipt checks and observer failures cannot turn a denial into an allow                 |
| Recovery         | Unknown outcome remains unknown until confirmed by read-only lookup; no automatic re-execution                                     |
| Console          | Read-only projection; it cannot approve, issue permits, or execute                                                                 |

The hosted-client profile has a separately documented single-composition
Auth0/ChatGPT/private-tunnel qualification. It does not establish general
provider compatibility. The default demo uses loopback and deterministic
simulators; no real destructive target is part of acceptance.

The isolated approval API test adds actual loopback HTTP and cryptographic
access-token verification to the PostgreSQL approval path. Its synthetic IdP
and operator enrollment are not a live third-party login qualification. It
tests durable snapshot/approval atomicity, tenant/owner and reviewer-role
isolation, stale policy, replay, concurrency, parse bounds and timestamp
precision. It does not qualify a public edge or browser approval UI.

Operator bootstrap acceptance also requires strict private-file/configuration
rejection, matching persistent keys, offline check with unreachable dependencies,
DB failure before listening, pending/consumed state across reconstructed startup,
and a real JVM SIGTERM/port-release test. These run in `pnpm test:approval-api`
and the aggregate suite. Existing numbered demos remain separate development
compositions; bootstrap qualification is not a live external-IdP test.

## Performance and release qualification

Measure classifier/policy time separately from token/JWKS, persistence,
network, and simulator time. Record p50/p95/p99, contention, expiry, outage,
and overload behavior without disabling security checks. Local scenario
latency is not a production SLO. A production release additionally requires
managed credentials and key custody, shared security state, target egress
controls, independent reviewer authentication, external audit integration,
alerting, rollback, and representative client/load tests.

For a change to a security boundary, update requirement IDs in the
[traceability matrix](12-traceability-matrix.md), the relevant
[ADR](../architecture/), and the public architecture/contract documentation.
