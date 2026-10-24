package io.tracepermit.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.ContractViolation;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PortableContractsTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));

  @Test
  void canonicalizationMatchesSharedGoldenVectors() {
    JsonNode vectors = JsonSupport.read(ROOT.resolve("contracts/vectors/canonicalization-v1.json"));
    assertThat(vectors.path("profile").asText()).isEqualTo(CanonicalJson.PROFILE);
    for (JsonNode item : vectors.path("cases")) {
      assertThat(CanonicalJson.canonicalize(item.path("input"))).as(item.path("name").asText()).isEqualTo(item.path("canonical").asText());
      assertThat(CanonicalJson.sha256(item.path("input"))).isEqualTo(item.path("hash").asText());
    }
  }

  @Test
  void actionValidationMatchesSharedVectors() {
    JsonNode vectors = JsonSupport.read(ROOT.resolve("contracts/vectors/validation-v1.json"));
    for (JsonNode item : vectors.path("cases")) {
      JsonNode action = JsonSupport.read(ROOT.resolve(item.path("fixture").asText()));
      JsonNode mutation = item.path("mutation");
      if (!mutation.isNull() && !mutation.isMissingNode()) applyMutation(action, mutation);
      if (item.has("mutations")) item.path("mutations").forEach(value -> applyMutation(action, value));
      if (item.path("valid").asBoolean()) {
        assertThatCode(() -> ActionValidator.validate(action)).as(item.path("name").asText()).doesNotThrowAnyException();
      } else {
        assertThatThrownBy(() -> ActionValidator.validate(action)).isInstanceOf(ContractViolation.class)
            .hasMessageContaining(item.path("expectedMessage").asText());
      }
    }
  }

  @Test
  void businessOperationValidationMatchesSharedVectors() {
    JsonNode vectors =
        JsonSupport.read(ROOT.resolve("contracts/vectors/business-operation-v1.json"));
    JsonNode operation = vectors.path("operation");
    assertThat(BusinessOperationValidator.validate(operation)).isEqualTo(operation);
    for (JsonNode mutation : vectors.path("invalidMutations")) {
      JsonNode candidate = operation.deepCopy();
      applyMutation(candidate, mutation);
      assertThatThrownBy(() -> BusinessOperationValidator.validate(candidate))
          .as(mutation.path("name").asText())
          .isInstanceOfSatisfying(
              ContractViolation.class,
              violation ->
                  assertThat(violation.path())
                      .isEqualTo(mutation.path("expectedPath").asText()));
    }
  }

  private static void applyMutation(JsonNode action, JsonNode mutation) {
    String path = mutation.path("path").asText();
    ObjectNode parent = (ObjectNode) action.at(path.substring(0, path.lastIndexOf('/')));
    String field = path.substring(path.lastIndexOf('/') + 1);
    if ("remove".equals(mutation.path("operation").asText())) parent.remove(field); else parent.set(field, mutation.path("value"));
  }

  @Test
  void normalizationStripsUnknownFieldsAndRejectsMalformedStructure() {
    JsonNode original = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    ObjectNode injected = original.deepCopy();
    injected.put("authenticated", true);
    ((ObjectNode) injected.path("identity").path("subject")).put("admin", true);
    ((ObjectNode) injected.path("action").path("target")).put("credential", "must-not-survive");
    JsonNode normalized = ActionValidator.validate(injected);
    assertThat(normalized.has("authenticated")).isFalse();
    assertThat(normalized.path("identity").path("subject").has("admin")).isFalse();
    assertThat(normalized.path("action").path("target").has("credential")).isFalse();
    assertThat(CanonicalJson.sha256(normalized)).isEqualTo(CanonicalJson.sha256(ActionValidator.validate(original)));

    ObjectNode invalidEnvironment = original.deepCopy();
    ((ObjectNode) invalidEnvironment.path("action").path("target")).put("environment", "production");
    assertThatThrownBy(() -> ActionValidator.validate(invalidEnvironment)).isInstanceOf(ContractViolation.class);

    ObjectNode invalidArguments = original.deepCopy();
    ((ObjectNode) invalidArguments.path("action")).put("arguments", "not-an-object");
    assertThatThrownBy(() -> ActionValidator.validate(invalidArguments)).isInstanceOf(ContractViolation.class);

    ObjectNode invalidFingerprint = original.deepCopy();
    ((ObjectNode) invalidFingerprint.path("identity").path("evidence").get(0)).put("credentialFingerprint", "secret-value");
    assertThatThrownBy(() -> ActionValidator.validate(invalidFingerprint)).isInstanceOf(ContractViolation.class);

    ObjectNode offsetTimestamp = original.deepCopy();
    offsetTimestamp.put("observedAt", "2026-10-08T10:15:31+00:00");
    assertThatThrownBy(() -> ActionValidator.validate(offsetTimestamp)).isInstanceOf(ContractViolation.class);

    ObjectNode unsupportedUuidVersion = original.deepCopy();
    unsupportedUuidVersion.put("eventId", "018f47a2-f5ce-0a4b-8f54-80e5d3eaf001");
    assertThatThrownBy(() -> ActionValidator.validate(unsupportedUuidVersion)).isInstanceOf(ContractViolation.class);
  }
}
