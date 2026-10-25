package io.tracepermit.persistence.identity;

import java.util.Objects;

/**
 * Deterministic runtime exception thrown when durable workload-identity replay operations fail.
 *
 * <p>To prevent sensitive database diagnostics, tokens, parameters, or credentials from escaping
 * into log sinks or error responses, underlying JDBC causes and suppressed exceptions are
 * deliberately discarded.
 */
public final class WorkloadIdentityReplayException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Code {
    REPLAY_STORAGE_UNAVAILABLE,
    REPLAY_COMMIT_UNKNOWN,
    REPLAY_SCHEMA_NOT_READY,
    REPLAY_STORAGE_READ_ONLY,
    REPLAY_STORAGE_PERMISSION_DENIED,
    REPLAY_INPUT_INVALID,
    REPLAY_CAPACITY_MISMATCH
  }

  private final Code code;

  public WorkloadIdentityReplayException(Code code) {
    super(code.name(), null, false, true);
    this.code = Objects.requireNonNull(code, "code");
  }

  @Override
  public synchronized Throwable initCause(Throwable cause) {
    return this;
  }

  public Code code() {
    return code;
  }
}
