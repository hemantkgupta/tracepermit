package io.tracepermit.execution;

import java.time.Instant;
import java.util.List;

/**
 * Transactional port for security-critical replay, execution, and evidence state.
 * Implementations must commit {@link #claim} before it returns STARTED.
 */
public interface DurableEnforcementState {
  DurableClaimResult claim(DurableExecutionClaim claim);

  /**
   * Atomically transitions an executing attempt and records its evidence. Unknown outcomes
   * must enqueue lookup-only reconciliation in the same transaction. Must reject an attempt
   * that is already succeeded, failed, or unknown without mutating it; only reconciliation
   * may resolve unknown to a confirmed outcome.
   * A thrown exception does not establish whether a transaction committed.
   */
  ExecutionAttempt complete(
      TenantPartition tenant,
      String attemptId,
      String outcome,
      String reasonCode,
      Instant occurredAt);

  ExecutionAttempt reconcile(
      TenantPartition tenant,
      String adapterId,
      String idempotencyKey,
      String outcome,
      String reasonCode,
      Instant occurredAt);

  ExecutionAttempt findByIdempotencyKey(
      TenantPartition tenant, String adapterId, String idempotencyKey);

  List<DurableEvidenceRecord> readEvidence(TenantPartition tenant);
}
