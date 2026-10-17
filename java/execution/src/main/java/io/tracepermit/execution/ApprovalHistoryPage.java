package io.tracepermit.execution;

import java.util.List;

/** One bounded database read, not a snapshot held across HTTP requests. */
public record ApprovalHistoryPage(List<ApprovalRequestState> items, boolean hasMore) {
  public ApprovalHistoryPage {
    items = List.copyOf(items);
    if (items.size() > 50 || (hasMore && items.isEmpty())) {
      throw new IllegalArgumentException("Invalid approval history page.");
    }
  }
}
