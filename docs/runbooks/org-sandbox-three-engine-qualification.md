# Organization-profile library three-engine qualification

Status: **test contract implemented; local human execution passed on
2026-10-28; candidate remains unmerged**.

Requirements: FR-001/008/009/012/015; SEC-001/002/003/006/012/019;
REL-002/005/008. This is scoped synthetic library evidence only and promotes no
whole requirement.

## Owned scope

`OrgSandboxLifecycleContract` composes the accepted organization-profile
libraries with real JDBC stores. The three wrappers pin PostgreSQL 16.4,
MariaDB 11.4.13 and MariaDB 10.6.25. The fixture creates a schema owner and a
separate minimum runtime principal, binds every published database port to
numeric loopback, and generates fresh RSA tokens and Ed25519 permit keys at
runtime.

The contract exercises:

- verified Okta requester and reviewer tokens, exact requester enrollment and
  the independent offline reviewer roster;
- organization registration, evaluation/review, permit issuance, admission,
  one fixed synthetic dispatch and read-only reconciliation;
- pending and denied approval with zero target calls, principal self-review and
  cross-client same-subject refusal;
- expected-profile, App, repository, ref and SHA substitutions, plus an
  adapter-crossed permit refusal;
- eight real JDBC review contenders, writer-time reviewer expiry after an
  actual head-row lock wait, and restricted runtime DDL/evidence mutation;
- durable `unknown`, repeated read-only reconciliation and refusal to resend;
- the original Track E public constructors, generalized/concrete binding hashes,
  canonical permit wire and successful legacy synthetic lifecycle.

This adds no production source or build dependency. It does not touch the
Mac-owned EKS lane, Cloud1 installed COMMIT-loss lane or Cloud2 artifact-verifier
lane.

## Fixed limits

Preserve the inherited 180-second JUnit deadline, eight review contenders,
five-second JDBC lock/query limits, ten-second socket limit, 60-second container
startup limit, replay capacity 64, two-second replay DML budget, exact database
images and existing 94% line/75% branch aggregate thresholds. Do not replace a
failed engine with a different tag, broaden grants, extend a timeout, reduce the
workload or use cached test results to obtain a pass.

The fixture contains no real GitHub target, credential source, ingress, Okta
login, token endpoint or deployment action. Tokens and keys are generated only
inside the test JVM and must not appear in logs or command arguments.

## Local qualification receipt (2026-10-28)

The prepared macOS runner used the pinned JDK, Gradle wrapper, offline cache and
already-cached database images described below. Private stdout, stderr and exit
receipts remained outside Git. No test token, key, permit, database credential
or JDBC URL is published here.

- PostgreSQL 16.4: all nine organization-sandbox lifecycle cases passed.
- MariaDB 11.4.13: all nine organization-sandbox lifecycle cases passed.
- MariaDB 10.6.25: all nine organization-sandbox lifecycle cases passed.
- Repository `pnpm check`, formatting, documentation links and documentation
  flow gates exited zero.
- The distinct `checkAll` receipt exited zero with the existing aggregate
  coverage verification enabled.

The first MariaDB 11.4 attempt isolated one failure to the new test fixture's
broad process-list observation of a lock waiter. No production source or
behavior failed. The fixture now captures the exact owned numeric backend ID
before the lifecycle-head lock attempt and observes only that backend's lock
wait. The two-second observation bound, authority-expiry assertion, head-row
contention, workload and all other deadlines remain unchanged. The corrected
case passed alone before the full three-engine sequence was repeated.

## Human-run focused sequence

Use the candidate commit and tree from the implementation handoff. The candidate
must have the accepted main commit and tree as its sole parent/base. Reuse the
prepared Homebrew aarch64 JDK `21.0.13.0.2`, Gradle 8.14.3 wrapper checksum
`bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`,
the existing repository cache, Docker runtime and already-cached images. Do not
install or download during qualification.

Create a private log directory, record stdout/stderr separately, record the exit
status even on failure, and stop after the first non-zero result:

