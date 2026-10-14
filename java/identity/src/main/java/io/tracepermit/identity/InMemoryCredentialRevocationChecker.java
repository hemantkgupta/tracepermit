package io.tracepermit.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit development/test adapter. Production revocation requires a durable authoritative source. */
public final class InMemoryCredentialRevocationChecker implements CredentialRevocationChecker {
  private final Set<String> revoked = ConcurrentHashMap.newKeySet();
  private volatile boolean available = true;

  @Override
  public RevocationStatus status(String tenantId, String issuer, String tokenId, Instant now) {
    Objects.requireNonNull(now);
    if (!available) return RevocationStatus.UNKNOWN;
    return revoked.contains(index(tenantId, issuer, tokenId))
        ? RevocationStatus.REVOKED
        : RevocationStatus.ACTIVE;
  }

  public void revoke(String tenantId, String issuer, String tokenId) {
    revoked.add(index(tenantId, issuer, tokenId));
  }

  public void setAvailable(boolean value) {
    available = value;
  }

  private static String index(String tenantId, String issuer, String tokenId) {
    return tenantId + "\u0000" + issuer + "\u0000" + tokenId;
  }
}
