package io.tracepermit.adapters.http;

import java.util.List;

public record LineagePage(
    String schemaVersion,
    boolean simulated,
    int total,
    List<LineageSummary> items) {

  public LineagePage {
    items = List.copyOf(items);
  }
}
