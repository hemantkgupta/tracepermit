package io.tracepermit.identity;

import java.time.Instant;

public interface SourceAttestationReplayStore {
  boolean consume(String issuer, String tokenId, Instant expiresAt, Instant now);
}
