package io.tracepermit.application;

/** Emits bounded security events without accepting arbitrary log messages or payloads. */
@FunctionalInterface
public interface SecurityTelemetry {
  void record(SecurityTelemetryEvent event);

  static SecurityTelemetry discardForExplicitDevelopmentTests() {
    return ignored -> {};
  }
}
