package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.VerifiedSourceEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Thread-safe registry holding tool definitions, schemas, scopes, and normalizers
 * exposed by the MCP dispatcher.
 */
public final class McpToolRegistry {
  private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_.-]{0,127}$");
  private final Map<String, McpToolDefinition> tools = new LinkedHashMap<>();
  private final boolean frozen;

  public McpToolRegistry() {
    this(false);
  }

  private McpToolRegistry(boolean frozen) {
    this.frozen = frozen;
  }

  public synchronized McpToolRegistry register(McpToolDefinition tool) {
    Objects.requireNonNull(tool);
    if (frozen) throw new IllegalStateException("The MCP tool registry is frozen.");
    String name = Objects.requireNonNull(tool.name());
    if (!TOOL_NAME.matcher(name).matches()
        || EnforcingMcpDispatcher.GET_PROFILE.equals(name)
        || tools.containsKey(name)) {
      throw new IllegalArgumentException("The MCP tool name is invalid or already registered.");
    }
    ConnectorScope scope = Objects.requireNonNull(tool.requiredScope());
    String description = tool.description();
    JsonNode inputSchema = tool.inputSchema();
    if (description == null || description.isBlank()
        || description.length() > 512
        || inputSchema == null || !inputSchema.isObject()) {
      throw new IllegalArgumentException("The MCP tool metadata is invalid.");
    }
    tools.put(name, new RegisteredTool(name, description, inputSchema.deepCopy(), scope, tool));
    return this;
  }

  /** Freezes the set of trusted tools when a dispatcher is constructed. */
  public synchronized McpToolRegistry snapshot() {
    McpToolRegistry copy = new McpToolRegistry(true);
    copy.tools.putAll(tools);
    return copy;
  }

  public synchronized Optional<McpToolDefinition> find(String toolName) {
    return Optional.ofNullable(tools.get(toolName));
  }

  public synchronized Collection<McpToolDefinition> allTools() {
    return Collections.unmodifiableCollection(new ArrayList<>(tools.values()));
  }

  public synchronized boolean hasTool(String toolName) {
    return tools.containsKey(toolName);
  }

  public synchronized int size() {
    return tools.size();
  }

  private record RegisteredTool(
      String name,
      String description,
      JsonNode schema,
      ConnectorScope requiredScope,
      McpToolDefinition delegate) implements McpToolDefinition {
    @Override
    public JsonNode inputSchema() {
      return schema.deepCopy();
    }

    @Override
    public JsonNode normalizeArguments(JsonNode rawArguments) {
      return delegate.normalizeArguments(rawArguments.deepCopy());
    }

    @Override
    public JsonNode createEvent(
        String eventId,
        String traceId,
        Instant observedAt,
        JsonNode normalizedArguments,
        AuthenticatedConnector connector,
        Optional<VerifiedSourceEvidence> sourceEvidence) {
      return delegate.createEvent(
          eventId, traceId, observedAt, normalizedArguments.deepCopy(), connector, sourceEvidence);
    }
  }
}
