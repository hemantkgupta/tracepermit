package io.tracepermit.application;

import io.tracepermit.execution.TenantPartition;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Requester authorization facts derived by a trusted adapter, never by action or HTTP data. */
public record TrustedRequesterContext(
    TenantPartition tenant,
    String requesterSubjectId,
    boolean authenticated,
    Set<String> roles,
    Set<String> scopes,
    String authenticationMethod,
    String credentialFingerprint,
    Instant authorityExpiresAt) {
  public TrustedRequesterContext {
    Objects.requireNonNull(tenant);
    requireIdentifier(requesterSubjectId, 256, true);
    roles = Set.copyOf(roles);
    scopes = Set.copyOf(scopes);
    if (roles.size() > 32 || scopes.size() > 64) {
      throw new IllegalArgumentException("The requester authorization context is too large.");
    }
    roles.forEach(value -> requireIdentifier(value, 128, false));
    scopes.forEach(value -> requireIdentifier(value, 128, false));
    requireIdentifier(authenticationMethod, 128, false);
    Objects.requireNonNull(credentialFingerprint);
    if (!credentialFingerprint.matches("^sha256:[a-f0-9]{64}$")) {
      throw new IllegalArgumentException("The requester credential fingerprint is invalid.");
    }
    Objects.requireNonNull(authorityExpiresAt);
  }

  private static void requireIdentifier(String value, int maximum, boolean subject) {
    Objects.requireNonNull(value);
    String pattern = subject ? "^[A-Za-z0-9][A-Za-z0-9._:@/+\\-#]*$"
        : "^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]*$";
    if (value.isEmpty() || value.length() > maximum || !value.matches(pattern)) {
      throw new IllegalArgumentException("The requester authorization identifier is invalid.");
    }
  }
}
