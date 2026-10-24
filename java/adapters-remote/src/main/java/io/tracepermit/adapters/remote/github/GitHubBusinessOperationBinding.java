package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure, versioned binding of stored operation data to the exact pilot target.
 *
 * <p>Neither constructing the expected profile nor binding a snapshot authenticates a caller,
 * proves a database read, approves an action, consumes a permit or claims execution. A future trusted
 * executor must join these data with current authority in its own atomic transaction.
 */
public final class GitHubBusinessOperationBinding {
  public static final String PROFILE = "tracepermit-github-draft-pr-binding-v1";
  public static final String ADAPTER_ID = "github-draft-pr-v1";
  private static final long MAXIMUM_EXACT_INTEGER = 9_007_199_254_740_991L;
  private static final int MAXIMUM_SNAPSHOT_BYTES = 262_144;
  private static final Set<String> ARGUMENTS =
      Set.of("repository", "title", "head", "base", "draft", "body");

  private GitHubBusinessOperationBinding() {}

  /** Operator/service-owned expectations; never deserialize this record from a public body. */
  public record ExpectedProfile(
      String adapterId,
      String environment,
      long recoveryGeneration,
      PolicyRelease policyRelease,
      Map<String, String> artifactDigests,
      GitHubPilotTargetBinding targetBinding) {
    public ExpectedProfile {
      require(ADAPTER_ID.equals(adapterId));
      require(Set.of("dev", "test", "staging").contains(environment == null ? "" : environment));
      require(recoveryGeneration > 0 && recoveryGeneration <= MAXIMUM_EXACT_INTEGER);
      require(policyRelease != null && targetBinding != null);
      artifactDigests = copyArtifacts(artifactDigests);
    }

    public ObjectNode resourceBinding() {
      ObjectNode identity = material("resource_identity");
      identity.put("system", "github");
      identity.put("installationId", targetBinding.installationId());
      identity.put("repositoryId", targetBinding.repositoryId());
      identity.put("ownerId", targetBinding.ownerId());
      identity.put("repository", targetBinding.repository());
      String resourceHash = CanonicalJson.sha256(identity);

      ObjectNode precondition = material("resource_precondition");
      precondition.put("resourceIdHash", resourceHash);
      precondition.put("head", targetBinding.head());
      precondition.put("base", targetBinding.base());
      precondition.put("headSha", targetBinding.headSha());
      precondition.put("baseSha", targetBinding.baseSha());

      ObjectNode binding = JsonSupport.MAPPER.createObjectNode();
      binding.put("system", "github");
      binding.put("environment", environment);
      binding.put("resourceType", "pull_request");
      binding.put("resourceIdHash", resourceHash);
      binding.putObject("precondition")
          .put("kind", "resource_state_digest")
          .put("valueHash", CanonicalJson.sha256(precondition));
      return binding;
    }

    public String fingerprint() {
      ObjectNode profile = material("expected_profile");
      profile.put("adapterId", adapterId);
      profile.put("environment", environment);
      profile.put("recoveryGeneration", recoveryGeneration);
      profile.set("policyRelease", policyRelease.json());
      ObjectNode artifacts = profile.putObject("artifactDigests");
      new TreeMap<>(artifactDigests).forEach(artifacts::put);
      profile.put("targetBindingHash", targetBinding.fingerprint());
      return CanonicalJson.sha256(profile);
    }

    @Override
    public String toString() {
      return "GitHubExpectedOperationProfile[redacted]";
    }
  }

  public record PolicyRelease(String policyId, long version, String contentHash) {
    public PolicyRelease {
      require(identifier(policyId) && version > 0 && version <= MAXIMUM_EXACT_INTEGER);
      require(hash(contentHash));
    }

    private ObjectNode json() {
      return JsonSupport.MAPPER.createObjectNode()
          .put("policyId", policyId)
          .put("version", version)
          .put("contentHash", contentHash);
    }

    private boolean matches(JsonNode validatedPolicy) {
      // The generic validator already requires an integral safe version. Jackson's
      // IntNode/LongNode widths are not different domain versions, unlike JSON strings.
      return policyId.equals(validatedPolicy.path("policyId").textValue())
          && version == validatedPolicy.path("version").longValue()
          && contentHash.equals(validatedPolicy.path("contentHash").textValue());
    }

    @Override
    public String toString() {
      return "GitHubExpectedPolicyRelease[redacted]";
    }
  }

  /** Data only. Even an approved/succeeded label in this snapshot conveys no authority. */
  public static final class BoundOperationData {
    private final JsonNode snapshot;
    private final GitHubPilotTargetBinding targetBinding;
    private final String profileFingerprint;
    private final String bindingDigest;

