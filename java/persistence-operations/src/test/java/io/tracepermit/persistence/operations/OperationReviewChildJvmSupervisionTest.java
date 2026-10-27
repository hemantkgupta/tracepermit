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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    AtomicReference<OperationReviewChildJvm.ContenderHandle> retained = new AtomicReference<>();
    OperationReviewChildJvm.testHandleObserver = retained::set;
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
    try {
      assertThatThrownBy(() -> OperationReviewChildJvm.startContender(
          req, Duration.ofMillis(300), mockProc, blockingOut, dummyIn, dummyErr))
          .isInstanceOf(TimeoutException.class)
          .hasMessageContaining("Child request write timed out");
      long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

      assertThat(elapsedMs)
          .isLessThan(OperationReviewChildJvm.ContenderHandle.CLEANUP_ENVELOPE_MS + 1000);
      assertThat(writeEntered.await(5, TimeUnit.SECONDS)).isTrue();

      mockProc.waitFor(2, TimeUnit.SECONDS);
      assertThat(mockProc.isAlive()).isFalse();

      var handle = retained.get();
      assertThat(handle).isNotNull();
      assertThat(handle.cleanlyReaped()).isTrue();
      assertThat(handle.executorTerminated()).isTrue();
      assertThat(handle.drainerTerminated()).isTrue();
      assertThat(handle.streamsClosed()).isTrue();
    } finally {
      OperationReviewChildJvm.testHandleObserver = null;
      if (mockProc.isAlive()) {
        mockProc.destroyForcibly();
        mockProc.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void testSetupWriteInterruptedPreservesInterruptFlagAndCleansUp() throws Exception {
    Process mockProc = new ProcessBuilder("sleep", "60").start();
    AtomicReference<OperationReviewChildJvm.ContenderHandle> retained = new AtomicReference<>();
    OperationReviewChildJvm.testHandleObserver = retained::set;
    CountDownLatch writeEntered = new CountDownLatch(1);
    AtomicBoolean writeEnteredReached = new AtomicBoolean(false);
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
        if (writeEntered.await(5, TimeUnit.SECONDS)) {
          writeEnteredReached.set(true);
          Thread.sleep(50);
          caller.interrupt();
        }
      } catch (InterruptedException ignored) {}
    });
    interrupter.start();

    try {
      assertThatThrownBy(() -> OperationReviewChildJvm.startContender(
          req, Duration.ofSeconds(10), mockProc, blockingOut, dummyIn, dummyErr))
          .isInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(writeEnteredReached.get()).isTrue();

      mockProc.waitFor(2, TimeUnit.SECONDS);
      assertThat(mockProc.isAlive()).isFalse();

      var handle = retained.get();
      assertThat(handle).isNotNull();
      assertThat(handle.cleanlyReaped()).isTrue();
      assertThat(handle.executorTerminated()).isTrue();
      assertThat(handle.drainerTerminated()).isTrue();
    } finally {
      Thread.interrupted(); // clear interrupted flag
      interrupter.join(1000);
      OperationReviewChildJvm.testHandleObserver = null;
      if (mockProc.isAlive()) {
        mockProc.destroyForcibly();
        mockProc.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void testInvalidBudgetsLaunchNoChild() {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p").build();
    assertThatThrownBy(() -> OperationReviewChildJvm.startContender(req, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("commandBudget must not be null");

    assertThatThrownBy(() -> OperationReviewChildJvm.startContender(req, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commandBudget must be positive");

    assertThatThrownBy(() -> OperationReviewChildJvm.startContender(req, Duration.ofSeconds(-5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commandBudget must be positive");

    assertThatThrownBy(() -> OperationReviewChildJvm.startContender(req, Duration.ofSeconds(Long.MAX_VALUE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void testPartialConstructionFailureCleansUpAcquiredResources() throws Exception {
    Process mockProc = new ProcessBuilder("sleep", "60").start();
    AtomicReference<OperationReviewChildJvm.PartialConstructionObservation> obsRef = new AtomicReference<>();
    OperationReviewChildJvm.testPartialCleanupObserver = obsRef::set;
    CountDownLatch taskRunning = new CountDownLatch(1);
    try {
      OperationReviewChildJvm.testHookBeforeHandleCreation = (exec) -> {
        exec.submit(() -> {
          taskRunning.countDown();
          try {
            Thread.sleep(10000);
          } catch (InterruptedException ignored) {}
        });
        try {
          if (!taskRunning.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("simulated worker did not start");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted before simulated worker started", interrupted);
        }
        throw new RuntimeException("simulated failure before handle creation");
      };
      var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p").build();
      assertThatThrownBy(() -> OperationReviewChildJvm.startContender(
          req, Duration.ofSeconds(5), mockProc,
          mockProc.getOutputStream(), mockProc.getInputStream(), mockProc.getErrorStream()))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("simulated failure before handle creation");

      mockProc.waitFor(2, TimeUnit.SECONDS);
      assertThat(mockProc.isAlive()).isFalse();

      OperationReviewChildJvm.PartialConstructionObservation obs = obsRef.get();
      assertThat(obs).isNotNull();
      assertThat(obs.cleanupCompleted).isTrue();
      assertThat(obs.processReaped).isTrue();
      assertThat(obs.executorTerminated).isTrue();
      assertThat(obs.drainerJoined).isTrue();
      assertThat(obs.streamsClosed).isTrue();
      assertThat(obs.stdinWriterCloseFuture).isNotNull().isDone();
      assertThat(obs.outputCloseFuture).isNotNull().isDone();
      assertThat(obs.inputCloseFuture).isNotNull().isDone();
      assertThat(obs.errorCloseFuture).isNotNull().isDone();
    } finally {
      OperationReviewChildJvm.testHookBeforeHandleCreation = null;
      OperationReviewChildJvm.testPartialCleanupObserver = null;
      if (mockProc.isAlive()) {
        mockProc.destroyForcibly();
        mockProc.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void testHostileStreamCloseDoesNotHangTeardown() throws Exception {
    Process mockProc = new ProcessBuilder("sleep", "60").start();
    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch releaseClose = new CountDownLatch(1);

    OutputStream hostileOut = new OutputStream() {
      @Override
      public void write(int b) throws IOException {}

      @Override
      public void close() throws IOException {
        closeEntered.countDown();
        try {
          releaseClose.await();
        } catch (InterruptedException ie) {
          try {
            releaseClose.await();
          } catch (InterruptedException ignored) {}
        }
      }
    };

    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p").build();
    OperationReviewChildJvm.ContenderHandle handle = null;
    ExecutorService closeExecutor = Executors.newSingleThreadExecutor();
    Future<?> closeCall = null;
    try {
      handle = OperationReviewChildJvm.startContender(
          req, Duration.ofSeconds(5), mockProc,
          hostileOut, new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0])
      );

      long closeStart = System.nanoTime();
      OperationReviewChildJvm.ContenderHandle ownedHandle = handle;
      closeCall = closeExecutor.submit(ownedHandle::close);

      // Prove the close worker reached the controlled hostile stream before the owner returns.
      assertThat(closeEntered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(closeCall.isDone()).isFalse();

      closeCall.get(OperationReviewChildJvm.ContenderHandle.CLEANUP_ENVELOPE_MS + 1000, TimeUnit.MILLISECONDS);
      long closeElapsedMs = Duration.ofNanos(System.nanoTime() - closeStart).toMillis();

      // Assert caller returns within finite bound (does not hang)
      assertThat(closeElapsedMs)
          .isLessThan(OperationReviewChildJvm.ContenderHandle.CLEANUP_ENVELOPE_MS + 1000);

      // Residual unclosed status reported honestly
      assertThat(handle.streamsClosed()).isFalse();
      assertThat(handle.cleanlyReaped()).isTrue();
      assertThat(handle.executorTerminated()).isTrue();

      // Closer worker future is owned and not done before release
      assertThat(handle.stdinCloseFuture()).isNotNull();
      assertThat(handle.stdinCloseFuture().isDone()).isFalse();
    } finally {
      // In unconditional fixture finally, release the controlled stall and verify clean worker termination
      releaseClose.countDown();
      if (handle != null && handle.stdinCloseFuture() != null) {
        handle.stdinCloseFuture().get(2, TimeUnit.SECONDS);
        assertThat(handle.stdinCloseFuture().isDone()).isTrue();
      }
      closeExecutor.shutdownNow();
      assertThat(closeExecutor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      if (mockProc.isAlive()) {
        mockProc.destroyForcibly();
        mockProc.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void testMissingOrNullExpectedProfileRefusesBaselineOkResponseAsUnacknowledged() throws Exception {
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p")
        .testMode("BASELINE_OK")
        .build();

    // Start contender with explicitly null expectedProfile
    try (var handle = OperationReviewChildJvm.startContenderWithExplicitProfile(req, Duration.ofSeconds(10), null)) {
      OperationReviewChildJvm.ChildResult result = handle.awaitResult(Duration.ofSeconds(10));

      // With missing expectedProfile, the valid baseline OK response MUST be refused as UNACKNOWLEDGED
      assertThat(result.acknowledged()).isFalse();
      assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");

      // Verify direct liveness and cleanup postconditions
      assertThat(handle.isAlive()).isFalse();
      assertThat(handle.cleanlyReaped()).isTrue();
      assertThat(handle.executorTerminated()).isTrue();
      assertThat(handle.drainerTerminated()).isTrue();
    }
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

  @Test
  void testThrowingStreamCloseReportsStreamsNotClosedCleanly() throws Exception {
    Process mockProc = new ProcessBuilder("sleep", "60").start();
    OutputStream throwingOut = new OutputStream() {
      @Override
      public void write(int b) throws IOException {}

      @Override
      public void close() throws IOException {
        throw new IOException("simulated disk/pipe write error during flush on close");
      }
    };

    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p").build();
    OperationReviewChildJvm.ContenderHandle handle = null;
    try {
      handle = OperationReviewChildJvm.startContender(
          req, Duration.ofSeconds(5), mockProc,
          throwingOut, new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0])
      );
      handle.close();
      // Throwing stream close must report streams not closed cleanly rather than falsely claiming clean close
      assertThat(handle.streamsClosed()).isFalse();
      assertThat(handle.cleanlyReaped()).isTrue();
      assertThat(handle.executorTerminated()).isTrue();
      assertThat(handle.drainerTerminated()).isTrue();
    } finally {
      if (handle != null) {
        handle.close();
      }
      if (mockProc.isAlive()) {
        mockProc.destroyForcibly();
        mockProc.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void testInterruptDuringStreamClosePreservesInterruptFlagAndReportsResidualStatus() throws Exception {
    Process mockProc = new ProcessBuilder("sleep", "60").start();
    CountDownLatch closeEntered = new CountDownLatch(1);
    CountDownLatch releaseClose = new CountDownLatch(1);

    OutputStream stallingOut = new OutputStream() {
      @Override
      public void write(int b) throws IOException {}

      @Override
      public void close() throws IOException {
        closeEntered.countDown();
        try {
          releaseClose.await();
        } catch (InterruptedException ie) {
          try {
            releaseClose.await();
          } catch (InterruptedException ignored) {}
        }
      }
    };

    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p").build();
    OperationReviewChildJvm.ContenderHandle handle = null;
    boolean interrupted = false;
    try {
      handle = OperationReviewChildJvm.startContender(
          req, Duration.ofSeconds(5), mockProc,
          stallingOut, new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0])
      );

      // Pre-interrupt caller thread
      Thread.currentThread().interrupt();
      try {
        handle.close();
      } finally {
        interrupted = Thread.interrupted();
      }

      // Interrupt flag must be preserved across cleanup
      assertThat(interrupted).isTrue();
      assertThat(handle.streamsClosed()).isFalse();
      assertThat(handle.cleanlyReaped()).isTrue();
    } finally {
      releaseClose.countDown();
      if (handle != null && handle.stdinCloseFuture() != null) {
        handle.stdinCloseFuture().get(2, TimeUnit.SECONDS);
      }
      if (mockProc.isAlive()) {
        mockProc.destroyForcibly();
        mockProc.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void testSequentialStreamCloseStallsShareSingleCleanupBudget() throws Exception {
    Process mockProc = new ProcessBuilder("sleep", "60").start();
    CountDownLatch inCloseEntered = new CountDownLatch(1);
    CountDownLatch outCloseEntered = new CountDownLatch(1);
    CountDownLatch releaseAll = new CountDownLatch(1);

    OutputStream stallingOut = new OutputStream() {
      @Override
      public void write(int b) throws IOException {}

      @Override
      public void close() throws IOException {
        outCloseEntered.countDown();
        try {
          releaseAll.await();
        } catch (InterruptedException ie) {
          try {
            releaseAll.await();
          } catch (InterruptedException ignored) {}
        }
      }
    };

    InputStream stallingIn = new InputStream() {
      @Override
      public int read() { return -1; }

      @Override
      public void close() throws IOException {
        inCloseEntered.countDown();
        try {
          releaseAll.await();
        } catch (InterruptedException ie) {
          try {
            releaseAll.await();
          } catch (InterruptedException ignored) {}
        }
      }
    };

    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p").build();
    OperationReviewChildJvm.ContenderHandle handle = null;
    try {
      handle = OperationReviewChildJvm.startContender(
          req, Duration.ofSeconds(5), mockProc,
          stallingOut, stallingIn, new ByteArrayInputStream(new byte[0])
      );

      long start = System.nanoTime();
      handle.close();
      long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

      // Total close elapsed time must remain bounded by single cleanup envelope (not 2x budget)
      assertThat(outCloseEntered.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(inCloseEntered.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(elapsedMs)
          .isLessThan(OperationReviewChildJvm.ContenderHandle.CLEANUP_ENVELOPE_MS + 1000);
      assertThat(handle.streamsClosed()).isFalse();
      assertThat(handle.cleanlyReaped()).isTrue();
      assertThat(handle.executorTerminated()).isTrue();
    } finally {
      releaseAll.countDown();
      if (handle != null) {
        if (handle.stdinCloseFuture() != null) {
          handle.stdinCloseFuture().get(2, TimeUnit.SECONDS);
        }
        if (handle.stdoutCloseFuture() != null) {
          handle.stdoutCloseFuture().get(2, TimeUnit.SECONDS);
        }
      }
      if (mockProc.isAlive()) {
        mockProc.destroyForcibly();
        mockProc.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void testNormalStreamCloseReportsStreamsClosedCleanly() throws Exception {
    Process mockProc = new ProcessBuilder("sleep", "60").start();
    var req = OperationReviewChildJvm.ChildRequest.builder("TEST", "dummy", "u", "p").build();
    OperationReviewChildJvm.ContenderHandle handle = null;
    try {
      handle = OperationReviewChildJvm.startContender(
          req, Duration.ofSeconds(5), mockProc,
          mockProc.getOutputStream(), mockProc.getInputStream(), mockProc.getErrorStream()
      );
      handle.close();
      assertThat(handle.streamsClosed()).isTrue();
      assertThat(handle.cleanlyReaped()).isTrue();
      assertThat(handle.executorTerminated()).isTrue();
      assertThat(handle.drainerTerminated()).isTrue();
    } finally {
      if (handle != null) {
        handle.close();
      }
      if (mockProc.isAlive()) {
        mockProc.destroyForcibly();
        mockProc.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }
}
