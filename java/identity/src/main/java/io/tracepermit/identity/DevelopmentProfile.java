package io.tracepermit.identity;

import java.util.List;

public enum DevelopmentProfile {
  CHATGPT_READONLY(
      "chatgpt-readonly",
      "user:alice@example.invalid",
      "Alice (development)",
      ConnectorOrigin.CHATGPT_WEB,
      "client:chatgpt-web",
      "ChatGPT Web (development)",
      List.of("aws.logs.discover", "aws.logs.read")),
  CHATGPT_DISCOVERY_ONLY(
      "chatgpt-discovery-only",
      "user:discovery@example.invalid",
      "Discovery-only user (development)",
      ConnectorOrigin.CHATGPT_WEB,
      "client:chatgpt-web",
      "ChatGPT Web (development)",
      List.of("aws.logs.discover")),
  CHATGPT_NO_ACCESS(
      "chatgpt-no-access",
      "user:no-access@example.invalid",
      "No-access user (development)",
      ConnectorOrigin.CHATGPT_WEB,
      "client:chatgpt-web",
      "ChatGPT Web (development)",
      List.of()),
  CLAUDE_READONLY(
      "claude-readonly",
      "user:claude-reader@example.invalid",
      "Claude reader (development)",
      ConnectorOrigin.CLAUDE_WEB,
      "client:claude-web",
      "Claude Web (development)",
      List.of("aws.logs.discover", "aws.logs.read")),
  CLI_READONLY(
      "cli-readonly",
      "user:cli-operator@example.invalid",
      "CLI operator (development)",
      ConnectorOrigin.CLI,
      "client:tracepermit-cli",
      "TracePermit managed CLI (development)",
      List.of("aws.logs.discover", "aws.logs.read"));

  public static final String ORGANIZATION_ID = "tracepermit-development";

  private final String id;
  private final String subjectId;
  private final String displayName;
  private final ConnectorOrigin origin;
  private final String clientId;
  private final String clientName;
  private final List<String> scopes;

  DevelopmentProfile(
      String id,
      String subjectId,
      String displayName,
      ConnectorOrigin origin,
      String clientId,
      String clientName,
      List<String> scopes) {
    this.id = id;
    this.subjectId = subjectId;
    this.displayName = displayName;
    this.origin = origin;
    this.clientId = clientId;
    this.clientName = clientName;
    this.scopes = List.copyOf(scopes);
  }

  public String id() {
    return id;
  }

  public String subjectId() {
    return subjectId;
  }

  public String displayName() {
    return displayName;
  }

  public ConnectorOrigin origin() {
    return origin;
  }

  public String clientId() {
    return clientId;
  }

  public String clientName() {
    return clientName;
  }

  public List<String> scopes() {
    return scopes;
  }
}
