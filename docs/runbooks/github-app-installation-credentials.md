# GitHub App installation credentials — synthetic foundation

Requirements: FR-001/008/009/012; SEC-001/002/003/012/015/019;
REL-001/003/005/009; OPS-002/004. See
[ADR0076](../architecture/0076-github-installation-credential-provider.md).
This library is unwired and synthetic-qualified only. Do not install credentials,
register an App, call a live provider or dispatch a target under this packet.

## Explicit operator inputs and stop conditions

The operator must independently obtain and approve the App/installation/owner/
repository IDs and the existing allowlisted repository, exact permission map
and private key source. The public factory accepts the immutable configuration
and an absolute key path; it accepts no origin, test flag, trust store, client
secret, request-body role or self-asserted verified fields.

The key source is a private POSIX regular file outside the source checkout,
RSA PKCS#8 DER, 2048..4096 bits, at most16KiB, owner read permitted and no group/
other/execute permissions. It is read only when the provider is explicitly
invoked. No key copy is written. Key preparation/conversion, custody, rotation,
non-POSIX support and live installation require a separate operator-approved
task; no key material or real configuration belongs in this repository.

The required permission profile is exactly `contents:read`, `metadata:read`,
`pull_requests:write`, restricted to one configured repository. The source
organization installation must match the pinned App/installation/account and
report that profile and unsuspended state. The token response must explicitly
confirm selection, exact repository/owner and permissions. GitHub makes these
assurance fields optional: omission is a compatibility stop, never a reason to
infer success from the request. No live compatibility has been tested.

Installation binding is vouched for through the authenticated fixed endpoint
and GitHub minting contract; the token is opaque. The response does not contain
an independent App/installation attestation. Do not decode a stateless token and
treat unverified fields as authority, claim introspection or weaken the existing
[target preflight](github-pilot-target-preflight.md).

## Synthetic verification

Use the prepared Node22+, Corepack pnpm11.25.0, Java21 and cached dependencies.
Full repository gates additionally require responsive Docker and pinned local
database fixtures. No provider credentials are required. From this branch:

