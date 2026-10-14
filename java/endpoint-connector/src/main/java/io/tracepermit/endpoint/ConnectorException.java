package io.tracepermit.endpoint;

public final class ConnectorException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public ConnectorException(String code, String safeMessage) {
    super(safeMessage);
    this.code = requireCode(code);
  }

  public ConnectorException(String code, String safeMessage, Throwable cause) {
    super(safeMessage, cause);
    this.code = requireCode(code);
  }

  public String code() {
    return code;
  }

  private static String requireCode(String value) {
    if (value == null || !value.matches("^[A-Z][A-Z0-9_]{1,63}$")) {
      throw new IllegalArgumentException("A bounded connector error code is required.");
    }
    return value;
  }
}
