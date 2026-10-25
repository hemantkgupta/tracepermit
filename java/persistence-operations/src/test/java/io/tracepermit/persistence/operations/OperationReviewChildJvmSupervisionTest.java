package io.tracepermit.persistence.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for child JVM process supervision, byte-bounded streams,
 * monotonic budgeting, diagnostic redaction, and conservative output handling.
 */
class OperationReviewChildJvmSupervisionTest {

  @Test
  void testBoundedLineReaderEnforcesMaxByteBound() throws Exception {
    String input = "hello world\n";
    ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
    String read = OperationReviewChildJvm.readBoundedLine(in, 64);
    assertThat(read).isEqualTo("hello world");

    // Over-limit input before newline
    String oversized = "a".repeat(128) + "\n";
    ByteArrayInputStream overIn = new ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> OperationReviewChildJvm.readBoundedLine(overIn, 64))
        .isInstanceOf(ProtocolException.class)
        .hasMessageContaining("Stream line length exceeded maximum limit of 64 bytes");

    // Empty input returns null
    ByteArrayInputStream emptyIn = new ByteArrayInputStream(new byte[0]);
    assertThat(OperationReviewChildJvm.readBoundedLine(emptyIn, 64)).isNull();
  }

  @Test
  void testRequestToStringRedactsCredentials() {
    var req = OperationReviewChildJvm.ChildRequest.builder("REVIEW", "jdbc:postgresql://secret-host:5432/db", "admin-user", "super-secret-password-123")
        .operationId("op-42")
        .decision("APPROVE")
        .build();

    String representation = req.toString();
    assertThat(representation).contains("credentials=[REDACTED]");
    assertThat(representation).doesNotContain("super-secret-password-123");
    assertThat(representation).doesNotContain("secret-host");
    assertThat(representation).doesNotContain("admin-user");
  }

  @Test
  void testResultToStringRedactsVoluminousDetails() {
    var res = new OperationReviewChildJvm.ChildResult(
        true, 0, "12345678-1234-1234-1234-123456789abc", "OK", null, "approved", "sha256:" + "f".repeat(64), null, null
    );

    String representation = res.toString();
    assertThat(representation).contains("acknowledged=true");
    assertThat(representation).contains("exitCode=0");
    assertThat(representation).contains("status=OK");
    assertThat(representation).contains("resultStatus=approved");
    assertThat(representation).contains("jvmId=12345678...");
  }

  @Test
  void testSilenceChildExitsWithoutResponseReportsUnacknowledged() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("SILENT")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testEarlyExitChildReportsUnacknowledgedWithNonZeroExitCode() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("EARLY_EXIT")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
    assertThat(result.exitCode()).isEqualTo(42);
  }

  @Test
  void testMalformedReadinessHandshakeFails() {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("MALFORMED_READY")
        .barrier(true)
        .build();

    assertThatThrownBy(() -> OperationReviewChildJvm.startContender(req))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Child failed readiness handshake");
  }

  @Test
  void testOversizedResponseLineRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("OVERSIZED_RESPONSE")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testNoNewlineResponseHandledSafely() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("NO_NEWLINE")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testStderrPressureDrainedConcurrentlyWithoutDeadlock() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("STDERR_PRESSURE")
        .simulateCrash(true)
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.exitCode()).isEqualTo(13);
    assertThat(result.stderrLines()).isNotEmpty();
    assertThat(result.stderrLines().size()).isLessThanOrEqualTo(OperationReviewChildJvm.MAX_STDERR_LINES);
  }
}
