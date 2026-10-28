package io.tracepermit.agentcore.httpcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class SessionSupervisorReliabilityTest {
  @Test
  void interruptionAfterReadyAndDuringResistantWorkerCleanupRemainsBoundUnknown() throws Exception {
    var supervisor = new SessionSupervisor();
    var target = new RecordingTarget(false);
    var exit = new AtomicInteger(-1);
    Thread runner = runner(supervisor, child(ResistantWorker.class.getName()), target, exit);
    try {
      assertThat(target.ready.await(5, TimeUnit.SECONDS)).isTrue();
      awaitMethod(runner, "awaitReceipt");
      runner.interrupt();
      awaitMethod(runner, "awaitExitPreserving");
      runner.interrupt();
      runner.join(4_000);
      assertThat(runner.isAlive()).isFalse();
      assertThat(runner.isInterrupted()).isTrue();
      assertThat(exit.get()).isEqualTo(1);
      assertUnknown(target);
      assertThat(supervisor.ownedProcess().isAlive()).isFalse();
    } finally { supervisor.stop(); runner.interrupt(); runner.join(4_000); }
  }

  @Test
  void explicitStopAfterReadyIsIdempotentAndNeverSynthesizesSuccess() throws Exception {
    var supervisor = new SessionSupervisor();
    var target = new RecordingTarget(false);
    var exit = new AtomicInteger(-1);
    Thread runner = runner(supervisor, hostile("ready-hang"), target, exit);
    try {
      assertThat(target.ready.await(5, TimeUnit.SECONDS)).isTrue();
      awaitMethod(runner, "awaitReceipt");
      supervisor.stop();
      supervisor.stop();
      runner.join(4_000);
      assertThat(runner.isAlive()).isFalse();
      assertThat(exit.get()).isEqualTo(1);
      assertUnknown(target);
      assertThat(supervisor.ownedProcess().isAlive()).isFalse();
    } finally { supervisor.stop(); runner.interrupt(); runner.join(4_000); }
  }

  @Test
  void outputLossAfterReadyCannotBecomeAnAcknowledgedSuccessfulSession() throws Exception {
    var supervisor = new SessionSupervisor();
    var target = new RecordingTarget(true);
    int exit = supervisor.run(hostile("valid-serve"), "serve-bounded", target,
        Duration.ofSeconds(3), Duration.ofMillis(500));
    assertThat(exit).isEqualTo(1);
    assertThat(target.receipts()).extracting(SessionReceipt::outcome).containsExactly("LISTENING");
    assertThat(target.rejectedWrites.get()).isPositive();
    assertThat(supervisor.ownedProcess().isAlive()).isFalse();
  }

  private static Thread runner(SessionSupervisor supervisor, ProcessBuilder child,
      RecordingTarget target, AtomicInteger exit) {
    var runner = new Thread(() -> exit.set(supervisor.run(child, "serve-bounded", target,
        Duration.ofSeconds(20), Duration.ofSeconds(2))), "supervisor-reliability-runner");
    runner.start(); return runner;
  }

  private static void awaitMethod(Thread runner, String method) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (runner.isAlive() && System.nanoTime() < deadline) {
      for (var frame : runner.getStackTrace()) {
        if (frame.getClassName().equals(SessionSupervisor.class.getName())
            && frame.getMethodName().equals(method)) return;
      }
      Thread.sleep(5);
    }
    throw new AssertionError("SUPERVISOR_PHASE_NOT_OBSERVED:" + method);
  }

  private static void assertUnknown(RecordingTarget target) {
    var receipts = target.receipts();
    assertThat(receipts).extracting(SessionReceipt::phase).containsExactly("READY", "FINAL");
    assertThat(receipts).extracting(SessionReceipt::outcome).containsExactly("LISTENING", "UNKNOWN");
    assertThat(receipts.get(1).port()).isEqualTo(receipts.get(0).port());
  }

  private static ProcessBuilder child(String main) {
    var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2",
        "-cp", System.getProperty("tracepermit.httpcli.testClasspath"), main);
    builder.environment().clear(); return builder;
  }

  private static ProcessBuilder hostile(String mode) {
    var builder = child(HostileChild.class.getName());
    builder.command().add(mode); return builder;
  }

  private static final class RecordingTarget extends OutputStream {
    final CountDownLatch ready = new CountDownLatch(1);
    final AtomicInteger rejectedWrites = new AtomicInteger();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final boolean rejectAfterReady;
    RecordingTarget(boolean rejectAfterReady) { this.rejectAfterReady = rejectAfterReady; }
    @Override public synchronized void write(int value) throws IOException {
      if (rejectAfterReady && ready.getCount() == 0) {
        rejectedWrites.incrementAndGet(); throw new IOException("SYNTHETIC_OUTPUT_LOST");
      }
      bytes.write(value);
    }
    @Override public synchronized void flush() {
      var input = new ByteArrayInputStream(bytes.toByteArray());
      var first = SessionProtocol.readFrame(input);
      if (first.isPresent() && first.orElseThrow().phase().equals("READY")) ready.countDown();
    }
    synchronized List<SessionReceipt> receipts() {
      var input = new ByteArrayInputStream(bytes.toByteArray());
      var result = new ArrayList<SessionReceipt>();
      while (true) {
        var receipt = SessionProtocol.readFrame(input);
        if (receipt.isEmpty()) return result;
        result.add(receipt.orElseThrow());
      }
    }
  }

  public static final class ResistantWorker {
    private ResistantWorker() {}
    public static void main(String[] args) throws Exception {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try { Thread.sleep(30_000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      }));
      byte[] body = SessionProtocol.bytes(SessionReceipt.ready(12_345));
      var output = new DataOutputStream(System.out);
      output.writeInt(body.length); output.write(body); output.flush();
      Thread.sleep(30_000);
    }
  }
}
