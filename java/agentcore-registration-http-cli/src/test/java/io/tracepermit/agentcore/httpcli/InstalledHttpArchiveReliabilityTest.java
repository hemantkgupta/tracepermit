package io.tracepermit.agentcore.httpcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class InstalledHttpArchiveReliabilityTest {
  @Test
  void finalInsteadOfReadyStillCleansTheStartedProcessAndReader() throws Exception {
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("tracepermit.httpcli.testClasspath"), RefusingChild.class.getName()).start();
    try {
      assertThatThrownBy(() -> InstalledHttpArchive.awaitReady(process, new byte[] {1}))
          .isInstanceOf(AssertionError.class).hasMessageContaining("phase=FINAL outcome=NOT_READY");
      assertThat(process.isAlive()).isFalse();
    } finally {
      process.destroyForcibly();
      assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void failedStartupReapsAnOwnedWorkerEvenWhenItsParentExitsPromptly() throws Exception {
    Process parent = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("tracepermit.httpcli.testClasspath"), RefusingParent.class.getName()).start();
    ProcessHandle worker = null;
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      var children = parent.toHandle().children().toList();
      while (children.isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(10); children = parent.toHandle().children().toList();
      }
      assertThat(children).hasSize(1);
      worker = children.get(0);
      assertThatThrownBy(() -> InstalledHttpArchive.awaitReady(parent, new byte[] {1}))
          .isInstanceOf(AssertionError.class).hasMessageContaining("phase=FINAL outcome=NOT_READY");
      assertThat(parent.isAlive()).isFalse();
      assertThat(worker.isAlive()).as("owned worker after prompt parent exit").isFalse();
    } finally {
      parent.destroyForcibly(); parent.waitFor(5, TimeUnit.SECONDS);
      if (worker != null && worker.isAlive()) {
        worker.destroyForcibly(); worker.onExit().get(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void finishesAndClosesEverySessionEvenWhenEarlierReceiptsFail() {
    var first = new CompletedProcess(SessionReceipt.finalFor("UNKNOWN", 1234));
    var second = new CompletedProcess(SessionReceipt.finalFor("UNKNOWN", 1235));
    var third = new CompletedProcess(SessionReceipt.finalFor("SESSION_ENDED", 1236));
    assertThatThrownBy(() -> InstalledHttpArchive.finishAll(List.of(session(first, 1234),
        session(second, 1235), session(third, 1236))))
        .isInstanceOf(AssertionError.class).satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
    for (var process : List.of(first, second, third)) {
      assertThat(process.input.reads).isPositive();
      assertThat(process.closed.get()).isTrue();
      assertThat(process.waited.get()).isTrue();
    }
  }

  private static InstalledHttpArchive.RunningSession session(CompletedProcess process, int port) {
    return new InstalledHttpArchive.RunningSession(process, SessionReceipt.ready(port),
        new ByteArrayOutputStream(), new AtomicBoolean(), new Thread());
  }

  public static final class RefusingChild {
    private RefusingChild() {}
    public static void main(String[] args) throws Exception {
      System.in.readAllBytes();
      byte[] body = SessionProtocol.bytes(SessionReceipt.failure("NOT_READY"));
      System.out.write(ByteBuffer.allocate(4).putInt(body.length).array());
      System.out.write(body); System.out.flush();
      Thread.sleep(10_000);
    }
  }

  public static final class RefusingParent {
    private RefusingParent() {}
    public static void main(String[] args) throws Exception {
      new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", System.getProperty("java.class.path"), SleepingWorker.class.getName()).start();
      RefusingChild.main(args);
    }
  }

  public static final class SleepingWorker {
    private SleepingWorker() {}
    public static void main(String[] args) throws Exception { Thread.sleep(10_000); }
  }

  private static final class CompletedProcess extends Process {
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicBoolean waited = new AtomicBoolean();
    final CountingInput input;
    final int exit;
    CompletedProcess(SessionReceipt receipt) {
      byte[] body = SessionProtocol.bytes(receipt);
      input = new CountingInput(ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array());
      exit = receipt.exitCode();
    }
    @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    @Override public InputStream getInputStream() { return input; }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public int waitFor() { waited.set(true); return exit; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) { waited.set(true); return true; }
    @Override public int exitValue() { return exit; }
    @Override public void destroy() { closed.set(true); }
    @Override public boolean isAlive() { return false; }
    @Override public ProcessHandle toHandle() { return ProcessHandle.current(); }
  }

  private static final class CountingInput extends ByteArrayInputStream {
    int reads;
    CountingInput(byte[] bytes) { super(bytes); }
    @Override public synchronized int read() { reads++; return super.read(); }
  }
}
