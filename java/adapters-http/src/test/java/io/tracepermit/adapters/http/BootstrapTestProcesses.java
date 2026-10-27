package io.tracepermit.adapters.http;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Bounded owned-child cleanup and fixed-vocabulary diagnostics; never print child output/configuration. */
final class BootstrapTestProcesses {
  private BootstrapTestProcesses() {}

  static String state(Process child, String phase, Path output) {
    String marker = "UNOBSERVED";
    try (var input = Files.newInputStream(output)) {
      String text = new String(input.readNBytes(4_096), StandardCharsets.UTF_8);
      for (String known : new String[] {"APPROVAL_CONFIGURATION_VALID", "APPROVAL_DATABASE_INITIALIZED",
          "APPROVAL_STARTUP_FAILED", "APPROVAL_RECOVERY_SNAPSHOT", "API listening"}) {
        if (text.contains(known)) { marker = known; break; }
      }
    } catch (Exception unavailable) { /* absence is an observation, never raw exception text */ }
    return "BOOTSTRAP_CHILD_EXIT[phase=" + phase + ",alive=" + child.isAlive() + ",marker=" + marker + "]";
  }

  static void stop(Process child) {
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try {
      if (child.isAlive()) child.destroyForcibly();
      while (child.isAlive()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new AssertionError("BOOTSTRAP_OWNED_CHILD_CLEANUP_TIMEOUT");
        try { child.waitFor(remaining, TimeUnit.NANOSECONDS); }
        catch (InterruptedException stop) { interrupted = true; }
      }
    } finally {
      for (var stream : new java.io.Closeable[] {child.getOutputStream(), child.getInputStream(), child.getErrorStream()}) {
        try { stream.close(); } catch (Exception ignored) { /* attempt all owned streams */ }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
