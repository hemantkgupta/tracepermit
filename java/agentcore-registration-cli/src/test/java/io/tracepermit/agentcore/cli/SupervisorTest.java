package io.tracepermit.agentcore.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
final class SupervisorTest {
  static ProcessBuilder child(String mode) {
    return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx32m", "-cp", System.getProperty("tracepermit.cli.testClasspath"), HostileChild.class.getName(), mode);
  }

  @Test void validReceiptRequiresNormalExitAndReadCompletion() {
    assertThat(new Supervisor().run(child("valid"), Duration.ofSeconds(5), Duration.ofSeconds(2)).outcome()).isEqualTo("CONFIG_VALID");
    assertThat(new Supervisor().run(new ProcessBuilder("/does-not-exist"), Duration.ofSeconds(1), Duration.ofSeconds(1)).outcome()).isEqualTo("UNKNOWN");
  }

  @ParameterizedTest @ValueSource(strings = {"exit", "stderr", "stdout", "read", "stall", "late-exit", "canary"})
  void realStalledAndHostileChildHasFiniteCleanup(String mode) throws Exception {
    var before = ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
    long start = System.nanoTime();
    Receipt receipt = new Supervisor().run(child(mode), Duration.ofSeconds(2), Duration.ofSeconds(2));
    assertThat(receipt.outcome()).isEqualTo("UNKNOWN");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    assertThat(ProcessHandle.current().descendants().filter(p -> !before.contains(p.pid()) && p.isAlive()).count()).isZero();
    // Readers and pipe closers must actually disappear, not only be daemonized.
    long end = System.nanoTime() + Duration.ofSeconds(1).toNanos();
    while (supervisorThreads() != 0 && System.nanoTime() < end) Thread.sleep(5);
    assertThat(supervisorThreads()).isZero();
  }

  @Test void interruptionKillsOwnedChildAndPreservesCallerInterrupt() throws Exception {
    var supervisor = new Supervisor();
    var outcome = new java.util.concurrent.atomic.AtomicReference<Receipt>();
    var preserved = new java.util.concurrent.atomic.AtomicBoolean();
    var childObserved = new java.util.concurrent.atomic.AtomicReference<Process>();
    Thread caller = new Thread(() -> {
      outcome.set(supervisor.run(child("stdout"), Duration.ofSeconds(10), Duration.ofSeconds(2)));
      preserved.set(Thread.currentThread().isInterrupted());
    }, "supervisor-caller-test");
    try {
      caller.start();
      // Wait until child is created, retained by supervisor, alive, and actively streaming I/O
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (System.nanoTime() < deadline) {
        Process p = supervisor.ownedProcess();
        if (p != null && p.isAlive()) {
          childObserved.set(p);
          break;
        }
        Thread.sleep(10);
      }
      Process childProcess = childObserved.get();
      assertThat(childProcess).as("child process retained before interrupt").isNotNull();
      assertThat(childProcess.isAlive()).as("child process alive with active I/O").isTrue();

      // Interrupt caller thread while child is alive and I/O is active
      caller.interrupt();
      caller.join(5000);

      assertThat(caller.isAlive()).as("caller thread terminated").isFalse();
      assertThat(outcome.get().outcome()).as("honest UNKNOWN reported on interrupted cleanup").isEqualTo("UNKNOWN");
      assertThat(preserved.get()).as("caller thread interrupt status preserved").isTrue();
      assertThat(childProcess.isAlive()).as("reaped owned child worker is not alive").isFalse();

      // Owned reader/writer/drainer threads must all be terminated
      long drainDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (supervisorThreads() != 0 && System.nanoTime() < drainDeadline) {
        Thread.sleep(10);
      }
      assertThat(supervisorThreads()).as("owned supervisor reader/drainer threads terminated").isZero();
    } finally {
      Process p = childObserved.get();
      if (p != null && p.isAlive()) {
        p.destroyForcibly();
        p.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test void interruptionDuringCleanupAwaitsForcedExitAndPreservesInterrupt() throws Exception {
    var supervisor = new Supervisor();
    var outcome = new java.util.concurrent.atomic.AtomicReference<Receipt>();
    var preserved = new java.util.concurrent.atomic.AtomicBoolean();
    Thread caller = new Thread(() -> {
      Thread.currentThread().interrupt(); // Pre-set interrupt entering supervisor
      outcome.set(supervisor.run(child("stall"), Duration.ofSeconds(5), Duration.ofSeconds(2)));
      preserved.set(Thread.currentThread().isInterrupted());
    }, "supervisor-caller-pre-interrupted");
    try {
      caller.start();
      caller.join(5000);
      assertThat(caller.isAlive()).isFalse();
      assertThat(outcome.get().outcome()).isEqualTo("UNKNOWN");
      assertThat(preserved.get()).isTrue();
      Process p = supervisor.ownedProcess();
      if (p != null) {
        assertThat(p.isAlive()).as("child reaped even when caller was pre-interrupted").isFalse();
      }
      long drainDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (supervisorThreads() != 0 && System.nanoTime() < drainDeadline) {
        Thread.sleep(10);
      }
      assertThat(supervisorThreads()).isZero();
    } finally {
      Process p = supervisor.ownedProcess();
      if (p != null && p.isAlive()) {
        p.destroyForcibly();
        p.waitFor(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test void nonReadingOutputAndThrowingOutputAreBounded() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    OutputStream blocked = new OutputStream() {
      @Override public void write(int b) throws java.io.IOException {
        entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("FIXED"); }
      }
    };
    try {
      assertThat(Supervisor.send(blocked, new byte[]{1}, Duration.ofMillis(100))).isFalse();
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
    } finally { release.countDown(); }
    assertThat(Supervisor.send(new OutputStream() { @Override public void write(int b) throws java.io.IOException { throw new java.io.IOException("CANARY"); } }, new byte[]{1}, Duration.ofSeconds(1))).isFalse();
    var swallowed = new java.io.PrintStream(new OutputStream() {
      @Override public void write(int b) throws java.io.IOException { throw new java.io.IOException("CANARY"); }
    });
    assertThat(Supervisor.send(swallowed, new byte[]{1}, Duration.ofSeconds(1))).isFalse();
  }

  @Test
  void sendPreservesInterruptFlagWhenInterrupted() {
    Thread.currentThread().interrupt();
    try {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      assertThat(Supervisor.send(out, new byte[]{1}, Duration.ofSeconds(1))).isFalse();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static long supervisorThreads() {
    return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().startsWith("registration-")).count();
  }
}
