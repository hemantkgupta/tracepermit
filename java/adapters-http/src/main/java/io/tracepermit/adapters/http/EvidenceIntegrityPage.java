package io.tracepermit.adapters.http;

import java.util.List;

public record EvidenceIntegrityPage(
    String schemaVersion,
    boolean simulated,
    int total,
    List<EvidenceIntegritySummary> items) {

  public EvidenceIntegrityPage {
    items = List.copyOf(items);
  }
}
