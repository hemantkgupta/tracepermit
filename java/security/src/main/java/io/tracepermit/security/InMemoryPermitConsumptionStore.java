package io.tracepermit.security;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryPermitConsumptionStore implements PermitConsumptionStore {
  private final ConcurrentHashMap<String, Instant> consumed = new ConcurrentHashMap<>();

  @Override
  public boolean consume(String permitId, Instant expiresAt, Instant now) {
    consumed.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    return consumed.putIfAbsent(permitId, expiresAt) == null;
  }
}
