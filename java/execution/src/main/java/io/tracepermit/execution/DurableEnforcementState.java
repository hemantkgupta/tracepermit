package io.tracepermit.execution;

import java.time.Instant;
import java.util.List;

/**
 * Transactional port for security-critical replay, execution, and evidence state.
 * Implementations must commit {@link #claim} before it returns STARTED.
 */
public interface DurableEnforcementState {
  DurableClaimResult claim(DurableExecutionClaim claim);

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
