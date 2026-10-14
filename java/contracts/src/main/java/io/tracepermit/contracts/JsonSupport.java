package io.tracepermit.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

public final class JsonSupport {
  public static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonSupport() {}

  public static JsonNode read(Path path) {
    try {
      return MAPPER.readTree(path.toFile());
    } catch (IOException exception) {
      throw new IllegalArgumentException("Cannot read JSON from " + path, exception);
    }
  }
}
