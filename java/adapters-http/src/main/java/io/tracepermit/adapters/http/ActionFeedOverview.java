package io.tracepermit.adapters.http;

public record ActionFeedOverview(
    String schemaVersion,
    boolean simulated,
    int total,
    int allowed,
    int blocked,
    int sanitized,
    int approvalRequired,
    long p95LatencyMs,
    int latencySampleSize,
    String latencySampleScope) {}
