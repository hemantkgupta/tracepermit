package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.identity.AuthenticatedConnector;
import java.util.Optional;

@FunctionalInterface
public interface RemoteMcpDispatcher {
  RemoteMcpResponse dispatch(JsonNode request, AuthenticatedConnector connector);

  /**
   * Returns a response only when this dispatcher explicitly exposes the request as public MCP
   * metadata. Implementations must never execute a tool or resolve user data from this path.
   */
  default Optional<RemoteMcpResponse> dispatchPublic(JsonNode request) {
    return Optional.empty();
  }
}
