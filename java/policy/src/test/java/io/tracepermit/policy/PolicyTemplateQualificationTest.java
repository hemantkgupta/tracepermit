package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.ContractViolation;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
    JsonNode matrix = read("fixtures/policy-templates/matrix.json");
    assertThat(matrix.path("schemaVersion").asText()).isEqualTo("1.0");
    assertThat(matrix.path("cases").isArray()).isTrue();
    ArrayNode cases = (ArrayNode) matrix.path("cases");
    List<String> caseIds = new ArrayList<>();
    cases.forEach(row -> caseIds.add(row.path("id").asText()));
    assertThat(caseIds).hasSize(19).doesNotHaveDuplicates().containsExactlyInAnyOrder(
        "case-01-positive-read",
        "case-02-approval-not-allow-write",
        "case-03-termination-denial",
        "case-04-destructive-deletion-denial",
        "case-05-plausible-read-like-tool",
        "case-06-unknown-tool",
        "case-07-wrong-origin-web",
        "case-08-wrong-origin-api-workload",
        "case-09-wrong-environment-read",
        "case-10-wrong-environment-write",
        "case-11-oversized-log-window",
        "case-12-unmatched-write",
        "case-13-wrong-subject",
        "case-14-wrong-target-system",
        "case-15-wrong-write-origin",
        "case-16-wrong-write-subject",
        "case-17-wrong-write-target-system",
        "case-18-threshold-allowed",
        "case-19-threshold-blocked");

    for (String template : templates()) {
      JsonNode policy = read(template);
      for (JsonNode row : cases) {
        PolicySimulationResult result = service.simulate(policy, read(row.path("fixture").asText()), T0);
        JsonNode expected = row.path("expectations").path(template);
        assertThat(expected.isObject()).as("%s expectations for %s", row.path("id"), template).isTrue();
        assertThat(result.dryRun()).isTrue();
        assertThat(result.policyId()).isEqualTo(policy.path("id").asText());
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.outcome()).as("%s %s outcome", template, row.path("id"))
            .isEqualTo(expected.path("outcome").asText());
        assertThat(result.reasonCode()).isEqualTo(expected.path("reasonCode").asText());
        assertThat(expected.has("matchedRuleId")).isTrue();
        assertThat(expected.has("approvalGroup")).isTrue();
        assertThat(result.matchedRuleId()).isEqualTo(nullableText(expected.path("matchedRuleId")));
        assertThat(result.approvalGroup()).isEqualTo(nullableText(expected.path("approvalGroup")));
        assertThat(result.simulatedAt()).isEqualTo(T0);
        JsonNode classification = row.path("classification");
        assertThat(classification.isObject()).isTrue();
        assertThat(classification.path("signals").isArray()).isTrue();
        Classification actual = result.evaluation().classification();
        assertThat(actual.actionClass()).isEqualTo(classification.path("actionClass").asText());
        assertThat(actual.score()).isEqualTo(classification.path("score").asInt());
        assertThat(actual.severity()).isEqualTo(classification.path("severity").asText());
        List<String> expectedSignals = new ArrayList<>();
        classification.path("signals").forEach(signal -> expectedSignals.add(signal.asText()));
        assertThat(actual.signals()).containsExactlyElementsOf(expectedSignals);
      }
    }
  }

  @Test
  void dryRunSimulationPreservesImmutabilityAndLeavesNoStoredState() {
    for (String template : templates()) {
      JsonNode policy = read(template);
      JsonNode policyBefore = policy.deepCopy();
      for (JsonNode row : matrixCases()) {
        JsonNode action = read(row.path("fixture").asText());
        JsonNode actionBefore = action.deepCopy();
        assertThat(service.simulate(policy, action, T0).dryRun()).isTrue();
        assertThat(policy).isEqualTo(policyBefore);
        assertThat(action).isEqualTo(actionBefore);
        assertThat(service.listPolicyIds()).isEmpty();
        String id = policy.path("id").asText();
        assertThat(service.activeVersion(id)).isEmpty();
        assertThat(service.getVersion(id, 1)).isEmpty();
        assertThat(service.listVersions(id)).isEmpty();
        assertThat(service.activationHistory("default", id)).isEmpty();
        assertThat(service.rollbackHistory("default", id)).isEmpty();
      }
    }
  }

  @Test
  void oversizedRulePrecedenceAndDestructiveMatchingAreExplicit() {
    JsonNode reviewedChange = JsonSupport.read(ROOT.resolve(REVIEWED_CHANGE_PATH));

    // An oversized log event has both actionClass=read and requiredSignals=[OVERSIZED_RESULT_WINDOW]
    // Priority 200 (block-oversized-log-window) must evaluate before Priority 100 (allow-cli-staging-cloudwatch-read)
    JsonNode oversizedLog = JsonSupport.read(
        ROOT.resolve("fixtures/policy-templates/cli-human-staging-oversized-log-read.json"));
    PolicySimulationResult oversizedResult = service.simulate(reviewedChange, oversizedLog, T0);
    assertThat(oversizedResult.outcome()).isEqualTo("block");
    assertThat(oversizedResult.matchedRuleId()).isEqualTo("block-oversized-log-window");
    assertThat(oversizedResult.reasonCode()).isEqualTo("OVERSIZED_LOG_WINDOW_BLOCKED");

    // This deletion matches the destructive rule, not the different update/modify rule.
    // It verifies destructive matching, not competing-rule precedence.
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
  void readScopeMutationChecksIndependentlyProtectHumanAndTargetSystemRestrictions() {
    Map<String, String> negatives = Map.of(
        "subjectTypes", "fixtures/policy-templates/cli-workload-staging-log-read.json",
        "targetSystems", "fixtures/policy-templates/cli-human-wrong-system-log-read.json");
    for (String template : templates()) {
      JsonNode policy = read(template);
      for (var negative : negatives.entrySet()) {
        ObjectNode mutant = policy.deepCopy();
        ((ObjectNode) rule(mutant, "allow-cli-staging-cloudwatch-read").path("match"))
            .remove(negative.getKey());
        JsonNode action = read(negative.getValue());
        assertThat(service.simulate(policy, action, T0).outcome()).isEqualTo("block");
        var broadened = service.simulate(mutant, action, T0);
        assertThat(broadened.outcome()).isEqualTo("allow");
        assertThat(broadened.matchedRuleId()).isEqualTo("allow-cli-staging-cloudwatch-read");
      }
    }
  }

  @Test
  void reviewedWriteScopeMutationChecksDetectEveryBroadenedPredicate() {
    JsonNode policy = read(REVIEWED_CHANGE_PATH);
    Map<String, String> negatives = Map.of(
        "origins", "fixtures/policy-templates/chatgpt-web-staging-security-group-update.json",
        "subjectTypes", "fixtures/policy-templates/cli-workload-staging-security-group-update.json",
        "targetSystems", "fixtures/policy-templates/cli-human-wrong-system-security-group-update.json",
        "environments", "fixtures/policy-templates/cli-human-prod-security-group-update.json",
        "toolNames", "fixtures/policy-templates/cli-human-staging-unmatched-modify.json");
    for (var negative : negatives.entrySet()) {
      ObjectNode mutant = policy.deepCopy();
      ((ObjectNode) rule(mutant, "require-approval-cli-staging-security-group-update").path("match"))
          .remove(negative.getKey());
      JsonNode action = read(negative.getValue());
      assertThat(service.simulate(policy, action, T0).outcome()).isEqualTo("block");
      var broadened = service.simulate(mutant, action, T0);
      assertThat(broadened.outcome()).isEqualTo("require_approval");
      assertThat(broadened.approvalGroup()).isEqualTo("simulator-security-reviewers");
      assertThat(broadened.matchedRuleId()).isEqualTo("require-approval-cli-staging-security-group-update");
    }
  }

  @Test
  void loweringOversizedPriorityExposesTheCompetingReadAllowInBothTemplates() {
    JsonNode action = read("fixtures/policy-templates/cli-human-staging-oversized-log-read.json");
    for (String template : templates()) {
      JsonNode policy = read(template);
      ObjectNode mutant = policy.deepCopy();
      rule(mutant, "block-oversized-log-window").put("priority", -100);
      assertThat(service.simulate(policy, action, T0).outcome()).isEqualTo("block");
      assertThat(service.simulate(mutant, action, T0).outcome()).isEqualTo("allow");
    }
  }

  @Test
  void candidateSimulationNeverTouchesCatalogForEitherTemplateOrAnyMatrixVector() {
    PolicyLifecycleService isolated = new PolicyLifecycleService(forbiddenCatalog());
    for (String template : templates()) {
      for (JsonNode row : matrixCases()) {
        assertThat(isolated.simulate(read(template), read(row.path("fixture").asText()), T0).dryRun()).isTrue();
      }
    }
  }

  @Test
  void simulationCharacterizesCanonicalAssertionsButDoesNotAuthenticateThem() {
    ObjectNode asserted = (ObjectNode) read("fixtures/policy-templates/cli-human-staging-log-read.json");
    ((ObjectNode) asserted.path("identity").path("subject")).put("assurance", "asserted");
    ((ObjectNode) asserted.path("identity").path("subject")).put("authenticationMethod", "unknown");
    for (JsonNode item : asserted.path("identity").path("evidence")) {
      ((ObjectNode) item).put("assurance", "asserted");
      ((ObjectNode) item).remove(List.of("verifiedAt", "credentialFingerprint"));
    }
    asserted.path("lineage").path("hops").forEach(hop ->
        ((ObjectNode) hop).put("assurance", "asserted"));
    ObjectNode fabricated = (ObjectNode) read("fixtures/policy-templates/cli-human-staging-log-read.json");
    ((ObjectNode) fabricated.path("identity").path("evidence").get(0))
        .put("issuer", "synthetic-untrusted-issuer");
    for (String template : templates()) {
      var assertion = service.simulate(read(template), asserted, T0);
      assertThat(assertion.outcome()).isEqualTo("allow");
      assertThat(assertion.evaluation().classification().signals()).contains("UNVERIFIED_PRINCIPAL");
      assertThat(service.simulate(read(template), fabricated, T0).outcome()).isEqualTo("allow");
    }
    // Deliberate library characterization, not evidence of network authentication
    // or authority. Only a verified trusted adapter may provide runtime identity.
  }

  @Test
  void rawCanonicalSimulationIsNotToolArgumentSchemaValidation() throws Exception {
    for (String template : templates()) {
      JsonNode policy = read(template);
      for (String limit : List.of("null", "\"500\"", "500.5", "false", "{}")) {
        ObjectNode action = (ObjectNode) read("fixtures/policy-templates/cli-human-staging-log-read.json");
        ((ObjectNode) action.path("action").path("arguments"))
            .set("limit", JsonSupport.MAPPER.readTree(limit));
        var result = service.simulate(policy, action, T0);
        assertThat(result.outcome()).isEqualTo("allow");
        assertThat(result.evaluation().classification().signals()).doesNotContain("OVERSIZED_RESULT_WINDOW");
      }
      ObjectNode missing = (ObjectNode) read("fixtures/policy-templates/cli-human-staging-log-read.json");
      ((ObjectNode) missing.path("action").path("arguments")).remove("limit");
      assertThat(service.simulate(policy, missing, T0).outcome()).isEqualTo("allow");
    }
    // Runtime adapters must reject malformed/missing/out-of-range tool arguments.
    // Large-integer narrowing is tracked separately; do not enshrine it as desired allow behavior.
  }

  @Test
  void unknownActionAndPolicySchemasFailThroughTheRealSimulationApi() {
    PolicyLifecycleService isolated = new PolicyLifecycleService(forbiddenCatalog());
    for (String template : templates()) {
      ObjectNode action = (ObjectNode) read("fixtures/policy-templates/cli-human-staging-log-read.json");
      action.put("schemaVersion", "unknown");
      assertThatThrownBy(() -> isolated.simulate(read(template), action, T0))
          .isInstanceOf(ContractViolation.class).hasMessage("must equal 1.0");
      ObjectNode policy = (ObjectNode) read(template);
      policy.put("schemaVersion", "unknown");
      assertThatThrownBy(() -> isolated.simulate(policy,
          read("fixtures/policy-templates/cli-human-staging-log-read.json"), T0))
          .isInstanceOf(ContractViolation.class).hasMessage("must equal 1.0");
    }
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

  private static List<String> templates() {
    return List.of(OBSERVER_PATH, REVIEWED_CHANGE_PATH);
  }

  private static JsonNode read(String path) {
    return JsonSupport.read(ROOT.resolve(path));
  }

  private static JsonNode matrixCases() {
    return read("fixtures/policy-templates/matrix.json").path("cases");
  }

  private static String nullableText(JsonNode value) {
    return value.isNull() ? null : value.asText();
  }

  private static ObjectNode rule(JsonNode policy, String id) {
    for (JsonNode candidate : policy.path("rules")) {
      if (id.equals(candidate.path("id").asText())) return (ObjectNode) candidate;
    }
    throw new AssertionError("Required rule missing: " + id);
  }

  private static PolicyLifecycleStore forbiddenCatalog() {
    return (PolicyLifecycleStore) Proxy.newProxyInstance(
        PolicyLifecycleStore.class.getClassLoader(), new Class<?>[] {PolicyLifecycleStore.class},
        (proxy, method, args) -> {
          throw new AssertionError("Candidate simulation called catalog method: " + method.getName());
        });
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
