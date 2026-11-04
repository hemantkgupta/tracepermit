package io.tracepermit.auth0reviewer;

import io.tracepermit.agentcore.cli.InstalledPrivateInput;
import io.tracepermit.agentcore.organizationcli.Auth0OrganizationReview;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicit login + terminal confirmation + one pinned durable decision; never delivery. */
public final class Auth0OperationReviewMain {
  static final Duration TOTAL = Duration.ofSeconds(240);
  private static final Duration RESERVE = Duration.ofSeconds(6);
  private Auth0OperationReviewMain() {}

  public static void main(String[] args) {
    java.util.logging.LogManager.getLogManager().reset();
    long started = System.nanoTime();
    int exit = 1;
    String command = args.length == 1 && args[0].equals("check-config") ? "check-config" : "review";
    byte[] receipt = failure(command, "REVIEW_REJECTED");
    try {
      if (args.length != 1 || !Set.of("check-config", "review").contains(args[0])) {
        receipt = failure(command, "USAGE");exit = 64;
      } else if (!InstalledPrivateInput.anonymousPipe()) {
        receipt = failure(command, "INPUT_INVALID");
      } else {
        var input = bounded(started, Duration.ofSeconds(5),
            () -> OperationReviewInput.read(System.in));
        if (args[0].equals("check-config")) {
          receipt = failure(command, "CONFIG_VALID");exit = 0;
        } else {
          if (!bounded(started, Duration.ofSeconds(1), ReviewConfirmation::available)) {
            throw new IllegalStateException("REVIEW_TERMINAL_REQUIRED");
          }
          var result = OperationReviewSession.run(started, input,
              ReviewerLogin.live(input.reviewer, Clock.systemUTC()), ReviewConfirmation.live());
          receipt = result.bytes();exit = result.exit();
        }
      }
    } catch (TimeoutException failure) { receipt = failure(command, "UNKNOWN"); }
    catch (Exception failure) { receipt = failure(command, "REVIEW_REJECTED"); }
    catch (Throwable failure) { receipt = failure(command, "UNKNOWN"); }
    final byte[] output = receipt;
    try {
      bounded(started, Duration.ofSeconds(1), () -> {
        System.out.write(output);System.out.flush();return true;
      });
    } catch (Exception failure) { exit = 1; }
    // End all owned daemon stage threads and descriptors; never kill another process.
    System.exit(exit);
  }

  static <T> T bounded(long started, Duration stage, Callable<T> action) throws Exception {
    long available = TOTAL.minus(RESERVE).toNanos() - (System.nanoTime() - started);
    if (available <= 0) throw new TimeoutException();
    var executor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "auth0-operation-review-stage");t.setDaemon(true);return t;
    });
    var result = executor.submit(action);
    try { return result.get(Math.min(available, stage.toNanos()), TimeUnit.NANOSECONDS); }
    finally { result.cancel(true);executor.shutdownNow(); }
  }

  private static byte[] failure(String command, String outcome) {
    // Only fixed literals reach this method; no exception, provider body or identity is rendered.
    return ("{\"schemaVersion\":\"1.0\",\"profile\":\"" + Auth0OrganizationReview.PROFILE
        + "\",\"command\":\"" + command + "\",\"outcome\":\"" + outcome
        + "\",\"executionEnabled\":false,\"automaticRetryAllowed\":false}\n")
        .getBytes(StandardCharsets.UTF_8);
  }
}
