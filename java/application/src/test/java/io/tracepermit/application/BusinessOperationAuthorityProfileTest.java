package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class BusinessOperationAuthorityProfileTest {
  private static final BusinessOperationScope SCOPE = new BusinessOperationScope(
      "synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private static final String HASH = "sha256:" + "a".repeat(64);

  @Test
  void pinnedOperatorPolicyActuallyEvaluatesCanonicalActionAndNeverMutatesInputs() {
    for (String outcome : List.of("block", "require_approval")) {
      ObjectNode policy = policy(outcome);
      ObjectNode operation = operation(policy);
      JsonNode before = operation.deepCopy();
      var profile = profile(policy, operation);
      var decision = profile.evaluate(operation);
      assertThat(decision.outcome()).isEqualTo(outcome);
      assertThat(decision.reasonCode()).isEqualTo("SYNTHETIC_REVIEW_REQUIRED");
      assertThat(decision.matchedRuleId()).isEqualTo("exact-draft-pr");
      assertThat(decision.policyId()).isEqualTo("synthetic-github-draft-pr");
      assertThat(decision.policyVersion()).isEqualTo(1);
      assertThat(operation).isEqualTo(before);
      ((ObjectNode) policy.path("rules").get(0).path("decision")).put("outcome", "allow");
      assertThat(profile.evaluate(operation).outcome()).isEqualTo(outcome);
      assertThat(profile.fingerprint()).matches("sha256:[a-f0-9]{64}");
      assertThat(profile.toString()).doesNotContain("synthetic-tenant", "sha256:", "principal:");
    }
  }

  @Test
  void allowAndSanitizeDoNotSilentlyBecomeApprovedInTheReviewOnlyProfile() {
    for (String outcome : List.of("allow", "sanitize")) {
      ObjectNode policy = policy(outcome);
      ObjectNode operation = operation(policy);
      assertCode(() -> profile(policy, operation).evaluate(operation), OPERATION_POLICY_OUTCOME_UNSUPPORTED);
    }
  }

  @Test
  void policyDefaultBlockIsEvaluatedRatherThanTrustingSnapshotApprovedLabel() {
    ObjectNode policy = policy("require_approval");
    ((ObjectNode) policy.path("rules").get(0).path("match")).putArray("toolNames").add("synthetic.other");
    ObjectNode operation = operation(policy);
    var profile = profile(policy, operation);
    var planner = new BusinessOperationTransitionPlanner();
    JsonNode approvedShape = planner.propose(SCOPE, operation, planner.observe(SCOPE, operation),
        BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION, "approved", "SYNTHETIC_SHAPE_ONLY",
        java.time.Instant.parse(operation.path("updatedAt").asText())).snapshot();
    assertThat(profile.evaluate(approvedShape).outcome()).isEqualTo("block");
  }

  @Test
  void coherentButDifferentScopeAdapterGenerationPolicyArtifactsAndPreconditionAllFailPins() {
    ObjectNode policy = policy("require_approval");
    ObjectNode operation = operation(policy);
    var profile = profile(policy, operation);
    List<Consumer<ObjectNode>> changes = List.of(
        value -> ((ObjectNode) value.path("trustedContext")).put("workspaceId", "other-workspace"),
        value -> value.put("adapterId", "other-adapter"),
        value -> value.put("recoveryGeneration", 2),
        value -> ((ObjectNode) value.path("policyRelease")).put("policyId", "other-policy"),
        value -> ((ObjectNode) value.path("policyRelease")).put("version", 2),
        value -> ((ObjectNode) value.path("policyRelease")).put("contentHash", HASH),
        value -> ((ObjectNode) value.path("artifactDigests").get(0)).put("digest", HASH),
        value -> ((ObjectNode) value.path("resourceBinding").path("precondition")).put("valueHash", HASH));
    for (var change : changes) {
      ObjectNode changed = operation.deepCopy();
      change.accept(changed);
      rehash(changed);
      BusinessOperationValidator.validate(changed); // Not just rejection by the structural validator.
      assertCode(() -> profile.evaluate(changed), OPERATION_PROFILE_MISMATCH);
    }
    ObjectNode malformed = operation.deepCopy();
    malformed.put("private-sensitive-canary", "must-not-appear-in-errors");
    assertCode(() -> profile.evaluate(malformed), OPERATION_PROFILE_MISMATCH);
    assertCode(() -> profile.evaluate(null), OPERATION_PROFILE_MISMATCH);
  }

  @Test
  void invalidDisabledOversizedOrPrivateOperatorPolicyIsOnlyFixedDiagnostic() {
    ObjectNode valid = policy("require_approval");
    ObjectNode disabled = valid.deepCopy().put("enabled", false);
    ObjectNode invalid = valid.deepCopy().put("private-sensitive-canary", "secret-fixture");
    ObjectNode oversized = valid.deepCopy().put("description", "x".repeat(65_537));
    for (JsonNode policy : java.util.Arrays.asList(null, JsonSupport.MAPPER.nullNode(), disabled, invalid, oversized)) {
      assertCode(() -> new BusinessOperationAuthorityProfile(SCOPE, "adapter", 1, policy, HASH, HASH),
          OPERATION_PROFILE_INVALID);
    }
    assertCode(() -> new BusinessOperationAuthorityProfile(null, "adapter", 1, valid, HASH, HASH), OPERATION_PROFILE_INVALID);
    for (String adapter : java.util.Arrays.asList(null, "", "adapter ", "a".repeat(129))) {
      assertCode(() -> new BusinessOperationAuthorityProfile(SCOPE, adapter, 1, valid, HASH, HASH), OPERATION_PROFILE_INVALID);
    }
    for (long generation : new long[] {0, -1, 9_007_199_254_740_992L}) {
      assertCode(() -> new BusinessOperationAuthorityProfile(SCOPE, "adapter", generation, valid, HASH, HASH), OPERATION_PROFILE_INVALID);
    }
    for (String hash : java.util.Arrays.asList(null, "", "sha256:" + "A".repeat(64), HASH + " ")) {
      assertCode(() -> new BusinessOperationAuthorityProfile(SCOPE, "adapter", 1, valid, hash, HASH), OPERATION_PROFILE_INVALID);
      assertCode(() -> new BusinessOperationAuthorityProfile(SCOPE, "adapter", 1, valid, HASH, hash), OPERATION_PROFILE_INVALID);
    }
  }

  @Test
  void byteBoundsAreUtf8NotCharacterCountAndOversizedOperationIsRejected() {
    ObjectNode policy = policy("require_approval");
    policy.put("description", "\u00e9".repeat(33_000));
    assertThat(CanonicalJson.canonicalize(policy).length()).isLessThan(65_536);
    assertCode(() -> new BusinessOperationAuthorityProfile(SCOPE, "adapter", 1, policy, HASH, HASH), OPERATION_PROFILE_INVALID);

    ObjectNode validPolicy = policy("require_approval");
    ObjectNode operation = operation(validPolicy);
    var profile = profile(validPolicy, operation);
    ((ObjectNode) operation.path("canonicalAction").path("action").path("arguments"))
        .put("title", "\u00e9".repeat(133_000));
    operation.put("canonicalActionHash", CanonicalJson.sha256(operation.path("canonicalAction")));
    operation.put("semanticPayloadDigest", CanonicalJson.sha256(operation.path("canonicalAction").path("action")));
    BusinessOperationValidator.validate(operation);
    assertThat(CanonicalJson.canonicalize(operation).length()).isLessThan(262_144);
    assertCode(() -> profile.evaluate(operation), OPERATION_PROFILE_MISMATCH);
  }

  @Test
  void profileFingerprintIsDomainSeparatedDeterministicAndBindsEveryConfiguredPin() {
    ObjectNode policy = policy("require_approval");
    ObjectNode operation = operation(policy);
    String original = profile(policy, operation).fingerprint();
    assertThat(profile(policy.deepCopy(), operation.deepCopy()).fingerprint()).isEqualTo(original);
    String artifacts = CanonicalJson.sha256(operation.path("artifactDigests"));
    String resources = operation.path("resourceBindingHash").asText();
    for (var changed : List.of(
        new BusinessOperationAuthorityProfile(new BusinessOperationScope("other", "workspace", "principal:owner"), "github-draft-pr-v1", 1, policy, artifacts, resources),
        new BusinessOperationAuthorityProfile(SCOPE, "other-adapter", 1, policy, artifacts, resources),
        new BusinessOperationAuthorityProfile(SCOPE, "github-draft-pr-v1", 2, policy, artifacts, resources),
        new BusinessOperationAuthorityProfile(SCOPE, "github-draft-pr-v1", 1, policy("block"), artifacts, resources),
        new BusinessOperationAuthorityProfile(SCOPE, "github-draft-pr-v1", 1, policy, HASH, resources),
        new BusinessOperationAuthorityProfile(SCOPE, "github-draft-pr-v1", 1, policy, artifacts, HASH))) {
      assertThat(changed.fingerprint()).isNotEqualTo(original);
    }
    new BusinessOperationAuthorityProfile(SCOPE, "adapter", 9_007_199_254_740_991L, policy, HASH, HASH);
  }

  private static BusinessOperationAuthorityProfile profile(JsonNode policy, JsonNode operation) {
    return new BusinessOperationAuthorityProfile(SCOPE, operation.path("adapterId").asText(), 1,
        policy, CanonicalJson.sha256(operation.path("artifactDigests")), operation.path("resourceBindingHash").asText());
  }

  private static ObjectNode policy(String outcome) {
    try {
      return (ObjectNode) JsonSupport.MAPPER.readTree("""
          {"schemaVersion":"1.0","id":"synthetic-github-draft-pr","version":1,
           "name":"Synthetic policy","description":"No target authority","enabled":true,
           "defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DEFAULT_DENY"},
           "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic exact operation",
             "match":{"toolNames":["github.pull_request.create"]},
             "decision":{"outcome":"%s","reasonCode":"SYNTHETIC_REVIEW_REQUIRED"}}]}
          """.formatted(outcome));
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private static ObjectNode operation(JsonNode policy) {
    try {
      ObjectNode operation = (ObjectNode) JsonSupport.MAPPER.readTree(Path.of(
          System.getProperty("tracepermit.repositoryRoot"), "contracts/vectors/business-operation-v1.json")
          .toFile()).path("operation").deepCopy();
      ((ObjectNode) operation.path("policyRelease")).put("contentHash", CanonicalJson.sha256(policy));
      return operation;
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void rehash(ObjectNode operation) {
    operation.put("resourceBindingHash", CanonicalJson.sha256(operation.path("resourceBinding")));
    operation.put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(
        operation.path("operationId").asText(), operation.path("adapterId").asText(), operation.path("recoveryGeneration").asLong()));
  }

  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      BusinessOperationStorageException.Code code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationStorageException.class, failure -> {
      assertThat(failure.code()).isEqualTo(code);
      failure.addSuppressed(new IllegalStateException("synthetic-sensitive-canary"));
      assertThat(failure.getSuppressed()).isEmpty();
    }).hasMessage(code.name()).hasNoCause();
  }
}
