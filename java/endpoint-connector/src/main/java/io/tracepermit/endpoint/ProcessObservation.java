package io.tracepermit.endpoint;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record ProcessObservation(
    long processId,
    String deviceId,
    String platform,
    String assurance,
    String executableName,
    Path executablePath,
    String executableHash,
    String parentExecutableName,
    Instant startedAt,
    boolean terminalAttached) {
  private static final Set<String> PLATFORMS = Set.of("macos", "linux", "windows");

  public ProcessObservation {
    if (processId <= 0) throw new IllegalArgumentException("Process ID must be positive.");
    deviceId = bounded(deviceId, "Device ID", 128);
    platform = bounded(platform, "Platform", 16);
    if (!PLATFORMS.contains(platform)) {
      throw new IllegalArgumentException("Unsupported endpoint platform.");
    }
    if (!"development".equals(assurance)) {
      throw new IllegalArgumentException("Only development endpoint assurance is supported.");
    }
    executableName = bounded(executableName, "Executable name", 128);
    executablePath = Objects.requireNonNull(executablePath).toAbsolutePath().normalize();
    if (!executableHash.matches("^sha256:[a-f0-9]{64}$")) {
      throw new IllegalArgumentException("Executable fingerprint is invalid.");
    }
    parentExecutableName = bounded(parentExecutableName, "Parent executable name", 128);
    startedAt = Objects.requireNonNull(startedAt);
  }

  private static String bounded(String value, String label, int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum) {
      throw new IllegalArgumentException(label + " is invalid.");
    }
    return value;
  }
}
