package io.tracepermit.agentcore.httpcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class SessionSupervisorTest {
  @Test
  void acceptsOnlyExactOneOrTwoReceiptLifecycleAndMatchingExit() throws Exception {
    assertResult("valid-check", "check-session", "CONFIG_VALID", 0);
    assertResult("valid-check", "serve-bounded", "UNKNOWN", 1);
    assertResult("valid-serve", "serve-bounded", "SESSION_ENDED", 0);
    assertResult("stderr", "check-session", "UNKNOWN", 1);
    assertResult("malformed", "check-session", "UNKNOWN", 1);
    assertResult("wrong-exit", "check-session", "UNKNOWN", 1);
    assertResult("extra", "serve-bounded", "UNKNOWN", 1);
  }

  @Test
  void silentAndInterruptionIgnoringChildrenAreKilledWithinCleanupReserve() throws Exception {
    long started = System.nanoTime();
    var silentSupervisor = new SessionSupervisor();
    var silentOutput = new ByteArrayOutputStream();
    assertThat(
            silentSupervisor.run(
                child("silent"),
                "serve-bounded",
                silentOutput,
                Duration.ofMillis(150),
                Duration.ofMillis(500)))
        .isEqualTo(1);
    assertThat(silentOutput.size()).isZero();
    assertThat(silentSupervisor.ownedProcess().isAlive()).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));

    var supervisor = new SessionSupervisor();
    var output = new ByteArrayOutputStream();
    Thread runner =
        new Thread(
            () ->
                supervisor.run(
                    child("ready-hang"),
                    "serve-bounded",
                    output,
                    Duration.ofSeconds(20),
                    Duration.ofSeconds(2)));
    runner.start();
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (supervisor.ownedProcess() == null && System.nanoTime() < deadline) Thread.sleep(5);
    assertThat(supervisor.ownedProcess()).isNotNull();
    runner.interrupt();
    runner.join(4_000);
    assertThat(runner.isAlive()).isFalse();
    assertThat(supervisor.ownedProcess().isAlive()).isFalse();
  }

  @Test
  void launchFailureHasOneFixedFinalReceipt() throws Exception {
    var supervisor = new SessionSupervisor();
    var output = new ByteArrayOutputStream();
    int exit =
        supervisor.run(
            new ProcessBuilder("/definitely/missing/tracepermit-java"),
            "check-session",
            output,
            Duration.ofSeconds(1),
            Duration.ofMillis(300));
    assertThat(exit).isEqualTo(1);
    assertThat(readAll(output)).extracting(SessionReceipt::outcome).containsExactly("UNKNOWN");
  }

  private static void assertResult(String mode, String command, String outcome, int exit)
      throws Exception {
    assertResult(mode, command, outcome, exit, Duration.ofSeconds(3));
  }

  private static void assertResult(
      String mode, String command, String outcome, int expectedExit, Duration total)
      throws Exception {
    var supervisor = new SessionSupervisor();
    var output = new ByteArrayOutputStream();
    int exit =
        supervisor.run(
            child(mode), command, output, total, Duration.ofMillis(500));
    var receipts = readAll(output);
    assertThat(exit).isEqualTo(expectedExit);
    assertThat(receipts.get(receipts.size() - 1).outcome()).isEqualTo(outcome);
    assertThat(supervisor.ownedProcess() == null || !supervisor.ownedProcess().isAlive()).isTrue();
  }

  private static ProcessBuilder child(String mode) {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    return new ProcessBuilder(
        java,
        "-cp",
        System.getProperty("tracepermit.httpcli.testClasspath"),
        HostileChild.class.getName(),
        mode);
  }

  private static java.util.List<SessionReceipt> readAll(ByteArrayOutputStream output) {
    var input = new java.io.ByteArrayInputStream(output.toByteArray());
    var receipts = new java.util.ArrayList<SessionReceipt>();
    while (true) {
      var receipt = SessionProtocol.readFrame(input);
      if (receipt.isEmpty()) return receipts;
      receipts.add(receipt.orElseThrow());
    }
  }
}
