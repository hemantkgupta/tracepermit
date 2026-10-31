package io.tracepermit.auth0reviewer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;

/** Package-local composition of concrete login, preview, terminal and durable review components. */
final class OperationReviewSession {
  private OperationReviewSession() {}
  record Outcome(int exit, byte[] bytes) {
    Outcome { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
    @Override public String toString() { return "OperationReviewSession.Outcome[redacted]"; }
  }

  static Outcome run(long started, OperationReviewInput input, ReviewerLogin login,
      ReviewConfirmation confirmation) {
    boolean attempted = false;
    try (var credential = Auth0OperationReviewMain.bounded(started, Duration.ofSeconds(120),
        login::loginForReview)) {
      var preview = Auth0OperationReviewMain.bounded(started, Duration.ofSeconds(15), () -> {
        byte[] bearer = credential.copy();
        try { return input.plan.preview(bearer); }
        finally { Arrays.fill(bearer, (byte)0); }
      });
      if (!Auth0OperationReviewMain.bounded(started, Duration.ofSeconds(30),
          () -> confirmation.decide(preview))) return failed("REVIEW_NOT_CONFIRMED");
      attempted = true;
      var result = Auth0OperationReviewMain.bounded(started, Duration.ofSeconds(60), () -> {
        byte[] bearer = credential.copy();
        try { return input.plan.review(bearer); }
        finally { Arrays.fill(bearer, (byte)0); }
      });
      return new Outcome(result.exitCode(), result.bytes());
    } catch (TimeoutException failure) { return failed("UNKNOWN"); }
    catch (Exception failure) { return failed(attempted ? "UNKNOWN" : "REVIEW_REJECTED"); }
  }

  private static Outcome failed(String outcome) {
    return new Outcome(1, ("{\"schemaVersion\":\"1.0\",\"profile\":\"tracepermit-auth0-operation-review-v1\","
        + "\"command\":\"review\",\"outcome\":\"" + outcome
        + "\",\"executionEnabled\":false,\"automaticRetryAllowed\":false}\n")
        .getBytes(StandardCharsets.UTF_8));
  }
}
