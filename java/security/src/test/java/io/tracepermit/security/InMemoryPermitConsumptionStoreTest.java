package io.tracepermit.security;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Test
  void tenantOverloadsKeepOneGlobalPermitIdNamespace() {
    InMemoryPermitConsumptionStore store = new InMemoryPermitConsumptionStore();

    assertThat(store.consume("permit-1", NOW.plusSeconds(30), NOW)).isTrue();
    // The same permit ID is refused through every overload shape, including from
    // another tenant: the legacy namespace is global and cross-tenant reuse stays closed.
    assertThat(store.consume("tenant-a", "permit-1", NOW.plusSeconds(30), NOW)).isFalse();
    assertThat(store.consume("tenant-b", "permit-1", NOW.plusSeconds(30), null, NOW)).isFalse();
    assertThat(store.consume("tenant-a", "permit-1", NOW.plusSeconds(30), "sha256:x", NOW))
        .isFalse();
    assertThat(store.liveEntries()).isEqualTo(1);
    assertThat(store.saturatedRefusals()).isZero();
  }

  @Test
  void tinyCapFillsThenRefusesAndEveryLiveOriginalStaysRejected() {
    InMemoryPermitConsumptionStore store = new InMemoryPermitConsumptionStore(3, 2);

    assertThat(store.consume("permit-1", NOW.plusSeconds(60), NOW)).isTrue();
    assertThat(store.consume("permit-2", NOW.plusSeconds(60), NOW)).isTrue();
    assertThat(store.consume("permit-3", NOW.plusSeconds(60), NOW)).isTrue();

    assertThat(store.consume("permit-4", NOW.plusSeconds(60), NOW)).isFalse();
    assertThat(store.saturatedRefusals()).isEqualTo(1);

    // No LRU eviction: every still-live original entry remains rejected afterwards.
    assertThat(store.consume("permit-1", NOW.plusSeconds(60), NOW)).isFalse();
    assertThat(store.consume("permit-2", NOW.plusSeconds(60), NOW)).isFalse();
    assertThat(store.consume("permit-3", NOW.plusSeconds(60), NOW)).isFalse();
    assertThat(store.liveEntries()).isEqualTo(3);
  }

  @Test
  void alreadyExpiredSubmissionIsRefusedWithoutConsumingCapacity() {
    InMemoryPermitConsumptionStore store = new InMemoryPermitConsumptionStore(2, 2);

    assertThat(store.consume("permit-dead", NOW, NOW)).isFalse();
    assertThat(store.consume("permit-dead", NOW.minusSeconds(1), NOW)).isFalse();
    assertThat(store.expiredSubmissionRefusals()).isEqualTo(2);
    assertThat(store.liveEntries()).isZero();
    assertThat(store.expiryIndexEntries()).isZero();

    assertThat(store.consume("permit-live", NOW.plusSeconds(60), NOW)).isTrue();
    assertThat(store.liveEntries()).isEqualTo(1);
  }

  @Test
  void boundedReclamationRecoversWithBoundedPerCallWork() {
    InMemoryPermitConsumptionStore store = new InMemoryPermitConsumptionStore(4, 2);

    for (int index = 1; index <= 4; index++) {
      assertThat(store.consume("permit-" + index, NOW.plusSeconds(index), NOW)).isTrue();
    }
    assertThat(store.consume("permit-fresh", NOW.plusSeconds(60), NOW)).isFalse();
    assertThat(store.liveEntries()).isEqualTo(4);

    long removalsBefore = store.reclamationRemovals();
    assertThat(store.consume("permit-5", NOW.plusSeconds(60), NOW.plusSeconds(10))).isTrue();
    assertThat(store.reclamationRemovals() - removalsBefore).isEqualTo(2);
    assertThat(store.liveEntries()).isEqualTo(3);
    assertThat(store.expiryIndexEntries()).isEqualTo(3);

    removalsBefore = store.reclamationRemovals();
    assertThat(store.consume("permit-6", NOW.plusSeconds(60), NOW.plusSeconds(10))).isTrue();
    assertThat(store.reclamationRemovals() - removalsBefore).isEqualTo(2);
    assertThat(store.liveEntries()).isEqualTo(2);
    assertThat(store.expiryIndexEntries()).isEqualTo(2);
  }

  @Test
  void repeatedChurnKeepsPrimaryAndExpiryMetadataBounded() {
    InMemoryPermitConsumptionStore store = new InMemoryPermitConsumptionStore(8, 4);

    for (int iteration = 0; iteration < 400; iteration++) {
      Instant now = NOW.plusSeconds(iteration);
      String permitId = "churn-" + (iteration % 6);
      assertThat(store.consume(permitId, now.plusSeconds(3), now)).isTrue();
      assertThat(store.consume(permitId, now.plusSeconds(300), now)).isFalse();
      assertThat(store.consume("permit-dead-" + iteration, now, now)).isFalse();
      assertThat(store.liveEntries()).isLessThanOrEqualTo(8);
      assertThat(store.expiryIndexEntries()).isEqualTo(store.liveEntries());
    }
    assertThat(store.expiredSubmissionRefusals()).isEqualTo(400);
  }

  @Test
  void distinctKeyConcurrencyNeverOvershootsTheCap() throws Exception {
    InMemoryPermitConsumptionStore store = new InMemoryPermitConsumptionStore(4, 2);
    CountDownLatch start = new CountDownLatch(1);
    var claims = IntStream.range(0, 32)
        .mapToObj(index -> (Callable<Boolean>) () -> {
          start.await();
          return store.consume("permit-" + index, NOW.plusSeconds(60), NOW);
        })
        .toList();

    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = claims.stream().map(executor::submit).toList();
      start.countDown();
      int winners = 0;
      for (var future : futures) {
        if (future.get(10, java.util.concurrent.TimeUnit.SECONDS)) winners++;
      }
      assertThat(winners).isEqualTo(4);
      assertThat(store.liveEntries()).isEqualTo(4);
      assertThat(store.expiryIndexEntries()).isEqualTo(4);
    }
  }

  @Test
  void invalidArgumentsAndCapacitiesAreRejectedBeforeAnyStateChanges() {
    InMemoryPermitConsumptionStore store = new InMemoryPermitConsumptionStore();
    assertThatThrownBy(() -> store.consume(null, NOW.plusSeconds(60), NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.consume("permit-1", null, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.consume("permit-1", NOW.plusSeconds(60), null))
        .isInstanceOf(NullPointerException.class);
    assertThat(store.liveEntries()).isZero();

    assertThatThrownBy(() -> new InMemoryPermitConsumptionStore(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InMemoryPermitConsumptionStore(4, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
