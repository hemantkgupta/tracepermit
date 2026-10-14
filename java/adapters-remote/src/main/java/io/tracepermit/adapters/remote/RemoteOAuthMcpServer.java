package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.application.SecurityTelemetry;
import io.tracepermit.application.SecurityTelemetryEvent;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.BearerChallenge;
import io.tracepermit.identity.ConnectorAuthenticator;
import io.tracepermit.identity.OAuthProtectedResourceMetadata;
import io.tracepermit.identity.TokenSenderConstraint;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;

/** TLS-only OAuth edge that authenticates before parsing or dispatching MCP JSON-RPC. */
public final class RemoteOAuthMcpServer implements AutoCloseable {
  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final String METADATA_PATH = "/.well-known/oauth-protected-resource";
  private static final String MCP_PATH = "/mcp";
  private static final List<String> DEPENDENCY_ERRORS =
      List.of("OIDC_CONFIGURATION_UNAVAILABLE", "OIDC_KEY_SET_UNAVAILABLE");

  private final HttpsServer server;
  private final ExecutorService executor;
  private final RemoteOAuthEndpointConfiguration configuration;
  private final OAuthProtectedResourceMetadata metadata;
  private final ConnectorAuthenticator authenticator;
  private final RemoteMcpDispatcher dispatcher;
  private final boolean requireMutualTlsSenderConstraint;
  private final RemoteRateLimiter networkRateLimiter;
  private final RemoteRateLimiter principalRateLimiter;
  private final Supplier<RemoteEdgeReadiness> readiness;
  private final SecurityTelemetry telemetry;

  private RemoteOAuthMcpServer(
      HttpsServer server,
      ExecutorService executor,
      RemoteOAuthEndpointConfiguration configuration,
      OAuthProtectedResourceMetadata metadata,
      ConnectorAuthenticator authenticator,
      RemoteMcpDispatcher dispatcher,
      boolean requireMutualTlsSenderConstraint,
      RemoteRateLimiter networkRateLimiter,
      RemoteRateLimiter principalRateLimiter,
      Supplier<RemoteEdgeReadiness> readiness,
      SecurityTelemetry telemetry) {
    this.server = server;
    this.executor = executor;
    this.configuration = configuration;
    this.metadata = metadata;
    this.authenticator = authenticator;
    this.dispatcher = dispatcher;
    this.requireMutualTlsSenderConstraint = requireMutualTlsSenderConstraint;
    this.networkRateLimiter = networkRateLimiter;
    this.principalRateLimiter = principalRateLimiter;
    this.readiness = readiness;
    this.telemetry = telemetry;
  }

  /** Test-only profile: never permit the permissive foundation defaults on a network interface. */
  static RemoteOAuthMcpServer startForLoopbackTests(
      InetSocketAddress address,
      SSLContext tlsContext,
      RemoteOAuthEndpointConfiguration configuration,
      OAuthProtectedResourceMetadata metadata,
      ConnectorAuthenticator authenticator,
      RemoteMcpDispatcher dispatcher) {
    Objects.requireNonNull(address);
    if (address.getAddress() == null || !address.getAddress().isLoopbackAddress()) {
      throw new IllegalArgumentException("The test-only remote MCP profile requires loopback binding.");
    }
    return start(
        address,
        tlsContext,
        configuration,
        metadata,
        authenticator,
        dispatcher,
        false,
        RemoteRateLimiter.allowAllForExplicitFoundationTests(),
        RemoteRateLimiter.allowAllForExplicitFoundationTests(),
        () -> RemoteEdgeReadiness.allReady(Instant.EPOCH),
        SecurityTelemetry.discardForExplicitDevelopmentTests());
  }

