package io.tracepermit.observability.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.tracepermit.application.SecurityTelemetry;
import io.tracepermit.application.SecurityTelemetryEvent;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** OpenTelemetry projection of the bounded application event; no raw payload API is exposed. */
public final class OpenTelemetrySecurityTelemetry implements SecurityTelemetry {
  private static final String INSTRUMENTATION_SCOPE = "io.tracepermit.security";

  private final Tracer tracer;
  private final LongCounter eventCounter;
  private final DoubleHistogram durationHistogram;

  public OpenTelemetrySecurityTelemetry(OpenTelemetry openTelemetry) {
    Objects.requireNonNull(openTelemetry);
    this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    var meter = openTelemetry.getMeter(INSTRUMENTATION_SCOPE);
    this.eventCounter =
        meter.counterBuilder("tracepermit.security.events")
            .setDescription("Count of bounded TracePermit security events")
            .build();
    this.durationHistogram =
        meter.histogramBuilder("tracepermit.security.operation.duration")
            .setUnit("ms")
            .setDescription("Duration of bounded TracePermit security operations")
            .build();
  }

  @Override
  public void record(SecurityTelemetryEvent event) {
    Objects.requireNonNull(event);
    Attributes attributes = attributes(event);
    eventCounter.add(1, attributes);
    durationHistogram.record(event.duration().toNanos() / 1_000_000.0, attributes);

    Span span =
        tracer
            .spanBuilder("tracepermit." + event.type().wireName())
            .setStartTimestamp(event.observedAt().toEpochMilli(), TimeUnit.MILLISECONDS)
            .startSpan();
    try {
      attributes.forEach(
          (key, value) -> span.setAttribute(key.getKey(), Objects.toString(value)));
      span.setAttribute("tracepermit.duration_nanos", event.duration().toNanos());
      if (event.outcome() == SecurityTelemetryEvent.Outcome.FAILED
          || event.outcome() == SecurityTelemetryEvent.Outcome.DENIED) {
        span.setStatus(StatusCode.ERROR);
      }
    } finally {
      span.end(
          event.observedAt().plus(event.duration()).toEpochMilli(), TimeUnit.MILLISECONDS);
    }
  }

  private static Attributes attributes(SecurityTelemetryEvent event) {
    AttributesBuilder attributes = Attributes.builder();
    attributes.put("tracepermit.event", event.type().wireName());
    attributes.put("tracepermit.outcome", event.outcome().wireName());
    event.attributes().forEach((key, value) -> attributes.put("tracepermit." + key, value));
    return attributes.build();
  }
}
