package io.tracepermit.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record ExecutionAttempt(
    String attemptId,
    String eventId,
    String permitId,
    String idempotencyKey,
    String status,
    Instant startedAt,
    Instant updatedAt,
    List<ExecutionTransition> transitions,
    JsonNode result) {
  public ExecutionAttempt {
    transitions = List.copyOf(transitions);
    result = result == null ? null : result.deepCopy();
  }

  @Override
  public JsonNode result() {
    return result == null ? null : result.deepCopy();
  }
}
