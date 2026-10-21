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
pnpm build:container-profile
pnpm test:container-profile
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

The Linux container install profile has a separate destructive-to-owned-fixture
gate. It builds from the clean source-free archive, generates synthetic secrets
and Ed25519 keys at runtime, creates a random Compose project, explicitly prepares
and initializes disposable state, checks React/health/readiness and numeric
loopback sockets, then removes only its own containers and volumes. It must also
reject retained-key replacement, default secrets, wildcard/public binding and
database-outage readiness. Passing it does not qualify Docker Desktop, an
external IdP, TLS/reverse proxy, sender constraint or production deployment.

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

| Boundary         | Required evidence                                                                                                                       |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Authentication   | Invalid, expired, wrong-issuer/audience, replayed, or sender-mismatched credentials stop before protected dispatch                      |
| Canonicalization | Unknown tools/schemas and client-asserted security fields do not become trusted action context                                          |
| Policy           | Allow, block, sanitize, and approval each have positive and negative tests; block and unresolved states never invoke the simulator      |
| Sequence         | History is gateway-owned and tenant/subject scoped; injected client history cannot create authority                                     |
| Approval         | Independently authenticated reviewer, exact request binding, expiry, and atomic single-use claim; self-approval and mutation fail       |
| Permit           | Signature, audience, canonical hash, time, and one-time claim checked before simulated effect                                           |
| Delegation       | Parent-linked scope, resource, audience, and lifetime narrowing; broadening fails                                                       |
| Evidence         | Credentials are redacted; integrity/outbox/receipt checks and observer failures cannot turn a denial into an allow                      |
| Recovery         | Unknown outcome remains unknown until confirmed by read-only lookup; no automatic re-execution                                          |
| Console          | Default demo is read-only; separate authenticated browser inbox requests/reviews/executes only through shared simulator approval checks |

The hosted-client profile has a separately documented single-composition
Auth0/ChatGPT/private-tunnel qualification. It does not establish general
provider compatibility. The default demo uses loopback and deterministic
simulators; no real destructive target is part of acceptance.

The isolated approval API test adds actual loopback HTTP and cryptographic
access-token verification to the PostgreSQL approval path. Its synthetic IdP
and operator enrollment are not a live third-party login qualification. It
tests durable snapshot/approval atomicity, tenant/owner and reviewer-role
isolation, stale policy, replay, concurrency, parse bounds and timestamp
precision. It does not qualify a public edge.

Optional endpoint observation capture (FR-016/017, SEC-003/012/014) must remain
off by default, preserve protocol-only stdout, use strict private-file bounds
and never serialize a credential, signed proof, private key, device ID, raw
argument or full executable path. Stability failure must produce neither a
record nor a forward; requested-capture failure must prevent forwarding.
The recorded request hash must match the signed method/params binding. Tests
must reject link/replacement/permission/growth/bounds failures and separately
prove missing, mutated, wrong-credential and replayed proofs cannot add a
simulator dispatch. Synthetic test observations are not native client proof;
see the [rehearsal procedure](../runbooks/native-endpoint-observation-rehearsal.md).

The optional browser profile additionally requires real HTTP cookie/CSRF and
signed ID/access-token tests, one-use browser-bound login state, wrong
issuer/audience/nonce/subject/time/hash rejection, current membership checks,
owner/distinct-reviewer/viewer authorization, logout/expiry/restart invalidation
and durable approval preservation. `SimulatorApprovalBrowserTest`,
`BrowserIdTokenVerifierTest` and outbound HTTPS token POST tests supply automated
evidence. The React client tests assert role-plus-scope guidance, exact hash
submissions, same-origin credentials and no automatic mutation retry. The
test-only `pnpm demo:approvals:browser` composition allows rendered sign-in,
request, viewer, independent review and owner execution qualification without
a personal account. Neither this synthetic profile nor offline validation
qualifies external provider compatibility, production cookies or a public edge.

Operator bootstrap acceptance also requires strict private-file/configuration
rejection, matching persistent keys, offline check with unreachable dependencies,
DB failure before listening, pending/consumed state across reconstructed startup,
and a real JVM SIGTERM/port-release test. These run in `pnpm test:approval-api`
and the aggregate suite. Existing numbered demos remain separate development
compositions; bootstrap qualification is not a live external-IdP test.

## Endpoint connector I/O deadline acceptance

FR-016/017, SEC-003/011/014, REL-001/002/005/009 and OPS-004 require bounded
failure behavior for the local endpoint connector. The exchange budget covers
complete HTTP body receipt, including stalled/partial success, error and
redirect responses. Tests must retain byte limits, safe codes and the caller's
interrupt flag, and independently observe cancellation at an owned loopback
peer. They must detect a removed deadline, not rely on fixture shutdown to
finish the call. Terminal-probe tests cover blocked output and pending child
exit under one budget, without manufacturing terminal presence.

Fixture waits and cleanup joins must be finite. These are local I/O controls,
not production latency, universal process termination or zero-effect claims.
A timeout after send never authorizes an automatic tool retry. See
[ADR 0057](../architecture/0057-endpoint-io-deadlines.md).

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
