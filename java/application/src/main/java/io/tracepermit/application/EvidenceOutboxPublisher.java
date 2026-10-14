package io.tracepermit.application;

import io.tracepermit.execution.EvidenceAnchorReceipt;
import io.tracepermit.execution.EvidenceOutboxEntry;
import io.tracepermit.execution.EvidencePublicationState;
import io.tracepermit.execution.TenantPartition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Publishes leased evidence outside database transactions and stores only verified receipts. */
public final class EvidenceOutboxPublisher {
  private static final int MAXIMUM_BATCH = 100;
  private static final Duration MAXIMUM_LEASE = Duration.ofMinutes(5);

  private final EvidencePublicationState state;
  private final EvidenceAnchorPublisher publisher;
  private final EvidenceAnchorVerifier verifier;
  private final Clock clock;
  private final String workerId;
  private final Duration leaseDuration;
  private final Duration retryDelay;

  public EvidenceOutboxPublisher(
      EvidencePublicationState state,
      EvidenceAnchorPublisher publisher,
      EvidenceAnchorVerifier verifier,
      Clock clock,
      String workerId,
      Duration leaseDuration,
      Duration retryDelay) {
    this.state = Objects.requireNonNull(state);
    this.publisher = Objects.requireNonNull(publisher);
    this.verifier = Objects.requireNonNull(verifier);
    this.clock = Objects.requireNonNull(clock);
    this.workerId = requireWorkerId(workerId);
    this.leaseDuration = requireDuration(leaseDuration, MAXIMUM_LEASE, "lease");
    this.retryDelay = requireDuration(retryDelay, Duration.ofHours(1), "retry");
  }

  public EvidencePublicationResult publishBatch(TenantPartition tenant, int maximumEntries) {
    Objects.requireNonNull(tenant);
    if (maximumEntries < 1 || maximumEntries > MAXIMUM_BATCH) {
      throw new IllegalArgumentException("The evidence publication batch size is invalid.");
    }
    Instant claimedAt = clock.instant();
    List<EvidenceOutboxEntry> entries =
        state.claimEvidenceBatch(
            tenant, workerId, maximumEntries, claimedAt, claimedAt.plus(leaseDuration));
    if (entries.size() > maximumEntries) {
      throw new IllegalStateException("The evidence state returned an oversized batch.");
    }
    int published = 0;
    for (EvidenceOutboxEntry entry : entries) {
      try {
        requireLease(entry);
        EvidenceAnchorReceipt receipt = Objects.requireNonNull(publisher.publish(entry));
        receipt.requireBinding(entry);
        verifier.verify(receipt);
        state.markEvidencePublished(entry, receipt, workerId, clock.instant());
        published++;
      } catch (RuntimeException failure) {
        state.releaseEvidenceClaim(
            entry, workerId, "ANCHOR_PUBLICATION_FAILED", clock.instant().plus(retryDelay));
      }
    }
    return new EvidencePublicationResult(entries.size(), published, entries.size() - published);
  }

  private void requireLease(EvidenceOutboxEntry entry) {
    if (!workerId.equals(entry.leaseOwner()) || !entry.leaseExpiresAt().isAfter(clock.instant())) {
      throw new IllegalStateException("The evidence publication lease is not valid.");
    }
  }

  private static String requireWorkerId(String value) {
    Objects.requireNonNull(value);
    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}")) {
      throw new IllegalArgumentException("The evidence publisher worker ID is invalid.");
    }
    return value;
  }

  private static Duration requireDuration(Duration value, Duration maximum, String label) {
    Objects.requireNonNull(value);
    if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException("The evidence publication " + label + " duration is invalid.");
    }
    return value;
  }
}
