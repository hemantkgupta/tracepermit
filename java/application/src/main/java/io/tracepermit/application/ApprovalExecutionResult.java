package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.policy.PolicyEvaluation;

public record ApprovalExecutionResult(
    String outcome,
    PolicyEvaluation evaluation,
    ApprovalRequestState approval,
    ExecutionAttempt attempt,
    JsonNode result) {
  public ApprovalExecutionResult {
    result = result == null ? null : result.deepCopy();
  }

  @Override
  public JsonNode result() {
    return result == null ? null : result.deepCopy();
  }
}
