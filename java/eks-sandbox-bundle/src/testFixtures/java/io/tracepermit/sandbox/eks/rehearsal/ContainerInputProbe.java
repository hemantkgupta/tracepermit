package io.tracepermit.sandbox.eks.rehearsal;

import io.tracepermit.agentcore.cli.InstalledPrivateInput;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Non-shipped fixture. Never accepts configuration, a token, a key or a permit. */
public final class ContainerInputProbe {
  private ContainerInputProbe() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) Runtime.getRuntime().halt(64);
    switch (arguments[0]) {
      case "descriptor", "descriptor-wait" -> {
        if (arguments[0].equals("descriptor-wait") && System.in.read() != 33) {
          Runtime.getRuntime().halt(1);
        }
        boolean accepted = InstalledPrivateInput.anonymousPipe();
        System.out.print(accepted ? "PIPE_ACCEPTED\n" : "PIPE_REFUSED\n");
        System.out.flush();
        Runtime.getRuntime().halt(accepted ? 0 : 1);
      }
      case "file" -> file();
      case "fifo", "unlinked-fifo" -> fifo(arguments[0].equals("unlinked-fifo"));
      case "containment" -> containment();
      case "flood-out", "flood-err" -> {
        OutputStream output = arguments[0].equals("flood-out") ? System.out : System.err;
        byte[] fixed = new byte[256];
        java.util.Arrays.fill(fixed, (byte) 'x');
        for (int index = 0; index < 64; index++) output.write(fixed);
        output.flush();
      }
      default -> Runtime.getRuntime().halt(64);
    }
  }

  private static ProcessBuilder child(String command) {
    return new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2", "-cp",
        System.getProperty("java.class.path"), ContainerInputProbe.class.getName(), command);
  }

  private static void file() throws Exception {
    Path file = Files.createTempFile(Path.of("/tmp"), "tp-public-sentinel-", ".txt");
    Files.writeString(file, "PUBLIC_TEST_SENTINEL_ONLY");
    Process process = null;
    try {
      process = child("descriptor")
          .redirectOutput(ProcessBuilder.Redirect.INHERIT)
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .redirectInput(file.toFile()).start();
      if (!process.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("PROBE_FILE_CHILD_TIMEOUT");
      if (process.exitValue() != 1) throw new AssertionError("PROBE_FILE_REFUSAL_NOT_CONFIRMED");
    } finally {
      if (process != null) {
        if (process.isAlive()) process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("PROBE_CHILD_NOT_REAPED");
      }
      Files.deleteIfExists(file);
    }
  }

  private static void fifo(boolean unlink) throws Exception {
    Path directory = Files.createTempDirectory("tp-public-fifo-");
    Path fifo = directory.resolve("sentinel");
    Process mkfifo = new ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).start();
    if (!mkfifo.waitFor(5, TimeUnit.SECONDS) || mkfifo.exitValue() != 0) {
      mkfifo.destroyForcibly();
      if (!mkfifo.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("PROBE_FIFO_TOOL_NOT_REAPED");
      throw new AssertionError("PROBE_FIFO_UNAVAILABLE");
    }
    AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    Thread writer = new Thread(() -> {
      try (OutputStream output = Files.newOutputStream(fifo)) {
        if (unlink) Files.delete(fifo);
        output.write(33);
        output.flush();
      } catch (Throwable failure) {
        writerFailure.set(failure);
      }
    }, "owned-public-fifo-writer");
    writer.setDaemon(true);
    Process process = null;
    try {
      writer.start();
      process = child("descriptor-wait")
          .redirectOutput(ProcessBuilder.Redirect.INHERIT)
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .redirectInput(fifo.toFile()).start();
      if (!process.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("PROBE_FIFO_CHILD_TIMEOUT");
      writer.join(1_000);
      if (writer.isAlive() || writerFailure.get() != null || process.exitValue() != 1) {
        throw new AssertionError("PROBE_FIFO_REFUSAL_NOT_CONFIRMED");
      }
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("PROBE_CHILD_NOT_REAPED");
      }
      Files.deleteIfExists(fifo);
      Files.deleteIfExists(directory);
    }
  }

  private static void containment() throws Exception {
    String status = Files.readString(Path.of("/proc/self/status"));
    if (!status.matches("(?s).*\nUid:\\s+10001\\s+10001\\s+10001\\s+10001\\s*\n.*")
        || !status.matches("(?s).*\nCapEff:\\s+0+\\s*\n.*")
        || !status.matches("(?s).*\nNoNewPrivs:\\s+1\\s*\n.*")) {
      throw new AssertionError("PROBE_PROCESS_CONTAINMENT_MISMATCH");
    }
    Path forbidden = Path.of("/opt/tracepermit/.tp-owned-write-probe");
    try {
      Files.createFile(forbidden);
      Files.deleteIfExists(forbidden);
      throw new AssertionError("PROBE_APPLICATION_ROOT_WRITABLE");
    } catch (java.io.IOException expected) {
      // Both metadata and an actual attempted application-root write are checked.
    }
    Path temporary = Files.createTempFile("tp-owned-writable-tmp-", ".txt");
    Files.delete(temporary);
    System.out.print("CONTAINMENT_VERIFIED\n");
    System.out.flush();
  }
}
