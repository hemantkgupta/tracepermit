package io.tracepermit.persistence.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.*;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Child JVM entry point and process supervision helper for cross-process durable review qualification.
 *
 * <p>Receives database credentials and trusted test configuration strictly via private parent-child
 * stdin pipes (zero argv or environment exposure). Emits fixed statuses, synthetic IDs, counts, and
 * approved hashes to stdout. Drains stdout and stderr concurrently to prevent pipe deadlocks.
 */
public final class OperationReviewChildJvm {
  private static final String JVM_ID = UUID.randomUUID().toString();
  private static final BusinessOperationTransitionPlanner PLANNER = new BusinessOperationTransitionPlanner();

  public static final BusinessOperationScope SCOPE = new BusinessOperationScope(
      "synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  public static final String GROUP = "synthetic-reviewers";
  public static final String HASH = "sha256:" + "a".repeat(64);

  public record ChildRequest(
      String command,
      String jdbcUrl,
      String username,
      String password,
      String operationId,
      String operationJson,
      String policyOutcome,
      long lifetimeMillis,
      long recoveryGeneration,
      String decision,
      String evaluatorExpiryIso,
      String reviewExpiryIso,
      String membershipExpiryIso,
      boolean barrier,
      boolean simulateCrash
  ) {
    public static Builder builder(String command, String jdbcUrl, String username, String password) {
      return new Builder(command, jdbcUrl, username, password);
    }

    public static class Builder {
      private final String command;
      private final String jdbcUrl;
      private final String username;
      private final String password;
      private String operationId;
      private String operationJson;
      private String policyOutcome = "require_approval";
      private long lifetimeMillis = 60_000L;
      private long recoveryGeneration = 1L;
      private String decision = "APPROVE";
      private String evaluatorExpiryIso;
      private String reviewExpiryIso;
      private String membershipExpiryIso;
      private boolean barrier = false;
      private boolean simulateCrash = false;

      Builder(String command, String jdbcUrl, String username, String password) {
        this.command = command;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
      }

      public Builder operationId(String id) { this.operationId = id; return this; }
      public Builder operationJson(String json) { this.operationJson = json; return this; }
      public Builder policyOutcome(String outcome) { this.policyOutcome = outcome; return this; }
      public Builder lifetimeMillis(long millis) { this.lifetimeMillis = millis; return this; }
      public Builder recoveryGeneration(long gen) { this.recoveryGeneration = gen; return this; }
      public Builder decision(String decision) { this.decision = decision; return this; }
      public Builder evaluatorExpiryIso(String iso) { this.evaluatorExpiryIso = iso; return this; }
      public Builder reviewExpiryIso(String iso) { this.reviewExpiryIso = iso; return this; }
      public Builder membershipExpiryIso(String iso) { this.membershipExpiryIso = iso; return this; }
      public Builder barrier(boolean b) { this.barrier = b; return this; }
      public Builder simulateCrash(boolean crash) { this.simulateCrash = crash; return this; }

      public ChildRequest build() {
        return new ChildRequest(
            command, jdbcUrl, username, password, operationId, operationJson,
            policyOutcome, lifetimeMillis, recoveryGeneration, decision,
            evaluatorExpiryIso, reviewExpiryIso, membershipExpiryIso, barrier, simulateCrash
        );
      }
    }
  }

  public record ChildResult(
      boolean acknowledged,
      int exitCode,
      String jvmId,
      String status,
      String code,
      String resultStatus,
      String snapshotHash,
      JsonNode snapshot,
      List<String> stderrLines
  ) {
    public boolean isOk() { return acknowledged && "OK".equals(status); }
    public boolean isRefused(BusinessOperationStorageException.Code expectedCode) {
      return acknowledged && "REFUSED".equals(status) && expectedCode.name().equals(code);
    }
  }

  public static void main(String[] args) {
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      String requestLine = reader.readLine();
      if (requestLine == null || requestLine.isBlank()) {
        System.err.println("CHILD_ERROR: missing request payload on stdin");
        System.exit(2);
        return;
      }

      JsonNode req = JsonSupport.MAPPER.readTree(requestLine);
      String command = req.path("command").asText();
      boolean simulateCrash = req.path("simulateCrash").asBoolean(false);
      if (simulateCrash) {
        System.err.println("CHILD_SIMULATED_CRASH");
        System.exit(13);
        return;
      }

      String jdbcUrl = req.path("jdbcUrl").asText();
      String username = req.path("username").asText();
      String password = req.path("password").asText();
      String policyOutcome = req.path("policyOutcome").asText("require_approval");
      long lifetimeMillis = req.path("lifetimeMillis").asLong(60_000L);
      long recoveryGeneration = req.path("recoveryGeneration").asLong(1L);
      boolean barrier = req.path("barrier").asBoolean(false);

      DataSource dataSource = createDataSource(jdbcUrl, username, password);

      // Construct target and profile
      ObjectNode policy = createPolicy(policyOutcome);
      var target = createExpectedProfile(policy);
      BusinessOperationAuthorityProfile profile = null;
      if (req.hasNonNull("operationJson")) {
        JsonNode opNode = JsonSupport.MAPPER.readTree(req.path("operationJson").asText());
        profile = new BusinessOperationAuthorityProfile(
            SCOPE, target.adapterId(), recoveryGeneration, policy,
            CanonicalJson.sha256(opNode.path("artifactDigests")),
            opNode.path("resourceBindingHash").asText(),
            new BusinessOperationReviewSettings(GROUP, HASH, Duration.ofMillis(lifetimeMillis))
        );
      }

      if (barrier) {
        // Signal ready and wait for barrier release
        System.out.println("READY");
        System.out.flush();
        String goSignal = reader.readLine();
        if (!"GO".equalsIgnoreCase(goSignal)) {
          System.err.println("CHILD_ERROR: expected GO signal but got: " + goSignal);
          System.exit(3);
          return;
        }
      }

      executeCommand(command, req, dataSource, profile, target);
      System.exit(0);
    } catch (Throwable t) {
      System.err.println("CHILD_UNCAUGHT_EXCEPTION: " + t.getClass().getSimpleName());
      System.exit(1);
    }
  }

