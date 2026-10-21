package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.BrowserIdTokenVerifier;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.StaticOidcProviderRegistry;
import io.tracepermit.persistence.postgres.PostgresDurableEnforcementState;
import io.tracepermit.persistence.postgres.PostgresPermitConsumptionStore;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.testcontainers.containers.PostgreSQLContainer;

/** TEST CLASSPATH ONLY: explicit account picker, synthetic signatures and disposable PostgreSQL. */
final class SimulatorApprovalBrowserFixture implements AutoCloseable {
  static final String ISSUER = "https://idp.example.invalid";
  static final String CLIENT = "approval-client";
  static final String ACTION = "{\"groupId\":\"sg-demo\",\"port\":443,\"cidr\":\"192.0.2.0/24\"}";
  final MutableClock clock = new MutableClock();
  final StatefulTargetSimulator simulator = new StatefulTargetSimulator();
  final AtomicInteger exchanges = new AtomicInteger();
  final SimulatorApprovalConfiguration config;
  final PostgresDurableEnforcementState state;
  final KeyPair rsa;
  final String issuer;
  final String organization;
  final AtomicInteger tokenPosts = new AtomicInteger();
  volatile KeyPair signingKey;
  volatile Consumer<ObjectNode> idHeader = header -> {};
  volatile Consumer<ObjectNode> accessHeader = header -> {};
  volatile Consumer<Map<String, String>> exchangeForm = form -> {};
  volatile String lastTokenResponse;
  final HttpServer idp;
  final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
  volatile Consumer<ObjectNode> idClaims = claims -> {};
  volatile Consumer<ObjectNode> accessClaims = claims -> {};
  volatile String tokenResponse;
  SimulatorApprovalServer server;

  SimulatorApprovalBrowserFixture(Path directory, PostgreSQLContainer<?> postgres) throws Exception {
    this(directory, postgres, false);
  }

