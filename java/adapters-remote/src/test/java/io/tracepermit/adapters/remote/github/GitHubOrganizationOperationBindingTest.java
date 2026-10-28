package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Fixture-only organization binding qualification. No credentials, transport or target calls. */
final class GitHubOrganizationOperationBindingTest {
  private static final String REPOSITORY = "example-sandbox/tracepermit-change-control";
  private static final String HEAD = "tracepermit/fixture-change";
  private static final String BASE = "main";
  private static final String HASH_5 = "sha256:" + "5".repeat(64);
  private static final String HASH_6 = "sha256:" + "6".repeat(64);
  private static final String HASH_7 = "sha256:" + "7".repeat(64);
  private static final BusinessOperationScope SCOPE =
      new BusinessOperationScope(
          "synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private static final GitHubOrganizationTargetBinding TARGET =
      target(61, 71, 101, 201, REPOSITORY, HEAD, BASE);

  @Test
  void exactOrganizationProfileBindsAppInstallationRepositoryPermissionsAndRefs() {
    var expected = profile(TARGET);
    ObjectNode operation = operation(expected);

    BoundGitHubOperation bound = expected.bind(SCOPE, operation);

    assertThat(bound.snapshot()).isEqualTo(operation).isNotSameAs(operation);
    assertThat(bound.targetBinding()).isEqualTo(TARGET);
    assertThat(bound.profileFingerprint()).isEqualTo(expected.fingerprint());
    assertThat(bound.bindingDigest()).matches("sha256:[a-f0-9]{64}");
    assertThat(expected.adapterId()).isEqualTo(GitHubOrganizationOperationBinding.ADAPTER_ID);
    assertThat(expected.resourceBinding().path("resourceType").asText()).isEqualTo("pull_request");
    assertThat(TARGET.permissions())
        .containsExactlyInAnyOrderEntriesOf(GitHubOrganizationTargetBinding.requiredPermissions());
    assertThat(TARGET.toString()).doesNotContain(REPOSITORY, HEAD, "61", "71");
  }

  @Test
  void legacyTrackEProfileRetainsItsOriginalBindingRecipeThroughTheNewInterface() {
    var target =
        new GitHubPilotTargetBinding(
            71,
            101,
            201,
            "tracepermit/agentcore-pr-pilot",
            "synthetic-change",
            "main",
            "a".repeat(40),
            "b".repeat(40));
    var expected =
        new GitHubBusinessOperationBinding.ExpectedProfile(
            GitHubBusinessOperationBinding.ADAPTER_ID,
            "test",
            1,
            policy(),
            artifacts(),
            target);
    ObjectNode operation = operation(expected);

    var original = GitHubBusinessOperationBinding.bind(SCOPE, operation, expected);
    BoundGitHubOperation generalized = ((GitHubOperationProfile) expected).bind(SCOPE, operation);

    assertThat(generalized.snapshot()).isEqualTo(original.snapshot());
    assertThat(generalized.profileFingerprint()).isEqualTo(original.profileFingerprint());
    assertThat(generalized.bindingDigest()).isEqualTo(original.bindingDigest());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "repository_case",
        "account_case",
        "head",
        "base",
        "app",
        "installation",
        "repository_id",
        "owner_id",
        "head_sha",
        "base_sha"
      })
  void storedOrOperatorTargetSubstitutionFailsClosed(String change) {
    var expected = profile(TARGET);
    ObjectNode operation = operation(expected);
    if ("repository_case".equals(change)) {
      arguments(operation).put("repository", "Example-Sandbox/tracepermit-change-control");
      refresh(operation);
      rejected(operation, expected);
      return;
    }
    if ("account_case".equals(change)) {
      targetNode(operation).put("accountId", "Example-Sandbox/tracepermit-change-control");
      refresh(operation);
      rejected(operation, expected);
      return;
    }
    GitHubOrganizationTargetBinding changed =
        switch (change) {
          case "head" -> target(61, 71, 101, 201, REPOSITORY, "tracepermit/other", BASE);
          case "base" -> target(61, 71, 101, 201, REPOSITORY, HEAD, "release");
          case "app" -> target(62, 71, 101, 201, REPOSITORY, HEAD, BASE);
          case "installation" -> target(61, 72, 101, 201, REPOSITORY, HEAD, BASE);
          case "repository_id" -> target(61, 71, 102, 201, REPOSITORY, HEAD, BASE);
          case "owner_id" -> target(61, 71, 101, 202, REPOSITORY, HEAD, BASE);
          case "head_sha" ->
              new GitHubOrganizationTargetBinding(
                  61,
                  71,
                  101,
                  201,
                  REPOSITORY,
                  HEAD,
                  BASE,
                  "c".repeat(40),
                  "b".repeat(40),
                  GitHubOrganizationTargetBinding.requiredPermissions());
          case "base_sha" ->
              new GitHubOrganizationTargetBinding(
                  61,
                  71,
                  101,
                  201,
                  REPOSITORY,
                  HEAD,
                  BASE,
                  "a".repeat(40),
                  "c".repeat(40),
                  GitHubOrganizationTargetBinding.requiredPermissions());
          default -> throw new AssertionError(change);
        };
    rejected(operation, profile(changed));
  }

