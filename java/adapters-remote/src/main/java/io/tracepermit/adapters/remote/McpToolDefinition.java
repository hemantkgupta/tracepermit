package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.VerifiedSourceEvidence;

import java.time.Instant;
import java.util.Optional;

/**
 * Definition of an MCP tool executable within {@link EnforcingMcpDispatcher}.
 *
 * <p>Encapsulates tool metadata, JSON Schema for MCP discovery, required connector
 * scope, argument normalization, and canonical Action event construction. Only
 * trusted application code may register definitions; implementations must be
 * thread-safe and must not perform a target effect during normalization or
 * event construction.
 */
public interface McpToolDefinition {
  /** The unique MCP tool name (e.g. {@code "aws.logs.filter_log_events"}). */
  String name();

  /** Human-readable tool description advertised in {@code tools/list}. */
  String description();

  /** JSON Schema describing accepted arguments advertised in {@code tools/list}. */
  JsonNode inputSchema();

  /** The OAuth/connector scope required to invoke this tool. */
  ConnectorScope requiredScope();

  /**
   * Validates and normalizes raw tool arguments.
   *
   * @param rawArguments the arguments object supplied in the JSON-RPC request.
   * @return normalized arguments JsonNode, or {@code null} if arguments are invalid.
   */
  JsonNode normalizeArguments(JsonNode rawArguments);

  /**
   * Constructs the canonical Action event for this tool invocation.
   */
  JsonNode createEvent(
      String eventId,
      String traceId,
      Instant observedAt,
      JsonNode normalizedArguments,
      AuthenticatedConnector connector,
      Optional<VerifiedSourceEvidence> sourceEvidence);
}
