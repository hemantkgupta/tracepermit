package io.tracepermit.sandbox.eks.rehearsal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only bounded Docker CLI I/O. Never logs argv, input bytes or raw failures. */
public final class DockerIo {
  public record Result(int exit, byte[] out, byte[] err) {
    @Override public String toString() {
      return "DockerResult[exit=" + exit + ",stdoutBytes=" + out.length + ",stderrBytes=" + err.length + "]";
    }
  }

  private final Path executable;
  private final Path clientConfig;
  private final String endpoint;

  public DockerIo(Path executable) throws java.io.IOException {
    this.executable = executable.toRealPath();
    if (!Files.isExecutable(this.executable) || !Files.isRegularFile(this.executable)) {
      throw new AssertionError("PREPARED_DOCKER_EXECUTABLE_REQUIRED");
    }
    endpoint = System.getProperty("tracepermit.container.dockerEndpoint", "");
    if (!endpoint.startsWith("unix://") || endpoint.contains("\n") || endpoint.contains("\r")) {
      throw new AssertionError("EXPLICIT_LOCAL_DOCKER_ENDPOINT_REQUIRED");
    }
    Path evidence = Path.of(System.getProperty("tracepermit.container.evidence")).toRealPath();
    clientConfig = Files.createTempDirectory(evidence, "empty-docker-client-",
        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
    Files.writeString(clientConfig.resolve("config.json"), "{\"auths\":{},\"proxies\":{}}\n");
  }

  public Result control(List<String> arguments) throws Exception {
    return run(arguments, new byte[0], false, Duration.ofSeconds(10), 262_144, null);
  }

  public Result run(List<String> arguments, byte[] input, boolean holdOpen,
      Duration budget, int maximum, CountDownLatch inputSent) throws Exception {
    if (budget.isNegative() || budget.isZero() || maximum < 1 || maximum > 262_144) {
      throw new IllegalArgumentException("INVALID_TEST_IO_BOUND");
    }
    var command = new ArrayList<String>();
    command.add(executable.toString());
    command.addAll(List.of("--config", clientConfig.toString(), "--host", endpoint));
    command.addAll(arguments);
    Process process;
    try {
      var builder = new ProcessBuilder(command);
      for (String key : List.of("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_AUTH_CONFIG",
          "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "DOCKER_CUSTOM_HEADERS",
          "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "all_proxy", "no_proxy")) {
        builder.environment().remove(key);
      }
      process = builder.start();
    }
    catch (Exception failure) { Arrays.fill(input, (byte) 0); throw new AssertionError("DOCKER_PROCESS_START_FAILED"); }
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var overflow = new AtomicBoolean();
    var failed = new AtomicBoolean();
    Thread stdout = reader(process.getInputStream(), out, maximum, overflow, failed);
    Thread stderr = reader(process.getErrorStream(), err, maximum, overflow, failed);
    Thread writer = new Thread(() -> {
      try {
        process.getOutputStream().write(input);
        process.getOutputStream().flush();
        if (!holdOpen) process.getOutputStream().close();
        if (inputSent != null) inputSent.countDown();
      } catch (Exception failure) { failed.set(true); }
    }, "owned-container-input-writer");
    writer.setDaemon(true);
    boolean interrupted = false;
    boolean reaped = true;
    stdout.start(); stderr.start(); writer.start();
    long deadline = System.nanoTime() + budget.toNanos();
    try {
      while (process.isAlive() && !overflow.get() && !failed.get()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new AssertionError("DOCKER_PROCESS_DEADLINE");
        process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
      }
      if (overflow.get()) throw new AssertionError("CONTAINER_OUTPUT_LIMIT");
      if (failed.get()) throw new AssertionError("CONTAINER_IO_FAILED");
      writer.join(1_000); stdout.join(1_000); stderr.join(1_000);
      if (writer.isAlive() || stdout.isAlive() || stderr.isAlive()) throw new AssertionError("CONTAINER_READER_NOT_REAPED");
      if (overflow.get()) throw new AssertionError("CONTAINER_OUTPUT_LIMIT");
      if (failed.get()) throw new AssertionError("CONTAINER_IO_FAILED");
      return new Result(process.exitValue(), out.toByteArray(), err.toByteArray());
    } catch (InterruptedException failure) {
      interrupted = true;
      throw failure;
    } finally {
      interrupted |= Thread.interrupted();
      try {
        if (process.isAlive()) process.destroyForcibly();
        reaped = process.waitFor(5, TimeUnit.SECONDS);
        try { process.getOutputStream().close(); } catch (Exception ignored) { }
        try { process.getInputStream().close(); } catch (Exception ignored) { }
        try { process.getErrorStream().close(); } catch (Exception ignored) { }
        writer.join(1_000); stdout.join(1_000); stderr.join(1_000);
        reaped &= !writer.isAlive() && !stdout.isAlive() && !stderr.isAlive();
      } catch (InterruptedException failure) {
        interrupted = true;
        reaped = false;
      } finally {
        Arrays.fill(input, (byte) 0);
        if (interrupted) Thread.currentThread().interrupt();
      }
      if (!reaped) throw new AssertionError("OWNED_DOCKER_CLIENT_CLEANUP_UNCONFIRMED");
    }
  }

  public static void capture(InputStream source, ByteArrayOutputStream destination,
      int maximum, AtomicBoolean overflow) throws java.io.IOException {
    byte[] buffer = new byte[256];
    int count;
    while ((count = source.read(buffer)) != -1) {
      if (count > maximum - destination.size()) { overflow.set(true); return; }
      destination.write(buffer, 0, count);
    }
  }

  private static Thread reader(InputStream source, ByteArrayOutputStream destination,
      int maximum, AtomicBoolean overflow, AtomicBoolean failed) {
    Thread thread = new Thread(() -> {
      try { capture(source, destination, maximum, overflow); }
      catch (Exception failure) { failed.set(true); }
    }, "owned-container-output-reader");
    thread.setDaemon(true);
    return thread;
  }
}
