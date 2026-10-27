package io.tracepermit.agentcore.httpcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class ArchiveBoundaryTest {
  private static byte[] validInput() {
    var config =
        HttpCliFixtures.config(
            "POSTGRESQL_16_4", 54_321, "synthetic_user", "synthetic_password");
    return HttpCliFixtures.frames(
        HttpCliFixtures.bytes(config), HttpCliFixtures.bytes(HttpCliFixtures.session(1_000)));
  }

  @Test
  void sourceFreeInventoryJava21BootstrapAndOfflineCheck() throws Exception {
    try (var installed = new InstalledHttpArchive()) {
      assertThat(Runtime.version().feature()).isEqualTo(21);
      Path archive = Path.of(System.getProperty("tracepermit.httpcli.archive"));
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
      assertThat(hash).hasSize(64);
      try (var paths = Files.walk(installed.home)) {
        for (Path path : paths.filter(Files::isRegularFile).toList()) {
          String relative = installed.home.relativize(path).toString();
          assertThat(relative)
              .doesNotMatch(".*(?:\\.java|\\.class|\\.gradle|\\.git|node_modules|src/|test/).*");
          if (relative.contains("agentcore-registration-http-cli-")
              && relative.endsWith(".jar")) {
            try (var jar = new JarFile(path.toFile())) {
              assertThat(
                      jar.stream()
                          .anyMatch(
                              entry ->
                                  entry
                                      .getName()
                                      .matches(
                                          ".*(?:Test|Fixtures|HostileChild|LocalDatabases).*")))
                  .isFalse();
              var bootstrap =
                  jar.getJarEntry("io/tracepermit/agentcore/httpcli/Bootstrap.class");
              assertThat(bootstrap).isNotNull();
              byte[] bytes = jar.getInputStream(bootstrap).readNBytes(8);
              assertThat(((bytes[6] & 255) << 8) | (bytes[7] & 255)).isEqualTo(52);
            }
          }
        }
      }
      assertThat(installed.check(validInput()).outcome()).isEqualTo("CONFIG_VALID");
    }
  }

  @Test
  void regularFileAndUnsupportedCommandRejectWithoutWorker() throws Exception {
    try (var installed = new InstalledHttpArchive()) {
      Path input = Files.createTempFile("tp-http-cli-file-input-", ".bin");
      Files.write(input, validInput());
      try {
        var builder =
            new ProcessBuilder(installed.launcher.toString(), "check-session")
                .directory(installed.root.toFile())
                .redirectInput(input.toFile());
        builder.environment().clear();
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("PATH", "/usr/bin:/bin");
        Process process = builder.start();
        try {
          assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
          assertThat(InstalledHttpArchive.read(process).outcome()).isEqualTo("INPUT_INVALID");
          assertThat(process.exitValue()).isEqualTo(1);
          assertThat(process.descendants().toList()).isEmpty();
        } finally {
          process.toHandle().destroyForcibly();
        }
      } finally {
        Files.deleteIfExists(input);
      }

      Process unsupported = installed.start("execute");
      try {
        unsupported.getOutputStream().close();
        assertThat(InstalledHttpArchive.read(unsupported).outcome()).isEqualTo("USAGE");
        assertThat(unsupported.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(unsupported.exitValue()).isEqualTo(64);
      } finally {
        unsupported.toHandle().destroyForcibly();
      }
    }
  }

  @Test
  void installedParserRejectsMissingExtraTruncatedOversizedAndDuplicateFrames() throws Exception {
    byte[] config =
        HttpCliFixtures.bytes(
            HttpCliFixtures.config(
                "POSTGRESQL_16_4", 54_321, "synthetic_user", "synthetic_password"));
    byte[] session = HttpCliFixtures.bytes(HttpCliFixtures.session(1_000));
    byte[] valid = HttpCliFixtures.frames(config, session);
    for (byte[] malformed :
        java.util.List.of(
            HttpCliFixtures.frames(config),
            HttpCliFixtures.frames(config, session, new byte[] {1}),
            java.util.Arrays.copyOf(valid, valid.length - 1),
            new byte[] {0, 1, 0, 1},
            HttpCliFixtures.frames(
                config,
                "{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
      try (var installed = new InstalledHttpArchive()) {
        assertThat(installed.check(malformed).exitCode()).isNotZero();
      }
    }
  }

  @Test
  void brokenPublicReceiptPipeCannotExitSuccess() throws Exception {
    try (var installed = new InstalledHttpArchive()) {
      Process process = installed.start("check-session");
      try {
        process.getInputStream().close();
        process.getOutputStream().write(validInput());
        process.getOutputStream().close();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isNotZero();
      } finally {
        process.toHandle().destroyForcibly();
      }
    }
  }

  @Test
  void namedAndUnlinkedFifosAreRejectedAtLauncherAndWorkerBoundaries() throws Exception {
    try (var installed = new InstalledHttpArchive()) {
      Path directory = Files.createTempDirectory("tp-http-cli-fifo-");
      Path linked = directory.resolve("linked.fifo");
      Path unlinked = directory.resolve("unlinked.fifo");
      try {
        createFifo(linked);
        createFifo(unlinked);
        assertFifoRejected(
            installed,
            linked,
            false,
            installed.launcher.toString(),
            "check-session");
        assertFifoRejected(
            installed,
            unlinked,
            true,
            installed.launcher.toString(),
            "check-session");

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        assertFifoRejected(
            installed,
            linked,
            false,
            java,
            "-cp",
            System.getProperty("tracepermit.httpcli.testClasspath"),
            InstalledHttpWorker.class.getName(),
            "check-session");
      } finally {
        Files.deleteIfExists(linked);
        Files.deleteIfExists(unlinked);
        Files.deleteIfExists(directory);
      }
    }
  }

  private static void createFifo(Path path) throws Exception {
    Process process = new ProcessBuilder("mkfifo", path.toString()).start();
    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isZero();
  }

  private static void assertFifoRejected(
      InstalledHttpArchive installed,
      Path fifo,
      boolean unlink,
      String... command)
      throws Exception {
    String quoted = java.util.Arrays.stream(command)
        .map(value -> "'" + value.replace("'", "'\\''") + "'")
        .collect(java.util.stream.Collectors.joining(" "));
    String shell = (unlink ? "rm -f \"$1\" && " : "") + "exec " + quoted;
    var builder =
        new ProcessBuilder("/bin/sh", "-c", shell, "sh", fifo.toString())
            .directory(installed.root.toFile())
            .redirectInput(fifo.toFile());
    builder.environment().clear();
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    builder.environment().put("PATH", "/usr/bin:/bin");
    Thread writer =
        InstalledHttpArchive.thread(
            () -> {
              try (var output = new java.io.FileOutputStream(fifo.toFile())) {
                output.write(new byte[] {0, 0, 0, 1, 0});
              } catch (Exception ignored) {
              }
            });
    Process process = builder.start();
    try {
      assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
      assertThat(InstalledHttpArchive.read(process).outcome()).isEqualTo("INPUT_INVALID");
      assertThat(process.exitValue()).isEqualTo(1);
      assertThat(process.getErrorStream().readAllBytes()).isEmpty();
    } finally {
      process.toHandle().destroyForcibly();
      writer.join(1_000);
    }
  }
}
