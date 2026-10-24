package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_STORAGE_CORRUPT;
import static org.assertj.core.api.Assertions.*;

import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.JsonSupport;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoredOperationJsonTest {
  @Test
  void acceptsOneValueAndDoesNotReconfigureTheSharedMapper() throws Exception {
    assertThat(StoredOperationJson.read(" \n{\"x\":1}\t ", 128).path("x").asInt()).isEqualTo(1);
    assertThat(JsonSupport.MAPPER.readTree("{\"x\":1,\"x\":2}").path("x").asInt()).isEqualTo(2);
    assertThat(JsonSupport.MAPPER.readTree("{} false").isObject()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "null", "{", "{} {}", "{} false", "{} garbage",
      "{\"x\":0,\"x\":1}", "{\"nested\":{\"x\":0,\"x\":1}}"})
  void rejectsAmbiguousMalformedOrNullValues(String input) {
    assertThatThrownBy(() -> StoredOperationJson.read(input, 128))
        .isInstanceOf(BusinessOperationStorageException.class)
        .hasMessage(OPERATION_STORAGE_CORRUPT.name()).hasNoCause();
  }

  @Test
  void enforcesTheUtf8ByteCeilingAndRejectsAbsentValues() {
    String value = "{\"text\":\"é\"}";
    int bytes = value.getBytes(StandardCharsets.UTF_8).length;
    assertThat(StoredOperationJson.read(value, bytes).path("text").asText()).isEqualTo("é");
    assertThatThrownBy(() -> StoredOperationJson.read(value, bytes - 1))
        .isInstanceOf(BusinessOperationStorageException.class).hasMessage(OPERATION_STORAGE_CORRUPT.name());
    assertThatThrownBy(() -> StoredOperationJson.read(null, 128))
        .isInstanceOf(BusinessOperationStorageException.class).hasMessage(OPERATION_STORAGE_CORRUPT.name());
  }

  @Test
  void parserFragmentsNeverSurviveInThrowableDiagnostics() {
    String canary = "SYNTHETIC_STORED_VALUE_CANARY";
    Throwable failure = catchThrowable(() -> StoredOperationJson.read("{\"" + canary + "\":", 128));
    assertThat(failure).isInstanceOf(BusinessOperationStorageException.class)
        .hasMessage(OPERATION_STORAGE_CORRUPT.name()).hasNoCause().hasNoSuppressedExceptions();
    StringWriter rendered = new StringWriter();
    failure.printStackTrace(new PrintWriter(rendered));
    assertThat(rendered.toString()).doesNotContain(canary);
  }
}
