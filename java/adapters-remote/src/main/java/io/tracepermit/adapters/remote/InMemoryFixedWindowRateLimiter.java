package io.tracepermit.adapters.remote;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded concurrent rate limiter for tests and single-process demonstrations.
 *
 * <p>Existing-key claims are atomic per key. New-key admission is serialized so
 * concurrent distinct principals cannot exceed the configured capacity. This
 * process-local adapter is not shared production abuse state.
 */
public final class InMemoryFixedWindowRateLimiter implements RemoteRateLimiter {
  private static final Duration MAXIMUM_WINDOW = Duration.ofHours(1);

  private final int permitsPerWindow;
  private final long windowMillis;
  private final int maximumKeys;
  private final Clock clock;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final Object capacityLock = new Object();

  public InMemoryFixedWindowRateLimiter(
      int permitsPerWindow, Duration window, int maximumKeys, Clock clock) {
    if (permitsPerWindow < 1 || permitsPerWindow > 100_000) {
      throw new IllegalArgumentException("The rate-limit permit count is outside its safe bound.");
    }
    Objects.requireNonNull(window);
    if (window.isZero() || window.isNegative() || window.compareTo(MAXIMUM_WINDOW) > 0) {
      throw new IllegalArgumentException("The rate-limit window is outside its safe bound.");
    }
    if (window.toMillis() < 1_000) {
      throw new IllegalArgumentException("The rate-limit window must be at least one second.");
    }
    if (maximumKeys < 1 || maximumKeys > 1_000_000) {
      throw new IllegalArgumentException("The rate-limit key capacity is outside its safe bound.");
    }
    this.permitsPerWindow = permitsPerWindow;
    this.windowMillis = window.toMillis();
    this.maximumKeys = maximumKeys;
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public RemoteRateLimitDecision acquire(String boundedKey) {
    requireKey(boundedKey);
    long nowMillis = clock.instant().toEpochMilli();
    RemoteRateLimitDecision existing = claimExisting(boundedKey, nowMillis);
    if (existing != null) return existing;

    synchronized (capacityLock) {
      // Another caller may have inserted this key before we acquired the admission lock.
      existing = claimExisting(boundedKey, nowMillis);
      if (existing != null) return existing;
      removeExpired(nowMillis);
      if (windows.size() >= maximumKeys) {
        return RemoteRateLimitDecision.deny(secondsUntilEarliestExpiry(nowMillis));
      }
      windows.put(boundedKey, new Window(nowMillis + windowMillis, 1));
      return RemoteRateLimitDecision.permit();
    }
  }

  private RemoteRateLimitDecision claimExisting(String boundedKey, long nowMillis) {
    AtomicReference<RemoteRateLimitDecision> decision = new AtomicReference<>();
    windows.computeIfPresent(
        boundedKey,
        (key, current) -> {
          if (nowMillis >= current.endsAtMillis()) {
            decision.set(RemoteRateLimitDecision.permit());
            return new Window(nowMillis + windowMillis, 1);
          }
          if (current.claims() >= permitsPerWindow) {
            decision.set(RemoteRateLimitDecision.deny(retryAfter(current.endsAtMillis(), nowMillis)));
            return current;
          }
          decision.set(RemoteRateLimitDecision.permit());
          return new Window(current.endsAtMillis(), current.claims() + 1);
        });
    return decision.get();
  }

  private void removeExpired(long nowMillis) {
    windows.forEach(
        (key, window) -> {
          if (nowMillis >= window.endsAtMillis()) windows.remove(key, window);
        });
  }

  private long secondsUntilEarliestExpiry(long nowMillis) {
    long earliest =
        windows.values().stream()
            .mapToLong(Window::endsAtMillis)
            .min()
            .orElse(nowMillis + windowMillis);
    return retryAfter(earliest, nowMillis);
  }

  private static long retryAfter(long endsAtMillis, long nowMillis) {
    long milliseconds = Math.max(1, endsAtMillis - nowMillis);
    return Math.min(3_600, Math.max(1, (milliseconds + 999) / 1_000));
  }

  private static void requireKey(String value) {
    Objects.requireNonNull(value);
    if (value.isEmpty()
        || value.length() > 256
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("The rate-limit key is invalid.");
    }
  }

  private record Window(long endsAtMillis, int claims) {}
}
