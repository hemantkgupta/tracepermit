# AgentCore execution-disabled ingress

Cloud 2 owns `codex/agentcore-trusted-ingress`, new ingress classes/tests,
ADR0078 and scoped documentation until a final pushed handoff releases ownership.
Base: `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`. Draft only; leave unmerged.

Environment startup verified the retained `tracepermit-java21-docker` Start
instructions, clean isolated checkout, exact main/base, saved Java21 selector,
Node24.19.0, pnpm11.25.0, managed Unix Docker28.4.0 and all three required images.
Two preflights and nine tooling tests passed. Historical setup results are not
fresh product qualification. Private planning remains excluded and unread.

Implemented boundary: bounded, strict Java registration through the existing
`BusinessOperationRepository`; enrollment-derived scope and exact operator
profile; no execution or target credential port, listener, launcher or CI change.
Workload identity does not prove human delegation. An unrelated second token
must be refused until a provider-specific verifiable relationship is qualified.
Focused and independent repository results are recorded below. Full-project and
exact-final-head hosted receipts belong to the final PR handoff.

## Closed request and operator setup

See [ADR0078](../architecture/0078-agentcore-execution-disabled-registration.md)
for official pinned sources, trust decisions and the exact missing OBO contract.
Use one explicitly enrolled issuer/subject/client/scope/target per instance.
Configure the unchanged verifier's exact HTTPS issuer/audience/key and tenant
mapping, plus `operations.register`. Do not expose this library through a public
listener or accept a request-supplied enrollment/context/profile.

The closed request has schema1.0, an ASCII `clientRequestKey` (1–128 characters),
nonblank `title` (at most256 UTF-16 code units), and optional nonblank `body`
(at most16384 code units). Maximum UTF-8 request size is32768 bytes. There is no
requester, workspace, policy, routing, approval or lifecycle field. Unsupported
fields, duplicate JSON, malformed UTF-8, trailing values and parser-limit
violations are refused. Absent body and a supplied nonblank body are distinct
semantic content.

The only accepted identity is an enrolled workload using the existing strict
access-token profile. Any second authorization value is refused. No adapter
accepts arbitrary body-supplied assurance flags. Synthetic signed-token results
are not proof of AWS/Okta compatibility, exchange or live key retrieval.

## Failure and retry behavior

- `AGENTCORE_REQUEST_INVALID`: repair the closed request; verification did not run.
- `AGENTCORE_DELEGATION_CONTRACT_REQUIRED`: stop OBO integration; see the missing
  provider grant/exchange contract in ADR0078.
- `AGENTCORE_IDENTITY_REJECTED`: verify operator enrollment and token trust/scope;
  no identity details are returned.
- `AGENTCORE_REGISTRATION_REJECTED`: expiry, replay, replay capacity/dependency or
  generated binding failure; registration did not run.
- `AGENTCORE_REQUEST_KEY_CONFLICT`: changed content at an existing scoped key;
  do not treat it as the original registration or retry an external effect.
- `AGENTCORE_REGISTRATION_UNKNOWN`: storage acknowledgement was uncertain. Only
  an explicit same-key registration with fresh identity can resolve metadata.
- `AGENTCORE_STORAGE_UNAVAILABLE`: stop and inspect operator-owned storage health;
  the adapter performs no automatic retry.

A consumed token stays consumed after a failed/uncertain insertion. A fresh
token permits a same-key explicit retry through the repository. Replay tracking
uses exact credential fingerprints, not `jti`, and the tested bounded store is
process-local. Registration and its proposed policy metadata never grant approval
or execution. Token expiry during storage cannot turn registration into authority.

## Synthetic qualification record

Fresh focused run:71 adapter cases, zero failures/errors/skips. Tests include
real local RSA signatures, an independently calculated scoped-key golden digest,
wrong issuer/audience/type/key/signature/time/scope, unenrolled subjects/tenants,
refused unrelated valid token pairs, authority/routing field rejection, parser
bounds, exact-token replay and16-way contention, dependency/error redaction,
original-return retry and same-key changed-content/target/generation forwarding.
The recording repository intentionally tests only the port boundary and forced
error mapping; it is not a JDBC implementation or durable-idempotency proof.
There is no execution, credential or target transport dependency to call.

Separately, the unchanged exact operation fixtures were forced with caches
disabled:78 cases each on PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25, plus11
strict reader cases (245 total); zero failures/errors/skips. All14 actionable
tasks executed in1m14s, with fresh XML captured immediately. This qualifies the
existing repository separately, not an end-to-end ingress/JDBC composition.

Frozen dependency install, formatting,1121 local documentation links/three helper
tests and13 flow/drift tests passed before the final interface-spec addition;
final documentation checks and full-project/hosted results are recorded in
[PR109](https://github.com/hemantkgupta/tracepermit/pull/109). Historical environment
setup receipts are not substituted for these fresh results.

Reproduce focused and separate repository qualification from the verified
published environment, with the saved Java selector and platform-managed Docker:

```bash
export TRACEPERMIT_JAVA_HOME=/workspace/.local/jdk-21
export XDG_DATA_HOME=/workspace/.local/share XDG_CACHE_HOME=/workspace/.cache
source scripts/configure-java-environment.sh
/usr/local/bin/docker-init -s -- java/gradlew -p java :adapters-remote:test --tests '*agentcore.*Test' --no-daemon --max-workers=2
/usr/local/bin/docker-init -s -- bash scripts/run-java-operation-tests.sh --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
```

Bound full-check Gradle workers with the command-scoped system property
`-Dorg.gradle.workers.max=2` in `GRADLE_OPTS`, preserving existing options. Do not
change test-internal concurrency, workloads, deadlines, assertions or coverage.
Keep the exact final commit's checks distinct from earlier ownership checkpoints.
All PRs remain draft/unmerged. No live provider/target/customer interaction,
public listener, production credential or production-readiness claim is authorized.
