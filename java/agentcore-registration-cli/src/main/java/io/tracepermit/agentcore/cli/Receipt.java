package io.tracepermit.agentcore.cli;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** No request, configuration, credential, or raw diagnostic can enter the receipt. */
record Receipt(String outcome, Boolean created, String operationId) {
  private static final Set<String> CODES = Set.of("CONFIG_VALID", "CREATED", "EXISTING",
      "INPUT_INVALID", "CONFIG_INVALID", "NOT_READY", "REJECTED", "UNKNOWN", "USAGE");

  Receipt {
    if (!CODES.contains(outcome)) throw new IllegalArgumentException("Invalid outcome");
    boolean registered = outcome.equals("CREATED") || outcome.equals("EXISTING");
    if (registered != (created != null && operationId != null)
        || registered && (!operationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
            || created != outcome.equals("CREATED"))
        || !registered && (created != null || operationId != null))
      throw new IllegalArgumentException("Invalid receipt");
  }

  static Receipt failure(String code) { return new Receipt(code, null, null); }
  int exitCode() { return Set.of("CONFIG_VALID", "CREATED", "EXISTING").contains(outcome) ? 0 : 1; }

  byte[] bytes() {
    var json = Protocol.JSON.createObjectNode();
    json.put("schemaVersion", "1.0"); json.put("profile", Protocol.PROFILE);
    json.put("outcome", outcome); json.put("executionEnabled", false);
    if (created == null) json.putNull("created"); else json.put("created", created);
    if (operationId == null) json.putNull("operationId"); else json.put("operationId", operationId);
    return (json.toString() + "\n").getBytes(StandardCharsets.UTF_8);
  }

  static Receipt decode(byte[] bytes, int exit) {
    try {
      var json = Protocol.object(bytes);
      Protocol.fields(json, Set.of("schemaVersion", "profile", "outcome", "executionEnabled",
          "created", "operationId"), Set.of());
      if (!json.path("schemaVersion").asText().equals("1.0")
          || !json.path("profile").asText().equals(Protocol.PROFILE)
          || !json.path("executionEnabled").isBoolean() || json.path("executionEnabled").booleanValue()
          || !json.path("created").isNull() && !json.path("created").isBoolean()
          || !json.path("operationId").isNull() && !json.path("operationId").isTextual())
        return failure("UNKNOWN");
      var receipt = new Receipt(json.path("outcome").asText(),
          json.path("created").isNull() ? null : json.path("created").booleanValue(),
          json.path("operationId").isNull() ? null : json.path("operationId").textValue());
      return receipt.exitCode() == exit ? receipt : failure("UNKNOWN");
    } catch (RuntimeException invalid) { return failure("UNKNOWN"); }
  }
}
