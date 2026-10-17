package io.tracepermit.execution;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * Storage port for approvals bound to a specific policy version and activation generation.
 */
public interface DurablePolicyBoundApprovalState extends DurableApprovalActionState {

  /**
   * Atomically creates a pending approval request with optional canonical action snapshot
   * and durable policy generation binding.
   */
  ApprovalRequestState createPendingBound(
      ApprovalBinding binding,
      JsonNode canonicalAction,
      ApprovalPolicyBinding policyBinding);

  /**
   * Retrieves the policy generation binding for an approval request, if recorded.
   */
  Optional<ApprovalPolicyBinding> findPolicyBinding(
      TenantPartition tenant,
      String approvalRequestId);
}
