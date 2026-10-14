package io.tracepermit.application;

import io.tracepermit.execution.TenantPartition;

import java.util.Objects;
import java.util.Set;

/** Authenticated reviewer facts supplied by a trusted adapter, never by approval request data. */
public record TrustedReviewerContext(
    TenantPartition tenant,
    String reviewerSubjectId,
    String principalType,
    boolean authenticated,
    Set<String> roles,
    Set<String> scopes,
    String authenticationMethod,
    String credentialFingerprint) {
  public TrustedReviewerContext {
    Objects.requireNonNull(tenant);
    requireIdentifier(reviewerSubjectId, 256, "reviewer subject ID");
    requireIdentifier(principalType, 32, "reviewer principal type");
    roles = Set.copyOf(roles);
    scopes = Set.copyOf(scopes);
    if (roles.size() > 32 || scopes.size() > 64) {
      throw new IllegalArgumentException("The reviewer authorization context is too large.");
    }
    roles.forEach(value -> requireIdentifier(value, 128, "reviewer role"));
    scopes.forEach(value -> requireIdentifier(value, 128, "reviewer scope"));
    requireIdentifier(authenticationMethod, 128, "reviewer authentication method");
    Objects.requireNonNull(credentialFingerprint);
    if (!credentialFingerprint.matches("^sha256:[a-f0-9]{64}$")) {
      throw new IllegalArgumentException("The reviewer credential fingerprint is invalid.");
    }
  }

  private static void requireIdentifier(String value, int maximum, String label) {
    Objects.requireNonNull(value);
    if (value.isEmpty() || value.length() > maximum
        || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]*$")) {
      throw new IllegalArgumentException("The " + label + " is invalid.");
    }
  }
}
