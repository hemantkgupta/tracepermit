package io.tracepermit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.execution.ApprovalCancellation;
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.DurableApprovalCancellationState;
import io.tracepermit.execution.DurableApprovalState;
import io.tracepermit.execution.DurableEnforcementState;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PendingApprovalCancellationTest {
  private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
  private static final TenantPartition TENANT = new TenantPartition("synthetic");
  private final AtomicReference<ApprovalCancellation> captured = new AtomicReference<>();

  @Test void cancellationForwardsOnlyTrustedProvenanceAndCannotMintClaimOrDispatch() {
    var result = service(true, true).cancel(context(true, true, true, NOW.plusSeconds(60)), "request-1", hash());
    assertThat(result.status()).isEqualTo("cancelled");
    assertThat(captured.get()).isEqualTo(new ApprovalCancellation(TENANT, "request-1", hash(),
        "issuer#owner", "oidc-access-token", hash(), NOW.plusSeconds(60)));
  }

  @Test void requesterMustBeAuthenticatedAndHaveBothRoleAndExplicitScope() {
    var service = service(true, true);
    for (var context : new TrustedRequesterContext[] {
        context(false, true, true, NOW.plusSeconds(60)),
        context(true, false, true, NOW.plusSeconds(60)),
        context(true, true, false, NOW.plusSeconds(60))}) {
      assertThatThrownBy(() -> service.cancel(context, "request-1", hash()))
          .hasMessage("REQUESTER_NOT_AUTHORIZED");
    }
    assertThat(captured.get()).isNull();
  }

  @Test void expiryReadinessAndMissingStorageCapabilityFailClosedBeforePersistence() {
    var context = context(true, true, true, NOW.plusSeconds(60));
    assertThatThrownBy(() -> service(true, true).cancel(
        context(true, true, true, NOW), "request-1", hash()))
        .hasMessage("CANCELLATION_AUTHENTICATION_EXPIRED");
    assertThatThrownBy(() -> service(false, true).cancel(context, "request-1", hash()))
        .hasMessage("SECURITY_DEPENDENCY_UNAVAILABLE");
    assertThatThrownBy(() -> service(true, false).cancel(context, "request-1", hash()))
        .hasMessage("APPROVAL_CANCELLATION_UNAVAILABLE");
    assertThat(captured.get()).isNull();
  }

  @Test void malformedTrustedOrBindingFieldsAreRejected() {
    var service = service(true, true);
    var context = context(true, true, true, NOW.plusSeconds(60));
    assertThatThrownBy(() -> service.cancel(context, "request-1", "not-a-hash"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TrustedRequesterContext(TENANT, "owner\nforged", true,
        Set.of(), Set.of(), "oidc", hash(), NOW)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TrustedRequesterContext(TENANT, "owner", true,
        Set.of(), Set.of(), "oidc", "token", NOW)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalCancellation(TENANT, "request-1", hash(), "owner",
        "bad method", hash(), NOW)).isInstanceOf(IllegalArgumentException.class);
    assertThat(captured.get()).isNull();
  }

  private HumanApprovalService service(boolean ready, boolean cancellation) {
    DurableApprovalState store = (DurableApprovalState) Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class<?>[] {cancellation
            ? DurableApprovalCancellationState.class : DurableApprovalState.class}, (proxy, method, args) -> {
          if (!method.getName().equals("cancel")) throw new AssertionError("Unexpected approval operation");
          captured.set((ApprovalCancellation) args[0]);
          var binding = new ApprovalBinding(TENANT, "request-1", hash(), hash(), "event", "evaluation",
              "issuer#owner", hash(), "policy", 1, "require_approval", "simulated.change", hash(),
              "reviewers", NOW.minusSeconds(1), NOW.plusSeconds(300), 1);
          return new ApprovalRequestState(binding, "cancelled", 2, null, null, null, null, null,
              null, NOW, null, null);
        });
    var executions = (DurableEnforcementState) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {DurableEnforcementState.class}, (proxy, method, args) -> {
          throw new AssertionError("Cancellation must not call the execution/claim port");
        });
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER, "synthetic",
        new byte[32], 30, clock, () -> { throw new AssertionError("No permit may be minted"); },
        new InMemoryPermitConsumptionStore());
    return new HumanApprovalService(new PolicyEvaluator(), JsonSupport.MAPPER.nullNode(), permits,
        store, executions, action -> { throw new AssertionError("No target call"); },
        () -> ready ? SecurityReadiness.allReady(NOW) : SecurityReadiness.forRuntime(false, NOW),
        clock, () -> { throw new AssertionError("No decision ID may be created"); }, "simulated", "simulator", 300);
  }

  private static TrustedRequesterContext context(boolean authenticated, boolean role, boolean scope, Instant expiry) {
    return new TrustedRequesterContext(TENANT, "issuer#owner", authenticated,
        role ? Set.of(HumanApprovalService.REQUESTER_ROLE) : Set.of(),
        scope ? Set.of(HumanApprovalService.CANCELLATION_SCOPE) : Set.of(), "oidc-access-token", hash(), expiry);
  }
  private static String hash() { return "sha256:" + "a".repeat(64); }
}
