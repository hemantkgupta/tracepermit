package io.tracepermit.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class InMemorySourceAttestationReplayStoreTest {
  private static final Instant T = Instant.parse("2030-01-01T00:00:00Z");
  private static final String ISSUER = "tracepermit-endpoint:device-simulated-001";

  @Test
  void tinyCapFillsThenRefusesAndEveryLiveOriginalStaysRejected() {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore(3, 2);

    assertThat(store.consume(ISSUER, "token-1", T.plusSeconds(60), T)).isTrue();
    assertThat(store.consume(ISSUER, "token-2", T.plusSeconds(60), T)).isTrue();
    assertThat(store.consume(ISSUER, "token-3", T.plusSeconds(60), T)).isTrue();
    assertThat(store.liveEntries()).isEqualTo(3);

    assertThat(store.consume(ISSUER, "token-4", T.plusSeconds(60), T)).isFalse();
    assertThat(store.saturatedRefusals()).isEqualTo(1);
    assertThat(store.liveEntries()).isEqualTo(3);

    // No LRU eviction: every still-live original entry remains rejected afterwards.
    assertThat(store.consume(ISSUER, "token-1", T.plusSeconds(60), T)).isFalse();
    assertThat(store.consume(ISSUER, "token-2", T.plusSeconds(60), T)).isFalse();
    assertThat(store.consume(ISSUER, "token-3", T.plusSeconds(60), T)).isFalse();
    assertThat(store.liveEntries()).isEqualTo(3);
  }

  @Test
  void alreadyExpiredSubmissionIsRefusedWithoutConsumingCapacity() {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore(2, 2);

    assertThat(store.consume(ISSUER, "token-expired", T, T)).isFalse();
    assertThat(store.consume(ISSUER, "token-expired", T.minusSeconds(1), T)).isFalse();
    assertThat(store.expiredSubmissionRefusals()).isEqualTo(2);
    assertThat(store.liveEntries()).isZero();
    assertThat(store.expiryIndexEntries()).isZero();

    assertThat(store.consume(ISSUER, "token-live", T.plusSeconds(60), T)).isTrue();
    assertThat(store.liveEntries()).isEqualTo(1);
  }

  @Test
  void duplicateWithLaterExpiryDoesNotRefreshOriginalClaim() {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore(4, 4);

    assertThat(store.consume(ISSUER, "token-1", T.plusSeconds(30), T)).isTrue();
    assertThat(store.consume(ISSUER, "token-1", T.plusSeconds(300), T.plusSeconds(10))).isFalse();
    assertThat(store.liveEntries()).isEqualTo(1);
    assertThat(store.expiryIndexEntries()).isEqualTo(1);

    // The original expiry is retained: the key becomes reclaimable exactly at T+30.
    assertThat(store.consume(ISSUER, "token-2", T.plusSeconds(60), T.plusSeconds(30))).isTrue();
    assertThat(store.reclamationRemovals()).isEqualTo(1);
    assertThat(store.liveEntries()).isEqualTo(1);
  }

  @Test
  void expiryEqualityBoundaryReclaimsAtOrBeforeNowOnly() {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore(4, 4);

    assertThat(store.consume(ISSUER, "token-1", T.plusSeconds(30), T)).isTrue();
    // One instant before expiry the entry is still live.
    assertThat(store.consume(ISSUER, "token-1", T.plusSeconds(90), T.plusSeconds(29))).isFalse();
    assertThat(store.reclamationRemovals()).isZero();
    // At exactly expiry (equality boundary) the entry is reclaimable and re-claimable.
    assertThat(store.consume(ISSUER, "token-1", T.plusSeconds(90), T.plusSeconds(30))).isTrue();
    assertThat(store.reclamationRemovals()).isEqualTo(1);
    assertThat(store.liveEntries()).isEqualTo(1);
  }

  @Test
  void boundedReclamationRecoversWithBoundedPerCallWork() {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore(4, 2);

    for (int index = 1; index <= 4; index++) {
      assertThat(store.consume(ISSUER, "token-" + index, T.plusSeconds(index), T)).isTrue();
    }
    // Full of live entries: bounded cleanup cannot help, so a fresh consume fails closed.
    assertThat(store.consume(ISSUER, "token-fresh", T.plusSeconds(60), T)).isFalse();
    assertThat(store.liveEntries()).isEqualTo(4);

    // All four expire; each call reclaims at most the budget (2), so recovery takes
    // multiple calls and no call ever sweeps the whole map.
    long removalsBefore = store.reclamationRemovals();
    assertThat(store.consume(ISSUER, "token-5", T.plusSeconds(60), T.plusSeconds(10))).isTrue();
    assertThat(store.reclamationRemovals() - removalsBefore).isEqualTo(2);
    assertThat(store.liveEntries()).isEqualTo(3);
    assertThat(store.expiryIndexEntries()).isEqualTo(3);

    removalsBefore = store.reclamationRemovals();
    assertThat(store.consume(ISSUER, "token-6", T.plusSeconds(60), T.plusSeconds(10))).isTrue();
    assertThat(store.reclamationRemovals() - removalsBefore).isEqualTo(2);
    assertThat(store.liveEntries()).isEqualTo(2);
    assertThat(store.expiryIndexEntries()).isEqualTo(2);
  }

  @Test
  void repeatedChurnKeepsPrimaryAndExpiryMetadataBounded() {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore(8, 4);

    for (int iteration = 0; iteration < 400; iteration++) {
      Instant now = T.plusSeconds(iteration);
      String key = "churn-" + (iteration % 6);
      assertThat(store.consume(ISSUER, key, now.plusSeconds(3), now)).isTrue();
      assertThat(store.consume(ISSUER, key, now.plusSeconds(300), now)).isFalse();
      assertThat(store.consume(ISSUER, "expired-offer", now, now)).isFalse();
      assertThat(store.liveEntries()).isLessThanOrEqualTo(8);
      assertThat(store.expiryIndexEntries()).isEqualTo(store.liveEntries());
    }
    assertThat(store.expiredSubmissionRefusals()).isEqualTo(400);
  }

  @Test
  void expiredDuplicateBehindCleanupBudgetWaitsButFreshKeyCanEnter() {
    var store = new InMemorySourceAttestationReplayStore(3, 1);
    for (int i = 1; i <= 3; i++) {
      assertThat(store.consume(ISSUER, "old-" + i, T.plusSeconds(i), T)).isTrue();
    }
    Instant later = T.plusSeconds(10);
    // Only old-1 is reclaimed; the offered old-3 is still retained and refused.
    assertThat(store.consume(ISSUER, "old-3", T.plusSeconds(90), later)).isFalse();
    assertThat(store.reclamationRemovals()).isEqualTo(1);
    // A genuinely new key can use the reclaimed space before the backlog is empty.
    assertThat(store.consume(ISSUER, "fresh", T.plusSeconds(90), later)).isTrue();
    assertThat(store.consume(ISSUER, "old-3", T.plusSeconds(90), later)).isTrue();
    assertThat(store.consume(ISSUER, "old-3", T.plusSeconds(120), later)).isFalse();
    assertThat(store.reclamationRemovals()).isEqualTo(3);
    assertThat(store.liveEntries()).isEqualTo(2);
    assertThat(store.expiryIndexEntries()).isEqualTo(2);
  }

  @Test
  void sameKeyConcurrencyProducesExactlyOneWinner() throws Exception {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore();
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> claims = new ArrayList<>();
    for (int index = 0; index < 32; index++) {
      claims.add(
          () -> {
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
            return store.consume(ISSUER, "token-shared", T.plusSeconds(60), T);
          });
    }

    ExecutorService executor = Executors.newFixedThreadPool(8, Thread.ofPlatform().daemon().factory());
    try {
      List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
      for (Callable<Boolean> claim : claims) {
        futures.add(executor.submit(claim));
      }
      start.countDown();
      int winners = 0;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      for (java.util.concurrent.Future<Boolean> future : futures) {
        if (future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
          winners++;
        }
      }
      assertThat(winners).isEqualTo(1);
      assertThat(store.liveEntries()).isEqualTo(1);
      assertThat(store.expiryIndexEntries()).isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(3, TimeUnit.SECONDS))
          .as("all owned claim workers terminated").isTrue();
    }
  }

  @Test
  void distinctKeyConcurrencyNeverOvershootsTheCap() throws Exception {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore(4, 2);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> claims = new ArrayList<>();
    for (int index = 0; index < 32; index++) {
      String token = "token-" + index;
      claims.add(
          () -> {
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
            return store.consume(ISSUER, token, T.plusSeconds(60), T);
          });
    }

    ExecutorService executor = Executors.newFixedThreadPool(8, Thread.ofPlatform().daemon().factory());
    try {
      List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
      for (Callable<Boolean> claim : claims) {
        futures.add(executor.submit(claim));
      }
      start.countDown();
      int winners = 0;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      for (java.util.concurrent.Future<Boolean> future : futures) {
        if (future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
          winners++;
        }
      }
      assertThat(winners).isEqualTo(4);
      assertThat(store.liveEntries()).isEqualTo(4);
      assertThat(store.expiryIndexEntries()).isEqualTo(4);
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(3, TimeUnit.SECONDS))
          .as("all owned claim workers terminated").isTrue();
    }
  }

  @Test
  void namespaceIsCollisionSafeIssuerPlusToken() {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore();

    // Same token under a different issuer is a different entry; same issuer with a
    // different token is a different entry; only the exact pair is single-use.
    assertThat(store.consume("issuer-a", "token-x", T.plusSeconds(60), T)).isTrue();
    assertThat(store.consume("issuer-b", "token-x", T.plusSeconds(60), T)).isTrue();
    assertThat(store.consume("issuer-a", "token-y", T.plusSeconds(60), T)).isTrue();
    assertThat(store.consume("issuer-a", "token-x", T.plusSeconds(60), T)).isFalse();

    // A record key cannot reproduce a delimiter-concatenation collision.
    assertThat(
            store.consume("issuer-a\u0000token-y", "pad", T.plusSeconds(60), T))
        .isTrue();
    assertThat(
            store.consume("issuer-a", "token-y\u0000pad", T.plusSeconds(60), T))
        .isTrue();
  }

  @Test
  void invalidArgumentsAreRejectedBeforeAnyStateChanges() {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore();
    assertThatThrownBy(() -> store.consume(null, "token", T.plusSeconds(60), T))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.consume(ISSUER, null, T.plusSeconds(60), T))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.consume(ISSUER, "token", null, T))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.consume(ISSUER, "token", T.plusSeconds(60), null))
        .isInstanceOf(NullPointerException.class);
    assertThat(store.liveEntries()).isZero();

    assertThatThrownBy(() -> new InMemorySourceAttestationReplayStore(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InMemorySourceAttestationReplayStore(4, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InMemorySourceAttestationReplayStore(-1, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void healthyFreshRequestSucceedsAfterGenuineExpiryReclamation() {
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore(2, 4);
    assertThat(store.consume(ISSUER, UUID.randomUUID().toString(), T.plusSeconds(30), T)).isTrue();
    assertThat(store.consume(ISSUER, UUID.randomUUID().toString(), T.plusSeconds(30), T)).isTrue();
    assertThat(store.consume(ISSUER, "fresh", T.plusSeconds(30), T)).isFalse();

    assertThat(store.consume(ISSUER, "fresh", T.plusSeconds(60), T.plusSeconds(30))).isTrue();
    assertThat(store.liveEntries()).isEqualTo(1);
    assertThat(store.reclamationRemovals()).isEqualTo(2);
  }
}
