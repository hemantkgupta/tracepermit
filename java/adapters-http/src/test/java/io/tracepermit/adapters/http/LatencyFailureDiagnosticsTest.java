package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LatencyFailureDiagnosticsTest {
  @Test
  void knownStatusesAreCountedWithoutCopyingUnknownMessageContent() {
    var result = LatencyFailureDiagnostics.operationCategories(List.of(
        "HTTP_REVIEW_STATUS_503", "HTTP_REVIEW_STATUS_503",
        "TOOL_ERROR_APPROVAL_NOT_EXECUTABLE", "HTTP_SECRET_CANARY",
        "HTTP_REVIEW_STATUS_503_SECRET_CANARY", "MCP_REQUEST_HTTP_STATUS_401"));
    assertThat(result).containsEntry("HTTP_REVIEW_STATUS_503", 2)
        .containsEntry("OTHER_OPERATION_FAILURE", 2)
        .containsEntry("TOOL_ERROR_APPROVAL_NOT_EXECUTABLE", 1)
        .containsEntry("MCP_REQUEST_HTTP_STATUS_401", 1);
    assertThat(result.toString()).doesNotContain("SECRET_CANARY");
  }

  @Test
  void categoryCardinalityIsBoundedAndAllFailuresRemainCounted() {
    List<String> codes = new ArrayList<>();
    for (int index = 0; index < 100; index++) codes.add("HTTP_REVIEW_STATUS_" + (400 + index));
    var result = LatencyFailureDiagnostics.operationCategories(codes);
    assertThat(result.size()).isLessThanOrEqualTo(9);
    assertThat(result.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
  }

  @Test
  void databaseLogObservationRetainsOnlyFixedCategories() {
    var diagnostics = new LatencyFailureDiagnostics();
    diagnostics.observeDatabaseLog("ERROR: deadlock detected; SECRET_CANARY statement and detail");
    diagnostics.observeDatabaseLog("ERROR: deadlock detected; another SECRET_CANARY");
    diagnostics.observeDatabaseLog("ERROR: duplicate key value violates unique constraint SECRET_CANARY");
    diagnostics.observeDatabaseLog("unrecognized SECRET_CANARY text");
    assertThat(diagnostics.databaseCategories()).isEqualTo(Map.of(
        "PG_DEADLOCK", 2, "PG_UNIQUE_VIOLATION", 1));
    assertThat(diagnostics.databaseCategories().toString()).doesNotContain("SECRET_CANARY");
  }

  @Test
  void phaseFailurePublishesCategoriesButCannotBecomeSuccess() {
    List<String> failures = new ArrayList<>();
    var phase = ApprovalLatencyMeasurements.runPhase("http_review", 1, List.of(),
        List.of(new ApprovalLatencyMeasurements.ApprovalItem(443)), item -> {
          throw new IllegalStateException("HTTP_REVIEW_STATUS_503");
        }, failures);
    assertThat(phase.failureCount()).isEqualTo(1);
    assertThat(phase.successCount()).isZero();
    assertThat(failures).singleElement().asString().contains("HTTP_REVIEW_STATUS_503");
  }
}
