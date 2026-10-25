package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises actual compiled JARs in a source-free directory and optionally the final archive. */
class PilotArchiveTest {
  @TempDir Path root;
  private PilotTestFiles files;
  private Path distribution;
  @BeforeEach void setup() throws Exception {
    files = new PilotTestFiles(root);
    String installed = System.getProperty("tracepermit.pilotArchiveRoot");
    distribution = Files.createDirectory(root.resolve("source free installed bundle with spaces"));
    if (installed != null) {
      try (var paths = Files.walk(Path.of(installed))) {
        for (Path source : paths.toList()) {
          Path destination = distribution.resolve(Path.of(installed).relativize(source));
          if (Files.isDirectory(source)) Files.createDirectories(destination);
          else Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    } else {
      Path lib = Files.createDirectory(distribution.resolve("lib"));
      var classpath = System.getProperty("tracepermit.pilotRuntimeClasspath").split(File.pathSeparator);
      for (String element : classpath) {
        Path file = Path.of(element);
        if (element.endsWith(".jar")) Files.copy(file, lib.resolve(file.getFileName()));
      }
      Path main = Path.of(System.getProperty("tracepermit.pilotJar")); Files.copy(main, lib.resolve(main.getFileName()));
      Path bin = Files.createDirectory(distribution.resolve("bin"));
      Files.copy(Path.of(System.getProperty("tracepermit.repositoryRoot"), "java/pilot-operator/src/main/scripts/tracepermit-pilot-operator"),
          bin.resolve("tracepermit-pilot-operator"), StandardCopyOption.COPY_ATTRIBUTES);
    }
  }

  @Test void java21OnlyOfflineCheckWorksWithEmptyPathSpacesAndNoSource() throws Exception {
    assertThat(Files.exists(distribution.resolve("java"))).isFalse();
    assertThat(Files.exists(distribution.resolve("node_modules"))).isFalse();
    var result = command(List.of(launcher(), "--check"), true);
    assertThat(result.exit).isZero(); assertThat(result.output).isEqualTo("CHECK_VALID_NON_AUTHORITATIVE\n");
  }

  @Test void syntheticDnsTripwireIsActiveAndOfflineMakesZeroProviderOrDnsCalls() throws Exception {
    var result = command(List.of(java(), "-cp", System.getProperty("tracepermit.pilotTestClasspath"),
        PilotOfflineProbe.class.getName()), false);
    assertThat(result.exit).isZero(); assertThat(result.output).isEqualTo("OFFLINE_ZERO_DNS_ZERO_PROVIDER\n");
  }

  @Test void launcherErasesAmbientJvmOptionsAndNeverEchoesConfiguration() throws Exception {
    files.json.put("verified", PilotTestFiles.CANARY); files.write();
    var result = command(List.of(launcher(), "--check"), true);
    assertThat(result.exit).isEqualTo(2);
    assertThat(result.output).isEqualTo("CONFIGURATION_REJECTED_NON_AUTHORITATIVE\n");
  }

  @Test void noDefaultServiceNoExecutionAndNoSecretArguments() throws Exception {
    for (List<String> args : List.of(List.<String>of(), List.of("--execute"), List.of("--run"),
        List.of("--check", PilotTestFiles.CANARY), List.of("--preflight", "--help"))) {
      var command = new ArrayList<String>(); command.add(launcher()); command.addAll(args);
      var result = command(command, true); assertThat(result.exit).isEqualTo(64);
      assertThat(result.output).isEqualTo("USAGE_REJECTED_NON_AUTHORITATIVE\n");
    }
  }

  @Test void helpHasNoConfigurationOrSensitiveValues() throws Exception {
    var result = command(List.of(launcher(), "--help"), true);
    assertThat(result.exit).isZero(); assertThat(result.output).contains("NON_AUTHORITATIVE", "--check", "--preflight")
        .doesNotContain(PilotTestFiles.CANARY, files.config.toString(), files.key.toString());
  }

  @Test void missingConfigAndMissingJavaAreFixed() throws Exception {
    var builder = builder(List.of(launcher(), "--check"), true);
    builder.environment().remove("TRACEPERMIT_PILOT_CONFIGURATION");
    assertThat(finish(builder).output).isEqualTo("CONFIGURATION_REJECTED_NON_AUTHORITATIVE\n");
    builder = builder(List.of(launcher(), "--check"), true); builder.environment().put("JAVA_HOME", "/synthetic-not-installed");
    var result = finish(builder); assertThat(result.exit).isEqualTo(2);
    assertThat(result.output).isEqualTo("CONFIGURATION_REJECTED_NON_AUTHORITATIVE\n");
  }

  @Test void hostDeadlineAlsoBoundsAStalledJvmStartupAndReapsItsChild() throws Exception {
    Path fakeHome = Files.createDirectories(root.resolve("synthetic stalled JVM/bin"));
    Path fakeJava = fakeHome.resolve("java");
    Files.writeString(fakeJava, "#!/bin/bash\nexec /bin/sleep 90\n");
    assertThat(fakeJava.toFile().setExecutable(true, true)).isTrue();
    var builder = builder(List.of(launcher(), "--check"), false);
    builder.environment().put("JAVA_HOME", fakeHome.getParent().toString());
    long start = System.nanoTime(); var process = builder.start();
    var children = new java.util.HashSet<ProcessHandle>();
    try {
      while (process.isAlive() && Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(40)) < 0) {
        process.descendants().forEach(children::add);
        process.waitFor(100, TimeUnit.MILLISECONDS);
      }
      assertThat(process.isAlive()).isFalse(); assertThat(process.exitValue()).isEqualTo(4);
      assertThat(new String(process.getInputStream().readNBytes(4097), StandardCharsets.UTF_8))
          .isEqualTo("DEADLINE_EXCEEDED_NON_AUTHORITATIVE\n");
      assertThat(children).isNotEmpty(); assertThat(children.stream().anyMatch(ProcessHandle::isAlive)).isFalse();
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(40));
    } finally {
      process.descendants().forEach(child -> child.destroyForcibly());
      if (process.isAlive()) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
      process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
    }
  }

  private String launcher() { return distribution.resolve("bin/tracepermit-pilot-operator").toString(); }
  private static String java() { return Path.of(System.getProperty("java.home"), "bin/java").toString(); }
  private Receipt command(List<String> command, boolean poison) throws Exception { return finish(builder(command, poison)); }
  private ProcessBuilder builder(List<String> command, boolean poison) {
    var builder = new ProcessBuilder(command).directory(distribution.toFile()).redirectErrorStream(true);
    builder.environment().clear(); builder.environment().put("PATH", "/synthetic-empty-path");
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    builder.environment().put("TRACEPERMIT_PILOT_CONFIGURATION", files.config.toString());
    if (poison) for (String name : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
      builder.environment().put(name, "-Dsynthetic.secret=" + PilotTestFiles.CANARY);
    }
    return builder;
  }
  private static Receipt finish(ProcessBuilder builder) throws Exception {
    long start = System.nanoTime(); var process = builder.start();
    try {
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
      byte[] bytes = process.getInputStream().readNBytes(4097); assertThat(bytes.length).isLessThanOrEqualTo(4096);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
      return new Receipt(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
    } finally {
      if (process.isAlive()) { process.destroyForcibly(); assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue(); }
      process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
    }
  }
  private record Receipt(int exit, String output) {}
}
