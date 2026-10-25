package io.tracepermit.agentcore.http;

import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Closed HTTP/1.1 framing; body UTF-8/JSON validation belongs solely to the existing ingress. */
final class RegistrationHttpCodec {
  static final int MAX_HEADER_BYTES = 24_576;
  private static final Set<String> HUMAN = Set.of("human-authorization", "x-human-authorization",
      "delegation", "x-delegation", "on-behalf-of", "x-on-behalf-of", "actor-token", "subject-token");
  private static final Set<String> SUPPORTED = Set.of("host", "authorization", "content-type",
      "content-length", "connection", "user-agent", "accept");

  private RegistrationHttpCodec() {}

  record Head(String authorization, String humanAuthorization, int bodyLength) {
    @Override public String toString() { return "RegistrationHttpHead[redacted]"; }
  }

  static final class Rejection extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int status;
    Rejection(int status, String code) {
      super(code, null, false, false);
      this.status = status;
    }
    Response response() { return error(status, getMessage()); }
  }

  static final class Response {
    private final byte[] wire;
    Response(int status, String json) {
      byte[] body = json.getBytes(StandardCharsets.UTF_8);
      String challenge = status == 401 ? "WWW-Authenticate: Bearer\r\n" : status == 405 ? "Allow: POST\r\n" : "";
      String headers = "HTTP/1.1 " + status + " " + reason(status) + "\r\n" + challenge
          + "Content-Type: application/json\r\nContent-Length: " + body.length
          + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
      byte[] prefix = headers.getBytes(StandardCharsets.US_ASCII);
      wire = new byte[prefix.length + body.length];
      System.arraycopy(prefix, 0, wire, 0, prefix.length);
      System.arraycopy(body, 0, wire, prefix.length, body.length);
    }
    ByteBuffer buffer() { return ByteBuffer.wrap(wire); }
    @Override public String toString() { return "RegistrationHttpResponse[redacted]"; }
  }

  static Head parse(byte[] header, int length, int port) {
    for (int i = 0; i < length; i++) {
      int value = header[i] & 0xff;
      if (value > 126 || value < 32 && value != 13 && value != 10) throw reject(400, "HTTP_REQUEST_INVALID");
      if (value == 13 && (i + 1 >= length || header[i + 1] != 10)
          || value == 10 && (i == 0 || header[i - 1] != 13)) throw reject(400, "HTTP_REQUEST_INVALID");
    }
    String[] lines = new String(header, 0, length - 4, StandardCharsets.US_ASCII).split("\r\n", -1);
    if (lines.length < 2 || lines.length > 17 || lines[0].length() > 512) throw reject(400, "HTTP_REQUEST_INVALID");
    String[] request = lines[0].split(" ", -1);
    if (request.length != 3 || !"HTTP/1.1".equals(request[2])) throw reject(400, "HTTP_REQUEST_INVALID");
    if (!"POST".equals(request[0])) throw reject(405, "HTTP_METHOD_NOT_ALLOWED");
    if (!AgentCoreRegistrationHttpServer.ROUTE.equals(request[1])) throw reject(404, "HTTP_ROUTE_NOT_FOUND");
    Map<String, String> fields = new HashMap<>();
    String human = null;
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      int colon = line.indexOf(':');
      if (colon < 1 || !line.substring(0, colon).matches("[A-Za-z][A-Za-z0-9-]{0,63}")) throw reject(400, "HTTP_REQUEST_INVALID");
      String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1);
      if (value.startsWith(" ")) value = value.substring(1);
      if (value.startsWith(" ") || value.endsWith(" ")) throw reject(400, "HTTP_REQUEST_INVALID");
      if (fields.putIfAbsent(name, value) != null) throw reject(400, "HTTP_REQUEST_INVALID");
      if (HUMAN.contains(name)) human = value;
      else if (name.equals("content-encoding")) throw reject(415, "HTTP_UNSUPPORTED_MEDIA");
      else if (name.equals("expect")) throw reject(417, "HTTP_EXPECTATION_UNSUPPORTED");
      else if (!SUPPORTED.contains(name)) throw reject(400, "HTTP_REQUEST_INVALID");
    }
    if (!fields.getOrDefault("host", "").equals("127.0.0.1:" + port)) throw reject(400, "HTTP_REQUEST_INVALID");
    String authorization = fields.get("authorization");
    if (authorization == null || authorization.isEmpty()) throw reject(401, "AGENTCORE_IDENTITY_REJECTED");
    if (authorization.length() > 16_391 || authorization.contains(",")) throw reject(400, "HTTP_REQUEST_INVALID");
    if (human != null) return new Head(authorization, human, 0);
    if (!Set.of("application/json", "application/json; charset=utf-8")
        .contains(fields.getOrDefault("content-type", ""))) throw reject(415, "HTTP_UNSUPPORTED_MEDIA");
    if (fields.containsKey("connection") && !"close".equalsIgnoreCase(fields.get("connection"))) throw reject(400, "HTTP_REQUEST_INVALID");
    String count = fields.get("content-length");
    if (count == null) throw reject(411, "HTTP_LENGTH_REQUIRED");
    if (!count.matches("0|[1-9][0-9]{0,4}")) {
      if (count.matches("[0-9]+")) throw reject(413, "HTTP_REQUEST_TOO_LARGE");
      throw reject(400, "HTTP_REQUEST_INVALID");
    }
    int bodyLength = Integer.parseInt(count);
    if (bodyLength > AgentCoreRegistrationIngress.MAXIMUM_REQUEST_BYTES) throw reject(413, "HTTP_REQUEST_TOO_LARGE");
    return new Head(authorization, null, bodyLength);
  }

  static Response receipt(AgentCoreRegistrationIngress.Receipt receipt) {
    String id = receipt.operationId();
    if (id == null || !UUID.fromString(id).toString().equals(id) || receipt.executionEnabled()) {
      return error(503, "HTTP_REGISTRATION_UNAVAILABLE");
    }
    return new Response(receipt.created() ? 201 : 200,
        "{\"created\":" + receipt.created() + ",\"operationId\":\"" + id + "\",\"executionEnabled\":false}");
  }

  static Response ingressError(String code) {
    return switch (code) {
      case "AGENTCORE_REQUEST_INVALID", "AGENTCORE_DELEGATION_CONTRACT_REQUIRED" -> error(400, code);
      case "AGENTCORE_IDENTITY_REJECTED" -> error(401, code);
      case "AGENTCORE_REQUEST_KEY_CONFLICT", "AGENTCORE_REGISTRATION_REJECTED" -> error(409, code);
      case "AGENTCORE_REGISTRATION_UNKNOWN", "AGENTCORE_STORAGE_UNAVAILABLE" -> error(503, code);
      default -> error(503, "HTTP_REGISTRATION_UNAVAILABLE");
    };
  }

  static Response error(int status, String code) { return new Response(status, "{\"error\":\"" + code + "\"}"); }
  private static Rejection reject(int status, String code) { return new Rejection(status, code); }
  private static String reason(int status) {
    return switch (status) {
      case 200 -> "OK";
      case 201 -> "Created";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 409 -> "Conflict";
      case 411 -> "Length Required";
      case 413 -> "Content Too Large";
      case 415 -> "Unsupported Media Type";
      case 417 -> "Expectation Failed";
      case 431 -> "Request Header Fields Too Large";
      default -> "Service Unavailable";
    };
  }
}
