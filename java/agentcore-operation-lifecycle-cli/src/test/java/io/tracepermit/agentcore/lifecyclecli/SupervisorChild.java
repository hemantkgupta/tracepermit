package io.tracepermit.agentcore.lifecyclecli;

import java.util.Arrays;

/** Isolated process fixture for exercising the finite lifecycle supervisor. */
public final class SupervisorChild {
  private SupervisorChild() {}

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    if (mode.equals("valid") || mode.equals("mismatch")) {
      System.out.write(LifecycleReceipt.failure("inspect", "INPUT_INVALID").bytes());
      System.out.flush();
      System.exit(mode.equals("valid") ? 1 : 0);
    }
    if (mode.equals("stderr")) {
      System.err.print("private");
      System.err.flush();
      System.exit(1);
    }
    if (mode.equals("oversize")) {
      byte[] output = new byte[LifecycleProtocol.RECEIPT_BYTES + 1];
      Arrays.fill(output, (byte) 'x');
      System.out.write(output);
      System.out.flush();
      System.exit(1);
    }
    if (mode.equals("invalid")) {
      System.out.print("not-json\n");
      System.out.flush();
      System.exit(1);
    }
    Thread.sleep(30_000);
  }
}
