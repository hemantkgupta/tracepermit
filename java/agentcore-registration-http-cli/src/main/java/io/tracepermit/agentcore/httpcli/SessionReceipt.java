package io.tracepermit.agentcore.httpcli;

import java.util.Set;

/** Lifecycle-only receipt. It never acknowledges a registration or grants authority. */
record SessionReceipt(String phase, String outcome, String host, Integer port) {
  private static final Set<String> OUTCOMES =
      Set.of(
          "LISTENING",
          "CONFIG_VALID",
          "SESSION_ENDED",
          "USAGE",
          "INPUT_INVALID",
          "CONFIG_INVALID",
          "NOT_READY",
          "START_FAILED",
          "UNKNOWN");

  SessionReceipt {
    if (!Set.of("READY", "FINAL").contains(phase) || !OUTCOMES.contains(outcome)) {
      throw new IllegalArgumentException("SESSION_RECEIPT_INVALID");
    }
    if (phase.equals("READY") != outcome.equals("LISTENING")) {
      throw new IllegalArgumentException("SESSION_RECEIPT_INVALID");
    }
    boolean listening = phase.equals("READY") && outcome.equals("LISTENING");
    boolean boundFinal = phase.equals("FINAL") && Set.of("SESSION_ENDED", "UNKNOWN").contains(outcome)
        && port != null;
    if (listening || boundFinal) {
      if (!"127.0.0.1".equals(host) || port == null || port < 1 || port > 65_535) {
        throw new IllegalArgumentException("SESSION_RECEIPT_INVALID");
      }
    } else if (host != null || port != null || phase.equals("READY")) {
      throw new IllegalArgumentException("SESSION_RECEIPT_INVALID");
    }
    if (outcome.equals("SESSION_ENDED") && port == null) {
      throw new IllegalArgumentException("SESSION_RECEIPT_INVALID");
    }
  }

  static SessionReceipt ready(int port) {
    return new SessionReceipt("READY", "LISTENING", "127.0.0.1", port);
  }

  static SessionReceipt failure(String outcome) {
    return new SessionReceipt("FINAL", outcome, null, null);
  }

  static SessionReceipt finalFor(String outcome, int port) {
    return new SessionReceipt("FINAL", outcome, "127.0.0.1", port);
  }

  int exitCode() {
    if (outcome.equals("CONFIG_VALID") || outcome.equals("SESSION_ENDED")) return 0;
    if (outcome.equals("USAGE")) return 64;
    return 1;
  }

  @Override
  public String toString() {
    return "SessionReceipt[phase=" + phase + ",outcome=" + outcome + "]";
  }
}
