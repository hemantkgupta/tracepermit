package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.ContractViolation;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PolicyTemplateQualificationTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant T0 = Instant.parse("2026-10-16T12:00:00Z");

  private static final String OBSERVER_PATH = "policies/templates/simulator-observer.json";
  private static final String REVIEWED_CHANGE_PATH = "policies/templates/simulator-reviewed-change.json";

  private final PolicyLifecycleService service = new PolicyLifecycleService();

  @Test
  void templatesPassStrictPolicyValidation() {
    JsonNode observer = JsonSupport.read(ROOT.resolve(OBSERVER_PATH));
    JsonNode validatedObserver = PolicyValidator.validate(observer);
    assertThat(validatedObserver.path("schemaVersion").asText()).isEqualTo("1.0");
    assertThat(validatedObserver.path("id").asText()).isEqualTo("tpl-simulator-observer-001");
    assertThat(validatedObserver.path("version").asInt()).isEqualTo(1);
    assertThat(validatedObserver.path("enabled").asBoolean()).isTrue();
    assertThat(validatedObserver.path("defaultDecision").path("outcome").asText()).isEqualTo("block");
    assertThat(validatedObserver.path("defaultDecision").path("reasonCode").asText())
        .isEqualTo("SIMULATOR_OBSERVER_DEFAULT_BLOCK");

    JsonNode reviewedChange = JsonSupport.read(ROOT.resolve(REVIEWED_CHANGE_PATH));
    JsonNode validatedReviewedChange = PolicyValidator.validate(reviewedChange);
    assertThat(validatedReviewedChange.path("schemaVersion").asText()).isEqualTo("1.0");
    assertThat(validatedReviewedChange.path("id").asText()).isEqualTo("tpl-simulator-reviewed-change-001");
    assertThat(validatedReviewedChange.path("version").asInt()).isEqualTo(1);
    assertThat(validatedReviewedChange.path("enabled").asBoolean()).isTrue();
    assertThat(validatedReviewedChange.path("defaultDecision").path("outcome").asText()).isEqualTo("block");
    assertThat(validatedReviewedChange.path("defaultDecision").path("reasonCode").asText())
        .isEqualTo("SIMULATOR_REVIEWED_CHANGE_DEFAULT_BLOCK");

    // Stable distinct IDs
    assertThat(validatedObserver.path("id").asText())
        .isNotEqualTo(validatedReviewedChange.path("id").asText());
  }

  @Test
  void matrixCasesExecuteAgainstRealSimulationApiWithExactExpectations() {
    JsonNode matrix = JsonSupport.read(ROOT.resolve("fixtures/policy-templates/matrix.json"));
    JsonNode observerPolicy = JsonSupport.read(ROOT.resolve(OBSERVER_PATH));
    JsonNode reviewedChangePolicy = JsonSupport.read(ROOT.resolve(REVIEWED_CHANGE_PATH));

    ArrayNode cases = (ArrayNode) matrix.path("cases");
    assertThat(cases).isNotEmpty();

    for (JsonNode testCase : cases) {
      String caseId = testCase.path("id").asText();
      String fixturePath = testCase.path("fixture").asText();
      JsonNode action = JsonSupport.read(ROOT.resolve(fixturePath));

      // 1. Evaluate against Simulator Observer template
      JsonNode observerExpectation = testCase.path("expectations").path(OBSERVER_PATH);
      PolicySimulationResult observerResult = service.simulate(observerPolicy, action, T0);
      assertThat(observerResult.dryRun()).as("Case %s: dryRun on observer", caseId).isTrue();
      assertThat(observerResult.policyId()).as("Case %s: policyId on observer", caseId)
          .isEqualTo("tpl-simulator-observer-001");
      assertThat(observerResult.version()).as("Case %s: version on observer", caseId).isEqualTo(1);
      assertThat(observerResult.outcome()).as("Case %s: outcome on observer", caseId)
          .isEqualTo(observerExpectation.path("outcome").asText());
      assertThat(observerResult.reasonCode()).as("Case %s: reasonCode on observer", caseId)
          .isEqualTo(observerExpectation.path("reasonCode").asText());
      if (observerExpectation.path("matchedRuleId").isNull()) {
        assertThat(observerResult.matchedRuleId()).as("Case %s: matchedRuleId on observer", caseId).isNull();
      } else {
        assertThat(observerResult.matchedRuleId()).as("Case %s: matchedRuleId on observer", caseId)
            .isEqualTo(observerExpectation.path("matchedRuleId").asText());
      }
      assertThat(observerResult.simulatedAt()).isEqualTo(T0);

      // 2. Evaluate against Simulator Reviewed Change template
      JsonNode reviewedExpectation = testCase.path("expectations").path(REVIEWED_CHANGE_PATH);
      PolicySimulationResult reviewedResult = service.simulate(reviewedChangePolicy, action, T0);
      assertThat(reviewedResult.dryRun()).as("Case %s: dryRun on reviewed-change", caseId).isTrue();
      assertThat(reviewedResult.policyId()).as("Case %s: policyId on reviewed-change", caseId)
          .isEqualTo("tpl-simulator-reviewed-change-001");
      assertThat(reviewedResult.version()).as("Case %s: version on reviewed-change", caseId).isEqualTo(1);
      assertThat(reviewedResult.outcome()).as("Case %s: outcome on reviewed-change", caseId)
          .isEqualTo(reviewedExpectation.path("outcome").asText());
      assertThat(reviewedResult.reasonCode()).as("Case %s: reasonCode on reviewed-change", caseId)
          .isEqualTo(reviewedExpectation.path("reasonCode").asText());
      if (reviewedExpectation.path("matchedRuleId").isNull()) {
        assertThat(reviewedResult.matchedRuleId()).as("Case %s: matchedRuleId on reviewed-change", caseId).isNull();
      } else {
        assertThat(reviewedResult.matchedRuleId()).as("Case %s: matchedRuleId on reviewed-change", caseId)
            .isEqualTo(reviewedExpectation.path("matchedRuleId").asText());
      }
      if (reviewedExpectation.path("approvalGroup").isNull()) {
        assertThat(reviewedResult.approvalGroup()).as("Case %s: approvalGroup on reviewed-change", caseId).isNull();
      } else {
        assertThat(reviewedResult.approvalGroup()).as("Case %s: approvalGroup on reviewed-change", caseId)
            .isEqualTo(reviewedExpectation.path("approvalGroup").asText());
      }
      assertThat(reviewedResult.simulatedAt()).isEqualTo(T0);
    }
  }

  @Test
  void dryRunSimulationPreservesImmutabilityAndLeavesNoStoredState() {
    JsonNode observerPolicy = JsonSupport.read(ROOT.resolve(OBSERVER_PATH));
    JsonNode action = JsonSupport.read(ROOT.resolve("fixtures/policy-templates/cli-human-staging-log-read.json"));

    JsonNode observerCopy = observerPolicy.deepCopy();
    JsonNode actionCopy = action.deepCopy();

    PolicySimulationResult result = service.simulate(observerPolicy, action, T0);

    // Invariant 1: Result is dry-run
    assertThat(result.dryRun()).isTrue();
    assertThat(result.outcome()).isEqualTo("allow");
    assertThat(result.policyId()).isEqualTo("tpl-simulator-observer-001");
    assertThat(result.version()).isEqualTo(1);
    assertThat(result.simulatedAt()).isEqualTo(T0);

    // Invariant 2: Candidate policy input document was not mutated
    assertThat(observerPolicy).isEqualTo(observerCopy);

    // Invariant 3: Canonical action event document was not mutated
    assertThat(action).isEqualTo(actionCopy);

    // Invariant 4: No staging, activation, or history records created in store
    assertThat(service.listPolicyIds()).isEmpty();
    assertThat(service.activeVersion("tpl-simulator-observer-001")).isEmpty();
    assertThat(service.getVersion("tpl-simulator-observer-001", 1)).isEmpty();
    assertThat(service.listVersions("tpl-simulator-observer-001")).isEmpty();
    assertThat(service.activationHistory("default", "tpl-simulator-observer-001")).isEmpty();
    assertThat(service.rollbackHistory("default", "tpl-simulator-observer-001")).isEmpty();
  }

  @Test
  void rulePrecedenceBlocksDestructiveActionsAndOversizedLogsBeforeReadRule() {
    JsonNode reviewedChange = JsonSupport.read(ROOT.resolve(REVIEWED_CHANGE_PATH));

    // An oversized log event has both actionClass=read and requiredSignals=[OVERSIZED_RESULT_WINDOW]
    // Priority 200 (block-oversized-log-window) must evaluate before Priority 100 (allow-cli-staging-cloudwatch-read)
    JsonNode oversizedLog = JsonSupport.read(
        ROOT.resolve("fixtures/policy-templates/cli-human-staging-oversized-log-read.json"));
    PolicySimulationResult oversizedResult = service.simulate(reviewedChange, oversizedLog, T0);
    assertThat(oversizedResult.outcome()).isEqualTo("block");
    assertThat(oversizedResult.matchedRuleId()).isEqualTo("block-oversized-log-window");
    assertThat(oversizedResult.reasonCode()).isEqualTo("OVERSIZED_LOG_WINDOW_BLOCKED");

    // A destructive security group deletion has toolName aws.ec2.delete_security_group (actionClass=destructive)
    // Priority 300 (block-destructive-operations) must evaluate before Priority 250 (require-approval-cli-staging-security-group-update)
    JsonNode deleteSg = JsonSupport.read(
        ROOT.resolve("fixtures/policy-templates/cli-human-staging-ec2-delete-security-group.json"));
    PolicySimulationResult deleteResult = service.simulate(reviewedChange, deleteSg, T0);
    assertThat(deleteResult.outcome()).isEqualTo("block");
    assertThat(deleteResult.matchedRuleId()).isEqualTo("block-destructive-operations");
    assertThat(deleteResult.reasonCode()).isEqualTo("DESTRUCTIVE_OPERATIONS_BLOCKED");
  }

  @Test
  void narrowRuleRemovalAndMutationDetectsBroadenedAllows() {
    JsonNode baseObserver = JsonSupport.read(ROOT.resolve(OBSERVER_PATH));

    // Mutation 1: Removing the oversized-log block rule broadens the policy to allow oversized extraction
    ObjectNode policyWithoutOversizedBlock = (ObjectNode) baseObserver.deepCopy();
    ArrayNode rulesMutated = (ArrayNode) policyWithoutOversizedBlock.path("rules");
    removeRuleById(rulesMutated, "block-oversized-log-window");

    JsonNode oversizedLog = JsonSupport.read(
        ROOT.resolve("fixtures/policy-templates/cli-human-staging-oversized-log-read.json"));
    PolicySimulationResult broadenedOversized = service.simulate(policyWithoutOversizedBlock, oversizedLog, T0);
    assertThat(broadenedOversized.outcome())
        .as("Removing block-oversized-log-window broadens allow for oversized log queries")
        .isEqualTo("allow");
    assertThat(broadenedOversized.matchedRuleId()).isEqualTo("allow-cli-staging-cloudwatch-read");

    // Mutation 2: Broadening allow-cli-staging-cloudwatch-read by removing toolNames restriction
    // permits plausible read-like operations (describe_log_groups)
    ObjectNode policyWithoutToolRestriction = (ObjectNode) baseObserver.deepCopy();
    ArrayNode rulesNoTool = (ArrayNode) policyWithoutToolRestriction.path("rules");
    for (JsonNode rule : rulesNoTool) {
      if ("allow-cli-staging-cloudwatch-read".equals(rule.path("id").asText())) {
        ((ObjectNode) rule.path("match")).remove("toolNames");
      }
    }
    JsonNode plausibleRead = JsonSupport.read(
        ROOT.resolve("fixtures/policy-templates/cli-human-staging-plausible-read.json"));
    PolicySimulationResult broadenedTool = service.simulate(policyWithoutToolRestriction, plausibleRead, T0);
    assertThat(broadenedTool.outcome())
        .as("Removing toolNames restriction broadens allow to any read operation")
        .isEqualTo("allow");

    // Mutation 3: Broadening allow-cli-staging-cloudwatch-read by removing environments restriction
    // permits reading from production
    ObjectNode policyWithoutEnvRestriction = (ObjectNode) baseObserver.deepCopy();
    ArrayNode rulesNoEnv = (ArrayNode) policyWithoutEnvRestriction.path("rules");
    for (JsonNode rule : rulesNoEnv) {
      if ("allow-cli-staging-cloudwatch-read".equals(rule.path("id").asText())) {
        ((ObjectNode) rule.path("match")).remove("environments");
      }
    }
    JsonNode prodRead = JsonSupport.read(
        ROOT.resolve("fixtures/policy-templates/cli-human-prod-log-read.json"));
    PolicySimulationResult broadenedEnv = service.simulate(policyWithoutEnvRestriction, prodRead, T0);
    assertThat(broadenedEnv.outcome())
        .as("Removing environments restriction broadens allow to production")
        .isEqualTo("allow");

    // Mutation 4: Broadening allow-cli-staging-cloudwatch-read by removing origins restriction
    // permits web agent reads
    ObjectNode policyWithoutOriginRestriction = (ObjectNode) baseObserver.deepCopy();
    ArrayNode rulesNoOrigin = (ArrayNode) policyWithoutOriginRestriction.path("rules");
    for (JsonNode rule : rulesNoOrigin) {
      if ("allow-cli-staging-cloudwatch-read".equals(rule.path("id").asText())) {
        ((ObjectNode) rule.path("match")).remove("origins");
      }
    }
    JsonNode webRead = JsonSupport.read(
        ROOT.resolve("fixtures/policy-templates/chatgpt-web-staging-log-read.json"));
    PolicySimulationResult broadenedOrigin = service.simulate(policyWithoutOriginRestriction, webRead, T0);
    assertThat(broadenedOrigin.outcome())
        .as("Removing origins restriction broadens allow to web agents")
        .isEqualTo("allow");

    // Mutation 5: Mutating reviewed-change rule decision from require_approval to allow
    // detects unauthorized write allow
    JsonNode baseReviewed = JsonSupport.read(ROOT.resolve(REVIEWED_CHANGE_PATH));
    ObjectNode policyWithUnconditionalWriteAllow = (ObjectNode) baseReviewed.deepCopy();
    ArrayNode rulesReviewed = (ArrayNode) policyWithUnconditionalWriteAllow.path("rules");
    for (JsonNode rule : rulesReviewed) {
      if ("require-approval-cli-staging-security-group-update".equals(rule.path("id").asText())) {
        ((ObjectNode) rule.path("decision")).put("outcome", "allow");
      }
    }
    JsonNode updateSg = JsonSupport.read(
        ROOT.resolve("fixtures/policy-templates/cli-human-staging-security-group-update.json"));
    PolicySimulationResult broadenedWrite = service.simulate(policyWithUnconditionalWriteAllow, updateSg, T0);
    assertThat(broadenedWrite.outcome())
        .as("Changing require_approval to allow creates unauthorized unconditional write allow")
        .isEqualTo("allow");
  }

  @Test
  void malformedAndUnknownPolicySchemasFailClosedWithContractViolation() {
    JsonNode valid = JsonSupport.read(ROOT.resolve(OBSERVER_PATH));

    // Unknown top-level property
    ObjectNode unknownTopField = (ObjectNode) valid.deepCopy();
    unknownTopField.put("unsupportedProperty", "injection");
    assertThatThrownBy(() -> PolicyValidator.validate(unknownTopField))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("unknown property is not permitted");

    // Unknown rule property
    ObjectNode unknownRuleField = (ObjectNode) valid.deepCopy();
    ((ObjectNode) unknownRuleField.path("rules").get(0)).put("extraRuleProperty", "injection");
    assertThatThrownBy(() -> PolicyValidator.validate(unknownRuleField))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("unknown property is not permitted");

    // Unknown match property
    ObjectNode unknownMatchField = (ObjectNode) valid.deepCopy();
    ((ObjectNode) unknownMatchField.path("rules").get(0).path("match")).put("cidrRange", "10.0.0.0/8");
    assertThatThrownBy(() -> PolicyValidator.validate(unknownMatchField))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("unknown property is not permitted");

    // Unsupported schemaVersion
    ObjectNode invalidSchemaVersion = (ObjectNode) valid.deepCopy();
    invalidSchemaVersion.put("schemaVersion", "2.0");
    assertThatThrownBy(() -> PolicyValidator.validate(invalidSchemaVersion))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must equal 1.0");

    // Non-positive version
    ObjectNode zeroVersion = (ObjectNode) valid.deepCopy();
    zeroVersion.put("version", 0);
    assertThatThrownBy(() -> PolicyValidator.validate(zeroVersion))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be a positive integer in the supported Java range 1..2147483647");

    // Duplicate rule IDs
    ObjectNode duplicateRuleId = (ObjectNode) valid.deepCopy();
    ((ObjectNode) duplicateRuleId.path("rules").get(1)).put("id", "block-destructive-operations");
    assertThatThrownBy(() -> PolicyValidator.validate(duplicateRuleId))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("rule IDs must be unique across the policy document");

    // Unsupported decision outcome
    ObjectNode invalidOutcome = (ObjectNode) valid.deepCopy();
    ((ObjectNode) invalidOutcome.path("rules").get(0).path("decision")).put("outcome", "permit");
    assertThatThrownBy(() -> PolicyValidator.validate(invalidOutcome))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be a valid outcome");
  }

  @Test
  void malformedActionEventsFailClosedWithRealContractViolation() {
    JsonNode policy = JsonSupport.read(ROOT.resolve(OBSERVER_PATH));
    JsonNode validAction = JsonSupport.read(ROOT.resolve("fixtures/policy-templates/cli-human-staging-log-read.json"));

    // Malformed UUID eventId
    ObjectNode invalidUuid = (ObjectNode) validAction.deepCopy();
    invalidUuid.put("eventId", "not-a-valid-uuid");
    assertThatThrownBy(() -> service.simulate(policy, invalidUuid, T0))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be a UUID");

    // Malformed observedAt timestamp
    ObjectNode invalidTimestamp = (ObjectNode) validAction.deepCopy();
    invalidTimestamp.put("observedAt", "invalid-instant");
    assertThatThrownBy(() -> service.simulate(policy, invalidTimestamp, T0))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("must be an ISO-8601 timestamp");

    // Lineage rootPrincipalId mismatch with identity subject
    ObjectNode lineageMismatch = (ObjectNode) validAction.deepCopy();
    ((ObjectNode) lineageMismatch.path("lineage")).put("rootPrincipalId", "user:impostor@example.com");
    assertThatThrownBy(() -> service.simulate(policy, lineageMismatch, T0))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("rootPrincipalId must match the identity subject");

    // Missing verified evidence for authenticated subject
    ObjectNode missingEvidence = (ObjectNode) validAction.deepCopy();
    ((ArrayNode) missingEvidence.path("identity").path("evidence")).removeAll();
    assertThatThrownBy(() -> service.simulate(policy, missingEvidence, T0))
        .isInstanceOf(ContractViolation.class)
        .hasMessageContaining("requires at least one item");
  }

  private static void removeRuleById(ArrayNode rules, String ruleId) {
    Iterator<JsonNode> iterator = rules.elements();
    while (iterator.hasNext()) {
      JsonNode rule = iterator.next();
      if (ruleId.equals(rule.path("id").asText())) {
        iterator.remove();
        return;
      }
    }
  }
}
