package io.tracepermit.persistence.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
  void testSetupWriteStallTimesOutAndCleansUpAllResources() throws Exception {
    Process mockProc = new ProcessBuilder("sleep", "60").start();
    CountDownLatch writeEntered = new CountDownLatch(1);
    OutputStream blockingOut = new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        writeEntered.countDown();
        try {
          Thread.sleep(60_000);
        } catch (InterruptedException e) {
          throw new IOException("Interrupted write", e);
        }
      }
    };
    InputStream dummyIn = new ByteArrayInputStream(new byte[0]);
    InputStream dummyErr = new ByteArrayInputStream(new byte[0]);

    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p").build();

    long start = System.nanoTime();
    assertThatThrownBy(() -> OperationReviewChildJvm.startContender(
        req, Duration.ofMillis(300), mockProc, blockingOut, dummyIn, dummyErr))
        .isInstanceOf(TimeoutException.class)
        .hasMessageContaining("Child request write timed out");
    long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

    assertThat(elapsedMs).isLessThan(4000);
    mockProc.waitFor(2, TimeUnit.SECONDS);
    assertThat(mockProc.isAlive()).isFalse();
  }

  @Test
  void testSetupWriteInterruptedPreservesInterruptFlagAndCleansUp() throws Exception {
    Process mockProc = new ProcessBuilder("sleep", "60").start();
    CountDownLatch writeEntered = new CountDownLatch(1);
    OutputStream blockingOut = new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        writeEntered.countDown();
        try {
          Thread.sleep(60_000);
        } catch (InterruptedException e) {
          throw new IOException("Interrupted write", e);
        }
      }
    };
    InputStream dummyIn = new ByteArrayInputStream(new byte[0]);
    InputStream dummyErr = new ByteArrayInputStream(new byte[0]);

    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p").build();

    Thread caller = Thread.currentThread();
    Thread interrupter = new Thread(() -> {
      try {
        writeEntered.await(5, TimeUnit.SECONDS);
        Thread.sleep(50);
        caller.interrupt();
      } catch (InterruptedException ignored) {}
    });
    interrupter.start();

    try {
      assertThatThrownBy(() -> OperationReviewChildJvm.startContender(
          req, Duration.ofSeconds(10), mockProc, blockingOut, dummyIn, dummyErr))
          .isInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear interrupted flag
      interrupter.join(1000);
    }

    mockProc.waitFor(2, TimeUnit.SECONDS);
    assertThat(mockProc.isAlive()).isFalse();
  }

  static class DeliberateAssertionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    DeliberateAssertionFailure(String msg) { super(msg); }
  }

  @Test
  void testInducedAssertionFailureDuringBarrierReapsChildProcessCleanly() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("HOLD_READY")
        .barrier(true)
        .build();

    AtomicReference<OperationReviewChildJvm.ContenderHandle> retained = new AtomicReference<>();
    boolean bodyReached = false;
    try {
      try (var handle = OperationReviewChildJvm.startContender(req)) {
        retained.set(handle);
        bodyReached = true;
        assertThat(handle.isAlive()).isTrue();
        throw new DeliberateAssertionFailure("simulated deliberate assertion failure");
      }
    } catch (DeliberateAssertionFailure expected) {
      assertThat(expected.getMessage()).isEqualTo("simulated deliberate assertion failure");
    }

    assertThat(bodyReached).isTrue();
    var handle = retained.get();
    assertThat(handle).isNotNull();
    assertThat(handle.isAlive()).isFalse();
    assertThat(handle.cleanlyReaped()).isTrue();
    assertThat(handle.executorTerminated()).isTrue();
    assertThat(handle.drainerTerminated()).isTrue();
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
  void testBaselineOkResponseAcceptedCleanly() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("BASELINE_OK")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isTrue();
    assertThat(result.status()).isEqualTo("OK");
    assertThat(result.resultStatus()).isEqualTo("evaluating");
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
  void testMismatchedOperationIdRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("MISMATCHED_OP_ID")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testMismatchedResultStatusRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("MISMATCHED_RESULT_STATUS")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testMismatchedFactIdRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("MISMATCHED_FACT_ID")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");
  }

  @Test
  void testMismatchedBindingDigestRejectedConservatively() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("MISMATCHED_BINDING_DIGEST")
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
  void testStderrOverlongLineEnforcesLineByteCap() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("STDERR_OVERLONG_LINE")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(10));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.stderrByteCount()).isGreaterThanOrEqualTo(5000);
    assertThat(result.stderrTruncated()).isTrue();
    assertThat(result.hasStderrCategory("CHILD_ERROR")).isTrue();
  }

  @Test
  void testStderrMultibytePressureDrainsContinuouslyAndEnforcesByteCap() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("STDERR_MULTIBYTE_PRESSURE")
        .build();

    OperationReviewChildJvm.ChildResult result = OperationReviewChildJvm.runChild(req, Duration.ofSeconds(15));
    assertThat(result.acknowledged()).isFalse();
    assertThat(result.stderrByteCount()).isGreaterThan(OperationReviewChildJvm.MAX_STDERR_TOTAL_BYTES);
    assertThat(result.stderrTruncated()).isTrue();
    assertThat(result.hasStderrCategory("CHILD_ERROR")).isTrue();
    assertThat(result.diagnosticCategories().size()).isLessThanOrEqualTo(OperationReviewChildJvm.MAX_STDERR_LINES);
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
