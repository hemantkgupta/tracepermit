package io.tracepermit.adapters.remote;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fail-closed production-readiness snapshot for the hardened remote edge. */
public record RemoteEdgeReadiness(
    boolean ready, Instant checkedAt, Map<String, Boolean> dependencies) {
  public static final List<String> REQUIRED =
      List.of(
          "tls_identity",
          "oauth_trust",
          "sender_constraint",
          "rate_limit_state",
          "durable_enforcement_state",
          "evidence_store",
          "security_telemetry",
          "target_egress_boundary",
          "connector_qualification");

  public RemoteEdgeReadiness {
    Objects.requireNonNull(checkedAt);
    Map<String, Boolean> snapshot = Map.copyOf(dependencies);
    if (!snapshot.keySet().equals(Set.copyOf(REQUIRED))) {
      throw new IllegalArgumentException(
          "Exactly the required remote-edge dependencies must be present.");
    }
    if (ready != REQUIRED.stream().allMatch(name -> Boolean.TRUE.equals(snapshot.get(name)))) {
      throw new IllegalArgumentException(
          "Remote-edge readiness must match all required dependency states.");
    }
    dependencies = snapshot;
  }

  public static RemoteEdgeReadiness allReady(Instant checkedAt) {
    Map<String, Boolean> states = new LinkedHashMap<>();
    REQUIRED.forEach(name -> states.put(name, true));
    return new RemoteEdgeReadiness(true, checkedAt, states);
  }
}
