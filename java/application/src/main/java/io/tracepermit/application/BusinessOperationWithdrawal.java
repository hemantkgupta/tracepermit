package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import java.time.Instant;
import java.util.UUID;

/**
 * Closed internal withdrawal input, NOT an authentication proof or a public command-body schema.
 * The trusted adapter must verify identity/enrollment and derive workspace scope before construction.
 * This class intentionally accepts no proposed JSON, desired status, reason, clock or callback.
 */
public final class BusinessOperationWithdrawal {
  public static final String ROLE = "operation_requester";
  public static final String SCOPE = "operations.cancel";
  public static final String REASON = "REQUESTER_WITHDRAWN";
  private final BusinessOperationScope scope;
  private final String operationId;
  private final BusinessOperationTransitionPlanner.StateToken expected;
  private final TrustedRequesterContext requester;

  public BusinessOperationWithdrawal(
      BusinessOperationScope scope,
      String operationId,
      BusinessOperationTransitionPlanner.StateToken expected,
      TrustedRequesterContext requester) {
    try {
      String id = UUID.fromString(operationId).toString();
      if (!id.equalsIgnoreCase(operationId) || expected == null
          || !id.equalsIgnoreCase(expected.operationId())) throw error(OPERATION_COMMAND_INVALID);
      this.operationId = id;
    } catch (IllegalArgumentException | NullPointerException failure) {
      throw error(OPERATION_COMMAND_INVALID);
    }
    this.scope = scope;
    this.expected = expected;
    this.requester = requester;
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

  /** Rechecked with store time AFTER the operation lock, not with a body-supplied clock. */
  public void requireAuthorizedAt(Instant storeTime) {
    requireIdentity();
    if (storeTime == null || !requester.authorityExpiresAt().isAfter(storeTime)) {
      throw error(OPERATION_AUTHENTICATION_EXPIRED);
    }
  }

  /** Audit metadata only: credential fingerprint, never a credential or approval capability. */
  public ObjectNode actor() {
    ObjectNode actor = JsonSupport.MAPPER.createObjectNode();
    actor.put("scopeHash", scope.fingerprint());
    actor.put("principalId", scope.requesterPrincipalId());
    actor.put("authenticationMethod", requester.authenticationMethod());
    actor.put("credentialFingerprint", requester.credentialFingerprint());
    actor.put("authorityExpiresAt", requester.authorityExpiresAt().toString());
    return actor;
  }

  private void requireIdentity() {
    if (scope == null || requester == null || !requester.authenticated()
        || !scope.tenantId().equals(requester.tenant().value())
        || !scope.requesterPrincipalId().equals(requester.requesterSubjectId())
        || !requester.roles().contains(ROLE) || !requester.scopes().contains(SCOPE)) {
      throw error(OPERATION_REQUESTER_NOT_AUTHORIZED);
    }
  }

  @Override
  public String toString() {
    return "BusinessOperationWithdrawal[redacted; pending only; not target authority]";
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
