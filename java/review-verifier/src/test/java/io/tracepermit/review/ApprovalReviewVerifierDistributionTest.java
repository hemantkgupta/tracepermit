package io.tracepermit.review;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

final class ApprovalReviewVerifierDistributionTest {
  private static Path installDirectory;
  private static String runtimeClasspath;
  private static Path approvalReviewVector;

  @TempDir
  Path temporaryDirectory;

  @BeforeAll
  static void locateBuiltArtifacts() {
    installDirectory = Path.of(requiredProperty("tracepermit.reviewVerifierInstallDirectory"));
    runtimeClasspath = requiredProperty("tracepermit.reviewVerifierRuntimeClasspath");
    approvalReviewVector = Path.of(requiredProperty("tracepermit.approvalReviewVector"));
  }

  @Test
  void actualMainClassAndGeneratedLauncherHandlePrivatePathsWithSpaces() throws Exception {
    Path privateDirectory = temporaryDirectory.resolve("private approval records");
    Files.createDirectory(privateDirectory);
    setPrivatePermissions(privateDirectory, "rwx------");
    Path valid = copyGolden(privateDirectory.resolve("valid review record.json"));
    setPrivatePermissions(valid, "rw-------");
    Path malformed = Files.writeString(
        privateDirectory.resolve("malformed review record.json"),
        "{malformed SECRET_CANARY",
        StandardCharsets.UTF_8);
    setPrivatePermissions(malformed, "rw-------");

    ProcessResult mainResult = run(List.of(
        javaExecutable().toString(),
        "-cp",
        runtimeClasspath,
        ApprovalReviewVerifierMain.class.getName(),
        valid.toString()));
    Path launcher = installDirectory.resolve("bin/review-verifier");
    ProcessResult launcherSuccess = run(List.of(launcher.toString(), valid.toString()));
    ProcessResult launcherFailure = run(List.of(launcher.toString(), malformed.toString()));

    assertAll(
        () -> assertSuccess(mainResult),
        () -> assertSuccess(launcherSuccess),
        () -> assertThat(launcherFailure.exitCode()).isEqualTo(5),
        () -> assertThat(launcherFailure.output()).isEmpty(),
        () -> assertThat(launcherFailure.diagnostics())
            .isEqualTo("TRACEPERMIT_APPROVAL_REVIEW_CHECK: MALFORMED_DOCUMENT\n"),
        () -> assertThat(launcherFailure.combined()).doesNotContain(
            malformed.toString(), "SECRET_CANARY", "Exception"));
  }

  @Test
  void generatedLauncherNeedsOnlyJavaOnPath() throws Exception {
    Path input = copyGolden(temporaryDirectory.resolve("path-only review.json"));
    Path launcher = installDirectory.resolve("bin/review-verifier");

    ProcessResult result = run(List.of(launcher.toString(), input.toString()), false);

    assertSuccess(result);
  }

  @Test
  void installDistContainsNoSourcesTestsCredentialsOrGradleRuntime() throws Exception {
    assertThat(installDirectory).isDirectory();
    List<Path> files;
    try (var paths = Files.walk(installDirectory)) {
      files = paths.filter(Files::isRegularFile).toList();
    }

    assertThat(files).isNotEmpty();
    for (Path file : files) {
      String normalized = file.toString().replace('\\', '/').toLowerCase();
      assertThat(normalized)
          .doesNotContain("/src/", "/test/", "credential", "private-key", ".pem", ".p12")
          .doesNotEndWith(".java");
      if (normalized.endsWith(".jar")) {
        try (JarFile jar = new JarFile(file.toFile())) {
          assertThat(jar.stream().map(entry -> entry.getName().toLowerCase()).toList())
              .noneMatch(name -> name.endsWith("test.class") || name.contains("/src/"));
        }
      }
    }
    assertThat(files.stream().map(path -> path.getFileName().toString().toLowerCase()))
        .noneMatch(name -> name.contains("gradle"));
  }

  private ProcessResult run(List<String> command) throws Exception {
    return run(command, true);
  }

  private ProcessResult run(List<String> command, boolean includeJavaHome) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
    Map<String, String> environment = builder.environment();
    environment.clear();
    if (includeJavaHome) {
      environment.put("JAVA_HOME", Path.of(System.getProperty("java.home")).toString());
    }
    environment.put("PATH", javaExecutable().getParent() + ":/usr/bin:/bin");
    environment.put("LANG", "C.UTF-8");
    Process process = builder.start();
    boolean finished = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new AssertionError("Verifier process exceeded its finite test deadline");
    }
    return new ProcessResult(
        process.exitValue(),
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  private Path copyGolden(Path target) throws IOException {
    Files.copy(approvalReviewVector, target);
    return target;
  }

  private static Path javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java");
  }

  private static void setPrivatePermissions(Path path, String permissions) throws IOException {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    } catch (UnsupportedOperationException exception) {
      // The verifier's supported Unix targets exercise this branch; other test file systems may not.
    }
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("Missing test property");
    return value;
  }

  private static void assertSuccess(ProcessResult result) {
    assertAll(
        () -> assertThat(result.exitCode()).isZero(),
        () -> assertThat(result.output()).isEqualTo(ApprovalReviewVerifierMain.SUCCESS + "\n"),
        () -> assertThat(result.diagnostics()).isEmpty());
  }

  private record ProcessResult(int exitCode, String output, String diagnostics) {
    String combined() {
      return output + diagnostics;
    }
  }
}
