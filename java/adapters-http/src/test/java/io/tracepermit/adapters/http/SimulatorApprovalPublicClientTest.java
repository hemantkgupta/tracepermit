package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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

/** Synthetic HTTP + signed tokens + actual durable simulator boundary, never an Auth0 tenant. */
final class SimulatorApprovalPublicClientTest {
  static PostgreSQLContainer<?> postgres;
  static HttpClient client;
  @TempDir Path directory;
  SimulatorApprovalBrowserFixture fixture;
  // Deliberately no generated credential-bearing record/toString.
  static final class Flow {
    String binder;
    String callback;
    Map<String, String> parameters;
  }
  static final class Login {
    String cookie;
    String csrf;
    JsonNode session;
  }

  @BeforeAll static void prerequisites() {
    postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
        .withPassword(java.util.UUID.randomUUID().toString());
    postgres.start();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }
  @AfterAll static void finish() { if (client != null) client.close(); if (postgres != null) postgres.stop(); }
  @BeforeEach void start() throws Exception { fixture = new SimulatorApprovalBrowserFixture(directory, postgres, true); }
  @AfterEach void stop() { if (fixture != null) fixture.close(); }

  @Test void publicLoginWithoutOpenidInAccessScopeStillRequiresIndependentReviewAndOneUseExecution() throws Exception {
    Login alice = login("alice"), bob = login("bob");
    assertThat(alice.session.path("scopes").toString().contains("openid")).isFalse();
    var created = json(mutate(alice, "", SimulatorApprovalBrowserFixture.ACTION, 201));
    String path = "/" + created.path("approvalRequestId").asText();
    String hash = "{\"requestHash\":\"" + created.path("requestHash").asText() + "\"}";
    mutate(alice, path + "/execute", hash, 409);
    mutate(alice, path + "/decision", hash.replace("}", ",\"decision\":\"approve\"}"), 403);
    mutate(bob, path + "/decision", hash.replace("}", ",\"decision\":\"approve\"}"), 200);
    assertThat(json(mutate(alice, path + "/execute", hash, 200)).path("executionStatus").asText()).isEqualTo("succeeded");
    mutate(alice, path + "/execute", hash, 409);
    assertThat(fixture.tokenPosts).hasValue(2);
  }

  @ParameterizedTest @ValueSource(strings = {"iss", "aud", "client_id", "org_id", "missing_org", "sub", "scope", "unknown_scope", "exp", "iat", "nbf", "alg", "kid", "typ", "default_typ", "signature"})
  void invalidApiCredentialCannotCreateSessionOrDurableAuthority(String claim) throws Exception {
    if (java.util.Set.of("alg", "kid", "typ").contains(claim)) fixture.accessHeader = h -> h.put(claim, "unqualified");
    else if ("default_typ".equals(claim)) fixture.accessHeader = h -> h.put("typ", "JWT");
    else if ("signature".equals(claim)) fixture.signingKey = java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair();
    else fixture.accessClaims = c -> {
      if ("exp".equals(claim)) c.put(claim, 1);
      else if ("iat".equals(claim) || "nbf".equals(claim)) c.put(claim, fixture.clock.instant().plusSeconds(600).getEpochSecond());
      else if ("scope".equals(claim)) c.put(claim, "approvals.request");
      else if ("unknown_scope".equals(claim)) c.put("scope", "approvals.read unqualified");
      else if ("missing_org".equals(claim)) c.remove("org_id");
      else c.put(claim, "wrong");
    };
    rejected(flow("alice"));
  }

  @ParameterizedTest @ValueSource(strings = {"nonce", "iss", "aud", "org_id", "missing_org", "sub", "azp", "exp", "iat", "nbf", "at_hash", "alg", "kid"})
  void independentIdTokenBindingsFailClosed(String claim) throws Exception {
    if ("alg".equals(claim) || "kid".equals(claim)) fixture.idHeader = h -> h.put(claim, "unqualified");
    else fixture.idClaims = c -> {
      if ("missing_org".equals(claim)) c.remove("org_id");
      else if ("exp".equals(claim)) c.put(claim, 1);
      else if ("iat".equals(claim) || "nbf".equals(claim)) c.put(claim, fixture.clock.instant().plusSeconds(600).getEpochSecond());
      else c.put(claim, "wrong");
    };
    rejected(flow("alice"));
  }

