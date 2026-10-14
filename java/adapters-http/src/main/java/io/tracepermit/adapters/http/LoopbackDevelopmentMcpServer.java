package io.tracepermit.adapters.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tracepermit.adapters.remote.EnforcingMcpDispatcher;
import io.tracepermit.adapters.remote.RemoteMcpResponse;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.DevelopmentProfile;
import io.tracepermit.identity.DevelopmentTestTokenIssuer;
import io.tracepermit.identity.DevelopmentTokenVerifier;
import io.tracepermit.identity.SourceAttestationException;
import io.tracepermit.identity.SourceAttestationVerifier;
import io.tracepermit.identity.VerifiedSourceEvidence;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Loopback-only development MCP edge. It cannot be configured as a remote production endpoint. */
public final class LoopbackDevelopmentMcpServer implements AutoCloseable {
  public static final String SOURCE_ATTESTATION_HEADER = "TracePermit-Source-Attestation";
  private static final String TOKEN_GRANT =
      "urn:tracepermit:params:oauth:grant-type:development-profile";
  private static final int MAX_REQUEST_BYTES = 64 * 1024;
  private static final Set<String> LOCAL_ORIGINS =
      Set.of("http://127.0.0.1:5173", "http://localhost:5173");
  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final HttpServer server;
  private final ExecutorService executor;
  private final DevelopmentTokenVerifier tokenVerifier;
  private final DevelopmentTestTokenIssuer tokenIssuer;
  private final SourceAttestationVerifier sourceVerifier;
  private final EnforcingMcpDispatcher dispatcher;
  private final Supplier<SecurityReadiness> readiness;

  private LoopbackDevelopmentMcpServer(
      HttpServer server,
      ExecutorService executor,
      DevelopmentTokenVerifier tokenVerifier,
      DevelopmentTestTokenIssuer tokenIssuer,
      SourceAttestationVerifier sourceVerifier,
      EnforcingMcpDispatcher dispatcher,
      Supplier<SecurityReadiness> readiness) {
    this.server = server;
    this.executor = executor;
    this.tokenVerifier = tokenVerifier;
    this.tokenIssuer = tokenIssuer;
    this.sourceVerifier = sourceVerifier;
    this.dispatcher = dispatcher;
    this.readiness = readiness;
  }

  public static LoopbackDevelopmentMcpServer start(
      InetSocketAddress address,
      DevelopmentTokenVerifier tokenVerifier,
      DevelopmentTestTokenIssuer tokenIssuer,
      SourceAttestationVerifier sourceVerifier,
      EnforcingMcpDispatcher dispatcher,
      Supplier<SecurityReadiness> readiness) {
    Objects.requireNonNull(address);
    Objects.requireNonNull(tokenVerifier);
    Objects.requireNonNull(tokenIssuer);
    Objects.requireNonNull(dispatcher);
    Objects.requireNonNull(readiness);
    InetAddress resolved = address.getAddress();
    if (resolved == null || !resolved.isLoopbackAddress()) {
      throw new IllegalArgumentException("The development MCP server must bind to loopback.");
    }
    try {
      HttpServer server = HttpServer.create(address, 0);
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      LoopbackDevelopmentMcpServer endpoint =
          new LoopbackDevelopmentMcpServer(
              server,
              executor,
              tokenVerifier,
              tokenIssuer,
              sourceVerifier,
              dispatcher,
              readiness);
      server.createContext("/", endpoint::handle);
      server.setExecutor(executor);
      server.start();
      return endpoint;
    } catch (IOException exception) {
      throw new IllegalStateException("The development MCP server could not start.", exception);
    }
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.close();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      securityHeaders(exchange);
      if (!validHost(exchange)) {
        sendError(exchange, 400, "INVALID_HOST", "The request Host is not allowed.");
        return;
      }
      if (!validOrigin(exchange)) {
        sendError(exchange, 403, "ORIGIN_NOT_ALLOWED", "The request Origin is not allowed.");
        return;
      }
      if (exchange.getRequestURI().getRawQuery() != null) {
        sendError(exchange, 400, "INVALID_REQUEST", "Query parameters are not accepted.");
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if ("OPTIONS".equals(exchange.getRequestMethod())) {
        preflight(exchange, path);
      } else if ("/health".equals(path)) {
        health(exchange);
      } else if ("/ready".equals(path)) {
        ready(exchange);
      } else if ("/.well-known/oauth-protected-resource".equals(path)
          || "/.well-known/oauth-protected-resource/mcp".equals(path)) {
        protectedResourceMetadata(exchange);
      } else if ("/dev/oauth/token".equals(path)) {
        token(exchange);
      } else if ("/mcp".equals(path)) {
        mcp(exchange);
      } else {
        sendError(exchange, 404, "NOT_FOUND", "The requested resource does not exist.");
      }
    } catch (IllegalArgumentException exception) {
      sendError(exchange, 400, "INVALID_REQUEST", "The request is invalid.");
    } catch (RuntimeException exception) {
      sendError(exchange, 500, "INTERNAL_ERROR", "The development request failed safely.");
    } finally {
      exchange.close();
    }
  }

