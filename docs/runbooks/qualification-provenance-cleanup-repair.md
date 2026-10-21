# Endpoint Connector Qualification Provenance and Cleanup Diagnostics Repair

Context: assurance repair packet for two residual merged PR65 harness defects.
Baseline: merge of PR65 at `b38e9289d791ffabc1e2142357fc8366301ee201` (current
`main` at branch creation). Branch: `codex/qualification-provenance-cleanup-repair`.
Relevant Requirements: OPS-002 (artifact and configuration provenance), OPS-004
(lifecycle and clean shutdown), SEC-003 (secret hygiene and exposure prevention).
Related Runbooks: [endpoint-connector-distribution.md](endpoint-connector-distribution.md).

This is a **qualification-only** runbook. It documents two test-harness/assurance
defects, their repairs, and the qualification evidence. It does not claim a
package release, a runtime behavior change, or closure of any demonstrated
production authorization bypass. Shared traceability-matrix and ADR updates are
owned by the integrator.

---

## 1. Defect 1: Gradle provenance wiring bypassed the strict bounded resolver

### Anatomy

`java/adapters-http/build.gradle.kts` resolved the expected-head provenance with
`providers.exec { git rev-parse HEAD; isIgnoreExitValue = true }` and forwarded
`stdout.trim()` unvalidated into
`tracepermit.endpointConnector.expectedHeadCommit`. The installed Java
qualification trusts a configured value as the authoritative expected HEAD, so a
private `git` wrapper that printed a valid stale SHA and then exited `73`
authorized a stale archive in strict mode. The separately tested bounded
resolver in `JavaBackendEndpointConnectorE2eTest` (exit-code, malformed-output,
missing-git, and stalled-git fail-closed tests) only runs when the system
property is unset, which never happens under the Gradle wiring.

### Repair (at the real provider boundary)

The provider now:

- bounds the git probe with a finite deadline: a supervising `perl` process
  forks, execs `git rev-parse HEAD` in the child, and enforces a 10-second
  bound from the parent (`alarm` + process-group `SIGKILL`), exiting `124` on
  timeout. The child leads its own process group, so the timeout reaps the
  entire probe tree; without the group kill, an orphaned grandchild holding
  the output pipe delays failure detection well past the deadline (observed
  120 s with a nested `sleep` model before the group kill was added). The
  parent never execs, so the deadline does not depend on alarm-across-exec
  behavior; a failed `exec` (missing provenance tooling) exits `127`;
- drops `isIgnoreExitValue`, so any non-zero probe exit — including exit 73 —
  fails configuration (Gradle `ExecException` with the exit value);
- accepts only a single full-match hex commit (`[0-9a-f]{40}` or
  `[0-9a-f]{64}`); empty, malformed, or ambiguous multi-line output throws a
  fixed fail-closed `GradleException`;
- keeps the exec-backed provider shape, so Gradle's configuration-cache
  tracking and the explicit task inputs are unchanged: a commit-only change
  still invalidates configuration and task caches.

Operator-provided exact-head strict-mode checks (system property / environment
override) are retained unchanged and bypass the probe. A wrapper that prints a
valid-format SHA and exits `0` is indistinguishable from healthy git at this
boundary; explicit operator-provided exact-head checks remain the mechanism for
that scenario.

A new observability task
(`:adapters-http:printEndpointConnectorExpectedHead`) prints the value the
wiring actually injects (`TRACEPERMIT_ENDPOINT_CONNECTOR_EXPECTED_HEAD=`),
giving the qualification battery a direct view of the Gradle→test path.

### Qualification controls

In `scripts/endpoint-connector-distribution.test.mjs`:

- Positive controls: healthy real git resolves exactly `git rev-parse HEAD`; a
  PATH wrapper forwarding to real git yields the identical value.
- Exploit sensitivity: stale-SHA-then-exit-73 wrapper fails the build, the
  failure identifies the non-zero exit value, and no expected-head value is
  printed.
- Empty, malformed, and ambiguous multi-line probe output each fail the build
  with the fixed fail-closed message.
- Unusable git (executable stub exiting `127`, modeling missing provenance
  tooling) fails the build. At the supervising-perl boundary a failed `exec`
  also exits `127`, so the wired control exercises the same fail-closed exit
  path. A non-executable `git` PATH entry is not a faithful model here:
  `execvp` skips it and resolves real git further down `PATH`, which returns
  true provenance and legitimately succeeds.
