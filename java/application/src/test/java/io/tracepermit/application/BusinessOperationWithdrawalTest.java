package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

import io.tracepermit.execution.TenantPartition;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BusinessOperationWithdrawalTest {
  private static final String ID = "1e7d1ec3-af54-4c5b-8f73-f58e9c3c9863";
  private static final BusinessOperationScope SCOPE = new BusinessOperationScope("tenant", "workspace", "principal:owner");
  private static final Instant DEADLINE = Instant.parse("2026-10-24T12:00:00Z");
  private static final BusinessOperationTransitionPlanner.StateToken EXPECTED =
      new BusinessOperationTransitionPlanner.StateToken(ID, 1, 3, 1, 1, 1, "sha256:" + "a".repeat(64));

  @Test
  void inputIsClosedInternalCommandNotGenericDesiredStateOrAuthenticationProof() {
    var command = new BusinessOperationWithdrawal(SCOPE, ID.toUpperCase(java.util.Locale.ROOT), EXPECTED, actor(
        "tenant", "principal:owner", true, Set.of(BusinessOperationWithdrawal.ROLE), Set.of(BusinessOperationWithdrawal.SCOPE)));
    assertThat(command.operationId()).isEqualTo(ID);
    assertThat(command.scope()).isEqualTo(SCOPE);
    assertThat(command.expected()).isEqualTo(EXPECTED);
    command.requireAuthorizedAt(DEADLINE.minusNanos(1));
    assertThat(command.actor().path("credentialFingerprint").asText()).isEqualTo("sha256:" + "b".repeat(64));
    assertThat(command.toString()).doesNotContain(ID, "principal:owner", "tenant", "sha256:");
    var changed = command.actor();
    changed.put("principalId", "mutated");
    assertThat(command.actor().path("principalId").asText()).isEqualTo("principal:owner");
  }

  @Test
  void identityOwnerRoleScopeAndTenantCannotBeReplacedByFlagsOrOtherMembership() {
    for (var requester : java.util.List.of(
        actor("other", "principal:owner", true, Set.of(BusinessOperationWithdrawal.ROLE), Set.of(BusinessOperationWithdrawal.SCOPE)),
        actor("tenant", "principal:other", true, Set.of(BusinessOperationWithdrawal.ROLE), Set.of(BusinessOperationWithdrawal.SCOPE)),
        actor("tenant", "principal:owner", false, Set.of(BusinessOperationWithdrawal.ROLE), Set.of(BusinessOperationWithdrawal.SCOPE)),
        actor("tenant", "principal:owner", true, Set.of("approval_reviewer"), Set.of(BusinessOperationWithdrawal.SCOPE)),
        actor("tenant", "principal:owner", true, Set.of(BusinessOperationWithdrawal.ROLE), Set.of("approvals.cancel")))) {
      assertCode(() -> new BusinessOperationWithdrawal(SCOPE, ID, EXPECTED, requester), OPERATION_REQUESTER_NOT_AUTHORIZED);
    }
    assertCode(() -> new BusinessOperationWithdrawal(null, ID, EXPECTED, null), OPERATION_REQUESTER_NOT_AUTHORIZED);
  }

  @Test
  void deadlineIsStrictAndMustBeCheckedUsingSuppliedTrustedStoreObservation() {
    var command = new BusinessOperationWithdrawal(SCOPE, ID, EXPECTED, actor(
        "tenant", "principal:owner", true, Set.of(BusinessOperationWithdrawal.ROLE), Set.of(BusinessOperationWithdrawal.SCOPE)));
    for (Instant time : java.util.Arrays.asList(null, DEADLINE, DEADLINE.plusNanos(1))) {
      assertCode(() -> command.requireAuthorizedAt(time), OPERATION_AUTHENTICATION_EXPIRED);
    }
  }

  @Test
  void malformedOrMismatchedOperationIdentityAndMissingExpectedTokenAreFixedErrors() {
    var requester = actor("tenant", "principal:owner", true,
        Set.of(BusinessOperationWithdrawal.ROLE), Set.of(BusinessOperationWithdrawal.SCOPE));
    for (String id : java.util.Arrays.asList(null, "bad", "1-1-1-1-1", java.util.UUID.randomUUID().toString())) {
      assertCode(() -> new BusinessOperationWithdrawal(SCOPE, id, EXPECTED, requester), OPERATION_COMMAND_INVALID);
    }
    assertCode(() -> new BusinessOperationWithdrawal(SCOPE, ID, null, requester), OPERATION_COMMAND_INVALID);
  }

  private static TrustedRequesterContext actor(String tenant, String principal, boolean authenticated,
      Set<String> roles, Set<String> scopes) {
    return new TrustedRequesterContext(new TenantPartition(tenant), principal, authenticated,
        roles, scopes, "synthetic-test", "sha256:" + "b".repeat(64), DEADLINE);
  }

  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      BusinessOperationStorageException.Code code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationStorageException.class,
        failure -> assertThat(failure.code()).isEqualTo(code)).hasMessage(code.name()).hasNoCause();
  }
}
