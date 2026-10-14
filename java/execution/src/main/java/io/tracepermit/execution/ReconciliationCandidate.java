package io.tracepermit.execution;

import java.time.Instant;
import java.util.Objects;

/** A leased unknown execution that may be queried but must never be re-executed. */
public record ReconciliationCandidate(
    TenantPartition tenant,
    String attemptId,
    String adapterId,
    String idempotencyKey,
    Instant unknownSince,
    int lookupAttempts,
    String leaseOwner,
    Instant leaseExpiresAt) {
  public ReconciliationCandidate {
    Objects.requireNonNull(tenant);
    DurableExecutionClaim.requireIdentifier(attemptId, 256, "attempt ID");
    DurableExecutionClaim.requireIdentifier(adapterId, 128, "adapter ID");
    DurableExecutionClaim.requireHash(idempotencyKey, "idempotency key");
    Objects.requireNonNull(unknownSince);
    if (lookupAttempts < 1) {
      throw new IllegalArgumentException("The reconciliation lookup attempt count is invalid.");
    }
    DurableExecutionClaim.requireIdentifier(leaseOwner, 128, "lease owner");
    Objects.requireNonNull(leaseExpiresAt);
    if (!leaseExpiresAt.isAfter(unknownSince)) {
      throw new IllegalArgumentException("The reconciliation lease expiry is invalid.");
    }
  }
}
