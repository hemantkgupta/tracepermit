package io.tracepermit.agentcore.archive;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Build-only finalization of one fresh Gradle distribution. No extraction or runtime entrypoint.
 * GNU name records become standard USTAR name/prefix fields; content and entry metadata stay intact.
 */
public final class LifecycleUstar {
  private static final int BLOCK = 512;
  private static final long ARCHIVE_LIMIT = 256L * 1024 * 1024;
  private static final long FILE_LIMIT = 32L * 1024 * 1024;
  private static final long EXPANDED_LIMIT = 128L * 1024 * 1024;
  private static final int ENTRY_LIMIT = 4096;
  private static final byte[] USTAR = {'u', 's', 't', 'a', 'r', 0, '0', '0'};
  private static final byte[] GNU = {'u', 's', 't', 'a', 'r', ' ', ' ', 0};

  private LifecycleUstar() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) throw invalid();
    normalize(Path.of(args[0]), args[1]);
    System.out.println("LIFECYCLE_USTAR_PRODUCED");
  }

  public static void normalize(Path archive, String root) throws IOException {
    component(root);
    BasicFileAttributes before = Files.readAttributes(archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile() || before.size() < 2L * BLOCK || before.size() > ARCHIVE_LIMIT
        || before.size() % BLOCK != 0) throw invalid();
    Set<PosixFilePermission> permissions;
    try { permissions = Files.getPosixFilePermissions(archive, LinkOption.NOFOLLOW_LINKS); }
    catch (UnsupportedOperationException unsupported) { permissions = null; }
    Path temporary = Files.createTempFile(archive.toAbsolutePath().getParent(), ".lifecycle-ustar-", ".tmp");
    try {
      try (InputStream input = new BufferedInputStream(Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS));
          OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporary))) {
        rewrite(input, output, root);
      }
      BasicFileAttributes after = Files.readAttributes(archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile() || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !Objects.equals(before.fileKey(), after.fileKey())) throw invalid();
      if (permissions != null) Files.setPosixFilePermissions(temporary, permissions);
      Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void rewrite(InputStream input, OutputStream output, String root) throws IOException {
    long[] consumed = {0};
    long expanded = 0;
    int entries = 0, names = 0, files = 0, zeros = 0;
    String pending = null;
    TreeMap<String, Boolean> paths = new TreeMap<>();
    while (true) {
      byte[] header = input.readNBytes(BLOCK);
      if (header.length == 0) break;
      count(consumed, header.length);
      if (header.length != BLOCK) throw invalid();
      if (zero(header, 0, BLOCK)) { zeros++; continue; }
      if (zeros != 0) throw invalid();
      verifyHeader(header);
      long size = octal(header, 124, 12);
      int type = header[156] & 0xff;
      if (type == 'L') {
        if (pending != null || ++names > ENTRY_LIMIT || size < 2 || size > BLOCK
            || !"././@LongLink".equals(text(header, 0, 100))) throw invalid();
        byte[] bytes = read(input, (int) size, consumed);
        if (bytes[bytes.length - 1] != 0) throw invalid();
        pending = ascii(bytes, 0, bytes.length - 1);
        padding(input, null, size, consumed);
        continue;
      }
      boolean directory = type == '5';
      if (!(type == 0 || type == '0' || directory) || size > FILE_LIMIT || (directory && size != 0)
          || ++entries > ENTRY_LIMIT) throw invalid();
      String prefix = Arrays.equals(Arrays.copyOfRange(header, 257, 265), USTAR) ? text(header, 345, 155) : "";
      String name = text(header, 0, 100);
      String full = pending != null ? pending : prefix.isEmpty() ? name : prefix + "/" + name;
      pending = null;
      String normalized = path(full, directory, root);
      if (entries == 1 && (!directory || !normalized.equals(root))) throw invalid();
      if (paths.containsKey(normalized)) throw invalid();
      for (int at = normalized.indexOf('/'); at >= 0; at = normalized.indexOf('/', at + 1)) {
        if (Boolean.FALSE.equals(paths.get(normalized.substring(0, at)))) throw invalid();
      }
      if (!directory) {
        String descendant = paths.ceilingKey(normalized + "/");
        if (descendant != null && descendant.startsWith(normalized + "/")) throw invalid();
        expanded += size;
        if (expanded > EXPANDED_LIMIT) throw invalid();
        files++;
      }
      paths.put(normalized, directory);
      output.write(ustar(header, full));
      byte[] buffer = new byte[8192];
      for (long remaining = size; remaining > 0; ) {
        int got = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
        if (got <= 0) throw invalid();
        count(consumed, got); output.write(buffer, 0, got); remaining -= got;
      }
      padding(input, output, size, consumed);
    }
    if (zeros < 2 || pending != null || files == 0) throw invalid();
    output.write(new byte[2 * BLOCK]);
  }

  private static void verifyHeader(byte[] header) throws IOException {
    byte[] magic = Arrays.copyOfRange(header, 257, 265);
    boolean gnu = Arrays.equals(magic, GNU);
    if (!gnu && !Arrays.equals(magic, USTAR)) throw invalid();
    long checksum = 0;
    for (int i = 0; i < BLOCK; i++) checksum += i >= 148 && i < 156 ? 32 : header[i] & 0xff;
    if (checksum != octal(header, 148, 8)) throw invalid();
    for (int offset : new int[] {100, 108, 116}) octal(header, offset, 8);
    octal(header, 136, 12);
    if (!zero(header, 157, 100) || !zero(header, 500, 12)
        || octal(header, 329, 8) != 0 || octal(header, 337, 8) != 0
        || (gnu && !zero(header, 345, 155))) throw invalid();
  }

  private static byte[] ustar(byte[] source, String path) throws IOException {
    String name = path, prefix = "";
    if (path.length() > 100) {
      boolean found = false;
      for (int at = path.lastIndexOf('/'); at > 0; at = path.lastIndexOf('/', at - 1)) {
        if (at <= 155 && path.length() - at - 1 >= 1 && path.length() - at - 1 <= 100) {
          prefix = path.substring(0, at); name = path.substring(at + 1); found = true; break;
        }
      }
      if (!found) throw invalid();
    }
    byte[] result = source.clone();
    Arrays.fill(result, 0, 100, (byte) 0);
    Arrays.fill(result, 345, BLOCK, (byte) 0);
    System.arraycopy(name.getBytes(StandardCharsets.US_ASCII), 0, result, 0, name.length());
    System.arraycopy(prefix.getBytes(StandardCharsets.US_ASCII), 0, result, 345, prefix.length());
    System.arraycopy(USTAR, 0, result, 257, USTAR.length);
    Arrays.fill(result, 148, 156, (byte) ' ');
    long sum = 0; for (byte value : result) sum += value & 0xff;
    byte[] checksum = String.format(Locale.ROOT, "%06o", sum).getBytes(StandardCharsets.US_ASCII);
    if (checksum.length != 6) throw invalid();
    System.arraycopy(checksum, 0, result, 148, checksum.length);
    result[154] = 0; result[155] = ' ';
    return result;
  }

  private static String path(String full, boolean directory, String root) throws IOException {
    String value = directory && full.endsWith("/") ? full.substring(0, full.length() - 1) : full;
    if (value.length() > 256 || !(value.equals(root) || value.startsWith(root + "/"))) throw invalid();
    for (String part : value.split("/", -1)) component(part);
    if (!directory && full.endsWith("/")) throw invalid();
    return value;
  }

  private static void component(String value) throws IOException {
    if (value == null || value.equals(".") || value.equals("..") || !value.matches("[A-Za-z0-9._+@=\\-]+")) throw invalid();
  }

  private static String text(byte[] bytes, int offset, int length) throws IOException {
    int end = offset;
    while (end < offset + length && bytes[end] != 0) end++;
    if (!zero(bytes, end, offset + length - end)) throw invalid();
    return ascii(bytes, offset, end - offset);
  }

  private static String ascii(byte[] bytes, int offset, int length) throws IOException {
    for (int i = offset; i < offset + length; i++) if (bytes[i] < 33 || bytes[i] > 126) throw invalid();
    return new String(bytes, offset, length, StandardCharsets.US_ASCII);
  }

  private static long octal(byte[] bytes, int offset, int length) throws IOException {
    int end = offset + length, at = offset; long value = 0;
    while (at < end && bytes[at] == ' ') at++;
    while (at < end && bytes[at] >= '0' && bytes[at] <= '7') value = value * 8 + bytes[at++] - '0';
    while (at < end && (bytes[at] == 0 || bytes[at] == ' ')) at++;
    if (at != end) throw invalid();
    return value;
  }

  private static byte[] read(InputStream input, int length, long[] consumed) throws IOException {
    byte[] result = input.readNBytes(length); count(consumed, result.length);
    if (result.length != length) throw invalid();
    return result;
  }

  private static void padding(InputStream input, OutputStream output, long size, long[] consumed) throws IOException {
    int length = (int) ((BLOCK - size % BLOCK) % BLOCK);
    byte[] bytes = read(input, length, consumed);
    if (!zero(bytes, 0, length)) throw invalid();
    if (output != null) output.write(bytes);
  }

  private static void count(long[] consumed, long amount) throws IOException {
    consumed[0] += amount;
    if (consumed[0] > ARCHIVE_LIMIT) throw invalid();
  }

  private static boolean zero(byte[] bytes, int offset, int length) {
    for (int i = offset; i < offset + length; i++) if (bytes[i] != 0) return false;
    return true;
  }

  private static IOException invalid() { return new IOException("LIFECYCLE_USTAR_PRODUCER_REFUSED"); }
}