    private BoundOperationData(
        JsonNode snapshot, BusinessOperationScope scope, ExpectedProfile expected) {
      this.snapshot = snapshot.deepCopy();
      targetBinding = expected.targetBinding();
      profileFingerprint = expected.fingerprint();
      ObjectNode data = material("bound_operation_data");
      data.put("scopeHash", scope.fingerprint());
      data.put("operationSnapshotHash", CanonicalJson.sha256(snapshot));
      data.put("expectedProfileHash", profileFingerprint);
      data.put("targetBindingHash", targetBinding.fingerprint());
      bindingDigest = CanonicalJson.sha256(data);
    }

    public JsonNode snapshot() {
      return snapshot.deepCopy();
    }

    public JsonNode canonicalAction() {
      return snapshot.path("canonicalAction").deepCopy();
    }

    public String canonicalActionHash() {
      return snapshot.path("canonicalActionHash").textValue();
    }

    public String operationId() {
      return snapshot.path("operationId").textValue();
    }

    public long recoveryGeneration() {
      return snapshot.path("recoveryGeneration").longValue();
    }

    public String targetIdempotencyKeyHash() {
      return snapshot.path("targetIdempotencyKeyHash").textValue();
    }

    public GitHubPilotTargetBinding targetBinding() {
      return targetBinding;
    }

    public String profileFingerprint() {
      return profileFingerprint;
    }

    public String bindingDigest() {
      return bindingDigest;
    }

    @Override
    public String toString() {
      return "GitHubBoundOperationData[redacted; no execution authority]";
    }
  }

  /** Fixed diagnostics intentionally retain no parser/argument values or suppressed causes. */
  public static final class BindingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private BindingException() {
      super("GITHUB_OPERATION_BINDING_INVALID", null, false, false);
    }

    public String code() {
      return "GITHUB_OPERATION_BINDING_INVALID";
    }
  }

  public static BoundOperationData bind(
      BusinessOperationScope trustedScope, JsonNode storedOperation, ExpectedProfile expected) {
    try {
      require(trustedScope != null && expected != null && storedOperation != null);
      // Validate our own snapshot, not a caller-owned tree that could change after hash checks.
      JsonNode snapshot = storedOperation.deepCopy();
      require(snapshot.toString().getBytes(StandardCharsets.UTF_8).length <= MAXIMUM_SNAPSHOT_BYTES);
      JsonNode operation = BusinessOperationValidator.validate(snapshot);
      require(trustedScope.trustedContext().equals(operation.path("trustedContext")));
      require(expected.adapterId().equals(operation.path("adapterId").textValue()));
      require(expected.recoveryGeneration() == operation.path("recoveryGeneration").longValue());
      require(expected.policyRelease().matches(operation.path("policyRelease")));
      require(expected.artifactDigests().equals(artifactMap(operation.path("artifactDigests"))));
      require(expected.resourceBinding().equals(operation.path("resourceBinding")));

      JsonNode event = operation.path("canonicalAction");
      JsonNode action = event.path("action");
      require("http".equals(action.path("protocol").textValue()));
      JsonNode target = action.path("target");
      require(expected.environment().equals(target.path("environment").textValue()));
      require(expected.targetBinding().repository().equals(target.path("accountId").textValue()));

      JsonNode arguments = action.path("arguments");
      arguments.fieldNames().forEachRemaining(field -> require(ARGUMENTS.contains(field)));
      require(expected.targetBinding().repository().equals(arguments.path("repository").textValue()));
      GitHubDraftPullRequestAction dispatch = GitHubDraftPullRequestAction.fromCanonical(event);
      expected.targetBinding().requireAction(dispatch);
      require(dispatch.title().equals(arguments.path("title").textValue()));
      require(dispatch.head().equals(arguments.path("head").textValue()));
      require(dispatch.base().equals(arguments.path("base").textValue()));
      require(dispatch.repository().equals(arguments.path("repository").textValue()));
      return new BoundOperationData(operation, trustedScope, expected);
    } catch (RuntimeException rejected) {
      throw new BindingException();
    }
  }

  private static Map<String, String> artifactMap(JsonNode artifacts) {
    Map<String, String> digests = new TreeMap<>();
    for (JsonNode artifact : artifacts) {
      digests.put(artifact.path("kind").textValue(), artifact.path("digest").textValue());
    }
    return digests;
  }

  private static Map<String, String> copyArtifacts(Map<String, String> artifacts) {
    try {
      require(artifacts != null);
      Map<String, String> copy = Map.copyOf(artifacts);
      require(!copy.isEmpty());
      for (Map.Entry<String, String> artifact : copy.entrySet()) {
        require(identifier(artifact.getKey()) && hash(artifact.getValue()));
      }
      return copy;
    } catch (RuntimeException rejected) {
      throw new BindingException();
    }
  }

  private static ObjectNode material(String kind) {
    return JsonSupport.MAPPER.createObjectNode().put("profile", PROFILE).put("kind", kind);
  }

  private static boolean identifier(String value) {
    return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,255}");
  }

  private static boolean hash(String value) {
    return value != null && value.matches("sha256:[a-f0-9]{64}");
  }

  private static void require(boolean condition) {
    if (!condition) throw new BindingException();
  }
}
