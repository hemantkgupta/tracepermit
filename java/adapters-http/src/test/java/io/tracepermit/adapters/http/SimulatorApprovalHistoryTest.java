package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/** Actual signed HTTP and disposable PostgreSQL; no browser-rendering or external IdP claim. */
final class SimulatorApprovalHistoryTest {
  static final TenantPartition TENANT = new TenantPartition("simulated-alpha");
  static PostgreSQLContainer<?> postgres;
  static HttpClient client;
  @TempDir Path directory;
  SimulatorApprovalBrowserFixture fixture;

  @BeforeAll static void prerequisites() {
    postgres = new PostgreSQLContainer<>("postgres:16.4-alpine").withPassword("synthetic-history-password");
    postgres.start();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }
  @AfterAll static void finish() { client.close(); postgres.stop(); }
  @BeforeEach void start() throws Exception {
    fixture = new SimulatorApprovalBrowserFixture(directory, postgres);
    fixture.clock.now = Instant.parse("2026-10-17T00:00:00Z");
    fixture.accessClaims = claims -> claims.put("scope", "approvals.read approvals.request approvals.decide approvals.execute");
  }
  @AfterEach void stop() { fixture.close(); }

  @Test void walksBeyondFiftyWithTimestampTiesNoDuplicatesAndNoReadSideEffects() throws Exception {
    String alice = fixture.accessToken("alice"), dave = fixture.accessToken("dave"), viewer = fixture.accessToken("carol");
    List<JsonNode> original = new ArrayList<>();
    for (int i = 0; i < 55; i++) {
      if (i == 28) fixture.clock.now = fixture.clock.now.plusSeconds(1);
      original.add(create(i % 2 == 0 ? alice : dave));
    }
    var expected = original.stream().sorted((a, b) -> {
      int time = b.path("createdAt").asText().compareTo(a.path("createdAt").asText());
      return time != 0 ? time : a.path("approvalRequestId").asText().compareTo(b.path("approvalRequestId").asText());
    }).map(a -> a.path("approvalRequestId").asText()).toList();
    var defaultPage = get(viewer, "");
    assertThat(defaultPage.path("items")).hasSize(50);
    assertThat(defaultPage.path("pageSize").asInt()).isEqualTo(50);
    assertThat(defaultPage.path("nextCursor").isTextual()).isTrue();
    JsonNode page = get(viewer, "?limit=7");
    List<String> ids = ids(page);
    String continuation = page.path("nextCursor").asText();
    // Inserting a newer request cannot shift older keyset pages or duplicate rows.
    fixture.clock.now = fixture.clock.now.plusSeconds(1);
    var newer = create(alice);
    var evidence = fixture.state.readEvidence(TENANT);
    var target = fixture.simulator.snapshot();
    fixture.restart(); // Continuation is position/context, not a process-local session capability.
    while (continuation != null) {
      page = get(viewer, "?limit=7&cursor=" + continuation);
      ids.addAll(ids(page));
      continuation = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
    }
    assertThat(ids).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    assertThat(ids).doesNotContain(newer.path("approvalRequestId").asText());
    assertThat(fixture.state.readEvidence(TENANT)).isEqualTo(evidence);
    assertThat(fixture.simulator.snapshot()).isEqualTo(target);
    for (var request : original) {
      assertThat(fixture.state.find(TENANT, request.path("approvalRequestId").asText()).status()).isEqualTo("pending");
    }
    assertThat(get(alice, "?limit=50").path("items")).hasSize(29);
  }

