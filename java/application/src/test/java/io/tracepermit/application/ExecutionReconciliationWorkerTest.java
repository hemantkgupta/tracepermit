package io.tracepermit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.execution.DurableReconciliationState;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.ExecutionTransition;
import io.tracepermit.execution.ReconciliationCandidate;
import io.tracepermit.execution.TenantPartition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ExecutionReconciliationWorkerTest {
  private static final Instant NOW = Instant.parse("2026-10-10T10:30:00Z");
  private static final TenantPartition TENANT = new TenantPartition("acme");

  @Test
  void confirmsOutcomeThroughReadOnlyLookupAndNeverOwnsAnExecutionPort() {
    RecordingState state = new RecordingState(candidate("worker-1", NOW.plusSeconds(30)));
    AtomicInteger lookups = new AtomicInteger();
    ExecutionReconciliationWorker worker =
        worker(
            state,
            item -> {
              lookups.incrementAndGet();
              assertThat(item.idempotencyKey()).isEqualTo(hash('1'));
              return new ReconciliationObservation(
                  "succeeded", "RECONCILIATION_CONFIRMED_SUCCESS");
            });

    assertThat(worker.reconcileBatch(TENANT, 10))
        .isEqualTo(new ReconciliationBatchResult(1, 1, 0));
    assertThat(lookups).hasValue(1);
    assertThat(state.completed).containsExactly("succeeded");
    assertThat(state.deferred).isEmpty();
  }

  @Test
  void defersStillUnknownAndFailedLookupsWithoutInventingAResult() {
    RecordingState unknown = new RecordingState(candidate("worker-1", NOW.plusSeconds(30)));
    assertThat(
            worker(
                    unknown,
                    item ->
                        new ReconciliationObservation(
                            "unknown", "TARGET_STILL_UNCONFIRMED"))
                .reconcileBatch(TENANT, 1))
        .isEqualTo(new ReconciliationBatchResult(1, 0, 1));
    assertThat(unknown.completed).isEmpty();
    assertThat(unknown.deferred).containsExactly("TARGET_STILL_UNCONFIRMED");

    RecordingState failed = new RecordingState(candidate("worker-1", NOW.plusSeconds(30)));
    assertThat(
            worker(
                    failed,
                    item -> {
                      throw new IllegalStateException("simulated target lookup outage");
                    })
                .reconcileBatch(TENANT, 1)
                .deferred())
        .isEqualTo(1);
    assertThat(failed.completed).isEmpty();
    assertThat(failed.deferred).containsExactly("TARGET_LOOKUP_FAILED");
  }

  @Test
  void rejectsExpiredLeasesMalformedObservationsAndUnsafeConfiguration() {
    RecordingState expired = new RecordingState(candidate("worker-1", NOW));
    assertThatThrownBy(() -> worker(expired, item -> null).reconcileBatch(TENANT, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease");
    assertThatThrownBy(() -> new ReconciliationObservation("maybe", "INVALID"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ExecutionReconciliationWorker(
                    expired,
                    item -> new ReconciliationObservation("unknown", "STILL_UNKNOWN"),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    "bad worker",
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ExecutionReconciliationWorker worker(
      RecordingState state, TargetOutcomeResolver resolver) {
    return new ExecutionReconciliationWorker(
        state,
        resolver,
        Clock.fixed(NOW, ZoneOffset.UTC),
        "worker-1",
        Duration.ofSeconds(30),
        Duration.ofSeconds(10));
  }

  private static ReconciliationCandidate candidate(String owner, Instant leaseExpiresAt) {
    return new ReconciliationCandidate(
        TENANT,
        "attempt-1",
        "simulator",
        hash('1'),
        NOW.minusSeconds(1),
        1,
        owner,
        leaseExpiresAt);
  }

  private static String hash(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static final class RecordingState implements DurableReconciliationState {
    private final ReconciliationCandidate candidate;
    private final List<String> completed = new ArrayList<>();
    private final List<String> deferred = new ArrayList<>();

    private RecordingState(ReconciliationCandidate candidate) {
      this.candidate = candidate;
    }

    @Override
    public List<ReconciliationCandidate> claimUnknownBatch(
        TenantPartition tenant,
        String workerId,
        int maximumEntries,
        Instant claimedAt,
        Instant leaseExpiresAt) {
      return List.of(candidate);
    }

    @Override
    public ExecutionAttempt completeReconciliation(
        ReconciliationCandidate item,
        String workerId,
        String outcome,
        String reasonCode,
        Instant occurredAt) {
      completed.add(outcome);
      return new ExecutionAttempt(
          item.attemptId(),
          "event-1",
          "permit-1",
          item.idempotencyKey(),
          outcome,
          item.unknownSince().minusSeconds(1),
          occurredAt,
          List.of(
              new ExecutionTransition(
                  1,
                  null,
                  "executing",
                  item.unknownSince().minusSeconds(1),
                  "EXECUTION_CLAIMED"),
              new ExecutionTransition(
                  2,
                  "executing",
                  "unknown",
                  item.unknownSince(),
                  "TARGET_OUTCOME_UNCONFIRMED"),
              new ExecutionTransition(3, "unknown", outcome, occurredAt, reasonCode)),
          null);
    }

    @Override
    public void deferReconciliation(
        ReconciliationCandidate item,
        String workerId,
        String reasonCode,
        Instant nextAttemptAt) {
      deferred.add(reasonCode);
    }
  }
}
