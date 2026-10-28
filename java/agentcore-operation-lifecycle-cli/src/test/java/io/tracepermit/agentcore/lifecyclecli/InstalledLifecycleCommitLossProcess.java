package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns only the extracted launcher's one exact deliver-synthetic worker and bounded private pipes. */
final class InstalledLifecycleCommitLossProcess implements AutoCloseable {
  private final Process parent;
  private final ProcessHandle worker;
  private final ByteArrayOutputStream output = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
  private final AtomicBoolean pipeFailed = new AtomicBoolean();
  private final Thread out;
  private final Thread err;
  private final Thread writer;

  InstalledLifecycleCommitLossProcess(InstalledLifecycleArchive archive, byte[] input) throws Exception {
    parent = archive.start("deliver-synthetic");
    ProcessHandle found;
    try {
      found = findWorker(archive);
    } catch (Throwable failure) {
      // Preserve the installed supervisor's shutdown hook so it can reap its own worker.
      parent.destroy();
      assertThat(parent.waitFor(5, TimeUnit.SECONDS)).isTrue();
      throw failure;
    }
    worker = found;
    out = thread(() -> capture(parent.getInputStream(), output));
    err = thread(() -> capture(parent.getErrorStream(), errors));
    writer = thread(() -> {
      try (var pipe = parent.getOutputStream()) {
        pipe.write(input);
      } catch (Exception failure) {
        pipeFailed.set(true);
      } finally {
        Arrays.fill(input, (byte) 0);
      }
    });
  }

  void requireWaitingWorker() {
    assertThat(parent.isAlive()).isTrue();
    assertThat(worker.isAlive()).isTrue();
    assertThat(worker.parent().map(p -> p.pid() == parent.pid())).contains(true);
    assertThat(writer.isAlive()).as("private input fully delivered").isFalse();
  }

  void killOwnedWorker() throws Exception {
    requireWaitingWorker();
    assertThat(worker.destroyForcibly()).isTrue();
    worker.onExit().get(5, TimeUnit.SECONDS);
    assertThat(worker.isAlive()).isFalse();
  }

  LifecycleReceipt receipt() throws Exception {
    assertThat(parent.waitFor(70, TimeUnit.SECONDS)).as("unchanged installed deadline").isTrue();
    for (Thread thread : List.of(writer, out, err)) {
      thread.join(1_000);
      assertThat(thread.isAlive()).isFalse();
    }
    assertThat(pipeFailed.get()).isFalse();
    assertThat(errors.size()).as("no private diagnostics").isZero();
    assertThat(worker.isAlive()).isFalse();
    LifecycleReceipt receipt = LifecycleReceipt.decode(output.toByteArray(), parent.exitValue());
    // A malformed/missing output must not pass just because decode also maps it to UNKNOWN.
    assertThat(output.toByteArray()).isEqualTo(receipt.bytes());
    return receipt;
  }

  private ProcessHandle findWorker(InstalledLifecycleArchive archive) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (parent.isAlive() && System.nanoTime() < end) {
      var found = parent.toHandle().children()
          .filter(child -> exactWorker(child, archive)).toList();
      assertThat(found.size()).isLessThanOrEqualTo(1);
      if (!found.isEmpty()) return found.getFirst();
      Thread.sleep(10);
    }
    throw new AssertionError("OWNED_INSTALLED_DELIVERY_WORKER_NOT_FOUND");
  }

  private boolean exactWorker(ProcessHandle child, InstalledLifecycleArchive archive) {
    if (!child.isAlive() || child.parent().map(p -> p.pid() != parent.pid()).orElse(true)) return false;
    try {
      String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath().toString();
      String command = child.info().command().orElse("");
      String[] args = child.info().arguments().orElse(null);
      if (args == null && System.getProperty("os.name").equals("Linux")) {
        byte[] bytes;
        try (var input = Files.newInputStream(Path.of("/proc", Long.toString(child.pid()), "cmdline"))) {
          bytes = input.readNBytes(LifecycleWorkerProcesses.MAXIMUM_COMMAND_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > LifecycleWorkerProcesses.MAXIMUM_COMMAND_BYTES
            || bytes[bytes.length - 1] != 0) return false;
        String[] full = StandardCharsets.UTF_8.newDecoder()
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString().split("\u0000", -1);
        if (full.length != 9 || !full[8].isEmpty()) return false;
        command = full[0];
        args = Arrays.copyOfRange(full, 1, 8);
      }
      if (!Path.of(command).toRealPath().toString().equals(javaExecutable) || args == null || args.length != 7
          || !args[0].equals("-Xms16m") || !args[1].equals("-Xmx64m")
          || !args[2].equals("-XX:ActiveProcessorCount=2") || !args[3].equals("-cp")
          || !args[5].equals(LifecycleWorker.class.getName()) || !args[6].equals("deliver-synthetic")) {
        return false;
      }
      Path lib = archive.home.resolve("lib").toRealPath();
      String[] entries = args[4].split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
      if (entries.length == 0) return false;
      for (String entry : entries) {
        if (!Path.of(entry).toRealPath().startsWith(lib) || !entry.endsWith(".jar")) return false;
      }
      return child.isAlive() && child.parent().map(p -> p.pid() == parent.pid()).orElse(false);
    } catch (Exception unreadable) {
      return false;
    }
  }

  private static Thread thread(Runnable action) {
    Thread thread = new Thread(action, "installed-lifecycle-commit-loss-pipe");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private void capture(InputStream input, ByteArrayOutputStream target) {
    try {
      byte[] buffer = new byte[256];
      for (int size; (size = input.read(buffer)) != -1; ) {
        if (size > LifecycleProtocol.RECEIPT_BYTES - target.size()) {
          pipeFailed.set(true);
          return;
        }
        target.write(buffer, 0, size);
      }
    } catch (Exception failure) {
      pipeFailed.set(true);
    }
  }

  @Override
  public void close() throws java.io.IOException, java.util.concurrent.ExecutionException,
      java.util.concurrent.TimeoutException {
    try {
      // This stored handle was proven to be our exact direct worker before input was sent. Never
      // enumerate or kill other workers/containers. Retain the supervisor so it can reap its child.
      if (worker.isAlive()) {
        assertThat(worker.destroyForcibly()).isTrue();
        worker.onExit().get(5, TimeUnit.SECONDS);
      }
      if (!parent.waitFor(5, TimeUnit.SECONDS)) {
        parent.destroyForcibly();
        assertThat(parent.waitFor(5, TimeUnit.SECONDS)).isTrue();
      }
      parent.getOutputStream().close();
      parent.getInputStream().close();
      parent.getErrorStream().close();
      for (Thread thread : List.of(writer, out, err)) {
        thread.join(1_000);
        assertThat(thread.isAlive()).isFalse();
      }
      assertThat(worker.isAlive() || parent.isAlive()).isFalse();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("INSTALLED_COMMIT_LOSS_CLEANUP_INTERRUPTED");
    }
  }
}
