package io.tracepermit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SecurityTelemetryEventTest {
  private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

  @Test
  void acceptsAndSnapshotsOnlyBoundedOperationalMetadata() {
    HashMap<String, String> attributes = new HashMap<>();
    attributes.put("component", "remote_edge");
    attributes.put("reason_code", "INVALID_ACCESS_TOKEN");
    SecurityTelemetryEvent event =
        new SecurityTelemetryEvent(
            SecurityTelemetryEvent.Type.REMOTE_EDGE_DENIAL,
            SecurityTelemetryEvent.Outcome.DENIED,
            Duration.ofMillis(8),
            NOW,
            attributes);

    attributes.put("stage", "injected");

    assertThat(event.type().wireName()).isEqualTo("remote.edge.denial");
    assertThat(event.outcome().wireName()).isEqualTo("denied");
    assertThat(event.attributes())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("component", "remote_edge", "reason_code", "INVALID_ACCESS_TOKEN"));
    assertThatThrownBy(() -> event.attributes().put("stage", "mutated"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsUnboundedSensitiveOrMalformedFields() {
    assertThatThrownBy(
            () ->
                new SecurityTelemetryEvent(
                    SecurityTelemetryEvent.Type.REMOTE_EDGE_DENIAL,
                    SecurityTelemetryEvent.Outcome.DENIED,
                    Duration.ZERO,
                    NOW,
                    Map.of("authorization", "Bearer-secret")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SecurityTelemetryEvent(
                    SecurityTelemetryEvent.Type.REMOTE_EDGE_DENIAL,
                    SecurityTelemetryEvent.Outcome.DENIED,
                    Duration.ofDays(2),
                    NOW,
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SecurityTelemetryEvent(
                    SecurityTelemetryEvent.Type.REMOTE_EDGE_DENIAL,
                    SecurityTelemetryEvent.Outcome.DENIED,
                    Duration.ZERO,
                    NOW,
                    Map.of("reason_code", "contains spaces")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void explicitDevelopmentSinkDiscardsAlreadyValidatedEvents() {
    SecurityTelemetry telemetry = SecurityTelemetry.discardForExplicitDevelopmentTests();
    SecurityTelemetryEvent event =
        new SecurityTelemetryEvent(
            SecurityTelemetryEvent.Type.REMOTE_EDGE_AUTHORIZED,
            SecurityTelemetryEvent.Outcome.ACCEPTED,
            Duration.ZERO,
            NOW,
            Map.of("component", "remote_edge"));

    telemetry.record(event);

    assertThat(event.observedAt()).isEqualTo(NOW);
  }
}
