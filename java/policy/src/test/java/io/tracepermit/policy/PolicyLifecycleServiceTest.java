package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PolicyLifecycleServiceTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-10-16T10:05:00Z");
  private static final Instant T2 = Instant.parse("2026-10-16T10:10:00Z");
  private static final Instant T3 = Instant.parse("2026-10-16T10:15:00Z");

  private final PolicyLifecycleService service = new PolicyLifecycleService();

  @Test
  void stagingValidPolicyCreatesStagedRecord() {
    JsonNode policy = validPolicy(1);
    PolicyVersionRecord record = service.stage(policy, T0);

    assertThat(record.policyId()).isEqualTo("pol-aws-web-001");
    assertThat(record.version()).isOne();
    assertThat(record.state()).isEqualTo(PolicyState.STAGED);
    assertThat(record.isStaged()).isTrue();
    assertThat(record.isActive()).isFalse();
    assertThat(record.stagedAt()).isEqualTo(T0);
    assertThat(record.activatedAt()).isNull();
    assertThat(record.contentHash()).isNotBlank();
    assertThat(service.listPolicyIds()).contains("pol-aws-web-001");

    // Idempotent re-staging of exact same document
    PolicyVersionRecord restaged = service.stage(policy, T1);
    assertThat(restaged.contentHash()).isEqualTo(record.contentHash());
  }

  @Test
  void stagingDifferentContentWithSameVersionFailsClosed() {
    JsonNode v1 = validPolicy(1);
    service.stage(v1, T0);

    ObjectNode mutated = (ObjectNode) v1.deepCopy();
    mutated.put("name", "Different Policy Name");
    assertThatThrownBy(() -> service.stage(mutated, T1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("POLICY_VERSION_IMMUTABLE");
  }

  @Test
  void activationTransitionsStagedToActiveAndDeprecatesPreviousVersion() {
    JsonNode v1 = validPolicy(1);
    JsonNode v2 = validPolicy(2);
    service.stage(v1, T0);
    service.stage(v2, T1);

    assertThat(service.activeVersion("pol-aws-web-001")).isEmpty();

    PolicyVersionRecord activeV1 = service.activate("pol-aws-web-001", 1, T1);
    assertThat(activeV1.isActive()).isTrue();
    assertThat(activeV1.activatedAt()).isEqualTo(T1);
    assertThat(service.activeVersion("pol-aws-web-001")).contains(activeV1);

    // Idempotent activate on same active version
    PolicyVersionRecord idempotentV1 = service.activate("pol-aws-web-001", 1, T1);
    assertThat(idempotentV1.version()).isOne();

    // Activate v2 -> v1 should become DEPRECATED, v2 becomes ACTIVE
    PolicyVersionRecord activeV2 = service.activate("pol-aws-web-001", 2, T2);
    assertThat(activeV2.version()).isEqualTo(2);
    assertThat(activeV2.isActive()).isTrue();
    assertThat(activeV2.activatedAt()).isEqualTo(T2);

    PolicyVersionRecord deprecatedV1 = service.getVersion("pol-aws-web-001", 1).orElseThrow();
    assertThat(deprecatedV1.state()).isEqualTo(PolicyState.DEPRECATED);
    assertThat(deprecatedV1.deprecatedAt()).isEqualTo(T2);
  }

  @Test
  void activationFailsForUnknownPolicyOrVersion() {
    assertThatThrownBy(() -> service.activate("pol-nonexistent", 1, T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("POLICY_NOT_FOUND");

    service.stage(validPolicy(1), T0);
    assertThatThrownBy(() -> service.activate("pol-aws-web-001", 99, T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("POLICY_VERSION_NOT_FOUND");
  }

  @Test
  void rollbackRestoresPrecedingActiveVersionAndMaintainsAuditTrail() {
    service.stage(validPolicy(1), T0);
    service.stage(validPolicy(2), T1);
    service.activate("pol-aws-web-001", 1, T1);
    service.activate("pol-aws-web-001", 2, T2);

    RollbackRecord rollback = service.rollback(
        "pol-aws-web-001", T3, "Discovered regression in v2 rule priority");

    assertThat(rollback.policyId()).isEqualTo("pol-aws-web-001");
    assertThat(rollback.fromVersion()).isEqualTo(2);
    assertThat(rollback.toVersion()).isOne();
    assertThat(rollback.rolledBackAt()).isEqualTo(T3);
    assertThat(rollback.reason()).contains("regression");

    // Current active version is now v1
    PolicyVersionRecord active = service.activeVersion("pol-aws-web-001").orElseThrow();
    assertThat(active.version()).isOne();
    assertThat(active.isActive()).isTrue();

    // Version 2 is marked ROLLED_BACK
    PolicyVersionRecord rolledBackV2 = service.getVersion("pol-aws-web-001", 2).orElseThrow();
    assertThat(rolledBackV2.state()).isEqualTo(PolicyState.ROLLED_BACK);
    assertThat(rolledBackV2.isRolledBack()).isTrue();
    assertThat(rolledBackV2.rollbackTargetVersion()).isEqualTo(1);
    assertThat(rolledBackV2.rolledBackAt()).isEqualTo(T3);

    // Rollback audit history is preserved
    List<RollbackRecord> history = service.rollbackHistory("pol-aws-web-001");
    assertThat(history).containsExactly(rollback);
  }

  @Test
  void rollbackFailsIfNoActiveVersionOrNoPreviousVersion() {
    service.stage(validPolicy(1), T0);

    // No active version at all
    assertThatThrownBy(() -> service.rollback("pol-aws-web-001", T1, "reason"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NO_ACTIVE_POLICY");

    // Activate v1 only (no preceding version to roll back to)
    service.activate("pol-aws-web-001", 1, T1);
    assertThatThrownBy(() -> service.rollback("pol-aws-web-001", T2, "reason"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NO_PREVIOUS_ACTIVE_VERSION");

    // Non-existent policy
    assertThatThrownBy(() -> service.rollback("pol-missing", T2, "reason"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("POLICY_NOT_FOUND");
  }

  @Test
  void dryRunSimulationProducesEvaluationWithoutSideEffects() {
    JsonNode v1 = validPolicy(1);
    JsonNode action = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));

    PolicySimulationResult result = service.simulate(v1, action, T0);
    assertThat(result.policyId()).isEqualTo("pol-aws-web-001");
    assertThat(result.version()).isOne();
    assertThat(result.outcome()).isEqualTo("allow");
    assertThat(result.matchedRuleId()).isEqualTo("allow-cloudwatch-read");
    assertThat(result.reasonCode()).isEqualTo("BOUNDED_LOG_READ");
    assertThat(result.dryRun()).isTrue();
    assertThat(result.simulatedAt()).isEqualTo(T0);

    // Simulation did not stage or activate the policy
    assertThat(service.activeVersion("pol-aws-web-001")).isEmpty();
    assertThat(service.getVersion("pol-aws-web-001", 1)).isEmpty();

    // Stage v1, simulate by (policyId, version)
    service.stage(v1, T0);
    PolicySimulationResult stagedSimResult = service.simulate("pol-aws-web-001", 1, action, T1);
    assertThat(stagedSimResult.outcome()).isEqualTo("allow");

    // Missing version simulation throws
    assertThatThrownBy(() -> service.simulate("pol-aws-web-001", 99, action, T1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("POLICY_VERSION_NOT_FOUND");
  }

  @Test
  void evaluateActiveDynamicallyTracksActivationsAndRollbacks() {
    JsonNode v1 = validPolicy(1);
    JsonNode v2 = validPolicy(2);
    // Mutate v2 to default to block instead of require_approval
    ((ObjectNode) v2.path("defaultDecision")).put("outcome", "block");
    ((ObjectNode) v2.path("defaultDecision")).put("reasonCode", "STRICT_DEFAULT_BLOCK");
    // Remove the allow rule from v2
    ArrayNode rules = (ArrayNode) v2.path("rules");
    rules.remove(2);

    service.stage(v1, T0);
    service.stage(v2, T1);

    JsonNode readAction = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));

    // Before activation -> throws
    assertThatThrownBy(() -> service.evaluateActive("pol-aws-web-001", readAction, ClassificationContext.EMPTY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NO_ACTIVE_POLICY");

    // Activate v1 -> allowed by allow rule
    service.activate("pol-aws-web-001", 1, T1);
    PolicyEvaluation evalV1 = service.evaluateActive("pol-aws-web-001", readAction, ClassificationContext.EMPTY);
    assertThat(evalV1.decision().outcome()).isEqualTo("allow");
    assertThat(evalV1.decision().policyVersion()).isOne();

    // Activate v2 -> blocked by default decision
    service.activate("pol-aws-web-001", 2, T2);
    PolicyEvaluation evalV2 = service.evaluateActive("pol-aws-web-001", readAction, ClassificationContext.EMPTY);
    assertThat(evalV2.decision().outcome()).isEqualTo("block");
    assertThat(evalV2.decision().policyVersion()).isEqualTo(2);

    // Rollback to v1 -> allowed again!
    service.rollback("pol-aws-web-001", T3, "Revert v2 block behavior");
    PolicyEvaluation evalRolledBack = service.evaluateActive("pol-aws-web-001", readAction, ClassificationContext.EMPTY);
    assertThat(evalRolledBack.decision().outcome()).isEqualTo("allow");
    assertThat(evalRolledBack.decision().policyVersion()).isOne();
  }

  @Test
  void concurrentEvaluationsDuringActivationAndRollbackRemainSafe() throws Exception {
    service.stage(validPolicy(1), T0);
    service.stage(validPolicy(2), T1);
    service.activate("pol-aws-web-001", 1, T1);

    JsonNode readAction = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    int readerCount = 4;
    ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicInteger evaluations = new AtomicInteger();

    for (int i = 0; i < readerCount; i++) {
      pool.submit(() -> {
        try {
          startLatch.await();
          while (running.get()) {
            PolicyEvaluation eval = service.evaluateActive("pol-aws-web-001", readAction, ClassificationContext.EMPTY);
            assertThat(eval.decision().outcome()).isNotNull();
            evaluations.incrementAndGet();
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    }

    pool.submit(() -> {
      try {
        startLatch.await();
        for (int i = 0; i < 20; i++) {
          service.activate("pol-aws-web-001", 2, Instant.now());
          service.rollback("pol-aws-web-001", Instant.now(), "iteration-" + i);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      } finally {
        running.set(false);
      }
    });

    startLatch.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    assertThat(evaluations.get()).isPositive();
  }

  @Test
  void listVersionsReturnsVersionsOrderedDescending() {
    service.stage(validPolicy(1), T0);
    service.stage(validPolicy(3), T1);
    service.stage(validPolicy(2), T2);

    List<PolicyVersionRecord> list = service.listVersions("pol-aws-web-001");
    assertThat(list).extracting(PolicyVersionRecord::version)
        .containsExactly(3, 2, 1);

    assertThat(service.listVersions("pol-unknown")).isEmpty();
    assertThat(service.rollbackHistory("pol-unknown")).isEmpty();
  }

  @Test
  void policyStateSerialization() {
    assertThat(PolicyState.STAGED.serialized()).isEqualTo("staged");
    assertThat(PolicyState.ACTIVE.serialized()).isEqualTo("active");
    assertThat(PolicyState.ROLLED_BACK.serialized()).isEqualTo("rolled_back");
    assertThat(PolicyState.DEPRECATED.serialized()).isEqualTo("deprecated");
  }

  private static JsonNode validPolicy(int version) {
    ObjectNode policy = (ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    policy.put("version", version);
    return policy;
  }
}
