package io.tracepermit.execution;

import java.time.Instant;
import java.util.Set;

/** Bounded metadata selectors, never tenant/subject authority or an execution capability. */
public record ApprovalHistoryQuery(
    int limit, String status, String toolName, String approvalRequestId,
    Instant beforeCreatedAt, String afterRequestId) {
  public ApprovalHistoryQuery {
    if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid history limit.");
    if (status != null && !Set.of("pending", "approved", "denied", "expired", "consumed", "cancelled").contains(status)) {
      throw new IllegalArgumentException("Invalid history status.");
    }
    if (toolName != null) DurableExecutionClaim.requireIdentifier(toolName, 256, "tool name");
    if (approvalRequestId != null) DurableExecutionClaim.requireIdentifier(approvalRequestId, 256, "approval ID");
    if ((beforeCreatedAt == null) != (afterRequestId == null)) {
      throw new IllegalArgumentException("Incomplete history position.");
    }
    if (afterRequestId != null) {
      DurableExecutionClaim.requireIdentifier(afterRequestId, 256, "history position ID");
      if (beforeCreatedAt.getNano() % 1000 != 0
          || beforeCreatedAt.getEpochSecond() < -62135596800L
          || beforeCreatedAt.getEpochSecond() > 253402300799L) {
        throw new IllegalArgumentException("Invalid history position time.");
      }
    }
  }
}
