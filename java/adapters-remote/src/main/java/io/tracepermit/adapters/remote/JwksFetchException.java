package io.tracepermit.adapters.remote;

public final class JwksFetchException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  JwksFetchException() {
    super("The configured OIDC verification keys could not be fetched safely.");
  }
}
