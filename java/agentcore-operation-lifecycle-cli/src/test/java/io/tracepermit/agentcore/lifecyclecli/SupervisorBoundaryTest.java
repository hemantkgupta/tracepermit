package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SupervisorBoundaryTest {
  @Test
  @Timeout(30)
  void finiteSupervisorAcceptsOnlyACompleteMatchingReceipt() {
    LifecycleReceipt valid =
        new LifecycleSupervisor().run(child("valid"), Duration.ofSeconds(5), Duration.ofSeconds(1));
    assertThat(valid.outcome()).isEqualTo("INPUT_INVALID");

    for (String mode : List.of("mismatch", "stderr", "oversize", "invalid")) {
      LifecycleReceipt refused =
          new LifecycleSupervisor()
              .run(child(mode), Duration.ofSeconds(5), Duration.ofSeconds(1));
      assertThat(refused.outcome()).isEqualTo("UNKNOWN");
    }

    LifecycleReceipt startFailure =
        new LifecycleSupervisor()
            .run(
                new ProcessBuilder("/tracepermit/does-not-exist"),
                Duration.ofSeconds(1),
                Duration.ofMillis(100));
    assertThat(startFailure.outcome()).isEqualTo("UNKNOWN");
    LifecycleReceipt timeout =
        new LifecycleSupervisor()
            .run(child("hang"), Duration.ofMillis(100), Duration.ofSeconds(1));
    assertThat(timeout.outcome()).isEqualTo("UNKNOWN");
  }

  @Test
  void boundedReceiptWriterReportsSuccessFailureAndInterruption() {
    var output = new ByteArrayOutputStream();
    assertThat(LifecycleSupervisor.send(output, new byte[] {1, 2}, Duration.ofSeconds(1)))
        .isTrue();
    assertThat(output.toByteArray()).containsExactly(1, 2);

    OutputStream failure =
        new OutputStream() {
          @Override
          public void write(int value) throws java.io.IOException {
            throw new java.io.IOException("synthetic");
          }
        };
    assertThat(LifecycleSupervisor.send(failure, new byte[] {1}, Duration.ofSeconds(1))).isFalse();

    Thread.currentThread().interrupt();
    try {
      assertThat(LifecycleSupervisor.send(output, new byte[] {3}, Duration.ofSeconds(1))).isFalse();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @Timeout(30)
  void workerDeathBeforeInputHasNoEffectAndReturnsFixedUnknown() throws Exception {
    try (var installed = new InstalledLifecycleArchive()) {
      Process process = installed.start("inspect");
      try {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        List<ProcessHandle> workers = List.of();
        var discovery = new LifecycleWorkerProcesses.Discovery(List.of(), 0, 0, 0);
        while (workers.isEmpty() && System.nanoTime() < deadline) {
          discovery = LifecycleWorkerProcesses.find(process, LifecycleWorker.class.getName());
          workers = discovery.matches();
          if (workers.isEmpty()) Thread.sleep(10);
        }
        assertThat(workers).as(discovery.summary()).hasSize(1);
        assertThat(workers.getFirst().destroyForcibly()).isTrue();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        byte[] output = process.getInputStream().readNBytes(LifecycleProtocol.RECEIPT_BYTES);
        LifecycleReceipt receipt = LifecycleReceipt.decode(output, process.exitValue());
        assertThat(receipt.outcome()).isEqualTo("UNKNOWN");
        assertThat(process.getErrorStream().readAllBytes()).isEmpty();
      } finally {
        process.toHandle().destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private static ProcessBuilder child(String mode) {
    return new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        System.getProperty("tracepermit.lifecycle.testClasspath"),
        SupervisorChild.class.getName(),
        mode);
  }
}
