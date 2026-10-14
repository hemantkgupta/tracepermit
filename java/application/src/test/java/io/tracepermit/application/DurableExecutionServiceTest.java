package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.DurableClaimResult;
import io.tracepermit.execution.DurableClaimStatus;
import io.tracepermit.execution.DurableEnforcementState;
import io.tracepermit.execution.DurableEvidenceRecord;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.ExecutionTransition;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DurableExecutionServiceTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");
  private static final TenantPartition ACME = new TenantPartition("acme");

  @Test
  void durableStateFailureBeforeClaimPreventsTargetDispatch() {
    AtomicInteger targetCalls = new AtomicInteger();
    FakeState state = new FakeState();
    state.claimFailure = new IllegalStateException("DURABLE_STATE_UNAVAILABLE");
    DurableExecutionService service = service(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.nullNode();
    });

    assertThatThrownBy(() -> service.execute(new TrustedTenantContext(ACME), event()))
        .isSameAs(state.claimFailure);
    assertThat(targetCalls).hasValue(0);
  }

  @Test
  void tenantMismatchAndReadinessFailureStopBeforeRepositoryAndTarget() {
    AtomicInteger targetCalls = new AtomicInteger();
    FakeState state = new FakeState();
    DurableExecutionService service = service(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.nullNode();
    });

    assertThatThrownBy(() -> service.execute(
        new TrustedTenantContext(new TenantPartition("other")), event()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("TENANT_CONTEXT_MISMATCH");
    assertThat(state.claimCalls).isZero();
    assertThat(targetCalls).hasValue(0);

    DurableExecutionService unavailable = service(
        state,
        action -> JsonSupport.MAPPER.nullNode(),
        () -> SecurityReadiness.forRuntime(false, NOW));
    assertThatThrownBy(() -> unavailable.execute(new TrustedTenantContext(ACME), event()))
        .hasMessage("SECURITY_DEPENDENCY_UNAVAILABLE");
    assertThat(state.claimCalls).isZero();
  }

  @Test
  void confirmedTargetDenialAndUncertainFailureUseDifferentDurableOutcomes() {
    FakeState deniedState = new FakeState();
    DurableExecutionService denied = service(deniedState, action -> {
      throw new TargetExecutionException("SIMULATOR_DENIED", "The simulator denied the action.");
    });
    assertThatThrownBy(() -> denied.execute(new TrustedTenantContext(ACME), event()))
        .isInstanceOf(TargetExecutionException.class);
    assertThat(deniedState.completedOutcome).isEqualTo("failed");
    assertThat(deniedState.completedReason).isEqualTo("SIMULATOR_DENIED");

    FakeState unknownState = new FakeState();
    DurableExecutionService unknown = service(unknownState, action -> {
      throw new IllegalStateException("connection-lost");
    });
    assertThatThrownBy(() -> unknown.execute(new TrustedTenantContext(ACME), event()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("connection-lost");
    assertThat(unknownState.completedOutcome).isEqualTo("unknown");
    assertThat(unknownState.completedReason).isEqualTo("TARGET_OUTCOME_UNCONFIRMED");
  }

  @Test
  void persistenceFailureAfterDispatchDoesNotReportSuccessOrRetryability() {
    AtomicInteger targetCalls = new AtomicInteger();
    FakeState state = new FakeState();
    state.completeFailure = new IllegalStateException("database-down");
    DurableExecutionService service = service(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("ok", true);
    });

    assertThatThrownBy(() -> service.execute(new TrustedTenantContext(ACME), event()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH")
        .hasCause(state.completeFailure);
    assertThat(targetCalls).hasValue(1);
  }

  @Test
  void duplicateStatesNeverDispatchAndReturnBoundedSemantics() {
    for (String status : List.of("executing", "unknown", "failed", "succeeded")) {
      AtomicInteger targetCalls = new AtomicInteger();
      FakeState state = new FakeState();
      state.claimStatus = DurableClaimStatus.IDEMPOTENCY_DUPLICATE;
      state.attemptStatus = status;
      DurableExecutionService service = service(state, action -> {
        targetCalls.incrementAndGet();
        return JsonSupport.MAPPER.nullNode();
      });
      if ("succeeded".equals(status)) {
        assertThat(service.execute(new TrustedTenantContext(ACME), event()).outcome())
            .isEqualTo("succeeded");
      } else {
        String expected = switch (status) {
          case "executing" -> "EXECUTION_ALREADY_IN_PROGRESS";
          case "unknown" -> "EXECUTION_OUTCOME_UNKNOWN";
          default -> "EXECUTION_PREVIOUSLY_FAILED";
        };
        assertThatThrownBy(() -> service.execute(new TrustedTenantContext(ACME), event()))
            .hasMessage(expected);
      }
      assertThat(targetCalls).hasValue(0);
    }
  }

  private static DurableExecutionService service(
      FakeState state, SimulatedTarget target) {
    return service(state, target, () -> SecurityReadiness.allReady(NOW));
  }

  private static DurableExecutionService service(
      FakeState state,
      SimulatedTarget target,
      java.util.function.Supplier<SecurityReadiness> readiness) {
    AtomicInteger ids = new AtomicInteger();
    return new DurableExecutionService(
        new PolicyEvaluator(),
        JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json")),
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "test-key",
            new byte[32],
            30,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "permit-" + ids.incrementAndGet(),
            new InMemoryPermitConsumptionStore()),
        state,
        target,
        readiness,
        Clock.fixed(NOW, ZoneOffset.UTC),
        "urn:tracepermit:test-executor",
        "simulator");
  }

  private static JsonNode event() {
    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    return JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
  }

  private static final class FakeState implements DurableEnforcementState {
    private RuntimeException claimFailure;
    private RuntimeException completeFailure;
    private DurableClaimStatus claimStatus = DurableClaimStatus.STARTED;
    private String attemptStatus = "executing";
    private int claimCalls;
    private String completedOutcome;
    private String completedReason;

    @Override
    public DurableClaimResult claim(DurableExecutionClaim claim) {
      claimCalls++;
      if (claimFailure != null) throw claimFailure;
      return new DurableClaimResult(claimStatus, attempt(attemptStatus));
    }

    @Override
    public ExecutionAttempt complete(
        TenantPartition tenant,
        String attemptId,
        String outcome,
        String reasonCode,
        Instant occurredAt) {
      if (completeFailure != null) throw completeFailure;
      completedOutcome = outcome;
      completedReason = reasonCode;
      return attempt(outcome);
    }

    @Override
    public ExecutionAttempt reconcile(
        TenantPartition tenant,
        String adapterId,
        String idempotencyKey,
        String outcome,
        String reasonCode,
        Instant occurredAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ExecutionAttempt findByIdempotencyKey(
        TenantPartition tenant, String adapterId, String idempotencyKey) {
      return null;
    }

    @Override
    public List<DurableEvidenceRecord> readEvidence(TenantPartition tenant) {
      return List.of();
    }

    private static ExecutionAttempt attempt(String status) {
      return new ExecutionAttempt(
          "attempt-1",
          "event-1",
          "permit-1",
          "sha256:" + "1".repeat(64),
          status,
          NOW,
          NOW,
          List.of(new ExecutionTransition(1, null, "executing", NOW, "EXECUTION_CLAIMED")),
          null);
    }
  }
}
