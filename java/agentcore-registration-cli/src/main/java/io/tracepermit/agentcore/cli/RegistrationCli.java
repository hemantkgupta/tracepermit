package io.tracepermit.agentcore.cli;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

/** Installed, private, source-free LOCAL SYNTHETIC command; execution stays disabled. */
public final class RegistrationCli {
  private RegistrationCli() {}

  public static void main(String[] args) {
    long started = System.nanoTime();
    var supervisor = new Supervisor();
    var hook = new Thread(supervisor::stop, "registration-owned-child-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    Receipt result;
    try {
      if (args.length != 1 || !java.util.Set.of("check-config", "register-once").contains(args[0]))
        result = Receipt.failure("USAGE");
      else if (!anonymousInput()) result = Receipt.failure("INPUT_INVALID");
      else {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(java, "-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2",
            "-cp", System.getProperty("java.class.path"), Worker.class.getName(), args[0]);
        builder.environment().clear();
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        result = supervisor.run(builder, Duration.ofNanos(Math.max(1,
            Supervisor.TOTAL.minusSeconds(1).toNanos() - (System.nanoTime() - started))), Supervisor.CLEANUP);
      }
    } catch (Throwable failure) { supervisor.stop(); result = Receipt.failure("UNKNOWN"); }
    Runtime.getRuntime().removeShutdownHook(hook);
    boolean sent = Supervisor.send(System.out, result.bytes(), Duration.ofSeconds(1));
    // Do not enter potentially unbounded JVM shutdown/stream flush hooks.
    Runtime.getRuntime().halt(sent ? result.exitCode() : 1);
  }

  static boolean anonymousInput() {
    String os = System.getProperty("os.name", "").toLowerCase();
    try {
      if (os.contains("linux")) {
        Path p = Path.of("/proc/self/fd/0");
        if (!Files.isSymbolicLink(p)) return false;
        Path target = Files.readSymbolicLink(p);
        if (!target.toString().matches("^pipe:\\[\\d+\\]$")) return false;
        int mode = (Integer) Files.getAttribute(p, "unix:mode");
        return (mode & 0170000) == 0010000;
      } else if (os.contains("mac") || os.contains("darwin")) {
        Path p = Path.of("/dev/fd/0");
        int mode = (Integer) Files.getAttribute(p, "unix:mode");
        if ((mode & 0170000) != 0010000) return false;
        int nlink = (Integer) Files.getAttribute(p, "unix:nlink");
        if (nlink != 0) return false;
        Path real = p.toRealPath();
        if (!real.equals(p)) return false;
        FileTime creationTime = (FileTime) Files.getAttribute(p, "unix:creationTime");
        if (creationTime == null || !creationTime.toInstant().equals(Instant.EPOCH)) return false;
        FileStore store = Files.getFileStore(p);
        return "devfs".equals(store.type());
      }
      return false;
    } catch (Exception unsupported) { return false; }
  }
}
