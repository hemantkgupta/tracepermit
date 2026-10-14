package io.tracepermit.endpoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

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
    Process process = null;
    try {
      process = terminalProbeCommand(processId).start();
      byte[] output;
      try (InputStream input = process.getInputStream()) {
        output = input.readNBytes(MAX_TTY_OUTPUT + 1);
      }
      if (output.length > MAX_TTY_OUTPUT
          || !process.waitFor(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS)
          || process.exitValue() != 0) {
        throw unavailable(null);
      }
      return ttyOutputIsInteractive(new String(output, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw unavailable(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable(exception);
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
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
