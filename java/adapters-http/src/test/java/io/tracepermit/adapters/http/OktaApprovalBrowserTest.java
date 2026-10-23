package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Generated signatures, real HTTP and durable simulator state; never a live Okta tenant. */
final class OktaApprovalBrowserTest {
  static PostgreSQLContainer<?> postgres;
  static HttpClient client;
  @TempDir Path directory;
  SimulatorApprovalBrowserFixture fixture;
  private static final class Flow { String binder; String callback; }
  private static final class Login { String cookie; String csrf; JsonNode session; }

  @BeforeAll static void prerequisites() {
    postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
        .withPassword(java.util.UUID.randomUUID().toString());
    postgres.start();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }
  @AfterAll static void finish() { if (client != null) client.close(); if (postgres != null) postgres.stop(); }
  @BeforeEach void start() throws Exception {
    fixture = new SimulatorApprovalBrowserFixture(directory, postgres, true, true);
    fixture.clock.now = java.time.Instant.now();
  }
  @AfterEach void stop() { if (fixture != null) fixture.close(); }

  @Test void requesterReviewerViewerAndNarrowedEnrollmentUseTheExistingDurableBoundary() throws Exception {
    Login alice = login("alice"), bob = login("bob"), viewer = login("carol"), combined = login("dave");
    assertThat(alice.session.path("roles").get(0).asText()).isEqualTo("REQUESTER");
    assertThat(bob.session.path("roles").get(0).asText()).isEqualTo("REVIEWER");
    assertThat(viewer.session.path("roles").get(0).asText()).isEqualTo("VIEWER");
    assertThat(combined.session.path("roles")).hasSize(1); // Signed group attenuates dual enrollment.
    var created = mutate(alice, "", SimulatorApprovalBrowserFixture.ACTION, 201);
    String suffix = "/" + created.path("approvalRequestId").asText();
    String hash = "{\"requestHash\":\"" + created.path("requestHash").asText() + "\"}";
    String decision = hash.replace("}", ",\"decision\":\"approve\"}");
    mutate(viewer, "", SimulatorApprovalBrowserFixture.ACTION, 403);
    mutate(bob, "", SimulatorApprovalBrowserFixture.ACTION, 403);
    mutate(viewer, suffix + "/decision", decision, 403);
    mutate(alice, suffix + "/decision", decision, 403);
    mutate(alice, suffix + "/decision", decision.replace("}", ",\"roles\":[\"REVIEWER\"]}"), 403);
    mutate(bob, suffix + "/execute", hash, 403);
    mutate(bob, suffix + "/decision", decision, 200);
    assertThat(mutate(alice, suffix + "/execute", hash, 200).path("executionStatus").asText()).isEqualTo("succeeded");
    mutate(alice, suffix + "/execute", hash, 409);
    assertThat(fixture.simulator.snapshot().path("aws").path("securityGroups").path("sg-demo").size()).isEqualTo(1);
    assertThat(fixture.tokenPosts).hasValue(4);
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "unknown", "ambiguous", "duplicate", "membership_mismatch",
      "raw_roles", "id_group", "id_missing_group", "id_tenant", "issuer", "audience", "client", "tenant",
      "scope", "signature", "kid", "nonce", "expired"})
  void invalidGroupsIdentityAndScopeNeverCreateSessionOrAuthority(String change) throws Exception {
    switch (change) {
      case "signature" -> fixture.signingKey = java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair();
      case "kid" -> fixture.accessHeader = h -> h.put("kid", "unknown-key");
      case "id_group" -> fixture.idClaims = c -> c.putArray("groups").add("pilot-reviewers");
      case "id_missing_group" -> fixture.idClaims = c -> c.remove("groups");
      case "id_tenant" -> fixture.idClaims = c -> c.put("tracepermit_tenant", "wrong");
      case "nonce" -> fixture.idClaims = c -> c.put("nonce", "wrong");
      default -> fixture.accessClaims = c -> {
        switch (change) {
          case "missing" -> c.remove("groups");
          case "unknown" -> c.putArray("groups").add("unmapped");
          case "ambiguous" -> c.putArray("groups").add("pilot-requesters").add("pilot-reviewers");
          case "duplicate" -> c.putArray("groups").add("pilot-requesters").add("pilot-requesters");
          case "membership_mismatch" -> c.putArray("groups").add("pilot-reviewers");
          case "raw_roles" -> { c.putArray("groups").add("unmapped"); c.putArray("roles").add("REVIEWER"); }
          case "issuer" -> c.put("iss", "https://wrong.example.invalid/oauth2/synthetic");
          case "audience" -> c.put("aud", "https://wrong.example.invalid");
          case "client" -> c.put("cid", "wrong");
          case "tenant" -> c.put("tracepermit_tenant", "wrong");
          case "scope" -> c.putArray("scp").add("approvals.request");
          case "expired" -> c.put("exp", 1);
          default -> throw new AssertionError();
        }
      };
    }
    rejected(flow("alice"));
  }

  @ParameterizedTest @ValueSource(strings = {"outsider", "robot"})
  void mappedGroupsDoNotReplaceEnrollmentOrHumanStatus(String subject) throws Exception {
    rejected(flow(subject));
  }

  @Test void groupRemovalAffectsFreshTokensAndOperatorRemovalRevokesExistingSessions() throws Exception {
    Login existing = login("alice");
    fixture.accessClaims = c -> c.putArray("groups").add("unmapped");
    rejected(flow("alice"));
    // Immutable old tokens retain their signed groups until expiry; this is not live introspection.
    assertThat(get(fixture.origin() + "/browser/session", existing.cookie).statusCode()).isEqualTo(200);
    fixture.clock.now = fixture.clock.instant().plusSeconds(291);
    assertThat(get(fixture.origin() + "/browser/session", existing.cookie).statusCode()).isEqualTo(401);
    fixture.accessClaims = c -> {};
    Login reviewer = login("bob");
    fixture.config.memberships().replace(List.of());
    assertThat(get(fixture.origin() + "/browser/session", reviewer.cookie).statusCode()).isEqualTo(403);
    noAuthority();
  }

  @ParameterizedTest @ValueSource(strings = {"state", "issuer", "redirect", "secret", "verifier", "code"})
  void callbackAndExchangeSubstitutionAreConsumedWithoutRetry(String change) throws Exception {
    Flow flow = flow("alice");
    if (change.equals("state") || change.equals("issuer") || change.equals("code")) {
      var query = SimulatorApprovalBrowserFixture.parse(URI.create(flow.callback).getRawQuery());
      query.put(change.equals("issuer") ? "iss" : change, "wrong");
      flow.callback = fixture.origin() + "/auth/callback?" + ApprovalBrowserAdapter.form(query);
    } else fixture.exchangeForm = form -> {
      if (change.equals("redirect")) form.put("redirect_uri", "http://127.0.0.1:1/auth/callback");
      else if (change.equals("secret")) form.put("client_secret", "synthetic-canary");
      else form.put("code_verifier", "A".repeat(43));
    };
    rejected(flow);
    assertThat(fixture.tokenPosts.get()).isLessThanOrEqualTo(1);
  }

  @Test void mixedAuthenticationAndCrossOriginCannotUseAValidSession() throws Exception {
    Login alice = login("alice");
    var request = HttpRequest.newBuilder(URI.create(fixture.origin() + "/browser/session"))
        .timeout(Duration.ofSeconds(10)).header("Cookie", alice.cookie)
        .header("Authorization", "Bearer synthetic-untrusted").GET().build();
    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    request = HttpRequest.newBuilder(URI.create(fixture.origin() + "/browser/session"))
        .timeout(Duration.ofSeconds(10)).header("Cookie", alice.cookie)
        .header("Origin", "https://other.example.invalid").GET().build();
    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    noAuthority();
  }

  @Test void mappedRolesCannotSupplyMissingOperationScopes() throws Exception {
    fixture.accessClaims = c -> c.putArray("scp").add("openid").add("groups").add("approvals.read");
    Login alice = login("alice"), bob = login("bob");
    mutate(alice, "", SimulatorApprovalBrowserFixture.ACTION, 403);
    // Required decide scope is checked before a caller can inspect or alter the requested decision.
    mutate(bob, "/00000000-0000-0000-0000-000000000000/decision",
        "{\"requestHash\":\"unused\",\"decision\":\"approve\"}", 403);
    noAuthority();
  }

  @Test void concurrentCallbackReplayHasOneExchangeAndNoCredentialDiagnostics() throws Exception {
    Flow flow = flow("alice");
    var ready = new CountDownLatch(8); var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(8);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
        ready.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
        return get(flow.callback, flow.binder).statusCode();
      }));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); release.countDown();
      var statuses = new ArrayList<Integer>();
      for (var future : futures) statuses.add(future.get(15, TimeUnit.SECONDS));
      assertThat(statuses.stream().filter(s -> s == 303).count()).isEqualTo(1);
      assertThat(statuses.stream().filter(s -> s == 401).count()).isEqualTo(7);
      assertThat(fixture.tokenPosts).hasValue(1);
      assertThat(fixture.exchanges).hasValue(1);
      assertThat(fixture.loginDiagnosticLines).allMatch(line -> line.matches(
          "BROWSER_LOGIN_DIAGNOSTIC stage=[A-Z_]+ reason=[A-Z_]+"));
      assertThat(fixture.loginDiagnosticLines.stream().noneMatch(line -> line.contains(flow.binder)
          || line.contains(flow.callback) || line.contains(fixture.lastTokenResponse))).isTrue();
    } finally {
      release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    noAuthority();
  }

  private Flow flow(String subject) throws Exception {
    var start = get(fixture.origin() + "/auth/login", null);
    assertThat(start.statusCode()).isEqualTo(303);
    String url = start.headers().firstValue("Location").orElseThrow();
    var params = SimulatorApprovalBrowserFixture.parse(URI.create(url).getRawQuery());
    assertThat(params).doesNotContainKeys("audience", "organization", "client_secret", "code_verifier");
    assertThat(params.get("scope").split(" ")).contains("openid", "groups", "approvals.read");
    assertThat(params.get("code_challenge_method")).isEqualTo("S256");
    assertThat(params.get("redirect_uri").equals(fixture.origin() + "/auth/callback")).isTrue();
    Flow flow = new Flow(); flow.binder = cookie(start, "tp_local_login");
    flow.callback = get(url + "&synthetic_subject=" + subject, null).headers().firstValue("Location").orElseThrow();
    return flow;
  }

  private Login login(String subject) throws Exception {
    Flow flow = flow(subject); var response = get(flow.callback, flow.binder);
    assertThat(response.statusCode()).isEqualTo(303);
    Login login = new Login(); login.cookie = cookie(response, "tp_local_session");
    var result = get(fixture.origin() + "/browser/session", login.cookie);
    assertThat(result.statusCode()).isEqualTo(200); login.session = JsonSupport.MAPPER.readTree(result.body());
    login.csrf = login.session.path("csrfToken").asText(); return login;
  }

  private void rejected(Flow flow) throws Exception {
    var response = get(flow.callback, flow.binder);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).isEqualTo("{\"error\":{\"code\":\"BROWSER_LOGIN_FAILED\"}}");
    assertThat(response.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("tp_local_session="))).isFalse();
    assertThat(get(flow.callback, flow.binder).statusCode()).isEqualTo(401);
    noAuthority();
  }

  private void noAuthority() {
    assertThat(fixture.state.readEvidence(new TenantPartition("simulated-alpha"))).isEmpty();
    assertThat(fixture.simulator.snapshot().path("aws").path("securityGroups").path("sg-demo").size()).isZero();
  }

  private JsonNode mutate(Login login, String suffix, String body, int expected) throws Exception {
    var response = client.send(HttpRequest.newBuilder(URI.create(fixture.origin() + ApprovalBrowserAdapter.API + suffix))
        .timeout(Duration.ofSeconds(10)).header("Cookie", login.cookie).header("Origin", fixture.origin())
        .header("X-CSRF-Token", login.csrf).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(expected); return JsonSupport.MAPPER.readTree(response.body());
  }

  private static HttpResponse<String> get(String url, String cookie) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
    if (cookie != null) request.header("Cookie", cookie);
    return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String cookie(HttpResponse<String> response, String name) {
    return response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith(name + "="))
        .findFirst().orElseThrow().split(";", 2)[0];
  }
}
