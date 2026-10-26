package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;

import java.time.Instant;
import java.util.UUID;

/** Closed evaluation input, NOT policy outcome, current-state proof, a committed fact or admission. */
public final class BusinessOperationEvaluation {
  public static final String ROLE = "operation_requester";
  public static final String SCOPE = "operations.evaluate";
  private final BusinessOperationScope scope;
  private final String operationId;
  private final BusinessOperationTransitionPlanner.StateToken expected;
  private final TrustedOperationEvaluatorContext evaluator;

  public BusinessOperationEvaluation(BusinessOperationScope scope, String operationId,
      BusinessOperationTransitionPlanner.StateToken expected, TrustedOperationEvaluatorContext evaluator) {
    try {
      String id = UUID.fromString(operationId).toString();
      if (!id.equalsIgnoreCase(operationId) || expected == null || !id.equalsIgnoreCase(expected.operationId())) {
        throw new IllegalArgumentException();
      }
      this.operationId = id;
    } catch (RuntimeException failure) {
      throw error(OPERATION_COMMAND_INVALID);
    }
    this.scope = scope;
    this.expected = expected;
    this.evaluator = evaluator;
    requireIdentity();
  }

  public BusinessOperationScope scope() { return scope; }
  public String operationId() { return operationId; }
  public BusinessOperationTransitionPlanner.StateToken expected() { return expected; }

  /** Fingerprinted identity metadata only, never a committed evaluation or permission proof. */
  public com.fasterxml.jackson.databind.node.ObjectNode actor() {
    var identity = evaluator.identity();
    return io.tracepermit.contracts.JsonSupport.MAPPER.createObjectNode()
        .put("scopeHash", scope.fingerprint()).put("principalId", identity.requesterSubjectId())
        .put("authenticationMethod", identity.authenticationMethod())
        .put("credentialFingerprint", identity.credentialFingerprint())
        .put("authorityExpiresAt", identity.authorityExpiresAt().toString());
  }

  /** Future transaction owner must repeat AFTER all locks; profile settings cannot default to v1. */
  public void requireAuthorizedAt(BusinessOperationAuthorityProfile profile, Instant writerTime) {
    requireIdentity();
    if (profile == null || !scope.equals(profile.scope()) || expected.recoveryGeneration() != profile.recoveryGeneration()) {
      throw error(OPERATION_PROFILE_MISMATCH);
    }
    profile.reviewSettings();
    if (writerTime == null || !evaluator.identity().authorityExpiresAt().isAfter(writerTime)) {
      throw error(OPERATION_AUTHENTICATION_EXPIRED);
    }
  }

  private void requireIdentity() {
    if (scope == null || evaluator == null) throw error(OPERATION_EVALUATOR_NOT_AUTHORIZED);
    var identity = evaluator.identity();
    if (!identity.authenticated() || !scope.tenantId().equals(identity.tenant().value())
        || !scope.workspaceId().equals(evaluator.workspaceId())
        || !scope.requesterPrincipalId().equals(identity.requesterSubjectId())
        || !identity.roles().contains(ROLE) || !identity.scopes().contains(SCOPE)) {
      throw error(OPERATION_EVALUATOR_NOT_AUTHORIZED);
    }
  }

  @Override public String toString() {
    return "BusinessOperationEvaluation[redacted; uncommitted; not authority]";
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
