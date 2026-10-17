package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ShortNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent exact-value, scope and composition regressions; all data are synthetic. */
final class LogWindowClassificationReviewTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
  private final PolicyEvaluator evaluator = new PolicyEvaluator();

  @Test
  void signedWrapFamiliesKeepExactMathematicalMeaningInConstructedAndParsedNodes() throws Exception {
    for (int bits : List.of(32, 64, 128, 256)) {
      for (int sign : List.of(-1, 1)) {
        for (int offset : List.of(-101, -1, 0, 50, 100, 101, 102)) {
          BigInteger value = BigInteger.ONE.shiftLeft(bits).multiply(BigInteger.valueOf(sign))
              .add(BigInteger.valueOf(offset));
          for (JsonNode node : List.of(new BigIntegerNode(value),
              JsonSupport.MAPPER.readTree(value.toString()))) {
            Classification actual = evaluator.classify(action(node));
            boolean oversized = value.compareTo(BigInteger.valueOf(100)) > 0;
            assertThat(actual.score()).as("value=%s", value).isEqualTo(oversized ? 60 : 10);
            assertThat(actual.severity()).isEqualTo(oversized ? "high" : "low");
            assertThat(actual.actionClass()).isEqualTo("read");
            assertThat(actual.operation()).isEqualTo("aws.logs.filter_log_events");
            assertThat(actual.classifierKind()).isEqualTo("deterministic");
            assertThat(actual.classifierVersion()).isEqualTo("0.1.0");
            assertThat(actual.signals()).containsExactlyElementsOf(oversized
                ? List.of("READ_VERB", "OVERSIZED_RESULT_WINDOW") : List.of("READ_VERB"));
            assertThat(actual.semanticIntents()).containsExactlyElementsOf(oversized
                ? List.of("POTENTIAL_DATA_EXFILTRATION") : List.of());
          }
        }
      }
    }
  }

  @Test
  void shortIntegralNodesUseTheSameThresholdWithoutCoercingOtherOperations() {
    assertThat(evaluator.classify(action(ShortNode.valueOf((short) 100))).score()).isEqualTo(10);
    assertThat(evaluator.classify(action(ShortNode.valueOf((short) 101))).score()).isEqualTo(60);
    for (String tool : List.of("aws.logs.describe_log_groups", "aws.ec2.update_security_group",
        "custom.unknown", "AWS.LOGS.FILTER_LOG_EVENTS")) {
      ObjectNode event = action(new BigIntegerNode(BigInteger.ONE.shiftLeft(128)));
      ((ObjectNode) event.path("action")).put("toolName", tool);
      assertThat(evaluator.classify(event).signals()).doesNotContain("OVERSIZED_RESULT_WINDOW");
    }
  }

  @Test
  void contextSignalDeduplicationAndScoreAdjustmentRemainExact() {
    ObjectNode event = action(new BigIntegerNode(BigInteger.ONE.shiftLeft(128)));
    var context = new ClassificationContext(
        List.of("READ_VERB", "OVERSIZED_RESULT_WINDOW", "SERVER_CONTEXT"), 7);
    var classification = evaluator.classify(event, context);
    assertThat(classification.signals())
        .containsExactly("READ_VERB", "OVERSIZED_RESULT_WINDOW", "SERVER_CONTEXT");
    assertThat(classification.semanticIntents()).containsExactly("POTENTIAL_DATA_EXFILTRATION");
    assertThat(classification.score()).isEqualTo(67);
    assertThat(classification.severity()).isEqualTo("high");
    assertThat(evaluator.classify(event, new ClassificationContext(List.of(), 100)).score()).isEqualTo(100);
  }

  @Test
  void bothCandidateSimulationsPreserveInputsAndCannotTouchCatalogForWideSignedValues() throws Exception {
    PolicyLifecycleStore forbidden = (PolicyLifecycleStore) Proxy.newProxyInstance(
        PolicyLifecycleStore.class.getClassLoader(), new Class<?>[] {PolicyLifecycleStore.class},
        (proxy, method, args) -> { throw new AssertionError("simulation called catalog: " + method.getName()); });
    var service = new PolicyLifecycleService(forbidden);
    for (String path : List.of("policies/templates/simulator-observer.json",
        "policies/templates/simulator-reviewed-change.json")) {
      JsonNode policy = JsonSupport.read(ROOT.resolve(path));
      JsonNode policyBefore = policy.deepCopy();
      for (BigInteger value : List.of(BigInteger.valueOf(100), BigInteger.valueOf(101),
          BigInteger.ONE.shiftLeft(32).add(BigInteger.valueOf(50)),
          BigInteger.ONE.shiftLeft(128), BigInteger.ONE.shiftLeft(32).negate().add(BigInteger.valueOf(101)))) {
        ObjectNode event = action(JsonSupport.MAPPER.readTree(value.toString()));
        JsonNode before = event.deepCopy();
        var result = service.simulate(policy, event, NOW);
        boolean oversized = value.compareTo(BigInteger.valueOf(100)) > 0;
        assertThat(result.dryRun()).isTrue();
        assertThat(result.policyId()).isEqualTo(policy.path("id").asText());
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(oversized ? "block" : "allow");
        assertThat(result.reasonCode()).isEqualTo(oversized
            ? "OVERSIZED_LOG_WINDOW_BLOCKED" : "CLI_STAGING_LOG_READ_ALLOWED");
        assertThat(result.matchedRuleId()).isEqualTo(oversized
            ? "block-oversized-log-window" : "allow-cli-staging-cloudwatch-read");
        assertThat(result.approvalGroup()).isNull();
        assertThat(result.simulatedAt()).isEqualTo(NOW);
        assertThat(result.evaluation().classification().score()).isEqualTo(oversized ? 60 : 10);
        assertThat(policy).isEqualTo(policyBefore);
        assertThat(event).isEqualTo(before);
      }
    }
  }

  @Test
  void wideNegativePreviouslyWrappedPositiveIsNotAnOversizedPositiveRequest() {
    BigInteger negative = BigInteger.ONE.shiftLeft(32).negate().add(BigInteger.valueOf(101));
    assertThat(negative.signum()).isNegative();
    assertThat(negative.intValue()).isEqualTo(101);
    Classification classification = evaluator.classify(action(new BigIntegerNode(negative)));
    assertThat(classification.signals()).doesNotContain("OVERSIZED_RESULT_WINDOW");
    assertThat(classification.score()).isEqualTo(10);
    // This corrects an old false positive, not a promise that negative limits
    // are valid tool arguments. Runtime argument validation stays separate.
  }

  private static ObjectNode action(JsonNode limit) {
    ObjectNode event = (ObjectNode) JsonSupport.read(
        ROOT.resolve("fixtures/policy-templates/cli-human-staging-log-read.json"));
    ((ObjectNode) event.path("action").path("arguments")).set("limit", limit);
    return event;
  }
}
