package io.tracepermit.identity;

import java.time.Instant;

/** Atomic single-use boundary for one request-bound workload identity credential. */
public interface WorkloadIdentityReplayStore {
  boolean consume(String tenantId, String issuer, String tokenId, Instant expiresAt, Instant now);
}
