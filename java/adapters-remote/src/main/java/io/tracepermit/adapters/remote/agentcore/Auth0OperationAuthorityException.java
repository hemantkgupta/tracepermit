package io.tracepermit.adapters.remote.agentcore;

/** Fixed diagnostics; never exposes a provider token or nested exception. */
public final class Auth0OperationAuthorityException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  Auth0OperationAuthorityException(String code) { super(code, null, false, false); }
}
