#!/usr/bin/env bash
set -euo pipefail

umask 077

readonly TP_CHECKOUT="/Users/hemantkgupta/code-all-air/tracepermit"
readonly TP_BRANCH="codex/organization-container-qualification"
readonly TP_ACCEPTED_MAIN="7c33ea1ad1c5ef9dae07df235f28db14a1ea2c14"
readonly TP_ACCEPTED_TREE="236c8805c91c3ba686f98a83443da06cd54c6a0e"
readonly TP_ACCEPTED_PARENTS="99e0d8e19fc1754e8ce5ee699500be4dc62a7581 793ee5cd5458b2e202662fff5d78708360c09749"
readonly TP_IMPLEMENTATION="3b1d8134cd9a74fe34faba90171aede8228f81cc"
readonly TP_IMPLEMENTATION_TREE="d050e6eceebe3e5b90e29685dbcbda168adc1af6"
readonly TP_IMPLEMENTATION_PARENT="95dea29d5fc28ccbf7d9e906f4cf2c71988030d4"
readonly TP_SOURCE_MANIFEST="4635aa4c1dc0f862a433a904c4aa1294fb08fd9c013fdf58cdec30b595e8b68d"
readonly TP_JAVA_HOME="/Users/hemantkgupta/.jenv/versions/21"
readonly TP_JAVA_REAL_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.13.0.2/libexec/openjdk.jdk/Contents/Home"
readonly TP_GRADLE_USER_HOME="$TP_CHECKOUT/.cache/gradle"
readonly TP_GRADLE="$TP_GRADLE_USER_HOME/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle"
readonly TP_GRADLE_WRAPPER_SHA="7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172"
readonly TP_GRADLE_DISTRIBUTION_SHA="bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
readonly TP_NODE="/opt/homebrew/bin/node"
readonly TP_PNPM="/opt/homebrew/bin/pnpm"
readonly TP_DOCKER="/Applications/Docker.app/Contents/Resources/bin/docker"
readonly TP_DOCKER_ENDPOINT="unix:///Users/hemantkgupta/.docker/run/docker.sock"
readonly TP_BASE_IMAGE="eclipse-temurin@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d"
readonly TP_POSTGRES_IMAGE_ID="sha256:5660c2cbfea50c7a9127d17dc4e48543eedd3d7a41a595a2dfa572471e37e64c"
readonly TP_MARIADB_IMAGE_ID="sha256:250e1609e93c0e6aecf64bfbab2d84d5e89d2481540fe77447ff6c855c3b1873"
readonly TP_ARCHIVE="$TP_CHECKOUT/java/agentcore-organization-lifecycle-cli/build/distributions/tracepermit-synthetic-organization-operator-0.1.0-SNAPSHOT.tar"
readonly TP_TEST_RESULTS="$TP_CHECKOUT/java/agentcore-organization-lifecycle-cli/build/test-results"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 focused|inventory|image|transport|postgresql|repository" >&2
  exit 64
fi
case "$1" in
  focused|inventory|image|transport|postgresql|repository) ;;
  *)
    echo "Usage: $0 focused|inventory|image|transport|postgresql|repository" >&2
    exit 64
    ;;
esac
readonly TP_MODE="$1"

