package io.tracepermit.security;

import java.time.Instant;

public interface PermitConsumptionStore {
  /** Legacy process-local implementations may not require action provenance. */
  boolean consume(String permitId, Instant expiresAt, Instant now);

  /** Durable implementations require the verified canonical action hash. */
  default boolean consume(String permitId, Instant expiresAt, String actionHash, Instant now) {
    return consume(permitId, expiresAt, now);
  }

  default boolean consume(String tenantId, String permitId, Instant expiresAt, Instant now) {
    return consume(tenantId, permitId, expiresAt, null, now);
  }

  default boolean consume(
      String tenantId, String permitId, Instant expiresAt, String actionHash, Instant now) {
    // Legacy implementations keep one global replay namespace; they do not provide tenant isolation.
    return consume(permitId, expiresAt, actionHash, now);
  }
}
