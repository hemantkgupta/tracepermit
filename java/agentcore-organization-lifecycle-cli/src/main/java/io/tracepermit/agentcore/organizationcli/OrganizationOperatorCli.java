package io.tracepermit.agentcore.organizationcli;

import io.tracepermit.agentcore.cli.InstalledPrivateInput;
import java.nio.file.Path;
import java.time.Duration;

/** Installed private source-free organization-profile synthetic operator. */
public final class OrganizationOperatorCli {
  private OrganizationOperatorCli() {}

  public static void main(String[] args) {
    long started = System.nanoTime();
    var supervisor = new OrganizationOperatorSupervisor();
    var hook = new Thread(supervisor::stop, "organization-operator-owned-child-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    OrganizationOperatorReceipt result;
    String command = args.length == 1 ? args[0] : "UNKNOWN";
    try {
      if (args.length != 1 || !OrganizationOperatorProtocol.COMMANDS.contains(command)) {
        result = OrganizationOperatorReceipt.failure(command, "USAGE");
      } else if (!InstalledPrivateInput.anonymousPipe()) {
        result = OrganizationOperatorReceipt.failure(command, "INPUT_INVALID");
      } else {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder =
            new ProcessBuilder(
                java,
                "-Xms16m",
                "-Xmx64m",
                "-XX:ActiveProcessorCount=2",
                "-cp",
                System.getProperty("java.class.path"),
                OrganizationOperatorWorker.class.getName(),
                command);
        builder.environment().clear();
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        Duration available =
            Duration.ofNanos(
                Math.max(
                    1,
                    OrganizationOperatorSupervisor.TOTAL.minusSeconds(1).toNanos()
                        - (System.nanoTime() - started)));
        result =
            supervisor.run(builder, available, OrganizationOperatorSupervisor.CLEANUP);
      }
    } catch (Throwable failure) {
      supervisor.stop();
      result = OrganizationOperatorReceipt.failure(command, "UNKNOWN");
    }
    Runtime.getRuntime().removeShutdownHook(hook);
    boolean sent =
        OrganizationOperatorSupervisor.send(System.out, result.bytes(), Duration.ofSeconds(1));
    Runtime.getRuntime().halt(sent ? result.exitCode() : 1);
  }
}
