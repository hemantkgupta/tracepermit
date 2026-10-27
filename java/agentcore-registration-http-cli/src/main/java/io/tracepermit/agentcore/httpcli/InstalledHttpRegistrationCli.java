package io.tracepermit.agentcore.httpcli;

import io.tracepermit.agentcore.cli.InstalledHttpRuntime;
import java.nio.file.Path;
import java.time.Duration;

/** Source-free bounded local synthetic HTTP pilot. No default service or public endpoint. */
public final class InstalledHttpRegistrationCli {
  private InstalledHttpRegistrationCli() {}

  public static void main(String[] args) {
    long started = System.nanoTime();
    var supervisor = new SessionSupervisor();
    var hook = new Thread(supervisor::stop, "registration-http-owned-child-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    int exit;
    try {
      if (args.length != 1
          || !java.util.Set.of("check-session", "serve-bounded").contains(args[0])) {
        exit =
            SessionProtocol.send(
                    System.out, SessionReceipt.failure("USAGE"), SessionSupervisor.OUTPUT)
                ? 64
                : 1;
      } else if (!InstalledHttpRuntime.anonymousInput()) {
        exit =
            SessionProtocol.send(
                    System.out, SessionReceipt.failure("INPUT_INVALID"), SessionSupervisor.OUTPUT)
                ? 1
                : 1;
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
                InstalledHttpWorker.class.getName(),
                args[0]);
        builder.environment().clear();
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        long elapsed = System.nanoTime() - started;
        Duration remaining =
            Duration.ofNanos(Math.max(1, SessionSupervisor.TOTAL.toNanos() - elapsed));
        exit = supervisor.run(builder, args[0], System.out, remaining, SessionSupervisor.CLEANUP);
      }
    } catch (Throwable failure) {
      supervisor.stop();
      SessionProtocol.send(
          System.out, SessionReceipt.failure("UNKNOWN"), SessionSupervisor.OUTPUT);
      exit = 1;
    }
    Runtime.getRuntime().removeShutdownHook(hook);
    Runtime.getRuntime().halt(exit);
  }
}
