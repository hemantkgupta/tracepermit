package io.tracepermit.auth0reviewer;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.io.DataOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReviewerProcessTest {
  @TempDir Path temp;
  private ProcessBuilder child(String mode) {
    // The owned child executes the real entry point, including System.exit. Reuse only
    // Gradle's existing local-file coverage agent; never forward ambient JVM options.
    var agents = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
        .stream().filter(arg -> arg.startsWith("-javaagent:") && arg.contains("jacoco")).toList();
    assertThat(agents).hasSize(1);
    String agent = agents.get(0);
    assertThat(agent).contains("destfile=").doesNotContain("append=false", "output=tcp", "output=none", "jmx=true");
    return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        agent, "-Xmx64m", "-cp", System.getProperty("tracepermit.reviewer.testClasspath"),
        Auth0ReviewerMain.class.getName(), mode).redirectErrorStream(true);
  }
  @Test void actualAnonymousFrameEofSupportsOfflineCheckAndNoIdentityLeak() throws Exception {
    Process p = child("check-config").start();
    try {
      byte[] bytes = ReviewerFixtures.bytes(ReviewerFixtures.json());
      try (var out = new DataOutputStream(p.getOutputStream())) { out.writeInt(bytes.length); out.write(bytes); }
      assertThat(p.waitFor(15, TimeUnit.SECONDS)).isTrue();
      assertThat(p.exitValue()).isZero();
      String receipt = new String(p.getInputStream().readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(receipt).contains("CONFIG_VALID", "executionEnabled\":false")
          .doesNotContain("synthetic-human", "publicKeySpki");
    } finally { if (p.isAlive()) { p.destroyForcibly(); assertThat(p.waitFor(5, TimeUnit.SECONDS)).isTrue(); } }
  }
  @Test void regularFileAndInvalidModeNeverOpenBrowserOrRequestToken() throws Exception {
    Path file = temp.resolve("config"); Files.write(file, ReviewerFixtures.bytes(ReviewerFixtures.json()));
    for (String mode : new String[]{"login", "unknown"}) {
      Process p = child(mode).redirectInput(file.toFile()).start();
      try {
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isEqualTo(mode.equals("unknown") ? 64 : 1);
      } finally { if (p.isAlive()) { p.destroyForcibly(); assertThat(p.waitFor(5, TimeUnit.SECONDS)).isTrue(); } }
    }
  }
  @Test void invalidLengthAndTruncatedFrameFailBeforeAnyProviderAction() throws Exception {
    for (int length : new int[]{-1, 0, 16385, 10}) {
      Process p = child("check-config").start();
      try {
        try (var out = new DataOutputStream(p.getOutputStream())) {
          out.writeInt(length);
          if (length == 10) out.write(new byte[]{'{', '}'});
        }
        assertThat(p.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isEqualTo(1);
        String receipt = new String(p.getInputStream().readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(receipt).contains("REVIEWER_REJECTED").doesNotContain("CONFIG_VALID", "REVIEWER_VERIFIED");
      } finally {
        if (p.isAlive()) { p.destroyForcibly(); assertThat(p.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
      }
    }
  }

  @Test void extraFrameOrMissingEofCannotProceed() throws Exception {
    for (boolean extra : new boolean[]{false, true}) {
      Process p = child("check-config").start();
      try {
        byte[] bytes = ReviewerFixtures.bytes(ReviewerFixtures.json());
        DataOutputStream out = new DataOutputStream(p.getOutputStream());
        out.writeInt(bytes.length); out.write(bytes);
        if (extra) { out.writeByte(1); out.close(); } else out.flush();
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isEqualTo(1);
      } finally { p.getOutputStream().close(); if (p.isAlive()) { p.destroyForcibly(); assertThat(p.waitFor(5, TimeUnit.SECONDS)).isTrue(); } }
    }
  }
}
