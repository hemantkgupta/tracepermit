package io.tracepermit.adapters.remote.github.credentials;

/** Fixed diagnostics only; dependency throwables can never be attached. */
public final class GitHubCredentialException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  enum Code {
    CONFIGURATION_REJECTED, REPOSITORY_REJECTED, KEY_SOURCE_REJECTED, SIGNING_REJECTED,
    CLOCK_REJECTED, DESTINATION_REJECTED, TRANSPORT_UNAVAILABLE, RESPONSE_TOO_LARGE,
    RESPONSE_INVALID, INSTALLATION_REJECTED, TOKEN_ASSURANCE_REJECTED, TOKEN_EXPIRY_REJECTED,
    CLEANUP_FAILED
  }
  GitHubCredentialException(Code code) { super(code.name(), null, false, true); }
  public String errorCode() { return getMessage(); }
  @Override public synchronized Throwable initCause(Throwable cause) {
    throw new IllegalStateException("CREDENTIAL_CAUSE_FORBIDDEN");
  }
  static GitHubCredentialException reject(Code code) { return new GitHubCredentialException(code); }
}
