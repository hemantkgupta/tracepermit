package io.tracepermit.application;

import io.tracepermit.execution.DurableClaimResult;
import io.tracepermit.execution.DurableEnforcementState;
import io.tracepermit.execution.DurableEvidenceRecord;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.ExecutionTransition;
import io.tracepermit.execution.TenantPartition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DurableOutcomeRecorderTest {
  private static final Instant NOW = Instant.parse("2026-10-17T10:15:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final TenantPartition TENANT = new TenantPartition("synthetic-tenant");

  @ParameterizedTest
  @ValueSource(strings = {"succeeded", "failed", "unknown"})
  void confirmedCompletionUsesOneWrite(String outcome) {
    State state = new State();
    ExecutionAttempt result = DurableOutcomeRecorder.complete(
        state, TENANT, attempt("executing"), outcome, "TARGET_OBSERVED", CLOCK);
    assertThat(result.status()).isEqualTo(outcome);
    assertThat(state.outcomes).containsExactly(outcome);
    assertThat(state.reasons).containsExactly("TARGET_OBSERVED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"succeeded", "failed", "unknown"})
  void uncommittedCompletionGetsOneUnknownWriteButOriginalCallStaysUncertain(String outcome) {
    State state = new State();
    state.firstFailure = new IllegalStateException("uncommitted");
    assertThatThrownBy(() -> DurableOutcomeRecorder.complete(
        state, TENANT, attempt("executing"), outcome, "TARGET_OBSERVED", CLOCK))
        .hasMessage("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH")
        .hasCause(state.firstFailure);
    assertThat(state.status).isEqualTo("unknown");
    assertThat(state.outcomes).containsExactly(outcome, "unknown");
    assertThat(state.reasons).containsExactly("TARGET_OBSERVED", "DURABLE_COMPLETION_UNCONFIRMED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"succeeded", "failed", "unknown"})
  void lostCommitAcknowledgementCannotOverwriteCommittedOutcome(String outcome) {
    State state = new State();
    state.firstFailure = new IllegalStateException("commit-acknowledgement-lost");
    state.commitBeforeFailure = true;
    assertThatThrownBy(() -> DurableOutcomeRecorder.complete(
        state, TENANT, attempt("executing"), outcome, "TARGET_OBSERVED", CLOCK))
        .hasMessage("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH")
        .hasCause(state.firstFailure)
        .satisfies(failure -> assertThat(failure.getSuppressed())
            .extracting(Throwable::getMessage).containsExactly("ALREADY_COMPLETED"));
    assertThat(state.status).isEqualTo(outcome);
    assertThat(state.outcomes).hasSize(2);
  }

  @Test
  void persistentOutageDoesNotLoopAndRetainsBothFailures() {
    State state = new State();
    state.firstFailure = new IllegalStateException("first-unavailable");
    state.secondFailure = new IllegalStateException("still-unavailable");
    assertThatThrownBy(() -> DurableOutcomeRecorder.complete(
        state, TENANT, attempt("executing"), "succeeded", "TARGET_OBSERVED", CLOCK))
        .hasMessage("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH")
        .hasCause(state.firstFailure)
        .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(state.secondFailure));
    assertThat(state.status).isEqualTo("executing");
    assertThat(state.outcomes).containsExactly("succeeded", "unknown");
  }

  @Test
  void repeatedExceptionObjectCannotCauseSelfSuppressionOrHideOriginalFailure() {
    State state = new State();
    state.firstFailure = new IllegalStateException("same-unavailable");
    state.secondFailure = state.firstFailure;
    assertThatThrownBy(() -> DurableOutcomeRecorder.complete(
        state, TENANT, attempt("executing"), "succeeded", "TARGET_OBSERVED", CLOCK))
        .hasMessage("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH")
        .hasCause(state.firstFailure)
        .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
    assertThat(state.outcomes).hasSize(2);
  }

  private static ExecutionAttempt attempt(String status) {
    return new ExecutionAttempt(
        "attempt-one", "event-one", "permit-one", "sha256:" + "1".repeat(64),
        status, NOW, NOW,
        List.of(new ExecutionTransition(1, null, "executing", NOW, "EXECUTION_CLAIMED")), null);
  }

  private static final class State implements DurableEnforcementState {
    private final List<String> outcomes = new ArrayList<>();
    private final List<String> reasons = new ArrayList<>();
    private String status = "executing";
    private RuntimeException firstFailure;
    private RuntimeException secondFailure;
    private boolean commitBeforeFailure;

    @Override
    public ExecutionAttempt complete(
        TenantPartition tenant, String attemptId, String outcome, String reasonCode, Instant at) {
      assertThat(tenant).isEqualTo(TENANT);
      assertThat(attemptId).isEqualTo("attempt-one");
      assertThat(at).isEqualTo(NOW);
      outcomes.add(outcome);
      reasons.add(reasonCode);
      if (outcomes.size() == 1 && firstFailure != null) {
        if (commitBeforeFailure) status = outcome;
        throw firstFailure;
      }
      if (outcomes.size() == 2 && secondFailure != null) throw secondFailure;
      if (!"executing".equals(status)) throw new IllegalStateException("ALREADY_COMPLETED");
      status = outcome;
      return attempt(status);
    }

    @Override public DurableClaimResult claim(DurableExecutionClaim claim) {
      throw new AssertionError("Outcome recording must not acquire new authority.");
    }
    @Override public ExecutionAttempt reconcile(
        TenantPartition tenant, String adapter, String key, String outcome, String reason, Instant at) {
      throw new AssertionError("A recording exception is not a confirmed target observation.");
    }
    @Override public ExecutionAttempt findByIdempotencyKey(
        TenantPartition tenant, String adapter, String key) { throw new AssertionError(); }
    @Override public List<DurableEvidenceRecord> readEvidence(TenantPartition tenant) {
      throw new AssertionError();
    }
  }
}