```bash
set -euo pipefail
test "$(git rev-parse HEAD^)" = a7539012ab367c94f2c9718371b1cd9bf17c2632
test "$(git show -s --format=%T HEAD^)" = 4347833d86112e4112a558accd5a1c36ddc47432
test -z "$(git status --porcelain=v1)"

export TRACEPERMIT_JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export TRACEPERMIT_CACHE_DIR="$PWD/.cache"
export TRACEPERMIT_GRADLE_USER_HOME="$TRACEPERMIT_CACHE_DIR/gradle"
export COREPACK_ENABLE_NETWORK=0
export PNPM_CONFIG_OFFLINE=true
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.offline=true"
test "$(sed -n 's/^JAVA_VERSION="\(.*\)"/\1/p' "$TRACEPERMIT_JAVA_HOME/release")" = 21.0.13.0.2
test "$(sed -n 's/^distributionSha256Sum=//p' java/gradle/wrapper/gradle-wrapper.properties)" = \
  bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531
source scripts/configure-java-environment.sh

TRACEPERMIT_PRIVATE_LOG_DIR="$(mktemp -d /private/tmp/tracepermit-org-sandbox.XXXXXX)"
chmod 700 "$TRACEPERMIT_PRIVATE_LOG_DIR"

run_private() {
  tracepermit_label="$1"
  shift
  set +e
  "$@" >"$TRACEPERMIT_PRIVATE_LOG_DIR/$tracepermit_label.stdout" \
    2>"$TRACEPERMIT_PRIVATE_LOG_DIR/$tracepermit_label.stderr"
  tracepermit_status=$?
  set -e
  printf '%s\n' "$tracepermit_status" \
    >"$TRACEPERMIT_PRIVATE_LOG_DIR/$tracepermit_label.exit"
  test "$tracepermit_status" -eq 0
}

run_private postgres-org-sandbox \
  caffeinate -i java/gradlew -p java :agentcore-durable-registration:test \
  --tests io.tracepermit.agentcore.registration.PostgresOrgSandboxLifecycleTest \
  --offline --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-daemon --max-workers=1

run_private mariadb-114-org-sandbox \
  caffeinate -i java/gradlew -p java :agentcore-durable-registration:test \
  --tests io.tracepermit.agentcore.registration.MariaDb114OrgSandboxLifecycleTest \
  --offline --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-daemon --max-workers=1

run_private mariadb-106-org-sandbox \
  caffeinate -i java/gradlew -p java :agentcore-durable-registration:test \
  --tests io.tracepermit.agentcore.registration.MariaDb106OrgSandboxLifecycleTest \
  --offline --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-daemon --max-workers=1
```

Keep the private directory outside Git. Report only fixed test names, counts,
timings, image/source fingerprints and exit status; never publish raw logs,
tokens, keys, permits, database passwords or JDBC URLs.

## Separate repository and coverage gates

Do not start these after a focused failure, and do not blindly repeat an earlier
aggregate hang. Diagnose and preserve the first failure, then obtain human
direction for a fresh command. On a focused-green candidate, run each gate once
with the same source/runtime/cache pins and the same private capture helper:

```bash
run_private repository-check caffeinate -i corepack pnpm check
run_private format-check corepack pnpm format:check
run_private documentation-links corepack pnpm docs:links
run_private documentation-flows corepack pnpm docs:flows:check

run_private aggregate-coverage \
  caffeinate -i java/gradlew -p java checkAll \
  --offline --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-daemon --max-workers=1
```

`pnpm check` already invokes the Java aggregate gate. The separately captured
`checkAll` command is therefore a new explicit coverage attempt, not an automatic
retry: run it only if the human qualification plan calls for a distinct fresh
coverage receipt after the repository gate. Its `jacocoAggregateCoverageVerification`
dependency retains the 94% line and 75% branch floors.

## Qualification boundary

A green contract would qualify the accepted library composition only under the
three local synthetic database fixtures. It would not approve EKS, provider or
signing-key custody, ingress, a real organization manifest, live Okta/GitHub
calls, external exactly-once delivery, or production readiness. Unknown remains
unknown and never authorizes a new permit, claim, attempt or resend.