  private static void executeCommand(
      String command,
      JsonNode req,
      DataSource dataSource,
      BusinessOperationAuthorityProfile profile,
      GitHubBusinessOperationBinding.ExpectedProfile target
  ) throws Exception {
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("jvmId", JVM_ID);

    try {
      switch (command) {
        case "EVALUATE" -> {
          JsonNode before = JsonSupport.MAPPER.readTree(req.path("operationJson").asText());
          Instant expiry = req.hasNonNull("evaluatorExpiryIso")
              ? Instant.parse(req.path("evaluatorExpiryIso").asText())
              : Instant.now().plusSeconds(120);
          var evalContext = new TrustedOperationEvaluatorContext(
              new TrustedRequesterContext(
                  new TenantPartition(SCOPE.tenantId()),
                  SCOPE.requesterPrincipalId(),
                  true,
                  Set.of("operation_requester"),
                  Set.of("operations.evaluate", "operations.cancel"),
                  "synthetic-test",
                  HASH,
                  expiry
              ),
              SCOPE.workspaceId()
          );
          var evaluation = new BusinessOperationEvaluation(
              SCOPE, before.path("operationId").asText(), PLANNER.observe(SCOPE, before), evalContext);
          var repo = new JdbcGitHubOperationReviewRepository(dataSource, profile, target);
          JsonNode result = repo.evaluate(evaluation);
          emitOk(response, result);
        }
        case "REVIEW" -> {
          JsonNode before = JsonSupport.MAPPER.readTree(req.path("operationJson").asText());
          BusinessOperationReview.Decision decision = BusinessOperationReview.Decision.valueOf(
              req.path("decision").asText("APPROVE"));
          Instant revExpiry = req.hasNonNull("reviewExpiryIso")
              ? Instant.parse(req.path("reviewExpiryIso").asText())
              : Instant.now().plusSeconds(120);
          Instant memExpiry = req.hasNonNull("membershipExpiryIso")
              ? Instant.parse(req.path("membershipExpiryIso").asText())
              : revExpiry.minusSeconds(30);

          var reviewerIdentity = new TrustedReviewerContext(
              new TenantPartition(SCOPE.tenantId()),
              "principal:reviewer",
              "human",
              true,
              Set.of("operation_reviewer"),
              Set.of("operations.decide"),
              "synthetic-test",
              HASH
          );
          var review = new BusinessOperationReview(
              SCOPE, before.path("operationId").asText(), PLANNER.observe(SCOPE, before),
              new TrustedOperationReviewerContext(reviewerIdentity, SCOPE.workspaceId(), revExpiry), decision);
          var membership = new TrustedOperationGroupMembership(
              SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", GROUP, HASH,
              Instant.now().minusSeconds(1), memExpiry);
          var repo = new JdbcGitHubOperationReviewRepository(dataSource, profile, target);
          JsonNode result = repo.review(review, membership);
          emitOk(response, result);
        }
        case "WITHDRAW" -> {
          JsonNode before = JsonSupport.MAPPER.readTree(req.path("operationJson").asText());
          Instant expiry = Instant.now().plusSeconds(120);
          var requester = new TrustedRequesterContext(
              new TenantPartition(SCOPE.tenantId()),
              SCOPE.requesterPrincipalId(),
              true,
              Set.of("operation_requester"),
              Set.of("operations.evaluate", "operations.cancel"),
              "synthetic-test",
              HASH,
              expiry
          );
          var withdrawal = new BusinessOperationWithdrawal(
              SCOPE, before.path("operationId").asText(), PLANNER.observe(SCOPE, before), requester);
          var repo = new JdbcGitHubOperationReviewRepository(dataSource, profile, target);
          JsonNode result = repo.withdraw(withdrawal);
          emitOk(response, result);
        }
        case "READ_CURRENT" -> {
          String operationId = req.path("operationId").asText();
          var lifecycleRepo = new JdbcBusinessOperationLifecycleRepository(dataSource);
          Optional<JsonNode> current = lifecycleRepo.findCurrent(SCOPE, operationId);
          if (current.isPresent()) {
            emitOk(response, current.get());
          } else {
            response.put("status", "NOT_FOUND");
            System.out.println(CanonicalJson.canonicalize(response));
            System.out.flush();
          }
        }
        case "READ_ORIGINAL" -> {
          String operationId = req.path("operationId").asText();
          var opRepo = new JdbcBusinessOperationRepository(dataSource);
          Optional<JsonNode> original = opRepo.find(SCOPE, operationId);
          if (original.isPresent()) {
            emitOk(response, original.get());
          } else {
            response.put("status", "NOT_FOUND");
            System.out.println(CanonicalJson.canonicalize(response));
            System.out.flush();
          }
        }
        default -> throw new IllegalArgumentException("Unknown command: " + command);
      }
    } catch (BusinessOperationStorageException failure) {
      response.put("status", "REFUSED");
      response.put("code", failure.code().name());
      System.out.println(CanonicalJson.canonicalize(response));
      System.out.flush();
    }
  }

