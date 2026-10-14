package io.tracepermit.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict, low-cardinality telemetry envelope. It has no free-form message or payload field. */
public record SecurityTelemetryEvent(
    Type type,
    Outcome outcome,
    Duration duration,
    Instant observedAt,
    Map<String, String> attributes) {
  private static final Set<String> ATTRIBUTE_KEYS =
      Set.of("component", "stage", "reason_code", "adapter", "profile");
  private static final Duration MAXIMUM_DURATION = Duration.ofHours(24);

  public SecurityTelemetryEvent {
    Objects.requireNonNull(type);
    Objects.requireNonNull(outcome);
    Objects.requireNonNull(duration);
    Objects.requireNonNull(observedAt);
    Objects.requireNonNull(attributes);
    if (duration.isNegative() || duration.compareTo(MAXIMUM_DURATION) > 0) {
      throw new IllegalArgumentException("The security telemetry duration is invalid.");
    }
    Map<String, String> snapshot = Map.copyOf(attributes);
    if (snapshot.size() > ATTRIBUTE_KEYS.size()
        || !ATTRIBUTE_KEYS.containsAll(snapshot.keySet())) {
      throw new IllegalArgumentException("The security telemetry attributes are not allowed.");
    }
    snapshot.forEach(
        (key, value) -> {
          Objects.requireNonNull(value);
          if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}")) {
            throw new IllegalArgumentException("A security telemetry attribute value is invalid.");
          }
        });
    attributes = snapshot;
  }

  public enum Type {
    REMOTE_EDGE_DENIAL("remote.edge.denial"),
    REMOTE_EDGE_AUTHORIZED("remote.edge.authorized"),
    REMOTE_EDGE_COMPLETED("remote.edge.completed"),
    EVIDENCE_PUBLICATION_BATCH("evidence.publication.batch"),
    RECONCILIATION_BATCH("execution.reconciliation.batch");

    private final String wireName;

    Type(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }
  }

  public enum Outcome {
    ACCEPTED("accepted"),
    SUCCEEDED("succeeded"),
    DENIED("denied"),
    FAILED("failed"),
    DEFERRED("deferred");

    private final String wireName;

    Outcome(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }
  }
}
