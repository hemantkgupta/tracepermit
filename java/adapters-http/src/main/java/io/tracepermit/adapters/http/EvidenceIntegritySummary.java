package io.tracepermit.adapters.http;

public record EvidenceIntegritySummary(
    String eventId,
    String traceId,
    String streamId,
    boolean valid,
    boolean anchored,
    int eventCount,
    String headHash,
    String issueCode,
    boolean simulated) {}
