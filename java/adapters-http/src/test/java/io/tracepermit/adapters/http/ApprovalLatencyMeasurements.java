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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    if (percentile <= 0.0 || percentile > 1.0) {
      throw new IllegalArgumentException("Percentile must be in range (0.0, 1.0].");
    }
    return Math.max(0, Math.min(size - 1, (int) Math.ceil(percentile * size) - 1));
  }

  /**
   * Computes nearest-rank percentiles, min, max, and mean in milliseconds.
   */
  public static Percentiles computePercentiles(long[] sampleNanos) {
    if (sampleNanos == null || sampleNanos.length == 0) {
      throw new IllegalArgumentException("Cannot compute percentiles of null or empty sample set.");
    }
    long[] sorted = sampleNanos.clone();
    Arrays.sort(sorted);
    int size = sorted.length;

    double p50 = nanosToMillis(sorted[nearestRankIndex(size, 0.50)]);
    double p95 = nanosToMillis(sorted[nearestRankIndex(size, 0.95)]);
    double p99 = nanosToMillis(sorted[nearestRankIndex(size, 0.99)]);
    double min = nanosToMillis(sorted[0]);
    double max = nanosToMillis(sorted[size - 1]);

    long sum = 0;
    for (long val : sorted) {
      sum += val;
    }
    double mean = nanosToMillis(sum / size);

    return new Percentiles(p50, p95, p99, min, max, mean);
  }

  /** Statistical summary for a measured latency distribution in milliseconds. */
  public record Percentiles(
      double p50Ms,
      double p95Ms,
      double p99Ms,
      double minMs,
      double maxMs,
      double meanMs) {

    public ObjectNode toJson() {
      ObjectNode node = JsonSupport.MAPPER.createObjectNode();
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
      sb.append(String.format("Environment: %s %s, %s CPUs, %s%n",
          environment.get("os"), environment.get("arch"), environment.get("availableProcessors"), environment.get("javaVersion")));
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
    if (concurrencyLevels.length != warmupCounts.length || concurrencyLevels.length != measuredCounts.length) {
      throw new IllegalArgumentException("Concurrency, warmup, and measured count arrays must have matching lengths.");
    }

    Path root = Path.of(System.getProperty("tracepermit.repositoryRoot", "."));
    String commitSha = getGitCommit(root);
    boolean dirty = isGitDirty(root);

    // Cryptographic keypairs outside measured intervals
    KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
    rsaGen.initialize(2048);
    KeyPair rsaKeyPair = rsaGen.generateKeyPair();
    KeyPair permitKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

    // Dedicated database schema
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSslMode("disable");

    String schema = "lat_qual_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE SCHEMA " + schema);
    }
    dataSource.setCurrentSchema(schema);

    // Controlled frozen authorization clock (2030-01-01) ensures synthetic tokens never expire
    // during the test, while real physical latency is measured using System.nanoTime().
    Instant frozenInstant = Instant.parse("2030-01-01T00:00:00Z");
    Clock clock = Clock.fixed(frozenInstant, ZoneOffset.UTC);

    // Memberships: separate requester (alice) and reviewer (bob)
    List<ApprovalMemberships.Entry> membershipsList = List.of(
        new ApprovalMemberships.Entry(
            ISSUER + "#alice", CLIENT_ID, TENANT, true, Set.of(ApprovalMemberships.Role.REQUESTER)),
        new ApprovalMemberships.Entry(
            ISSUER + "#bob", CLIENT_ID, TENANT, true, Set.of(ApprovalMemberships.Role.REVIEWER)));
    ApprovalMemberships memberships = new ApprovalMemberships(membershipsList);

    // Provider configuration and token verifier
    Set<ConnectorScope> requesterScopes = Set.of(
        ConnectorScope.of("approvals.read"),
        ConnectorScope.of("approvals.request"),
        ConnectorScope.of("approvals.execute"));
    Set<ConnectorScope> reviewerScopes = Set.of(
        ConnectorScope.of("approvals.read"),
        ConnectorScope.of("approvals.decide"));

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

    // Pre-mint tokens outside measurement loops
    String requesterToken = mintToken("alice", "approvals.read approvals.request approvals.execute", rsaKeyPair, clock);
    String reviewerToken = mintToken("bob", "approvals.read approvals.decide", rsaKeyPair, clock);

    // Stores and server setup
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

    SimulatorApprovalServer server = SimulatorApprovalServer.start(
        new InetSocketAddress("127.0.0.1", 0),
        verifier,
        memberships,
        enforcementState,
        policy,
        permitAuthority,
        simulator,
        clock);

    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    List<SweepMetrics> sweepResults = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    boolean overallPassed = true;
    boolean unauthRejected = false;
    boolean replayRejected = false;
    int replayRuleDelta = -1;

    AtomicInteger portGenerator = new AtomicInteger(1000);

    try {
      // 1. Initial MCP protocol handshake qualification
      performMcpHandshake(server.port(), requesterToken, client);

      int baselineRules = countRules(simulator);

      // 2. Concurrency sweeps
      for (int i = 0; i < concurrencyLevels.length; i++) {
        int concurrency = concurrencyLevels[i];
        int warmup = warmupCounts[i];
        int measured = measuredCounts[i];

        int sweepStartRules = countRules(simulator);
        long sweepStartNanos = System.nanoTime();

        Map<String, PhaseMetrics> phaseMetricsMap = new LinkedHashMap<>();

        // Generate distinct approval items
        List<ApprovalItem> warmupItems = new ArrayList<>();
        for (int w = 0; w < warmup; w++) {
          warmupItems.add(new ApprovalItem(portGenerator.incrementAndGet()));
        }
        List<ApprovalItem> measuredItems = new ArrayList<>();
        for (int m = 0; m < measured; m++) {
          measuredItems.add(new ApprovalItem(portGenerator.incrementAndGet()));
        }

        // --- Phase 1: mcp_request ---
        PhaseMetrics reqMetrics = runPhase(
            "mcp_request",
            concurrency,
            warmupItems,
            measuredItems,
            (item) -> executeMcpRequest(server.port(), requesterToken, item, client),
            failures);
        phaseMetricsMap.put("mcp_request", reqMetrics);
        if (countRules(simulator) != sweepStartRules) {
          failures.add("Phase mcp_request mutated target simulator state unexpectedly.");
        }

        // --- Phase 2: mcp_status ---
        PhaseMetrics statusMetrics = runPhase(
            "mcp_status",
            concurrency,
            warmupItems,
            measuredItems,
            (item) -> executeMcpStatus(server.port(), requesterToken, item, client),
            failures);
        phaseMetricsMap.put("mcp_status", statusMetrics);
        if (countRules(simulator) != sweepStartRules) {
          failures.add("Phase mcp_status mutated target simulator state unexpectedly.");
        }

        // --- Phase 3: http_review ---
        PhaseMetrics reviewMetrics = runPhase(
            "http_review",
            concurrency,
            warmupItems,
            measuredItems,
            (item) -> executeHttpReview(server.port(), reviewerToken, item, client),
            failures);
        phaseMetricsMap.put("http_review", reviewMetrics);
        if (countRules(simulator) != sweepStartRules) {
          failures.add("Phase http_review mutated target simulator state unexpectedly.");
        }

        // --- Phase 4: mcp_resume ---
        PhaseMetrics resumeMetrics = runPhase(
            "mcp_resume",
            concurrency,
            warmupItems,
            measuredItems,
            (item) -> executeMcpResume(server.port(), requesterToken, item, client),
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

        // Database durable attempt and permit counts
        int dbPermits = queryCount(dataSource, "SELECT count(*) FROM tp_consumed_execution_permits WHERE tenant_id = 'simulated-alpha'");
        int dbAttempts = queryCount(dataSource, "SELECT count(*) FROM tp_execution_attempts WHERE tenant_id = 'simulated-alpha'");

        sweepResults.add(new SweepMetrics(
            concurrency,
            phaseMetricsMap,
            sweepElapsedNanos,
            observedRuleDelta,
            dbPermits,
            dbAttempts));
      }

      // 3. Negative control: Unauthenticated call rejection
      int rulesBeforeUnauth = countRules(simulator);
      unauthRejected = executeUnauthenticatedCheck(server.port(), client);
      if (countRules(simulator) != rulesBeforeUnauth) {
        failures.add("Unauthenticated negative control modified target simulator state.");
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
      replayRejected = executeReplayCheck(server.port(), requesterToken, replayItem, client);
      replayRuleDelta = countRules(simulator) - rulesBeforeReplay;

      if (!replayRejected) {
        failures.add("Replay execution was not rejected with APPROVAL_NOT_EXECUTABLE.");
      }
      if (replayRuleDelta != 0) {
        failures.add("Replay execution added unexpected simulator rules: " + replayRuleDelta);
      }

      if (!failures.isEmpty()) {
        overallPassed = false;
      }

    } catch (Exception e) {
      failures.add("Execution exception: " + e.getMessage());
      overallPassed = false;
    } finally {
      server.close();
      client.close();
    }

    Map<String, String> provenance = Map.of(
        "commitSha", commitSha,
        "dirty", String.valueOf(dirty));

    Map<String, Object> environment = Map.of(
        "os", System.getProperty("os.name") + " " + System.getProperty("os.version"),
        "arch", System.getProperty("os.arch"),
        "availableProcessors", Runtime.getRuntime().availableProcessors(),
        "javaVersion", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
        "postgresVersion", "postgres:16.4-alpine (Testcontainers)");

    return new LatencyQualificationReport(
        "1.0",
        Instant.now().toString(),
        "simulator-authenticated-durable-approval",
        "static-policy simulator server, fixture-resolved RS256 token verification, PGSimpleDataSource, loopback HTTP",
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

  // --- Workflow Phase Execution Helper ---

  @FunctionalInterface
  interface ItemOperation {
    void execute(ApprovalItem item) throws Exception;
  }

  private static PhaseMetrics runPhase(
      String phaseName,
      int concurrency,
      List<ApprovalItem> warmupItems,
      List<ApprovalItem> measuredItems,
      ItemOperation operation,
      List<String> failures) {

    // 1. Warm-up operations (exercise exact path, excluded from measured statistics)
    if (!warmupItems.isEmpty()) {
      runConcurrent(concurrency, warmupItems, operation, null, null, null);
    }

    // 2. Measured steady-state operations
    int measuredCount = measuredItems.size();
    long[] sampleNanos = new long[measuredCount];
    AtomicInteger successCounter = new AtomicInteger();
    AtomicInteger failureCounter = new AtomicInteger();
    AtomicInteger timeoutCounter = new AtomicInteger();

    long wallStart = System.nanoTime();
    runConcurrent(concurrency, measuredItems, operation, sampleNanos, successCounter, failureCounter);
    long wallElapsed = System.nanoTime() - wallStart;

    int successes = successCounter.get();
    int failed = failureCounter.get();
    int timeouts = timeoutCounter.get();

    if (failed > 0 || successes != measuredCount) {
      failures.add(String.format("Phase %s (concurrency=%d) had %d failures out of %d operations.",
          phaseName, concurrency, failed, measuredCount));
    }

    Percentiles percentiles = (successes > 0 && sampleNanos.length > 0)
        ? computePercentiles(sampleNanos)
        : null;

    return new PhaseMetrics(
        phaseName,
        concurrency,
        warmupItems.size(),
        measuredCount,
        successes,
        failed,
        timeouts,
        wallElapsed,
        percentiles);
  }

  private static void runConcurrent(
      int concurrency,
      List<ApprovalItem> items,
      ItemOperation operation,
      long[] sampleNanos,
      AtomicInteger successCounter,
      AtomicInteger failureCounter) {

    int size = items.size();
    if (size == 0) return;

    ExecutorService executor = Executors.newFixedThreadPool(concurrency);
    try {
      List<Future<Void>> futures = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        final int index = i;
        final ApprovalItem item = items.get(i);
        futures.add(executor.submit(() -> {
          long start = System.nanoTime();
          try {
            operation.execute(item);
            long elapsed = System.nanoTime() - start;
            if (sampleNanos != null) {
              sampleNanos[index] = elapsed;
            }
            if (successCounter != null) {
              successCounter.incrementAndGet();
            }
          } catch (Exception e) {
            if (failureCounter != null) {
              failureCounter.incrementAndGet();
            }
            throw e;
          }
          return null;
        }));
      }

      for (Future<Void> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      if (failureCounter != null) {
        failureCounter.incrementAndGet();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  // --- Protocol Operations ---

  private static void performMcpHandshake(int port, String token, HttpClient client) throws Exception {
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
      throw new IllegalStateException("MCP initialize failed with HTTP " + initResp.statusCode());
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
      throw new IllegalStateException("MCP notifications/initialized failed with HTTP " + notifResp.statusCode());
    }
  }

  private static void executeMcpRequest(int port, String token, ApprovalItem item, HttpClient client) throws Exception {
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
      throw new IllegalStateException("MCP request returned HTTP " + response.statusCode() + ": " + response.body());
    }

    JsonNode json = JsonSupport.MAPPER.readTree(response.body());
    if (json.path("result").path("isError").asBoolean(true)) {
      throw new IllegalStateException("MCP request returned tool error: " + response.body());
    }

    JsonNode content = json.path("result").path("structuredContent");
    if (!"pending".equals(content.path("status").asText())
        || !"not_executed".equals(content.path("executionStatus").asText())) {
      throw new IllegalStateException("Unexpected approval status in request response: " + response.body());
    }

    item.approvalRequestId = content.path("approvalRequestId").asText();
    item.requestHash = content.path("requestHash").asText();

    if (item.approvalRequestId.isBlank() || item.requestHash.isBlank()) {
      throw new IllegalStateException("Missing approvalRequestId or requestHash in response.");
    }
  }

  private static void executeMcpStatus(int port, String token, ApprovalItem item, HttpClient client) throws Exception {
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
      throw new IllegalStateException("MCP status returned HTTP " + response.statusCode() + ": " + response.body());
    }

    JsonNode json = JsonSupport.MAPPER.readTree(response.body());
    if (json.path("result").path("isError").asBoolean(true)) {
      throw new IllegalStateException("MCP status returned tool error: " + response.body());
    }

    JsonNode content = json.path("result").path("structuredContent");
    if (!"pending".equals(content.path("status").asText())) {
      throw new IllegalStateException("Expected pending status, but got: " + content.path("status").asText());
    }
  }

  private static void executeHttpReview(int port, String reviewerToken, ApprovalItem item, HttpClient client) throws Exception {
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
      throw new IllegalStateException("Review decision returned HTTP " + response.statusCode() + ": " + response.body());
    }

    JsonNode json = JsonSupport.MAPPER.readTree(response.body());
    if (!"approved".equals(json.path("status").asText())) {
      throw new IllegalStateException("Expected approved status, but got: " + json.path("status").asText());
    }
  }

  private static void executeMcpResume(int port, String token, ApprovalItem item, HttpClient client) throws Exception {
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
      throw new IllegalStateException("MCP resume returned HTTP " + response.statusCode() + ": " + response.body());
    }

    JsonNode json = JsonSupport.MAPPER.readTree(response.body());
    if (json.path("result").path("isError").asBoolean(true)) {
      throw new IllegalStateException("MCP resume returned tool error: " + response.body());
    }

    JsonNode content = json.path("result").path("structuredContent");
    if (!"consumed".equals(content.path("status").asText())
        || !"succeeded".equals(content.path("executionStatus").asText())) {
      throw new IllegalStateException("Unexpected resume outcome: " + response.body());
    }
  }

  private static boolean executeUnauthenticatedCheck(int port, HttpClient client) throws Exception {
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

  private static boolean executeReplayCheck(int port, String token, ApprovalItem item, HttpClient client) throws Exception {
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

    JsonNode json = JsonSupport.MAPPER.readTree(response.body());
    if (!json.path("result").path("isError").asBoolean(false)) return false;

    String code = json.path("result").path("structuredContent").path("errorCode").asText();
    return "APPROVAL_NOT_EXECUTABLE".equals(code);
  }

  private static int countRules(StatefulTargetSimulator simulator) {
    JsonNode rules = simulator.snapshot().path("aws").path("securityGroups").path("sg-demo");
    return rules.isMissingNode() ? 0 : rules.size();
  }

  private static int queryCount(PGSimpleDataSource ds, String sql) throws SQLException {
    try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
      return rs.next() ? rs.getInt(1) : 0;
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

  private static String getGitCommit(Path root) {
    try {
      Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
          .directory(root.toFile())
          .start();
      if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      }
    } catch (Exception ignored) {}
    return "unknown";
  }

  private static boolean isGitDirty(Path root) {
    try {
      Process p = new ProcessBuilder("git", "status", "--porcelain")
          .directory(root.toFile())
          .start();
      if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return !out.isEmpty();
      }
    } catch (Exception ignored) {}
    return false;
  }
}
