package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.ContractViolation;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PolicyValidatorTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));

  @Test
  void validPoliciesInRepositoryPassValidation() {
    String[] policyFiles = {
        "policies/aws-web-readonly.json",
        "policies/aws-agentic-demo.json",
        "policies/aws-scheduled-stop-demo.json",
        "policies/aws-production-deletion-demo.json",
        "policies/synthetic-pii-exfil-demo.json",
        "policies/tool-result-iam-demo.json"
    };
    for (String file : policyFiles) {
      JsonNode policy = JsonSupport.read(ROOT.resolve(file));
      JsonNode validated = PolicyValidator.validate(policy);
      assertThat(validated).isNotNull();
      assertThat(validated.path("schemaVersion").asText()).isEqualTo("1.0");
    }
  }

  @Test
  void nonObjectOrNullPolicyFailsValidation() {
    assertThatThrownBy(() -> PolicyValidator.validate(null))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be an object");

    assertThatThrownBy(() -> PolicyValidator.validate(JsonSupport.MAPPER.createArrayNode()))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be an object");
  }

  @Test
  void unknownTopLevelPropertyFailsClosed() {
    ObjectNode policy = validPolicy();
    policy.put("unrecognizedField", "malicious_injection");
    assertThatThrownBy(() -> PolicyValidator.validate(policy))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("unknown property is not permitted");
  }

  @Test
  void non1SchemaVersionFailsClosed() {
    ObjectNode policy = validPolicy();
    policy.put("schemaVersion", "2.0");
    assertThatThrownBy(() -> PolicyValidator.validate(policy))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must equal 1.0");

    policy.remove("schemaVersion");
    assertThatThrownBy(() -> PolicyValidator.validate(policy))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must equal 1.0");
  }

  @Test
  void missingOrBlankRequiredMetadataFails() {
    ObjectNode missingId = validPolicy();
    missingId.put("id", "");
    assertThatThrownBy(() -> PolicyValidator.validate(missingId))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must not be empty");

    ObjectNode invalidVersion = validPolicy();
    invalidVersion.put("version", 0);
    assertThatThrownBy(() -> PolicyValidator.validate(invalidVersion))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be a positive integer");

    ObjectNode blankName = validPolicy();
    blankName.put("name", "  ");
    assertThatThrownBy(() -> PolicyValidator.validate(blankName))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must not be empty");

    ObjectNode blankDesc = validPolicy();
    blankDesc.put("description", "");
    assertThatThrownBy(() -> PolicyValidator.validate(blankDesc))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must not be empty");

    ObjectNode missingEnabled = validPolicy();
    missingEnabled.remove("enabled");
    assertThatThrownBy(() -> PolicyValidator.validate(missingEnabled))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be a boolean");
  }

  @Test
  void invalidDefaultDecisionFails() {
    ObjectNode badOutcome = validPolicy();
    ((ObjectNode) badOutcome.path("defaultDecision")).put("outcome", "bypass");
    assertThatThrownBy(() -> PolicyValidator.validate(badOutcome))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be a valid outcome");

    ObjectNode blankReason = validPolicy();
    ((ObjectNode) blankReason.path("defaultDecision")).put("reasonCode", "");
    assertThatThrownBy(() -> PolicyValidator.validate(blankReason))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must not be empty");

    ObjectNode extraProp = validPolicy();
    ((ObjectNode) extraProp.path("defaultDecision")).put("extra", "value");
    assertThatThrownBy(() -> PolicyValidator.validate(extraProp))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("unknown property is not permitted");
  }

  @Test
  void duplicateRuleIdFails() {
    ObjectNode policy = validPolicy();
    ArrayNode rules = (ArrayNode) policy.path("rules");
    rules.add(rules.get(0).deepCopy());
    assertThatThrownBy(() -> PolicyValidator.validate(policy))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("rule IDs must be unique across the policy document");
  }

  @Test
  void ruleWithUnknownPropertyFails() {
    ObjectNode policy = validPolicy();
    ((ObjectNode) policy.path("rules").get(0)).put("unknownRuleProp", "value");
    assertThatThrownBy(() -> PolicyValidator.validate(policy))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("unknown property is not permitted");
  }

  @Test
  void ruleMatchWithInvalidEnumOrUnknownPropertyFails() {
    ObjectNode badOrigin = validPolicy();
    ((ArrayNode) badOrigin.path("rules").get(0).path("match").path("origins")).add("invalid-origin");
    assertThatThrownBy(() -> PolicyValidator.validate(badOrigin))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("value not permitted");

    ObjectNode badSubjectType = validPolicy();
    ((ObjectNode) badSubjectType.path("rules").get(0).path("match"))
        .putArray("subjectTypes").add("superuser");
    assertThatThrownBy(() -> PolicyValidator.validate(badSubjectType))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("value not permitted");

    ObjectNode badEnv = validPolicy();
    ((ObjectNode) badEnv.path("rules").get(0).path("match"))
        .putArray("environments").add("production_typo");
    assertThatThrownBy(() -> PolicyValidator.validate(badEnv))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("value not permitted");

    ObjectNode badActionClass = validPolicy();
    ((ObjectNode) badActionClass.path("rules").get(0).path("match"))
        .putArray("actionClasses").add("admin_takeover");
    assertThatThrownBy(() -> PolicyValidator.validate(badActionClass))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("value not permitted");

    ObjectNode emptyRequiredSignals = validPolicy();
    ((ObjectNode) emptyRequiredSignals.path("rules").get(0).path("match"))
        .putArray("requiredSignals");
    assertThatThrownBy(() -> PolicyValidator.validate(emptyRequiredSignals))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("requires at least one item");

    ObjectNode badSeverity = validPolicy();
    ((ObjectNode) badSeverity.path("rules").get(0).path("match"))
        .put("minimumSeverity", "extreme");
    assertThatThrownBy(() -> PolicyValidator.validate(badSeverity))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be a valid severity enum");

    ObjectNode extraMatchProp = validPolicy();
    ((ObjectNode) extraMatchProp.path("rules").get(0).path("match"))
        .put("unknownMatchField", "bypass");
    assertThatThrownBy(() -> PolicyValidator.validate(extraMatchProp))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("unknown property is not permitted");
  }

  @Test
  void ruleDecisionValidation() {
    ObjectNode badDecisionOutcome = validPolicy();
    ((ObjectNode) badDecisionOutcome.path("rules").get(0).path("decision"))
        .put("outcome", "auto_permit");
    assertThatThrownBy(() -> PolicyValidator.validate(badDecisionOutcome))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be a valid outcome");

    ObjectNode badReasonCode = validPolicy();
    ((ObjectNode) badReasonCode.path("rules").get(0).path("decision"))
        .put("reasonCode", "   ");
    assertThatThrownBy(() -> PolicyValidator.validate(badReasonCode))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must not be empty");

    ObjectNode blankApprovalGroup = validPolicy();
    ((ObjectNode) blankApprovalGroup.path("rules").get(0).path("decision"))
        .put("approvalGroup", "");
    assertThatThrownBy(() -> PolicyValidator.validate(blankApprovalGroup))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must not be empty");

    ObjectNode validApproval = validPolicy();
    ((ObjectNode) validApproval.path("rules").get(0).path("decision"))
        .put("approvalGroup", "secops-approvers");
    JsonNode validated = PolicyValidator.validate(validApproval);
    assertThat(validated.path("rules").get(0).path("decision").path("approvalGroup").asText())
        .isEqualTo("secops-approvers");
  }

  private static ObjectNode validPolicy() {
    return (ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
  }
}
