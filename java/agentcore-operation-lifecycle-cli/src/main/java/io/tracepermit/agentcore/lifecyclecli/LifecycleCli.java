package io.tracepermit.agentcore.lifecyclecli;

import io.tracepermit.agentcore.cli.InstalledPrivateInput;
import java.nio.file.Path;
import java.time.Duration;

/** Installed private source-free synthetic lifecycle command. */
public final class LifecycleCli {
  private LifecycleCli() {}

  public static void main(String[] args) {
    long started = System.nanoTime();
    var supervisor = new LifecycleSupervisor();
    var hook = new Thread(supervisor::stop, "operation-lifecycle-owned-child-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    LifecycleReceipt result;
    String command = args.length == 1 ? args[0] : "UNKNOWN";
    try {
      if (args.length != 1 || !LifecycleProtocol.COMMANDS.contains(command)) {
        result = LifecycleReceipt.failure(command, "USAGE");
      } else if (!InstalledPrivateInput.anonymousPipe()) {
        result = LifecycleReceipt.failure(command, "INPUT_INVALID");
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
                LifecycleWorker.class.getName(),
                command);
        builder.environment().clear();
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        Duration available =
            Duration.ofNanos(
                Math.max(
                    1,
                    LifecycleSupervisor.TOTAL.minusSeconds(1).toNanos()
                        - (System.nanoTime() - started)));
        result = supervisor.run(builder, available, LifecycleSupervisor.CLEANUP);
      }
    } catch (Throwable failure) {
      supervisor.stop();
      result = LifecycleReceipt.failure(command, "UNKNOWN");
    }
    Runtime.getRuntime().removeShutdownHook(hook);
    boolean sent = LifecycleSupervisor.send(System.out, result.bytes(), Duration.ofSeconds(1));
    Runtime.getRuntime().halt(sent ? result.exitCode() : 1);
  }
}
