package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.OidcVerificationKeyResolver;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/** Operator files -> actual runtime -> signed HTTP -> PostgreSQL -> simulator; no external IdP. */
final class SimulatorApprovalBootstrapTest {
  @TempDir Path directory;
  private PostgreSQLContainer<?> postgres;
  private SimulatorApprovalFixture fixture;
  private SimulatorApprovalServer server;
  private HttpClient client;
  private KeyPair rsa;
  private final AtomicInteger verifications = new AtomicInteger();

  @BeforeEach void setup() throws Exception {
    postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
        .withDatabaseName("simulated_approvals").withUsername("simulated_operator")
        .withPassword("synthetic-local-test-password");
    postgres.start();
    fixture = new SimulatorApprovalFixture(directory);
    fixture.json.withObject("/postgres").put("port", postgres.getMappedPort(5432));
    fixture.json.put("port", freePort());
    fixture.save();
    rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach void cleanup() {
    if (server != null) server.close();
    if (client != null) client.close();
    if (postgres != null) postgres.stop();
  }

  @Test void operatorCompositionReconstructsPendingAndConsumedStateWithPersistentKey() throws Exception {
    OidcVerificationKeyResolver resolver = (provider, kid, algorithm, now) -> {
      verifications.incrementAndGet();
      assertThat(kid).isEqualTo("synthetic-idp-key");
      return (RSAPublicKey) rsa.getPublic();
    };
    server = SimulatorApprovalMain.start(fixture.load(), Clock.systemUTC(), resolver);
    var pending = call("POST", "/api/v1/approvals", "alice",
        "{\"groupId\":\"sg-demo\",\"port\":443,\"cidr\":\"192.0.2.0/24\"}", 201);
    assertThat(pending.path("policyId").asText()).isEqualTo("pol-local-simulator-approval");
    assertThat(pending.path("executionStatus").asText()).isEqualTo("not_executed");
    String path = "/api/v1/approvals/" + pending.path("approvalRequestId").asText();
    String hash = "{\"requestHash\":\"" + pending.path("requestHash").asText() + "\"}";
    server.close();
    server.close(); // shutdown hook and owner may both close safely
    server = SimulatorApprovalMain.start(fixture.load(), Clock.systemUTC(), resolver);
    assertThat(call("GET", path, "alice", null, 200).path("requestHash")).isEqualTo(pending.path("requestHash"));
    call("POST", path + "/decision", "alice", hash.replace("}", ",\"decision\":\"approve\"}"), 403);
    call("POST", path + "/decision", "bob", hash.replace("}", ",\"decision\":\"approve\"}"), 200);
    assertThat(call("POST", path + "/execute", "alice", hash, 200).path("executionStatus").asText()).isEqualTo("succeeded");
    server.close();
    server = SimulatorApprovalMain.start(fixture.load(), Clock.systemUTC(), resolver);
    assertThat(call("GET", path, "alice", null, 200).path("executionStatus").asText()).isEqualTo("succeeded");
    call("POST", path + "/execute", "alice", hash, 409);
    assertThat(verifications).hasValue(7);
    assertThat(SimulatorApprovalMain.policy().path("defaultDecision").path("outcome").asText()).isEqualTo("block");
  }

  @Test void optionalOperatorBrowserModeServesBuiltAssetsAndStartsConfiguredLoginWithoutIdpTraffic() throws Exception {
    Path console = directory.resolve("console"); Files.createDirectory(console);
    Files.writeString(console.resolve("index.html"), "<!doctype html><html>synthetic-console</html>");
    fixture.write("browser.secret", "synthetic-browser-client-secret".getBytes(StandardCharsets.UTF_8));
    fixture.json.putObject("browser").put("authorizationUri", "https://idp.example.invalid/authorize")
        .put("tokenUri", "https://idp.example.invalid/token").put("clientSecretFile", "browser.secret")
        .put("consoleDirectory", console.toString());
    server = SimulatorApprovalMain.start(fixture.load(), Clock.systemUTC(),
        (p, kid, alg, now) -> { throw new AssertionError("Anonymous page/login must not fetch JWKS."); });
    URI base = URI.create("http://127.0.0.1:" + server.port());
    var page = client.send(HttpRequest.newBuilder(base.resolve("/approvals")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("synthetic-console").doesNotContain("client-secret");
    var login = client.send(HttpRequest.newBuilder(base.resolve("/auth/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).isEqualTo(303);
    assertThat(login.headers().firstValue("Location").orElseThrow()).startsWith("https://idp.example.invalid/authorize?")
        .contains("code_challenge_method=S256", "scope=openid", "client_id=approval-client")
        .doesNotContain("synthetic-browser-client-secret");
    var session = client.send(HttpRequest.newBuilder(base.resolve("/browser/session")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(session.statusCode()).isEqualTo(401);
  }

  @Test void mainCheckIsOfflineThenMainRunsAndTerminatesGracefully() throws Exception {
    // Unreachable DB and .invalid IdP are deliberate: check-only must not contact either.
    fixture.json.withObject("/postgres").put("port", 1); fixture.save();
    Path output = directory.resolve("child-output.txt");
    Process check = launch("--check", output);
    try {
      assertThat(check.waitFor(15, TimeUnit.SECONDS)).isTrue();
      assertThat(check.exitValue()).isZero();
      assertThat(Files.readString(output)).contains("APPROVAL_CONFIGURATION_VALID").doesNotContain("jdbc:");
    } finally { if (check.isAlive()) check.destroyForcibly().waitFor(); }
    fixture.json.withObject("/postgres").put("port", postgres.getMappedPort(5432)); fixture.save();
    Process runtime = launch("--run", output);
    try {
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (runtime.isAlive() && System.nanoTime() < deadline
          && !Files.readString(output).contains("API listening")) Thread.sleep(50);
      assertThat(Files.readString(output)).contains("API listening").doesNotContain(
          "synthetic-local-test-password", "alice", fixture.config.toString());
      int port = fixture.json.path("port").intValue();
      var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/approvals"))
          .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(401);
      runtime.destroy(); // SIGTERM on the supported macOS/Linux hosts
      assertThat(runtime.waitFor(10, TimeUnit.SECONDS)).isTrue();
      try (var rebound = new ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
        assertThat(rebound.isBound()).isTrue();
      }
    } finally { if (runtime.isAlive()) runtime.destroyForcibly().waitFor(); }
  }

  @Test void databaseFailureNeverOpensListenerAndMainRedactsFailure() throws Exception {
    fixture.json.withObject("/postgres").put("port", 1);
    var config = fixture.load();
    assertThatThrownBy(() -> SimulatorApprovalMain.start(config, Clock.systemUTC())).isInstanceOf(RuntimeException.class);
    try (var rebound = new ServerSocket(config.port(), 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      assertThat(rebound.isBound()).isTrue();
    }
    Path output = directory.resolve("failed-child.txt");
    Process child = launch("--run", output);
    try {
      assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
      assertThat(child.exitValue()).isEqualTo(2);
      assertThat(Files.readString(output)).contains("APPROVAL_STARTUP_FAILED")
          .doesNotContain("synthetic-local-test-password", fixture.config.toString(), "alice", "Exception");
    } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
  }

  private Process launch(String argument, Path output) throws Exception {
    var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("tracepermit.approvalRuntimeClasspath"),
        SimulatorApprovalMain.class.getName(), argument).redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().put("TRACEPERMIT_APPROVAL_CONFIG", fixture.config.toString());
    return builder.start();
  }

  private static int freePort() throws Exception {
    try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

  private JsonNode call(String method, String path, String subject, String body, int status) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token(subject));
    if (body != null) request.header("Content-Type", "application/json");
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    var result = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).describedAs(result.body()).isEqualTo(status);
    return JsonSupport.MAPPER.readTree(result.body());
  }

  private String token(String subject) throws Exception {
    long now = Clock.systemUTC().instant().getEpochSecond();
    var header = JsonSupport.MAPPER.createObjectNode().put("alg", "RS256").put("typ", "at+jwt").put("kid", "synthetic-idp-key");
    var claims = JsonSupport.MAPPER.createObjectNode().put("iss", "https://idp.example.invalid")
        .put("aud", "https://approval.example.invalid").put("sub", subject).put("client_id", "approval-client")
        .put("org_id", "alpha").put("scope", "approvals.read approvals.request approvals.decide approvals.execute")
        .put("iat", now - 10).put("exp", now + 290).put("jti", UUID.randomUUID().toString());
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input = encoder.encodeToString(JsonSupport.MAPPER.writeValueAsBytes(header)) + "."
        + encoder.encodeToString(JsonSupport.MAPPER.writeValueAsBytes(claims));
    var signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(rsa.getPrivate()); signer.update(input.getBytes(StandardCharsets.UTF_8));
    return input + "." + encoder.encodeToString(signer.sign());
  }
}
