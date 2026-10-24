package io.tracepermit.persistence.mariadb;

/** Fail-closed wrapper for MariaDB lifecycle and durable-state failures. */
public final class MariaDbStateException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public MariaDbStateException(String message, Throwable cause) {
    super(message, cause);
  }
}
