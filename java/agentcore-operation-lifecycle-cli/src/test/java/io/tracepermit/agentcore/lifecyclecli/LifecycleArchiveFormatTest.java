package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LifecycleArchiveFormatTest {
  @Test @Timeout(30)
  void actualDistributionUsesOnlyStandardHeadersAndRetainsFullLongPaths() throws Exception {
    Path archive = Path.of(System.getProperty("tracepermit.lifecycle.archive"));
    Set<String> paths = new HashSet<>(); int prefixEntries = 0, files = 0, zeros = 0;
    try (InputStream input = Files.newInputStream(archive)) {
      while (true) {
        byte[] header = input.readNBytes(512);
        if (header.length == 0) break;
        assertThat(header).hasSize(512);
        if (Arrays.equals(header, new byte[512])) { zeros++; continue; }
        assertThat(zeros).isZero();
        assertThat(Arrays.copyOfRange(header, 257, 265)).isEqualTo(new byte[] {'u','s','t','a','r',0,'0','0'});
        assertThat((int) header[156]).isIn(0, (int) '0', (int) '5');
        String name = text(header, 0, 100), prefix = text(header, 345, 155);
        String full = prefix.isEmpty() ? name : prefix + "/" + name;
        assertThat(paths.add(full)).isTrue();
        if (!prefix.isEmpty()) prefixEntries++;
        long size = Long.parseLong(text(header, 124, 12).trim(), 8);
        long checksum = 0;
        for (int i = 0; i < 512; i++) checksum += i >= 148 && i < 156 ? 32 : header[i] & 255;
        assertThat(Long.parseLong(text(header, 148, 7).trim(), 8)).isEqualTo(checksum);
        if (header[156] != '5') files++;
        input.skipNBytes(size);
        assertThat(input.readNBytes((int) ((512 - size % 512) % 512)))
            .isEqualTo(new byte[(int) ((512 - size % 512) % 512)]);
      }
    }
    assertThat(zeros).isEqualTo(2);
    assertThat(files).isGreaterThan(2);
    assertThat(prefixEntries).isGreaterThan(0);
    String root = archive.getFileName().toString().replaceFirst("\\.tar$", "");
    assertThat(paths).contains(root + "/bin/tracepermit-synthetic-operation-lifecycle",
        root + "/bin/tracepermit-synthetic-operation-lifecycle.bat",
        root + "/lib/agentcore-operation-lifecycle-cli-0.1.0-SNAPSHOT.jar");
    assertThat(Files.isRegularFile(Path.of(System.getProperty("tracepermit.lifecycle.rawArchive")))).isTrue();
  }

  @Test @Timeout(30)
  void extractedArchiveExcludesTheBuildToolAndPreservesInstalledFailureReceipts() throws Exception {
    try (var installed = new InstalledLifecycleArchive(); var files = Files.list(installed.home.resolve("lib"))) {
      for (Path path : files.toList()) {
        try (var jar = new JarFile(path.toFile())) {
          assertThat(jar.stream()).noneMatch(entry -> entry.getName().startsWith("io/tracepermit/agentcore/archive/"));
        }
      }
      assertThat(installed.call("not-a-command", new byte[0]).outcome()).isEqualTo("USAGE");
      assertThat(installed.call("inspect", new byte[0]).outcome()).isEqualTo("INPUT_INVALID");
    }
  }

  private static String text(byte[] bytes, int offset, int length) {
    int end = offset; while (end < offset + length && bytes[end] != 0) end++;
    return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
  }
}
