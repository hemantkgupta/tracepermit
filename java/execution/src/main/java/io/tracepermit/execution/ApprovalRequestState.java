package io.tracepermit.execution;

import java.time.Instant;
import java.util.Objects;

/** Durable snapshot of an approval aggregate. Transition history remains append-only in storage. */
public record ApprovalRequestState(
    ApprovalBinding binding,
    String status,
    int transitionSequence,
    String approvalDecisionId,
    String reviewerSubjectId,
    String reviewerAuthenticationMethod,
    String reviewerCredentialFingerprint,
    String decisionReasonCode,
    Instant decidedAt,
    Instant updatedAt,
    String consumedPermitId,
    String consumedAttemptId) {
  public ApprovalRequestState {
    Objects.requireNonNull(binding);
    if (!java.util.Set.of("pending", "approved", "denied", "expired", "consumed").contains(status)) {
      throw new IllegalArgumentException("The approval status is invalid.");
    }
    if (transitionSequence < 1) {
      throw new IllegalArgumentException("The approval transition sequence is invalid.");
    }
    Objects.requireNonNull(updatedAt);
  }
}
