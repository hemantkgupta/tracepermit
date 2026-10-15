package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.HandshakeOnlyMcpDispatcher;
import io.tracepermit.contracts.JsonSupport;
import java.util.Set;

/** Stateless JSON-response MCP transport. Authentication/enrollment happen before dispatch. */
final class ApprovalMcpAdapter {
  static final String REQUEST = "tracepermit_request_simulated_change";
  static final String STATUS = "tracepermit_get_approval";
  static final String RESUME = "tracepermit_resume_approval";
  static final Set<String> PROTOCOLS = Set.of("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26");
  private static final Set<String> ROOT_FIELDS = Set.of("jsonrpc", "id", "method", "params");
  private static final Set<String> STATE_ERRORS = Set.of("APPROVAL_ALREADY_DECIDED",
      "APPROVAL_NOT_EXECUTABLE", "APPROVAL_REPLAYED", "APPROVAL_EXPIRED",
      "APPROVAL_BINDING_MISMATCH", "APPROVAL_POLICY_MISMATCH", "APPROVAL_NOT_REQUIRED");
  @FunctionalInterface interface Operations { JsonNode call(String name, JsonNode arguments); }
  record Response(int status, JsonNode body) {}
  private final Operations operations;
  private final Set<String> allowedTools;

  ApprovalMcpAdapter(Operations operations, Set<String> allowedTools) {
    this.operations = operations; this.allowedTools = Set.copyOf(allowedTools);
  }

  Response dispatch(JsonNode request) {
    JsonNode id = request.get("id");
    if (!only(request, ROOT_FIELDS) || !"2.0".equals(request.path("jsonrpc").asText())
        || !request.path("method").isTextual()
        || (request.has("params") && !request.path("params").isObject())) {
      return error(id, -32600, "INVALID_REQUEST");
    }
    String method = request.path("method").asText();
    if ("notifications/initialized".equals(method) && id == null) return new Response(202, null);
    if (!validId(id)) return error(null, -32600, "INVALID_REQUEST");
    JsonNode params = request.path("params");
    switch (method) {
      case "initialize", "server/discover" -> {
        try {
          var response = new HandshakeOnlyMcpDispatcher().dispatchPublic(request).orElseThrow();
          if (response.body().has("result")) {
            ((ObjectNode) response.body().path("result")).put("instructions",
                "Simulator only. Request a change to receive a durable pending approval, not execution. "
                + "A different human reviews the exact change in the browser inbox. "
                + "Get status, then explicitly resume with approvalRequestId and requestHash. "
                + "Never retry a write automatically or treat pending/approved as executed. No approval tool is exposed.");
          }
          return new Response(response.status(), response.body());
        } catch (IllegalArgumentException failure) { return error(id, -32602, "INVALID_PARAMS"); }
      }
      case "ping" -> { return success(id, JsonSupport.MAPPER.createObjectNode()); }
      case "tools/list" -> {
        if (!params.isMissingNode() && (!only(params, Set.of("_meta"))
            || (params.has("_meta") && !params.path("_meta").isObject()))) return error(id, -32602, "INVALID_PARAMS");
        ObjectNode catalog = catalog();
        var tools = catalog.withArray("/tools");
        for (int index = tools.size() - 1; index >= 0; index--) {
          if (!allowedTools.contains(tools.get(index).path("name").asText())) tools.remove(index);
        }
        return success(id, catalog);
      }
      case "tools/call" -> {
        if (!only(params, Set.of("name", "arguments", "_meta"))
            || (params.has("_meta") && !params.path("_meta").isObject())
            || !params.path("name").isTextual()) return error(id, -32602, "INVALID_PARAMS");
        String name = params.path("name").asText();
        Set<String> fields = switch (name) {
          case REQUEST -> Set.of("groupId", "port", "cidr");
          case STATUS -> Set.of("approvalRequestId");
          case RESUME -> Set.of("approvalRequestId", "requestHash");
          default -> null;
        };
        if (fields == null) return error(id, -32602, "TOOL_NOT_FOUND");
        // Discovery is advisory; invocation always repeats current scope/role checks too.
        if (!allowedTools.contains(name)) return toolError(id, "NOT_AUTHORIZED");
        JsonNode arguments = params.path("arguments");
        if (!only(arguments, fields) || arguments.size() != fields.size()
            || (!REQUEST.equals(name) && !arguments.path("approvalRequestId").asText().matches("[a-f0-9-]{36}"))
            || (RESUME.equals(name) && (!arguments.path("requestHash").isTextual()
                || arguments.path("requestHash").asText().length() > 128))) {
          return error(id, -32602, "INVALID_PARAMS");
        }
        try { return toolResult(id, operations.call(name, arguments), false); }
        catch (SimulatorApprovalServer.HttpFailure failure) { return toolError(id, failure.code); }
        catch (SecurityException failure) { return toolError(id, "NOT_AUTHORIZED"); }
        catch (IllegalArgumentException failure) {
          return toolError(id, "APPROVAL_BINDING_MISMATCH".equals(failure.getMessage())
              ? "APPROVAL_BINDING_MISMATCH" : "INVALID_REQUEST");
        } catch (IllegalStateException failure) {
          String code = failure.getMessage();
          return toolError(id, STATE_ERRORS.contains(code == null ? "" : code) ? code : "SECURITY_STATE_UNAVAILABLE");
        } catch (RuntimeException failure) { return toolError(id, "SECURITY_STATE_UNAVAILABLE"); }
      }
      default -> { return error(id, -32601, "METHOD_NOT_FOUND"); }
    }
  }

