package io.tracepermit.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Process-local, bounded single-use store for execution permits.
 *
 * <p>Like the legacy implementation this store keeps one <em>global</em> permit-ID namespace:
 * the tenant- and action-aware interface overloads delegate here without partitioning permit
 * IDs, so a permit ID consumed under one tenant is refused everywhere. Single use stays
 * atomic: duplicate and capacity checks and the insert happen under one monitor, so
 * concurrent claims of one permit produce exactly one winner. The store is bounded by a
 * finite entry cap and never evicts a live entry to accept more work: at full capacity a
 * fresh consume fails closed ({@code false}), the same refusal contract as a replay.
 * Reclamation is bounded per call: at most {@code cleanupBudget} expired entries (expiry at
 * or before the supplied {@code now}) are reclaimed per consume, never a full-map sweep.
 *
 * <p>State is per process instance: it does not survive a restart, is not shared across
 * replicas, and trusts the supplied clock. Defaults: 65,536 entries, 64 reclamation removals
 * per call.
 */
public final class InMemoryPermitConsumptionStore implements PermitConsumptionStore {
  private static final int DEFAULT_CAPACITY = 65_536;
  private static final int DEFAULT_CLEANUP_BUDGET = 64;

  private final int capacity;
  private final int cleanupBudget;
  private final HashMap<String, Instant> live;
  // One heap entry per live entry, created only at insert and removed only by
  // reclamation, so expiryOrder.size() always equals live.size() and no stale
  // expiry record can ever address a newer live generation.
  private final PriorityQueue<ExpiryEntry> expiryOrder;
  private long insertionSequence;
  private long reclamationRemovals;
  private long saturatedRefusals;
  private long expiredSubmissionRefusals;

  public InMemoryPermitConsumptionStore() {
    this(DEFAULT_CAPACITY, DEFAULT_CLEANUP_BUDGET);
  }

  public InMemoryPermitConsumptionStore(int capacity, int cleanupBudget) {
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
  public boolean consume(String permitId, Instant expiresAt, Instant now) {
    Objects.requireNonNull(permitId, "permitId");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(now, "now");
    synchronized (this) {
      if (!expiresAt.isAfter(now)) {
        expiredSubmissionRefusals++;
        return false;
      }
      reclaimExpired(now);
      if (live.containsKey(permitId)) {
        return false;
      }
      if (live.size() >= capacity) {
        saturatedRefusals++;
        return false;
      }
      live.put(permitId, expiresAt);
      expiryOrder.add(new ExpiryEntry(expiresAt, insertionSequence++, permitId));
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
      live.remove(oldest.permitId());
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

  private record ExpiryEntry(Instant expiresAt, long sequence, String permitId)
      implements Comparable<ExpiryEntry> {
    @Override
    public int compareTo(ExpiryEntry other) {
      int byExpiry = expiresAt.compareTo(other.expiresAt);
      return byExpiry != 0 ? byExpiry : Long.compare(sequence, other.sequence);
    }
  }
}
