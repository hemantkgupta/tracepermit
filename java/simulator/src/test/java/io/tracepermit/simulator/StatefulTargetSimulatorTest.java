package io.tracepermit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class StatefulTargetSimulatorTest {
  @Test
  void boundedLogReadsReturnOnlyMatchingSimulatedEvents() {
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    ObjectNode arguments = JsonSupport.MAPPER.createObjectNode()
        .put("logGroupName", "/app/prod")
        .put("filterPattern", "ERROR")
        .put("limit", 1);

    JsonNode result = simulator.invoke(action("aws.logs.filter_log_events", "aws", arguments));

    assertThat(result.path("mode").asText()).isEqualTo("simulated");
    assertThat(result.path("matchCount").asInt()).isEqualTo(1);
    assertThat(result.path("events").get(0).path("message").asText()).contains("ERROR");
    assertThat(result.toString()).doesNotContain("credential", "secret", "token");
  }

  @Test
  void securityGroupUpdatesAreStatefulAndIdempotent() {
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    ObjectNode arguments = JsonSupport.MAPPER.createObjectNode()
        .put("groupId", "sg-demo")
        .put("port", 443)
        .put("cidr", "192.0.2.0/24");

    JsonNode first = simulator.invoke(action("aws.ec2.update_security_group", "aws", arguments));
    JsonNode duplicate = simulator.invoke(action("aws.ec2.update_security_group", "aws", arguments));

    assertThat(first.path("changed").asBoolean()).isTrue();
    assertThat(duplicate.path("changed").asBoolean()).isFalse();
    assertThat(simulator.snapshot().path("aws").path("securityGroups").path("sg-demo"))
        .hasSize(1);
  }

  @Test
  void deletionRemovesOnlyTheFixedProductionLabelledSimulatedGroup() {
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    ObjectNode deletion = action(
        "aws.ec2.delete_security_group", "aws",
        JsonSupport.MAPPER.createObjectNode().put("groupId", "sg-demo"));
    ((ObjectNode) deletion.path("target")).put("environment", "prod");
    assertThat(simulator.snapshot().path("aws").path("securityGroups").has("sg-demo")).isTrue();

    ObjectNode wrongAccount = deletion.deepCopy();
    ((ObjectNode) wrongAccount.path("target")).put("accountId", "simulated-other");
    assertCode(simulator, wrongAccount, "INVALID_SIMULATOR_TARGET");
    ObjectNode wrongEnvironment = deletion.deepCopy();
    ((ObjectNode) wrongEnvironment.path("target")).put("environment", "dev");
    assertCode(simulator, wrongEnvironment, "INVALID_SIMULATOR_TARGET");
    ObjectNode extraArgument = deletion.deepCopy();
    ((ObjectNode) extraArgument.path("arguments")).put("credential", "not-accepted");
    assertCode(simulator, extraArgument, "INVALID_SIMULATOR_ARGUMENTS");

    JsonNode result = simulator.invoke(deletion);
    assertThat(result.path("mode").asText()).isEqualTo("simulated");
    assertThat(result.path("deleted").asBoolean()).isTrue();
    assertThat(simulator.snapshot().path("aws").path("securityGroups").has("sg-demo")).isFalse();
    assertCode(simulator, deletion, "SIMULATED_RESOURCE_NOT_FOUND");
  }

  @Test
  void jiraIssueLifecyclePersistsOnlyInsideTheSimulator() {
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    JsonNode created = simulator.invoke(
        action(
            "jira.issue.create",
            "jira",
            JsonSupport.MAPPER.createObjectNode()
                .put("projectKey", "SIM")
                .put("summary", "Review the simulated finding")));
    String issueKey = created.path("issue").path("issueKey").asText();
    assertThat(issueKey).isEqualTo("SIM-1");

    JsonNode transitioned = simulator.invoke(
        action(
            "jira.issue.transition",
            "jira",
            JsonSupport.MAPPER.createObjectNode()
                .put("issueKey", issueKey)
                .put("transition", "start_progress")));
    assertThat(transitioned.path("issue").path("status").asText()).isEqualTo("IN_PROGRESS");
    simulator.invoke(
        action(
            "jira.issue.transition",
            "jira",
            JsonSupport.MAPPER.createObjectNode()
                .put("issueKey", issueKey)
                .put("transition", "resolve")));

    JsonNode fetched = simulator.invoke(
        action(
            "jira.issue.get",
            "jira",
            JsonSupport.MAPPER.createObjectNode().put("issueKey", issueKey)));
    ((ObjectNode) fetched.path("issue")).put("status", "MUTATED_BY_CALLER");
    assertThat(simulator.snapshot().path("jira").path("issues").path(issueKey).path("status").asText())
        .isEqualTo("DONE");
  }

  @Test
  void targetAndArgumentAllowListsRejectBypassInputs() {
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    ObjectNode readArguments = JsonSupport.MAPPER.createObjectNode()
        .put("logGroupName", "/app/prod")
        .put("filterPattern", "")
        .put("limit", 20);
    ObjectNode realTarget = action("aws.logs.filter_log_events", "aws", readArguments);
    ((ObjectNode) realTarget.path("target")).put("accountId", "123456789012");
    assertCode(simulator, realTarget, "NON_SIMULATED_TARGET_REJECTED");

    readArguments.put("credential", "must-not-be-accepted");
    assertCode(
        simulator,
        action("aws.logs.filter_log_events", "aws", readArguments),
        "INVALID_SIMULATOR_ARGUMENTS");
    assertCode(
        simulator,
        action("aws.logs.filter_log_events", "jira", JsonSupport.MAPPER.createObjectNode()
            .put("logGroupName", "/app/prod").put("limit", 20)),
        "INVALID_SIMULATOR_TARGET");
    assertCode(
        simulator,
        action("aws.ec2.call_real_api", "aws", JsonSupport.MAPPER.createObjectNode()),
        "SIMULATOR_TOOL_NOT_SUPPORTED");
  }

  @Test
  void invalidResourcesRangesTypesAndTransitionsHaveBoundedErrors() {
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    assertCode(
        simulator,
        action("aws.logs.filter_log_events", "aws", JsonSupport.MAPPER.createObjectNode()
            .put("logGroupName", "/missing").put("limit", 20)),
        "SIMULATED_RESOURCE_NOT_FOUND");
    assertCode(
        simulator,
        action("aws.logs.filter_log_events", "aws", JsonSupport.MAPPER.createObjectNode()
            .put("logGroupName", "/app/prod").put("limit", 101)),
        "INVALID_SIMULATOR_ARGUMENTS");
    assertCode(
        simulator,
        action("aws.logs.filter_log_events", "aws", JsonSupport.MAPPER.createObjectNode()
            .put("logGroupName", "/app/prod").put("filterPattern", 42).put("limit", 20)),
        "INVALID_SIMULATOR_ARGUMENTS");
    assertCode(
        simulator,
        action("aws.ec2.update_security_group", "aws", JsonSupport.MAPPER.createObjectNode()
            .put("groupId", "sg-demo").put("port", 443).put("cidr", "10.0.0.0/8")),
        "INVALID_SIMULATOR_ARGUMENTS");
    assertCode(
        simulator,
        action("jira.issue.get", "jira", JsonSupport.MAPPER.createObjectNode().put("issueKey", "SIM-404")),
        "SIMULATED_RESOURCE_NOT_FOUND");
    JsonNode created = simulator.invoke(
        action("jira.issue.create", "jira", JsonSupport.MAPPER.createObjectNode()
            .put("projectKey", "SIM").put("summary", "A simulated issue")));
    assertCode(
        simulator,
        action("jira.issue.transition", "jira", JsonSupport.MAPPER.createObjectNode()
            .put("issueKey", created.path("issue").path("issueKey").asText())
            .put("transition", "delete_everything")),
        "INVALID_SIMULATOR_ARGUMENTS");
  }

  @Test
  void githubDraftPullRequestCreationIsStatefulAndEnforcesDraft() {
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    ObjectNode arguments =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("repository", "tracepermit/agentcore-pr-pilot")
            .put("title", "Simulated draft PR")
            .put("head", "feature/sim-1")
            .put("base", "main")
            .put("draft", true)
            .put("body", "Simulated body");

    JsonNode result = simulator.invoke(action("github.pull_request.create", "github", arguments));

    assertThat(result.path("mode").asText()).isEqualTo("simulated");
    assertThat(result.path("system").asText()).isEqualTo("github");
    assertThat(result.path("operation").asText()).isEqualTo("github.pull_request.create");
    assertThat(result.path("pullRequest").path("number").asInt()).isEqualTo(1);
    assertThat(result.path("pullRequest").path("draft").asBoolean()).isTrue();
    assertThat(result.path("pullRequest").path("head").asText()).isEqualTo("feature/sim-1");
    assertThat(result.path("pullRequest").path("base").asText()).isEqualTo("main");

    // Snapshot check
    JsonNode snapshot = simulator.snapshot();
    assertThat(snapshot.path("github").path("pullRequests").has("PR-1")).isTrue();

    // Rejection: draft = false
    ObjectNode draftFalse = arguments.deepCopy();
    draftFalse.put("draft", false);
    assertCode(simulator, action("github.pull_request.create", "github", draftFalse), "INVALID_SIMULATOR_ARGUMENTS");

    // Rejection: base != main
    ObjectNode wrongBase = arguments.deepCopy();
    wrongBase.put("base", "develop");
    assertCode(simulator, action("github.pull_request.create", "github", wrongBase), "INVALID_SIMULATOR_ARGUMENTS");
  }

  private static ObjectNode action(String toolName, String system, ObjectNode arguments) {
    ObjectNode target = JsonSupport.MAPPER.createObjectNode()
        .put("system", system)
        .put("accountId", "simulated-123");
    ObjectNode action = JsonSupport.MAPPER.createObjectNode().put("toolName", toolName);
    action.set("arguments", arguments);
    action.set("target", target);
    return action;
  }

  private static void assertCode(
      StatefulTargetSimulator simulator, JsonNode action, String expectedCode) {
    assertThatThrownBy(() -> simulator.invoke(action))
        .isInstanceOf(SimulatorException.class)
        .extracting(failure -> ((SimulatorException) failure).code())
        .isEqualTo(expectedCode);
  }
}
