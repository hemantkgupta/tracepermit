package io.tracepermit.adapters.http;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Standalone executable qualification harness that measures reproducible loopback MCP
 * and HTTP approval latency against a containerized PostgreSQL instance.
 *
 * <p>Requirements: PERF-001/002/003, OPS-003, SEC-001/002/005/006, REL-001/005.
 * ADRs: 0035, 0036, 0037, 0040, 0042, 0044, 0045.
 */
public final class SimulatorApprovalLatencyQualificationMain {

  private SimulatorApprovalLatencyQualificationMain() {}

  public static void main(String[] args) {
    int[] concurrencyLevels = new int[] {1, 4, 20};
    int[] warmupCounts = new int[] {5, 8, 20};
    int[] measuredCounts = new int[] {20, 32, 40};

    System.out.println("Starting disposable PostgreSQL container (postgres:16.4-alpine)...");
    try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")) {
      postgres.start();

      System.out.println("Running closed-loop simulator approval latency qualification...");
      ApprovalLatencyMeasurements.LatencyQualificationReport report =
          ApprovalLatencyMeasurements.runQualification(postgres, concurrencyLevels, warmupCounts, measuredCounts);

      System.out.println(report.toConsoleSummary());
      System.out.println("\nJSON Report:");
      System.out.println(report.toJson().toPrettyString());

      if (!report.passed()) {
        System.err.println("\n[ERROR] Qualification failed with " + report.failures().size() + " failure(s):");
        for (String failure : report.failures()) {
          System.err.println("  - " + failure);
        }
        System.exit(1);
      }

      System.out.println("\n[OK] Qualification passed successfully.");
    } catch (Exception e) {
      System.err.println("\n[FATAL] Unexpected error during latency qualification: " + e.getMessage());
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }
}
