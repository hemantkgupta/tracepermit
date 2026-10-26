package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

import io.tracepermit.execution.TenantPartition;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BusinessOperationReviewTest {
  private static final String ID = "1e7d1ec3-af54-4c5b-8f73-f58e9c3c9863";
  private static final BusinessOperationScope SCOPE = new BusinessOperationScope("tenant", "workspace", "principal:owner");
  private static final Instant DEADLINE = Instant.parse("2026-10-25T12:00:00Z");
  private static final BusinessOperationTransitionPlanner.StateToken EXPECTED =
      new BusinessOperationTransitionPlanner.StateToken(ID, 1, 4, 2, 1, 1, "sha256:" + "a".repeat(64));

  @Test
  void approvalAndDenialAreClosedUncommittedInputsWithDefensiveFingerprintedMetadata() {
    for (var decision : BusinessOperationReview.Decision.values()) {
      var command = new BusinessOperationReview(SCOPE, ID.toUpperCase(java.util.Locale.ROOT),
          EXPECTED, reviewer(), decision);
      assertThat(command.scope()).isEqualTo(SCOPE);
      assertThat(command.operationId()).isEqualTo(ID);
      assertThat(command.expected()).isEqualTo(EXPECTED);
      assertThat(command.decision()).isEqualTo(decision);
      command.requireAuthorizedAt(DEADLINE.minusNanos(1));
      var actor = command.actor();
      assertThat(actor.size()).isEqualTo(6);
      assertThat(actor.path("scopeHash").asText()).isEqualTo(SCOPE.fingerprint());
      assertThat(actor.path("principalId").asText()).isEqualTo("principal:reviewer");
      assertThat(actor.path("principalType").asText()).isEqualTo("human");
      assertThat(actor.path("authenticationMethod").asText()).isEqualTo("synthetic-test");
      assertThat(actor.path("credentialFingerprint").asText()).isEqualTo("sha256:" + "b".repeat(64));
      assertThat(actor.path("authorityExpiresAt").asText()).isEqualTo(DEADLINE.toString());
      actor.put("principalId", "mutated");
      assertThat(command.actor().path("principalId").asText()).isEqualTo("principal:reviewer");
      assertThat(command.toString()).doesNotContain(ID, "tenant", "principal:", "sha256:", decision.name());
    }
    assertThat(reviewer().toString()).doesNotContain("tenant", "workspace", "principal:", "sha256:");
  }

  @Test
  void onlyIndependentAuthenticatedHumanInExactTenantAndWorkspaceWithOperationRoleAndScopePasses() {
    for (var identity : List.of(
        identity("other", "principal:reviewer", "human", true, Set.of(BusinessOperationReview.ROLE), Set.of(BusinessOperationReview.SCOPE)),
        identity("tenant", "principal:owner", "human", true, Set.of(BusinessOperationReview.ROLE), Set.of(BusinessOperationReview.SCOPE)),
        identity("tenant", "principal:reviewer", "agent", true, Set.of(BusinessOperationReview.ROLE), Set.of(BusinessOperationReview.SCOPE)),
        identity("tenant", "principal:reviewer", "workload", true, Set.of(BusinessOperationReview.ROLE), Set.of(BusinessOperationReview.SCOPE)),
        identity("tenant", "principal:reviewer", "human", false, Set.of(BusinessOperationReview.ROLE), Set.of(BusinessOperationReview.SCOPE)),
        identity("tenant", "principal:reviewer", "human", true, Set.of("approval_reviewer"), Set.of(BusinessOperationReview.SCOPE)),
        identity("tenant", "principal:reviewer", "human", true, Set.of(BusinessOperationReview.ROLE), Set.of("approvals.decide")))) {
      assertCode(() -> command(new TrustedOperationReviewerContext(identity, "workspace", DEADLINE)),
          OPERATION_REVIEWER_NOT_AUTHORIZED);
    }
    assertCode(() -> command(new TrustedOperationReviewerContext(reviewer().identity(), "other", DEADLINE)),
        OPERATION_REVIEWER_NOT_AUTHORIZED);
    assertCode(() -> command(null), OPERATION_REVIEWER_NOT_AUTHORIZED);
    assertCode(() -> new BusinessOperationReview(null, ID, EXPECTED, reviewer(), BusinessOperationReview.Decision.DENY),
        OPERATION_REVIEWER_NOT_AUTHORIZED);
  }

  @Test
  void noDefaultDeadlineOrMalformedWorkspaceCanCreateOperationEnrollment() {
    assertCode(() -> new TrustedOperationReviewerContext(null, "workspace", DEADLINE), OPERATION_REVIEWER_NOT_AUTHORIZED);
    assertCode(() -> new TrustedOperationReviewerContext(reviewer().identity(), "workspace", null), OPERATION_REVIEWER_NOT_AUTHORIZED);
    for (String workspace : java.util.Arrays.asList(null, "", " workspace", "workspace ", "a".repeat(129), "work\nspace")) {
      assertCode(() -> new TrustedOperationReviewerContext(reviewer().identity(), workspace, DEADLINE),
          OPERATION_REVIEWER_NOT_AUTHORIZED);
    }
  }

  @Test
  void authenticationExpiryIsStrictAndRepeatedWithFreshTrustedStoreObservation() {
    var command = command(reviewer());
    command.requireAuthorizedAt(DEADLINE.minusNanos(1));
    for (Instant now : java.util.Arrays.asList(null, DEADLINE, DEADLINE.plusNanos(1))) {
      assertCode(() -> command.requireAuthorizedAt(now), OPERATION_AUTHENTICATION_EXPIRED);
    }
  }

  @Test
  void wrongOperationMissingStateAndUnknownDecisionCannotBecomeReviewInputs() {
    for (String id : java.util.Arrays.asList(null, "bad", "1-1-1-1-1", "00000000-0000-0000-0000-000000000000")) {
      assertCode(() -> new BusinessOperationReview(SCOPE, id, EXPECTED, reviewer(), BusinessOperationReview.Decision.APPROVE),
          OPERATION_COMMAND_INVALID);
    }
    assertCode(() -> new BusinessOperationReview(SCOPE, ID, null, reviewer(), BusinessOperationReview.Decision.APPROVE),
        OPERATION_COMMAND_INVALID);
    assertCode(() -> new BusinessOperationReview(SCOPE, ID, EXPECTED, reviewer(), null), OPERATION_COMMAND_INVALID);
  }

  @Test
  void expectedTokenIsNotAuthorityAndMustStillBeComparedToLockedWholeState() {
    var forged = new BusinessOperationTransitionPlanner.StateToken(ID, 2, 99, 5, 7, 9, "sha256:" + "f".repeat(64));
    var command = new BusinessOperationReview(SCOPE, ID, forged, reviewer(), BusinessOperationReview.Decision.APPROVE);
    assertThat(command.expected()).isEqualTo(forged).isNotEqualTo(EXPECTED);
    // Acceptance of a command object deliberately does not assert pending state or commit review.
    assertThat(command.toString()).contains("uncommitted", "not execution authority");
  }

  private static BusinessOperationReview command(TrustedOperationReviewerContext reviewer) {
    return new BusinessOperationReview(SCOPE, ID, EXPECTED, reviewer, BusinessOperationReview.Decision.APPROVE);
  }

  private static TrustedOperationReviewerContext reviewer() {
    return new TrustedOperationReviewerContext(identity("tenant", "principal:reviewer", "human", true,
        Set.of(BusinessOperationReview.ROLE), Set.of(BusinessOperationReview.SCOPE)), "workspace", DEADLINE);
  }

  private static TrustedReviewerContext identity(String tenant, String principal, String type,
      boolean authenticated, Set<String> roles, Set<String> scopes) {
    return new TrustedReviewerContext(new TenantPartition(tenant), principal, type, authenticated,
        roles, scopes, "synthetic-test", "sha256:" + "b".repeat(64));
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
