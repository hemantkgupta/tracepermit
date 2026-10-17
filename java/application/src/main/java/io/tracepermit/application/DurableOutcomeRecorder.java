package io.tracepermit.application;

import io.tracepermit.execution.DurableEnforcementState;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.TenantPartition;
import java.time.Clock;

/** Records a dispatched outcome without ever acquiring authority to invoke a target again. */
final class DurableOutcomeRecorder {
  static final String UNCONFIRMED = "DURABLE_COMPLETION_UNCONFIRMED";

  private DurableOutcomeRecorder() {}

  static ExecutionAttempt complete(
      DurableEnforcementState state,
      TenantPartition tenant,
      ExecutionAttempt attempt,
      String outcome,
      String reasonCode,
      Clock clock) {
    try {
      return state.complete(tenant, attempt.attemptId(), outcome, reasonCode, clock.instant());
    } catch (RuntimeException failure) {
      IllegalStateException uncertain =
          new IllegalStateException("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH", failure);
      try {
        // A completion exception is not proof of rollback. The repository must atomically
        // transition only executing attempts; a committed terminal outcome cannot regress.
        // Persisting unknown also queues lookup-only reconciliation in the same transaction.
        state.complete(tenant, attempt.attemptId(), "unknown", UNCONFIRMED, clock.instant());
      } catch (RuntimeException recordingFailure) {
        if (recordingFailure != failure) uncertain.addSuppressed(recordingFailure);
      }
      // Even successful uncertainty recording does not turn the original call into success.
      // There is exactly one recovery write, no target retry, new claim, or unbounded loop.
      throw uncertain;
    }
  }
}
