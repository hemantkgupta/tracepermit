#!/usr/bin/env bash

# Shared Java/Gradle environment configuration for local and cloud development.
# This file is intended to be sourced by the repository's Java launch scripts.

tracepermit_java_major() {
  local tracepermit_javac="$1"
  "$tracepermit_javac" -version 2>&1 | awk '{split($2, version, "."); print version[1]}'
}

tracepermit_activate_jdk() {
  local tracepermit_candidate="$1"
  local tracepermit_candidate_major

  [[ -x "$tracepermit_candidate/bin/javac" ]] || return 1
  tracepermit_candidate_major="$(tracepermit_java_major "$tracepermit_candidate/bin/javac")"
  [[ "$tracepermit_candidate_major" =~ ^[0-9]+$ ]] || return 1
  [[ "$tracepermit_candidate_major" -eq 21 ]] || return 1

  export JAVA_HOME="$tracepermit_candidate"
  export PATH="$JAVA_HOME/bin:$PATH"
}

tracepermit_configure_java_environment() {
  local tracepermit_repo_root
  local tracepermit_managed_jdk
  local tracepermit_macos_jdk=""
  local tracepermit_path_java_major=""
  local tracepermit_path_javac
  local tracepermit_path_java
  local tracepermit_requested_java_major="unknown"
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
      if [[ -x "$TRACEPERMIT_JAVA_HOME/bin/javac" ]]; then
        tracepermit_requested_java_major="$(tracepermit_java_major "$TRACEPERMIT_JAVA_HOME/bin/javac")"
      fi
      echo "TRACEPERMIT_JAVA_HOME must point to a Java 21 JDK; found Java $tracepermit_requested_java_major." >&2
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
    tracepermit_path_java_major="$(tracepermit_java_major "$tracepermit_path_javac")"
    if [[ -n "$tracepermit_path_java" ]] && \
      [[ "$tracepermit_path_java_major" =~ ^[0-9]+$ ]] && \
      [[ "$tracepermit_path_java_major" -eq 21 ]]; then
      unset JAVA_HOME
      tracepermit_java_selected=true
    fi
  fi

  if [[ "$tracepermit_java_selected" == false ]]; then
    if [[ "$tracepermit_path_java_major" =~ ^[0-9]+$ ]]; then
      echo "Java 21 JDK is required; found Java $tracepermit_path_java_major. Install Java 21 or set TRACEPERMIT_JAVA_HOME to its installation directory." >&2
    else
      echo "Java 21 JDK is required. Install it locally or set TRACEPERMIT_JAVA_HOME." >&2
    fi
    return 1
  fi

  tracepermit_cache_root="${TRACEPERMIT_CACHE_DIR:-$tracepermit_repo_root/.cache}"
  tracepermit_java_preferences="$tracepermit_cache_root/java-preferences"
  export GRADLE_USER_HOME="${TRACEPERMIT_GRADLE_USER_HOME:-$tracepermit_cache_root/gradle}"
  mkdir -p "$tracepermit_java_preferences" "$GRADLE_USER_HOME"

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

tracepermit_configure_java_environment
unset -f tracepermit_java_major tracepermit_activate_jdk tracepermit_configure_java_environment
