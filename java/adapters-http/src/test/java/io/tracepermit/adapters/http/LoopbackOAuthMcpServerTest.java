package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.RemoteMcpDispatcher;
import io.tracepermit.adapters.remote.RemoteMcpResponse;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.CredentialFingerprint;
import io.tracepermit.identity.OAuthProtectedResourceMetadata;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class LoopbackOAuthMcpServerTest {
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
  private static final URI RESOURCE = URI.create("https://mcp.example.invalid");
  private static final URI METADATA =
      URI.create("https://mcp.example.invalid/.well-known/oauth-protected-resource");
  private static final ConnectorScope DISCOVER = ConnectorScope.of("aws.logs.discover");
  private static final ConnectorScope READ = ConnectorScope.of("aws.logs.read");

  @Test
  void publishesPublicCatalogAndAuthenticatesEveryToolCallWithoutALocalTokenIssuer()
      throws Exception {
    AtomicInteger authentications = new AtomicInteger();
    AtomicInteger dispatches = new AtomicInteger();
    OAuthProtectedResourceMetadata metadata =
        new OAuthProtectedResourceMetadata(
            RESOURCE,
            "TracePermit simulator MCP",
            List.of(URI.create("https://identity.example.invalid")),
            List.of(DISCOVER, READ),
            List.of("header"));
    try (LoopbackOAuthMcpServer server =
        LoopbackOAuthMcpServer.start(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            METADATA,
            metadata,
            List.of(DISCOVER, READ),
            Set.of("https://chatgpt.com"),
            authorization -> {
              authentications.incrementAndGet();
              if (authorization == null) {
                throw new AuthenticationException(
                    "MISSING_BEARER_TOKEN", "A Bearer token is required.");
              }
              if (!"Bearer valid".equals(authorization)) {
                throw new AuthenticationException(
                    "INVALID_ACCESS_TOKEN", "The Bearer token is invalid.");
              }
              return connector();
            },
            new RemoteMcpDispatcher() {
              @Override
              public RemoteMcpResponse dispatch(
                  JsonNode request, AuthenticatedConnector connector) {
                dispatches.incrementAndGet();
                ObjectNode body = JsonSupport.MAPPER.createObjectNode();
                body.put("jsonrpc", "2.0");
                body.set("id", request.path("id").deepCopy());
                body.putObject("result").put("authenticatedSubject", connector.subjectId());
                return new RemoteMcpResponse(200, body);
              }

              @Override
              public Optional<RemoteMcpResponse> dispatchPublic(JsonNode request) {
                String method = request.path("method").asText();
                if (!Set.of("server/discover", "tools/list").contains(method)) {
                  return Optional.empty();
                }
                ObjectNode body = JsonSupport.MAPPER.createObjectNode();
                body.put("jsonrpc", "2.0");
                body.set("id", request.path("id").deepCopy());
                ObjectNode result = body.putObject("result");
                if ("server/discover".equals(method)) {
                  result.put("resultType", "complete");
                  result.putArray("supportedVersions").add("2026-07-28");
                } else {
                  result.putArray("tools").addObject().put("name", "public.tool");
                }
                return Optional.of(new RemoteMcpResponse(200, body));
              }
            },
            () -> SecurityReadiness.allReady(NOW))) {
      HttpResponse<String> discovery =
          get(server, "/.well-known/oauth-protected-resource/mcp");
      assertThat(discovery.statusCode()).isEqualTo(200);
      assertThat(discovery.body())
          .contains(
              "https://mcp.example.invalid",
              "https://identity.example.invalid",
              "aws.logs.read");

      HttpResponse<String> tokenEndpoint = get(server, "/dev/oauth/token");
      assertThat(tokenEndpoint.statusCode()).isEqualTo(404);

      HttpResponse<String> catalog =
          post(
              server,
              null,
              "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}",
              null);
      assertThat(catalog.statusCode()).isEqualTo(200);
      assertThat(catalog.body()).contains("public.tool");
      assertThat(authentications).hasValue(0);
      assertThat(dispatches).hasValue(0);

      HttpResponse<String> modernDiscovery =
          post(
              server,
              null,
              """
              {"jsonrpc":"2.0","id":"discover-1","method":"server/discover","params":{
                "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}
              """,
              null);
      assertThat(modernDiscovery.statusCode()).isEqualTo(200);
      assertThat(modernDiscovery.body()).contains("complete", "2026-07-28");
      assertThat(authentications).hasValue(0);
      assertThat(dispatches).hasValue(0);

      HttpResponse<String> malformed = post(server, null, "not-json", null);
      assertThat(malformed.statusCode()).isEqualTo(400);
      assertThat(authentications).hasValue(0);
      assertThat(dispatches).hasValue(0);

      HttpResponse<String> missing =
          post(
              server,
              null,
              """
              {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"public.tool","arguments":{}}}
              """,
              null);
      assertThat(missing.statusCode()).isEqualTo(200);
      assertThat(missing.body())
          .contains(
              "mcp/www_authenticate",
              "insufficient_scope",
              "error_description",
              METADATA.toString())
          .doesNotContain("authenticatedSubject");
      assertThat(dispatches).hasValue(0);

      HttpResponse<String> invalid =
          post(
              server,
              "Bearer invalid",
              """
              {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                "name":"public.tool","arguments":{}}}
              """,
              null);
      assertThat(invalid.statusCode()).isEqualTo(200);
      assertThat(invalid.body())
          .contains("mcp/www_authenticate", "invalid_token", "error_description")
          .doesNotContain("authenticatedSubject");
      assertThat(dispatches).hasValue(0);

      HttpResponse<String> protectedUnknown =
          post(
              server,
              null,
              "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"unknown\",\"params\":{}}",
              null);
      assertThat(protectedUnknown.statusCode()).isEqualTo(401);
      assertThat(protectedUnknown.headers().firstValue("WWW-Authenticate"))
          .hasValueSatisfying(
              value -> assertThat(value).contains(METADATA.toString(), "aws.logs.discover"));

      HttpResponse<String> accepted =
          post(
              server,
              "Bearer valid",
              "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\",\"params\":{}}",
              "https://chatgpt.com");
      assertThat(accepted.statusCode()).isEqualTo(200);
      assertThat(accepted.body()).contains("https://identity.example.invalid#user-123");
      assertThat(dispatches).hasValue(1);

      HttpResponse<String> wrongOrigin =
          post(
              server,
              "Bearer valid",
              "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ping\",\"params\":{}}",
              "https://attacker.example");
      assertThat(wrongOrigin.statusCode()).isEqualTo(403);
      assertThat(dispatches).hasValue(1);
      assertThat(authentications).hasValue(4);
    }
  }

  @Test
  void rejectsUnsafeListenerAndMetadataConfiguration() throws Exception {
    OAuthProtectedResourceMetadata metadata = metadata();

    assertThatThrownBy(
            () ->
                start(
                    new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 0),
                    METADATA,
                    List.of(DISCOVER),
                    metadata,
                    ignored -> connector(),
                    (request, connector) -> new RemoteMcpResponse(204, null),
                    () -> SecurityReadiness.allReady(NOW)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("loopback");
    assertThatThrownBy(
            () ->
                start(
                    InetSocketAddress.createUnresolved("localhost", 0),
                    METADATA,
                    List.of(DISCOVER),
                    metadata,
                    ignored -> connector(),
                    (request, connector) -> new RemoteMcpResponse(204, null),
                    () -> SecurityReadiness.allReady(NOW)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("loopback");

    for (String unsafe :
        List.of(
            "http://mcp.example.invalid/.well-known/oauth-protected-resource",
            "https:/missing-host",
            "https://user@mcp.example.invalid/metadata",
            "https://mcp.example.invalid/metadata?query=true",
            "https://mcp.example.invalid/metadata#fragment")) {
      assertThatThrownBy(
              () ->
                  start(
                      loopback(),
                      URI.create(unsafe),
                      List.of(DISCOVER),
                      metadata,
                      ignored -> connector(),
                      (request, connector) -> new RemoteMcpResponse(204, null),
                      () -> SecurityReadiness.allReady(NOW)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("safe HTTPS");
    }

    assertThatThrownBy(
            () ->
                start(
                    loopback(),
                    METADATA,
                    List.of(),
                    metadata,
                    ignored -> connector(),
                    (request, connector) -> new RemoteMcpResponse(204, null),
                    () -> SecurityReadiness.allReady(NOW)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OAuth scope");
  }

  @Test
  void exposesOnlyBoundedPublicTransportSurface() throws Exception {
    try (LoopbackOAuthMcpServer server =
        start(
            loopback(),
            METADATA,
            List.of(DISCOVER, READ),
            metadata(),
            ignored -> connector(),
            (request, connector) -> new RemoteMcpResponse(204, null),
            () -> SecurityReadiness.allReady(NOW))) {
      HttpResponse<String> health = get(server, "/health");
      assertThat(health.statusCode()).isEqualTo(200);
      assertThat(health.headers().firstValue("Cache-Control")).contains("no-store");

      HttpResponse<String> rootMetadata =
          get(server, "/.well-known/oauth-protected-resource");
      assertThat(rootMetadata.statusCode()).isEqualTo(200);

      HttpResponse<String> preflight =
          request(server, "OPTIONS", "/mcp", null, null, "https://chatgpt.com", null);
      assertThat(preflight.statusCode()).isEqualTo(204);
      assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin"))
          .contains("https://chatgpt.com");
      assertThat(preflight.headers().firstValue("Access-Control-Allow-Methods"))
          .contains("POST, OPTIONS");

      assertThat(request(server, "OPTIONS", "/health", null, null, null, null).statusCode())
          .isEqualTo(404);
      assertThat(request(server, "POST", "/health", null, "{}", null, "application/json").statusCode())
          .isEqualTo(405);
      assertThat(request(server, "POST", "/ready", null, "{}", null, "application/json").statusCode())
          .isEqualTo(405);
      assertThat(
              request(
                      server,
                      "POST",
                      "/.well-known/oauth-protected-resource",
                      null,
                      "{}",
                      null,
                      "application/json")
                  .statusCode())
          .isEqualTo(405);
      assertThat(request(server, "GET", "/mcp", null, null, null, null).statusCode())
          .isEqualTo(405);
      assertThat(get(server, "/health?unexpected=true").statusCode()).isEqualTo(400);
    }
  }

  @Test
  void failsClosedAcrossReadinessAuthenticationParsingAndAuthorization() throws Exception {
    AtomicReference<String> outcome = new AtomicReference<>("accepted");
    AtomicInteger authentications = new AtomicInteger();
    try (LoopbackOAuthMcpServer server =
        start(
            loopback(),
            METADATA,
            List.of(DISCOVER, READ),
            metadata(),
            authorization -> {
              authentications.incrementAndGet();
              if ("Bearer dependency".equals(authorization)) {
                throw new AuthenticationException(
                    "OIDC_KEY_SET_UNAVAILABLE", "The key set is unavailable.");
              }
              if (!"Bearer valid".equals(authorization)) {
                throw new AuthenticationException(
                    "INVALID_ACCESS_TOKEN", "The access token is invalid.");
              }
              return connector();
            },
            (request, connector) -> {
              if ("denied".equals(outcome.get())) {
                throw new AuthenticationException("INSUFFICIENT_SCOPE", "Scope is missing.");
              }
              if ("empty".equals(outcome.get())) return new RemoteMcpResponse(204, null);
              ObjectNode body = JsonSupport.MAPPER.createObjectNode();
              body.put("ok", true);
              return new RemoteMcpResponse(200, body);
            },
            () -> SecurityReadiness.allReady(NOW))) {
      HttpResponse<String> invalid = post(server, "Bearer invalid", "{}", null);
      assertThat(invalid.statusCode()).isEqualTo(401);
      assertThat(invalid.body()).contains("INVALID_ACCESS_TOKEN");
      assertThat(invalid.headers().firstValue("WWW-Authenticate"))
          .hasValueSatisfying(value -> assertThat(value).contains("invalid_token"));

      HttpResponse<String> dependency = post(server, "Bearer dependency", "{}", null);
      assertThat(dependency.statusCode()).isEqualTo(503);
      assertThat(dependency.body()).contains("AUTHENTICATION_UNAVAILABLE");

      assertThat(
              request(server, "POST", "/mcp", "Bearer valid", "{}", null, "text/plain")
                  .statusCode())
          .isEqualTo(415);
      assertThat(post(server, "Bearer valid", "[]", null).statusCode()).isEqualTo(400);
      assertThat(post(server, "Bearer valid", "", null).statusCode()).isEqualTo(400);
      assertThat(post(server, "Bearer valid", "x".repeat(65_537), null).statusCode())
          .isEqualTo(413);

      outcome.set("denied");
      assertThat(post(server, "Bearer valid", "{}", null).statusCode()).isEqualTo(403);
      outcome.set("empty");
      assertThat(post(server, "Bearer valid", "{}", null).statusCode()).isEqualTo(204);
      assertThat(authentications).hasValue(4);
    }
  }

  @Test
  void reportsUnavailableReadinessBeforeAuthenticationAndContainsSupplierFailure() throws Exception {
    AtomicInteger authentications = new AtomicInteger();
    try (LoopbackOAuthMcpServer server =
        start(
            loopback(),
            METADATA,
            List.of(DISCOVER, READ),
            metadata(),
            ignored -> {
              authentications.incrementAndGet();
              return connector();
            },
            (request, connector) -> new RemoteMcpResponse(204, null),
            () -> SecurityReadiness.forRuntime(false, NOW))) {
      assertThat(get(server, "/ready").statusCode()).isEqualTo(503);
      HttpResponse<String> protectedRequest = post(server, "Bearer valid", "{}", null);
      assertThat(protectedRequest.statusCode()).isEqualTo(503);
      assertThat(protectedRequest.body()).contains("SECURITY_DEPENDENCY_UNAVAILABLE");
      assertThat(authentications).hasValue(0);
    }

    try (LoopbackOAuthMcpServer server =
        start(
            loopback(),
            METADATA,
            List.of(DISCOVER, READ),
            metadata(),
            ignored -> connector(),
            (request, connector) -> new RemoteMcpResponse(204, null),
            () -> {
              throw new IllegalStateException("unavailable");
            })) {
      HttpResponse<String> response = get(server, "/ready");
      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.body()).contains("INTERNAL_ERROR");
    }
  }

  @Test
  void emitsOnlyBoundedMcpMethodToolRouteAndStatusDiagnostics() throws Exception {
    ByteArrayOutputStream diagnosticBytes = new ByteArrayOutputStream();
    try (PrintStream diagnostics =
            new PrintStream(diagnosticBytes, true, StandardCharsets.UTF_8);
        LoopbackOAuthMcpServer server =
            LoopbackOAuthMcpServer.start(
                loopback(),
                METADATA,
                metadata(),
                List.of(DISCOVER, READ),
                Set.of("https://chatgpt.com"),
                ignored -> connector(),
                new RemoteMcpDispatcher() {
                  @Override
                  public RemoteMcpResponse dispatch(
                      JsonNode request, AuthenticatedConnector connector) {
                    return new RemoteMcpResponse(204, null);
                  }

                  @Override
                  public Optional<RemoteMcpResponse> dispatchPublic(JsonNode request) {
                    if (!"initialize".equals(request.path("method").asText())) {
                      return Optional.empty();
                    }
                    return Optional.of(new RemoteMcpResponse(200, JsonSupport.MAPPER.createObjectNode()));
                  }
                },
                () -> SecurityReadiness.allReady(NOW),
                diagnostics)) {
      assertThat(
              post(
                      server,
                      null,
                      """
                      {"jsonrpc":"2.0","id":"secret-request-id","method":"initialize","params":{
                        "token":"secret-bearer-value","email":"person@example.com"}}
                      """,
                      null)
                  .statusCode())
          .isEqualTo(200);

      assertThat(
              post(
                      server,
                      "Bearer secret-bearer-value",
                      """
                      {"jsonrpc":"2.0","id":"secret-request-id","method":"tools/call","params":{
                        "name":"tracepermit.get_profile","arguments":{
                          "subject":"person@example.com","token":"secret-bearer-value"}}}
                      """,
                      null)
                  .statusCode())
          .isEqualTo(204);

      assertThat(
              post(
                      server,
                      "Bearer secret-bearer-value",
                      """
                      {"jsonrpc":"2.0","id":3,"method":"secret-bearer-value","params":{
                        "name":"person@example.com","arguments":{}}}
                      """,
                      null)
                  .statusCode())
          .isEqualTo(204);
    }

    String output = diagnosticBytes.toString(StandardCharsets.UTF_8);
    assertThat(output)
        .contains(
            "stage=REQUEST method=INITIALIZE tool=NONE route=RECEIVED status=NONE",
            "stage=RESPONSE method=INITIALIZE tool=NONE route=PUBLIC status=200",
            "stage=REQUEST method=TOOLS_CALL tool=TRACEPERMIT_GET_PROFILE route=RECEIVED status=NONE",
            "stage=RESPONSE method=TOOLS_CALL tool=TRACEPERMIT_GET_PROFILE route=PROTECTED status=204",
            "stage=REQUEST method=UNKNOWN tool=NONE route=RECEIVED status=NONE")
        .doesNotContain(
            "secret-bearer-value", "secret-request-id", "person@example.com", "subject", "token");
  }

  private static AuthenticatedConnector connector() {
    return new AuthenticatedConnector(
        "https://identity.example.invalid#user-123",
        "Demo User",
        "tenant:demo",
        ConnectorOrigin.CHATGPT_WEB,
        "https://chatgpt.com/oauth/client.json",
        "ChatGPT hosted client",
        "https://identity.example.invalid",
        NOW,
        "oauth-access-token-jwt-rs256-v1",
        "key-1",
        new CredentialFingerprint("sha256:" + "a".repeat(64)),
        List.of(DISCOVER, READ),
        NOW.plusSeconds(300));
  }

  private static OAuthProtectedResourceMetadata metadata() {
    return new OAuthProtectedResourceMetadata(
        RESOURCE,
        "TracePermit simulator MCP",
        List.of(URI.create("https://identity.example.invalid")),
        List.of(DISCOVER, READ),
        List.of("header"));
  }

  private static InetSocketAddress loopback() {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
  }

  private static LoopbackOAuthMcpServer start(
      InetSocketAddress address,
      URI metadataUri,
      List<ConnectorScope> scopes,
      OAuthProtectedResourceMetadata metadata,
      io.tracepermit.identity.ConnectorAuthenticator authenticator,
      io.tracepermit.adapters.remote.RemoteMcpDispatcher dispatcher,
      java.util.function.Supplier<SecurityReadiness> readiness) {
    return LoopbackOAuthMcpServer.start(
        address,
        metadataUri,
        metadata,
        scopes,
        Set.of("https://chatgpt.com"),
        authenticator,
        dispatcher,
        readiness);
  }

  private static HttpResponse<String> get(LoopbackOAuthMcpServer server, String path)
      throws Exception {
    HttpRequest request = HttpRequest.newBuilder(url(server, path)).GET().build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(
      LoopbackOAuthMcpServer server, String authorization, String body, String origin)
      throws Exception {
    return request(server, "POST", "/mcp", authorization, body, origin, "application/json");
  }

  private static HttpResponse<String> request(
      LoopbackOAuthMcpServer server,
      String method,
      String path,
      String authorization,
      String body,
      String origin,
      String contentType)
      throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(url(server, path));
    if (authorization != null) request.header("Authorization", authorization);
    if (origin != null) request.header("Origin", origin);
    if (contentType != null) request.header("Content-Type", contentType);
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return HttpClient.newHttpClient()
        .send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static URI url(LoopbackOAuthMcpServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }
}
