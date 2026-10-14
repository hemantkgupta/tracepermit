package io.tracepermit.identity;

public final class WorkloadCredentialException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public WorkloadCredentialException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
