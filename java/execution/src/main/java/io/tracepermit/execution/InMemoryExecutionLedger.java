package io.tracepermit.execution;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class InMemoryExecutionLedger {
  private final Map<String, ExecutionAttempt> byAttemptId = new ConcurrentHashMap<>();
  private final Map<String, String> attemptIdByKey = new ConcurrentHashMap<>();
  private final Supplier<String> createId;

  public InMemoryExecutionLedger(Supplier<String> createId) {
    this.createId = Objects.requireNonNull(createId);
  }

  public static String idempotencyKey(JsonNode event, String actionHash) {
    JsonNode action = ActionValidator.validate(event);
    if (!actionHash.matches("^sha256:[a-f0-9]{64}$")) throw new IllegalArgumentException("The action hash is invalid.");
    String material = action.path("eventId").asText() + ":" + actionHash;
    return "sha256:" + java.util.HexFormat.of().formatHex(CanonicalJson.sha256Bytes(material.getBytes(StandardCharsets.UTF_8)));
  }

  public synchronized BeginExecutionResult begin(String eventId, String permitId, String idempotencyKey, Instant at) {
    String existingId = attemptIdByKey.get(idempotencyKey);
    if (existingId != null) return new BeginExecutionResult(false, byAttemptId.get(existingId));
    ExecutionAttempt attempt = new ExecutionAttempt(createId.get(), eventId, permitId, idempotencyKey, "executing", at, at,
        java.util.List.of(new ExecutionTransition(1, null, "executing", at, "EXECUTION_CLAIMED")), null);
    byAttemptId.put(attempt.attemptId(), attempt);
    attemptIdByKey.put(idempotencyKey, attempt.attemptId());
    return new BeginExecutionResult(true, attempt);
  }

  public synchronized ExecutionAttempt succeed(String attemptId, Instant at, JsonNode result) {
    return transition(attemptId, "executing", "succeeded", at, "TARGET_CONFIRMED_SUCCESS", result);
  }

  public synchronized ExecutionAttempt fail(String attemptId, Instant at, String reasonCode) {
    return transition(attemptId, "executing", "failed", at, reasonCode, null);
  }

  public synchronized ExecutionAttempt markUnknown(String attemptId, Instant at, String reasonCode) {
    return transition(attemptId, "executing", "unknown", at, reasonCode, null);
  }

  public synchronized ExecutionAttempt reconcile(String idempotencyKey, Instant at, String outcome, String reasonCode, JsonNode result) {
    String attemptId = attemptIdByKey.get(idempotencyKey);
    if (attemptId == null) throw new IllegalArgumentException("Cannot reconcile an unknown idempotency key.");
    if (!"succeeded".equals(outcome) && !"failed".equals(outcome)) throw new IllegalArgumentException("Reconciliation outcome must be succeeded or failed.");
    return transition(attemptId, "unknown", outcome, at, reasonCode, "succeeded".equals(outcome) ? result : null);
  }

  public synchronized ExecutionAttempt findByIdempotencyKey(String key) {
    String attemptId = attemptIdByKey.get(key);
    return attemptId == null ? null : byAttemptId.get(attemptId);
  }

  private ExecutionAttempt transition(String attemptId, String expected, String target, Instant at, String reasonCode, JsonNode result) {
    ExecutionAttempt current = byAttemptId.get(attemptId);
    if (current == null) throw new IllegalArgumentException("Execution attempt does not exist.");
    if (!expected.equals(current.status())) throw new IllegalStateException("Execution attempt is " + current.status() + ", not " + expected + ".");
    ArrayList<ExecutionTransition> transitions = new ArrayList<>(current.transitions());
    transitions.add(new ExecutionTransition(transitions.size() + 1, current.status(), target, at, reasonCode));
    ExecutionAttempt next = new ExecutionAttempt(current.attemptId(), current.eventId(), current.permitId(), current.idempotencyKey(), target,
        current.startedAt(), at, transitions, result);
    byAttemptId.put(attemptId, next);
    return next;
  }
}
