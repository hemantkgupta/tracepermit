package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import io.tracepermit.identity.StaticOidcProviderRegistry;
import io.tracepermit.persistence.postgres.PostgresDurableEnforcementState;
import io.tracepermit.persistence.postgres.PostgresPermitConsumptionStore;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.simulator.StatefulTargetSimulator;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measurement utilities, statistical models, and closed-loop workload harness
 * for TracePermit's authenticated durable simulator approval runtime.
 *
 * <p>Requirements: PERF-001/002/003, OPS-003, SEC-001/002/005/006, REL-001/005.
 * ADRs: 0035, 0036, 0037, 0040, 0042, 0044, 0045.
 */
public final class ApprovalLatencyMeasurements {

  public static final String ISSUER = "https://idp.example.invalid";
  public static final String AUDIENCE = "https://approval.example.invalid";
  public static final String CLIENT_ID = "approval-client";
  public static final TenantPartition TENANT = new TenantPartition("simulated-alpha");

  public static final int SERVER_WORKER_THREADS = 8;
  public static final int MAX_CONCURRENCY = 50;
  public static final int MAX_WARMUP = 200;
  public static final int MAX_MEASURED = 500;
  public static final int MIN_PORT = 1024;
  public static final int MAX_PORT = 65535;

  public static final List<String> TIMING_INCLUDED = List.of(
      "http_client_serialization",
      "loopback_tcp_network_transit",
      "server_http_and_json_parsing",
      "rs256_synthetic_access_token_verification",
      "postgres_durable_transactions",
      "stateful_simulator_effect");

  public static final List<String> TIMING_EXCLUDED = List.of(
      "key_pair_generation",
      "container_startup_and_schema_migrations",
      "jwt_token_minting_and_signing",
      "human_review_waiting",
      "latency_percentiles_calculation");

  private ApprovalLatencyMeasurements() {}

  /**
   * Converts nanoseconds to milliseconds with fractional precision.
   */
  public static double nanosToMillis(long nanos) {
    return nanos / 1_000_000.0;
  }

  /**
   * Calculates nearest-rank 0-based index for a sorted sample set.
   * Rank r = ceil(p * N), index = r - 1.
   */
  public static int nearestRankIndex(int size, double percentile) {
    if (size <= 0) {
      throw new IllegalArgumentException("Sample size must be greater than zero.");
    }
    if (!Double.isFinite(percentile) || percentile <= 0.0 || percentile > 1.0) {
      throw new IllegalArgumentException("Percentile must be finite and in range (0.0, 1.0].");
    }
    return Math.max(0, Math.min(size - 1, (int) Math.ceil(percentile * size) - 1));
  }

  /**
   * Computes nearest-rank percentiles, min, max, and mean in milliseconds.
   * Rejects null, empty, or negative durations. Sums with floating-point accumulation
   * to eliminate 64-bit integer overflow risk while retaining fractional precision.
   */
  public static Percentiles computePercentiles(long[] sampleNanos) {
    if (sampleNanos == null || sampleNanos.length == 0) {
      throw new IllegalArgumentException("Cannot compute percentiles of null or empty sample set.");
    }
    int size = sampleNanos.length;
    long[] sorted = new long[size];
    double sumNanos = 0.0;
    for (int i = 0; i < size; i++) {
      long val = sampleNanos[i];
      if (val < 0L) {
        throw new IllegalArgumentException("Negative duration observed in sample set: " + val);
      }
      sorted[i] = val;
      sumNanos += val;
    }
    Arrays.sort(sorted);

    double p50 = nanosToMillis(sorted[nearestRankIndex(size, 0.50)]);
    double p95 = nanosToMillis(sorted[nearestRankIndex(size, 0.95)]);
    double p99 = nanosToMillis(sorted[nearestRankIndex(size, 0.99)]);
    double min = nanosToMillis(sorted[0]);
    double max = nanosToMillis(sorted[size - 1]);
    double mean = nanosToMillis((long) (sumNanos / size));

    return new Percentiles(p50, p95, p99, min, max, mean, size, "success_only");
  }

  /** Statistical summary for a measured latency distribution in milliseconds. */
  public record Percentiles(
      double p50Ms,
      double p95Ms,
      double p99Ms,
      double minMs,
      double maxMs,
      double meanMs,
      int sampleCount,
      String population) {

    public Percentiles(double p50Ms, double p95Ms, double p99Ms, double minMs, double maxMs, double meanMs) {
      this(p50Ms, p95Ms, p99Ms, minMs, maxMs, meanMs, 0, "success_only");
    }

    public ObjectNode toJson() {
      ObjectNode node = JsonSupport.MAPPER.createObjectNode();
      node.put("population", population != null ? population : "success_only");
      if (sampleCount > 0) {
        node.put("sampleCount", sampleCount);
      }
      node.put("p50Ms", round(p50Ms));
      node.put("p95Ms", round(p95Ms));
      node.put("p99Ms", round(p99Ms));
      node.put("minMs", round(minMs));
      node.put("maxMs", round(maxMs));
      node.put("meanMs", round(meanMs));
      return node;
    }

    private static double round(double val) {
      return Math.round(val * 1000.0) / 1000.0;
    }
  }

  /** Metrics for an individual workflow phase at a specific concurrency level. */
  public record PhaseMetrics(
      String phaseName,
      int concurrency,
      int warmupOperations,
      int measuredOperations,
      int successCount,
      int failureCount,
      int timeoutCount,
      long elapsedWallNanos,
      Percentiles percentiles) {

    public double throughputOpsPerSec() {
      if (elapsedWallNanos <= 0 || successCount == 0) {
        return 0.0;
      }
      return successCount / (elapsedWallNanos / 1_000_000_000.0);
    }

    public ObjectNode toJson() {
      ObjectNode node = JsonSupport.MAPPER.createObjectNode();
      node.put("phaseName", phaseName);
      node.put("concurrency", concurrency);
      node.put("warmupOperations", warmupOperations);
      node.put("measuredOperations", measuredOperations);
      node.put("successCount", successCount);
      node.put("failureCount", failureCount);
      node.put("timeoutCount", timeoutCount);
      node.put("elapsedWallTimeMs", Math.round(nanosToMillis(elapsedWallNanos) * 1000.0) / 1000.0);
      node.put("throughputOpsPerSec", Math.round(throughputOpsPerSec() * 100.0) / 100.0);
      if (percentiles != null) {
        node.set("percentiles", percentiles.toJson());
      }
      return node;
    }
  }

