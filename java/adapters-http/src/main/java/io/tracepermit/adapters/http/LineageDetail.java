package io.tracepermit.adapters.http;

import java.util.List;

public record LineageDetail(
    String eventId,
    String traceId,
    String observedAt,
    String intent,
    String rootPrincipalId,
    int hopCount,
    int delegationClaimCount,
    String authoritySummary,
    String targetSystem,
    String targetTool,
    List<LineageNodeView> hops,
    boolean simulated) {

  public LineageDetail {
    hops = List.copyOf(hops);
  }

  public LineageSummary summary() {
    return new LineageSummary(
        eventId,
        traceId,
        observedAt,
        intent,
        rootPrincipalId,
        hopCount,
        delegationClaimCount,
        authoritySummary,
        simulated);
  }

  public record LineageNodeView(
      String hopId,
      String parentHopId,
      String kind,
      String subjectId,
      String assurance,
      String source,
      String relation) {}
}
