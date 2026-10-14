package io.tracepermit.identity;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryWorkloadIdentityReplayStore implements WorkloadIdentityReplayStore {
  private final ConcurrentHashMap<String, Instant> consumed = new ConcurrentHashMap<>();

  @Override
  public boolean consume(
      String tenantId, String issuer, String tokenId, Instant expiresAt, Instant now) {
    consumed.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    return consumed.putIfAbsent(tenantId + "\u0000" + issuer + "\u0000" + tokenId, expiresAt)
        == null;
  }
}
