package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OrganizationArchiveFormatTest {
  @Test
  @Timeout(30)
  void actualRawAndFinalDistributionsHaveIdenticalLogicalInventoryAndStrictFinalHeaders()
      throws Exception {
    Path raw = Path.of(System.getProperty("tracepermit.organization.rawArchive"));
    Path finalized = Path.of(System.getProperty("tracepermit.organization.archive"));
    var comparison = OrganizationArchiveInventory.compare(raw, finalized);

    assertThat(Files.isWritable(raw)).isFalse();
    assertThat(comparison.raw().gnuLongNames()).isGreaterThan(0);
    assertThat(comparison.finalized().gnuLongNames()).isZero();
    assertThat(comparison.finalized().prefixEntries()).isGreaterThan(0);
    assertThat(comparison.finalized().entries()).isEqualTo(comparison.raw().entries());
    assertThat(comparison.finalized().sha256()).isNotEqualTo(comparison.raw().sha256());

    String root = finalized.getFileName().toString().replaceFirst("\\.tar$", "");
    assertThat(comparison.finalized().entries())
        .extracting(OrganizationArchiveInventory.Entry::path)
        .contains(
            root + "/bin/tracepermit-synthetic-organization-operator",
            root + "/bin/tracepermit-synthetic-organization-operator.bat",
            root + "/lib/agentcore-organization-lifecycle-cli-0.1.0-SNAPSHOT.jar");
  }

  @Test
  @Timeout(30)
  void installedRuntimeExcludesBuildToolsAndPreservesStartupFailureBoundaries() throws Exception {
    Path root = Files.createTempDirectory("tp-organization-ustar-installed-");
    try {
      Process extraction =
          new ProcessBuilder(
                  "/usr/bin/tar",
                  "-xf",
                  System.getProperty("tracepermit.organization.archive"),
                  "-C",
                  root.toString())
              .start();
      assertThat(extraction.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(extraction.exitValue()).isZero();
      Path home;
      try (var directories = Files.list(root)) {
        home = directories.findFirst().orElseThrow();
      }
      try (var files = Files.list(home.resolve("lib"))) {
        for (Path jar : files.toList()) {
          assertThat(jar.getFileName().toString())
              .doesNotContain("tracepermit-lifecycle-ustar-tool");
          try (var archive = new JarFile(jar.toFile())) {
            assertThat(archive.stream())
                .noneMatch(
                    entry ->
                        entry.getName().startsWith("io/tracepermit/agentcore/archive/")
                            || entry
                                .getName()
                                .equals(
                                    "io/tracepermit/agentcore/organizationcli/OrganizationArchiveInventory.class"));
          }
        }
      }
      Path launcher = home.resolve("bin/tracepermit-synthetic-organization-operator");
      assertThat(call(launcher, root, "not-a-command").outcome()).isEqualTo("USAGE");
      assertThat(call(launcher, root, "inspect").outcome()).isEqualTo("INPUT_INVALID");
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static OrganizationOperatorReceipt call(Path launcher, Path directory, String command)
      throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(launcher.toString(), command).directory(directory.toFile());
    builder.environment().clear();
    builder.environment().put("PATH", "/usr/bin:/bin");
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    Process process = builder.start();
    try {
      process.getOutputStream().close();
      assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
      byte[] output = process.getInputStream().readNBytes(OrganizationOperatorProtocol.RECEIPT_BYTES);
      assertThat(process.getInputStream().readAllBytes()).isEmpty();
      assertThat(process.getErrorStream().readAllBytes()).isEmpty();
      assertThat(process.toHandle().children().filter(ProcessHandle::isAlive).toList()).isEmpty();
      return OrganizationOperatorReceipt.decode(output, process.exitValue());
    } finally {
      process.toHandle().destroyForcibly();
      process.waitFor(2, TimeUnit.SECONDS);
    }
  }
}
