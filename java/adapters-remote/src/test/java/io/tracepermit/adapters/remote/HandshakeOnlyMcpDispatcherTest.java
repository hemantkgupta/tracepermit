package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class HandshakeOnlyMcpDispatcherTest {
  private final HandshakeOnlyMcpDispatcher dispatcher = new HandshakeOnlyMcpDispatcher();

  @Test
  void chatGptAndClaudeStyleInitializeRequestsNegotiateWithoutTrustingClientInfo()
      throws Exception {
    for (String request :
        java.util.List.of(
            request(
                "openai-mcp",
                "1.0",
                "2025-11-25"),
            request(
                "chatgpt-web",
                "1.0",
                "2025-06-18"),
            request(
                "claude-web",
                "1.0",
                "2025-03-26"))) {
      RemoteMcpResponse response =
          dispatcher.dispatch(JsonSupport.MAPPER.readTree(request), RemoteTestSupport.connector(RemoteTestSupport.DISCOVER));
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.body().path("result").path("protocolVersion").asText())
          .isIn("2025-11-25", "2025-06-18", "2025-03-26");
      assertThat(response.body().toString())
          .doesNotContain("tenant:acme", "credential", "chatgpt-connector");
    }
  }

  @Test
  void unknownProtocolNegotiatesCurrentServerVersion() throws Exception {
    JsonNode input = JsonSupport.MAPPER.readTree(request("future-client", "1", "2099-01-01"));
    assertThat(
            dispatcher
                .dispatch(input, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
                .body()
                .path("result")
                .path("protocolVersion")
                .asText())
        .isEqualTo(HandshakeOnlyMcpDispatcher.CURRENT_PROTOCOL_VERSION);
  }

  @Test
  void modernServerDiscoveryIsPublicStaticAndNonExecuting() throws Exception {
    JsonNode request =
        JsonSupport.MAPPER.readTree(
            """
            {"jsonrpc":"2.0","id":"discover-1","method":"server/discover","params":{
              "_meta":{
                "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                "io.modelcontextprotocol/clientInfo":{"name":"openai-mcp","version":"1"},
                "io.modelcontextprotocol/clientCapabilities":{}}}}
            """);

    RemoteMcpResponse response = dispatcher.dispatchPublic(request).orElseThrow();
    JsonNode result = response.body().path("result");
    assertThat(response.status()).isEqualTo(200);
    assertThat(result.path("resultType").asText()).isEqualTo("complete");
    assertThat(result.path("supportedVersions").toString())
        .contains(HandshakeOnlyMcpDispatcher.CURRENT_PROTOCOL_VERSION, "2025-11-25");
    assertThat(result.path("capabilities").has("tools")).isTrue();
    assertThat(
            result
                .path("_meta")
                .path("io.modelcontextprotocol/serverInfo")
                .path("name")
                .asText())
        .isEqualTo("TracePermit");
    assertThat(result.path("cacheScope").asText()).isEqualTo("public");
    assertThat(response.body().toString())
        .doesNotContain("openai-mcp", "tenant:acme", "credential", "chatgpt-connector");

    JsonNode invalid =
        JsonSupport.MAPPER.readTree(
            """
            {"jsonrpc":"2.0","id":"discover-2","method":"server/discover","params":{
              "authority":"admin"}}
            """);
    assertThat(
            dispatcher
                .dispatchPublic(invalid)
                .orElseThrow()
                .body()
                .path("error")
                .path("data")
                .path("code")
                .asText())
        .isEqualTo("INVALID_PARAMS");
  }

  @Test
  void discoveryRequiresScopeAndReturnsOnlySimulatedToolDescription() throws Exception {
    JsonNode request =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
    RemoteMcpResponse response =
        dispatcher.dispatch(request, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER));
    assertThat(response.body().path("result").path("tools")).hasSize(1);
    assertThat(response.body().toString()).contains("strictly simulated");

    assertThatThrownBy(
            () -> dispatcher.dispatch(request, RemoteTestSupport.connector(RemoteTestSupport.READ)))
        .isInstanceOf(AuthenticationException.class)
        .extracting(error -> ((AuthenticationException) error).code())
        .isEqualTo("AUTHORIZATION_DENIED");
  }

  @Test
  void invocationUnknownMethodsAndInjectedAuthorityDoNotGainDispatch() throws Exception {
    JsonNode call =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"aws.logs.filter_log_events\"}}");
    assertThat(
            dispatcher
                .dispatch(call, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
                .body()
                .path("error")
                .path("data")
                .path("code")
                .asText())
        .isEqualTo("REMOTE_TOOL_INVOCATION_NOT_CONFIGURED");

    JsonNode unknown =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/list\",\"params\":{}}");
    assertThat(
            dispatcher
                .dispatch(unknown, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
                .body()
                .path("error")
                .path("data")
                .path("code")
                .asText())
        .isEqualTo("METHOD_NOT_FOUND");

    JsonNode injected =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\",\"params\":{},\"authenticated\":true}");
    assertThat(
            dispatcher
                .dispatch(injected, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
                .body()
                .path("error")
                .path("data")
                .path("code")
                .asText())
        .isEqualTo("INVALID_REQUEST");
  }

  @Test
  void notificationsPingAndInvalidRequestShapesHaveBoundedSemantics() throws Exception {
    JsonNode initialized =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
    assertThat(
            dispatcher
                .dispatch(initialized, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
                .status())
        .isEqualTo(204);
    JsonNode ping =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":\"ping-1\",\"method\":\"ping\",\"params\":{}}");
    assertThat(
            dispatcher
                .dispatch(ping, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
                .body()
                .path("id")
                .asText())
        .isEqualTo("ping-1");
    JsonNode invalid = JsonSupport.MAPPER.readTree("{\"jsonrpc\":\"1.0\",\"method\":42}");
    assertThat(
            dispatcher
                .dispatch(invalid, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
                .body()
                .path("error")
                .path("code")
                .asInt())
        .isEqualTo(-32600);
  }

  @Test
  void invalidInitializeNotificationAndIdsAreRejectedWithoutAuthority() throws Exception {
    JsonNode badCapabilities =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":[],"
                + "\"clientInfo\":{\"name\":\"client\",\"version\":\"1\"}}}");
    assertThat(
            dispatcher
                .dispatch(badCapabilities, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
                .body()
                .path("error")
                .path("data")
                .path("code")
                .asText())
        .isEqualTo("INVALID_PARAMS");

    JsonNode notificationWithId =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"notifications/initialized\",\"params\":{}}");
    assertThat(
            dispatcher
                .dispatch(
                    notificationWithId,
                    RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
                .body()
                .path("error")
                .path("data")
                .path("code")
                .asText())
        .isEqualTo("INVALID_REQUEST");

    for (String request :
        java.util.List.of(
            "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":\"\",\"method\":\"ping\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"\",\"version\":\"1\"}}}")) {
      assertThatThrownBy(
              () ->
                  dispatcher.dispatch(
                      JsonSupport.MAPPER.readTree(request),
                      RemoteTestSupport.connector(RemoteTestSupport.DISCOVER)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void responseValueRejectsContradictoryStatusAndBody() {
    assertThatThrownBy(() -> new RemoteMcpResponse(199, JsonSupport.MAPPER.nullNode()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RemoteMcpResponse(200, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RemoteMcpResponse(204, JsonSupport.MAPPER.createObjectNode()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String request(String clientName, String clientVersion, String protocol) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
        + "\"protocolVersion\":\""
        + protocol
        + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\""
        + clientName
        + "\",\"version\":\""
        + clientVersion
        + "\"}}}";
  }
}
