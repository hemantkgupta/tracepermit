package io.tracepermit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.SimulatedTarget;
import io.tracepermit.contracts.JsonSupport;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A deterministic in-memory target used to exercise adapter and execution semantics.
 * It has no network client, credential provider, AWS SDK, or Jira SDK.
 */
public final class StatefulTargetSimulator implements SimulatedTarget {
  private static final Set<String> DOCUMENTATION_CIDRS =
      Set.of("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24");
  private final Map<String, List<ObjectNode>> logGroups = new LinkedHashMap<>();
  private final Map<String, ArrayNode> securityGroupRules = new LinkedHashMap<>();
  private final Map<String, ObjectNode> issues = new LinkedHashMap<>();
  private final Map<String, ObjectNode> pullRequests = new LinkedHashMap<>();
  private int nextIssueNumber = 1;
  private int nextPullRequestNumber = 1;

  public StatefulTargetSimulator() {
    logGroups.put(
        "/app/prod",
        List.of(
            log("2026-10-08T10:01:12Z", "INFO request completed requestId=sim-100"),
            log("2026-10-08T10:02:44Z", "ERROR payment simulation timed out requestId=sim-101"),
            log("2026-10-08T10:04:09Z", "ERROR retry exhausted requestId=sim-102")));
    logGroups.put(
        "/app/staging",
        List.of(log("2026-10-08T09:55:00Z", "WARN staging canary is simulated")));
    securityGroupRules.put("sg-demo", JsonSupport.MAPPER.createArrayNode());
  }

  @Override
  public synchronized JsonNode invoke(JsonNode action) {
    require(action != null && action.isObject(), "INVALID_SIMULATOR_ACTION", "The simulated action must be an object.");
    String toolName = requiredText(action, "toolName");
    JsonNode arguments = action.path("arguments");
    JsonNode target = action.path("target");
    require(arguments.isObject(), "INVALID_SIMULATOR_ARGUMENTS", "The simulated arguments must be an object.");
    require(target.isObject(), "INVALID_SIMULATOR_TARGET", "The simulated target must be an object.");
    require(
        target.path("accountId").asText().startsWith("simulated-"),
        "NON_SIMULATED_TARGET_REJECTED",
        "The stateful simulator accepts only simulated target identifiers.");

    return switch (toolName) {
      case "aws.logs.filter_log_events" -> filterLogEvents(arguments, target);
      case "aws.ec2.update_security_group" -> updateSecurityGroup(arguments, target);
      case "aws.ec2.delete_security_group" -> deleteSecurityGroup(arguments, target);
      case "jira.issue.create" -> createIssue(arguments, target);
      case "jira.issue.get" -> getIssue(arguments, target);
      case "jira.issue.transition" -> transitionIssue(arguments, target);
      case "github.pull_request.create" -> createPullRequest(arguments, target);
      default -> throw error(
          "SIMULATOR_TOOL_NOT_SUPPORTED", "The requested tool is not implemented by the stateful simulator.");
    };
  }

  public synchronized JsonNode snapshot() {
    ObjectNode aws = JsonSupport.MAPPER.createObjectNode();
    ObjectNode groups = JsonSupport.MAPPER.createObjectNode();
    logGroups.forEach((name, events) -> groups.put(name, events.size()));
    aws.set("logGroups", groups);
    ObjectNode rules = JsonSupport.MAPPER.createObjectNode();
    securityGroupRules.forEach((name, entries) -> rules.set(name, entries.deepCopy()));
    aws.set("securityGroups", rules);

    ObjectNode jira = JsonSupport.MAPPER.createObjectNode();
    ObjectNode issueSnapshot = JsonSupport.MAPPER.createObjectNode();
    issues.forEach((key, issue) -> issueSnapshot.set(key, issue.deepCopy()));
    jira.set("issues", issueSnapshot);

    ObjectNode github = JsonSupport.MAPPER.createObjectNode();
    ObjectNode prSnapshot = JsonSupport.MAPPER.createObjectNode();
    pullRequests.forEach((key, pr) -> prSnapshot.set(key, pr.deepCopy()));
    github.set("pullRequests", prSnapshot);

    ObjectNode state = JsonSupport.MAPPER.createObjectNode();
    state.put("schemaVersion", "1.0");
    state.put("mode", "simulated");
    state.set("aws", aws);
    state.set("jira", jira);
    state.set("github", github);
    return state;
  }

  private JsonNode filterLogEvents(JsonNode arguments, JsonNode target) {
    requireSystem(target, "aws");
    requireOnly(arguments, Set.of("logGroupName", "filterPattern", "limit"));
    String groupName = requiredText(arguments, "logGroupName");
    String filter = optionalText(arguments, "filterPattern").toLowerCase(Locale.ROOT);
    int limit = requiredInt(arguments, "limit", 1, 100);
    List<ObjectNode> events = logGroups.get(groupName);
    require(events != null, "SIMULATED_RESOURCE_NOT_FOUND", "The simulated log group does not exist.");

    ArrayNode matches = JsonSupport.MAPPER.createArrayNode();
    events.stream()
        .filter(event -> event.path("message").asText().toLowerCase(Locale.ROOT).contains(filter))
        .limit(limit)
        .forEach(event -> matches.add(event.deepCopy()));
    ObjectNode result = baseResult("aws", "aws.logs.filter_log_events");
    result.put("logGroupName", groupName);
    result.put("matchCount", matches.size());
    result.set("events", matches);
    return result;
  }

