package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.application.SimulatedTarget;
import io.tracepermit.application.TargetExecutionException;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.ExecutionPermitException;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import io.tracepermit.simulator.StatefulTargetSimulator;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.tracepermit.executor.simulator.ExecutorTestFixtures.AUDIENCE;
import static io.tracepermit.executor.simulator.ExecutorTestFixtures.ISSUER;
import static io.tracepermit.executor.simulator.ExecutorTestFixtures.KEY_ID;
import static io.tracepermit.executor.simulator.ExecutorTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifierOnlySimulatorExecutorTest {
  @Test
  void verifiesConsumesDispatchesAndReturnsOnlyBoundedReceipt() {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    JsonNode event = ExecutorTestFixtures.action();
    String permit = issue(keys, event);
    AtomicInteger calls = new AtomicInteger();
    SimulatedTarget target = action -> {
      calls.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode()
          .put("credential", "must-not-escape")
          .put("rawTargetValue", "sensitive-result");
    };
    var executor = executor(keys, NOW, new InMemoryPermitConsumptionStore(), target);

    JsonNode receipt = executor.execute(permit, event);

    assertThat(calls).hasValue(1);
    assertThat(fieldNames(receipt)).containsExactlyInAnyOrder(
        "schemaVersion", "status", "mode", "eventId", "permitId", "system", "operation",
        "resultHash");
    assertThat(receipt.path("status").asText()).isEqualTo("succeeded");
    assertThat(receipt.path("mode").asText()).isEqualTo("simulated");
    assertThat(receipt.path("resultHash").asText()).matches("sha256:[a-f0-9]{64}");
    assertThat(receipt.toString()).doesNotContain("credential", "must-not-escape", "sensitive-result",
        "groupId", "cidr");
  }

  @Test
  void rejectsAnyAuthorityThatCanSign() {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    var signer = ExecutorTestFixtures.signer(keys, ExecutorTestFixtures.clock(NOW), new AtomicInteger());

    assertThatThrownBy(() -> new VerifierOnlySimulatorExecutor(
        signer, AUDIENCE, List.of(ExecutorTestFixtures.route()), action -> action))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("EXECUTOR_REQUIRES_VERIFIER_ONLY_AUTHORITY");
  }

  @Test
  void rejectsMissingAudienceAndEmptyAllowlist() {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    ExecutionPermitAuthority verifier = ExecutorTestFixtures.verifier(
        keys, ExecutorTestFixtures.clock(NOW), new InMemoryPermitConsumptionStore());

    assertThatThrownBy(() -> new VerifierOnlySimulatorExecutor(
        verifier, " ", List.of(ExecutorTestFixtures.route()), action -> action))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VerifierOnlySimulatorExecutor(
        verifier, AUDIENCE, List.of(), action -> action))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("EXECUTOR_REQUIRES_ALLOWLIST");
  }

  @Test
  void rejectsNonCanonicalAndDisallowedActionsBeforeTargetOrConsumption() {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    JsonNode event = ExecutorTestFixtures.action();
    String permit = issue(keys, event);
    AtomicInteger calls = new AtomicInteger();
    InMemoryPermitConsumptionStore store = new InMemoryPermitConsumptionStore();
    var executor = executor(keys, NOW, store, action -> {
      calls.incrementAndGet();
      return action;
    });

    JsonNode injected = event.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) injected).put("verificationKey", "request-key");
    assertThatThrownBy(() -> executor.execute(permit, injected))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("NON_CANONICAL_EXECUTOR_ACTION");

    JsonNode disallowed = event.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) disallowed.path("action").path("target"))
        .put("accountId", "simulated-other");
    String disallowedPermit = issue(keys, disallowed);
    assertThatThrownBy(() -> executor.execute(disallowedPermit, disallowed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("EXECUTOR_ROUTE_NOT_ALLOWED");
    assertThat(calls).hasValue(0);

    assertThat(executor.execute(permit, event).path("status").asText()).isEqualTo("succeeded");
    assertThat(calls).hasValue(1);
  }

  @Test
  void rejectsActionMutationWrongAudienceIssuerKeyAndAlgorithm() {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    JsonNode event = ExecutorTestFixtures.action();
    String permit = issue(keys, event);

    JsonNode mutated = event.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) mutated.path("action").path("arguments"))
        .put("port", 8443);
    assertRejected(executor(keys, NOW, new InMemoryPermitConsumptionStore(), action -> action),
        permit, mutated);

    var wrongAudience = new VerifierOnlySimulatorExecutor(
        ExecutorTestFixtures.verifier(keys, ExecutorTestFixtures.clock(NOW),
            new InMemoryPermitConsumptionStore()),
        "tracepermit://another-executor", List.of(ExecutorTestFixtures.route()), action -> action);
    assertRejected(wrongAudience, permit, event);

    var wrongIssuerAuthority = new ExecutionPermitAuthority(
        "https://another-issuer.invalid", KEY_ID, keys.getPublic(), 60,
        ExecutorTestFixtures.clock(NOW), new InMemoryPermitConsumptionStore());
    assertRejected(new VerifierOnlySimulatorExecutor(
        wrongIssuerAuthority, AUDIENCE, List.of(ExecutorTestFixtures.route()), action -> action),
        permit, event);

    KeyPair otherKeys = ExecutorTestFixtures.keyPair();
    assertRejected(executor(otherKeys, NOW, new InMemoryPermitConsumptionStore(), action -> action),
        permit, event);

    var wrongKeyIdAuthority = new ExecutionPermitAuthority(
        ISSUER, "another-key", keys.getPublic(), 60, ExecutorTestFixtures.clock(NOW),
        new InMemoryPermitConsumptionStore());
    assertRejected(new VerifierOnlySimulatorExecutor(
        wrongKeyIdAuthority, AUDIENCE, List.of(ExecutorTestFixtures.route()), action -> action),
        permit, event);

    String[] segments = permit.split("\\.");
    JsonNode header;
    try {
      header = JsonSupport.MAPPER.readTree(java.util.Base64.getUrlDecoder().decode(segments[0]));
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
    ((com.fasterxml.jackson.databind.node.ObjectNode) header).put("alg", "HS256");
    String algorithmInjected = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(header.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
        + "." + segments[1] + "." + segments[2];
    assertRejected(executor(keys, NOW, new InMemoryPermitConsumptionStore(), action -> action),
        algorithmInjected, event);
  }

  @Test
  void rejectsExpiredAndReplayedPermits() {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    JsonNode event = ExecutorTestFixtures.action();
    String permit = issue(keys, event);
    var expired = executor(keys, NOW.plusSeconds(61), new InMemoryPermitConsumptionStore(), action -> action);
    assertRejected(expired, permit, event);

    AtomicInteger calls = new AtomicInteger();
    var executor = executor(keys, NOW, new InMemoryPermitConsumptionStore(), action -> {
      calls.incrementAndGet();
      return action;
    });
    executor.execute(permit, event);
    assertRejected(executor, permit, event);
    assertThat(calls).hasValue(1);
  }

  @Test
  void concurrentClaimAllowsExactlyOneTargetInvocation() throws Exception {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    JsonNode event = ExecutorTestFixtures.action();
    String permit = issue(keys, event);
    InMemoryPermitConsumptionStore shared = new InMemoryPermitConsumptionStore();
    AtomicInteger calls = new AtomicInteger();
    var first = executor(keys, NOW, shared, action -> {
      calls.incrementAndGet();
      return action;
    });
    var second = executor(keys, NOW, shared, action -> {
      calls.incrementAndGet();
      return action;
    });

    try (var pool = Executors.newFixedThreadPool(16)) {
      List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, 32)
          .<Callable<Boolean>>mapToObj(index -> () -> {
            try {
              (index % 2 == 0 ? first : second).execute(permit, event);
              return true;
            } catch (ExecutionPermitException rejected) {
              return false;
            }
          }).toList();
      long successes = pool.invokeAll(attempts).stream().filter(future -> {
        try {
          return future.get();
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      }).count();
      assertThat(successes).isOne();
    }
    assertThat(calls).hasValue(1);
  }

  @Test
  void targetRejectionOccursOnlyAfterAuthorityIsConsumed() {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    JsonNode event = ExecutorTestFixtures.action();
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("action").path("arguments"))
        .put("cidr", "10.0.0.0/8");
    String permit = issue(keys, event);
    var executor = executor(keys, NOW, new InMemoryPermitConsumptionStore(),
        new StatefulTargetSimulator());

    assertThatThrownBy(() -> executor.execute(permit, event))
        .isInstanceOf(TargetExecutionException.class);
    assertRejected(executor, permit, event);
  }

  private static VerifierOnlySimulatorExecutor executor(
      KeyPair keys, java.time.Instant now, InMemoryPermitConsumptionStore store, SimulatedTarget target) {
    return new VerifierOnlySimulatorExecutor(
        ExecutorTestFixtures.verifier(keys, ExecutorTestFixtures.clock(now), store),
        AUDIENCE,
        List.of(ExecutorTestFixtures.route()),
        target);
  }

  private static String issue(KeyPair keys, JsonNode event) {
    return ExecutorTestFixtures.signer(keys, ExecutorTestFixtures.clock(NOW), new AtomicInteger())
        .issue(event, ExecutorTestFixtures.evaluation(event), AUDIENCE);
  }

  private static void assertRejected(
      VerifierOnlySimulatorExecutor executor, String permit, JsonNode event) {
    assertThatThrownBy(() -> executor.execute(permit, event))
        .isInstanceOfAny(ExecutionPermitException.class, IllegalArgumentException.class);
  }

  private static Set<String> fieldNames(JsonNode value) {
    Set<String> names = new java.util.HashSet<>();
    value.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
