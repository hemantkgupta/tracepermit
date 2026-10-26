package io.tracepermit.adapters.remote.github.credentials;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Real HTTP exchange; all generated signing/token material remains in memory. No request recording. */
public final class PilotHttpCredentialFixture implements AutoCloseable {
  public enum Fault {
    NONE, INSTALLATION_ID, TOKEN_PERMISSIONS, TOKEN_REPOSITORIES, TOKEN_REDIRECT,
    TOKEN_SERVER_ERROR, TOKEN_LOST_RESPONSE, TOKEN_MALFORMED, TOKEN_OVERSIZED,
    CLOSE_FAILURE, EXPIRES_AT_CLOSE
  }
  private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
  private static final String CANARY = "SYNTHETIC_HTTP_PRIVATE_DIAGNOSTIC";
  private final GitHubInstallationConfiguration configuration;
  private final Fault fault;
  private final Consumer<String> events;
  private final KeyPair keys;
  private final String token = "synthetic-http-pilot-" + UUID.randomUUID();
  private final AtomicReference<Instant> now = new AtomicReference<>(START);
  private final List<String> violations = new CopyOnWriteArrayList<>();
  private final AtomicInteger requests = new AtomicInteger(), jwtChecks = new AtomicInteger();
  private final AtomicInteger restrictionChecks = new AtomicInteger(), closeAcknowledgements = new AtomicInteger();
  private final AtomicInteger keyReads = new AtomicInteger(), opens = new AtomicInteger();
  private final HttpServer server;
  private final ExecutorService executor;
  private byte[] firstJwtDigest;
  private final Clock clock = new Clock() {
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId ignored) { return this; }
    @Override public Instant instant() { return now.get(); }
  };
  private final GitHubInstallationCredentialProvider provider;

  public PilotHttpCredentialFixture(GitHubInstallationConfiguration configuration, Fault fault,
      Consumer<String> events) throws Exception {
    this.configuration = configuration; this.fault = fault; this.events = events;
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
    executor = Executors.newFixedThreadPool(1, task -> { var thread = new Thread(task, "pilot-http-credential-fixture"); thread.setDaemon(true); return thread; });
    server.setExecutor(executor); server.createContext("/", this::handle); server.start();
    var transport = GitHubCredentialTransport.synthetic(origin(), configuration.installationId(), Duration.ofSeconds(3), 65_536);
    provider = new GitHubInstallationCredentialProvider(configuration, () -> {
      keyReads.incrementAndGet(); events.accept("KEY_READ"); return keys.getPrivate();
    }, () -> {
      var real = transport.open(); opens.incrementAndGet(); events.accept("CREDENTIAL_OPEN");
      return new CredentialExchange() {
        @Override public Reply installation(String jwt) throws Exception { return real.installation(jwt); }
        @Override public Reply token(String jwt, JsonNode restrictions) throws Exception { return real.token(jwt, restrictions); }
        @Override public void close() {
          real.close(); closeAcknowledgements.incrementAndGet(); events.accept("CREDENTIAL_CLOSE_ACK");
          if (fault == Fault.EXPIRES_AT_CLOSE) now.set(START.plusSeconds(60));
          if (fault == Fault.CLOSE_FAILURE) {
            var failure = new IllegalStateException(CANARY, new IOException(CANARY));
            failure.addSuppressed(new IOException(CANARY)); throw failure;
          }
        }
      };
    }, clock);
  }

  public GitHubInstallationCredentialProvider provider() { return provider; }
  public Clock clock() { return clock; }
  public int requests() { return requests.get(); }
  public int verifiedJwts() { return jwtChecks.get(); }
  public int verifiedRestrictions() { return restrictionChecks.get(); }
  public int closeAcknowledgements() { return closeAcknowledgements.get(); }
  public int keyReads() { return keyReads.get(); }
  public int opens() { return opens.get(); }
  public List<String> violations() { return List.copyOf(violations); }
  public boolean authorizes(String authorization) { return ("Bearer " + token).equals(authorization); }
  public boolean leaks(String diagnostic) { return diagnostic.contains(token) || diagnostic.contains(CANARY); }
  private URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      requests.incrementAndGet();
      String path = exchange.getRequestURI().getRawPath();
      boolean installation = path.equals("/app/installations/" + configuration.installationId());
      boolean mint = path.equals("/app/installations/" + configuration.installationId() + "/access_tokens");
      String route = installation ? "CREDENTIAL_GET" : mint ? "CREDENTIAL_POST" : "CREDENTIAL_UNEXPECTED";
      events.accept(route);
      if (!(installation && exchange.getRequestMethod().equals("GET")
          || mint && exchange.getRequestMethod().equals("POST")) || exchange.getRequestURI().getRawQuery() != null) {
        reject(exchange, "CREDENTIAL_ROUTE_REJECTED"); return;
      }
      if (!verifyJwt(exchange.getRequestHeaders().getFirst("Authorization"))) {
        reject(exchange, "JWT_CONTRACT_REJECTED"); return;
      }
      if (mint && !verifyRestrictions(exchange)) { reject(exchange, "RESTRICTION_CONTRACT_REJECTED"); return; }
      if (installation) {
        ObjectNode response = installation();
        if (fault == Fault.INSTALLATION_ID) response.put("id", configuration.installationId() + 1);
        send(exchange, 200, response.toString()); return;
      }
      switch (fault) {
        case TOKEN_REDIRECT -> { exchange.getResponseHeaders().set("Location", origin() + "/tripwire"); send(exchange, 307, CANARY); }
        case TOKEN_SERVER_ERROR -> { exchange.getResponseHeaders().set("Retry-After", "0"); send(exchange, 503, CANARY); }
        case TOKEN_LOST_RESPONSE -> { /* Body was received/verified; close before any response. */ }
        case TOKEN_MALFORMED -> send(exchange, 201, "{" + CANARY);
        case TOKEN_OVERSIZED -> send(exchange, 201, "x".repeat(65_537));
        default -> {
          ObjectNode response = token();
          if (fault == Fault.TOKEN_PERMISSIONS) response.withObject("/permissions").put("contents", "write");
          if (fault == Fault.TOKEN_REPOSITORIES) {
            var additional = repository().put("id", configuration.repositoryId() + 1)
                .put("name", "synthetic-other").put("full_name", configuration.repository().split("/")[0] + "/synthetic-other");
            response.withArray("/repositories").add(additional);
          }
          send(exchange, 201, response.toString());
        }
      }
    } catch (IOException disconnected) {
      // The client's bounded read/close can terminate the synthetic connection; no raw diagnostics.
    } catch (Exception problem) {
      violations.add("FIXTURE_HANDLER_FAILED");
    }
  }

  private boolean verifyJwt(String authorization) {
    try {
      if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 16_384) return false;
      String compact = authorization.substring(7); String[] pieces = compact.split("\\.", -1);
      if (pieces.length != 3) return false;
      var decoder = Base64.getUrlDecoder();
      JsonNode header = JSON.readTree(decoder.decode(pieces[0])); JsonNode claims = JSON.readTree(decoder.decode(pieces[1]));
      if (!header.isObject() || header.size() != 2 || !"RS256".equals(header.path("alg").asText())
          || !"JWT".equals(header.path("typ").asText()) || !claims.isObject() || claims.size() != 3
          || !claims.path("iss").isTextual() || !Long.toString(configuration.appId()).equals(claims.path("iss").textValue())
          || !claims.path("iat").isIntegralNumber() || !claims.path("exp").isIntegralNumber()
          || claims.path("iat").longValue() != START.getEpochSecond() - 60
          || claims.path("exp").longValue() != START.getEpochSecond() + 540
          || claims.path("exp").longValue() - claims.path("iat").longValue() != 600) return false;
      var verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(keys.getPublic());
      verifier.update((pieces[0] + "." + pieces[1]).getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(decoder.decode(pieces[2]))) return false;
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(compact.getBytes(StandardCharsets.US_ASCII));
      if (firstJwtDigest == null) firstJwtDigest = digest;
      else if (!MessageDigest.isEqual(firstJwtDigest, digest)) return false;
      jwtChecks.incrementAndGet(); return true;
    } catch (Exception invalid) { return false; }
  }

  private boolean verifyRestrictions(HttpExchange exchange) throws IOException {
    byte[] bytes = exchange.getRequestBody().readNBytes(4097);
    try {
      if (bytes.length > 4096) return false;
      JsonNode actual = JSON.readTree(bytes);
      if (actual == null || !actual.isObject() || actual.size() != 2) return false;
      JsonNode repositories = actual.path("repository_ids"), permissions = actual.path("permissions");
      if (!repositories.isArray() || repositories.size() != 1 || !permissions.isObject()
          || permissions.size() != configuration.permissions().size()) return false;
      JsonNode id = repositories.get(0);
      if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() != configuration.repositoryId()) return false;
      for (var entry : configuration.permissions().entrySet()) {
        JsonNode value = permissions.path(entry.getKey());
        if (!value.isTextual() || !entry.getValue().equals(value.textValue())) return false;
      }
      restrictionChecks.incrementAndGet(); return true;
    } finally { Arrays.fill(bytes, (byte) 0); }
  }

  public ObjectNode repository() {
    var repo = JSON.createObjectNode().put("id", configuration.repositoryId()).put("full_name", configuration.repository())
        .put("name", configuration.repository().split("/")[1]).put("private", true).put("archived", false)
        .put("disabled", false).put("fork", false);
    repo.putObject("owner").put("id", configuration.ownerId()).put("login", configuration.repository().split("/")[0]); return repo;
  }
  private ObjectNode installation() {
    var value = JSON.createObjectNode().put("id", configuration.installationId()).put("app_id", configuration.appId())
        .put("target_id", configuration.ownerId()).put("target_type", "Organization").put("repository_selection", "selected")
        .putNull("suspended_at");
    value.putObject("account").put("id", configuration.ownerId()).put("login", configuration.repository().split("/")[0])
        .put("type", "Organization");
    ObjectNode permissions = value.putObject("permissions"); configuration.permissions().forEach(permissions::put); return value;
  }
  private ObjectNode token() {
    var value = JSON.createObjectNode().put("token", token).put("expires_at", START.plusSeconds(60).toString())
        .put("repository_selection", "selected");
    ObjectNode permissions = value.putObject("permissions"); configuration.permissions().forEach(permissions::put);
    value.putArray("repositories").add(repository()); return value;
  }
  private void reject(HttpExchange exchange, String code) throws IOException { violations.add(code); send(exchange, 400, code); }
  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
    finally { Arrays.fill(bytes, (byte) 0); }
  }
  @Override public void close() {
    server.stop(0); executor.shutdownNow();
    try { if (!executor.awaitTermination(3, TimeUnit.SECONDS)) throw new AssertionError("CREDENTIAL_FIXTURE_CLEANUP_UNCONFIRMED"); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("CREDENTIAL_FIXTURE_CLEANUP_INTERRUPTED"); }
  }
}
