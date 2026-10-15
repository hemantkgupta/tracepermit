package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import io.tracepermit.contracts.JsonSupport;

/** Real HTTP, real signatures, server cookies, durable PG and simulator. No external account. */
final class SimulatorApprovalBrowserTest {
  static PostgreSQLContainer<?> postgres;
  static HttpClient client;
  @TempDir Path directory;
  SimulatorApprovalBrowserFixture fixture;
  record Login(String cookie, JsonNode session) {}
  @BeforeAll static void prerequisites() {
    postgres = new PostgreSQLContainer<>("postgres:16.4-alpine").withPassword("synthetic-browser-password");
    postgres.start(); client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }
  @AfterAll static void finish() { client.close(); postgres.stop(); }
  @BeforeEach void start() throws Exception {
    fixture = new SimulatorApprovalBrowserFixture(directory, postgres);
    fixture.clock.now = java.time.Instant.now();
  }
  @AfterEach void stop() { fixture.close(); }

  @Test void requesterReviewerViewerAndOwnerExecuteShareExactDurableChecks() throws Exception {
    Login alice = login("alice"); Login bob = login("bob"); Login carol = login("carol"); Login dave = login("dave");
    assertThat(alice.session().path("roles").get(0).asText()).isEqualTo("REQUESTER");
    assertThat(alice.session().toString()).doesNotContain("access_token", "id_token", "fixture-key", "#alice");
    var created = mutate(alice, "", SimulatorApprovalBrowserFixture.ACTION, 201);
    String id = "/" + created.path("approvalRequestId").asText();
    String hash = "{\"requestHash\":\"" + created.path("requestHash").asText() + "\"}";
    String decision = hash.replace("}", ",\"decision\":\"approve\"}");
    mutate(carol, "", SimulatorApprovalBrowserFixture.ACTION, 403);
    mutate(carol, id + "/decision", decision, 403);
    mutate(bob, id + "/execute", hash, 403);
    assertThat(json(call("GET", fixture.origin() + ApprovalBrowserAdapter.API, dave.cookie(), null, Map.of()))
        .path("items").size()).isEqualTo(1); // requester + reviewer has workspace read scope
    assertThat(json(call("GET", fixture.origin() + ApprovalBrowserAdapter.API, alice.cookie(), null, Map.of()))
        .path("items").size()).isEqualTo(1);
    mutate(bob, id + "/decision", decision.replace(created.path("requestHash").asText(), "changed"), 409);
    mutate(bob, id + "/decision", decision, 200);
    assertThat(mutate(alice, id + "/execute", hash, 200).path("executionStatus").asText()).isEqualTo("succeeded");
    mutate(alice, id + "/execute", hash, 409);
    var own = mutate(dave, "", SimulatorApprovalBrowserFixture.ACTION, 201);
    mutate(dave, "/" + own.path("approvalRequestId").asText() + "/decision",
        "{\"requestHash\":\"" + own.path("requestHash").asText() + "\",\"decision\":\"approve\"}", 403);
    assertThat(json(call("GET", fixture.origin() + ApprovalBrowserAdapter.API, alice.cookie(), null, Map.of()))
        .path("items").size()).isEqualTo(1); // cannot see Dave's
    assertThat(call("GET", fixture.origin() + ApprovalBrowserAdapter.API + "/" + own.path("approvalRequestId").asText(), alice.cookie(), null, Map.of()).statusCode()).isEqualTo(404);
  }

