package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.application.ExecutionResult;

/**
 * Receives a bounded post-enforcement notification for non-authoritative read projections.
 * Observer failures must never change the enforcement result or trigger another target call.
 */
@FunctionalInterface
public interface McpOutcomeObserver {
  McpOutcomeObserver NONE = (event, result, latencyMs) -> {};

  void completed(JsonNode event, ExecutionResult result, long latencyMs);
}
