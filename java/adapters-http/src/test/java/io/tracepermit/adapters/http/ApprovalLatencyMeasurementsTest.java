package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

final class ApprovalLatencyMeasurementsTest {

  private static PostgreSQLContainer<?> postgres;

  @BeforeAll
  static void startContainer() {
    postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    postgres.start();
  }

  @AfterAll
  static void stopContainer() {
    if (postgres != null) {
      postgres.stop();
    }
  }

  @Test
  void nearestRankIndexComputesCorrectIndices() {
    assertThatThrownBy(() -> ApprovalLatencyMeasurements.nearestRankIndex(0, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalLatencyMeasurements.nearestRankIndex(10, 0.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalLatencyMeasurements.nearestRankIndex(10, 1.1))
        .isInstanceOf(IllegalArgumentException.class);

    // Size = 1
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(1, 0.50)).isEqualTo(0);
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(1, 0.95)).isEqualTo(0);
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(1, 0.99)).isEqualTo(0);
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(1, 1.00)).isEqualTo(0);

    // Size = 100: rank = ceil(p * 100), index = rank - 1
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(100, 0.50)).isEqualTo(49);
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(100, 0.95)).isEqualTo(94);
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(100, 0.99)).isEqualTo(98);
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(100, 1.00)).isEqualTo(99);

    // Size = 20: rank = ceil(p * 20), index = rank - 1
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(20, 0.50)).isEqualTo(9);
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(20, 0.95)).isEqualTo(18);
    assertThat(ApprovalLatencyMeasurements.nearestRankIndex(20, 0.99)).isEqualTo(19);
  }

  @Test
  void computePercentilesHandlesDistributions() {
    assertThatThrownBy(() -> ApprovalLatencyMeasurements.computePercentiles(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalLatencyMeasurements.computePercentiles(new long[0]))
        .isInstanceOf(IllegalArgumentException.class);

    // Constant array of 10 items with 5_000_000 ns (5.0 ms)
    long[] constantNanos = new long[10];
    for (int i = 0; i < 10; i++) {
      constantNanos[i] = 5_000_000L;
    }
    ApprovalLatencyMeasurements.Percentiles pConstant = ApprovalLatencyMeasurements.computePercentiles(constantNanos);
    assertThat(pConstant.minMs()).isEqualTo(5.0);
    assertThat(pConstant.maxMs()).isEqualTo(5.0);
    assertThat(pConstant.meanMs()).isEqualTo(5.0);
    assertThat(pConstant.p50Ms()).isEqualTo(5.0);
    assertThat(pConstant.p95Ms()).isEqualTo(5.0);
    assertThat(pConstant.p99Ms()).isEqualTo(5.0);

    // 100 items from 1ms to 100ms (1_000_000 to 100_000_000 ns)
    long[] rangeNanos = new long[100];
    for (int i = 0; i < 100; i++) {
      rangeNanos[i] = (i + 1) * 1_000_000L;
    }
    ApprovalLatencyMeasurements.Percentiles pRange = ApprovalLatencyMeasurements.computePercentiles(rangeNanos);
    assertThat(pRange.minMs()).isEqualTo(1.0);
    assertThat(pRange.maxMs()).isEqualTo(100.0);
    assertThat(pRange.p50Ms()).isEqualTo(50.0);
    assertThat(pRange.p95Ms()).isEqualTo(95.0);
    assertThat(pRange.p99Ms()).isEqualTo(99.0);
    assertThat(pRange.meanMs()).isEqualTo(50.5);
  }

  @Test
  void phaseMetricsCalculatesThroughputCorrectly() {
    ApprovalLatencyMeasurements.PhaseMetrics zeroElapsed = new ApprovalLatencyMeasurements.PhaseMetrics(
        "test_phase", 1, 0, 10, 10, 0, 0, 0, null);
    assertThat(zeroElapsed.throughputOpsPerSec()).isEqualTo(0.0);

    ApprovalLatencyMeasurements.PhaseMetrics zeroSuccess = new ApprovalLatencyMeasurements.PhaseMetrics(
        "test_phase", 1, 0, 10, 0, 10, 0, 1_000_000_000L, null);
    assertThat(zeroSuccess.throughputOpsPerSec()).isEqualTo(0.0);

    // 10 successes in 2 seconds (2_000_000_000 ns) = 5.0 ops/sec
    ApprovalLatencyMeasurements.PhaseMetrics metrics = new ApprovalLatencyMeasurements.PhaseMetrics(
        "test_phase", 1, 0, 10, 10, 0, 0, 2_000_000_000L, null);
    assertThat(metrics.throughputOpsPerSec()).isEqualTo(5.0);
  }

  @Test
  void reportJsonContainsRequiredMetadataWithoutLeakingSecrets() {
    ApprovalLatencyMeasurements.Percentiles p = new ApprovalLatencyMeasurements.Percentiles(
        12.345, 23.456, 34.567, 5.678, 45.678, 15.0);
    ApprovalLatencyMeasurements.PhaseMetrics pm = new ApprovalLatencyMeasurements.PhaseMetrics(
        "mcp_request", 1, 1, 5, 5, 0, 0, 1_000_000_000L, p);
    ApprovalLatencyMeasurements.SweepMetrics sweep = new ApprovalLatencyMeasurements.SweepMetrics(
        1, Map.of("mcp_request", pm), 1_500_000_000L, 6, 6, 6);

    ApprovalLatencyMeasurements.LatencyQualificationReport report =
        new ApprovalLatencyMeasurements.LatencyQualificationReport(
            "1.0",
            "2026-10-17T00:00:00Z",
            "simulator-authenticated-durable-approval",
            "test-composition",
            "System.nanoTime()",
            Map.of("commitSha", "abc1234", "dirty", "false"),
            Map.of("os", "Mac OS X", "arch", "aarch64", "availableProcessors", 8, "javaVersion", "21", "postgresVersion", "16.4"),
            ApprovalLatencyMeasurements.TIMING_INCLUDED,
            ApprovalLatencyMeasurements.TIMING_EXCLUDED,
            List.of(sweep),
            true,
            true,
            0,
            true,
            List.of());

    ObjectNode json = report.toJson();
    assertThat(json.path("schemaVersion").asText()).isEqualTo("1.0");
    assertThat(json.path("profile").asText()).isEqualTo("simulator-authenticated-durable-approval");
    assertThat(json.path("passed").asBoolean()).isTrue();
    assertThat(json.path("controls").path("unauthenticatedRejected").asBoolean()).isTrue();
    assertThat(json.path("controls").path("replayRejected").asBoolean()).isTrue();
    assertThat(json.path("controls").path("replayRuleDelta").asInt()).isEqualTo(0);

    String jsonString = json.toPrettyString();
    // Verify no secret leakage
    assertThat(jsonString).doesNotContain("Bearer ");
    assertThat(jsonString).doesNotContain("PRIVATE KEY");
    assertThat(jsonString).doesNotContain("eyJh");
  }

  @Test
  void smokeTestRunsQualificationAgainstPostgres() throws Exception {
    int[] concurrency = new int[] {1};
    int[] warmup = new int[] {1};
    int[] measured = new int[] {2};

    ApprovalLatencyMeasurements.LatencyQualificationReport report =
        ApprovalLatencyMeasurements.runQualification(postgres, concurrency, warmup, measured);

    assertThat(report.passed()).isTrue();
    assertThat(report.failures()).isEmpty();
    assertThat(report.unauthenticatedRejected()).isTrue();
    assertThat(report.replayRejected()).isTrue();
    assertThat(report.replayRuleDelta()).isEqualTo(0);
    assertThat(report.sweeps()).hasSize(1);

    ApprovalLatencyMeasurements.SweepMetrics sweep = report.sweeps().get(0);
    assertThat(sweep.concurrency()).isEqualTo(1);
    assertThat(sweep.observedRuleDelta()).isEqualTo(3); // 1 warmup + 2 measured
    assertThat(sweep.durableConsumedPermits()).isEqualTo(3);
    assertThat(sweep.durableExecutionAttempts()).isEqualTo(3);

    assertThat(sweep.phases()).containsKeys("mcp_request", "mcp_status", "http_review", "mcp_resume");
    for (ApprovalLatencyMeasurements.PhaseMetrics pm : sweep.phases().values()) {
      assertThat(pm.successCount()).isEqualTo(2);
      assertThat(pm.failureCount()).isEqualTo(0);
      assertThat(pm.percentiles()).isNotNull();
      assertThat(pm.percentiles().p50Ms()).isGreaterThan(0.0);
    }
  }
}
