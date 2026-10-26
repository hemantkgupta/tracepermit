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
  local compiler_version="$2"
  local runtime_version="$3"

  mkdir -p "$fake_jdk/bin"
  printf '#!/bin/bash\nprintf '\''javac %%s\\n'\'' '\''%s'\''\nexit %s\n' \
    "$compiler_version" "${4:-0}" >"$fake_jdk/bin/javac"
  printf '#!/bin/bash\nprintf '\''openjdk version "%%s"\\n'\'' '\''%s'\''\nexit %s\n' \
    "$runtime_version" "${5:-0}" >"$fake_jdk/bin/java"
  chmod +x "$fake_jdk/bin/javac" "$fake_jdk/bin/java"
}

make_fake_jdk "$test_root/jdk 21" "21.0.7" "21.0.7"
make_fake_jdk "$test_root/jdk-25" "25.0.1" "25.0.1"
make_fake_jdk "$test_root/jre-only" "21.0.7" "21.0.7"
rm "$test_root/jre-only/bin/javac"
make_fake_jdk "$test_root/compiler-only" "21.0.7" "21.0.7"
rm "$test_root/compiler-only/bin/java"
make_fake_jdk "$test_root/mismatched" "21.0.7" "25.0.1"
make_fake_jdk "$test_root/compiler-fails" "21.0.7" "21.0.7" 9 0
make_fake_jdk "$test_root/runtime-fails" "21.0.7" "21.0.7" 0 9
make_fake_jdk "$test_root/runtime-not-executable" "21.0.7" "21.0.7"
chmod -x "$test_root/runtime-not-executable/bin/java"

# Relocate only the setup fixture so a real managed/macOS JDK cannot mask PATH
# failures. Minimal PATH contains utilities and a harmless Gradle sentinel only.
mkdir -p "$test_root/isolated-repo/scripts" "$test_root/tools"
cp "$repo_root/scripts/configure-java-environment.sh" "$test_root/isolated-repo/scripts/"
for utility in awk dirname mkdir; do
  ln -s "$(command -v "$utility")" "$test_root/tools/$utility"
done
printf '#!/bin/bash\nprintf "TracePermitFixture\\n"\n' >"$test_root/tools/uname"
printf '#!/bin/bash\nprintf "sentinel\\n" >"$TRACEPERMIT_TEST_SENTINEL"\n' >"$test_root/tools/gradle"
chmod +x "$test_root/tools/uname" "$test_root/tools/gradle"

cat >"$test_root/status-driver" <<'EOF'
#!/bin/bash
set -u
if [[ "$3" == errexit ]]; then set -e; else set +e; fi
case "$2" in
  plain) source "$1"; status=$? ;;
  if) if source "$1"; then status=0; else status=$?; fi ;;
  and) source "$1" && gradle; status=$? ;;
  direct)
    if [[ "$3" == errexit ]]; then "$BASH" -e "$1"; else "$BASH" "$1"; fi
    status=$?
    ;;
  *) exit 40 ;;
esac
[[ "$status" == "$4" ]] || exit 41
for helper in tracepermit_java_major tracepermit_java_pair_is_21 tracepermit_activate_jdk \
  tracepermit_configure_java_environment tracepermit_finish_java_environment; do
  if declare -F "$helper" >/dev/null; then exit 42; fi
done
if [[ "$4" == 1 ]]; then
  [[ ! -e "$TRACEPERMIT_TEST_SENTINEL" && ! -d "$TRACEPERMIT_CACHE_DIR" && ! -d "$TRACEPERMIT_GRADLE_USER_HOME" ]] || exit 43
else
  [[ -d "$TRACEPERMIT_CACHE_DIR/java-preferences" && -d "$TRACEPERMIT_GRADLE_USER_HOME" ]] || exit 44
  if [[ "$2" != direct ]]; then
    if [[ "$5" == path ]]; then
      [[ -z "${JAVA_HOME+x}" ]] || exit 45
    else
      [[ "$JAVA_HOME" == "$5" ]] || exit 46
    fi
    [[ "$GRADLE_USER_HOME" == "$TRACEPERMIT_GRADLE_USER_HOME" ]] || exit 47
    [[ "$GRADLE_OPTS" == *"-Djava.util.prefs.userRoot=$TRACEPERMIT_CACHE_DIR/java-preferences"* ]] || exit 48
  fi
