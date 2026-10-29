package io.tracepermit.agentcore.organizationcli;

import java.util.Arrays;

/** Isolated process fixture for the finite organization-operator supervisor. */
public final class OrganizationOperatorSupervisorChild {
  private OrganizationOperatorSupervisorChild() {}

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    if (mode.equals("valid") || mode.equals("mismatch")) {
      System.out.write(OrganizationOperatorReceipt.failure("inspect", "INPUT_INVALID").bytes());
      System.out.flush();
      System.exit(mode.equals("valid") ? 1 : 0);
    }
    if (mode.equals("stderr")) {
      System.err.print("private");
      System.err.flush();
      System.exit(1);
    }
    if (mode.equals("oversize")) {
      byte[] output = new byte[OrganizationOperatorProtocol.RECEIPT_BYTES + 1];
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
