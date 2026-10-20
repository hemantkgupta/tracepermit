package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** Strict, bounded one-document input for the one-shot executor. */
record ExecutorRequest(String permit, JsonNode action) {
  private static final int MAXIMUM_BYTES = 65_536;
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static ExecutorRequest read(InputStream input) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int total = 0;
      int count;
      while ((count = input.read(buffer)) != -1) {
        total += count;
        if (total > MAXIMUM_BYTES) throw new IllegalArgumentException("INVALID_EXECUTOR_REQUEST");
        bytes.write(buffer, 0, count);
      }
      JsonNode root = JSON.readTree(bytes.toByteArray());
      require(root != null && root.isObject());
      Set<String> fields = new HashSet<>();
      root.fieldNames().forEachRemaining(fields::add);
      require(fields.equals(Set.of("schemaVersion", "permit", "action")));
      require("1.0".equals(root.path("schemaVersion").asText()));
      JsonNode permit = root.path("permit");
      require(permit.isTextual() && !permit.textValue().isBlank() && permit.textValue().length() <= 16_384);
      JsonNode action = root.path("action");
      require(action.isObject());
      return new ExecutorRequest(permit.textValue(), action.deepCopy());
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException("INVALID_EXECUTOR_REQUEST");
    }
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException("INVALID_EXECUTOR_REQUEST");
  }
}