  private void health(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "GET");
      return;
    }
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("status", "ok");
    response.put("service", "tracepermit-java-development-mcp");
    response.put("mode", "simulated-loopback");
    send(exchange, 200, response);
  }

  private void ready(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "GET");
      return;
    }
    SecurityReadiness snapshot = readinessSnapshot();
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("ready", snapshot.ready());
    response.put("checkedAt", snapshot.checkedAt().toString());
    response.put("mode", "simulated-loopback");
    response.set("dependencies", JsonSupport.MAPPER.valueToTree(snapshot.dependencies()));
    send(exchange, snapshot.ready() ? 200 : 503, response);
  }

  private void protectedResourceMetadata(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "GET");
      return;
    }
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("resource", "http://127.0.0.1:" + port() + "/mcp");
    response.put("resource_name", "TracePermit MCP (simulated development)");
    response.putArray("scopes_supported").add("aws.logs.discover").add("aws.logs.read");
    response.putArray("bearer_methods_supported").add("header");
    send(exchange, 200, response);
  }

  private void token(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "POST, OPTIONS");
      return;
    }
    if (!readyForProtectedRequest(exchange)) return;
    JsonNode request = readJsonRequest(exchange);
    if (request == null) return;
    if (!fields(request).equals(Set.of("grant_type", "profile"))
        || !TOKEN_GRANT.equals(request.path("grant_type").asText())
        || !request.path("profile").isTextual()) {
      sendError(
          exchange,
          400,
          "INVALID_DEVELOPMENT_TOKEN_REQUEST",
          "The development token request is invalid.");
      return;
    }
    DevelopmentProfile profile = profile(request.path("profile").asText());
    if (profile == null) {
      sendError(
          exchange,
          400,
          "INVALID_DEVELOPMENT_TOKEN_REQUEST",
          "The development token request is invalid.");
      return;
    }
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("access_token", tokenIssuer.issueToken(profile));
    response.put("token_type", "Bearer");
    response.put("expires_in", 300);
    response.put("scope", String.join(" ", profile.scopes()));
    send(exchange, 200, response);
  }

  private void mcp(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "POST, OPTIONS");
      return;
    }
    if (!readyForProtectedRequest(exchange)) return;

    AuthenticatedConnector connector;
    try {
      connector = tokenVerifier.authenticate(uniqueHeader(exchange, "Authorization"));
    } catch (AuthenticationException exception) {
      sendError(exchange, 401, exception.code(), "The connector credential is invalid.");
      return;
    }

    JsonNode request = readJsonRequest(exchange);
    if (request == null) return;
    Optional<VerifiedSourceEvidence> source = verifySource(exchange, request, connector);
    if (source == null) return;

    RemoteMcpResponse response;
    try {
      response = dispatcher.dispatch(request, connector, source);
    } catch (AuthenticationException exception) {
      sendError(exchange, 403, "AUTHORIZATION_DENIED", "The connector lacks required authority.");
      return;
    }
    if (response.body() == null) {
      exchange.sendResponseHeaders(response.status(), -1);
    } else {
      send(exchange, response.status(), response.body());
    }
  }

  private Optional<VerifiedSourceEvidence> verifySource(
      HttpExchange exchange, JsonNode request, AuthenticatedConnector connector) throws IOException {
    String presented = uniqueHeader(exchange, SOURCE_ATTESTATION_HEADER);
    if (connector.origin() != ConnectorOrigin.CLI) {
      if (presented != null) {
        sendError(
            exchange,
            400,
            "SOURCE_ATTESTATION_NOT_ALLOWED",
            "Source attestation is not accepted for this connector profile.");
        return null;
      }
      return Optional.empty();
    }
    if (sourceVerifier == null) {
      sendError(
          exchange,
          503,
          "SOURCE_ATTESTATION_UNAVAILABLE",
          "Source-attestation verification is unavailable.");
      return null;
    }
    if (presented == null || presented.isEmpty()) {
      sendError(
          exchange,
          401,
          "MISSING_SOURCE_ATTESTATION",
          "This connector profile requires a source attestation.");
      return null;
    }
    try {
      VerifiedSourceEvidence source =
          sourceVerifier.verify(
              presented, request, connector.credentialFingerprint().value());
      if (!"interactive".equals(source.triggerEvidence().requestedMode())
          || !source.triggerEvidence().terminalAttached()) {
        sendError(
            exchange,
            403,
            "SOURCE_TRIGGER_NOT_INTERACTIVE",
            "The CLI profile requires authenticated interactive source evidence.");
        return null;
      }
      return Optional.of(source);
    } catch (SourceAttestationException exception) {
      int status =
          "SOURCE_ATTESTATION_REPLAYED".equals(exception.code())
              ? 409
              : "EXPIRED_SOURCE_ATTESTATION".equals(exception.code()) ? 401 : 403;
      sendError(exchange, status, exception.code(), "The source attestation is invalid.");
      return null;
    }
  }

  private JsonNode readJsonRequest(HttpExchange exchange) throws IOException {
    if (!jsonContentType(uniqueHeader(exchange, "Content-Type"))) {
      sendError(
          exchange,
          415,
          "UNSUPPORTED_MEDIA_TYPE",
          "The request must use application/json.");
      return null;
    }
    String contentLength = uniqueHeader(exchange, "Content-Length");
    if (contentLength != null) {
      try {
        long length = Long.parseLong(contentLength);
        if (length < 0 || length > MAX_REQUEST_BYTES) {
          sendError(
              exchange,
              413,
              "REQUEST_TOO_LARGE",
              "The request exceeds the size limit.");
          return null;
        }
      } catch (NumberFormatException exception) {
        sendError(exchange, 400, "INVALID_REQUEST", "The request is invalid.");
        return null;
      }
    }
    byte[] bytes;
    try (InputStream input = exchange.getRequestBody()) {
      bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
    }
    if (bytes.length > MAX_REQUEST_BYTES) {
      sendError(exchange, 413, "REQUEST_TOO_LARGE", "The request exceeds the size limit.");
      return null;
    }
    try {
      JsonNode request = STRICT_MAPPER.readTree(bytes);
      if (request == null || !request.isObject()) {
        sendError(exchange, 400, "MALFORMED_JSON", "The request JSON is malformed.");
        return null;
      }
      return request;
    } catch (IOException exception) {
      sendError(exchange, 400, "MALFORMED_JSON", "The request JSON is malformed.");
      return null;
    }
  }

  private boolean readyForProtectedRequest(HttpExchange exchange) throws IOException {
    if (readinessSnapshot().ready()) return true;
    sendError(
        exchange,
        503,
        "SECURITY_DEPENDENCY_UNAVAILABLE",
        "A required security dependency is unavailable.");
    return false;
  }

  private SecurityReadiness readinessSnapshot() {
    try {
      return Objects.requireNonNull(readiness.get());
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Security readiness is unavailable.", exception);
    }
  }

  private void preflight(HttpExchange exchange, String path) throws IOException {
    if (!"/mcp".equals(path) && !"/dev/oauth/token".equals(path)) {
      sendError(exchange, 404, "NOT_FOUND", "The requested resource does not exist.");
      return;
    }
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
    exchange
        .getResponseHeaders()
        .set(
            "Access-Control-Allow-Headers",
            "Authorization, Content-Type, TracePermit-Source-Attestation");
    exchange.sendResponseHeaders(204, -1);
  }

  private boolean validHost(HttpExchange exchange) {
    String host = uniqueHeader(exchange, "Host");
    if (host == null) return false;
    String suffix = ":" + port();
    return host.equals("127.0.0.1" + suffix)
        || host.equals("localhost" + suffix)
        || host.equals("[::1]" + suffix);
  }

  private static boolean validOrigin(HttpExchange exchange) {
    String origin = uniqueHeader(exchange, "Origin");
    if (origin == null) return true;
    if (!LOCAL_ORIGINS.contains(origin)) return false;
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
    exchange.getResponseHeaders().set("Vary", "Origin");
    return true;
  }

  private static String uniqueHeader(HttpExchange exchange, String name) {
    var values = exchange.getRequestHeaders().get(name);
    if (values == null || values.isEmpty()) return null;
    if (values.size() != 1) throw new IllegalArgumentException("Repeated header.");
    String value = values.getFirst();
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("Invalid header.");
    }
    return value;
  }

  private static boolean jsonContentType(String value) {
    if (value == null) return false;
    return "application/json"
        .equals(value.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT));
  }

  private static DevelopmentProfile profile(String id) {
    for (DevelopmentProfile candidate : DevelopmentProfile.values()) {
      if (candidate.id().equals(id)) return candidate;
    }
    return null;
  }

  private static Set<String> fields(JsonNode value) {
    Set<String> names = new HashSet<>();
    value.fieldNames().forEachRemaining(names::add);
    return Set.copyOf(names);
  }

  private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
    exchange.getResponseHeaders().set("Allow", allow);
    sendError(exchange, 405, "METHOD_NOT_ALLOWED", "The HTTP method is not allowed.");
  }

  private static void securityHeaders(HttpExchange exchange) {
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
  }

  private static void sendError(
      HttpExchange exchange, int status, String code, String message) throws IOException {
    ObjectNode body = JsonSupport.MAPPER.createObjectNode();
    body.put("error", code);
    body.put("message", message);
    body.putObject("execution").put("status", "not_executed");
    send(exchange, status, body);
  }

  private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
    byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
