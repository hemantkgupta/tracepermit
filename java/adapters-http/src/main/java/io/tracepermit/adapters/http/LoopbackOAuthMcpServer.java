package io.tracepermit.adapters.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tracepermit.adapters.remote.RemoteMcpDispatcher;
import io.tracepermit.adapters.remote.RemoteMcpResponse;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.BearerChallenge;
import io.tracepermit.identity.ConnectorAuthenticator;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OAuthProtectedResourceMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Loopback OAuth resource server for an outbound hosted-client tunnel.
 *
 * <p>This profile deliberately has no token issuer. It permits only bounded public
 * handshake/catalog parsing before authentication and verifies each provider-issued credential
 * before protected MCP dispatch.
 */
public final class LoopbackOAuthMcpServer implements AutoCloseable {
  private static final int MAXIMUM_REQUEST_BYTES = 65_536;
  private static final Set<String> DEPENDENCY_ERRORS =
      Set.of("OIDC_CONFIGURATION_UNAVAILABLE", "OIDC_KEY_SET_UNAVAILABLE");
  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final HttpServer server;
  private final ExecutorService executor;
  private final URI resourceMetadataUri;
  private final OAuthProtectedResourceMetadata metadata;
  private final List<ConnectorScope> requiredScopes;
  private final Set<String> allowedOrigins;
  private final ConnectorAuthenticator authenticator;
  private final RemoteMcpDispatcher dispatcher;
  private final Supplier<SecurityReadiness> readiness;
  private final PrintStream diagnostics;

  private LoopbackOAuthMcpServer(
      HttpServer server,
      ExecutorService executor,
      URI resourceMetadataUri,
      OAuthProtectedResourceMetadata metadata,
      List<ConnectorScope> requiredScopes,
      Set<String> allowedOrigins,
      ConnectorAuthenticator authenticator,
      RemoteMcpDispatcher dispatcher,
      Supplier<SecurityReadiness> readiness,
      PrintStream diagnostics) {
    this.server = server;
    this.executor = executor;
    this.resourceMetadataUri = resourceMetadataUri;
    this.metadata = metadata;
    this.requiredScopes = requiredScopes;
    this.allowedOrigins = allowedOrigins;
    this.authenticator = authenticator;
    this.dispatcher = dispatcher;
    this.readiness = readiness;
    this.diagnostics = diagnostics;
  }

  public static LoopbackOAuthMcpServer start(
      InetSocketAddress address,
      URI resourceMetadataUri,
      OAuthProtectedResourceMetadata metadata,
      List<ConnectorScope> requiredScopes,
      Set<String> allowedOrigins,
      ConnectorAuthenticator authenticator,
      RemoteMcpDispatcher dispatcher,
      Supplier<SecurityReadiness> readiness) {
    return start(
        address,
        resourceMetadataUri,
        metadata,
        requiredScopes,
        allowedOrigins,
        authenticator,
        dispatcher,
        readiness,
        System.err);
  }

