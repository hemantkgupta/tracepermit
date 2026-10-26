#!/usr/bin/env bash

# Shared Java/Gradle environment configuration for local and cloud development.
# This file is intended to be sourced by the repository's Java launch scripts.

tracepermit_java_major() {
  local tracepermit_version_output
  tracepermit_version_output="$("$1" -version 2>&1)" || return 1
  printf '%s\n' "$tracepermit_version_output" | awk -v tool="$2" '
    NR == 1 {
      if (tool == "javac" && $1 == "javac") value = $2
      else if (tool == "java" && ($1 == "java" || $1 == "openjdk") && $2 == "version") value = $3
      else exit 1
      gsub(/"/, "", value)
      split(value, version, ".")
      if (version[1] !~ /^[0-9]+$/) exit 1
      print version[1]
    }'
}

tracepermit_java_pair_is_21() {
  local tracepermit_compiler_major
  local tracepermit_runtime_major
  [[ -x "$1" && -x "$2" ]] || return 1
  tracepermit_compiler_major="$(tracepermit_java_major "$1" javac)" || return 1
  tracepermit_runtime_major="$(tracepermit_java_major "$2" java)" || return 1
  [[ "$tracepermit_compiler_major" == 21 && "$tracepermit_runtime_major" == 21 ]]
}

tracepermit_activate_jdk() {
  local tracepermit_candidate="$1"
  tracepermit_java_pair_is_21 "$tracepermit_candidate/bin/javac" "$tracepermit_candidate/bin/java" || return 1

  export JAVA_HOME="$tracepermit_candidate"
  export PATH="$JAVA_HOME/bin:$PATH"
}

tracepermit_configure_java_environment() {
  local tracepermit_repo_root
  local tracepermit_managed_jdk
  local tracepermit_macos_jdk=""
  local tracepermit_path_javac
  local tracepermit_path_java
  local tracepermit_java_selected=false
  local tracepermit_cache_root
  local tracepermit_java_preferences
  local tracepermit_proxy_url
  local tracepermit_proxy_address
  local tracepermit_proxy_host
  local tracepermit_proxy_port

  tracepermit_repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  tracepermit_managed_jdk="$tracepermit_repo_root/../.local/jdk-21.0.12"

  if [[ -n "${TRACEPERMIT_JAVA_HOME:-}" ]]; then
    if ! tracepermit_activate_jdk "$TRACEPERMIT_JAVA_HOME"; then
      echo "TRACEPERMIT_JAVA_HOME must point to a Java 21 JDK with working java and javac executables." >&2
      return 1
    fi
    tracepermit_java_selected=true
  elif [[ -n "${JAVA_HOME:-}" ]] && tracepermit_activate_jdk "$JAVA_HOME"; then
    tracepermit_java_selected=true
  elif tracepermit_activate_jdk "$tracepermit_managed_jdk"; then
    tracepermit_java_selected=true
  elif [[ "$(uname -s)" == "Darwin" ]] && [[ -x /usr/libexec/java_home ]]; then
    tracepermit_macos_jdk="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$tracepermit_macos_jdk" ]] && tracepermit_activate_jdk "$tracepermit_macos_jdk"; then
      tracepermit_java_selected=true
    fi
  fi

  if [[ "$tracepermit_java_selected" == false ]] && command -v javac >/dev/null 2>&1; then
    tracepermit_path_javac="$(command -v javac)"
    tracepermit_path_java="$(command -v java 2>/dev/null || true)"
    if tracepermit_java_pair_is_21 "$tracepermit_path_javac" "$tracepermit_path_java"; then
      unset JAVA_HOME
      tracepermit_java_selected=true
    fi
  fi

  if [[ "$tracepermit_java_selected" == false ]]; then
    echo "Java 21 java and javac executables are required. Install a Java 21 JDK locally or set TRACEPERMIT_JAVA_HOME." >&2
    return 1
  fi

  tracepermit_cache_root="${TRACEPERMIT_CACHE_DIR:-$tracepermit_repo_root/.cache}"
  tracepermit_java_preferences="$tracepermit_cache_root/java-preferences"
  export GRADLE_USER_HOME="${TRACEPERMIT_GRADLE_USER_HOME:-$tracepermit_cache_root/gradle}"
  mkdir -p "$tracepermit_java_preferences" "$GRADLE_USER_HOME" || return 1

  export GRADLE_OPTS="${GRADLE_OPTS:-} -Djava.util.prefs.userRoot=$tracepermit_java_preferences"

  if [[ -f /etc/ssl/certs/java/cacerts ]]; then
    export GRADLE_OPTS="$GRADLE_OPTS -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
  fi

  tracepermit_proxy_url="${HTTPS_PROXY:-${https_proxy:-}}"
  if [[ -n "$tracepermit_proxy_url" ]]; then
    tracepermit_proxy_address="${tracepermit_proxy_url#*://}"
    tracepermit_proxy_host="${tracepermit_proxy_address%%:*}"
    tracepermit_proxy_port="${tracepermit_proxy_address##*:}"
    export GRADLE_OPTS="$GRADLE_OPTS -Dhttps.proxyHost=$tracepermit_proxy_host -Dhttps.proxyPort=$tracepermit_proxy_port -Dhttp.proxyHost=$tracepermit_proxy_host -Dhttp.proxyPort=$tracepermit_proxy_port"
  fi
}

# Capture the status before cleanup, including when source is used under if/&&
# (where Bash suppresses errexit inside the sourced functions). A local status
# also avoids leaving a status variable in the caller's environment.
tracepermit_finish_java_environment() {
  local tracepermit_configuration_status=0
  tracepermit_configure_java_environment || tracepermit_configuration_status=$?
  unset -f tracepermit_java_major tracepermit_java_pair_is_21 tracepermit_activate_jdk \
    tracepermit_configure_java_environment tracepermit_finish_java_environment
  return "$tracepermit_configuration_status"
}

# Bash3.2 can apply errexit to a final bare function call even when the
# caller conditionally sources this file. Guard that call here, then finish
# explicitly at file scope. Configuration's public status is always 0 or 1.
# return leaves a sourcing caller in control; direct Bash execution uses exit.
if tracepermit_finish_java_environment; then
  return 0 2>/dev/null || exit 0
else
  return 1 2>/dev/null || exit 1
fi
