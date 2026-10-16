package io.tracepermit.security;

import java.time.Instant;

public interface PermitConsumptionStore {
  boolean consume(String permitId, Instant expiresAt, Instant now);

  default boolean consume(String permitId, Instant expiresAt, String actionHash, Instant now) {
    return consume(permitId, expiresAt, now);
  }

  default boolean consume(String tenantId, String permitId, Instant expiresAt, Instant now) {
    return consume(tenantId, permitId, expiresAt, null, now);
  }

  default boolean consume(
      String tenantId, String permitId, Instant expiresAt, String actionHash, Instant now) {
    return consume(permitId, expiresAt, actionHash, now);
  }
}

