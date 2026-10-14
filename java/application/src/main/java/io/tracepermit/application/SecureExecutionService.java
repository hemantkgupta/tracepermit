package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.BeginExecutionResult;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.policy.ClassificationContext;
import io.tracepermit.policy.PolicyEvaluation;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

public final class SecureExecutionService implements ActionEnforcer {
  private final PolicyEvaluator policyEvaluator;
  private final JsonNode policy;
  private final ExecutionPermitAuthority permits;
  private final InMemoryExecutionLedger executions;
  private final InMemoryEvidenceLedger evidence;
  private final SimulatedTarget target;
  private final Supplier<SecurityReadiness> readiness;
  private final Clock clock;
  private final String audience;
  private final ActionSanitizer sanitizer;
  private final ActionSequenceStore sequenceStore;

  public SecureExecutionService(PolicyEvaluator policyEvaluator, JsonNode policy, ExecutionPermitAuthority permits,
      InMemoryExecutionLedger executions, InMemoryEvidenceLedger evidence, SimulatedTarget target,
      Supplier<SecurityReadiness> readiness, Clock clock, String audience) {
    this(
        policyEvaluator,
        policy,
        permits,
        executions,
        evidence,
        target,
        readiness,
        clock,
        audience,
        ActionSanitizer.NONE,
        ActionSequenceStore.DISABLED);
  }

  public SecureExecutionService(
      PolicyEvaluator policyEvaluator,
      JsonNode policy,
      ExecutionPermitAuthority permits,
      InMemoryExecutionLedger executions,
      InMemoryEvidenceLedger evidence,
      SimulatedTarget target,
      Supplier<SecurityReadiness> readiness,
      Clock clock,
      String audience,
      ActionSanitizer sanitizer,
      ActionSequenceStore sequenceStore) {
    this.policyEvaluator = Objects.requireNonNull(policyEvaluator);
    this.policy = policy.deepCopy();
    this.permits = Objects.requireNonNull(permits);
    this.executions = Objects.requireNonNull(executions);
    this.evidence = Objects.requireNonNull(evidence);
    this.target = Objects.requireNonNull(target);
    this.readiness = Objects.requireNonNull(readiness);
    this.clock = Objects.requireNonNull(clock);
    this.audience = Objects.requireNonNull(audience);
    this.sanitizer = Objects.requireNonNull(sanitizer);
    this.sequenceStore = Objects.requireNonNull(sequenceStore);
  }

  @Override
  public synchronized ExecutionResult execute(JsonNode event) {
    if (!readiness.get().ready()) throw new IllegalStateException("SECURITY_DEPENDENCY_UNAVAILABLE");
    event = ActionValidator.validate(event);
    Instant now = clock.instant();
    ClassificationContext sequenceContext;
    try {
      sequenceContext = sequenceStore.contextFor(event, now);
    } catch (RuntimeException failure) {
      throw new IllegalStateException("SEQUENCE_STATE_UNAVAILABLE", failure);
    }
    PolicyEvaluation evaluation = policyEvaluator.evaluate(event, policy, sequenceContext);
    appendEvidence("action.observed", event, evaluation);
    appendEvidence("policy.evaluated", event, evaluation);
    JsonNode effectiveEvent = event;
    PolicyEvaluation effectiveEvaluation = evaluation;
    SanitizationSummary sanitization = null;
    if ("sanitize".equals(evaluation.decision().outcome())) {
      SanitizedAction sanitized;
      try {
        sanitized = sanitizer.sanitize(event, evaluation, now).orElse(null);
      } catch (RuntimeException failure) {
        appendEvidence("sanitization.failed", event, evaluation);
        return new ExecutionResult("sanitize", evaluation, null, null);
      }
      if (sanitized == null) {
        appendEvidence("sanitization.unavailable", event, evaluation);
        return new ExecutionResult("sanitize", evaluation, null, null);
      }
      effectiveEvent = sanitized.event();
      sanitization = sanitized.summary();
      if (!reviewedTransformation(event, evaluation, now, sanitized)) {
        appendEvidence("sanitization.rejected", event, evaluation);
        return new ExecutionResult("sanitize", evaluation, null, null);
      }
      effectiveEvaluation = policyEvaluator.evaluate(effectiveEvent, policy, sequenceContext);
      appendSanitizationEvidence(event, sanitization, effectiveEvaluation);
      appendEvidence("policy.re_evaluated", effectiveEvent, effectiveEvaluation);
      if (!"allow".equals(effectiveEvaluation.decision().outcome())) {
        appendEvidence("sanitization.unresolved", effectiveEvent, effectiveEvaluation);
        return new ExecutionResult(
            "sanitize",
            evaluation,
            null,
            null,
            effectiveEvent,
            effectiveEvaluation,
            sanitization);
      }
    } else if (!"allow".equals(evaluation.decision().outcome())) {
      appendEvidence("authorization.denied", event, evaluation);
      return new ExecutionResult(evaluation.decision().outcome(), evaluation, null, null);
    }
    // Record only authorized proposals. A denied read must not establish the
    // cross-tool history used to classify a later external-send proposal.
    try {
      sequenceStore.record(effectiveEvent, effectiveEvaluation.classification(), now);
    } catch (RuntimeException failure) {
      throw new IllegalStateException("SEQUENCE_STATE_UNAVAILABLE", failure);
    }
    String permit =
        permits.issue(
            effectiveEvent,
            evaluationDocument(effectiveEvent, effectiveEvaluation),
            audience);
    JsonNode claims = permits.verifyAndConsume(permit, effectiveEvent, audience);
    appendEvidence("authorization.issued", effectiveEvent, effectiveEvaluation);
    String key =
        InMemoryExecutionLedger.idempotencyKey(
            effectiveEvent, CanonicalJson.sha256(effectiveEvent));
    BeginExecutionResult begun =
        executions.begin(
            effectiveEvent.path("eventId").asText(),
            claims.path("permitId").asText(),
            key,
            clock.instant());
    if (!begun.started()) {
      return existing(
          evaluation,
          effectiveEvaluation,
          effectiveEvent,
          sanitization,
          begun.attempt());
    }
    appendEvidence("target.dispatched", effectiveEvent, effectiveEvaluation);
    JsonNode result;
    try {
      result = target.invoke(effectiveEvent.path("action").deepCopy());
    } catch (TargetExecutionException failure) {
      executions.fail(begun.attempt().attemptId(), clock.instant(), failure.code());
      appendEvidence("target.failed", effectiveEvent, effectiveEvaluation);
      throw failure;
    } catch (RuntimeException failure) {
      executions.markUnknown(begun.attempt().attemptId(), clock.instant(), "TARGET_OUTCOME_UNCONFIRMED");
      appendEvidence("target.outcome_unknown", effectiveEvent, effectiveEvaluation);
      throw failure;
    }
    ExecutionAttempt attempt = executions.succeed(begun.attempt().attemptId(), clock.instant(), result);
    appendEvidence("target.succeeded", effectiveEvent, effectiveEvaluation);
    return new ExecutionResult(
        "succeeded",
        evaluation,
        attempt,
        result,
        sanitization == null ? null : effectiveEvent,
        effectiveEvaluation,
        sanitization);
  }

