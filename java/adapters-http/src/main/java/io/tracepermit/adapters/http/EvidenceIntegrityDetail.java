package io.tracepermit.adapters.http;

import java.util.List;

public record EvidenceIntegrityDetail(
    String eventId,
    String traceId,
    String streamId,
    boolean valid,
    boolean anchored,
    int eventCount,
    String headHash,
    String issueCode,
    List<EvidenceEventView> events,
    boolean simulated) {

  public EvidenceIntegrityDetail {
    events = List.copyOf(events);
  }

  public EvidenceIntegritySummary summary() {
    return new EvidenceIntegritySummary(
        eventId,
        traceId,
        streamId,
        valid,
        anchored,
        eventCount,
        headHash,
        issueCode,
        simulated);
  }

  public record EvidenceEventView(
      String evidenceEventId,
      int sequence,
      String eventType,
      String occurredAt,
      String recordedAt,
      String previousEventHash,
      String eventHash) {}
}
