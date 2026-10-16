package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Storage port for policy revisions and transactional lifecycle state.
 *
 * <p>Implementations must enforce:
 * <ul>
 *   <li>Immutable revisions: existing versions cannot be altered or overwritten.
 *   <li>Idempotent restaging: restaging identical content returns the existing record.
 *   <li>Content conflict rejection: restaging different content under an existing version fails closed.
 *   <li>Atomic activation/rollback: state pointer and history transitions commit in one transaction.
 *   <li>Optimistic concurrency: {@code expectedActiveVersion} preconditions reject competing writes.
 *   <li>Workspace isolation: distinct workspaces have completely isolated catalogs and lifecycles.
 * </ul>
 */
public interface PolicyLifecycleStore {

  /**
   * Stages a validated policy document in the specified workspace.
   *
   * @param workspaceId the trusted workspace or tenant namespace
   * @param policyDocument the policy JSON document
   * @param now timestamp for staging
   * @return the staged or existing identical policy revision record
   * @throws IllegalStateException if an existing version has differing content
   */
  PolicyVersionRecord stage(String workspaceId, JsonNode policyDocument, Instant now);

  /**
   * Activates a staged policy version.
   *
   * @param workspaceId the trusted workspace namespace
   * @param policyId the unique policy identifier
   * @param version the version number to activate
   * @param expectedActiveVersion optional precondition (current active version); null to bypass
   * @param now timestamp for activation
   * @param actorId unauthenticated caller-supplied tracking identifier (nullable)
   * @param reason explanation for the activation
   * @return the activated policy version record
   * @throws PolicyConcurrencyException if {@code expectedActiveVersion} does not match current state
   * @throws IllegalArgumentException if the target policy or version does not exist
   */
  PolicyVersionRecord activate(
      String workspaceId,
      String policyId,
      int version,
      Integer expectedActiveVersion,
      Instant now,
      String actorId,
      String reason);

  /**
   * Rolls back the currently active policy version.
   *
   * @param workspaceId the trusted workspace namespace
   * @param policyId the unique policy identifier
   * @param expectedActiveVersion optional precondition (current active version); null to bypass
   * @param targetVersion explicit version to restore; null to restore immediate predecessor
   * @param now timestamp for rollback
   * @param actorId unauthenticated caller-supplied tracking identifier (nullable)
   * @param reason explanation for the rollback
   * @return the rollback record
   * @throws PolicyConcurrencyException if {@code expectedActiveVersion} does not match current state
   * @throws IllegalStateException if no active version or no previous active version exists
   * @throws IllegalArgumentException if the target version does not exist
   */
  RollbackRecord rollback(
      String workspaceId,
      String policyId,
      Integer expectedActiveVersion,
      Integer targetVersion,
      Instant now,
      String actorId,
      String reason);

  /**
   * Retrieves the currently active version of a policy, if any.
   */
  Optional<PolicyVersionRecord> activeVersion(String workspaceId, String policyId);

  /**
   * Retrieves a specific version of a policy in a workspace.
   */
  Optional<PolicyVersionRecord> getVersion(String workspaceId, String policyId, int version);

  /**
   * Lists all versions of a policy in descending version order.
   */
  List<PolicyVersionRecord> listVersions(String workspaceId, String policyId);

  /**
   * Retrieves the rollback history for a policy.
   */
  List<RollbackRecord> rollbackHistory(String workspaceId, String policyId);

  /**
   * Retrieves the full transition history (activations and rollbacks) for a policy.
   */
  List<PolicyTransitionRecord> activationHistory(String workspaceId, String policyId);

  /**
   * Lists all policy identifiers present in a workspace.
   */
  Set<String> listPolicyIds(String workspaceId);
}
