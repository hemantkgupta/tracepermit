package io.tracepermit.security;

import java.time.Instant;

public interface PermitConsumptionStore {
  boolean consume(String permitId, Instant expiresAt, Instant now);
}
