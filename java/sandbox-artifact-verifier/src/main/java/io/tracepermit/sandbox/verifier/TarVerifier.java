package io.tracepermit.sandbox.verifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.TreeMap;

/** Strict streaming ustar inventory; archive names are never opened as host paths. */
final class TarVerifier {
  static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
  static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
  static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
  static final int MAX_ENTRIES = 4_096;
  static final int BLOCK = 512;

  private TarVerifier() {}

  enum Result { MATCH, INVALID, MISMATCH }

  static Result verify(Path file, String expected) {
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Result.INVALID;
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream raw = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
           DigestInputStream input = new DigestInputStream(raw, digest)) {
        inventory(input);
        return MessageDigest.isEqual(HexFormat.of().parseHex(expected), digest.digest())
            ? Result.MATCH : Result.MISMATCH;
      }
    } catch (IOException | IllegalArgumentException | NoSuchAlgorithmException failure) {
      return Result.INVALID;
    }
  }

  private static void inventory(InputStream input) throws IOException {
    byte[] header = new byte[BLOCK];
    byte[] buffer = new byte[8_192];
    TreeMap<String, Boolean> paths = new TreeMap<>();
    long bytes = 0;
    long expanded = 0;
    int entries = 0;
    int files = 0;
    int zeros = 0;
    while (true) {
      int count = readBlock(input, header);
      if (count == 0) {
        if (zeros < 2 || files == 0) throw invalid();
        return;
      }
      if (count != BLOCK) throw invalid();
      bytes = addBytes(bytes, BLOCK, MAX_ARCHIVE_BYTES);
      if (allZero(header, 0, BLOCK)) {
        zeros++;
        continue;
      }
      if (zeros != 0 || ++entries > MAX_ENTRIES) throw invalid();
      if (!Arrays.equals(Arrays.copyOfRange(header, 257, 263),
          new byte[] {'u', 's', 't', 'a', 'r', 0})
          || header[263] != '0' || header[264] != '0') throw invalid();
      long checksum = octal(header, 148, 8);
      long sum = 0;
      for (int i = 0; i < BLOCK; i++) sum += i >= 148 && i < 156 ? 32 : header[i] & 0xff;
      if (sum != checksum) throw invalid();
      octal(header, 100, 8); // mode
      octal(header, 108, 8); // uid
      octal(header, 116, 8); // gid
      octal(header, 136, 12); // modification time
      if (!allZero(header, 157, 100) || !allZero(header, 500, 12)
          || octal(header, 329, 8) != 0 || octal(header, 337, 8) != 0) throw invalid();
      int type = header[156] & 0xff;
      boolean directory = type == '5';
      if (!(type == 0 || type == '0' || directory)) throw invalid();
      long size = octal(header, 124, 12);
      if (size > MAX_ENTRY_BYTES || (directory && size != 0)) throw invalid();
      if (!directory) {
        expanded = addBytes(expanded, size, MAX_EXPANDED_BYTES);
        files++;
      }
      String name = text(header, 0, 100);
      String prefix = text(header, 345, 155);
      String path = normalize(prefix.isEmpty() ? name : prefix + "/" + name, directory);
      if (paths.containsKey(path)) throw invalid();
      for (int i = path.indexOf('/'); i >= 0; i = path.indexOf('/', i + 1)) {
        if (Boolean.FALSE.equals(paths.get(path.substring(0, i)))) throw invalid();
      }
      if (!directory) {
        String descendant = paths.ceilingKey(path + "/");
        if (descendant != null && descendant.startsWith(path + "/")) throw invalid();
      }
      paths.put(path, directory);
      long left = size;
      while (left > 0) {
        int got = input.read(buffer, 0, (int) Math.min(left, buffer.length));
        if (got <= 0) throw invalid();
        bytes = addBytes(bytes, got, MAX_ARCHIVE_BYTES);
        left -= got;
      }
      int padding = (int) ((BLOCK - size % BLOCK) % BLOCK);
      while (padding > 0) {
        int got = input.read(buffer, 0, Math.min(padding, buffer.length));
        if (got <= 0 || !allZero(buffer, 0, got)) throw invalid();
        bytes = addBytes(bytes, got, MAX_ARCHIVE_BYTES);
        padding -= got;
      }
    }
  }

  private static int readBlock(InputStream input, byte[] block) throws IOException {
    int count = 0;
    while (count < block.length) {
      int got = input.read(block, count, block.length - count);
      if (got < 0) break;
      if (got == 0) throw invalid();
      count += got;
    }
    return count;
  }

  private static long addBytes(long current, long amount, long maximum) throws IOException {
    if (amount < 0 || amount > maximum - current) throw invalid();
    return current + amount;
  }

  private static long octal(byte[] data, int offset, int length) throws IOException {
    int end = offset + length;
    int at = offset;
    while (at < end && data[at] == ' ') at++;
    long result = 0;
    boolean digits = false;
    while (at < end && data[at] >= '0' && data[at] <= '7') {
      digits = true;
      int digit = data[at++] - '0';
      if (result > (Long.MAX_VALUE - digit) / 8) throw invalid();
      result = result * 8 + digit;
    }
    while (at < end && (data[at] == 0 || data[at] == ' ')) at++;
    if (at != end) throw invalid();
    return digits ? result : 0;
  }

  private static String text(byte[] data, int offset, int length) throws IOException {
    StringBuilder value = new StringBuilder();
    boolean ended = false;
    for (int i = offset; i < offset + length; i++) {
      int ch = data[i] & 0xff;
      if (ch == 0) ended = true;
      else {
        if (ended || ch < 33 || ch > 126) throw invalid();
        value.append((char) ch);
      }
    }
    return value.toString();
  }

  private static String normalize(String name, boolean directory) throws IOException {
    if (directory && name.endsWith("/")) name = name.substring(0, name.length() - 1);
    if (name.isEmpty() || name.length() > 512 || name.startsWith("/") || name.endsWith("/")) {
      throw invalid();
    }
    for (String component : name.split("/", -1)) {
      if (component.isEmpty() || component.equals(".") || component.equals("..")
          || !component.matches("[A-Za-z0-9._+@=\\-]+")) throw invalid();
    }
    return name;
  }

  private static boolean allZero(byte[] bytes, int offset, int length) {
    for (int i = offset; i < offset + length; i++) if (bytes[i] != 0) return false;
    return true;
  }

  private static IOException invalid() { return new IOException(); }
}
