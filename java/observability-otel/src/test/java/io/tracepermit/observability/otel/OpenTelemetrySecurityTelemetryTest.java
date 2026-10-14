package io.tracepermit.observability.otel;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.tracepermit.application.SecurityTelemetryEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class OpenTelemetrySecurityTelemetryTest {
  @Test
  void exportsOnlyTheBoundedProjectionToTraces() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    InMemoryMetricReader metricReader = InMemoryMetricReader.create();
    try (SdkTracerProvider provider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        SdkMeterProvider meterProvider =
            SdkMeterProvider.builder().registerMetricReader(metricReader).build()) {
      OpenTelemetrySdk sdk =
          OpenTelemetrySdk.builder()
              .setTracerProvider(provider)
              .setMeterProvider(meterProvider)
              .build();
      OpenTelemetrySecurityTelemetry telemetry = new OpenTelemetrySecurityTelemetry(sdk);

      telemetry.record(
          new SecurityTelemetryEvent(
              SecurityTelemetryEvent.Type.REMOTE_EDGE_DENIAL,
              SecurityTelemetryEvent.Outcome.DENIED,
              Duration.ofMillis(12),
              Instant.parse("2026-03-01T12:00:00Z"),
              Map.of(
                  "component", "remote_edge",
                  "stage", "authentication",
                  "reason_code", "INVALID_ACCESS_TOKEN")));

      assertThat(exporter.getFinishedSpanItems()).hasSize(1);
      var span = exporter.getFinishedSpanItems().getFirst();
      assertThat(span.getName()).isEqualTo("tracepermit.remote.edge.denial");
      assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
      assertThat(span.getAttributes().asMap())
          .containsEntry(AttributeKey.stringKey("tracepermit.event"), "remote.edge.denial")
          .containsEntry(AttributeKey.stringKey("tracepermit.outcome"), "denied")
          .containsEntry(AttributeKey.stringKey("tracepermit.component"), "remote_edge")
          .containsEntry(AttributeKey.longKey("tracepermit.duration_nanos"), 12_000_000L)
          .doesNotContainKey(AttributeKey.stringKey("authorization"))
          .doesNotContainKey(AttributeKey.stringKey("arguments"))
          .doesNotContainKey(AttributeKey.stringKey("result"));
      assertThat(metricReader.collectAllMetrics())
          .extracting(metric -> metric.getName())
          .containsExactlyInAnyOrder(
              "tracepermit.security.events", "tracepermit.security.operation.duration");
    }
  }
}
