package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;

public record RemoteMcpResponse(int status, JsonNode body) {
  public RemoteMcpResponse {
    if (status < 200 || status > 599) {
      throw new IllegalArgumentException("status is invalid.");
    }
    if (status != 204 && body == null) {
      throw new IllegalArgumentException("A non-empty response status requires a body.");
    }
    if (status == 204 && body != null) {
      throw new IllegalArgumentException("A 204 response must not include a body.");
    }
  }
}