```bash
corepack enable
pnpm install --frozen-lockfile
source scripts/configure-java-environment.sh
java/gradlew -p java :adapters-remote:test --max-workers=1 \
  --tests '*github.credentials.*' --console=plain
java/gradlew -p java :adapters-remote:test --max-workers=1 --console=plain
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

All new HTTP fixtures bind numeric loopback only and generate signing material
at runtime. Requests are limited to the synthetic installation GET and restricted
token POST. Assertions reject any PR creation/unrelated request. No test calls
the production factory's network path. No public listener, real token exchange,
customer/provider account or paid API is used.

Controls must retain exact JWT signature/issuer/time and request restrictions,
typed response assurance, expiry/clock boundaries, no retry, byte/JSON bounds,
resource cleanup and sentinel-secret redaction through nested/suppressed errors.
Concurrent calls must own separate sessions/results; no broad credential cache
or refresh loop exists. A later explicit call is a new exchange, not an automatic
retry of the failed invocation. A failed mint/cleanup is not proof no token was
issued; expiry limits residual unused-token exposure.

Do not retain raw JWT, key, token, authorization headers, request/response
fragments, operator file paths or provider diagnostics. Evidence is limited to
source/head, fixed codes, counts, gate outcomes and bounded cleanup observations.
No token or private key is committed, including development/test fixtures.

## Official contract versus local design

Reviewed official GitHub docs at
`02c25d16369fe27f9bdb28d1201f6b452f0d21dd`, and OpenAPI at
`755ee33b4a81d51f6f07a902cce26cbec3b822f2`:

- [App JWT](https://docs.github.com/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app):
  RS256, recommended `iat=now-60`, expiry at most ten minutes in the future;
  issuer may be App/client ID, with client ID recommended. This implementation
  explicitly chooses supported numeric App ID as text and `exp=now+540`.
- [Token exchange](https://docs.github.com/rest/apps/apps#create-an-installation-access-token-for-an-app):
  App-JWT authentication, installation-specific endpoint, explicit repository IDs
  and permissions, one-hour expiry. New stateless formats must not be mistaken
  for fixed40-character secrets; the existing bounded opaque-token profile is used.
- [Installation lookup](https://docs.github.com/rest/apps/apps#get-an-installation-for-the-authenticated-app):
  JWT-authenticated installation identity, App ID, account and permission fields.
- [Pinned token schema](https://github.com/github/rest-api-description/blob/755ee33b4a81d51f6f07a902cce26cbec3b822f2/descriptions/api.github.com/api.github.com.json):
  only token/expiry required; repository/permission assurance is optional. This
  foundation requires it and fails closed. Enterprise tokens cannot be scoped
  down as required and are outside this organization-only profile.

The permission profile, strict response-field requirement, DER/POSIX source,
clock alignment, RSA limits, no-cache choice and transport/cleanup budgets are
local conservative design decisions, not claims that every GitHub App supports
this exact composition. Official facts do not qualify a real App, signing key,
installation, token or target permission.

## Evidence

Focused, retained-regression and broad gate outcomes are recorded in the final
HANDOFF and draft PR. Missing/failed/skipped gates remain explicit. No local
or hosted pass promotes this library to a live integration or production release.

### Recorded local checkpoint

Prepared runtime: Node24.19.0 (meets22+), Corepack pnpm11.25.0, Java21.0.12.1,
one Gradle worker and managed Docker28.4.0. Corepack used the existing prepared
cache and a lane-local shim directory; no toolchain installation or policy change.
Pinned PostgreSQL16.4-alpine and MariaDB11.4.13/10.6.25 images were present.

- PASS frozen installation (`corepack enable --install-directory "$PNPM_HOME"`
  followed by `pnpm install --frozen-lockfile`, with a dedicated shim directory).
- PASS new focused suite:143 cases, zero failures/errors/skips.
- PASS fresh complete `:adapters-remote:test --offline --max-workers=1 --rerun`:
  443 cases, including300 retained GitHub/transport/identity cases and143 new
  credential cases; zero failures/errors/skips. The restored source passed443
  again after the bounded negative control below.
- Negative control: remove only App-ID and repository-ID comparisons, run44
  selected permanent cases. Exactly three failed (wrong App, wrong repository ID,
  string-valued repository ID), with zero errors/skips. The typed-ID comparison
  also guards numeric type, explaining the third failure. Restore was trap-based
  and verified byte-for-byte before the passing443-case rerun. No assertion changed.
- FAIL one `pnpm check`: build/typecheck,124 React tests and Java environment
  check passed. The PostgreSQL-backed HTTP suite ran428 cases:424 passed,
  three failed, one existing installed-archive/native-PTY skip. All failures are
  unchanged `JavaBackendEndpointConnectorE2eTest` process-tree cleanup controls:
  `processTreeTrackerHandlesRootEarlyExitWithoutProcessNameKills`,
  `processTreeTrackerEscalatesToForceKillWhenTermIgnored`, and
  `ptyProcessTreeCleanupTerminatesAllDescendantsWithNoLingeringProcesses`.
  Each surfaced cleanup-unconfirmed at the existing termination helper. No raw
  process identifiers are retained here. Root cause was not established; no
  process helper, workload, assertion or unrelated runtime was changed or retried.
  Later Java modules, forced database-profile gates and aggregate coverage did
  not run after the HTTP failure. No full-gate/coverage pass is claimed.
- PASS formatting, documentation links,13 flow tests/synchronized guides and
  whitespace checks; final counts/commands are in HANDOFF and the draft PR.
- Post-run Docker listing showed zero running Testcontainers-labelled containers.
  This is not proof that the unrelated native process cleanup succeeded: those
  failures remain explicit, and no global/user process cleanup was attempted.

One initial main compile found inaccessible package-private address-policy reuse;
production now calls the existing public `BoundedHostResolver.resolvePublic`
instead of widening/duplicating that policy. Prepared Corepack cache/shim
selection was corrected before frozen install; no setup repair loop occurred.
The negative-control receipt initially expected two failures; actual44-case
output correctly included the numeric-type rejection as the third. It was
recorded without repeating the mutation to obtain a favored count.

All raw local logs, official-source downloads and temporary control backup are
outside Git. Only bounded sanitized facts are published. Hosted exact-head
checks remain separate; earlier source/other-lane results are not substituted.

## Wave 2 independent review and synthetic composition

The approved Cloud 1 packet was read at planning commit
`305c777d69462bfd46179ff6aae6829188e8604c`. The prepared
`tracepermit-java21-docker` environment passed two preflights and all nine setup
tests after activating its saved Java selector: Java/javac21.0.8, Node24.19.0,
pnpm11.25.0, Gradle8.14.3 and managed Unix Docker28.4.0. No setup rebuild,
daemon change, live credential installation or provider/target call occurred.

PR105 ownership was claimed from its released `9405c0e` head. A normal merge of
main `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f` produced
`6c999a8ea9e9efeb6e9e47cd08b235fd757b2125`, preserving both parents and the
complete other-lane handoffs. Only HANDOFF required conflict resolution.
The initial tracked switch could not infer tracking from this checkout's
main-only fetch mapping; a no-track switch completed against the verified exact
PR tree without reset, discard or rewriting any source. No other chat's local
work or private planning file was used.

Review repaired one local buffer-lifecycle gap: rejected HTTP status/pagination
replies now erase their owned decoded bytes in the same `finally` as JSON parse.
The public production surface and all existing target, permit, action, allowlist
and unknown-outcome semantics remain unchanged. Internal client-policy assembly
can be tested with literal public DNS answers; no production DNS/TLS request is
made by those policy tests. They establish retained configuration and rejection
of untrusted certificates, not live handshake/provider compatibility.

The test-source `SyntheticInstallationCredentialFixture` constructs the concrete
provider using generated RSA material and fake in-memory GET/token exchanges.
`GitHubCredentialCompositionTest` supplies it to the unchanged synthetic adapter
and numeric-loopback fake target. It proves successful fingerprint-only result
handling, permit consumption/request binding, allowlist, wrong-App/installation,
failed/expired cleanup, target repository/ref/expiry preflight, process-local
success replay and unknown-outcome refusal to dispatch again. Fresh explicit
adapter invocations may mint another credential even when a local result is
reused or unknown; there is no effectful retry or credential cache. This is a
composition fixture, not a shipped factory, launcher or business-operation
executor, and its in-memory ledger provides no durable authority.

### Review validation

Run with the saved selector and bounded Gradle workers, preserving the test
workloads, assertions and deadlines:

```bash
export TRACEPERMIT_JAVA_HOME=/workspace/.local/jdk-21
export XDG_DATA_HOME=/workspace/.local/share XDG_CACHE_HOME=/workspace/.cache
source scripts/configure-java-environment.sh
pnpm install --frozen-lockfile
/usr/local/bin/docker-init -s -- java/gradlew -p java :adapters-remote:test \
  --offline --no-daemon --max-workers=2 --no-build-cache \
  --no-configuration-cache --rerun-tasks --console=plain
GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.workers.max=2" \
  /usr/local/bin/docker-init -s -- pnpm check
/usr/local/bin/docker-init -s -- bash scripts/run-java-operation-tests.sh \
  --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

- PASS merged baseline:143 focused credential cases, fresh test execution.
- PASS complete remote suite:673 cases/17 suites, zero failures/errors/skips,
  all17 Gradle tasks executed without build or configuration cache. This includes
  the402 merged-main remote cases,143 original credential cases and128 new
  review/composition cases (105 assurance/policy/cleanup, eight transport bounds,
  15 composition). Fresh XML was recorded after successful runner exit.
- Negative control: temporarily restoring only the old status/pagination guard
  yielded exactly two expected failures among four response-buffer cases;
  success and malformed-JSON erasure still passed. The repair was restored
  before the fresh673-case passing run. These expected failures are retained.
- Two intermediate composition-suite runs each had one new fixture assertion
  failure: the expected installation-list URL omitted query parameters, then
  assumed a stable map iteration order for them. The test now verifies exact
  request paths/order plus both named query parameters independently. The
  runtime query semantics and retained tests were unchanged.
- Full repository and forced database results, final documentation checks and
  exact-head hosted results are recorded in the final handoff below/PR105.

The prepared-environment full check passed on the reviewed source:124 React
tests and2,096 recorded Java cases across150 suites, zero failures/errors and
one existing installed-archive/native-PTY skip. Gradle took4m8s with six tasks
executed and59 up-to-date; this is not an all-fresh receipt. Coverage was94.5357%
line/78.5294% branch with unchanged thresholds. Forced operation qualification
then executed all14 tasks without cache in1m12s:78 cases on each of the three
exact database fixtures plus11 strict-reader cases,245 total, zero failures/
errors/skips. Forced standalone MariaDB qualification executed all10 tasks in23s:
17 cases including both exact versions, zero failures/errors/skips. Fresh XML
timestamps/modification times were captured after each successful runner exit.
All three retained image IDs matched the prepared manifest. Formatting,1,130
documentation links/three helper tests,13 flows/drift and whitespace passed;
final documentation-only edits receive the same checks before publication.

Source-control publication uses the scoped GitHub Git Data API rather than
moving the personal network secret to a Git host. Blob, tree and commit IDs are
checked against local Git, including both ordered merge parents; ref updates
are non-forced and guarded by the expected published head. An initial tree
assembly rejected missing auto-merged public-document blobs; uploading those
four blobs produced the exact local merge tree/commit before any ref update.
No source history was rewritten. Exact-final-head hosted results and writer
release belong to the authoritative PR105 final handoff.

The original PR105 local three native process-cleanup failures remain historical
failures. A result obtained in this prepared environment is a separate receipt;
neither it nor hosted success relabels that earlier run. The installed-archive
native-PTY case and any reused test/cache results must be reported separately.
No live installation compatibility, key custody/rotation, durable authority or
production readiness is certified by this work.
