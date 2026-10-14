package io.tracepermit.adapters.http;

public record PolicyVersionSummary(
    String policyId,
    int version,
    String name,
    String status,
    String schemaVersion,
    String contentHash,
    int evaluationCount,
    String activatedAt,
    boolean simulated) {}
