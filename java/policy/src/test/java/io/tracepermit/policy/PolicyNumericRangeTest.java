package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.ContractViolation;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PolicyNumericRangeTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

  @Test
  void unsupportedVersionRangesCannotEnterOrCollideWithCatalog() {
    PolicyLifecycleService service = new PolicyLifecycleService();
    ObjectNode initial = policy();
    initial.put("version", 1);
    PolicyVersionRecord stored = service.stage(initial, NOW);
    service.activate(stored.policyId(), 1, NOW);
    List<BigInteger> invalid = List.of(
        BigInteger.ZERO, BigInteger.valueOf(-1),
        BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE),
        new BigInteger("4294967297"),
        new BigInteger("9007199254740991"),
        new BigInteger("9007199254740992"),
        BigInteger.valueOf(Long.MAX_VALUE),
        new BigInteger("18446744073709551617"));
    for (BigInteger version : invalid) {
      ObjectNode candidate = initial.deepCopy();
      candidate.put("version", version);
      assertThatThrownBy(() -> PolicyValidator.validate(candidate))
          .as("version %s", version).isInstanceOf(ContractViolation.class);
      assertThatThrownBy(() -> service.stage(candidate, NOW))
          .as("catalog ingestion of version %s", version).isInstanceOf(ContractViolation.class);
      assertThat(service.listVersions(stored.policyId())).hasSize(1);
      assertThat(service.activeVersion(stored.policyId()).orElseThrow().contentHash())
          .isEqualTo(stored.contentHash());
    }
  }

  @Test
  void maximumSupportedVersionRetainsIdentityThroughActivationAndSimulation() {
    PolicyLifecycleService service = new PolicyLifecycleService();
    ObjectNode candidate = policy();
    candidate.put("version", Integer.MAX_VALUE);
    PolicyVersionRecord staged = service.stage(candidate, NOW);
    PolicyVersionRecord active = service.activate(staged.policyId(), Integer.MAX_VALUE, NOW);
    assertThat(active.version()).isEqualTo(Integer.MAX_VALUE);
    assertThat(active.document().path("version").intValue()).isEqualTo(Integer.MAX_VALUE);
    JsonNode action = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    PolicySimulationResult simulated = service.simulate(
        staged.policyId(), Integer.MAX_VALUE, action, NOW);
    assertThat(simulated.version()).isEqualTo(Integer.MAX_VALUE);
    assertThat(simulated.evaluation().decision().policyVersion()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void outOfRangePrioritiesFailInsteadOfChangingRuleOrder() {
    List<BigInteger> invalid = List.of(
        BigInteger.valueOf(Integer.MIN_VALUE).subtract(BigInteger.ONE),
        BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE),
        new BigInteger("9007199254740992"),
        new BigInteger("-9007199254740992"),
        BigInteger.valueOf(Long.MAX_VALUE), BigInteger.valueOf(Long.MIN_VALUE),
        new BigInteger("18446744073709551617"));
    for (BigInteger priority : invalid) {
      ObjectNode candidate = policy();
      ((ObjectNode) candidate.path("rules").get(0)).put("priority", priority);
      assertThatThrownBy(() -> PolicyValidator.validate(candidate))
          .as("priority %s", priority).isInstanceOf(ContractViolation.class);
    }
  }

  @Test
  void signedPriorityBoundariesAndNegativeValuesStayValidAndOrdered() {
    for (int priority : new int[] {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}) {
      ObjectNode candidate = policy();
      ((ObjectNode) candidate.path("rules").get(0)).put("priority", priority);
      assertThat(PolicyValidator.validate(candidate).path("rules").get(0).path("priority").intValue())
          .isEqualTo(priority);
    }

    ObjectNode candidate = policy();
    ArrayNode rules = candidate.putArray("rules");
    addRule(rules, "low", Integer.MIN_VALUE, "allow");
    addRule(rules, "high", Integer.MAX_VALUE, "block");
    JsonNode validated = PolicyValidator.validate(candidate);
    JsonNode action = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    PolicyDecision decision = new PolicyEvaluator().evaluate(action, validated).decision();
    assertThat(decision.outcome()).isEqualTo("block");
    assertThat(decision.matchedRuleId()).isEqualTo("high");
  }

  private static void addRule(ArrayNode rules, String id, int priority, String outcome) {
    ObjectNode rule = rules.addObject().put("id", id).put("priority", priority)
        .put("description", "Synthetic numeric-boundary regression");
    rule.putObject("match");
    rule.putObject("decision").put("outcome", outcome).put("reasonCode", "NUMERIC_BOUNDARY_TEST");
  }

  private static ObjectNode policy() {
    return (ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
  }
}
