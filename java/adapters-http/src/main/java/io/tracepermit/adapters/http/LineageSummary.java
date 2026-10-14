package io.tracepermit.adapters.http;

public record LineageSummary(
    String eventId,
    String traceId,
    String observedAt,
    String intent,
    String rootPrincipalId,
    int hopCount,
    int delegationClaimCount,
    String authoritySummary,
    boolean simulated) {}
