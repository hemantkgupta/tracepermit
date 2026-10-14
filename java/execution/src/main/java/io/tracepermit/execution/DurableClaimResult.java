package io.tracepermit.execution;

import java.util.Objects;

public record DurableClaimResult(DurableClaimStatus status, ExecutionAttempt attempt) {
  public DurableClaimResult {
    Objects.requireNonNull(status);
    Objects.requireNonNull(attempt);
  }

  public boolean started() {
    return status == DurableClaimStatus.STARTED;
  }
}
