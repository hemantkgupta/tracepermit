# Source-free organization-profile synthetic operator qualification

Status: **implemented; validation pending**.

Requirements: FR-001/008/009/012/015; SEC-001/002/003/006/007/008/012/019;
REL-001/002/004/005/008; OPS-002/004/005. This is local synthetic evidence only.

## Candidate and ownership

The working branch is `codex/source-free-organization-operator`. It normally
merged published qualification commit
`8108598a658121735dbd59d5a44053b3ff22da7a` into accepted main
`dbb6e05930827633d7326240551ddeb73657c5a7`; the integration merge is
`5da2b6d48ba592537f1b9cf23dae753ddb604feb`, tree
`59f9a6b40bb29b1f0a28268098be0f5954d0be37`, with those ordered parents. Do not
rebase, cherry-pick, reset or force-update this history.

The source/documentation implementation checkpoint is
`a9270e4651d39d4825b2ef3efcaccce684760aa7`, tree
`f3ad45469efddf2f6edf6b4fdd65622db2219651`, with the integration merge as its
sole parent. This is an unvalidated local checkpoint, not a qualification receipt
or publication.

This lane owns the new executable module, its tests/docs and the additive durable
organization factory. It does not own EKS, installed COMMIT-loss injection,
artifact/identity verification or credential-custody corrections. MariaDB10.6
retirement is not present in the accepted base; retain its historical 9/9
library receipt and do not add an installed 10.6 launcher.

## Closed installed contract

The executable is `bin/tracepermit-synthetic-organization-operator`; the profile
is `tracepermit-installed-github-org-synthetic-operator-v1`. Input is big-endian
length-prefixed frames followed by EOF:

| Command | Frames in exact order |
| --- | --- |
| `check-config` | configuration |
| `register` | configuration, requester compact JWT, registration request |
| `inspect`, `evaluate`, `reconcile` | configuration, requester compact JWT, operation command |
| `review` | configuration, reviewer compact JWT, operation command |
| `deliver-synthetic` | configuration, requester compact JWT, Ed25519 PKCS8, operation command |

The root configuration has exactly `schemaVersion`, `profile`, `replay`,
`operations`, `replayCapacity`, `replayDmlMillis`, `scope`,
`organizationTarget`, `authority`, `requesterProvider`, `reviewerProvider`,
`requesterEnrollment` and `reviewerMemberships`. Both stores are distinct
numeric-loopback PostgreSQL16.4 stores; capacity is exactly 64 and the replay DML
budget exactly 2,000ms. Unknown fields, type coercion, duplicate keys, trailing
data, malformed UTF-8, unsupported engines and substituted enrollment/profile/
target/authority pins refuse.

Frame limits are configuration 65,536 bytes, JWT 16,384, registration 32,768,
private key 128, command 1,024 and receipt 2,048. A command gets 60 seconds total,
owned cleanup five seconds and receipt writing one second. Installed test cases
retain a 240-second ceiling; boundary tests retain 30 seconds, container startup
60 seconds, JDBC connect/query/lock five seconds, socket ten seconds and eight
contenders.

## Focused manual validation

Do not run these commands automatically. A human runs one command at a time,
keeps private stdout/stderr/exit files and stops at the first non-zero exit. Use
the existing JDK/cache/dependencies and cached PostgreSQL16.4 image only; do not
install, download, restart/prune Docker or clean another owner's processes.

