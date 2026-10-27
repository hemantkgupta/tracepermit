package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Actual extracted application archive outside every checkout. */
final class InstalledLifecycleArchive implements AutoCloseable {
  final Path root;
  final Path home;
  final Path launcher;

  InstalledLifecycleArchive() throws Exception {
    root = Files.createTempDirectory("tp-operation-lifecycle-installed-");
    assertThat(
            root.toRealPath()
                .startsWith(
                    Path.of(System.getProperty("tracepermit.repositoryRoot")).toRealPath()))
        .isFalse();
    Process tar =
        new ProcessBuilder(
                "/usr/bin/tar",
                "-xf",
                System.getProperty("tracepermit.lifecycle.archive"),
                "-C",
                root.toString())
            .start();
    assertThat(tar.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThat(tar.exitValue()).isZero();
    try (var directories = Files.list(root)) {
      home = directories.findFirst().orElseThrow();
    }
    launcher = home.resolve("bin/tracepermit-synthetic-operation-lifecycle");
    assertThat(Files.isExecutable(launcher)).isTrue();
  }

  Process start(String command) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(launcher.toString(), command).directory(root.toFile());
    builder.environment().clear();
    builder.environment().put("PATH", "/usr/bin:/bin");
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    return builder.start();
  }

  LifecycleReceipt call(String command, byte[] input) throws Exception {
    Process process = start(command);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    AtomicBoolean failed = new AtomicBoolean();
    AtomicReference<Throwable> writeFailure = new AtomicReference<>();
    Thread out = thread(() -> capture(process.getInputStream(), output, failed));
    Thread err = thread(() -> capture(process.getErrorStream(), errors, failed));
    Thread writer =
        thread(
            () -> {
              try {
                process.getOutputStream().write(input);
                process.getOutputStream().close();
              } catch (Throwable failure) {
                writeFailure.set(failure);
              }
            });
    try {
      assertThat(process.waitFor(70, TimeUnit.SECONDS)).as("installed lifecycle deadline").isTrue();
      writer.join(1_000);
      out.join(1_000);
      err.join(1_000);
      assertThat(writer.isAlive() || out.isAlive() || err.isAlive()).isFalse();
      assertThat(failed.get()).isFalse();
      assertThat(writeFailure.get()).isNull();
      assertThat(errors.size()).as("no private diagnostic channel").isZero();
      LifecycleReceipt receipt = LifecycleReceipt.decode(output.toByteArray(), process.exitValue());
      assertThat(output.toByteArray()).isEqualTo(receipt.bytes());
      return receipt;
    } finally {
      process.toHandle().destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      try {
        process.getOutputStream().close();
        process.getInputStream().close();
        process.getErrorStream().close();
      } catch (Exception ignored) {
        // The process is already reaped; this is best-effort test cleanup.
      }
    }
  }

  private static Thread thread(Runnable action) {
    Thread thread = new Thread(action);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static void capture(
      java.io.InputStream source, ByteArrayOutputStream output, AtomicBoolean failed) {
    try {
      byte[] buffer = new byte[256];
      int count;
      while ((count = source.read(buffer)) != -1) {
        if (output.size() + count > LifecycleProtocol.RECEIPT_BYTES) {
          failed.set(true);
          return;
        }
        output.write(buffer, 0, count);
      }
    } catch (Exception failure) {
      failed.set(true);
    }
  }

  @Override
  public void close() throws java.io.IOException {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}
