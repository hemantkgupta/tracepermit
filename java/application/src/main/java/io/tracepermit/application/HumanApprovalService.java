package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.execution.ApprovalConsumptionResult;
import io.tracepermit.execution.ApprovalConsumptionStatus;
import io.tracepermit.execution.ApprovalDecision;
import io.tracepermit.execution.ApprovalExecutionClaim;
import io.tracepermit.execution.ApprovalPolicyBinding;
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.DurableApprovalState;
import io.tracepermit.execution.DurableApprovalActionState;
import io.tracepermit.execution.DurablePolicyBoundApprovalState;
import io.tracepermit.execution.DurableEnforcementState;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.policy.ActivePolicySnapshot;
import io.tracepermit.policy.PolicyEvaluation;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.policy.PolicyLifecycleStore;
import io.tracepermit.security.ExecutionPermitAuthority;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Request-bound approval orchestration; transport adapters must derive reviewer authority. */
public final class HumanApprovalService {
  public static final String REVIEWER_ROLE = "approval_reviewer";
  public static final String REVIEWER_SCOPE = "approvals.decide";

  private final PolicyEvaluator policyEvaluator;
  private final JsonNode policy;
  private final PolicyLifecycleStore policyStore;
  private final String workspaceId;
  private final String policyId;
  private final ExecutionPermitAuthority permits;
  private final DurableApprovalState approvals;
  private final DurableEnforcementState executions;
  private final SimulatedTarget target;
  private final Supplier<SecurityReadiness> readiness;
  private final Clock clock;
  private final Supplier<String> createId;
  private final String audience;
  private final String adapterId;
  private final int approvalLifetimeSeconds;

  public HumanApprovalService(
      PolicyEvaluator policyEvaluator,
      JsonNode policy,
      ExecutionPermitAuthority permits,
      DurableApprovalState approvals,
      DurableEnforcementState executions,
      SimulatedTarget target,
      Supplier<SecurityReadiness> readiness,
      Clock clock,
      Supplier<String> createId,
      String audience,
      String adapterId,
      int approvalLifetimeSeconds) {
    this.policyEvaluator = Objects.requireNonNull(policyEvaluator);
    this.policy = Objects.requireNonNull(policy).deepCopy();
    this.policyStore = null;
    this.workspaceId = null;
    this.policyId = null;
    this.permits = Objects.requireNonNull(permits);
    this.approvals = Objects.requireNonNull(approvals);
    this.executions = Objects.requireNonNull(executions);
    this.target = Objects.requireNonNull(target);
    this.readiness = Objects.requireNonNull(readiness);
    this.clock = Objects.requireNonNull(clock);
    this.createId = Objects.requireNonNull(createId);
    this.audience = Objects.requireNonNull(audience);
    this.adapterId = Objects.requireNonNull(adapterId);
    if (approvalLifetimeSeconds < 1 || approvalLifetimeSeconds > 3600) {
      throw new IllegalArgumentException("Approval lifetime must be between 1 and 3600 seconds.");
    }
    this.approvalLifetimeSeconds = approvalLifetimeSeconds;
  }

  public HumanApprovalService(
      PolicyEvaluator policyEvaluator,
      PolicyLifecycleStore policyStore,
      String workspaceId,
      String policyId,
      ExecutionPermitAuthority permits,
      DurableApprovalState approvals,
      DurableEnforcementState executions,
      SimulatedTarget target,
      Supplier<SecurityReadiness> readiness,
      Clock clock,
      Supplier<String> createId,
      String audience,
      String adapterId,
      int approvalLifetimeSeconds) {
    this.policyEvaluator = Objects.requireNonNull(policyEvaluator);
    this.policy = null;
    this.policyStore = Objects.requireNonNull(policyStore);
    this.workspaceId = Objects.requireNonNull(workspaceId);
    this.policyId = Objects.requireNonNull(policyId);
    this.permits = Objects.requireNonNull(permits);
    this.approvals = Objects.requireNonNull(approvals);
    this.executions = Objects.requireNonNull(executions);
    this.target = Objects.requireNonNull(target);
    this.readiness = Objects.requireNonNull(readiness);
    this.clock = Objects.requireNonNull(clock);
    this.createId = Objects.requireNonNull(createId);
    this.audience = Objects.requireNonNull(audience);
    this.adapterId = Objects.requireNonNull(adapterId);
    if (approvalLifetimeSeconds < 1 || approvalLifetimeSeconds > 3600) {
      throw new IllegalArgumentException("Approval lifetime must be between 1 and 3600 seconds.");
    }
    this.approvalLifetimeSeconds = approvalLifetimeSeconds;
  }

  public ApprovalExecutionResult requestApproval(TrustedTenantContext context, JsonNode candidate) {
    return requestApproval(context, candidate, false);
  }

