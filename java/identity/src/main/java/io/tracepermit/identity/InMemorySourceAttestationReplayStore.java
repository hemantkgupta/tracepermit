package io.tracepermit.identity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Process-local, bounded replay store for source attestations.
 *
 * <p>Single use stays atomic: duplicate and capacity checks and the insert happen under one
 * monitor, so concurrent claims of one attestation produce exactly one winner. The store is
 * bounded by a finite entry cap and never evicts a live entry to accept more work: at full
 * capacity a fresh consume fails closed ({@code false}), the same refusal contract as a replay.
 * Reclamation is bounded per call: at most {@code cleanupBudget} expired entries (expiry at or
 * before the supplied {@code now}) are reclaimed per consume, never a full-map sweep.
 *
 * <p>State is per process instance: it does not survive a restart, is not shared across
 * replicas, and trusts the supplied clock. Defaults: 65,536 entries, 64 reclamation removals
 * per call.
 */
public final class InMemorySourceAttestationReplayStore implements SourceAttestationReplayStore {
  private static final int DEFAULT_CAPACITY = 65_536;
  private static final int DEFAULT_CLEANUP_BUDGET = 64;

  private final int capacity;
  private final int cleanupBudget;
  private final HashMap<ReplayKey, Instant> live;
  // One heap entry per live entry, created only at insert and removed only by
  // reclamation, so expiryOrder.size() always equals live.size() and no stale
  // expiry record can ever address a newer live generation.
  private final PriorityQueue<ExpiryEntry> expiryOrder;
  private long insertionSequence;
  private long reclamationRemovals;
  private long saturatedRefusals;
  private long expiredSubmissionRefusals;

  public InMemorySourceAttestationReplayStore() {
    this(DEFAULT_CAPACITY, DEFAULT_CLEANUP_BUDGET);
  }

  public InMemorySourceAttestationReplayStore(int capacity, int cleanupBudget) {
    if (capacity <= 0 || cleanupBudget <= 0) {
      throw new IllegalArgumentException(
          "Capacity and cleanup budget must be positive: " + capacity + ", " + cleanupBudget);
    }
    this.capacity = capacity;
    this.cleanupBudget = cleanupBudget;
    this.live = new HashMap<>();
    this.expiryOrder = new PriorityQueue<>();
  }

  @Override
  public boolean consume(String issuer, String tokenId, Instant expiresAt, Instant now) {
    Objects.requireNonNull(issuer, "issuer");
    Objects.requireNonNull(tokenId, "tokenId");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(now, "now");
    ReplayKey key = new ReplayKey(issuer, tokenId);
    synchronized (this) {
      if (!expiresAt.isAfter(now)) {
        expiredSubmissionRefusals++;
        return false;
      }
      reclaimExpired(now);
      if (live.containsKey(key)) {
        return false;
      }
      if (live.size() >= capacity) {
        saturatedRefusals++;
        return false;
      }
      live.put(key, expiresAt);
      expiryOrder.add(new ExpiryEntry(expiresAt, insertionSequence++, key));
      return true;
    }
  }

  private void reclaimExpired(Instant now) {
    int remaining = cleanupBudget;
    while (remaining > 0 && !expiryOrder.isEmpty()) {
      ExpiryEntry oldest = expiryOrder.peek();
      if (oldest.expiresAt().isAfter(now)) {
        return;
      }
      expiryOrder.poll();
      live.remove(oldest.key());
      reclamationRemovals++;
      remaining--;
    }
  }

  int liveEntries() {
    synchronized (this) {
      return live.size();
    }
  }

  int expiryIndexEntries() {
    synchronized (this) {
      return expiryOrder.size();
    }
  }

  long reclamationRemovals() {
    synchronized (this) {
      return reclamationRemovals;
    }
  }

  long saturatedRefusals() {
    synchronized (this) {
      return saturatedRefusals;
    }
  }

  long expiredSubmissionRefusals() {
    synchronized (this) {
      return expiredSubmissionRefusals;
    }
  }

  private record ReplayKey(String issuer, String tokenId) {}

  private record ExpiryEntry(Instant expiresAt, long sequence, ReplayKey key)
      implements Comparable<ExpiryEntry> {
    @Override
    public int compareTo(ExpiryEntry other) {
      int byExpiry = expiresAt.compareTo(other.expiresAt);
      return byExpiry != 0 ? byExpiry : Long.compare(sequence, other.sequence);
    }
  }
}
