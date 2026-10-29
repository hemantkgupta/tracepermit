package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.sandbox.eks.rehearsal.OwnedContainer;
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

/** Local OCI plumbing only; no claim about EKS attach, audit or privileged daemon custody. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(240)
final class ContainerPrivateInputQualificationTest {
  private ContainerQualificationEnvironment environment;

  @BeforeAll void prepare() throws Exception { environment = new ContainerQualificationEnvironment(); }

  @Test @Order(1)
  void actualAttachedStdinMustPassTheUnmodifiedDescriptorCheck() throws Exception {
    try (OwnedContainer container = environment.probe("descriptor", false)) {
      var result = container.call(new byte[0], false);
      assertThat(result.exit()).as("CONTAINER_STDIN_PROVENANCE_INCOMPATIBLE_STOP_NO_WORKAROUND").isZero();
      ContainerQualificationEnvironment.fixedOutput(result, 0, "PIPE_ACCEPTED\n");
    }
  }

  @Test @Order(2)
  void nonrootCapabilitiesNoNewPrivilegesReadOnlyRootAndWritableTmpAreObserved() throws Exception {
    try (OwnedContainer container = environment.probe("containment", false)) {
      ContainerQualificationEnvironment.fixedOutput(container.call(new byte[0], false), 0, "CONTAINMENT_VERIFIED\n");
    }
  }

  @ParameterizedTest @ValueSource(strings = {"file", "fifo", "unlinked-fifo"}) @Order(3)
  void fileAndNamedFifoDescriptorsRemainRefused(String mode) throws Exception {
    try (OwnedContainer container = environment.probe(mode, false)) {
      ContainerQualificationEnvironment.fixedOutput(container.call(new byte[0], false), 0, "PIPE_REFUSED\n");
    }
  }

  @Test @Order(4)
  void terminalDescriptorIsRefusedWithoutSendingAnyPrivateInput() throws Exception {
    try (OwnedContainer container = environment.probe("descriptor", true)) {
      var result = container.call(new byte[0], false);
      assertThat(result.exit()).isEqualTo(1);
      assertThat(result.err().length).isZero();
      String output = new String(result.out(), StandardCharsets.US_ASCII);
      // Terminal line discipline may translate LF; trusted application receipts never use a TTY.
      assertThat(output.equals("PIPE_REFUSED\n") || output.equals("PIPE_REFUSED\r\n")).isTrue();
    }
  }

  @Test @Order(5)
  void unchangedParentAndWorkerAcceptValidOfflineFramesWithMandatoryEof() throws Exception {
    try (var fixture = new ContainerLifecycleFixture(false)) {
      LifecycleReceipt result = environment.call("check-config", null, fixture.offlineInput(), false);
      assertThat(result.outcome()).isEqualTo("CONFIG_VALID");
      assertThat(result.exitCode()).isZero();
    }
  }

  @Test @Order(6)
  void trailingBytesAndOversizedDeclaredFramesAreRefusedOffline() throws Exception {
    try (var fixture = new ContainerLifecycleFixture(false)) {
      byte[] valid = fixture.offlineInput();
      byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
      trailing[trailing.length - 1] = 1;
      assertThat(environment.call("check-config", null, trailing, false).outcome()).isEqualTo("INPUT_INVALID");
      Arrays.fill(valid, (byte) 0);
      byte[] oversized = java.nio.ByteBuffer.allocate(4).putInt(65_537).array();
      assertThat(environment.call("check-config", null, oversized, false).outcome()).isEqualTo("INPUT_INVALID");
    }
  }

  @Test @Order(7)
  void withheldEofCannotBecomeConfigValidAndOriginalParentBudgetRemainsFinite() throws Exception {
    try (var fixture = new ContainerLifecycleFixture(false)) {
      long started = System.nanoTime();
      LifecycleReceipt receipt = environment.call("check-config", null, fixture.offlineInput(), true);
      assertThat(receipt.outcome()).isEqualTo("UNKNOWN");
      assertThat(receipt.exitCode()).isEqualTo(1);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(55), Duration.ofSeconds(80));
    }
  }

  @ParameterizedTest @ValueSource(strings = {"flood-out", "flood-err"}) @Order(8)
  void ownedOutputPressureIsCappedAndTheContainerIsRemoved(String mode) throws Exception {
    OwnedContainer container = environment.probe(mode, false);
    try {
      assertThatThrownBy(() -> container.call(new byte[0], false)).hasMessage("CONTAINER_OUTPUT_LIMIT");
    } finally { container.close(); }
    assertThat(container.removalObserved()).isTrue();
  }

  @Test @Order(9)
  void interruptedAttachPreservesInterruptionAndRemovesOnlyTheOwnedContainer() throws Exception {
    try (var fixture = new ContainerLifecycleFixture(false)) {
      OwnedContainer container = environment.application("check-config", null);
      AtomicBoolean interruption = new AtomicBoolean();
      AtomicReference<Throwable> unexpected = new AtomicReference<>();
      Thread caller = new Thread(() -> {
        try { container.call(fixture.offlineInput(), true); unexpected.set(new AssertionError("INTERRUPTION_NOT_OBSERVED")); }
        catch (InterruptedException expected) { interruption.set(Thread.currentThread().isInterrupted()); }
        catch (Throwable failure) { unexpected.set(failure); }
      }, "owned-attach-interruption-test");
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
        if (caller.isAlive()) { caller.interrupt(); caller.join(20_000); }
        container.close();
      }
    }
  }
}
