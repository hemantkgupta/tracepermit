package io.tracepermit.sandbox.eks;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

/** Source-free offline CLI. Does not load kubeconfig, execute a process or contact a cluster. */
public final class EksAcceptanceMain {
  private EksAcceptanceMain() {}

  public static void main(String[] args) { System.exit(run(args, System.out)); }

  static int run(String[] args, PrintStream output) {
    boolean prepare = args.length == 3 && Set.of("prepare", "catalog").contains(args[0]);
    boolean assess = args.length == 4 && "assess".equals(args[0]);
    if (!prepare && !assess) { output.println("EKS_ACCEPTANCE_USAGE"); return 64; }
    try {
      var plan = EksAcceptanceKit.parsePlan(EksSandboxMain.readPrivate(Path.of(args[1]), EksAcceptanceKit.PLAN_BYTES));
      var result = assess ? EksAcceptanceKit.assess(plan,
          EksSandboxMain.readPrivate(Path.of(args[2]), EksAcceptanceKit.SNAPSHOT_BYTES), Instant.now())
          : args[0].equals("prepare") ? EksAcceptanceKit.render(plan) : EksAcceptanceKit.catalog(plan);
      EksSandboxMain.writeNewPrivate(Path.of(args[assess ? 3 : 2]),
          EksSandboxBundle.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
      boolean blocked = "BLOCKED".equals(result.path("status").asText());
      output.println(blocked ? "EKS_ACCEPTANCE_BLOCKED" : assess ? "EKS_ACCEPTANCE_REVIEW_REQUIRED" : "EKS_ACCEPTANCE_PREPARED");
      return blocked ? 1 : 0;
    } catch (Exception invalid) { output.println("EKS_ACCEPTANCE_REFUSED"); return 1; }
  }
}
