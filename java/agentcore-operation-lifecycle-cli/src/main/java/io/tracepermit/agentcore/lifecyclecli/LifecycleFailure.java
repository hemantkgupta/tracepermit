package io.tracepermit.agentcore.lifecyclecli;

/** Fixed installed-command failure with no retained cause or private diagnostic. */
final class LifecycleFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;
  final String code;

  LifecycleFailure(String code) {
    super(code, null, false, false);
    this.code = code;
  }

  @Override
  public synchronized Throwable initCause(Throwable cause) {
    return this;
  }
}
