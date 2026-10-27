package io.tracepermit.agentcore.httpcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
    var errors = new ByteArrayOutputStream();
    var failed = new AtomicBoolean();
    Thread stderr = thread(() -> capture(process.getErrorStream(), errors, failed));
    process
        .getOutputStream()
        .write(
            HttpCliFixtures.frames(
                HttpCliFixtures.bytes(config),
                HttpCliFixtures.bytes(HttpCliFixtures.session(millis))));
    process.getOutputStream().flush();
    process.getOutputStream().close();
    SessionReceipt ready = read(process);
    assertThat(ready.phase()).isEqualTo("READY");
    assertThat(ready.outcome()).isEqualTo("LISTENING");
    return new RunningSession(process, ready, errors, failed, stderr);
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
      SessionReceipt result = InstalledHttpArchive.read(process);
      assertThat(process.waitFor(65, TimeUnit.SECONDS)).isTrue();
      stderr.join(1_000);
      assertThat(stderr.isAlive()).isFalse();
      assertThat(failed.get()).isFalse();
      assertThat(errors.size()).isZero();
      assertThat(process.getInputStream().read()).isEqualTo(-1);
      assertThat(process.exitValue()).isEqualTo(result.exitCode());
      return result;
    }

    void close() {
      boolean interrupted = false;
      process.toHandle().destroy();
      try {
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.toHandle().destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
        }
      } catch (InterruptedException stop) {
        interrupted = true;
        process.toHandle().destroyForcibly();
      }
      try {
        process.getInputStream().close();
        process.getErrorStream().close();
        process.getOutputStream().close();
      } catch (Exception ignored) {
      }
      try {
        stderr.join(1_000);
      } catch (InterruptedException stop) {
        interrupted = true;
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
