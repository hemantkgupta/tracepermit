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
PostgreSQL and the unwired MariaDB permit-consumption integration tests use
disposable Testcontainers; Docker is needed for those checks. The MariaDB gate
runs the same bounded replay cases on exact 10.6.25 and 11.4.13 images; it does
not qualify RDS, replication or the PostgreSQL approval/evidence composition.
Runtime-affecting changes also need the closest numbered
simulator scenario or an equivalent protocol smoke test.

Simulator listener admission also requires the bounded dispatch controls in
[ADR0074](../architecture/0074-reserved-lane-bounded-dispatch.md): delayed
worker wakeup cannot collapse eight lane reservations into only queue16;
overflow, concurrent producers, failure/interrupt/cancellation and bounded
shutdown remain fail closed. The unchanged complete latency qualification
requires three independent passes under the documented two-CPU test profile,
not retries of failed operations or changes to the default 1/4/20 workload.
This is local simulator evidence, not a public-edge or production SLO claim.

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

GitHub target-preflight acceptance (FR-008/009/012/015; SEC-001/002/003/012/015/019;
REL-001/003/004/005/008/009; OPS-002/004) uses only numeric-loopback fake peers and
runtime-generated keys. The focused remote adapter suite must prove zero POST
for destination/credential/preflight mismatch; strict typed 201 identity;
read-only exact-positive lookup and unknown for absent/duplicate/incomplete/
unavailable observations; no retry, cross-origin credential forwarding or stale
confirmation reuse; bounded chunked reads, time and cleanup; and concurrent
single dispatch. Permanent POST/lookup body-type regressions must reject scalar
coercion and empty containers, retain unknown/no-redispatch and remove stale
confirmation, while exact strings and explicit null for empty bodies still pass.
Guard-removal controls must fail before restoration and the
final gates. The suite replaces ADR0069's permissive/empty-list success/failure
fixtures, without weakening permit or target allowlists. No live target or
durable executor is qualified. See the
[runbook](../runbooks/github-pilot-target-preflight.md).

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

## Approval review export round-trip acceptance

Requirements: FR-007/010/012; SEC-003/005/009/012; DATA-001/003;
REL-001/002/009; OPS-002/004.

`pnpm test:approval-export:browser` is the permanent source-to-file qualification.
Its wrapper runs the focused Java HTTP authorization/export tests and the
installed verifier/distribution tests before starting the rendered browser. The
positive path uses a fixed synthetic clock/action, disposable PostgreSQL and
runtime-generated signing keys. Exactly one React-initiated request reaches the
real Java browser export route, exactly one Playwright download is saved to a
private owned temporary directory, and the exact saved path is passed to the
installed Java 21 launcher. A file-byte SHA-256 is recorded before execution and
must be identical afterward. The successful input is never regenerated,
normalized or replaced with an equivalent fixture.

The gate also requires single-shot fail-closed results for malformed JSON,
oversize, unsupported schema, extra root fields, wrong mode/assurance, wrong
checksum, mutated downloaded payload, missing evidence, denied scope/enrollment
and cross-owner visibility, stale session, refresh/filter/logout invalidation,
and controlled API/download/verifier failure. Each applicable browser control
asserts no retry, no second download, no false success and no canary-bearing
diagnostic. The Java test supplies the role/scope/workspace and missing-snapshot
boundary cases that cannot be safely manufactured through the rendered console.
The gate also fails on an owned listener, process, browser context, container or
temporary artifact that survives cleanup.

This is simulator-only test evidence. The export remains unsigned and is not a
signed attestation. Successful verification is not current authorization and
does not establish original action contents, history completeness, independent
anchoring or a real target effect.

Operator bootstrap acceptance also requires strict private-file/configuration
rejection, matching persistent keys, offline check with unreachable dependencies,
DB failure before listening, pending/consumed state across reconstructed startup,
and a real JVM SIGTERM/port-release test. These run in `pnpm test:approval-api`
and the aggregate suite. Existing numbered demos remain separate development
compositions; bootstrap qualification is not a live external-IdP test.

## Fresh-Mac evaluation preparation (Day 3 pending)

