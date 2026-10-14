package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.DurableClaimResult;
import io.tracepermit.execution.DurableClaimStatus;
import io.tracepermit.execution.DurableEnforcementState;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.policy.PolicyEvaluation;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.ExecutionPermitException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Production-shaped orchestration with durable claims around an out-of-transaction target call. */
public final class DurableExecutionService implements TenantActionEnforcer {
  private final PolicyEvaluator policyEvaluator;
  private final JsonNode policy;
  private final ExecutionPermitAuthority permits;
  private final DurableEnforcementState state;
  private final SimulatedTarget target;
  private final Supplier<SecurityReadiness> readiness;
  private final Clock clock;
  private final String audience;
  private final String adapterId;

  public DurableExecutionService(
      PolicyEvaluator policyEvaluator,
      JsonNode policy,
      ExecutionPermitAuthority permits,
      DurableEnforcementState state,
      SimulatedTarget target,
      Supplier<SecurityReadiness> readiness,
      Clock clock,
      String audience,
      String adapterId) {
    this.policyEvaluator = Objects.requireNonNull(policyEvaluator);
    this.policy = Objects.requireNonNull(policy).deepCopy();
    this.permits = Objects.requireNonNull(permits);
    this.state = Objects.requireNonNull(state);
    this.target = Objects.requireNonNull(target);
    this.readiness = Objects.requireNonNull(readiness);
    this.clock = Objects.requireNonNull(clock);
    this.audience = Objects.requireNonNull(audience);
    this.adapterId = Objects.requireNonNull(adapterId);
  }

  public ExecutionResult execute(TrustedTenantContext context, JsonNode candidate) {
    Objects.requireNonNull(context);
    if (!readiness.get().ready()) throw new IllegalStateException("SECURITY_DEPENDENCY_UNAVAILABLE");
    JsonNode event = ActionValidator.validate(candidate);
    String actionTenant = event.path("identity").path("subject").path("organizationId").asText();
    if (!context.tenant().value().equals(actionTenant)) {
      throw new IllegalArgumentException("TENANT_CONTEXT_MISMATCH");
    }

    PolicyEvaluation evaluation = policyEvaluator.evaluate(event, policy);
    if (!"allow".equals(evaluation.decision().outcome())) {
      return new ExecutionResult(evaluation.decision().outcome(), evaluation, null, null);
    }

    String compactPermit = permits.issue(event, evaluationDocument(event, evaluation), audience);
    JsonNode claims = permits.verify(compactPermit, event, audience);
    Instant now = clock.instant();
    String actionHash = CanonicalJson.sha256(event);
    String idempotencyKey = InMemoryExecutionLedger.idempotencyKey(event, actionHash);
    DurableExecutionClaim claim = new DurableExecutionClaim(
        context.tenant(),
        claims.path("permitId").asText(),
        Instant.parse(claims.path("expiresAt").asText()),
        adapterId,
        idempotencyKey,
        event.path("eventId").asText(),
        event.path("traceId").asText(),
        claims.path("evaluationId").asText(),
        event.path("identity").path("subject").path("id").asText(),
        actionHash,
        claims.path("decision").path("policyId").asText(),
        claims.path("decision").path("policyVersion").asInt(),
        claims.path("decision").path("outcome").asText(),
        credentialFingerprints(event),
        now);

    DurableClaimResult begun = state.claim(claim);
    if (!begun.started()) return existing(evaluation, begun);

    JsonNode result;
    try {
      result = target.invoke(event.path("action").deepCopy());
    } catch (TargetExecutionException denied) {
      finishAfterDispatch(context, begun.attempt(), "failed", denied.code(), denied);
      throw denied;
    } catch (RuntimeException uncertain) {
      finishAfterDispatch(context, begun.attempt(), "unknown", "TARGET_OUTCOME_UNCONFIRMED", uncertain);
      throw uncertain;
    }

    ExecutionAttempt attempt;
    try {
      attempt = state.complete(
          context.tenant(), begun.attempt().attemptId(), "succeeded", "TARGET_CONFIRMED_SUCCESS", clock.instant());
    } catch (RuntimeException failure) {
      throw new IllegalStateException("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH", failure);
    }
    return new ExecutionResult("succeeded", evaluation, attempt, result);
  }

  private void finishAfterDispatch(
      TrustedTenantContext context,
      ExecutionAttempt attempt,
      String outcome,
      String reasonCode,
      RuntimeException targetFailure) {
    try {
      state.complete(context.tenant(), attempt.attemptId(), outcome, reasonCode, clock.instant());
    } catch (RuntimeException persistenceFailure) {
      persistenceFailure.addSuppressed(targetFailure);
      throw new IllegalStateException("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH", persistenceFailure);
    }
  }

  private static ExecutionResult existing(PolicyEvaluation evaluation, DurableClaimResult claim) {
    if (claim.status() == DurableClaimStatus.PERMIT_REPLAYED) {
      throw new ExecutionPermitException(
          "EXECUTION_PERMIT_REPLAYED", "The single-use execution permit has already been consumed.");
    }
    ExecutionAttempt attempt = claim.attempt();
    return switch (attempt.status()) {
      case "succeeded" -> new ExecutionResult("succeeded", evaluation, attempt, null);
      case "executing" -> throw new IllegalStateException("EXECUTION_ALREADY_IN_PROGRESS");
      case "unknown" -> throw new IllegalStateException("EXECUTION_OUTCOME_UNKNOWN");
      default -> throw new IllegalStateException("EXECUTION_PREVIOUSLY_FAILED");
    };
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
    if (evaluation.decision().matchedRuleId() != null) {
      decision.put("matchedRuleId", evaluation.decision().matchedRuleId());
    }
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

  private static List<String> credentialFingerprints(JsonNode event) {
    return JsonSupport.MAPPER.convertValue(
        event.path("identity").path("evidence").findValues("credentialFingerprint"),
        JsonSupport.MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
  }
}
