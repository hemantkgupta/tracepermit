package io.tracepermit.execution;

import java.time.Instant;
import java.util.List;

/** Durable lease and receipt port for the transactional evidence outbox. */
public interface EvidencePublicationState {
  List<EvidenceOutboxEntry> claimEvidenceBatch(
      TenantPartition tenant,
      String workerId,
      int maximumEntries,
      Instant claimedAt,
      Instant leaseExpiresAt);

  void markEvidencePublished(
      EvidenceOutboxEntry entry,
      EvidenceAnchorReceipt receipt,
      String workerId,
      Instant publishedAt);

  void releaseEvidenceClaim(
      EvidenceOutboxEntry entry,
      String workerId,
      String failureCode,
      Instant nextAttemptAt);

  List<EvidenceAnchorReceipt> readEvidenceAnchors(TenantPartition tenant);
}
