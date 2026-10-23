package io.tracepermit.application;

/** Fixed diagnostics for an uncommitted proposal, with no payload or nested cause. */
public final class BusinessOperationTransitionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Code {
    OPERATION_SCOPE_MISMATCH,
    OPERATION_STATE_STALE,
    OPERATION_TRANSITION_INVALID,
    OPERATION_TOO_LARGE
  }

  private final Code code;

  public BusinessOperationTransitionException(Code code) {
    super(code.name());
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