  /** Metrics across all ordered phases for a single concurrency sweep. */
  public record SweepMetrics(
      int concurrency,
      Map<String, PhaseMetrics> phases,
      long totalElapsedWallNanos,
      int observedRuleDelta,
      int durableConsumedPermits,
      int durableExecutionAttempts) {

    public ObjectNode toJson() {
      ObjectNode node = JsonSupport.MAPPER.createObjectNode();
      node.put("concurrency", concurrency);
      node.put("totalElapsedWallTimeMs", Math.round(nanosToMillis(totalElapsedWallNanos) * 1000.0) / 1000.0);
      node.put("observedRuleDelta", observedRuleDelta);
      node.put("durableConsumedPermits", durableConsumedPermits);
      node.put("durableExecutionAttempts", durableExecutionAttempts);

      ObjectNode phasesNode = node.putObject("phases");
      for (Map.Entry<String, PhaseMetrics> entry : phases.entrySet()) {
        phasesNode.set(entry.getKey(), entry.getValue().toJson());
      }
      return node;
    }
  }

  /** Complete structured report for the qualification run. */
  public record LatencyQualificationReport(
      String schemaVersion,
      String timestamp,
      String profile,
      String composition,
      String clock,
      Map<String, String> provenance,
      Map<String, Object> environment,
      List<String> timingIncluded,
      List<String> timingExcluded,
      List<SweepMetrics> sweeps,
      boolean unauthenticatedRejected,
      boolean replayRejected,
      int replayRuleDelta,
      boolean passed,
      List<String> failures) {

    public ObjectNode toJson() {
      ObjectNode root = JsonSupport.MAPPER.createObjectNode();
      root.put("schemaVersion", schemaVersion);
      root.put("timestamp", timestamp);
      root.put("profile", profile);
      root.put("composition", composition);
      root.put("clock", clock);

      ObjectNode provNode = root.putObject("provenance");
      provenance.forEach(provNode::put);

      ObjectNode envNode = root.putObject("environment");
      environment.forEach((k, v) -> envNode.put(k, String.valueOf(v)));

      ArrayNode incNode = root.putArray("timingIncluded");
      timingIncluded.forEach(incNode::add);

      ArrayNode excNode = root.putArray("timingExcluded");
      timingExcluded.forEach(excNode::add);

      ArrayNode sweepsNode = root.putArray("sweeps");
      sweeps.forEach(sweep -> sweepsNode.add(sweep.toJson()));

      ObjectNode controlsNode = root.putObject("controls");
      controlsNode.put("unauthenticatedRejected", unauthenticatedRejected);
      controlsNode.put("replayRejected", replayRejected);
      controlsNode.put("replayRuleDelta", replayRuleDelta);

      root.put("passed", passed);

      if (!failures.isEmpty()) {
        ArrayNode failNode = root.putArray("failures");
        failures.forEach(failNode::add);
      }

      return root;
    }

    public String toConsoleSummary() {
      StringBuilder sb = new StringBuilder();
      sb.append("\n=========================================================================================\n");
      sb.append("TracePermit Simulator Approval Latency Qualification Report\n");
      sb.append("=========================================================================================\n");
      sb.append(String.format("Profile:     %s%n", profile));
      sb.append(String.format("Composition: %s%n", composition));
      sb.append(String.format("Clock:       %s%n", clock));
      sb.append(String.format("Commit:      %s (dirty=%s)%n", provenance.get("commitSha"), provenance.get("dirty")));
      sb.append(String.format("Environment: %s %s, %s host CPUs, %s HTTP worker threads, %s%n",
          environment.get("os"), environment.get("arch"), environment.get("availableProcessors"),
          environment.get("serverWorkerThreads"), environment.get("javaVersion")));
      sb.append(String.format("PostgreSQL:  %s%n", environment.get("postgresVersion")));
      sb.append("-----------------------------------------------------------------------------------------\n");

      for (SweepMetrics sweep : sweeps) {
        sb.append(String.format("Concurrency Level: %d (Total elapsed: %.2f ms)%n",
            sweep.concurrency(), nanosToMillis(sweep.totalElapsedWallNanos())));
        sb.append(String.format("  %-16s | %-7s | %-8s | %-8s | %-8s | %-8s | %-10s%n",
            "Phase", "Samples", "p50 (ms)", "p95 (ms)", "p99 (ms)", "Mean (ms)", "Throughput"));
        sb.append("  -----------------+---------+----------+----------+----------+----------+------------\n");

        for (Map.Entry<String, PhaseMetrics> entry : sweep.phases().entrySet()) {
          PhaseMetrics pm = entry.getValue();
          Percentiles p = pm.percentiles();
          sb.append(String.format("  %-16s | %-7d | %-8.3f | %-8.3f | %-8.3f | %-8.3f | %8.1f op/s%n",
              entry.getKey(), pm.measuredOperations(),
              p != null ? p.p50Ms() : 0.0,
              p != null ? p.p95Ms() : 0.0,
              p != null ? p.p99Ms() : 0.0,
              p != null ? p.meanMs() : 0.0,
              pm.throughputOpsPerSec()));
        }
        sb.append(String.format("  Observed Rule Delta: %d | Consumed Permits: %d | Execution Attempts: %d%n%n",
            sweep.observedRuleDelta(), sweep.durableConsumedPermits(), sweep.durableExecutionAttempts()));
      }

      sb.append("Controls:\n");
      sb.append(String.format("  Unauthenticated call rejected: %s%n", unauthenticatedRejected ? "PASS" : "FAIL"));
      sb.append(String.format("  Replay call rejected:          %s%n", replayRejected ? "PASS" : "FAIL"));
      sb.append(String.format("  Replay rule delta == 0:        %s%n", replayRuleDelta == 0 ? "PASS" : "FAIL"));
      sb.append("-----------------------------------------------------------------------------------------\n");
      sb.append(String.format("Overall Status: %s%n", passed ? "PASSED" : "FAILED"));
      sb.append("=========================================================================================\n");

      return sb.toString();
    }
  }

  /**
   * Internal tracker for a single approval item across all 4 workflow phases.
   */
  static final class ApprovalItem {
    final int port;
    volatile String approvalRequestId;
    volatile String requestHash;

    ApprovalItem(int port) {
      this.port = port;
    }
  }

  enum OperationOutcomeKind {
    SUCCESS,
    FAILURE,
    TIMEOUT,
    CANCELLED_OR_NOT_STARTED
  }

  record OperationOutcome(
      int index,
      OperationOutcomeKind kind,
      long elapsedNanos,
      String safeErrorCode) {}

  record BatchExecution(
      int plannedCount,
      int successCount,
      int failureCount,
      int timeoutCount,
      int cancelledCount,
      long[] successDurationsNanos,
      long wallElapsedNanos,
      List<String> failureCodes) {}

  @FunctionalInterface
  interface ItemOperation {
    void execute(ApprovalItem item) throws Exception;
  }

  @FunctionalInterface
  interface PhaseBoundary {
    boolean awaitQuiescence();
  }

