package io.tracepermit.auth0reviewer;

import io.tracepermit.identity.AuthenticatedConnector;
import java.util.Arrays;

/** Package-private in-memory handoff only. Never serialized, persisted or printed. */
final class ReviewCredential implements AutoCloseable {
  private final AuthenticatedConnector identity;
  private byte[] token;

  ReviewCredential(AuthenticatedConnector identity, byte[] token) {
    this.identity = java.util.Objects.requireNonNull(identity);
    this.token = java.util.Objects.requireNonNull(token);
  }

  AuthenticatedConnector identity() { return identity; }

  synchronized byte[] copy() {
    if (token == null) throw new IllegalStateException("REVIEW_CREDENTIAL_CLOSED");
    return token.clone();
  }

  @Override public synchronized void close() {
    if (token != null) Arrays.fill(token, (byte) 0);
    token = null;
  }

  @Override public String toString() { return "ReviewCredential[redacted]"; }
}
