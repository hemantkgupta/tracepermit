package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface SimulatedTarget {
  JsonNode invoke(JsonNode action);
}