  @ParameterizedTest
  @ValueSource(strings = {"prod", "extra_permission", "broad_permission", "unsafe_ref", "unsafe_id"})
  void invalidTrustedOrganizationConfigurationIsRejectedBeforeBinding(String change) {
    assertThatThrownBy(
            () -> {
              switch (change) {
                case "prod" ->
                    new GitHubOrganizationOperationBinding.ExpectedProfile(
                        GitHubOrganizationOperationBinding.ADAPTER_ID,
                        "prod",
                        1,
                        policy(),
                        artifacts(),
                        TARGET);
                case "extra_permission" ->
                    new GitHubOrganizationTargetBinding(
                        61,
                        71,
                        101,
                        201,
                        REPOSITORY,
                        HEAD,
                        BASE,
                        "a".repeat(40),
                        "b".repeat(40),
                        Map.of(
                            "contents",
                            "read",
                            "metadata",
                            "read",
                            "pull_requests",
                            "write",
                            "issues",
                            "write"));
                case "broad_permission" ->
                    new GitHubOrganizationTargetBinding(
                        61,
                        71,
                        101,
                        201,
                        REPOSITORY,
                        HEAD,
                        BASE,
                        "a".repeat(40),
                        "b".repeat(40),
                        Map.of(
                            "contents", "write", "metadata", "read", "pull_requests", "write"));
                case "unsafe_ref" -> target(61, 71, 101, 201, REPOSITORY, "refs/../main", BASE);
                case "unsafe_id" -> target(0, 71, 101, 201, REPOSITORY, HEAD, BASE);
                default -> throw new AssertionError(change);
              }
            })
        .isInstanceOf(RuntimeException.class)
        .hasNoCause();
  }

  @Test
  void profileInterfaceDoesNotTurnBoundDataIntoAuthority() {
    GitHubOperationProfile expected = profile(TARGET);
    BoundGitHubOperation bound = expected.bind(SCOPE, operation(expected));
    assertThat(bound.toString()).contains("no execution authority");
    assertThatCode(() -> BusinessOperationValidator.validate(bound.snapshot()))
        .doesNotThrowAnyException();
  }

  private static GitHubOrganizationOperationBinding.ExpectedProfile profile(
      GitHubOrganizationTargetBinding target) {
    return new GitHubOrganizationOperationBinding.ExpectedProfile(
        GitHubOrganizationOperationBinding.ADAPTER_ID,
        "test",
        1,
        policy(),
        artifacts(),
        target);
  }

  private static GitHubBusinessOperationBinding.PolicyRelease policy() {
    return new GitHubBusinessOperationBinding.PolicyRelease(
        "synthetic-github-draft-pr", 1, HASH_5);
  }

  private static Map<String, String> artifacts() {
    return Map.of("policy_bundle", HASH_6, "adapter_release", HASH_7);
  }

  private static GitHubOrganizationTargetBinding target(
      long app,
      long installation,
      long repositoryId,
      long owner,
      String repository,
      String head,
      String base) {
    return new GitHubOrganizationTargetBinding(
        app,
        installation,
        repositoryId,
        owner,
        repository,
        head,
        base,
        "a".repeat(40),
        "b".repeat(40),
        GitHubOrganizationTargetBinding.requiredPermissions());
  }

  private static ObjectNode operation(GitHubOperationProfile expected) {
    Path root = Path.of(System.getProperty("tracepermit.repositoryRoot"));
    ObjectNode operation =
        (ObjectNode)
            JsonSupport.read(root.resolve("contracts/vectors/business-operation-v1.json"))
                .path("operation")
                .deepCopy();
    arguments(operation).remove("owner");
    arguments(operation)
        .put("repository", expected.targetBinding().repository())
        .put("head", expected.targetBinding().head())
        .put("base", expected.targetBinding().base());
    targetNode(operation)
        .put("accountId", expected.targetBinding().repository())
        .put("resourceType", "pull_request")
        .put("environment", expected.environment());
    operation.put("adapterId", expected.adapterId());
    operation.put("recoveryGeneration", expected.recoveryGeneration());
    operation.set("resourceBinding", expected.resourceBinding());
    operation
        .putObject("policyRelease")
        .put("policyId", expected.policyRelease().policyId())
        .put("version", expected.policyRelease().version())
        .put("contentHash", expected.policyRelease().contentHash());
    ArrayNode artifactArray = operation.putArray("artifactDigests");
    expected
        .artifactDigests()
        .forEach(
            (kind, digest) ->
                artifactArray.addObject().put("kind", kind).put("digest", digest));
    refresh(operation);
    return operation;
  }

  private static void refresh(ObjectNode operation) {
    operation.put("canonicalActionHash", CanonicalJson.sha256(operation.path("canonicalAction")));
    operation.put(
        "semanticPayloadDigest",
        CanonicalJson.sha256(operation.path("canonicalAction").path("action")));
    operation.put("resourceBindingHash", CanonicalJson.sha256(operation.path("resourceBinding")));
    operation.put(
        "targetIdempotencyKeyHash",
        BusinessOperationValidator.targetIdempotencyKeyHash(
            operation.path("operationId").textValue(),
            operation.path("adapterId").textValue(),
            operation.path("recoveryGeneration").longValue()));
  }

  private static ObjectNode arguments(ObjectNode operation) {
    return (ObjectNode) operation.path("canonicalAction").path("action").path("arguments");
  }

  private static ObjectNode targetNode(ObjectNode operation) {
    return (ObjectNode) operation.path("canonicalAction").path("action").path("target");
  }

  private static void rejected(
      ObjectNode operation, GitHubOrganizationOperationBinding.ExpectedProfile expected) {
    assertThatThrownBy(() -> expected.bind(SCOPE, operation))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class)
        .hasMessage("GITHUB_OPERATION_BINDING_INVALID")
        .hasNoCause();
  }
}