if [[ ! "${TRACEPERMIT_EXPECTED_HEAD:-}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "TRACEPERMIT_EXPECTED_HEAD must be the exact validation-preparation commit." >&2
  exit 64
fi
if [[ ! "${TRACEPERMIT_EXPECTED_TREE:-}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "TRACEPERMIT_EXPECTED_TREE must be that commit's exact tree." >&2
  exit 64
fi
readonly TP_EXPECTED_HEAD="$TRACEPERMIT_EXPECTED_HEAD"
readonly TP_EXPECTED_TREE="$TRACEPERMIT_EXPECTED_TREE"
readonly TP_LOG_ROOT="${TRACEPERMIT_PRIVATE_LOG_DIR:-/private/tmp/tracepermit-organization-container-$TP_EXPECTED_HEAD}"
case "$TP_LOG_ROOT" in
  /private/tmp/*) ;;
  *)
    echo "Private logs must be beneath /private/tmp." >&2
    exit 64
    ;;
esac

if [[ "$TP_MODE" == focused ]]; then
  if [[ -e "$TP_LOG_ROOT" ]]; then
    echo "Refusing to overwrite existing private logs: $TP_LOG_ROOT" >&2
    exit 73
  fi
  mkdir -m 700 "$TP_LOG_ROOT"
elif [[ ! -d "$TP_LOG_ROOT/focused" ]]; then
  echo "Focused validation must pass first under the same private log root." >&2
  exit 66
fi
readonly TP_RUN_DIR="$TP_LOG_ROOT/$TP_MODE"
if [[ -e "$TP_RUN_DIR" ]]; then
  echo "Refusing to overwrite the existing $TP_MODE receipt." >&2
  exit 73
fi
mkdir -m 700 "$TP_RUN_DIR"

export JAVA_HOME="$TP_JAVA_HOME"
export JENV_VERSION=21
export PATH="$JAVA_HOME/bin:${TP_DOCKER%/docker}:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export GRADLE_USER_HOME="$TP_GRADLE_USER_HOME"
export TRACEPERMIT_CACHE_DIR="$TP_CHECKOUT/.cache"
export TRACEPERMIT_GRADLE_USER_HOME="$TP_GRADLE_USER_HOME"
export COREPACK_ENABLE_NETWORK=0
export PNPM_CONFIG_OFFLINE=true
export npm_config_offline=true
export GRADLE_OPTS="-Djava.util.prefs.userRoot=$TP_CHECKOUT/.cache/java-preferences -Dorg.gradle.offline=true -Dorg.gradle.daemon=false -Dorg.gradle.caching=false -Dorg.gradle.configuration-cache=false -Dorg.gradle.workers.max=1"

tp_stage() {
  local label="$1"
  shift
  local status=0
  set +e
  (
    set -euo pipefail
    "$@"
  ) >"$TP_RUN_DIR/$label.stdout" 2>"$TP_RUN_DIR/$label.stderr"
  status=$?
  set -e
  printf '%s\n' "$status" >"$TP_RUN_DIR/$label.exit"
  if [[ "$status" -ne 0 ]]; then
    echo "Stopped at $label (exit $status); inspect $TP_RUN_DIR privately." >&2
    exit "$status"
  fi
}

tp_source_paths() {
  git ls-files \
    java/agentcore-organization-lifecycle-cli/build.gradle.kts \
    java/agentcore-organization-lifecycle-cli/src/bootstrap \
    java/agentcore-organization-lifecycle-cli/src/main \
    java/agentcore-organization-lifecycle-cli/src/containerTest \
    deploy/sandbox/organization-operator/Dockerfile
}

tp_allowed_preparation_path() {
  case "$1" in
    deploy/sandbox/organization-operator/README.md) return 0 ;;
    deploy/sandbox/organization-operator/validate-organization-container.sh) return 0 ;;
    *) return 1 ;;
  esac
}

tp_source_guards() {
  cd "$TP_CHECKOUT"
  [[ "$(pwd -P)" == "$TP_CHECKOUT" ]]
  [[ "$(git branch --show-current)" == "$TP_BRANCH" ]]
  [[ "$(git rev-parse HEAD)" == "$TP_EXPECTED_HEAD" ]]
  [[ "$(git rev-parse HEAD^{tree})" == "$TP_EXPECTED_TREE" ]]
  [[ -z "$(git status --porcelain=v1 --untracked-files=all)" ]]
  [[ "$(git rev-parse refs/remotes/origin/main)" == "$TP_ACCEPTED_MAIN" ]]
  [[ "$(git rev-parse "$TP_ACCEPTED_MAIN^{tree}")" == "$TP_ACCEPTED_TREE" ]]
  [[ "$(git show -s --format=%P "$TP_ACCEPTED_MAIN")" == "$TP_ACCEPTED_PARENTS" ]]
  [[ "$(git rev-parse "$TP_IMPLEMENTATION^{tree}")" == "$TP_IMPLEMENTATION_TREE" ]]
  [[ "$(git show -s --format=%P "$TP_IMPLEMENTATION")" == "$TP_IMPLEMENTATION_PARENT" ]]
  git merge-base --is-ancestor "$TP_ACCEPTED_MAIN" "$TP_IMPLEMENTATION"
  git merge-base --is-ancestor "$TP_IMPLEMENTATION" "$TP_EXPECTED_HEAD"

  local changed
  while IFS= read -r changed; do
    if ! tp_allowed_preparation_path "$changed"; then
      echo "Unexpected post-implementation path: $changed" >&2
      return 1
    fi
  done < <(git diff --name-only "$TP_IMPLEMENTATION" "$TP_EXPECTED_HEAD")

  tp_source_paths >"$TP_RUN_DIR/source-manifest.paths"
  while IFS= read -r changed; do
    /sbin/sha256sum "$changed"
  done <"$TP_RUN_DIR/source-manifest.paths" >"$TP_RUN_DIR/source-manifest.inventory"
  [[ "$(/sbin/sha256sum "$TP_RUN_DIR/source-manifest.inventory" | awk '{print $1}')" == "$TP_SOURCE_MANIFEST" ]]
  printf '%s\n' "$TP_SOURCE_MANIFEST" >"$TP_RUN_DIR/source-manifest.sha256"
  git show -s --format='commit=%H%ntree=%T%nparents=%P%nsubject=%s' "$TP_EXPECTED_HEAD" \
    >"$TP_RUN_DIR/candidate.provenance"
  git diff --name-status "$TP_ACCEPTED_MAIN" "$TP_EXPECTED_HEAD" \
    >"$TP_RUN_DIR/candidate-scope"
  printf '%s\n' "$TP_BRANCH" >"$TP_RUN_DIR/branch"
  printf '%s\n' "$TP_EXPECTED_HEAD" >"$TP_RUN_DIR/validation-head"
  printf '%s\n' "$TP_EXPECTED_TREE" >"$TP_RUN_DIR/validation-tree"

  [[ "$(grep -Ec '@Test' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/OrganizationOperatorArchiveTest.java)" == 2 ]]
  [[ "$(grep -Ec '@Test' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/OrganizationOperatorBoundaryTest.java)" == 4 ]]
  [[ "$(grep -Ec '@Test' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/OrganizationOperatorIdentityTest.java)" == 4 ]]
  [[ "$(grep -Ec '@Test' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/OrganizationOperatorProtocolTest.java)" == 4 ]]
  [[ "$(grep -Ec '@Test' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/OrganizationOperatorReceiptTest.java)" == 2 ]]
  [[ "$(grep -Ec '@Test' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/OrganizationOperatorSupervisorTest.java)" == 2 ]]
  [[ "$(grep -Ec '@Test' java/agentcore-organization-lifecycle-cli/src/test/java/io/tracepermit/agentcore/organizationcli/PostgresInstalledOrganizationOperatorTest.java)" == 7 ]]
}

tp_runtime_guards() {
  local java_version
  [[ -x /usr/bin/caffeinate ]]
  [[ -x "$TP_JAVA_HOME/bin/java" ]]
  [[ "$(cd -P "$TP_JAVA_HOME" && pwd)" == "$TP_JAVA_REAL_HOME" ]]
  java_version="$("$TP_JAVA_HOME/bin/java" -version 2>&1)"
  printf '%s\n' "$java_version"
  printf '%s\n' "$java_version" | grep -F '21.0.13.0.2'
  [[ "$("$TP_JAVA_HOME/bin/javac" -version 2>&1)" == "javac 21.0.13.0.2" ]]
  [[ -x "$TP_GRADLE" ]]
  [[ "$(/usr/bin/shasum -a 256 "$TP_CHECKOUT/java/gradle/wrapper/gradle-wrapper.jar" | awk '{print $1}')" == "$TP_GRADLE_WRAPPER_SHA" ]]
  grep -F "distributionSha256Sum=$TP_GRADLE_DISTRIBUTION_SHA" \
    "$TP_CHECKOUT/java/gradle/wrapper/gradle-wrapper.properties"
  "$TP_GRADLE" --version | grep -F 'Gradle 8.14.3'
  [[ -d "$TP_GRADLE_USER_HOME/caches/modules-2/files-2.1" ]]
  [[ -x "$TP_NODE" && -x "$TP_PNPM" ]]
  [[ "$("$TP_NODE" -p 'Number(process.versions.node.split(".")[0]) >= 22 ? "yes" : "no"')" == yes ]]
  "$TP_NODE" --version
  [[ "$("$TP_PNPM" --version)" == 11.25.0 ]]
  [[ -d "$TP_CHECKOUT/node_modules/.pnpm" ]]
  grep -F '"packageManager": "pnpm@11.25.0"' "$TP_CHECKOUT/package.json"
  [[ -x "$TP_DOCKER" && -S "${TP_DOCKER_ENDPOINT#unix://}" ]]
  "$TP_DOCKER" --host "$TP_DOCKER_ENDPOINT" version \
    --format 'client={{.Client.Version}} server={{.Server.Version}}'

  case "$TP_MODE" in
    focused|postgresql|repository)
      [[ "$("$TP_DOCKER" --host "$TP_DOCKER_ENDPOINT" image inspect postgres:16.4-alpine --format '{{.Id}}')" == "$TP_POSTGRES_IMAGE_ID" ]]
      "$TP_DOCKER" --host "$TP_DOCKER_ENDPOINT" image inspect postgres:16.4-alpine \
        --format 'postgres16.4={{.Id}} digests={{join .RepoDigests ","}}'
      ;;
  esac
  if [[ "$TP_MODE" == repository ]]; then
    [[ "$("$TP_DOCKER" --host "$TP_DOCKER_ENDPOINT" image inspect mariadb:11.4.13 --format '{{.Id}}')" == "$TP_MARIADB_IMAGE_ID" ]]
    "$TP_DOCKER" --host "$TP_DOCKER_ENDPOINT" image inspect mariadb:11.4.13 \
      --format 'mariadb11.4.13={{.Id}} digests={{join .RepoDigests ","}}'
  fi
  if [[ "$TP_MODE" == image ]]; then
    "$TP_DOCKER" --host "$TP_DOCKER_ENDPOINT" image inspect "$TP_BASE_IMAGE" \
      --format 'base={{.Id}} os={{.Os}} architecture={{.Architecture}} digests={{join .RepoDigests ","}}'
  fi
}

tp_gradle() {
  /usr/bin/caffeinate -i "$TP_GRADLE" -p "$TP_CHECKOUT/java" "$@" \
    --offline --rerun-tasks --no-build-cache --no-configuration-cache \
    --no-daemon --max-workers=1
}

tp_focused() {
  tp_gradle :agentcore-organization-lifecycle-cli:test \
    --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorProtocolTest \
    --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorReceiptTest \
    --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorIdentityTest \
    --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorArchiveTest \
    --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorBoundaryTest \
    --tests io.tracepermit.agentcore.organizationcli.OrganizationOperatorSupervisorTest \
    --tests io.tracepermit.agentcore.organizationcli.PostgresInstalledOrganizationOperatorTest
}

tp_capture_archive() {
  [[ -f "$TP_ARCHIVE" ]]
  printf '%s\n' "$TP_ARCHIVE" >"$TP_RUN_DIR/executed-archive.path"
  /sbin/sha256sum "$TP_ARCHIVE" >"$TP_RUN_DIR/executed-archive.sha256"
  /usr/bin/tar -tf "$TP_ARCHIVE" >"$TP_RUN_DIR/executed-archive.inventory"
  if grep -E '(^|/)(\.git|AGENTS\.md|build\.gradle\.kts|settings\.gradle\.kts)$|\.(java|kt)$' \
    "$TP_RUN_DIR/executed-archive.inventory" >/dev/null; then
    echo "The executed organization archive contains source or repository metadata." >&2
    return 1
  fi
  cp "$TP_ARCHIVE" "$TP_RUN_DIR/executed-archive.tar"
  [[ "$(/sbin/sha256sum "$TP_RUN_DIR/executed-archive.tar" | awk '{print $1}')" == "$(awk '{print $1}' "$TP_RUN_DIR/executed-archive.sha256")" ]]
  mkdir -m 700 "$TP_RUN_DIR/extracted"
  /usr/bin/tar -xf "$TP_RUN_DIR/executed-archive.tar" -C "$TP_RUN_DIR/extracted"
  printf '%s\n' "$TP_RUN_DIR/extracted" >"$TP_RUN_DIR/extraction.path"
  find "$TP_RUN_DIR/extracted" -print | LC_ALL=C sort >"$TP_RUN_DIR/extraction.inventory"
}

tp_capture_results() {
  local results="$1"
  local selectors="$2"
  local cases="$3"
  [[ -d "$results" ]]
  cp -R "$results" "$TP_RUN_DIR/test-results"
  "$TP_NODE" --input-type=commonjs - "$TP_RUN_DIR/test-results" "$selectors" "$cases" <<'NODE'
const fs = require("node:fs");
const path = require("node:path");
const root = process.argv[2];
const expectedSelectors = Number(process.argv[3]);
const expectedCases = Number(process.argv[4]);
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
if (files.length !== expectedSelectors || tests !== expectedCases || failures !== 0 || errors !== 0 || skipped !== 0) process.exit(1);
NODE
}

tp_require_stage() {
  local mode="$1"
  local stage="$2"
  [[ "$(cat "$TP_LOG_ROOT/$mode/$stage.exit")" == 0 ]]
  [[ "$(cat "$TP_LOG_ROOT/$mode/validation-head")" == "$TP_EXPECTED_HEAD" ]]
  [[ "$(cat "$TP_LOG_ROOT/$mode/source-manifest.sha256")" == "$TP_SOURCE_MANIFEST" ]]
}

tp_require_focused() {
  tp_require_stage focused 10-focused-validation
  [[ "$(cat "$TP_LOG_ROOT/focused/11-executed-archive.exit")" == 0 ]]
  [[ "$(cat "$TP_LOG_ROOT/focused/12-focused-results.exit")" == 0 ]]
}

tp_inventory() {
  tp_gradle :agentcore-organization-lifecycle-cli:organizationContainerInventory \
    -Ptracepermit.container.evidence="$TP_RUN_DIR" \
    -Ptracepermit.container.sourceCommit="$TP_EXPECTED_HEAD" \
    -Ptracepermit.container.sourceManifestHash="$TP_SOURCE_MANIFEST"
}

tp_image() {
  tp_gradle :agentcore-organization-lifecycle-cli:assembleOrganizationContainerImage \
    -Ptracepermit.container.docker="$TP_DOCKER" \
    -Ptracepermit.container.dockerEndpoint="$TP_DOCKER_ENDPOINT" \
    -Ptracepermit.container.baseImage="$TP_BASE_IMAGE" \
    -Ptracepermit.container.evidence="$TP_RUN_DIR" \
    -Ptracepermit.container.sourceCommit="$TP_EXPECTED_HEAD" \
    -Ptracepermit.container.sourceManifestHash="$TP_SOURCE_MANIFEST"
}

tp_image_provenance() {
  "$TP_NODE" --input-type=commonjs - \
    "$TP_LOG_ROOT/inventory/organization-archive-inventory.json" \
    "$TP_RUN_DIR/assembled-organization-image.json" \
    "$TP_EXPECTED_HEAD" "$TP_SOURCE_MANIFEST" <<'NODE'
const fs = require("node:fs");
const inventory = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const image = JSON.parse(fs.readFileSync(process.argv[3], "utf8"));
const source = process.argv[4];
const manifest = process.argv[5];
if (inventory.sourceCommit !== source || image.sourceCommit !== source ||
    inventory.sourceManifestSha256 !== manifest || image.sourceManifestSha256 !== manifest ||
    inventory.organizationArchiveSha256 !== image.organizationArchiveSha256 ||
    image.installRoot !== "/opt/organization" ||
    image.entrypoint !== "/opt/organization/bin/tracepermit-synthetic-organization-operator" ||
    image.registryPull !== false || image.imagePush !== false || image.qualification !== "UNRUN" ||
    !/^sha256:[a-f0-9]{64}$/.test(image.runtimeImageId)) process.exit(1);
console.log(JSON.stringify({
  sourceCommit: source,
  sourceManifestSha256: manifest,
  organizationArchiveSha256: image.organizationArchiveSha256,
  runtimeImageId: image.runtimeImageId,
  entrypoint: image.entrypoint,
}));
NODE
}

tp_image_field() {
  "$TP_NODE" -e \
    'const r=require(process.argv[1]); const v=r[process.argv[2]]; if(typeof v!=="string")process.exit(1); process.stdout.write(v)' \
    "$TP_LOG_ROOT/image/assembled-organization-image.json" "$1"
}

tp_container_test() {
  local task="$1"
  local runtime_image
  local archive_sha
  runtime_image="$(tp_image_field runtimeImageId)"
  archive_sha="$(tp_image_field organizationArchiveSha256)"
  tp_gradle ":agentcore-organization-lifecycle-cli:$task" \
    -Ptracepermit.container.docker="$TP_DOCKER" \
    -Ptracepermit.container.dockerEndpoint="$TP_DOCKER_ENDPOINT" \
    -Ptracepermit.container.runtimeImage="$runtime_image" \
    -Ptracepermit.container.evidence="$TP_RUN_DIR" \
    -Ptracepermit.container.sourceCommit="$TP_EXPECTED_HEAD" \
    -Ptracepermit.container.archiveSha256="$archive_sha"
}

tp_preserve_build_evidence() {
  local build
  local relative
  local destination
  cd "$TP_CHECKOUT"
  find "$TP_CHECKOUT/java" -type d -name build -prune -print \
    | LC_ALL=C sort >"$TP_RUN_DIR/pre-repository-build-paths"
  [[ -s "$TP_RUN_DIR/pre-repository-build-paths" ]]
  while IFS= read -r build; do
    relative="${build#"$TP_CHECKOUT/"}"
    destination="$TP_RUN_DIR/pre-repository-builds/$relative"
    mkdir -p "$(dirname "$destination")"
    mv "$build" "$destination"
  done <"$TP_RUN_DIR/pre-repository-build-paths"
  if find "$TP_CHECKOUT/java" -type f -path '*/build/*' -print -quit | grep -q .; then
    echo "Java build outputs remain after evidence preservation." >&2
    return 1
  fi
  find "$TP_RUN_DIR/pre-repository-builds" -type f -print \
    | LC_ALL=C sort \
    | while IFS= read -r build; do /sbin/sha256sum "$build"; done \
    >"$TP_RUN_DIR/pre-repository-builds.sha256"
}

tp_repository_check() {
  cd "$TP_CHECKOUT"
  date +%s >"$TP_RUN_DIR/repository-check.started-epoch"
  /usr/bin/caffeinate -i "$TP_PNPM" check
}

tp_coverage_receipt() {
  local report="$TP_CHECKOUT/java/build/reports/jacoco/jacocoAggregateReport/jacocoAggregateReport.xml"
  [[ -f "$report" ]]
  [[ "$(/usr/bin/stat -f '%m' "$report")" -ge "$(cat "$TP_RUN_DIR/repository-check.started-epoch")" ]]
  cp "$report" "$TP_RUN_DIR/jacocoAggregateReport.xml"
  /sbin/sha256sum "$TP_RUN_DIR/jacocoAggregateReport.xml" \
    >"$TP_RUN_DIR/jacocoAggregateReport.sha256"
  "$TP_NODE" --input-type=commonjs - "$TP_RUN_DIR/jacocoAggregateReport.xml" <<'NODE'
const fs = require("node:fs");
const xml = fs.readFileSync(process.argv[2], "utf8");
function ratio(type) {
  const values = [...xml.matchAll(new RegExp(`<counter type="${type}" missed="(\\d+)" covered="(\\d+)"/>`, "g"))];
  if (values.length === 0) throw new Error(`Missing ${type} counter`);
  const missed = Number(values.at(-1)[1]);
  const covered = Number(values.at(-1)[2]);
  return covered / (covered + missed);
}
const line = ratio("LINE");
const branch = ratio("BRANCH");
console.log(JSON.stringify({ line, branch }));
if (line < 0.94 || branch < 0.75) process.exit(1);
NODE
}

tp_ci_helpers() {
  cd "$TP_CHECKOUT"
  "$TP_NODE" --test \
    scripts/ci-workflow.test.mjs \
    scripts/ci-policy.test.mjs \
    scripts/ci-coverage.test.mjs \
    scripts/ci-mariadb-scope.test.mjs
}

tp_diff_checks() {
  cd "$TP_CHECKOUT"
  git diff --check "$TP_ACCEPTED_MAIN..$TP_EXPECTED_HEAD"
  git diff --check
  git diff --cached --check
}

tp_stage 00-source-guards tp_source_guards
tp_stage 01-runtime-guards tp_runtime_guards

case "$TP_MODE" in
  focused)
    tp_stage 10-focused-validation tp_focused
    tp_stage 11-executed-archive tp_capture_archive
    tp_stage 12-focused-results tp_capture_results "$TP_TEST_RESULTS/test" 7 25
    ;;
  inventory)
    tp_stage 02-focused-receipt tp_require_focused
    tp_stage 10-container-inventory tp_inventory
    tp_stage 11-inventory-results tp_capture_results "$TP_TEST_RESULTS/organizationContainerInventory" 1 1
    ;;
  image)
    tp_stage 02-focused-receipt tp_require_focused
    tp_stage 03-inventory-receipt tp_require_stage inventory 10-container-inventory
    [[ "$(cat "$TP_LOG_ROOT/inventory/11-inventory-results.exit")" == 0 ]]
    tp_stage 10-image-assembly tp_image
    tp_stage 11-image-provenance tp_image_provenance
    ;;
  transport)
    tp_stage 02-focused-receipt tp_require_focused
    tp_stage 03-inventory-receipt tp_require_stage inventory 10-container-inventory
    tp_stage 04-image-receipt tp_require_stage image 11-image-provenance
    tp_stage 10-transport-qualification tp_container_test organizationContainerTransportQualification
    tp_stage 11-transport-results tp_capture_results "$TP_TEST_RESULTS/organizationContainerTransportQualification" 1 14
    ;;
  postgresql)
    tp_stage 02-focused-receipt tp_require_focused
    tp_stage 03-inventory-receipt tp_require_stage inventory 10-container-inventory
    tp_stage 04-image-receipt tp_require_stage image 11-image-provenance
    tp_stage 05-transport-receipt tp_require_stage transport 10-transport-qualification
    [[ "$(cat "$TP_LOG_ROOT/transport/11-transport-results.exit")" == 0 ]]
    tp_stage 10-postgresql-qualification tp_container_test organizationContainerPostgresQualification
    tp_stage 11-postgresql-results tp_capture_results "$TP_TEST_RESULTS/organizationContainerPostgresQualification" 1 3
    ;;
  repository)
    tp_stage 02-focused-receipt tp_require_focused
    tp_stage 03-inventory-receipt tp_require_stage inventory 10-container-inventory
    tp_stage 04-image-receipt tp_require_stage image 11-image-provenance
    tp_stage 05-transport-receipt tp_require_stage transport 10-transport-qualification
    tp_stage 06-postgresql-receipt tp_require_stage postgresql 10-postgresql-qualification
    [[ "$(cat "$TP_LOG_ROOT/postgresql/11-postgresql-results.exit")" == 0 ]]
    tp_stage 10-preserve-build-evidence tp_preserve_build_evidence
    tp_stage 11-repository-check tp_repository_check
    tp_stage 12-aggregate-coverage-receipt tp_coverage_receipt
    tp_stage 13-format-check bash -c 'cd "$1" && "$2" format:check' _ "$TP_CHECKOUT" "$TP_PNPM"
    tp_stage 14-documentation-links bash -c 'cd "$1" && "$2" docs:links' _ "$TP_CHECKOUT" "$TP_PNPM"
    tp_stage 15-documentation-flows bash -c 'cd "$1" && "$2" docs:flows:check' _ "$TP_CHECKOUT" "$TP_PNPM"
    tp_stage 16-ci-policy-helpers tp_ci_helpers
    tp_stage 17-diff-checks tp_diff_checks
    ;;
esac

printf '%s validation stages passed; private receipts: %s\n' "$TP_MODE" "$TP_RUN_DIR"
