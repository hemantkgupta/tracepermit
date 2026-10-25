package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_EVALUATOR_NOT_AUTHORIZED;

/** Adapter-derived requester enrollment, not deserializable authorization or token verification. */
public record TrustedOperationEvaluatorContext(TrustedRequesterContext identity, String workspaceId) {
  public TrustedOperationEvaluatorContext {
    if (identity == null || workspaceId == null || !workspaceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new BusinessOperationStorageException(OPERATION_EVALUATOR_NOT_AUTHORIZED);
    }
  }

  @Override public String toString() {
    return "TrustedOperationEvaluatorContext[redacted; adapter facts, not proof]";
  }
}
