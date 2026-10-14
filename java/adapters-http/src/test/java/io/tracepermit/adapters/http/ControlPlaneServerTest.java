package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.simulator.StatefulTargetSimulator;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ControlPlaneServerTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final String EVENT_ID = "018f47a2-f5ce-7a4b-8f54-80e5d3eaf092";
  private static final String TRACE_ID = "9f3a2c1e4b5d67890123456789abcdef";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC);

  @Test
  void readOnlyApiServesHealthOverviewListsDetailsAndSimulatorState() throws Exception {
    try (ControlPlaneServer server = start(); HttpClient client = HttpClient.newHttpClient()) {
      JsonNode health = getJson(client, server, "/health", 200);
      assertThat(health.path("mode").asText()).isEqualTo("simulated");
      JsonNode ready = getJson(client, server, "/ready", 200);
      assertThat(ready.path("ready").asBoolean()).isTrue();
      assertThat(ready.path("checkedAt").asText()).isEqualTo(CLOCK.instant().toString());
      JsonNode system = getJson(client, server, "/api/v1/system-info", 200);
      assertThat(system.path("backend").asText()).isEqualTo("java");
      assertThat(system.path("mode").asText()).isEqualTo("simulated-loopback");
      assertThat(system.path("simulated").asBoolean()).isTrue();
      assertThat(system.path("javaVersion").asText()).isNotBlank();

      JsonNode overview = getJson(client, server, "/api/v1/overview", 200);
      assertThat(overview.path("total").asInt()).isEqualTo(8);
      JsonNode page = getJson(
          client,
          server,
          "/api/v1/actions?decision=block&origin=chatgpt-web&q=terminate&limit=10",
          200);
      String eventId = page.path("items").get(0).path("eventId").asText();
      JsonNode detail = getJson(client, server, "/api/v1/actions/" + eventId, 200);
      assertThat(detail.path("decision").path("outcome").asText()).isEqualTo("block");
      assertThat(detail.toString()).doesNotContain("arguments", "credentialFingerprint");

      JsonNode state = getJson(client, server, "/api/v1/simulator/state", 200);
      assertThat(state.path("mode").asText()).isEqualTo("simulated");
      assertThat(getJson(client, server, "/api/v1/actions/00000000-0000-7000-8000-000000000000", 404)
          .path("error").path("code").asText()).isEqualTo("ACTION_NOT_FOUND");
      assertThat(getJson(client, server, "/missing", 404)
          .path("error").path("code").asText()).isEqualTo("NOT_FOUND");
    }
  }

  @Test
  void runtimeInformationIsBoundedAndCannotClaimARealTargetMode() {
    DevelopmentRuntimeInfo info =
        DevelopmentRuntimeInfo.from(
            java.util.Map.of(
                "TRACEPERMIT_BUILD_VERSION", "0.1.0-demo",
                "TRACEPERMIT_BUILD_COMMIT", "80c68f6"));
    assertThat(info.productVersion()).isEqualTo("0.1.0-demo");
    assertThat(info.buildCommit()).isEqualTo("80c68f6");
    assertThat(info.backend()).isEqualTo("java");

    assertThatThrownBy(
            () ->
                DevelopmentRuntimeInfo.from(
                    java.util.Map.of("TRACEPERMIT_BUILD_COMMIT", "not a commit")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DevelopmentRuntimeInfo(
                    "1.0", "0.1.0", "development", "java", "21", "production", false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dedicatedEvidencePolicyAndLineageEndpointsServeBoundedListAndDetailViews()
      throws Exception {
    try (ControlPlaneServer server = start(); HttpClient client = HttpClient.newHttpClient()) {
      JsonNode evidencePage = getJson(client, server, "/api/v1/evidence-integrity", 200);
      JsonNode evidence = getJson(client, server, "/api/v1/evidence-integrity/" + EVENT_ID, 200);
      assertThat(evidencePage.path("total").asInt()).isEqualTo(8);
      assertThat(evidence.path("valid").asBoolean()).isTrue();
      assertThat(evidence.path("anchored").asBoolean()).isFalse();
      assertThat(evidence.path("events")).hasSize(3);

      JsonNode policyPage = getJson(client, server, "/api/v1/policy-versions", 200);
      JsonNode policy = getJson(
          client, server, "/api/v1/policy-versions/pol-aws-agentic-demo-001/1", 200);
      assertThat(policyPage.path("total").asInt()).isOne();
      assertThat(policy.path("status").asText()).isEqualTo("active");
      assertThat(policy.path("runtimeVersions").path("executionKey").asText())
          .isEqualTo("not-used-read-only");

      JsonNode lineagePage = getJson(client, server, "/api/v1/lineages", 200);
      JsonNode lineage = getJson(client, server, "/api/v1/lineages/" + TRACE_ID, 200);
      assertThat(lineagePage.path("total").asInt()).isEqualTo(8);
      assertThat(lineage.path("authoritySummary").asText())
          .isEqualTo("validated_delegation_claim");
      assertThat(lineage.path("hops")).hasSize(2);

      assertSecretMinimized(evidencePage, evidence, policyPage, policy, lineagePage, lineage);
    }
  }

  @Test
  void detailEndpointsDistinguishNotFoundFromMalformedIdentifiers() throws Exception {
    try (ControlPlaneServer server = start(); HttpClient client = HttpClient.newHttpClient()) {
      assertThat(getJson(
              client,
              server,
              "/api/v1/evidence-integrity/00000000-0000-7000-8000-000000000000",
              404)
          .path("error").path("code").asText()).isEqualTo("EVIDENCE_NOT_FOUND");
      assertThat(getJson(client, server, "/api/v1/policy-versions/pol-missing-001/1", 404)
          .path("error").path("code").asText()).isEqualTo("POLICY_VERSION_NOT_FOUND");
      assertThat(getJson(
              client,
              server,
              "/api/v1/lineages/00000000000000000000000000000000",
              404)
          .path("error").path("code").asText()).isEqualTo("LINEAGE_NOT_FOUND");

      for (String path : java.util.List.of(
          "/api/v1/actions/not-an-event",
          "/api/v1/evidence-integrity/not-an-event",
          "/api/v1/policy-versions/INVALID/zero",
          "/api/v1/policy-versions/pol-aws-agentic-demo-001",
          "/api/v1/lineages/not-a-trace")) {
        assertThat(getJson(client, server, path, 400)
            .path("error").path("code").asText()).isEqualTo("INVALID_IDENTIFIER");
      }
    }
  }

  @Test
  void methodQueryOriginAndHostControlsFailClosed() throws Exception {
    try (ControlPlaneServer server = start(); HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> post = client.send(
          request(server, "/api/v1/actions").POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(post.statusCode()).isEqualTo(405);
      assertThat(post.headers().firstValue("Allow")).contains("GET, OPTIONS");

      assertThat(getJson(client, server, "/api/v1/actions?limit=zero", 400)
          .path("error").path("code").asText()).isEqualTo("INVALID_QUERY");
      assertThat(getJson(client, server, "/api/v1/actions?unknown=true", 400)
          .path("error").path("code").asText()).isEqualTo("INVALID_QUERY");
      assertThat(getJson(client, server, "/api/v1/actions?q=a&q=b", 400)
          .path("error").path("code").asText()).isEqualTo("INVALID_QUERY");

      HttpRequest deniedOrigin = request(server, "/health")
          .header("Origin", "https://attacker.invalid")
          .GET()
          .build();
      assertThat(client.send(deniedOrigin, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(403);
      HttpRequest allowedOrigin = request(server, "/health")
          .header("Origin", "http://127.0.0.1:5173")
          .GET()
          .build();
      assertThat(client.send(allowedOrigin, HttpResponse.BodyHandlers.ofString())
          .headers().firstValue("Access-Control-Allow-Origin"))
          .contains("http://127.0.0.1:5173");

      HttpRequest options = request(server, "/api/v1/actions")
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
          .build();
      assertThat(client.send(options, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
      assertThat(rawStatus(server.port(), "evil.invalid")).contains("400");

      for (String path : java.util.List.of(
          "/api/v1/evidence-integrity",
          "/api/v1/evidence-integrity/" + EVENT_ID,
          "/api/v1/policy-versions",
          "/api/v1/policy-versions/pol-aws-agentic-demo-001/1",
          "/api/v1/lineages",
          "/api/v1/lineages/" + TRACE_ID)) {
        HttpResponse<String> deniedMutation = client.send(
            request(server, path).POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(deniedMutation.statusCode()).isEqualTo(405);
      }
    }

    assertThatThrownBy(
            () -> {
              DemoControlPlaneData data = DemoControlPlaneData.load(ROOT);
              ControlPlaneServer.start(
                  new InetSocketAddress("0.0.0.0", 0),
                  data.actionFeed(),
                  data.views(),
                  new StatefulTargetSimulator(),
                  CLOCK);
            })
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("loopback");
  }

  private static ControlPlaneServer start() throws Exception {
    DemoControlPlaneData data = DemoControlPlaneData.load(ROOT);
    return ControlPlaneServer.start(
        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
        data.actionFeed(),
        data.views(),
        new StatefulTargetSimulator(),
        CLOCK);
  }

  private static void assertSecretMinimized(JsonNode... responses) {
    String serialized = java.util.Arrays.toString(responses);
    assertThat(serialized)
        .doesNotContain(
            "arguments",
            "targetResult",
            "credential",
            "credentialFingerprint",
            "bearer",
            "tokenHash",
            "delegationTokenId",
            "clientClaims");
  }

  private static JsonNode getJson(
      HttpClient client, ControlPlaneServer server, String path, int expectedStatus) throws Exception {
    HttpResponse<String> response = client.send(
        request(server, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(expectedStatus);
    assertThat(response.headers().firstValue("Content-Type"))
        .contains("application/json; charset=utf-8");
    return JsonSupport.MAPPER.readTree(response.body());
  }

  private static HttpRequest.Builder request(ControlPlaneServer server, String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
  }

  private static String rawStatus(int port, String host) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port);
        OutputStreamWriter writer =
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
      writer.write("GET /health HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n");
      writer.flush();
      return reader.readLine();
    }
  }
}
