package io.tracepermit.security;

public final class ExecutionPermitException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public ExecutionPermitException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
