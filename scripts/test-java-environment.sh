#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/tracepermit-java-environment.XXXXXX")"

cleanup() {
  rm -rf "$test_root"
}
trap cleanup EXIT

export TRACEPERMIT_CACHE_DIR="$test_root/cache"
export TRACEPERMIT_GRADLE_USER_HOME="$test_root/gradle"

source "$repo_root/scripts/configure-java-environment.sh"

[[ "$GRADLE_USER_HOME" == "$test_root/gradle" ]]
[[ -d "$TRACEPERMIT_CACHE_DIR/java-preferences" ]]
[[ -d "$GRADLE_USER_HOME" ]]
[[ "$GRADLE_OPTS" == *"-Djava.util.prefs.userRoot=$TRACEPERMIT_CACHE_DIR/java-preferences"* ]]

make_fake_jdk() {
  local fake_jdk="$1"
  local fake_version="$2"

  mkdir -p "$fake_jdk/bin"
  printf '#!/usr/bin/env bash\nprintf '\''javac %%s\\n'\'' '\''%s'\''\n' "$fake_version" >"$fake_jdk/bin/javac"
  chmod +x "$fake_jdk/bin/javac"
}

make_fake_jdk "$test_root/jdk-21" "21.0.7"
make_fake_jdk "$test_root/jdk-25" "25.0.1"

selected_java_home="$(
  TRACEPERMIT_JAVA_HOME="$test_root/jdk-21" \
    TRACEPERMIT_CACHE_DIR="$test_root/explicit-cache" \
    TRACEPERMIT_GRADLE_USER_HOME="$test_root/explicit-gradle" \
    bash -c 'set -euo pipefail; source "$1"; printf "%s" "$JAVA_HOME"' _ \
    "$repo_root/scripts/configure-java-environment.sh"
)"
[[ "$selected_java_home" == "$test_root/jdk-21" ]]

if TRACEPERMIT_JAVA_HOME="$test_root/jdk-25" \
  TRACEPERMIT_CACHE_DIR="$test_root/rejected-cache" \
  bash -c 'set -euo pipefail; source "$1"' _ \
  "$repo_root/scripts/configure-java-environment.sh" \
  >"$test_root/jdk-25-output" 2>&1; then
  echo "Java 25 must be rejected before Gradle starts." >&2
  exit 1
fi
grep -F "must point to a Java 21 JDK; found Java 25." "$test_root/jdk-25-output" >/dev/null

if grep -n '/workspace' \
  "$repo_root/scripts/configure-java-environment.sh" \
  "$repo_root/scripts/run-java-check.sh" \
  "$repo_root/scripts/run-java-control-plane.sh"; then
  echo "Java scripts must not contain workspace-specific absolute paths." >&2
  exit 1
fi

echo "Java environment configuration is portable and requires Java 21."
