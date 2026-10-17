package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.ActionValidator;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PolicyLifecycleService {
  private static final String DEFAULT_WORKSPACE = "default";

  private final PolicyLifecycleStore store;
  private final PolicyEvaluator policyEvaluator;

  public PolicyLifecycleService() {
    this(new InMemoryPolicyLifecycleStore(), new PolicyEvaluator());
  }

  public PolicyLifecycleService(PolicyEvaluator policyEvaluator) {
    this(new InMemoryPolicyLifecycleStore(), policyEvaluator);
  }

  public PolicyLifecycleService(PolicyLifecycleStore store) {
    this(store, new PolicyEvaluator());
  }

  public PolicyLifecycleService(PolicyLifecycleStore store, PolicyEvaluator policyEvaluator) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.policyEvaluator = Objects.requireNonNull(policyEvaluator, "policyEvaluator must not be null");
  }

  public PolicyLifecycleStore store() {
    return store;
  }

  public PolicyVersionRecord stage(JsonNode policyDocument, Instant now) {
    return stage(DEFAULT_WORKSPACE, policyDocument, now);
  }

  public PolicyVersionRecord stage(String workspaceId, JsonNode policyDocument, Instant now) {
    return store.stage(workspaceId, policyDocument, now);
  }

  public PolicyVersionRecord activate(String policyId, int version, Instant now) {
    return activate(DEFAULT_WORKSPACE, policyId, version, null, now, null, "direct-activation");
  }

  public PolicyVersionRecord activate(
      String workspaceId, String policyId, int version, Instant now, String actorId, String reason) {
    return activate(workspaceId, policyId, version, null, now, actorId, reason);
  }

  public PolicyVersionRecord activate(
      String workspaceId,
      String policyId,
      int version,
      Integer expectedActiveVersion,
      Instant now,
      String actorId,
      String reason) {
    return store.activate(workspaceId, policyId, version, expectedActiveVersion, now, actorId, reason);
  }

  public RollbackRecord rollback(String policyId, Instant now, String reason) {
    return rollback(DEFAULT_WORKSPACE, policyId, null, null, now, null, reason);
  }

  public RollbackRecord rollback(
      String workspaceId, String policyId, Instant now, String actorId, String reason) {
    return rollback(workspaceId, policyId, null, null, now, actorId, reason);
  }

  public RollbackRecord rollback(
      String workspaceId,
      String policyId,
      Integer expectedActiveVersion,
      Integer targetVersion,
      Instant now,
      String actorId,
      String reason) {
    return store.rollback(workspaceId, policyId, expectedActiveVersion, targetVersion, now, actorId, reason);
  }

  public Optional<PolicyVersionRecord> activeVersion(String policyId) {
    return activeVersion(DEFAULT_WORKSPACE, policyId);
  }

  public Optional<PolicyVersionRecord> activeVersion(String workspaceId, String policyId) {
    return store.activeVersion(workspaceId, policyId);
  }

  public Optional<ActivePolicySnapshot> activePolicySnapshot(String policyId) {
    return activePolicySnapshot(DEFAULT_WORKSPACE, policyId);
  }

  public Optional<ActivePolicySnapshot> activePolicySnapshot(String workspaceId, String policyId) {
    return store.activePolicySnapshot(workspaceId, policyId);
  }

  public Optional<PolicyVersionRecord> getVersion(String policyId, int version) {
    return getVersion(DEFAULT_WORKSPACE, policyId, version);
  }

  public Optional<PolicyVersionRecord> getVersion(String workspaceId, String policyId, int version) {
    return store.getVersion(workspaceId, policyId, version);
  }

  public List<PolicyVersionRecord> listVersions(String policyId) {
    return listVersions(DEFAULT_WORKSPACE, policyId);
  }

  public List<PolicyVersionRecord> listVersions(String workspaceId, String policyId) {
    return store.listVersions(workspaceId, policyId);
  }

  public List<RollbackRecord> rollbackHistory(String policyId) {
    return rollbackHistory(DEFAULT_WORKSPACE, policyId);
  }

  public List<RollbackRecord> rollbackHistory(String workspaceId, String policyId) {
    return store.rollbackHistory(workspaceId, policyId);
  }

  public List<PolicyTransitionRecord> activationHistory(String workspaceId, String policyId) {
    return store.activationHistory(workspaceId, policyId);
  }

  public Set<String> listPolicyIds() {
    return listPolicyIds(DEFAULT_WORKSPACE);
  }

  public Set<String> listPolicyIds(String workspaceId) {
    return store.listPolicyIds(workspaceId);
  }

  public PolicySimulationResult simulate(
      JsonNode candidatePolicy, JsonNode canonicalActionEvent, Instant now) {
    return simulate(candidatePolicy, canonicalActionEvent, ClassificationContext.EMPTY, now);
  }

  public PolicySimulationResult simulate(
      JsonNode candidatePolicy,
      JsonNode canonicalActionEvent,
      ClassificationContext context,
      Instant now) {
    Objects.requireNonNull(candidatePolicy, "candidatePolicy must not be null");
    Objects.requireNonNull(canonicalActionEvent, "canonicalActionEvent must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(now, "now must not be null");

    JsonNode validatedPolicy = PolicyValidator.validate(candidatePolicy);
    JsonNode validatedAction = ActionValidator.validate(canonicalActionEvent);

    PolicyEvaluation evaluation = policyEvaluator.evaluate(validatedAction, validatedPolicy, context);
    return new PolicySimulationResult(
        validatedPolicy.path("id").asText(),
        validatedPolicy.path("version").asInt(),
        evaluation,
        now,
        true);
  }

  public PolicySimulationResult simulate(
      String policyId, int version, JsonNode canonicalActionEvent, Instant now) {
    return simulate(DEFAULT_WORKSPACE, policyId, version, canonicalActionEvent, ClassificationContext.EMPTY, now);
  }

  public PolicySimulationResult simulate(
      String policyId,
      int version,
      JsonNode canonicalActionEvent,
      ClassificationContext context,
      Instant now) {
    return simulate(DEFAULT_WORKSPACE, policyId, version, canonicalActionEvent, context, now);
  }

  public PolicySimulationResult simulate(
      String workspaceId,
      String policyId,
      int version,
      JsonNode canonicalActionEvent,
      ClassificationContext context,
      Instant now) {
    PolicyVersionRecord record = getVersion(workspaceId, policyId, version)
        .orElseThrow(() -> new IllegalArgumentException("POLICY_VERSION_NOT_FOUND: " + policyId + "@v" + version));
    return simulate(record.document(), canonicalActionEvent, context, now);
  }

  public PolicyEvaluation evaluateActive(
      String policyId, JsonNode canonicalActionEvent, ClassificationContext context) {
    return evaluateActive(DEFAULT_WORKSPACE, policyId, canonicalActionEvent, context);
  }

  public PolicyEvaluation evaluateActive(
      String workspaceId, String policyId, JsonNode canonicalActionEvent, ClassificationContext context) {
    PolicyVersionRecord active = activeVersion(workspaceId, policyId)
        .orElseThrow(() -> new IllegalStateException("NO_ACTIVE_POLICY: " + policyId));
    return policyEvaluator.evaluate(canonicalActionEvent, active.document(), context);
  }

  public PolicyEvaluator evaluator() {
    return policyEvaluator;
  }
}
