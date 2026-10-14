package io.tracepermit.identity;

import java.time.Instant;

/** Revocation is an independent trust input; unavailable status is never treated as active. */
public interface CredentialRevocationChecker {
  RevocationStatus status(String tenantId, String issuer, String tokenId, Instant now);

  enum RevocationStatus {
    ACTIVE,
    REVOKED,
    UNKNOWN
  }
}
