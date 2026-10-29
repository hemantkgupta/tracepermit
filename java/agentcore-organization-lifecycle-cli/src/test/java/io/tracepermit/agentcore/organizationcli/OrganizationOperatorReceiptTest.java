package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OrganizationOperatorReceiptTest {
  @Test
  void exactUnionPinsRegistrationLifecycleAndFailureShapes() {
    var created =
        OrganizationOperatorReceipt.registration(
            true, "00000000-0000-0000-0000-000000000001");
    assertThat(created.outcome()).isEqualTo("CREATED");
    assertThat(created.created()).isTrue();
    assertThat(created.bytes().length)
        .isLessThanOrEqualTo(OrganizationOperatorProtocol.RECEIPT_BYTES);
    assertThat(OrganizationOperatorReceipt.decode(created.bytes(), 0)).isEqualTo(created);

    var failure = OrganizationOperatorReceipt.failure("register", "REJECTED");
    assertThat(failure.operationId()).isNull();
    assertThat(failure.exitCode()).isOne();
    assertThat(OrganizationOperatorReceipt.failure("UNKNOWN", "USAGE").exitCode()).isEqualTo(64);
    assertThat(OrganizationOperatorReceipt.decode(failure.bytes(), 0).outcome())
        .isEqualTo("UNKNOWN");

    assertThatThrownBy(
            () ->
                new OrganizationOperatorReceipt(
                    "register", "CREATED", null, "id", null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OrganizationOperatorReceipt(
                    "inspect", "OBSERVED", null, "id", null, null, false, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decoderRejectsTypeShapeAndExitSubstitution() {
    byte[] valid = OrganizationOperatorReceipt.failure("inspect", "INPUT_INVALID").bytes();
    var wrongType = OrganizationOperatorProtocol.object(valid);
    wrongType.put("executionEnabled", "false");
    assertThat(
            OrganizationOperatorReceipt.decode(
                    (wrongType.toString() + "\n").getBytes(StandardCharsets.UTF_8), 1)
                .outcome())
        .isEqualTo("UNKNOWN");

    var wrongFlag = OrganizationOperatorProtocol.object(valid);
    wrongFlag.put("automaticRetryAllowed", true);
    assertThat(
            OrganizationOperatorReceipt.decode(
                    (wrongFlag.toString() + "\n").getBytes(StandardCharsets.UTF_8), 1)
                .outcome())
        .isEqualTo("UNKNOWN");
  }
}
