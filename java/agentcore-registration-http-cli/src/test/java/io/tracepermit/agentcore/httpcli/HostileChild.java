package io.tracepermit.agentcore.httpcli;

import java.io.DataOutputStream;

/** Test-only worker permutations for supervisor containment. */
public final class HostileChild {
  private HostileChild() {}

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    if (mode.equals("silent")) {
      Thread.sleep(30_000);
      return;
    }
    if (mode.equals("stderr")) {
      System.err.print("SYNTHETIC_CANARY");
      System.err.flush();
      return;
    }
    if (mode.equals("malformed")) {
      System.out.write(new byte[] {0, 0, 0, 2, '{'});
      System.out.flush();
      return;
    }
    if (mode.equals("valid-check")) {
      send(SessionReceipt.failure("CONFIG_VALID"));
      return;
    }
    if (mode.equals("valid-serve")) {
      send(SessionReceipt.ready(12_345));
      send(SessionReceipt.finalFor("SESSION_ENDED", 12_345));
      return;
    }
    if (mode.equals("extra")) {
      send(SessionReceipt.ready(12_345));
      send(SessionReceipt.finalFor("SESSION_ENDED", 12_345));
      send(SessionReceipt.finalFor("SESSION_ENDED", 12_345));
      return;
    }
    if (mode.equals("ready-hang")) {
      send(SessionReceipt.ready(12_345));
      while (true) {
        try {
          Thread.sleep(30_000);
        } catch (InterruptedException ignored) {
        }
      }
    }
    if (mode.equals("wrong-exit")) {
      send(SessionReceipt.failure("CONFIG_VALID"));
      Runtime.getRuntime().halt(1);
    }
  }

  private static void send(SessionReceipt receipt) throws Exception {
    byte[] bytes = SessionProtocol.bytes(receipt);
    var output = new DataOutputStream(System.out);
    output.writeInt(bytes.length);
    output.write(bytes);
    output.flush();
  }
}
