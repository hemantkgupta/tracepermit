package io.tracepermit.auth0reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tracepermit.agentcore.organizationcli.OrganizationOperatorFixture;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real entry point, System.exit, anonymous pipe and EOF; never a valid live-review invocation. */
final class OperationReviewProcessTest {
  @TempDir Path temp;

  private ProcessBuilder child(String mode) {
    var agents = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
        .stream().filter(s -> s.startsWith("-javaagent:") && s.contains("jacoco")).toList();
    assertThat(agents).hasSize(1);
    assertThat(agents.getFirst()).contains("destfile=").doesNotContain("append=false","output=tcp","jmx=true");
    var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
        agents.getFirst(), "-Xmx64m", "-cp", System.getProperty("tracepermit.reviewer.testClasspath"),
        Auth0OperationReviewMain.class.getName(), mode).redirectErrorStream(true);
    builder.environment().clear();builder.environment().put("PATH","/usr/bin:/bin");
    return builder;
  }

  @Test void realAnonymousThreeFrameCheckIsOfflineEvenWhenDatabasePortAcceptsConnections() throws Exception {
    var f = OperationReviewInputTest.fixture();
    try (var sentinel = new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))) {
      sentinel.setSoTimeout(200);
      for (String n : new String[]{"replay","operations"}) {
        ((com.fasterxml.jackson.databind.node.ObjectNode)f.configuration.path(n)).put("port",sentinel.getLocalPort());
      }
      Process p = child("check-config").start();
      try {
        try (var out = p.getOutputStream()) {out.write(OperationReviewInputTest.frames(f,OperationReviewInputTest.request(f)));}
        assertThat(p.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
        String result = new String(p.getInputStream().readNBytes(4096),StandardCharsets.UTF_8);
        assertThat(result).contains("CONFIG_VALID","\"executionEnabled\":false")
            .doesNotContain("publicKeySpki","password",f.reviewerSubject(),"REVIEW_APPROVED");
        org.assertj.core.api.Assertions.assertThatThrownBy(sentinel::accept).isInstanceOf(java.net.SocketTimeoutException.class);
      } finally {stop(p);}
    }
  }

  @Test void regularFileAndUnknownCommandNeverStartLoginOrReview() throws Exception {
    var f = OperationReviewInputTest.fixture();
    Path file = temp.resolve("input");Files.write(file,OperationReviewInputTest.frames(f,OperationReviewInputTest.request(f)));
    for (String mode : new String[]{"review","unknown"}) {
      Process p = child(mode).redirectInput(file.toFile()).start();
      try {
        assertThat(p.waitFor(10,TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isEqualTo(mode.equals("unknown")?64:1);
        assertThat(new String(p.getInputStream().readNBytes(4096),StandardCharsets.UTF_8))
            .contains(mode.equals("unknown")?"USAGE":"INPUT_INVALID").doesNotContain("REVIEW_APPROVED","REVIEWER_VERIFIED");
      } finally {stop(p);}
    }
  }

  @Test void truncatedAndExtraFramesAreRejectedAndMissingEofExpiresBeforeEffects() throws Exception {
    var f = OperationReviewInputTest.fixture();
    for (String kind : new String[]{"truncated","extra","missing-eof"}) {
      Process p = child("check-config").start();
      try {
        var out = new DataOutputStream(p.getOutputStream());
        if (kind.equals("truncated")) {out.writeInt(10);out.writeByte('{');out.close();}
        else {
          out.write(OperationReviewInputTest.frames(f,OperationReviewInputTest.request(f)));
          if (kind.equals("extra")) {out.writeByte(0);out.close();}else out.flush();
        }
        assertThat(p.waitFor(12,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isEqualTo(1);
        assertThat(new String(p.getInputStream().readNBytes(4096),StandardCharsets.UTF_8))
            .contains(kind.equals("missing-eof")?"UNKNOWN":"REVIEW_REJECTED")
            .doesNotContain("CONFIG_VALID","REVIEW_APPROVED");
      } finally {p.getOutputStream().close();stop(p);}
    }
  }
  private static void stop(Process p) throws Exception {
    if (p.isAlive()) {p.destroyForcibly();assertThat(p.waitFor(5,TimeUnit.SECONDS)).isTrue();}
  }
}
