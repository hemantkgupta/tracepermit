package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.ActionValidator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PolicyEvaluator {
  private static final BigInteger OVERSIZED_LOG_WINDOW_THRESHOLD = BigInteger.valueOf(100);
  private static final List<String> DESTRUCTIVE = List.of("delete", "drop", "terminate", "destroy", "purge", "truncate");
  private static final List<String> ORCHESTRATION = List.of("delegate", "spawn", "subagent", "assume_role", "issue_token");
  private static final List<String> READ = List.of("get", "list", "read", "describe", "search", "query", "filter", "fetch");
  private static final List<String> MODIFY = List.of("create", "update", "put", "post", "patch", "set", "rotate", "start", "stop", "restart", "attach", "detach", "launch", "send");
  private static final List<String> BULK_READ = List.of("export", "dump", "download_all", "bulk_read");
  private static final Map<String, Integer> SEVERITY = Map.of("low", 0, "medium", 1, "high", 2, "critical", 3);

  public PolicyEvaluation evaluate(JsonNode event, JsonNode policy) {
    return evaluate(event, policy, ClassificationContext.EMPTY);
  }

  public PolicyEvaluation evaluate(
      JsonNode event, JsonNode policy, ClassificationContext context) {
    JsonNode validated = ActionValidator.validate(event);
    Classification classification = classify(validated, context);
    JsonNode match = null;
    List<JsonNode> rules = new ArrayList<>();
    policy.path("rules").forEach(rules::add);
    rules.sort(Comparator.comparingInt((JsonNode rule) -> rule.path("priority").asInt()).reversed());
    for (JsonNode rule : rules) {
      if (matches(rule.path("match"), validated, classification)) {
        match = rule;
        break;
      }
    }
    JsonNode decision = match == null ? policy.path("defaultDecision") : match.path("decision");
    return new PolicyEvaluation(classification, new PolicyDecision(
        decision.path("outcome").asText(),
        decision.path("reasonCode").asText(),
        optionalText(decision, "approvalGroup"),
        match == null ? null : match.path("id").asText(),
        policy.path("id").asText(),
        policy.path("version").asInt()));
  }

  public Classification classify(JsonNode event) {
    return classify(event, ClassificationContext.EMPTY);
  }

  public Classification classify(JsonNode event, ClassificationContext context) {
    java.util.Objects.requireNonNull(context);
    String operation = event.path("action").path("toolName").asText();
    List<String> signals = new ArrayList<>();
    List<String> semanticIntents = new ArrayList<>();
    String actionClass;
    int score;
    if (includes(operation, DESTRUCTIVE)) {
      actionClass = "destructive"; score = 85; signals.add("DESTRUCTIVE_VERB");
    } else if (includes(operation, ORCHESTRATION)) {
      actionClass = "orchestration"; score = 65; signals.add("AUTHORITY_DELEGATION");
    } else if (includes(operation, BULK_READ)) {
      actionClass = "read"; score = 60; signals.add("BULK_READ"); signals.add("POTENTIAL_EXFILTRATION");
    } else if (includes(operation, READ)) {
      actionClass = "read"; score = 10; signals.add("READ_VERB");
    } else if (includes(operation, MODIFY)) {
      actionClass = "modify"; score = 45; signals.add("MUTATION_VERB");
    } else {
      actionClass = "modify"; score = 60; signals.add("UNKNOWN_TOOL"); signals.add("CONSERVATIVE_DEFAULT");
    }
    if ("prod".equals(event.path("action").path("target").path("environment").asText()) && !"read".equals(actionClass)) {
      score += 10; signals.add("PRODUCTION_TARGET");
    }
    if ("asserted".equals(event.path("identity").path("subject").path("assurance").asText())) {
      score += 10; signals.add("UNVERIFIED_PRINCIPAL");
    }
    addSemanticIntents(operation, semanticIntents);
    JsonNode arguments = event.path("action").path("arguments");
    if ("aws.logs.filter_log_events".equals(operation)
        && arguments.path("limit").isIntegralNumber()
        && arguments.path("limit").bigIntegerValue().compareTo(OVERSIZED_LOG_WINDOW_THRESHOLD) > 0) {
      score += 50;
      addSignal(signals, "OVERSIZED_RESULT_WINDOW");
      addSignal(semanticIntents, "POTENTIAL_DATA_EXFILTRATION");
    }
    if ("demo.customer.bulk_read".equals(operation)
        && "synthetic-crm".equals(event.path("action").path("target").path("system").asText())) {
      addSignal(signals, "SYNTHETIC_PII_DATASET");
    }
    if ("demo.egress.send_external".equals(operation)
        && "synthetic-egress".equals(event.path("action").path("target").path("system").asText())) {
      addSignal(signals, "EXTERNAL_SEND_PROPOSAL");
    }
    if ("aws.iam.attach_role_policy".equals(operation)
        && "aws".equals(event.path("action").path("target").path("system").asText())) {
      score += 35;
      addSignal(signals, "ACCESS_CONTROL_MUTATION");
    }
    for (String signal : context.signals()) addSignal(signals, signal);
    score += context.scoreAdjustment();
    score = Math.min(score, 100);
    return new Classification(
        actionClass,
        severity(score),
        score,
        operation,
        signals,
        semanticIntents,
        "deterministic",
        "0.1.0");
  }

  private static boolean matches(JsonNode match, JsonNode event, Classification classification) {
    return containsOrAbsent(match, "origins", event.path("trigger").path("origin").asText())
        && containsOrAbsent(match, "subjectTypes", event.path("identity").path("subject").path("type").asText())
        && containsOrAbsent(match, "targetSystems", event.path("action").path("target").path("system").asText())
        && containsOrAbsent(match, "environments", event.path("action").path("target").path("environment").asText())
        && containsOrAbsent(match, "actionClasses", classification.actionClass())
        && containsOrAbsent(match, "toolNames", event.path("action").path("toolName").asText())
        && containsAllOrAbsent(match, "requiredSignals", classification.signals())
        && (!match.has("minimumSeverity") || SEVERITY.get(classification.severity()) >= SEVERITY.get(match.path("minimumSeverity").asText()));
  }

  private static boolean containsAllOrAbsent(
      JsonNode object, String field, List<String> actual) {
    if (!object.has(field)) return true;
    for (JsonNode value : object.path(field)) {
      if (!actual.contains(value.asText())) return false;
    }
    return true;
  }

  private static boolean containsOrAbsent(JsonNode object, String field, String actual) {
    if (!object.has(field)) return true;
    for (JsonNode value : object.path(field)) if (actual.equals(value.asText())) return true;
    return false;
  }

  private static boolean includes(String input, List<String> terms) {
    String normalized = input.toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace('.', '_');
    return terms.stream().anyMatch(normalized::contains);
  }

  private static void addSemanticIntents(String operation, List<String> semanticIntents) {
    String normalized = operation.toLowerCase(java.util.Locale.ROOT);
    if (includes(operation, DESTRUCTIVE)) addSignal(semanticIntents, "DATA_DESTRUCTION");
    if (includes(operation, ORCHESTRATION)) {
      addSignal(semanticIntents, "AUTHORITY_DELEGATION");
    }
    if (includes(operation, BULK_READ)) {
      addSignal(semanticIntents, "POTENTIAL_DATA_EXFILTRATION");
    }
    if (normalized.matches(".*(access_key|secret|credential|password|token).*$")) {
      addSignal(semanticIntents, "CREDENTIAL_SECRET_MANIPULATION");
    }
    if (normalized.matches(".*(create_(database|cluster|instance)|launch).*$")) {
      addSignal(semanticIntents, "INFRASTRUCTURE_PROVISIONING");
    }
    if (normalized.matches(".*(security_group|acl|iam|permission|role).*$")) {
      addSignal(semanticIntents, "ACCESS_CONTROL_CHANGE");
    }
    if (normalized.matches(".*(update|configure|configuration|set_).*$")) {
      addSignal(semanticIntents, "CONFIGURATION_CHANGE");
    }
    if (normalized.matches(".*(invoke|execute|run_command|shell).*$")) {
      addSignal(semanticIntents, "REMOTE_CODE_EXECUTION");
    }
    if (normalized.matches(".*(git|source|repository|commit|push).*$")) {
      addSignal(semanticIntents, "SOURCE_CODE_CHANGE");
    }
    if (normalized.matches(".*(webhook|email|message|notify|external).*$")) {
      addSignal(semanticIntents, "POTENTIAL_EXTERNAL_COMMUNICATION");
    }
  }

  private static void addSignal(List<String> signals, String signal) {
    if (!signals.contains(signal)) signals.add(signal);
  }

  private static String severity(int score) {
    if (score >= 85) return "critical";
    if (score >= 60) return "high";
    if (score >= 30) return "medium";
    return "low";
  }

  private static String optionalText(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.path(field).asText() : null;
  }
}
