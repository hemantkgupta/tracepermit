#!/usr/bin/env bash
set -euo pipefail

umask 077

readonly TRACEPERMIT_CHECKOUT="/Users/hemantkgupta/code-all-air/tracepermit"
readonly TRACEPERMIT_BRANCH="codex/source-free-organization-operator"
readonly TRACEPERMIT_CANDIDATE="878013d68e90815fde57d792cf239f86338ae505"
readonly TRACEPERMIT_CANDIDATE_TREE="b228fd7a12e9a63f9c1b912751e8d7e79c098e4d"
readonly TRACEPERMIT_CANDIDATE_PARENTS="317e333d17b79ae5c64a11efcea366ab9ade9b0e dfb7b3b11ed7af3b4d0230fae9adf6e514289ba5"
readonly TRACEPERMIT_ACCEPTED_MAIN="dfb7b3b11ed7af3b4d0230fae9adf6e514289ba5"
readonly TRACEPERMIT_ACCEPTED_MAIN_TREE="0a9012fdedb7a2db72aba37de93e2adc891df48b"
readonly TRACEPERMIT_IMPLEMENTATION_CHECKPOINT="a9270e4651d39d4825b2ef3efcaccce684760aa7"
readonly TRACEPERMIT_IMPLEMENTATION_TREE="f3ad45469efddf2f6edf6b4fdd65622db2219651"
readonly TRACEPERMIT_QUALIFICATION_HISTORY="8108598a658121735dbd59d5a44053b3ff22da7a"
readonly TRACEPERMIT_QUALIFICATION_TREE="8597529c2854945be6eede63dc06e6a2931cf6f0"
readonly TRACEPERMIT_SOURCE_MANIFEST="7c69e49ffa6819568ab1bca55fc863809bb0041b3a52d9aa5906fa7db30839fd"
readonly TRACEPERMIT_JENV="/opt/homebrew/bin/jenv"
readonly TRACEPERMIT_DOCKER="/Applications/Docker.app/Contents/Resources/bin/docker"
readonly TRACEPERMIT_POSTGRES_IMAGE_ID="sha256:5660c2cbfea50c7a9127d17dc4e48543eedd3d7a41a595a2dfa572471e37e64c"
readonly TRACEPERMIT_MARIADB_IMAGE_ID="sha256:250e1609e93c0e6aecf64bfbab2d84d5e89d2481540fe77447ff6c855c3b1873"
readonly TRACEPERMIT_JAVA_HOME="/Users/hemantkgupta/.jenv/versions/21"
readonly TRACEPERMIT_JAVA_REAL_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.13.0.2/libexec/openjdk.jdk/Contents/Home"
readonly TRACEPERMIT_GRADLE_HOME="$TRACEPERMIT_CHECKOUT/.cache/gradle"
readonly TRACEPERMIT_GRADLE_DIST="$TRACEPERMIT_GRADLE_HOME/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3"
readonly TRACEPERMIT_GRADLE_WRAPPER_SHA="7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172"
readonly TRACEPERMIT_GRADLE_DISTRIBUTION_SHA="bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
readonly TRACEPERMIT_ARCHIVE="$TRACEPERMIT_CHECKOUT/java/agentcore-organization-lifecycle-cli/build/distributions/tracepermit-synthetic-organization-operator-0.1.0-SNAPSHOT.tar"
readonly TRACEPERMIT_RESULTS="$TRACEPERMIT_CHECKOUT/java/agentcore-organization-lifecycle-cli/build/test-results/test"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 focused|repo" >&2
  exit 64
fi
if [[ "$1" != focused && "$1" != repo ]]; then
  echo "Usage: $0 focused|repo" >&2
  exit 64
fi
readonly TRACEPERMIT_MODE="$1"

