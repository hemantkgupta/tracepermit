package io.tracepermit.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record SecurityReadiness(boolean ready, Instant checkedAt, Map<String, Boolean> dependencies) {
  public static final List<String> REQUIRED = List.of("authentication", "policy", "permit_signer", "replay_store", "evidence_store");

  public SecurityReadiness {
    Objects.requireNonNull(checkedAt);
    Map<String, Boolean> snapshot = Map.copyOf(dependencies);
    if (!snapshot.keySet().equals(Set.copyOf(REQUIRED))) throw new IllegalArgumentException("Exactly the required security dependencies must be present.");
    if (ready != REQUIRED.stream().allMatch(name -> Boolean.TRUE.equals(snapshot.get(name)))) {
      throw new IllegalArgumentException("Readiness must match all required dependency states.");
    }
    dependencies = snapshot;
  }

  public static SecurityReadiness allReady(Instant checkedAt) {
    Map<String, Boolean> states = new LinkedHashMap<>();
    REQUIRED.forEach(name -> states.put(name, true));
    return new SecurityReadiness(true, checkedAt, states);
  }

  public static SecurityReadiness forRuntime(boolean isLoopbackHost, Instant checkedAt) {
    if (isLoopbackHost) return allReady(checkedAt);
    Map<String, Boolean> states = new LinkedHashMap<>();
    states.put("authentication", false);
    states.put("policy", true);
    states.put("permit_signer", false);
    states.put("replay_store", false);
    states.put("evidence_store", false);
    return new SecurityReadiness(false, checkedAt, states);
  }
}
