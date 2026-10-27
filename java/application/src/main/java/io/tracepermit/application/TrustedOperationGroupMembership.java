package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_GROUP_MEMBERSHIP_INVALID;

import java.time.Instant;

/**
 * Independently verified adapter enrollment facts, NEVER a request-body model or verification proof.
 * Exact source/principal/workspace/group and temporal bounds are rechecked by the review command.
 */
public record TrustedOperationGroupMembership(
    String tenantId, String workspaceId, String principalId, String groupId,
    String sourceFingerprint, Instant observedAt, Instant expiresAt) {
  public TrustedOperationGroupMembership {
    if (!identifier(tenantId, "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        || !identifier(workspaceId, "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        || !identifier(principalId, "[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}")
        || !identifier(groupId, "[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}")
        || !identifier(sourceFingerprint, "sha256:[a-f0-9]{64}")
        || observedAt == null || expiresAt == null || !expiresAt.isAfter(observedAt)) {
      throw new BusinessOperationStorageException(OPERATION_GROUP_MEMBERSHIP_INVALID);
    }
  }

  private static boolean identifier(String value, String pattern) {
    return value != null && value.matches(pattern);
  }

  @Override public String toString() {
    return "TrustedOperationGroupMembership[redacted; adapter facts, not proof]";
  }
}