The [canonical fresh-Mac checklist](../runbooks/fresh-macbook-evaluation.md)
separates source/synthetic evaluation, source-free operator installation and
live-provider/installed-requester manual gates. Documentation acceptance for
FR-007/012; SEC-003/011/012; REL-002; OPS-002/004 requires supported Corepack
owner-writable shims pinned by `packageManager`, clean recorded source or a
trusted outer checksum/manifest commit, extraction into a new directory, private
operator files, offline `--check` before empty-only `--init-db`/`--run`, bounded
observation, loopback/port-release checks, distinct disposable versus retained
restart behavior and a bounded private fact-only record.

`scripts/flow-diagrams.test.mjs` checks documented modes/results/archive naming
against the shipped Java main, launcher/build inputs, synthetic account/session
fixture and canonical site include; it parses Bash without executing operator
commands and rejects unsafe install/probe/evidence regressions.
`scripts/site-onboarding-browser.test.mjs` checks the built canonical page,
three paths, critical commands, pending status, anchor navigation and responsive
rendering at phone/desktop widths. An explicit absolute
`TRACEPERMIT_SITE_BROWSER_EXECUTABLE` can select an available local browser for
this documentation test; CI retains the pinned Playwright browser by default.

Run `pnpm docs:flows:check`, `pnpm docs:links`, `npm --prefix site ci`,
`pnpm site:build`, `node --test scripts/site-onboarding-browser.test.mjs`,
`pnpm check`, `pnpm format:check` and `git diff --check`. Passing these gates is
documentation evidence only. It does not complete Day 3, a second-Mac installation,
an archive runtime trial or live external-IdP/installed-Codex qualification.

## Public-client browser acceptance

Schema1.1 `none` requires successful code/S256 exchange without Authorization or
secret, strict configuration/method rejection, exact redirect and independent
ID/API-token organization bindings. `BrowserCodeExchangeTest` and
`SimulatorApprovalConfigurationTest` cover form/configuration boundaries;
`SimulatorApprovalPublicClientTest` covers signed HTTP login without API-token
`openid`, required `approvals.read`, mandatory ID token, wrong credentials,
claims/keys/times, verifier mutation, browser/code/nonce substitution and
concurrent callback replay. ID-token and local-TLS suites retain separate
cryptographic and transport controls; existing confidential/synthetic browser
and durable API tests must remain green.

This is automatically tested behavior, not actual Auth0 login. The
[local-Mac gate](../runbooks/auth0-native-oidc-qualification.md) still requires
native organization support, exact token type and callback `iss`, distinct
review and explicit one-use simulator execution. Missing live variables or an
unreachable cloud loopback browser cannot be relabeled a pass. OPS-005 remains
partial; no public deployment or container-version qualification is inferred.

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

The simulator [transport diagnostic evidence](../runbooks/latency-qualification-transport-diagnostics.md)
preserves default concurrency1/4/20, warmup5/8/20, measured20/32/40, zero-effect,
exact25/40/60 per-sweep deltas and the full real success gate. Fixed transport,
consequential missing-ID and admission categories must never turn a failure
into success or publish arbitrary exception messages/classes. A controlled
negative executor/HTTP probe is separate from qualification: it demonstrates
the frozen barrier's worker-release gap, not the cause of every hosted failure.
The separately authorized package-private qualification helper additionally
waits for worker release under its original total deadline; it changes no
request/runtime enforcement or admission policy. Held-after-Future regression,
budget/interrupt/shutdown cases and three independent unchanged complete
qualification runs are required. A passing rerun or empty asynchronous SQL map is not root-cause proof
(PERF-001/002/003; OPS-002/003/004; REL-001/005/009; SEC-003/005/006/012).

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

## Installed requester sign-in acceptance

Automated acceptance requires closed offline configuration; one-shot numeric
loopback callback; exact public authorization/token shapes; independent signed
access/ID-token validation; requester-only qualification with zero authority or
target mutation; owner-only no-replace publication including a simultaneous
publisher race; and a fixed child launch whose argv/output omit secret sentinels.
The source-free archive must include executable requester and approval launchers,
example configs, runbooks, manifest entries and complete checksums, without test
fixtures, source, provider IDs, keys or credentials.

Live provider and installed-Codex acceptance remains manual: use the exact clean
archive commit, dedicated requester/reviewer identities, numeric loopback only,
and show request → distinct review → explicit resume → one simulated effect plus
expiry/re-login and replay rejection. A missing variable, provider mismatch or
unavailable interactive browser is pending/failed, never an automated pass.
