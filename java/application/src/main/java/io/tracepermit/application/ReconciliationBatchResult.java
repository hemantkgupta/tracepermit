package io.tracepermit.application;

public record ReconciliationBatchResult(int claimed, int reconciled, int deferred) {
  public ReconciliationBatchResult {
    if (claimed < 0 || reconciled < 0 || deferred < 0 || reconciled + deferred != claimed) {
      throw new IllegalArgumentException("The reconciliation batch result is inconsistent.");
    }
  }
}
