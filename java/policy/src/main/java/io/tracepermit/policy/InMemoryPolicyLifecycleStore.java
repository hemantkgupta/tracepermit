package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.CanonicalJson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory reference implementation of {@link PolicyLifecycleStore}.
 *
 * <p>Suitable for lightweight ephemeral tests and single-process demonstrations. State is
 * lost on process restart.
 */
public final class InMemoryPolicyLifecycleStore implements PolicyLifecycleStore {

  private final Map<String, Map<String, PolicyHolder>> workspaces = new ConcurrentHashMap<>();

  private Map<String, PolicyHolder> getWorkspace(String workspaceId) {
    Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    return workspaces.computeIfAbsent(workspaceId, k -> new ConcurrentHashMap<>());
  }

  @Override
  public PolicyVersionRecord stage(String workspaceId, JsonNode policyDocument, Instant now) {
    Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    Objects.requireNonNull(policyDocument, "policyDocument must not be null");
    Objects.requireNonNull(now, "now must not be null");

    JsonNode validated = PolicyValidator.validate(policyDocument);
    String policyId = validated.path("id").asText();
    int version = validated.path("version").asInt();

    PolicyHolder holder = getWorkspace(workspaceId).computeIfAbsent(policyId, PolicyHolder::new);
    synchronized (holder) {
      PolicyVersionRecord existing = holder.versions.get(version);
      if (existing != null) {
        String candidateHash = PolicyVersionRecord.staged(validated, now).contentHash();
        if (existing.contentHash().equals(candidateHash)) {
          return existing;
        }
        throw new IllegalStateException(
            "POLICY_VERSION_IMMUTABLE: version " + version + " of policy " + policyId
                + " already exists with differing content hash");
      }
      PolicyVersionRecord record = PolicyVersionRecord.staged(validated, now);
      holder.versions.put(version, record);
      return record;
    }
  }

  @Override
  public PolicyVersionRecord activate(
      String workspaceId,
      String policyId,
      int version,
      Integer expectedActiveVersion,
      Instant now,
      String actorId,
      String reason) {
    Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(reason, "reason must not be null");

    PolicyHolder holder = getWorkspace(workspaceId).get(policyId);
    if (holder == null) {
      throw new IllegalArgumentException("POLICY_NOT_FOUND: " + policyId);
    }

    synchronized (holder) {
      PolicyVersionRecord target = holder.versions.get(version);
      if (target == null) {
        throw new IllegalArgumentException("POLICY_VERSION_NOT_FOUND: " + policyId + "@v" + version);
      }

      PolicyVersionRecord currentActive = holder.activeVersion.get();
      Integer currentActiveVersion = (currentActive != null && currentActive.isActive())
          ? currentActive.version()
          : null;

      if (expectedActiveVersion != null && !Objects.equals(expectedActiveVersion, currentActiveVersion)) {
        throw new PolicyConcurrencyException(
            "POLICY_CONCURRENCY_CONFLICT: Expected active version " + expectedActiveVersion
                + " but found " + currentActiveVersion);
      }

      if (currentActive != null && currentActive.version() == version && currentActive.isActive()) {
        return currentActive;
      }

      if (currentActive != null && currentActive.isActive()) {
        PolicyVersionRecord deprecated = currentActive.deprecate(now);
        holder.versions.put(deprecated.version(), deprecated);
      }

      PolicyVersionRecord activated = target.activate(now);
      holder.versions.put(version, activated);
      holder.activationHistoryList.add(version);
      holder.activeVersion.set(activated);
      holder.activationSequence.incrementAndGet();

      long seq = holder.sequenceCounter.incrementAndGet();
      holder.transitions.add(new PolicyTransitionRecord(
          UUID.randomUUID().toString(),
          workspaceId,
          policyId,
          seq,
          "activate",
          currentActiveVersion,
          version,
          now,
          actorId,
          reason));

      return activated;
    }
  }

  @Override
  public RollbackRecord rollback(
      String workspaceId,
      String policyId,
      Integer expectedActiveVersion,
      Integer targetVersion,
      Instant now,
      String actorId,
      String reason) {
    Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(reason, "reason must not be null");

    PolicyHolder holder = getWorkspace(workspaceId).get(policyId);
    if (holder == null) {
      throw new IllegalArgumentException("POLICY_NOT_FOUND: " + policyId);
    }

    synchronized (holder) {
      PolicyVersionRecord currentActive = holder.activeVersion.get();
      if (currentActive == null || !currentActive.isActive()) {
        throw new IllegalStateException("NO_ACTIVE_POLICY: Policy " + policyId + " has no active version to roll back");
      }

      int currentActiveVersion = currentActive.version();
      if (expectedActiveVersion != null && expectedActiveVersion != currentActiveVersion) {
        throw new PolicyConcurrencyException(
            "POLICY_CONCURRENCY_CONFLICT: Expected active version " + expectedActiveVersion
                + " but found " + currentActiveVersion);
      }

      int resolvedTargetVersion;
      if (targetVersion != null) {
        resolvedTargetVersion = targetVersion;
      } else {
        if (holder.activationHistoryList.size() < 2) {
          throw new IllegalStateException(
              "NO_PREVIOUS_ACTIVE_VERSION: Policy " + policyId + " has no previous active version to roll back to");
        }
        resolvedTargetVersion = holder.activationHistoryList.get(holder.activationHistoryList.size() - 2);
      }

      PolicyVersionRecord targetRecord = holder.versions.get(resolvedTargetVersion);
      if (targetRecord == null) {
        throw new IllegalStateException(
            "PREVIOUS_ACTIVE_VERSION_MISSING: Version " + resolvedTargetVersion + " not found in policy catalog");
      }

      PolicyVersionRecord rolledBackActive = currentActive.rollback(now, resolvedTargetVersion);
      holder.versions.put(currentActive.version(), rolledBackActive);

      PolicyVersionRecord reactivated = targetRecord.activate(now);
      holder.versions.put(resolvedTargetVersion, reactivated);

      holder.activationHistoryList.add(resolvedTargetVersion);
      holder.activeVersion.set(reactivated);
      holder.activationSequence.incrementAndGet();

      long seq = holder.sequenceCounter.incrementAndGet();
      holder.transitions.add(new PolicyTransitionRecord(
          UUID.randomUUID().toString(),
          workspaceId,
          policyId,
          seq,
          "rollback",
          currentActiveVersion,
          resolvedTargetVersion,
          now,
          actorId,
          reason));

      RollbackRecord record = new RollbackRecord(
          workspaceId, policyId, currentActive.version(), resolvedTargetVersion, now, actorId, reason);
      holder.rollbacks.add(record);
      return record;
    }
  }

