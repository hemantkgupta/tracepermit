package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Local OCI plumbing only; no provider, deployment or daemon-custody claim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(240)
final class OrganizationContainerPrivateInputQualificationTest {
  private OrganizationContainerQualificationEnvironment environment;

  @BeforeAll
  void prepare() throws Exception {
    environment = new OrganizationContainerQualificationEnvironment();
  }

  @Test
  @Order(1)
  void actualAttachedStdinPassesTheUnmodifiedDescriptorCheckBeforePrivateFrames()
      throws Exception {
    try (OrganizationOwnedContainer container = environment.probe("descriptor", false)) {
      var result = container.call(new byte[0], false);
      assertThat(result.exit())
          .as("ORGANIZATION_CONTAINER_STDIN_PROVENANCE_INCOMPATIBLE_STOP_NO_WORKAROUND")
          .isZero();
      OrganizationContainerQualificationEnvironment.fixedOutput(
          result, 0, "PIPE_ACCEPTED\n");
    }
  }

  @Test
  @Order(2)
  void nonrootCapabilitiesNoNewPrivilegesReadOnlyRootAndWritableTmpAreObserved()
      throws Exception {
    try (OrganizationOwnedContainer container = environment.probe("containment", false)) {
      OrganizationContainerQualificationEnvironment.fixedOutput(
          container.call(new byte[0], false), 0, "CONTAINMENT_VERIFIED\n");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"file", "fifo", "unlinked-fifo"})
  @Order(3)
  void fileAndLinkedAndUnlinkedFifoDescriptorsRemainRefused(String mode) throws Exception {
    try (OrganizationOwnedContainer container = environment.probe(mode, false)) {
      OrganizationContainerQualificationEnvironment.fixedOutput(
          container.call(new byte[0], false), 0, "PIPE_REFUSED\n");
    }
  }

  @Test
  @Order(4)
  void terminalDescriptorIsRefusedWithoutSendingPrivateInput() throws Exception {
    try (OrganizationOwnedContainer container = environment.probe("descriptor", true)) {
      var result = container.call(new byte[0], false);
      assertThat(result.exit()).isEqualTo(1);
      assertThat(result.err()).isEmpty();
      String output = new String(result.out(), StandardCharsets.US_ASCII);
      assertThat(output.equals("PIPE_REFUSED\n") || output.equals("PIPE_REFUSED\r\n"))
          .isTrue();
    }
  }

  @Test
  @Order(5)
  void unchangedParentAndWorkerAcceptValidOfflineConfigurationOnlyAfterEof()
      throws Exception {
    var fixture = new OrganizationOperatorFixture();
    OrganizationOperatorReceipt result =
        environment.call(
            "check-config",
            null,
            OrganizationOperatorFixture.frames(fixture.configBytes()),
            false);
    assertThat(result.outcome()).isEqualTo("CONFIG_VALID");
    assertThat(result.exitCode()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"malformed", "trailing", "oversized"})
  @Order(6)
  void malformedTrailingAndOversizedFramesAreRefusedOffline(String kind) throws Exception {
    var fixture = new OrganizationOperatorFixture();
    byte[] valid = OrganizationOperatorFixture.frames(fixture.configBytes());
    byte[] invalid;
    switch (kind) {
      case "malformed" -> invalid = new byte[] {0, 0, 0};
      case "trailing" -> {
        invalid = Arrays.copyOf(valid, valid.length + 1);
        invalid[invalid.length - 1] = 1;
      }
      case "oversized" -> invalid = ByteBuffer.allocate(4).putInt(65_537).array();
      default -> throw new AssertionError("UNREACHABLE_ORGANIZATION_FRAME_CASE");
    }
    try {
      assertThat(environment.call("check-config", null, invalid, false).outcome())
          .isEqualTo("INPUT_INVALID");
    } finally {
      Arrays.fill(valid, (byte) 0);
      Arrays.fill(invalid, (byte) 0);
    }
  }

  @Test
  @Order(7)
  void withheldEofCannotBecomeConfigValidAndAcceptedParentBudgetRemainsFinite()
      throws Exception {
    var fixture = new OrganizationOperatorFixture();
    long started = System.nanoTime();
    OrganizationOperatorReceipt receipt =
        environment.call(
            "check-config",
            null,
            OrganizationOperatorFixture.frames(fixture.configBytes()),
            true);
    assertThat(receipt.outcome()).isEqualTo("UNKNOWN");
    assertThat(receipt.exitCode()).isEqualTo(1);
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isBetween(Duration.ofSeconds(55), Duration.ofSeconds(80));
  }

  @ParameterizedTest
  @ValueSource(strings = {"flood-out", "flood-err"})
  @Order(8)
  void ownedOutputPressureIsCappedAndTheContainerIsRemoved(String mode) throws Exception {
    OrganizationOwnedContainer container = environment.probe(mode, false);
    try {
      assertThatThrownBy(() -> container.call(new byte[0], false))
          .hasMessage("CONTAINER_OUTPUT_LIMIT");
    } finally {
      container.close();
    }
    assertThat(container.removalObserved()).isTrue();
  }

  @Test
  @Order(9)
  void interruptedAttachPreservesInterruptionAndRemovesOnlyTheOwnedContainer()
      throws Exception {
    var fixture = new OrganizationOperatorFixture();
    OrganizationOwnedContainer container = environment.application("check-config", null);
    AtomicBoolean interruption = new AtomicBoolean();
    AtomicReference<Throwable> unexpected = new AtomicReference<>();
    Thread caller =
        new Thread(
            () -> {
              try {
                container.call(
                    OrganizationOperatorFixture.frames(fixture.configBytes()), true);
                unexpected.set(new AssertionError("ORGANIZATION_INTERRUPTION_NOT_OBSERVED"));
              } catch (InterruptedException expected) {
                interruption.set(Thread.currentThread().isInterrupted());
              } catch (Throwable failure) {
                unexpected.set(failure);
              }
            },
            "owned-organization-attach-interruption-test");
    caller.setDaemon(true);
    try {
      caller.start();
      assertThat(container.inputSent.await(10, TimeUnit.SECONDS)).isTrue();
      caller.interrupt();
      caller.join(20_000);
      assertThat(caller.isAlive()).isFalse();
      assertThat(unexpected.get() == null).as("fixed interruption/owned-cleanup result").isTrue();
      assertThat(interruption.get()).isTrue();
      assertThat(container.removalObserved()).isTrue();
    } finally {
      if (caller.isAlive()) {
        caller.interrupt();
        caller.join(20_000);
      }
      container.close();
    }
  }
}
