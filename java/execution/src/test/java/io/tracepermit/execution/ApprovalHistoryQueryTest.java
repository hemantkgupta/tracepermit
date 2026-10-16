package io.tracepermit.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ApprovalHistoryQueryTest {
  @Test void strictSelectorsAndCursorPairsNeverBecomeAuthority() {
    assertThat(new ApprovalHistoryQuery(50, null, null, null, null, null).limit()).isEqualTo(50);
    for (String status : List.of("pending", "approved", "denied", "expired", "consumed")) {
      assertThat(new ApprovalHistoryQuery(1, status, "aws.logs.read", "request-1", Instant.EPOCH, "request-1").status())
          .isEqualTo(status);
    }
    for (int limit : new int[]{0, -1, 51, Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> new ApprovalHistoryQuery(limit, null, null, null, null, null))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new ApprovalHistoryQuery(1, "all", null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalHistoryQuery(1, null, "%", null, null, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalHistoryQuery(1, null, null, " ", null, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalHistoryQuery(1, null, null, null, Instant.EPOCH, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalHistoryQuery(1, null, null, null, null, "id")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalHistoryQuery(1, null, null, null, Instant.EPOCH, "%")).isInstanceOf(IllegalArgumentException.class);
    for (Instant instant : List.of(Instant.EPOCH.plusNanos(1), Instant.MIN, Instant.MAX)) {
      assertThatThrownBy(() -> new ApprovalHistoryQuery(1, null, null, null, instant, "id"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test void pagesCopyCollectionsAndRejectUnboundedOrImpossibleResults() {
    var source = new ArrayList<ApprovalRequestState>();
    var page = new ApprovalHistoryPage(source, false);
    assertThat(page.items()).isEmpty();
    assertThatThrownBy(() -> page.items().add(null)).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> new ApprovalHistoryPage(List.of(), true)).isInstanceOf(IllegalArgumentException.class);
    // Size is checked independently from elements; a repeated synthetic aggregate is sufficient.
    var binding = new ApprovalBinding(new TenantPartition("simulated-a"), "request-1", hash(), hash(),
        "event-1", "eval-1", "user:alice", hash(), "policy-1", 1, "require_approval",
        "aws.logs.read", hash(), "reviewers", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), 1);
    var item = new ApprovalRequestState(binding, "pending", 1, null, null, null, null, null, null, Instant.EPOCH, null, null);
    source.add(item);
    assertThat(page.items()).isEmpty();
    assertThatThrownBy(() -> new ApprovalHistoryPage(java.util.Collections.nCopies(51, item), false))
        .isInstanceOf(IllegalArgumentException.class);
  }
  private static String hash() { return "sha256:" + "a".repeat(64); }
}