  /**
   * Starts the hardened direct-TLS profile. The supplied TLS context must trust the configured
   * connector client certificates; the access token must carry the matching RFC 8705 thumbprint.
   */
  public static RemoteOAuthMcpServer startHardened(
      InetSocketAddress address,
      SSLContext tlsContext,
      RemoteOAuthEndpointConfiguration configuration,
      OAuthProtectedResourceMetadata metadata,
      ConnectorAuthenticator authenticator,
      RemoteMcpDispatcher dispatcher,
      RemoteRateLimiter networkRateLimiter,
      RemoteRateLimiter principalRateLimiter,
      Supplier<RemoteEdgeReadiness> readiness,
      SecurityTelemetry telemetry) {
    return start(
        address,
        tlsContext,
        configuration,
        metadata,
        authenticator,
        dispatcher,
        true,
        networkRateLimiter,
        principalRateLimiter,
        readiness,
        telemetry);
  }

  private static RemoteOAuthMcpServer start(
      InetSocketAddress address,
      SSLContext tlsContext,
      RemoteOAuthEndpointConfiguration configuration,
      OAuthProtectedResourceMetadata metadata,
      ConnectorAuthenticator authenticator,
      RemoteMcpDispatcher dispatcher,
      boolean requireMutualTlsSenderConstraint,
      RemoteRateLimiter networkRateLimiter,
      RemoteRateLimiter principalRateLimiter,
      Supplier<RemoteEdgeReadiness> readiness,
      SecurityTelemetry telemetry) {
    Objects.requireNonNull(address);
    Objects.requireNonNull(tlsContext);
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(metadata);
    Objects.requireNonNull(authenticator);
    Objects.requireNonNull(dispatcher);
    Objects.requireNonNull(networkRateLimiter);
    Objects.requireNonNull(principalRateLimiter);
    Objects.requireNonNull(readiness);
    Objects.requireNonNull(telemetry);
    if (!configuration.resourceMetadataUri().getPath().equals(METADATA_PATH)) {
      throw new IllegalArgumentException("The initial metadata URI must use the standard path.");
    }
    try {
      HttpsServer server = HttpsServer.create(address, 0);
      server.setHttpsConfigurator(
          new HttpsConfigurator(tlsContext) {
            @Override
            public void configure(HttpsParameters parameters) {
              SSLParameters defaults = getSSLContext().getDefaultSSLParameters();
              defaults.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
              defaults.setWantClientAuth(requireMutualTlsSenderConstraint);
              parameters.setSSLParameters(defaults);
            }
          });
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      RemoteOAuthMcpServer endpoint =
          new RemoteOAuthMcpServer(
              server,
              executor,
              configuration,
              metadata,
              authenticator,
              dispatcher,
              requireMutualTlsSenderConstraint,
              networkRateLimiter,
              principalRateLimiter,
              readiness,
              telemetry);
      server.createContext("/", endpoint::handle);
      server.setExecutor(executor);
      server.start();
      return endpoint;
    } catch (IOException exception) {
      throw new IllegalStateException("The remote OAuth MCP endpoint could not start.", exception);
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
    long startedAt = System.nanoTime();
    try {
      applySecurityHeaders(exchange);
      if (!validHost(exchange)) {
        recordDenied(startedAt, "host", "INVALID_HOST");
        sendError(exchange, 400, "INVALID_HOST", "The request Host is not allowed.");
        return;
      }
      if (!validOrigin(exchange)) {
        recordDenied(startedAt, "origin", "ORIGIN_NOT_ALLOWED");
        sendError(exchange, 403, "ORIGIN_NOT_ALLOWED", "The request Origin is not allowed.");
        return;
      }
      if (exchange.getRequestURI().getRawQuery() != null) {
        recordDenied(startedAt, "request_shape", "INVALID_MCP_REQUEST");
        sendError(exchange, 400, "INVALID_MCP_REQUEST", "Query parameters are not accepted.");
        return;
      }
      if ("OPTIONS".equals(exchange.getRequestMethod())) {
        preflight(exchange);
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (METADATA_PATH.equals(path)) {
        metadata(exchange);
      } else if (MCP_PATH.equals(path)) {
        mcp(exchange);
      } else {
        sendError(exchange, 404, "NOT_FOUND", "The requested resource does not exist.");
      }
    } catch (IllegalArgumentException exception) {
      recordDenied(startedAt, "request_shape", "INVALID_MCP_REQUEST");
      sendError(exchange, 400, "INVALID_MCP_REQUEST", "The MCP request is invalid.");
    } catch (RuntimeException exception) {
      recordDenied(startedAt, "internal", "INTERNAL_ERROR");
      sendError(exchange, 500, "INTERNAL_ERROR", "The remote connector request failed safely.");
    } finally {
      exchange.close();
    }
  }

  private void metadata(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      exchange.getResponseHeaders().set("Allow", "GET");
      sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Only metadata reads are allowed here.");
      return;
    }
    send(exchange, 200, metadata.toJson());
  }

  private void mcp(HttpExchange exchange) throws IOException {
    long startedAt = System.nanoTime();
    if (!"POST".equals(exchange.getRequestMethod())) {
      exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
      sendError(exchange, 405, "METHOD_NOT_ALLOWED", "The MCP endpoint requires POST.");
      return;
    }
    if (!validContentLength(exchange)) {
      recordDenied(startedAt, "content_length", "REQUEST_TOO_LARGE");
      sendError(exchange, 413, "REQUEST_TOO_LARGE", "The MCP request exceeds the size limit.");
      return;
    }
    if (requireMutualTlsSenderConstraint && !ready()) {
      recordDenied(startedAt, "readiness", "REMOTE_EDGE_NOT_READY");
      sendError(
          exchange,
          503,
          "REMOTE_EDGE_NOT_READY",
          "The hardened remote connector is not ready.");
      return;
    }
    if (!rateLimit(
        exchange, networkRateLimiter, networkKey(exchange), "network_rate_limit", startedAt)) {
      return;
    }

    AuthenticatedConnector connector;
    String authorization = uniqueHeader(exchange, "Authorization");
    try {
      connector = authenticator.authenticate(authorization);
    } catch (AuthenticationException exception) {
      recordDenied(startedAt, "authentication", exception.code());
      authenticationError(exchange, exception);
      return;
    }
    if (requireMutualTlsSenderConstraint && !validSenderConstraint(exchange, connector)) {
      recordDenied(startedAt, "sender_constraint", "INVALID_SENDER_CONSTRAINT");
      exchange
          .getResponseHeaders()
          .set(
              "WWW-Authenticate",
              BearerChallenge.invalidToken(
                  configuration.resourceMetadataUri(), configuration.requiredScopes()));
      sendError(
          exchange,
          401,
          "INVALID_SENDER_CONSTRAINT",
          "The access token is not bound to this TLS client.");
      return;
    }
    if (!rateLimit(
        exchange, principalRateLimiter, principalKey(connector), "principal_rate_limit", startedAt)) {
      return;
    }

    if (!isJsonContentType(uniqueHeader(exchange, "Content-Type"))) {
      recordDenied(startedAt, "content_type", "UNSUPPORTED_MEDIA_TYPE");
      sendError(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", "The MCP request must be JSON.");
      return;
    }
    JsonNode request;
    try {
      request = readJson(exchange);
    } catch (RequestTooLargeException exception) {
      recordDenied(startedAt, "body", "REQUEST_TOO_LARGE");
      sendError(exchange, 413, "REQUEST_TOO_LARGE", "The MCP request exceeds the size limit.");
      return;
    }
    if (!recordRequired(
        event(
            startedAt,
            SecurityTelemetryEvent.Type.REMOTE_EDGE_AUTHORIZED,
            SecurityTelemetryEvent.Outcome.ACCEPTED,
            "dispatch",
            "EDGE_AUTHORIZED"))) {
      sendError(
          exchange,
          503,
          "SECURITY_TELEMETRY_UNAVAILABLE",
          "Security telemetry is unavailable; the request was not dispatched.");
      return;
    }
    RemoteMcpResponse response;
    try {
      response = dispatcher.dispatch(request, connector);
    } catch (AuthenticationException exception) {
      recordDenied(startedAt, "authorization", exception.code());
      sendError(exchange, 403, "AUTHORIZATION_DENIED", "The connector lacks required authority.");
      return;
    }
    recordBestEffort(
        event(
            startedAt,
            SecurityTelemetryEvent.Type.REMOTE_EDGE_COMPLETED,
            SecurityTelemetryEvent.Outcome.SUCCEEDED,
            "dispatch",
            "REQUEST_COMPLETED"));
    send(exchange, response.status(), response.body());
  }

  private boolean ready() {
    try {
      RemoteEdgeReadiness snapshot = Objects.requireNonNull(readiness.get());
      return snapshot.ready();
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private boolean rateLimit(
      HttpExchange exchange,
      RemoteRateLimiter limiter,
      String boundedKey,
      String stage,
      long startedAt)
      throws IOException {
    RemoteRateLimitDecision decision;
    try {
      decision = Objects.requireNonNull(limiter.acquire(boundedKey));
    } catch (RuntimeException exception) {
      recordDenied(startedAt, stage, "ABUSE_CONTROL_UNAVAILABLE");
      sendError(
          exchange,
          503,
          "ABUSE_CONTROL_UNAVAILABLE",
          "The remote abuse-control dependency is unavailable.");
      return false;
    }
    if (decision.allowed()) return true;
    recordDenied(startedAt, stage, "RATE_LIMITED");
    exchange
        .getResponseHeaders()
        .set("Retry-After", Long.toString(decision.retryAfterSeconds()));
    sendError(exchange, 429, "RATE_LIMITED", "The remote request rate limit was exceeded.");
    return false;
  }

  private void recordDenied(long startedAt, String stage, String reasonCode) {
    try {
      recordBestEffort(
          event(
              startedAt,
              SecurityTelemetryEvent.Type.REMOTE_EDGE_DENIAL,
              SecurityTelemetryEvent.Outcome.DENIED,
              stage,
              reasonCode));
    } catch (RuntimeException ignored) {
      // Invalid or unavailable denial telemetry cannot replace the safer response.
    }
  }

  private SecurityTelemetryEvent event(
      long startedAt,
      SecurityTelemetryEvent.Type type,
      SecurityTelemetryEvent.Outcome outcome,
      String stage,
      String reasonCode) {
    long elapsed = Math.max(0L, System.nanoTime() - startedAt);
    return new SecurityTelemetryEvent(
        type,
        outcome,
        Duration.ofNanos(elapsed),
        Instant.now(),
        java.util.Map.of(
            "component", "remote_edge", "stage", stage, "reason_code", reasonCode));
  }

  private boolean recordRequired(SecurityTelemetryEvent event) {
    try {
      telemetry.record(event);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private void recordBestEffort(SecurityTelemetryEvent event) {
    try {
      telemetry.record(event);
    } catch (RuntimeException ignored) {
      // A telemetry outage must never turn an already-denied request into an allow.
    }
  }

  private static String networkKey(HttpExchange exchange) {
    byte[] address = exchange.getRemoteAddress().getAddress().getAddress();
    return "network:" + digest(address);
  }

  private static String principalKey(AuthenticatedConnector connector) {
    String input =
        connector.organizationId()
            + "\n"
            + connector.issuer()
            + "\n"
            + connector.subjectId()
            + "\n"
            + connector.clientId();
    return "principal:" + digest(input.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean validSenderConstraint(
      HttpExchange exchange, AuthenticatedConnector connector) {
    if (!(exchange instanceof HttpsExchange httpsExchange)
        || connector.senderConstraint().isEmpty()) {
      return false;
    }
    TokenSenderConstraint constraint = connector.senderConstraint().orElseThrow();
    if (!TokenSenderConstraint.MTLS_X5T_S256_V1.equals(constraint.profile())) return false;
    try {
      var certificates = httpsExchange.getSSLSession().getPeerCertificates();
      if (certificates.length < 1 || !(certificates[0] instanceof X509Certificate certificate)) {
        return false;
      }
      String presented = "sha256:" + digest(certificate.getEncoded());
      return MessageDigest.isEqual(
          presented.getBytes(StandardCharsets.US_ASCII),
          constraint.certificateFingerprint().value().getBytes(StandardCharsets.US_ASCII));
    } catch (SSLPeerUnverifiedException | GeneralSecurityException exception) {
      return false;
    }
  }

  private static String digest(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  private void authenticationError(HttpExchange exchange, AuthenticationException exception)
      throws IOException {
    if (DEPENDENCY_ERRORS.contains(exception.code())) {
      exchange
          .getResponseHeaders()
          .set(
              "WWW-Authenticate",
              BearerChallenge.missingToken(
                  configuration.resourceMetadataUri(), configuration.requiredScopes()));
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
                ? BearerChallenge.missingToken(
                    configuration.resourceMetadataUri(), configuration.requiredScopes())
                : BearerChallenge.invalidToken(
                    configuration.resourceMetadataUri(), configuration.requiredScopes()));
    sendError(
        exchange,
        401,
        missing ? "AUTHENTICATION_REQUIRED" : "INVALID_ACCESS_TOKEN",
        missing
            ? "A Bearer access token is required."
            : "The Bearer access token is not valid for this resource.");
  }

  private JsonNode readJson(HttpExchange exchange) {
    try {
      byte[] body =
          exchange.getRequestBody().readNBytes(configuration.maximumRequestBytes() + 1);
      if (body.length > configuration.maximumRequestBytes()) {
        throw new RequestTooLargeException();
      }
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      JsonNode request = STRICT_MAPPER.readTree(json);
      if (request == null || !request.isObject()) {
        throw new IllegalArgumentException("MCP requests must be JSON objects.");
      }
      return request;
    } catch (RequestTooLargeException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalArgumentException("The MCP request could not be parsed.", exception);
    }
  }

  private boolean validHost(HttpExchange exchange) {
    String authority = uniqueHeader(exchange, "Host");
    if (authority == null || authority.chars().anyMatch(Character::isISOControl)) return false;
    String host;
    int port;
    int separator = authority.lastIndexOf(':');
    if (separator < 1 || authority.indexOf(':') != separator) {
      host = authority.toLowerCase(Locale.ROOT);
      port = 443;
    } else {
      host = authority.substring(0, separator).toLowerCase(Locale.ROOT);
      try {
        port = Integer.parseInt(authority.substring(separator + 1));
      } catch (NumberFormatException exception) {
        return false;
      }
    }
    int expectedPort = port() == 443 ? 443 : port();
    return port == expectedPort && configuration.allowedHostnames().contains(host);
  }

  private boolean validOrigin(HttpExchange exchange) {
    String origin = uniqueHeader(exchange, "Origin");
    if (origin == null) return configuration.allowMissingOrigin();
    try {
      URI value = URI.create(origin);
      if (!configuration.allowedOrigins().contains(value)) return false;
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
      exchange.getResponseHeaders().set("Vary", "Origin");
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private void preflight(HttpExchange exchange) throws IOException {
    if (!MCP_PATH.equals(exchange.getRequestURI().getPath())) {
      sendError(exchange, 404, "NOT_FOUND", "The requested resource does not exist.");
      return;
    }
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
    exchange
        .getResponseHeaders()
        .set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    exchange.getResponseHeaders().set("Access-Control-Max-Age", "300");
    send(exchange, 204, null);
  }

  private boolean validContentLength(HttpExchange exchange) {
    String contentLength = uniqueHeader(exchange, "Content-Length");
    if (contentLength == null) return true;
    try {
      long value = Long.parseLong(contentLength);
      return value >= 0 && value <= configuration.maximumRequestBytes();
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private static String uniqueHeader(HttpExchange exchange, String name) {
    List<String> values = exchange.getRequestHeaders().get(name);
    if (values == null || values.isEmpty()) return null;
    if (values.size() != 1) return "";
    return values.get(0);
  }

  private static boolean isJsonContentType(String value) {
    if (value == null) return false;
    return "application/json"
        .equals(value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT));
  }

  private static void applySecurityHeaders(HttpExchange exchange) {
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

  private static final class RequestTooLargeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