  SimulatorApprovalBrowserFixture(Path directory, PostgreSQLContainer<?> postgres, boolean publicClient) throws Exception {
    issuer = publicClient ? ISSUER + "/" : ISSUER;
    organization = publicClient ? "org_synthetic" : null;
    var files = new SimulatorApprovalFixture(directory);
    if (publicClient) {
      java.nio.file.Files.createDirectories(directory.resolve("console"));
      java.nio.file.Files.writeString(directory.resolve("console/index.html"), "<html></html>");
      files.json.put("schemaVersion", "1.1");
      files.json.withObject("/oidc").put("issuer", issuer).put("tenantValue", organization)
          .put("jwksUri", issuer + ".well-known/jwks.json");
      files.json.putObject("browser").put("tokenEndpointAuthMethod", "none")
          .put("authorizationUri", issuer + "authorize").put("tokenUri", issuer + "oauth/token")
          .put("consoleDirectory", "console");
    }
    files.json.withObject("/postgres").put("port", postgres.getMappedPort(5432))
        .put("database", postgres.getDatabaseName()).put("user", postgres.getUsername());
    files.write("database.secret", postgres.getPassword().getBytes(StandardCharsets.UTF_8));
    files.json.withArray("/memberships").addObject().put("subject", "carol").put("human", true)
        .putArray("roles").add("VIEWER");
    files.json.withArray("/memberships").addObject().put("subject", "dave").put("human", true)
        .putArray("roles").add("REQUESTER").add("REVIEWER");
    files.json.withArray("/memberships").addObject().put("subject", "robot").put("human", false)
        .putArray("roles").add("REQUESTER");
    config = files.load();
    String schema = "browser_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = config.database().getConnection(); var sql = connection.createStatement()) {
      sql.execute("CREATE SCHEMA " + schema);
    }
    config.database().setCurrentSchema(schema);
    state = new PostgresDurableEnforcementState(config.database(), clock, () -> UUID.randomUUID().toString());
    rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    signingKey = rsa;
    idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
    idp.createContext("/authorize", request -> {
      var params = parse(request.getRequestURI().getRawQuery());
      String subject = params.get("synthetic_subject");
      if (subject == null) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><title>Synthetic test identity provider</title></head><body><h1>Synthetic identity provider</h1><p>Local test accounts only. No password or personal login.</p>");
        for (String account : List.of("alice", "bob", "carol", "dave")) {
          var next = new HashMap<>(params); next.put("synthetic_subject", account);
          html.append("<p><a href=\"/authorize?").append(ApprovalBrowserAdapter.form(next).replace("&", "&amp;"))
              .append("\">Sign in as ").append(account).append("</a></p>");
        }
        byte[] bytes = html.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        request.sendResponseHeaders(200, bytes.length); request.getResponseBody().write(bytes);
      } else {
        String code = UUID.randomUUID().toString(); codes.put(code, params);
        request.getResponseHeaders().set("Location", params.get("redirect_uri") + "?"
            + ApprovalBrowserAdapter.form(Map.of("code", code, "state", params.get("state"), "iss", issuer)));
        request.sendResponseHeaders(303, -1);
      }
      request.close();
    });
    idp.start();
    restart();
  }

  void restart() throws Exception {
    if (server != null) server.close();
    var resolver = (io.tracepermit.identity.OidcVerificationKeyResolver) (p, kid, alg, now) -> {
      if (!"fixture-key".equals(kid) || !"RS256".equals(alg)) throw new SecurityException("SYNTHETIC_UNKNOWN_KEY");
      return (RSAPublicKey) rsa.getPublic();
    };
    var verifier = new OidcAccessTokenVerifier(new StaticOidcProviderRegistry(List.of(config.provider())), resolver, clock);
    var browser = new ApprovalBrowserAdapter(URI.create(idpOrigin() + "/authorize"), issuer,
        config.provider().resourceAudience(), CLIENT,
        Path.of(System.getProperty("tracepermit.repositoryRoot"), "apps/console/dist"), form -> {
          var changed = new HashMap<>(form); exchangeForm.accept(changed);
          if (organization == null) return exchange(changed);
          return new BrowserCodeExchange(config.browser().tokenUri, server.port(), CLIENT, "none", null,
              (uri, body, auth) -> {
                tokenPosts.incrementAndGet();
                if (auth != null || !CLIENT.equals(body.get("client_id")) || body.containsKey("client_secret"))
                  throw new SecurityException("SYNTHETIC_CLIENT_REJECTED");
                return exchange(body);
              }).exchange(changed);
        }, verifier, new BrowserIdTokenVerifier(config.provider(), resolver, clock, organization),
        config.memberships(), clock, organization);
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER,
        config.keyId(), config.permitKey(), 30, clock, () -> UUID.randomUUID().toString(),
        new PostgresPermitConsumptionStore(config.database()));
    server = SimulatorApprovalServer.start(new InetSocketAddress("127.0.0.1", 0), verifier,
        config.memberships(), state, SimulatorApprovalMain.policy(), permits, simulator, clock, browser);
  }

  String origin() { return "http://127.0.0.1:" + server.port(); }
  String idpOrigin() { return "http://127.0.0.1:" + idp.getAddress().getPort(); }

  // Test/qualification only. No issuer or minting endpoint is selectable in normal runtime.
  String accessToken(String subject) throws Exception {
    long now = clock.instant().getEpochSecond();
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode().put("iss", issuer)
        .put("aud", config.provider().resourceAudience()).put("sub", subject)
        .put("client_id", CLIENT).put("org_id", organization == null ? "alpha" : organization)
        .put("scope", "approvals.read approvals.request approvals.execute")
        .put("iat", now - 10).put("exp", now + 290).put("jti", UUID.randomUUID().toString());
    accessClaims.accept(claims);
    return sign(claims, "at+jwt");
  }

  private String exchange(Map<String, String> form) {
    exchanges.incrementAndGet();
    try {
      Map<String, String> params = codes.remove(form.get("code"));
      String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
          .digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
      if (params == null || !challenge.equals(params.get("code_challenge"))
          || !form.get("redirect_uri").equals(params.get("redirect_uri"))) throw new SecurityException();
      if (tokenResponse != null) return tokenResponse;
      long now = clock.instant().getEpochSecond();
      ObjectNode access = JsonSupport.MAPPER.createObjectNode().put("iss", issuer)
          .put("aud", config.provider().resourceAudience()).put("sub", params.get("synthetic_subject"))
          .put("client_id", CLIENT).put("org_id", organization == null ? "alpha" : organization)
          .put("scope", "approvals.read approvals.request approvals.decide approvals.execute approvals.cancel")
          .put("iat", now - 10).put("exp", now + 290).put("jti", UUID.randomUUID().toString());
      accessClaims.accept(access);
      ObjectNode identity = JsonSupport.MAPPER.createObjectNode().put("iss", issuer).put("aud", CLIENT)
          .put("sub", params.get("synthetic_subject")).put("nonce", params.get("nonce"))
          .put("iat", now - 10).put("exp", now + 290);
      if (organization != null) identity.put("org_id", organization);
      idClaims.accept(identity);
      lastTokenResponse = JsonSupport.MAPPER.createObjectNode().put("access_token", sign(access, "at+jwt"))
          .put("id_token", sign(identity, "JWT")).put("token_type", "Bearer").toString();
      return lastTokenResponse;
    } catch (Exception failure) { throw new SecurityException("SYNTHETIC_CODE_REJECTED"); }
  }

  String sign(ObjectNode claims, String type) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    var header = JsonSupport.MAPPER.createObjectNode().put("alg", "RS256").put("typ", type).put("kid", "fixture-key");
    if ("JWT".equals(type)) idHeader.accept(header); else accessHeader.accept(header);
    String input = encoder.encodeToString(JsonSupport.MAPPER.writeValueAsBytes(header)) + "."
        + encoder.encodeToString(JsonSupport.MAPPER.writeValueAsBytes(claims));
    var signer = Signature.getInstance("SHA256withRSA"); signer.initSign(signingKey.getPrivate());
    signer.update(input.getBytes(StandardCharsets.UTF_8));
    return input + "." + encoder.encodeToString(signer.sign());
  }

  static Map<String, String> parse(String query) {
    Map<String, String> result = new HashMap<>();
    for (String pair : query.split("&")) {
      String[] parts = pair.split("=", 2);
      result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  @Override public void close() { if (server != null) server.close(); idp.stop(0); }

  static final class MutableClock extends Clock {
    volatile Instant now;
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now == null ? Instant.now() : now; }
  }
}
