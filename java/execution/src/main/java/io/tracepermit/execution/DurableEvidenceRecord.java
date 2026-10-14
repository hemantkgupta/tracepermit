package io.tracepermit.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public record DurableEvidenceRecord(
    TenantPartition tenant,
    String streamId,
    long sequence,
    String evidenceEventId,
    JsonNode payload,
    String previousEventHash,
    String eventHash) {
  public DurableEvidenceRecord {
    Objects.requireNonNull(tenant);
    DurableExecutionClaim.requireIdentifier(streamId, 128, "stream ID");
    if (sequence < 1) throw new IllegalArgumentException("The evidence sequence is invalid.");
    DurableExecutionClaim.requireIdentifier(evidenceEventId, 256, "evidence event ID");
    payload = Objects.requireNonNull(payload).deepCopy();
    if (previousEventHash != null) DurableExecutionClaim.requireHash(previousEventHash, "previous event hash");
    DurableExecutionClaim.requireHash(eventHash, "event hash");
  }

  @Override
  public JsonNode payload() {
    return payload.deepCopy();
  }
}
