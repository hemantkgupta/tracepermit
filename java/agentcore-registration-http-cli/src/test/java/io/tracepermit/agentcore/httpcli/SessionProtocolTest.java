package io.tracepermit.agentcore.httpcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class SessionProtocolTest {
  @Test
  void exactReceiptsRoundTripAndNeverClaimAuthorityOrRollback() throws Exception {
    for (SessionReceipt receipt :
        java.util.List.of(
            SessionReceipt.ready(12_345),
            SessionReceipt.finalFor("SESSION_ENDED", 12_345),
            SessionReceipt.failure("CONFIG_VALID"),
            SessionReceipt.failure("USAGE"),
            SessionReceipt.failure("INPUT_INVALID"),
            SessionReceipt.failure("CONFIG_INVALID"),
            SessionReceipt.failure("NOT_READY"),
            SessionReceipt.failure("START_FAILED"),
            SessionReceipt.failure("UNKNOWN"))) {
      byte[] json = SessionProtocol.bytes(receipt);
      String text = new String(json, java.nio.charset.StandardCharsets.UTF_8);
      assertThat(text).contains("\"executionEnabled\":false", "\"rollbackConfirmed\":false");
      assertThat(text).doesNotContain("password", "Bearer", "credential", "operationId");
      var wire = new ByteArrayOutputStream();
      var output = new DataOutputStream(wire);
      output.writeInt(json.length);
      output.write(json);
      assertThat(SessionProtocol.readFrame(new ByteArrayInputStream(wire.toByteArray())))
          .contains(receipt);
      assertThat(receipt.toString()).doesNotContain("127.0.0.1", "12345");
    }
    assertThat(SessionReceipt.failure("CONFIG_VALID").exitCode()).isZero();
    assertThat(SessionReceipt.failure("USAGE").exitCode()).isEqualTo(64);
    assertThat(SessionReceipt.failure("UNKNOWN").exitCode()).isEqualTo(1);
  }

  @Test
  void strictReaderRejectsEveryFramingAndSchemaAmbiguity() throws Exception {
    var values =
        java.util.List.of(
            new byte[] {0, 0, 0, 0},
            new byte[] {0, 0, 4, 1},
            frame("{}"),
            frame("{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"}"),
            frame(
                "{\"schemaVersion\":\"1.0\",\"profile\":\""
                    + SessionProtocol.PROFILE
                    + "\",\"phase\":\"FINAL\",\"outcome\":\"UNKNOWN\",\"host\":null,\"port\":null,\"executionEnabled\":true,\"rollbackConfirmed\":false}"),
            frame(new String(SessionProtocol.bytes(SessionReceipt.failure("UNKNOWN"))) + " {}"));
    for (byte[] value : values) {
      assertThatThrownBy(() -> SessionProtocol.readFrame(new ByteArrayInputStream(value)))
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(
              failure ->
                  assertThat(failure.getMessage())
                      .isIn("SESSION_FRAME_INVALID", "SESSION_RECEIPT_INVALID"))
          .hasNoCause();
    }
    assertThat(SessionProtocol.readFrame(new ByteArrayInputStream(new byte[0]))).isEmpty();
  }

  @Test
  void outputDeadlineIsBoundedAndInterruptionIsPreserved() throws Exception {
    OutputStream blocked =
        new OutputStream() {
          @Override
          public void write(int value) {
            while (true) {
              try {
                Thread.sleep(1_000);
              } catch (InterruptedException ignored) {
              }
            }
          }
        };
    long started = System.nanoTime();
    assertThat(
            SessionProtocol.send(
                blocked, SessionReceipt.failure("UNKNOWN"), Duration.ofMillis(60)))
        .isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));

    Thread.currentThread().interrupt();
    assertThat(
            SessionProtocol.send(
                new ByteArrayOutputStream(),
                SessionReceipt.failure("UNKNOWN"),
                Duration.ofSeconds(1)))
        .isFalse();
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void receiptConstructorRejectsInvalidPhaseOutcomeAndEndpointCombinations() {
    assertThatThrownBy(() -> new SessionReceipt("READY", "UNKNOWN", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SessionReceipt("FINAL", "LISTENING", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SessionReceipt("FINAL", "SESSION_ENDED", "127.0.0.1", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SessionReceipt.ready(0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] frame(String value) throws Exception {
    byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var output = new ByteArrayOutputStream();
    new DataOutputStream(output).writeInt(bytes.length);
    output.write(bytes);
    return output.toByteArray();
  }
}