  @Test void exactMetadataFiltersKeepOwnerRolesStoredStatusAndExecutionDistinct() throws Exception {
    String alice = fixture.accessToken("alice"), reviewer = fixture.accessToken("bob"), viewer = fixture.accessToken("carol");
    JsonNode a = create(alice), d = create(fixture.accessToken("dave"));
    String aId = a.path("approvalRequestId").asText(), dId = d.path("approvalRequestId").asText();
    String hash = "{\"requestHash\":\"" + a.path("requestHash").asText() + "\"}";
    assertThat(call("POST", "/" + aId + "/decision", reviewer,
        hash.replace("}", ",\"decision\":\"approve\"}")).statusCode()).isEqualTo(200);
    assertThat(ids(get(viewer, "?status=approved"))).containsExactly(aId);
    assertThat(ids(get(alice, "?approvalRequestId=" + dId))).isEmpty();
    assertThat(ids(get(viewer, "?approvalRequestId=" + dId))).containsExactly(dId);
    assertThat(ids(get(reviewer, "?toolName=aws.ec2.update_security_group"))).containsExactlyInAnyOrder(aId, dId);
    assertThat(ids(get(reviewer, "?toolName=unknown.tool"))).isEmpty();
    assertThat(ids(get(reviewer, "?status=denied"))).isEmpty();
    assertThat(ids(get(reviewer, "?status=approved&approvalRequestId=" + dId))).isEmpty();
    assertThat(call("POST", "/" + aId + "/execute", alice, hash).statusCode()).isEqualTo(200);
    var consumed = get(viewer, "?status=consumed").path("items").get(0);
    assertThat(consumed.path("status").asText()).isEqualTo("consumed");
    assertThat(consumed.path("executionStatus").asText()).isEqualTo("succeeded");
    var target = fixture.simulator.snapshot();
    fixture.clock.now = fixture.clock.now.plusSeconds(301);
    String freshViewer = fixture.accessToken("carol");
    var evidence = fixture.state.readEvidence(TENANT);
    var expiredPending = get(freshViewer, "?status=pending").path("items");
    assertThat(expiredPending).hasSize(1);
    assertThat(expiredPending.get(0).path("approvalRequestId").asText()).isEqualTo(dId);
    assertThat(expiredPending.get(0).path("expired").asBoolean()).isTrue();
    assertThat(get(freshViewer, "?status=expired").path("items")).isEmpty();
    assertThat(fixture.state.readEvidence(TENANT)).isEqualTo(evidence);
    assertThat(fixture.simulator.snapshot()).isEqualTo(target);
  }

  @Test void cursorContextCannotReplaceAuthenticationOwnershipOrFreshMembership() throws Exception {
    String alice = fixture.accessToken("alice"), viewer = fixture.accessToken("carol");
    create(alice); create(alice); var foreignOwner = create(fixture.accessToken("dave"));
    String cursor = get(alice, "?limit=1").path("nextCursor").asText();
    for (String suffix : List.of("?tenant=other", "?limit=1&limit=2", "?status=all", "?limit=51",
        "?subject=other", "?limit=01", "?cursor=invalid", "?status=%27OR%201%3D1", "?toolName=%25")) {
      assertThat(call("GET", suffix, viewer, null).statusCode()).isEqualTo(400);
      assertThat(call("GET", suffix, null, null).statusCode()).isEqualTo(401);
    }
    assertThat(call("GET", "?limit=1&cursor=" + cursor, viewer, null).statusCode()).isEqualTo(400);
    assertThat(call("GET", "?status=approved&cursor=" + cursor, alice, null).statusCode()).isEqualTo(400);
    // Positions are unsigned and editable, not capabilities: an arbitrary earlier position still returns only owned rows.
    var ownedIds = ids(get(alice, ""));
    ObjectNode forged = (ObjectNode) JsonSupport.MAPPER.readTree(Base64.getUrlDecoder().decode(cursor));
    forged.put("afterRequestId", "0");
    String edited = Base64.getUrlEncoder().withoutPadding().encodeToString(JsonSupport.MAPPER.writeValueAsBytes(forged));
    assertThat(ids(get(alice, "?cursor=" + edited))).containsExactlyInAnyOrderElementsOf(ownedIds)
        .doesNotContain(foreignOwner.path("approvalRequestId").asText());
    fixture.accessClaims = claims -> claims.put("scope", "approvals.request");
    assertThat(call("GET", "?cursor=" + cursor, fixture.accessToken("alice"), null).statusCode()).isEqualTo(403);
    fixture.clock.now = fixture.clock.now.plusSeconds(301);
    assertThat(call("GET", "?cursor=" + cursor, alice, null).statusCode()).isEqualTo(401);
    fixture.config.memberships().replace(List.of());
    assertThat(call("GET", "?cursor=" + cursor, fixture.accessToken("alice"), null).statusCode()).isEqualTo(403);
    assertThat(fixture.simulator.snapshot().path("aws").path("securityGroups").path("sg-demo")).isEmpty();
  }

  private JsonNode create(String bearer) throws Exception {
    var response = call("POST", "", bearer, SimulatorApprovalBrowserFixture.ACTION);
    assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(201);
    return JsonSupport.MAPPER.readTree(response.body());
  }
  private JsonNode get(String bearer, String query) throws Exception {
    var response = call("GET", query, bearer, null);
    assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
    return JsonSupport.MAPPER.readTree(response.body());
  }
  private HttpResponse<String> call(String method, String suffix, String bearer, String body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(fixture.origin() + "/api/v1/approvals" + suffix))
        .timeout(Duration.ofSeconds(10));
    if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
    if (body != null) builder.header("Content-Type", "application/json");
    return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }
  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.path("items").forEach(item -> ids.add(item.path("approvalRequestId").asText()));
    return ids;
  }
}
