package io.tracepermit.identity;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySourceAttestationReplayStore implements SourceAttestationReplayStore {
  private final ConcurrentHashMap<String, Instant> consumed = new ConcurrentHashMap<>();

  @Override
  public boolean consume(String issuer, String tokenId, Instant expiresAt, Instant now) {
    consumed.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    return consumed.putIfAbsent(issuer + "\u0000" + tokenId, expiresAt) == null;
  }
}
