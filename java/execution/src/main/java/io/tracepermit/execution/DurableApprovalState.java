package io.tracepermit.execution;

/** Persistence-neutral boundary for durable reviewer decisions and atomic approval consumption. */
public interface DurableApprovalState {
  ApprovalRequestState createPending(ApprovalBinding binding);

  ApprovalRequestState find(TenantPartition tenant, String approvalRequestId);

  ApprovalRequestState decide(ApprovalDecision decision);

  ApprovalConsumptionResult consumeAndClaim(ApprovalExecutionClaim claim);
}
