package io.tracepermit.sandbox.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TarVerifierTest {
  @TempDir Path directory;
  private Path write(byte[] bytes) throws Exception {
    Path file = directory.resolve("synthetic.tar");
    Files.write(file, bytes);
    return file;
  }
  private void invalid(byte[] bytes) throws Exception {
    assertThat(TarVerifier.verify(write(bytes), "0".repeat(64))).isEqualTo(TarVerifier.Result.INVALID);
  }

  @Test void validArchiveAndDigestAreAccepted() throws Exception {
    byte[] archive = Fixtures.archive(Fixtures.header("bin/run", '0', 0));
    assertThat(TarVerifier.verify(write(archive), Fixtures.digest(archive))).isEqualTo(TarVerifier.Result.MATCH);
  }

  @Test void wrongDigestAndTamperingAreRejected() throws Exception {
    byte[] archive = Fixtures.archive(Fixtures.header("bin/run", '0', 0));
    String digest = Fixtures.digest(archive);
    assertThat(TarVerifier.verify(write(archive), "0".repeat(64))).isEqualTo(TarVerifier.Result.MISMATCH);
    archive[100] = archive[100] == '0' ? (byte) '1' : (byte) '0';
    assertThat(TarVerifier.verify(write(archive), digest)).isEqualTo(TarVerifier.Result.INVALID);
    byte[] content = new byte[512]; content[0] = 'A';
    byte[] payload = Fixtures.archive(Fixtures.header("file", '0', 1), content);
    String original = Fixtures.digest(payload);
    payload[512] = 'B';
    assertThat(TarVerifier.verify(write(payload), original)).isEqualTo(TarVerifier.Result.MISMATCH);
  }

  @Test void malformedHeadersTruncationAndTrailingDataAreRejected() throws Exception {
    byte[] archive = Fixtures.archive(Fixtures.header("file", '0', 0));
    invalid(Arrays.copyOf(archive, 700));
    byte[] bad = archive.clone(); bad[257] = 'x'; invalid(bad);
    byte[] badTrailer = archive.clone(); badTrailer[1024] = 1; invalid(badTrailer);
    byte[] data = Fixtures.archive(Fixtures.header("file", '0', 5)); invalid(data);
    invalid(new byte[1024]);
  }

  @Test void traversalAbsoluteAmbiguityAndDuplicatesAreRejected() throws Exception {
    for (String path : new String[] {"/root", "../escape", "a/../b", "a//b", "a/./b", "a\\b", "a/"}) {
      invalid(Fixtures.archive(Fixtures.header(path, '0', 0)));
    }
    invalid(Fixtures.archive(Fixtures.header("dir/", '5', 0), Fixtures.header("dir", '5', 0)));
    invalid(Fixtures.archive(Fixtures.header("file", '0', 0), Fixtures.header("file/child", '0', 0)));
    invalid(Fixtures.archive(Fixtures.header("file/child", '0', 0), Fixtures.header("file", '0', 0)));
  }

  @Test void linkAndUnsupportedTypesNeverPass() throws Exception {
    for (char type : new char[] {'1', '2', '3', '4', '6', '7', 'x', 'g', 'L', 'S'}) {
      invalid(Fixtures.archive(Fixtures.header("entry", type, 0)));
    }
    byte[] header = Fixtures.header("entry", '0', 0);
    Fixtures.put(header, 157, "target"); Fixtures.checksum(header);
    invalid(Fixtures.archive(header));
    invalid(new byte[] {'P', 'K', 3, 4});
  }

  @Test void entryAndCountBoundsAreEnforced() throws Exception {
    invalid(Fixtures.archive(Fixtures.header("large", '0', TarVerifier.MAX_ENTRY_BYTES + 1)));
    byte[][] headers = new byte[TarVerifier.MAX_ENTRIES + 1][];
    for (int i = 0; i < headers.length; i++) headers[i] = Fixtures.header("file" + i, '0', 0);
    invalid(Fixtures.archive(headers));
  }

  @Test void expandedByteLimitIsEnforcedWithoutLargeHeapAllocation() throws Exception {
    Path file = directory.resolve("expanded.tar");
    byte[] zeros = new byte[8192];
    try (OutputStream out = Files.newOutputStream(file)) {
      for (int i = 0; i < 4; i++) {
        out.write(Fixtures.header("file" + i, '0', TarVerifier.MAX_ENTRY_BYTES));
        for (long written = 0; written < TarVerifier.MAX_ENTRY_BYTES; written += zeros.length) out.write(zeros);
      }
      out.write(Fixtures.header("overflow", '0', 1));
    }
    assertThat(TarVerifier.verify(file, "0".repeat(64))).isEqualTo(TarVerifier.Result.INVALID);
  }

  @Test void archiveByteLimitRejectsSparseTrailingBytes() throws Exception {
    Path file = directory.resolve("bounded.tar");
    try (SeekableByteChannel out = Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      out.write(ByteBuffer.wrap(Fixtures.archive(Fixtures.header("file", '0', 0))));
      out.position(TarVerifier.MAX_ARCHIVE_BYTES);
      out.write(ByteBuffer.wrap(new byte[512]));
    }
    assertThat(TarVerifier.verify(file, "0".repeat(64))).isEqualTo(TarVerifier.Result.INVALID);
  }

  @Test void hostSymlinkIsNeverFollowed() throws Exception {
    Path target = write(Fixtures.archive(Fixtures.header("file", '0', 0)));
    Path link = directory.resolve("link");
    Files.createSymbolicLink(link, target);
    assertThat(TarVerifier.verify(link, "0".repeat(64))).isEqualTo(TarVerifier.Result.INVALID);
  }
}
