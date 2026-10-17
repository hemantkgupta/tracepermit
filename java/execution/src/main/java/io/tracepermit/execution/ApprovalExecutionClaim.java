package io.tracepermit.execution;

import java.time.Instant;
import java.util.Objects;

/** Approval consumption and execution claim that must commit as one transaction. */
public record ApprovalExecutionClaim(
    ApprovalBinding binding,
    String approvalDecisionId,
    DurableExecutionClaim executionClaim,
    Instant occurredAt,
    ApprovalPolicyBinding policyBinding) {

  public ApprovalExecutionClaim(
      ApprovalBinding binding,
      String approvalDecisionId,
      DurableExecutionClaim executionClaim,
      Instant occurredAt) {
    this(binding, approvalDecisionId, executionClaim, occurredAt, null);
  }

  public ApprovalExecutionClaim {
    Objects.requireNonNull(binding);
    DurableExecutionClaim.requireIdentifier(approvalDecisionId, 256, "approval decision ID");
    Objects.requireNonNull(executionClaim);
    Objects.requireNonNull(occurredAt);
    if (!binding.tenant().equals(executionClaim.tenant())
        || !binding.actionHash().equals(executionClaim.actionHash())
        || !binding.eventId().equals(executionClaim.eventId())
        || !binding.evaluationId().equals(executionClaim.evaluationId())
        || !binding.subjectId().equals(executionClaim.subjectId())
        || !binding.policyId().equals(executionClaim.policyId())
        || binding.policyVersion() != executionClaim.policyVersion()) {
      throw new IllegalArgumentException("The approval and execution claim bindings differ.");
    }
    if (policyBinding != null) {
      if (!binding.policyId().equals(policyBinding.policyId())
          || binding.policyVersion() != policyBinding.policyVersion()) {
        throw new IllegalArgumentException("The approval policy binding and approval request policy differ.");
      }
    }
  }
}
