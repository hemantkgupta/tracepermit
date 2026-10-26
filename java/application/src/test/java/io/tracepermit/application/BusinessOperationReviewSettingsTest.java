package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BusinessOperationReviewSettingsTest {
  private static final BusinessOperationScope SCOPE = new BusinessOperationScope(
      "synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private static final String GROUP = "synthetic-reviewers";
  private static final String SOURCE = "sha256:" + "a".repeat(64);
  private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
  private static final String ID = "1e7d1ec3-af54-4c5b-8f73-f58e9c3c9863";
  private static final BusinessOperationTransitionPlanner.StateToken EXPECTED =
      new BusinessOperationTransitionPlanner.StateToken(ID, 1, 4, 2, 1, 1, SOURCE);

  @Test
  void settingsHaveNoDefaultAndKeepExactPositiveLifetimeThroughFiveMinutes() {
    for (Duration lifetime : List.of(Duration.ofNanos(1), Duration.ofSeconds(1, 1), Duration.ofMinutes(5))) {
      var settings = settings(GROUP, SOURCE, lifetime);
      assertThat(settings.deadlineFromWriterTime(NOW)).isEqualTo(NOW.plus(lifetime));
      assertThat(settings.pins().path("approvalLifetimeSeconds").asLong()).isEqualTo(lifetime.getSeconds());
      assertThat(settings.pins().path("approvalLifetimeNanos").asInt()).isEqualTo(lifetime.getNano());
      settings.pins().put("reviewerGroupId", "mutated");
      assertThat(settings.pins().path("reviewerGroupId").asText()).isEqualTo(GROUP);
      assertThat(settings.toString()).doesNotContain(GROUP, SOURCE);
    }
    for (Duration invalid : Arrays.asList(null, Duration.ZERO, Duration.ofNanos(-1),
        Duration.ofMinutes(5).plusNanos(1), Duration.ofSeconds(Long.MAX_VALUE))) {
      assertCode(() -> settings(GROUP, SOURCE, invalid), OPERATION_PROFILE_INVALID);
    }
    assertCode(() -> settings().deadlineFromWriterTime(null), OPERATION_PROFILE_INVALID);
    assertCode(() -> settings().deadlineFromWriterTime(Instant.MAX), OPERATION_PROFILE_INVALID);
  }

  @Test
  void groupAndSourceRejectMissingAliasedMalformedOrUnboundedIdentifiers() {
    for (String invalid : Arrays.asList(null, "", " group", "group ", "a".repeat(257), "group\n", "\ud800")) {
      assertCode(() -> settings(invalid, SOURCE, Duration.ofMinutes(1)), OPERATION_PROFILE_INVALID);
    }
    for (String invalid : Arrays.asList(null, "", "sha256:" + "A".repeat(64), SOURCE + " ")) {
      assertCode(() -> settings(GROUP, invalid, Duration.ofMinutes(1)), OPERATION_PROFILE_INVALID);
    }
  }

  @Test
  void v2BindsEverySettingsPinWithoutChangingLegacyV1HashRecipe() throws Exception {
    var policy = policy();
    var operation = operation(policy);
    var legacy = profile(policy, operation, null);
    var pins = JsonSupport.MAPPER.createObjectNode();
    pins.put("profile", "tracepermit-operation-review-v1");
    pins.put("scopeHash", SCOPE.fingerprint());
    pins.put("adapterId", operation.path("adapterId").asText());
    pins.put("recoveryGeneration", 1);
    pins.put("policyHash", CanonicalJson.sha256(policy));
    pins.put("artifactDigestsHash", CanonicalJson.sha256(operation.path("artifactDigests")));
    pins.put("resourceBindingHash", operation.path("resourceBindingHash").asText());
    assertThat(legacy.fingerprint()).isEqualTo(CanonicalJson.sha256(pins));
    assertCode(legacy::reviewSettings, OPERATION_REVIEW_SETTINGS_REQUIRED);
    String original = profile(policy, operation, settings()).fingerprint();
    assertThat(original).isNotEqualTo(legacy.fingerprint());
    assertThat(profile(policy, operation, settings()).fingerprint()).isEqualTo(original);
    for (var changed : List.of(settings("Synthetic-reviewers", SOURCE, Duration.ofMinutes(1)),
        settings(GROUP, "sha256:" + "b".repeat(64), Duration.ofMinutes(1)),
        settings(GROUP, SOURCE, Duration.ofMinutes(1).plusNanos(1)))) {
      assertThat(profile(policy, operation, changed).fingerprint()).isNotEqualTo(original);
    }
    assertCode(() -> new BusinessOperationAuthorityProfile(SCOPE, operation.path("adapterId").asText(), 1,
        policy, CanonicalJson.sha256(operation.path("artifactDigests")), operation.path("resourceBindingHash").asText(), null),
        OPERATION_PROFILE_INVALID);
  }

  @Test
  void policyCannotSelectAnotherOrMissingReviewGroupUnderV2() throws Exception {
    for (String group : Arrays.asList(null, "other", "Synthetic-reviewers", GROUP + " ")) {
      var policy = policy();
      var decision = (ObjectNode) policy.path("rules").get(0).path("decision");
      if (group == null) decision.remove("approvalGroup"); else decision.put("approvalGroup", group);
      var operation = operation(policy);
      assertCode(() -> profile(policy, operation, settings()).evaluate(operation), OPERATION_PROFILE_MISMATCH);
    }
    var policy = policy();
    var operation = operation(policy);
    assertThat(profile(policy, operation, settings()).evaluate(operation).approvalGroup()).isEqualTo(GROUP);
    ((ObjectNode) policy.path("rules").get(0).path("decision")).put("outcome", "block");
    operation = operation(policy);
    assertThat(profile(policy, operation, settings()).evaluate(operation).outcome()).isEqualTo("block");
  }

  @Test
  void exactFreshMembershipIsRequiredForBothApproveAndDenyWithoutChangingLegacyActor() throws Exception {
    var profile = strictProfile();
    for (var decision : BusinessOperationReview.Decision.values()) {
      var command = command(decision);
      command.requireGroupAuthorizedAt(profile, membership(), NOW);
      assertThat(command.actor().size()).isEqualTo(6); // No new journal vocabulary/adoption here.
      assertCode(() -> command.requireGroupAuthorizedAt(profile, null, NOW), OPERATION_GROUP_MEMBERSHIP_INVALID);
      for (var changed : List.of(
          member("other", SCOPE.workspaceId(), "principal:reviewer", GROUP, SOURCE, NOW, NOW.plusSeconds(20)),
          member(SCOPE.tenantId(), "other", "principal:reviewer", GROUP, SOURCE, NOW, NOW.plusSeconds(20)),
          member(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:other", GROUP, SOURCE, NOW, NOW.plusSeconds(20)),
          member(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", "Synthetic-reviewers", SOURCE, NOW, NOW.plusSeconds(20)),
          member(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", GROUP, "sha256:" + "b".repeat(64), NOW, NOW.plusSeconds(20)),
          member(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", GROUP, SOURCE, NOW, NOW.plusSeconds(61)))) {
        assertCode(() -> command.requireGroupAuthorizedAt(profile, changed, NOW), OPERATION_GROUP_MEMBERSHIP_INVALID);
      }
    }
    assertThat(membership().toString()).doesNotContain(GROUP, SOURCE, "principal:reviewer", SCOPE.tenantId());
  }

  @Test
  void membershipTimeIsFreshAfterWaitsAndCannotExtendAuthentication() throws Exception {
    var command = command(BusinessOperationReview.Decision.APPROVE);
    var profile = strictProfile();
    command.requireGroupAuthorizedAt(profile, membership(), NOW.plusSeconds(20).minusNanos(1));
    assertCode(() -> command.requireGroupAuthorizedAt(profile, membership(), NOW.plusSeconds(20)), OPERATION_GROUP_MEMBERSHIP_EXPIRED);
    assertCode(() -> command.requireGroupAuthorizedAt(profile, membership(), NOW.minusNanos(1)), OPERATION_GROUP_MEMBERSHIP_EXPIRED);
    assertCode(() -> command.requireGroupAuthorizedAt(profile, membership(), NOW.plusSeconds(60)), OPERATION_AUTHENTICATION_EXPIRED);
    assertCode(() -> command.requireGroupAuthorizedAt(profile, membership(), null), OPERATION_AUTHENTICATION_EXPIRED);
  }

  @Test
  void malformedMembershipCannotRetainPrivateDiagnosticsOrImplicitTemporalDefaults() {
    for (Instant expiry : Arrays.asList(null, NOW, NOW.minusNanos(1))) {
      assertCode(() -> member(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", GROUP, SOURCE, NOW, expiry),
          OPERATION_GROUP_MEMBERSHIP_INVALID);
    }
    assertCode(() -> member(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", GROUP, SOURCE, null, NOW), OPERATION_GROUP_MEMBERSHIP_INVALID);
    for (String invalid : Arrays.asList(null, "", " group", "a".repeat(257), "\ud800")) {
      assertCode(() -> member(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", invalid, SOURCE, NOW, NOW.plusSeconds(1)), OPERATION_GROUP_MEMBERSHIP_INVALID);
    }
    assertCode(() -> member(null, SCOPE.workspaceId(), "principal:reviewer", GROUP, SOURCE, NOW, NOW.plusSeconds(1)), OPERATION_GROUP_MEMBERSHIP_INVALID);
    assertCode(() -> member(SCOPE.tenantId(), null, "principal:reviewer", GROUP, SOURCE, NOW, NOW.plusSeconds(1)), OPERATION_GROUP_MEMBERSHIP_INVALID);
    assertCode(() -> member(SCOPE.tenantId(), SCOPE.workspaceId(), null, GROUP, SOURCE, NOW, NOW.plusSeconds(1)), OPERATION_GROUP_MEMBERSHIP_INVALID);
    assertCode(() -> member(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", GROUP, null, NOW, NOW.plusSeconds(1)), OPERATION_GROUP_MEMBERSHIP_INVALID);
  }

  @Test
  void evaluationRequiresSeparateFreshPermissionForExactOwnerWorkspace() throws Exception {
    var profile = strictProfile();
    var command = new BusinessOperationEvaluation(SCOPE, ID.toUpperCase(java.util.Locale.ROOT), EXPECTED,
        evaluator(SCOPE.tenantId(), SCOPE.workspaceId(), SCOPE.requesterPrincipalId(), true,
            Set.of(BusinessOperationEvaluation.ROLE), Set.of(BusinessOperationEvaluation.SCOPE)));
    command.requireAuthorizedAt(profile, NOW);
    assertThat(command.scope()).isEqualTo(SCOPE);
    assertThat(command.operationId()).isEqualTo(ID);
    assertThat(command.expected()).isEqualTo(EXPECTED);
    assertThat(command.toString()).doesNotContain(ID, SOURCE, SCOPE.tenantId());
    assertCode(() -> command.requireAuthorizedAt(profile, NOW.plusSeconds(60)), OPERATION_AUTHENTICATION_EXPIRED);
    assertCode(() -> command.requireAuthorizedAt(profile, null), OPERATION_AUTHENTICATION_EXPIRED);
    for (var context : List.of(
        evaluator("other", SCOPE.workspaceId(), SCOPE.requesterPrincipalId(), true, Set.of(BusinessOperationEvaluation.ROLE), Set.of(BusinessOperationEvaluation.SCOPE)),
        evaluator(SCOPE.tenantId(), "other", SCOPE.requesterPrincipalId(), true, Set.of(BusinessOperationEvaluation.ROLE), Set.of(BusinessOperationEvaluation.SCOPE)),
        evaluator(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:other", true, Set.of(BusinessOperationEvaluation.ROLE), Set.of(BusinessOperationEvaluation.SCOPE)),
        evaluator(SCOPE.tenantId(), SCOPE.workspaceId(), SCOPE.requesterPrincipalId(), false, Set.of(BusinessOperationEvaluation.ROLE), Set.of(BusinessOperationEvaluation.SCOPE)),
        evaluator(SCOPE.tenantId(), SCOPE.workspaceId(), SCOPE.requesterPrincipalId(), true, Set.of("other"), Set.of(BusinessOperationEvaluation.SCOPE)),
        evaluator(SCOPE.tenantId(), SCOPE.workspaceId(), SCOPE.requesterPrincipalId(), true, Set.of(BusinessOperationEvaluation.ROLE), Set.of(BusinessOperationWithdrawal.SCOPE)))) {
      assertCode(() -> new BusinessOperationEvaluation(SCOPE, ID, EXPECTED, context), OPERATION_EVALUATOR_NOT_AUTHORIZED);
    }
    assertCode(() -> new BusinessOperationEvaluation(SCOPE, ID, EXPECTED, null), OPERATION_EVALUATOR_NOT_AUTHORIZED);
    assertCode(() -> new TrustedOperationEvaluatorContext(null, SCOPE.workspaceId()), OPERATION_EVALUATOR_NOT_AUTHORIZED);
    assertCode(() -> new TrustedOperationEvaluatorContext(commandEvaluator(), null), OPERATION_EVALUATOR_NOT_AUTHORIZED);
  }

  @Test
  void missingOrWrongProfileNeverDefaultsTheseGatesToLegacyOrCallerState() throws Exception {
    var policy = policy();
    var operation = operation(policy);
    var evaluation = new BusinessOperationEvaluation(SCOPE, ID, EXPECTED,
        new TrustedOperationEvaluatorContext(commandEvaluator(), SCOPE.workspaceId()));
    var review = command(BusinessOperationReview.Decision.DENY);
    var legacy = profile(policy, operation, null);
    assertCode(() -> evaluation.requireAuthorizedAt(legacy, NOW), OPERATION_REVIEW_SETTINGS_REQUIRED);
    assertCode(() -> review.requireGroupAuthorizedAt(legacy, membership(), NOW), OPERATION_REVIEW_SETTINGS_REQUIRED);
    assertCode(() -> evaluation.requireAuthorizedAt(null, NOW), OPERATION_PROFILE_MISMATCH);
    assertCode(() -> review.requireGroupAuthorizedAt(null, membership(), NOW), OPERATION_PROFILE_MISMATCH);
    for (var profile : List.of(new BusinessOperationAuthorityProfile(SCOPE, "adapter", 2, policy, SOURCE, SOURCE, settings()),
        new BusinessOperationAuthorityProfile(new BusinessOperationScope("other", "workspace", "principal:owner"), "adapter", 1, policy, SOURCE, SOURCE, settings()))) {
      assertCode(() -> evaluation.requireAuthorizedAt(profile, NOW), OPERATION_PROFILE_MISMATCH);
      assertCode(() -> review.requireGroupAuthorizedAt(profile, membership(), NOW), OPERATION_PROFILE_MISMATCH);
    }
    assertCode(() -> new BusinessOperationEvaluation(SCOPE, "bad", EXPECTED, null), OPERATION_COMMAND_INVALID);
    assertCode(() -> new BusinessOperationEvaluation(SCOPE, ID, null, null), OPERATION_COMMAND_INVALID);
  }

  private static BusinessOperationReviewSettings settings() { return settings(GROUP, SOURCE, Duration.ofMinutes(1)); }
  private static BusinessOperationReviewSettings settings(String group, String source, Duration duration) {
    return new BusinessOperationReviewSettings(group, source, duration);
  }
  private static TrustedOperationGroupMembership membership() {
    return member(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", GROUP, SOURCE, NOW, NOW.plusSeconds(20));
  }
  private static TrustedOperationGroupMembership member(String tenant, String workspace, String principal,
      String group, String source, Instant observed, Instant expiry) {
    return new TrustedOperationGroupMembership(tenant, workspace, principal, group, source, observed, expiry);
  }
  private static BusinessOperationReview command(BusinessOperationReview.Decision decision) {
    var identity = new TrustedReviewerContext(new TenantPartition(SCOPE.tenantId()), "principal:reviewer", "human", true,
        Set.of(BusinessOperationReview.ROLE), Set.of(BusinessOperationReview.SCOPE), "synthetic-test", SOURCE);
    return new BusinessOperationReview(SCOPE, ID, EXPECTED,
        new TrustedOperationReviewerContext(identity, SCOPE.workspaceId(), NOW.plusSeconds(60)), decision);
  }
  private static TrustedRequesterContext commandEvaluator() {
    return evaluator(SCOPE.tenantId(), SCOPE.workspaceId(), SCOPE.requesterPrincipalId(), true,
        Set.of(BusinessOperationEvaluation.ROLE), Set.of(BusinessOperationEvaluation.SCOPE)).identity();
  }
  private static TrustedOperationEvaluatorContext evaluator(String tenant, String workspace, String principal,
      boolean authenticated, Set<String> roles, Set<String> scopes) {
    return new TrustedOperationEvaluatorContext(new TrustedRequesterContext(new TenantPartition(tenant), principal,
        authenticated, roles, scopes, "synthetic-test", SOURCE, NOW.plusSeconds(60)), workspace);
  }
  private static BusinessOperationAuthorityProfile strictProfile() throws Exception {
    var policy = policy();
    return profile(policy, operation(policy), settings());
  }
  private static BusinessOperationAuthorityProfile profile(ObjectNode policy, ObjectNode operation,
      BusinessOperationReviewSettings settings) {
    String adapter = operation.path("adapterId").asText();
    String artifacts = CanonicalJson.sha256(operation.path("artifactDigests"));
    String resource = operation.path("resourceBindingHash").asText();
    return settings == null ? new BusinessOperationAuthorityProfile(SCOPE, adapter, 1, policy, artifacts, resource)
        : new BusinessOperationAuthorityProfile(SCOPE, adapter, 1, policy, artifacts, resource, settings);
  }
  private static ObjectNode operation(ObjectNode policy) throws Exception {
    var operation = (ObjectNode) JsonSupport.MAPPER.readTree(Path.of(System.getProperty("tracepermit.repositoryRoot"),
        "contracts/vectors/business-operation-v1.json").toFile()).path("operation").deepCopy();
    ((ObjectNode) operation.path("policyRelease")).put("contentHash", CanonicalJson.sha256(policy));
    return operation;
  }
  private static ObjectNode policy() throws Exception {
    return (ObjectNode) JsonSupport.MAPPER.readTree("""
      {"schemaVersion":"1.0","id":"synthetic-github-draft-pr","version":1,"name":"Synthetic",
       "description":"No target authority","enabled":true,"defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
       "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic",
       "match":{"toolNames":["github.pull_request.create"]},
       "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW","approvalGroup":"synthetic-reviewers"}}]}
      """);
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
