package io.tracepermit.identity;

import java.time.Instant;
import java.util.Objects;

/** Adapter-owned schedule, tenant, request, and authority mapping; never populated from a body. */
public record TrustedScheduledActionContext(
    String tenantId,
    String schedulerId,
    String scheduleId,
    String runId,
    String eventId,
    String traceId,
    String audience,
    String requiredScope,
    String requiredResource,
    boolean requireDelegation,
    Instant observedAt) {
  public TrustedScheduledActionContext {
    requireIdentifier(tenantId, 128, "tenant ID");
    requireIdentifier(schedulerId, 256, "scheduler ID");
    requireIdentifier(scheduleId, 256, "schedule ID");
    requireIdentifier(runId, 256, "run ID");
    requireIdentifier(eventId, 256, "event ID");
    if (!traceId.matches("^[a-f0-9]{32}$")) {
      throw new IllegalArgumentException("The trace ID is invalid.");
    }
    requireIdentifier(audience, 256, "audience");
    requireIdentifier(requiredScope, 256, "required scope");
    requireIdentifier(requiredResource, 512, "required resource");
    Objects.requireNonNull(observedAt);
  }

  private static void requireIdentifier(String value, int maximum, String label) {
    Objects.requireNonNull(value);
    if (value.isEmpty()
        || value.length() > maximum
        || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]*$")) {
      throw new IllegalArgumentException("The " + label + " is invalid.");
    }
  }
}
