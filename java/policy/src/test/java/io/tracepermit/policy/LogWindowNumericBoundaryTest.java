package io.tracepermit.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

final class LogWindowNumericBoundaryTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant T0 = Instant.parse("2026-10-16T12:00:00Z");

  private static final String OBSERVER_PATH = "policies/templates/simulator-observer.json";
  private static final String REVIEWED_CHANGE_PATH = "policies/templates/simulator-reviewed-change.json";
  private static final String BASE_FIXTURE_PATH = "fixtures/policy-templates/cli-human-staging-log-read.json";

  private final PolicyEvaluator evaluator = new PolicyEvaluator();
  private final PolicyLifecycleService service = new PolicyLifecycleService();

  static final class LimitCase {
    final String label;
    final JsonNode limitNode;
    final boolean expectedOversized;

    LimitCase(String label, JsonNode limitNode, boolean expectedOversized) {
      this.label = label;
      this.limitNode = limitNode;
      this.expectedOversized = expectedOversized;
    }

    @Override
    public String toString() {
      return label + " (oversized=" + expectedOversized + ")";
    }
  }

  static Stream<LimitCase> integralBoundaryCases() {
    BigInteger twoTo32Plus50 = BigInteger.valueOf(1L << 32).add(BigInteger.valueOf(50)); // 4294967346 (lower 32-bits = 50)
    BigInteger longMaxPlusOne = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE); // 9223372036854775808
    BigInteger hugeBigInt = new BigInteger("1000000000000000000000000000000"); // 10^30
    // BigInteger whose lower 32-bits wrap to 48 (<= 100)
    BigInteger bigIntWrapTo48 = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).add(BigInteger.valueOf(50));

    return Stream.of(
        // Controls <= 100 (constructed nodes)
        new LimitCase("negative: -100", IntNode.valueOf(-100), false),
        new LimitCase("negative: -1", IntNode.valueOf(-1), false),
        new LimitCase("zero: 0", IntNode.valueOf(0), false),
        new LimitCase("positive below threshold: 99", IntNode.valueOf(99), false),
        new LimitCase("exact threshold boundary: 100", IntNode.valueOf(100), false),

        // Oversized positive cases > 100 (constructed nodes)
        new LimitCase("first integer above threshold: 101", IntNode.valueOf(101), true),
        new LimitCase("Integer.MAX_VALUE: 2147483647", IntNode.valueOf(Integer.MAX_VALUE), true),
        new LimitCase("Integer.MAX_VALUE + 1: 2147483648 (wraps to negative in int)",
            LongNode.valueOf((long) Integer.MAX_VALUE + 1), true),
        new LimitCase("2^32 + 50: 4294967346 (wraps to 50 in 32-bit int)",
            new BigIntegerNode(twoTo32Plus50), true),
        new LimitCase("Long.MAX_VALUE: 9223372036854775807 (wraps to -1 in int)",
            LongNode.valueOf(Long.MAX_VALUE), true),
        new LimitCase("Long.MAX_VALUE + 1: 9223372036854775808",
            new BigIntegerNode(longMaxPlusOne), true),
        new LimitCase("BigInteger wrap-to-small: 2*Long.MAX + 50",
            new BigIntegerNode(bigIntWrapTo48), true),
        new LimitCase("huge BigInteger: 10^30",
            new BigIntegerNode(hugeBigInt), true),

        // Parsed JSON representation cases
        new LimitCase("parsed JSON: 99", parseJsonNumber("99"), false),
        new LimitCase("parsed JSON: 100", parseJsonNumber("100"), false),
        new LimitCase("parsed JSON: 101", parseJsonNumber("101"), true),
        new LimitCase("parsed JSON: 2147483648", parseJsonNumber("2147483648"), true),
        new LimitCase("parsed JSON: 4294967346", parseJsonNumber("4294967346"), true),
        new LimitCase("parsed JSON: 9223372036854775807", parseJsonNumber("9223372036854775807"), true),
        new LimitCase("parsed JSON: 9223372036854775808", parseJsonNumber("9223372036854775808"), true),
        new LimitCase("parsed JSON wrap-to-small: " + bigIntWrapTo48,
            parseJsonNumber(bigIntWrapTo48.toString()), true),
        new LimitCase("parsed JSON: 1000000000000000000000000000000",
            parseJsonNumber("1000000000000000000000000000000"), true)
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("integralBoundaryCases")
  void classifierCorrectlyIdentifiesOversizedResultWindowWithoutNumericNarrowing(LimitCase testCase) {
    ObjectNode action = createActionWithLimit(testCase.limitNode);
    Classification classification = evaluator.classify(action);

    if (testCase.expectedOversized) {
      assertThat(classification.signals())
          .as("Case %s must contain OVERSIZED_RESULT_WINDOW", testCase.label)
          .contains("OVERSIZED_RESULT_WINDOW");
      assertThat(classification.semanticIntents())
          .as("Case %s must contain POTENTIAL_DATA_EXFILTRATION", testCase.label)
          .contains("POTENTIAL_DATA_EXFILTRATION");
      assertThat(classification.severity())
          .as("Case %s must have high severity (score 60)", testCase.label)
          .isEqualTo("high");
      assertThat(classification.score())
          .as("Case %s score must be at least 60", testCase.label)
          .isGreaterThanOrEqualTo(60);
    } else {
      assertThat(classification.signals())
          .as("Case %s must NOT contain OVERSIZED_RESULT_WINDOW", testCase.label)
          .doesNotContain("OVERSIZED_RESULT_WINDOW");
      assertThat(classification.semanticIntents())
          .as("Case %s must NOT contain POTENTIAL_DATA_EXFILTRATION", testCase.label)
          .doesNotContain("POTENTIAL_DATA_EXFILTRATION");
      assertThat(classification.severity())
          .as("Case %s below threshold must have low severity", testCase.label)
          .isEqualTo("low");
      assertThat(classification.score())
          .as("Case %s score must remain 10", testCase.label)
          .isEqualTo(10);
    }
  }

  @Test
  void simulationWithBothCandidateTemplatesBlocksOversizedNarrowingVectors() {
    JsonNode observer = JsonSupport.read(ROOT.resolve(OBSERVER_PATH));
    JsonNode reviewedChange = JsonSupport.read(ROOT.resolve(REVIEWED_CHANGE_PATH));

    // Vector 1: Integer.MAX_VALUE + 1 (2147483648)
    ObjectNode eventMaxPlusOne = createActionWithLimit(LongNode.valueOf((long) Integer.MAX_VALUE + 1));
    assertOversizedSimulationBlocks(observer, eventMaxPlusOne, "tpl-simulator-observer-001");
    assertOversizedSimulationBlocks(reviewedChange, eventMaxPlusOne, "tpl-simulator-reviewed-change-001");

    // Vector 2: 2^32 + 50 (4294967346, wraps to 50 in 32-bit int)
    ObjectNode eventWrapTo50 = createActionWithLimit(
        new BigIntegerNode(BigInteger.valueOf(1L << 32).add(BigInteger.valueOf(50))));
    assertOversizedSimulationBlocks(observer, eventWrapTo50, "tpl-simulator-observer-001");
    assertOversizedSimulationBlocks(reviewedChange, eventWrapTo50, "tpl-simulator-reviewed-change-001");

    // Vector 3: Long.MAX_VALUE (9223372036854775807)
    ObjectNode eventLongMax = createActionWithLimit(LongNode.valueOf(Long.MAX_VALUE));
    assertOversizedSimulationBlocks(observer, eventLongMax, "tpl-simulator-observer-001");
    assertOversizedSimulationBlocks(reviewedChange, eventLongMax, "tpl-simulator-reviewed-change-001");

    // Vector 4: Long.MAX_VALUE + 1 (9223372036854775808)
    ObjectNode eventLongMaxPlusOne = createActionWithLimit(
        new BigIntegerNode(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
    assertOversizedSimulationBlocks(observer, eventLongMaxPlusOne, "tpl-simulator-observer-001");
    assertOversizedSimulationBlocks(reviewedChange, eventLongMaxPlusOne, "tpl-simulator-reviewed-change-001");

    // Vector 5: BigInteger whose lower 32-bits wrap to 48 (<= 100)
    BigInteger bigIntWrapTo48 = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).add(BigInteger.valueOf(50));
    ObjectNode eventWrapTo48 = createActionWithLimit(new BigIntegerNode(bigIntWrapTo48));
    assertOversizedSimulationBlocks(observer, eventWrapTo48, "tpl-simulator-observer-001");
    assertOversizedSimulationBlocks(reviewedChange, eventWrapTo48, "tpl-simulator-reviewed-change-001");

    // Vector 6: 10^30 BigInteger
    ObjectNode eventHuge = createActionWithLimit(new BigIntegerNode(new BigInteger("1000000000000000000000000000000")));
    assertOversizedSimulationBlocks(observer, eventHuge, "tpl-simulator-observer-001");
    assertOversizedSimulationBlocks(reviewedChange, eventHuge, "tpl-simulator-reviewed-change-001");
  }

  @Test
  void simulationWithBothCandidateTemplatesAllowsBoundedRequestsAtAndBelowThreshold() {
    JsonNode observer = JsonSupport.read(ROOT.resolve(OBSERVER_PATH));
    JsonNode reviewedChange = JsonSupport.read(ROOT.resolve(REVIEWED_CHANGE_PATH));

    // Vector A: limit = 100 (exact threshold boundary, allowed)
    ObjectNode event100 = createActionWithLimit(IntNode.valueOf(100));
    assertBoundedSimulationAllows(observer, event100, "tpl-simulator-observer-001");
    assertBoundedSimulationAllows(reviewedChange, event100, "tpl-simulator-reviewed-change-001");

    // Vector B: limit = 50 (typical bounded read, allowed)
    ObjectNode event50 = createActionWithLimit(IntNode.valueOf(50));
    assertBoundedSimulationAllows(observer, event50, "tpl-simulator-observer-001");
    assertBoundedSimulationAllows(reviewedChange, event50, "tpl-simulator-reviewed-change-001");

    // Vector C: limit = 99 (one below threshold, allowed)
    ObjectNode event99 = createActionWithLimit(IntNode.valueOf(99));
    assertBoundedSimulationAllows(observer, event99, "tpl-simulator-observer-001");
    assertBoundedSimulationAllows(reviewedChange, event99, "tpl-simulator-reviewed-change-001");
  }

  @Test
  void simulationLeavesInputsUnmutatedAndStoresNoCatalogState() {
    JsonNode observer = JsonSupport.read(ROOT.resolve(OBSERVER_PATH));
    ObjectNode action = createActionWithLimit(LongNode.valueOf((long) Integer.MAX_VALUE + 1));

    JsonNode observerCopy = observer.deepCopy();
    JsonNode actionCopy = action.deepCopy();

    PolicySimulationResult result = service.simulate(observer, action, T0);
    assertThat(result.dryRun()).isTrue();
    assertThat(result.outcome()).isEqualTo("block");
    assertThat(result.reasonCode()).isEqualTo("OVERSIZED_LOG_WINDOW_BLOCKED");
    assertThat(result.matchedRuleId()).isEqualTo("block-oversized-log-window");

    // Invariant: candidate policy input document was not mutated
    assertThat(observer).isEqualTo(observerCopy);

    // Invariant: action event input document was not mutated
    assertThat(action).isEqualTo(actionCopy);

    // Invariant: zero catalog mutation
    assertThat(service.listPolicyIds()).isEmpty();
    assertThat(service.activeVersion("tpl-simulator-observer-001")).isEmpty();
    assertThat(service.getVersion("tpl-simulator-observer-001", 1)).isEmpty();
    assertThat(service.listVersions("tpl-simulator-observer-001")).isEmpty();
  }

  @Test
  void nonIntegralAndMissingLimitsCharacterizeExistingClassifierBehavior() {
    // Missing limit field
    ObjectNode actionMissing = (ObjectNode) JsonSupport.read(ROOT.resolve(BASE_FIXTURE_PATH));
    ((ObjectNode) actionMissing.path("action").path("arguments")).remove("limit");
    Classification cMissing = evaluator.classify(actionMissing);
    assertThat(cMissing.signals()).doesNotContain("OVERSIZED_RESULT_WINDOW");
    assertThat(cMissing.severity()).isEqualTo("low");

    // Null limit
    Classification cNull = evaluator.classify(createActionWithLimit(NullNode.getInstance()));
    assertThat(cNull.signals()).doesNotContain("OVERSIZED_RESULT_WINDOW");

    // String limit (not an integral number node)
    Classification cString = evaluator.classify(createActionWithLimit(TextNode.valueOf("500")));
    assertThat(cString.signals()).doesNotContain("OVERSIZED_RESULT_WINDOW");

    // Fractional/floating-point limit (100.5 is not an integral number)
    Classification cDouble = evaluator.classify(createActionWithLimit(DoubleNode.valueOf(100.5)));
    assertThat(cDouble.signals()).doesNotContain("OVERSIZED_RESULT_WINDOW");

    // Boolean limit
    Classification cBool = evaluator.classify(createActionWithLimit(BooleanNode.TRUE));
    assertThat(cBool.signals()).doesNotContain("OVERSIZED_RESULT_WINDOW");

    // Object limit
    Classification cObj = evaluator.classify(createActionWithLimit(JsonSupport.MAPPER.createObjectNode()));
    assertThat(cObj.signals()).doesNotContain("OVERSIZED_RESULT_WINDOW");
  }

  private void assertOversizedSimulationBlocks(JsonNode policy, ObjectNode action, String expectedPolicyId) {
    PolicySimulationResult result = service.simulate(policy, action, T0);
    assertThat(result.dryRun()).isTrue();
    assertThat(result.policyId()).isEqualTo(expectedPolicyId);
    assertThat(result.version()).isEqualTo(1);
    assertThat(result.outcome())
        .as("Oversized limit must be blocked by rule block-oversized-log-window")
        .isEqualTo("block");
    assertThat(result.reasonCode()).isEqualTo("OVERSIZED_LOG_WINDOW_BLOCKED");
    assertThat(result.matchedRuleId()).isEqualTo("block-oversized-log-window");
    assertThat(result.evaluation().classification().signals())
        .contains("OVERSIZED_RESULT_WINDOW", "READ_VERB");
    assertThat(result.evaluation().classification().severity()).isEqualTo("high");
  }

  private void assertBoundedSimulationAllows(JsonNode policy, ObjectNode action, String expectedPolicyId) {
    PolicySimulationResult result = service.simulate(policy, action, T0);
    assertThat(result.dryRun()).isTrue();
    assertThat(result.policyId()).isEqualTo(expectedPolicyId);
    assertThat(result.version()).isEqualTo(1);
    assertThat(result.outcome())
        .as("Bounded limit must be allowed by rule allow-cli-staging-cloudwatch-read")
        .isEqualTo("allow");
    assertThat(result.reasonCode()).isEqualTo("CLI_STAGING_LOG_READ_ALLOWED");
    assertThat(result.matchedRuleId()).isEqualTo("allow-cli-staging-cloudwatch-read");
    assertThat(result.evaluation().classification().signals())
        .contains("READ_VERB")
        .doesNotContain("OVERSIZED_RESULT_WINDOW");
    assertThat(result.evaluation().classification().severity()).isEqualTo("low");
  }

  private ObjectNode createActionWithLimit(JsonNode limitNode) {
    ObjectNode action = (ObjectNode) JsonSupport.read(ROOT.resolve(BASE_FIXTURE_PATH));
    ((ObjectNode) action.path("action").path("arguments")).set("limit", limitNode);
    return action;
  }

  private static JsonNode parseJsonNumber(String numberString) {
    try {
      return JsonSupport.MAPPER.readTree("{\"val\":" + numberString + "}").path("val");
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
