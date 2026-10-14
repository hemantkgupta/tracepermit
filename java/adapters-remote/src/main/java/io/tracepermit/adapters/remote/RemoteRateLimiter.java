package io.tracepermit.adapters.remote;

/** Abuse-control port. Production implementations must share state across serving replicas. */
@FunctionalInterface
public interface RemoteRateLimiter {
  RemoteRateLimitDecision acquire(String boundedKey);

  static RemoteRateLimiter allowAllForExplicitFoundationTests() {
    return ignored -> RemoteRateLimitDecision.permit();
  }
}
