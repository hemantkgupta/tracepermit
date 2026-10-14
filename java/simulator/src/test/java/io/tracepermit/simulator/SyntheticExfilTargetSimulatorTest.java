package io.tracepermit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SyntheticExfilTargetSimulatorTest {
  @Test
  void fixedDatasetAndOutboxRemainLocalAndRejectNonDemoInputs() {
    SyntheticExfilTargetSimulator simulator = new SyntheticExfilTargetSimulator();
    ObjectNode read = action("demo.customer.bulk_read", "synthetic-crm");
    read.putObject("arguments").put("datasetId", "synthetic-customers-demo").put("limit", 20);

    JsonNode result = simulator.invoke(read);
    assertThat(result.path("recordCount").asInt()).isEqualTo(20);
    assertThat(result.path("records").get(0).path("email").asText()).endsWith("@example.invalid");
    assertThat(simulator.readCount()).isEqualTo(1);

    ObjectNode send = action("demo.egress.send_external", "synthetic-egress");
    send.putObject("arguments")
        .put("datasetId", "synthetic-customers-demo")
        .put("destination", "audit@example.invalid");
    ObjectNode routable = send.deepCopy();
    ((ObjectNode) routable.path("arguments")).put("destination", "audit@example.com");
    assertCode(simulator, routable, "INVALID_SIMULATOR_ARGUMENTS");
    ObjectNode realAccount = send.deepCopy();
    ((ObjectNode) realAccount.path("target")).put("accountId", "123456789012");
    assertCode(simulator, realAccount, "NON_SIMULATED_TARGET_REJECTED");
    ObjectNode extra = send.deepCopy();
    ((ObjectNode) extra.path("arguments")).put("body", "unreviewed bytes");
    assertCode(simulator, extra, "INVALID_SIMULATOR_ARGUMENTS");
    ObjectNode prod = send.deepCopy();
    ((ObjectNode) prod.path("target")).put("environment", "prod");
    assertCode(simulator, prod, "INVALID_SIMULATOR_TARGET");
    assertThat(simulator.sendCount()).isZero();

    JsonNode simulatedSend = simulator.invoke(send);
    assertThat(simulatedSend.path("mode").asText()).isEqualTo("simulated");
    assertThat(simulatedSend.path("simulatedOutboxCount").asInt()).isEqualTo(1);
    assertThat(simulator.sendCount()).isEqualTo(1);
  }

  private static ObjectNode action(String tool, String system) {
    ObjectNode action = JsonSupport.MAPPER.createObjectNode();
    action.put("toolName", tool);
    action.putObject("target")
        .put("system", system)
        .put("environment", "test")
        .put("accountId", "simulated-123")
        .put("resourceType", "synthetic-crm".equals(system) ? "customer-dataset" : "outbox");
    return action;
  }

  private static void assertCode(
      SyntheticExfilTargetSimulator simulator, JsonNode action, String expected) {
    assertThatThrownBy(() -> simulator.invoke(action))
        .isInstanceOf(SimulatorException.class)
        .extracting(failure -> ((SimulatorException) failure).code())
        .isEqualTo(expected);
  }
}
