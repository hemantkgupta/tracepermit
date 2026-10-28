package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
final class BootstrapTestProcessesTest {
  @TempDir Path directory;

  @Test
  void interruptedCleanupReapsItsOwnChildAndRestoresInterruption() throws Exception {
    Process child = new ProcessBuilder("/bin/sleep", "30").start();
    try {
      Thread.currentThread().interrupt();
      BootstrapTestProcesses.stop(child);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(child.isAlive()).isFalse();
    } finally {
      Thread.interrupted();
      BootstrapTestProcesses.stop(child);
    }
  }

  @Test
  void deadlineDiagnosticContainsOnlyKnownMarkersAndNoOutputOrPath() throws Exception {
    Path output = directory.resolve("synthetic-sensitive-output.txt");
    String secret = "SYNTHETIC_SECRET_SHOULD_NEVER_APPEAR";
    Files.writeString(output, secret + " jdbc:synthetic://private APPROVAL_STARTUP_FAILED");
    Process child = new ProcessBuilder("/bin/sleep", "30").start();
    try {
      assertThat(BootstrapTestProcesses.state(child, "CHECK", output))
          .contains("phase=CHECK", "alive=true", "marker=APPROVAL_STARTUP_FAILED")
          .containsPattern("cpu=(UNDER_1S|AT_LEAST_1S|UNAVAILABLE)")
          .doesNotContain(secret, output.toString(), "jdbc:");
    } finally { BootstrapTestProcesses.stop(child); }
  }
}
