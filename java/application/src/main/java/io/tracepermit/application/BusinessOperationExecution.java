package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import java.time.Instant;
import java.util.UUID;

/** Trusted adapter command, not a public body schema, verified permit or execution capability. */
public final class BusinessOperationExecution {
  private final BusinessOperationScope scope;
  private final String operationId;
  private final BusinessOperationTransitionPlanner.StateToken expected;
  private final TrustedRequesterContext identity;
  private final String workspaceId;

  public BusinessOperationExecution(BusinessOperationScope scope, String operationId,
      BusinessOperationTransitionPlanner.StateToken expected, TrustedRequesterContext identity,
      String workspaceId) {
    try {
      String id = UUID.fromString(operationId).toString();
      if (!id.equals(operationId) || expected == null || !id.equals(expected.operationId())) throw new IllegalArgumentException();
      this.operationId = id;
    } catch (RuntimeException failure) {
      throw error(OPERATION_COMMAND_INVALID);
    }
    this.scope = scope;
    this.expected = expected;
    this.identity = identity;
    this.workspaceId = workspaceId;
    requireIdentity();
  }

  public BusinessOperationScope scope() { return scope; }
  public String operationId() { return operationId; }
  public BusinessOperationTransitionPlanner.StateToken expected() { return expected; }

  public void requireAuthorizedAt(BusinessOperationAuthorityProfile profile, Instant writerTime) {
    requireIdentity();
    if (profile == null || !scope.equals(profile.scope()) || profile.recoveryGeneration() != expected.recoveryGeneration()) {
      throw error(OPERATION_PROFILE_MISMATCH);
    }
    profile.admissionSettings();
    if (writerTime == null || !identity.authorityExpiresAt().isAfter(writerTime)) throw error(OPERATION_AUTHENTICATION_EXPIRED);
  }

  public ObjectNode actor() {
    return JsonSupport.MAPPER.createObjectNode().put("scopeHash", scope.fingerprint())
        .put("principalId", identity.requesterSubjectId()).put("authenticationMethod", identity.authenticationMethod())
        .put("credentialFingerprint", identity.credentialFingerprint())
        .put("authorityExpiresAt", identity.authorityExpiresAt().toString());
  }

  private void requireIdentity() {
    if (scope == null || identity == null || !identity.authenticated()
        || !scope.tenantId().equals(identity.tenant().value()) || !scope.workspaceId().equals(workspaceId)
        || !scope.requesterPrincipalId().equals(identity.requesterSubjectId())
        || !identity.roles().contains("operation_requester") || !identity.scopes().contains("operations.execute")) {
      throw error(OPERATION_EXECUTOR_NOT_AUTHORIZED);
    }
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }

  @Override public String toString() { return "BusinessOperationExecution[redacted; uncommitted; not authority]"; }
}
