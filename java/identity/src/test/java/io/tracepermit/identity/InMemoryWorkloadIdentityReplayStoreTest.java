package io.tracepermit.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class InMemoryWorkloadIdentityReplayStoreTest {
  private static final Instant T = Instant.parse("2030-01-01T00:00:00Z");
  private static final String TENANT = "tenant:acme";
  private static final String ISSUER = "tracepermit-scheduler:primary";

  @Test
  void tinyCapFillsThenRefusesAndEveryLiveOriginalStaysRejected() {
    InMemoryWorkloadIdentityReplayStore store = new InMemoryWorkloadIdentityReplayStore(3, 2);

    assertThat(store.consume(TENANT, ISSUER, "token-1", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, ISSUER, "token-2", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, ISSUER, "token-3", T.plusSeconds(300), T)).isTrue();

    assertThat(store.consume(TENANT, ISSUER, "token-4", T.plusSeconds(300), T)).isFalse();
    assertThat(store.saturatedRefusals()).isEqualTo(1);

    // No LRU eviction: every still-live original entry remains rejected afterwards.
    assertThat(store.consume(TENANT, ISSUER, "token-1", T.plusSeconds(300), T)).isFalse();
    assertThat(store.consume(TENANT, ISSUER, "token-2", T.plusSeconds(300), T)).isFalse();
    assertThat(store.consume(TENANT, ISSUER, "token-3", T.plusSeconds(300), T)).isFalse();
    assertThat(store.liveEntries()).isEqualTo(3);
  }

  @Test
  void tenantNamespaceSeparatesIdenticalIssuerAndToken() {
    InMemoryWorkloadIdentityReplayStore store = new InMemoryWorkloadIdentityReplayStore();

    assertThat(store.consume("tenant:acme", ISSUER, "token-x", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenant:other", ISSUER, "token-x", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenant:acme", ISSUER, "token-x", T.plusSeconds(300), T)).isFalse();
    assertThat(store.consume("tenant:other", ISSUER, "token-x", T.plusSeconds(300), T)).isFalse();
    assertThat(store.liveEntries()).isEqualTo(2);
  }

  @Test
  void compoundKeyCannotCollideThroughDelimiterConcatenation() {
    InMemoryWorkloadIdentityReplayStore store = new InMemoryWorkloadIdentityReplayStore();

    assertThat(store.consume("t\u0000i", "i", "x", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("t", "i\u0000x", "pad", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("t", "i", "x\u0000pad", T.plusSeconds(300), T)).isTrue();
    assertThat(store.liveEntries()).isEqualTo(3);
  }

  @Test
  void duplicateWithLaterExpiryAlreadyExpiredOfferAndEqualityBoundary() {
    InMemoryWorkloadIdentityReplayStore store = new InMemoryWorkloadIdentityReplayStore(4, 4);

    assertThat(store.consume(TENANT, ISSUER, "token-1", T.plusSeconds(30), T)).isTrue();
    // A duplicate with a later requested expiry neither refreshes the claim nor grows metadata.
    assertThat(store.consume(TENANT, ISSUER, "token-1", T.plusSeconds(600), T.plusSeconds(5)))
        .isFalse();
    assertThat(store.liveEntries()).isEqualTo(1);
    assertThat(store.expiryIndexEntries()).isEqualTo(1);

    // An already-expired submission consumes no capacity.
    assertThat(store.consume(TENANT, ISSUER, "token-dead", T, T.plusSeconds(1))).isFalse();
    assertThat(store.expiredSubmissionRefusals()).isEqualTo(1);
    assertThat(store.liveEntries()).isEqualTo(1);

    // Before expiry the entry is live; at exactly expiry it is reclaimable and re-claimable.
    assertThat(store.consume(TENANT, ISSUER, "token-1", T.plusSeconds(90), T.plusSeconds(29)))
        .isFalse();
    assertThat(store.reclamationRemovals()).isZero();
    assertThat(store.consume(TENANT, ISSUER, "token-1", T.plusSeconds(90), T.plusSeconds(30)))
        .isTrue();
    assertThat(store.reclamationRemovals()).isEqualTo(1);
  }

  @Test
  void boundedReclamationRecoversWithBoundedPerCallWork() {
    InMemoryWorkloadIdentityReplayStore store = new InMemoryWorkloadIdentityReplayStore(4, 2);

    for (int index = 1; index <= 4; index++) {
      assertThat(
              store.consume(TENANT, ISSUER, "token-" + index, T.plusSeconds(index), T))
          .isTrue();
    }
    assertThat(store.consume(TENANT, ISSUER, "token-fresh", T.plusSeconds(300), T)).isFalse();
    assertThat(store.liveEntries()).isEqualTo(4);

    long removalsBefore = store.reclamationRemovals();
    assertThat(
            store.consume(TENANT, ISSUER, "token-5", T.plusSeconds(300), T.plusSeconds(10)))
        .isTrue();
    assertThat(store.reclamationRemovals() - removalsBefore).isEqualTo(2);
    assertThat(store.liveEntries()).isEqualTo(3);
    assertThat(store.expiryIndexEntries()).isEqualTo(3);

    removalsBefore = store.reclamationRemovals();
    assertThat(
            store.consume(TENANT, ISSUER, "token-6", T.plusSeconds(300), T.plusSeconds(10)))
        .isTrue();
    assertThat(store.reclamationRemovals() - removalsBefore).isEqualTo(2);
    assertThat(store.liveEntries()).isEqualTo(2);
    assertThat(store.expiryIndexEntries()).isEqualTo(2);
  }

  @Test
  void repeatedChurnKeepsPrimaryAndExpiryMetadataBounded() {
    InMemoryWorkloadIdentityReplayStore store = new InMemoryWorkloadIdentityReplayStore(8, 4);

    for (int iteration = 0; iteration < 400; iteration++) {
      Instant now = T.plusSeconds(iteration);
      String token = "churn-" + (iteration % 6);
      assertThat(store.consume(TENANT, ISSUER, token, now.plusSeconds(3), now)).isTrue();
      assertThat(store.consume(TENANT, ISSUER, token, now.plusSeconds(900), now)).isFalse();
      assertThat(store.liveEntries()).isLessThanOrEqualTo(8);
      assertThat(store.expiryIndexEntries()).isEqualTo(store.liveEntries());
    }
  }

  @Test
  void sameKeyConcurrencyProducesExactlyOneWinner() throws Exception {
    InMemoryWorkloadIdentityReplayStore store = new InMemoryWorkloadIdentityReplayStore();
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> claims = new ArrayList<>();
    for (int index = 0; index < 32; index++) {
      claims.add(
          () -> {
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
            return store.consume(TENANT, ISSUER, "token-shared", T.plusSeconds(300), T);
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
    InMemoryWorkloadIdentityReplayStore store = new InMemoryWorkloadIdentityReplayStore(4, 2);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> claims = new ArrayList<>();
    for (int index = 0; index < 32; index++) {
      String token = "token-" + index;
      claims.add(
          () -> {
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
            return store.consume(TENANT, ISSUER, token, T.plusSeconds(300), T);
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
  void invalidArgumentsAreRejectedBeforeAnyStateChanges() {
    InMemoryWorkloadIdentityReplayStore store = new InMemoryWorkloadIdentityReplayStore();
    assertThatThrownBy(() -> store.consume(null, ISSUER, "token", T.plusSeconds(60), T))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.consume(TENANT, null, "token", T.plusSeconds(60), T))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.consume(TENANT, ISSUER, "token", null, T))
        .isInstanceOf(NullPointerException.class);
    assertThat(store.liveEntries()).isZero();

    assertThatThrownBy(() -> new InMemoryWorkloadIdentityReplayStore(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InMemoryWorkloadIdentityReplayStore(4, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void expiredDuplicateBehindCleanupBudgetWaitsButFreshKeyCanEnter() {
    var store = new InMemoryWorkloadIdentityReplayStore(3, 1);
    for (int i = 1; i <= 3; i++) {
      assertThat(store.consume(TENANT, ISSUER, "old-" + i, T.plusSeconds(i), T)).isTrue();
    }
    Instant later = T.plusSeconds(10);
    assertThat(store.consume(TENANT, ISSUER, "old-3", T.plusSeconds(90), later)).isFalse();
    assertThat(store.reclamationRemovals()).isEqualTo(1);
    assertThat(store.consume(TENANT, ISSUER, "fresh", T.plusSeconds(90), later)).isTrue();
    assertThat(store.consume(TENANT, ISSUER, "old-3", T.plusSeconds(90), later)).isTrue();
    assertThat(store.consume(TENANT, ISSUER, "old-3", T.plusSeconds(120), later)).isFalse();
    assertThat(store.reclamationRemovals()).isEqualTo(3);
    assertThat(store.liveEntries()).isEqualTo(2);
    assertThat(store.expiryIndexEntries()).isEqualTo(2);
  }
}
