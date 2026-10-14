package io.tracepermit.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SecurityReadinessTest {
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");

  @Test
  void dependencySetMustBeExactAndReadyMustMatchEveryDependency() {
    var missing = readyStates();
    missing.remove("policy");
    assertThatThrownBy(() -> new SecurityReadiness(false, NOW, missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Exactly the required security dependencies must be present.");

    var extra = readyStates();
    extra.put("database", true);
    assertThatThrownBy(() -> new SecurityReadiness(true, NOW, extra))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Exactly the required security dependencies must be present.");

    var unavailable = readyStates();
    unavailable.put("authentication", false);
    assertThatThrownBy(() -> new SecurityReadiness(true, NOW, unavailable))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Readiness must match all required dependency states.");
  }

  @Test
  void readinessSnapshotIsImmutableAndRemoteRuntimeFailsClosed() {
    var states = readyStates();
    SecurityReadiness readiness = new SecurityReadiness(true, NOW, states);
    states.put("policy", false);
    assertThat(readiness.dependencies()).containsEntry("policy", true);
    assertThatThrownBy(() -> readiness.dependencies().put("policy", false))
        .isInstanceOf(UnsupportedOperationException.class);

    SecurityReadiness remote = SecurityReadiness.forRuntime(false, NOW);
    assertThat(remote.ready()).isFalse();
    assertThat(remote.dependencies()).containsEntry("policy", true);
    assertThat(remote.dependencies()).containsEntry("authentication", false);
    assertThat(remote.dependencies()).containsEntry("permit_signer", false);
    assertThat(remote.dependencies()).containsEntry("replay_store", false);
    assertThat(remote.dependencies()).containsEntry("evidence_store", false);
  }

  private static HashMap<String, Boolean> readyStates() {
    HashMap<String, Boolean> states = new HashMap<>();
    SecurityReadiness.REQUIRED.forEach(name -> states.put(name, true));
    return states;
  }
}
