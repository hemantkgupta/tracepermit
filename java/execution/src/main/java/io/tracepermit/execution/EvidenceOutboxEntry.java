package io.tracepermit.execution;

import java.time.Instant;
import java.util.Objects;

/** A leased, secret-minimized evidence pointer ready for external publication. */
public record EvidenceOutboxEntry(
    TenantPartition tenant,
    String outboxId,
    String streamId,
    long sequence,
    String evidenceEventId,
    String payloadHash,
    Instant createdAt,
    int publicationAttempts,
    String leaseOwner,
    Instant leaseExpiresAt) {
  public EvidenceOutboxEntry {
    Objects.requireNonNull(tenant);
    DurableExecutionClaim.requireIdentifier(outboxId, 256, "outbox ID");
    DurableExecutionClaim.requireIdentifier(streamId, 128, "stream ID");
    if (sequence < 1) throw new IllegalArgumentException("The evidence sequence is invalid.");
    DurableExecutionClaim.requireIdentifier(evidenceEventId, 256, "evidence event ID");
    DurableExecutionClaim.requireHash(payloadHash, "payload hash");
    Objects.requireNonNull(createdAt);
    if (publicationAttempts < 1) {
      throw new IllegalArgumentException("The publication attempt count is invalid.");
    }
    DurableExecutionClaim.requireIdentifier(leaseOwner, 128, "lease owner");
    Objects.requireNonNull(leaseExpiresAt);
    if (!leaseExpiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("The publication lease expiry is invalid.");
    }
  }
}
