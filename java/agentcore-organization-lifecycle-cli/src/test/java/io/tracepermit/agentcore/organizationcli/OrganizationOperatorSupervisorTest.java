package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OrganizationOperatorSupervisorTest {
  @Test
  @Timeout(30)
  void finiteSupervisorAcceptsOnlyACompleteMatchingReceipt() {
    OrganizationOperatorReceipt valid =
        new OrganizationOperatorSupervisor()
            .run(child("valid"), Duration.ofSeconds(5), Duration.ofSeconds(1));
    assertThat(valid.outcome()).isEqualTo("INPUT_INVALID");

    for (String mode : List.of("mismatch", "stderr", "oversize", "invalid")) {
      OrganizationOperatorReceipt refused =
          new OrganizationOperatorSupervisor()
              .run(child(mode), Duration.ofSeconds(5), Duration.ofSeconds(1));
      assertThat(refused.outcome()).isEqualTo("UNKNOWN");
    }

    OrganizationOperatorReceipt startFailure =
        new OrganizationOperatorSupervisor()
            .run(
                new ProcessBuilder("/tracepermit/does-not-exist"),
                Duration.ofSeconds(1),
                Duration.ofMillis(100));
    assertThat(startFailure.outcome()).isEqualTo("UNKNOWN");
    OrganizationOperatorReceipt timeout =
        new OrganizationOperatorSupervisor()
            .run(child("hang"), Duration.ofMillis(100), Duration.ofSeconds(1));
    assertThat(timeout.outcome()).isEqualTo("UNKNOWN");
  }

  @Test
  void boundedReceiptWriterReportsSuccessFailureAndInterruption() {
    var supervisor = new OrganizationOperatorSupervisor();
    supervisor.stop();
    var output = new ByteArrayOutputStream();
    assertThat(
            OrganizationOperatorSupervisor.send(
                output, new byte[] {1, 2}, Duration.ofSeconds(1)))
        .isTrue();
    assertThat(output.toByteArray()).containsExactly(1, 2);

    OutputStream failure =
        new OutputStream() {
          @Override
          public void write(int value) throws java.io.IOException {
            throw new java.io.IOException("synthetic");
          }
        };
    assertThat(
            OrganizationOperatorSupervisor.send(
                failure, new byte[] {1}, Duration.ofSeconds(1)))
        .isFalse();

    Thread.currentThread().interrupt();
    try {
      assertThat(
              OrganizationOperatorSupervisor.send(
                  output, new byte[] {3}, Duration.ofSeconds(1)))
          .isFalse();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static ProcessBuilder child(String mode) {
    return new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        System.getProperty("tracepermit.organization.testClasspath"),
        OrganizationOperatorSupervisorChild.class.getName(),
        mode);
  }
}
