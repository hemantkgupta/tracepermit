package io.tracepermit.endpoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongPredicate;

public class ProcessHandleProcessObserver implements ProcessObserver {
  private static final long MAX_EXECUTABLE_BYTES = 256L * 1024 * 1024;

  private final String deviceId;
  private final LongPredicate terminalProbe;

  public ProcessHandleProcessObserver(String deviceId, LongPredicate terminalProbe) {
    this.deviceId = Objects.requireNonNull(deviceId);
    this.terminalProbe = Objects.requireNonNull(terminalProbe);
  }

  @Override
  public ProcessObservation observe() {
    ProcessHandle subject = ProcessHandle.current().parent().orElseThrow(
        () -> unavailable("The parent process is unavailable."));
    ProcessHandle parent = subject.parent().orElseThrow(
        () -> unavailable("The parent-process ancestor is unavailable."));
    ProcessSnapshot subjectSnapshot = snapshot(subject);
    ProcessSnapshot parentSnapshot = snapshot(parent);
    return new ProcessObservation(
        subject.pid(),
        deviceId,
        platform(),
        "development",
        subjectSnapshot.executableName(),
        subjectSnapshot.executablePath(),
        subjectSnapshot.executableHash(),
        parentSnapshot.executableName(),
        subjectSnapshot.startedAt(),
        terminalProbe.test(subject.pid()));
  }

  @Override
  public void verifyStable(ProcessObservation observation) {
    Objects.requireNonNull(observation);
    ProcessHandle process = ProcessHandle.of(observation.processId()).orElseThrow(
        () -> changed(null));
    ProcessSnapshot current;
    try {
      current = snapshot(process);
    } catch (ConnectorException exception) {
      throw changed(exception);
    }
    if (!process.isAlive()
        || !current.executablePath().equals(observation.executablePath())
        || !current.executableName().equals(observation.executableName())
        || !current.executableHash().equals(observation.executableHash())
        || !current.startedAt().equals(observation.startedAt())) {
      throw changed(null);
    }
  }

  private static ProcessSnapshot snapshot(ProcessHandle process) {
    ProcessHandle.Info info = process.info();
    String command = info.command().orElseThrow(
        () -> unavailable("The process executable is unavailable."));
    Instant startedAt = info.startInstant().orElseThrow(
        () -> unavailable("The process start time is unavailable."));
    Path path;
    try {
      path = Path.of(command).toAbsolutePath().normalize().toRealPath();
    } catch (IOException exception) {
      throw unavailable("The process executable cannot be resolved.", exception);
    }
    String name = path.getFileName() == null ? "" : path.getFileName().toString();
    if (name.isBlank() || name.length() > 128) {
      throw unavailable("The process executable name is invalid.");
    }
    return new ProcessSnapshot(name, path, hashStableExecutable(path), startedAt);
  }

  private static String hashStableExecutable(Path path) {
    try {
      BasicFileAttributes before = Files.readAttributes(
          path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile()
          || before.size() <= 0
          || before.size() > MAX_EXECUTABLE_BYTES) {
        throw unavailable("The process executable cannot be measured safely.");
      }
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(path)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read > 0) digest.update(buffer, 0, read);
        }
      }
      BasicFileAttributes after = Files.readAttributes(
          path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !Objects.equals(before.fileKey(), after.fileKey())) {
        throw changed(null);
      }
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (ConnectorException exception) {
      throw exception;
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw unavailable("The process executable could not be fingerprinted.", exception);
    }
  }

  private static String platform() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("mac")) return "macos";
    if (os.contains("win")) return "windows";
    return "linux";
  }

  private static ConnectorException unavailable(String message) {
    return unavailable(message, null);
  }

  private static ConnectorException unavailable(String message, Throwable cause) {
    return new ConnectorException(
        "ENDPOINT_OBSERVATION_UNAVAILABLE",
        message == null ? "Endpoint process observation is unavailable." : message,
        cause);
  }

  private static ConnectorException changed(Throwable cause) {
    return new ConnectorException(
        "ENDPOINT_EXECUTABLE_CHANGED",
        "The observed endpoint executable changed before dispatch.",
        cause);
  }

  private record ProcessSnapshot(
      String executableName,
      Path executablePath,
      String executableHash,
      Instant startedAt) {}
}
