package io.tracepermit.adapters.remote.agentcore;

/** Closed, redacted Okta authority diagnostic. */
public final class OktaOperationAuthorityException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  OktaOperationAuthorityException(String code) {
    super(code, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