  @Test void csrfOriginMixedAuthCookieAndHostBoundaries() throws Exception {
    Login alice = login("alice");
    for (Map<String, String> headers : java.util.List.of(Map.<String, String>of(),
        Map.of("Origin", fixture.origin()), Map.of("Origin", "https://attacker.invalid", "X-CSRF-Token", alice.session().path("csrfToken").asText()),
        Map.of("Origin", fixture.origin(), "X-CSRF-Token", "wrong"))) {
      assertThat(call("POST", fixture.origin() + ApprovalBrowserAdapter.API, alice.cookie(), SimulatorApprovalBrowserFixture.ACTION, headers).statusCode()).isEqualTo(403);
    }
    assertThat(call("GET", fixture.origin() + "/browser/session", alice.cookie(), null, Map.of("Authorization", "Bearer attacker")).statusCode()).isEqualTo(400);
    assertThat(call("GET", fixture.origin() + "/browser/session", alice.cookie() + "; " + alice.cookie(), null, Map.of()).statusCode()).isEqualTo(400);
    assertThat(call("GET", fixture.origin() + "/api/v1/approvals", alice.cookie(), null, Map.of()).statusCode()).isEqualTo(403);
    assertThat(call("GET", fixture.origin() + "/browser/session", alice.cookie(), null, Map.of("Origin", "https://attacker.invalid")).statusCode()).isEqualTo(403);
    assertThat(call("GET", fixture.origin() + "/browser/session", alice.cookie(), null, Map.of("Sec-Fetch-Site", "cross-site")).statusCode()).isEqualTo(403);
    assertThat(call("GET", fixture.origin().replace("127.0.0.1", "localhost") + "/browser/session", alice.cookie(), null, Map.of()).statusCode()).isEqualTo(400);
    assertThat(call("POST", fixture.origin() + "/auth/login", null, "{}", Map.of()).statusCode()).isEqualTo(405);
    assertThat(call("GET", fixture.origin() + "/browser/session?x=y", alice.cookie(), null, Map.of()).statusCode()).isEqualTo(400);
  }

  @Test void loginIsBrowserBoundOneUseExpiringAndDoesNotExchangeBadState() throws Exception {
    var start = call("GET", fixture.origin() + "/auth/login", null, null, Map.of());
    assertThat(start.statusCode()).isEqualTo(303);
    String binder = cookie(start, "tp_local_login");
    String callback = authorize(start, "alice");
    assertThat(call("GET", callback, null, null, Map.of()).statusCode()).isEqualTo(401);
    assertThat(fixture.exchanges).hasValue(0);
    assertThat(call("GET", callback, binder, null, Map.of()).statusCode()).isEqualTo(303);
    assertThat(call("GET", callback, binder, null, Map.of()).statusCode()).isEqualTo(401);
    var second = call("GET", fixture.origin() + "/auth/login", null, null, Map.of());
    String next = authorize(second, "alice");
    assertThat(call("GET", next.replace("state=", "state=changed"), cookie(second, "tp_local_login"), null, Map.of()).statusCode()).isEqualTo(401);
    var third = call("GET", fixture.origin() + "/auth/login", null, null, Map.of());
    String expired = authorize(third, "alice"); fixture.clock.now = fixture.clock.now.plusSeconds(121);
    assertThat(call("GET", expired, cookie(third, "tp_local_login"), null, Map.of()).statusCode()).isEqualTo(401);
    assertThat(fixture.exchanges).hasValue(1);
  }

  @ParameterizedTest @ValueSource(strings = {"nonce", "aud", "iss", "sub", "azp", "exp", "iat", "nbf", "at_hash"})
  void invalidIdTokenCannotBecomeBrowserAuthority(String claim) throws Exception {
    fixture.idClaims = claims -> {
      if (java.util.Set.of("exp", "iat", "nbf").contains(claim)) claims.put(claim, claim.equals("exp") ? 1 : fixture.clock.instant().getEpochSecond() + 600);
      else claims.put(claim, "wrong");
    };
    failedLogin("alice");
  }

  @ParameterizedTest @ValueSource(strings = {"unknown", "robot"})
  void unenrolledOrWorkloadSubjectCannotUseBrowser(String subject) throws Exception { failedLogin(subject); }

  @ParameterizedTest @ValueSource(strings = {"{}", "{\"token_type\":\"Bearer\",\"access_token\":\"bad\",\"id_token\":\"bad\"}", "{\"access_token\":1}", "{} {}"})
  void malformedExchangeResponseIsRedacted(String response) throws Exception {
    fixture.tokenResponse = response; failedLogin("alice");
  }

