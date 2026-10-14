package io.tracepermit.execution;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class InMemoryExecutionLedgerTest {
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");

  @Test
  void lifecycleIsMonotonicAndRejectsInvalidTransitions() {
    InMemoryExecutionLedger ledger = new InMemoryExecutionLedger(() -> "attempt-1");
    ExecutionAttempt attempt = ledger.begin("event-1", "permit-1", "key-1", NOW).attempt();

    ExecutionAttempt unknown =
        ledger.markUnknown(attempt.attemptId(), NOW.plusSeconds(1), "TARGET_OUTCOME_UNCONFIRMED");
    assertThat(unknown.status()).isEqualTo("unknown");
    assertThatThrownBy(
            () -> ledger.succeed(attempt.attemptId(), NOW.plusSeconds(2), result(true)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown, not executing");

    ExecutionAttempt reconciled = ledger.reconcile(
        "key-1", NOW.plusSeconds(3), "succeeded", "OPERATOR_CONFIRMED_SUCCESS", result(true));
    assertThat(reconciled.status()).isEqualTo("succeeded");
    assertThat(reconciled.transitions()).extracting(ExecutionTransition::sequence)
        .containsExactly(1, 2, 3);
    assertThatThrownBy(
            () -> ledger.reconcile(
                "key-1", NOW.plusSeconds(4), "failed", "LATE_CONTRADICTION", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("succeeded, not unknown");
  }

  @Test
  void resultsAndTransitionListsCannotMutateStoredEvidence() {
    InMemoryExecutionLedger ledger = new InMemoryExecutionLedger(() -> "attempt-1");
    ExecutionAttempt attempt = ledger.begin("event-1", "permit-1", "key-1", NOW).attempt();
    ObjectNode targetResult = result(true);
    ExecutionAttempt succeeded =
        ledger.succeed(attempt.attemptId(), NOW.plusSeconds(1), targetResult);

    targetResult.put("ok", false);
    ((ObjectNode) succeeded.result()).put("ok", false);
    assertThat(ledger.findByIdempotencyKey("key-1").result().path("ok").asBoolean()).isTrue();
    assertThatThrownBy(
            () -> succeeded.transitions().add(
                new ExecutionTransition(3, "succeeded", "failed", NOW, "MUTATED")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void reconciliationRejectsUnknownKeysAndOutcomes() {
    InMemoryExecutionLedger ledger = new InMemoryExecutionLedger(() -> "attempt-1");
    assertThatThrownBy(
            () -> ledger.reconcile("missing", NOW, "succeeded", "CONFIRMED", result(true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot reconcile an unknown idempotency key.");

    ledger.begin("event-1", "permit-1", "key-1", NOW);
    assertThatThrownBy(
            () -> ledger.reconcile("key-1", NOW, "unknown", "STILL_UNKNOWN", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Reconciliation outcome must be succeeded or failed.");
  }

  private static ObjectNode result(boolean ok) {
    return JsonSupport.MAPPER.createObjectNode().put("ok", ok);
  }
}
