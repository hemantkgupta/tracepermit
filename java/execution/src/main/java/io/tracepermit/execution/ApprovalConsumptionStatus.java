package io.tracepermit.execution;

public enum ApprovalConsumptionStatus {
  STARTED,
  APPROVAL_EXPIRED,
  APPROVAL_REPLAYED,
  IDEMPOTENCY_DUPLICATE,
  STALE_POLICY_GENERATION
}
