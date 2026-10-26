package io.tracepermit.adapters.remote.github;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Finite explicit diagnostics only. No service, action, ledger, permit issuer or executor. */
public final class PilotOperatorMain {
  enum Result {
    CHECK_VALID(0), PREFLIGHT_OBSERVED(0), CONFIGURATION_REJECTED(2),
    PREFLIGHT_INDETERMINATE(3), DEADLINE_EXCEEDED(4), CANCELLED(5), USAGE_REJECTED(64);
    final int exit;
    Result(int exit) { this.exit = exit; }
    String diagnostic() { return name() + "_NON_AUTHORITATIVE"; }
  }
  @FunctionalInterface interface Preflights { PilotReadOnlyPreflight create(PilotConfiguration configuration); }
  private PilotOperatorMain() {}

  public static void main(String[] args) {
    if (args.length == 1 && "--help".equals(args[0])) {
      System.out.println("Usage: tracepermit-pilot-operator --check | --preflight | --help\n"
          + "Set TRACEPERMIT_PILOT_CONFIGURATION to an absolute private JSON file path.\n"
          + "Java21; POSIX host. macOS is the intended operator profile; see qualification limits.\n"
          + "All results are NON_AUTHORITATIVE. No execution or automatic retry.");
      return;
    }
    Result result = bounded(args, System.getenv("TRACEPERMIT_PILOT_CONFIGURATION"),
        PilotReadOnlyPreflight::production, Duration.ofSeconds(30));
    System.out.println(result.diagnostic());
    // Hard process boundary: interrupted native I/O/cleanup cannot keep the command alive.
    System.exit(result.exit);
  }

  static Result bounded(String[] args, String file, Preflights preflights, Duration deadline) {
    if (args.length != 1 || !("--check".equals(args[0]) || "--preflight".equals(args[0]))) return Result.USAGE_REJECTED;
    if (Runtime.version().feature() != 21 || file == null || file.isEmpty()) return Result.CONFIGURATION_REJECTED;
    // A pre-cancelled caller must never race a completed diagnostic Future.
    if (Thread.currentThread().isInterrupted()) return Result.CANCELLED;
    var worker = Executors.newSingleThreadExecutor(task -> {
      var thread = new Thread(task, "pilot-diagnostic"); thread.setDaemon(true); return thread;
    });
    var future = worker.submit(() -> diagnose(args[0], file, preflights));
    try {
      return future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt(); return Result.CANCELLED;
    } catch (TimeoutException failure) {
      return Result.DEADLINE_EXCEEDED;
    } catch (ExecutionException failure) {
      return Result.PREFLIGHT_INDETERMINATE;
    } finally {
      future.cancel(true); worker.shutdownNow();
    }
  }

  private static Result diagnose(String mode, String file, Preflights preflights) {
    PilotConfiguration configuration;
    try { configuration = PilotConfiguration.read(Path.of(file)); }
    catch (Exception failure) { return Result.CONFIGURATION_REJECTED; }
    if ("--check".equals(mode)) return Result.CHECK_VALID;
    try {
      preflights.create(configuration).observe(configuration);
      return Result.PREFLIGHT_OBSERVED;
    } catch (Exception failure) {
      return Result.PREFLIGHT_INDETERMINATE;
    }
  }
}
