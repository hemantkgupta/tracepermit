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
import java.net.ProtocolException;
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
 *
 * <p>Enforces explicit byte bounds on request/response/stderr lines, monotonic budget accounting across
 * all execution phases, immediate process ownership upon fork, and conservative response validation.
 */
public final class OperationReviewChildJvm {
  private static final String JVM_ID = UUID.randomUUID().toString();
  private static final BusinessOperationTransitionPlanner PLANNER = new BusinessOperationTransitionPlanner();

  public static final BusinessOperationScope SCOPE = new BusinessOperationScope(
      "synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  public static final String GROUP = "synthetic-reviewers";
  public static final String HASH = "sha256:" + "a".repeat(64);

  /**
   * Maximum allowed line size for request and response JSON streams.
   * Valid canonical business operation vectors are ~2-4 KB; 64 KB provides generous headroom
   * while conservatively rejecting runaway, malicious, or unbounded payloads.
   */
  public static final int MAX_LINE_BYTES = 65_536;

  /** Maximum byte bound per retained stderr line to prevent buffer overflow. */
  public static final int MAX_STDERR_LINE_BYTES = 1024;

  /** Maximum number of stderr lines retained for diagnostics. */
  public static final int MAX_STDERR_LINES = 100;

  /** Maximum aggregate stderr retention bytes across all lines. */
  public static final int MAX_STDERR_TOTAL_BYTES = 32_768;

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
      boolean simulateCrash,
      String testMode
  ) {
    public static Builder builder(String command, String jdbcUrl, String username, String password) {
      return new Builder(command, jdbcUrl, username, password);
    }

    @Override
    public String toString() {
      return "ChildRequest[command=" + command
          + ", operationId=" + operationId
          + ", policyOutcome=" + policyOutcome
          + ", decision=" + decision
          + ", generation=" + recoveryGeneration
          + ", barrier=" + barrier
          + ", simulateCrash=" + simulateCrash
          + ", testMode=" + testMode
          + ", credentials=[REDACTED]]";
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
      private String testMode = "NORMAL";

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
      public Builder testMode(String mode) { this.testMode = mode; return this; }

      public ChildRequest build() {
        return new ChildRequest(
            command, jdbcUrl, username, password, operationId, operationJson,
            policyOutcome, lifetimeMillis, recoveryGeneration, decision,
            evaluatorExpiryIso, reviewExpiryIso, membershipExpiryIso, barrier, simulateCrash, testMode
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

    @Override
    public String toString() {
      return "ChildResult[acknowledged=" + acknowledged
          + ", exitCode=" + exitCode
          + ", jvmId=" + (jvmId != null ? (jvmId.length() > 8 ? jvmId.substring(0, 8) + "..." : jvmId) : "null")
          + ", status=" + status
          + ", code=" + code
          + ", resultStatus=" + resultStatus
          + ", snapshotHash=" + (snapshotHash != null ? (snapshotHash.length() > 16 ? snapshotHash.substring(0, 16) + "..." : snapshotHash) : "null")
          + ", stderrCount=" + (stderrLines != null ? stderrLines.size() : 0) + "]";
    }
  }

  /**
   * Reads a single line from an InputStream up to a strict maximum byte limit.
   * Strips trailing CRLF or LF. Throws ProtocolException if maxBytes is exceeded before a newline.
   * Returns null on EOF before any bytes are read.
   */
  public static String readBoundedLine(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 1024));
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        byte[] bytes = buffer.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
          len--;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
      }
      buffer.write(b);
      if (buffer.size() > maxBytes) {
        throw new ProtocolException("Stream line length exceeded maximum limit of " + maxBytes + " bytes");
      }
    }
    if (buffer.size() == 0) {
      return null;
    }
    byte[] bytes = buffer.toByteArray();
    int len = bytes.length;
    if (len > 0 && bytes[len - 1] == '\r') {
      len--;
    }
    return new String(bytes, 0, len, StandardCharsets.UTF_8);
  }

  public static void main(String[] args) {
    try {
      String requestLine = readBoundedLine(System.in, MAX_LINE_BYTES);
      if (requestLine == null || requestLine.isBlank()) {
        System.err.println("CHILD_ERROR: missing request payload on stdin");
        System.exit(2);
        return;
      }

      JsonNode req = JsonSupport.MAPPER.readTree(requestLine);
      String testMode = req.path("testMode").asText("NORMAL");
      if ("SILENT".equals(testMode)) {
        System.exit(0);
        return;
      }
      if ("EARLY_EXIT".equals(testMode)) {
        System.err.println("CHILD_SIMULATED_EARLY_EXIT");
        System.exit(42);
        return;
      }
      if ("MALFORMED_READY".equals(testMode)) {
        System.out.println("NOT_READY_MALFORMED");
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("STDERR_PRESSURE".equals(testMode)) {
        for (int i = 0; i < 500; i++) {
          System.err.println("STDERR_PRESSURE_LINE_" + i);
        }
      }
      if ("OVERSIZED_RESPONSE".equals(testMode)) {
        System.out.print("X".repeat(100_000));
        System.out.println();
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("NO_NEWLINE".equals(testMode)) {
        System.out.print("{\"status\":\"OK\"}");
        System.out.flush();
        System.exit(0);
        return;
      }

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
        System.out.println("READY");
        System.out.flush();
        String goSignal = readBoundedLine(System.in, 1024);
        if (!"GO".equalsIgnoreCase(goSignal)) {
          System.err.println("CHILD_ERROR: expected GO signal");
          System.exit(3);
          return;
        }
      }

      executeCommand(command, req, dataSource, profile, target, testMode);
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
      GitHubBusinessOperationBinding.ExpectedProfile target,
      String testMode
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

          if ("POST_COMMIT_BARRIER".equals(testMode)) {
            System.out.println("COMMITTED");
            System.out.flush();
            String releaseSignal = readBoundedLine(System.in, 1024);
            if (!"RELEASE".equals(releaseSignal)) {
              System.err.println("CHILD_BARRIER_ABORTED");
              System.exit(14);
              return;
            }
          }
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

          if ("POST_COMMIT_BARRIER".equals(testMode)) {
            System.out.println("COMMITTED");
            System.out.flush();
            String releaseSignal = readBoundedLine(System.in, 1024);
            if (!"RELEASE".equals(releaseSignal)) {
              System.err.println("CHILD_BARRIER_ABORTED");
              System.exit(14);
              return;
            }
          }
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
        default -> throw new IllegalArgumentException("Unknown command");
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
    try (ContenderHandle handle = startContender(request)) {
      return handle.awaitResult(timeout);
    }
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
    ContenderHandle handle = null;

    try {
      // Spawn concurrent bounded stderr drainer
      List<String> stderrLines = new CopyOnWriteArrayList<>();
      Thread stderrThread = new Thread(() -> {
        try {
          InputStream err = process.getErrorStream();
          int totalBytes = 0;
          String line;
          while ((line = readBoundedLine(err, MAX_STDERR_LINE_BYTES)) != null) {
            if (stderrLines.size() < MAX_STDERR_LINES && totalBytes < MAX_STDERR_TOTAL_BYTES) {
              stderrLines.add(line);
              totalBytes += line.length();
            }
          }
        } catch (IOException ignored) {}
      }, "child-stderr-drainer");
      stderrThread.setDaemon(true);
      stderrThread.start();

      // Open stdin writer and stdout stream
      BufferedWriter stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      InputStream stdoutStream = process.getInputStream();

      // Send request payload as a single bounded line
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
      reqNode.put("testMode", request.testMode());

      String serializedReq = CanonicalJson.canonicalize(reqNode);
      if (serializedReq.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
        throw new ProtocolException("Serialized ChildRequest exceeds MAX_LINE_BYTES bound");
      }
      stdinWriter.write(serializedReq);
      stdinWriter.newLine();
      stdinWriter.flush();

      handle = new ContenderHandle(process, stdinWriter, stdoutStream, stderrLines, stderrThread);
      if (request.barrier()) {
        handle.awaitReady(Duration.ofSeconds(15));
      }
      return handle;
    } catch (Throwable t) {
      if (handle != null) {
        handle.close();
      } else {
        process.destroyForcibly();
        try {
          process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
      throw t;
    }
  }

  public static final class ContenderHandle implements AutoCloseable {
    private final Process process;
    private final BufferedWriter stdinWriter;
    private final InputStream stdoutStream;
    private final List<String> stderrLines;
    private final Thread stderrThread;

    ContenderHandle(Process process, BufferedWriter stdinWriter, InputStream stdoutStream,
                    List<String> stderrLines, Thread stderrThread) {
      this.process = process;
      this.stdinWriter = stdinWriter;
      this.stdoutStream = stdoutStream;
      this.stderrLines = stderrLines;
      this.stderrThread = stderrThread;
    }

    public void awaitReady(Duration timeout) throws Exception {
      long startNanos = System.nanoTime();
      long budgetNanos = timeout.toNanos();
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<String> future = executor.submit(() -> readBoundedLine(stdoutStream, MAX_LINE_BYTES));
        String line = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!"READY".equals(line)) {
          throw new IllegalStateException("Child failed readiness handshake, got: "
              + (line != null ? (line.length() > 32 ? line.substring(0, 32) + "..." : line) : "null"));
        }
      } catch (TimeoutException te) {
        close();
        throw new TimeoutException("Child timed out waiting for READY signal within monotonic budget");
      } catch (Throwable t) {
        close();
        throw t;
      } finally {
        executor.shutdownNow();
        try {
          executor.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    }

    public void signalGo() throws Exception {
      stdinWriter.write("GO\n");
      stdinWriter.flush();
    }

    public void awaitCommitted(Duration timeout) throws Exception {
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<String> future = executor.submit(() -> readBoundedLine(stdoutStream, MAX_LINE_BYTES));
        String line = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!"COMMITTED".equals(line)) {
          throw new IllegalStateException("Child failed post-commit handshake, got: "
              + (line != null ? (line.length() > 32 ? line.substring(0, 32) + "..." : line) : "null"));
        }
      } catch (TimeoutException te) {
        close();
        throw new TimeoutException("Child timed out waiting for COMMITTED signal within monotonic budget");
      } catch (Throwable t) {
        close();
        throw t;
      } finally {
        executor.shutdownNow();
        try {
          executor.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    }

    public void signalRelease() throws Exception {
      stdinWriter.write("RELEASE\n");
      stdinWriter.flush();
    }

    public ChildResult awaitResult(Duration timeout) throws Exception {
      long startNanos = System.nanoTime();
      long totalBudgetNanos = timeout.toNanos();
      ExecutorService executor = Executors.newSingleThreadExecutor();

      try {
        Future<String> lineFuture = executor.submit(() -> readBoundedLine(stdoutStream, MAX_LINE_BYTES));
        String responseLine = null;
        try {
          long remainingNanos = totalBudgetNanos - (System.nanoTime() - startNanos);
          if (remainingNanos <= 0) {
            close();
            throw new TimeoutException("Child timed out before reading response line");
          }
          responseLine = lineFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException te) {
          close();
          throw new TimeoutException("Child timed out waiting for response line within monotonic budget");
        } catch (ExecutionException ee) {
          // Stream closed or IO error
        }

        long remainingNanos = totalBudgetNanos - (System.nanoTime() - startNanos);
        long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        boolean exited = process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
        if (!exited) {
          process.destroyForcibly();
          process.waitFor(2, TimeUnit.SECONDS);
        }
        int exitCode = process.exitValue();

        remainingNanos = totalBudgetNanos - (System.nanoTime() - startNanos);
        long stderrWait = Math.min(500, Math.max(10, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        stderrThread.join(stderrWait);

        if (responseLine == null || responseLine.isBlank() || exitCode != 0) {
          return new ChildResult(false, exitCode, null, "UNACKNOWLEDGED", null, null, null, null, stderrLines);
        }

        JsonNode resNode;
        try {
          resNode = JsonSupport.MAPPER.readTree(responseLine);
        } catch (Exception parseError) {
          return new ChildResult(false, exitCode, null, "UNACKNOWLEDGED", null, null, null, null, stderrLines);
        }

        if (!resNode.isObject()) {
          return new ChildResult(false, exitCode, null, "UNACKNOWLEDGED", null, null, null, null, stderrLines);
        }

        String jvmId = resNode.path("jvmId").asText(null);
        if (jvmId == null || !jvmId.matches("^[0-9a-fA-F\\-]{36}$")) {
          return new ChildResult(false, exitCode, null, "UNACKNOWLEDGED", null, null, null, null, stderrLines);
        }

        String status = resNode.path("status").asText(null);
        if ("OK".equals(status)) {
          if (!resNode.hasNonNull("snapshot") || !resNode.path("snapshot").isObject()
              || !resNode.hasNonNull("resultStatus")
              || !resNode.hasNonNull("snapshotHash")) {
            return new ChildResult(false, exitCode, jvmId, "UNACKNOWLEDGED", null, null, null, null, stderrLines);
          }
          String resultStatus = resNode.path("resultStatus").asText();
          String snapshotHash = resNode.path("snapshotHash").asText();
          JsonNode snapshot = resNode.path("snapshot");
          return new ChildResult(true, exitCode, jvmId, "OK", null, resultStatus, snapshotHash, snapshot, stderrLines);
        } else if ("REFUSED".equals(status)) {
          String code = resNode.path("code").asText(null);
          if (code == null || Arrays.stream(BusinessOperationStorageException.Code.values()).noneMatch(c -> c.name().equals(code))) {
            return new ChildResult(false, exitCode, jvmId, "UNACKNOWLEDGED", null, null, null, null, stderrLines);
          }
          return new ChildResult(true, exitCode, jvmId, "REFUSED", code, null, null, null, stderrLines);
        } else {
          return new ChildResult(false, exitCode, jvmId, "UNACKNOWLEDGED", null, null, null, null, stderrLines);
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ie;
      } finally {
        executor.shutdownNow();
        try {
          executor.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
        close();
      }
    }

    @Override
    public void close() {
      try { stdinWriter.close(); } catch (Throwable ignored) {}
      try { stdoutStream.close(); } catch (Throwable ignored) {}
      if (process.isAlive()) {
        process.destroyForcibly();
        try {
          process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
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
