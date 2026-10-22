package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.HumanApprovalService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.persistence.postgres.PostgresPermitConsumptionStore;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Actual authenticated response boundaries; canaries are never usable credentials. */
final class SimulatorApprovalRedactionBoundaryTest {
  private static final TenantPartition TENANT = new TenantPartition("simulated-alpha");
  private static final String CANARY = "synthetic-projection-canary-not-a-credential";
  private static final String API = "/api/v1/approvals";
  private static PostgreSQLContainer<?> postgres;
  private static HttpClient client;
  @TempDir Path directory;
  private SimulatorApprovalBrowserFixture fixture;

  private record Stored(String id, String requestHash, JsonNode original) {}
  private record Login(String cookie, JsonNode session) {}

  @BeforeAll static void prerequisites() {
    postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
        .withPassword("synthetic-redaction-password");
    postgres.start();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterAll static void finish() {
    if (client != null) client.close();
    if (postgres != null) postgres.stop();
  }

  @BeforeEach void start() throws Exception {
    fixture = new SimulatorApprovalBrowserFixture(directory, postgres);
    fixture.clock.now = Instant.parse("2030-01-01T00:00:00Z");
  }

  @AfterEach void stop() { if (fixture != null) fixture.close(); }

  @Test void httpAndMcpReadSurfacesMaskTheSameActionWithoutChangingAuthority() throws Exception {
    Stored stored = seedCanaries();
    var evidence = fixture.state.readEvidence(TENANT);
    String bearer = fixture.accessToken("alice");
    var exported = api("GET", API + "/" + stored.id() + "/export", bearer, null, 200);
    assertNoCanaries(exported.body());
    assertThat(json(exported).path("payload").path("approval").has("action")).isFalse();
    JsonNode detail = json(api("GET", API + "/" + stored.id(), bearer, null, 200));
    assertProjection(detail, stored);
    JsonNode page = json(api("GET", API, bearer, null, 200));
    assertProjection(item(page, stored.id()), stored);
    JsonNode rpc = rpc(bearer, ApprovalMcpAdapter.STATUS, statusArgs(stored.id()));
    assertThat(rpc.path("result").path("isError").asBoolean()).isFalse();
    assertProjection(rpc.path("result").path("structuredContent"), stored);
    // MCP has two payload representations; neither may retain the original canaries.
    assertNoCanaries(rpc.toString());
    assertProjection(JsonSupport.MAPPER.readTree(rpc.path("result").path("content")
        .get(0).path("text").asText()), stored);
    ((ObjectNode) detail.path("action").path("arguments")).put("port", 8443);
    assertProjection(json(api("GET", API + "/" + stored.id(), bearer, null, 200)), stored);
    assertOriginal(stored);
    assertThat(fixture.state.readEvidence(TENANT)).isEqualTo(evidence);
    assertNoCanaries(evidence.toString());
    assertThat(rules()).isZero();
  }

  @Test void browserViewerAndReviewerSeeMaskedDataAndReviewTheOriginalHash() throws Exception {
    Stored stored = seedCanaries();
    for (String subject : List.of("carol", "bob")) {
      Login login = login(subject);
      assertProjection(item(json(browser("GET", "", login, null, 200)), stored.id()), stored);
      assertProjection(json(browser("GET", "/" + stored.id(), login, null, 200)), stored);
      if ("carol".equals(subject)) {
        browser("POST", "/" + stored.id() + "/decision", login, decision(stored, "approve"), 403);
        assertThat(fixture.state.find(TENANT, stored.id()).status()).isEqualTo("pending");
      } else {
        JsonNode approved = json(browser("POST", "/" + stored.id() + "/decision", login,
            decision(stored, "approve"), 200));
        assertProjection(approved, stored);
        assertThat(approved.path("status").asText()).isEqualTo("approved");
      }
    }
    assertOriginal(stored);
    assertThat(rules()).isZero();
  }

  @Test void authorizationAndOwnershipPrecedeProjectionOfAnInvalidSnapshot() throws Exception {
    Stored stored = seed(SimulatorApprovalRedactionBoundaryTest::oversizedNodeArray);
    var evidence = fixture.state.readEvidence(TENANT);
    HttpResponse<String> unauthenticated = api("GET", API + "/" + stored.id(), null, null, 401);
    assertThat(json(unauthenticated).path("error").path("code").asText()).isEqualTo("INVALID_ACCESS_TOKEN");
    api("GET", API + "/" + stored.id(), fixture.accessToken("unknown"), null, 403);
    // This is operator-owned fixture enrollment, not a token/body role assertion.
    fixture.config.memberships().replace(List.of(
        new ApprovalMemberships.Entry(SimulatorApprovalBrowserFixture.ISSUER + "#alice",
            SimulatorApprovalBrowserFixture.CLIENT, TENANT, true, Set.of(ApprovalMemberships.Role.REQUESTER)),
        new ApprovalMemberships.Entry(SimulatorApprovalBrowserFixture.ISSUER + "#erin",
            SimulatorApprovalBrowserFixture.CLIENT, TENANT, true, Set.of(ApprovalMemberships.Role.REQUESTER))));
    HttpResponse<String> notOwned = api("GET", API + "/" + stored.id(), fixture.accessToken("erin"), null, 404);
    assertThat(json(notOwned).path("error").path("code").asText()).isEqualTo("APPROVAL_NOT_FOUND");
    assertThat(structured(rpc(fixture.accessToken("erin"), ApprovalMcpAdapter.STATUS,
        statusArgs(stored.id()))).path("errorCode").asText()).isEqualTo("APPROVAL_NOT_FOUND");
    fixture.accessClaims = claims -> claims.put("scope", "approvals.request");
    api("GET", API + "/" + stored.id(), fixture.accessToken("alice"), null, 403);
    fixture.accessClaims = claims -> {};
    assertOriginal(stored);
    assertThat(fixture.state.readEvidence(TENANT)).isEqualTo(evidence);
    assertThat(rules()).isZero();
  }

  @ParameterizedTest @ValueSource(strings = {"depth", "nodes"})
  void projectionBoundsFailClosedWithoutRawFallbackOrReadSideEffects(String bound) throws Exception {
    Stored stored = seed(arguments -> {
      if ("nodes".equals(bound)) oversizedNodeArray(arguments);
      else {
        ObjectNode nested = arguments.putObject("password");
        for (int i = 0; i < 33; i++) nested = nested.putObject("next");
        nested.put("value", CANARY);
      }
    });
    var evidence = fixture.state.readEvidence(TENANT);
    Login reviewer = login("bob");
    HttpResponse<String> detail = api("GET", API + "/" + stored.id(), fixture.accessToken("alice"), null, 400);
    assertThat(json(detail)).isEqualTo(JsonSupport.MAPPER.readTree("{\"error\":{\"code\":\"INVALID_REQUEST\"}}"));
    assertNoCanaries(detail.body());
    assertNoCanaries(api("GET", API, fixture.accessToken("alice"), null, 400).body());
    assertNoCanaries(browser("GET", "/" + stored.id(), reviewer, null, 400).body());
    JsonNode status = rpc(fixture.accessToken("alice"), ApprovalMcpAdapter.STATUS, statusArgs(stored.id()));
    assertThat(status.path("result").path("isError").asBoolean()).isTrue();
    assertThat(structured(status)).isEqualTo(JsonSupport.MAPPER.readTree(
        "{\"mode\":\"simulated\",\"errorCode\":\"INVALID_REQUEST\"}"));
    assertNoCanaries(status.toString());
    fixture.restart();
    assertNoCanaries(api("GET", API + "/" + stored.id(), fixture.accessToken("alice"), null, 400).body());
    assertOriginal(stored);
    assertThat(fixture.state.find(TENANT, stored.id()).status()).isEqualTo("pending");
    assertThat(fixture.state.readEvidence(TENANT)).isEqualTo(evidence);
    assertThat(rules()).isZero();
  }

  @Test void strictIngressStillRejectsCredentialArgumentsAndCleanApprovalExecutesOnce() throws Exception {
    String bearer = fixture.accessToken("alice");
    ObjectNode injected = (ObjectNode) JsonSupport.MAPPER.readTree(SimulatorApprovalBrowserFixture.ACTION);
    injected.put("AWS_SECRET_ACCESS_KEY", CANARY);
    api("POST", API, bearer, injected.toString(), 400);
    assertThat(rpc(bearer, ApprovalMcpAdapter.REQUEST, injected).path("error").path("message").asText())
        .isEqualTo("INVALID_PARAMS");
    browser("POST", "", login("alice"), injected.toString(), 400);
    assertThat(fixture.state.listApprovals(TENANT, null, 50)).isEmpty();
    assertThat(fixture.state.readEvidence(TENANT)).isEmpty();
    assertThat(rules()).isZero();

    JsonNode pending = structured(rpc(bearer, ApprovalMcpAdapter.REQUEST,
        JsonSupport.MAPPER.readTree(SimulatorApprovalBrowserFixture.ACTION)));
    String id = pending.path("approvalRequestId").asText();
    var original = fixture.state.findAction(TENANT, id);
    Stored stored = new Stored(id, pending.path("requestHash").asText(), original);
    String wrongHash = "{\"requestHash\":\"sha256:" + "0".repeat(64) + "\",\"decision\":\"approve\"}";
    browser("POST", "/" + id + "/decision", login("bob"), wrongHash, 409);
    browser("POST", "/" + id + "/decision", login("bob"), decision(stored, "approve"), 200);
    ObjectNode resume = statusArgs(id).put("requestHash", stored.requestHash());
    JsonNode executed = structured(rpc(bearer, ApprovalMcpAdapter.RESUME, resume));
    assertThat(executed.path("status").asText()).isEqualTo("consumed");
    assertThat(executed.path("executionStatus").asText()).isEqualTo("succeeded");
    assertThat(rules()).isEqualTo(1);
    fixture.restart();
    assertThat(structured(rpc(bearer, ApprovalMcpAdapter.RESUME, resume)).path("errorCode").asText())
        .isEqualTo("APPROVAL_NOT_EXECUTABLE");
    assertOriginal(stored);
    assertThat(rules()).isEqualTo(1);
  }

  private Stored seedCanaries() throws Exception {
    return seed(arguments -> {
      arguments.put("AWS_SECRET_ACCESS_KEY", CANARY + "-aws");
      arguments.put("description", "Bearer " + CANARY + "-bearer");
      arguments.put("aws_region", "simulated-region").put("session_token_count", 2);
      arguments.putObject("headers").put("X-Api-Key", CANARY + "-header");
      arguments.putArray("nested").addObject().put("refreshToken", CANARY + "-nested");
    });
  }

  private static void oversizedNodeArray(ObjectNode arguments) {
    var nodes = arguments.putArray("password");
    for (int i = 0; i < 10001; i++) nodes.addNull();
    nodes.add(CANARY);
  }

  /**
   * Test-only internal fixture: strict public ingress cannot accept these extra fields.
   * Start from an actually authenticated canonical snapshot, then let the real service
   * derive a fresh exact binding. Never rewrite a database row or execute this fixture.
   */
  private Stored seed(Consumer<ObjectNode> arguments) throws Exception {
    JsonNode baseline = structured(rpc(fixture.accessToken("alice"), ApprovalMcpAdapter.REQUEST,
        JsonSupport.MAPPER.readTree(SimulatorApprovalBrowserFixture.ACTION)));
    ObjectNode event = (ObjectNode) fixture.state.findAction(TENANT, baseline.path("approvalRequestId").asText());
    event.put("eventId", UUID.randomUUID().toString());
    arguments.accept((ObjectNode) event.path("action").path("arguments"));
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER,
        fixture.config.keyId(), fixture.config.permitKey(), 30, fixture.clock,
        () -> UUID.randomUUID().toString(), new PostgresPermitConsumptionStore(fixture.config.database()));
    var service = new HumanApprovalService(new PolicyEvaluator(), SimulatorApprovalMain.policy(), permits,
        fixture.state, fixture.state, fixture.simulator,
        () -> SecurityReadiness.allReady(fixture.clock.instant()), fixture.clock,
        () -> UUID.randomUUID().toString(), "urn:tracepermit:approval-api:simulator", "simulator", 300);
    var approval = service.requestApprovalWithSnapshot(new TrustedTenantContext(TENANT), event).approval();
    Stored stored = new Stored(approval.binding().approvalRequestId(), approval.binding().requestHash(), event.deepCopy());
    assertOriginal(stored);
    return stored;
  }

