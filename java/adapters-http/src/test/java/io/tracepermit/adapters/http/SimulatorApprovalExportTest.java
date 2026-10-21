package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/** Signed HTTP + PostgreSQL + synthetic browser protocol; no rendered UI or real IdP claim. */
final class SimulatorApprovalExportTest {
  private static final TenantPartition TENANT = new TenantPartition("simulated-alpha");
  private static PostgreSQLContainer<?> postgres;
  private static HttpClient client;
  @TempDir Path directory;
  private SimulatorApprovalBrowserFixture fixture;
  private record Login(String cookie, JsonNode session) {}

  @BeforeAll static void prerequisites() {
    postgres = new PostgreSQLContainer<>("postgres:16.4-alpine").withPassword("synthetic-export-password");
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
    fixture.accessClaims = claims -> claims.put("scope", "approvals.read approvals.request approvals.decide approvals.execute approvals.cancel");
  }
  @AfterEach void stop() { if (fixture != null) fixture.close(); }

  @Test void exportUsesCurrentRoleVisibilityAndNeverGrantsOrConsumesAuthority() throws Exception {
    String alice = fixture.accessToken("alice");
    JsonNode request = create(alice);
    String id = request.path("approvalRequestId").asText();
    var target = fixture.simulator.snapshot();
    var evidence = fixture.state.readEvidence(TENANT);
    for (String subject : List.of("alice", "bob", "carol", "dave")) {
      var response = call("GET", "/api/v1/approvals/" + id + "/export", fixture.accessToken(subject), null, Map.of());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("Content-Disposition")).contains(
          "attachment; filename=\"tracepermit-approval-" + id + ".json\"");
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
      assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
      assertExport(response, request, "pending", "not_executed");
    }
    JsonNode other = create(fixture.accessToken("dave"));
    assertThat(export(other, alice).statusCode()).isEqualTo(404);
    assertThat(export(request, null).statusCode()).isEqualTo(401);
    assertThat(export(request, fixture.accessToken("unknown")).statusCode()).isEqualTo(403);
    // Downloaded metadata is not an execution request or authority artifact.
    assertThat(call("POST", "/api/v1/approvals/" + id + "/execute", alice,
        export(request, alice).body(), Map.of()).statusCode()).isEqualTo(400);
    assertThat(fixture.state.readEvidence(TENANT)).hasSize(evidence.size() + 1); // Only the second create wrote evidence.
    assertThat(fixture.simulator.snapshot()).isEqualTo(target);
    assertThat(fixture.state.find(TENANT, id).status()).isEqualTo("pending");
    try (var connection = fixture.config.database().getConnection(); var sql = connection.createStatement()) {
      for (String table : List.of("tp_execution_attempts", "tp_consumed_execution_permits")) {
        try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
          assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
        }
      }
    }
  }

  @Test void roleScopeEnrollmentTenantAndExpiryAreCheckedOnEveryDownload() throws Exception {
    JsonNode request = create(fixture.accessToken("alice"));
    fixture.accessClaims = claims -> claims.put("scope", "approvals.request");
    assertThat(export(request, fixture.accessToken("alice")).statusCode()).isEqualTo(403);
    fixture.accessClaims = claims -> claims.put("scope", "approvals.read").put("org_id", "other");
    var foreign = new TenantPartition("simulated-other");
    fixture.config.memberships().replace(List.of(new ApprovalMemberships.Entry(
        SimulatorApprovalBrowserFixture.ISSUER + "#carol", SimulatorApprovalBrowserFixture.CLIENT,
        foreign, true, Set.of(ApprovalMemberships.Role.VIEWER))));
    // The fixture enrolls only organization alpha in issuer trust: a foreign organization
    // is rejected by the token verifier, before membership or record lookup.
    assertThat(export(request, fixture.accessToken("carol")).statusCode()).isEqualTo(401);
    fixture.accessClaims = claims -> claims.put("scope", "approvals.read");
    fixture.config.memberships().replace(List.of());
    assertThat(export(request, fixture.accessToken("carol")).statusCode()).isEqualTo(403);
    fixture.accessClaims = claims -> claims.put("exp", fixture.clock.instant().minusSeconds(1).getEpochSecond());
    assertThat(export(request, fixture.accessToken("alice")).statusCode()).isEqualTo(401);
  }

  @Test void terminalStatesExpiryRestartAndOneExecutionRemainDistinct() throws Exception {
    String alice = fixture.accessToken("alice"), bob = fixture.accessToken("bob");
    JsonNode cancelled = create(alice), denied = create(alice), executed = create(alice), expired = create(alice);
    mutate(cancelled, "cancel", alice, hash(cancelled));
    mutate(denied, "decision", bob, hash(denied).replace("}", ",\"decision\":\"deny\"}"));
    mutate(executed, "decision", bob, hash(executed).replace("}", ",\"decision\":\"approve\"}"));
    assertExport(export(executed, alice), executed, "approved", "not_executed");
    mutate(executed, "execute", alice, hash(executed));
    var target = fixture.simulator.snapshot();
    var evidence = fixture.state.readEvidence(TENANT);
    fixture.restart();
    assertExport(export(cancelled, alice), cancelled, "cancelled", "not_executed");
    assertExport(export(denied, alice), denied, "denied", "not_executed");
    assertExport(export(executed, alice), executed, "consumed", "succeeded");
    assertThat(call("POST", "/api/v1/approvals/" + executed.path("approvalRequestId").asText() + "/execute",
        alice, hash(executed), Map.of()).statusCode()).isEqualTo(409);
    fixture.clock.now = fixture.clock.now.plusSeconds(301);
    var report = assertExport(export(expired, fixture.accessToken("alice")), expired, "pending", "not_executed");
    assertThat(report.path("payload").path("approval").path("expired").asBoolean()).isTrue();
    assertThat(fixture.simulator.snapshot()).isEqualTo(target);
    assertThat(fixture.state.readEvidence(TENANT)).isEqualTo(evidence);
  }

  @Test void partitionAndVisibilityPrecedeMissingSnapshotWithoutPartialDownload() throws Exception {
    String alice = fixture.accessToken("alice");
    JsonNode request = create(alice);
    var source = fixture.state.find(TENANT, request.path("approvalRequestId").asText()).binding();
    String localId = java.util.UUID.randomUUID().toString(), foreignId = java.util.UUID.randomUUID().toString();
    // Trusted library-only create intentionally omits an operational snapshot. No row is corrupted.
    fixture.state.createPending(copyBinding(source, TENANT, localId));
    fixture.state.createPending(copyBinding(source, new TenantPartition("simulated-other"), foreignId));
    String root = "/api/v1/approvals/";
    assertThat(call("GET", root + foreignId + "/export", fixture.accessToken("carol"), null, Map.of()).statusCode()).isEqualTo(404);
    fixture.config.memberships().replace(List.of(
        new ApprovalMemberships.Entry(SimulatorApprovalBrowserFixture.ISSUER + "#alice", SimulatorApprovalBrowserFixture.CLIENT,
            TENANT, true, Set.of(ApprovalMemberships.Role.REQUESTER)),
        new ApprovalMemberships.Entry(SimulatorApprovalBrowserFixture.ISSUER + "#erin", SimulatorApprovalBrowserFixture.CLIENT,
            TENANT, true, Set.of(ApprovalMemberships.Role.REQUESTER))));
    assertThat(call("GET", root + localId + "/export", fixture.accessToken("erin"), null, Map.of()).statusCode()).isEqualTo(404);
    var response = call("GET", root + localId + "/export", alice, null, Map.of());
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
    assertThat(response.body()).isEqualTo("{\"error\":{\"code\":\"SECURITY_STATE_UNAVAILABLE\"}}");
    assertThat(fixture.state.find(TENANT, localId).status()).isEqualTo("pending");
  }

  private static io.tracepermit.execution.ApprovalBinding copyBinding(
      io.tracepermit.execution.ApprovalBinding b, TenantPartition tenant, String id) {
    String syntheticHash = CanonicalJson.sha256(JsonSupport.MAPPER.getNodeFactory().textNode(id));
    return new io.tracepermit.execution.ApprovalBinding(tenant, id, syntheticHash, b.actionHash(), id,
        id, b.subjectId(), b.actorLineageHash(), b.policyId(), b.policyVersion(), b.originalDecision(),
        b.toolName(), b.resourceHash(), b.approvalGroup(), b.createdAt(), b.expiresAt(), b.maxUses());
  }

  @Test void browserExportUsesSameOriginSessionAndRejectsMixedAuthAndMutationMethods() throws Exception {
    JsonNode request = create(fixture.accessToken("alice"));
    Login viewer = login("carol");
    String path = ApprovalBrowserAdapter.API + "/" + request.path("approvalRequestId").asText() + "/export";
    var response = call("GET", path, null, null, Map.of("Cookie", viewer.cookie(), "Origin", fixture.origin()));
    assertExport(response, request, "pending", "not_executed");
    assertThat(call("GET", path, fixture.accessToken("carol"), null, Map.of("Cookie", viewer.cookie())).statusCode()).isEqualTo(400);
    assertThat(call("GET", path, null, null, Map.of("Cookie", viewer.cookie(), "Origin", "https://evil.invalid")).statusCode()).isEqualTo(403);
    assertThat(call("POST", path, null, "{}", Map.of("Cookie", viewer.cookie(), "Origin", fixture.origin(),
        "X-CSRF-Token", viewer.session().path("csrfToken").asText())).statusCode()).isEqualTo(405);
    String bearerPath = "/api/v1/approvals/" + request.path("approvalRequestId").asText() + "/export";
    assertThat(call("GET", bearerPath + "?tenant=other", fixture.accessToken("carol"), null, Map.of()).statusCode()).isEqualTo(400);
    assertThat(call("POST", bearerPath, fixture.accessToken("carol"), "{}", Map.of()).statusCode()).isEqualTo(405);
    assertThat(call("GET", bearerPath, fixture.accessToken("carol"), "{}", Map.of()).statusCode()).isEqualTo(400);
  }

  private JsonNode create(String bearer) throws Exception {
    var response = call("POST", "/api/v1/approvals", bearer, SimulatorApprovalBrowserFixture.ACTION, Map.of());
    assertThat(response.statusCode()).isEqualTo(201);
    return JsonSupport.MAPPER.readTree(response.body());
  }
  private HttpResponse<String> export(JsonNode request, String bearer) throws Exception {
    return call("GET", "/api/v1/approvals/" + request.path("approvalRequestId").asText() + "/export", bearer, null, Map.of());
  }
  private void mutate(JsonNode request, String operation, String bearer, String body) throws Exception {
    assertThat(call("POST", "/api/v1/approvals/" + request.path("approvalRequestId").asText() + "/" + operation,
        bearer, body, Map.of()).statusCode()).isEqualTo(200);
  }
  private static String hash(JsonNode request) { return "{\"requestHash\":\"" + request.path("requestHash").asText() + "\"}"; }
  private static JsonNode assertExport(HttpResponse<String> response, JsonNode original, String status, String execution) throws Exception {
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(ApprovalReviewExport.MAX_BYTES);
    JsonNode report = JsonSupport.MAPPER.readTree(response.body());
    assertThat(report.path("kind").asText()).isEqualTo("tracepermit-approval-review");
    assertThat(report.path("payloadHash").asText()).isEqualTo(CanonicalJson.sha256(report.path("payload")));
    var approval = report.path("payload").path("approval");
    assertThat(approval.path("requestHash")).isEqualTo(original.path("requestHash"));
    assertThat(approval.path("approvalRequestId")).isEqualTo(original.path("approvalRequestId"));
    assertThat(approval.path("status").asText()).isEqualTo(status);
    assertThat(approval.path("executionStatus").asText()).isEqualTo(execution);
    assertThat(response.body()).doesNotContain("\"arguments\"", "\"target\"", "access_token", "id_token", "credentialFingerprint", "#alice", "#carol");
    return report;
  }
  private Login login(String subject) throws Exception {
    var start = call("GET", "/auth/login", null, null, Map.of());
    String flow = start.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    URI authorize = URI.create(start.headers().firstValue("Location").orElseThrow() + "&synthetic_subject=" + subject);
    var redirect = client.send(HttpRequest.newBuilder(authorize).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    var callback = client.send(HttpRequest.newBuilder(URI.create(redirect.headers().firstValue("Location").orElseThrow()))
        .timeout(Duration.ofSeconds(10)).header("Cookie", flow).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(callback.statusCode()).isEqualTo(303);
    String session = callback.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("tp_local_session="))
        .findFirst().orElseThrow().split(";", 2)[0];
    var me = call("GET", "/browser/session", null, null, Map.of("Cookie", session));
    assertThat(me.statusCode()).isEqualTo(200);
    return new Login(session, JsonSupport.MAPPER.readTree(me.body()));
  }
  private HttpResponse<String> call(String method, String path, String bearer, String body, Map<String, String> headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(fixture.origin() + path)).timeout(Duration.ofSeconds(10));
    if (bearer != null) request.header("Authorization", "Bearer " + bearer);
    if (body != null) request.header("Content-Type", "application/json");
    headers.forEach(request::header);
    return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .build(), HttpResponse.BodyHandlers.ofString());
  }
}
