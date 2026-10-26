package io.tracepermit.adapters.remote.github.credentials;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Explicit private POSIX regular file, bounded PKCS#8 DER only; never writes signing material. */
final class PrivateAppKeyFile implements AppSigningKeySource {
  private static final int MAX_BYTES = 16_384;
  private final Path path;
  PrivateAppKeyFile(Path path) {
    if (path == null || !path.isAbsolute()) {
      throw GitHubCredentialException.reject(GitHubCredentialException.Code.CONFIGURATION_REJECTED);
    }
    this.path = path.normalize();
  }
  @Override public PrivateKey read() {
    byte[] buffer = new byte[MAX_BYTES + 1];
    byte[] encoded = null;
    try {
      var before = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      var allowed = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      if (!before.isRegularFile() || before.fileKey() == null || before.size() < 1 || before.size() > MAX_BYTES
          || !before.permissions().contains(PosixFilePermission.OWNER_READ)
          || !allowed.containsAll(before.permissions())) throw new IllegalArgumentException();
      int size;
      try (var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        var target = ByteBuffer.wrap(buffer);
        while (target.hasRemaining()) {
          int read = file.read(target);
          if (read < 0) break;
          if (read == 0) throw new IllegalArgumentException();
        }
        size = target.position();
      }
      var after = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (size < 1 || size > MAX_BYTES || !after.isRegularFile()
          || !Objects.equals(before.fileKey(), after.fileKey()) || !before.permissions().equals(after.permissions())
          || before.size() != size || after.size() != size) throw new IllegalArgumentException();
      encoded = Arrays.copyOf(buffer, size);
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    } catch (Throwable failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw GitHubCredentialException.reject(GitHubCredentialException.Code.KEY_SOURCE_REJECTED);
    } finally {
      Arrays.fill(buffer, (byte) 0);
      if (encoded != null) Arrays.fill(encoded, (byte) 0);
    }
  }
  @Override public String toString() { return "PrivateAppKeyFile[redacted]"; }
}
