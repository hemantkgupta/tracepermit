package io.tracepermit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Concurrency guarantees of the un-striped enforcement path: the sequence window stays
 * atomic per principal, target invocation stays idempotent per event, the evidence hash
 * chain stays valid under concurrent appends, and distinct principals no longer
 * serialize on one process-wide monitor.
 */
final class SecureExecutionServiceConcurrencyTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-11T01:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final StripedMonitors STRIPES = new StripedMonitors();

  @Test
  void rapidReadGateStaysAtomicPerPrincipalUnderConcurrency() throws Exception {
    InMemoryEvidenceLedger evidence = evidence();
    AtomicInteger targetCalls = new AtomicInteger();
    SecureExecutionService service =
        service(
            evidence,
            action -> {
              targetCalls.incrementAndGet();
              return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
            },
            ActionSanitizer.NONE,
            InMemoryActionSequenceStore.oneMinute());
    List<Call> calls = runConcurrently(16, 32, index -> service.execute(event(100 + index, 20)));

    List<ExecutionResult> succeeded =
        calls.stream().filter(call -> call.result() != null
                && "succeeded".equals(call.result().outcome()))
            .map(Call::result)
            .toList();
    List<ExecutionResult> blocked =
        calls.stream().filter(call -> call.result() != null
                && "block".equals(call.result().outcome()))
            .map(Call::result)
            .toList();

    assertThat(succeeded).hasSize(2);
    assertThat(blocked).hasSize(30);
    blocked.forEach(result ->
        assertThat(result.evaluation().classification().signals())
            .contains(InMemoryActionSequenceStore.RAPID_READ));
    assertThat(calls).allSatisfy(call -> assertThat(call.error()).isNull());
    assertThat(targetCalls).hasValue(2);
    assertThat(evidence.readAll())
        .extracting(item -> item.path("payload").path("eventType").asText())
        .filteredOn("target.succeeded"::equals)
        .hasSize(2);
    assertThat(evidence.verify(null).valid()).isTrue();
  }

  @Test
  void identicalEventRacingCallsInvokeTargetExactlyOnce() throws Exception {
    InMemoryEvidenceLedger evidence = evidence();
    AtomicInteger targetCalls = new AtomicInteger();
    SecureExecutionService service =
        service(
            evidence,
            action -> {
              targetCalls.incrementAndGet();
              return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
            },
            ActionSanitizer.NONE,
            ActionSequenceStore.DISABLED);
    ObjectNode shared = event(300, 20);
    List<Call> calls = runConcurrently(16, 32, index -> service.execute(shared.deepCopy()));

    assertThat(targetCalls).hasValue(1);
    long succeeded =
        calls.stream()
            .filter(call -> call.result() != null
                && "succeeded".equals(call.result().outcome()))
            .count();
    long inProgress =
        calls.stream().filter(call -> "EXECUTION_ALREADY_IN_PROGRESS".equals(call.error()))
            .count();
    assertThat(succeeded).isGreaterThanOrEqualTo(1);
    assertThat(succeeded + inProgress).isEqualTo(32);
    assertThat(calls)
        .allSatisfy(call ->
            assertThat(call.error() == null
                || "EXECUTION_ALREADY_IN_PROGRESS".equals(call.error()))
                .isTrue());
    assertThat(evidence.readAll())
        .extracting(item -> item.path("payload").path("eventType").asText())
        .filteredOn("target.dispatched"::equals)
        .hasSize(1);
    assertThat(evidence.readAll())
        .extracting(item -> item.path("payload").path("eventType").asText())
        .filteredOn("target.succeeded"::equals)
        .hasSize(1);
    assertThat(evidence.verify(null).valid()).isTrue();
  }

  @Test
  void evidenceChainStaysValidUnderMixedConcurrentPrincipals() throws Exception {
    InMemoryEvidenceLedger evidence = evidence();
    AtomicInteger targetCalls = new AtomicInteger();
    SecureExecutionService service =
        service(
            evidence,
            action -> {
              targetCalls.incrementAndGet();
              return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
            },
            ActionSanitizer.NONE,
            InMemoryActionSequenceStore.oneMinute());
    List<Call> calls = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Call>> futures = new ArrayList<>();
      for (int principal = 0; principal < 8; principal++) {
        String subjectId = "user:person-" + principal + "@example.com";
        for (int read = 0; read < 2; read++) {
          ObjectNode event = event(400 + principal * 2 + read, 20, subjectId);
          futures.add(pool.submit(() -> invoke(() -> service.execute(event))));
        }
      }
      for (Future<Call> future : futures) calls.add(future.get(30, TimeUnit.SECONDS));
    }

    assertThat(calls).allSatisfy(call -> assertThat(call.error()).isNull());
    assertThat(calls)
        .filteredOn(call -> call.result() != null
            && "succeeded".equals(call.result().outcome()))
        .hasSize(16);
    assertThat(targetCalls).hasValue(16);
    assertThat(evidence.readAll()).hasSize(16 * 5);
    assertThat(evidence.verify(null).valid()).isTrue();
  }

  @Test
  void distinctPrincipalsNoLongerSerializeOnOneMonitor() throws Exception {
    String first = "user:person-0@example.com";
    String second = distinctStripeSubject(first);
    assertThat(STRIPES.lockFor(principalKeyOf(first)))
        .isNotSameAs(STRIPES.lockFor(principalKeyOf(second)));
    CyclicBarrier bothTargets = new CyclicBarrier(2);
    AtomicInteger targetCalls = new AtomicInteger();
    SecureExecutionService service =
        service(
            evidence(),
            action -> {
              targetCalls.incrementAndGet();
              try {
                bothTargets.await(10, TimeUnit.SECONDS);
              } catch (Exception failure) {
                throw new IllegalStateException("concurrent target barrier broken", failure);
              }
              return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
            },
            ActionSanitizer.NONE,
            ActionSequenceStore.DISABLED);

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<ExecutionResult> left =
          pool.submit(() -> service.execute(event(200, 20, first)));
      Future<ExecutionResult> right =
          pool.submit(() -> service.execute(event(201, 20, second)));
      // Under the old method-level monitor the second call could not reach the target,
      // the barrier would time out, and these gets would fail. Distinct stripes must
      // let both executions overlap at the target.
      assertThat(left.get(20, TimeUnit.SECONDS).outcome()).isEqualTo("succeeded");
      assertThat(right.get(20, TimeUnit.SECONDS).outcome()).isEqualTo("succeeded");
    }
    assertThat(targetCalls).hasValue(2);
  }

  private interface Invocation {
    ExecutionResult invoke();
  }

  private record Call(ExecutionResult result, String error) {}

  private static Call invoke(Invocation invocation) {
    try {
      return new Call(invocation.invoke(), null);
    } catch (RuntimeException failure) {
      return new Call(null, failure.getMessage());
    }
  }

  private static List<Call> runConcurrently(
      int threads, int count, java.util.function.IntFunction<ExecutionResult> action)
      throws Exception {
    List<Call> calls = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<Call>> futures = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        int current = index;
        futures.add(pool.submit(() -> invoke(() -> action.apply(current))));
      }
      for (Future<Call> future : futures) calls.add(future.get(60, TimeUnit.SECONDS));
    }
    return calls;
  }

  private static String distinctStripeSubject(String first) {
    for (int index = 1; index < 100_000; index++) {
      String candidate = "user:person-" + index + "@example.com";
      if (STRIPES.lockFor(principalKeyOf(first))
          != STRIPES.lockFor(principalKeyOf(candidate))) {
        return candidate;
      }
    }
    throw new IllegalStateException("No distinct-stripe subject found.");
  }

  private static String principalKeyOf(String subjectId) {
    return "acme\u0000" + subjectId;
  }

  private static SecureExecutionService service(
      InMemoryEvidenceLedger evidence,
      SimulatedTarget target,
      ActionSanitizer sanitizer,
      ActionSequenceStore sequences) {
    AtomicInteger identifiers = new AtomicInteger();
    return new SecureExecutionService(
        new PolicyEvaluator(),
        JsonSupport.read(ROOT.resolve("policies/aws-agentic-demo.json")),
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "concurrency-test-key",
            new byte[32],
            30,
            CLOCK,
            () -> "permit-" + identifiers.incrementAndGet(),
            new InMemoryPermitConsumptionStore()),
        new InMemoryExecutionLedger(() -> "attempt-" + identifiers.incrementAndGet()),
        evidence,
        target,
        () -> SecurityReadiness.allReady(NOW),
        CLOCK,
        "urn:tracepermit:concurrency-test-simulator",
        sanitizer,
        sequences);
  }

  private static InMemoryEvidenceLedger evidence() {
    AtomicInteger identifiers = new AtomicInteger();
    return new InMemoryEvidenceLedger(
        "urn:tracepermit:concurrency-test",
        CLOCK,
        () -> "evidence-" + identifiers.incrementAndGet());
  }

  private static ObjectNode event(int suffix, int limit) {
    ObjectNode event =
        (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    event.put("eventId", String.format("00000000-0000-7000-8000-%012d", suffix));
    ((ObjectNode) event.path("action").path("arguments")).put("limit", limit);
    return event;
  }

  private static ObjectNode event(int suffix, int limit, String subjectId) {
    ObjectNode event = event(suffix, limit);
    String original = event.path("identity").path("subject").path("id").asText();
    ((ObjectNode) event.path("identity").path("subject")).put("id", subjectId);
    ((ObjectNode) event.path("lineage")).put("rootPrincipalId", subjectId);
    for (JsonNode hop : event.path("lineage").path("hops")) {
      if (original.equals(hop.path("subjectId").asText())) {
        ((ObjectNode) hop).put("subjectId", subjectId);
      }
    }
    for (JsonNode item : event.path("identity").path("evidence")) {
      if (original.equals(item.path("subjectId").asText())) {
        ((ObjectNode) item).put("subjectId", subjectId);
      }
    }
    return event;
  }
}
