package io.tracepermit.agentcore.organizationcli;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exact redacted receipt union; known delivery uncertainty remains distinct from command UNKNOWN.
 */
record OrganizationOperatorReceipt(
    String command,
    String outcome,
    Boolean created,
    String operationId,
    String authorizationStatus,
    String executionStatus,
    Boolean dispatchStarted,
    String authorityProfileFingerprint,
    String expectedProfileFingerprint) {
  private static final Set<String> FAILURES =
      Set.of("INPUT_INVALID", "CONFIG_INVALID", "NOT_READY", "REJECTED", "UNKNOWN", "USAGE");
  private static final Map<String, Set<String>> SUCCESSES =
      Map.of(
          "check-config", Set.of("CONFIG_VALID"),
          "register", Set.of("CREATED", "EXISTING"),
          "inspect", Set.of("OBSERVED"),
          "evaluate", Set.of("EVALUATED"),
          "review", Set.of("REVIEW_APPROVED", "REVIEW_DENIED"),
          "deliver-synthetic",
              Set.of("DELIVERY_SUCCEEDED", "DELIVERY_REJECTED", "DELIVERY_UNKNOWN"),
          "reconcile", Set.of("RECONCILED"));

  OrganizationOperatorReceipt {
    if (!(OrganizationOperatorProtocol.COMMANDS.contains(command) || command.equals("UNKNOWN"))
        || !(FAILURES.contains(outcome)
            || SUCCESSES.getOrDefault(command, Set.of()).contains(outcome))) {
      throw invalid();
    }
    if (outcome.equals("USAGE") != command.equals("UNKNOWN")
        || (command.equals("UNKNOWN") && !Set.of("USAGE", "UNKNOWN").contains(outcome))) {
      throw invalid();
    }
    boolean registration = Set.of("CREATED", "EXISTING").contains(outcome);
    boolean lifecycle =
        Set.of(
                "OBSERVED",
                "EVALUATED",
                "REVIEW_APPROVED",
                "REVIEW_DENIED",
                "DELIVERY_SUCCEEDED",
                "DELIVERY_REJECTED",
                "DELIVERY_UNKNOWN",
                "RECONCILED")
            .contains(outcome);
    if (registration
        != (created != null
            && operationId != null
            && authorizationStatus == null
            && executionStatus == null
            && dispatchStarted == null
            && authorityProfileFingerprint == null
            && expectedProfileFingerprint == null)) {
      throw invalid();
    }
    if (lifecycle
        != (created == null
            && operationId != null
            && authorizationStatus != null
            && executionStatus != null
            && dispatchStarted != null
            && authorityProfileFingerprint != null
            && expectedProfileFingerprint != null)) {
      throw invalid();
    }
    if (!registration
        && !lifecycle
        && (created != null
            || operationId != null
            || authorizationStatus != null
            || executionStatus != null
            || dispatchStarted != null
            || authorityProfileFingerprint != null
            || expectedProfileFingerprint != null)) {
      throw invalid();
    }
    if (registration && created.booleanValue() != outcome.equals("CREATED")) throw invalid();
    if ((registration || lifecycle) && !canonicalUuid(operationId)) throw invalid();
    if (lifecycle
        && (!boundedState(authorizationStatus)
            || !boundedState(executionStatus)
            || !hash(authorityProfileFingerprint)
            || !hash(expectedProfileFingerprint))) {
      throw invalid();
    }
  }

  static OrganizationOperatorReceipt failure(String command, String outcome) {
    return new OrganizationOperatorReceipt(
        OrganizationOperatorProtocol.COMMANDS.contains(command) ? command : "UNKNOWN",
        outcome,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  static OrganizationOperatorReceipt registration(boolean created, String operationId) {
    return new OrganizationOperatorReceipt(
        "register",
        created ? "CREATED" : "EXISTING",
        created,
        operationId,
        null,
        null,
        null,
        null,
        null);
  }

  static OrganizationOperatorReceipt state(
      String command,
      String outcome,
      String operationId,
      String authorization,
      String execution,
      boolean dispatchStarted,
      OrganizationOperatorConfig configuration) {
    return new OrganizationOperatorReceipt(
        command,
        outcome,
        null,
        operationId,
        authorization,
        execution,
        dispatchStarted,
        configuration.authorityProfile().fingerprint(),
        configuration.expectedProfile().fingerprint());
  }

  int exitCode() {
    if (SUCCESSES.getOrDefault(command, Set.of()).contains(outcome)) return 0;
    if (outcome.equals("USAGE")) return 64;
    return 1;
  }

  byte[] bytes() {
    var json = OrganizationOperatorProtocol.JSON.createObjectNode();
    json.put("schemaVersion", "1.0");
    json.put("profile", OrganizationOperatorProtocol.PROFILE);
    json.put("command", command);
    json.put("outcome", outcome);
    put(json, "created", created);
    put(json, "operationId", operationId);
    put(json, "authorizationStatus", authorizationStatus);
    put(json, "executionStatus", executionStatus);
    put(json, "dispatchStarted", dispatchStarted);
    put(json, "authorityProfileFingerprint", authorityProfileFingerprint);
    put(json, "expectedProfileFingerprint", expectedProfileFingerprint);
    json.put("executionEnabled", false);
    json.put("automaticRetryAllowed", false);
    byte[] result = (json.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    if (result.length > OrganizationOperatorProtocol.RECEIPT_BYTES) {
      throw new IllegalStateException("ORGANIZATION_OPERATOR_RECEIPT_TOO_LARGE");
    }
    return result;
  }

  static OrganizationOperatorReceipt decode(byte[] bytes, int exit) {
    try {
      var json = OrganizationOperatorProtocol.object(bytes);
      OrganizationOperatorProtocol.fields(
          json,
          Set.of(
              "schemaVersion",
              "profile",
              "command",
              "outcome",
              "created",
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
          || !OrganizationOperatorProtocol.PROFILE.equals(json.path("profile").asText())
          || !json.path("executionEnabled").isBoolean()
          || json.path("executionEnabled").booleanValue()
          || !json.path("automaticRetryAllowed").isBoolean()
          || json.path("automaticRetryAllowed").booleanValue()) {
        return failure("UNKNOWN", "UNKNOWN");
      }
      var receipt =
          new OrganizationOperatorReceipt(
              requiredText(json, "command"),
              requiredText(json, "outcome"),
              nullableBoolean(json, "created"),
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

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("ORGANIZATION_OPERATOR_RECEIPT_INVALID");
  }

  private static boolean canonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static boolean boundedState(String value) {
    return value != null && value.matches("[a-z][a-z0-9_]{0,63}");
  }

  private static boolean hash(String value) {
    return value != null && value.matches("sha256:[a-f0-9]{64}");
  }

  private static void put(
      com.fasterxml.jackson.databind.node.ObjectNode json, String field, Object value) {
    if (value == null) json.putNull(field);
    else if (value instanceof Boolean fixed) json.put(field, fixed);
    else json.put(field, value.toString());
  }

  private static String requiredText(com.fasterxml.jackson.databind.JsonNode json, String field) {
    var value = json.path(field);
    if (!value.isTextual()) throw invalid();
    return value.textValue();
  }

  private static String nullableText(com.fasterxml.jackson.databind.JsonNode json, String field) {
    var value = json.path(field);
    if (value.isNull()) return null;
    if (!value.isTextual()) throw invalid();
    return value.textValue();
  }

  private static Boolean nullableBoolean(
      com.fasterxml.jackson.databind.JsonNode json, String field) {
    var value = json.path(field);
    if (value.isNull()) return null;
    if (!value.isBoolean()) throw invalid();
    return value.booleanValue();
  }
}