if [[ ! "${TRACEPERMIT_EXPECTED_HEAD:-}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "TRACEPERMIT_EXPECTED_HEAD must be the exact 40-character validation-preparation commit." >&2
  exit 64
fi
readonly TRACEPERMIT_VALIDATION_HEAD="$TRACEPERMIT_EXPECTED_HEAD"
readonly TRACEPERMIT_LOG_ROOT="${TRACEPERMIT_PRIVATE_LOG_DIR:-/private/tmp/tracepermit-org-operator-$TRACEPERMIT_VALIDATION_HEAD}"

case "$TRACEPERMIT_LOG_ROOT" in
  /private/tmp/*) ;;
  *) echo "Private logs must be beneath /private/tmp." >&2; exit 64 ;;
esac

if [[ "$TRACEPERMIT_MODE" == focused ]]; then
  if [[ -e "$TRACEPERMIT_LOG_ROOT" ]]; then
    echo "Refusing to overwrite existing private logs: $TRACEPERMIT_LOG_ROOT" >&2
    exit 73
  fi
  mkdir -m 700 "$TRACEPERMIT_LOG_ROOT"
else
  if [[ ! -d "$TRACEPERMIT_LOG_ROOT/focused" ]]; then
    echo "The focused mode must pass first using the same private log root." >&2
    exit 66
  fi
fi

readonly TRACEPERMIT_RUN_DIR="$TRACEPERMIT_LOG_ROOT/$TRACEPERMIT_MODE"
if [[ -e "$TRACEPERMIT_RUN_DIR" ]]; then
  echo "Refusing to overwrite an existing $TRACEPERMIT_MODE receipt." >&2
  exit 73
fi
mkdir -m 700 "$TRACEPERMIT_RUN_DIR"

export JENV_VERSION=21
export TRACEPERMIT_JAVA_HOME
export JAVA_HOME="$TRACEPERMIT_JAVA_HOME"
export PATH="$JAVA_HOME/bin:${TRACEPERMIT_DOCKER%/docker}:$PATH"
export TRACEPERMIT_CACHE_DIR="$TRACEPERMIT_CHECKOUT/.cache"
export TRACEPERMIT_GRADLE_USER_HOME="$TRACEPERMIT_GRADLE_HOME"
export GRADLE_USER_HOME="$TRACEPERMIT_GRADLE_HOME"
export COREPACK_ENABLE_NETWORK=0
export PNPM_CONFIG_OFFLINE=true
export npm_config_offline=true
export GRADLE_OPTS="-Djava.util.prefs.userRoot=$TRACEPERMIT_CHECKOUT/.cache/java-preferences -Dorg.gradle.offline=true -Dorg.gradle.daemon=false -Dorg.gradle.caching=false -Dorg.gradle.configuration-cache=false -Dorg.gradle.workers.max=1"

tracepermit_run_stage() {
  local tracepermit_label="$1"
  shift
  local tracepermit_status=0
  set +e
  (
    set -euo pipefail
    "$@"
  ) >"$TRACEPERMIT_RUN_DIR/$tracepermit_label.stdout" \
    2>"$TRACEPERMIT_RUN_DIR/$tracepermit_label.stderr"
  tracepermit_status=$?
  set -e
  printf '%s\n' "$tracepermit_status" >"$TRACEPERMIT_RUN_DIR/$tracepermit_label.exit"
  if [[ "$tracepermit_status" -ne 0 ]]; then
    echo "Stopped at $tracepermit_label (exit $tracepermit_status); inspect $TRACEPERMIT_RUN_DIR privately." >&2
    exit "$tracepermit_status"
  fi
}

tracepermit_source_manifest_inventory() {
  git ls-tree -r --full-tree "$TRACEPERMIT_CANDIDATE" -- \
    .github/workflows/ci.yml java package.json pnpm-lock.yaml pnpm-workspace.yaml scripts \
    | LC_ALL=C sort
}

tracepermit_source_guards() {
  cd "$TRACEPERMIT_CHECKOUT"
  [[ "$(pwd -P)" == "$TRACEPERMIT_CHECKOUT" ]]
  [[ "$(git branch --show-current)" == "$TRACEPERMIT_BRANCH" ]]
  [[ "$(git rev-parse HEAD)" == "$TRACEPERMIT_VALIDATION_HEAD" ]]
  [[ -z "$(git status --porcelain=v1 --untracked-files=all)" ]]
  [[ "$(git rev-parse refs/remotes/origin/main)" == "$TRACEPERMIT_ACCEPTED_MAIN" ]]
  [[ "$(git rev-parse "$TRACEPERMIT_ACCEPTED_MAIN^{tree}")" == "$TRACEPERMIT_ACCEPTED_MAIN_TREE" ]]
  [[ "$(git rev-parse "$TRACEPERMIT_CANDIDATE^{tree}")" == "$TRACEPERMIT_CANDIDATE_TREE" ]]
  [[ "$(git show -s --format=%P "$TRACEPERMIT_CANDIDATE")" == "$TRACEPERMIT_CANDIDATE_PARENTS" ]]
  [[ "$(git rev-parse "$TRACEPERMIT_IMPLEMENTATION_CHECKPOINT^{tree}")" == "$TRACEPERMIT_IMPLEMENTATION_TREE" ]]
  [[ "$(git rev-parse "$TRACEPERMIT_QUALIFICATION_HISTORY^{tree}")" == "$TRACEPERMIT_QUALIFICATION_TREE" ]]
  git merge-base --is-ancestor "$TRACEPERMIT_ACCEPTED_MAIN" "$TRACEPERMIT_CANDIDATE"
  git merge-base --is-ancestor "$TRACEPERMIT_IMPLEMENTATION_CHECKPOINT" "$TRACEPERMIT_CANDIDATE"
  git merge-base --is-ancestor "$TRACEPERMIT_QUALIFICATION_HISTORY" "$TRACEPERMIT_CANDIDATE"
  git merge-base --is-ancestor "$TRACEPERMIT_CANDIDATE" "$TRACEPERMIT_VALIDATION_HEAD"

  local tracepermit_path
  while IFS= read -r tracepermit_path; do
    case "$tracepermit_path" in
      docs/runbooks/source-free-organization-profile-synthetic-operator.md) ;;
      scripts/validate-source-free-organization-operator.sh) ;;
      *) echo "Unexpected post-candidate path: $tracepermit_path" >&2; return 1 ;;
    esac
  done < <(git diff --name-only "$TRACEPERMIT_CANDIDATE" "$TRACEPERMIT_VALIDATION_HEAD")

  tracepermit_source_manifest_inventory >"$TRACEPERMIT_RUN_DIR/source-manifest.inventory"
  [[ "$(shasum -a 256 "$TRACEPERMIT_RUN_DIR/source-manifest.inventory" | awk '{print $1}')" == "$TRACEPERMIT_SOURCE_MANIFEST" ]]
  printf '%s\n' "$TRACEPERMIT_SOURCE_MANIFEST" >"$TRACEPERMIT_RUN_DIR/source-manifest.sha256"
  git show -s --format='commit=%H%ntree=%T%nparents=%P%nsubject=%s' "$TRACEPERMIT_CANDIDATE" \
    >"$TRACEPERMIT_RUN_DIR/candidate.provenance"
  printf '%s\n' "$TRACEPERMIT_VALIDATION_HEAD" >"$TRACEPERMIT_RUN_DIR/validation-head"
  printf '%s\n' "$TRACEPERMIT_BRANCH" >"$TRACEPERMIT_RUN_DIR/branch"
  git diff --name-status "$TRACEPERMIT_ACCEPTED_MAIN" "$TRACEPERMIT_CANDIDATE" \
    >"$TRACEPERMIT_RUN_DIR/candidate-scope"

  [[ "$(grep -Ec '^[[:space:]]*@Test[[:space:]]*$' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/OrganizationOperatorProtocolTest.java)" == 4 ]]
  [[ "$(grep -Ec '^[[:space:]]*@Test[[:space:]]*$' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/OrganizationOperatorReceiptTest.java)" == 2 ]]
  [[ "$(grep -Ec '^[[:space:]]*@Test[[:space:]]*$' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/OrganizationOperatorIdentityTest.java)" == 4 ]]
  [[ "$(grep -Ec '^[[:space:]]*@Test[[:space:]]*$' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/OrganizationOperatorArchiveTest.java)" == 2 ]]
  [[ "$(grep -Ec '^[[:space:]]*@Test[[:space:]]*$' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/PostgresInstalledOrganizationOperatorTest.java)" == 4 ]]
}

tracepermit_runtime_guards() {
  local tracepermit_java_version
  [[ -x /usr/bin/caffeinate ]]
  [[ -x "$TRACEPERMIT_JENV" ]]
  [[ -x "$TRACEPERMIT_DOCKER" ]]
  [[ "$($TRACEPERMIT_JENV --version)" == "jenv 0.6.0" ]]
  [[ "$(cd -P "$TRACEPERMIT_JAVA_HOME" && pwd)" == "$TRACEPERMIT_JAVA_REAL_HOME" ]]
  tracepermit_java_version="$("$TRACEPERMIT_JAVA_HOME/bin/java" -version 2>&1)"
  printf '%s\n' "$tracepermit_java_version"
  [[ "$($TRACEPERMIT_JAVA_HOME/bin/javac -version 2>&1)" == "javac 21.0.13.0.2" ]]
  printf '%s\n' "$tracepermit_java_version" | grep -F '21.0.13.0.2'

  [[ "$(node -p 'Number(process.versions.node.split(".")[0]) >= 22 ? "yes" : "no"')" == yes ]]
  node --version
  [[ "$(corepack pnpm --version)" == 11.25.0 ]]
  [[ -d "$TRACEPERMIT_CHECKOUT/node_modules/.pnpm" ]]
  grep -F '"packageManager": "pnpm@11.25.0"' "$TRACEPERMIT_CHECKOUT/package.json"

  [[ "$(shasum -a 256 "$TRACEPERMIT_CHECKOUT/java/gradle/wrapper/gradle-wrapper.jar" | awk '{print $1}')" == "$TRACEPERMIT_GRADLE_WRAPPER_SHA" ]]
  grep -F "distributionSha256Sum=$TRACEPERMIT_GRADLE_DISTRIBUTION_SHA" \
    "$TRACEPERMIT_CHECKOUT/java/gradle/wrapper/gradle-wrapper.properties"
  grep -F 'distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip' \
    "$TRACEPERMIT_CHECKOUT/java/gradle/wrapper/gradle-wrapper.properties"
  [[ -x "$TRACEPERMIT_GRADLE_DIST/bin/gradle" ]]
  [[ -f "${TRACEPERMIT_GRADLE_DIST%/gradle-8.14.3}/gradle-8.14.3-bin.zip.ok" ]]
  "$TRACEPERMIT_GRADLE_DIST/bin/gradle" --version | grep -F 'Gradle 8.14.3'
  [[ -d "$TRACEPERMIT_GRADLE_HOME/caches/modules-2/files-2.1" ]]

  "$TRACEPERMIT_DOCKER" version --format 'client={{.Client.Version}} server={{.Server.Version}}'
  [[ "$("$TRACEPERMIT_DOCKER" image inspect postgres:16.4-alpine --format '{{.Id}}')" == \
    "$TRACEPERMIT_POSTGRES_IMAGE_ID" ]]
  "$TRACEPERMIT_DOCKER" image inspect postgres:16.4-alpine \
    --format 'postgres16.4={{.Id}} digests={{join .RepoDigests ","}}'
  if [[ "$TRACEPERMIT_MODE" == repo ]]; then
    [[ "$("$TRACEPERMIT_DOCKER" image inspect mariadb:11.4.13 --format '{{.Id}}')" == \
      "$TRACEPERMIT_MARIADB_IMAGE_ID" ]]
    "$TRACEPERMIT_DOCKER" image inspect mariadb:11.4.13 \
      --format 'mariadb11.4.13={{.Id}} digests={{join .RepoDigests ","}}'
  fi
}

tracepermit_focused_validation() {
  cd "$TRACEPERMIT_CHECKOUT"
  /usr/bin/caffeinate -i "$TRACEPERMIT_JENV" exec java/gradlew -p java \
    :agentcore-organization-lifecycle-cli:test \
    --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorProtocolTest \
    --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorReceiptTest \
    --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorIdentityTest \
    --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorArchiveTest \
    --tests io.tracepermit.agentcore.organizationcli.PostgresInstalledOrganizationOperatorTest \
    --offline --rerun-tasks --no-build-cache --no-configuration-cache \
    --no-daemon --max-workers=1
}

tracepermit_record_class_origin() {
  local tracepermit_class="$1"
  local tracepermit_matches=0
  local tracepermit_jar
  while IFS= read -r tracepermit_jar; do
    if /usr/bin/unzip -Z1 "$tracepermit_jar" | grep -Fx "$tracepermit_class" >/dev/null; then
      tracepermit_matches=$((tracepermit_matches + 1))
      printf '%s  %s  %s\n' \
        "$tracepermit_class" \
        "${tracepermit_jar#"$TRACEPERMIT_RUN_DIR/extracted/"}" \
        "$(shasum -a 256 "$tracepermit_jar" | awk '{print $1}')"
    fi
  done < <(find "$TRACEPERMIT_RUN_DIR/extracted" -type f -name '*.jar' | LC_ALL=C sort)
  [[ "$tracepermit_matches" -eq 1 ]]
}

tracepermit_capture_archive() {
  [[ -f "$TRACEPERMIT_ARCHIVE" ]]
  printf '%s\n' "$TRACEPERMIT_ARCHIVE" >"$TRACEPERMIT_RUN_DIR/executed-archive.path"
  shasum -a 256 "$TRACEPERMIT_ARCHIVE" >"$TRACEPERMIT_RUN_DIR/executed-archive.sha256"
  /usr/bin/tar -tf "$TRACEPERMIT_ARCHIVE" >"$TRACEPERMIT_RUN_DIR/executed-archive.inventory"
  if grep -E '(^|/)(\.git|AGENTS\.md|build\.gradle\.kts|settings\.gradle\.kts)$|\.(java|kt)$' \
    "$TRACEPERMIT_RUN_DIR/executed-archive.inventory" >/dev/null; then
    echo "The executed archive contains source or repository metadata." >&2
    return 1
  fi

  cp "$TRACEPERMIT_ARCHIVE" "$TRACEPERMIT_RUN_DIR/executed-archive.tar"
  [[ "$(shasum -a 256 "$TRACEPERMIT_RUN_DIR/executed-archive.tar" | awk '{print $1}')" == \
    "$(awk '{print $1}' "$TRACEPERMIT_RUN_DIR/executed-archive.sha256")" ]]
  mkdir -m 700 "$TRACEPERMIT_RUN_DIR/extracted"
  /usr/bin/tar -xf "$TRACEPERMIT_RUN_DIR/executed-archive.tar" -C "$TRACEPERMIT_RUN_DIR/extracted"
  printf '%s\n' "$TRACEPERMIT_RUN_DIR/extracted" >"$TRACEPERMIT_RUN_DIR/extraction.path"
  find "$TRACEPERMIT_RUN_DIR/extracted" -print | LC_ALL=C sort \
    >"$TRACEPERMIT_RUN_DIR/extraction.inventory"

  {
    tracepermit_record_class_origin \
      io/tracepermit/agentcore/organizationcli/OrganizationOperatorWorker.class
    tracepermit_record_class_origin \
      io/tracepermit/adapters/remote/agentcore/OktaOperationAuthority.class
    tracepermit_record_class_origin \
      io/tracepermit/adapters/remote/github/GitHubOrganizationOperationBinding.class
    tracepermit_record_class_origin \
      io/tracepermit/agentcore/registration/DurableAgentCoreRegistration.class
    tracepermit_record_class_origin \
      io/tracepermit/persistence/operations/SyntheticGitHubDeliveryTarget.class
  } >"$TRACEPERMIT_RUN_DIR/class-origins.sha256"
}

tracepermit_capture_focused_results() {
  [[ -d "$TRACEPERMIT_RESULTS" ]]
  cp -R "$TRACEPERMIT_RESULTS" "$TRACEPERMIT_RUN_DIR/test-results"
  find "$TRACEPERMIT_RUN_DIR/test-results" -type f -name 'TEST-*.xml' -print \
    | LC_ALL=C sort >"$TRACEPERMIT_RUN_DIR/test-result-files"
  [[ "$(wc -l <"$TRACEPERMIT_RUN_DIR/test-result-files" | tr -d ' ')" == 5 ]]
  node --input-type=commonjs - "$TRACEPERMIT_RUN_DIR/test-results" <<'NODE'
const fs = require("node:fs");
const path = require("node:path");
const root = process.argv[2];
const files = fs.readdirSync(root).filter((name) => /^TEST-.*\.xml$/.test(name)).sort();
let tests = 0;
let failures = 0;
let errors = 0;
let skipped = 0;
for (const file of files) {
  const xml = fs.readFileSync(path.join(root, file), "utf8");
  const suite = xml.match(/<testsuite\b[^>]*>/)?.[0];
  if (!suite) throw new Error(`Missing testsuite in ${file}`);
  const value = (name) => Number(suite.match(new RegExp(`${name}="(\\d+)"`))?.[1]);
  tests += value("tests");
  failures += value("failures");
  errors += value("errors");
  skipped += value("skipped");
}
console.log(JSON.stringify({ selectors: files.length, tests, failures, errors, skipped }));
if (files.length !== 5 || tests !== 16 || failures !== 0 || errors !== 0 || skipped !== 0) process.exit(1);
NODE
}

tracepermit_require_focused_receipt() {
  [[ "$(cat "$TRACEPERMIT_LOG_ROOT/focused/00-source-guards.exit")" == 0 ]]
  [[ "$(cat "$TRACEPERMIT_LOG_ROOT/focused/01-runtime-guards.exit")" == 0 ]]
  [[ "$(cat "$TRACEPERMIT_LOG_ROOT/focused/10-focused-validation.exit")" == 0 ]]
  [[ "$(cat "$TRACEPERMIT_LOG_ROOT/focused/11-executed-archive.exit")" == 0 ]]
  [[ "$(cat "$TRACEPERMIT_LOG_ROOT/focused/12-focused-results.exit")" == 0 ]]
  [[ "$(cat "$TRACEPERMIT_LOG_ROOT/focused/validation-head")" == "$TRACEPERMIT_VALIDATION_HEAD" ]]
  [[ "$(cat "$TRACEPERMIT_LOG_ROOT/focused/source-manifest.sha256")" == "$TRACEPERMIT_SOURCE_MANIFEST" ]]
}

tracepermit_preserve_build_evidence() {
  local tracepermit_build
  local tracepermit_relative
  local tracepermit_destination
  cd "$TRACEPERMIT_CHECKOUT"
  find "$TRACEPERMIT_CHECKOUT/java" -type d -name build -prune -print \
    | LC_ALL=C sort >"$TRACEPERMIT_RUN_DIR/pre-repository-build-paths"
  [[ -s "$TRACEPERMIT_RUN_DIR/pre-repository-build-paths" ]]
  while IFS= read -r tracepermit_build; do
    tracepermit_relative="${tracepermit_build#"$TRACEPERMIT_CHECKOUT/"}"
    tracepermit_destination="$TRACEPERMIT_RUN_DIR/pre-repository-builds/$tracepermit_relative"
    mkdir -p "$(dirname "$tracepermit_destination")"
    mv "$tracepermit_build" "$tracepermit_destination"
  done <"$TRACEPERMIT_RUN_DIR/pre-repository-build-paths"
  if find "$TRACEPERMIT_CHECKOUT/java" -type f -path '*/build/*' -print -quit | grep -q .; then
    echo "Java build outputs remain after evidence preservation." >&2
    return 1
  fi
  find "$TRACEPERMIT_RUN_DIR/pre-repository-builds" -type f -print \
    | LC_ALL=C sort \
    | while IFS= read -r tracepermit_build; do
        shasum -a 256 "$tracepermit_build"
      done >"$TRACEPERMIT_RUN_DIR/pre-repository-builds.sha256"
}

tracepermit_repository_check() {
  cd "$TRACEPERMIT_CHECKOUT"
  date +%s >"$TRACEPERMIT_RUN_DIR/repository-check.started-epoch"
  /usr/bin/caffeinate -i corepack pnpm check
}

tracepermit_capture_coverage() {
  local tracepermit_report="$TRACEPERMIT_CHECKOUT/java/build/reports/jacoco/jacocoAggregateReport/jacocoAggregateReport.xml"
  [[ -f "$tracepermit_report" ]]
  [[ "$(/usr/bin/stat -f '%m' "$tracepermit_report")" -ge "$(cat "$TRACEPERMIT_RUN_DIR/repository-check.started-epoch")" ]]
  cp "$tracepermit_report" "$TRACEPERMIT_RUN_DIR/jacocoAggregateReport.xml"
  shasum -a 256 "$TRACEPERMIT_RUN_DIR/jacocoAggregateReport.xml" \
    >"$TRACEPERMIT_RUN_DIR/jacocoAggregateReport.sha256"
  node --input-type=commonjs - "$TRACEPERMIT_RUN_DIR/jacocoAggregateReport.xml" <<'NODE'
const fs = require("node:fs");
const xml = fs.readFileSync(process.argv[2], "utf8");
function ratio(type) {
  const matches = [...xml.matchAll(new RegExp(`<counter type="${type}" missed="(\\d+)" covered="(\\d+)"/>`, "g"))];
  if (matches.length === 0) throw new Error(`Missing ${type} counter`);
  const [, missed, covered] = matches.at(-1).map(Number);
  return covered / (covered + missed);
}
const line = ratio("LINE");
const branch = ratio("BRANCH");
console.log(JSON.stringify({ line, branch }));
if (line < 0.94 || branch < 0.75) process.exit(1);
NODE
}

tracepermit_ci_policy_helpers() {
  cd "$TRACEPERMIT_CHECKOUT"
  node --test \
    scripts/ci-workflow.test.mjs \
    scripts/ci-policy.test.mjs \
    scripts/ci-coverage.test.mjs \
    scripts/ci-mariadb-scope.test.mjs
}

tracepermit_diff_checks() {
  cd "$TRACEPERMIT_CHECKOUT"
  git diff --check "$TRACEPERMIT_CANDIDATE..$TRACEPERMIT_VALIDATION_HEAD"
  git diff --check
  git diff --cached --check
}

tracepermit_run_stage 00-source-guards tracepermit_source_guards
tracepermit_run_stage 01-runtime-guards tracepermit_runtime_guards

if [[ "$TRACEPERMIT_MODE" == focused ]]; then
  tracepermit_run_stage 10-focused-validation tracepermit_focused_validation
  tracepermit_run_stage 11-executed-archive tracepermit_capture_archive
  tracepermit_run_stage 12-focused-results tracepermit_capture_focused_results
else
  tracepermit_run_stage 02-focused-receipt tracepermit_require_focused_receipt
  tracepermit_run_stage 10-preserve-build-evidence tracepermit_preserve_build_evidence
  tracepermit_run_stage 11-repository-check tracepermit_repository_check
  tracepermit_run_stage 12-aggregate-coverage-receipt tracepermit_capture_coverage
  tracepermit_run_stage 13-format-check bash -c \
    'cd "$1" && corepack pnpm format:check' _ "$TRACEPERMIT_CHECKOUT"
  tracepermit_run_stage 14-documentation-links bash -c \
    'cd "$1" && corepack pnpm docs:links' _ "$TRACEPERMIT_CHECKOUT"
  tracepermit_run_stage 15-documentation-flows bash -c \
    'cd "$1" && corepack pnpm docs:flows:check' _ "$TRACEPERMIT_CHECKOUT"
  tracepermit_run_stage 16-ci-policy-helpers tracepermit_ci_policy_helpers
  tracepermit_run_stage 17-diff-checks tracepermit_diff_checks
fi

printf '%s validation stages passed; private receipts: %s\n' \
  "$TRACEPERMIT_MODE" "$TRACEPERMIT_RUN_DIR"
