package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure synthetic data binding. No credentials, signing, database, transport or target are used. */
final class GitHubBusinessOperationBindingTest {
  private static final String REPOSITORY = "tracepermit/agentcore-pr-pilot";
  private static final String HEAD = "synthetic-change";
  private static final String HASH_5 = "sha256:" + "5".repeat(64);
  private static final String HASH_6 = "sha256:" + "6".repeat(64);
  private static final String HASH_7 = "sha256:" + "7".repeat(64);
  private static final String HASH_8 = "sha256:" + "8".repeat(64);
  private static final long MAXIMUM_EXACT_INTEGER = 9_007_199_254_740_991L;
  private static final BusinessOperationScope SCOPE =
      new BusinessOperationScope(
          "synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private static final GitHubPilotTargetBinding TARGET =
      new GitHubPilotTargetBinding(
          71, 101, 201, REPOSITORY, HEAD, "main", "a".repeat(40), "b".repeat(40));

  @Test
  void acceptsExactStoredDataAndPreservesEveryDistinctIdentity() throws Exception {
    var expected = profile();
    ObjectNode source = operation(expected);
    // A real parsed JSON document uses IntNode for small values, unlike put(String,long).
    ObjectNode parsed = (ObjectNode) JsonSupport.MAPPER.readTree(source.toString());
    assertThat(parsed.path("policyRelease").path("version").isInt()).isTrue();

    var bound = GitHubBusinessOperationBinding.bind(SCOPE, parsed, expected);

    assertThat(bound.snapshot()).isEqualTo(parsed).isNotSameAs(parsed);
    assertThat(bound.canonicalAction()).isEqualTo(parsed.path("canonicalAction"));
    assertThat(bound.canonicalActionHash()).isEqualTo(parsed.path("canonicalActionHash").textValue());
    assertThat(bound.operationId()).isEqualTo(parsed.path("operationId").textValue());
    assertThat(bound.recoveryGeneration()).isEqualTo(1);
    assertThat(bound.targetIdempotencyKeyHash())
        .isEqualTo(parsed.path("targetIdempotencyKeyHash").textValue())
        .isNotEqualTo(
            GitHubDraftPullRequestAdapter.computeOperationKey(71, REPOSITORY, HEAD, "main"));
    assertThat(bound.targetBinding()).isEqualTo(TARGET);
    assertThat(bound.profileFingerprint()).isEqualTo(expected.fingerprint());
    assertThat(bound.bindingDigest()).matches("sha256:[a-f0-9]{64}");
    assertThat(bound.toString()).contains("no execution authority");
  }

  @Test
  void frozenBridgeRecipeMatchesIndependentLanguageNeutralGoldenHashes() {
    Path root = Path.of(System.getProperty("tracepermit.repositoryRoot"));
    JsonNode vector = JsonSupport.read(root.resolve("contracts/vectors/github-business-operation-binding-v1.json"));
    JsonNode tuple = vector.path("targetBinding");
    GitHubPilotTargetBinding target = new GitHubPilotTargetBinding(
        tuple.path("installationId").longValue(), tuple.path("repositoryId").longValue(),
        tuple.path("ownerId").longValue(), tuple.path("repository").textValue(),
        tuple.path("head").textValue(), tuple.path("base").textValue(),
        tuple.path("headSha").textValue(), tuple.path("baseSha").textValue());
    JsonNode configured = vector.path("expectedProfile");
    JsonNode release = configured.path("policyRelease");
    Map<String, String> digests = new java.util.TreeMap<>();
    configured.path("artifactDigests").fields().forEachRemaining(
        entry -> digests.put(entry.getKey(), entry.getValue().textValue()));
    var expected = new GitHubBusinessOperationBinding.ExpectedProfile(
        configured.path("adapterId").textValue(), configured.path("environment").textValue(),
        configured.path("recoveryGeneration").longValue(),
        new GitHubBusinessOperationBinding.PolicyRelease(release.path("policyId").textValue(),
            release.path("version").longValue(), release.path("contentHash").textValue()),
        digests, target);
    assertThat(target).isEqualTo(TARGET);
    assertThat(expected.fingerprint()).isEqualTo(profile().fingerprint());
    ObjectNode source = operation(expected);
    ArrayNode ordered = source.putArray("artifactDigests");
    new java.util.TreeMap<>(expected.artifactDigests()).forEach(
        (kind, digest) -> ordered.addObject().put("kind", kind).put("digest", digest));
    var bound = GitHubBusinessOperationBinding.bind(SCOPE, source, expected);
    JsonNode hashes = vector.path("expected");
    assertThat(vector.path("profile").textValue()).isEqualTo(GitHubBusinessOperationBinding.PROFILE);
    assertThat(expected.resourceBinding()).isEqualTo(vector.path("resourceBinding"));
    assertThat(target.fingerprint()).isEqualTo(hashes.path("targetBindingHash").textValue());
    assertThat(bound.profileFingerprint()).isEqualTo(hashes.path("profileFingerprint").textValue());
    assertThat(source.path("resourceBindingHash").textValue()).isEqualTo(hashes.path("resourceBindingHash").textValue());
    assertThat(bound.canonicalActionHash()).isEqualTo(hashes.path("canonicalActionHash").textValue());
    assertThat(source.path("semanticPayloadDigest").textValue()).isEqualTo(hashes.path("semanticPayloadDigest").textValue());
    assertThat(bound.targetIdempotencyKeyHash()).isEqualTo(hashes.path("targetIdempotencyKeyHash").textValue());
    assertThat(CanonicalJson.sha256(bound.snapshot())).isEqualTo(hashes.path("operationSnapshotHash").textValue());
    assertThat(bound.bindingDigest()).isEqualTo(hashes.path("bindingDigest").textValue());
  }

  @Test
  void oversizedUtf8SnapshotsAreRejectedBeforeBecomingBoundData() {
    var expected = profile();
    ObjectNode source = operation(expected);
    ((ObjectNode) source.path("canonicalAction").path("clientClaims"))
        .put("synthetic_untrusted_data", "\u20ac".repeat(90_000));
    refreshHashes(source);
    assertThatCode(() -> BusinessOperationValidator.validate(source)).doesNotThrowAnyException();
    assertRejected(SCOPE, source, expected);
  }

  @Test
  void exactUtf8LimitAcceptsAndOneExtraByteRejectsWithoutChangingDomainBounds() {
    var expected = profile();
    ObjectNode source = operation(expected);
    ObjectNode claims = (ObjectNode) source.path("canonicalAction").path("clientClaims");
    claims.put("synthetic_padding", "\u20ac");
    refreshHashes(source);
    int overhead = source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 3;
    int paddingBytes = 262_144 - overhead;
    String padding = "\u20ac".repeat(paddingBytes / 3) + "x".repeat(paddingBytes % 3);
    claims.put("synthetic_padding", padding);
    refreshHashes(source);
    assertThat(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(262_144);
    assertThatCode(() -> GitHubBusinessOperationBinding.bind(SCOPE, source, expected))
        .doesNotThrowAnyException();

    claims.put("synthetic_padding", padding + "x");
    refreshHashes(source);
    assertThat(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(262_145);
    assertThatCode(() -> BusinessOperationValidator.validate(source)).doesNotThrowAnyException();
    assertRejected(SCOPE, source, expected);
  }

  @Test
  void rejectionCannotLaterAcquireSensitiveCauseOrSuppressedCleanupData() {
    try {
      GitHubBusinessOperationBinding.bind(SCOPE, null, profile());
      throw new AssertionError("expected fixed binding rejection");
    } catch (GitHubBusinessOperationBinding.BindingException failure) {
      failure.addSuppressed(new IllegalStateException("synthetic-sensitive-cleanup-marker"));
      assertThatThrownBy(() -> failure.initCause(new IllegalStateException("synthetic-sensitive-cause-marker")))
          .isInstanceOf(IllegalStateException.class);
      fixedDiagnostic(failure);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "test", "staging"})
  void supportsOnlyAnExplicitMatchingNonProductionProfile(String environment) {
    var expected = profile(environment, 1, policy(), artifacts(), TARGET);
    var bound = GitHubBusinessOperationBinding.bind(SCOPE, operation(expected), expected);
    assertThat(bound.snapshot().path("resourceBinding").path("environment").textValue())
        .isEqualTo(environment);
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "empty", "text"})
  void optionalBodyRemainsExactWithoutChangingTheStoredAction(String bodyKind) {
    var expected = profile();
    ObjectNode source = operation(expected);
    if (!"absent".equals(bodyKind)) arguments(source).put("body", "empty".equals(bodyKind) ? "" : "  exact body\n");
    refreshHashes(source);
    var bound = GitHubBusinessOperationBinding.bind(SCOPE, source, expected);
    assertThat(bound.canonicalAction()).isEqualTo(source.path("canonicalAction"));
    assertThat(bound.canonicalAction().path("action").path("arguments").has("body"))
        .isEqualTo(!"absent".equals(bodyKind));
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "workspace", "requester"})
  void separatelySuppliedScopeMustMatchEveryStoredPartition(String field) {
    BusinessOperationScope other = switch (field) {
      case "tenant" -> new BusinessOperationScope("other-tenant", SCOPE.workspaceId(), SCOPE.requesterPrincipalId());
      case "workspace" -> new BusinessOperationScope(SCOPE.tenantId(), "other-workspace", SCOPE.requesterPrincipalId());
      case "requester" -> new BusinessOperationScope(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:other");
      default -> throw new AssertionError();
    };
    var expected = profile();
    assertRejected(other, operation(expected), expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "workspace", "requester"})
  void coordinatedStoredIdentityRebindingStillCannotChangeTheTrustedScope(String field) {
    var expected = profile();
    ObjectNode source = operation(expected);
    ObjectNode context = (ObjectNode) source.path("trustedContext");
    ObjectNode event = (ObjectNode) source.path("canonicalAction");
    switch (field) {
      case "tenant" -> {
        context.put("tenantId", "other-tenant");
        ((ObjectNode) event.path("identity").path("subject")).put("organizationId", "other-tenant");
      }
      case "workspace" -> context.put("workspaceId", "other-workspace");
      case "requester" -> {
        context.put("requesterPrincipalId", "principal:other");
        ((ObjectNode) event.path("identity").path("subject")).put("id", "principal:other");
        ((ObjectNode) event.path("identity").path("evidence").get(0)).put("subjectId", "principal:other");
        ((ObjectNode) event.path("lineage")).put("rootPrincipalId", "principal:other");
        ((ObjectNode) event.path("lineage").path("hops").get(0)).put("subjectId", "principal:other");
      }
      default -> throw new AssertionError(field);
    }
    refreshHashes(source);
    assertThatCode(() -> BusinessOperationValidator.validate(source)).doesNotThrowAnyException();
    assertRejected(SCOPE, source, expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "adapter", "generation", "policy_id", "policy_version", "policy_content", "artifact_digest",
      "artifact_kind", "artifact_extra", "artifact_missing", "resource_hash", "precondition_hash",
      "precondition_kind", "precondition_none", "environment", "system", "resource_type", "protocol",
      "tool", "repository", "account", "head", "base", "owner_alias", "repo_alias", "draft_string",
      "draft_false", "draft_missing", "title_boolean", "body_null", "body_boolean", "body_number",
      "body_object", "body_array", "unknown_argument", "authenticated", "delegated", "verified"
  })
  void genericallyValidRecomputedSnapshotsCannotBypassTheExactProfile(String change) {
    var expected = profile();
    ObjectNode source = operation(expected);
    ObjectNode binding = (ObjectNode) source.path("resourceBinding");
    ObjectNode precondition = (ObjectNode) binding.path("precondition");
    ObjectNode policy = (ObjectNode) source.path("policyRelease");
    ArrayNode artifacts = (ArrayNode) source.path("artifactDigests");
    switch (change) {
      case "adapter" -> source.put("adapterId", "other-adapter-v1");
      case "generation" -> source.put("recoveryGeneration", 2);
      case "policy_id" -> policy.put("policyId", "synthetic-other-policy");
      case "policy_version" -> policy.put("version", 2);
      case "policy_content" -> policy.put("contentHash", HASH_8);
      case "artifact_digest" -> ((ObjectNode) artifacts.get(0)).put("digest", HASH_8);
      case "artifact_kind" -> ((ObjectNode) artifacts.get(0)).put("kind", "other_bundle");
      case "artifact_extra" -> artifacts.addObject().put("kind", "unexpected").put("digest", HASH_8);
      case "artifact_missing" -> artifacts.remove(0);
      case "resource_hash" -> binding.put("resourceIdHash", HASH_8);
      case "precondition_hash" -> precondition.put("valueHash", HASH_8);
      case "precondition_kind" -> precondition.put("kind", "commit_sha");
      case "precondition_none" -> { precondition.put("kind", "none"); precondition.remove("valueHash"); }
      case "environment" -> { target(source).put("environment", "prod"); binding.put("environment", "prod"); }
      case "system" -> { target(source).put("system", "other"); binding.put("system", "other"); }
      case "resource_type" -> { target(source).put("resourceType", "repository"); binding.put("resourceType", "repository"); }
      case "protocol" -> action(source).put("protocol", "mcp");
      case "tool" -> action(source).put("toolName", "github.pull_request.merge");
      case "repository" -> arguments(source).put("repository", "tracepermit/other");
      case "account" -> target(source).put("accountId", "tracepermit/other");
      case "head" -> arguments(source).put("head", "other-head");
      case "base" -> arguments(source).put("base", "other-base");
      case "owner_alias" -> arguments(source).put("owner", "tracepermit");
      case "repo_alias" -> arguments(source).put("repo", "agentcore-pr-pilot");
      case "draft_string" -> arguments(source).put("draft", "true");
      case "draft_false" -> arguments(source).put("draft", false);
      case "draft_missing" -> arguments(source).remove("draft");
      case "title_boolean" -> arguments(source).put("title", true);
      case "body_null" -> arguments(source).putNull("body");
      case "body_boolean" -> arguments(source).put("body", true);
      case "body_number" -> arguments(source).put("body", 17);
      case "body_object" -> arguments(source).putObject("body");
      case "body_array" -> arguments(source).putArray("body");
      case "unknown_argument" -> arguments(source).put("installationId", 71);
      case "authenticated", "delegated", "verified" -> arguments(source).put(change, true);
      default -> throw new AssertionError(change);
    }
    refreshHashes(source);
    assertThatCode(() -> BusinessOperationValidator.validate(source)).doesNotThrowAnyException();
    assertRejected(SCOPE, source, expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"installation", "repository_id", "owner_id", "head", "head_sha", "base_sha"})
  void changedTrustedTargetCannotBorrowAnEarlierStoredResourceBinding(String change) {
    var expected = profile();
    GitHubPilotTargetBinding changed = switch (change) {
      case "installation" -> target(72, 101, 201, HEAD, "a".repeat(40), "b".repeat(40));
      case "repository_id" -> target(71, 102, 201, HEAD, "a".repeat(40), "b".repeat(40));
      case "owner_id" -> target(71, 101, 202, HEAD, "a".repeat(40), "b".repeat(40));
      case "head" -> target(71, 101, 201, "other-head", "a".repeat(40), "b".repeat(40));
      case "head_sha" -> target(71, 101, 201, HEAD, "c".repeat(40), "b".repeat(40));
      case "base_sha" -> target(71, 101, 201, HEAD, "a".repeat(40), "c".repeat(40));
      default -> throw new AssertionError(change);
    };
    var changedProfile = profile("test", 1, policy(), artifacts(), changed);
    ObjectNode original = operation(expected);
    assertRejected(SCOPE, original, changedProfile);

    ObjectNode rebound = operation(changedProfile);
    assertThatCode(() -> BusinessOperationValidator.validate(rebound)).doesNotThrowAnyException();
    assertRejected(SCOPE, rebound, expected);
    assertThat(changedProfile.fingerprint()).isNotEqualTo(expected.fingerprint());
    assertThat(changedProfile.resourceBinding()).isNotEqualTo(expected.resourceBinding());
  }

  @ParameterizedTest
  @ValueSource(strings = {"title_space", "head_space", "base_space", "repository_case", "repository_space", "account_space"})
  void domainNormalizationMustNotRewriteAnAlreadyBoundAction(String change) {
    var expected = profile();
    ObjectNode source = operation(expected);
    switch (change) {
      case "title_space" -> arguments(source).put("title", "  Synthetic governed change  ");
      case "head_space" -> arguments(source).put("head", " " + HEAD + " ");
      case "base_space" -> arguments(source).put("base", " main ");
      case "repository_case" -> arguments(source).put("repository", "TracePermit/agentcore-pr-pilot");
      case "repository_space" -> arguments(source).put("repository", " " + REPOSITORY + " ");
      case "account_space" -> target(source).put("accountId", " " + REPOSITORY + " ");
      default -> throw new AssertionError(change);
    }
    refreshHashes(source);
    assertThatCode(() -> BusinessOperationValidator.validate(source)).doesNotThrowAnyException();
    assertRejected(SCOPE, source, expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"root_schema", "action_schema", "root_unknown", "action_unknown", "target_unknown", "trusted_claim", "canonical_hash", "semantic_hash", "resource_hash", "target_key"})
  void malformedOrUnboundRawSnapshotsHaveOnlyFixedDiagnostics(String change) {
    var expected = profile();
    ObjectNode source = operation(expected);
    switch (change) {
      case "root_schema" -> source.put("schemaVersion", "2.0");
      case "action_schema" -> ((ObjectNode) source.path("canonicalAction")).put("schemaVersion", "2.0");
      case "root_unknown" -> source.put("authenticated", true);
      case "action_unknown" -> ((ObjectNode) source.path("canonicalAction")).put("verified", true);
      case "target_unknown" -> target(source).put("installationId", 71);
      case "trusted_claim" -> ((ObjectNode) source.path("trustedContext")).put("authenticated", true);
      case "canonical_hash" -> source.put("canonicalActionHash", HASH_8);
      case "semantic_hash" -> source.put("semanticPayloadDigest", HASH_8);
      case "resource_hash" -> source.put("resourceBindingHash", HASH_8);
      case "target_key" -> source.put("targetIdempotencyKeyHash", HASH_8);
      default -> throw new AssertionError(change);
    }
    assertRejected(SCOPE, source, expected);
  }

  @Test
  void frozenGenericVectorIsPreservedButNotSilentlyReinterpretedAsAGitHubBinding() {
    ObjectNode generic = genericOperation();
    String before = generic.toString();
    assertThatCode(() -> BusinessOperationValidator.validate(generic)).doesNotThrowAnyException();
    assertRejected(SCOPE, generic, profile());
    assertThat(generic.toString()).isEqualTo(before);
    assertThat(generic.path("resourceBinding").path("resourceType").textValue()).isEqualTo("repository");
  }

  @Test
  void profileAndBoundDataAreDefensiveInBothDirections() {
    Map<String, String> mutableArtifacts = new HashMap<>(artifacts());
    var expected = profile("test", 1, policy(), mutableArtifacts, TARGET);
    ObjectNode source = operation(expected);
    var bound = GitHubBusinessOperationBinding.bind(SCOPE, source, expected);
    JsonNode original = bound.snapshot();
    String fingerprint = expected.fingerprint();
    String digest = bound.bindingDigest();

    mutableArtifacts.put("policy_bundle", HASH_8);
    mutableArtifacts.put("extra", HASH_8);
    expected.resourceBinding().put("resourceIdHash", HASH_8);
    arguments(source).put("title", "caller mutation");
    ((ObjectNode) bound.snapshot()).put("operationId", "caller mutation");
    ((ObjectNode) bound.canonicalAction().path("action").path("arguments")).put("title", "result mutation");

    assertThat(expected.artifactDigests()).isEqualTo(artifacts());
    assertThat(expected.fingerprint()).isEqualTo(fingerprint);
    assertThat(bound.snapshot()).isEqualTo(original);
    assertThat(bound.canonicalAction()).isEqualTo(original.path("canonicalAction"));
    assertThat(bound.bindingDigest()).isEqualTo(digest);
    assertThatThrownBy(() -> expected.artifactDigests().put("other", HASH_8))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void artifactOrderingIsNotAuthorityAndDoesNotChangeTheExpectedProfileFingerprint() {
    var expected = profile();
    ObjectNode source = operation(expected);
    ArrayNode artifacts = (ArrayNode) source.path("artifactDigests");
    JsonNode first = artifacts.remove(0);
    artifacts.add(first);
    var reordered = profile("test", 1, policy(),
        Map.of("adapter_release", HASH_7, "policy_bundle", HASH_6), TARGET);
    assertThat(reordered.fingerprint()).isEqualTo(expected.fingerprint());
    assertThatCode(() -> GitHubBusinessOperationBinding.bind(SCOPE, source, reordered))
        .doesNotThrowAnyException();
  }

  @Test
  void validContentOperationAndGenerationChangesProduceDifferentDataDigests() {
    var expected = profile();
    ObjectNode original = operation(expected);
    var first = GitHubBusinessOperationBinding.bind(SCOPE, original, expected);
    ObjectNode content = original.deepCopy();
    arguments(content).put("title", "A different exact title").put("body", "Different exact content");
    refreshHashes(content);
    var second = GitHubBusinessOperationBinding.bind(SCOPE, content, expected);
    assertThat(second.canonicalActionHash()).isNotEqualTo(first.canonicalActionHash());
    assertThat(second.bindingDigest()).isNotEqualTo(first.bindingDigest());
    assertThat(second.profileFingerprint()).isEqualTo(first.profileFingerprint());

    ObjectNode otherId = original.deepCopy();
    otherId.put("operationId", "018f47a2-f5ce-7a4b-8f54-80e5d3eaf299");
    refreshHashes(otherId);
    var third = GitHubBusinessOperationBinding.bind(SCOPE, otherId, expected);
    assertThat(third.canonicalActionHash()).isEqualTo(first.canonicalActionHash());
    assertThat(third.targetIdempotencyKeyHash()).isNotEqualTo(first.targetIdempotencyKeyHash());
    assertThat(third.bindingDigest()).isNotEqualTo(first.bindingDigest());

    var nextGeneration = profile("test", 2, policy(), artifacts(), TARGET);
    ObjectNode recovered = operation(nextGeneration);
    assertRejected(SCOPE, recovered, expected);
    var fourth = GitHubBusinessOperationBinding.bind(SCOPE, recovered, nextGeneration);
    assertThat(fourth.targetIdempotencyKeyHash()).isNotEqualTo(first.targetIdempotencyKeyHash());
    assertThat(fourth.profileFingerprint()).isNotEqualTo(first.profileFingerprint());
    assertThat(fourth.bindingDigest()).isNotEqualTo(first.bindingDigest());
  }

  @Test
  void largestAllowedAdjacentIdentifiersAndPolicyVersionsRemainDistinct() {
    var upperTarget = target(71, MAXIMUM_EXACT_INTEGER, 201, HEAD, "a".repeat(40), "b".repeat(40));
    var lowerTarget = target(71, MAXIMUM_EXACT_INTEGER - 1, 201, HEAD, "a".repeat(40), "b".repeat(40));
    var upper = profile("test", 1, policy(), artifacts(), upperTarget);
    var lower = profile("test", 1, policy(), artifacts(), lowerTarget);
    assertThat(upper.resourceBinding()).isNotEqualTo(lower.resourceBinding());
    assertThat(upper.fingerprint()).isNotEqualTo(lower.fingerprint());
    var upperBound = GitHubBusinessOperationBinding.bind(SCOPE, operation(upper), upper);
    var lowerBound = GitHubBusinessOperationBinding.bind(SCOPE, operation(lower), lower);
    assertThat(upperBound.bindingDigest()).isNotEqualTo(lowerBound.bindingDigest());
    assertRejected(SCOPE, operation(upper), lower);

    var policyUpper = new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", MAXIMUM_EXACT_INTEGER, HASH_5);
    var policyLower = new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", MAXIMUM_EXACT_INTEGER - 1, HASH_5);
    var expectedUpper = profile("test", 1, policyUpper, artifacts(), TARGET);
    var expectedLower = profile("test", 1, policyLower, artifacts(), TARGET);
    assertThat(expectedUpper.fingerprint()).isNotEqualTo(expectedLower.fingerprint());
    assertThatCode(() -> GitHubBusinessOperationBinding.bind(SCOPE, operation(expectedUpper), expectedUpper))
        .doesNotThrowAnyException();
    assertRejected(SCOPE, operation(expectedUpper), expectedLower);
  }

  @ParameterizedTest
  @ValueSource(strings = {"awaiting_approval", "approved", "claimed", "executing", "unknown", "succeeded", "failed", "cancelled"})
  void structurallyValidLifecycleLabelsAreDataAndNeverAuthority(String state) {
    var expected = profile();
    ObjectNode source = operation(expected);
    var planner = new BusinessOperationTransitionPlanner();
    if (!"awaiting_approval".equals(state)) {
      if ("cancelled".equals(state)) {
        source = progress(planner, source, BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION, "cancelled");
      } else {
        source = progress(planner, source, BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION, "approved");
        if (!"approved".equals(state)) {
          source = progress(planner, source, BusinessOperationTransitionPlanner.Dimension.EXECUTION, "claimed");
          if (!"claimed".equals(state)) {
            source = progress(planner, source, BusinessOperationTransitionPlanner.Dimension.EXECUTION, "executing");
            if (!"executing".equals(state)) {
              source = progress(planner, source, BusinessOperationTransitionPlanner.Dimension.EXECUTION, state);
            }
          }
        }
      }
    }
    var bound = GitHubBusinessOperationBinding.bind(SCOPE, source, expected);
    assertThat(bound.snapshot()).isEqualTo(source);
    assertThat(bound.toString()).contains("no execution authority");
  }

  @ParameterizedTest
  @ValueSource(strings = {"prod", "unknown", "adapter", "zero_generation", "unsafe_generation", "null_policy", "null_artifacts", "empty_artifacts", "artifact_key", "artifact_hash", "null_target"})
  void invalidTrustedProfilesFailClosedWithoutRelaxingThePilot(String change) {
    assertThatThrownBy(() -> {
      switch (change) {
        case "prod", "unknown" -> profile(change, 1, policy(), artifacts(), TARGET);
        case "adapter" -> new GitHubBusinessOperationBinding.ExpectedProfile("other-adapter", "test", 1, policy(), artifacts(), TARGET);
        case "zero_generation" -> profile("test", 0, policy(), artifacts(), TARGET);
        case "unsafe_generation" -> profile("test", MAXIMUM_EXACT_INTEGER + 1, policy(), artifacts(), TARGET);
        case "null_policy" -> profile("test", 1, null, artifacts(), TARGET);
        case "null_artifacts" -> profile("test", 1, policy(), null, TARGET);
        case "empty_artifacts" -> profile("test", 1, policy(), Map.of(), TARGET);
        case "artifact_key" -> profile("test", 1, policy(), Map.of("not allowed", HASH_6), TARGET);
        case "artifact_hash" -> profile("test", 1, policy(), Map.of("policy_bundle", "sensitive-invalid-value"), TARGET);
        case "null_target" -> profile("test", 1, policy(), artifacts(), null);
        default -> throw new AssertionError(change);
      }
    }).isInstanceOfSatisfying(GitHubBusinessOperationBinding.BindingException.class, GitHubBusinessOperationBindingTest::fixedDiagnostic);
  }

  @Test
  void policyReleaseRejectsInvalidIdentifiersVersionsAndDigests() {
    assertThatThrownBy(() -> new GitHubBusinessOperationBinding.PolicyRelease("bad id", 1, HASH_5))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);
    assertThatThrownBy(() -> new GitHubBusinessOperationBinding.PolicyRelease("synthetic", 0, HASH_5))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);
    assertThatThrownBy(() -> new GitHubBusinessOperationBinding.PolicyRelease("synthetic", MAXIMUM_EXACT_INTEGER + 1, HASH_5))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);
    assertThatThrownBy(() -> new GitHubBusinessOperationBinding.PolicyRelease("synthetic", 1, "invalid"))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);
  }

  @Test
  void absentInputsAndSensitiveValuesNeverEscapeThroughDiagnosticObjects() {
    var expected = profile();
    ObjectNode source = operation(expected);
    assertRejected(null, source, expected);
    assertRejected(SCOPE, null, expected);
    assertRejected(SCOPE, source, null);
    String sensitive = "synthetic-private-marker-not-a-credential";
    arguments(source).put("body", sensitive);
    refreshHashes(source);
    var bound = GitHubBusinessOperationBinding.bind(SCOPE, source, expected);
    assertThat(bound.toString()).doesNotContain(sensitive, REPOSITORY, SCOPE.tenantId(), HASH_5);
    assertThat(expected.toString()).doesNotContain(REPOSITORY, HASH_5);
    assertThat(expected.policyRelease().toString()).doesNotContain(HASH_5, "synthetic-github-draft-pr");
    arguments(source).put("authenticated", sensitive);
    refreshHashes(source);
    assertRejected(SCOPE, source, expected);
  }

  private static ObjectNode progress(
      BusinessOperationTransitionPlanner planner,
      ObjectNode current,
      BusinessOperationTransitionPlanner.Dimension dimension,
      String status) {
    Instant next = Instant.parse(current.path("updatedAt").textValue()).plusMillis(1);
    return (ObjectNode) planner.propose(SCOPE, current, planner.observe(SCOPE, current), dimension, status,
        "SYNTHETIC_DATA_ONLY", next).snapshot();
  }

  private static GitHubBusinessOperationBinding.ExpectedProfile profile() {
    return profile("test", 1, policy(), artifacts(), TARGET);
  }

  private static GitHubBusinessOperationBinding.ExpectedProfile profile(
      String environment,
      long generation,
      GitHubBusinessOperationBinding.PolicyRelease policy,
      Map<String, String> artifacts,
      GitHubPilotTargetBinding target) {
    return new GitHubBusinessOperationBinding.ExpectedProfile(
        GitHubBusinessOperationBinding.ADAPTER_ID, environment, generation, policy, artifacts, target);
  }

  private static GitHubBusinessOperationBinding.PolicyRelease policy() {
    return new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", 1, HASH_5);
  }

  private static Map<String, String> artifacts() {
    return Map.of("policy_bundle", HASH_6, "adapter_release", HASH_7);
  }

  private static GitHubPilotTargetBinding target(
      long installation, long repository, long owner, String head, String headSha, String baseSha) {
    return new GitHubPilotTargetBinding(installation, repository, owner, REPOSITORY, head, "main", headSha, baseSha);
  }

  private static ObjectNode genericOperation() {
    Path root = Path.of(System.getProperty("tracepermit.repositoryRoot"));
    return (ObjectNode) JsonSupport.read(root.resolve("contracts/vectors/business-operation-v1.json"))
        .path("operation").deepCopy();
  }

  private static ObjectNode operation(GitHubBusinessOperationBinding.ExpectedProfile expected) {
    ObjectNode operation = genericOperation();
    arguments(operation).remove("owner");
    arguments(operation).put("repository", expected.targetBinding().repository());
    arguments(operation).put("head", expected.targetBinding().head());
    arguments(operation).put("base", expected.targetBinding().base());
    target(operation).put("accountId", expected.targetBinding().repository());
    target(operation).put("resourceType", "pull_request");
    target(operation).put("environment", expected.environment());
    operation.put("adapterId", expected.adapterId());
    operation.put("recoveryGeneration", expected.recoveryGeneration());
    operation.set("resourceBinding", expected.resourceBinding());
    operation.putObject("policyRelease")
        .put("policyId", expected.policyRelease().policyId())
        .put("version", expected.policyRelease().version())
        .put("contentHash", expected.policyRelease().contentHash());
    ArrayNode artifacts = operation.putArray("artifactDigests");
    expected.artifactDigests().forEach((kind, digest) -> artifacts.addObject().put("kind", kind).put("digest", digest));
    refreshHashes(operation);
    return operation;
  }

  private static void refreshHashes(ObjectNode operation) {
    operation.put("canonicalActionHash", CanonicalJson.sha256(operation.path("canonicalAction")));
    operation.put("semanticPayloadDigest", CanonicalJson.sha256(operation.path("canonicalAction").path("action")));
    operation.put("resourceBindingHash", CanonicalJson.sha256(operation.path("resourceBinding")));
    operation.put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(
        operation.path("operationId").textValue(), operation.path("adapterId").textValue(), operation.path("recoveryGeneration").longValue()));
  }

  private static ObjectNode action(ObjectNode operation) {
    return (ObjectNode) operation.path("canonicalAction").path("action");
  }

  private static ObjectNode arguments(ObjectNode operation) {
    return (ObjectNode) action(operation).path("arguments");
  }

  private static ObjectNode target(ObjectNode operation) {
    return (ObjectNode) action(operation).path("target");
  }

  private static void assertRejected(
      BusinessOperationScope scope,
      JsonNode operation,
      GitHubBusinessOperationBinding.ExpectedProfile expected) {
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(scope, operation, expected))
        .isInstanceOfSatisfying(GitHubBusinessOperationBinding.BindingException.class, GitHubBusinessOperationBindingTest::fixedDiagnostic);
  }

  private static void fixedDiagnostic(GitHubBusinessOperationBinding.BindingException failure) {
    assertThat(failure.code()).isEqualTo("GITHUB_OPERATION_BINDING_INVALID");
    assertThat(failure).hasMessage("GITHUB_OPERATION_BINDING_INVALID").hasNoCause();
    assertThat(failure.getSuppressed()).isEmpty();
    assertThat(failure.getStackTrace()).isEmpty();
    assertThat(failure.toString()).doesNotContain("synthetic-private-marker-not-a-credential");
  }
}
