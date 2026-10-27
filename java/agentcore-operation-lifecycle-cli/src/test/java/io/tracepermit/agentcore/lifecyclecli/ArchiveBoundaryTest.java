package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ArchiveBoundaryTest {
  @Test
  @Timeout(30)
  void extractedDistributionContainsNoSourceOrRepositoryMetadata() throws Exception {
    try (var installed = new InstalledLifecycleArchive();
        var paths = Files.walk(installed.home)) {
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
        }
      }
    }
  }
}
