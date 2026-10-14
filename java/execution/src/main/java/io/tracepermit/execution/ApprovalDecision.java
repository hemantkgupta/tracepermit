package io.tracepermit.execution;

import java.time.Instant;
import java.util.Objects;

/** Reviewer provenance derived only from a trusted application context. */
public record ApprovalDecision(
    TenantPartition tenant,
    String approvalRequestId,
    String approvalDecisionId,
    String reviewerSubjectId,
    String reviewerAuthenticationMethod,
    String reviewerCredentialFingerprint,
    String decision,
    String reasonCode,
    Instant decidedAt) {
  public ApprovalDecision {
    Objects.requireNonNull(tenant);
    DurableExecutionClaim.requireIdentifier(approvalRequestId, 256, "approval request ID");
    DurableExecutionClaim.requireIdentifier(approvalDecisionId, 256, "approval decision ID");
    DurableExecutionClaim.requireSubjectIdentifier(reviewerSubjectId, "reviewer subject ID");
    DurableExecutionClaim.requireIdentifier(
        reviewerAuthenticationMethod, 128, "reviewer authentication method");
    DurableExecutionClaim.requireHash(
        reviewerCredentialFingerprint, "reviewer credential fingerprint");
    if (!java.util.Set.of("approve", "deny").contains(decision)) {
      throw new IllegalArgumentException("The approval decision is invalid.");
    }
    Objects.requireNonNull(reasonCode);
    if (!reasonCode.matches("^[A-Z][A-Z0-9_]{0,127}$")) {
      throw new IllegalArgumentException("The approval reason code is invalid.");
    }
    Objects.requireNonNull(decidedAt);
  }
}
