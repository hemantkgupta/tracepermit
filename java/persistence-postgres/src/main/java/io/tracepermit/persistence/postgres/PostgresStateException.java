package io.tracepermit.persistence.postgres;

public final class PostgresStateException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public PostgresStateException(String message, Throwable cause) {
    super(message, cause);
  }
}
