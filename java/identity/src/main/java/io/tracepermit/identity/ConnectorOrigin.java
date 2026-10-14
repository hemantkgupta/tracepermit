package io.tracepermit.identity;

public enum ConnectorOrigin {
  CHATGPT_WEB("chatgpt-web"),
  CLAUDE_WEB("claude-web"),
  CLI("cli");

  private final String value;

  ConnectorOrigin(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  static ConnectorOrigin parse(String value) {
    for (ConnectorOrigin origin : values()) {
      if (origin.value.equals(value)) return origin;
    }
    throw new AuthenticationException("INVALID_ACCESS_TOKEN", "The bearer token contains invalid claims.");
  }
}
