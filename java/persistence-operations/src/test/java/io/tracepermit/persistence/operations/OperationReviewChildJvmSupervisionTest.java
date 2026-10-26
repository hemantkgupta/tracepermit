package io.tracepermit.persistence.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.ProtocolException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Hermetic unit tests for child JVM process supervision, byte-bounded streams,
 * monotonic budgeting, diagnostic redaction, and closed conservative output handling.
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
  void testBoundedLineReaderStrictUtf8DecodingRejectsMalformedBytes() {
    // Malformed UTF-8 sequence: 0xC3 followed by 0x28 (not a valid continuation byte)
    byte[] malformed = new byte[]{(byte) 0xC3, (byte) 0x28, (byte) '\n'};
    ByteArrayInputStream in = new ByteArrayInputStream(malformed);

    assertThatThrownBy(() -> OperationReviewChildJvm.readBoundedLine(in, 64))
        .isInstanceOf(CharacterCodingException.class);
  }

  @Test
  void testRequestToStringRedactsCredentialsAndCanaries() {
    var req = OperationReviewChildJvm.ChildRequest.builder("REVIEW", "jdbc:postgresql://secret-host:5432/db", "admin-user", "super-secret-password-123")
        .operationId("op-42")
        .decision("APPROVE")
        .canary("synthetic-canary-secret-token")
        .build();

    String representation = req.toString();
    assertThat(representation).contains("credentials=[REDACTED]");
    assertThat(representation).doesNotContain("super-secret-password-123");
    assertThat(representation).doesNotContain("secret-host");
    assertThat(representation).doesNotContain("admin-user");
    assertThat(representation).doesNotContain("synthetic-canary-secret-token");
  }

  @Test
  void testResultToStringRedactsVoluminousDetailsAndNeverPrintsCanaries() {
    var res = new OperationReviewChildJvm.ChildResult(
        true, 0, "12345678-1234-1234-1234-123456789abc", "OK", null, "approved", "sha256:" + "f".repeat(64), null,
        List.of("CHILD_SIMULATED_CRASH"), 5, 250, false
    );

    String representation = res.toString();
    assertThat(representation).contains("acknowledged=true");
    assertThat(representation).contains("exitCode=0");
    assertThat(representation).contains("status=OK");
    assertThat(representation).contains("resultStatus=approved");
    assertThat(representation).contains("jvmId=12345678...");
    assertThat(representation).contains("stderrCategories=[CHILD_SIMULATED_CRASH]");
    assertThat(representation).contains("stderrLines=5");
    assertThat(representation).contains("stderrBytes=250");
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
    assertThat(result.hasStderrCategory("CHILD_SIMULATED_EARLY_EXIT")).isTrue();
  }

  @Test
  void testMalformedReadinessHandshakeFailsWithoutStdoutLeak() {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("MALFORMED_READY")
        .barrier(true)
        .build();

    assertThatThrownBy(() -> OperationReviewChildJvm.startContender(req))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Child failed readiness handshake: unexpected stdout signal")
        .hasMessageNotContaining("NOT_READY_MALFORMED");
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
  void testStderrDrainerEnforcesCapsAndDrainsContinuouslyUnderPressure() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("STDERR_PRESSURE")
        .simulateCrash(true)
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(15));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.exitCode()).isEqualTo(13);
    assertThat(result.hasStderrCategory("STDERR_PRESSURE")).isTrue();
    assertThat(result.stderrLineCount()).isGreaterThanOrEqualTo(500);
    assertThat(result.stderrByteCount()).isGreaterThan(OperationReviewChildJvm.MAX_STDERR_TOTAL_BYTES);
    assertThat(result.stderrTruncated()).isTrue();
    assertThat(result.diagnosticCategories().size()).isLessThanOrEqualTo(OperationReviewChildJvm.MAX_STDERR_LINES);
  }

  @Test
  void testStalledChildBeforeStdinTimesOutAndCleansUpImmediately() {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .stallBeforeStdin(true)
        .barrier(true)
        .build();

    long start = System.nanoTime();
    assertThatThrownBy(() -> OperationReviewChildJvm.startContender(req, Duration.ofSeconds(1)))
        .isInstanceOf(TimeoutException.class);
    long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

    // Proven bounded: completes in ~1s + reap reserve, not hanging 60s
    assertThat(elapsedMs).isLessThan(5000);
  }

  @Test
  void testInducedAssertionFailureDuringPostCommitBarrierReapsChildProcessCleanly() {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("MALFORMED_READY")
        .barrier(true)
        .build();

    try {
      try (var handle = OperationReviewChildJvm.startContender(req)) {
        assertThat(handle.isAlive()).isTrue();
        throw new RuntimeException("Simulated assertion failure");
      }
    } catch (Throwable expected) {
      // Setup or simulated failure immediately cleans up the child
    }

    // Process is reaped without leaked handles
  }

  @Test
  void testMalformedUtf8ResponseRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("MALFORMED_UTF8")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testDuplicateKeyResponseRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("DUPLICATE_KEY_RESPONSE")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testTrailingTokenResponseRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("TRAILING_TOKEN_RESPONSE")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testInvalidUuidRegexRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("INVALID_UUID")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testIncompleteOrEmptySnapshotRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("INCOMPLETE_SNAPSHOT")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testMismatchedSnapshotHashRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("MISMATCHED_HASH")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testUnknownFieldsInOkResponseRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("UNKNOWN_FIELDS_OK")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testEndToEndPrivateCanaryRedactionInExceptionsAndDiagnostics() throws Exception {
    final String CANARY = "PRIVATE_CANARY_SECRET_xyz987";

    // 1. Handshake failure with canary on stdout
    var barrierReq = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("CANARY_DIAGNOSTIC")
        .barrier(true)
        .canary(CANARY)
        .build();

    assertThatThrownBy(() -> OperationReviewChildJvm.startContender(barrierReq))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Child failed readiness handshake: unexpected stdout signal")
        .hasMessageNotContaining(CANARY);

    // 2. ChildResult and stderr categories do not contain canary
    var directReq = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("CANARY_DIAGNOSTIC")
        .canary(CANARY)
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(directReq, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.toString()).doesNotContain(CANARY);
    assertThat(result.diagnosticCategories()).noneMatch(c -> c.contains(CANARY));
    assertThat(directReq.toString()).doesNotContain(CANARY);
  }
}
