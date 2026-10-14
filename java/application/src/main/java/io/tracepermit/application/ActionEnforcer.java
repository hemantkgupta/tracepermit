package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;

/** Application boundary for enforcing one already normalized canonical action. */
@FunctionalInterface
public interface ActionEnforcer {
  ExecutionResult execute(JsonNode event);
}
