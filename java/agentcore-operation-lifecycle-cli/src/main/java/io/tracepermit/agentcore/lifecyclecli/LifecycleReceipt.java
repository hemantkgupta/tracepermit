package io.tracepermit.agentcore.lifecyclecli;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Fixed redacted installed lifecycle receipt; known target unknown differs from command UNKNOWN. */
record LifecycleReceipt(
    String command,
    String outcome,
    String operationId,
    String authorizationStatus,
    String executionStatus,
    Boolean dispatchStarted,
    String authorityProfileFingerprint,
    String expectedProfileFingerprint) {
  private static final Set<String> OUTCOMES =
      Set.of(
          "CONFIG_VALID",
          "OBSERVED",
          "EVALUATED",
          "REVIEW_APPROVED",
          "REVIEW_DENIED",
          "DELIVERY_SUCCEEDED",
          "DELIVERY_REJECTED",
          "DELIVERY_UNKNOWN",
          "RECONCILED",
          "INPUT_INVALID",
          "CONFIG_INVALID",
          "NOT_READY",
          "REJECTED",
          "UNKNOWN",
          "USAGE");
  private static final Set<String> SUCCESSES =
      Set.of(
          "CONFIG_VALID",
          "OBSERVED",
          "EVALUATED",
          "REVIEW_APPROVED",
          "REVIEW_DENIED",
          "DELIVERY_SUCCEEDED",
          "DELIVERY_REJECTED",
          "DELIVERY_UNKNOWN",
          "RECONCILED");

  LifecycleReceipt {
    if (!(LifecycleProtocol.COMMANDS.contains(command) || command.equals("UNKNOWN"))
        || !OUTCOMES.contains(outcome)) {
      throw new IllegalArgumentException("LIFECYCLE_RECEIPT_INVALID");
    }
    boolean operation = operationId != null;
    if (operation
        != (authorizationStatus != null
            && executionStatus != null
            && authorityProfileFingerprint != null
            && expectedProfileFingerprint != null)) {
      throw new IllegalArgumentException("LIFECYCLE_RECEIPT_INVALID");
    }
    if (!operation && dispatchStarted != null) {
      throw new IllegalArgumentException("LIFECYCLE_RECEIPT_INVALID");
    }
  }

  static LifecycleReceipt failure(String command, String outcome) {
    return new LifecycleReceipt(
        LifecycleProtocol.COMMANDS.contains(command) ? command : "UNKNOWN",
        outcome,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  static LifecycleReceipt state(
      String command,
      String outcome,
      String operationId,
      String authorization,
      String execution,
      Boolean dispatchStarted,
      LifecycleOperatorConfig configuration) {
    return new LifecycleReceipt(
        command,
        outcome,
        operationId,
        authorization,
        execution,
        dispatchStarted,
        configuration.authority().fingerprint(),
        configuration.expected().fingerprint());
  }

  int exitCode() {
    if (SUCCESSES.contains(outcome)) return 0;
    if (outcome.equals("USAGE")) return 64;
    return 1;
  }

  byte[] bytes() {
    var json = LifecycleProtocol.JSON.createObjectNode();
    json.put("schemaVersion", "1.0");
    json.put("profile", LifecycleProtocol.PROFILE);
    json.put("command", command);
    json.put("outcome", outcome);
    put(json, "operationId", operationId);
    put(json, "authorizationStatus", authorizationStatus);
    put(json, "executionStatus", executionStatus);
    if (dispatchStarted == null) json.putNull("dispatchStarted");
    else json.put("dispatchStarted", dispatchStarted);
    put(json, "authorityProfileFingerprint", authorityProfileFingerprint);
    put(json, "expectedProfileFingerprint", expectedProfileFingerprint);
    json.put("executionEnabled", false);
    json.put("automaticRetryAllowed", false);
    byte[] result = (json.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    if (result.length > LifecycleProtocol.RECEIPT_BYTES) {
      throw new IllegalStateException("LIFECYCLE_RECEIPT_TOO_LARGE");
    }
    return result;
  }

  static LifecycleReceipt decode(byte[] bytes, int exit) {
    try {
      var json = LifecycleProtocol.object(bytes);
      LifecycleProtocol.fields(
          json,
          Set.of(
              "schemaVersion",
              "profile",
              "command",
              "outcome",
              "operationId",
              "authorizationStatus",
              "executionStatus",
              "dispatchStarted",
              "authorityProfileFingerprint",
              "expectedProfileFingerprint",
              "executionEnabled",
              "automaticRetryAllowed"),
          Set.of());
      if (!"1.0".equals(json.path("schemaVersion").asText())
          || !LifecycleProtocol.PROFILE.equals(json.path("profile").asText())
          || !json.path("executionEnabled").isBoolean()
          || json.path("executionEnabled").booleanValue()
          || !json.path("automaticRetryAllowed").isBoolean()
          || json.path("automaticRetryAllowed").booleanValue()) {
        return failure("UNKNOWN", "UNKNOWN");
      }
      LifecycleReceipt receipt =
          new LifecycleReceipt(
              json.path("command").asText(),
              json.path("outcome").asText(),
              nullableText(json, "operationId"),
              nullableText(json, "authorizationStatus"),
              nullableText(json, "executionStatus"),
              nullableBoolean(json, "dispatchStarted"),
              nullableText(json, "authorityProfileFingerprint"),
              nullableText(json, "expectedProfileFingerprint"));
      return receipt.exitCode() == exit ? receipt : failure("UNKNOWN", "UNKNOWN");
    } catch (RuntimeException invalid) {
      return failure("UNKNOWN", "UNKNOWN");
    }
  }

  private static void put(
      com.fasterxml.jackson.databind.node.ObjectNode json, String field, String value) {
    if (value == null) json.putNull(field);
    else json.put(field, value);
  }

  private static String nullableText(com.fasterxml.jackson.databind.JsonNode json, String field) {
    var value = json.path(field);
    if (value.isNull()) return null;
    if (!value.isTextual()) throw new IllegalArgumentException("LIFECYCLE_RECEIPT_INVALID");
    return value.textValue();
  }

  private static Boolean nullableBoolean(com.fasterxml.jackson.databind.JsonNode json, String field) {
    var value = json.path(field);
    if (value.isNull()) return null;
    if (!value.isBoolean()) throw new IllegalArgumentException("LIFECYCLE_RECEIPT_INVALID");
    return value.booleanValue();
  }
}
