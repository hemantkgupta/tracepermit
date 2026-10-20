package io.tracepermit.execution;

/** Pending-only owner withdrawal, atomically committed with its evidence and outbox. */
public interface DurableApprovalCancellationState extends DurableApprovalState {
  ApprovalRequestState cancel(ApprovalCancellation cancellation);
}
