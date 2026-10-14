package io.tracepermit.identity;

public final class AuthenticationException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;
  private final String diagnosticCode;

  public AuthenticationException(String code, String message) {
    this(code, message, "UNSPECIFIED");
  }

  public AuthenticationException(String code, String message, String diagnosticCode) {
    super(message);
    this.code = code;
    if (diagnosticCode == null || !diagnosticCode.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
      throw new IllegalArgumentException("Authentication diagnostic code is invalid.");
    }
    this.diagnosticCode = diagnosticCode;
  }

  public String code() {
    return code;
  }

  public String diagnosticCode() {
    return diagnosticCode;
  }
}
