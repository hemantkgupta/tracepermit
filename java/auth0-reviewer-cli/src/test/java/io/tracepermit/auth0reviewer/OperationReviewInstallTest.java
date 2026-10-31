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
    Path installed = temp.resolve("installed");
    try (var paths = Files.walk(original)) {
      for (Path path : paths.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
        assertThat(Files.isSymbolicLink(path)).isFalse();
        Path copy = installed.resolve(original.relativize(path));
        if (Files.isDirectory(path)) Files.createDirectories(copy);
        else Files.copy(path,copy,StandardCopyOption.COPY_ATTRIBUTES);
      }
    }
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
    assertThat(agents.getFirst()).contains("destfile=").doesNotContain("append=false","output=tcp","jmx=true");
    var builder = new ProcessBuilder(review.toString(),"check-config").directory(installed.toFile()).redirectErrorStream(true);
    builder.environment().clear();builder.environment().put("PATH","/usr/bin:/bin");
    builder.environment().put("JAVA_HOME",System.getProperty("java.home"));
    builder.environment().put("JAVA_OPTS",agents.getFirst());
    Process p = builder.start();
    try {
      var f = OperationReviewInputTest.fixture();
      try (var out = p.getOutputStream()) {out.write(OperationReviewInputTest.frames(f,OperationReviewInputTest.request(f)));}
      assertThat(p.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
      assertThat(new String(p.getInputStream().readNBytes(4096),StandardCharsets.UTF_8))
          .contains("CONFIG_VALID","\"command\":\"check-config\"","\"executionEnabled\":false")
          .doesNotContain("publicKeySpki","password","REVIEW_APPROVED");
    } finally {if (p.isAlive()) {p.destroyForcibly();assertThat(p.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
  }
}
