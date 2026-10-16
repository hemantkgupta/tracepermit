package io.tracepermit.policy;

/**
 * Thrown when an optimistic concurrency precondition fails during policy lifecycle transitions.
 */
public final class PolicyConcurrencyException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public PolicyConcurrencyException(String message) {
    super(message);
  }

  public PolicyConcurrencyException(String message, Throwable cause) {
    super(message, cause);
  }
}
