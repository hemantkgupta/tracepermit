package io.tracepermit.adapters.http;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryControlPlaneViews {
  private final Map<String, EvidenceIntegrityDetail> evidence = new LinkedHashMap<>();
  private final Map<String, PolicyVersionDetail> policies = new LinkedHashMap<>();
  private final Map<String, LineageDetail> lineages = new LinkedHashMap<>();

  public synchronized void appendEvidence(EvidenceIntegrityDetail detail) {
    if (evidence.putIfAbsent(detail.eventId(), detail) != null) {
      throw new IllegalArgumentException("An evidence-integrity view with that event ID already exists.");
    }
  }

  public synchronized void appendPolicy(PolicyVersionDetail detail) {
    if (policies.putIfAbsent(policyKey(detail.policyId(), detail.version()), detail) != null) {
      throw new IllegalArgumentException("A policy-version view with that ID and version already exists.");
    }
  }

  public synchronized void replacePolicy(PolicyVersionDetail detail) {
    String key = policyKey(detail.policyId(), detail.version());
    if (!policies.containsKey(key)) {
      throw new IllegalArgumentException("The policy-version view does not exist.");
    }
    policies.put(key, detail);
  }

  public synchronized void appendLineage(LineageDetail detail) {
    if (lineages.putIfAbsent(detail.traceId(), detail) != null) {
      throw new IllegalArgumentException("A lineage view with that trace ID already exists.");
    }
  }

  public synchronized EvidenceIntegrityPage evidencePage() {
    List<EvidenceIntegritySummary> items = evidence.values().stream()
        .map(EvidenceIntegrityDetail::summary)
        .sorted(Comparator.comparing(EvidenceIntegritySummary::eventId).reversed())
        .toList();
    return new EvidenceIntegrityPage("1.0", true, items.size(), items);
  }

  public synchronized EvidenceIntegrityDetail evidence(String eventId) {
    return evidence.get(eventId);
  }

  public synchronized PolicyVersionPage policyPage() {
    List<PolicyVersionSummary> items = policies.values().stream()
        .map(PolicyVersionDetail::summary)
        .sorted(Comparator.comparingInt(PolicyVersionSummary::version).reversed())
        .toList();
    return new PolicyVersionPage("1.0", true, items.size(), items);
  }

  public synchronized PolicyVersionDetail policy(String policyId, int version) {
    return policies.get(policyKey(policyId, version));
  }

  public synchronized LineagePage lineagePage() {
    List<LineageSummary> items = lineages.values().stream()
        .map(LineageDetail::summary)
        .sorted(Comparator.comparing(LineageSummary::observedAt).reversed())
        .toList();
    return new LineagePage("1.0", true, items.size(), items);
  }

  public synchronized LineageDetail lineage(String traceId) {
    return lineages.get(traceId);
  }

  private static String policyKey(String policyId, int version) {
    return policyId + ":" + version;
  }
}
