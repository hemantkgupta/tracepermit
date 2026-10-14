package io.tracepermit.adapters.remote;

/** Bounded outcome from a remote-edge abuse-control claim. */
public record RemoteRateLimitDecision(boolean allowed, long retryAfterSeconds) {
  public RemoteRateLimitDecision {
    if (allowed && retryAfterSeconds != 0) {
      throw new IllegalArgumentException("Allowed rate-limit decisions cannot require a retry.");
    }
    if (!allowed && (retryAfterSeconds < 1 || retryAfterSeconds > 3_600)) {
      throw new IllegalArgumentException("Denied rate-limit retry time is outside its safe bound.");
    }
  }

  public static RemoteRateLimitDecision permit() {
    return new RemoteRateLimitDecision(true, 0);
  }

  public static RemoteRateLimitDecision deny(long retryAfterSeconds) {
    return new RemoteRateLimitDecision(false, retryAfterSeconds);
  }
}