  private static ObjectNode catalog() {
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    ArrayNode tools = result.putArray("tools");
    ObjectNode request = tool(tools, REQUEST, "Request independent human review of one simulated security-group change. Returns pending, never execution.", false);
    ObjectNode properties = request.withObject("/inputSchema/properties");
    properties.putObject("groupId").put("type", "string").putArray("enum").add("sg-demo");
    properties.putObject("port").put("type", "integer").put("minimum", 1).put("maximum", 65535);
    properties.putObject("cidr").put("type", "string").putArray("enum")
        .add("192.0.2.0/24").add("198.51.100.0/24").add("203.0.113.0/24");
    request.withArray("/inputSchema/required").add("groupId").add("port").add("cidr");
    for (String name : java.util.List.of(STATUS, RESUME)) {
      ObjectNode entry = tool(tools, name, STATUS.equals(name)
          ? "Read an owned durable approval and execution status. Does not execute."
          : "Explicitly resume an approved owned MCP change once. No new arguments are accepted. Never blindly retry.", STATUS.equals(name));
      entry.withObject("/inputSchema/properties").putObject("approvalRequestId").put("type", "string")
          .put("pattern", "^[a-f0-9-]{36}$");
      entry.withArray("/inputSchema/required").add("approvalRequestId");
      if (RESUME.equals(name)) {
        entry.withObject("/inputSchema/properties").putObject("requestHash").put("type", "string").put("maxLength", 128);
        entry.withArray("/inputSchema/required").add("requestHash");
      }
    }
    return result;
  }

  private static ObjectNode tool(ArrayNode tools, String name, String description, boolean readOnly) {
    ObjectNode tool = tools.addObject().put("name", name).put("description", description);
    tool.putObject("inputSchema").put("type", "object").put("additionalProperties", false)
        .putObject("properties");
    tool.putObject("annotations").put("readOnlyHint", readOnly).put("destructiveHint", !readOnly)
        .put("idempotentHint", readOnly).put("openWorldHint", false);
    return tool;
  }

  private static boolean only(JsonNode value, Set<String> fields) {
    if (!value.isObject()) return false;
    var names = value.fieldNames();
    while (names.hasNext()) if (!fields.contains(names.next())) return false;
    return true;
  }

  private static boolean validId(JsonNode id) {
    return id != null && ((id.isIntegralNumber() && id.canConvertToLong()) || (id.isTextual()
        && !id.asText().isBlank() && id.asText().length() <= 128
        && id.asText().chars().noneMatch(Character::isISOControl)));
  }

  private static Response toolError(JsonNode id, String code) {
    return toolResult(id, JsonSupport.MAPPER.createObjectNode().put("mode", "simulated").put("errorCode", code), true);
  }

  private static Response toolResult(JsonNode id, JsonNode data, boolean failed) {
    ObjectNode result = JsonSupport.MAPPER.createObjectNode().put("isError", failed);
    result.set("structuredContent", data);
    result.putArray("content").addObject().put("type", "text").put("text", data.toString());
    return success(id, result);
  }

  private static Response success(JsonNode id, JsonNode result) {
    ObjectNode response = JsonSupport.MAPPER.createObjectNode().put("jsonrpc", "2.0");
    response.set("id", id); response.set("result", result);
    return new Response(200, response);
  }

  private static Response error(JsonNode id, int code, String message) {
    ObjectNode response = JsonSupport.MAPPER.createObjectNode().put("jsonrpc", "2.0");
    if (validId(id)) response.set("id", id); else response.putNull("id");
    response.putObject("error").put("code", code).put("message", message);
    return new Response(200, response);
  }
}
