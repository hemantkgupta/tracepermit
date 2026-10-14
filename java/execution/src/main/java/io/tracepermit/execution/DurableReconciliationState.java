package io.tracepermit.execution;

import java.time.Instant;
import java.util.List;

/** Durable lease and append-only completion port for unknown executions. */
public interface DurableReconciliationState {
  List<ReconciliationCandidate> claimUnknownBatch(
      TenantPartition tenant,
      String workerId,
      int maximumEntries,
      Instant claimedAt,
      Instant leaseExpiresAt);

  ExecutionAttempt completeReconciliation(
      ReconciliationCandidate candidate,
      String workerId,
      String outcome,
      String reasonCode,
      Instant occurredAt);

  void deferReconciliation(
      ReconciliationCandidate candidate,
      String workerId,
      String reasonCode,
      Instant nextAttemptAt);
}
