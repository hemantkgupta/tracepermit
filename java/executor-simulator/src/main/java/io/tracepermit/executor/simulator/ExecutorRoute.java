package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Operator-owned simulator adapter allowlist entry. */
record ExecutorRoute(
    String toolName,
    String targetSystem,
    String targetEnvironment,
    String targetAccountId) {

  ExecutorRoute {
    requireIdentifier(toolName, 128);
    requireIdentifier(targetSystem, 64);
    requireIdentifier(targetEnvironment, 16);
    requireIdentifier(targetAccountId, 128);
  }

  boolean allows(JsonNode event) {
    JsonNode action = event.path("action");
    JsonNode target = action.path("target");
    return toolName.equals(action.path("toolName").asText())
        && targetSystem.equals(target.path("system").asText())
        && targetEnvironment.equals(target.path("environment").asText())
        && targetAccountId.equals(target.path("accountId").asText());
  }

  private static void requireIdentifier(String value, int maximum) {
    Objects.requireNonNull(value, "route value");
    if (value.isBlank()
        || value.length() > maximum
        || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]*$")) {
      throw new IllegalArgumentException("INVALID_EXECUTOR_CONFIGURATION");
    }
  }
}
