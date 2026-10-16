package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.ContractViolation;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public final class PolicyValidator {
  private static final Set<String> TOP_LEVEL_PROPERTIES = Set.of(
      "schemaVersion", "id", "version", "name", "description", "enabled", "defaultDecision", "rules"
  );
  private static final Set<String> ALLOWED_OUTCOMES = Set.of(
      "allow", "block", "sanitize", "require_approval"
  );
  private static final Set<String> ALLOWED_ORIGINS = Set.of(
      "chatgpt-web", "claude-web", "cli", "api", "scheduler"
  );
  private static final Set<String> ALLOWED_SUBJECT_TYPES = Set.of(
      "human", "workload"
  );
  private static final Set<String> ALLOWED_ENVIRONMENTS = Set.of(
      "dev", "test", "staging", "prod"
  );
  private static final Set<String> ALLOWED_ACTION_CLASSES = Set.of(
      "read", "modify", "destructive", "orchestration"
  );
  private static final Set<String> ALLOWED_SEVERITIES = Set.of(
      "low", "medium", "high", "critical"
  );
  private static final Set<String> RULE_PROPERTIES = Set.of(
      "id", "priority", "description", "match", "decision"
  );
  private static final Set<String> MATCH_PROPERTIES = Set.of(
      "origins", "subjectTypes", "targetSystems", "environments", "actionClasses", "toolNames", "requiredSignals", "minimumSeverity"
  );
  private static final Set<String> DECISION_PROPERTIES = Set.of(
      "outcome", "reasonCode", "approvalGroup"
  );

  private PolicyValidator() {}

  public static JsonNode validate(JsonNode policy) {
    require(policy != null && policy.isObject(), "", "policy document must be an object");
    // Validate the exact snapshot returned to ingestion, not a caller-owned tree.
    policy = policy.deepCopy();
    checkNoExtraFields(policy, TOP_LEVEL_PROPERTIES, "");

    require("1.0".equals(text(policy, "schemaVersion")), "schemaVersion", "must equal 1.0");
    require(!text(policy, "id").isBlank(), "id", "must not be empty");
    // PolicyDecision, catalog keys and rule ordering currently use Java int.
    // The language-neutral schema has a wider envelope; reject unsupported
    // values here before any narrowing can change version identity or priority.
    require(policy.path("version").isIntegralNumber()
            && policy.path("version").canConvertToInt()
            && policy.path("version").intValue() > 0,
        "version", "must be a positive integer in the supported Java range 1..2147483647");
    require(!text(policy, "name").isBlank(), "name", "must not be empty");
    require(!text(policy, "description").isBlank(), "description", "must not be empty");
    require(policy.has("enabled") && policy.path("enabled").isBoolean(), "enabled", "must be a boolean");

    JsonNode defaultDecision = object(policy, "defaultDecision");
    checkNoExtraFields(defaultDecision, Set.of("outcome", "reasonCode"), "defaultDecision");
    require(ALLOWED_OUTCOMES.contains(text(defaultDecision, "outcome")), "defaultDecision.outcome", "must be a valid outcome");
    require(!text(defaultDecision, "reasonCode").isBlank(), "defaultDecision.reasonCode", "must not be empty");

    JsonNode rules = array(policy, "rules");
    Set<String> ruleIds = new HashSet<>();
    for (int index = 0; index < rules.size(); index++) {
      String base = "rules[" + index + "]";
      JsonNode rule = rules.get(index);
      require(rule.isObject(), base, "must be an object");
      checkNoExtraFields(rule, RULE_PROPERTIES, base);

      String ruleId = text(rule, "id");
      require(!ruleId.isBlank(), base + ".id", "must not be empty");
      require(ruleIds.add(ruleId), base + ".id", "rule IDs must be unique across the policy document");

      require(rule.path("priority").isIntegralNumber() && rule.path("priority").canConvertToInt(),
          base + ".priority", "must be an integer in the supported Java range -2147483648..2147483647");
      require(!text(rule, "description").isBlank(), base + ".description", "must not be empty");

      JsonNode match = object(rule, "match");
      checkNoExtraFields(match, MATCH_PROPERTIES, base + ".match");
      validateMatch(match, base + ".match");

      JsonNode decision = object(rule, "decision");
      checkNoExtraFields(decision, DECISION_PROPERTIES, base + ".decision");
      require(ALLOWED_OUTCOMES.contains(text(decision, "outcome")), base + ".decision.outcome", "must be a valid outcome");
      require(!text(decision, "reasonCode").isBlank(), base + ".decision.reasonCode", "must not be empty");
      if (decision.has("approvalGroup")) {
        require(!text(decision, "approvalGroup").isBlank(), base + ".decision.approvalGroup", "must not be empty");
      }
    }

    return policy;
  }

  private static void validateMatch(JsonNode match, String base) {
    if (match.has("origins")) {
      validateEnumArray(match, "origins", ALLOWED_ORIGINS, base + ".origins");
    }
    if (match.has("subjectTypes")) {
      validateEnumArray(match, "subjectTypes", ALLOWED_SUBJECT_TYPES, base + ".subjectTypes");
    }
    if (match.has("targetSystems")) {
      validateStringArray(match, "targetSystems", base + ".targetSystems", false);
    }
    if (match.has("environments")) {
      validateEnumArray(match, "environments", ALLOWED_ENVIRONMENTS, base + ".environments");
    }
    if (match.has("actionClasses")) {
      validateEnumArray(match, "actionClasses", ALLOWED_ACTION_CLASSES, base + ".actionClasses");
    }
    if (match.has("toolNames")) {
      validateStringArray(match, "toolNames", base + ".toolNames", false);
    }
    if (match.has("requiredSignals")) {
      validateStringArray(match, "requiredSignals", base + ".requiredSignals", true);
    }
    if (match.has("minimumSeverity")) {
      require(ALLOWED_SEVERITIES.contains(text(match, "minimumSeverity")),
          base + ".minimumSeverity", "must be a valid severity enum");
    }
  }

  private static void validateEnumArray(JsonNode parent, String field, Set<String> allowed, String path) {
    JsonNode array = array(parent, field);
    for (int index = 0; index < array.size(); index++) {
      JsonNode item = array.get(index);
      require(item != null && item.isTextual() && allowed.contains(item.asText()),
          path + "[" + index + "]", "value not permitted: " + (item == null ? "null" : item.asText()));
    }
  }

  private static void validateStringArray(JsonNode parent, String field, String path, boolean requireMinOne) {
    JsonNode array = array(parent, field);
    if (requireMinOne) {
      require(!array.isEmpty(), path, "requires at least one item");
    }
    for (int index = 0; index < array.size(); index++) {
      JsonNode item = array.get(index);
      require(item != null && item.isTextual() && !item.asText().isBlank(),
          path + "[" + index + "]", "must be a non-empty string");
    }
  }

  private static void checkNoExtraFields(JsonNode node, Set<String> allowedFields, String pathPrefix) {
    Iterator<String> fieldNames = node.fieldNames();
    while (fieldNames.hasNext()) {
      String field = fieldNames.next();
      if (!allowedFields.contains(field)) {
        String errorPath = pathPrefix.isEmpty() ? field : pathPrefix + "." + field;
        throw new ContractViolation(errorPath, "unknown property is not permitted");
      }
    }
  }

  private static JsonNode object(JsonNode parent, String name) {
    JsonNode value = parent == null ? null : parent.get(name);
    require(value != null && value.isObject(), name, "must be an object");
    return value;
  }

  private static JsonNode array(JsonNode parent, String name) {
    JsonNode value = parent == null ? null : parent.get(name);
    require(value != null && value.isArray(), name, "must be an array");
    return value;
  }

  private static String text(JsonNode parent, String name) {
    JsonNode value = parent == null ? null : parent.get(name);
    return value != null && value.isTextual() ? value.textValue() : "";
  }

  private static void require(boolean condition, String path, String message) {
    if (!condition) {
      throw new ContractViolation(path, message);
    }
  }
}
