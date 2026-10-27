package io.tracepermit.agentcore.cli;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual application archive outside all checkouts. Only Java and a POSIX shell are on PATH. */
final class InstalledArchive implements AutoCloseable {
  final Path root;
  final Path home;
  final Path launcher;

  InstalledArchive() throws Exception {
    root = Files.createTempDirectory("tp-cli-installed-");
    assertThat(root.toRealPath().startsWith(Path.of(System.getProperty("tracepermit.repositoryRoot")).toRealPath())).isFalse();
    var tar = new ProcessBuilder("/usr/bin/tar", "-xf", System.getProperty("tracepermit.cli.archive"), "-C", root.toString()).start();
    assertThat(tar.waitFor(10, TimeUnit.SECONDS)).isTrue(); assertThat(tar.exitValue()).isZero();
    try (var dirs = Files.list(root)) { home = dirs.findFirst().orElseThrow(); }
    launcher = home.resolve("bin/tracepermit-synthetic-registration");
    assertThat(Files.isExecutable(launcher)).isTrue();
  }

  Process start(String command) throws Exception {
    var builder = new ProcessBuilder(launcher.toString(), command).directory(root.toFile());
    builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin");
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    return builder.start();
  }

  static final class RunningCommand {
    final Process process;
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    final AtomicBoolean failed = new AtomicBoolean();
    final AtomicReference<Throwable> ioError = new AtomicReference<>();
    final Thread reader;
    final Thread stderr;

    RunningCommand(Process process) {
      this.process = process;
      this.reader = thread(() -> capture(process.getInputStream(), output, failed));
      this.stderr = thread(() -> capture(process.getErrorStream(), errors, failed));
    }

    void writeInput(byte[] input) {
      try {
        process.getOutputStream().write(input);
        process.getOutputStream().flush();
        process.getOutputStream().close();
      } catch (Throwable failure) {
        ioError.set(failure);
      }
    }

    Receipt finish() throws Exception {
      try {
        assertThat(process.waitFor(70, TimeUnit.SECONDS)).as("installed command deadline").isTrue();
        reader.join(1000); stderr.join(1000);
        assertThat(reader.isAlive() || stderr.isAlive()).as("pipe threads joined").isFalse();
        assertThat(failed.get()).isFalse(); assertThat(ioError.get() == null).isTrue();
        assertThat(errors.size()).as("fixed diagnostics only").isZero();
        Receipt receipt = Receipt.decode(output.toByteArray(), process.exitValue());
        assertThat(java.util.Arrays.equals(output.toByteArray(), receipt.bytes())).as("exact closed receipt bytes").isTrue();
        return receipt;
      } finally {
        process.toHandle().destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
        Thread closer = thread(() -> {
          try { process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close(); }
          catch (Exception ignored) { }
        });
        closer.join(1000); assertThat(closer.isAlive()).as("bounded pipe cleanup").isFalse();
      }
    }
  }

  Receipt call(String command, byte[] input) throws Exception {
    RunningCommand cmd = new RunningCommand(start(command));
    cmd.writeInput(input);
    return cmd.finish();
  }

  static Thread thread(Runnable action) { var t = new Thread(action); t.setDaemon(true); t.start(); return t; }
  private static void capture(java.io.InputStream stream, ByteArrayOutputStream out, AtomicBoolean failed) {
    try {
      byte[] buffer = new byte[256]; int read;
      while ((read = stream.read(buffer)) != -1) {
        if (out.size() + read > 2048) { failed.set(true); return; }
        out.write(buffer, 0, read);
      }
    } catch (Exception failure) { failed.set(true); }
  }

  @Override public void close() throws java.io.IOException {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
}
