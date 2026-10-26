package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_REVIEWER_NOT_AUTHORIZED;

import java.time.Instant;

/**
 * Adapter-derived operation-workspace enrollment and deadline, not an authentication proof.
 * Construction does not verify a token, current membership or an identity provider. Never bind this
 * type from a request body. The adapter must independently verify and bound all supplied facts.
 */
public record TrustedOperationReviewerContext(
    TrustedReviewerContext identity, String workspaceId, Instant authorityExpiresAt) {
  public TrustedOperationReviewerContext {
    if (identity == null || workspaceId == null
        || !workspaceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        || authorityExpiresAt == null) {
      throw new BusinessOperationStorageException(OPERATION_REVIEWER_NOT_AUTHORIZED);
    }
  }

  @Override
  public String toString() {
    return "TrustedOperationReviewerContext[redacted; adapter facts, not proof]";
  }
}
