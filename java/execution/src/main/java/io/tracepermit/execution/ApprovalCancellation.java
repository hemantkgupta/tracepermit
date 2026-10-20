package io.tracepermit.execution;

import java.time.Instant;
import java.util.Objects;

/** Exact withdrawal intent and verified requester provenance; the store supplies transition time. */
public record ApprovalCancellation(
    TenantPartition tenant,
    String approvalRequestId,
    String requestHash,
    String requesterSubjectId,
    String authenticationMethod,
    String credentialFingerprint,
    Instant authorityExpiresAt) {
  public ApprovalCancellation {
    Objects.requireNonNull(tenant);
    DurableExecutionClaim.requireIdentifier(approvalRequestId, 256, "approval request ID");
    DurableExecutionClaim.requireHash(requestHash, "request hash");
    DurableExecutionClaim.requireSubjectIdentifier(requesterSubjectId, "requester subject ID");
    DurableExecutionClaim.requireIdentifier(authenticationMethod, 128, "authentication method");
    DurableExecutionClaim.requireHash(credentialFingerprint, "credential fingerprint");
    Objects.requireNonNull(authorityExpiresAt);
  }
}
