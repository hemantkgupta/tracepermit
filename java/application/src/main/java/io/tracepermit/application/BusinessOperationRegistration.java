package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Registration is metadata only and conveys no approval or execution authority. */
public record BusinessOperationRegistration(boolean created, JsonNode operation) {
  public BusinessOperationRegistration {
    operation = Objects.requireNonNull(operation).deepCopy();
  }

  @Override
  public JsonNode operation() {
    return operation.deepCopy();
  }

  @Override
  public String toString() {
    return "BusinessOperationRegistration[created=" + created + "]";
  }
}
