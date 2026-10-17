package io.tracepermit.policy;

import java.time.Instant;
import java.util.Objects;

/**
 * Record of a policy rollback transition.
 *
 * <p>The {@code actorId} field is caller-supplied tracking context and does not represent
 * authenticated operator identity or cryptographic non-repudiation.
 */
public record RollbackRecord(
    String workspaceId,
    String policyId,
    int fromVersion,
    int toVersion,
    Instant rolledBackAt,
    String actorId,
    String reason) {

  public RollbackRecord {
    Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(rolledBackAt, "rolledBackAt must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
  }

  public RollbackRecord(
      String policyId,
      int fromVersion,
      int toVersion,
      Instant rolledBackAt,
      String reason) {
    this("default", policyId, fromVersion, toVersion, rolledBackAt, null, reason);
  }
}
