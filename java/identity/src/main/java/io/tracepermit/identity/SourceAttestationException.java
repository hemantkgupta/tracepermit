package io.tracepermit.identity;

public final class SourceAttestationException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public SourceAttestationException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
