package io.tracepermit.adapters.http;

import java.util.List;

public record ActionFeedPage(
    String schemaVersion,
    boolean simulated,
    int total,
    String nextCursor,
    List<ActionFeedEntry> items) {
  public ActionFeedPage {
    items = List.copyOf(items);
  }
}
