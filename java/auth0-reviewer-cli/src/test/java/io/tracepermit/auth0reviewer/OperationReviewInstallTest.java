package io.tracepermit.auth0reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Installed Java-only helper outside the checkout; offline check-config only, never browser/login. */
final class OperationReviewInstallTest {
  @TempDir Path temp;

  @Test void installedReviewAndIdentityLaunchersRemainSeparateAndNoTestFixtureIsShipped() throws Exception {
    Path original = Path.of(System.getProperty("tracepermit.reviewer.install"));
    String originalHash = manifest(original);
    Path installed = temp.resolve("installed");
    try (var paths = Files.walk(original)) {
      for (Path path : paths.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
        assertThat(Files.isSymbolicLink(path)).isFalse();
        Path copy = installed.resolve(original.relativize(path));
        if (Files.isDirectory(path)) Files.createDirectories(copy);
        else Files.copy(path,copy,StandardCopyOption.COPY_ATTRIBUTES);
      }
    }
    assertThat(manifest(installed)).isEqualTo(originalHash);
    assertThat(installed.toRealPath().startsWith(Path.of(System.getProperty("tracepermit.repositoryRoot")).toRealPath())).isFalse();
    Path review = installed.resolve("bin/tracepermit-auth0-operation-review");
    assertThat(Files.isExecutable(review)).isTrue();
    assertThat(Files.isExecutable(installed.resolve("bin/tracepermit-auth0-reviewer"))).isTrue();
    try (var jars = Files.list(installed.resolve("lib"))) {
      for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
        try (var input = new JarFile(jar.toFile())) {
          assertThat(input.stream().map(java.util.jar.JarEntry::getName))
              .noneMatch(n -> n.contains("OrganizationOperatorFixture") || n.contains("OrganizationReviewTestStore")
                  || n.contains("PreparedAuth0QualificationImagesOnly"));
        }
      }
    }
    var agents = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
        .stream().filter(s -> s.startsWith("-javaagent:") && s.contains("jacoco")).toList();
    assertThat(agents).hasSize(1);
    assertThat(agents.getFirst()).contains("destfile=").doesNotContain("append=false","output=tcp","output=none","jmx=true");
    var builder = new ProcessBuilder(review.toString(),"check-config").directory(installed.toFile()).redirectErrorStream(true);
    builder.environment().clear();builder.environment().put("PATH","/usr/bin:/bin");
    builder.environment().put("JAVA_HOME",System.getProperty("java.home"));
    // Gradle serializes file options relative to its test working directory. The
    // installed child has a different cwd; keep coverage in the original test
    // destination, never inside the immutable installation being qualified.
    String agent = absoluteCoverageDestination(agents.getFirst(), Path.of(System.getProperty("user.dir")));
    Path coverage = Path.of(coverageDestination(agent));
    assertThat(coverage.isAbsolute()).isTrue();
    assertThat(coverage.startsWith(installed.toAbsolutePath())).isFalse();
    assertThat(coverage.startsWith(original.toAbsolutePath())).isFalse();
    builder.environment().put("JAVA_OPTS", "\"" + agent.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    Process p = builder.start();
    try {
      var f = OperationReviewInputTest.fixture();
      try (var out = p.getOutputStream()) {out.write(OperationReviewInputTest.frames(f,OperationReviewInputTest.request(f)));}
      assertThat(p.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
      assertThat(new String(p.getInputStream().readNBytes(4096),StandardCharsets.UTF_8))
          .contains("CONFIG_VALID","\"command\":\"check-config\"","\"executionEnabled\":false")
          .doesNotContain("publicKeySpki","password","REVIEW_APPROVED");
      assertThat(Files.isRegularFile(coverage)).isTrue();
      assertThat(Files.size(coverage)).isPositive();
      assertThat(manifest(installed)).isEqualTo(originalHash);
      assertThat(manifest(original)).isEqualTo(originalHash);
      System.out.println("AUTH0_OPERATION_REVIEW_INSTALL_SHA256=" + originalHash);
    } finally {if (p.isAlive()) {p.destroyForcibly();assertThat(p.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
  }

  @Test void relativeCoverageDestinationIsAnchoredWithoutChangingOtherAgentOptions() {
    Path working = temp.toAbsolutePath();
    String relative = "-javaagent:/trusted/jacocoagent.jar=destfile=build/jacoco/test.exec,append=true,output=file,jmx=false";
    String absolute = "-javaagent:/trusted/jacocoagent.jar=destfile="
        + working.resolve("build/jacoco/test.exec") + ",append=true,output=file,jmx=false";
    assertThat(absoluteCoverageDestination(relative, working)).isEqualTo(absolute);
    assertThat(absoluteCoverageDestination(absolute, working.resolve("another-cwd"))).isEqualTo(absolute);
  }

  private static String absoluteCoverageDestination(String agent, Path working) {
    String destination = coverageDestination(agent);
    Path path = Path.of(destination);
    if (!path.isAbsolute()) path = working.resolve(path);
    int start = agent.indexOf("destfile=") + "destfile=".length();
    return agent.substring(0, start) + path.toAbsolutePath().normalize()
        + agent.substring(start + destination.length());
  }

  private static String coverageDestination(String agent) {
    int marker = agent.indexOf("destfile=");
    assertThat(marker).isGreaterThanOrEqualTo(0);
    assertThat(agent.indexOf("destfile=", marker + 1)).isEqualTo(-1);
    int start = marker + "destfile=".length();
    int end = agent.indexOf(',', start);
    String destination = agent.substring(start, end < 0 ? agent.length() : end);
    assertThat(destination).isNotEmpty();
    return destination;
  }

  private static String manifest(Path home) throws Exception {
    var files = new java.util.TreeMap<String,String>();
    try (var paths = Files.walk(home)) {
      for (Path p : paths.filter(Files::isRegularFile).toList()) {
        assertThat(Files.isSymbolicLink(p)).isFalse();
        files.put(home.relativize(p).toString(),java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
      }
    }
    return io.tracepermit.contracts.CanonicalJson.sha256(
        ReviewerConfiguration.JSON.valueToTree(files));
  }
}
