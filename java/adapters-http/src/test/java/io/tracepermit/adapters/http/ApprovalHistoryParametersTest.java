package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.TenantPartition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ApprovalHistoryParametersTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "limit=0", "limit=51", "limit=01", "limit=-1", "limit=1.0", "limit=999",
      "status=all", "status=", "toolName=%25", "approvalRequestId=+", "tenant=other", "subject=other",
      "limit=1&limit=2", "limit=1&%6cimit=1", "limit=1&status=pending&toolName=t&approvalRequestId=r&cursor=c&x=y",
      "limit=1&", "limit", "=1", "cursor=A", "cursor=ab", "cursor=abc=", "cursor=%23", "status=%XX"})
  void malformedInputIsBoundedAndNeverReflected(String value) {
    assertThatThrownBy(() -> parse(value)).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid approval history query.");
  }

  @Test void defaultsExactMetadataAndContextBoundContinuation() throws Exception {
    assertThat(parse(null).query().limit()).isEqualTo(50);
    var first = parse("limit=2&status=pending&toolName=aws.logs.read&approvalRequestId=request-1");
    var last = approval();
    String token = first.nextCursor(last);
    assertThat(token.length()).isLessThan(2048);
    var second = parse("limit=1&status=pending&toolName=aws.logs.read&approvalRequestId=request-1&cursor=" + token);
    assertThat(second.query().beforeCreatedAt()).isEqualTo(last.binding().createdAt());
    assertThat(second.query().afterRequestId()).isEqualTo("request-1");
    assertThatThrownBy(() -> parse("cursor=" + token)).isInstanceOf(IllegalArgumentException.class);
    for (String[] context : List.of(new String[]{"other", "principal:a", null},
        new String[]{"tenant", "principal:b", null}, new String[]{"tenant", "principal:a", "principal:a"})) {
      assertThatThrownBy(() -> ApprovalHistoryParameters.parse(
          "status=pending&toolName=aws.logs.read&approvalRequestId=request-1&cursor=" + token,
          context[0], context[1], context[2])).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test void invalidCursorShapesTimesAndBoundsFailClosed() throws Exception {
    var first = parse(null);
    var valid = JsonSupport.MAPPER.readTree(Base64.getUrlDecoder().decode(first.nextCursor(approval())));
    for (String body : List.of("null", "[]", "{}", "not-json", valid + " {}",
        valid.toString().replace("\"1.0\"", "\"unknown\""),
        valid.toString().replace("\"1.0\"", "1"),
        valid.toString().replace("\"1.0\"", "1.0"),
        valid.toString().replace("request-1", "%"),
        valid.toString().replace("2026-10-17T00:00:00Z", "bad-time"),
        valid.toString().replace("2026-10-17T00:00:00Z", "2026-10-17T00:00:00.000000001Z"),
        valid.toString().replace("2026-10-17T00:00:00Z", "+10000-01-01T00:00:00Z"),
        valid.toString().replace("{", "{\"schemaVersion\":\"1.0\","),
        valid.toString().replace("{", "{\"extra\":true,"),
        valid.toString().replace("\"request-1\"", "1"),
        valid.toString().replace("\"2026-10-17T00:00:00Z\"", "false"),
        valid.toString().replace("sha256:", "sha512:"))) {
      assertThatThrownBy(() -> parse("cursor=" + encode(body))).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> parse("cursor=" + "A".repeat(2049))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> parse("status=" + "A".repeat(4096))).isInstanceOf(IllegalArgumentException.class);
  }

  private static ApprovalHistoryParameters parse(String query) {
    return ApprovalHistoryParameters.parse(query, "tenant", "principal:a", null);
  }
  private static String encode(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
  private static ApprovalRequestState approval() {
    String hash = "sha256:" + "a".repeat(64);
    Instant now = Instant.parse("2026-10-17T00:00:00Z");
    var binding = new ApprovalBinding(new TenantPartition("tenant"), "request-1", hash, hash,
        "event-1", "eval-1", "subject-1", hash, "policy-1", 1, "require_approval", "aws.logs.read",
        hash, "reviewers", now, now.plusSeconds(300), 1);
    return new ApprovalRequestState(binding, "pending", 1, null, null, null, null, null, null, now, null, null);
  }
}
