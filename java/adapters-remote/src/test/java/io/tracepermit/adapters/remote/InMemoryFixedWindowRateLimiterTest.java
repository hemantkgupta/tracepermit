package io.tracepermit.adapters.remote;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class InMemoryFixedWindowRateLimiterTest {
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

  @Test
  void boundsClaimsAndResetsOnlyAfterTheWindowExpires() {
    MutableClock clock = new MutableClock(NOW);
    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(2, Duration.ofSeconds(10), 4, clock);

    assertThat(limiter.acquire("network:a").allowed()).isTrue();
    assertThat(limiter.acquire("network:a").allowed()).isTrue();
    assertThat(limiter.acquire("network:a"))
        .isEqualTo(RemoteRateLimitDecision.deny(10));
    assertThat(limiter.acquire("network:b").allowed()).isTrue();

    clock.advance(Duration.ofSeconds(10));
    assertThat(limiter.acquire("network:a").allowed()).isTrue();
  }

  @Test
  void boundedKeyCapacityFailsClosedUntilAnEntryExpires() {
    MutableClock clock = new MutableClock(NOW);
    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(1, Duration.ofSeconds(5), 1, clock);

    assertThat(limiter.acquire("network:a").allowed()).isTrue();
    assertThat(limiter.acquire("network:b"))
        .isEqualTo(RemoteRateLimitDecision.deny(5));
    clock.advance(Duration.ofSeconds(5));
    assertThat(limiter.acquire("network:b").allowed()).isTrue();
  }

  @Test
  void concurrentClaimsHaveExactlyTheConfiguredNumberOfWinners() throws Exception {
    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(
            8, Duration.ofMinutes(1), 10, Clock.fixed(NOW, ZoneOffset.UTC));
    CountDownLatch ready = new CountDownLatch(32);
    CountDownLatch start = new CountDownLatch(1);
    List<Boolean> results = java.util.Collections.synchronizedList(new ArrayList<>());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < 32; index++) {
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              results.add(limiter.acquire("principal:shared").allowed());
              return null;
            });
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
    }

    assertThat(results).hasSize(32);
    assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(8);
  }

  @Test
  void concurrentNewKeysNeverExceedTheConfiguredCapacity() throws Exception {
    int maximumKeys = 3;
    int contenders = 64;
    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(
            1, Duration.ofMinutes(1), maximumKeys, Clock.fixed(NOW, ZoneOffset.UTC));
    CountDownLatch ready = new CountDownLatch(contenders);
    CountDownLatch start = new CountDownLatch(1);
    List<Boolean> results = java.util.Collections.synchronizedList(new ArrayList<>());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < contenders; index++) {
        final String key = "principal:contender-" + index;
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              results.add(limiter.acquire(key).allowed());
              return null;
            });
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
    }

    assertThat(results).hasSize(contenders);
    assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(maximumKeys);
  }

  @Test
  void configurationDecisionsKeysAndReadinessAreStrictAndImmutable() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    assertThatThrownBy(
            () -> new InMemoryFixedWindowRateLimiter(0, Duration.ofSeconds(1), 1, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new InMemoryFixedWindowRateLimiter(1, Duration.ofMillis(999), 1, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new InMemoryFixedWindowRateLimiter(1, Duration.ofHours(2), 1, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new InMemoryFixedWindowRateLimiter(1, Duration.ofSeconds(1), 0, clock))
        .isInstanceOf(IllegalArgumentException.class);

    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(1, Duration.ofSeconds(1), 1, clock);
    assertThatThrownBy(() -> limiter.acquire("bad\nkey"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RemoteRateLimitDecision(true, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RemoteRateLimitDecision(false, 0))
        .isInstanceOf(IllegalArgumentException.class);

    HashMap<String, Boolean> dependencies = new HashMap<>();
    RemoteEdgeReadiness.REQUIRED.forEach(name -> dependencies.put(name, true));
    RemoteEdgeReadiness ready = new RemoteEdgeReadiness(true, NOW, dependencies);
    dependencies.put("oauth_trust", false);
    assertThat(ready.dependencies()).containsEntry("oauth_trust", true);
    assertThatThrownBy(() -> ready.dependencies().put("oauth_trust", false))
        .isInstanceOf(UnsupportedOperationException.class);

    HashMap<String, Boolean> unavailable = new HashMap<>(ready.dependencies());
    unavailable.put("evidence_store", false);
    assertThatThrownBy(() -> new RemoteEdgeReadiness(true, NOW, unavailable))
        .isInstanceOf(IllegalArgumentException.class);
    unavailable.remove("security_telemetry");
    assertThatThrownBy(() -> new RemoteEdgeReadiness(false, NOW, unavailable))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void concurrentClaimsAcrossDistinctKeysExecuteWithoutContention() throws Exception {
    InMemoryFixedWindowRateLimiter limiter =
        new InMemoryFixedWindowRateLimiter(
            5, Duration.ofMinutes(1), 1000, Clock.fixed(NOW, ZoneOffset.UTC));
    int threadCount = 64;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    List<Boolean> results = java.util.Collections.synchronizedList(new ArrayList<>());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < threadCount; index++) {
        final String key = "principal:user-" + index;
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              results.add(limiter.acquire(key).allowed());
              return null;
            });
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
    }

    assertThat(results).hasSize(threadCount);
    assertThat(results).allMatch(Boolean::booleanValue);
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;

    private MutableClock(Instant current) {
      this.current = new AtomicReference<>(current);
    }

    void advance(Duration duration) {
      current.updateAndGet(value -> value.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("Only UTC is supported.");
      return this;
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }
}
