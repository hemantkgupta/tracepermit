package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.VerifiedSourceEvidence;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class McpToolRegistryTest {
  @Test
  void snapshotCannotBeWidenedAfterDispatcherConstruction() {
    McpToolRegistry source = new McpToolRegistry().register(tool("custom.read"));
    McpToolRegistry snapshot = source.snapshot();
    source.register(tool("custom.write"));

    assertThat(snapshot.hasTool("custom.read")).isTrue();
    assertThat(snapshot.hasTool("custom.write")).isFalse();
    assertThatThrownBy(() -> snapshot.register(tool("custom.extra")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void duplicateReservedAndMalformedNamesFailClosed() {
    McpToolRegistry registry = new McpToolRegistry().register(tool("custom.read"));
    assertThatThrownBy(() -> registry.register(tool("custom.read")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry.register(tool(EnforcingMcpDispatcher.GET_PROFILE)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry.register(tool("bad tool name")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static McpToolDefinition tool(String name) {
    return new McpToolDefinition() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "Simulated test tool.";
      }

      @Override
      public JsonNode inputSchema() {
        return JsonSupport.MAPPER.createObjectNode().put("type", "object");
      }

      @Override
      public ConnectorScope requiredScope() {
        return ConnectorScope.of("custom.read");
      }

      @Override
      public JsonNode normalizeArguments(JsonNode rawArguments) {
        return rawArguments;
      }

      @Override
      public JsonNode createEvent(
          String eventId,
          String traceId,
          Instant observedAt,
          JsonNode normalizedArguments,
          AuthenticatedConnector connector,
          Optional<VerifiedSourceEvidence> sourceEvidence) {
        throw new UnsupportedOperationException("Registry tests never dispatch a tool.");
      }
    };
  }
}
