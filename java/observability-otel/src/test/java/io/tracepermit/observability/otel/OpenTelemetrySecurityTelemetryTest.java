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
import java.util.concurrent.TimeUnit;
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
      assertThat(span.getStartEpochNanos())
          .isEqualTo(
              TimeUnit.MILLISECONDS.toNanos(
                  Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()));
      assertThat(span.getEndEpochNanos())
          .isEqualTo(
              TimeUnit.MILLISECONDS.toNanos(
                  Instant.parse("2026-03-01T12:00:00.012Z").toEpochMilli()));
      assertThat(span.getAttributes().asMap())
          .containsEntry(AttributeKey.stringKey("tracepermit.event"), "remote.edge.denial")
          .containsEntry(AttributeKey.stringKey("tracepermit.outcome"), "denied")
          .containsEntry(AttributeKey.stringKey("tracepermit.component"), "remote_edge")
          .containsEntry(AttributeKey.longKey("tracepermit.duration_nanos"), 12_000_000L)
          .doesNotContainKey(AttributeKey.stringKey("authorization"))
          .doesNotContainKey(AttributeKey.stringKey("arguments"))
          .doesNotContainKey(AttributeKey.stringKey("result"));
      var metrics = metricReader.collectAllMetrics();
      assertThat(metrics)
          .extracting(metric -> metric.getName())
          .containsExactlyInAnyOrder(
              "tracepermit.security.events", "tracepermit.security.operation.duration");

      var eventCount =
          metrics.stream()
              .filter(metric -> metric.getName().equals("tracepermit.security.events"))
              .findFirst()
              .orElseThrow()
              .getLongSumData()
              .getPoints()
              .iterator()
              .next();
      assertThat(eventCount.getValue()).isEqualTo(1L);
      assertThat(eventCount.getAttributes().asMap())
          .containsEntry(AttributeKey.stringKey("tracepermit.event"), "remote.edge.denial")
          .containsEntry(AttributeKey.stringKey("tracepermit.outcome"), "denied")
          .containsEntry(AttributeKey.stringKey("tracepermit.component"), "remote_edge")
          .containsEntry(AttributeKey.stringKey("tracepermit.stage"), "authentication")
          .containsEntry(
              AttributeKey.stringKey("tracepermit.reason_code"), "INVALID_ACCESS_TOKEN")
          .doesNotContainKey(AttributeKey.stringKey("authorization"))
          .doesNotContainKey(AttributeKey.stringKey("arguments"))
          .doesNotContainKey(AttributeKey.stringKey("result"));

      var duration =
          metrics.stream()
              .filter(
                  metric ->
                      metric.getName().equals("tracepermit.security.operation.duration"))
              .findFirst()
              .orElseThrow()
              .getHistogramData()
              .getPoints()
              .iterator()
              .next();
      assertThat(duration.getCount()).isEqualTo(1L);
      assertThat(duration.getSum()).isEqualTo(12.0);
      assertThat(duration.getAttributes()).isEqualTo(eventCount.getAttributes());
    }
  }
}
