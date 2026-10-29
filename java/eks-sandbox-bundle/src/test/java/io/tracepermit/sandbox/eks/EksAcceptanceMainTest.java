package io.tracepermit.sandbox.eks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class EksAcceptanceMainTest {
  @TempDir Path temporary;
  private Path directory;

  @BeforeEach void privateDirectory() throws Exception {
    directory = Files.createTempDirectory(temporary.toRealPath(), "acceptance-");
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
  }

  private Path write(String name, byte[] bytes) throws Exception {
    Path path = directory.resolve(name); Files.write(path, bytes);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); return path;
  }

  private Path plan() throws Exception {
    return write("plan.json", EksAcceptanceKitTest.bytes(EksAcceptanceKitTest.example()
        .put("evidenceNotBefore", Instant.now().minusSeconds(3_600).toString())));
  }

  private int call(String... args) {
    var output = new ByteArrayOutputStream(); int status = EksAcceptanceMain.run(args, new PrintStream(output));
    assertThat(output.toString(StandardCharsets.UTF_8)).matches("EKS_ACCEPTANCE_[A-Z_]+\\R"); return status;
  }

  @Test void usageIsClosedAndHasNoImplicitContextOrInputSelection() {
    assertThat(call()).isEqualTo(64);
    assertThat(call("apply", "plan", "output")).isEqualTo(64);
    assertThat(call("assess", "plan", "snapshot")).isEqualTo(64);
  }

  @Test void prepareAndCatalogWriteOnlyNewOwnerPrivateArtifacts() throws Exception {
    Path plan = plan(), manifest = directory.resolve("manifest.json"), catalog = directory.resolve("catalog.json");
    assertThat(call("prepare", plan.toString(), manifest.toString())).isZero();
    assertThat(call("catalog", plan.toString(), catalog.toString())).isZero();
    assertThat(Files.getPosixFilePermissions(manifest)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(call("prepare", plan.toString(), manifest.toString())).isEqualTo(1);
    assertThat(Files.readString(manifest)).contains("\"suspend\" : true", EksAcceptanceKit.LAUNCHER);
  }

  @Test void assessmentWritesReviewRequiredOrBlockedButNeverAcceptance() throws Exception {
    Path plan = plan(); var parsed = EksAcceptanceKit.parsePlan(Files.readAllBytes(plan));
    var snapshot = EksAcceptanceKitTest.snapshot(parsed, Instant.now());
    Path supplied = write("snapshot.json", EksAcceptanceKitTest.bytes(snapshot));
    Path review = directory.resolve("review.json");
    assertThat(call("assess", plan.toString(), supplied.toString(), review.toString())).isZero();
    var result = EksSandboxBundle.JSON.readTree(Files.readAllBytes(review));
    assertThat(result.path("status").textValue()).isEqualTo("REVIEW_REQUIRED");
    assertThat(result.path("clusterAcceptanceGranted").booleanValue()).isFalse();
    ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.get("posture")).put("agentTokenAutomount", true);
    Path bad = write("snapshot-bad.json", EksAcceptanceKitTest.bytes(snapshot));
    Path blocked = directory.resolve("blocked.json");
    assertThat(call("assess", plan.toString(), bad.toString(), blocked.toString())).isEqualTo(1);
    assertThat(Files.readString(blocked)).contains("BLOCKED");
  }

  @Test void fileSymlinkParentPermissionAndOversizeSubstitutionsHaveFixedRefusal() throws Exception {
    Path plan = plan(), target = directory.resolve("result.json");
    Files.setPosixFilePermissions(plan, PosixFilePermissions.fromString("rw-r--r--"));
    assertThat(call("catalog", plan.toString(), target.toString())).isEqualTo(1);
    Files.setPosixFilePermissions(plan, PosixFilePermissions.fromString("rw-------"));
    Path link = directory.resolve("link.json"); Files.createSymbolicLink(link, plan);
    assertThat(call("catalog", link.toString(), target.toString())).isEqualTo(1);
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertThat(call("catalog", plan.toString(), target.toString())).isEqualTo(1);
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    Path big = write("big.json", new byte[65_537]);
    assertThat(call("catalog", big.toString(), target.toString())).isEqualTo(1);
    assertThat(Files.exists(target)).isFalse();
  }

  @Test void extractedArchiveLauncherWorksOutsideCheckoutWithNoBuildTools() throws Exception {
    Path extraction = Files.createDirectory(directory.resolve("installed"));
    Process tar = new ProcessBuilder("/usr/bin/tar", "-xf", System.getProperty("tracepermit.eks.archive"), "-C", extraction.toString()).start();
    try {
      assertThat(tar.waitFor(10, TimeUnit.SECONDS)).isTrue(); assertThat(tar.exitValue()).isZero();
    } finally {
      if (tar.isAlive()) { tar.destroyForcibly(); assertThat(tar.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
    }
    Path root;
    try (var files = Files.list(extraction)) { var children = files.toList(); assertThat(children).hasSize(1); root = children.get(0); }
    assertThat(Files.readString(root.resolve("examples/acceptance-plan.example.json"))).contains("REQUIRED_OPERATOR_VALUE");
    assertThat(Files.readString(root.resolve("examples/acceptance-snapshot.example.json"))).contains("NOT_RUN");
    Path launcher = root.resolve("bin/tracepermit-eks-acceptance"), plan = plan(), result = directory.resolve("installed-catalog.json");
    assertThat(Files.isExecutable(launcher)).isTrue();
    var builder = new ProcessBuilder(launcher.toString(), "catalog", plan.toString(), result.toString()).directory(extraction.toFile());
    builder.environment().clear(); builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    builder.environment().put("PATH", "/usr/bin:/bin");
    Process process = builder.start();
    try {
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero();
      assertThat(new String(process.getInputStream().readNBytes(513), StandardCharsets.US_ASCII)).isEqualTo("EKS_ACCEPTANCE_PREPARED\n");
      assertThat(process.getErrorStream().readNBytes(513)).isEmpty();
      assertThat(Files.readString(result)).contains("MANUAL_ACCEPTANCE_CATALOG");
    } finally {
      if (process.isAlive()) { process.destroyForcibly(); assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
    }
  }
}
