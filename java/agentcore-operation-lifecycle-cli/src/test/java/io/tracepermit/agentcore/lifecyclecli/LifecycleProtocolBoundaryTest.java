package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LifecycleProtocolBoundaryTest {
  @Test
  void primitiveReadersRejectMalformedAndNonCanonicalValues() throws Exception {
    assertThatThrownBy(() -> LifecycleProtocol.frame(data(0), 4))
        .isInstanceOf(LifecycleFailure.class);
    assertThatThrownBy(() -> LifecycleProtocol.frame(data(5), 4))
        .isInstanceOf(LifecycleFailure.class);
    assertThatThrownBy(() -> LifecycleProtocol.frame(data(2, (byte) 1), 4))
        .isInstanceOf(LifecycleFailure.class);
    assertThat(LifecycleProtocol.frame(data(1, (byte) 7), 4)).containsExactly(7);

    assertThatThrownBy(() -> LifecycleProtocol.object("null".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(LifecycleFailure.class);
    assertThatThrownBy(() -> LifecycleProtocol.object("[]".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(LifecycleFailure.class);
    assertThatThrownBy(() -> LifecycleProtocol.object("{} {}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(LifecycleFailure.class);
    assertThatThrownBy(
            () -> LifecycleProtocol.object("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(LifecycleFailure.class);
    assertThatThrownBy(() -> LifecycleProtocol.utf8(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(LifecycleFailure.class);

    var object = LifecycleProtocol.JSON.createObjectNode().put("required", "value");
    LifecycleProtocol.fields(object, Set.of("required"), Set.of());
    assertThatThrownBy(() -> LifecycleProtocol.fields(null, Set.of(), Set.of()))
        .isInstanceOf(LifecycleFailure.class);
    assertThatThrownBy(() -> LifecycleProtocol.fields(object, Set.of("missing"), Set.of()))
        .isInstanceOf(LifecycleFailure.class);
    assertThatThrownBy(() -> LifecycleProtocol.fields(object, Set.of(), Set.of()))
        .isInstanceOf(LifecycleFailure.class);

    assertThat(LifecycleProtocol.text(object, "required", 5)).isEqualTo("value");
    for (String invalid : new String[] {"", " ", "abcdef", "a\n", "\ud800", "\udc00"}) {
      object.set("text", TextNode.valueOf(invalid));
      assertThatThrownBy(() -> LifecycleProtocol.text(object, "text", 5))
          .isInstanceOf(LifecycleFailure.class);
    }
    object.put("text", 1);
    assertThatThrownBy(() -> LifecycleProtocol.text(object, "text", 5))
        .isInstanceOf(LifecycleFailure.class);

    object.put("number", 2);
    assertThat(LifecycleProtocol.number(object, "number", 1, 2)).isEqualTo(2);
    object.put("number", 0);
    assertThatThrownBy(() -> LifecycleProtocol.number(object, "number", 1, 2))
        .isInstanceOf(LifecycleFailure.class);
    object.put("number", 3);
    assertThatThrownBy(() -> LifecycleProtocol.number(object, "number", 1, 2))
        .isInstanceOf(LifecycleFailure.class);
    object.put("number", 1.5);
    assertThatThrownBy(() -> LifecycleProtocol.number(object, "number", 1, 2))
        .isInstanceOf(LifecycleFailure.class);
  }

  @Test
  void fixedReceiptDecoderFailsClosedOnShapeAndExitMismatches() {
    LifecycleReceipt failure = LifecycleReceipt.failure("not-a-command", "UNKNOWN");
    assertThat(failure.command()).isEqualTo("UNKNOWN");
    assertThat(failure.exitCode()).isOne();
    assertThat(LifecycleReceipt.failure("inspect", "USAGE").exitCode()).isEqualTo(64);
    assertThat(LifecycleReceipt.decode(failure.bytes(), 0).outcome()).isEqualTo("UNKNOWN");
    assertThat(LifecycleReceipt.decode(new byte[] {1}, 1).outcome()).isEqualTo("UNKNOWN");

    var invalidInvariant = LifecycleProtocol.object(failure.bytes());
    invalidInvariant.put("executionEnabled", true);
    assertThat(
            LifecycleReceipt.decode(
                    invalidInvariant.toString().getBytes(StandardCharsets.UTF_8), 1)
                .outcome())
        .isEqualTo("UNKNOWN");

    var malformed = LifecycleProtocol.JSON.createObjectNode();
    malformed.put("schemaVersion", "1.0");
    assertThat(
            LifecycleReceipt.decode(
                    (malformed.toString() + "\n").getBytes(StandardCharsets.UTF_8), 1)
                .outcome())
        .isEqualTo("UNKNOWN");

    assertThatThrownBy(
            () ->
                new LifecycleReceipt(
                    "invalid", "UNKNOWN", null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new LifecycleReceipt(
                    "inspect", "OBSERVED", "id", null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new LifecycleReceipt(
                    "inspect", "UNKNOWN", null, null, null, false, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DataInputStream data(int length, byte... bytes) {
    ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + bytes.length);
    buffer.putInt(length).put(bytes);
    return new DataInputStream(new ByteArrayInputStream(buffer.array()));
  }
}