- Stalled probe (`sleep 120` wrapper) fails closed at the finite deadline:
  the child is killed by the build-side alarm and the test bounds the parent
  (`spawnSync` timeout, elapsed assertion). The wrapper models a stalled git;
  this proves the deadline behavior, not real git behavior.
- Isolated clone (`git clone -s -l`) with the live build file overlaid: a
  commit-only advance re-resolves provenance (printed head changes from commit
  A to commit B), proving configuration/task caches are invalidated for the
  adapters-http wiring.

`EndpointConnectorProvenanceWiringTest` (new, always runs in the
`:adapters-http` suite) asserts that any value the wiring injects into the test
JVM is a validated hex commit.

---

## 2. Defect 2: Unconfirmed-cleanup diagnostics embedded raw kill-error text

### Anatomy

`gracefulTerminate` embedded `termError.message` and `killError.message` in the
`ERR_CLEANUP_UNCONFIRMED` message. A synthetic kill exception could leak an
opaque sensitive canary into public diagnostics, with or without a primary test
failure (the cleanup error travels as suppressed/cause on the primary error).

### Repair

Delivery diagnostics now use fixed safe phase categories only:
`DELIVERED` (kill returned true), `REJECTED` (kill returned false), `FAILED`
(kill threw), `SKIPPED` (not attempted or no kill function). Raw error
messages are never embedded and raw errors are never attached as causes. The
stable code (`ERR_CLEANUP_UNCONFIRMED`), bounded metadata (child name, pid,
timeout, `termSent`/`killSent` booleans), primary-failure preservation
(suppressed/cause attachment, rethrow), listener removal, and
throw-on-unconfirmed behavior are unchanged.

### Qualification controls

Synthetic kill-throw models (honestly labeled; these prove diagnostic hygiene,
not OS unkillability) with canary-bearing kill exceptions:

- primary-absent path: rejection carries only fixed phase categories; canary is
  absent from message and stack; no raw cause attached; listeners removed.
- primary-present path: primary error rethrown; attached cleanup error remains
  canary-free and carries fixed phase categories; listeners removed.
- delivered-TERM-then-rejected-KILL escalation carries per-phase categories.
- cooperative-success positive control still resolves with clean listeners.
- Existing models (`kill → false`, kill throwing, exit-on-TERM, primary
  preservation) keep their stable-code and listener assertions.

---

## 3. Re-running the qualification

From the repository root (macOS or Linux; Java 21 available; on hosts without
Java on `PATH`, source `scripts/configure-java-environment.sh` first):

```bash
pnpm build:endpoint-distribution
```

```bash
pnpm test:endpoint-distribution
```

Focused provenance-only checks (no distribution build needed):

```bash
java/gradlew -p java --no-daemon :adapters-http:printEndpointConnectorExpectedHead
```

```bash
java/gradlew -p java --no-daemon :adapters-http:test --tests "io.tracepermit.adapters.http.EndpointConnectorProvenanceWiringTest"
```

---

## 4. Boundaries and non-claims

- These are assurance/test-harness defects; there is **no demonstrated runtime
  authorization bypass** in the connector, backend, or enforcement path.
- No Java/connector runtime, approval/export/UI/identity/permit/policy/schema/
  migration/dependency, or security-rule behavior changed.
- Strict tests and documentation were not weakened; the stale-archive strict
  qualification and its existing sensitivity controls are unchanged.
- The git probe depends on `/usr/bin/perl` as a supervising parent that forks
  the child and bounds the probe's whole process group from the parent; the
  parent never execs, so the deadline does not rely on alarm-across-exec
  behavior (verified absent on the qualification host). If perl is absent the
  probe fails closed (non-zero), never open.
- The bounded probe does not provide production code-signing attestation or
  external artifact custody guarantees; OPS-002 scope remains deterministic
  simulator qualification provenance.
- Synthetic process models model delivery failure and stalled behavior; no OS
  unkillability qualification is claimed.
- The unchanged simulator-approval packaging providers
  (`distributionCommit`/`distributionSourceClean`) already fail closed on
  non-zero git exits; they are outside this packet's exclusive scope.
