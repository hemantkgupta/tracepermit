package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Protocol tests only: this class does not claim native Codex client qualification. */
final class SimulatorApprovalMcpAdapterTest {
  private static final Set<String> TOOLS = Set.of(ApprovalMcpAdapter.REQUEST, ApprovalMcpAdapter.STATUS, ApprovalMcpAdapter.RESUME);
  private final ApprovalMcpAdapter adapter = new ApprovalMcpAdapter((name, args) -> args, TOOLS);

  @Test void handshakeDiscoveryAndNotificationsAdvertiseOnlyTheBoundedWorkflow() throws Exception {
    var response = adapter.dispatch(json("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
         "capabilities":{},"clientInfo":{"name":"protocol-fixture","version":"1"}}}
        """));
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().path("result").path("protocolVersion").asText()).isEqualTo("2025-03-26");
    assertThat(response.body().path("result").path("instructions").asText()).contains("pending", "different human", "Never retry");
    assertThat(adapter.dispatch(json("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).status()).isEqualTo(202);
    assertThat(dispatch("server/discover", "{}").path("result").path("instructions").asText()).contains("Simulator only");
    assertThat(dispatch("ping", "{}").path("result").isObject()).isTrue();
    var catalog = adapter.dispatch(json("{\"jsonrpc\":\"2.0\",\"id\":\"list\",\"method\":\"tools/list\"}")).body();
    assertThat(catalog.path("result").path("tools")).hasSize(3);
    assertThat(dispatch("tools/list", "{\"_meta\":{}}").path("result").path("tools")).hasSize(3);
    assertThat(dispatch("tools/list", "{\"_meta\":1}").has("error")).isTrue();
    assertThat(dispatch("tools/list", "{\"cursor\":\"arbitrary\"}").has("error")).isTrue();
    assertThat(dispatch("initialize", "{}").has("error")).isTrue();
    assertThat(dispatch("initialize", "{\"protocolVersion\":\"2025-03-26\",\"clientInfo\":{\"name\":\"x\",\"version\":\"1\"},\"capabilities\":1}").has("error")).isTrue();
    assertThat(dispatch("unsupported", "{}").path("error").path("code").asInt()).isEqualTo(-32601);
  }

  @ParameterizedTest @ValueSource(strings = {"[]", "{}", "{\"jsonrpc\":2.0,\"id\":1,\"method\":\"ping\"}",
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":1}", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":[]}",
      "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}", "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}",
      "{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}", "{\"jsonrpc\":\"2.0\",\"id\":\" \",\"method\":\"ping\"}",
      "{\"jsonrpc\":\"2.0\",\"id\":\"a\\nb\",\"method\":\"ping\"}",
      "{\"jsonrpc\":\"2.0\",\"id\":9223372036854775808,\"method\":\"ping\"}",
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"authenticated\":true}"})
  void malformedEnvelopesFailWithoutInvoking(String request) throws Exception {
    assertThat(adapter.dispatch(json(request)).body().has("error")).isTrue();
  }

  @ParameterizedTest @ValueSource(strings = {"{}", "{\"name\":1}", "{\"name\":\"unknown\",\"arguments\":{}}",
      "{\"name\":\"tracepermit_get_approval\",\"arguments\":{}}",
      "{\"name\":\"tracepermit_get_approval\",\"arguments\":{\"approvalRequestId\":123456789012345678901234567890123456}}",
      "{\"name\":\"tracepermit_get_approval\",\"arguments\":{\"approvalRequestId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"subject\":\"admin\"}}",
      "{\"name\":\"tracepermit_get_approval\",\"arguments\":[],\"_meta\":{}}",
      "{\"name\":\"tracepermit_resume_approval\",\"arguments\":{\"approvalRequestId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"requestHash\":1}}",
      "{\"name\":\"tracepermit_get_approval\",\"arguments\":{},\"_meta\":1}",
      "{\"name\":\"tracepermit_get_approval\",\"arguments\":{},\"verified\":true}"})
  void toolSchemasRejectAuthorityInjectionAndMutation(String params) throws Exception {
    assertThat(dispatch("tools/call", params).has("error")).isTrue();
  }

  @Test void boundsAndToolFailuresAreSafeMcpResults() throws Exception {
    assertThat(adapter.dispatch(json("{\"jsonrpc\":\"2.0\",\"id\":\"" + "a".repeat(129) + "\",\"method\":\"ping\"}")).body().has("error")).isTrue();
    String params = "{\"name\":\"tracepermit_resume_approval\",\"arguments\":{\"approvalRequestId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"requestHash\":\"" + "a".repeat(129) + "\"}}";
    assertThat(dispatch("tools/call", params).has("error")).isTrue();
    var call = json("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"tracepermit_request_simulated_change\",\"arguments\":{\"groupId\":\"sg-demo\",\"port\":443,\"cidr\":\"192.0.2.0/24\"},\"_meta\":{\"role\":\"reviewer\"}}}");
    assertThat(adapter.dispatch(call).body().path("result").path("isError").asBoolean()).isFalse();
    for (RuntimeException failure : java.util.List.of(new SecurityException("secret"),
        new IllegalArgumentException("secret"), new IllegalArgumentException("APPROVAL_BINDING_MISMATCH"),
        new IllegalStateException("APPROVAL_EXPIRED"), new IllegalStateException("secret"),
        new IllegalStateException(), new RuntimeException("secret"),
        new SimulatorApprovalServer.HttpFailure(403, "NOT_AUTHORIZED"))) {
      var result = new ApprovalMcpAdapter((name, args) -> { throw failure; }, TOOLS).dispatch(call).body();
      assertThat(result.path("result").path("isError").asBoolean()).isTrue();
      assertThat(result.toString()).doesNotContain("secret");
      assertThat(result.path("result").path("content").get(0).path("text").asText())
          .isEqualTo(result.path("result").path("structuredContent").toString());
    }
  }

  private JsonNode dispatch(String method, String params) throws Exception {
    return adapter.dispatch(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}")).body();
  }
  private static JsonNode json(String text) throws Exception { return JsonSupport.MAPPER.readTree(text); }
}
