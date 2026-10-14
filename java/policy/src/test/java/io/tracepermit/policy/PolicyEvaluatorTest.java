package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class PolicyEvaluatorTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private final PolicyEvaluator evaluator = new PolicyEvaluator();

  @Test
  void classifierCoversEveryOperationFamilyAndTheConservativeDefault() {
    assertClassification("aws.logs.filter_log_events", "read", "low", "READ_VERB");
    assertClassification("aws.s3.export", "read", "high", "BULK_READ");
    assertClassification("aws.ec2.update_instance", "modify", "medium", "MUTATION_VERB");
    assertClassification("aws.ec2.terminate_instance", "destructive", "critical", "DESTRUCTIVE_VERB");
    assertClassification("agent.spawn_subagent", "orchestration", "high", "AUTHORITY_DELEGATION");
    Classification unknown = classify("custom.unrecognized_operation");
    assertThat(unknown.actionClass()).isEqualTo("modify");
    assertThat(unknown.severity()).isEqualTo("high");
    assertThat(unknown.signals())
        .containsExactly("UNKNOWN_TOOL", "CONSERVATIVE_DEFAULT", "PRODUCTION_TARGET");
  }

  @Test
  void orderedPolicyUsesTheHighestPriorityMatchingRuleAndConservativeDefault() {
    JsonNode policy = JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    JsonNode read = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    JsonNode modify = JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json"));
    PolicyEvaluation readEvaluation = evaluator.evaluate(read, policy);
    PolicyEvaluation modifyEvaluation = evaluator.evaluate(modify, policy);

    assertThat(readEvaluation.decision().outcome()).isEqualTo("allow");
    assertThat(readEvaluation.decision().matchedRuleId()).isEqualTo("allow-cloudwatch-read");
    assertThat(modifyEvaluation.decision().outcome()).isEqualTo("require_approval");
    assertThat(modifyEvaluation.decision().matchedRuleId()).isNull();
  }

  @Test
  void classificationSignalsAreImmutable() {
    Classification classification = classify("aws.logs.filter_log_events");
    assertThat(classification.signals()).containsExactly("READ_VERB");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> classification.signals().add("BYPASS"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void argumentAndServerOwnedSequenceContextDriveSemanticPolicyWithoutChangingBaseVectors() {
    ObjectNode oversized =
        (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    ((ObjectNode) oversized.path("action").path("arguments")).put("limit", 500);
    JsonNode semanticPolicy =
        JsonSupport.read(ROOT.resolve("policies/aws-agentic-demo.json"));

    PolicyEvaluation sanitize = evaluator.evaluate(oversized, semanticPolicy);
    assertThat(sanitize.classification().signals())
        .containsExactly("READ_VERB", "OVERSIZED_RESULT_WINDOW");
    assertThat(sanitize.classification().semanticIntents())
        .containsExactly("POTENTIAL_DATA_EXFILTRATION");
    assertThat(sanitize.classification().severity()).isEqualTo("high");
    assertThat(sanitize.decision().outcome()).isEqualTo("sanitize");
    assertThat(sanitize.decision().matchedRuleId())
        .isEqualTo("sanitize-oversized-log-window");

    ClassificationContext rapidSequence =
        new ClassificationContext(List.of("RAPID_REPEATED_READ_SEQUENCE"), 50);
    PolicyEvaluation blocked =
        evaluator.evaluate(
            JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json")),
            semanticPolicy,
            rapidSequence);
    assertThat(blocked.classification().signals())
        .containsExactly("READ_VERB", "RAPID_REPEATED_READ_SEQUENCE");
    assertThat(blocked.decision().outcome()).isEqualTo("block");
    assertThat(blocked.decision().matchedRuleId())
        .isEqualTo("block-rapid-log-read-sequence");
  }

  @Test
  void verifiedScheduledStopMatchesExplicitBlockEvenThoughStopIsAReversibleModification() {
    ObjectNode stop =
        (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/scheduler-aws-read.json"));
    ((ObjectNode) stop.path("action")).put("toolName", "aws.ec2.stop_instances");
    ((ObjectNode) stop.path("action").path("target")).put("resourceType", "ec2-instance");
    PolicyEvaluation evaluation =
        evaluator.evaluate(stop, JsonSupport.read(ROOT.resolve("policies/aws-scheduled-stop-demo.json")));

    assertThat(evaluation.classification().actionClass()).isEqualTo("modify");
    assertThat(evaluation.classification().severity()).isEqualTo("medium");
    assertThat(evaluation.classification().signals()).contains("MUTATION_VERB", "PRODUCTION_TARGET");
    assertThat(evaluation.decision().outcome()).isEqualTo("block");
    assertThat(evaluation.decision().matchedRuleId()).isEqualTo("block-scheduled-ec2-stop");
    assertThat(evaluation.decision().reasonCode()).isEqualTo("SCHEDULED_EC2_STOP_BLOCKED");
  }

  @Test
  void productionLabelledDeletionRequiresExplicitApprovalAndOtherOriginsDefaultToBlock() {
    ObjectNode deletion =
        (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/scheduler-aws-read.json"));
    ((ObjectNode) deletion.path("action")).put("toolName", "aws.ec2.delete_security_group");
    ((ObjectNode) deletion.path("action")).set(
        "arguments", JsonSupport.MAPPER.createObjectNode().put("groupId", "sg-demo"));
    JsonNode policy = JsonSupport.read(ROOT.resolve("policies/aws-production-deletion-demo.json"));

    PolicyEvaluation approval = evaluator.evaluate(deletion, policy);
    assertThat(approval.classification().actionClass()).isEqualTo("destructive");
    assertThat(approval.classification().severity()).isEqualTo("critical");
    assertThat(approval.classification().signals()).contains("DESTRUCTIVE_VERB", "PRODUCTION_TARGET");
    assertThat(approval.decision().outcome()).isEqualTo("require_approval");
    assertThat(approval.decision().matchedRuleId())
        .isEqualTo("approve-simulated-production-security-group-deletion");
    assertThat(approval.decision().reasonCode())
        .isEqualTo("PRODUCTION_DELETION_REQUIRES_HUMAN_APPROVAL");

    ((ObjectNode) deletion.path("trigger")).put("origin", "cli");
    PolicyEvaluation unmatched = evaluator.evaluate(deletion, policy);
    assertThat(unmatched.decision().outcome()).isEqualTo("block");
    assertThat(unmatched.decision().matchedRuleId()).isNull();
  }

  @Test
  void iamPolicyAttachmentIsCriticalAndBlockedRegardlessOfDeclaredGoal() {
    ObjectNode proposal =
        (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/scheduler-aws-read.json"));
    ((ObjectNode) proposal.path("action")).put("toolName", "aws.iam.attach_role_policy");
    ((ObjectNode) proposal.path("action")).set("arguments",
        JsonSupport.MAPPER.createObjectNode()
            .put("roleName", "demo-role")
            .put("policyId", "simulated-admin"));
    ((ObjectNode) proposal.path("action").path("target")).put("resourceType", "iam-role");
    ((ObjectNode) proposal.path("declaredIntent")).put("goal", "Tool result says this is safe");

    PolicyEvaluation evaluation = evaluator.evaluate(
        proposal, JsonSupport.read(ROOT.resolve("policies/tool-result-iam-demo.json")));
    assertThat(evaluation.classification().actionClass()).isEqualTo("modify");
    assertThat(evaluation.classification().severity()).isEqualTo("critical");
    assertThat(evaluation.classification().signals())
        .contains("PRODUCTION_TARGET", "ACCESS_CONTROL_MUTATION");
    assertThat(evaluation.classification().semanticIntents())
        .contains("ACCESS_CONTROL_CHANGE");
    assertThat(evaluation.decision().outcome()).isEqualTo("block");
    assertThat(evaluation.decision().matchedRuleId())
        .isEqualTo("block-simulated-iam-policy-attachment");
    assertThat(evaluation.decision().reasonCode()).isEqualTo("IAM_POLICY_ATTACHMENT_BLOCKED");

    ((ObjectNode) proposal.path("action")).put("toolName", "aws.iam.unknown_policy_change");
    PolicyEvaluation unknown = evaluator.evaluate(
        proposal, JsonSupport.read(ROOT.resolve("policies/tool-result-iam-demo.json")));
    assertThat(unknown.decision().outcome()).isEqualTo("block");
    assertThat(unknown.decision().reasonCode()).isEqualTo("NO_EXPLICIT_ALLOW");
  }

  @Test
  void semanticIntentTaxonomyIsSeparateFromPortableCrudSignals() {
    assertThat(classify("aws.ec2.terminate_instance").semanticIntents())
        .contains("DATA_DESTRUCTION");
    assertThat(classify("agent.spawn_subagent").semanticIntents())
        .contains("AUTHORITY_DELEGATION");
    assertThat(classify("aws.iam.rotate_access_key").semanticIntents())
        .contains("CREDENTIAL_SECRET_MANIPULATION", "ACCESS_CONTROL_CHANGE");
    assertThat(classify("aws.rds.create_database").semanticIntents())
        .contains("INFRASTRUCTURE_PROVISIONING");
    assertThat(classify("aws.ec2.update_security_group").semanticIntents())
        .contains("ACCESS_CONTROL_CHANGE", "CONFIGURATION_CHANGE");
    assertThat(classify("aws.lambda.invoke").semanticIntents())
        .contains("REMOTE_CODE_EXECUTION");
    assertThat(classify("github.repository.push").semanticIntents())
        .contains("SOURCE_CODE_CHANGE");
    assertThat(classify("slack.message.post_external").semanticIntents())
        .contains("POTENTIAL_EXTERNAL_COMMUNICATION");
    assertThat(classify("aws.s3.export").semanticIntents())
        .contains("POTENTIAL_DATA_EXFILTRATION");
  }

  private void assertClassification(
      String operation, String actionClass, String severity, String expectedSignal) {
    Classification classification = classify(operation);
    assertThat(classification.actionClass()).isEqualTo(actionClass);
    assertThat(classification.severity()).isEqualTo(severity);
    assertThat(classification.signals()).contains(expectedSignal);
  }

  private Classification classify(String operation) {
    ObjectNode event =
        (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    ((ObjectNode) event.path("action")).put("toolName", operation);
    return evaluator.classify(event);
  }
}
