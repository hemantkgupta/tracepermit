package io.tracepermit.adapters.remote.github;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Redacted exception for bounded GitHub pull request adapter operations.
 * Error messages and causes are sanitized to prevent credential leakage.
 */
public final class GitHubPullRequestException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private static final Pattern TOKEN_PATTERN = Pattern.compile("(ghs_[a-zA-Z0-9_]{10,}|Bearer\\s+[a-zA-Z0-9_.-]+)", Pattern.CASE_INSENSITIVE);
  private final String errorCode;
  private final String reasonCode;

  public GitHubPullRequestException(String errorCode, String reasonCode, String message) {
    super(sanitize(message));
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
  }

  public GitHubPullRequestException(String errorCode, String reasonCode, String message, Throwable cause) {
    super(sanitize(message), sanitizeCause(cause));
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
  }

  public String errorCode() {
    return errorCode;
  }

  public String reasonCode() {
    return reasonCode;
  }

  static String sanitize(String message) {
    if (message == null) {
      return "An error occurred during GitHub pull request processing.";
    }
    return TOKEN_PATTERN.matcher(message).replaceAll("[REDACTED_CREDENTIAL]");
  }

  static Throwable sanitizeCause(Throwable cause) {
    if (cause == null) {
      return null;
    }
    String message = cause.getMessage();
    if (message != null && TOKEN_PATTERN.matcher(message).find()) {
      return new RuntimeException(sanitize(message));
    }
    return cause;
  }
}