  static LoopbackOAuthMcpServer start(
      InetSocketAddress address,
      URI resourceMetadataUri,
      OAuthProtectedResourceMetadata metadata,
      List<ConnectorScope> requiredScopes,
      Set<String> allowedOrigins,
      ConnectorAuthenticator authenticator,
      RemoteMcpDispatcher dispatcher,
      Supplier<SecurityReadiness> readiness,
      PrintStream diagnostics) {
    Objects.requireNonNull(address);
    Objects.requireNonNull(resourceMetadataUri);
    Objects.requireNonNull(metadata);
    requiredScopes = List.copyOf(requiredScopes);
    allowedOrigins = Set.copyOf(allowedOrigins);
    Objects.requireNonNull(authenticator);
    Objects.requireNonNull(dispatcher);
    Objects.requireNonNull(readiness);
    Objects.requireNonNull(diagnostics);
    InetAddress resolved = address.getAddress();
    if (resolved == null || !resolved.isLoopbackAddress()) {
      throw new IllegalArgumentException("The hosted-client OAuth server must bind to loopback.");
    }
    if (!"https".equals(resourceMetadataUri.getScheme())
        || resourceMetadataUri.getHost() == null
        || resourceMetadataUri.getUserInfo() != null
        || resourceMetadataUri.getQuery() != null
        || resourceMetadataUri.getFragment() != null) {
      throw new IllegalArgumentException("The external resource metadata URI must be safe HTTPS.");
    }
    if (requiredScopes.isEmpty()) {
      throw new IllegalArgumentException("At least one OAuth scope is required.");
    }
    try {
      HttpServer server = HttpServer.create(address, 0);
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      LoopbackOAuthMcpServer endpoint =
          new LoopbackOAuthMcpServer(
              server,
              executor,
              resourceMetadataUri,
              metadata,
              requiredScopes,
              allowedOrigins,
              authenticator,
              dispatcher,
              readiness,
              diagnostics);
      server.createContext("/", endpoint::handle);
      server.setExecutor(executor);
      server.start();
      return endpoint;
    } catch (IOException exception) {
      throw new IllegalStateException("The hosted-client OAuth server could not start.", exception);
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
        metadata(exchange);
      } else if ("/mcp".equals(path)) {
        mcp(exchange);
      } else {
        sendError(exchange, 404, "NOT_FOUND", "The requested resource does not exist.");
      }
    } catch (IllegalArgumentException exception) {
      sendError(exchange, 400, "INVALID_REQUEST", "The request is invalid.");
    } catch (RuntimeException exception) {
      sendError(exchange, 500, "INTERNAL_ERROR", "The OAuth connector request failed safely.");
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
    response.put("service", "tracepermit-java-hosted-client-oauth-mcp");
    response.put("mode", "simulated-oauth-loopback");
    response.put("tokenIssuerPresent", false);
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
    response.put("mode", "simulated-oauth-loopback");
    response.put("tokenIssuerPresent", false);
    response.set("dependencies", JsonSupport.MAPPER.valueToTree(snapshot.dependencies()));
    send(exchange, snapshot.ready() ? 200 : 503, response);
  }