fi
EOF

shell_cases=0
run_status_case() {
  local selector="$1" fixture="$2" expected="$3" mode="$4" errexit="$5"
  local explicit="$fixture" inherited="$test_root/jdk 21" chosen="$fixture"
  local fixture_path="$test_root/jdk 21/bin:$test_root/tools"
  local child_status=0 expected_child_status=0
  shell_cases=$((shell_cases + 1))
  if [[ "$selector" == path ]]; then
    explicit=""; inherited=""; chosen=path
    fixture_path="$fixture/bin:$test_root/tools"
  elif [[ "$selector" == inherited ]]; then
    explicit=""; inherited="$fixture"
  fi
  if [[ "$expected" == 1 && "$errexit" == errexit && ( "$mode" == plain || "$mode" == direct ) ]]; then
    expected_child_status=1
  fi
  if env TRACEPERMIT_JAVA_HOME="$explicit" JAVA_HOME="$inherited" PATH="$fixture_path" \
    TRACEPERMIT_CACHE_DIR="$test_root/case-$shell_cases-cache" \
    TRACEPERMIT_GRADLE_USER_HOME="$test_root/case-$shell_cases-gradle" \
    TRACEPERMIT_TEST_SENTINEL="$test_root/case-$shell_cases-sentinel" \
    "$BASH" "$test_root/status-driver" "$test_root/isolated-repo/scripts/configure-java-environment.sh" \
    "$mode" "$errexit" "$expected" "$chosen" >"$test_root/case-$shell_cases-output" 2>&1; then
    child_status=0
  else
    child_status=$?
  fi
  if [[ "$child_status" != "$expected_child_status" ]]; then
    echo "Java environment shell case $shell_cases failed (status $child_status, expected $expected_child_status)." >&2
    return 1
  fi
  if [[ "$expected" == 1 ]]; then
    [[ ! -e "$test_root/case-$shell_cases-sentinel" ]]
    [[ ! -d "$test_root/case-$shell_cases-cache" && ! -d "$test_root/case-$shell_cases-gradle" ]]
    if [[ "$selector" == explicit ]]; then
      grep -F 'TRACEPERMIT_JAVA_HOME must point to a Java 21 JDK with working java and javac executables.' \
        "$test_root/case-$shell_cases-output" >/dev/null
    fi
  fi
}

for mode in plain if and direct; do
  for errexit in no-errexit errexit; do
    # Explicit invalid selectors must not fall through to the valid inherited
    # Java21 pair or the independently valid pair on PATH.
    for fixture in missing-jdk jdk-25 jre-only compiler-only mismatched compiler-fails runtime-fails runtime-not-executable; do
      run_status_case explicit "$test_root/$fixture" 1 "$mode" "$errexit"
      run_status_case path "$test_root/$fixture" 1 "$mode" "$errexit"
    done
    run_status_case explicit "$test_root/jdk 21" 0 "$mode" "$errexit"
    run_status_case inherited "$test_root/jdk 21" 0 "$mode" "$errexit"
    run_status_case path "$test_root/jdk 21" 0 "$mode" "$errexit"
  done
done

# An explicit valid JDK also takes precedence over an invalid inherited JDK.
selected_java_home="$(
  TRACEPERMIT_JAVA_HOME="$test_root/jdk 21" JAVA_HOME="$test_root/jdk-25" \
    bash -c 'set -euo pipefail; source "$1"; printf "%s" "$JAVA_HOME"' _ \
    "$repo_root/scripts/configure-java-environment.sh"
)"
[[ "$selected_java_home" == "$test_root/jdk 21" ]]

if grep -n '/workspace' \
  "$repo_root/scripts/configure-java-environment.sh" \
  "$repo_root/scripts/run-java-check.sh" \
  "$repo_root/scripts/run-java-control-plane.sh"; then
  echo "Java scripts must not contain workspace-specific absolute paths." >&2
  exit 1
fi

echo "Java environment configuration passed $shell_cases shell scenarios, native setup and explicit precedence."
