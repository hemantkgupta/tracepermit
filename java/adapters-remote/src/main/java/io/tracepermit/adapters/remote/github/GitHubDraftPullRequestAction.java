package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.ActionValidator;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates and normalizes the canonical action for creating a draft pull request.
 * Enforces pilot repository and branch allowlists, draft=true invariant, and schema boundaries.
 */
public record GitHubDraftPullRequestAction(
    String eventId,
    String toolName,
    String repository,
    String title,
    String head,
    String base,
    boolean draft,
    String body,
    String environment) {

  public static final String OPERATION = "github.pull_request.create";
  public static final String ALLOWED_REPOSITORY = "tracepermit/agentcore-pr-pilot";
  public static final String ALLOWED_BASE_BRANCH = "main";
  public static final String ALLOWED_TARGET_SYSTEM = "github";

  private static final Pattern HEAD_REF_PATTERN = Pattern.compile("^[a-zA-Z0-9._/-]{1,255}$");
  private static final Set<String> ALLOWED_TARGET_FIELDS =
      Set.of("system", "environment", "accountId", "resourceType");
  private static final Set<String> ALLOWED_ARGUMENT_FIELDS =
      Set.of("repository", "owner", "repo", "title", "head", "base", "draft", "body");
  private static final Set<String> FORBIDDEN_SELF_ASSERTION_FIELDS =
      Set.of("authenticated", "delegated", "verified", "approved", "sanitized");

  public GitHubDraftPullRequestAction {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(toolName, "toolName must not be null");
    Objects.requireNonNull(repository, "repository must not be null");
    Objects.requireNonNull(title, "title must not be null");
    Objects.requireNonNull(head, "head must not be null");
    Objects.requireNonNull(base, "base must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
  }

  public static GitHubDraftPullRequestAction fromCanonical(JsonNode event) {
    Objects.requireNonNull(event, "event must not be null");

    // Fail closed on raw input before normalizer can silently drop unknown fields
    JsonNode rawAction = event.path("action");
    if (rawAction.isObject()) {
      if (rawAction.path("target").isObject()) {
        validateFields(rawAction.path("target"), ALLOWED_TARGET_FIELDS, "UNSUPPORTED_TARGET_FIELD");
      }
      if (rawAction.path("arguments").isObject()) {
        checkForSelfAssertions(rawAction.path("arguments"));
        validateFields(rawAction.path("arguments"), ALLOWED_ARGUMENT_FIELDS, "UNSUPPORTED_ARGUMENT");
      }
    }

    JsonNode validated = ActionValidator.validate(event);

    String eventId = validated.path("eventId").asText();
    JsonNode actionNode = validated.path("action");
    if (!actionNode.isObject()) {
      throw new GitHubPullRequestException(
          "INVALID_ACTION", "MALFORMED_INPUT", "The action node must be an object.");
    }

    String toolName = actionNode.path("toolName").asText();
    if (!OPERATION.equals(toolName)) {
      throw new GitHubPullRequestException(
          "UNKNOWN_TOOL", "TOOL_NOT_SUPPORTED", "The requested tool is not supported by this adapter: " + toolName);
    }

    JsonNode targetNode = actionNode.path("target");
    if (!targetNode.isObject()) {
      throw new GitHubPullRequestException(
          "INVALID_TARGET", "MALFORMED_INPUT", "The action target must be an object.");
    }

    String system = targetNode.path("system").asText();
    if (!ALLOWED_TARGET_SYSTEM.equals(system)) {
      throw new GitHubPullRequestException(
          "INVALID_TARGET_SYSTEM", "PILOT_BOUNDARY_VIOLATION", "The target system must be 'github'.");
    }
    if (!"pull_request".equals(targetNode.path("resourceType").asText())) {
      throw GitHubApiTransport.rejected("INVALID_TARGET_RESOURCE");
    }

    String environment = targetNode.path("environment").asText();

    JsonNode argumentsNode = actionNode.path("arguments");
    if (!argumentsNode.isObject()) {
      throw new GitHubPullRequestException(
          "INVALID_ARGUMENTS", "MALFORMED_INPUT", "The action arguments must be an object.");
    }

    // Repository extraction & allowlisting
    String repository = resolveRepository(argumentsNode, targetNode);
    if (!ALLOWED_REPOSITORY.equalsIgnoreCase(repository)) {
      throw new GitHubPullRequestException(
          "REPOSITORY_NOT_ALLOWLISTED",
          "PILOT_BOUNDARY_VIOLATION",
          "Repository is not allowlisted for the AgentCore pilot: " + repository);
    }

    // Base branch extraction & allowlisting
    JsonNode baseNode = argumentsNode.path("base");
    if (!baseNode.isTextual() || baseNode.asText().isBlank()) {
      throw new GitHubPullRequestException(
          "MISSING_BASE_BRANCH", "MALFORMED_INPUT", "A non-empty base branch argument is required.");
    }
    String base = baseNode.asText().trim();
    if (!ALLOWED_BASE_BRANCH.equals(base)) {
      throw new GitHubPullRequestException(
          "BASE_BRANCH_NOT_ALLOWLISTED",
          "PILOT_BOUNDARY_VIOLATION",
          "Base branch must strictly be '" + ALLOWED_BASE_BRANCH + "'; received: " + base);
    }

    // Draft=true enforcement (invariant: draft must always be true)
    JsonNode draftNode = argumentsNode.path("draft");
    if (!draftNode.isBoolean() || !draftNode.booleanValue()) {
      throw new GitHubPullRequestException(
          "DRAFT_REQUIRED_TRUE",
          "PILOT_BOUNDARY_VIOLATION",
          "Draft pull request creation requires draft=true.");
    }

    // Head branch validation
    JsonNode headNode = argumentsNode.path("head");
    if (!headNode.isTextual() || headNode.asText().isBlank()) {
      throw new GitHubPullRequestException(
          "MISSING_HEAD_BRANCH", "MALFORMED_INPUT", "A non-empty head branch argument is required.");
    }
    String head = headNode.asText().trim();
    if (!HEAD_REF_PATTERN.matcher(head).matches()
        || head.startsWith("-")
        || head.startsWith("/")
        || head.endsWith("/")
        || head.contains("..")) {
      throw new GitHubPullRequestException(
          "INVALID_HEAD_BRANCH", "MALFORMED_INPUT", "Head branch format is invalid.");
    }
    if (ALLOWED_BASE_BRANCH.equals(head)) {
      throw new GitHubPullRequestException(
          "HEAD_EQUALS_BASE", "PILOT_BOUNDARY_VIOLATION", "Head branch cannot equal base branch.");
    }

    // Title validation
    JsonNode titleNode = argumentsNode.path("title");
    if (!titleNode.isTextual() || titleNode.asText().isBlank()) {
      throw new GitHubPullRequestException(
          "MISSING_TITLE", "MALFORMED_INPUT", "A non-empty pull request title is required.");
    }
    String title = titleNode.asText().trim();
    if (title.length() > 256) {
      throw new GitHubPullRequestException(
          "INVALID_TITLE", "BOUNDS_EXCEEDED", "Pull request title exceeds maximum length of 256 characters.");
    }

    // Body validation (optional)
    String body = "";
    if (argumentsNode.has("body")) {
      JsonNode bodyNode = argumentsNode.path("body");
      if (!bodyNode.isTextual()) {
        throw new GitHubPullRequestException(
            "INVALID_BODY", "MALFORMED_INPUT", "Pull request body must be a string.");
      }
      body = bodyNode.asText();
      if (body.length() > 65_536) {
        throw new GitHubPullRequestException(
            "INVALID_BODY", "BOUNDS_EXCEEDED", "Pull request body exceeds maximum length of 65,536 characters.");
      }
    }

    return new GitHubDraftPullRequestAction(
        eventId,
        toolName,
        repository.toLowerCase(Locale.ROOT),
        title,
        head,
        base,
        true,
        body,
        environment);
  }

  private static void validateFields(JsonNode node, Set<String> allowedFields, String errorCode) {
    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!allowedFields.contains(name)) {
        throw new GitHubPullRequestException(
            errorCode, "UNKNOWN_FIELD", "Unsupported field encountered: " + name);
      }
    }
  }

  private static void checkForSelfAssertions(JsonNode arguments) {
    for (String forbidden : FORBIDDEN_SELF_ASSERTION_FIELDS) {
      if (arguments.has(forbidden)) {
        throw new GitHubPullRequestException(
            "SELF_ASSERTION_FORBIDDEN",
            "SECURITY_VIOLATION",
            "Request arguments must not self-assert security state: " + forbidden);
      }
    }
  }

  private static String resolveRepository(JsonNode arguments, JsonNode target) {
    String resolved = null;
    if (arguments.has("repository")) {
      if (!arguments.path("repository").isTextual()) throw GitHubApiTransport.rejected("REPOSITORY_BINDING_INVALID");
      resolved = arguments.path("repository").textValue().trim();
    }
    if (arguments.has("owner") || arguments.has("repo")) {
      if (!arguments.path("owner").isTextual() || !arguments.path("repo").isTextual()) {
        throw GitHubApiTransport.rejected("REPOSITORY_BINDING_INVALID");
      }
      String split = arguments.path("owner").textValue().trim() + "/" + arguments.path("repo").textValue().trim();
      if (resolved != null && !resolved.equalsIgnoreCase(split)) throw GitHubApiTransport.rejected("REPOSITORY_BINDING_INVALID");
      resolved = split;
    }
    if (target.has("accountId")) {
      if (!target.path("accountId").isTextual()) throw GitHubApiTransport.rejected("REPOSITORY_BINDING_INVALID");
      String account = target.path("accountId").textValue().trim();
      if (resolved != null && !resolved.equalsIgnoreCase(account)) throw GitHubApiTransport.rejected("REPOSITORY_BINDING_INVALID");
      resolved = account;
    }
    if (resolved == null || resolved.isBlank()) throw GitHubApiTransport.rejected("MISSING_REPOSITORY");
    return resolved;
  }
}
