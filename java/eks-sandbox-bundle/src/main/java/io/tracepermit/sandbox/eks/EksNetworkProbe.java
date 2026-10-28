package io.tracepermit.sandbox.eks;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Explicit human-run TCP observations only: no DNS, HTTP, credentials or target mutation. */
public final class EksNetworkProbe {
  private EksNetworkProbe() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out));
  }

  static int run(String[] args, PrintStream output) {
    if (args.length != 3) {
      output.println("PROBE_USAGE");
      return 64;
    }
    try {
      for (String ip : args) EksSandboxBundle.ipv4(ip);
      boolean control = connect(args[0], 18080);
      int forbidden = 0;
      if (connect(args[1], 443)) forbidden++;
      if (connect(args[2], 443)) forbidden++;
      if (connect("169.254.169.254", 80)) forbidden++;
      if (connect("169.254.170.23", 80)) forbidden++;
      return report(control, forbidden, output);
    } catch (IllegalArgumentException invalid) {
      output.println("PROBE_REFUSED");
      return 64;
    }
  }

  static int report(boolean control, int forbidden, PrintStream output) {
    if (!control) {
      output.println("PROBE_INCONCLUSIVE_CONTROL_UNAVAILABLE");
      return 2;
    }
    output.println(forbidden == 0 ? "DENIAL_OBSERVATIONS_RECORDED" : "BYPASS_CONNECTION_OBSERVED");
    return forbidden == 0 ? 0 : 1;
  }

  static boolean connect(String ip, int port) {
    byte[] address = EksSandboxBundle.ipv4(ip);
    EksSandboxBundle.require(port >= 1 && port <= 65535);
    try (var socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getByAddress(address), port), 500);
      return true;
    } catch (IOException | SecurityException denied) {
      return false;
    }
  }
}