  @Override
  public Optional<PolicyVersionRecord> activeVersion(String workspaceId, String policyId) {
    Map<String, PolicyHolder> ws = workspaces.get(workspaceId);
    if (ws == null) return Optional.empty();
    PolicyHolder holder = ws.get(policyId);
    if (holder == null) return Optional.empty();
    PolicyVersionRecord record = holder.activeVersion.get();
    return record != null && record.isActive() ? Optional.of(record) : Optional.empty();
  }

  @Override
  public Optional<ActivePolicySnapshot> activePolicySnapshot(String workspaceId, String policyId) {
    Map<String, PolicyHolder> ws = workspaces.get(workspaceId);
    if (ws == null) return Optional.empty();
    PolicyHolder holder = ws.get(policyId);
    if (holder == null) return Optional.empty();
    synchronized (holder) {
      PolicyVersionRecord record = holder.activeVersion.get();
      if (record == null || !record.isActive()) return Optional.empty();
      return Optional.of(new ActivePolicySnapshot(
          workspaceId,
          policyId,
          record.version(),
          record.contentHash(),
          record.document(),
          holder.activationSequence.get(),
          record.activatedAt() != null ? record.activatedAt() : Instant.EPOCH));
    }
  }

  @Override
  public Optional<PolicyVersionRecord> getVersion(String workspaceId, String policyId, int version) {
    Map<String, PolicyHolder> ws = workspaces.get(workspaceId);
    if (ws == null) return Optional.empty();
    PolicyHolder holder = ws.get(policyId);
    if (holder == null) return Optional.empty();
    return Optional.ofNullable(holder.versions.get(version));
  }

  @Override
  public List<PolicyVersionRecord> listVersions(String workspaceId, String policyId) {
    Map<String, PolicyHolder> ws = workspaces.get(workspaceId);
    if (ws == null) return List.of();
    PolicyHolder holder = ws.get(policyId);
    if (holder == null) return List.of();
    List<PolicyVersionRecord> list = new ArrayList<>(holder.versions.values());
    list.sort(Comparator.comparingInt(PolicyVersionRecord::version).reversed());
    return Collections.unmodifiableList(list);
  }

  @Override
  public List<RollbackRecord> rollbackHistory(String workspaceId, String policyId) {
    Map<String, PolicyHolder> ws = workspaces.get(workspaceId);
    if (ws == null) return List.of();
    PolicyHolder holder = ws.get(policyId);
    if (holder == null) return List.of();
    return Collections.unmodifiableList(new ArrayList<>(holder.rollbacks));
  }

  @Override
  public List<PolicyTransitionRecord> activationHistory(String workspaceId, String policyId) {
    Map<String, PolicyHolder> ws = workspaces.get(workspaceId);
    if (ws == null) return List.of();
    PolicyHolder holder = ws.get(policyId);
    if (holder == null) return List.of();
    return Collections.unmodifiableList(new ArrayList<>(holder.transitions));
  }

  @Override
  public Set<String> listPolicyIds(String workspaceId) {
    Map<String, PolicyHolder> ws = workspaces.get(workspaceId);
    if (ws == null) return Set.of();
    return Collections.unmodifiableSet(ws.keySet());
  }

  private static final class PolicyHolder {
    final String policyId;
    final Map<Integer, PolicyVersionRecord> versions = new ConcurrentHashMap<>();
    final AtomicReference<PolicyVersionRecord> activeVersion = new AtomicReference<>();
    final List<Integer> activationHistoryList = new ArrayList<>();
    final List<RollbackRecord> rollbacks = new CopyOnWriteArrayList<>();
    final List<PolicyTransitionRecord> transitions = new CopyOnWriteArrayList<>();
    final AtomicLong sequenceCounter = new AtomicLong();
    final AtomicLong activationSequence = new AtomicLong();

    PolicyHolder(String policyId) {
      this.policyId = policyId;
    }
  }
}
