package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.ActionValidator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class PolicyLifecycleService {
  private final PolicyEvaluator policyEvaluator;
  private final Map<String, PolicyHolder> policies = new ConcurrentHashMap<>();

  public PolicyLifecycleService() {
    this(new PolicyEvaluator());
  }

  public PolicyLifecycleService(PolicyEvaluator policyEvaluator) {
    this.policyEvaluator = Objects.requireNonNull(policyEvaluator, "policyEvaluator must not be null");
  }

  public PolicyVersionRecord stage(JsonNode policyDocument, Instant now) {
    Objects.requireNonNull(policyDocument, "policyDocument must not be null");
    Objects.requireNonNull(now, "now must not be null");
    JsonNode validated = PolicyValidator.validate(policyDocument);
    String policyId = validated.path("id").asText();
    int version = validated.path("version").asInt();

    PolicyHolder holder = policies.computeIfAbsent(policyId, PolicyHolder::new);
    synchronized (holder) {
      PolicyVersionRecord existing = holder.versions.get(version);
      if (existing != null) {
        String candidateHash = PolicyVersionRecord.staged(validated, now).contentHash();
        if (existing.contentHash().equals(candidateHash)) {
          return existing;
        }
        throw new IllegalStateException(
            "POLICY_VERSION_IMMUTABLE: version " + version + " of policy " + policyId + " already exists with differing content hash");
      }
      PolicyVersionRecord record = PolicyVersionRecord.staged(validated, now);
      holder.versions.put(version, record);
      return record;
    }
  }

  public PolicyVersionRecord activate(String policyId, int version, Instant now) {
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(now, "now must not be null");
    PolicyHolder holder = policies.get(policyId);
    if (holder == null) {
      throw new IllegalArgumentException("POLICY_NOT_FOUND: " + policyId);
    }

    synchronized (holder) {
      PolicyVersionRecord target = holder.versions.get(version);
      if (target == null) {
        throw new IllegalArgumentException("POLICY_VERSION_NOT_FOUND: " + policyId + "@v" + version);
      }

      PolicyVersionRecord currentActive = holder.activeVersion.get();
      if (currentActive != null && currentActive.version() == version && currentActive.isActive()) {
        return currentActive;
      }

      if (currentActive != null && currentActive.isActive()) {
        PolicyVersionRecord deprecated = currentActive.deprecate(now);
        holder.versions.put(deprecated.version(), deprecated);
      }

      PolicyVersionRecord activated = target.activate(now);
      holder.versions.put(version, activated);
      holder.activationHistory.add(version);
      holder.activeVersion.set(activated);
      return activated;
    }
  }

  public RollbackRecord rollback(String policyId, Instant now, String reason) {
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    PolicyHolder holder = policies.get(policyId);
    if (holder == null) {
      throw new IllegalArgumentException("POLICY_NOT_FOUND: " + policyId);
    }

    synchronized (holder) {
      PolicyVersionRecord currentActive = holder.activeVersion.get();
      if (currentActive == null || !currentActive.isActive()) {
        throw new IllegalStateException("NO_ACTIVE_POLICY: Policy " + policyId + " has no active version to roll back");
      }

      if (holder.activationHistory.size() < 2) {
        throw new IllegalStateException(
            "NO_PREVIOUS_ACTIVE_VERSION: Policy " + policyId + " has no previous active version to roll back to");
      }

      int previousActiveVersion = holder.activationHistory.get(holder.activationHistory.size() - 2);
      PolicyVersionRecord targetRecord = holder.versions.get(previousActiveVersion);
      if (targetRecord == null) {
        throw new IllegalStateException(
            "PREVIOUS_ACTIVE_VERSION_MISSING: Version " + previousActiveVersion + " not found in policy catalog");
      }

      PolicyVersionRecord rolledBackActive = currentActive.rollback(now, previousActiveVersion);
      holder.versions.put(currentActive.version(), rolledBackActive);

      PolicyVersionRecord reactivated = targetRecord.activate(now);
      holder.versions.put(previousActiveVersion, reactivated);

      holder.activationHistory.add(previousActiveVersion);
      holder.activeVersion.set(reactivated);

      RollbackRecord record = new RollbackRecord(policyId, currentActive.version(), previousActiveVersion, now, reason);
      holder.rollbacks.add(record);
      return record;
    }
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
    return simulate(policyId, version, canonicalActionEvent, ClassificationContext.EMPTY, now);
  }

  public PolicySimulationResult simulate(
      String policyId,
      int version,
      JsonNode canonicalActionEvent,
      ClassificationContext context,
      Instant now) {
    PolicyVersionRecord record = getVersion(policyId, version)
        .orElseThrow(() -> new IllegalArgumentException("POLICY_VERSION_NOT_FOUND: " + policyId + "@v" + version));
    return simulate(record.document(), canonicalActionEvent, context, now);
  }

  public PolicyEvaluation evaluateActive(
      String policyId, JsonNode canonicalActionEvent, ClassificationContext context) {
    PolicyVersionRecord active = activeVersion(policyId)
        .orElseThrow(() -> new IllegalStateException("NO_ACTIVE_POLICY: " + policyId));
    return policyEvaluator.evaluate(canonicalActionEvent, active.document(), context);
  }

  public Optional<PolicyVersionRecord> activeVersion(String policyId) {
    PolicyHolder holder = policies.get(policyId);
    if (holder == null) {
      return Optional.empty();
    }
    PolicyVersionRecord record = holder.activeVersion.get();
    return record != null && record.isActive() ? Optional.of(record) : Optional.empty();
  }

  public Optional<PolicyVersionRecord> getVersion(String policyId, int version) {
    PolicyHolder holder = policies.get(policyId);
    if (holder == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(holder.versions.get(version));
  }

  public List<PolicyVersionRecord> listVersions(String policyId) {
    PolicyHolder holder = policies.get(policyId);
    if (holder == null) {
      return List.of();
    }
    List<PolicyVersionRecord> list = new ArrayList<>(holder.versions.values());
    list.sort(Comparator.comparingInt(PolicyVersionRecord::version).reversed());
    return Collections.unmodifiableList(list);
  }

  public List<RollbackRecord> rollbackHistory(String policyId) {
    PolicyHolder holder = policies.get(policyId);
    if (holder == null) {
      return List.of();
    }
    return Collections.unmodifiableList(new ArrayList<>(holder.rollbacks));
  }

  public Set<String> listPolicyIds() {
    return Collections.unmodifiableSet(policies.keySet());
  }

  public PolicyEvaluator evaluator() {
    return policyEvaluator;
  }

  private static final class PolicyHolder {
    final String policyId;
    final Map<Integer, PolicyVersionRecord> versions = new ConcurrentHashMap<>();
    final AtomicReference<PolicyVersionRecord> activeVersion = new AtomicReference<>();
    final List<Integer> activationHistory = new ArrayList<>();
    final List<RollbackRecord> rollbacks = new CopyOnWriteArrayList<>();

    PolicyHolder(String policyId) {
      this.policyId = policyId;
    }
  }
}
