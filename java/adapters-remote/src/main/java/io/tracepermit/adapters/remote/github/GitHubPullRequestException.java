package io.tracepermit.adapters.remote.github;

import java.util.Objects;

/**
 * Redacted exception for bounded GitHub pull request adapter operations.
 * Error messages and causes are sanitized to prevent credential leakage.
 */
public final class GitHubPullRequestException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String errorCode;
  private final String reasonCode;

  public GitHubPullRequestException(String errorCode, String reasonCode, String message) {
    super("GitHub pull request operation rejected.");
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
  }

  public GitHubPullRequestException(String errorCode, String reasonCode, String message, Throwable cause) {
    super("GitHub pull request operation rejected.");
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
  }

  public String errorCode() {
    return errorCode;
  }

  public String reasonCode() {
    return reasonCode;
  }

}
