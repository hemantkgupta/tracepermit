package io.tracepermit.endpoint;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

public interface GatewayTransport {
  Optional<JsonNode> forward(
      JsonNode request, String bearerToken, String compactSourceAttestation);
}
