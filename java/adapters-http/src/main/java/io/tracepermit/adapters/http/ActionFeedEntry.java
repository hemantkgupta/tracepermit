package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.SanitizationSummary;
import io.tracepermit.policy.PolicyEvaluation;

import java.util.ArrayList;
import java.util.List;

public record ActionFeedEntry(
    String eventId,
    String observedAt,
    String traceId,
    String intent,
    IdentityView identity,
    TriggerView trigger,
    TargetView target,
    RiskView risk,
    DecisionView decision,
    long latencyMs,
    String executionStatus,
    SanitizationView sanitization,
    List<LineageHopView> lineage,
    boolean simulated) {

  public ActionFeedEntry {
    lineage = List.copyOf(lineage);
  }

  public static ActionFeedEntry from(
      JsonNode event, PolicyEvaluation evaluation, long latencyMs, String executionStatus) {
    return from(event, evaluation, null, evaluation, latencyMs, executionStatus);
  }

  public static ActionFeedEntry from(
      JsonNode event, ExecutionResult result, long latencyMs, String executionStatus) {
    return from(
        event,
        result.evaluation(),
        result.sanitization(),
        result.effectiveEvaluation(),
        latencyMs,
        executionStatus);
  }

  private static ActionFeedEntry from(
      JsonNode event,
      PolicyEvaluation evaluation,
      SanitizationSummary sanitization,
      PolicyEvaluation effectiveEvaluation,
      long latencyMs,
      String executionStatus) {
    JsonNode action = ActionValidator.validate(event);
    JsonNode subject = action.path("identity").path("subject");
    JsonNode actor = action.path("identity").path("actor");
    JsonNode createdBy = action.path("identity").path("createdBy");
    JsonNode trigger = action.path("trigger");
    JsonNode target = action.path("action").path("target");
    List<LineageHopView> hops = new ArrayList<>();
    for (JsonNode hop : action.path("lineage").path("hops")) {
      hops.add(
          new LineageHopView(
              hop.path("hopId").asText(),
              optionalText(hop, "parentHopId"),
              hop.path("kind").asText(),
              hop.path("subjectId").asText(),
              hop.path("assurance").asText(),
              hop.path("source").asText()));
    }
    return new ActionFeedEntry(
        action.path("eventId").asText(),
        action.path("observedAt").asText(),
        action.path("traceId").asText(),
        action.path("declaredIntent").path("goal").asText(
            displayIntent(action.path("action").path("toolName").asText())),
        new IdentityView(
            principal(subject),
            actor.isObject() ? principal(actor) : null,
            createdBy.isObject() ? principal(createdBy) : null),
        new TriggerView(
            trigger.path("kind").asText(),
            trigger.path("origin").asText(),
            trigger.path("clientName").asText()),
        new TargetView(
            target.path("system").asText(),
            target.path("environment").asText(),
            target.path("accountId").asText(),
            target.path("resourceType").asText(),
            action.path("action").path("toolName").asText()),
        new RiskView(
            evaluation.classification().actionClass(),
            evaluation.classification().severity(),
            evaluation.classification().score(),
            evaluation.classification().signals(),
            evaluation.classification().semanticIntents()),
        new DecisionView(
            evaluation.decision().outcome(),
            evaluation.decision().reasonCode(),
            evaluation.decision().policyId(),
            evaluation.decision().policyVersion(),
            evaluation.decision().matchedRuleId()),
        latencyMs,
        executionStatus,
        sanitization == null
            ? null
            : new SanitizationView(
                sanitization.transformerId(),
                sanitization.changes(),
                sanitization.effectiveEventId(),
                effectiveEvaluation.decision().outcome()),
        hops,
        true);
  }

  private static PrincipalView principal(JsonNode value) {
    return new PrincipalView(
        value.path("id").asText(),
        value.path("displayName").asText(),
        value.path("type").asText(),
        value.path("assurance").asText());
  }

  private static String optionalText(JsonNode value, String field) {
    return value.hasNonNull(field) ? value.path(field).asText() : null;
  }

  private static String displayIntent(String toolName) {
    return switch (toolName) {
      case "aws.logs.filter_log_events" -> "Read bounded simulated application logs";
      case "aws.ec2.terminate_instances" -> "Terminate simulated compute instances";
      default -> toolName;
    };
  }

  public record PrincipalView(String id, String displayName, String type, String assurance) {}

  public record IdentityView(
      PrincipalView subject, PrincipalView actor, PrincipalView createdBy) {}

  public record TriggerView(String kind, String origin, String clientName) {}

  public record TargetView(
      String system,
      String environment,
      String accountId,
      String resourceType,
      String toolName) {}

  public record RiskView(
      String actionClass,
      String severity,
      int score,
      List<String> signals,
      List<String> semanticIntents) {
    public RiskView {
      signals = List.copyOf(signals);
      semanticIntents = List.copyOf(semanticIntents);
    }
  }

  public record SanitizationView(
      String transformerId,
      List<String> changes,
      String effectiveEventId,
      String finalOutcome) {
    public SanitizationView {
      changes = List.copyOf(changes);
    }
  }

  public record DecisionView(
      String outcome,
      String reasonCode,
      String policyId,
      int policyVersion,
      String matchedRuleId) {}

  public record LineageHopView(
      String hopId,
      String parentHopId,
      String kind,
      String subjectId,
      String assurance,
      String source) {}
}
