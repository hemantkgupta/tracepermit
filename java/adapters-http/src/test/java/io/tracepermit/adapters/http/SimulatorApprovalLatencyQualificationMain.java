package io.tracepermit.adapters.http;

import org.testcontainers.containers.PostgreSQLContainer;

import java.io.PrintStream;

/**
 * Standalone executable qualification harness that measures reproducible loopback MCP
 * and HTTP approval latency against a containerized PostgreSQL instance.
 *
 * <p>Requirements: PERF-001/002/003, OPS-003, SEC-001/002/005/006, REL-001/005.
 * ADRs: 0035, 0036, 0037, 0040, 0042, 0044, 0045.
 */
public final class SimulatorApprovalLatencyQualificationMain {

  private SimulatorApprovalLatencyQualificationMain() {}

  /**
   * Testable runner that executes the full latency qualification suite and returns an exit code.
   * Guarantees resource cleanup (container, server, client, executors) before returning.
   *
   * @param out output stream for reports and progress
   * @param err error stream for failure diagnostics
   * @param args optional command-line arguments
   * @return 0 on success, non-zero on failure or error
   */
  public static int run(PrintStream out, PrintStream err, String[] args) {
    int[] concurrencyLevels = new int[] {1, 4, 20};
    int[] warmupCounts = new int[] {5, 8, 20};
    int[] measuredCounts = new int[] {20, 32, 40};

    PostgreSQLContainer<?> postgres = null;
    try {
      out.println("Starting disposable PostgreSQL container (postgres:16.4-alpine)...");
      postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
      postgres.start();

      out.println("Running closed-loop simulator approval latency qualification...");
      ApprovalLatencyMeasurements.LatencyQualificationReport report =
          ApprovalLatencyMeasurements.runQualification(postgres, concurrencyLevels, warmupCounts, measuredCounts);

      out.println(report.toConsoleSummary());
      out.println("\nJSON Report:");
      out.println(report.toJson().toPrettyString());

      if (!report.passed()) {
        err.println("\n[ERROR] Qualification failed with " + report.failures().size() + " failure(s):");
        for (String failure : report.failures()) {
          err.println("  - " + failure);
        }
        return 1;
      }

      out.println("\n[OK] Qualification passed successfully.");
      return 0;
    } catch (Throwable t) {
      err.println("\n[FATAL] Error during latency qualification: " + t.getClass().getSimpleName());
      return 1;
    } finally {
      if (postgres != null) {
        try {
          postgres.close();
        } catch (Throwable ignored) {}
      }
    }
  }

  public static void main(String[] args) {
    int exitCode = run(System.out, System.err, args);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }
}
