package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OrganizationOperatorArchiveTest {
  @Test
  @Timeout(30)
  void extractedArchiveOutsideCheckoutContainsNoSourceOrRepositoryMetadata() throws Exception {
    Path root = Files.createTempDirectory("tp-organization-operator-installed-");
    try {
      assertThat(
              root.toRealPath()
                  .startsWith(
                      Path.of(System.getProperty("tracepermit.repositoryRoot")).toRealPath()))
          .isFalse();
      Process tar =
          new ProcessBuilder(
                  "/usr/bin/tar",
                  "-xf",
                  System.getProperty("tracepermit.organization.archive"),
                  "-C",
                  root.toString())
              .start();
      assertThat(tar.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(tar.exitValue()).isZero();
      Path home;
      try (var directories = Files.list(root)) {
        home = directories.findFirst().orElseThrow();
      }
      assertThat(
              Files.isExecutable(
                  home.resolve("bin/tracepermit-synthetic-organization-operator")))
          .isTrue();
      try (var paths = Files.walk(home)) {
        var files = paths.filter(Files::isRegularFile).toList();
        assertThat(files).isNotEmpty();
        assertThat(files)
            .noneMatch(
                path -> {
                  String name = path.getFileName().toString();
                  return name.endsWith(".java")
                      || name.endsWith(".kt")
                      || name.equals("build.gradle.kts")
                      || name.equals("settings.gradle.kts")
                      || name.equals("AGENTS.md")
                      || name.equals(".git");
                });
        for (Path jar : files.stream().filter(path -> path.toString().endsWith(".jar")).toList()) {
          try (var archive = new JarFile(jar.toFile())) {
            assertThat(archive.stream())
                .noneMatch(
                    entry ->
                        entry.getName().endsWith(".java")
                            || entry.getName().endsWith(".kt")
                            || entry.getName().contains("agentcore-work"));
            var bootstrap =
                archive.getJarEntry(
                    "io/tracepermit/agentcore/organizationcli/Bootstrap.class");
            if (bootstrap != null) {
              try (var input = archive.getInputStream(bootstrap)) {
                byte[] header = input.readNBytes(8);
                assertThat(header).hasSize(8);
                assertThat(ByteBuffer.wrap(header).getInt()).isEqualTo(0xCAFEBABE);
                assertThat(ByteBuffer.wrap(header).getShort(6)).isEqualTo((short) 52);
              }
            }
          }
        }
      }
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  @Test
  @Timeout(30)
  void installedLauncherRejectsFileInputBeforeStartingAWorker() throws Exception {
    Path root = Files.createTempDirectory("tp-organization-operator-file-input-");
    try {
      Process tar =
          new ProcessBuilder(
                  "/usr/bin/tar",
                  "-xf",
                  System.getProperty("tracepermit.organization.archive"),
                  "-C",
                  root.toString())
              .start();
      assertThat(tar.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(tar.exitValue()).isZero();
      Path home;
      try (var directories = Files.list(root)) {
        home = directories.findFirst().orElseThrow();
      }
      Path input = root.resolve("input.bin");
      Files.write(
          input,
          OrganizationOperatorFixture.frames("{}".getBytes(StandardCharsets.UTF_8)));
      ProcessBuilder builder =
          new ProcessBuilder(
                  home.resolve("bin/tracepermit-synthetic-organization-operator").toString(),
                  "check-config")
              .directory(root.toFile())
              .redirectInput(input.toFile());
      builder.environment().clear();
      builder.environment().put("PATH", "/usr/bin:/bin");
      builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
      Process process = builder.start();
      try {
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        byte[] output =
            process
                .getInputStream()
                .readNBytes(OrganizationOperatorProtocol.RECEIPT_BYTES);
        assertThat(OrganizationOperatorReceipt.decode(output, process.exitValue()).outcome())
            .isEqualTo("INPUT_INVALID");
        assertThat(process.getErrorStream().readAllBytes()).isEmpty();
        assertThat(process.toHandle().children().filter(ProcessHandle::isAlive).toList())
            .isEmpty();
      } finally {
        process.toHandle().destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
      }
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }
}
