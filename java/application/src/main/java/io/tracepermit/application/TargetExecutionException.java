package io.tracepermit.application;

/** A bounded target-adapter failure that confirms no successful target effect. */
public class TargetExecutionException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public TargetExecutionException(String code, String message) {
    super(message);
    this.code = code;
  }

  public final String code() {
    return code;
  }
}
