package io.tracepermit.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class CanonicalJsonPropertiesTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));

  @Test
  void objectInsertionOrderDoesNotChangeCanonicalBytesOrHash() {
    List<String> fields = List.of("alpha", "bravo", "charlie", "delta", "echo", "foxtrot");
    ObjectNode baseline = objectInOrder(fields);
    String expectedCanonical = CanonicalJson.canonicalize(baseline);
    String expectedHash = CanonicalJson.sha256(baseline);
    Random random = new Random(47_221L);

    for (int iteration = 0; iteration < 250; iteration++) {
      List<String> shuffled = new ArrayList<>(fields);
      Collections.shuffle(shuffled, random);
      ObjectNode candidate = objectInOrder(shuffled);
      assertThat(CanonicalJson.canonicalize(candidate)).isEqualTo(expectedCanonical);
      assertThat(CanonicalJson.sha256(candidate)).isEqualTo(expectedHash);
    }
  }

  @Test
  void canonicalNumberBoundariesAndUnsupportedNodesFailPredictably() {
    JsonNode vectors = JsonSupport.read(ROOT.resolve("contracts/vectors/canonicalization-v1.json"));
    for (JsonNode vector : vectors.path("cases")) {
      assertThat(CanonicalJson.canonicalize(vector.path("input")))
          .as(vector.path("name").asText())
          .isEqualTo(vector.path("canonical").asText());
    }
    assertThat(CanonicalJson.canonicalize(JsonSupport.MAPPER.getNodeFactory().numberNode(-0.0d)))
        .isEqualTo("0");
    assertThatThrownBy(
            () -> CanonicalJson.canonicalize(JsonSupport.MAPPER.getNodeFactory().missingNode()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported JSON value");
  }

  @Test
  void actionNormalizationRemovesUnknownAuthorityFieldsBeforeHashing() {
    ObjectNode original =
        (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    ObjectNode injected = original.deepCopy();
    injected.put("authenticated", true);
    ((ObjectNode) injected.path("identity").path("subject")).put("administrator", true);
    ((ObjectNode) injected.path("action").path("target")).put("credential", "not-trusted");

    JsonNode normalized = ActionValidator.validate(injected);
    assertThat(normalized.has("authenticated")).isFalse();
    assertThat(normalized.path("identity").path("subject").has("administrator")).isFalse();
    assertThat(normalized.path("action").path("target").has("credential")).isFalse();
    assertThat(CanonicalJson.sha256(normalized))
        .isEqualTo(CanonicalJson.sha256(ActionValidator.validate(original)));
  }

  @Test
  void verifiedSourceProjectionIsBoundedAndRejectsRawAttestationFields() {
    ObjectNode action =
        (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    ObjectNode source = action.putObject("source");
    source.put("issuer", "tracepermit-endpoint:device-simulated-001");
    source.put("keyId", "endpoint-key-001");
    source.put("keyFingerprint", "sha256:" + "a".repeat(64));
    source.put("verifiedAt", "2030-01-01T00:00:30Z");
    ObjectNode device = source.putObject("device");
    device.put("deviceId", "device-simulated-001");
    device.put("platform", "macos");
    device.put("assurance", "development");
    ObjectNode process = source.putObject("process");
    process.put("executableName", "codex");
    process.put("executableHash", "sha256:" + "b".repeat(64));
    process.put("parentExecutableName", "terminal");
    process.put("startedAt", "2030-01-01T00:00:00Z");
    ObjectNode trigger = source.putObject("triggerEvidence");
    trigger.put("requestedMode", "interactive");
    trigger.put("terminalAttached", true);

    JsonNode normalized = ActionValidator.validate(action);
    assertThat(normalized.path("source").path("keyId").asText()).isEqualTo("endpoint-key-001");
    assertThat(normalized.path("source").has("tokenId")).isFalse();

    source.put("compactAttestation", "must-not-enter-the-action");
    assertThatThrownBy(() -> ActionValidator.validate(action))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("unsupported or missing fields");
  }

  @Test
  void integralNumbersPreserveExactPrecisionWithoutDoubleTruncation() {
    var factory = JsonSupport.MAPPER.getNodeFactory();
    assertThat(CanonicalJson.canonicalizeExactIntegers(factory.numberNode(0L))).isEqualTo("0");
    assertThat(CanonicalJson.canonicalizeExactIntegers(factory.numberNode(42))).isEqualTo("42");
    assertThat(CanonicalJson.canonicalizeExactIntegers(factory.numberNode(-100))).isEqualTo("-100");

    // 2^53 + 1 exceeds IEEE 754 double 53-bit mantissa; doubleValue() loses the least significant bit
    long safeIntegerBoundaryPlusOne = 9007199254740993L;
    assertThat(CanonicalJson.canonicalize(factory.numberNode(safeIntegerBoundaryPlusOne)))
        .isEqualTo("9007199254740992");
    assertThat(CanonicalJson.canonicalizeExactIntegers(factory.numberNode(safeIntegerBoundaryPlusOne)))
        .isEqualTo("9007199254740993");

    assertThat(CanonicalJson.canonicalizeExactIntegers(factory.numberNode(Long.MAX_VALUE)))
        .isEqualTo("9223372036854775807");
    assertThat(CanonicalJson.canonicalizeExactIntegers(factory.numberNode(Long.MIN_VALUE)))
        .isEqualTo("-9223372036854775808");

    java.math.BigInteger largeBigInt =
        new java.math.BigInteger("9999999999999999999999999999999999999999");
    assertThat(CanonicalJson.canonicalizeExactIntegers(factory.numberNode(largeBigInt)))
        .isEqualTo("9999999999999999999999999999999999999999");
  }

  private static ObjectNode objectInOrder(List<String> fields) {
    ObjectNode value = JsonSupport.MAPPER.createObjectNode();
    for (String field : fields) value.put(field, field.length());
    return value;
  }
}
