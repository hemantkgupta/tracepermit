# Durable AgentCore registration qualification

Date: 25 October 2026 (Asia/Kolkata). Local qualification and repository gates
passed. Exact-head hosted CI is reported separately in PR120 and the handoff.

## Source and ownership

Only `codex/m2-agentcore-durable-registration-qualification` is owned by this task.
The original main checkout and prior PR118 qualification are preserved. The new
sparse clone excluded `agentcore-work/` before materialization and never read,
staged or published that private subtree. GitHub remains authoritative.

- First frozen parent: PR118 `b7d5c1a336bc4a456fa179e9d1ed7c0f63a6d758`, including
  repaired PR113 `996b7d1dc135f5c0e922007975f7d36870c128dc`.
- Second frozen parent: PR111 `317a1be331b46292699a1e5bbf2e824537a27eb8`, including
  PR109 `bc2486a18558dc14998db840187024fd2a2faac9`.
- Shared main baseline: `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`.
- Separately identifiable integration merge: `eb0fdb30a163e4aed36adf54f207b0c939e891ab`.
  Only additive documentation conflicts were resolved; existing Java behavior
  was not resolved, rewritten or weakened.
- Draft stacked [PR120](https://github.com/hemantkgupta/tracepermit/pull/120) targets
  `codex/m2-replay-repair-qualification`. All PRs remain unmerged.

New code is confined to `java/agentcore-durable-registration`, with additive
Gradle inclusion, ADR0086 and focused specification/traceability documentation.
The root `checkAll` includes every subproject's `check` and aggregate coverage;
the new module is a required member, with no Docker-availability skip.

## Supported composition

Call `DurableAgentCoreRegistration.create` with a trusted concrete verifier,
enrollment/clock, separate initialized replay and operation datasources, explicit
capacity and finite DML budget. Install each dedicated database separately using
the producer's explicit lifecycle before construction. Runtime only validates;
there is no migration, schema adoption or in-memory fallback. Caller pools are
not closed. No listener, deployment executable, live key fetch, target client,
approval/admission/permit/outbox/dispatch dependency is introduced.

The public receipt contains only original operation identity and creation status,
with `executionEnabled=false`. Token consumption precedes a separate registration
commit. The token can remain spent after registration fails. Never automatically
retry an unknown acknowledgement; an explicitly supplied new verified token can
perform request-key idempotent metadata recovery without authorizing execution.
See [ADR0086](../architecture/0086-durable-agentcore-registration-composition.md).

## Requirement and evidence matrix

All groups below run equivalently on each exact database profile. These are
partial requirement pointers, not whole-ID completion claims.

| Requirements                         | Actual composition evidence                                                                                                                                                                                                                                                     | Limit                                                                                                  |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| FR-003/012; SEC-001/003/009/012      | Real runtime-generated RSA tokens, concrete verifier and ingress, exact enrollment/profile reuse, fingerprint-only persisted identity evidence, minimal execution-disabled receipt.                                                                                             | Synthetic key trust and operator enrollment, not live provider interoperability.                       |
| FR-008/011; SEC-006; REL-001/002/004 | Shared JDBC replay precedes real JDBC registration; independent observations assert one claim/counter, original operation, exact three facts and current head; no lifecycle journal/authority tables.                                                                           | Registration is neither a human delegation grant nor admission/authority. No cross-database atomicity. |
| SEC-006/009; REL-004                 | Two 32-contender races use independent ingress/store instances: one token admits exactly one registration; 32 distinct verified tokens under one scoped key create one original and 31 explicit idempotent outcomes.                                                            | Bounded local synthetic contention, not production load or every distributed schedule.                 |
| SEC-006; REL-001/004                 | Fresh ingress/store/repository connections reject a spent token; a genuinely terminated first JVM and newly launched second JVM share the stores and reject the same token.                                                                                                     | Process restart, not database crash, failover or backup restoration.                                   |
| FR-003; SEC-001/012; REL-001         | Actual wrong-key/signature, issuer, audience, scope, enrollment and expiry negatives plus forbidden body authority fields refuse before replay/registration writes; human-token pairs always refuse.                                                                            | Existing producer token/body profiles remain unchanged.                                                |
| SEC-006; REL-001/002                 | Capacity saturation/mismatch, missing/unready schema and unchecked datasource initialization refuse without fallback; real replay INSERT permission denial has a valid owner control and cannot reach registration.                                                             | Trusted operator capacity and partial schema validation; no DBA or restore fencing.                    |
| SEC-006; REL-001/002/004             | Real replay commit/cleanup calls with lost acknowledgements, including successful calls returning after unchanged expiry, retain a claim but make zero registration calls; original token stays refused.                                                                        | Statement timeouts and conservative late acknowledgement are not total datasource deadlines.           |
| REL-001/002/004; SEC-006/012         | Known operation rollback after two actual fact inserts leaves zero partial operation/facts/head while replay remains spent; commit/cleanup ambiguity is unknown despite independently visible complete registration. Fresh identity performs explicit original-return recovery. | No automatic retry, outbox, permit or target-side exactly-once claim.                                  |
| OPS-002/004; SEC-003/012             | Fixed startup/ingress diagnostics discard causes/suppressed data, connections close, caller pool close is never invoked, exact inventories exclude execution stores, and fixture workers/children/containers terminate.                                                         | Deployment pool/network bounds, revocation and production operations remain operator work.             |

Idempotency tests distinguish changed semantic content (conflict, token remains
spent) from changed proposed policy/artifact metadata (return original, no
overwrite). Changing a request key never makes a spent token reusable.

The child protocol passes bounded synthetic configuration, public key and signed
token/body only through inherited anonymous pipes. It accepts no secret argv,
token/configuration files or listeners, and emits only fixed result codes. Input,
output, child exit and cleanup have finite waits. An owned TEST-only process-local
replay control returns EXISTING in the second JVM, and the same replay-refusal
oracle demonstrably rejects that result. The production factory has no such
control or generic replay/repository override. The wrong-public-key fixture
separately proves actual cryptographic verification, rather than a success stub.

Counter-lock tests hold a real database lock, observe an entered real counter
UPDATE remaining blocked, then compare a positive pre-expiry release with a
release after unchanged expiry. They use real waits, not clock advancement.
Late-acknowledgement tests also assert that the selected real successful JDBC
stage is reached before expiry and delays its return across that deadline.

## Environment and bounds

Reused Mac Air setup: arm64 host, 16 GiB RAM, Java and javac21.0.13.0.2,
Node22.23.4, pnpm11.25.0, Gradle8.14.3 and Docker29.9.2 Linux aarch64. No
machine-wide installation, Docker restart/prune or environment rebuild occurred.
One heavy Gradle/coverage writer ran at a time. Gradle worker count1 did not
reduce the test-internal 32 contenders. Dedicated replay/operation catalogs share
one owned engine container per profile; published test ports are asserted to bind
numeric127.0.0.1 only. All three images are native arm64, without substitution:

| Exact image            | Observed digest                                                           |
| ---------------------- | ------------------------------------------------------------------------- |
| `postgres:16.4-alpine` | `sha256:5660c2cbfea50c7a9127d17dc4e48543eedd3d7a41a595a2dfa572471e37e64c` |
| `mariadb:11.4.13`      | `sha256:250e1609e93c0e6aecf64bfbab2d84d5e89d2481540fe77447ff6c855c3b1873` |
| `mariadb:10.6.25`      | `sha256:6055d4ee6a6045626442cafe4feaf7419e878c65baf3af658265e24114af1cd7` |

Fixture connections use five-second connect and ten-second socket limits;
session lock waits are five seconds. Production statements retain their producer
timeouts (explicit two-second replay budget in this fixture, five-second operation
queries). Child input is bounded to10s, exit30s, output5s and termination/worker
cleanup5s stages. Race start is10s, aggregate contender completion30s and executor
cleanup10s. Test methods have a180s bound; framework/container startup and cleanup
have separate finite bounds. These fixture settings are not library-enforced
total commit, rollback, pool acquisition or cleanup deadlines.

## Exact commands and receipts

Use the existing Java21 JDK and Docker setup; do not install or rebuild it:

```sh
export TRACEPERMIT_JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="/opt/homebrew/opt/node@22/bin:/opt/homebrew/bin:/Applications/Docker.app/Contents/Resources/bin:$PATH"
export JENV_VERSION=21
export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.workers.max=1"
corepack enable
pnpm install --frozen-lockfile
bash -c 'set -euo pipefail; source scripts/configure-java-environment.sh; exec java/gradlew -p java :agentcore-durable-registration:test --no-daemon --max-workers=1 --rerun-tasks --no-build-cache --no-configuration-cache'
pnpm check
# Only after diagnosing/preserving the first failure and explicit human approval:
caffeinate -i pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

The prepared Gradle dependency cache can be reused through
`TRACEPERMIT_GRADLE_USER_HOME`; source/build/report directories remain in this
isolated checkout. No dependency-directory symlinks were copied from another Mac.

| Gate                                      | Result                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Compilation                               | PASS, 11/11 tasks executed.                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| Initial PostgreSQL slice                  | PASS, 34 cases before the additional closed-public-API case; zero failures/errors/skips; all23 tasks executed, 1m15s.                                                                                                                                                                                                                                                                                                                                            |
| Complete new module, both caches disabled | PASS, 35 cases per engine, 105 total, zero failures/errors/skips; all23 actionable tasks executed, 7m11s.                                                                                                                                                                                                                                                                                                                                                        |
| Aggregate and documentation gates         | Second full attempt PASS: 124 console tests; 2,272 recorded Java cases, zero failures/errors, one existing opt-in installed-archive/native-PTY skip. LINE 94.187%, BRANCH 78.032%, unchanged 94%/75% gates. Java checkAll 9m5s: 26 tasks executed, 16 from cache, 32 up-to-date; configuration cache reused. The new module check was included and reused its separately preserved 105-case output. Format, 1,149 local links, flows and diff whitespace passed. |
| Hosted CI                                 | Reported against the exact pushed head in PR120 and the final handoff; prior-head green checks do not qualify new code.                                                                                                                                                                                                                                                                                                                                          |

The fresh XML receipt was saved immediately before any aggregate run. The user
interrupted the chat while the module ran; the existing process was observed to
finish successfully, not terminated or replaced with an overlapping writer.

## Failure and inconclusive history

No composition test failed or skipped in the initial PostgreSQL or fresh complete
module runs. The first aggregate check failed in two unchanged HTTP adapter cases:
`SimulatorApprovalAdmissionTest.repeatedCyclesDoNotLeakAdmissionSlots` and
`SimulatorApprovalRedactionBoundaryTest.browserViewerAndReviewerSeeMaskedDataAndReviewTheOriginalHash`.
Both failed with `HttpTimeoutException`; 428 HTTP cases completed, with two
failures and one existing opt-in skip. The run ended after29m23s, before aggregate
coverage completion. Host power transitions overlapped the failed run; this is
a plausible environment explanation, not proof of causality. An unchanged focused
diagnostic rerun passed both cases, with all24 tasks executed, caches disabled,
in25s. The failed receipt and sanitized failure summary were preserved. The human
explicitly approved one second aggregate attempt under a temporary `caffeinate -i`
assertion; no persistent power setting, deadline, assertion or workload changed.
The second attempt passed in9m5s with the counts and cache reuse above; the first
failure remains recorded here. No third broad attempt was made. The temporary
awake assertion ended with its command; no persistent sleep/lock setting changed. Integration publication preparation first exceeded the local output
envelope and then omitted the automatically merged interface-spec blob from an
API tree request (HTTP422). Neither failure moved a branch. After the missing
reconciled blob was uploaded, local and remote tree hashes matched before normal
fast-forward publication. These are publication-preparation errors, not passing
qualification evidence. No third broad retry, reduced assertion/deadline/workload,
producer runtime change or threshold reduction was used.

This is execution-disabled synthetic registration composition, not live AWS,
Okta, AgentCore or customer interoperability, OBO, human approval, admission,
target execution, restore/failover fencing, instant revocation or production
certification. Existing producer limitations remain explicit and unchanged.
