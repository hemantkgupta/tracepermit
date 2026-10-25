package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import java.time.Instant;
import java.util.UUID;

/**
 * Closed internal independent-human review input. This is NOT a committed approval, a permit or a
 * public body schema. A future transaction owner must load the exact pending approval, recheck its
 * deadline/current policy/recovery state, and check this command after ALL authority locks. There is
 * deliberately no proposed JSON, arbitrary status/reason, approval deadline, caller clock or port.
 */
public final class BusinessOperationReview {
  public static final String ROLE = "operation_reviewer";
  public static final String SCOPE = "operations.decide";

  public enum Decision {
    APPROVE, DENY
  }

  private final BusinessOperationScope scope;
  private final String operationId;
  private final BusinessOperationTransitionPlanner.StateToken expected;
  private final TrustedOperationReviewerContext reviewer;
  private final Decision decision;

  public BusinessOperationReview(BusinessOperationScope scope, String operationId,
      BusinessOperationTransitionPlanner.StateToken expected,
      TrustedOperationReviewerContext reviewer, Decision decision) {
    try {
      String id = UUID.fromString(operationId).toString();
      if (!id.equalsIgnoreCase(operationId) || expected == null
          || !id.equalsIgnoreCase(expected.operationId()) || decision == null) {
        throw error(OPERATION_COMMAND_INVALID);
      }
      this.operationId = id;
    } catch (IllegalArgumentException | NullPointerException failure) {
      throw error(OPERATION_COMMAND_INVALID);
    }
    this.scope = scope;
    this.expected = expected;
    this.reviewer = reviewer;
    this.decision = decision;
    requireIdentity();
  }

  public BusinessOperationScope scope() {
    return scope;
  }

  public String operationId() {
    return operationId;
  }

  public BusinessOperationTransitionPlanner.StateToken expected() {
    return expected;
  }

  public Decision decision() {
    return decision;
  }

  /** Legacy identity-only check, NOT complete group authorization or a durable decision gate. */
  public void requireAuthorizedAt(Instant storeTime) {
    requireIdentity();
    if (storeTime == null || !reviewer.authorityExpiresAt().isAfter(storeTime)) {
      throw error(OPERATION_AUTHENTICATION_EXPIRED);
    }
  }

  /** Full approved group input check, repeated using writer time AFTER all authority locks. */
  public void requireGroupAuthorizedAt(BusinessOperationAuthorityProfile profile,
      TrustedOperationGroupMembership membership, Instant storeTime) {
    requireAuthorizedAt(storeTime);
    if (profile == null || !scope.equals(profile.scope())
        || expected.recoveryGeneration() != profile.recoveryGeneration()) throw error(OPERATION_PROFILE_MISMATCH);
    var settings = profile.reviewSettings();
    if (membership == null || !scope.tenantId().equals(membership.tenantId())
        || !scope.workspaceId().equals(membership.workspaceId())
        || !reviewer.identity().reviewerSubjectId().equals(membership.principalId())
        || !settings.reviewerGroupId().equals(membership.groupId())
        || !settings.membershipSourceFingerprint().equals(membership.sourceFingerprint())
        || membership.expiresAt().isAfter(reviewer.authorityExpiresAt())) {
      throw error(OPERATION_GROUP_MEMBERSHIP_INVALID);
    }
    if (membership.observedAt().isAfter(storeTime) || !membership.expiresAt().isAfter(storeTime)) {
      throw error(OPERATION_GROUP_MEMBERSHIP_EXPIRED);
    }
  }

  /** Fingerprinted metadata only; this record cannot attest that review committed. */
  public ObjectNode actor() {
    var identity = reviewer.identity();
    ObjectNode actor = JsonSupport.MAPPER.createObjectNode();
    actor.put("scopeHash", scope.fingerprint());
    actor.put("principalId", identity.reviewerSubjectId());
    actor.put("principalType", identity.principalType());
    actor.put("authenticationMethod", identity.authenticationMethod());
    actor.put("credentialFingerprint", identity.credentialFingerprint());
    actor.put("authorityExpiresAt", reviewer.authorityExpiresAt().toString());
    return actor;
  }

  private void requireIdentity() {
    if (scope == null || reviewer == null) throw error(OPERATION_REVIEWER_NOT_AUTHORIZED);
    var identity = reviewer.identity();
    if (!identity.authenticated() || !"human".equals(identity.principalType())
        || !scope.tenantId().equals(identity.tenant().value())
        || !scope.workspaceId().equals(reviewer.workspaceId())
        || scope.requesterPrincipalId().equals(identity.reviewerSubjectId())
        || !identity.roles().contains(ROLE) || !identity.scopes().contains(SCOPE)) {
      throw error(OPERATION_REVIEWER_NOT_AUTHORIZED);
    }
  }

  @Override
  public String toString() {
    return "BusinessOperationReview[redacted; uncommitted; not execution authority]";
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
