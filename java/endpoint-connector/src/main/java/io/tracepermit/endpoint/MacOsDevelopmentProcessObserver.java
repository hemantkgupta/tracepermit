package io.tracepermit.endpoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MacOsDevelopmentProcessObserver implements ProcessObserver {
  private static final int MAX_TTY_OUTPUT = 256;
  private final ProcessHandleProcessObserver delegate;

  public MacOsDevelopmentProcessObserver(String deviceId) {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
      throw new IllegalStateException(
          "The development macOS process observer is available only on macOS.");
    }
    delegate = new ProcessHandleProcessObserver(deviceId, MacOsDevelopmentProcessObserver::hasTty);
  }

  @Override
  public ProcessObservation observe() {
    return delegate.observe();
  }

  @Override
  public void verifyStable(ProcessObservation observation) {
    delegate.verifyStable(observation);
  }

  static boolean ttyOutputIsInteractive(String output) {
    String value = output == null ? "" : output.trim();
    return !value.isEmpty() && !"??".equals(value) && !"?".equals(value);
  }

  static boolean hasTty(long processId) {
    try {
      return terminalResult(terminalProbeCommand(processId).start(), Duration.ofSeconds(2));
    } catch (IOException exception) {
      throw unavailable(exception);
    }
  }

  static boolean terminalResult(Process process, Duration timeout) {
    long started = System.nanoTime();
    FutureTask<byte[]> outputRead = new FutureTask<>(() -> {
      try (InputStream input = process.getInputStream()) {
        return input.readNBytes(MAX_TTY_OUTPUT + 1);
      }
    });
    Thread reader = Thread.ofVirtual().name("tracepermit-terminal-probe").start(outputRead);
    try {
      byte[] output = outputRead.get(remaining(started, timeout), TimeUnit.NANOSECONDS);
      if (output.length > MAX_TTY_OUTPUT
          || !process.waitFor(remaining(started, timeout), TimeUnit.NANOSECONDS)
          || process.exitValue() != 0) {
        throw unavailable(null);
      }
      remaining(started, timeout);
      return ttyOutputIsInteractive(new String(output, StandardCharsets.UTF_8));
    } catch (ExecutionException | TimeoutException exception) {
      throw unavailable(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable(exception);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
      outputRead.cancel(true);
      reader.interrupt();
    }
  }

  private static long remaining(long started, Duration timeout) throws TimeoutException {
    long remaining = timeout.toNanos() - (System.nanoTime() - started);
    if (remaining <= 0) throw new TimeoutException();
    return remaining;
  }

  static ProcessBuilder terminalProbeCommand(long processId) {
    ProcessBuilder command = new ProcessBuilder(
        "/bin/ps", "-o", "tty=", "-p", Long.toString(processId));
    command.environment().clear();
    return command;
  }

  private static ConnectorException unavailable(Throwable cause) {
    return new ConnectorException(
        "ENDPOINT_OBSERVATION_UNAVAILABLE",
        "The macOS terminal observation is unavailable.",
        cause);
  }
}
