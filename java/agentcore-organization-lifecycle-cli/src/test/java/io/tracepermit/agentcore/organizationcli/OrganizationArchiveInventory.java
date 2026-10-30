package io.tracepermit.agentcore.organizationcli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Test-only streaming comparison of one fresh Gradle TAR and its finalized USTAR bytes. */
public final class OrganizationArchiveInventory {
  private static final int BLOCK = 512;
  private static final long ARCHIVE_LIMIT = 256L * 1024 * 1024;
  private static final long ENTRY_LIMIT = 32L * 1024 * 1024;
  private static final int MAX_ENTRIES = 4096;
  private static final byte[] USTAR = {'u', 's', 't', 'a', 'r', 0, '0', '0'};
  private static final byte[] GNU = {'u', 's', 't', 'a', 'r', ' ', ' ', 0};

  private OrganizationArchiveInventory() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("raw final output-directory required");
    Path output = Path.of(args[2]);
    Files.createDirectories(output);
    Comparison comparison = compare(Path.of(args[0]), Path.of(args[1]));
    writeInventory(output.resolve("raw.inventory.tsv"), comparison.raw());
    writeInventory(output.resolve("final.inventory.tsv"), comparison.finalized());
    Files.writeString(
        output.resolve("comparison.properties"),
        "rawSha256="
            + comparison.raw().sha256()
            + "\nfinalSha256="
            + comparison.finalized().sha256()
            + "\nentries="
            + comparison.finalized().entries().size()
            + "\nrawGnuLongNames="
            + comparison.raw().gnuLongNames()
            + "\nfinalPrefixEntries="
            + comparison.finalized().prefixEntries()
            + "\nlogicalInventory=MATCH\n",
        StandardCharsets.UTF_8);
    System.out.println("ORGANIZATION_ARCHIVE_INVENTORY_MATCH");
  }

  static Comparison compare(Path raw, Path finalized) throws Exception {
    Archive rawArchive = read(raw, true);
    Archive finalArchive = read(finalized, false);
    if (rawArchive.gnuLongNames() < 1
        || finalArchive.gnuLongNames() != 0
        || finalArchive.prefixEntries() < 1
        || rawArchive.sha256().equals(finalArchive.sha256())
        || !rawArchive.entries().equals(finalArchive.entries())) {
      throw new IOException("ORGANIZATION_ARCHIVE_INVENTORY_MISMATCH");
    }
    return new Comparison(rawArchive, finalArchive);
  }

  private static Archive read(Path archive, boolean allowGnu) throws Exception {
    if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw invalid();
    MessageDigest archiveDigest = digest();
    List<Entry> entries = new ArrayList<>();
    int gnuLongNames = 0;
    int prefixEntries = 0;
    int zeroBlocks = 0;
    int files = 0;
    long consumed = 0;
    String pendingName = null;
    try (InputStream raw = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS);
        DigestInputStream input = new DigestInputStream(raw, archiveDigest)) {
      while (true) {
        byte[] header = input.readNBytes(BLOCK);
        if (header.length == 0) break;
        consumed = add(consumed, header.length, ARCHIVE_LIMIT);
        if (header.length != BLOCK) throw invalid();
        if (allZero(header, 0, BLOCK)) {
          zeroBlocks++;
          continue;
        }
        if (zeroBlocks != 0 || entries.size() >= MAX_ENTRIES) throw invalid();
        boolean ustar = Arrays.equals(Arrays.copyOfRange(header, 257, 265), USTAR);
        boolean gnu = Arrays.equals(Arrays.copyOfRange(header, 257, 265), GNU);
        if (!ustar && !(allowGnu && gnu)) throw invalid();
        verifyChecksum(header);
        long size = octal(header, 124, 12);
        if (size > ENTRY_LIMIT) throw invalid();
        int type = header[156] & 0xff;
        if (type == 'L') {
          if (!allowGnu
              || !gnu
              || pendingName != null
              || size < 2
              || size > BLOCK
              || !"././@LongLink".equals(text(header, 0, 100))) {
            throw invalid();
          }
          byte[] name = input.readNBytes((int) size);
          consumed = add(consumed, name.length, ARCHIVE_LIMIT);
          if (name.length != size || name[name.length - 1] != 0) throw invalid();
          pendingName = ascii(name, 0, name.length - 1);
          consumed = readPadding(input, size, consumed);
          gnuLongNames++;
          continue;
        }
        boolean directory = type == '5';
        if (!(type == 0 || type == '0' || directory) || (directory && size != 0)) {
          throw invalid();
        }
        String name = text(header, 0, 100);
        String prefix = ustar ? text(header, 345, 155) : "";
        String path =
            pendingName != null ? pendingName : prefix.isEmpty() ? name : prefix + "/" + name;
        pendingName = null;
        if (directory && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) throw invalid();
        if (!prefix.isEmpty()) prefixEntries++;
        MessageDigest payloadDigest = digest();
        byte[] buffer = new byte[8192];
        for (long remaining = size; remaining > 0; ) {
          int read = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
          if (read <= 0) throw invalid();
          consumed = add(consumed, read, ARCHIVE_LIMIT);
          payloadDigest.update(buffer, 0, read);
          remaining -= read;
        }
        consumed = readPadding(input, size, consumed);
        entries.add(
            new Entry(
                path,
                directory ? "directory" : "file",
                octal(header, 100, 8),
                octal(header, 108, 8),
                octal(header, 116, 8),
                octal(header, 136, 12),
                text(header, 265, 32),
                text(header, 297, 32),
                text(header, 157, 100),
                octal(header, 329, 8),
                octal(header, 337, 8),
                size,
                HexFormat.of().formatHex(payloadDigest.digest())));
        if (!directory) files++;
      }
    }
    if (zeroBlocks < 2 || files < 1 || pendingName != null) throw invalid();
    return new Archive(
        List.copyOf(entries),
        gnuLongNames,
        prefixEntries,
        HexFormat.of().formatHex(archiveDigest.digest()));
  }

  private static void writeInventory(Path destination, Archive archive) throws IOException {
    StringBuilder output =
        new StringBuilder(
            "index\tpath\ttype\tmode\tuid\tgid\tmtime\tuser\tgroup\tlink\tdevMajor\tdevMinor\tsize\tpayloadSha256\n");
    for (int index = 0; index < archive.entries().size(); index++) {
      Entry entry = archive.entries().get(index);
      output
          .append(index)
          .append('\t')
          .append(entry.path())
          .append('\t')
          .append(entry.type())
          .append('\t')
          .append(entry.mode())
          .append('\t')
          .append(entry.uid())
          .append('\t')
          .append(entry.gid())
          .append('\t')
          .append(entry.modified())
          .append('\t')
          .append(entry.user())
          .append('\t')
          .append(entry.group())
          .append('\t')
          .append(entry.link())
          .append('\t')
          .append(entry.devMajor())
          .append('\t')
          .append(entry.devMinor())
          .append('\t')
          .append(entry.size())
          .append('\t')
          .append(entry.payloadSha256())
          .append('\n');
    }
    Files.writeString(destination, output, StandardCharsets.UTF_8);
  }

  private static long readPadding(InputStream input, long size, long consumed) throws IOException {
    int length = (int) ((BLOCK - size % BLOCK) % BLOCK);
    byte[] padding = input.readNBytes(length);
    if (padding.length != length || !allZero(padding, 0, padding.length)) throw invalid();
    return add(consumed, padding.length, ARCHIVE_LIMIT);
  }

  private static void verifyChecksum(byte[] header) throws IOException {
    long expected = octal(header, 148, 8);
    long actual = 0;
    for (int index = 0; index < header.length; index++) {
      actual += index >= 148 && index < 156 ? 32 : header[index] & 0xff;
    }
    if (expected != actual) throw invalid();
  }

  private static long octal(byte[] bytes, int offset, int length) throws IOException {
    int end = offset + length;
    int at = offset;
    long value = 0;
    boolean digits = false;
    while (at < end && bytes[at] == ' ') at++;
    while (at < end && bytes[at] >= '0' && bytes[at] <= '7') {
      digits = true;
      int digit = bytes[at++] - '0';
      if (value > (Long.MAX_VALUE - digit) / 8) throw invalid();
      value = value * 8 + digit;
    }
    while (at < end && (bytes[at] == 0 || bytes[at] == ' ')) at++;
    if (at != end) throw invalid();
    return digits ? value : 0;
  }

  private static String text(byte[] bytes, int offset, int length) throws IOException {
    int end = offset;
    while (end < offset + length && bytes[end] != 0) end++;
    if (!allZero(bytes, end, offset + length - end)) throw invalid();
    return ascii(bytes, offset, end - offset);
  }

  private static String ascii(byte[] bytes, int offset, int length) throws IOException {
    for (int index = offset; index < offset + length; index++) {
      if (bytes[index] < 33 || bytes[index] > 126) throw invalid();
    }
    return new String(bytes, offset, length, StandardCharsets.US_ASCII);
  }

  private static boolean allZero(byte[] bytes, int offset, int length) {
    for (int index = offset; index < offset + length; index++) {
      if (bytes[index] != 0) return false;
    }
    return true;
  }

  private static long add(long current, long amount, long maximum) throws IOException {
    if (amount < 0 || amount > maximum - current) throw invalid();
    return current + amount;
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static IOException invalid() {
    return new IOException("ORGANIZATION_ARCHIVE_INVENTORY_INVALID");
  }

  record Comparison(Archive raw, Archive finalized) {}

  record Archive(List<Entry> entries, int gnuLongNames, int prefixEntries, String sha256) {}

  record Entry(
      String path,
      String type,
      long mode,
      long uid,
      long gid,
      long modified,
      String user,
      String group,
      String link,
      long devMajor,
      long devMinor,
      long size,
      String payloadSha256) {}
}
