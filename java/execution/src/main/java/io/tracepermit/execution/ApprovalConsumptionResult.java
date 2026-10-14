package io.tracepermit.execution;

import java.util.Objects;

public record ApprovalConsumptionResult(
    ApprovalConsumptionStatus status,
    ApprovalRequestState approval,
    ExecutionAttempt attempt) {
  public ApprovalConsumptionResult {
    Objects.requireNonNull(status);
    Objects.requireNonNull(approval);
    if (status == ApprovalConsumptionStatus.STARTED && attempt == null) {
      throw new IllegalArgumentException("A started approval consumption requires an attempt.");
    }
  }

  public boolean started() {
    return status == ApprovalConsumptionStatus.STARTED;
  }
}
