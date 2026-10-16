package io.tracepermit.endpoint;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.SourceAttestationVerifier;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Opt-in local diagnostics, not a source-verification result or an authority input. */
public final class FileProcessObservationRecorder implements EndpointObservationCapture, AutoCloseable {
  static final int MAX_RECORDS = 32;
  private static final int MAX_BYTES = 64 * 1024;
  private static final Set<PosixFilePermission> DIRECTORY_MODE =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_MODE =
      PosixFilePermissions.fromString("rw-------");

  private final Clock clock;
  private final Path directory;
  private final Path path;
  private final UserPrincipal owner;
  private final Object directoryKey;
  private final Object fileKey;
  private final FileChannel channel;
  private int records;
  private int bytesWritten;

  public FileProcessObservationRecorder(Path directory) {
    this(directory, Clock.systemUTC());
  }

  FileProcessObservationRecorder(Path directory, Clock clock) {
    this.clock = Objects.requireNonNull(clock);
    this.directory = Objects.requireNonNull(directory).normalize();
    FileChannel opened = null;
    try {
      if (!this.directory.isAbsolute()
          || !this.directory.equals(this.directory.toRealPath())) throw unavailable(null);
      owner = this.directory.getFileSystem().getUserPrincipalLookupService()
          .lookupPrincipalByName(System.getProperty("user.name"));
      PosixFileAttributes parent = attributes(this.directory);
      if (!parent.isDirectory() || !parent.owner().equals(owner)
          || !parent.permissions().equals(DIRECTORY_MODE) || parent.fileKey() == null) {
        throw unavailable(null);
      }
      directoryKey = parent.fileKey();
      path = this.directory.resolve("process-observations-" + UUID.randomUUID() + ".jsonl");
      opened = FileChannel.open(path,
          Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
          PosixFilePermissions.asFileAttribute(FILE_MODE));
      PosixFileAttributes file = attributes(path);
      fileKey = Objects.requireNonNull(file.fileKey());
      channel = opened;
      verifyFiles();
    } catch (IOException | RuntimeException failure) {
      if (opened != null) {
        try {
          opened.close();
        } catch (IOException ignored) {
          // Never disclose filesystem/provider exception text to the MCP client.
        }
      }
      throw unavailable(failure);
    }
  }

  /** Local operator convenience only; this path is never included in a record. */
  public Path path() {
    return path;
  }

  @Override
  public synchronized void record(ProcessObservation observation, JsonNode request) {
    try {
      verifyFiles();
      if (records >= MAX_RECORDS || !channel.isOpen()
          || channel.size() != bytesWritten || channel.position() != bytesWritten) {
        throw unavailable(null);
      }
      ObjectNode record = JsonSupport.MAPPER.createObjectNode();
      record.put("schemaVersion", "1.0");
      record.put("kind", "endpoint-process-observation");
      record.put("stage", "stability_checked_before_forward");
      record.put("requestBindingProfile", SourceAttestationFactory.REQUEST_BINDING_PROFILE);
      record.put("requestHash", SourceAttestationVerifier.hashRequest(request));
      record.put("sequence", records + 1);
      record.put("capturedAt", clock.instant().toString());
      record.put("platform", observation.platform());
      record.put("assurance", observation.assurance());
      record.put("terminalAttached", observation.terminalAttached());
      ObjectNode process = record.putObject("process");
      process.put("processId", observation.processId());
      process.put("executableName", observation.executableName());
      process.put("executableHash", observation.executableHash());
      process.put("parentExecutableName", observation.parentExecutableName());
      process.put("startedAt", observation.startedAt().toString());
      byte[] encoded = (JsonSupport.MAPPER.writeValueAsString(record) + "\n")
          .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (bytesWritten + encoded.length > MAX_BYTES) throw unavailable(null);
      ByteBuffer buffer = ByteBuffer.wrap(encoded);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(false);
      bytesWritten += encoded.length;
      records++;
    } catch (IOException | RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private void verifyFiles() throws IOException {
    if (!directory.equals(directory.toRealPath())) throw unavailable(null);
    PosixFileAttributes parent = attributes(directory);
    PosixFileAttributes file = attributes(path);
    if (!parent.isDirectory() || !parent.owner().equals(owner)
        || !parent.permissions().equals(DIRECTORY_MODE)
        || !Objects.equals(parent.fileKey(), directoryKey)
        || !file.isRegularFile() || !file.owner().equals(owner)
        || !file.permissions().equals(FILE_MODE) || !Objects.equals(file.fileKey(), fileKey)
        || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() != 1) {
      throw unavailable(null);
    }
  }

  private static PosixFileAttributes attributes(Path target) throws IOException {
    return Files.readAttributes(target, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public synchronized void close() {
    try {
      channel.close();
    } catch (IOException failure) {
      throw unavailable(failure);
    }
  }

  private static ConnectorException unavailable(Throwable cause) {
    return new ConnectorException("ENDPOINT_CAPTURE_UNAVAILABLE",
        "The requested private process-observation capture is unavailable.", cause);
  }
}
