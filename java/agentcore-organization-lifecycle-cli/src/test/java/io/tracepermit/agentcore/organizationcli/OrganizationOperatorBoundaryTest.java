package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Permission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class OrganizationOperatorBoundaryTest {
  @Test
  void primitiveReadersRejectMalformedAndNonCanonicalValues() throws Exception {
    assertThatThrownBy(() -> OrganizationOperatorProtocol.frame(data(0), 4))
        .isInstanceOf(OrganizationFailure.class);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.frame(data(5), 4))
        .isInstanceOf(OrganizationFailure.class);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.frame(data(2, (byte) 1), 4))
        .isInstanceOf(OrganizationFailure.class);
    assertThat(OrganizationOperatorProtocol.frame(data(1, (byte) 7), 4)).containsExactly(7);

    assertThatThrownBy(
            () -> OrganizationOperatorProtocol.object("null".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(OrganizationFailure.class);
    assertThatThrownBy(
            () -> OrganizationOperatorProtocol.object("[]".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(OrganizationFailure.class);
    assertThatThrownBy(
            () -> OrganizationOperatorProtocol.object("{} {}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(OrganizationFailure.class);

    var object = OrganizationOperatorProtocol.JSON.createObjectNode().put("required", "value");
    OrganizationOperatorProtocol.fields(object, Set.of("required"), Set.of());
    assertThatThrownBy(() -> OrganizationOperatorProtocol.fields(null, Set.of(), Set.of()))
        .isInstanceOf(OrganizationFailure.class);
    assertThatThrownBy(
            () -> OrganizationOperatorProtocol.fields(object, Set.of("missing"), Set.of()))
        .isInstanceOf(OrganizationFailure.class);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.fields(object, Set.of(), Set.of()))
        .isInstanceOf(OrganizationFailure.class);

    assertThat(OrganizationOperatorProtocol.text(object, "required", 5)).isEqualTo("value");
    for (String invalid : new String[] {"", " ", "abcdef", "a\n", "\ud800", "\udc00"}) {
      object.set("text", TextNode.valueOf(invalid));
      assertThatThrownBy(() -> OrganizationOperatorProtocol.text(object, "text", 5))
          .isInstanceOf(OrganizationFailure.class);
    }
    object.put("text", 1);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.text(object, "text", 5))
        .isInstanceOf(OrganizationFailure.class);

    object.put("number", 2);
    assertThat(OrganizationOperatorProtocol.number(object, "number", 1, 2)).isEqualTo(2);
    object.put("number", 0);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.number(object, "number", 1, 2))
        .isInstanceOf(OrganizationFailure.class);
    object.put("number", 3);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.number(object, "number", 1, 2))
        .isInstanceOf(OrganizationFailure.class);
    object.put("number", 1.5);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.number(object, "number", 1, 2))
        .isInstanceOf(OrganizationFailure.class);
  }

  @Test
  void receiptDecoderAndUnionFailClosedOnEveryBoundaryMismatch() {
    OrganizationOperatorReceipt fallback =
        OrganizationOperatorReceipt.failure("not-a-command", "UNKNOWN");
    assertThat(fallback.command()).isEqualTo("UNKNOWN");
    assertThat(fallback.exitCode()).isOne();
    assertThat(OrganizationOperatorReceipt.failure("UNKNOWN", "USAGE").exitCode()).isEqualTo(64);
    assertThat(OrganizationOperatorReceipt.decode(fallback.bytes(), 0).outcome())
        .isEqualTo("UNKNOWN");
    assertThat(OrganizationOperatorReceipt.decode(new byte[] {1}, 1).outcome())
        .isEqualTo("UNKNOWN");

    var invalidInvariant = OrganizationOperatorProtocol.object(fallback.bytes());
    invalidInvariant.put("executionEnabled", true);
    assertThat(
            OrganizationOperatorReceipt.decode(
                    (invalidInvariant.toString() + "\n").getBytes(StandardCharsets.UTF_8), 1)
                .outcome())
        .isEqualTo("UNKNOWN");

    var malformed = OrganizationOperatorProtocol.JSON.createObjectNode();
    malformed.put("schemaVersion", "1.0");
    assertThat(
            OrganizationOperatorReceipt.decode(
                    (malformed.toString() + "\n").getBytes(StandardCharsets.UTF_8), 1)
                .outcome())
        .isEqualTo("UNKNOWN");

    assertThatThrownBy(
            () ->
                new OrganizationOperatorReceipt(
                    "invalid", "UNKNOWN", null, null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> OrganizationOperatorReceipt.failure("inspect", "USAGE"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> OrganizationOperatorReceipt.failure("UNKNOWN", "INPUT_INVALID"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OrganizationOperatorReceipt(
                    "register", "CREATED", false, "not-a-uuid", null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OrganizationOperatorReceipt(
                    "inspect",
                    "OBSERVED",
                    null,
                    "00000000-0000-0000-0000-000000000001",
                    "INVALID",
                    "pending",
                    false,
                    "invalid",
                    "invalid"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void closedConfigurationAccessorsAndLifecycleReceiptRemainPinned() {
    var fixture = new OrganizationOperatorFixture();
    var configuration = OrganizationOperatorConfig.parse(fixture.configuration);
    assertThat(configuration.replay()).isNotNull();
    assertThat(configuration.operations()).isNotNull();
    assertThat(configuration.replayCapacity()).isEqualTo(64);
    assertThat(configuration.replayDmlBudget()).isEqualTo(Duration.ofSeconds(2));
    assertThat(configuration.scope()).isNotNull();
    assertThat(configuration.expectedProfile()).isNotNull();
    assertThat(configuration.authorityProfile()).isNotNull();
    assertThat(configuration.registrationEnrollment()).isNotNull();
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    assertThat(configuration.requesterVerifier(clock)).isNotNull();
    assertThat(configuration.authority(clock)).isNotNull();
    assertThat(configuration.toString()).isEqualTo("OrganizationOperatorConfig[redacted]");

    OrganizationOperatorReceipt receipt =
        OrganizationOperatorReceipt.state(
            "inspect",
            "OBSERVED",
            "00000000-0000-0000-0000-000000000001",
            "approved",
            "pending",
            false,
            configuration);
    assertThat(OrganizationOperatorReceipt.decode(receipt.bytes(), 0)).isEqualTo(receipt);
  }

  @SuppressWarnings("removal")
  @Test
  void installedEntrypointsEmitOneFixedReceiptBeforeHalting() {
    assertLaunch(args -> OrganizationOperatorCli.main(args), new String[0], "USAGE");
    assertLaunch(
        args -> OrganizationOperatorCli.main(args), new String[] {"inspect"}, "INPUT_INVALID");
    assertLaunch(
        args -> OrganizationOperatorWorker.main(args), new String[] {"inspect"}, "INPUT_INVALID");
  }

  @SuppressWarnings("removal")
  private static void assertLaunch(
      Consumer<String[]> launch, String[] arguments, String expectedOutcome) {
    SecurityManager original = System.getSecurityManager();
    PrintStream saved = System.out;
    var output = new ByteArrayOutputStream();
    System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
    System.setSecurityManager(
        new SecurityManager() {
          @Override
          public void checkPermission(Permission permission) {}

          @Override
          public void checkExit(int status) {
            throw new FixedExit(status);
          }
        });
    try {
      assertThatThrownBy(() -> launch.accept(arguments))
          .isInstanceOfSatisfying(
              FixedExit.class,
              failure -> {
                OrganizationOperatorReceipt receipt =
                    OrganizationOperatorReceipt.decode(output.toByteArray(), failure.status);
                assertThat(receipt.outcome()).isEqualTo(expectedOutcome);
                assertThat(receipt.bytes()).isEqualTo(output.toByteArray());
              });
    } finally {
      System.setSecurityManager(original);
      System.setOut(saved);
    }
  }

  private static DataInputStream data(int length, byte... bytes) {
    ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + bytes.length);
    buffer.putInt(length).put(bytes);
    return new DataInputStream(new ByteArrayInputStream(buffer.array()));
  }

  private static final class FixedExit extends SecurityException {
    private static final long serialVersionUID = 1L;
    private final int status;

    FixedExit(int status) {
      super("FIXED_EXIT");
      this.status = status;
    }
  }
}