  private JsonNode updateSecurityGroup(JsonNode arguments, JsonNode target) {
    requireSystem(target, "aws");
    requireOnly(arguments, Set.of("groupId", "port", "cidr"));
    String groupId = requiredText(arguments, "groupId");
    int port = requiredInt(arguments, "port", 1, 65_535);
    String cidr = requiredText(arguments, "cidr");
    require(
        DOCUMENTATION_CIDRS.contains(cidr),
        "INVALID_SIMULATOR_ARGUMENTS",
        "The simulator accepts only documentation CIDR ranges.");
    ArrayNode rules = securityGroupRules.get(groupId);
    require(rules != null, "SIMULATED_RESOURCE_NOT_FOUND", "The simulated security group does not exist.");

    ObjectNode rule = JsonSupport.MAPPER.createObjectNode().put("port", port).put("cidr", cidr);
    boolean exists = false;
    for (JsonNode current : rules) {
      if (current.equals(rule)) {
        exists = true;
        break;
      }
    }
    if (!exists) rules.add(rule);
    ObjectNode result = baseResult("aws", "aws.ec2.update_security_group");
    result.put("groupId", groupId);
    result.put("changed", !exists);
    result.set("rules", rules.deepCopy());
    return result;
  }

  private JsonNode deleteSecurityGroup(JsonNode arguments, JsonNode target) {
    requireSystem(target, "aws");
    require(
        "prod".equals(target.path("environment").asText())
            && "simulated-123".equals(target.path("accountId").asText()),
        "INVALID_SIMULATOR_TARGET",
        "The deletion demo accepts only its fixed production-labelled simulated account.");
    requireOnly(arguments, Set.of("groupId"));
    String groupId = requiredText(arguments, "groupId");
    require(
        "sg-demo".equals(groupId),
        "INVALID_SIMULATOR_ARGUMENTS",
        "The deletion demo accepts only its fixed simulated security group.");
    require(
        securityGroupRules.containsKey(groupId),
        "SIMULATED_RESOURCE_NOT_FOUND",
        "The simulated security group does not exist.");
    securityGroupRules.remove(groupId);
    return baseResult("aws", "aws.ec2.delete_security_group")
        .put("groupId", groupId)
        .put("deleted", true);
  }

  private JsonNode createIssue(JsonNode arguments, JsonNode target) {
    requireSystem(target, "jira");
    requireOnly(arguments, Set.of("projectKey", "summary"));
    String projectKey = requiredText(arguments, "projectKey");
    String summary = requiredText(arguments, "summary");
    require(
        "SIM".equals(projectKey) && summary.length() <= 160,
        "INVALID_SIMULATOR_ARGUMENTS",
        "The simulator accepts project SIM and summaries up to 160 characters.");
    String issueKey = "SIM-" + nextIssueNumber++;
    ObjectNode issue = JsonSupport.MAPPER.createObjectNode();
    issue.put("issueKey", issueKey);
    issue.put("projectKey", projectKey);
    issue.put("summary", summary);
    issue.put("status", "OPEN");
    issues.put(issueKey, issue);
    ObjectNode result = baseResult("jira", "jira.issue.create");
    result.set("issue", issue.deepCopy());
    return result;
  }

  private JsonNode getIssue(JsonNode arguments, JsonNode target) {
    requireSystem(target, "jira");
    requireOnly(arguments, Set.of("issueKey"));
    ObjectNode issue = findIssue(requiredText(arguments, "issueKey"));
    ObjectNode result = baseResult("jira", "jira.issue.get");
    result.set("issue", issue.deepCopy());
    return result;
  }

  private JsonNode transitionIssue(JsonNode arguments, JsonNode target) {
    requireSystem(target, "jira");
    requireOnly(arguments, Set.of("issueKey", "transition"));
    ObjectNode issue = findIssue(requiredText(arguments, "issueKey"));
    String transition = requiredText(arguments, "transition");
    String status = switch (transition) {
      case "start_progress" -> "IN_PROGRESS";
      case "resolve" -> "DONE";
      default -> throw error(
          "INVALID_SIMULATOR_ARGUMENTS", "The simulated Jira transition is not supported.");
    };
    issue.put("status", status);
    ObjectNode result = baseResult("jira", "jira.issue.transition");
    result.set("issue", issue.deepCopy());
    return result;
  }

  private ObjectNode findIssue(String issueKey) {
    ObjectNode issue = issues.get(issueKey);
    require(issue != null, "SIMULATED_RESOURCE_NOT_FOUND", "The simulated Jira issue does not exist.");
    return issue;
  }