  private static void emitOk(ObjectNode response, JsonNode result) {
    response.put("status", "OK");
    response.put("resultStatus", result.path("authorization").path("status").asText());
    response.put("operationId", result.path("operationId").asText());
    response.put("snapshotHash", CanonicalJson.sha256(result));
    response.set("snapshot", result);
    System.out.println(CanonicalJson.canonicalize(response));
    System.out.flush();
  }

  // --- Supervision Methods executed in the Parent Test ---

  public static ChildResult runChild(ChildRequest request, Duration timeout) throws Exception {
    ContenderHandle handle = startContender(request);
    return handle.awaitResult(timeout);
  }

  public static ContenderHandle startContender(ChildRequest request) throws Exception {
    String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("tracepermit.operationsTestClasspath");
    if (classpath == null || classpath.isBlank()) {
      classpath = System.getProperty("java.class.path");
    }

    ProcessBuilder pb = new ProcessBuilder(
        javaBinary,
        "-cp", classpath,
        OperationReviewChildJvm.class.getName()
    );
    // Explicitly do NOT put credentials or request into env or args!
    Process process = pb.start();

    // Spawn concurrent stderr drainer
    List<String> stderrLines = new CopyOnWriteArrayList<>();
    Thread stderrThread = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) {
          if (stderrLines.size() < 100) stderrLines.add(line);
        }
      } catch (IOException ignored) {}
    }, "child-stderr-drainer");
    stderrThread.setDaemon(true);
    stderrThread.start();

    // Open stdin writer and stdout reader
    BufferedWriter stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

    // Send request payload as a single line
    ObjectNode reqNode = JsonSupport.MAPPER.createObjectNode();
    reqNode.put("command", request.command());
    reqNode.put("jdbcUrl", request.jdbcUrl());
    reqNode.put("username", request.username());
    reqNode.put("password", request.password());
    if (request.operationId() != null) reqNode.put("operationId", request.operationId());
    if (request.operationJson() != null) reqNode.put("operationJson", request.operationJson());
    reqNode.put("policyOutcome", request.policyOutcome());
    reqNode.put("lifetimeMillis", request.lifetimeMillis());
    reqNode.put("recoveryGeneration", request.recoveryGeneration());
    reqNode.put("decision", request.decision());
    if (request.evaluatorExpiryIso() != null) reqNode.put("evaluatorExpiryIso", request.evaluatorExpiryIso());
    if (request.reviewExpiryIso() != null) reqNode.put("reviewExpiryIso", request.reviewExpiryIso());
    if (request.membershipExpiryIso() != null) reqNode.put("membershipExpiryIso", request.membershipExpiryIso());
    reqNode.put("barrier", request.barrier());
    reqNode.put("simulateCrash", request.simulateCrash());

    stdinWriter.write(CanonicalJson.canonicalize(reqNode));
    stdinWriter.newLine();
    stdinWriter.flush();

    ContenderHandle handle = new ContenderHandle(process, stdinWriter, stdoutReader, stderrLines, stderrThread);
    if (request.barrier()) {
      handle.awaitReady(Duration.ofSeconds(15));
    }
    return handle;
  }

  public static final class ContenderHandle implements AutoCloseable {
    private final Process process;
    private final BufferedWriter stdinWriter;
    private final BufferedReader stdoutReader;
    private final List<String> stderrLines;
    private final Thread stderrThread;

    ContenderHandle(Process process, BufferedWriter stdinWriter, BufferedReader stdoutReader,
                    List<String> stderrLines, Thread stderrThread) {
      this.process = process;
      this.stdinWriter = stdinWriter;
      this.stdoutReader = stdoutReader;
      this.stderrLines = stderrLines;
      this.stderrThread = stderrThread;
    }

    public void awaitReady(Duration timeout) throws Exception {
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<String> future = executor.submit(stdoutReader::readLine);
        String line = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!"READY".equals(line)) {
          throw new IllegalStateException("Child failed readiness handshake: " + line);
        }
      } finally {
        executor.shutdownNow();
      }
    }

    public void signalGo() throws Exception {
      stdinWriter.write("GO");
      stdinWriter.newLine();
      stdinWriter.flush();
    }

    public ChildResult awaitResult(Duration timeout) throws Exception {
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<String> lineFuture = executor.submit(stdoutReader::readLine);
        String responseLine = null;
        try {
          responseLine = lineFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
          process.destroyForcibly();
          throw new TimeoutException("Child timed out waiting for response line after " + timeout);
        } catch (ExecutionException ee) {
          // stream closed or IO error
        }

        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
        }
        int exitCode = process.exitValue();
        stderrThread.join(1000);

        if (responseLine == null || responseLine.isBlank() || exitCode != 0) {
          return new ChildResult(false, exitCode, null, "UNACKNOWLEDGED", null, null, null, null, stderrLines);
        }

        JsonNode resNode = JsonSupport.MAPPER.readTree(responseLine);
        String status = resNode.path("status").asText();
        String jvmId = resNode.path("jvmId").asText(null);
        String code = resNode.hasNonNull("code") ? resNode.path("code").asText() : null;
        String resultStatus = resNode.hasNonNull("resultStatus") ? resNode.path("resultStatus").asText() : null;
        String snapshotHash = resNode.hasNonNull("snapshotHash") ? resNode.path("snapshotHash").asText() : null;
        JsonNode snapshot = resNode.path("snapshot");

        return new ChildResult(true, exitCode, jvmId, status, code, resultStatus, snapshotHash, snapshot, stderrLines);
      } finally {
        executor.shutdownNow();
        close();
      }
    }

    @Override
    public void close() {
      try { stdinWriter.close(); } catch (Exception ignored) {}
      try { stdoutReader.close(); } catch (Exception ignored) {}
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  // --- Helpers for Configuration and Profile Construction ---

  public static ObjectNode createPolicy(String outcome) throws Exception {
    ObjectNode policy = (ObjectNode) JsonSupport.MAPPER.readTree("""
        {"schemaVersion":"1.0","id":"synthetic-github-draft-pr","version":1,"name":"Synthetic",
         "description":"No target authority","enabled":true,"defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
         "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic",
         "match":{"toolNames":["github.pull_request.create"]},
         "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW","approvalGroup":"synthetic-reviewers"}}]}
        """);
    ((ObjectNode) policy.path("rules").get(0).path("decision")).put("outcome", outcome);
    return policy;
  }

  public static GitHubBusinessOperationBinding.ExpectedProfile createExpectedProfile(ObjectNode policy) {
    return new GitHubBusinessOperationBinding.ExpectedProfile(
        "github-draft-pr-v1", "test", 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", 1, CanonicalJson.sha256(policy)),
        Map.of("policy_bundle", HASH, "adapter_release", HASH),
        new GitHubPilotTargetBinding(71, 101, 201, "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40))
    );
  }

  public static ObjectNode createInitialOperation(GitHubBusinessOperationBinding.ExpectedProfile expected, Path repoRoot) {
    ObjectNode value = (ObjectNode) JsonSupport.read(repoRoot.resolve("contracts/vectors/business-operation-v1.json"))
        .path("operation").deepCopy();
    value.put("operationId", UUID.randomUUID().toString());
    ((ArrayNode) value.path("transitions")).remove(3);
    ((ObjectNode) value.path("authorization")).put("status", "evaluating").put("version", 1)
        .put("updatedAt", value.path("createdAt").asText());
    value.put("updatedAt", value.path("transitions").get(2).path("at").asText());
    ObjectNode action = (ObjectNode) value.path("canonicalAction").path("action");
    ObjectNode arguments = (ObjectNode) action.path("arguments");
    arguments.remove("owner");
    arguments.put("repository", expected.targetBinding().repository())
        .put("head", expected.targetBinding().head())
        .put("base", "main");
    ((ObjectNode) action.path("target")).put("accountId", expected.targetBinding().repository())
        .put("resourceType", "pull_request").put("environment", expected.environment());
    value.set("resourceBinding", expected.resourceBinding());
    value.putObject("policyRelease").put("policyId", expected.policyRelease().policyId())
        .put("version", 1).put("contentHash", expected.policyRelease().contentHash());
    var artifacts = value.putArray("artifactDigests");
    new TreeMap<>(expected.artifactDigests()).forEach((kind, hash) -> artifacts.addObject().put("kind", kind).put("digest", hash));
    value.put("canonicalActionHash", CanonicalJson.sha256(value.path("canonicalAction")));
    value.put("semanticPayloadDigest", CanonicalJson.sha256(action));
    value.put("resourceBindingHash", CanonicalJson.sha256(value.path("resourceBinding")));
    value.put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(
        value.path("operationId").asText(), expected.adapterId(), 1));
    return (ObjectNode) BusinessOperationValidator.validate(value);
  }

  private static DataSource createDataSource(String url, String username, String password) {
    return new DataSource() {
      @Override public Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        if (url.startsWith("jdbc:postgresql:")) {
          props.setProperty("connectTimeout", "5");
          props.setProperty("socketTimeout", "5");
        } else if (url.startsWith("jdbc:mariadb:")) {
          props.setProperty("connectTimeout", "5000");
          props.setProperty("socketTimeout", "5000");
        }
        return DriverManager.getConnection(url, props);
      }
      @Override public Connection getConnection(String u, String p) throws SQLException {
        throw new SQLFeatureNotSupportedException();
      }
      @Override public PrintWriter getLogWriter() { return null; }
      @Override public void setLogWriter(PrintWriter out) {}
      @Override public void setLoginTimeout(int seconds) {}
      @Override public int getLoginTimeout() { return 5; }
      @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
      }
      @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLFeatureNotSupportedException();
      }
      @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    };
  }
}