  private void assertOriginal(Stored stored) {
    assertThat(fixture.state.findAction(TENANT, stored.id())).isEqualTo(stored.original());
    var binding = fixture.state.find(TENANT, stored.id()).binding();
    assertThat(binding.actionHash()).isEqualTo(CanonicalJson.sha256(stored.original()));
    assertThat(binding.requestHash()).isEqualTo(stored.requestHash());
  }

  private static void assertProjection(JsonNode projection, Stored stored) {
    assertNoCanaries(projection.toString());
    assertThat(projection.path("approvalRequestId").asText()).isEqualTo(stored.id());
    assertThat(projection.path("requestHash").asText()).isEqualTo(stored.requestHash());
    assertThat(projection.path("requesterId")).isEqualTo(stored.original().path("identity").path("subject").path("id"));
    assertThat(projection.path("policyId").asText()).isEqualTo("pol-local-simulator-approval");
    assertThat(projection.path("policyVersion").asInt()).isEqualTo(2);
    assertThat(projection.path("executionStatus").asText()).isEqualTo("not_executed");
    JsonNode args = projection.path("action").path("arguments");
    assertThat(args.path("AWS_SECRET_ACCESS_KEY").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(args.path("headers").path("X-Api-Key").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(args.path("nested").get(0).path("refreshToken").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(args.path("description").asText()).isEqualTo("[REDACTED:bearer_token]");
    assertThat(args.path("aws_region").asText()).isEqualTo("simulated-region");
    assertThat(args.path("session_token_count").asInt()).isEqualTo(2);
    assertThat(args.path("port").asInt()).isEqualTo(443);
    assertThat(args.path("cidr").asText()).isEqualTo("192.0.2.0/24");
    assertThat(projection.path("action")).isNotEqualTo(stored.original().path("action"));
  }

  private static void assertNoCanaries(String text) { assertThat(text).doesNotContain(CANARY); }
  private static ObjectNode statusArgs(String id) {
    return JsonSupport.MAPPER.createObjectNode().put("approvalRequestId", id);
  }
  private static JsonNode structured(JsonNode rpc) { return rpc.path("result").path("structuredContent"); }
  private static JsonNode item(JsonNode page, String id) {
    for (JsonNode item : page.path("items")) if (id.equals(item.path("approvalRequestId").asText())) return item;
    throw new AssertionError("The synthetic approval was missing from the response.");
  }
  private static String decision(Stored stored, String outcome) {
    return JsonSupport.MAPPER.createObjectNode().put("requestHash", stored.requestHash())
        .put("decision", outcome).toString();
  }
  private int rules() { return fixture.simulator.snapshot().path("aws").path("securityGroups").path("sg-demo").size(); }

  private HttpResponse<String> api(String method, String path, String bearer, String body, int expected) throws Exception {
    return checked(method, fixture.origin() + path, null, body,
        bearer == null ? Map.of() : Map.of("Authorization", "Bearer " + bearer), expected);
  }
  private HttpResponse<String> browser(String method, String suffix, Login login, String body, int expected) throws Exception {
    Map<String, String> headers = body == null ? Map.of()
        : Map.of("Origin", fixture.origin(), "X-CSRF-Token", login.session().path("csrfToken").asText());
    return checked(method, fixture.origin() + ApprovalBrowserAdapter.API + suffix, login.cookie(), body, headers, expected);
  }
  private JsonNode rpc(String bearer, String tool, JsonNode args) throws Exception {
    ObjectNode rpc = JsonSupport.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
    rpc.putObject("params").put("name", tool).set("arguments", args);
    return json(api("POST", "/mcp", bearer, rpc.toString(), 200));
  }
  private Login login(String subject) throws Exception {
    var start = checked("GET", fixture.origin() + "/auth/login", null, null, Map.of(), 303);
    String authorize = start.headers().firstValue("Location").orElseThrow() + "&synthetic_subject=" + subject;
    var authorized = checked("GET", authorize, null, null, Map.of(), 303);
    var callback = checked("GET", authorized.headers().firstValue("Location").orElseThrow(),
        cookie(start, "tp_local_login"), null, Map.of(), 303);
    String session = cookie(callback, "tp_local_session");
    return new Login(session, json(checked("GET", fixture.origin() + "/browser/session", session, null, Map.of(), 200)));
  }
  private static String cookie(HttpResponse<String> response, String name) {
    return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith(name + "="))
        .findFirst().orElseThrow().split(";", 2)[0];
  }
  private static JsonNode json(HttpResponse<String> response) throws Exception { return JsonSupport.MAPPER.readTree(response.body()); }
  private static HttpResponse<String> checked(String method, String url, String cookie, String body,
      Map<String, String> headers, int expected) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
    if (cookie != null) request.header("Cookie", cookie);
    if (body != null) request.header("Content-Type", "application/json");
    headers.forEach(request::header);
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(expected);
    assertNoCanaries(response.headers().map().toString());
    return response;
  }
}