  /**
   * Executes a bounded concurrent batch of operations with exact accounting.
   * Every planned operation is accounted for exactly once in the terminal outcomes:
   * plannedCount == successCount + failureCount + timeoutCount + cancelledCount.
   */
  static BatchExecution runConcurrent(
      int concurrency,
      List<ApprovalItem> items,
      ItemOperation operation,
      Duration timeout) {

    int size = items.size();
    if (size == 0) {
      return new BatchExecution(0, 0, 0, 0, 0, new long[0], 0L, List.of());
    }

    int boundedConcurrency = Math.max(1, Math.min(concurrency, MAX_CONCURRENCY));
    ExecutorService executor = Executors.newFixedThreadPool(boundedConcurrency);

    OperationOutcome[] outcomes = new OperationOutcome[size];
    long wallStart = System.nanoTime();
    long deadlineNanos = wallStart + timeout.toNanos();

    List<Future<?>> futures = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      final int index = i;
      final ApprovalItem item = items.get(i);
      futures.add(executor.submit(() -> {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos) {
          outcomes[index] = new OperationOutcome(index, OperationOutcomeKind.CANCELLED_OR_NOT_STARTED, 0L, "NOT_STARTED");
          return;
        }
        long opStart = System.nanoTime();
        try {
          operation.execute(item);
          long opElapsed = Math.max(0L, System.nanoTime() - opStart);
          outcomes[index] = new OperationOutcome(index, OperationOutcomeKind.SUCCESS, opElapsed, null);
        } catch (HttpTimeoutException | TimeoutException e) {
          long opElapsed = Math.max(0L, System.nanoTime() - opStart);
          outcomes[index] = new OperationOutcome(index, OperationOutcomeKind.TIMEOUT, opElapsed, "TIMEOUT");
        } catch (Throwable t) {
          long opElapsed = Math.max(0L, System.nanoTime() - opStart);
          outcomes[index] = new OperationOutcome(index, OperationOutcomeKind.FAILURE, opElapsed, classifySafeErrorCode(t));
        }
      }));
    }

    // Await completion up to the single phase deadline
    for (int i = 0; i < size; i++) {
      Future<?> future = futures.get(i);
      long remainingNanos = deadlineNanos - System.nanoTime();
      try {
        if (remainingNanos > 0) {
          future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } else {
          future.cancel(true);
        }
      } catch (TimeoutException e) {
        for (int j = i; j < size; j++) {
          futures.get(j).cancel(true);
        }
        break;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        for (Future<?> f : futures) {
          f.cancel(true);
        }
        break;
      } catch (ExecutionException e) {
        // Worker recorded terminal outcome in outcomes[index]
      }
    }

    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    long wallElapsed = Math.max(0L, System.nanoTime() - wallStart);

    int successes = 0;
    int failures = 0;
    int timeouts = 0;
    int cancelled = 0;
    List<String> failureCodes = new ArrayList<>();

    for (int i = 0; i < size; i++) {
      OperationOutcome outcome = outcomes[i];
      if (outcome == null) {
        outcome = new OperationOutcome(i, OperationOutcomeKind.CANCELLED_OR_NOT_STARTED, 0L, "CANCELLED");
        outcomes[i] = outcome;
      }
      switch (outcome.kind()) {
        case SUCCESS -> successes++;
        case FAILURE -> {
          failures++;
          if (outcome.safeErrorCode() != null) {
            failureCodes.add(outcome.safeErrorCode());
          }
        }
        case TIMEOUT -> timeouts++;
        case CANCELLED_OR_NOT_STARTED -> cancelled++;
      }
    }

    if (successes + failures + timeouts + cancelled != size) {
      throw new IllegalStateException("Outcome counts do not reconcile: " +
          successes + " successes + " + failures + " failures + " + timeouts + " timeouts + " + cancelled + " cancelled != " + size);
    }

    long[] successDurations = new long[successes];
    int sIdx = 0;
    for (int i = 0; i < size; i++) {
      if (outcomes[i].kind() == OperationOutcomeKind.SUCCESS) {
        successDurations[sIdx++] = outcomes[i].elapsedNanos();
      }
    }

    return new BatchExecution(
        size,
        successes,
        failures,
        timeouts,
        cancelled,
        successDurations,
        wallElapsed,
        failureCodes);
  }

  /**
   * Executes one client-concurrency wave at a time and proves that the server has
   * returned every accepted handler lane before offering the next wave. The
   * operation timeout remains one budget across all waves; barrier time is bounded
   * separately and is not counted as operation wall time. This is qualification
   * coordination only: it never retries an item or changes server admission bounds.
   */
  static BatchExecution runConcurrentWaves(
      int concurrency,
      List<ApprovalItem> items,
      ItemOperation operation,
      Duration timeout,
      String phaseName,
      String population,
      PhaseBoundary phaseBoundary) {

    if (items.isEmpty()) {
      return new BatchExecution(0, 0, 0, 0, 0, new long[0], 0L, List.of());
    }

    int waveSize = Math.max(1, Math.min(concurrency, MAX_CONCURRENCY));
    long remainingBudgetNanos;
    try {
      remainingBudgetNanos = timeout.toNanos();
    } catch (ArithmeticException overflow) {
      remainingBudgetNanos = Long.MAX_VALUE;
    }

    int successes = 0;
    int failures = 0;
    int timeouts = 0;
    int cancelled = 0;
    long operationWallNanos = 0L;
    var successDurations = new ArrayList<Long>();
    var failureCodes = new ArrayList<String>();

    for (int from = 0; from < items.size(); from += waveSize) {
      int to = Math.min(items.size(), from + waveSize);
      if (remainingBudgetNanos <= 0L) {
        cancelled += items.size() - from;
        break;
      }

      BatchExecution wave = runConcurrent(
          waveSize,
          items.subList(from, to),
          operation,
          Duration.ofNanos(remainingBudgetNanos));
      successes += wave.successCount();
      failures += wave.failureCount();
      timeouts += wave.timeoutCount();
      cancelled += wave.cancelledCount();
      operationWallNanos = saturatedAdd(operationWallNanos, wave.wallElapsedNanos());
      remainingBudgetNanos = Math.max(0L, remainingBudgetNanos - wave.wallElapsedNanos());
      for (long duration : wave.successDurationsNanos()) {
        successDurations.add(duration);
      }
      failureCodes.addAll(wave.failureCodes());

      requirePhaseQuiescence(phaseName, population + " wave " + ((from / waveSize) + 1), phaseBoundary);
    }

    long[] durationArray = new long[successDurations.size()];
    for (int i = 0; i < successDurations.size(); i++) {
      durationArray[i] = successDurations.get(i);
    }
    return new BatchExecution(
        items.size(),
        successes,
        failures,
        timeouts,
        cancelled,
        durationArray,
        operationWallNanos,
        List.copyOf(failureCodes));
  }

  private static long saturatedAdd(long left, long right) {
    if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
    return left + right;
  }

  /**
   * Executes a workflow phase with separate warmup and measured operations.
   * Any unexpected failure in warm-up or measured batches records an explicit failure.
   */
  static PhaseMetrics runPhase(
      String phaseName,
      int concurrency,
      List<ApprovalItem> warmupItems,
      List<ApprovalItem> measuredItems,
      ItemOperation operation,
      List<String> failures) {
    return runPhase(phaseName, concurrency, warmupItems, measuredItems, operation, () -> true, failures);
  }

  static PhaseMetrics runPhase(
      String phaseName,
      int concurrency,
      List<ApprovalItem> warmupItems,
      List<ApprovalItem> measuredItems,
      ItemOperation operation,
      PhaseBoundary phaseBoundary,
      List<String> failures) {

    Duration phaseTimeout = Duration.ofSeconds(30);

    // 1. Warm-up operations (exercise exact code path; fail closed on warmup error)
    if (!warmupItems.isEmpty()) {
      BatchExecution warmupBatch = runConcurrentWaves(
          concurrency, warmupItems, operation, phaseTimeout, phaseName, "warmup", phaseBoundary);
      if (warmupBatch.failureCount() > 0 || warmupBatch.timeoutCount() > 0 || warmupBatch.cancelledCount() > 0) {
        failures.add(String.format("Phase %s (concurrency=%d) warmup had unexpected failures: %d failed, %d timed out, %d cancelled out of %d operations.",
            phaseName, concurrency, warmupBatch.failureCount(), warmupBatch.timeoutCount(), warmupBatch.cancelledCount(), warmupItems.size())
            + " Categories: " + LatencyFailureDiagnostics.operationCategories(warmupBatch.failureCodes()));
        return new PhaseMetrics(
            phaseName,
            concurrency,
            warmupItems.size(),
            measuredItems.size(),
            0,
            0,
            0,
            0L,
            null);
      }
    }

    // 2. Measured steady-state operations
    int measuredCount = measuredItems.size();
    BatchExecution measuredBatch = runConcurrentWaves(
        concurrency, measuredItems, operation, phaseTimeout, phaseName, "measured", phaseBoundary);
    int successes = measuredBatch.successCount();
    int failed = measuredBatch.failureCount();
    int timeouts = measuredBatch.timeoutCount();
    int cancelled = measuredBatch.cancelledCount();

    if (failed > 0 || timeouts > 0 || cancelled > 0 || successes != measuredCount) {
      failures.add(String.format("Phase %s (concurrency=%d) had %d failure(s), %d timeout(s), %d cancelled out of %d operations.",
          phaseName, concurrency, failed, timeouts, cancelled, measuredCount)
          + " Categories: " + LatencyFailureDiagnostics.operationCategories(measuredBatch.failureCodes()));
    }

    Percentiles percentiles = (successes > 0)
        ? computePercentiles(measuredBatch.successDurationsNanos())
        : null;

    return new PhaseMetrics(
        phaseName,
        concurrency,
        warmupItems.size(),
        measuredCount,
        successes,
        failed,
        timeouts,
        measuredBatch.wallElapsedNanos(),
        percentiles);
  }

  private static void requirePhaseQuiescence(
      String phaseName, String population, PhaseBoundary phaseBoundary) {
    if (!phaseBoundary.awaitQuiescence()) {
      throw new IllegalStateException(
          "PHASE_" + sanitizeCode(phaseName) + "_" + sanitizeCode(population) + "_QUIESCENCE_TIMEOUT");
    }
  }

  /**
   * Executes the full closed-loop qualification against a disposable PostgreSQL container.
   */
  public static LatencyQualificationReport runQualification(
      PostgreSQLContainer<?> postgres,
      int[] concurrencyLevels,
      int[] warmupCounts,
      int[] measuredCounts) throws Exception {

    Objects.requireNonNull(postgres, "PostgreSQL container must not be null");
    if (!postgres.isRunning()) {
      throw new IllegalStateException("PostgreSQL container is not running.");
    }
    if (concurrencyLevels == null || warmupCounts == null || measuredCounts == null) {
      throw new IllegalArgumentException("Concurrency, warmup, and measured count arrays must not be null.");
    }
    if (concurrencyLevels.length == 0 || concurrencyLevels.length != warmupCounts.length || concurrencyLevels.length != measuredCounts.length) {
      throw new IllegalArgumentException("Concurrency, warmup, and measured count arrays must have matching non-zero lengths.");
    }
    if (concurrencyLevels.length > 10) {
      throw new IllegalArgumentException("Maximum of 10 sweeps allowed.");
    }

    for (int i = 0; i < concurrencyLevels.length; i++) {
      if (concurrencyLevels[i] < 1 || concurrencyLevels[i] > MAX_CONCURRENCY) {
        throw new IllegalArgumentException("Concurrency level out of bounds [1, " + MAX_CONCURRENCY + "]: " + concurrencyLevels[i]);
      }
      if (warmupCounts[i] < 0 || warmupCounts[i] > MAX_WARMUP) {
        throw new IllegalArgumentException("Warmup count out of bounds [0, " + MAX_WARMUP + "]: " + warmupCounts[i]);
      }
      if (measuredCounts[i] < 1 || measuredCounts[i] > MAX_MEASURED) {
        throw new IllegalArgumentException("Measured count out of bounds [1, " + MAX_MEASURED + "]: " + measuredCounts[i]);
      }
    }

    int totalWarmup = 0;
    int totalMeasured = 0;
    for (int i = 0; i < concurrencyLevels.length; i++) {
      totalWarmup += warmupCounts[i];
      totalMeasured += measuredCounts[i];
    }
    int totalItems = totalWarmup + totalMeasured + 10;
    if (MIN_PORT + totalItems > MAX_PORT) {
      throw new IllegalArgumentException("Total requested operations exceeds port address space.");
    }

    Path root = Path.of(System.getProperty("tracepermit.repositoryRoot", "."));
    Map<String, String> provenance = getGitProvenance(root);

    KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
    rsaGen.initialize(2048);
    KeyPair rsaKeyPair = rsaGen.generateKeyPair();
    KeyPair permitKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSslMode("disable");

    String schema = "lat_qual_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
      stmt.setQueryTimeout(10);
      stmt.execute("CREATE SCHEMA " + schema);
    }
    dataSource.setCurrentSchema(schema);

    String postgresVersion = "unknown";
    try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT version()")) {
      if (rs.next()) {
        postgresVersion = rs.getString(1);
      }
    } catch (Exception ignored) {}

    Instant frozenInstant = Instant.parse("2030-01-01T00:00:00Z");
    Clock clock = Clock.fixed(frozenInstant, ZoneOffset.UTC);

    List<ApprovalMemberships.Entry> membershipsList = List.of(
        new ApprovalMemberships.Entry(
            ISSUER + "#alice", CLIENT_ID, TENANT, true, Set.of(ApprovalMemberships.Role.REQUESTER)),
        new ApprovalMemberships.Entry(
            ISSUER + "#bob", CLIENT_ID, TENANT, true, Set.of(ApprovalMemberships.Role.REVIEWER)));
    ApprovalMemberships memberships = new ApprovalMemberships(membershipsList);

    Set<ConnectorScope> allScopes = Set.of(
        ConnectorScope.of("approvals.read"),
        ConnectorScope.of("approvals.request"),
        ConnectorScope.of("approvals.decide"),
        ConnectorScope.of("approvals.execute"));

    OidcProviderConfiguration providerConfig = new OidcProviderConfiguration(
        URI.create(ISSUER),
        AUDIENCE,
        URI.create(ISSUER + "/jwks"),
        URI.create(AUDIENCE + "/metadata"),
        List.of(URI.create(ISSUER)),
        "Simulator approval latency qualification",
        Map.of(CLIENT_ID, "Approval Latency Client"),
        "org_id",
        Map.of("alpha", TENANT.value()),
        allScopes,
        ConnectorOrigin.CLI,
        Duration.ofMinutes(15),
        Duration.ofMinutes(1),
        Duration.ofSeconds(5));

    OidcAccessTokenVerifier verifier = new OidcAccessTokenVerifier(
        new StaticOidcProviderRegistry(List.of(providerConfig)),
        (p, keyId, alg, now) -> {
          if (!"fixture-key".equals(keyId)) {
            throw new AuthenticationException("INVALID_KEY", "Key not recognized");
          }
          return (RSAPublicKey) rsaKeyPair.getPublic();
        },
        clock);

    String requesterToken = mintToken("alice", "approvals.read approvals.request approvals.execute", rsaKeyPair, clock);
    String reviewerToken = mintToken("bob", "approvals.read approvals.decide", rsaKeyPair, clock);

    PostgresDurableEnforcementState enforcementState =
        new PostgresDurableEnforcementState(dataSource, clock, () -> UUID.randomUUID().toString());
    ExecutionPermitAuthority permitAuthority = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "fixture-ed25519",
        permitKeyPair,
        30,
        clock,
        () -> UUID.randomUUID().toString(),
        new PostgresPermitConsumptionStore(dataSource));
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    JsonNode policy = SimulatorApprovalMain.policy();

    SimulatorApprovalServer server = null;
    HttpClient client = null;

    List<SweepMetrics> sweepResults = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    boolean overallPassed = true;
    boolean unauthRejected = false;
    boolean replayRejected = false;
    int replayRuleDelta = -1;

    AtomicInteger portGenerator = new AtomicInteger(MIN_PORT);

    try {
      server = SimulatorApprovalServer.start(
          new InetSocketAddress("127.0.0.1", 0),
          verifier,
          memberships,
          enforcementState,
          policy,
          permitAuthority,
          simulator,
          clock);

      client = newQualificationHttpClient();

      performMcpHandshake(server.port(), requesterToken, client);

      for (int i = 0; i < concurrencyLevels.length; i++) {
        if (i > 0) {
          retireQualificationHttpClient(client);
          client = newQualificationHttpClient();
        }
        int concurrency = concurrencyLevels[i];
        int warmup = warmupCounts[i];
        int measured = measuredCounts[i];

        int sweepStartRules = countRules(simulator);
        int sweepStartPermits = queryCount(dataSource, "SELECT count(*) FROM tp_consumed_execution_permits WHERE tenant_id = 'simulated-alpha'");
        int sweepStartAttempts = queryCount(dataSource, "SELECT count(*) FROM tp_execution_attempts WHERE tenant_id = 'simulated-alpha'");
        long sweepStartNanos = System.nanoTime();

        Map<String, PhaseMetrics> phaseMetricsMap = new LinkedHashMap<>();

        List<ApprovalItem> warmupItems = new ArrayList<>();
        for (int w = 0; w < warmup; w++) {
          warmupItems.add(new ApprovalItem(portGenerator.incrementAndGet()));
        }
        List<ApprovalItem> measuredItems = new ArrayList<>();
        for (int m = 0; m < measured; m++) {
          measuredItems.add(new ApprovalItem(portGenerator.incrementAndGet()));
        }

        // --- Phase 1: mcp_request ---
        final SimulatorApprovalServer currentServer = server;
        final HttpClient currentClient = client;

        PhaseMetrics reqMetrics = runPhase(
            "mcp_request",
            concurrency,
            warmupItems,
            measuredItems,
            (item) -> executeMcpRequest(currentServer.port(), requesterToken, item, currentClient),
            () -> currentServer.awaitDispatchQuiescence(Duration.ofSeconds(5)),
            failures);
        phaseMetricsMap.put("mcp_request", reqMetrics);
        assertZeroStateDeltas("mcp_request", simulator, dataSource, sweepStartRules, sweepStartPermits, sweepStartAttempts, failures);

        // --- Phase 2: mcp_status ---
        PhaseMetrics statusMetrics = runPhase(
            "mcp_status",
            concurrency,
            warmupItems,
            measuredItems,
            (item) -> executeMcpStatus(currentServer.port(), requesterToken, item, currentClient),
            () -> currentServer.awaitDispatchQuiescence(Duration.ofSeconds(5)),
            failures);
        phaseMetricsMap.put("mcp_status", statusMetrics);
        assertZeroStateDeltas("mcp_status", simulator, dataSource, sweepStartRules, sweepStartPermits, sweepStartAttempts, failures);

        // --- Phase 3: http_review ---
        PhaseMetrics reviewMetrics = runPhase(
            "http_review",
            concurrency,
            warmupItems,
            measuredItems,
            (item) -> executeHttpReview(currentServer.port(), reviewerToken, item, currentClient),
            () -> currentServer.awaitDispatchQuiescence(Duration.ofSeconds(5)),
            failures);
        phaseMetricsMap.put("http_review", reviewMetrics);
        assertZeroStateDeltas("http_review", simulator, dataSource, sweepStartRules, sweepStartPermits, sweepStartAttempts, failures);

        // --- Phase 4: mcp_resume ---
        PhaseMetrics resumeMetrics = runPhase(
            "mcp_resume",
            concurrency,
            warmupItems,
            measuredItems,
            (item) -> executeMcpResume(currentServer.port(), requesterToken, item, currentClient),
            () -> currentServer.awaitDispatchQuiescence(Duration.ofSeconds(5)),
            failures);
        phaseMetricsMap.put("mcp_resume", resumeMetrics);

        long sweepElapsedNanos = System.nanoTime() - sweepStartNanos;
        int sweepEndRules = countRules(simulator);
        int observedRuleDelta = sweepEndRules - sweepStartRules;
        int expectedRuleDelta = warmup + measured;

        if (observedRuleDelta != expectedRuleDelta) {
          failures.add(String.format(
              "Sweep concurrency=%d expected rule delta %d, but observed %d.",
              concurrency, expectedRuleDelta, observedRuleDelta));
        }

        int currentPermits = queryCount(dataSource, "SELECT count(*) FROM tp_consumed_execution_permits WHERE tenant_id = 'simulated-alpha'");
        int currentAttempts = queryCount(dataSource, "SELECT count(*) FROM tp_execution_attempts WHERE tenant_id = 'simulated-alpha'");
        int observedPermitDelta = currentPermits - sweepStartPermits;
        int observedAttemptDelta = currentAttempts - sweepStartAttempts;

        if (observedPermitDelta != expectedRuleDelta) {
          failures.add(String.format(
              "Sweep concurrency=%d expected %d consumed permits delta, but observed %d.",
              concurrency, expectedRuleDelta, observedPermitDelta));
        }
        if (observedAttemptDelta != expectedRuleDelta) {
          failures.add(String.format(
              "Sweep concurrency=%d expected %d execution attempts delta, but observed %d.",
              concurrency, expectedRuleDelta, observedAttemptDelta));
        }

        sweepResults.add(new SweepMetrics(
            concurrency,
            phaseMetricsMap,
            sweepElapsedNanos,
            observedRuleDelta,
            currentPermits,
            currentAttempts));
      }

      // 3. Negative control: Unauthenticated call rejection
      int rulesBeforeUnauth = countRules(simulator);
      int permitsBeforeUnauth = queryCount(dataSource, "SELECT count(*) FROM tp_consumed_execution_permits WHERE tenant_id = 'simulated-alpha'");
      int attemptsBeforeUnauth = queryCount(dataSource, "SELECT count(*) FROM tp_execution_attempts WHERE tenant_id = 'simulated-alpha'");

      unauthRejected = executeUnauthenticatedCheck(server.port(), client);
      if (countRules(simulator) != rulesBeforeUnauth) {
        failures.add("Unauthenticated negative control modified target simulator state.");
      }
      if (queryCount(dataSource, "SELECT count(*) FROM tp_consumed_execution_permits WHERE tenant_id = 'simulated-alpha'") != permitsBeforeUnauth) {
        failures.add("Unauthenticated negative control created consumed permits.");
      }
      if (queryCount(dataSource, "SELECT count(*) FROM tp_execution_attempts WHERE tenant_id = 'simulated-alpha'") != attemptsBeforeUnauth) {
        failures.add("Unauthenticated negative control created execution attempts.");
      }
      if (!unauthRejected) {
        failures.add("Unauthenticated request was not rejected with HTTP 401.");
      }

      // 4. Replay control: Attempting to resume an already consumed approval
      ApprovalItem replayItem = new ApprovalItem(portGenerator.incrementAndGet());
      executeMcpRequest(server.port(), requesterToken, replayItem, client);
      executeHttpReview(server.port(), reviewerToken, replayItem, client);
      executeMcpResume(server.port(), requesterToken, replayItem, client);

      int rulesBeforeReplay = countRules(simulator);
      int permitsBeforeReplay = queryCount(dataSource, "SELECT count(*) FROM tp_consumed_execution_permits WHERE tenant_id = 'simulated-alpha'");
      int attemptsBeforeReplay = queryCount(dataSource, "SELECT count(*) FROM tp_execution_attempts WHERE tenant_id = 'simulated-alpha'");

      replayRejected = executeReplayCheck(server.port(), requesterToken, replayItem, client);
      replayRuleDelta = countRules(simulator) - rulesBeforeReplay;

      if (!replayRejected) {
        failures.add("Replay execution was not rejected with APPROVAL_NOT_EXECUTABLE.");
      }
      if (replayRuleDelta != 0) {
        failures.add("Replay execution added unexpected simulator rules: " + replayRuleDelta);
      }
      if (queryCount(dataSource, "SELECT count(*) FROM tp_consumed_execution_permits WHERE tenant_id = 'simulated-alpha'") != permitsBeforeReplay) {
        failures.add("Replay execution added consumed permits.");
      }
      if (queryCount(dataSource, "SELECT count(*) FROM tp_execution_attempts WHERE tenant_id = 'simulated-alpha'") != attemptsBeforeReplay) {
        failures.add("Replay execution created execution attempts.");
      }

      if (!failures.isEmpty()) {
        overallPassed = false;
      }

    } catch (Exception e) {
      failures.add("Execution exception: " + classifySafeErrorCode(e));
      overallPassed = false;
    } finally {
      if (server != null) {
        try {
          server.close();
        } catch (Throwable ignored) {}
      }
      if (client != null) {
        try {
          client.close();
        } catch (Throwable ignored) {}
      }
    }

    Map<String, Object> environment = new LinkedHashMap<>();
    environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    environment.put("arch", System.getProperty("os.arch"));
    environment.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    environment.put("serverWorkerThreads", SERVER_WORKER_THREADS);
    environment.put("javaVersion", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
    environment.put("postgresVersion", postgresVersion);

    return new LatencyQualificationReport(
        "1.0",
        Instant.now().toString(),
        "simulator-authenticated-durable-approval",
        "static-policy simulator server (8 worker threads), fixture-resolved RS256 token verification, unpooled PGSimpleDataSource, loopback HTTP",
        "System.nanoTime() elapsed duration; frozen synthetic authorization clock (2030-01-01)",
        provenance,
        environment,
        TIMING_INCLUDED,
        TIMING_EXCLUDED,
        sweepResults,
        unauthRejected,
        replayRejected,
        replayRuleDelta,
        overallPassed,
        failures);
  }

  static HttpClient newQualificationHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  /**
   * Retires one sweep's transport pool before the next sweep begins. This keeps
   * an idle connection closed by the JDK loopback server from leaking into a
   * later, slower sweep as an unrelated transport failure. No operation is
   * retried, and the fixed server admission capacity is unchanged.
   */
  static void retireQualificationHttpClient(HttpClient client) throws InterruptedException {
    client.shutdown();
    if (!client.awaitTermination(Duration.ofSeconds(5))) {
      client.shutdownNow();
      if (!client.awaitTermination(Duration.ofSeconds(5))) {
        throw new IllegalStateException("HTTP_CLIENT_SHUTDOWN_TIMEOUT");
      }
    }
  }

  private static void assertZeroStateDeltas(
      String phaseName,
      StatefulTargetSimulator simulator,
      PGSimpleDataSource dataSource,
      int expectedRules,
      int expectedPermits,
      int expectedAttempts,
      List<String> failures) throws SQLException {
    if (countRules(simulator) != expectedRules) {
      failures.add("Phase " + phaseName + " mutated target simulator state unexpectedly.");
    }
    if (queryCount(dataSource, "SELECT count(*) FROM tp_consumed_execution_permits WHERE tenant_id = 'simulated-alpha'") != expectedPermits) {
      failures.add("Phase " + phaseName + " mutated consumed execution permits unexpectedly.");
    }
    if (queryCount(dataSource, "SELECT count(*) FROM tp_execution_attempts WHERE tenant_id = 'simulated-alpha'") != expectedAttempts) {
      failures.add("Phase " + phaseName + " mutated execution attempts unexpectedly.");
    }
  }

  // --- Protocol Operations (Safe Diagnostics, No Raw Canary Leakage) ---

  static void performMcpHandshake(int port, String token, HttpClient client) throws Exception {
    String initBody = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
         "capabilities":{},"clientInfo":{"name":"latency-qualification-harness","version":"1.0.0"}}}
        """;
    HttpRequest initReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(initBody))
        .build();
    HttpResponse<String> initResp = client.send(initReq, HttpResponse.BodyHandlers.ofString());
    if (initResp.statusCode() != 200) {
      throw new IllegalStateException("MCP_INIT_HTTP_STATUS_" + initResp.statusCode());
    }

    String notifBody = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    HttpRequest notifReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(notifBody))
        .build();
    HttpResponse<String> notifResp = client.send(notifReq, HttpResponse.BodyHandlers.ofString());
    if (notifResp.statusCode() != 202) {
      throw new IllegalStateException("MCP_NOTIF_HTTP_STATUS_" + notifResp.statusCode());
    }
  }

  static void executeMcpRequest(int port, String token, ApprovalItem item, HttpClient client) throws Exception {
    String payload = String.format("""
        {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"tracepermit_request_simulated_change","arguments":{"groupId":"sg-demo","port":%d,"cidr":"192.0.2.0/24"}}}
        """, item.port, item.port);

    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(15))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("MCP_REQUEST_HTTP_STATUS_" + response.statusCode());
    }

    JsonNode json;
    try {
      json = JsonSupport.MAPPER.readTree(response.body());
    } catch (Exception e) {
      throw new IllegalStateException("JSON_PARSE_ERROR");
    }

    if (json.path("result").path("isError").asBoolean(true)) {
      String code = json.path("result").path("structuredContent").path("errorCode").asText("TOOL_ERROR");
      throw new IllegalStateException("TOOL_ERROR_" + sanitizeCode(code));
    }

    JsonNode content = json.path("result").path("structuredContent");
    if (!"simulated".equals(content.path("mode").asText())) {
      throw new IllegalStateException("UNEXPECTED_SIMULATOR_MODE");
    }
    if (!"pending".equals(content.path("status").asText())) {
      throw new IllegalStateException("UNEXPECTED_STATUS_" + sanitizeCode(content.path("status").asText()));
    }
    if (!"not_executed".equals(content.path("executionStatus").asText())) {
      throw new IllegalStateException("UNEXPECTED_EXECUTION_STATUS_" + sanitizeCode(content.path("executionStatus").asText()));
    }

    item.approvalRequestId = content.path("approvalRequestId").asText();
    item.requestHash = content.path("requestHash").asText();

    if (item.approvalRequestId.isBlank() || item.requestHash.isBlank()) {
      throw new IllegalStateException("MISSING_REQUEST_ID_OR_HASH");
    }
  }

  static void executeMcpStatus(int port, String token, ApprovalItem item, HttpClient client) throws Exception {
    if (item.approvalRequestId == null || item.approvalRequestId.isBlank()) {
      throw new IllegalStateException("CANNOT_QUERY_STATUS_MISSING_ID");
    }
    String payload = String.format("""
        {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"tracepermit_get_approval","arguments":{"approvalRequestId":"%s"}}}
        """, item.port, item.approvalRequestId);

    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(15))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("MCP_STATUS_HTTP_STATUS_" + response.statusCode());
    }

    JsonNode json;
    try {
      json = JsonSupport.MAPPER.readTree(response.body());
    } catch (Exception e) {
      throw new IllegalStateException("JSON_PARSE_ERROR");
    }

    if (json.path("result").path("isError").asBoolean(true)) {
      String code = json.path("result").path("structuredContent").path("errorCode").asText("TOOL_ERROR");
      throw new IllegalStateException("TOOL_ERROR_" + sanitizeCode(code));
    }

    JsonNode content = json.path("result").path("structuredContent");
    if (!"simulated".equals(content.path("mode").asText())) {
      throw new IllegalStateException("UNEXPECTED_SIMULATOR_MODE");
    }
    if (!"pending".equals(content.path("status").asText())) {
      throw new IllegalStateException("UNEXPECTED_STATUS_" + sanitizeCode(content.path("status").asText()));
    }
    if (!item.approvalRequestId.equals(content.path("approvalRequestId").asText())) {
      throw new IllegalStateException("APPROVAL_REQUEST_ID_MISMATCH");
    }
    if (!item.requestHash.equals(content.path("requestHash").asText())) {
      throw new IllegalStateException("REQUEST_HASH_MISMATCH");
    }
  }

  static void executeHttpReview(int port, String reviewerToken, ApprovalItem item, HttpClient client) throws Exception {
    if (item.approvalRequestId == null || item.approvalRequestId.isBlank() || item.requestHash == null) {
      throw new IllegalStateException("CANNOT_REVIEW_MISSING_ID_OR_HASH");
    }
    String payload = String.format("""
        {"requestHash":"%s","decision":"approve"}
        """, item.requestHash);

    HttpRequest request = HttpRequest.newBuilder(URI.create(
        "http://127.0.0.1:" + port + "/api/v1/approvals/" + item.approvalRequestId + "/decision"))
        .header("Authorization", "Bearer " + reviewerToken)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(15))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("HTTP_REVIEW_STATUS_" + response.statusCode());
    }

    JsonNode json;
    try {
      json = JsonSupport.MAPPER.readTree(response.body());
    } catch (Exception e) {
      throw new IllegalStateException("JSON_PARSE_ERROR");
    }

    if (!"approved".equals(json.path("status").asText())) {
      throw new IllegalStateException("UNEXPECTED_STATUS_" + sanitizeCode(json.path("status").asText()));
    }
  }

  static void executeMcpResume(int port, String token, ApprovalItem item, HttpClient client) throws Exception {
    if (item.approvalRequestId == null || item.approvalRequestId.isBlank() || item.requestHash == null) {
      throw new IllegalStateException("CANNOT_RESUME_MISSING_ID_OR_HASH");
    }
    String payload = String.format("""
        {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"tracepermit_resume_approval","arguments":{"approvalRequestId":"%s","requestHash":"%s"}}}
        """, item.port, item.approvalRequestId, item.requestHash);

    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(15))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("MCP_RESUME_HTTP_STATUS_" + response.statusCode());
    }

    JsonNode json;
    try {
      json = JsonSupport.MAPPER.readTree(response.body());
    } catch (Exception e) {
      throw new IllegalStateException("JSON_PARSE_ERROR");
    }

    if (json.path("result").path("isError").asBoolean(true)) {
      String code = json.path("result").path("structuredContent").path("errorCode").asText("TOOL_ERROR");
      throw new IllegalStateException("TOOL_ERROR_" + sanitizeCode(code));
    }

    JsonNode content = json.path("result").path("structuredContent");
    if (!"simulated".equals(content.path("mode").asText())) {
      throw new IllegalStateException("UNEXPECTED_SIMULATOR_MODE");
    }
    if (!"consumed".equals(content.path("status").asText())) {
      throw new IllegalStateException("UNEXPECTED_STATUS_" + sanitizeCode(content.path("status").asText()));
    }
    if (!"succeeded".equals(content.path("executionStatus").asText())) {
      throw new IllegalStateException("UNEXPECTED_EXECUTION_STATUS_" + sanitizeCode(content.path("executionStatus").asText()));
    }
    if (!item.approvalRequestId.equals(content.path("approvalRequestId").asText())) {
      throw new IllegalStateException("APPROVAL_REQUEST_ID_MISMATCH");
    }
    if (!item.requestHash.equals(content.path("requestHash").asText())) {
      throw new IllegalStateException("REQUEST_HASH_MISMATCH");
    }
  }

  static boolean executeUnauthenticatedCheck(int port, HttpClient client) throws Exception {
    String payload = """
        {"jsonrpc":"2.0","id":99999,"method":"tools/call","params":{"name":"tracepermit_request_simulated_change","arguments":{"groupId":"sg-demo","port":8080,"cidr":"192.0.2.0/24"}}}
        """;
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    return response.statusCode() == 401;
  }

  static boolean executeReplayCheck(int port, String token, ApprovalItem item, HttpClient client) throws Exception {
    String payload = String.format("""
        {"jsonrpc":"2.0","id":99998,"method":"tools/call","params":{"name":"tracepermit_resume_approval","arguments":{"approvalRequestId":"%s","requestHash":"%s"}}}
        """, item.approvalRequestId, item.requestHash);

    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) return false;

    JsonNode json;
    try {
      json = JsonSupport.MAPPER.readTree(response.body());
    } catch (Exception e) {
      return false;
    }

    if (!json.path("result").path("isError").asBoolean(false)) return false;

    String code = json.path("result").path("structuredContent").path("errorCode").asText();
    return "APPROVAL_NOT_EXECUTABLE".equals(code);
  }

  private static int countRules(StatefulTargetSimulator simulator) {
    JsonNode rules = simulator.snapshot().path("aws").path("securityGroups").path("sg-demo");
    return rules.isMissingNode() ? 0 : rules.size();
  }

  private static int queryCount(PGSimpleDataSource ds, String sql) throws SQLException {
    try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
      stmt.setQueryTimeout(10);
      try (ResultSet rs = stmt.executeQuery(sql)) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  private static String mintToken(String subject, String scopes, KeyPair rsa, Clock clock) throws Exception {
    ObjectNode header = JsonSupport.MAPPER.createObjectNode()
        .put("alg", "RS256")
        .put("typ", "at+jwt")
        .put("kid", "fixture-key");

    long now = clock.instant().getEpochSecond();
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode()
        .put("iss", ISSUER)
        .put("aud", AUDIENCE)
        .put("sub", subject)
        .put("client_id", CLIENT_ID)
        .put("org_id", "alpha")
        .put("scope", scopes)
        .put("iat", now - 10)
        .put("exp", now + 290)
        .put("jti", UUID.randomUUID().toString());

    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String input = encoder.encodeToString(JsonSupport.MAPPER.writeValueAsBytes(header)) + "."
        + encoder.encodeToString(JsonSupport.MAPPER.writeValueAsBytes(claims));

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(rsa.getPrivate());
    signer.update(input.getBytes(StandardCharsets.UTF_8));
    return input + "." + encoder.encodeToString(signer.sign());
  }

  static String sanitizeCode(String input) {
    if (input == null || input.isBlank()) return "UNKNOWN";
    return input.replaceAll("[^A-Za-z0-9_]", "_");
  }

  static String classifySafeErrorCode(Throwable t) {
    if (t instanceof HttpTimeoutException || t instanceof TimeoutException) {
      return "TIMEOUT";
    }
    String msg = t.getMessage();
    if (msg != null && (msg.startsWith("MCP_") || msg.startsWith("HTTP_") || msg.startsWith("TOOL_ERROR_")
        || msg.startsWith("UNEXPECTED_") || msg.startsWith("JSON_") || msg.startsWith("MISSING_"))) {
      return msg;
    }
    return "OPERATION_FAILED_" + t.getClass().getSimpleName();
  }

  static Map<String, String> getGitProvenance(Path root) {
    String sha = "unknown";
    String dirty = "unknown";

    try {
      Process pSha = new ProcessBuilder("git", "rev-parse", "HEAD")
          .directory(root.toFile())
          .redirectErrorStream(true)
          .start();
      if (pSha.waitFor(3, TimeUnit.SECONDS) && pSha.exitValue() == 0) {
        try (InputStream is = pSha.getInputStream()) {
          byte[] buf = is.readNBytes(128);
          String out = new String(buf, StandardCharsets.UTF_8).trim();
          if (!out.isBlank()) sha = out;
        }
      } else {
        pSha.destroyForcibly();
      }
    } catch (Exception ignored) {}

    try {
      Process pDirty = new ProcessBuilder("git", "status", "--porcelain")
          .directory(root.toFile())
          .redirectErrorStream(true)
          .start();
      if (pDirty.waitFor(3, TimeUnit.SECONDS) && pDirty.exitValue() == 0) {
        try (InputStream is = pDirty.getInputStream()) {
          byte[] buf = is.readNBytes(4096);
          String out = new String(buf, StandardCharsets.UTF_8).trim();
          dirty = String.valueOf(!out.isEmpty());
        }
      } else {
        pDirty.destroyForcibly();
      }
    } catch (Exception ignored) {}

    return Map.of("commitSha", sha, "dirty", dirty);
  }
}
