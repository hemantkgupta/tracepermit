package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable snapshot of the currently active policy revision and its monotonic activation generation.
 *
 * <p>Callers receive defensive copies of the underlying JSON document so mutation of returned nodes
 * cannot affect stored state.
 */
public record ActivePolicySnapshot(
    String workspaceId,
    String policyId,
    int version,
    String contentHash,
    JsonNode document,
    long activationGeneration,
    Instant activatedAt) {

  private static final Pattern WORKSPACE_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}$");
  private static final Pattern POLICY_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}$");
  private static final Pattern HASH_PATTERN =
      Pattern.compile("^sha256:[0-9a-f]{64}$");

  public ActivePolicySnapshot {
    Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(contentHash, "contentHash must not be null");
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(activatedAt, "activatedAt must not be null");

    if (!WORKSPACE_PATTERN.matcher(workspaceId).matches()) {
      throw new IllegalArgumentException("Invalid workspaceId format: " + workspaceId);
    }
    if (!POLICY_PATTERN.matcher(policyId).matches()) {
      throw new IllegalArgumentException("Invalid policyId format: " + policyId);
    }
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive: " + version);
    }
    if (activationGeneration < 1) {
      throw new IllegalArgumentException("activationGeneration must be positive: " + activationGeneration);
    }
    if (!HASH_PATTERN.matcher(contentHash).matches()) {
      throw new IllegalArgumentException("Invalid contentHash format: " + contentHash);
    }
    document = document.deepCopy();
  }

  /** JsonNode is mutable: callers receive a copy, never the stored revision. */
  @Override
  public JsonNode document() {
    return document.deepCopy();
  }

  /** Alias for {@link #activationGeneration()} matching the persistence column name. */
  public long activationSequence() {
    return activationGeneration;
  }
}