  @ParameterizedTest @ValueSource(strings = {"state", "iss", "missing_iss", "duplicate", "unknown", "error", "empty_code", "malformed"})
  void invalidAuthorizationResponsesNeverReachTokenEndpoint(String change) throws Exception {
    Flow flow = flow("alice");
    var query = new HashMap<>(SimulatorApprovalBrowserFixture.parse(URI.create(flow.callback).getRawQuery()));
    switch (change) {
      case "state", "iss" -> query.put(change, "wrong");
      case "missing_iss" -> query.remove("iss");
      case "unknown" -> query.put("organization", "org_attacker");
      case "error" -> { query.remove("code"); query.put("error", "access_denied"); }
      case "empty_code" -> query.put("code", "");
      default -> { }
    }
    flow.callback = fixture.origin() + "/auth/callback?" + ApprovalBrowserAdapter.form(query);
    if ("duplicate".equals(change)) flow.callback += "&state=duplicate";
    if ("malformed".equals(change)) flow.callback += "&invalid";
    var response = call(flow.callback, flow.binder);
    assertThat(response.statusCode() == 400 || response.statusCode() == 401).isTrue();
    assertThat(response.body().contains("wrong") || response.body().contains("access_denied")).isFalse();
    assertThat(fixture.tokenPosts).hasValue(0);
    noAuthority();
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "mutated", "redirect", "secret"})
  void missingOrMutatedVerifierRedirectOrSecretInjectionNeverAuthenticates(String change) throws Exception {
    fixture.exchangeForm = form -> {
      switch (change) {
        case "missing" -> form.remove("code_verifier");
        case "mutated" -> form.put("code_verifier", "A".repeat(43));
        case "redirect" -> form.put("redirect_uri", "http://127.0.0.1:1/auth/callback");
        case "secret" -> form.put("client_secret", java.util.UUID.randomUUID().toString());
        default -> throw new AssertionError();
      }
    };
    rejected(flow("alice"));
    assertThat(fixture.tokenPosts).hasValue("mutated".equals(change) ? 1 : 0);
  }

  @Test void swappedCodeWrongBrowserCookieAndReplayedIdTokenCannotSignIn() throws Exception {
    Flow first = flow("alice"), second = flow("bob");
    assertThat(call(first.callback, second.binder).statusCode()).isEqualTo(401);
    assertThat(fixture.tokenPosts).hasValue(0);
    var query = SimulatorApprovalBrowserFixture.parse(URI.create(first.callback).getRawQuery());
    query.put("code", SimulatorApprovalBrowserFixture.parse(URI.create(second.callback).getRawQuery()).get("code"));
    first.callback = fixture.origin() + "/auth/callback?" + ApprovalBrowserAdapter.form(query);
    rejected(first); // First flow's verifier cannot redeem second flow's code.
    Login valid = login("alice");
    fixture.tokenResponse = fixture.lastTokenResponse; // Old ID token has a different login nonce.
    rejected(flow("alice"));
    assertThat(call(fixture.origin() + "/browser/session", valid.cookie).statusCode()).isEqualTo(200);
  }

  @Test void idTokenIsMandatoryEvenWithValidApiTokenAndOpenidEchoIsOptional() throws Exception {
    login("alice");
    var response = (com.fasterxml.jackson.databind.node.ObjectNode)
        io.tracepermit.contracts.JsonSupport.MAPPER.readTree(fixture.lastTokenResponse);
    response.remove("id_token"); fixture.tokenResponse = response.toString();
    rejected(flow("alice"));
  }

  @Test void optionalOpenidEchoIsAllowlistedButGrantsNoMutationScope() throws Exception {
    fixture.accessClaims = claims -> claims.put("scope", "openid approvals.read");
    Login login = login("alice");
    mutate(login, "", SimulatorApprovalBrowserFixture.ACTION, 403);
    noAuthority();
  }

  @Test void exchangeExceptionsCannotLeakGeneratedCredentialsIntoResponsesOrLogs() throws Exception {
    Login login = login("alice");
    Flow flow = flow("alice");
    var canaries = new ArrayList<String>();
    canaries.add(login.cookie); canaries.add(flow.binder);
    JsonNode tokens = io.tracepermit.contracts.JsonSupport.MAPPER.readTree(fixture.lastTokenResponse);
    canaries.add(tokens.path("access_token").asText()); canaries.add(tokens.path("id_token").asText());
    // Runtime-only synthetic password/client-secret canaries, never real credentials.
    canaries.add(java.util.UUID.randomUUID().toString()); canaries.add(java.util.UUID.randomUUID().toString());
    fixture.exchangeForm = form -> {
      canaries.add(form.get("code")); canaries.add(form.get("code_verifier"));
      throw new IllegalStateException(String.join(" ", canaries));
    };
    var output = new java.io.ByteArrayOutputStream();
    var errors = new java.io.ByteArrayOutputStream();
    var previousOut = System.out; var previousErr = System.err;
    HttpResponse<String> response;
    try (var out = new java.io.PrintStream(output); var err = new java.io.PrintStream(errors)) {
      System.setOut(out); System.setErr(err);
      response = call(flow.callback, flow.binder);
    } finally { System.setOut(previousOut); System.setErr(previousErr); }
    assertThat(response.statusCode()).isEqualTo(401);
    String projected = response.body() + response.headers() + output + errors;
    assertThat(canaries.stream().noneMatch(projected::contains)).isTrue();
    noAuthority();
  }

  @Test void expiredFlowNeverExchangesAndExpiredSessionCannotRead() throws Exception {
    fixture.clock.now = java.time.Instant.now();
    Flow expired = flow("alice");
    fixture.clock.now = fixture.clock.instant().plusSeconds(121);
    rejected(expired);
    assertThat(fixture.tokenPosts).hasValue(0);
    Login login = login("alice");
    fixture.clock.now = fixture.clock.instant().plusSeconds(291);
    assertThat(call(fixture.origin() + "/browser/session", login.cookie).statusCode()).isEqualTo(401);
  }

  @Test void concurrentCallbackReplayHasExactlyOneExchangeAndOneSession() throws Exception {
    Flow flow = flow("alice");
    var ready = new CountDownLatch(8); var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(8);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
        ready.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
        return call(flow.callback, flow.binder).statusCode();
      }));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); release.countDown();
      var statuses = new ArrayList<Integer>();
      for (var future : futures) statuses.add(future.get(15, TimeUnit.SECONDS));
      assertThat(statuses.stream().filter(s -> s == 303).count()).isEqualTo(1);
      assertThat(statuses.stream().filter(s -> s == 401).count()).isEqualTo(7);
      assertThat(fixture.tokenPosts).hasValue(1); assertThat(fixture.exchanges).hasValue(1);
      noAuthority();
    } finally {
      release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Flow flow(String subject) throws Exception {
    var start = call(fixture.origin() + "/auth/login", null);
    assertThat(start.statusCode()).isEqualTo(303);
    String location = start.headers().firstValue("Location").orElseThrow();
    Flow flow = new Flow();
    flow.parameters = SimulatorApprovalBrowserFixture.parse(URI.create(location).getRawQuery());
    assertThat(flow.parameters.get("response_type")).isEqualTo("code");
    assertThat(flow.parameters.get("code_challenge_method")).isEqualTo("S256");
    assertThat(flow.parameters.get("organization")).isEqualTo("org_synthetic");
    assertThat(flow.parameters.get("redirect_uri").equals(fixture.origin() + "/auth/callback")).isTrue();
    assertThat(flow.parameters.get("scope").split(" ")).contains("openid", "approvals.read");
    assertThat(flow.parameters.containsKey("client_secret") || flow.parameters.containsKey("code_verifier")).isFalse();
    flow.binder = cookie(start, "tp_local_login");
    flow.callback = call(location + "&synthetic_subject=" + subject, null).headers().firstValue("Location").orElseThrow();
    return flow;
  }
  private Login login(String subject) throws Exception {
    Flow flow = flow(subject); var response = call(flow.callback, flow.binder);
    assertThat(response.statusCode()).isEqualTo(303);
    assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo("/approvals");
    Login login = new Login(); login.cookie = cookie(response, "tp_local_session");
    login.session = json(call(fixture.origin() + "/browser/session", login.cookie));
    assertThat(login.session.has("csrfToken")).isTrue();
    assertThat(login.session.has("access_token") || login.session.has("id_token")).isFalse();
    login.csrf = login.session.path("csrfToken").asText(); return login;
  }
  private void rejected(Flow flow) throws Exception {
    var response = call(flow.callback, flow.binder);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).isEqualTo("{\"error\":{\"code\":\"BROWSER_LOGIN_FAILED\"}}");
    assertThat(response.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("tp_local_session="))).isFalse();
    assertThat(call(flow.callback, flow.binder).statusCode()).isEqualTo(401);
    assertThat(call(fixture.origin() + "/browser/session", null).statusCode()).isEqualTo(401);
    noAuthority();
  }
  private void noAuthority() {
    assertThat(fixture.state.readEvidence(new io.tracepermit.execution.TenantPartition("simulated-alpha"))).isEmpty();
    assertThat(fixture.simulator.snapshot().path("aws").path("securityGroups").path("sg-demo").size()).isZero();
  }
  private HttpResponse<String> mutate(Login login, String suffix, String body, int status) throws Exception {
    var response = client.send(HttpRequest.newBuilder(URI.create(fixture.origin() + ApprovalBrowserAdapter.API + suffix))
        .timeout(Duration.ofSeconds(10)).header("Cookie", login.cookie).header("Origin", fixture.origin())
        .header("X-CSRF-Token", login.csrf).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(status); return response;
  }
  private static HttpResponse<String> call(String url, String cookie) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
    if (cookie != null) request.header("Cookie", cookie);
    return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }
  private static String cookie(HttpResponse<String> response, String name) {
    return response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith(name + "="))
        .findFirst().orElseThrow().split(";", 2)[0];
  }
  private static JsonNode json(HttpResponse<String> response) throws Exception {
    return io.tracepermit.contracts.JsonSupport.MAPPER.readTree(response.body());
  }
}
