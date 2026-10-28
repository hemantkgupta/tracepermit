package io.tracepermit.agentcore.organizationcli;

/** Stable installed-command failure without retained causes or private diagnostics. */
final class OrganizationFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;
  final String code;

  OrganizationFailure(String code) {
    super(code, null, false, false);
    this.code = code;
  }

  @Override
  public synchronized Throwable initCause(Throwable cause) {
    return this;
  }
}
