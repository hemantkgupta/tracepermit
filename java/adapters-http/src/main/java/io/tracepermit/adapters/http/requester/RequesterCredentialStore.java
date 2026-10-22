package io.tracepermit.adapters.http.requester;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.contracts.JsonSupport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** Owner-only, bounded, atomic no-replace custody for one short-lived bearer. */
final class RequesterCredentialStore {
  static final int MAXIMUM_ENVELOPE_BYTES = 20 * 1024;
  private static final int MAXIMUM_TOKEN_CHARACTERS = 16_384;
  private static final Set<PosixFilePermission> OWNER_FILE =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
      PosixFilePermission.OWNER_EXECUTE);
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static final class Credential {
    private final String accessToken;
    private final Instant expiresAt;
    private final Object fileKey;

    private Credential(String accessToken, Instant expiresAt, Object fileKey) {
      this.accessToken = accessToken;
      this.expiresAt = expiresAt;
      this.fileKey = fileKey;
    }

    String accessToken() { return accessToken; }
    Instant expiresAt() { return expiresAt; }
  }

  private final Path target;
  private final Clock clock;

  RequesterCredentialStore(Path target, Clock clock) {
    this.target = target;
    this.clock = clock;
  }

  void publish(String accessToken, Instant expiresAt) {
    if (!safeToken(accessToken) || expiresAt == null
        || !expiresAt.isAfter(clock.instant().plusSeconds(59))) {
      throw new InstalledRequesterFailure("CREDENTIAL_EXPIRED");
    }
    Path temporary = null;
    try {
      validateDirectory();
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new InstalledRequesterFailure("CREDENTIAL_ALREADY_EXISTS");
      }
      byte[] envelope = envelope(accessToken, expiresAt);
      if (envelope.length > MAXIMUM_ENVELOPE_BYTES) {
        throw new InstalledRequesterFailure("CREDENTIAL_PATH_UNSAFE");
      }
      temporary = Files.createTempFile(target.getParent(), ".tracepermit-requester-", ".tmp",
          PosixFilePermissions.asFileAttribute(OWNER_FILE));
      validateFile(temporary, null);
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer bytes = ByteBuffer.wrap(envelope);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      BasicFileAttributes staged = validateFile(temporary, null);
      try {
        Files.createLink(target, temporary);
      } catch (FileAlreadyExistsException failure) {
        throw new InstalledRequesterFailure("CREDENTIAL_ALREADY_EXISTS");
      } catch (UnsupportedOperationException failure) {
        throw new InstalledRequesterFailure("CREDENTIAL_PATH_UNSAFE");
      }
      BasicFileAttributes published = validateFile(target, staged.fileKey());
      require(linkCount(target) == 2 && published.size() == envelope.length);
      Files.delete(temporary);
      temporary = null;
      validateFile(target, published.fileKey());
      require(linkCount(target) == 1);
    } catch (InstalledRequesterFailure failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw new InstalledRequesterFailure("CREDENTIAL_PATH_UNSAFE");
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The stable public failure never exposes a temporary path or nested exception.
        }
      }
    }
  }

  Credential read() {
    try {
      validateDirectory();
      BasicFileAttributes before = validateFile(target, null);
      require(linkCount(target) == 1 && before.size() > 0
          && before.size() <= MAXIMUM_ENVELOPE_BYTES);
      byte[] bytes = readBounded();
      BasicFileAttributes after = validateFile(target, before.fileKey());
      require(before.size() == after.size() && linkCount(target) == 1);
      JsonNode value = JSON.readTree(utf8(bytes));
      require(value != null && value.isObject() && value.size() == 3
          && value.has("schemaVersion") && value.has("accessToken") && value.has("expiresAt"));
      value.fieldNames().forEachRemaining(field -> require(
          Set.of("schemaVersion", "accessToken", "expiresAt").contains(field)));
      require("1.0".equals(text(value, "schemaVersion", 8)));
      String token = text(value, "accessToken", MAXIMUM_TOKEN_CHARACTERS);
      require(safeToken(token));
      Instant expiry = Instant.parse(text(value, "expiresAt", 64));
      return new Credential(token, expiry, after.fileKey());
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof InstalledRequesterFailure installed) throw installed;
      throw new InstalledRequesterFailure("CREDENTIAL_PATH_UNSAFE");
    }
  }

  void consume(Credential credential) {
    try {
      validateDirectory();
      validateFile(target, credential.fileKey);
      require(linkCount(target) == 1);
      Files.delete(target);
    } catch (IOException | RuntimeException failure) {
      throw new InstalledRequesterFailure("CREDENTIAL_CLEANUP_REQUIRED");
    }
  }

  void cleanup() {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
    Credential credential = read();
    consume(credential);
  }

  private byte[] envelope(String accessToken, Instant expiresAt) throws IOException {
    var value = JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0")
        .put("accessToken", accessToken).put("expiresAt", expiresAt.toString());
    return JsonSupport.MAPPER.writeValueAsBytes(value);
  }

  private byte[] readBounded() throws IOException {
    try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ,
        LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.allocate(MAXIMUM_ENVELOPE_BYTES + 1);
      while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded */ }
      require(buffer.position() > 0 && buffer.position() <= MAXIMUM_ENVELOPE_BYTES);
      return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }
  }

  private void validateDirectory() throws IOException {
    Path directory = target.getParent();
    require(target.isAbsolute() && target.normalize().equals(target) && directory != null);
    PosixFileAttributes attributes = Files.readAttributes(
        directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    require(attributes.isDirectory() && !Files.isSymbolicLink(directory)
        && attributes.permissions().equals(OWNER_DIRECTORY)
        && attributes.owner().equals(currentOwner(directory))
        && directory.toRealPath().equals(directory));
  }

  private BasicFileAttributes validateFile(Path path, Object expectedKey) throws IOException {
    PosixFileAttributes attributes = Files.readAttributes(
        path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    require(attributes.isRegularFile() && !Files.isSymbolicLink(path)
        && attributes.permissions().equals(OWNER_FILE)
        && attributes.owner().equals(currentOwner(path)));
    if (expectedKey != null) require(expectedKey.equals(attributes.fileKey()));
    return attributes;
  }

  private static java.nio.file.attribute.UserPrincipal currentOwner(Path path) throws IOException {
    return path.getFileSystem().getUserPrincipalLookupService()
        .lookupPrincipalByName(System.getProperty("user.name"));
  }

  private static int linkCount(Path path) throws IOException {
    Object value = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
    require(value instanceof Number);
    return ((Number) value).intValue();
  }

  private static String utf8(byte[] bytes) throws IOException {
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
  }

  private static String text(JsonNode node, String field, int maximum) {
    JsonNode value = node.path(field);
    require(value.isTextual() && !value.textValue().isBlank()
        && value.textValue().length() <= maximum
        && value.textValue().chars().noneMatch(Character::isISOControl));
    return value.textValue();
  }

  private static boolean safeToken(String value) {
    return value != null && value.length() <= MAXIMUM_TOKEN_CHARACTERS
        && value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
  }

  private static void require(boolean condition) {
    if (!condition) throw new InstalledRequesterFailure("CREDENTIAL_PATH_UNSAFE");
  }
}
