package io.tracepermit.execution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record DurableExecutionClaim(
    TenantPartition tenant,
    String permitId,
    Instant permitExpiresAt,
    String adapterId,
    String idempotencyKey,
    String eventId,
    String traceId,
    String evaluationId,
    String subjectId,
    String actionHash,
    String policyId,
    int policyVersion,
    String decision,
    List<String> credentialFingerprints,
    Instant occurredAt) {
  public DurableExecutionClaim {
    Objects.requireNonNull(tenant);
    requireIdentifier(permitId, 256, "permit ID");
    Objects.requireNonNull(permitExpiresAt);
    requireIdentifier(adapterId, 128, "adapter ID");
    requireHash(idempotencyKey, "idempotency key");
    requireIdentifier(eventId, 256, "event ID");
    requireIdentifier(traceId, 256, "trace ID");
    requireIdentifier(evaluationId, 256, "evaluation ID");
    requireSubjectIdentifier(subjectId, "subject ID");
    requireHash(actionHash, "action hash");
    requireIdentifier(policyId, 256, "policy ID");
    if (policyVersion < 1) throw new IllegalArgumentException("The policy version is invalid.");
    if (!"allow".equals(decision)) throw new IllegalArgumentException("Only allow decisions can be durably claimed.");
    credentialFingerprints = List.copyOf(credentialFingerprints);
    if (credentialFingerprints.size() > 16) throw new IllegalArgumentException("Too many credential fingerprints.");
    credentialFingerprints.forEach(value -> requireHash(value, "credential fingerprint"));
    Objects.requireNonNull(occurredAt);
    if (!permitExpiresAt.isAfter(occurredAt)) throw new IllegalArgumentException("The execution permit is expired.");
  }

  static void requireIdentifier(String value, int maximum, String label) {
    Objects.requireNonNull(value);
    if (value.isEmpty()
        || value.length() > maximum
        || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]*$")) {
      throw new IllegalArgumentException("The " + label + " is invalid.");
    }
  }

  static void requireSubjectIdentifier(String value, String label) {
    Objects.requireNonNull(value);
    if (value.isEmpty()
        || value.length() > 256
        || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-#]*$")) {
      throw new IllegalArgumentException("The " + label + " is invalid.");
    }
  }

  static void requireHash(String value, String label) {
    Objects.requireNonNull(value);
    if (!value.matches("^sha256:[a-f0-9]{64}$")) throw new IllegalArgumentException("The " + label + " is invalid.");
  }
}
