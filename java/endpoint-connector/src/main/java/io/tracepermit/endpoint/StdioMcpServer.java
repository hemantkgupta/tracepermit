package io.tracepermit.endpoint;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class StdioMcpServer {
  public static final int MAX_FRAME_BYTES = 64 * 1024;
  private static final Set<String> SUPPORTED_METHODS =
      Set.of("initialize", "notifications/initialized", "tools/list", "tools/call");
  private static final Set<String> REQUEST_FIELDS = Set.of("jsonrpc", "id", "method", "params");
  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final InputStream input;
  private final OutputStream output;
  private final PrintStream diagnostics;
  private final EndpointConnector connector;
  private boolean outputFailed;

  public StdioMcpServer(
      InputStream input,
      OutputStream output,
      PrintStream diagnostics,
      EndpointConnector connector) {
    this.input = Objects.requireNonNull(input);
    this.output = Objects.requireNonNull(output);
    this.diagnostics = Objects.requireNonNull(diagnostics);
    this.connector = Objects.requireNonNull(connector);
  }

  public int run() {
    while (true) {
      byte[] frame;
      try {
        frame = readFrame();
      } catch (FrameTooLargeException exception) {
        writeError(null, -32600, "Request frame exceeds the configured limit.", "FRAME_TOO_LARGE");
        diagnostic("FRAME_TOO_LARGE");
        return 2;
      } catch (IOException exception) {
        diagnostic("STDIO_READ_FAILED");
        return 2;
      }
      if (frame == null) return 0;
      if (frame.length == 0) continue;
      handle(frame);
      if (outputFailed) return 2;
    }
  }

  private void handle(byte[] frame) {
    JsonNode request;
    try {
      request = STRICT_MAPPER.readTree(frame);
    } catch (IOException exception) {
      writeError(null, -32700, "Malformed JSON-RPC input.", "MALFORMED_JSON_RPC");
      diagnostic("MALFORMED_JSON_RPC");
      return;
    }
    JsonNode id =
        request != null && request.isObject() && validId(request)
            ? request.get("id")
            : null;
    if (!validRequest(request)) {
      writeError(id, -32600, "Invalid JSON-RPC request.", "INVALID_JSON_RPC");
      diagnostic("INVALID_JSON_RPC");
      return;
    }

    String method = request.path("method").asText();
    boolean notification = !request.has("id");
    if (!SUPPORTED_METHODS.contains(method)) {
      if (!notification) {
        writeError(id, -32601, "Unsupported MCP method.", "UNSUPPORTED_MCP_METHOD");
      }
      diagnostic("UNSUPPORTED_MCP_METHOD");
      return;
    }
    if ("notifications/initialized".equals(method) != notification) {
      writeError(id, -32600, "Invalid request or notification form.", "INVALID_JSON_RPC");
      diagnostic("INVALID_JSON_RPC");
      return;
    }

    try {
      Optional<JsonNode> response = connector.forward(request);
      if (notification) return;
      JsonNode value = response.orElseThrow(
          () -> new ConnectorException(
              "INVALID_GATEWAY_RESPONSE", "The gateway response is invalid."));
      if (!"2.0".equals(value.path("jsonrpc").asText())
          || !value.has("id")
          || !value.get("id").equals(id)) {
        throw new ConnectorException(
            "INVALID_GATEWAY_RESPONSE", "The gateway response correlation is invalid.");
      }
      write(value);
    } catch (ConnectorException exception) {
      if (!notification) {
        writeError(id, -32000, "TracePermit could not forward the request.", exception.code());
      }
      diagnostic(exception.code());
    } catch (RuntimeException exception) {
      if (!notification) {
        writeError(id, -32000, "TracePermit could not forward the request.", "CONNECTOR_FAILURE");
      }
      diagnostic("CONNECTOR_FAILURE");
    }
  }

  private byte[] readFrame() throws IOException, FrameTooLargeException {
    ByteArrayOutputStream frame = new ByteArrayOutputStream(Math.min(4096, MAX_FRAME_BYTES));
    while (true) {
      int value = input.read();
      if (value < 0) return frame.size() == 0 ? null : stripCarriageReturn(frame.toByteArray());
      if (value == '\n') return stripCarriageReturn(frame.toByteArray());
      if (frame.size() >= MAX_FRAME_BYTES) throw new FrameTooLargeException();
      frame.write(value);
    }
  }

  private static byte[] stripCarriageReturn(byte[] value) {
    if (value.length == 0 || value[value.length - 1] != '\r') return value;
    return java.util.Arrays.copyOf(value, value.length - 1);
  }

  private static boolean validRequest(JsonNode request) {
    if (request == null
        || !request.isObject()
        || !"2.0".equals(request.path("jsonrpc").asText())
        || !request.path("method").isTextual()
        || request.path("method").asText().isBlank()
        || request.path("method").asText().length() > 128
        || (request.has("params") && !request.path("params").isObject())
        || !validId(request)) {
      return false;
    }
    java.util.Iterator<String> fields = request.fieldNames();
    while (fields.hasNext()) {
      if (!REQUEST_FIELDS.contains(fields.next())) return false;
    }
    return true;
  }

  private static boolean validId(JsonNode request) {
    if (request == null || !request.isObject() || !request.has("id")) return true;
    JsonNode id = request.get("id");
    return id != null && (id.isTextual() || id.isIntegralNumber());
  }

  private void writeError(JsonNode id, int code, String message, String tracepermitCode) {
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    if (id == null) response.putNull("id");
    else response.set("id", id.deepCopy());
    ObjectNode error = response.putObject("error");
    error.put("code", code);
    error.put("message", message);
    error.putObject("data").put("tracepermitCode", tracepermitCode);
    write(response);
  }

  private void write(JsonNode response) {
    try {
      byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(response);
      output.write(bytes);
      output.write('\n');
      output.flush();
    } catch (IOException exception) {
      diagnostic("STDIO_WRITE_FAILED");
      outputFailed = true;
    }
  }

  private void diagnostic(String code) {
    diagnostics.println("TracePermit endpoint connector: " + code);
  }

  private static final class FrameTooLargeException extends Exception {
    private static final long serialVersionUID = 1L;
  }
}
