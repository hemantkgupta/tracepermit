package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.policy.PolicyEvaluation;

public record ExecutionResult(
    String outcome,
    PolicyEvaluation evaluation,
    ExecutionAttempt attempt,
    JsonNode result,
    JsonNode effectiveEvent,
    PolicyEvaluation effectiveEvaluation,
    SanitizationSummary sanitization) {
  public ExecutionResult(
      String outcome, PolicyEvaluation evaluation, ExecutionAttempt attempt, JsonNode result) {
    this(outcome, evaluation, attempt, result, null, evaluation, null);
  }

  public ExecutionResult {
    result = result == null ? null : result.deepCopy();
    effectiveEvent = effectiveEvent == null ? null : effectiveEvent.deepCopy();
  }

  @Override
  public JsonNode result() {
    return result == null ? null : result.deepCopy();
  }

  @Override
  public JsonNode effectiveEvent() {
    return effectiveEvent == null ? null : effectiveEvent.deepCopy();
  }
}
