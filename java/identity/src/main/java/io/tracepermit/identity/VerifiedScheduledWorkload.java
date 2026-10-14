package io.tracepermit.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Secret-free projection emitted only after workload and complete-chain verification. */
public record VerifiedScheduledWorkload(
    String tenantId,
    String subjectId,
    String schedulerId,
    String issuer,
    String keyId,
    String keyFingerprint,
    String credentialFingerprint,
    Instant verifiedAt,
    List<VerifiedDelegationGrant> delegations) {
  public VerifiedScheduledWorkload {
    Objects.requireNonNull(tenantId);
    Objects.requireNonNull(subjectId);
    Objects.requireNonNull(schedulerId);
    Objects.requireNonNull(issuer);
    Objects.requireNonNull(keyId);
    Objects.requireNonNull(keyFingerprint);
    Objects.requireNonNull(credentialFingerprint);
    Objects.requireNonNull(verifiedAt);
    delegations = List.copyOf(delegations);
  }

  public record VerifiedDelegationGrant(
      String tokenId,
      String parentTokenId,
      String issuer,
      String keyId,
      String keyFingerprint,
      String actorId,
      String subjectId,
      String audience,
      List<String> scopes,
      List<String> resources,
      String purpose,
      String traceId,
      Instant issuedAt,
      Instant expiresAt,
      String tokenHash) {
    public VerifiedDelegationGrant {
      Objects.requireNonNull(tokenId);
      Objects.requireNonNull(issuer);
      Objects.requireNonNull(keyId);
      Objects.requireNonNull(keyFingerprint);
      Objects.requireNonNull(actorId);
      Objects.requireNonNull(subjectId);
      Objects.requireNonNull(audience);
      scopes = List.copyOf(scopes);
      resources = List.copyOf(resources);
      Objects.requireNonNull(purpose);
      Objects.requireNonNull(traceId);
      Objects.requireNonNull(issuedAt);
      Objects.requireNonNull(expiresAt);
      Objects.requireNonNull(tokenHash);
    }
  }
}