  private static boolean reviewedTransformation(
      JsonNode original,
      PolicyEvaluation evaluation,
      Instant now,
      SanitizedAction candidate) {
    if (!BoundedLogReadSanitizer.TRANSFORMER_ID.equals(candidate.transformerId())) {
      return false;
    }
    return new BoundedLogReadSanitizer()
        .sanitize(original, evaluation, now)
        .map(expected -> expected.event().equals(candidate.event())
            && expected.summary().equals(candidate.summary()))
        .orElse(false);
  }

  private ExecutionResult existing(
      PolicyEvaluation evaluation,
      PolicyEvaluation effectiveEvaluation,
      JsonNode effectiveEvent,
      SanitizationSummary sanitization,
      ExecutionAttempt attempt) {
    return switch (attempt.status()) {
      case "succeeded" ->
          new ExecutionResult(
              "succeeded",
              evaluation,
              attempt,
              attempt.result(),
              sanitization == null ? null : effectiveEvent,
              effectiveEvaluation,
              sanitization);
      case "executing" -> throw new IllegalStateException("EXECUTION_ALREADY_IN_PROGRESS");
      case "unknown" -> throw new IllegalStateException("EXECUTION_OUTCOME_UNKNOWN");
      default -> throw new IllegalStateException("EXECUTION_PREVIOUSLY_FAILED");
    };
  }

  private void appendSanitizationEvidence(
      JsonNode original,
      SanitizationSummary sanitization,
      PolicyEvaluation effectiveEvaluation) {
    ObjectNode fields = JsonSupport.MAPPER.createObjectNode();
    fields.put("eventType", "action.sanitized");
    fields.put("traceId", original.path("traceId").asText());
    fields.put("actionEventId", original.path("eventId").asText());
    fields.put("effectiveEventId", sanitization.effectiveEventId());
    fields.put("transformerId", sanitization.transformerId());
    fields.put("originalActionHash", sanitization.originalActionHash());
    fields.put("effectiveActionHash", sanitization.effectiveActionHash());
    fields.set("changes", JsonSupport.MAPPER.valueToTree(sanitization.changes()));
    fields.put("effectiveDecision", effectiveEvaluation.decision().outcome());
    evidence.append(fields, clock.instant());
  }

  private ObjectNode evaluationDocument(JsonNode event, PolicyEvaluation evaluation) {
    ObjectNode classification = JsonSupport.MAPPER.createObjectNode();
    classification.put("actionClass", evaluation.classification().actionClass());
    classification.put("severity", evaluation.classification().severity());
    classification.put("score", evaluation.classification().score());
    classification.put("operation", evaluation.classification().operation());
    classification.set("signals", JsonSupport.MAPPER.valueToTree(evaluation.classification().signals()));
    classification.set("classifier", JsonSupport.MAPPER.createObjectNode()
        .put("kind", evaluation.classification().classifierKind())
        .put("version", evaluation.classification().classifierVersion()));
    ObjectNode decision = JsonSupport.MAPPER.createObjectNode();
    decision.put("outcome", evaluation.decision().outcome());
    decision.put("policyId", evaluation.decision().policyId());
    decision.put("policyVersion", evaluation.decision().policyVersion());
    if (evaluation.decision().matchedRuleId() != null) decision.put("matchedRuleId", evaluation.decision().matchedRuleId());
    decision.put("reasonCode", evaluation.decision().reasonCode());
    ObjectNode document = JsonSupport.MAPPER.createObjectNode();
    document.put("evaluationId", event.path("eventId").asText());
    document.put("evaluatedAt", clock.instant().toString());
    document.put("eventId", event.path("eventId").asText());
    document.set("classification", classification);
    document.set("decision", decision);
    document.put("latencyMs", 0);
    return document;
  }

  private void appendEvidence(String eventType, JsonNode event, PolicyEvaluation evaluation) {
    ObjectNode fields = JsonSupport.MAPPER.createObjectNode();
    fields.put("eventType", eventType);
    fields.put("traceId", event.path("traceId").asText());
    fields.put("actionEventId", event.path("eventId").asText());
    fields.put("subjectId", event.path("identity").path("subject").path("id").asText());
    fields.put("decision", evaluation.decision().outcome());
    evidence.append(fields, clock.instant());
  }
}