```bash
set -euo pipefail
cd /Users/hemantkgupta/code-all-air/tracepermit
test "$(git branch --show-current)" = codex/source-free-organization-operator
test "$(git show -s --format='%P' 5da2b6d48ba592537f1b9cf23dae753ddb604feb)" = \
  "dbb6e05930827633d7326240551ddeb73657c5a7 8108598a658121735dbd59d5a44053b3ff22da7a"
test "$(git show -s --format='%T' 5da2b6d48ba592537f1b9cf23dae753ddb604feb)" = \
  59f9a6b40bb29b1f0a28268098be0f5954d0be37
test "$(git rev-parse a9270e4651d39d4825b2ef3efcaccce684760aa7^{tree})" = \
  f3ad45469efddf2f6edf6b4fdd65622db2219651
git merge-base --is-ancestor a9270e4651d39d4825b2ef3efcaccce684760aa7 HEAD

export JENV_VERSION=21
export TRACEPERMIT_GRADLE_USER_HOME="$PWD/.cache/gradle"
export GRADLE_USER_HOME="$TRACEPERMIT_GRADLE_USER_HOME"
export COREPACK_ENABLE_NETWORK=0
export PNPM_CONFIG_OFFLINE=true
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.offline=true"

TRACEPERMIT_PRIVATE_LOG_DIR="$(mktemp -d /private/tmp/tracepermit-org-operator.XXXXXX)"
chmod 700 "$TRACEPERMIT_PRIVATE_LOG_DIR"
git rev-parse HEAD >"$TRACEPERMIT_PRIVATE_LOG_DIR/head"
git rev-parse HEAD^{tree} >"$TRACEPERMIT_PRIVATE_LOG_DIR/tree"
git status --porcelain=v1 >"$TRACEPERMIT_PRIVATE_LOG_DIR/status"
git diff --binary >"$TRACEPERMIT_PRIVATE_LOG_DIR/uncommitted.patch"
{
  git diff --name-only
  git ls-files --others --exclude-standard
} | LC_ALL=C sort -u >"$TRACEPERMIT_PRIVATE_LOG_DIR/uncommitted.paths"
while IFS= read -r tracepermit_path; do
  test -n "$tracepermit_path" || continue
  shasum -a 256 -- "$tracepermit_path"
done <"$TRACEPERMIT_PRIVATE_LOG_DIR/uncommitted.paths" \
  >"$TRACEPERMIT_PRIVATE_LOG_DIR/uncommitted.sha256"

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

run_private organization-protocol \
  caffeinate -i /opt/homebrew/bin/jenv exec java/gradlew -p java \
  :agentcore-organization-lifecycle-cli:test \
  --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorProtocolTest \
  --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorReceiptTest \
  --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorIdentityTest \
  --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorArchiveTest \
  --offline --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-daemon --max-workers=1

run_private organization-postgresql-installed \
  caffeinate -i /opt/homebrew/bin/jenv exec java/gradlew -p java \
  :agentcore-organization-lifecycle-cli:test \
  --tests io.tracepermit.agentcore.organizationcli.PostgresInstalledOrganizationOperatorTest \
  --offline --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-daemon --max-workers=1

TRACEPERMIT_ORGANIZATION_ARCHIVE="$PWD/java/agentcore-organization-lifecycle-cli/build/distributions/tracepermit-synthetic-organization-operator-0.1.0-SNAPSHOT.tar"
test -f "$TRACEPERMIT_ORGANIZATION_ARCHIVE"
shasum -a 256 "$TRACEPERMIT_ORGANIZATION_ARCHIVE" \
  >"$TRACEPERMIT_PRIVATE_LOG_DIR/archive.sha256"
/usr/bin/tar -tf "$TRACEPERMIT_ORGANIZATION_ARCHIVE" \
  >"$TRACEPERMIT_PRIVATE_LOG_DIR/archive.inventory"
```

The installed contract uses a tar extracted outside all checkouts, a Java-only
`PATH`, production class origins, runtime-generated RSA/Ed25519 material,
restricted grants and separate processes for register → inspect → evaluate →
independent approve → deliver → reconcile. Denial, restart, recorded unknown,
no-resend and eight contenders remain active. Do not describe database events or
missing receipts as an observed target invocation count.

## Separate repository and coverage gates

Run only after both focused commands pass. `pnpm check` already includes the
Java aggregate; do not automatically follow it with a duplicate `checkAll`.
Run a separate aggregate only when the human explicitly wants a distinct fresh
coverage receipt.

```bash
run_private repository-check caffeinate -i corepack pnpm check
run_private format-check corepack pnpm format:check
run_private documentation-links corepack pnpm docs:links
run_private documentation-flows corepack pnpm docs:flows:check

# Human-directed separate coverage receipt only; not an automatic retry.
run_private aggregate-coverage \
  caffeinate -i /opt/homebrew/bin/jenv exec java/gradlew -p java checkAll \
  --offline --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-daemon --max-workers=1
```

Keep 94% line and 75% branch coverage floors, every assertion, timeout, grant and
workload active. Preserve and diagnose the first failure before any new command.

## Qualification boundary

A green run would qualify only the extracted local PostgreSQL synthetic operator
candidate and repository gates at its exact source/tree. It would not qualify a
live Okta/GitHub integration, credential custody, EKS containment, remote ingress,
deployment, external target-call count, target-side exactly-once behavior or
production readiness. Publication remains a later human instruction; keep any
future PR draft and unmerged.
