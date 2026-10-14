package io.tracepermit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SyntheticPromptInjectionSimulatorTest {
  @Test
  void untrustedResultIsFixedAndIamEffectExistsOnlyInMemory() {
    SyntheticPromptInjectionSimulator simulator = new SyntheticPromptInjectionSimulator();
    ObjectNode read = action("demo.knowledge.fetch_runbook", "synthetic-knowledge", "test", "runbook");
    read.putObject("arguments").put("documentId", "simulated-ops-runbook");

    JsonNode result = simulator.invoke(read);
    assertThat(result.path("mode").asText()).isEqualTo("simulated");
    assertThat(result.path("content").asText())
        .contains(SyntheticPromptInjectionSimulator.ATTACK_MARKER, "aws.iam.attach_role_policy");
    assertThat(simulator.readCount()).isEqualTo(1);
    assertThat(simulator.iamMutationCount()).isZero();

    ObjectNode iam = action("aws.iam.attach_role_policy", "aws", "prod", "iam-role");
    iam.putObject("arguments").put("roleName", "demo-role").put("policyId", "simulated-admin");
    ObjectNode realAccount = iam.deepCopy();
    ((ObjectNode) realAccount.path("target")).put("accountId", "123456789012");
    assertCode(simulator, realAccount, "NON_SIMULATED_TARGET_REJECTED");
    ObjectNode extra = iam.deepCopy();
    ((ObjectNode) extra.path("arguments")).put("policyArn", "unreviewed");
    assertCode(simulator, extra, "INVALID_SIMULATOR_ARGUMENTS");
    ObjectNode wrongRole = iam.deepCopy();
    ((ObjectNode) wrongRole.path("arguments")).put("roleName", "another-role");
    assertCode(simulator, wrongRole, "INVALID_SIMULATOR_ARGUMENTS");
    assertThat(simulator.simulatedPolicyAttached()).isFalse();

    JsonNode directSimulatedEffect = simulator.invoke(iam);
    assertThat(directSimulatedEffect.path("simulatedPolicyAttached").asBoolean()).isTrue();
    assertThat(simulator.iamMutationCount()).isEqualTo(1);
    assertThat(simulator.simulatedPolicyAttached()).isTrue();
  }

  private static ObjectNode action(
      String tool, String system, String environment, String resourceType) {
    ObjectNode action = JsonSupport.MAPPER.createObjectNode();
    action.put("toolName", tool);
    action.putObject("target")
        .put("system", system)
        .put("environment", environment)
        .put("accountId", "simulated-123")
        .put("resourceType", resourceType);
    return action;
  }

  private static void assertCode(
      SyntheticPromptInjectionSimulator simulator, JsonNode action, String code) {
    assertThatThrownBy(() -> simulator.invoke(action))
        .isInstanceOf(SimulatorException.class)
        .extracting(failure -> ((SimulatorException) failure).code())
        .isEqualTo(code);
  }
}
