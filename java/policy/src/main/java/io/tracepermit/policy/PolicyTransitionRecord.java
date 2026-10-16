package io.tracepermit.policy;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit record of a policy lifecycle transition (activation or rollback).
 *
 * <p>The {@code actorId} field is caller-supplied tracking context and does not represent
 * authenticated operator identity or cryptographic non-repudiation.
 */
public record PolicyTransitionRecord(
    String transitionId,
    String workspaceId,
    String policyId,
    long transitionSequence,
    String transitionType,
    Integer fromVersion,
    int toVersion,
    Instant occurredAt,
    String actorId,
    String reason) {

  public PolicyTransitionRecord {
    Objects.requireNonNull(transitionId, "transitionId must not be null");
    Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(transitionType, "transitionType must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    if (!"activate".equals(transitionType) && !"rollback".equals(transitionType)) {
      throw new IllegalArgumentException("Invalid transitionType: " + transitionType);
    }
    if (transitionSequence < 1) {
      throw new IllegalArgumentException("transitionSequence must be positive");
    }
    if (toVersion < 1) {
      throw new IllegalArgumentException("toVersion must be positive");
    }
    if (fromVersion != null && fromVersion < 1) {
      throw new IllegalArgumentException("fromVersion must be positive");
    }
  }
}