  /** The API path requires a transactionally persisted exact action, not a client resubmission. */
  public ApprovalExecutionResult requestApprovalWithSnapshot(
      TrustedTenantContext context, JsonNode candidate) {
    return requestApproval(context, candidate, true);
  }

  private ApprovalExecutionResult requestApproval(
      TrustedTenantContext context, JsonNode candidate, boolean persistAction) {
    JsonNode event = validateContext(context, candidate);
    JsonNode effectivePolicy;
    ActivePolicySnapshot snapshot = null;
    if (policyStore != null) {
      snapshot = policyStore.activePolicySnapshot(workspaceId, policyId)
          .orElseThrow(() -> new IllegalStateException("ACTIVE_POLICY_UNAVAILABLE"));
      effectivePolicy = snapshot.document();
    } else {
      effectivePolicy = policy;
    }
    PolicyEvaluation evaluation = policyEvaluator.evaluate(event, effectivePolicy);
    if (!"require_approval".equals(evaluation.decision().outcome())) {
      throw new IllegalStateException("APPROVAL_NOT_REQUIRED");
    }
    // PostgreSQL cannot preserve arbitrary nanoseconds. Bind a portable precision before hashing.
    Instant createdAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    String requestId = createId.get();
    ApprovalBinding binding = binding(
        context, event, evaluation, requestId, createdAt, createdAt.plusSeconds(approvalLifetimeSeconds));
    ApprovalRequestState request;
    if (snapshot != null) {
      if (!(approvals instanceof DurablePolicyBoundApprovalState policyBoundState)) {
        throw new IllegalStateException("APPROVAL_SNAPSHOT_UNAVAILABLE");
      }
      ApprovalPolicyBinding policyBinding = new ApprovalPolicyBinding(
          workspaceId,
          policyId,
          snapshot.version(),
          snapshot.activationGeneration(),
          snapshot.contentHash(),
          createdAt);
      request = policyBoundState.createPendingBound(binding, persistAction ? event : null, policyBinding);
    } else if (persistAction) {
      if (!(approvals instanceof DurableApprovalActionState snapshots)) {
        throw new IllegalStateException("APPROVAL_SNAPSHOT_UNAVAILABLE");
      }
      request = snapshots.createPending(binding, event);
    } else {
      request = approvals.createPending(binding);
    }
    return new ApprovalExecutionResult("pending_approval", evaluation, request, null, null);
  }

  public ApprovalRequestState review(
      TrustedReviewerContext reviewer,
      String approvalRequestId,
      String decision,
      String reasonCode) {
    requireReady();
    authorizeReviewer(reviewer);
    ApprovalRequestState request = approvals.find(reviewer.tenant(), approvalRequestId);
    if (request == null) throw new IllegalArgumentException("APPROVAL_REQUEST_NOT_FOUND");
    if (reviewer.reviewerSubjectId().equals(request.binding().subjectId())) {
      throw new IllegalArgumentException("SELF_APPROVAL_DENIED");
    }
    ApprovalDecision trustedDecision = new ApprovalDecision(
        reviewer.tenant(),
        approvalRequestId,
        createId.get(),
        reviewer.reviewerSubjectId(),
        reviewer.authenticationMethod(),
        reviewer.credentialFingerprint(),
        decision,
        reasonCode,
        clock.instant());
    ApprovalRequestState updated = approvals.decide(trustedDecision);
    if ("expired".equals(updated.status())) throw new IllegalStateException("APPROVAL_EXPIRED");
    return updated;
  }

