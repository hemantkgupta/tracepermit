package io.tracepermit.policy;

import java.time.Instant;
import java.util.Objects;

public record RollbackRecord(
    String policyId,
    int fromVersion,
    int toVersion,
    Instant rolledBackAt,
    String reason) {

  public RollbackRecord {
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(rolledBackAt, "rolledBackAt must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
  }
}
