package io.tracepermit.execution;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable policy binding context recorded when an approval request is created under durable policy mode.
 *
 * <p>Identifies the exact workspace, policy, version, content hash, and monotonic activation generation
 * under which the pending approval was evaluated.
 */
public record ApprovalPolicyBinding(
    String workspaceId,
    String policyId,
    int policyVersion,
    long policyGeneration,
    String policyContentHash,
    Instant boundAt) {

  private static final Pattern WORKSPACE_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}$");
  private static final Pattern POLICY_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}$");
  private static final Pattern HASH_PATTERN =
      Pattern.compile("^sha256:[0-9a-f]{64}$");

  public ApprovalPolicyBinding {
    Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(policyContentHash, "policyContentHash must not be null");
    Objects.requireNonNull(boundAt, "boundAt must not be null");

    if (!WORKSPACE_PATTERN.matcher(workspaceId).matches()) {
      throw new IllegalArgumentException("Invalid workspaceId format: " + workspaceId);
    }
    if (!POLICY_PATTERN.matcher(policyId).matches()) {
      throw new IllegalArgumentException("Invalid policyId format: " + policyId);
    }
    if (policyVersion < 1) {
      throw new IllegalArgumentException("policyVersion must be positive: " + policyVersion);
    }
    if (policyGeneration < 1) {
      throw new IllegalArgumentException("policyGeneration must be positive: " + policyGeneration);
    }
    if (!HASH_PATTERN.matcher(policyContentHash).matches()) {
      throw new IllegalArgumentException("Invalid policyContentHash format: " + policyContentHash);
    }
  }
}
