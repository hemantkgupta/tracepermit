package io.tracepermit.execution;

import java.time.Instant;
import java.util.Objects;

/** Immutable, tenant-scoped description of the exact request a human may approve. */
public record ApprovalBinding(
    TenantPartition tenant,
    String approvalRequestId,
    String requestHash,
    String actionHash,
    String eventId,
    String evaluationId,
    String subjectId,
    String actorLineageHash,
    String policyId,
    int policyVersion,
    String originalDecision,
    String toolName,
    String resourceHash,
    String approvalGroup,
    Instant createdAt,
    Instant expiresAt,
    int maxUses) {
  public ApprovalBinding {
    Objects.requireNonNull(tenant);
    DurableExecutionClaim.requireIdentifier(approvalRequestId, 256, "approval request ID");
    DurableExecutionClaim.requireHash(requestHash, "approval request hash");
    DurableExecutionClaim.requireHash(actionHash, "action hash");
    DurableExecutionClaim.requireIdentifier(eventId, 256, "event ID");
    DurableExecutionClaim.requireIdentifier(evaluationId, 256, "evaluation ID");
    DurableExecutionClaim.requireSubjectIdentifier(subjectId, "subject ID");
    DurableExecutionClaim.requireHash(actorLineageHash, "actor and lineage hash");
    DurableExecutionClaim.requireIdentifier(policyId, 256, "policy ID");
    if (policyVersion < 1) throw new IllegalArgumentException("The policy version is invalid.");
    if (!"require_approval".equals(originalDecision)) {
      throw new IllegalArgumentException("An approval must preserve a require_approval decision.");
    }
    DurableExecutionClaim.requireIdentifier(toolName, 256, "tool name");
    DurableExecutionClaim.requireHash(resourceHash, "resource hash");
    DurableExecutionClaim.requireIdentifier(approvalGroup, 128, "approval group");
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(expiresAt);
    if (!expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("The approval expiry must follow its creation time.");
    }
    if (maxUses != 1) throw new IllegalArgumentException("Approvals must be single use.");
  }
}
