package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.agentcore.archive.LifecycleUstar;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LifecycleUstarTest {
  private static final String ROOT = "tracepermit-synthetic-operation-lifecycle-0.1.0-SNAPSHOT";
  private static final String LONG = ROOT + "/lib/agentcore-operation-lifecycle-cli-0.1.0-SNAPSHOT.jar";
  @TempDir Path directory;

  @Test void longPathUsesPrefixWithoutChangingPayloadOrEntryMetadata() throws Exception {
    byte[] content = new byte[777]; Arrays.fill(content, (byte) 0xa5);
    byte[] sourceHeader = header(LONG.substring(0, 100), '0', content.length);
    byte[] original = archive(concat(longName(LONG), entry(sourceHeader, content)));
    Path file = write(original);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    LifecycleUstar.normalize(file, ROOT);
    byte[] result = Files.readAllBytes(file);
    byte[] actual = Arrays.copyOfRange(result, 512, 1024);
    assertThat(result).hasSize(original.length - 1024);
    assertThat(text(actual, 345, 155) + "/" + text(actual, 0, 100)).isEqualTo(LONG);
    assertThat(Arrays.copyOfRange(actual, 257, 265)).isEqualTo(new byte[] {'u', 's', 't', 'a', 'r', 0, '0', '0'});
    assertThat(Arrays.copyOfRange(actual, 100, 148)).isEqualTo(Arrays.copyOfRange(sourceHeader, 100, 148));
    assertThat(Arrays.copyOfRange(actual, 265, 345)).isEqualTo(Arrays.copyOfRange(sourceHeader, 265, 345));
    assertThat(actual[156]).isEqualTo((byte) '0');
    assertThat(Arrays.copyOfRange(result, 1024, 1024 + content.length)).isEqualTo(content);
    assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertChecksum(actual);
  }

  @Test void anAlreadyStandardArchiveKeepsItsPathsPayloadAndPermissionFields() throws Exception {
    byte[] original = archive(entry(header(ROOT + "/bin/run", '0', 3), new byte[] {1, 2, 3}));
    Path file = write(original);
    LifecycleUstar.normalize(file, ROOT);
    assertThat(Files.readAllBytes(file)).isEqualTo(original);
    LifecycleUstar.normalize(file, ROOT);
    assertThat(Files.readAllBytes(file)).isEqualTo(original);
  }

  @Test void anExactlyHundredByteNameNeedsNoLongNameRecord() throws Exception {
    String name = ROOT + "/" + "a".repeat(100 - ROOT.length() - 1);
    Path file = write(archive(concat(longName(name), entry(header(name, '0', 1), new byte[] {9}))));
    LifecycleUstar.normalize(file, ROOT);
    byte[] header = Arrays.copyOfRange(Files.readAllBytes(file), 512, 1024);
    assertThat(text(header, 0, 100)).isEqualTo(name);
    assertThat(text(header, 345, 155)).isEmpty();
  }

  @Test void directoryLongNameAndFollowingFileRetainTheirExactPaths() throws Exception {
    String child = ROOT + "/" + "d".repeat(60) + "/";
    byte[] directoryEntry = entry(header(child.substring(0, 100), '5', 0), new byte[0]);
    String filePath = child + "x";
    Path file = write(archive(concat(longName(child), directoryEntry, longName(filePath),
        entry(header(filePath.substring(0, 100), '0', 1), new byte[] {7}))));
    LifecycleUstar.normalize(file, ROOT);
    byte[] bytes = Files.readAllBytes(file);
    for (int offset : new int[] {512, 1024}) {
      byte[] header = Arrays.copyOfRange(bytes, offset, offset + 512);
      assertThat(text(header, 345, 155) + "/" + text(header, 0, 100)).isEqualTo(offset == 512 ? child : filePath);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"2", "1", "K", "x", "g", "3", "6"})
  void linksDevicesAndPaxAreRefusedWithoutReplacingTheInput(String type) throws Exception {
    byte[] bytes = archive(entry(header(ROOT + "/bad", type.charAt(0), 0), new byte[0]));
    refused(bytes);
  }

  @ParameterizedTest @ValueSource(strings = {"traversal", "absolute", "outside", "space", "nonascii", "unrepresentable"})
  void invalidOrUnrepresentableLongPathsAreNotTruncatedOrRenamed(String kind) throws Exception {
    String name = switch (kind) {
      case "traversal" -> ROOT + "/../escape";
      case "absolute" -> "/" + ROOT + "/x";
      case "outside" -> "other/x";
      case "space" -> ROOT + "/has space";
      case "nonascii" -> ROOT + "/é";
      default -> ROOT + "/" + "x".repeat(101);
    };
    refused(archive(concat(longName(name), entry(header(ROOT + "/x", '0', 1), new byte[] {1}))));
  }

  @ParameterizedTest @ValueSource(strings = {"checksum", "binary", "linkname", "device", "reserved", "padding", "truncated", "no-end", "after-end", "gnu-extra"})
  void malformedHeadersPayloadPaddingAndTrailersNeverReplaceTheInput(String kind) throws Exception {
    byte[] h = header(ROOT + "/x", '0', 1);
    switch (kind) {
      case "checksum" -> h[20] ^= 1;
      case "binary" -> { h[124] = (byte) 0x80; checksum(h); }
      case "linkname" -> { h[157] = 'x'; checksum(h); }
      case "device" -> { octal(h, 329, 8, 1); checksum(h); }
      case "reserved" -> { h[500] = 1; checksum(h); }
      case "gnu-extra" -> {
        System.arraycopy(new byte[] {'u','s','t','a','r',' ',' ',0}, 0, h, 257, 8);
        h[345] = '1'; checksum(h);
      }
      default -> { }
    }
    byte[] source = archive(entry(h, new byte[] {4}));
    if (kind.equals("padding")) source[1025] = 1;
    if (kind.equals("truncated")) source = Arrays.copyOf(source, 1535);
    if (kind.equals("no-end")) source = Arrays.copyOf(source, 2048);
    if (kind.equals("after-end")) source = concat(source, header(ROOT + "/late", '0', 0));
    refused(source);
  }

  @Test void duplicatePathsAndFileDirectoryCollisionsAreRefused() throws Exception {
    byte[] file = entry(header(ROOT + "/x", '0', 1), new byte[] {4});
    refused(archive(concat(file, file)));
    refused(archive(concat(file, entry(header(ROOT + "/x/y", '0', 1), new byte[] {5}))));
    refused(archive(concat(entry(header(ROOT + "/x/y", '0', 1), new byte[] {5}), file)));
  }

  @Test void orphanAndRepeatedLongNameRecordsAreRefused() throws Exception {
    refused(archive(longName(LONG)));
    refused(archive(concat(longName(LONG), longName(LONG), entry(header(ROOT + "/x", '0', 1), new byte[] {1}))));
  }

  @Test void aWrongRootAndSymlinkAreRefusedWithoutTouchingTheTarget() throws Exception {
    byte[] original = archive(entry(header(ROOT + "/x", '0', 1), new byte[] {1}));
    Path file = write(original), link = directory.resolve("link.tar");
    assertThatThrownBy(() -> LifecycleUstar.normalize(file, "different")).isInstanceOf(IOException.class);
    Files.createSymbolicLink(link, file);
    assertThatThrownBy(() -> LifecycleUstar.normalize(link, ROOT)).isInstanceOf(IOException.class);
    assertThat(Files.readAllBytes(file)).isEqualTo(original);
  }

  @Test void unsupportedEntrySizeIsRefusedBeforeReadingItsPayload() throws Exception {
    byte[] h = header(ROOT + "/large", '0', 32L * 1024 * 1024 + 1);
    refused(archive(h));
  }

  private void refused(byte[] source) throws Exception {
    Path file = write(source);
    assertThatThrownBy(() -> LifecycleUstar.normalize(file, ROOT)).isInstanceOf(IOException.class);
    assertThat(Files.readAllBytes(file)).isEqualTo(source);
    try (var entries = Files.list(directory)) {
      assertThat(entries.noneMatch(p -> p.getFileName().toString().startsWith(".lifecycle-ustar-"))).isTrue();
    }
  }

  private Path write(byte[] bytes) throws Exception {
    Path file = Files.createTempFile(directory, "input-", ".tar"); Files.write(file, bytes); return file;
  }

  private static byte[] archive(byte[] entries) throws Exception {
    return concat(entry(header(ROOT + "/", '5', 0), new byte[0]), entries, new byte[1024]);
  }

  private static byte[] longName(String name) throws Exception {
    byte[] data = Arrays.copyOf(name.getBytes(StandardCharsets.UTF_8), name.getBytes(StandardCharsets.UTF_8).length + 1);
    byte[] h = header("././@LongLink", 'L', data.length);
    System.arraycopy(new byte[] {'u','s','t','a','r',' ',' ',0}, 0, h, 257, 8); checksum(h);
    return entry(h, data);
  }

  private static byte[] entry(byte[] header, byte[] data) throws Exception {
    return concat(header, data, new byte[(512 - data.length % 512) % 512]);
  }

  private static byte[] header(String name, char type, long size) {
    byte[] h = new byte[512]; byte[] path = name.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(path, 0, h, 0, Math.min(path.length, 100));
    octal(h, 100, 8, type == '5' ? 040755 : 0100755);
    octal(h, 108, 8, 7); octal(h, 116, 8, 11); octal(h, 124, 12, size); octal(h, 136, 12, 123456);
    h[156] = (byte) type; System.arraycopy(new byte[] {'u','s','t','a','r',0,'0','0'}, 0, h, 257, 8);
    System.arraycopy("builder".getBytes(StandardCharsets.US_ASCII), 0, h, 265, 7);
    System.arraycopy("group".getBytes(StandardCharsets.US_ASCII), 0, h, 297, 5);
    checksum(h); return h;
  }

  private static void octal(byte[] header, int offset, int length, long value) {
    byte[] encoded = String.format(Locale.ROOT, "%0" + (length - 1) + "o", value).getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(encoded, 0, header, offset, encoded.length);
  }

  private static void checksum(byte[] h) {
    Arrays.fill(h, 148, 156, (byte) ' '); long sum = 0; for (byte b : h) sum += b & 255;
    byte[] value = String.format(Locale.ROOT, "%06o", sum).getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(value, 0, h, 148, 6); h[154] = 0; h[155] = ' ';
  }

  private static void assertChecksum(byte[] header) {
    long sum = 0; for (int i = 0; i < 512; i++) sum += i >= 148 && i < 156 ? 32 : header[i] & 255;
    assertThat(Long.parseLong(text(header, 148, 7), 8)).isEqualTo(sum);
  }

  private static String text(byte[] bytes, int offset, int length) {
    int end = offset; while (end < offset + length && bytes[end] != 0) end++;
    return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
  }

  private static byte[] concat(byte[]... values) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] value : values) out.write(value); return out.toByteArray();
  }
}
