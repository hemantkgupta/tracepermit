package io.tracepermit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.policy.Classification;
import io.tracepermit.policy.ClassificationContext;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.policy.PolicyValidator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Concurrency guarantees of the striped enforcement path: the sequence window stays
 * atomic per principal, target invocation stays idempotent per event, the evidence hash
 * chain stays valid under concurrent appends, and distinct principals no longer
 * serialize on the service instance's old execute monitor.
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
    try (BoundedExecutor pool = boundedExecutor(16)) {
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

    try (BoundedExecutor pool = boundedExecutor(2)) {
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

  @Test
  void temporalOrderRegression_stalePreLockTimestamp_shippedPolicy() throws Exception {
    runStalePreLockTimestampSchedule(false);
  }

  @Test
  void temporalOrderRegression_stalePreLockTimestamp_conditionalPolicy() throws Exception {
    runStalePreLockTimestampSchedule(true);
  }

  private static void runStalePreLockTimestampSchedule(boolean conditionalPolicy) throws Exception {
    Instant t0 = Instant.parse("2026-10-11T01:00:00Z");
    Instant t1 = t0.plusSeconds(1);
    CountDownLatch sendCaptured = new CountDownLatch(1);
    CountDownLatch sendRelease = new CountDownLatch(1);
    AtomicBoolean sendCapturedFirst = new AtomicBoolean(true);

    Clock controllableClock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            if (sendCapturedFirst.compareAndSet(true, false)) {
              sendCaptured.countDown();
              try {
                if (!sendRelease.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Test latch timeout waiting for sendRelease");
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              return t0;
            }
            return t1;
          }
        };

    ObjectNode policy = syntheticPolicy(conditionalPolicy);
    AtomicInteger sendDispatches = new AtomicInteger();
    SecureExecutionService service =
        syntheticService(
            policy,
            controllableClock,
            sendDispatches,
            InMemoryActionSequenceStore.oneMinute());

    ObjectNode send = syntheticEvent(901, false);
    ObjectNode read = syntheticEvent(902, true);
    ObjectNode subsequentSend = syntheticEvent(903, false);

    try (BoundedExecutor pool = boundedExecutor(2)) {
      Future<ExecutionResult> sendFuture = pool.submit(() -> service.execute(send));
      assertThat(sendCaptured.await(5, TimeUnit.SECONDS)).isTrue();

      Future<ExecutionResult> readFuture = pool.submit(() -> service.execute(read));
      boolean readCompletedBeforeSend = false;
      try {
        ExecutionResult readResult = readFuture.get(100, TimeUnit.MILLISECONDS);
        assertThat(readResult.outcome()).isEqualTo("succeeded");
        readCompletedBeforeSend = true;
      } catch (TimeoutException expectedWhenSendHoldsStripeLock) {
        // Under fixed clock placement inside the stripe lock, send already holds the lock.
        // Read is properly blocked from interleaving and cannot complete before send.
      } finally {
        sendRelease.countDown();
      }

      ExecutionResult sendResult = sendFuture.get(5, TimeUnit.SECONDS);
      ExecutionResult readResult = readFuture.get(5, TimeUnit.SECONDS);
      assertThat(readResult.outcome()).isEqualTo("succeeded");

      if (readCompletedBeforeSend) {
        // This branch is reached if send captured its timestamp BEFORE acquiring the lock,
        // allowing read to commit ahead of send's authorization.
        // The committed read MUST be visible to send's authorization.
        assertThat(sendResult.evaluation().classification().signals())
            .as("Read committed before send authorization must be visible in sequence signals")
            .contains(InMemoryActionSequenceStore.PII_READ_THEN_EXTERNAL_SEND);
        assertThat(sendResult.outcome()).isEqualTo("block");
        assertThat(sendResult.evaluation().decision().reasonCode())
            .isEqualTo("SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED");
        assertThat(sendDispatches).hasValue(0);
      } else {
        // Genuinely serialized send-before-read order: send acquired the lock first,
        // read executed after send. Prove that subsequent send after completed read
        // sees the PII signal and blocks.
        if (conditionalPolicy) {
          assertThat(sendResult.outcome()).isEqualTo("succeeded");
          assertThat(sendDispatches).hasValue(1);
        } else {
          assertThat(sendResult.outcome()).isEqualTo("block");
          assertThat(sendResult.evaluation().decision().reasonCode())
              .isEqualTo("EXTERNAL_SEND_NOT_AUTHORIZED");
          assertThat(sendDispatches).hasValue(0);
        }

        ExecutionResult subsequentResult = service.execute(subsequentSend);
        assertThat(subsequentResult.evaluation().classification().signals())
            .as("Subsequent send after completed read must observe PII sequence signal")
            .contains(InMemoryActionSequenceStore.PII_READ_THEN_EXTERNAL_SEND);
        assertThat(subsequentResult.outcome()).isEqualTo("block");
        assertThat(subsequentResult.evaluation().decision().reasonCode())
            .isEqualTo("SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED");
        if (conditionalPolicy) {
          assertThat(sendDispatches).hasValue(1);
        } else {
          assertThat(sendDispatches).hasValue(0);
        }
      }
    } finally {
      sendRelease.countDown();
    }
  }

  @Test
  void readCommittedBeforeSendAuthorizationMustBlock_shippedPolicy() throws Exception {
    ObjectNode policy = syntheticPolicy(false);
    AtomicInteger sends = new AtomicInteger();
    SecureExecutionService service =
        syntheticService(policy, CLOCK, sends, InMemoryActionSequenceStore.oneMinute());
    ExecutionResult readResult = service.execute(syntheticEvent(910, true));
    assertThat(readResult.outcome()).isEqualTo("succeeded");
    ExecutionResult sendResult = service.execute(syntheticEvent(911, false));
    assertThat(sendResult.evaluation().classification().signals())
        .contains(InMemoryActionSequenceStore.PII_READ_THEN_EXTERNAL_SEND);
    assertThat(sendResult.outcome()).isEqualTo("block");
    assertThat(sendResult.evaluation().decision().reasonCode())
        .isEqualTo("SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED");
    assertThat(sends).hasValue(0);
  }

  @Test
  void readCommittedBeforeSendAuthorizationMustBlock_conditionalPolicy() throws Exception {
    ObjectNode policy = syntheticPolicy(true);
    AtomicInteger sends = new AtomicInteger();
    SecureExecutionService service =
        syntheticService(policy, CLOCK, sends, InMemoryActionSequenceStore.oneMinute());
    // Control send before any read succeeds:
    ExecutionResult controlSend = service.execute(syntheticEvent(912, false));
    assertThat(controlSend.outcome()).isEqualTo("succeeded");
    assertThat(controlSend.evaluation().decision().reasonCode()).isEqualTo("BASELINE_SEND_ALLOWED");
    assertThat(sends).hasValue(1);

    // Now execute read:
    ExecutionResult readResult = service.execute(syntheticEvent(913, true));
    assertThat(readResult.outcome()).isEqualTo("succeeded");

    // After recorded read, send must be blocked by sequence rule:
    ExecutionResult blockedSend = service.execute(syntheticEvent(914, false));
    assertThat(blockedSend.evaluation().classification().signals())
        .contains(InMemoryActionSequenceStore.PII_READ_THEN_EXTERNAL_SEND);
    assertThat(blockedSend.outcome()).isEqualTo("block");
    assertThat(blockedSend.evaluation().decision().reasonCode())
        .isEqualTo("SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED");
    assertThat(sends).hasValue(1); // 0 additional send dispatches
  }

  @Test
  void stripeLockIsReleasedWhenSequenceStoreContextForFails() throws Exception {
    AtomicBoolean shouldFail = new AtomicBoolean(true);
    ActionSequenceStore faultStore =
        new ActionSequenceStore() {
          @Override
          public ClassificationContext contextFor(JsonNode event, Instant now) {
            if (shouldFail.get()) {
              throw new RuntimeException("injected contextFor failure");
            }
            return ClassificationContext.EMPTY;
          }

          @Override
          public void record(JsonNode event, Classification classification, Instant now) {}
        };

    AtomicInteger targetCalls = new AtomicInteger();
    SecureExecutionService service =
        syntheticService(
            syntheticPolicy(false),
            CLOCK,
            action -> {
              targetCalls.incrementAndGet();
              return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
            },
            faultStore);

    ObjectNode event1 = syntheticEvent(920, true);
    ObjectNode event2 = syntheticEvent(921, true);

    AtomicReference<Thread> firstThread = new AtomicReference<>();
    try (BoundedExecutor pool = boundedExecutor(2)) {
      Future<?> first =
          pool.submit(
              () -> {
                firstThread.set(Thread.currentThread());
                assertThatThrownBy(() -> service.execute(event1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SEQUENCE_STATE_UNAVAILABLE");
              });
      first.get(5, TimeUnit.SECONDS);
      assertThat(targetCalls).hasValue(0);

      // Disable fault and submit event for the SAME principal from a DIFFERENT thread:
      shouldFail.set(false);
      Future<ExecutionResult> second =
          pool.submit(
              () -> {
                assertThat(Thread.currentThread()).isNotSameAs(firstThread.get());
                return service.execute(event2);
              });
      ExecutionResult result = second.get(5, TimeUnit.SECONDS);
      assertThat(result.outcome()).isEqualTo("succeeded");
      assertThat(targetCalls).hasValue(1);
    }
  }

  @Test
  void stripeLockIsReleasedWhenSequenceStoreRecordFails() throws Exception {
    AtomicBoolean shouldFail = new AtomicBoolean(true);
    ActionSequenceStore faultStore =
        new ActionSequenceStore() {
          @Override
          public ClassificationContext contextFor(JsonNode event, Instant now) {
            return ClassificationContext.EMPTY;
          }

          @Override
          public void record(JsonNode event, Classification classification, Instant now) {
            if (shouldFail.get()) {
              throw new RuntimeException("injected record failure");
            }
          }
        };

    AtomicInteger targetCalls = new AtomicInteger();
    SecureExecutionService service =
        syntheticService(
            syntheticPolicy(false),
            CLOCK,
            action -> {
              targetCalls.incrementAndGet();
              return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
            },
            faultStore);

    ObjectNode event1 = syntheticEvent(922, true);
    ObjectNode event2 = syntheticEvent(923, true);

    AtomicReference<Thread> firstThread = new AtomicReference<>();
    try (BoundedExecutor pool = boundedExecutor(2)) {
      Future<?> first =
          pool.submit(
              () -> {
                firstThread.set(Thread.currentThread());
                assertThatThrownBy(() -> service.execute(event1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SEQUENCE_STATE_UNAVAILABLE");
              });
      first.get(5, TimeUnit.SECONDS);
      assertThat(targetCalls).hasValue(0);

      shouldFail.set(false);
      Future<ExecutionResult> second =
          pool.submit(
              () -> {
                assertThat(Thread.currentThread()).isNotSameAs(firstThread.get());
                return service.execute(event2);
              });
      ExecutionResult result = second.get(5, TimeUnit.SECONDS);
      assertThat(result.outcome()).isEqualTo("succeeded");
      assertThat(targetCalls).hasValue(1);
    }
  }

  @Test
  void stripeLockIsReleasedWhenSanitizerFails() throws Exception {
    AtomicBoolean shouldFail = new AtomicBoolean(true);
    ActionSanitizer faultSanitizer =
        (event, evaluation, now) -> {
          if (shouldFail.get()) {
            throw new RuntimeException("injected sanitizer failure");
          }
          return Optional.empty();
        };

    AtomicInteger targetCalls = new AtomicInteger();
    InMemoryEvidenceLedger evidence = evidence();
    SecureExecutionService service =
        service(
            evidence,
            action -> {
              targetCalls.incrementAndGet();
              return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
            },
            faultSanitizer,
            ActionSequenceStore.DISABLED);

    // Event requiring sanitization: limit = 500 triggers sanitize in aws-agentic-demo.json
    ObjectNode sanitizeEvent1 = event(924, 500);
    ObjectNode normalEvent2 = event(925, 20);

    AtomicReference<Thread> firstThread = new AtomicReference<>();
    try (BoundedExecutor pool = boundedExecutor(2)) {
      Future<ExecutionResult> first =
          pool.submit(
              () -> {
                firstThread.set(Thread.currentThread());
                return service.execute(sanitizeEvent1);
              });
      ExecutionResult result1 = first.get(5, TimeUnit.SECONDS);
      assertThat(result1.outcome()).isEqualTo("sanitize");
      assertThat(targetCalls).hasValue(0);

      // Submit normal event for same principal from different thread:
      shouldFail.set(false);
      Future<ExecutionResult> second =
          pool.submit(
              () -> {
                assertThat(Thread.currentThread()).isNotSameAs(firstThread.get());
                return service.execute(normalEvent2);
              });
      ExecutionResult result2 = second.get(5, TimeUnit.SECONDS);
      assertThat(result2.outcome()).isEqualTo("succeeded");
      assertThat(targetCalls).hasValue(1);
    }
  }

  @Test
  void crossToolActionsForSamePrincipalShareSameStripeLock() {
    ObjectNode read = syntheticEvent(930, true);
    ObjectNode send = syntheticEvent(931, false);

    // Read and send have different tools and target systems:
    assertThat(read.path("action").path("toolName").asText())
        .isNotEqualTo(send.path("action").path("toolName").asText());
    assertThat(read.path("action").path("target").path("system").asText())
        .isNotEqualTo(send.path("action").path("target").path("system").asText());

    // But identical organization and subject:
    assertThat(read.path("identity").path("subject"))
        .isEqualTo(send.path("identity").path("subject"));

    // Therefore both map to the exact same principal stripe lock:
    String readKey = principalKeyOf(read.path("identity").path("subject").path("id").asText());
    String sendKey = principalKeyOf(send.path("identity").path("subject").path("id").asText());
    assertThat(readKey).isEqualTo(sendKey);
    assertThat(STRIPES.lockFor(readKey)).isSameAs(STRIPES.lockFor(sendKey));
  }

  @Test
  void hashCollisionsOverSerializeSafelyWithoutSplittingPrincipal() {
    String subject1 = "user:person-0@example.com";
    String subject2 = null;
    for (int i = 1; i < 100_000; i++) {
      String candidate = "user:person-" + i + "@example.com";
      if (STRIPES.lockFor(principalKeyOf(subject1)) == STRIPES.lockFor(principalKeyOf(candidate))) {
        subject2 = candidate;
        break;
      }
    }
    assertThat(subject2).isNotNull();
    assertThat(subject1).isNotEqualTo(subject2);
    // Hash collision shares the same lock (safe over-serialization):
    assertThat(STRIPES.lockFor(principalKeyOf(subject1)))
        .isSameAs(STRIPES.lockFor(principalKeyOf(subject2)));
    // And each principal maps deterministically to exactly one lock:
    assertThat(STRIPES.lockFor(principalKeyOf(subject1)))
        .isSameAs(STRIPES.lockFor(principalKeyOf(subject1)));
    assertThat(STRIPES.lockFor(principalKeyOf(subject2)))
        .isSameAs(STRIPES.lockFor(principalKeyOf(subject2)));
  }

  private static ObjectNode syntheticPolicy(boolean allowBaselineSend) {
    ObjectNode policy =
        (ObjectNode) JsonSupport.read(ROOT.resolve("policies/synthetic-pii-exfil-demo.json"));
    if (allowBaselineSend) {
      ((ObjectNode) policy.path("rules").get(1).path("decision"))
          .put("outcome", "allow")
          .put("reasonCode", "BASELINE_SEND_ALLOWED");
    }
    PolicyValidator.validate(policy);
    return policy;
  }

  private static ObjectNode syntheticEvent(int suffix, boolean read) {
    ObjectNode event =
        (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/scheduler-aws-read.json"));
    event.put("eventId", String.format("00000000-0000-7000-8000-%012d", suffix));
    ObjectNode action = (ObjectNode) event.path("action");
    action.put("protocol", "mcp");
    action.put("toolName", read ? "demo.customer.bulk_read" : "demo.egress.send_external");
    ObjectNode arguments = JsonSupport.MAPPER.createObjectNode();
    arguments.put("datasetId", "synthetic-customers-demo");
    if (read) {
      arguments.put("limit", 20);
    } else {
      arguments.put("destination", "audit@example.invalid");
    }
    action.set("arguments", arguments);
    ObjectNode target = (ObjectNode) action.path("target");
    target.put("system", read ? "synthetic-crm" : "synthetic-egress");
    target.put("environment", "test");
    target.put("resourceType", read ? "customer-dataset" : "outbox");
    return event;
  }

  private static SecureExecutionService syntheticService(
      JsonNode policy,
      Clock clock,
      AtomicInteger sendDispatches,
      ActionSequenceStore sequenceStore) {
    return syntheticService(
        policy,
        clock,
        action -> {
          if (action.path("toolName").asText().contains("send_external")) {
            sendDispatches.incrementAndGet();
          }
          return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
        },
        sequenceStore);
  }

  private static SecureExecutionService syntheticService(
      JsonNode policy,
      Clock clock,
      SimulatedTarget target,
      ActionSequenceStore sequenceStore) {
    AtomicInteger identifiers = new AtomicInteger();
    return new SecureExecutionService(
        new PolicyEvaluator(),
        policy,
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "synthetic-test-key",
            new byte[32],
            30,
            clock,
            () -> "permit-" + identifiers.incrementAndGet(),
            new InMemoryPermitConsumptionStore()),
        new InMemoryExecutionLedger(() -> "attempt-" + identifiers.incrementAndGet()),
        new InMemoryEvidenceLedger(
            "urn:tracepermit:synthetic-concurrency-test",
            clock,
            () -> "evidence-" + identifiers.incrementAndGet()),
        target,
        () -> SecurityReadiness.allReady(NOW),
        clock,
        "urn:tracepermit:synthetic-concurrency-simulator",
        ActionSanitizer.NONE,
        sequenceStore);
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
    try (BoundedExecutor pool = boundedExecutor(threads)) {
      List<Future<Call>> futures = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        int current = index;
        futures.add(pool.submit(() -> invoke(() -> action.apply(current))));
      }
      for (Future<Call> future : futures) calls.add(future.get(60, TimeUnit.SECONDS));
    }
    return calls;
  }

  private static BoundedExecutor boundedExecutor(int threads) {
    return new BoundedExecutor(
        Executors.newFixedThreadPool(
            threads, Thread.ofPlatform().daemon().name("tracepermit-concurrency-test-", 0).factory()));
  }

  /** A broken, uninterruptible stripe must fail the test, not hang ExecutorService.close(). */
  private record BoundedExecutor(ExecutorService delegate) implements AutoCloseable {
    <T> Future<T> submit(Callable<T> task) {
      return delegate.submit(task);
    }

    Future<?> submit(Runnable task) {
      return delegate.submit(task);
    }

    @Override
    public void close() {
      delegate.shutdownNow();
      try {
        assertThat(delegate.awaitTermination(2, TimeUnit.SECONDS))
            .as("Owned concurrency workers must terminate within the cleanup deadline")
            .isTrue();
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while stopping owned concurrency workers", failure);
      }
    }
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
