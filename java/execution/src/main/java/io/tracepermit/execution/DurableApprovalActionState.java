package io.tracepermit.execution;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Exact, immutable action snapshots stored atomically with pending approval. Not an audit payload. */
public interface DurableApprovalActionState extends DurableApprovalState {
  ApprovalRequestState createPending(ApprovalBinding binding, JsonNode canonicalAction);

  JsonNode findAction(TenantPartition tenant, String approvalRequestId);

  List<ApprovalRequestState> listApprovals(TenantPartition tenant, String requesterSubjectId, int limit);
}
