package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.ScopeAuthorizer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A stateless MCP handshake/discovery surface with no tool invocation authority. */
public final class HandshakeOnlyMcpDispatcher implements RemoteMcpDispatcher {
  public static final String CURRENT_PROTOCOL_VERSION = "2026-07-28";
  private static final List<String> SUPPORTED_PROTOCOLS =
      List.of(CURRENT_PROTOCOL_VERSION, "2025-11-25", "2025-06-18", "2025-03-26");
  private static final Set<String> TOP_LEVEL_FIELDS =
      Set.of("jsonrpc", "id", "method", "params");
  private static final Set<String> DISCOVER_FIELDS = Set.of("_meta");
  private static final ConnectorScope DISCOVERY_SCOPE =
      ConnectorScope.of("aws.logs.discover");

  @Override
  public RemoteMcpResponse dispatch(JsonNode request, AuthenticatedConnector connector) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(connector);
    RemoteMcpResponse invalid = validate(request);
    if (invalid != null) return invalid;

    return switch (request.path("method").textValue()) {
      case "server/discover" -> discover(request);
      case "initialize" -> initialize(request);
      case "notifications/initialized" -> initialized(request);
      case "ping" -> success(requireId(request), JsonSupport.MAPPER.createObjectNode());
      case "tools/list" -> tools(request, connector);
      case "tools/call" ->
          jsonRpcError(
              request.get("id"),
              -32601,
              "REMOTE_TOOL_INVOCATION_NOT_CONFIGURED",
              "Remote tool invocation is not enabled in this transport foundation.");
      default ->
          jsonRpcError(
              request.get("id"), -32601, "METHOD_NOT_FOUND", "The MCP method is not supported.");
    };
  }

  /** Returns only the static handshake and tool catalog; it never authorizes tool invocation. */
  @Override
  public Optional<RemoteMcpResponse> dispatchPublic(JsonNode request) {
    Objects.requireNonNull(request);
    RemoteMcpResponse invalid = validate(request);
    if (invalid != null) return Optional.of(invalid);

    return Optional.of(
        switch (request.path("method").textValue()) {
          case "server/discover" -> discover(request);
          case "initialize" -> initialize(request);
          case "notifications/initialized" -> initialized(request);
          case "ping" -> success(requireId(request), JsonSupport.MAPPER.createObjectNode());
          case "tools/list" -> tools(request);
          default ->
              jsonRpcError(
                  request.get("id"),
                  -32601,
                  "METHOD_NOT_FOUND",
                  "The MCP method is not supported.");
        });
  }

  private static RemoteMcpResponse validate(JsonNode request) {
    if (!request.isObject()
        || !request.path("jsonrpc").isTextual()
        || !"2.0".equals(request.path("jsonrpc").textValue())
        || !request.path("method").isTextual()
        || request.path("method").textValue().isEmpty()
        || request.path("method").textValue().length() > 128
        || request.path("method").textValue().chars().anyMatch(Character::isISOControl)
        || !fields(request).stream().allMatch(TOP_LEVEL_FIELDS::contains)
        || (request.has("params") && !request.path("params").isObject())) {
      return jsonRpcError(request.get("id"), -32600, "INVALID_REQUEST", "The MCP request is invalid.");
    }
    return null;
  }

  private static RemoteMcpResponse initialize(JsonNode request) {
    JsonNode id = requireId(request);
    JsonNode params = request.path("params");
    JsonNode clientInfo = params.path("clientInfo");
    String requestedProtocol = text(params, "protocolVersion", 32);
    text(clientInfo, "name", 128);
    text(clientInfo, "version", 64);
    if (!params.path("capabilities").isObject()) {
      return jsonRpcError(id, -32602, "INVALID_PARAMS", "Initialize parameters are invalid.");
    }
    String selected =
        SUPPORTED_PROTOCOLS.contains(requestedProtocol)
            ? requestedProtocol
            : CURRENT_PROTOCOL_VERSION;
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    result.put("protocolVersion", selected);
    ObjectNode capabilities = result.putObject("capabilities");
    capabilities.putObject("tools").put("listChanged", false);
    ObjectNode serverInfo = result.putObject("serverInfo");
    serverInfo.put("name", "TracePermit");
    serverInfo.put("version", "0.1.0");
    result.put(
        "instructions",
        "Read-only discovery is available; remote tool invocation is not configured.");
    return success(id, result);
  }

  private static RemoteMcpResponse discover(JsonNode request) {
    JsonNode id = requireId(request);
    JsonNode params = request.path("params");
    if (!fields(params).stream().allMatch(DISCOVER_FIELDS::contains)
        || (params.has("_meta") && !params.path("_meta").isObject())) {
      return jsonRpcError(id, -32602, "INVALID_PARAMS", "Discovery parameters are invalid.");
    }
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    result.put("resultType", "complete");
    result.set("supportedVersions", JsonSupport.MAPPER.valueToTree(SUPPORTED_PROTOCOLS));
    result.putObject("capabilities").putObject("tools");
    ObjectNode serverInfo = result.putObject("_meta").putObject("io.modelcontextprotocol/serverInfo");
    serverInfo.put("name", "TracePermit");
    serverInfo.put("version", "0.1.0");
    result.put(
        "instructions",
        "Read-only discovery is available; remote tool invocation is not configured.");
    result.put("ttlMs", 3_600_000);
    result.put("cacheScope", "public");
    return success(id, result);
  }

  private static RemoteMcpResponse initialized(JsonNode request) {
    if (request.has("id")) {
      return jsonRpcError(
          request.get("id"), -32600, "INVALID_REQUEST", "Initialized must be a notification.");
    }
    return new RemoteMcpResponse(204, null);
  }

  private static RemoteMcpResponse tools(
      JsonNode request, AuthenticatedConnector connector) {
    ScopeAuthorizer.require(connector, DISCOVERY_SCOPE);
    return tools(request);
  }

  private static RemoteMcpResponse tools(JsonNode request) {
    JsonNode id = requireId(request);
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    ArrayNode tools = result.putArray("tools");
    ObjectNode logs = tools.addObject();
    logs.put("name", "aws.logs.filter_log_events");
    logs.put("description", "Read bounded, strictly simulated AWS log events.");
    ObjectNode schema = logs.putObject("inputSchema");
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    ArrayNode required = schema.putArray("required");
    required.add("logGroupName");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("logGroupName").put("type", "string");
    properties.putObject("filterPattern").put("type", "string");
    properties.putObject("limit").put("type", "integer");
    return success(id, result);
  }

  private static RemoteMcpResponse success(JsonNode id, JsonNode result) {
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id.deepCopy());
    response.set("result", result);
    return new RemoteMcpResponse(200, response);
  }

  private static RemoteMcpResponse jsonRpcError(
      JsonNode id, int number, String code, String message) {
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    if (validId(id)) response.set("id", id.deepCopy());
    else response.putNull("id");
    ObjectNode error = response.putObject("error");
    error.put("code", number);
    error.put("message", message);
    error.putObject("data").put("code", code);
    return new RemoteMcpResponse(200, response);
  }

  private static JsonNode requireId(JsonNode request) {
    JsonNode id = request.get("id");
    if (!validId(id)) {
      throw new IllegalArgumentException("MCP requests require a bounded string or integer ID.");
    }
    if (id.isTextual()
        && (id.textValue().isEmpty()
            || id.textValue().length() > 128
            || id.textValue().chars().anyMatch(Character::isISOControl))) {
      throw new IllegalArgumentException("MCP request ID is invalid.");
    }
    return id;
  }

  private static boolean validId(JsonNode id) {
    return id != null && (id.isTextual() || id.isIntegralNumber());
  }

  private static String text(JsonNode node, String field, int maximum) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isTextual()
        || value.textValue().isEmpty()
        || value.textValue().length() > maximum
        || value.textValue().chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " is invalid.");
    }
    return value.textValue();
  }

  private static List<String> fields(JsonNode value) {
    java.util.ArrayList<String> fields = new java.util.ArrayList<>();
    value.fieldNames().forEachRemaining(fields::add);
    return List.copyOf(fields);
  }
}
