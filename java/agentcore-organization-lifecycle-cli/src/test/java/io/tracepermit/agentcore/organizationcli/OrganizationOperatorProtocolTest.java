package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OrganizationOperatorProtocolTest {
  @Test
  @Timeout(30)
  void checkConfigIsOfflineAndRequiresCompleteEofBeforeParsing() {
    var fixture = new OrganizationOperatorFixture();
    byte[] complete = OrganizationOperatorFixture.frames(fixture.configBytes());
    OrganizationOperatorReceipt receipt =
        OrganizationOperatorWorker.execute(new ByteArrayInputStream(complete), "check-config");
    assertThat(receipt.outcome()).isEqualTo("CONFIG_VALID");
    assertThat(receipt.exitCode()).isZero();

    byte[] trailing = java.util.Arrays.copyOf(complete, complete.length + 1);
    trailing[trailing.length - 1] = 1;
    OrganizationOperatorReceipt refused =
        OrganizationOperatorWorker.execute(new ByteArrayInputStream(trailing), "check-config");
    assertThat(refused.outcome()).isEqualTo("INPUT_INVALID");
    assertThat(refused.exitCode()).isOne();
  }

  @Test
  void exactFramesRejectMalformedUtf8DuplicatesCoercionAndUnknownFields() {
    assertThatThrownBy(() -> OrganizationOperatorProtocol.frame(data(0), 4))
        .isInstanceOf(OrganizationFailure.class);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.frame(data(5), 4))
        .isInstanceOf(OrganizationFailure.class);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.frame(data(2, (byte) 1), 4))
        .isInstanceOf(OrganizationFailure.class);
    assertThat(OrganizationOperatorProtocol.frame(data(1, (byte) 7), 4)).containsExactly(7);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.utf8(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(OrganizationFailure.class);
    assertThatThrownBy(
            () ->
                OrganizationOperatorProtocol.object(
                    "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(OrganizationFailure.class);
    assertThatThrownBy(
            () ->
                OrganizationOperatorProtocol.object(
                    "{} {}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(OrganizationFailure.class);

    var exact = OrganizationOperatorProtocol.JSON.createObjectNode().put("required", "value");
    OrganizationOperatorProtocol.fields(exact, Set.of("required"), Set.of("optional"));
    exact.put("unknown", true);
    assertThatThrownBy(
            () ->
                OrganizationOperatorProtocol.fields(
                    exact, Set.of("required"), Set.of("optional")))
        .isInstanceOf(OrganizationFailure.class);
    exact.remove("unknown");
    exact.put("number", 1.5);
    assertThatThrownBy(() -> OrganizationOperatorProtocol.number(exact, "number", 1, 2))
        .isInstanceOf(OrganizationFailure.class);
  }

  @Test
  void commandFramesAreCommandSpecificAndCompactJwtIsRaw() {
    var fixture = new OrganizationOperatorFixture();
    String operationId = "00000000-0000-0000-0000-000000000001";
    byte[] valid =
        OrganizationOperatorFixture.frames(
            fixture.configBytes(),
            fixture.requesterToken(
                OrganizationOperatorFixture.REQUESTER_SUBJECT,
                Set.of("operations.execute")),
            fixture.commandBytes(operationId, "inspect", null));
    try (var input =
        OrganizationOperatorProtocol.read(new ByteArrayInputStream(valid), "inspect")) {
      assertThat(input.authorization()).startsWith("Bearer ").doesNotContain("Bearer Bearer");
      assertThat(input.command().operationId()).isEqualTo(operationId);
    }

    byte[] headerValue = "Bearer a.b.c".getBytes(StandardCharsets.US_ASCII);
    byte[] refused =
        OrganizationOperatorFixture.frames(
            fixture.configBytes(),
            headerValue,
            fixture.commandBytes(operationId, "inspect", null));
    assertThatThrownBy(
            () ->
                OrganizationOperatorProtocol.read(
                    new ByteArrayInputStream(refused), "inspect"))
        .isInstanceOf(OrganizationFailure.class)
        .hasMessage("INPUT_INVALID");
  }

  @Test
  void closedConfigurationRejectsEngineBoundsAndTargetOrRosterSubstitution() {
    var fixture = new OrganizationOperatorFixture();
    assertConfigInvalid(fixture.configuration.deepCopy().put("unexpected", true));
    assertConfigInvalid(fixture.configuration.deepCopy().put("replayCapacity", 65));
    ObjectNodeEngine.mutate(
        fixture.configuration.deepCopy(), value -> value.put("engine", "MARIADB_11_4_13"));

    var sameStore = fixture.configuration.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) sameStore.path("operations"))
        .put("port", sameStore.path("replay").path("port").intValue())
        .put("catalog", sameStore.path("replay").path("catalog").textValue());
    assertConfigInvalid(sameStore);

    for (String field : Set.of("appId", "repositoryId", "ownerId")) {
      var changed = fixture.configuration.deepCopy();
      var target =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              changed.path("organizationTarget");
      target.put(field, target.path(field).longValue() + 1);
      assertConfigInvalid(changed);
    }
    for (String field : Set.of("repository", "head", "headSha")) {
      var changed = fixture.configuration.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("organizationTarget"))
          .put(field, field.equals("headSha") ? "c".repeat(40) : "substituted/value");
      assertConfigInvalid(changed);
    }
    var adapter = fixture.configuration.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) adapter.path("organizationTarget"))
        .put("adapterId", "github-draft-pr-v1");
    assertConfigInvalid(adapter);
    var permission = fixture.configuration.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            permission.path("organizationTarget").path("permissions"))
        .put("issues", "write");
    assertConfigInvalid(permission);
    var roster = fixture.configuration.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            roster.path("reviewerMemberships").get(0))
        .put("groupId", "substituted-reviewers");
    assertConfigInvalid(roster);
  }

  private static void assertConfigInvalid(com.fasterxml.jackson.databind.node.ObjectNode config) {
    OrganizationOperatorReceipt receipt =
        OrganizationOperatorWorker.execute(
            new ByteArrayInputStream(
                OrganizationOperatorFixture.frames(
                    config.toString().getBytes(StandardCharsets.UTF_8))),
            "check-config");
    assertThat(receipt.outcome()).isEqualTo("CONFIG_INVALID");
  }

  private static final class ObjectNodeEngine {
    private ObjectNodeEngine() {}

    static void mutate(
        com.fasterxml.jackson.databind.node.ObjectNode root,
        java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutation) {
      mutation.accept((com.fasterxml.jackson.databind.node.ObjectNode) root.path("replay"));
      assertConfigInvalid(root);
    }
  }

  private static DataInputStream data(int length, byte... bytes) {
    ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + bytes.length);
    buffer.putInt(length).put(bytes);
    return new DataInputStream(new ByteArrayInputStream(buffer.array()));
  }
}
