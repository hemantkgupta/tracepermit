package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_STORAGE_CORRUPT;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.JsonSupport;
import java.nio.charset.StandardCharsets;

/** Strict, bounded stored-value decoding; does not change global JSON or canonical hash semantics. */
final class StoredOperationJson {
  private static final ObjectReader READER = JsonSupport.MAPPER.copy()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private StoredOperationJson() {}

  static JsonNode read(String value, int maximumBytes) {
    if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) throw corrupt();
    try {
      JsonNode parsed = READER.readTree(value);
      if (parsed == null || parsed.isNull() || parsed.isMissingNode()) throw corrupt();
      return parsed;
    } catch (java.io.IOException | RuntimeException failure) {
      // Never retain parser diagnostics, input fragments, causes or suppressed failures.
      throw corrupt();
    }
  }

  private static BusinessOperationStorageException corrupt() {
    return new BusinessOperationStorageException(OPERATION_STORAGE_CORRUPT);
  }
}
