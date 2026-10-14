package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.EnforcingMcpDispatcher;
import io.tracepermit.application.SecureExecutionService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.endpoint.EndpointConnector;
import io.tracepermit.endpoint.EndpointKeyProvider;
import io.tracepermit.endpoint.LoopbackHttpGatewayTransport;
import io.tracepermit.endpoint.ProcessObservation;
import io.tracepermit.endpoint.ProcessObserver;
import io.tracepermit.endpoint.SourceAttestationFactory;
import io.tracepermit.endpoint.StdioMcpServer;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.identity.DevelopmentTestTokenIssuer;
import io.tracepermit.identity.DevelopmentTokenVerifier;
import io.tracepermit.identity.EndpointKeyRegistry;
import io.tracepermit.identity.InMemorySourceAttestationReplayStore;
import io.tracepermit.identity.SourceAttestationVerifier;
import io.tracepermit.identity.StaticEndpointKeyRegistry;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class JavaBackendEndpointConnectorE2eTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-10T10:15:30Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String ISSUER = "tracepermit-endpoint:java-e2e";
  private static final String KEY_ID = "endpoint-key-java-e2e";
  private static final String DEVICE_ID = "device-java-e2e";
  private static final String TOKEN_GRANT =
      "urn:tracepermit:params:oauth:grant-type:development-profile";

  @Test
  void javaConnectorTraversesJavaMcpPolicyPermitAndSimulator() throws Exception {
    KeyPair keys = keyPair();
    RuntimeFixture fixture = runtime(keys, () -> SecurityReadiness.allReady(NOW));
    try (LoopbackDevelopmentMcpServer server = fixture.server()) {
      String token = issueToken(server.port(), "cli-readonly");
      ProcessObservation observation = observation(true);
      ProcessObserver observer =
          new ProcessObserver() {
            @Override
            public ProcessObservation observe() {
              return observation;
            }

            @Override
            public void verifyStable(ProcessObservation candidate) {
              assertThat(candidate).isEqualTo(observation);
            }
          };
      EndpointKeyProvider keyProvider = keyProvider(keys);
      EndpointConnector connector =
          new EndpointConnector(
              observer,
              () -> token,
              new SourceAttestationFactory(
                  keyProvider,
                  CLOCK,
                  () -> UUID.randomUUID().toString(),
                  Duration.ofSeconds(30)),
              new LoopbackHttpGatewayTransport(
                  URI.create("http://127.0.0.1:" + server.port() + "/mcp"),
                  Duration.ofSeconds(2),
                  Duration.ofSeconds(10)));

      String frames =
          """
          {"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"java-e2e","version":"1.0.0"}}}
          {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
          {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
          {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"aws.logs.filter_log_events","arguments":{"logGroupName":"/app/staging","filterPattern":"WARN","limit":2}}}
          {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"aws.ec2.terminate_instances","arguments":{"instanceIds":["i-simulated-java-e2e"],"region":"us-east-1"}}}
          {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"aws.ec2.unknown_operation","arguments":{}}}
          """;
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
      int exit =
          new StdioMcpServer(
                  new ByteArrayInputStream(frames.getBytes(StandardCharsets.UTF_8)),
                  output,
                  new PrintStream(diagnostics, true, StandardCharsets.UTF_8),
                  connector)
              .run();

      assertThat(exit).isZero();
      List<JsonNode> responses = responseLines(output);
      assertThat(responses).hasSize(5);
      assertThat(find(responses, "init").path("result").path("serverInfo").path("name").asText())
          .isEqualTo("TracePermit");
      assertThat(find(responses, "2").path("result").path("tools")).hasSize(2);

      JsonNode allowed = content(find(responses, "3"));
      assertThat(allowed.path("evaluation").path("decision").path("outcome").asText())
          .isEqualTo("allow");
      assertThat(allowed.path("execution").path("status").asText()).isEqualTo("executed");
      assertThat(allowed.path("execution").path("result").path("mode").asText())
          .isEqualTo("simulated");

      JsonNode blocked = content(find(responses, "4"));
      assertThat(find(responses, "4").path("result").path("isError").asBoolean()).isTrue();
      assertThat(blocked.path("evaluation").path("decision").path("outcome").asText())
          .isEqualTo("block");
      assertThat(blocked.path("execution").path("status").asText()).isEqualTo("denied");
      assertThat(find(responses, "5").path("error").path("code").asInt()).isEqualTo(-32602);
      assertThat(fixture.targetInvocations()).hasValue(1);

      String captured =
          output.toString(StandardCharsets.UTF_8)
              + diagnostics.toString(StandardCharsets.UTF_8);
      assertThat(captured).doesNotContain(token, Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
      assertThat(fixture.evidence().readAll().toString())
          .doesNotContain("filterPattern", "WARN", token, "PRIVATE KEY");
    }
  }

  @Test
  void authenticationAndSourceProofFailClosedBeforeDispatch() throws Exception {
    KeyPair keys = keyPair();
    RuntimeFixture fixture = runtime(keys, () -> SecurityReadiness.allReady(NOW));
    try (LoopbackDevelopmentMcpServer server = fixture.server()) {
      URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/mcp");
      String request =
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
      HttpResponse<String> unauthenticated = post(endpoint, null, null, "not-json");
      assertThat(unauthenticated.statusCode()).isEqualTo(401);
      assertThat(unauthenticated.body()).contains("MISSING_BEARER_TOKEN");

      String cliToken = issueToken(server.port(), "cli-readonly");
      HttpResponse<String> missingSource = post(endpoint, cliToken, null, request);
      assertThat(missingSource.statusCode()).isEqualTo(401);
      assertThat(missingSource.body()).contains("MISSING_SOURCE_ATTESTATION");

      String webToken = issueToken(server.port(), "chatgpt-readonly");
      HttpResponse<String> injectedSource = post(endpoint, webToken, "attacker-value", request);
      assertThat(injectedSource.statusCode()).isEqualTo(400);
      assertThat(injectedSource.body()).contains("SOURCE_ATTESTATION_NOT_ALLOWED");
      assertThat(fixture.targetInvocations()).hasValue(0);
    }
  }

  @Test
  void sourceAttestationIsSingleUseAndRequestBound() throws Exception {
    KeyPair keys = keyPair();
    RuntimeFixture fixture = runtime(keys, () -> SecurityReadiness.allReady(NOW));
    try (LoopbackDevelopmentMcpServer server = fixture.server()) {
      String token = issueToken(server.port(), "cli-readonly");
      JsonNode request =
          JsonSupport.MAPPER.readTree(
              "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
      SourceAttestationFactory factory =
          new SourceAttestationFactory(
              keyProvider(keys),
              CLOCK,
              () -> UUID.randomUUID().toString(),
              Duration.ofSeconds(30));
      String proof = factory.create(request, token, observation(true));
      URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/mcp");

      HttpResponse<String> first = post(endpoint, token, proof, request.toString());
      HttpResponse<String> replay = post(endpoint, token, proof, request.toString());
      JsonNode mutated = request.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) mutated)
          .set(
              "params",
              JsonSupport.MAPPER.createObjectNode().put("unexpected", true));
      String wrongBinding = factory.create(request, token, observation(true));
      HttpResponse<String> mutation = post(endpoint, token, wrongBinding, mutated.toString());

      assertThat(first.statusCode()).isEqualTo(200);
      assertThat(replay.statusCode()).isEqualTo(409);
      assertThat(replay.body()).contains("SOURCE_ATTESTATION_REPLAYED");
      assertThat(mutation.statusCode()).isEqualTo(403);
      assertThat(mutation.body()).contains("INVALID_SOURCE_ATTESTATION");
    }
  }

  @Test
  void unsafeBindingIncompleteTrustAndReadinessFailureAreRejected() throws Exception {
    KeyPair keys = keyPair();
    RuntimeFixture components = components(keys);
    assertThatThrownBy(
            () ->
                LoopbackDevelopmentMcpServer.start(
                    new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 0),
                    components.tokenVerifier(),
                    components.tokenIssuer(),
                    components.sourceVerifier(),
                    components.dispatcher(),
                    () -> SecurityReadiness.allReady(NOW)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("loopback");
    assertThatThrownBy(
            () ->
                DevelopmentBackendMain.sourceVerifier(
                    Map.of("TRACEPERMIT_ENDPOINT_ISSUER", ISSUER), CLOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete");

    SecurityReadiness unavailable =
        new SecurityReadiness(
            false,
            NOW,
            Map.of(
                "authentication", true,
                "policy", true,
                "permit_signer", false,
                "replay_store", true,
                "evidence_store", true));
    RuntimeFixture fixture = runtime(keys, () -> unavailable);
    try (LoopbackDevelopmentMcpServer server = fixture.server()) {
      HttpResponse<String> response =
          post(
              URI.create("http://127.0.0.1:" + server.port() + "/dev/oauth/token"),
              null,
              null,
              tokenRequest("chatgpt-readonly"));
      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(response.body()).contains("SECURITY_DEPENDENCY_UNAVAILABLE");
      assertThat(fixture.targetInvocations()).hasValue(0);
    }
  }

  @Test
  void completeDevelopmentBackendStartsBothLoopbackSurfaces() throws Exception {
    KeyPair keys = keyPair();
    Map<String, String> environment =
        Map.of(
            "TRACEPERMIT_REPOSITORY_ROOT",
            ROOT.toString(),
            "TRACEPERMIT_JAVA_MCP_PORT",
            "0",
            "TRACEPERMIT_JAVA_API_PORT",
            "0",
            "TRACEPERMIT_ENDPOINT_ISSUER",
            ISSUER,
            "TRACEPERMIT_ENDPOINT_KEY_ID",
            KEY_ID,
            "TRACEPERMIT_ENDPOINT_DEVICE_ID",
            DEVICE_ID,
            "TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64",
            Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));

    try (DevelopmentBackendMain.RunningBackend backend =
        DevelopmentBackendMain.start(environment, CLOCK)) {
      assertThat(backend.mcpPort()).isPositive();
      assertThat(backend.controlPlanePort()).isPositive();
      HttpResponse<String> health =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + backend.mcpPort() + "/health"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> readiness =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + backend.mcpPort() + "/ready"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> overview =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create(
                              "http://127.0.0.1:"
                                  + backend.controlPlanePort()
                                  + "/api/v1/overview"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(health.statusCode()).isEqualTo(200);
      assertThat(health.body()).contains("tracepermit-java-development-mcp");
      assertThat(readiness.statusCode()).isEqualTo(200);
      assertThat(readiness.body())
          .contains("\"ready\":true", "\"mode\":\"simulated-loopback\"");
      assertThat(overview.statusCode()).isEqualTo(200);
      assertThat(overview.body()).contains("\"simulated\":true");

      JsonNode resourceMetadata =
          getJson(backend.mcpPort(), "/.well-known/oauth-protected-resource/mcp");
      assertThat(resourceMetadata.path("resource").asText())
          .isEqualTo("http://127.0.0.1:" + backend.mcpPort() + "/mcp");
      assertThat(resourceMetadata.path("resource_name").asText())
          .isEqualTo("TracePermit MCP (simulated development)");
      assertThat(resourceMetadata.path("authorization_servers").isMissingNode()).isTrue();
      assertThat(resourceMetadata.path("scopes_supported"))
          .extracting(JsonNode::asText)
          .containsExactly("aws.logs.discover", "aws.logs.read");

      JsonNode rootResourceMetadata =
          getJson(backend.mcpPort(), "/.well-known/oauth-protected-resource");
      assertThat(rootResourceMetadata).isEqualTo(resourceMetadata);

      String token = issueToken(backend.mcpPort(), "chatgpt-readonly");
      HttpResponse<String> allowed =
          post(
              URI.create("http://127.0.0.1:" + backend.mcpPort() + "/mcp"),
              token,
              null,
              "{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"tools/call\",\"params\":{\"name\":\"aws.logs.filter_log_events\",\"arguments\":{\"logGroupName\":\"/app/staging\",\"limit\":2}}}");
      HttpResponse<String> blocked =
          post(
              URI.create("http://127.0.0.1:" + backend.mcpPort() + "/mcp"),
              token,
              null,
              "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\",\"params\":{\"name\":\"aws.ec2.terminate_instances\",\"arguments\":{\"instanceIds\":[\"i-simulated-live-view\"]}}}");
      assertThat(content(JsonSupport.MAPPER.readTree(allowed.body()))
          .path("execution").path("status").asText()).isEqualTo("executed");
      assertThat(content(JsonSupport.MAPPER.readTree(blocked.body()))
          .path("execution").path("status").asText()).isEqualTo("denied");

      JsonNode liveOverview = getJson(backend.controlPlanePort(), "/api/v1/overview");
      JsonNode liveActions = getJson(backend.controlPlanePort(), "/api/v1/actions?limit=50");
      JsonNode liveEvidence = getJson(backend.controlPlanePort(), "/api/v1/evidence-integrity");
      JsonNode liveLineage = getJson(backend.controlPlanePort(), "/api/v1/lineages");
      JsonNode livePolicy =
          getJson(
              backend.controlPlanePort(),
              "/api/v1/policy-versions/pol-aws-agentic-demo-001/1");
      assertThat(liveOverview.path("total").asInt()).isEqualTo(10);
      assertThat(liveOverview.path("allowed").asInt()).isEqualTo(3);
      assertThat(liveOverview.path("blocked").asInt()).isEqualTo(4);
      assertThat(liveOverview.path("latencySampleSize").asInt()).isEqualTo(2);
      assertThat(liveOverview.path("latencySampleScope").asText()).isEqualTo("current_run");
      assertThat(liveActions.path("items")).hasSize(10);
      assertThat(liveEvidence.path("total").asInt()).isEqualTo(10);
      assertThat(liveLineage.path("total").asInt()).isEqualTo(10);
      assertThat(livePolicy.path("evaluationCount").asInt()).isEqualTo(10);
      assertThat(
              liveActions.toString()
                  + liveEvidence.toString()
                  + liveLineage.toString()
                  + livePolicy.toString())
          .doesNotContain(
              "arguments",
              "credentialFingerprint",
              "filterPattern",
              "instanceIds",
              token);
    }

    assertThatThrownBy(
            () ->
                DevelopmentBackendMain.start(
                    Map.of(
                        "TRACEPERMIT_REPOSITORY_ROOT",
                        ROOT.toString(),
                        "TRACEPERMIT_JAVA_MCP_PORT",
                        "not-a-port"),
                    CLOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TRACEPERMIT_JAVA_MCP_PORT");
    assertThatThrownBy(
            () ->
                DevelopmentBackendMain.sourceVerifier(
                    Map.of(
                        "TRACEPERMIT_ENDPOINT_ISSUER",
                        ISSUER,
                        "TRACEPERMIT_ENDPOINT_KEY_ID",
                        KEY_ID,
                        "TRACEPERMIT_ENDPOINT_DEVICE_ID",
                        DEVICE_ID,
                        "TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64",
                        "not-base64"),
                    CLOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("public key");
  }

  @Test
  void restartInvalidatesDevelopmentCredentialsAndPreservesZeroLiveSideEffects() throws Exception {
    KeyPair keys = keyPair();
    Map<String, String> environment =
        Map.of(
            "TRACEPERMIT_REPOSITORY_ROOT",
            ROOT.toString(),
            "TRACEPERMIT_JAVA_MCP_PORT",
            "0",
            "TRACEPERMIT_JAVA_API_PORT",
            "0",
            "TRACEPERMIT_ENDPOINT_ISSUER",
            ISSUER,
            "TRACEPERMIT_ENDPOINT_KEY_ID",
            KEY_ID,
            "TRACEPERMIT_ENDPOINT_DEVICE_ID",
            DEVICE_ID,
            "TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64",
            Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));

    String tokenFromStoppedProcess;
    try (DevelopmentBackendMain.RunningBackend first =
        DevelopmentBackendMain.start(environment, CLOCK)) {
      tokenFromStoppedProcess = issueToken(first.mcpPort(), "chatgpt-readonly");
    }

    try (DevelopmentBackendMain.RunningBackend restarted =
        DevelopmentBackendMain.start(environment, CLOCK)) {
      HttpResponse<String> rejected =
          post(
              URI.create("http://127.0.0.1:" + restarted.mcpPort() + "/mcp"),
              tokenFromStoppedProcess,
              null,
              "{\"jsonrpc\":\"2.0\",\"id\":81,\"method\":\"tools/call\",\"params\":{\"name\":\"aws.logs.filter_log_events\",\"arguments\":{\"logGroupName\":\"/app/staging\",\"limit\":1}}}");

      assertThat(rejected.statusCode()).isEqualTo(401);
      assertThat(rejected.body()).contains("INVALID_ACCESS_TOKEN");
      assertThat(getJson(restarted.controlPlanePort(), "/api/v1/overview").path("total").asInt())
          .isEqualTo(8);
    }
  }

  @Test
  void mcpServerRejectsInvalidHttpRequests() throws Exception {
    KeyPair keys = keyPair();
    RuntimeFixture fixture = runtime(keys, () -> SecurityReadiness.allReady(NOW));
    try (LoopbackDevelopmentMcpServer server = fixture.server()) {
      HttpClient client = HttpClient.newHttpClient();
      int port = server.port();

      // 1. GET on /mcp should return 405 Method Not Allowed
      HttpResponse<String> getMcp = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(getMcp.statusCode()).isEqualTo(405);

      // 2. OPTIONS on /mcp should return 204 Preflight
      HttpResponse<String> optionsMcp = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(optionsMcp.statusCode()).isEqualTo(204);

      // 3. Unknown path should return 404
      HttpResponse<String> unknown = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/unknown-path")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(unknown.statusCode()).isEqualTo(404);

      // 4. POST /mcp with query string should return 400
      HttpResponse<String> queryParam = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp?query=bad"))
              .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(queryParam.statusCode()).isEqualTo(400);

      // 5. POST /dev/oauth/token with non-POST should return 405
      HttpResponse<String> getToken = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dev/oauth/token")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(getToken.statusCode()).isEqualTo(405);

      // 6. POST /dev/oauth/token with malformed JSON should return 400
      HttpResponse<String> badJsonToken = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dev/oauth/token"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("not-json")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(badJsonToken.statusCode()).isEqualTo(400);

      // 7. POST /.well-known/oauth-protected-resource should return 405 (only GET allowed)
      HttpResponse<String> postMeta = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/.well-known/oauth-protected-resource"))
              .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(postMeta.statusCode()).isEqualTo(405);

      // 8. POST /.well-known/oauth-protected-resource/mcp with POST should return 405
      HttpResponse<String> postMcpMeta = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/.well-known/oauth-protected-resource/mcp"))
              .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(postMcpMeta.statusCode()).isEqualTo(405);

      // 9. POST /dev/oauth/token with invalid grant_type should return 400
      HttpResponse<String> badGrant = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dev/oauth/token"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{\"grant_type\":\"invalid\",\"profile\":\"cli-readonly\"}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(badGrant.statusCode()).isEqualTo(400);

      // 10. POST /dev/oauth/token with invalid profile should return 400
      HttpResponse<String> badProfile = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dev/oauth/token"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{\"grant_type\":\"" + TOKEN_GRANT + "\",\"profile\":\"nonexistent\"}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(badProfile.statusCode()).isEqualTo(400);

      String chatgptToken = issueToken(port, "chatgpt-readonly");

      // 11. POST /mcp with non-application/json content-type should return 415
      HttpResponse<String> badMediaType = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
              .header("Authorization", "Bearer " + chatgptToken)
              .header("Content-Type", "text/plain")
              .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(badMediaType.statusCode()).isEqualTo(415);

      // 12. POST /mcp with disallowed Origin header should return 403
      HttpResponse<String> badOrigin = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
              .header("Content-Type", "application/json")
              .header("Origin", "https://unauthorized-external-origin.com")
              .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(badOrigin.statusCode()).isEqualTo(403);

      // 13. POST /mcp with ChatGPT token and unexpected source attestation header should return 400
      HttpResponse<String> unexpectedAttestation = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
              .header("Authorization", "Bearer " + chatgptToken)
              .header("Content-Type", "application/json")
              .header(LoopbackDevelopmentMcpServer.SOURCE_ATTESTATION_HEADER, "some-token")
              .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(unexpectedAttestation.statusCode()).isEqualTo(400);

      // 13. POST /mcp with CLI token but missing source attestation should return 401
      String cliToken = issueToken(port, "cli-readonly");
      HttpResponse<String> missingAttestation = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + cliToken)
              .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(missingAttestation.statusCode()).isEqualTo(401);
    }
  }

  private static RuntimeFixture runtime(
      KeyPair keys, java.util.function.Supplier<SecurityReadiness> readiness) throws Exception {
    RuntimeFixture components = components(keys);
    LoopbackDevelopmentMcpServer server =
        LoopbackDevelopmentMcpServer.start(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            components.tokenVerifier(),
            components.tokenIssuer(),
            components.sourceVerifier(),
            components.dispatcher(),
            readiness);
    return components.withServer(server);
  }

  private static RuntimeFixture components(KeyPair keys) {
    byte[] developmentKey = "d".repeat(32).getBytes(StandardCharsets.UTF_8);
    DevelopmentTokenVerifier tokenVerifier = new DevelopmentTokenVerifier(developmentKey, CLOCK);
    DevelopmentTestTokenIssuer tokenIssuer =
        new DevelopmentTestTokenIssuer(
            developmentKey, CLOCK, () -> UUID.randomUUID().toString(), 300);
    SourceAttestationVerifier sourceVerifier =
        new SourceAttestationVerifier(
            new StaticEndpointKeyRegistry(
                List.of(
                    new EndpointKeyRegistry.EnrolledEndpointKey(
                        ISSUER, KEY_ID, DEVICE_ID, keys.getPublic()))),
            new InMemorySourceAttestationReplayStore(),
            CLOCK);
    AtomicInteger ids = new AtomicInteger();
    AtomicInteger targets = new AtomicInteger();
    InMemoryEvidenceLedger evidence =
        new InMemoryEvidenceLedger(
            "urn:test:java-backend-e2e", CLOCK, () -> "evidence-" + ids.incrementAndGet());
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    SecureExecutionService service =
        new SecureExecutionService(
            new PolicyEvaluator(),
            JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json")),
            new ExecutionPermitAuthority(
                ExecutionPermitAuthority.DEFAULT_ISSUER,
                "java-e2e-key",
                "p".repeat(32).getBytes(StandardCharsets.UTF_8),
                30,
                CLOCK,
                () -> "permit-" + ids.incrementAndGet(),
                new InMemoryPermitConsumptionStore()),
            new InMemoryExecutionLedger(() -> "attempt-" + ids.incrementAndGet()),
            evidence,
            action -> {
              targets.incrementAndGet();
              return simulator.invoke(action);
            },
            () -> SecurityReadiness.allReady(NOW),
            CLOCK,
            "urn:test:java-e2e-simulator");
    EnforcingMcpDispatcher dispatcher =
        new EnforcingMcpDispatcher(
            service,
            CLOCK,
            () -> UUID.randomUUID().toString(),
            () -> "ab".repeat(16));
    return new RuntimeFixture(
        null,
        tokenVerifier,
        tokenIssuer,
        sourceVerifier,
        dispatcher,
        evidence,
        targets);
  }

  private static EndpointKeyProvider keyProvider(KeyPair keys) {
    return new EndpointKeyProvider() {
      @Override
      public KeyMetadata metadata() {
        return new KeyMetadata(ISSUER, KEY_ID, DEVICE_ID);
      }

      @Override
      public byte[] sign(byte[] signingInput) {
        try {
          Signature signer = Signature.getInstance("Ed25519");
          signer.initSign(keys.getPrivate());
          signer.update(signingInput);
          return signer.sign();
        } catch (GeneralSecurityException exception) {
          throw new IllegalStateException(exception);
        }
      }
    };
  }

  private static ProcessObservation observation(boolean terminalAttached) {
    return new ProcessObservation(
        42,
        DEVICE_ID,
        "linux",
        "development",
        "java-e2e-client",
        Path.of("/simulated/java-e2e-client"),
        "sha256:" + "a".repeat(64),
        "test-runner",
        NOW.minusSeconds(60),
        terminalAttached);
  }

  private static KeyPair keyPair() throws GeneralSecurityException {
    return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  private static String issueToken(int port, String profile) throws Exception {
    HttpResponse<String> response =
        post(
            URI.create("http://127.0.0.1:" + port + "/dev/oauth/token"),
            null,
            null,
            tokenRequest(profile));
    assertThat(response.statusCode()).isEqualTo(200);
    return JsonSupport.MAPPER.readTree(response.body()).path("access_token").asText();
  }

  private static String tokenRequest(String profile) {
    return "{\"grant_type\":\"" + TOKEN_GRANT + "\",\"profile\":\"" + profile + "\"}";
  }

  private static HttpResponse<String> post(
      URI uri, String token, String sourceProof, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (sourceProof != null) {
      request.header(LoopbackDevelopmentMcpServer.SOURCE_ATTESTATION_HEADER, sourceProof);
    }
    return HttpClient.newHttpClient()
        .send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode getJson(int port, String path) throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return JsonSupport.MAPPER.readTree(response.body());
  }

  private static List<JsonNode> responseLines(ByteArrayOutputStream output) throws Exception {
    List<JsonNode> responses = new ArrayList<>();
    for (String line : output.toString(StandardCharsets.UTF_8).lines().toList()) {
      if (!line.isBlank()) responses.add(JsonSupport.MAPPER.readTree(line));
    }
    return List.copyOf(responses);
  }

  private static JsonNode find(List<JsonNode> responses, String id) {
    Optional<JsonNode> result =
        responses.stream().filter(item -> id.equals(item.path("id").asText())).findFirst();
    return result.orElseThrow();
  }

  private static JsonNode content(JsonNode response) throws Exception {
    return JsonSupport.MAPPER.readTree(
        response.path("result").path("content").get(0).path("text").asText());
  }

  private record RuntimeFixture(
      LoopbackDevelopmentMcpServer server,
      DevelopmentTokenVerifier tokenVerifier,
      DevelopmentTestTokenIssuer tokenIssuer,
      SourceAttestationVerifier sourceVerifier,
      EnforcingMcpDispatcher dispatcher,
      InMemoryEvidenceLedger evidence,
      AtomicInteger targetInvocations) {
    RuntimeFixture withServer(LoopbackDevelopmentMcpServer value) {
      return new RuntimeFixture(
          value,
          tokenVerifier,
          tokenIssuer,
          sourceVerifier,
          dispatcher,
          evidence,
          targetInvocations);
    }
  }
}
