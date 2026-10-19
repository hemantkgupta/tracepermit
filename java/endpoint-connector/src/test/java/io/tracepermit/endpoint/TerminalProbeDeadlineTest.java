package io.tracepermit.endpoint;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TerminalProbeDeadlineTest {
  @Test
  void retainsBoundedTtyClassificationAndExitChecks() {
    assertThat(MacOsDevelopmentProcessObserver.terminalResult(
        new Probe("ttys001\n", false, 0), Duration.ofSeconds(1))).isTrue();
    assertThat(MacOsDevelopmentProcessObserver.terminalResult(
        new Probe("??\n", false, 0), Duration.ofSeconds(1))).isFalse();
    for (Probe invalid : new Probe[] {
        new Probe("x".repeat(257), false, 0), new Probe("ttys001\n", false, 1)}) {
      assertUnavailable(() -> MacOsDevelopmentProcessObserver.terminalResult(
          invalid, Duration.ofSeconds(1)));
    }
  }

  @Test
  void blockedReadAndPendingExitBothHaveFiniteBudgetsAndCleanup() throws Exception {
    for (Probe probe : new Probe[] {new Probe("", true, 0), new Probe("ttys001\n", true, 0)}) {
      long started = System.nanoTime();
      try {
        assertUnavailable(() -> MacOsDevelopmentProcessObserver.terminalResult(
            probe, Duration.ofMillis(100)));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
        assertThat(probe.destroyed.get()).isTrue();
        assertThat(probe.readClosed.await(2, TimeUnit.SECONDS)).isTrue();
      } finally {
        probe.destroyForcibly();
      }
    }
  }

  @Test
  void readFailureIsUnavailableAndInterruptionRetainsItsFlag() throws Exception {
    Probe failed = new Probe("", false, 0) {
      @Override
      public InputStream getInputStream() {
        return new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("synthetic-private-probe-error");
          }
        };
      }
    };
    assertUnavailable(() -> MacOsDevelopmentProcessObserver.terminalResult(failed, Duration.ofSeconds(1)));

    Probe blocked = new Probe("", true, 0);
    FutureTask<Boolean> result = new FutureTask<>(() -> {
      assertUnavailable(() -> MacOsDevelopmentProcessObserver.terminalResult(blocked, Duration.ofSeconds(10)));
      return Thread.currentThread().isInterrupted();
    });
    Thread caller = Thread.ofVirtual().start(result);
    try {
      assertThat(blocked.readStarted.await(2, TimeUnit.SECONDS)).isTrue();
      caller.interrupt();
      assertThat(result.get(2, TimeUnit.SECONDS)).isTrue();
      assertThat(blocked.destroyed.get()).isTrue();
      assertThat(blocked.readClosed.await(2, TimeUnit.SECONDS)).isTrue();
    } finally {
      blocked.destroyForcibly();
      result.cancel(true);
      caller.join(2000);
      assertThat(caller.isAlive()).isFalse();
    }
  }

  private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOf(ConnectorException.class)
        .hasMessage("The macOS terminal observation is unavailable.")
        .extracting(failure -> ((ConnectorException) failure).code())
        .isEqualTo("ENDPOINT_OBSERVATION_UNAVAILABLE");
  }

  private static class Probe extends Process {
    final CountDownLatch readStarted = new CountDownLatch(1);
    final CountDownLatch readClosed = new CountDownLatch(1);
    final AtomicBoolean destroyed = new AtomicBoolean();
    private final CountDownLatch exited;
    private final byte[] output;
    private final int exitCode;

    Probe(String output, boolean alive, int exitCode) {
      this.output = output.getBytes(StandardCharsets.UTF_8);
      this.exited = new CountDownLatch(alive ? 1 : 0);
      this.exitCode = exitCode;
    }

    @Override
    public InputStream getInputStream() {
      return new InputStream() {
        private final ByteArrayInputStream contents = new ByteArrayInputStream(output);

        @Override
        public int read() {
          byte[] value = new byte[1];
          return read(value, 0, 1) == -1 ? -1 : value[0] & 0xff;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
          readStarted.countDown();
          if (output.length == 0) {
            try {
              if (!exited.await(5, TimeUnit.SECONDS)) throw new AssertionError("Synthetic read not released");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              return -1;
            }
          }
          return contents.read(bytes, offset, length);
        }

        @Override
        public void close() {
          readClosed.countDown();
        }
      };
    }

    @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public int waitFor() throws InterruptedException { exited.await(); return exitCode; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      return exited.await(timeout, unit);
    }
    @Override public int exitValue() {
      if (isAlive()) throw new IllegalThreadStateException("Synthetic probe is still running");
      return exitCode;
    }
    @Override public boolean isAlive() { return exited.getCount() != 0; }
    @Override public void destroy() { destroyed.set(true); exited.countDown(); }
    @Override public Process destroyForcibly() { destroy(); return this; }
  }
}
