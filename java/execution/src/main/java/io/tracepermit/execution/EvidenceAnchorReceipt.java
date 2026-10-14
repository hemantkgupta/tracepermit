package io.tracepermit.execution;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** Versioned receipt signed by an independently trusted evidence anchor. */
public record EvidenceAnchorReceipt(
    String schemaVersion,
    TenantPartition tenant,
    String outboxId,
    String streamId,
    long sequence,
    String evidenceEventId,
    String payloadHash,
    String provider,
    String keyId,
    String anchorId,
    Instant anchoredAt,
    String signatureAlgorithm,
    String signature) {
  public static final String VERSION = "1.0";

  public EvidenceAnchorReceipt {
    if (!VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("The evidence anchor receipt version is unsupported.");
    }
    Objects.requireNonNull(tenant);
    DurableExecutionClaim.requireIdentifier(outboxId, 256, "outbox ID");
    DurableExecutionClaim.requireIdentifier(streamId, 128, "stream ID");
    if (sequence < 1) throw new IllegalArgumentException("The evidence sequence is invalid.");
    DurableExecutionClaim.requireIdentifier(evidenceEventId, 256, "evidence event ID");
    DurableExecutionClaim.requireHash(payloadHash, "payload hash");
    DurableExecutionClaim.requireIdentifier(provider, 128, "anchor provider");
    DurableExecutionClaim.requireIdentifier(keyId, 128, "anchor key ID");
    DurableExecutionClaim.requireIdentifier(anchorId, 256, "anchor ID");
    Objects.requireNonNull(anchoredAt);
    if (!"Ed25519".equals(signatureAlgorithm)) {
      throw new IllegalArgumentException("The evidence anchor signature algorithm is unsupported.");
    }
    Objects.requireNonNull(signature);
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(signature);
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("The evidence anchor signature is malformed.", failure);
    }
    if (decoded.length != 64
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(signature)) {
      throw new IllegalArgumentException("The evidence anchor signature is malformed.");
    }
  }

  public byte[] signingInput() {
    return String.join(
            "\n",
            "tracepermit-evidence-anchor-v1",
            tenant.value(),
            outboxId,
            streamId,
            Long.toString(sequence),
            evidenceEventId,
            payloadHash,
            provider,
            keyId,
            anchorId,
            anchoredAt.toString(),
            signatureAlgorithm)
        .getBytes(StandardCharsets.UTF_8);
  }

  public void requireBinding(EvidenceOutboxEntry entry) {
    Objects.requireNonNull(entry);
    if (!tenant.equals(entry.tenant())
        || !outboxId.equals(entry.outboxId())
        || !streamId.equals(entry.streamId())
        || sequence != entry.sequence()
        || !evidenceEventId.equals(entry.evidenceEventId())
        || !payloadHash.equals(entry.payloadHash())) {
      throw new IllegalArgumentException("The evidence anchor receipt binding does not match.");
    }
  }
}
