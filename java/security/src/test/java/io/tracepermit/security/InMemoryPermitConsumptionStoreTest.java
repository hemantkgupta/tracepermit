package io.tracepermit.security;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

final class InMemoryPermitConsumptionStoreTest {
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");

  @RepeatedTest(20)
  void concurrentClaimsHaveExactlyOneWinner() throws Exception {
    InMemoryPermitConsumptionStore store = new InMemoryPermitConsumptionStore();
    CountDownLatch start = new CountDownLatch(1);
    var claims = IntStream.range(0, 32)
        .mapToObj(index -> (Callable<Boolean>) () -> {
          start.await();
          return store.consume("permit-shared", NOW.plusSeconds(30), NOW);
        })
        .toList();

    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = claims.stream().map(executor::submit).toList();
      start.countDown();
      int winners = 0;
      for (var future : futures) {
        if (future.get()) winners++;
      }
      assertThat(winners).isEqualTo(1);
    }
  }

  @Test
  void expiredClaimsArePrunedWithoutReopeningLiveClaims() {
    InMemoryPermitConsumptionStore store = new InMemoryPermitConsumptionStore();

    assertThat(store.consume("permit-live", NOW.plusSeconds(30), NOW)).isTrue();
    assertThat(store.consume("permit-live", NOW.plusSeconds(40), NOW.plusSeconds(29))).isFalse();
    assertThat(store.consume("permit-expired", NOW.plusSeconds(1), NOW)).isTrue();
    assertThat(store.consume("permit-expired", NOW.plusSeconds(60), NOW.plusSeconds(1))).isTrue();
    assertThat(store.consume("permit-live", NOW.plusSeconds(60), NOW.plusSeconds(30))).isTrue();
  }
}