  @Test void roleRemovalExpiryLogoutAndRestartInvalidateSessionsWithoutDeletingApprovals() throws Exception {
    Login alice = login("alice");
    mutate(alice, "", SimulatorApprovalBrowserFixture.ACTION, 201);
    var out = call("POST", fixture.origin() + "/browser/logout", alice.cookie(), "{}", headers(alice));
    assertThat(out.statusCode()).isEqualTo(200);
    assertThat(call("GET", fixture.origin() + "/browser/session", alice.cookie(), null, Map.of()).statusCode()).isEqualTo(401);
    alice = login("alice"); fixture.restart();
    assertThat(call("GET", fixture.origin() + "/browser/session", alice.cookie(), null, Map.of()).statusCode()).isEqualTo(401);
    alice = login("alice");
    assertThat(json(call("GET", fixture.origin() + ApprovalBrowserAdapter.API, alice.cookie(), null, Map.of())).path("items").size()).isEqualTo(1);
    fixture.clock.now = fixture.clock.now.plusSeconds(291);
    assertThat(call("GET", fixture.origin() + "/browser/session", alice.cookie(), null, Map.of()).statusCode()).isEqualTo(401);
    Login bob = login("bob"); fixture.config.memberships().replace(java.util.List.of());
    assertThat(call("GET", fixture.origin() + "/browser/session", bob.cookie(), null, Map.of()).statusCode()).isEqualTo(403);
  }

  @Test void staticConsoleHasRestrictiveHeadersAndRejectsTraversal() throws Exception {
    var response = call("GET", fixture.origin() + "/approvals", null, null, Map.of("Sec-Fetch-Site", "cross-site"));
    // Dist is built by pnpm check before Java tests; standalone test can omit build.
    if (java.nio.file.Files.exists(Path.of(System.getProperty("tracepermit.repositoryRoot"), "apps/console/dist/index.html"))) {
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("root");
      assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("frame-ancestors 'none'");
    }
    assertThat(call("GET", fixture.origin() + "/assets/..%2findex.html", null, null, Map.of()).statusCode()).isEqualTo(400);
    assertThat(call("GET", fixture.origin() + "/assets/missing.js", null, null, Map.of()).statusCode()).isEqualTo(404);
  }

  private Login login(String subject) throws Exception {
    var start = call("GET", fixture.origin() + "/auth/login", null, null, Map.of());
    var callback = call("GET", authorize(start, subject), cookie(start, "tp_local_login"), null, Map.of());
    assertThat(callback.statusCode()).describedAs(callback.body()).isEqualTo(303);
    String session = cookie(callback, "tp_local_session");
    assertThat(callback.headers().allValues("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax");
    var response = call("GET", fixture.origin() + "/browser/session", session, null, Map.of());
    assertThat(response.statusCode()).isEqualTo(200);
    return new Login(session, json(response));
  }
  private void failedLogin(String subject) throws Exception {
    var start = call("GET", fixture.origin() + "/auth/login", null, null, Map.of());
    var result = call("GET", authorize(start, subject), cookie(start, "tp_local_login"), null, Map.of());
    assertThat(result.statusCode()).describedAs(result.body()).isEqualTo(401);
    assertThat(result.body()).isEqualTo("{\"error\":{\"code\":\"BROWSER_LOGIN_FAILED\"}}");
    assertThat(result.headers().allValues("Set-Cookie").toString()).doesNotContain("tp_local_session=");
  }
  private String authorize(HttpResponse<String> start, String subject) throws Exception {
    String url = start.headers().firstValue("Location").orElseThrow() + "&synthetic_subject=" + subject;
    var result = call("GET", url, null, null, Map.of());
    assertThat(result.statusCode()).isEqualTo(303);
    return result.headers().firstValue("Location").orElseThrow();
  }
  private JsonNode mutate(Login login, String suffix, String body, int expected) throws Exception {
    var response = call("POST", fixture.origin() + ApprovalBrowserAdapter.API + suffix, login.cookie(), body, headers(login));
    assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(expected);
    return json(response);
  }
  private Map<String, String> headers(Login login) { return Map.of("Origin", fixture.origin(), "X-CSRF-Token", login.session().path("csrfToken").asText()); }
  private static String cookie(HttpResponse<String> response, String name) {
    return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow().split(";", 2)[0];
  }
  private static JsonNode json(HttpResponse<String> response) throws Exception { return JsonSupport.MAPPER.readTree(response.body()); }
  private static HttpResponse<String> call(String method, String url, String cookie, String body, Map<String, String> headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
    if (cookie != null) builder.header("Cookie", cookie);
    if (body != null) builder.header("Content-Type", "application/json");
    headers.forEach(builder::header);
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