  public ApprovalExecutionResult executeApproved(
      TrustedTenantContext context,
      String approvalRequestId,
      JsonNode candidate) {
    JsonNode event = validateContext(context, candidate);
    ApprovalRequestState approved = approvals.find(context.tenant(), approvalRequestId);
    if (approved == null) throw new IllegalArgumentException("APPROVAL_REQUEST_NOT_FOUND");
    if (!"approved".equals(approved.status())) {
      throw new IllegalStateException("APPROVAL_NOT_EXECUTABLE");
    }

    JsonNode effectivePolicy;
    ApprovalPolicyBinding policyBinding = null;
    if (policyStore != null) {
      ActivePolicySnapshot snapshot = policyStore.activePolicySnapshot(workspaceId, policyId)
          .orElseThrow(() -> new IllegalStateException("ACTIVE_POLICY_UNAVAILABLE"));
      if (approvals instanceof DurablePolicyBoundApprovalState policyBoundState) {
        policyBinding = policyBoundState.findPolicyBinding(context.tenant(), approvalRequestId).orElse(null);
      }
      if (policyBinding == null) {
        throw new IllegalStateException("STALE_POLICY_GENERATION");
      }
      if (!policyBinding.workspaceId().equals(workspaceId)
          || !policyBinding.policyId().equals(policyId)
          || policyBinding.policyGeneration() != snapshot.activationGeneration()
          || policyBinding.policyVersion() != snapshot.version()
          || !policyBinding.policyContentHash().equals(snapshot.contentHash())) {
        throw new IllegalStateException("STALE_POLICY_GENERATION");
      }
      effectivePolicy = snapshot.document();
    } else {
      effectivePolicy = policy;
    }

    PolicyEvaluation evaluation = policyEvaluator.evaluate(event, effectivePolicy);
    if (!"require_approval".equals(evaluation.decision().outcome())) {
      throw new IllegalStateException("APPROVAL_POLICY_MISMATCH");
    }
    ApprovalBinding current = binding(
        context,
        event,
        evaluation,
        approved.binding().approvalRequestId(),
        approved.binding().createdAt(),
        approved.binding().expiresAt());
    if (!current.equals(approved.binding())) throw new IllegalArgumentException("APPROVAL_BINDING_MISMATCH");

    ObjectNode evaluationDocument = evaluationDocument(event, evaluation);
    String compactPermit = permits.issueApproved(
        event,
        evaluationDocument,
        audience,
        approvalRequestId,
        approved.approvalDecisionId());
    JsonNode claims = permits.verify(compactPermit, event, audience);
    if (!approvalRequestId.equals(
            claims.path("authorization").path("approvalRequestId").asText())
        || !approved.approvalDecisionId().equals(
            claims.path("authorization").path("approvalDecisionId").asText())) {
      throw new IllegalStateException("APPROVAL_PERMIT_MISMATCH");
    }

    Instant now = clock.instant();
    String actionHash = CanonicalJson.sha256(event);
    DurableExecutionClaim executionClaim = new DurableExecutionClaim(
        context.tenant(),
        claims.path("permitId").asText(),
        Instant.parse(claims.path("expiresAt").asText()),
        adapterId,
        InMemoryExecutionLedger.idempotencyKey(event, actionHash),
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
    ApprovalConsumptionResult begun = approvals.consumeAndClaim(new ApprovalExecutionClaim(
        current, approved.approvalDecisionId(), executionClaim, now, policyBinding));
    if (!begun.started()) return existing(evaluation, begun);

    JsonNode result;
    try {
      result = target.invoke(event.path("action").deepCopy());
    } catch (TargetExecutionException denied) {
      finishAfterDispatch(context, begun.attempt(), "failed", denied.code(), denied);
      throw denied;
    } catch (RuntimeException uncertain) {
      finishAfterDispatch(
          context, begun.attempt(), "unknown", "TARGET_OUTCOME_UNCONFIRMED", uncertain);
      throw uncertain;
    }

    ExecutionAttempt attempt;
    try {
      attempt = executions.complete(
          context.tenant(),
          begun.attempt().attemptId(),
          "succeeded",
          "TARGET_CONFIRMED_SUCCESS",
          clock.instant());
    } catch (RuntimeException failure) {
      throw new IllegalStateException("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH", failure);
    }
    return new ApprovalExecutionResult("succeeded", evaluation, begun.approval(), attempt, result);
  }

  private JsonNode validateContext(TrustedTenantContext context, JsonNode candidate) {
    Objects.requireNonNull(context);
    requireReady();
    JsonNode event = ActionValidator.validate(candidate);
    String actionTenant = event.path("identity").path("subject").path("organizationId").asText();
    if (!context.tenant().value().equals(actionTenant)) {
      throw new IllegalArgumentException("TENANT_CONTEXT_MISMATCH");
    }
    return event;
  }

  private void requireReady() {
    if (!readiness.get().ready()) throw new IllegalStateException("SECURITY_DEPENDENCY_UNAVAILABLE");
  }

  private static void authorizeReviewer(TrustedReviewerContext reviewer) {
    Objects.requireNonNull(reviewer);
    if (!reviewer.authenticated()
        || !"human".equals(reviewer.principalType())
        || !reviewer.roles().contains(REVIEWER_ROLE)
        || !reviewer.scopes().contains(REVIEWER_SCOPE)) {
      throw new IllegalArgumentException("REVIEWER_NOT_AUTHORIZED");
    }
  }

  private ApprovalBinding binding(
      TrustedTenantContext context,
      JsonNode event,
      PolicyEvaluation evaluation,
      String requestId,
      Instant createdAt,
      Instant expiresAt) {
    String actionHash = CanonicalJson.sha256(event);
    ObjectNode actorLineage = JsonSupport.MAPPER.createObjectNode();
    JsonNode actor = event.path("identity").path("actor");
    if (!actor.isMissingNode()) actorLineage.set("actor", actor.deepCopy());
    actorLineage.set("lineage", event.path("lineage").deepCopy());
    ObjectNode resource = JsonSupport.MAPPER.createObjectNode();
    resource.put("toolName", event.path("action").path("toolName").asText());
    resource.set("target", event.path("action").path("target").deepCopy());
    resource.set("arguments", event.path("action").path("arguments").deepCopy());

    ObjectNode request = JsonSupport.MAPPER.createObjectNode();
    request.put("schemaVersion", "2.0");
    request.put("tenantId", context.tenant().value());
    request.put("approvalRequestId", requestId);
    request.put("actionHash", actionHash);
    request.put("eventId", event.path("eventId").asText());
    request.put("evaluationId", event.path("eventId").asText());
    request.put("subjectId", event.path("identity").path("subject").path("id").asText());
    request.put("actorLineageHash", CanonicalJson.sha256(actorLineage));
    request.put("policyId", evaluation.decision().policyId());
    request.put("policyVersion", evaluation.decision().policyVersion());
    request.put("originalDecision", evaluation.decision().outcome());
    request.put("toolName", event.path("action").path("toolName").asText());
    request.put("resourceHash", CanonicalJson.sha256(resource));
    request.put("approvalGroup", approvalGroup(evaluation));
    request.put("createdAt", createdAt.toString());
    request.put("expiresAt", expiresAt.toString());
    request.put("maxUses", 1);

    return new ApprovalBinding(
        context.tenant(),
        requestId,
        CanonicalJson.sha256(request),
        actionHash,
        event.path("eventId").asText(),
        event.path("eventId").asText(),
        event.path("identity").path("subject").path("id").asText(),
        CanonicalJson.sha256(actorLineage),
        evaluation.decision().policyId(),
        evaluation.decision().policyVersion(),
        evaluation.decision().outcome(),
        event.path("action").path("toolName").asText(),
        CanonicalJson.sha256(resource),
        approvalGroup(evaluation),
        createdAt,
        expiresAt,
        1);
  }

  private static String approvalGroup(PolicyEvaluation evaluation) {
    return evaluation.decision().approvalGroup() == null
        ? "default-reviewers"
        : evaluation.decision().approvalGroup();
  }

  private ObjectNode evaluationDocument(JsonNode event, PolicyEvaluation evaluation) {
    ObjectNode decision = JsonSupport.MAPPER.createObjectNode();
    decision.put("outcome", evaluation.decision().outcome());
    decision.put("policyId", evaluation.decision().policyId());
    decision.put("policyVersion", evaluation.decision().policyVersion());
    decision.put("reasonCode", evaluation.decision().reasonCode());
    ObjectNode document = JsonSupport.MAPPER.createObjectNode();
    document.put("evaluationId", event.path("eventId").asText());
    document.put("evaluatedAt", clock.instant().toString());
    document.put("eventId", event.path("eventId").asText());
    document.set("decision", decision);
    return document;
  }

  private void finishAfterDispatch(
      TrustedTenantContext context,
      ExecutionAttempt attempt,
      String outcome,
      String reasonCode,
      RuntimeException targetFailure) {
    try {
      executions.complete(context.tenant(), attempt.attemptId(), outcome, reasonCode, clock.instant());
    } catch (RuntimeException persistenceFailure) {
      persistenceFailure.addSuppressed(targetFailure);
      throw new IllegalStateException("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH", persistenceFailure);
    }
  }

  private static ApprovalExecutionResult existing(
      PolicyEvaluation evaluation, ApprovalConsumptionResult consumption) {
    if (consumption.status() == ApprovalConsumptionStatus.APPROVAL_EXPIRED) {
      throw new IllegalStateException("APPROVAL_EXPIRED");
    }
    if (consumption.status() == ApprovalConsumptionStatus.APPROVAL_REPLAYED) {
      throw new IllegalStateException("APPROVAL_REPLAYED");
    }
    if (consumption.status() == ApprovalConsumptionStatus.STALE_POLICY_GENERATION) {
      throw new IllegalStateException("STALE_POLICY_GENERATION");
    }
    ExecutionAttempt attempt = consumption.attempt();
    if (attempt == null) throw new IllegalStateException("APPROVAL_NOT_EXECUTABLE");
    return switch (attempt.status()) {
      case "succeeded" -> new ApprovalExecutionResult(
          "succeeded", evaluation, consumption.approval(), attempt, null);
      case "executing" -> throw new IllegalStateException("EXECUTION_ALREADY_IN_PROGRESS");
      case "unknown" -> throw new IllegalStateException("EXECUTION_OUTCOME_UNKNOWN");
      default -> throw new IllegalStateException("EXECUTION_PREVIOUSLY_FAILED");
    };
  }

  private static List<String> credentialFingerprints(JsonNode event) {
    return JsonSupport.MAPPER.convertValue(
        event.path("identity").path("evidence").findValues("credentialFingerprint"),
        JsonSupport.MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
  }
}
