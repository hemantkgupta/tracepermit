package io.tracepermit.application;

import io.tracepermit.execution.DurableReconciliationState;
import io.tracepermit.execution.ReconciliationCandidate;
import io.tracepermit.execution.TenantPartition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Resolves unknown outcomes by read-only lookup; it never owns a target invocation port. */
public final class ExecutionReconciliationWorker {
  private static final int MAXIMUM_BATCH = 100;

  private final DurableReconciliationState state;
  private final TargetOutcomeResolver resolver;
  private final Clock clock;
  private final String workerId;
  private final Duration leaseDuration;
  private final Duration retryDelay;

  public ExecutionReconciliationWorker(
      DurableReconciliationState state,
      TargetOutcomeResolver resolver,
      Clock clock,
      String workerId,
      Duration leaseDuration,
      Duration retryDelay) {
    this.state = Objects.requireNonNull(state);
    this.resolver = Objects.requireNonNull(resolver);
    this.clock = Objects.requireNonNull(clock);
    this.workerId = requireWorkerId(workerId);
    this.leaseDuration = requireDuration(leaseDuration, Duration.ofMinutes(5), "lease");
    this.retryDelay = requireDuration(retryDelay, Duration.ofHours(24), "retry");
  }

  public ReconciliationBatchResult reconcileBatch(TenantPartition tenant, int maximumEntries) {
    Objects.requireNonNull(tenant);
    if (maximumEntries < 1 || maximumEntries > MAXIMUM_BATCH) {
      throw new IllegalArgumentException("The reconciliation batch size is invalid.");
    }
    Instant claimedAt = clock.instant();
    List<ReconciliationCandidate> candidates =
        state.claimUnknownBatch(
            tenant, workerId, maximumEntries, claimedAt, claimedAt.plus(leaseDuration));
    if (candidates.size() > maximumEntries) {
      throw new IllegalStateException("The reconciliation state returned an oversized batch.");
    }
    int reconciled = 0;
    for (ReconciliationCandidate candidate : candidates) {
      requireLease(candidate);
      ReconciliationObservation observation;
      try {
        observation = Objects.requireNonNull(resolver.lookup(candidate));
      } catch (RuntimeException failure) {
        state.deferReconciliation(
            candidate,
            workerId,
            "TARGET_LOOKUP_FAILED",
            clock.instant().plus(retryDelay));
        continue;
      }
      if (observation.confirmed()) {
        state.completeReconciliation(
            candidate,
            workerId,
            observation.outcome(),
            observation.reasonCode(),
            clock.instant());
        reconciled++;
      } else {
        state.deferReconciliation(
            candidate,
            workerId,
            observation.reasonCode(),
            clock.instant().plus(retryDelay));
      }
    }
    return new ReconciliationBatchResult(
        candidates.size(), reconciled, candidates.size() - reconciled);
  }

  private void requireLease(ReconciliationCandidate candidate) {
    if (!workerId.equals(candidate.leaseOwner())
        || !candidate.leaseExpiresAt().isAfter(clock.instant())) {
      throw new IllegalStateException("The reconciliation lease is not valid.");
    }
  }

  private static String requireWorkerId(String value) {
    Objects.requireNonNull(value);
    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}")) {
      throw new IllegalArgumentException("The reconciliation worker ID is invalid.");
    }
    return value;
  }

  private static Duration requireDuration(Duration value, Duration maximum, String label) {
    Objects.requireNonNull(value);
    if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException("The reconciliation " + label + " duration is invalid.");
    }
    return value;
  }
}
