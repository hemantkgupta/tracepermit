package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.application.SecureExecutionService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.SecurityTelemetry;
import io.tracepermit.application.SecurityTelemetryEvent;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.CachingOidcJwkResolver;
import io.tracepermit.identity.ConnectorAuthenticator;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.OAuthProtectedResourceMetadata;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import io.tracepermit.identity.StaticOidcProviderRegistry;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class RemoteOAuthMcpServerTest {
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final String INITIALIZE =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
          + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
          + "\"clientInfo\":{\"name\":\"connector-test\",\"version\":\"1.0\"}}}";

  @Test
  void permissiveFoundationProfileRejectsNonLoopbackBinding() throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    for (InetSocketAddress address :
        List.of(
            new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0),
            InetSocketAddress.createUnresolved("example.invalid", 0))) {
      assertThatThrownBy(
              () ->
                  RemoteOAuthMcpServer.startForLoopbackTests(
                      address,
                      tls.server().sslContext(),
                      RemoteTestSupport.endpointConfiguration(),
                      metadata(),
                      fixedAuthenticator(),
                      new HandshakeOnlyMcpDispatcher()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("loopback");
    }
  }

  @Test
  void servesPublicMetadataAndAuthenticatedHandshakeOverTls() throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    AtomicInteger dispatches = new AtomicInteger();
    RemoteMcpDispatcher dispatcher =
        (request, connector) -> {
          dispatches.incrementAndGet();
          return new HandshakeOnlyMcpDispatcher().dispatch(request, connector);
        };
    try (RemoteOAuthMcpServer server =
        start(tls, fixedAuthenticator(), dispatcher, RemoteTestSupport.endpointConfiguration())) {
      OkHttpClient client = RemoteTestSupport.client(tls);
      try (Response metadata = client.newCall(get(server, metadataPath())).execute()) {
        assertThat(metadata.code()).isEqualTo(200);
        JsonNode body = JsonSupport.MAPPER.readTree(requiredBody(metadata));
        assertThat(body.path("resource").asText()).isEqualTo(RemoteTestSupport.AUDIENCE);
        assertThat(body.path("bearer_methods_supported").get(0).asText()).isEqualTo("header");
        assertSecurityHeaders(metadata);
      }

      try (Response response =
          client
              .newCall(post(server, "/mcp", INITIALIZE).header("Authorization", "Bearer valid").build())
              .execute()) {
        assertThat(response.code()).isEqualTo(200);
        JsonNode body = JsonSupport.MAPPER.readTree(requiredBody(response));
        assertThat(body.path("result").path("protocolVersion").asText())
            .isEqualTo("2025-06-18");
        assertThat(body.toString()).doesNotContain("Bearer valid", "tenant:acme", "sha256:");
        assertSecurityHeaders(response);
      }
      assertThat(dispatches).hasValue(1);
    }
  }

  @Test
  void authenticationRunsBeforeBodyParsingAndDispatch() throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    AtomicInteger authentications = new AtomicInteger();
    AtomicInteger dispatches = new AtomicInteger();
    ConnectorAuthenticator authenticator =
        header -> {
          authentications.incrementAndGet();
          if (!"Bearer valid".equals(header)) {
            throw new AuthenticationException(
                "MISSING_BEARER_TOKEN", "A valid Bearer authorization header is required.");
          }
          return RemoteTestSupport.connector(RemoteTestSupport.DISCOVER);
        };
    RemoteMcpDispatcher dispatcher =
        (request, connector) -> {
          dispatches.incrementAndGet();
          return new HandshakeOnlyMcpDispatcher().dispatch(request, connector);
        };
    try (RemoteOAuthMcpServer server =
        start(tls, authenticator, dispatcher, RemoteTestSupport.endpointConfiguration())) {
      OkHttpClient client = RemoteTestSupport.client(tls);
      try (Response missing = client.newCall(post(server, "/mcp", "{not-json").build()).execute()) {
        assertThat(missing.code()).isEqualTo(401);
        assertThat(missing.header("WWW-Authenticate"))
            .contains("resource_metadata=")
            .doesNotContain("invalid_token");
      }
      assertThat(dispatches).hasValue(0);

      try (Response malformed =
          client
              .newCall(
                  post(server, "/mcp", "{not-json")
                      .header("Authorization", "Bearer valid")
                      .build())
              .execute()) {
        assertThat(malformed.code()).isEqualTo(400);
      }
      try (Response duplicate =
          client
              .newCall(
                  post(
                          server,
                          "/mcp",
                          "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")
                      .header("Authorization", "Bearer valid")
                      .build())
              .execute()) {
        assertThat(duplicate.code()).isEqualTo(400);
      }
      assertThat(authentications).hasValue(3);
      assertThat(dispatches).hasValue(0);
    }
  }

  @Test
  void hostOriginPreflightAndMethodPoliciesFailClosedBeforeAuthentication()
      throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    AtomicInteger authentications = new AtomicInteger();
    ConnectorAuthenticator authenticator =
        header -> {
          authentications.incrementAndGet();
          return RemoteTestSupport.connector(RemoteTestSupport.DISCOVER);
        };
    try (RemoteOAuthMcpServer server =
        start(
            tls,
            authenticator,
            new HandshakeOnlyMcpDispatcher(),
            RemoteTestSupport.endpointConfiguration())) {
      OkHttpClient client = RemoteTestSupport.client(tls);
      try (Response badHost =
          client
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Host", "attacker.invalid:" + server.port())
                      .header("Authorization", "Bearer valid")
                      .build())
              .execute()) {
        assertThat(badHost.code()).isEqualTo(400);
      }
      try (Response badOrigin =
          client
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Origin", "https://attacker.invalid")
                      .header("Authorization", "Bearer valid")
                      .build())
              .execute()) {
        assertThat(badOrigin.code()).isEqualTo(403);
      }
      Request preflight =
          new Request.Builder()
              .url(url(server, "/mcp"))
              .header("Origin", "https://chatgpt.com")
              .method("OPTIONS", null)
              .build();
      try (Response response = client.newCall(preflight).execute()) {
        assertThat(response.code()).isEqualTo(204);
        assertThat(response.header("Access-Control-Allow-Origin"))
            .isEqualTo("https://chatgpt.com");
        assertThat(response.header("Access-Control-Allow-Methods")).isEqualTo("POST, OPTIONS");
      }
      try (Response wrongMethod = client.newCall(get(server, "/mcp")).execute()) {
        assertThat(wrongMethod.code()).isEqualTo(405);
        assertThat(wrongMethod.header("Allow")).isEqualTo("POST, OPTIONS");
      }
      try (Response metadataMutation =
          client.newCall(post(server, metadataPath(), "{}").build()).execute()) {
        assertThat(metadataMutation.code()).isEqualTo(405);
        assertThat(metadataMutation.header("Allow")).isEqualTo("GET");
      }
      try (Response query = client.newCall(get(server, metadataPath() + "?unsafe=true")).execute()) {
        assertThat(query.code()).isEqualTo(400);
      }
      assertThat(authentications).hasValue(0);
    }
  }

  @Test
  void invalidAndUnavailableCredentialsReturnSafeChallengesWithoutDispatch()
      throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    String secretToken = "secret-token-material";
    ConnectorAuthenticator authenticator =
        header -> {
          if (header == null) {
            throw new AuthenticationException("MISSING_BEARER_TOKEN", "missing");
          }
          if (header.endsWith("dependency-trigger")) {
            throw new AuthenticationException("OIDC_KEY_SET_UNAVAILABLE", "jwks-secret");
          }
          throw new AuthenticationException("INVALID_ACCESS_TOKEN", "signature-secret");
        };
    AtomicInteger dispatches = new AtomicInteger();
    try (RemoteOAuthMcpServer server =
        start(
            tls,
            authenticator,
            (request, connector) -> {
              dispatches.incrementAndGet();
              return new HandshakeOnlyMcpDispatcher().dispatch(request, connector);
            },
            RemoteTestSupport.endpointConfiguration())) {
      OkHttpClient client = RemoteTestSupport.client(tls);
      try (Response invalid =
          client
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer " + secretToken)
                      .build())
              .execute()) {
        assertThat(invalid.code()).isEqualTo(401);
        assertThat(invalid.header("WWW-Authenticate")).contains("error=\"invalid_token\"");
        assertThat(requiredBody(invalid))
            .doesNotContain(secretToken, "signature-secret", "jwks-secret");
      }
      try (Response unavailable =
          client
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer dependency-trigger")
                      .build())
              .execute()) {
        assertThat(unavailable.code()).isEqualTo(503);
        assertThat(requiredBody(unavailable))
            .contains("AUTHENTICATION_UNAVAILABLE")
            .doesNotContain("dependency-trigger", "jwks-secret");
      }
      assertThat(dispatches).hasValue(0);
    }
  }

  @Test
  void mediaTypeBodyLimitAndDiscoveryAuthorizationFailClosed() throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    RemoteOAuthEndpointConfiguration small =
        new RemoteOAuthEndpointConfiguration(
            Set.of("localhost"),
            Set.of(URI.create("https://chatgpt.com")),
            true,
            URI.create(RemoteTestSupport.AUDIENCE + metadataPath()),
            List.of(RemoteTestSupport.DISCOVER),
            1_024);
    ConnectorAuthenticator readOnly =
        ignored -> RemoteTestSupport.connector(RemoteTestSupport.READ);
    try (RemoteOAuthMcpServer server =
        start(tls, readOnly, new HandshakeOnlyMcpDispatcher(), small)) {
      OkHttpClient client = RemoteTestSupport.client(tls);
      Request text =
          new Request.Builder()
              .url(url(server, "/mcp"))
              .header("Authorization", "Bearer valid")
              .post(RequestBody.create("{}", MediaType.get("text/plain")))
              .build();
      try (Response response = client.newCall(text).execute()) {
        assertThat(response.code()).isEqualTo(415);
      }
      try (Response response =
          client
              .newCall(
                  post(server, "/mcp", "x".repeat(1_025))
                      .header("Authorization", "Bearer valid")
                      .build())
              .execute()) {
        assertThat(response.code()).isEqualTo(413);
      }
      try (Response response =
          client
              .newCall(
                  post(
                          server,
                          "/mcp",
                          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
                      .header("Authorization", "Bearer valid")
                      .build())
              .execute()) {
        assertThat(response.code()).isEqualTo(403);
        assertThat(requiredBody(response)).contains("AUTHORIZATION_DENIED");
      }
    }
  }

  @Test
  void liveJwksOidcTlsAndFullEnforcementComposeEndToEnd() throws Exception {
    RemoteTestSupport.MutualTlsMaterial tls = RemoteTestSupport.mutualTlsMaterial();
    KeyPair signingKey = RemoteTestSupport.rsaKeyPair();
    try (MockWebServer jwksServer = new MockWebServer()) {
      jwksServer.useHttps(tls.server().sslSocketFactory(), false);
      jwksServer.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/jwk-set+json")
              .setBody(jwks(signingKey)));
      jwksServer.start();
      URI jwksUri = jwksServer.url("/jwks").uri();
      OidcProviderConfiguration provider = provider(jwksUri);
      SsrfSafeHttpsJwkSetSource source =
          new SsrfSafeHttpsJwkSetSource(
              Set.of(jwksUri),
              Duration.ofSeconds(1),
              Duration.ofSeconds(2),
              ignored -> List.of(InetAddress.getByName("127.0.0.1")),
              ignored -> true,
              tls.client().sslSocketFactory(),
              tls.client().trustManager());
      OidcAccessTokenVerifier verifier =
          new OidcAccessTokenVerifier(
              new StaticOidcProviderRegistry(List.of(provider)),
              new CachingOidcJwkResolver(source),
              Clock.fixed(RemoteTestSupport.NOW, ZoneOffset.UTC));
      try (RemoteOAuthMcpServer endpoint =
          startHardened(
              tls,
              verifier,
              enforcingDispatcher(),
              new InMemoryFixedWindowRateLimiter(
                  100,
                  Duration.ofMinutes(1),
                  100,
                  Clock.fixed(RemoteTestSupport.NOW, ZoneOffset.UTC)),
              new InMemoryFixedWindowRateLimiter(
                  100,
                  Duration.ofMinutes(1),
                  100,
                  Clock.fixed(RemoteTestSupport.NOW, ZoneOffset.UTC)),
              () -> RemoteEdgeReadiness.allReady(RemoteTestSupport.NOW))) {
        OkHttpClient client = RemoteTestSupport.client(tls.client());
        String token = RemoteTestSupport.token(signingKey, tls.clientCertificate());
        try (Response response =
            client
                .newCall(
                    post(endpoint, "/mcp", INITIALIZE)
                        .header("Authorization", "Bearer " + token)
                        .header("Origin", "https://claude.ai")
                        .build())
                .execute()) {
          assertThat(response.code()).isEqualTo(200);
          assertThat(requiredBody(response))
              .contains("protocolVersion")
              .doesNotContain(token, "018f47a2-f5ce-7a4b-8f54-80e5d3eaf090");
          assertThat(response.header("Access-Control-Allow-Origin"))
              .isEqualTo("https://claude.ai");
        }
        String call =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"aws.logs.filter_log_events\",\"arguments\":{"
                + "\"logGroupName\":\"/app/prod\",\"filterPattern\":\"ERROR\",\"limit\":20}}}";
        try (Response response =
            client
                .newCall(
                    post(endpoint, "/mcp", call)
                        .header("Authorization", "Bearer " + token)
                        .header("Origin", "https://chatgpt.com")
                        .build())
                .execute()) {
          assertThat(response.code()).isEqualTo(200);
          JsonNode body = JsonSupport.MAPPER.readTree(requiredBody(response));
          JsonNode toolResult =
              JsonSupport.MAPPER.readTree(
                  body.path("result").path("content").get(0).path("text").asText());
          assertThat(toolResult.path("execution").path("status").asText())
              .isEqualTo("executed");
          assertThat(toolResult.path("execution").path("result").path("matchCount").asInt())
              .isEqualTo(2);
          assertThat(body.toString())
              .doesNotContain(token, "018f47a2-f5ce-7a4b-8f54-80e5d3eaf090", "enterprise-acme");
        }
        assertThat(jwksServer.getRequestCount()).isOne();
      }
    }
  }

  @Test
  void hardenedProfileRequiresTheCertificateBoundByTheVerifiedToken() throws Exception {
    RemoteTestSupport.MutualTlsMaterial tls = RemoteTestSupport.mutualTlsMaterial();
    AtomicInteger authentications = new AtomicInteger();
    AtomicInteger dispatches = new AtomicInteger();
    ConnectorAuthenticator authenticator =
        header -> {
          authentications.incrementAndGet();
          if ("Bearer unbound".equals(header)) {
            return RemoteTestSupport.connector(
                RemoteTestSupport.DISCOVER, RemoteTestSupport.READ);
          }
          return RemoteTestSupport.senderConstrainedConnector(
              tls.clientCertificate(), RemoteTestSupport.DISCOVER, RemoteTestSupport.READ);
        };
    RemoteMcpDispatcher dispatcher =
        (request, connector) -> {
          dispatches.incrementAndGet();
          return new HandshakeOnlyMcpDispatcher().dispatch(request, connector);
        };
    try (RemoteOAuthMcpServer server =
        startHardened(
            tls,
            authenticator,
            dispatcher,
            RemoteRateLimiter.allowAllForExplicitFoundationTests(),
            RemoteRateLimiter.allowAllForExplicitFoundationTests(),
            () -> RemoteEdgeReadiness.allReady(RemoteTestSupport.NOW))) {
      OkHttpClient boundClient = RemoteTestSupport.client(tls.client());
      try (Response response =
          boundClient
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer bound")
                      .build())
              .execute()) {
        assertThat(response.code()).isEqualTo(200);
      }

      OkHttpClient otherClient = RemoteTestSupport.client(tls.otherClient());
      try (Response response =
          otherClient
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer stolen-bound-token")
                      .build())
              .execute()) {
        assertThat(response.code()).isEqualTo(401);
        assertThat(response.header("WWW-Authenticate")).contains("invalid_token");
        assertThat(requiredBody(response))
            .contains("INVALID_SENDER_CONSTRAINT")
            .doesNotContain("stolen-bound-token", "sha256:");
      }

      try (Response response =
          boundClient
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer unbound")
                      .build())
              .execute()) {
        assertThat(response.code()).isEqualTo(401);
        assertThat(requiredBody(response)).doesNotContain("unbound");
      }

      OkHttpClient publicClient = RemoteTestSupport.client(tls.publicClient());
      try (Response metadata = publicClient.newCall(get(server, metadataPath())).execute()) {
        assertThat(metadata.code()).isEqualTo(200);
      }
      try (Response response =
          publicClient
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer bound-without-certificate")
                      .build())
              .execute()) {
        assertThat(response.code()).isEqualTo(401);
        assertThat(requiredBody(response)).doesNotContain("bound-without-certificate");
      }
    }
    assertThat(authentications).hasValue(4);
    assertThat(dispatches).hasValue(1);
  }

  @Test
  void hardenedProfileRateLimitsBeforeAndAfterAuthentication() throws Exception {
    RemoteTestSupport.MutualTlsMaterial tls = RemoteTestSupport.mutualTlsMaterial();
    AtomicInteger authentications = new AtomicInteger();
    AtomicInteger dispatches = new AtomicInteger();
    ConnectorAuthenticator authenticator =
        ignored -> {
          authentications.incrementAndGet();
          return RemoteTestSupport.senderConstrainedConnector(
              tls.clientCertificate(), RemoteTestSupport.DISCOVER);
        };
    RemoteMcpDispatcher dispatcher =
        (request, connector) -> {
          dispatches.incrementAndGet();
          return new HandshakeOnlyMcpDispatcher().dispatch(request, connector);
        };
    Clock clock = Clock.fixed(RemoteTestSupport.NOW, ZoneOffset.UTC);
    try (RemoteOAuthMcpServer server =
        startHardened(
            tls,
            authenticator,
            dispatcher,
            new InMemoryFixedWindowRateLimiter(2, Duration.ofMinutes(1), 4, clock),
            new InMemoryFixedWindowRateLimiter(1, Duration.ofMinutes(1), 4, clock),
            () -> RemoteEdgeReadiness.allReady(RemoteTestSupport.NOW))) {
      OkHttpClient client = RemoteTestSupport.client(tls.client());
      try (Response first =
          client
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer valid")
                      .build())
              .execute()) {
        assertThat(first.code()).isEqualTo(200);
      }
      try (Response principalLimited =
          client
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer valid")
                      .build())
              .execute()) {
        assertThat(principalLimited.code()).isEqualTo(429);
        assertThat(principalLimited.header("Retry-After")).isEqualTo("60");
        assertThat(requiredBody(principalLimited)).contains("RATE_LIMITED");
      }
      try (Response networkLimited =
          client
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer should-not-authenticate")
                      .build())
              .execute()) {
        assertThat(networkLimited.code()).isEqualTo(429);
        assertThat(requiredBody(networkLimited)).doesNotContain("should-not-authenticate");
      }
    }
    assertThat(authentications).hasValue(2);
    assertThat(dispatches).hasValue(1);
  }

  @Test
  void hardenedReadinessAndAbuseControlFailuresStopBeforeAuthentication() throws Exception {
    RemoteTestSupport.MutualTlsMaterial tls = RemoteTestSupport.mutualTlsMaterial();
    AtomicInteger authentications = new AtomicInteger();
    ConnectorAuthenticator authenticator =
        ignored -> {
          authentications.incrementAndGet();
          return RemoteTestSupport.senderConstrainedConnector(
              tls.clientCertificate(), RemoteTestSupport.DISCOVER);
        };
    HashMap<String, Boolean> unavailable = new HashMap<>();
    RemoteEdgeReadiness.REQUIRED.forEach(name -> unavailable.put(name, true));
    unavailable.put("durable_enforcement_state", false);
    try (RemoteOAuthMcpServer server =
        startHardened(
            tls,
            authenticator,
            new HandshakeOnlyMcpDispatcher(),
            RemoteRateLimiter.allowAllForExplicitFoundationTests(),
            RemoteRateLimiter.allowAllForExplicitFoundationTests(),
            () -> new RemoteEdgeReadiness(false, RemoteTestSupport.NOW, unavailable))) {
      try (Response response =
          RemoteTestSupport.client(tls.client())
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer readiness-secret")
                      .build())
              .execute()) {
        assertThat(response.code()).isEqualTo(503);
        assertThat(requiredBody(response))
            .contains("REMOTE_EDGE_NOT_READY")
            .doesNotContain("readiness-secret", "durable_enforcement_state");
      }
    }

    try (RemoteOAuthMcpServer server =
        startHardened(
            tls,
            authenticator,
            new HandshakeOnlyMcpDispatcher(),
            ignored -> {
              throw new IllegalStateException("rate-store-secret");
            },
            RemoteRateLimiter.allowAllForExplicitFoundationTests(),
            () -> RemoteEdgeReadiness.allReady(RemoteTestSupport.NOW))) {
      try (Response response =
          RemoteTestSupport.client(tls.client())
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer rate-secret")
                      .build())
              .execute()) {
        assertThat(response.code()).isEqualTo(503);
        assertThat(requiredBody(response))
            .contains("ABUSE_CONTROL_UNAVAILABLE")
            .doesNotContain("rate-store-secret", "rate-secret");
      }
    }
    assertThat(authentications).hasValue(0);
  }

  @Test
  void hardenedTelemetryIsBoundedAndFailureStopsBeforeDispatch() throws Exception {
    RemoteTestSupport.MutualTlsMaterial tls = RemoteTestSupport.mutualTlsMaterial();
    CopyOnWriteArrayList<SecurityTelemetryEvent> events = new CopyOnWriteArrayList<>();
    AtomicInteger dispatches = new AtomicInteger();
    ConnectorAuthenticator authenticator =
        authorization -> {
          if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AuthenticationException(
                "MISSING_BEARER_TOKEN", "A valid Bearer authorization header is required.");
          }
          if ("Bearer malformed-code".equals(authorization)) {
            throw new AuthenticationException("attacker controlled code", "must-not-export");
          }
          return RemoteTestSupport.senderConstrainedConnector(
              tls.clientCertificate(), RemoteTestSupport.DISCOVER);
        };
    RemoteMcpDispatcher dispatcher =
        (request, connector) -> {
          dispatches.incrementAndGet();
          return new HandshakeOnlyMcpDispatcher().dispatch(request, connector);
        };
    try (RemoteOAuthMcpServer server =
        startHardened(
            tls,
            authenticator,
            dispatcher,
            RemoteRateLimiter.allowAllForExplicitFoundationTests(),
            RemoteRateLimiter.allowAllForExplicitFoundationTests(),
            () -> RemoteEdgeReadiness.allReady(RemoteTestSupport.NOW),
            events::add)) {
      try (Response denied =
          RemoteTestSupport.client(tls.client())
              .newCall(post(server, "/mcp", INITIALIZE).build())
              .execute()) {
        assertThat(denied.code()).isEqualTo(401);
      }
      try (Response malformedCode =
          RemoteTestSupport.client(tls.client())
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer malformed-code")
                      .build())
              .execute()) {
        assertThat(malformedCode.code()).isEqualTo(401);
        assertThat(requiredBody(malformedCode)).doesNotContain("must-not-export");
      }
      try (Response allowed =
          RemoteTestSupport.client(tls.client())
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer telemetry-secret")
                      .build())
              .execute()) {
        assertThat(allowed.code()).isEqualTo(200);
      }
    }
    assertThat(events)
        .extracting(SecurityTelemetryEvent::type)
        .containsExactly(
            SecurityTelemetryEvent.Type.REMOTE_EDGE_DENIAL,
            SecurityTelemetryEvent.Type.REMOTE_EDGE_AUTHORIZED,
            SecurityTelemetryEvent.Type.REMOTE_EDGE_COMPLETED);
    assertThat(events.toString())
        .doesNotContain("telemetry-secret", "Authorization", "arguments", "result");
    assertThat(dispatches).hasValue(1);

    try (RemoteOAuthMcpServer server =
        startHardened(
            tls,
            authenticator,
            dispatcher,
            RemoteRateLimiter.allowAllForExplicitFoundationTests(),
            RemoteRateLimiter.allowAllForExplicitFoundationTests(),
            () -> RemoteEdgeReadiness.allReady(RemoteTestSupport.NOW),
            ignored -> {
              throw new IllegalStateException("telemetry-exporter-secret");
            })) {
      try (Response unavailable =
          RemoteTestSupport.client(tls.client())
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer must-not-dispatch")
                      .build())
              .execute()) {
        assertThat(unavailable.code()).isEqualTo(503);
        assertThat(requiredBody(unavailable))
            .contains("SECURITY_TELEMETRY_UNAVAILABLE")
            .doesNotContain("must-not-dispatch", "telemetry-exporter-secret");
      }
    }
    assertThat(dispatches).hasValue(1);
  }

  @Test
  void endpointConfigurationAndMetadataPathAreStrict() throws Exception {
    assertThatThrownBy(
            () ->
                new RemoteOAuthEndpointConfiguration(
                    Set.of("*.example.invalid"),
                    Set.of(),
                    true,
                    URI.create(RemoteTestSupport.AUDIENCE + metadataPath()),
                    List.of(RemoteTestSupport.DISCOVER),
                    65_536))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RemoteOAuthEndpointConfiguration(
                    Set.of("mcp.example.invalid"),
                    Set.of(URI.create("http://chatgpt.com")),
                    false,
                    URI.create(RemoteTestSupport.AUDIENCE + metadataPath()),
                    List.of(RemoteTestSupport.DISCOVER),
                    65_536))
        .isInstanceOf(IllegalArgumentException.class);

    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    RemoteOAuthEndpointConfiguration wrongPath =
        new RemoteOAuthEndpointConfiguration(
            Set.of("localhost"),
            Set.of(),
            true,
            URI.create(RemoteTestSupport.AUDIENCE + "/metadata"),
            List.of(RemoteTestSupport.DISCOVER),
            65_536);
    assertThatThrownBy(
            () ->
                start(
                    tls,
                    fixedAuthenticator(),
                    new HandshakeOnlyMcpDispatcher(),
                    wrongPath))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("standard path");
  }

  @Test
  void endpointConfigurationRejectsEmptyDuplicateAndOutOfRangePolicies() {
    URI metadata = URI.create(RemoteTestSupport.AUDIENCE + metadataPath());
    assertThatThrownBy(
            () ->
                new RemoteOAuthEndpointConfiguration(
                    Set.of(),
                    Set.of(),
                    true,
                    metadata,
                    List.of(RemoteTestSupport.DISCOVER),
                    65_536))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RemoteOAuthEndpointConfiguration(
                    Set.of("localhost"),
                    Set.of(URI.create("https://chatgpt.com/")),
                    true,
                    metadata,
                    List.of(RemoteTestSupport.DISCOVER),
                    65_536))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RemoteOAuthEndpointConfiguration(
                    Set.of("localhost"),
                    Set.of(),
                    true,
                    metadata,
                    List.of(RemoteTestSupport.DISCOVER, RemoteTestSupport.DISCOVER),
                    65_536))
        .isInstanceOf(IllegalArgumentException.class);
    for (int maximum : List.of(1_023, 65_537)) {
      assertThatThrownBy(
              () ->
                  new RemoteOAuthEndpointConfiguration(
                      Set.of("localhost"),
                      Set.of(),
                      true,
                      metadata,
                      List.of(RemoteTestSupport.DISCOVER),
                      maximum))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void unknownPathsRuntimeFailuresJsonArraysAndChunkedOversizeStayBounded()
      throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    RemoteOAuthEndpointConfiguration small =
        new RemoteOAuthEndpointConfiguration(
            Set.of("localhost"),
            Set.of(),
            true,
            URI.create(RemoteTestSupport.AUDIENCE + metadataPath()),
            List.of(RemoteTestSupport.DISCOVER),
            1_024);
    try (RemoteOAuthMcpServer server =
        start(
            tls,
            fixedAuthenticator(),
            (request, connector) -> {
              throw new IllegalStateException("dispatcher-secret");
            },
            small)) {
      OkHttpClient client = RemoteTestSupport.client(tls);
      try (Response missing = client.newCall(get(server, "/missing")).execute()) {
        assertThat(missing.code()).isEqualTo(404);
      }
      try (Response array =
          client
              .newCall(
                  post(server, "/mcp", "[]")
                      .header("Authorization", "Bearer valid")
                      .build())
              .execute()) {
        assertThat(array.code()).isEqualTo(400);
      }
      RequestBody chunked =
          new RequestBody() {
            @Override
            public MediaType contentType() {
              return JSON;
            }

            @Override
            public long contentLength() {
              return -1;
            }

            @Override
            public void writeTo(BufferedSink sink) throws java.io.IOException {
              sink.writeUtf8("x".repeat(1_025));
            }
          };
      try (Response oversize =
          client
              .newCall(
                  new Request.Builder()
                      .url(url(server, "/mcp"))
                      .header("Authorization", "Bearer valid")
                      .post(chunked)
                      .build())
              .execute()) {
        assertThat(oversize.code()).isEqualTo(413);
      }
      try (Response failed =
          client
              .newCall(
                  post(server, "/mcp", INITIALIZE)
                      .header("Authorization", "Bearer valid")
                      .build())
              .execute()) {
        assertThat(failed.code()).isEqualTo(500);
        assertThat(requiredBody(failed)).doesNotContain("dispatcher-secret");
      }
      Request unknownPreflight =
          new Request.Builder()
              .url(url(server, "/missing"))
              .method("OPTIONS", null)
              .build();
      try (Response response = client.newCall(unknownPreflight).execute()) {
        assertThat(response.code()).isEqualTo(404);
      }
    }
  }

  private static RemoteOAuthMcpServer start(
      RemoteTestSupport.TlsMaterial tls,
      ConnectorAuthenticator authenticator,
      RemoteMcpDispatcher dispatcher,
      RemoteOAuthEndpointConfiguration configuration)
      throws Exception {
    return RemoteOAuthMcpServer.startForLoopbackTests(
        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
        tls.server().sslContext(),
        configuration,
        metadata(),
        authenticator,
        dispatcher);
  }

  private static RemoteOAuthMcpServer startHardened(
      RemoteTestSupport.MutualTlsMaterial tls,
      ConnectorAuthenticator authenticator,
      RemoteMcpDispatcher dispatcher,
      RemoteRateLimiter networkRateLimiter,
      RemoteRateLimiter principalRateLimiter,
      java.util.function.Supplier<RemoteEdgeReadiness> readiness)
      throws Exception {
    return startHardened(
        tls,
        authenticator,
        dispatcher,
        networkRateLimiter,
        principalRateLimiter,
        readiness,
        SecurityTelemetry.discardForExplicitDevelopmentTests());
  }

  private static RemoteOAuthMcpServer startHardened(
      RemoteTestSupport.MutualTlsMaterial tls,
      ConnectorAuthenticator authenticator,
      RemoteMcpDispatcher dispatcher,
      RemoteRateLimiter networkRateLimiter,
      RemoteRateLimiter principalRateLimiter,
      java.util.function.Supplier<RemoteEdgeReadiness> readiness,
      SecurityTelemetry telemetry)
      throws Exception {
    return RemoteOAuthMcpServer.startHardened(
        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
        tls.server().sslContext(),
        RemoteTestSupport.endpointConfiguration(),
        metadata(),
        authenticator,
        dispatcher,
        networkRateLimiter,
        principalRateLimiter,
        readiness,
        telemetry);
  }

  private static ConnectorAuthenticator fixedAuthenticator() {
    return header -> {
      if (!"Bearer valid".equals(header)) {
        throw new AuthenticationException(
            "MISSING_BEARER_TOKEN", "A valid Bearer authorization header is required.");
      }
      return RemoteTestSupport.connector(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ);
    };
  }

  private static RemoteMcpDispatcher enforcingDispatcher() {
    AtomicInteger ids = new AtomicInteger();
    InMemoryEvidenceLedger evidence =
        new InMemoryEvidenceLedger(
            "urn:test:remote-enforcement",
            Clock.fixed(RemoteTestSupport.NOW, ZoneOffset.UTC),
            () -> "evidence-" + ids.incrementAndGet());
    ExecutionPermitAuthority permits =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "remote-test-key",
            new byte[32],
            30,
            Clock.fixed(RemoteTestSupport.NOW, ZoneOffset.UTC),
            () -> "permit-" + ids.incrementAndGet(),
            new InMemoryPermitConsumptionStore());
    SecureExecutionService service =
        new SecureExecutionService(
            new PolicyEvaluator(),
            JsonSupport.read(
                Path.of(System.getProperty("tracepermit.repositoryRoot"))
                    .resolve("policies/aws-web-readonly.json")),
            permits,
            new InMemoryExecutionLedger(() -> "attempt-" + ids.incrementAndGet()),
            evidence,
            new StatefulTargetSimulator(),
            () -> SecurityReadiness.allReady(RemoteTestSupport.NOW),
            Clock.fixed(RemoteTestSupport.NOW, ZoneOffset.UTC),
            "urn:tracepermit:simulator");
    return new EnforcingMcpDispatcher(
        service,
        Clock.fixed(RemoteTestSupport.NOW, ZoneOffset.UTC),
        () -> String.format("00000000-0000-7000-8000-%012d", ids.incrementAndGet()),
        () -> String.format("%032x", ids.get()));
  }

  private static OAuthProtectedResourceMetadata metadata() {
    return new OAuthProtectedResourceMetadata(
        URI.create(RemoteTestSupport.AUDIENCE),
        "TracePermit MCP",
        List.of(URI.create(RemoteTestSupport.ISSUER)),
        List.of(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ),
        List.of("header"));
  }

  private static OidcProviderConfiguration provider(URI jwksUri) {
    return new OidcProviderConfiguration(
        URI.create(RemoteTestSupport.ISSUER),
        RemoteTestSupport.AUDIENCE,
        jwksUri,
        URI.create(RemoteTestSupport.AUDIENCE + metadataPath()),
        List.of(URI.create(RemoteTestSupport.ISSUER)),
        "TracePermit MCP",
        Map.of("chatgpt-connector", "ChatGPT Web"),
        "org_id",
        Map.of("enterprise-acme", "tenant:acme"),
        Set.of(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ),
        ConnectorOrigin.CHATGPT_WEB,
        Duration.ofMinutes(5),
        Duration.ofMinutes(1),
        Duration.ofSeconds(30));
  }

  private static String jwks(KeyPair keyPair) {
    RSAPublicKey key = (RSAPublicKey) keyPair.getPublic();
    return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\""
        + RemoteTestSupport.KEY_ID
        + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
        + unsigned(key.getModulus())
        + "\",\"e\":\""
        + unsigned(key.getPublicExponent())
        + "\"}]}";
  }

  private static String unsigned(BigInteger value) {
    byte[] signed = value.toByteArray();
    byte[] bytes = signed[0] == 0 ? java.util.Arrays.copyOfRange(signed, 1, signed.length) : signed;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static Request get(RemoteOAuthMcpServer server, String path) {
    return new Request.Builder().url(url(server, path)).get().build();
  }

  private static Request.Builder post(RemoteOAuthMcpServer server, String path, String body) {
    return new Request.Builder()
        .url(url(server, path))
        .post(RequestBody.create(body, JSON));
  }

  private static String url(RemoteOAuthMcpServer server, String path) {
    return "https://localhost:" + server.port() + path;
  }

  private static String metadataPath() {
    return "/.well-known/oauth-protected-resource";
  }

  private static String requiredBody(Response response) throws Exception {
    assertThat(response.body()).isNotNull();
    return response.body().string();
  }

  private static void assertSecurityHeaders(Response response) {
    assertThat(response.header("Cache-Control")).isEqualTo("no-store");
    assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.header("Content-Security-Policy")).isEqualTo("default-src 'none'");
  }
}
