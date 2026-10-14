package io.tracepermit.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class InMemoryEvidenceLedger {
  private static final java.time.format.DateTimeFormatter MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
  private final List<JsonNode> records = new ArrayList<>();
  private final String streamId;
  private final Clock clock;
  private final Supplier<String> createId;

  public InMemoryEvidenceLedger(String streamId, Clock clock, Supplier<String> createId) {
    this.streamId = Objects.requireNonNull(streamId);
    this.clock = Objects.requireNonNull(clock);
    this.createId = Objects.requireNonNull(createId);
  }

  public synchronized JsonNode append(ObjectNode fields, Instant occurredAt) {
    String previous = records.isEmpty() ? null : records.getLast().path("integrity").path("eventHash").asText();
    ObjectNode payload = fields.deepCopy();
    payload.put("schemaVersion", "1.0");
    payload.put("evidenceEventId", createId.get());
    payload.put("streamId", streamId);
    payload.put("sequence", records.size() + 1);
    payload.put("occurredAt", MILLIS.format(occurredAt));
    payload.put("recordedAt", MILLIS.format(clock.instant()));
    ObjectNode integrity = JsonSupport.MAPPER.createObjectNode();
    integrity.put("algorithm", "sha256");
    if (previous == null) integrity.putNull("previousEventHash"); else integrity.put("previousEventHash", previous);
    integrity.put("eventHash", hash(payload, previous));
    ObjectNode record = JsonSupport.MAPPER.createObjectNode();
    record.set("payload", payload);
    record.set("integrity", integrity);
    records.add(record);
    return record.deepCopy();
  }

  public synchronized List<JsonNode> readAll() {
    return records.stream().map(item -> item.<JsonNode>deepCopy()).toList();
  }

  public synchronized EvidenceIntegrityResult verify(String expectedHeadHash) {
    return verifyRecords(records, expectedHeadHash);
  }

  public static EvidenceIntegrityResult verifyRecords(List<? extends JsonNode> candidates, String expectedHeadHash) {
    String previous = null;
    for (int index = 0; index < candidates.size(); index++) {
      JsonNode record = candidates.get(index);
      if (record.path("payload").path("sequence").asInt() != index + 1) return invalid(candidates.size(), index, "INVALID_SEQUENCE", previous, expectedHeadHash);
      JsonNode prior = record.path("integrity").path("previousEventHash");
      String linked = prior.isNull() ? null : prior.asText();
      if (!Objects.equals(linked, previous)) return invalid(candidates.size(), index, "BROKEN_LINK", previous, expectedHeadHash);
      String calculated = hash(record.path("payload"), previous);
      if (!calculated.equals(record.path("integrity").path("eventHash").asText())) return invalid(candidates.size(), index, "HASH_MISMATCH", previous, expectedHeadHash);
      previous = calculated;
    }
    if (expectedHeadHash != null && !Objects.equals(previous, expectedHeadHash)) return invalid(candidates.size(), Math.max(0, candidates.size() - 1), "ANCHOR_MISMATCH", previous, expectedHeadHash);
    return new EvidenceIntegrityResult(true, candidates.size(), previous, expectedHeadHash != null, -1, null);
  }

  public static String hash(JsonNode payload, String previous) {
    ObjectNode input = JsonSupport.MAPPER.createObjectNode();
    input.put("algorithm", "sha256");
    input.set("payload", payload);
    if (previous == null) input.putNull("previousEventHash"); else input.put("previousEventHash", previous);
    return CanonicalJson.sha256(input);
  }

  private static EvidenceIntegrityResult invalid(int count, int index, String code, String head, String expected) {
    return new EvidenceIntegrityResult(false, count, head, expected != null, index, code);
  }
}