  private void metadata(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "GET");
      return;
    }
    send(exchange, 200, metadata.toJson());
  }

  private void mcp(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      methodNotAllowed(exchange, "POST, OPTIONS");
      return;
    }
    if (!readyForProtectedRequest(exchange)) return;
    if (!validContentLength(exchange)) {
      sendError(exchange, 413, "REQUEST_TOO_LARGE", "The MCP request exceeds the size limit.");
      return;
    }

    JsonNode request = readJsonRequest(exchange);
    if (request == null) return;
    McpDiagnostic requestDiagnostic = McpDiagnostic.from(request);
    diagnostic(requestDiagnostic, "REQUEST", "RECEIVED", 0);
    var publicResponse = dispatcher.dispatchPublic(request);
    if (publicResponse.isPresent()) {
      RemoteMcpResponse response = publicResponse.orElseThrow();
      diagnostic(requestDiagnostic, "RESPONSE", "PUBLIC", response.status());
      sendMcpResponse(exchange, response);
      return;
    }

    AuthenticatedConnector connector;
    try {
      connector = authenticator.authenticate(uniqueHeader(exchange, "Authorization"));
    } catch (AuthenticationException exception) {
      diagnostics.println(
          "TracePermit OAuth authentication rejected: " + exception.diagnosticCode());
      if (!DEPENDENCY_ERRORS.contains(exception.code()) && isToolCall(request)) {
        diagnostic(requestDiagnostic, "RESPONSE", "AUTHENTICATION_REJECTED", 200);
        toolAuthenticationError(exchange, request, exception);
        return;
      }
      diagnostic(
          requestDiagnostic,
          "RESPONSE",
          "AUTHENTICATION_REJECTED",
          DEPENDENCY_ERRORS.contains(exception.code()) ? 503 : 401);
      authenticationError(exchange, exception);
      return;
    }

    RemoteMcpResponse response;
    try {
      response = dispatcher.dispatch(request, connector);
    } catch (AuthenticationException exception) {
      diagnostic(requestDiagnostic, "RESPONSE", "AUTHORIZATION_REJECTED", 403);
      sendError(exchange, 403, "AUTHORIZATION_DENIED", "The connector lacks required authority.");
      return;
    }
    diagnostic(requestDiagnostic, "RESPONSE", "PROTECTED", response.status());
    sendMcpResponse(exchange, response);
  }

  private void diagnostic(
      McpDiagnostic request, String stage, String route, int responseStatus) {
    String status = responseStatus == 0 ? "NONE" : Integer.toString(responseStatus);
    diagnostics.printf(
        "TracePermit MCP exchange: stage=%s method=%s tool=%s route=%s status=%s%n",
        stage, request.method(), request.tool(), route, status);
  }

  private record McpDiagnostic(String method, String tool) {
    private static McpDiagnostic from(JsonNode request) {
      String method =
          switch (request.path("method").asText()) {
            case "server/discover" -> "SERVER_DISCOVER";
            case "initialize" -> "INITIALIZE";
            case "notifications/initialized" -> "INITIALIZED_NOTIFICATION";
            case "ping" -> "PING";
            case "tools/list" -> "TOOLS_LIST";
            case "tools/call" -> "TOOLS_CALL";
            default -> "UNKNOWN";
          };
      if (!"TOOLS_CALL".equals(method)) return new McpDiagnostic(method, "NONE");
      String tool =
          switch (request.path("params").path("name").asText()) {
            case "tracepermit.get_profile" -> "TRACEPERMIT_GET_PROFILE";
            case "aws.logs.filter_log_events" -> "AWS_LOGS_FILTER_LOG_EVENTS";
            case "aws.ec2.terminate_instances" -> "AWS_EC2_TERMINATE_INSTANCES";
            default -> "UNKNOWN";
          };
      return new McpDiagnostic(method, tool);
    }
  }

  private static void sendMcpResponse(HttpExchange exchange, RemoteMcpResponse response)
      throws IOException {
    if (response.body() == null) {
      exchange.sendResponseHeaders(response.status(), -1);
    } else {
      send(exchange, response.status(), response.body());
    }
  }

  private void toolAuthenticationError(
      HttpExchange exchange, JsonNode request, AuthenticationException exception) throws IOException {
    boolean missing = "MISSING_BEARER_TOKEN".equals(exception.code());
    String challenge =
        missing
            ? BearerChallenge.toolAuthenticationRequired(resourceMetadataUri, requiredScopes)
            : BearerChallenge.toolInvalidToken(resourceMetadataUri, requiredScopes);
    ObjectNode body = JsonSupport.MAPPER.createObjectNode();
    body.put("jsonrpc", "2.0");
    body.set("id", request.path("id").deepCopy());
    ObjectNode result = body.putObject("result");
    result
        .putArray("content")
        .addObject()
        .put("type", "text")
        .put(
            "text",
            missing
                ? "Authentication is required to use this tool."
                : "The access token is invalid or expired.");
    result.putObject("_meta").putArray("mcp/www_authenticate").add(challenge);
    result.put("isError", true);
    send(exchange, 200, body);
  }

  private static boolean isToolCall(JsonNode request) {
    JsonNode id = request.get("id");
    return "2.0".equals(request.path("jsonrpc").asText())
        && "tools/call".equals(request.path("method").asText())
        && id != null
        && (id.isIntegralNumber()
            || (id.isTextual()
                && !id.textValue().isEmpty()
                && id.textValue().length() <= 128
                && id.textValue().chars().noneMatch(Character::isISOControl)));
  }

  private void authenticationError(HttpExchange exchange, AuthenticationException exception)
      throws IOException {
    if (DEPENDENCY_ERRORS.contains(exception.code())) {
      exchange
          .getResponseHeaders()
          .set(
              "WWW-Authenticate",
              BearerChallenge.missingToken(resourceMetadataUri, requiredScopes));
      sendError(
          exchange,
          503,
          "AUTHENTICATION_UNAVAILABLE",
          "Connector authentication is temporarily unavailable.");
      return;
    }
    boolean missing = "MISSING_BEARER_TOKEN".equals(exception.code());
    exchange
        .getResponseHeaders()
        .set(
            "WWW-Authenticate",
            missing
                ? BearerChallenge.missingToken(resourceMetadataUri, requiredScopes)
                : BearerChallenge.invalidToken(resourceMetadataUri, requiredScopes));
    sendError(
        exchange,
        401,
        missing ? "AUTHENTICATION_REQUIRED" : "INVALID_ACCESS_TOKEN",
        missing
            ? "A Bearer access token is required."
            : "The Bearer access token is not valid for this resource.");
  }

  private JsonNode readJsonRequest(HttpExchange exchange) throws IOException {
    if (!jsonContentType(uniqueHeader(exchange, "Content-Type"))) {
      sendError(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", "The MCP request must be JSON.");
      return null;
    }
    byte[] bytes;
    try (InputStream input = exchange.getRequestBody()) {
      bytes = input.readNBytes(MAXIMUM_REQUEST_BYTES + 1);
    }
    if (bytes.length > MAXIMUM_REQUEST_BYTES) {
      sendError(exchange, 413, "REQUEST_TOO_LARGE", "The MCP request exceeds the size limit.");
      return null;
    }
    try {
      JsonNode request = STRICT_MAPPER.readTree(bytes);
      if (request == null || !request.isObject()) {
        sendError(exchange, 400, "MALFORMED_JSON", "The MCP request JSON is malformed.");
        return null;
      }
      return request;
    } catch (IOException exception) {
      sendError(exchange, 400, "MALFORMED_JSON", "The MCP request JSON is malformed.");
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
    if (!"/mcp".equals(path)) {
      sendError(exchange, 404, "NOT_FOUND", "The requested resource does not exist.");
      return;
    }
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    exchange.getResponseHeaders().set("Access-Control-Max-Age", "300");
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

  private boolean validOrigin(HttpExchange exchange) {
    String origin = uniqueHeader(exchange, "Origin");
    if (origin == null) return true;
    if (!allowedOrigins.contains(origin)) return false;
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
    exchange.getResponseHeaders().set("Vary", "Origin");
    return true;
  }

  private static String uniqueHeader(HttpExchange exchange, String name) {
    List<String> values = exchange.getRequestHeaders().get(name);
    if (values == null || values.isEmpty()) return null;
    if (values.size() != 1) throw new IllegalArgumentException("Repeated header.");
    String value = values.getFirst();
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("Invalid header.");
    }
    return value;
  }

  private static boolean validContentLength(HttpExchange exchange) {
    String contentLength = uniqueHeader(exchange, "Content-Length");
    if (contentLength == null) return true;
    try {
      long value = Long.parseLong(contentLength);
      return value >= 0 && value <= MAXIMUM_REQUEST_BYTES;
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private static boolean jsonContentType(String value) {
    if (value == null) return false;
    return "application/json"
        .equals(value.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT));
  }

  private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
    exchange.getResponseHeaders().set("Allow", allow);
    sendError(exchange, 405, "METHOD_NOT_ALLOWED", "The HTTP method is not allowed.");
  }

  private static void securityHeaders(HttpExchange exchange) {
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
  }

  private static void sendError(
      HttpExchange exchange, int status, String code, String message) throws IOException {
    ObjectNode body = JsonSupport.MAPPER.createObjectNode();
    ObjectNode error = body.putObject("error");
    error.put("code", code);
    error.put("message", message);
    send(exchange, status, body);
  }

  private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
    if (body == null) {
      exchange.sendResponseHeaders(status, -1);
      return;
    }
    byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
