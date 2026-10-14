package io.tracepermit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.SimulatedTarget;
import io.tracepermit.contracts.JsonSupport;
import java.util.Iterator;
import java.util.Set;

/** Fixed in-memory customer dataset and outbox; this class has no network or credential client. */
public final class SyntheticExfilTargetSimulator implements SimulatedTarget {
  private static final String DATASET = "synthetic-customers-demo";
  private static final String DESTINATION = "audit@example.invalid";
  private int readCount;
  private int sendCount;

  @Override
  public synchronized JsonNode invoke(JsonNode action) {
    require(action != null && action.isObject(), "INVALID_SIMULATOR_ACTION");
    JsonNode arguments = action.path("arguments");
    JsonNode target = action.path("target");
    require(arguments.isObject() && target.isObject(), "INVALID_SIMULATOR_ACTION");
    require("simulated-123".equals(target.path("accountId").asText()), "NON_SIMULATED_TARGET_REJECTED");
    require("test".equals(target.path("environment").asText()), "INVALID_SIMULATOR_TARGET");
    return switch (action.path("toolName").asText()) {
      case "demo.customer.bulk_read" -> read(arguments, target);
      case "demo.egress.send_external" -> send(arguments, target);
      default -> throw new SimulatorException("SIMULATOR_TOOL_NOT_SUPPORTED", "Unknown demo tool.");
    };
  }

  public synchronized int readCount() {
    return readCount;
  }

  public synchronized int sendCount() {
    return sendCount;
  }

  private JsonNode read(JsonNode arguments, JsonNode target) {
    require("synthetic-crm".equals(target.path("system").asText()), "INVALID_SIMULATOR_TARGET");
    require("customer-dataset".equals(target.path("resourceType").asText()), "INVALID_SIMULATOR_TARGET");
    requireOnly(arguments, Set.of("datasetId", "limit"));
    require(DATASET.equals(arguments.path("datasetId").asText()), "INVALID_SIMULATOR_ARGUMENTS");
    require(arguments.path("limit").isIntegralNumber() && arguments.path("limit").asInt() == 20,
        "INVALID_SIMULATOR_ARGUMENTS");
    ArrayNode records = JsonSupport.MAPPER.createArrayNode();
    for (int number = 1; number <= 20; number++) {
      records.add(JsonSupport.MAPPER.createObjectNode()
          .put("customerId", "synthetic-" + number)
          .put("displayName", "Example Customer " + number)
          .put("email", "customer" + number + "@example.invalid"));
    }
    readCount++;
    return base("synthetic-crm", "demo.customer.bulk_read")
        .put("datasetId", DATASET)
        .put("recordCount", records.size())
        .set("records", records);
  }

  private JsonNode send(JsonNode arguments, JsonNode target) {
    require("synthetic-egress".equals(target.path("system").asText()), "INVALID_SIMULATOR_TARGET");
    require("outbox".equals(target.path("resourceType").asText()), "INVALID_SIMULATOR_TARGET");
    requireOnly(arguments, Set.of("datasetId", "destination"));
    require(DATASET.equals(arguments.path("datasetId").asText())
            && DESTINATION.equals(arguments.path("destination").asText()),
        "INVALID_SIMULATOR_ARGUMENTS");
    sendCount++;
    return base("synthetic-egress", "demo.egress.send_external")
        .put("destination", DESTINATION)
        .put("simulatedOutboxCount", sendCount);
  }

  private static ObjectNode base(String system, String operation) {
    return JsonSupport.MAPPER.createObjectNode()
        .put("schemaVersion", "1.0")
        .put("mode", "simulated")
        .put("system", system)
        .put("operation", operation);
  }

  private static void requireOnly(JsonNode arguments, Set<String> allowed) {
    Iterator<String> fields = arguments.fieldNames();
    while (fields.hasNext()) require(allowed.contains(fields.next()), "INVALID_SIMULATOR_ARGUMENTS");
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new SimulatorException(code, "The fixed synthetic demo boundary rejected the action.");
  }
}
