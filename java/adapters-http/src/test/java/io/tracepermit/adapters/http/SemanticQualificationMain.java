package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.ActionSanitizer;
import io.tracepermit.application.ActionSequenceStore;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.SecureExecutionService;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.policy.Classification;
import io.tracepermit.policy.ClassificationContext;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded local qualification for the deterministic semantic enforcement path. */
public final class SemanticQualificationMain {
  private static final int POLICY_WARMUP = 2_000;
  private static final int POLICY_SAMPLES = 10_000;
  private static final int AUTH_WARMUP = 200;
  private static final int AUTH_SAMPLES = 1_000;
  private static final double POLICY_P95_OBJECTIVE_MS = 25.0;
  private static final double AUTH_P95_OBJECTIVE_MS = 100.0;

  private SemanticQualificationMain() {}

  public static void main(String[] args) {
    if (args.length != 0) throw new IllegalArgumentException("No arguments are supported.");
    Path root = Path.of(System.getProperty("tracepermit.repositoryRoot"));
    JsonNode policy = JsonSupport.read(root.resolve("policies/aws-agentic-demo.json"));
    ObjectNode base = (ObjectNode) JsonSupport.read(root.resolve("fixtures/chatgpt-aws-read.json"));
    PolicyEvaluator evaluator = new PolicyEvaluator();

    for (int index = 0; index < POLICY_WARMUP; index++) evaluator.evaluate(base, policy);
    long[] policyNanos = new long[POLICY_SAMPLES];
    for (int index = 0; index < POLICY_SAMPLES; index++) {
      long started = System.nanoTime();
      evaluator.evaluate(base, policy);
      policyNanos[index] = System.nanoTime() - started;
    }

    Clock clock = Clock.systemUTC();
    SecureExecutionService authorization =
        service(policy, clock, ActionSanitizer.NONE, ActionSequenceStore.DISABLED, new AtomicInteger());
    for (int index = 0; index < AUTH_WARMUP; index++) {
      authorization.execute(uniqueEvent(base, index));
    }
    long[] authorizationNanos = new long[AUTH_SAMPLES];
    for (int index = 0; index < AUTH_SAMPLES; index++) {
      JsonNode event = uniqueEvent(base, index + AUTH_WARMUP);
      long started = System.nanoTime();
      authorization.execute(event);
      authorizationNanos[index] = System.nanoTime() - started;
    }

    AtomicInteger failSecureDispatches = new AtomicInteger();
    ActionSequenceStore unavailableSequence =
        new ActionSequenceStore() {
          @Override
          public ClassificationContext contextFor(JsonNode event, Instant now) {
            throw new IllegalStateException("simulated sequence-state outage");
          }

          @Override
          public void record(JsonNode event, Classification classification, Instant now) {}
        };
    SecureExecutionService unavailable =
        service(policy, clock, ActionSanitizer.NONE, unavailableSequence, failSecureDispatches);
    String sequenceFailure;
    try {
      unavailable.execute(uniqueEvent(base, 20_000));
      throw new IllegalStateException("Unavailable sequence state did not fail closed.");
    } catch (IllegalStateException failure) {
      sequenceFailure = failure.getMessage();
      require(
          "SEQUENCE_STATE_UNAVAILABLE".equals(sequenceFailure),
          "Unexpected sequence-state failure.");
    }

    ObjectNode oversized = uniqueEvent(base, 20_001);
    ((ObjectNode) oversized.path("action").path("arguments")).put("limit", 500);
    SecureExecutionService missingSanitizer =
        service(
            policy,
            clock,
            ActionSanitizer.NONE,
            ActionSequenceStore.DISABLED,
            failSecureDispatches);
    ExecutionResult unresolved = missingSanitizer.execute(oversized);
    require("sanitize".equals(unresolved.outcome()), "Unresolved sanitization changed outcome.");
    require(failSecureDispatches.get() == 0, "A fail-secure probe invoked the target.");

    Percentiles policyResult = percentiles(policyNanos);
    Percentiles authorizationResult = percentiles(authorizationNanos);
    boolean policyPass = policyResult.p95Ms() <= POLICY_P95_OBJECTIVE_MS;
    boolean authorizationPass = authorizationResult.p95Ms() <= AUTH_P95_OBJECTIVE_MS;

    System.out.println();
    System.out.println("TracePermit Scenario 5 — Local latency and fail-secure qualification");
    System.out.println("====================================================================");
    System.out.printf(
        "Deterministic classification + policy: %,d samples, p50 %.3f ms, p95 %.3f ms%n",
        POLICY_SAMPLES, policyResult.p50Ms(), policyResult.p95Ms());
    System.out.printf(
        "  PERF-001 local objective <= %.1f ms p95: %s%n",
        POLICY_P95_OBJECTIVE_MS, pass(policyPass));
    System.out.printf(
        "Local authorization + permit + in-memory simulator boundary: %,d samples, p50 %.3f ms, p95 %.3f ms%n",
        AUTH_SAMPLES, authorizationResult.p50Ms(), authorizationResult.p95Ms());
    System.out.printf(
        "  PERF-002 local POC objective <= %.1f ms p95: %s%n",
        AUTH_P95_OBJECTIVE_MS, pass(authorizationPass));
    System.out.println("Sequence-state outage: " + sequenceFailure + "; target dispatches = 0");
    System.out.println("Missing sanitizer: sanitize remains not-executed; target dispatches = 0");
    System.out.println("No network target, provider credential, or production performance claim is involved.");

    require(policyPass, "PERF-001 local objective was not met.");
    require(authorizationPass, "PERF-002 local POC objective was not met.");
    System.out.println("\n[PASS] Local latency objectives and fail-secure probes passed.");
  }

  private static SecureExecutionService service(
      JsonNode policy,
      Clock clock,
      ActionSanitizer sanitizer,
      ActionSequenceStore sequenceStore,
      AtomicInteger targetCalls) {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    AtomicInteger ids = new AtomicInteger();
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    return new SecureExecutionService(
        new PolicyEvaluator(),
        policy,
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "qualification-key",
            key,
            30,
            clock,
            () -> "permit-" + UUID.randomUUID(),
            new InMemoryPermitConsumptionStore()),
        new InMemoryExecutionLedger(() -> "attempt-" + UUID.randomUUID()),
        new InMemoryEvidenceLedger(
            "urn:tracepermit:semantic-qualification",
            clock,
            () -> "evidence-" + ids.incrementAndGet()),
        action -> {
          targetCalls.incrementAndGet();
          return simulator.invoke(action);
        },
        () -> SecurityReadiness.allReady(clock.instant()),
        clock,
        "urn:tracepermit:qualification-simulator",
        sanitizer,
        sequenceStore);
  }

  private static ObjectNode uniqueEvent(ObjectNode base, int suffix) {
    ObjectNode event = base.deepCopy();
    event.put("eventId", String.format("00000000-0000-7000-8000-%012d", suffix + 1));
    return event;
  }

  private static Percentiles percentiles(long[] values) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    return new Percentiles(milliseconds(sorted[index(sorted.length, 0.50)]), milliseconds(sorted[index(sorted.length, 0.95)]));
  }

  private static int index(int size, double percentile) {
    return Math.max(0, Math.min(size - 1, (int) Math.ceil(percentile * size) - 1));
  }

  private static double milliseconds(long nanos) {
    return nanos / 1_000_000.0;
  }

  private static String pass(boolean value) {
    return value ? "PASS" : "FAIL";
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private record Percentiles(double p50Ms, double p95Ms) {}
}
