package io.tracepermit.agentcore.cli;

/** Positive stalled/pressure fixtures for this module's supervisor; never in the distribution. */
public final class HostileChild {
  private HostileChild() {}
  public static void main(String[] args) throws Exception {
    switch (args[0]) {
      case "valid" -> { System.out.writeBytes(Receipt.failure("CONFIG_VALID").bytes()); System.out.flush(); }
      case "exit" -> Runtime.getRuntime().halt(3);
      case "stderr" -> { for (;;) { System.err.write(new byte[4096]); System.err.flush(); } }
      case "stdout" -> { for (;;) { System.out.write(new byte[4096]); System.out.flush(); } }
      case "read" -> { System.out.print("READY"); System.out.flush(); System.in.read(); }
      case "stall" -> { System.out.print("READY"); System.out.flush(); Thread.sleep(60_000); }
      case "late-exit" -> { System.out.writeBytes(Receipt.failure("CONFIG_VALID").bytes()); System.out.flush(); Thread.sleep(60_000); }
      case "canary" -> { System.err.print("SYNTHETIC_DIAGNOSTIC_CANARY"); System.err.flush(); }
      default -> throw new AssertionError("FIXTURE_MODE_INVALID");
    }
  }
}
