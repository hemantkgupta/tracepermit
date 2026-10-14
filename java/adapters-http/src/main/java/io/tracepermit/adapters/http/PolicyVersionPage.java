package io.tracepermit.adapters.http;

import java.util.List;

public record PolicyVersionPage(
    String schemaVersion,
    boolean simulated,
    int total,
    List<PolicyVersionSummary> items) {

  public PolicyVersionPage {
    items = List.copyOf(items);
  }
}
