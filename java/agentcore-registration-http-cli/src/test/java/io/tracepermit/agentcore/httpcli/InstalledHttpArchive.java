package io.tracepermit.agentcore.httpcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Extracted source-free distribution with bounded lifecycle-frame handling. */
final class InstalledHttpArchive implements java.io.Closeable {
  final Path root;
  final Path home;
  final Path launcher;

  InstalledHttpArchive() throws Exception {
    root = Files.createTempDirectory("tp-http-cli-installed-");
    var tar =
        new ProcessBuilder(
                "/usr/bin/tar",
                "-xf",
                System.getProperty("tracepermit.httpcli.archive"),
                "-C",
                root.toString())
            .start();
    assertThat(tar.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThat(tar.exitValue()).isZero();
    try (var dirs = Files.list(root)) {
      home = dirs.findFirst().orElseThrow();
    }
    launcher = home.resolve("bin/tracepermit-synthetic-registration-http");
    assertThat(Files.isExecutable(launcher)).isTrue();
  }

  Process start(String command) throws Exception {
    var builder = new ProcessBuilder(launcher.toString(), command).directory(root.toFile());
    builder.environment().clear();
    builder.environment().put("PATH", "/usr/bin:/bin");
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    return builder.start();
  }

  RunningSession serve(LocalDatabases database, long millis) throws Exception {
    return serve(HttpCliFixtures.config(database), millis);
  }

  RunningSession serve(com.fasterxml.jackson.databind.node.ObjectNode config, long millis)
      throws Exception {
    Process process = start("serve-bounded");
    return awaitReady(process, HttpCliFixtures.frames(
        HttpCliFixtures.bytes(config), HttpCliFixtures.bytes(HttpCliFixtures.session(millis))));
  }

  static RunningSession awaitReady(Process process, byte[] input) throws Exception {
    var errors = new ByteArrayOutputStream();
    var failed = new AtomicBoolean();
    Thread stderr = thread(() -> capture(process.getErrorStream(), errors, failed));
    try {
      process.getOutputStream().write(input);
      process.getOutputStream().flush();
      process.getOutputStream().close();
      SessionReceipt ready = read(process);
      assertThat(ready.phase()).as("INSTALLED_START phase=%s outcome=%s", ready.phase(), ready.outcome())
          .isEqualTo("READY");
      assertThat(ready.outcome()).isEqualTo("LISTENING");
      return new RunningSession(process, ready, errors, failed, stderr);
    } catch (Exception | Error failure) {
      try { stop(process, stderr); }
      catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  SessionReceipt check(byte[] input) throws Exception {
    Process process = start("check-session");
    try {
      process.getOutputStream().write(input);
      process.getOutputStream().close();
      SessionReceipt result = read(process);
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(process.getErrorStream().readAllBytes()).isEmpty();
      assertThat(process.getInputStream().read()).isEqualTo(-1);
      assertThat(process.exitValue()).isEqualTo(result.exitCode());
      return result;
    } finally {
      process.toHandle().destroyForcibly();
      process.waitFor(2, TimeUnit.SECONDS);
    }
  }

  static SessionReceipt read(Process process) throws Exception {
    return SessionProtocol.readFrame(new DataInputStream(process.getInputStream())).orElseThrow();
  }

  static Thread thread(Runnable action) {
    var thread = new Thread(action);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  static void finishAll(List<RunningSession> sessions) throws Exception {
    Throwable first = null;
    for (var session : sessions) {
      try {
        SessionReceipt result = session.finish();
        assertThat(result.outcome()).isEqualTo("SESSION_ENDED");
        assertThat(result.port()).isEqualTo(session.ready.port());
      } catch (Exception | Error failure) {
        if (first == null) first = failure;
        else first.addSuppressed(failure);
      }
    }
    if (first instanceof Exception failure) throw failure;
    if (first instanceof Error failure) throw failure;
  }

  private static void stop(Process process, Thread stderr) {
    var interrupted = new AtomicBoolean(Thread.interrupted());
    long forceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
    var children = process.isAlive() ? process.toHandle().descendants().toList() : List.<ProcessHandle>of();
    try {
      process.destroy();
      awaitExit(process, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), interrupted);
      // A prompt parent exit does not prove its already-observed children exited.
      for (var child : children) if (child.isAlive()) child.destroyForcibly();
      if (process.isAlive()) process.destroyForcibly();
      assertThat(awaitExit(process, forceDeadline, interrupted)).as("OWNED_PARENT_FORCE_EXIT").isTrue();
      for (var child : children) {
        while (child.isAlive()) {
          long remaining = forceDeadline - System.nanoTime();
          if (remaining <= 0) throw new AssertionError("OWNED_WORKER_FORCE_EXIT_TIMEOUT");
          try { child.onExit().get(remaining, TimeUnit.NANOSECONDS); }
          catch (InterruptedException stop) { interrupted.set(true); }
          catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException stop) {
            throw new AssertionError("OWNED_WORKER_FORCE_EXIT_UNOBSERVED");
          }
        }
      }
    } finally {
      for (var stream : List.of(process.getInputStream(), process.getErrorStream(), process.getOutputStream())) {
        try { stream.close(); } catch (Exception ignored) { /* attempt every owned stream */ }
      }
      long readerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (stderr.isAlive() && System.nanoTime() < readerDeadline) {
        try { TimeUnit.NANOSECONDS.timedJoin(stderr, readerDeadline - System.nanoTime()); }
        catch (InterruptedException stop) { interrupted.set(true); }
      }
      if (interrupted.get()) Thread.currentThread().interrupt();
    }
    assertThat(process.isAlive()).as("OWNED_PARENT_REAPED").isFalse();
    assertThat(stderr.isAlive()).as("OWNED_STDERR_READER_REAPED").isFalse();
  }

  private static boolean awaitExit(Process process, long deadline, AtomicBoolean interrupted) {
    while (process.isAlive()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return false;
      try { process.waitFor(remaining, TimeUnit.NANOSECONDS); }
      catch (InterruptedException stop) { interrupted.set(true); }
    }
    return true;
  }

  private static void capture(
      java.io.InputStream input,
      ByteArrayOutputStream output,
      AtomicBoolean failed) {
    try {
      byte[] buffer = new byte[256];
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (output.size() + read > 1_024) {
          failed.set(true);
          return;
        }
        output.write(buffer, 0, read);
      }
    } catch (Exception failure) {
      failed.set(true);
    }
  }

  @Override
  public void close() throws java.io.IOException {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  static final class RunningSession {
    final Process process;
    final SessionReceipt ready;
    private final ByteArrayOutputStream errors;
    private final AtomicBoolean failed;
    private final Thread stderr;

    RunningSession(
        Process process,
        SessionReceipt ready,
        ByteArrayOutputStream errors,
        AtomicBoolean failed,
        Thread stderr) {
      this.process = process;
      this.ready = ready;
      this.errors = errors;
      this.failed = failed;
      this.stderr = stderr;
    }

    SessionReceipt finish() throws Exception {
      try {
        SessionReceipt result = InstalledHttpArchive.read(process);
        assertThat(process.waitFor(65, TimeUnit.SECONDS)).as("INSTALLED_FINAL_EXIT phase=%s outcome=%s",
            result.phase(), result.outcome()).isTrue();
        stderr.join(1_000);
        assertThat(stderr.isAlive()).isFalse();
        assertThat(failed.get()).isFalse();
        assertThat(errors.size()).isZero();
        assertThat(process.getInputStream().read()).isEqualTo(-1);
        assertThat(process.exitValue()).isEqualTo(result.exitCode());
        return result;
      } finally {
        close();
      }
    }

    void close() {
      stop(process, stderr);
    }
  }
}