  private JsonNode createPullRequest(JsonNode arguments, JsonNode target) {
    requireSystem(target, "github");
    requireOnly(arguments, Set.of("repository", "owner", "repo", "title", "head", "base", "draft", "body"));

    String repository = "";
    if (arguments.has("repository") && arguments.path("repository").isTextual()) {
      repository = arguments.path("repository").asText().trim();
    } else if (arguments.has("owner") && arguments.has("repo")
        && arguments.path("owner").isTextual() && arguments.path("repo").isTextual()) {
      repository = arguments.path("owner").asText().trim() + "/" + arguments.path("repo").asText().trim();
    } else if (target.has("accountId") && target.path("accountId").isTextual()) {
      repository = target.path("accountId").asText().trim();
    }
    require(
        "tracepermit/agentcore-pr-pilot".equalsIgnoreCase(repository)
            || "simulated-tracepermit/agentcore-pr-pilot".equalsIgnoreCase(repository)
            || target.path("accountId").asText().startsWith("simulated-"),
        "INVALID_SIMULATOR_ARGUMENTS",
        "The simulator accepts only allowlisted pilot repository tracepermit/agentcore-pr-pilot.");

    String base = requiredText(arguments, "base");
    require(
        "main".equals(base),
        "INVALID_SIMULATOR_ARGUMENTS",
        "The simulator accepts only base branch main.");

    require(
        arguments.has("draft") && arguments.path("draft").isBoolean() && arguments.path("draft").booleanValue(),
        "INVALID_SIMULATOR_ARGUMENTS",
        "The simulator requires draft=true.");

    String head = requiredText(arguments, "head");
    require(
        !head.equals(base) && !head.isBlank(),
        "INVALID_SIMULATOR_ARGUMENTS",
        "The head branch must not equal base branch.");

    String title = requiredText(arguments, "title");
    require(
        title.length() <= 256,
        "INVALID_SIMULATOR_ARGUMENTS",
        "Title exceeds maximum length of 256 characters.");

    String body = optionalText(arguments, "body");
    int prNumber = nextPullRequestNumber++;
    String prKey = "PR-" + prNumber;

    ObjectNode pr = JsonSupport.MAPPER.createObjectNode();
    pr.put("number", prNumber);
    pr.put("id", 1000L + prNumber);
    pr.put("htmlUrl", "https://github.com/tracepermit/agentcore-pr-pilot/pull/" + prNumber);
    pr.put("state", "open");
    pr.put("draft", true);
    pr.put("title", title);
    pr.put("head", head);
    pr.put("base", base);
    pr.put("body", body);
    pullRequests.put(prKey, pr);

    ObjectNode result = baseResult("github", "github.pull_request.create");
    result.set("pullRequest", pr.deepCopy());
    return result;
  }

  private static ObjectNode baseResult(String system, String operation) {
    return JsonSupport.MAPPER.createObjectNode()
        .put("schemaVersion", "1.0")
        .put("mode", "simulated")
        .put("system", system)
        .put("operation", operation);
  }

  private static ObjectNode log(String timestamp, String message) {
    return JsonSupport.MAPPER.createObjectNode()
        .put("timestamp", Instant.parse(timestamp).toString())
        .put("message", message);
  }

  private static void requireSystem(JsonNode target, String expected) {
    require(
        expected.equals(target.path("system").asText()),
        "INVALID_SIMULATOR_TARGET",
        "The tool and simulated target system do not match.");
  }

  private static void requireOnly(JsonNode arguments, Set<String> allowed) {
    Iterator<String> names = arguments.fieldNames();
    while (names.hasNext()) {
      require(
          allowed.contains(names.next()),
          "INVALID_SIMULATOR_ARGUMENTS",
          "The simulated action contains an unsupported argument.");
    }
  }

  private static String requiredText(JsonNode object, String field) {
    JsonNode value = object.get(field);
    require(
        value != null && value.isTextual() && !value.textValue().isBlank(),
        "INVALID_SIMULATOR_ARGUMENTS",
        "A required simulated string argument is missing.");
    return value.textValue();
  }

  private static String optionalText(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null) return "";
    require(
        value.isTextual(),
        "INVALID_SIMULATOR_ARGUMENTS",
        "A simulated string argument has the wrong type.");
    return value.textValue();
  }

  private static int requiredInt(JsonNode object, String field, int minimum, int maximum) {
    JsonNode value = object.get(field);
    require(
        value != null && value.isIntegralNumber(),
        "INVALID_SIMULATOR_ARGUMENTS",
        "A required simulated integer argument is missing.");
    int number = value.intValue();
    require(
        number >= minimum && number <= maximum,
        "INVALID_SIMULATOR_ARGUMENTS",
        "A simulated integer argument is outside its allowed range.");
    return number;
  }

  private static void require(boolean condition, String code, String message) {
    if (!condition) throw error(code, message);
  }

  private static SimulatorException error(String code, String message) {
    return new SimulatorException(code, message);
  }
}
