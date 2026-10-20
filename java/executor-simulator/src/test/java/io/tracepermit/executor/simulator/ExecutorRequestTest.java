package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutorRequestTest {
  @Test
  void readsExactlyOneBoundedRequest() throws IOException {
    ObjectNode root = request();
    ExecutorRequest parsed = ExecutorRequest.read(new ByteArrayInputStream(
        JsonSupport.MAPPER.writeValueAsBytes(root)));

    assertThat(parsed.permit()).isEqualTo("signed.permit.value");
    assertThat(parsed.action()).isEqualTo(ExecutorTestFixtures.action());
  }

  @Test
  void rejectsMalformedUnknownInjectedAndOversizedRequests() throws IOException {
    ObjectNode unknown = request();
    unknown.put("publicKey", "request-key");
    ObjectNode wrongVersion = request();
    wrongVersion.put("schemaVersion", "2.0");
    ObjectNode missingAction = request();
    missingAction.remove("action");
    ObjectNode nonObjectAction = request();
    nonObjectAction.put("action", "not-an-action");
    ObjectNode blankPermit = request();
    blankPermit.put("permit", " ");
    ObjectNode longPermit = request();
    longPermit.put("permit", "x".repeat(16_385));

    for (byte[] rejected : List.of(
        new byte[0],
        "not-json".getBytes(StandardCharsets.UTF_8),
        "{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"}"
            .getBytes(StandardCharsets.UTF_8),
        JsonSupport.MAPPER.writeValueAsBytes(unknown),
        JsonSupport.MAPPER.writeValueAsBytes(wrongVersion),
        JsonSupport.MAPPER.writeValueAsBytes(missingAction),
        JsonSupport.MAPPER.writeValueAsBytes(nonObjectAction),
        JsonSupport.MAPPER.writeValueAsBytes(blankPermit),
        JsonSupport.MAPPER.writeValueAsBytes(longPermit),
        new byte[65_537])) {
      assertThatThrownBy(() -> ExecutorRequest.read(new ByteArrayInputStream(rejected)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("INVALID_EXECUTOR_REQUEST");
    }
  }

  @Test
  void rejectsTrailingDocumentsAndInputFailures() throws IOException {
    byte[] first = JsonSupport.MAPPER.writeValueAsBytes(request());
    byte[] trailing = (new String(first, StandardCharsets.UTF_8) + " {}")
        .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> ExecutorRequest.read(new ByteArrayInputStream(trailing)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExecutorRequest.read(new InputStream() {
      @Override public int read() throws IOException { throw new IOException("sensitive-input"); }
      @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        throw new IOException("sensitive-input");
      }
    })).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_EXECUTOR_REQUEST")
        .hasMessageNotContaining("sensitive-input");
  }

  private static ObjectNode request() {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "1.0");
    root.put("permit", "signed.permit.value");
    root.set("action", ExecutorTestFixtures.action());
    return root;
  }
}
