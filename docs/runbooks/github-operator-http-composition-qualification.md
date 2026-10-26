# GitHub operator HTTP composition qualification

Requirements: SEC-003/007/012/015; REL-001/005/009; OPS-004.
Base: released PR114 `76aa4fe19b290fda7d0813aa9f65dc85d96506ac`.
This tests/docs-only slice belongs to the isolated
`codex/github-operator-http-composition-qualification` branch. Final exact-head
results and writer release are recorded in its draft stacked PR.

## Qualification level

[ADR0076](../architecture/0076-github-installation-credential-provider.md)
provides a concrete installation credential provider and bounded HTTP transport.
The existing `PilotPreflightTest` composes that provider with in-memory
`CredentialExchange` replies and a real target HTTP peer. This slice fills the
remaining cross-transport gap using both real transport implementations together.

New test sources:

- `PilotHttpCredentialCompositionTest.java` in the pilot-operator GitHub test package.
- `credentials/PilotHttpCredentialFixture.java` in that module's test sources.

The fixture creates an RSA2048 key pair in memory and passes it through the
existing package-private provider constructor. The provider performs its real
signing and validation; its real `GitHubCredentialTransport.synthetic` session
makes the installation GET and restricted token POST. A forwarding wrapper
observes the real close acknowledgement and injects only named close/clock
boundary faults. It never supplies an in-memory substitute HTTP reply.

The test assembly passes the acquired credential to the actual
`GitHubTargetPreflight.verify` and `GitHubApiTransport.synthetic` session. It
returns an existing fixed `PilotOperatorMain.Result` diagnostic label only after
the real preflight and target close return. This is a test-only library
composition; it does not invoke the production CLI/configuration loader or
production fixed-origin factory. Existing operator CLI/configuration/archive
qualification remains separate; see the
[operator runbook](pilot-operator-distribution.md).

No production, launcher, dependency, schema, enforcement, existing fixture/test
or CI file changes are required. Neither synthetic factory becomes a production
configuration flag. Both JDK HTTP fixture servers bind only numeric127.0.0.1.
No public listener, DNS/public egress, live App/provider/target, approval,
admission, operation permit, execution, PR creation or reconciliation is used.

## Evidence and representative matrix

The positive control requires this exact sequence:

1. One generated-key read and credential session open.
2. Real GET `/app/installations/{pinnedInstallationId}`.
3. Real POST to that installation's `/access_tokens` with exactly one integral
   pinned repository ID and the actual closed three-permission profile.
4. Credential transport close acknowledgement before any target session opens.
5. Exactly four real target GETs: installation repository selection, exact
   repository identity, explicit head ref/SHA and base `main` ref/SHA.
6. Target close acknowledgement before the fixed
   `PREFLIGHT_OBSERVED_NON_AUTHORITATIVE` test result.

The server independently verifies each App JWT's RS256 signature with the
runtime-generated public key, exact algorithm/type/issuer/claim set and frozen
lifetime (`iat=now-60`, `exp=now+540`,600seconds between them). It compares only
JWT digests across GET/POST; raw JWTs are not recorded. Target Authorization must
contain exactly the token supplied by that synthetic HTTP exchange. Allowed
routes/methods are closed; any other request, including `/pulls`, is a failure.
Query parameter names/count/values are exact and do not depend on map order.

| New composed case                                     | Required outcome                                                                                |
| ----------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Valid two-transport sequence                          | Two credential requests, four target GETs, acknowledged cleanup, fixed non-authoritative result |
| Installation-ID mismatch                              | One credential GET; no mint POST or target contact                                              |
| Broader token permissions or repository selection     | One mint POST; no target contact                                                                |
| Token redirect or503 with Retry-After0                | One mint POST, no additional HTTP exchange or target contact                                    |
| Lost token response after request receipt             | One mint POST, bounded refusal; no retry or target contact                                      |
| Malformed or65,537-byte credential response           | Bounded fixed refusal; no target contact                                                        |
| Reported close failure after real credential close    | Fixed cleanup refusal; no target contact                                                        |
| Credential equals expiry after real close             | Synthetic-clock equality refusal; no target contact                                             |
| Target head-SHA mismatch or redirect after valid mint | One credential exchange; exact bounded target GET prefix; no follow-up/write                    |

There are13 new cases: one positive, ten credential-side negatives and two
target-side negatives. No availability skip is used. Lost response/cleanup
failure never proves that no credential was minted; unused-token expiry remains
a separate conservative boundary. Cleanup acknowledgement here means the actual
transport close method returned, followed by bounded teardown of owned fixture
servers/executors. It is not certification of live-provider cleanup.

## Secret handling and finite work

The new tests keep generated private/public key material, tokens and transient
JWT/request/response material in memory. They use JDK HTTP handlers rather than
a request-recording server. No key files, captured headers, request bodies,
JWTs, tokens or raw exception diagnostics are written, rendered in assertions,
logged or placed in evidence. Evidence contains only fixed route/event labels,
counts, booleans and fixed diagnostic codes. Request-body byte buffers are
bounded to4097bytes and erased after validation.

Each real credential/target sequence retains a three-second synthetic transport
budget within the existing allowed production bounds. Each test has an outer
15-second deadline; each fixture executor close has a three-second bound. The
production defaults and deadlines are unchanged. Fixed2030 clocks exercise only
the named cleanup-expiry equality case; this is not a real wait or clock-skew
qualification. No implicit retries, caches or alternative credentials are added.

## Existing coverage retained

The existing provider suites already cover the expanded identity/permission/
typed-payload mutation matrix, status ranges, malformed JSON shapes, decoded
compressed-body bounds, complete-body budgets, key sources, clock rollback and
exception boundaries. The operator suites cover private configuration/Unicode/
files, offline DNS/provider tripwires, all target mutations, caller cancellation,
target cleanup and installed-process behavior. These cases are retained rather
than copied into a second exhaustive suite. The new close/expiry cases add a
real HTTP acquisition before the existing package-private cleanup seam.

Production TLS handshakes, fixed public-origin/DNS behavior, live GitHub
assurance compatibility, key custody and actual CLI factory/network wiring are
not established by these numeric-loopback HTTP tests. The existing synthetic
policy tests remain distinct. Neither fingerprints nor a successful preflight
are current authority or permission to dispatch. No new native-Mac qualification
or cross-host byte identity is claimed.

## Commands and distinguishing control

Use the prepared environment's saved Java21 selector and retained Gradle home.
Run Gradle test/coverage/XML writers serially:

```bash
java/gradlew -p java :pilot-operator:test --tests '*PilotHttpCredentialCompositionTest' \
  --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
java/gradlew -p java :pilot-operator:test \
  --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
java/gradlew -p java :adapters-remote:test --tests '*github.credentials.*' \
  --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

A temporary test-only change to the expected repository restriction must make
the positive HTTP composition fail. Restore the fixture byte-for-byte before
final focused/full gates. Never remove a production guard or relax the request
count to obtain green. Preserve any initial fixture failures, selector issues,
expected control failures, skips and cache reuse in the final handoff. Any
archive build must use clean exact-source provenance and the existing installed
inventory/process gates; an older archive is not a new-head receipt.
