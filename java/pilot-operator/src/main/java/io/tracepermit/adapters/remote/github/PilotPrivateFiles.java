package io.tracepermit.adapters.remote.github;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Operator-owned POSIX files only; no creation, path rendering or link following. */
final class PilotPrivateFiles {
  private static final Set<PosixFilePermission> FILE_MODE = Set.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  private static final Set<PosixFilePermission> DIRECTORY_MODE = Set.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

  private PilotPrivateFiles() {}

  static byte[] read(Path path, int limit) throws Exception {
    if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) throw new IllegalArgumentException();
    // Reject links in every path component, including the immediate private directory.
    Path component = path.getRoot();
    for (Path segment : path) {
      component = component.resolve(segment);
      if (Files.isSymbolicLink(component)) throw new IllegalArgumentException();
    }
    var parent = attributes(path.getParent());
    if (!parent.isDirectory() || !owned(parent) || !DIRECTORY_MODE.equals(parent.permissions())) {
      throw new IllegalArgumentException();
    }
    var before = attributes(path);
    if (!before.isRegularFile() || !owned(before) || before.fileKey() == null
        || before.size() < 1 || before.size() > limit
        || !before.permissions().contains(PosixFilePermission.OWNER_READ)
        || !FILE_MODE.containsAll(before.permissions())
        || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
      throw new IllegalArgumentException();
    }
    byte[] buffer = new byte[limit + 1];
    try {
      int size;
      try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        var target = ByteBuffer.wrap(buffer);
        while (target.hasRemaining()) {
          int read = channel.read(target);
          if (read == -1) break;
          if (read == 0 || Thread.currentThread().isInterrupted()) throw new IllegalArgumentException();
        }
        size = target.position();
      }
      var after = attributes(path);
      var parentAfter = attributes(path.getParent());
      if (size < 1 || size > limit || !after.isRegularFile() || !owned(after)
          || !Objects.equals(before.fileKey(), after.fileKey()) || !before.permissions().equals(after.permissions())
          || before.size() != size || after.size() != size
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !Objects.equals(parent.fileKey(), parentAfter.fileKey())
          || !parent.permissions().equals(parentAfter.permissions()) || !owned(parentAfter)) {
        throw new IllegalArgumentException();
      }
      return Arrays.copyOf(buffer, size);
    } finally {
      Arrays.fill(buffer, (byte) 0);
    }
  }

  private static PosixFileAttributes attributes(Path path) throws Exception {
    return Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  private static boolean owned(PosixFileAttributes attributes) {
    return attributes.owner().getName().equals(System.getProperty("user.name"));
  }
}
