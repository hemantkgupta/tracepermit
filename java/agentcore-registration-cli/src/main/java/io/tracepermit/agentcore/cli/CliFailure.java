package io.tracepermit.agentcore.cli;

final class CliFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;
  final String code;
  CliFailure(String code) { super(code, null, false, false); this.code=code; }
  @Override public synchronized Throwable initCause(Throwable cause) { return this; }
}
