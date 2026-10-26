package io.tracepermit.persistence.operations;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Child JVM entry point and process supervision helper for cross-process durable review qualification.
 *
 * <p>Receives database credentials and trusted test configuration strictly via private parent-child
 * stdin pipes (zero argv or environment exposure). Emits fixed statuses, synthetic IDs, counts, and
 * approved hashes to stdout. Drains stdout and stderr concurrently to prevent pipe deadlocks.
 *
 * <p>Enforces explicit byte bounds on request/response/stderr lines, monotonic budget accounting across
 * all execution phases, immediate process ownership upon fork, and closed, conservative response validation.
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

  /** Separate finite reserve for process destroying and reaping on teardown. */
  public static final long REAP_RESERVE_MS = 2000L;

  private static final Pattern STRICT_UUID_PATTERN = Pattern.compile(
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
  );

  private static final Set<String> ALLOWED_OK_FIELDS = Set.of(
      "jvmId", "status", "operationId", "resultStatus", "snapshotHash",
      "bindingDigest", "factId", "snapshot"
  );

  private static final Set<String> ALLOWED_REFUSED_FIELDS = Set.of(
      "jvmId", "status", "code"
  );

  private static final JsonFactory STRICT_JSON_FACTORY = JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build();
  private static final ObjectMapper LOCAL_STRICT_MAPPER = new ObjectMapper(STRICT_JSON_FACTORY);

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
      String testMode,
      boolean stallBeforeStdin,
      String canary
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
          + ", stallBeforeStdin=" + stallBeforeStdin
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
      private boolean stallBeforeStdin = false;
      private String canary;

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
      public Builder stallBeforeStdin(boolean stall) { this.stallBeforeStdin = stall; return this; }
      public Builder canary(String canary) { this.canary = canary; return this; }

      public ChildRequest build() {
        return new ChildRequest(
            command, jdbcUrl, username, password, operationId, operationJson,
            policyOutcome, lifetimeMillis, recoveryGeneration, decision,
            evaluatorExpiryIso, reviewExpiryIso, membershipExpiryIso, barrier, simulateCrash,
            testMode, stallBeforeStdin, canary
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
      List<String> diagnosticCategories,
      int stderrLineCount,
      int stderrByteCount,
      boolean stderrTruncated
  ) {
    public boolean isOk() { return acknowledged && "OK".equals(status); }
    public boolean isRefused(BusinessOperationStorageException.Code expectedCode) {
      return acknowledged && "REFUSED".equals(status) && expectedCode.name().equals(code);
    }
    public boolean hasStderrCategory(String category) {
      return diagnosticCategories != null && diagnosticCategories.contains(category);
    }
    public int stderrLines() {
      return stderrLineCount;
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
          + ", stderrCategories=" + (diagnosticCategories != null ? diagnosticCategories : "[]")
          + ", stderrLines=" + stderrLineCount
          + ", stderrBytes=" + stderrByteCount + "]";
    }
  }

  /**
   * Reads a single line from an InputStream up to a strict maximum byte limit.
   * Strips trailing CRLF or LF. Throws ProtocolException if maxBytes is exceeded before a newline,
   * or if malformed UTF-8 sequences are encountered.
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
        return decodeStrictUtf8(bytes, len);
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
    return decodeStrictUtf8(bytes, len);
  }

  public static String decodeStrictUtf8(byte[] bytes, int len) throws CharacterCodingException {
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes, 0, len));
    return chars.toString();
  }

  public static void main(String[] args) {
    if (args.length > 0 && "--stall-before-stdin".equals(args[0])) {
      try {
        Thread.sleep(60_000);
      } catch (InterruptedException ignored) {}
      System.exit(0);
      return;
    }

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
          System.err.println("STDERR_PRESSURE_LINE_" + i + " " + "x".repeat(300));
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
      if ("MALFORMED_UTF8".equals(testMode)) {
        System.out.write(new byte[]{(byte) 0xC3, (byte) 0x28, (byte) '\n'});
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("HOLD_READY".equals(testMode)) {
        System.out.println("READY");
        System.out.flush();
        readBoundedLine(System.in, 1024);
        System.exit(0);
        return;
      }
      if ("BASELINE_OK".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        System.out.println(CanonicalJson.canonicalize(okRes));
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("DUPLICATE_KEY_RESPONSE".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        String json = CanonicalJson.canonicalize(okRes);
        String mutated = json.replace("\"status\":\"OK\"", "\"status\":\"OK\",\"status\":\"OK\"");
        System.out.println(mutated);
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("TRAILING_TOKEN_RESPONSE".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        String json = CanonicalJson.canonicalize(okRes);
        System.out.println(json + " trailing_token_garbage");
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("INVALID_UUID".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        okRes.put("jvmId", "invalid-uuid-format");
        System.out.println(CanonicalJson.canonicalize(okRes));
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("MISMATCHED_HASH".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        okRes.put("snapshotHash", "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        System.out.println(CanonicalJson.canonicalize(okRes));
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("MISMATCHED_OP_ID".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        okRes.put("operationId", "00000000-0000-4000-8000-000000000000");
        System.out.println(CanonicalJson.canonicalize(okRes));
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("MISMATCHED_RESULT_STATUS".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        okRes.put("resultStatus", "denied");
        System.out.println(CanonicalJson.canonicalize(okRes));
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("MISMATCHED_FACT_ID".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        okRes.put("factId", "different-operation-id#999");
        System.out.println(CanonicalJson.canonicalize(okRes));
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("MISMATCHED_BINDING_DIGEST".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        okRes.put("bindingDigest", "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        System.out.println(CanonicalJson.canonicalize(okRes));
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("UNKNOWN_FIELDS_OK".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        okRes.put("unexpectedDisallowedField", true);
        System.out.println(CanonicalJson.canonicalize(okRes));
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("INCOMPLETE_SNAPSHOT".equals(testMode)) {
        Path root = findRepoRoot();
        ObjectNode okRes = createBaselineOkResponse(root);
        okRes.set("snapshot", JsonSupport.MAPPER.createObjectNode());
        System.out.println(CanonicalJson.canonicalize(okRes));
        System.out.flush();
        System.exit(0);
        return;
      }
      if ("STDERR_OVERLONG_LINE".equals(testMode)) {
        System.err.print("CHILD_ERROR: ");
        System.err.println("x".repeat(5000));
        System.err.flush();
        System.exit(0);
        return;
      }
      if ("STDERR_MULTIBYTE_PRESSURE".equals(testMode)) {
        for (int i = 0; i < 100; i++) {
          System.err.println("CHILD_ERROR: \u20AC\uD83D\uDE00 " + "\u20AC".repeat(150));
        }
        System.err.flush();
        System.exit(0);
        return;
      }
      if ("CANARY_DIAGNOSTIC".equals(testMode)) {
        String canary = req.path("canary").asText("synthetic-canary-secret");
        System.out.println("NOT_READY_SIGNAL_" + canary);
        System.out.flush();
        System.err.println("CHILD_ERROR: with secret " + canary);
        System.exit(1);
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
          emitOk(response, result, SCOPE, target);
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
          emitOk(response, result, SCOPE, target);
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
          emitOk(response, result, SCOPE, target);
        }
        case "READ_CURRENT" -> {
          String operationId = req.path("operationId").asText();
          var lifecycleRepo = new JdbcBusinessOperationLifecycleRepository(dataSource);
          Optional<JsonNode> current = lifecycleRepo.findCurrent(SCOPE, operationId);
          if (current.isPresent()) {
            emitOk(response, current.get(), SCOPE, target);
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
            emitOk(response, original.get(), SCOPE, target);
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

  public static ObjectNode populateOk(
      ObjectNode response,
      JsonNode result,
      BusinessOperationScope scope,
      GitHubBusinessOperationBinding.ExpectedProfile target
  ) {
    response.put("status", "OK");
    String opId = result.path("operationId").asText();
    response.put("operationId", opId);
    response.put("resultStatus", result.path("authorization").path("status").asText());
    response.put("snapshotHash", CanonicalJson.sha256(result));

    var bound = GitHubBusinessOperationBinding.bind(scope, result, target);
    response.put("bindingDigest", bound.bindingDigest());

    JsonNode transitions = result.path("transitions");
    if (!transitions.isArray() || transitions.isEmpty()) {
      throw new IllegalStateException("Snapshot missing transitions array");
    }
    JsonNode lastFact = transitions.get(transitions.size() - 1);
    String factId = opId + "#" + lastFact.path("sequence").asInt();
    response.put("factId", factId);

    response.set("snapshot", result);
    return response;
  }

  private static void emitOk(
      ObjectNode response,
      JsonNode result,
      BusinessOperationScope scope,
      GitHubBusinessOperationBinding.ExpectedProfile target
  ) {
    populateOk(response, result, scope, target);
    System.out.println(CanonicalJson.canonicalize(response));
    System.out.flush();
  }

  // --- Stderr Drainer Class with Exact Byte and Line Tracking ---

  public static final class StderrDrainer implements Runnable {
    private final InputStream stream;
    private final List<String> categories = new CopyOnWriteArrayList<>();
    private final AtomicInteger lineCount = new AtomicInteger(0);
    private final AtomicInteger byteCount = new AtomicInteger(0);
    private final AtomicBoolean truncated = new AtomicBoolean(false);

    private static final List<String> KNOWN_CATEGORIES = List.of(
        "CHILD_SIMULATED_CRASH",
        "CHILD_SIMULATED_EARLY_EXIT",
        "STDERR_PRESSURE",
        "CHILD_BARRIER_ABORTED",
        "CHILD_ERROR",
        "CHILD_UNCAUGHT_EXCEPTION"
    );

    public StderrDrainer(InputStream stream) {
      this.stream = stream;
    }

    @Override
    public void run() {
      byte[] buf = new byte[1024];
      ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
      boolean currentLineTruncated = false;
      int retainedBytes = 0;

      try {
        int read;
        while ((read = stream.read(buf)) != -1) {
          byteCount.addAndGet(read);
          for (int i = 0; i < read; i++) {
            byte b = buf[i];
            if (b == '\n') {
              lineCount.incrementAndGet();
              byte[] lineBytes = lineBuffer.toByteArray();
              lineBuffer.reset();
              retainedBytes = processLine(lineBytes, currentLineTruncated, retainedBytes);
              currentLineTruncated = false;
            } else {
              if (lineBuffer.size() < MAX_STDERR_LINE_BYTES) {
                lineBuffer.write(b);
              } else {
                currentLineTruncated = true;
                truncated.set(true);
              }
            }
          }
        }
        if (lineBuffer.size() > 0) {
          lineCount.incrementAndGet();
          byte[] lineBytes = lineBuffer.toByteArray();
          processLine(lineBytes, currentLineTruncated, retainedBytes);
        }
      } catch (IOException ignored) {
        // Stream closed or process terminated
      }
    }

    private int processLine(byte[] lineBytes, boolean wasTruncated, int retainedBytes) {
      int len = lineBytes.length;
      if (len > 0 && lineBytes[len - 1] == '\r') {
        len--;
      }
      String lineText;
      try {
        lineText = decodeStrictUtf8(lineBytes, len);
      } catch (CharacterCodingException e) {
        lineText = "";
      }

      String category = "UNCLASSIFIED_STDERR";
      for (String known : KNOWN_CATEGORIES) {
        if (lineText.contains(known)) {
          category = known;
          break;
        }
      }

      if (categories.size() < MAX_STDERR_LINES && (retainedBytes + len) <= MAX_STDERR_TOTAL_BYTES) {
        categories.add(category);
        return retainedBytes + len;
      } else {
        truncated.set(true);
        return retainedBytes;
      }
    }

    public List<String> categories() { return Collections.unmodifiableList(categories); }
    public int lineCount() { return lineCount.get(); }
    public int byteCount() { return byteCount.get(); }
    public boolean isTruncated() { return truncated.get(); }
  }

  // --- Supervision Methods executed in the Parent Test ---

  public static ChildResult runChild(ChildRequest request, Duration timeout) throws Exception {
    try (ContenderHandle handle = startContender(request, timeout)) {
      return handle.awaitResult(timeout);
    }
  }

  public static ContenderHandle startContender(ChildRequest request) throws Exception {
    return startContender(request, Duration.ofSeconds(60));
  }

  public static ContenderHandle startContender(ChildRequest request, Duration commandBudget) throws Exception {
    String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("tracepermit.operationsTestClasspath");
    if (classpath == null || classpath.isBlank()) {
      classpath = System.getProperty("java.class.path");
    }

    List<String> commandList = new ArrayList<>();
    commandList.add(javaBinary);
    commandList.add("-cp");
    commandList.add(classpath);
    commandList.add(OperationReviewChildJvm.class.getName());
    if (request.stallBeforeStdin()) {
      commandList.add("--stall-before-stdin");
    }

    ProcessBuilder pb = new ProcessBuilder(commandList);
    // Explicitly do NOT put credentials or request into env or args!
    Process process = pb.start();

    return startContender(
        request,
        commandBudget,
        process,
        process.getOutputStream(),
        process.getInputStream(),
        process.getErrorStream()
    );
  }

  static ContenderHandle startContender(
      ChildRequest request,
      Duration commandBudget,
      Process process,
      OutputStream outputStream,
      InputStream inputStream,
      InputStream errorStream
  ) throws Exception {
    long startNanos = System.nanoTime();
    long budgetNanos = commandBudget.toNanos();
    ContenderHandle handle = null;

    try {
      // 1. Concurrent bounded stderr drainer
      StderrDrainer drainer = new StderrDrainer(errorStream);
      Thread drainerThread = new Thread(drainer, "child-stderr-drainer");
      drainerThread.setDaemon(true);
      drainerThread.start();

      // 2. IO worker executor
      ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "contender-io-worker");
        t.setDaemon(true);
        return t;
      });

      // 3. Stdin writer and stdout stream
      BufferedWriter stdinWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
      InputStream stdoutStream = inputStream;

      // Extract expected operation ID if known
      String expectedOpId = request.operationId();
      if (expectedOpId == null && request.operationJson() != null) {
        try {
          JsonNode op = JsonSupport.MAPPER.readTree(request.operationJson());
          if (op.hasNonNull("operationId")) {
            expectedOpId = op.path("operationId").asText();
          }
        } catch (Exception ignored) {}
      }

      GitHubBusinessOperationBinding.ExpectedProfile expectedProfile = null;
      try {
        ObjectNode syntheticPolicy = createPolicy(request.policyOutcome() != null ? request.policyOutcome() : "require_approval");
        expectedProfile = createExpectedProfile(syntheticPolicy);
      } catch (Exception ignored) {}

      // 4. UNCONDITIONAL OWNERSHIP: ContenderHandle takes ownership of all resources IMMEDIATELY
      handle = new ContenderHandle(
          process, stdinWriter, stdoutStream, drainer, drainerThread, executor,
          startNanos, budgetNanos, expectedOpId, expectedProfile
      );

      // 5. Serialize request payload
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
      if (request.canary() != null) reqNode.put("canary", request.canary());

      String serializedReq = CanonicalJson.canonicalize(reqNode);
      if (serializedReq.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
        throw new ProtocolException("Serialized ChildRequest exceeds MAX_LINE_BYTES bound");
      }

      // 6. Send request payload bounded by monotonic command budget
      long writeRemNanos = Math.max(0, budgetNanos - (System.nanoTime() - startNanos));
      if (writeRemNanos <= 0) {
        throw new TimeoutException("Monotonic command budget expired before request write");
      }
      Future<?> writeFuture = executor.submit(() -> {
        stdinWriter.write(serializedReq);
        stdinWriter.newLine();
        stdinWriter.flush();
        return null;
      });
      try {
        writeFuture.get(writeRemNanos, TimeUnit.NANOSECONDS);
      } catch (TimeoutException te) {
        throw new TimeoutException("Child request write timed out within monotonic command budget");
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ie;
      } catch (ExecutionException ee) {
        throw new IOException("Failed to write request to child stdin", ee.getCause());
      }

      if (request.barrier()) {
        handle.awaitReady();
      }
      return handle;
    } catch (Throwable t) {
      if (handle != null) {
        handle.close();
      } else {
        if (process.isAlive()) {
          process.destroyForcibly();
          try {
            process.waitFor(REAP_RESERVE_MS, TimeUnit.MILLISECONDS);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        }
        try { inputStream.close(); } catch (Throwable ignored) {}
        try { outputStream.close(); } catch (Throwable ignored) {}
        try { errorStream.close(); } catch (Throwable ignored) {}
      }
      if (t instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw t;
    }
  }

  public static final class ContenderHandle implements AutoCloseable {
    private final Process process;
    private final BufferedWriter stdinWriter;
    private final InputStream stdoutStream;
    private final StderrDrainer drainer;
    private final Thread drainerThread;
    private final ExecutorService executor;
    private final long startNanos;
    private final long budgetNanos;
    private final String expectedOperationId;
    private final GitHubBusinessOperationBinding.ExpectedProfile expectedProfile;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean cleanlyReaped = new AtomicBoolean(false);
    private final AtomicBoolean executorTerminated = new AtomicBoolean(false);
    private final AtomicBoolean drainerJoined = new AtomicBoolean(false);

    ContenderHandle(
        Process process,
        BufferedWriter stdinWriter,
        InputStream stdoutStream,
        StderrDrainer drainer,
        Thread drainerThread,
        ExecutorService executor,
        long startNanos,
        long budgetNanos,
        String expectedOperationId,
        GitHubBusinessOperationBinding.ExpectedProfile expectedProfile
    ) {
      this.process = process;
      this.stdinWriter = stdinWriter;
      this.stdoutStream = stdoutStream;
      this.drainer = drainer;
      this.drainerThread = drainerThread;
      this.executor = executor;
      this.startNanos = startNanos;
      this.budgetNanos = budgetNanos;
      this.expectedOperationId = expectedOperationId;
      this.expectedProfile = expectedProfile;
    }

    public boolean isAlive() {
      return process.isAlive();
    }

    public int exitValue() {
      return process.exitValue();
    }

    public void destroy() {
      close();
    }

    public boolean cleanlyReaped() {
      return cleanlyReaped.get();
    }

    public boolean executorTerminated() {
      return executorTerminated.get();
    }

    public boolean drainerTerminated() {
      return drainerJoined.get();
    }

    private long remainingNanos() {
      return Math.max(0, budgetNanos - (System.nanoTime() - startNanos));
    }

    public void awaitReady() throws Exception {
      awaitReady(Duration.ofNanos(remainingNanos()));
    }

    public void awaitReady(Duration timeout) throws Exception {
      long remNanos = Math.min(remainingNanos(), timeout.toNanos());
      if (remNanos <= 0) {
        close();
        throw new TimeoutException("Child timed out waiting for READY signal: monotonic budget expired");
      }

      try {
        Future<String> future = executor.submit(() -> readBoundedLine(stdoutStream, MAX_LINE_BYTES));
        String line = future.get(remNanos, TimeUnit.NANOSECONDS);
        if (!"READY".equals(line)) {
          throw new IllegalStateException("Child failed readiness handshake: unexpected stdout signal");
        }
      } catch (TimeoutException te) {
        close();
        throw new TimeoutException("Child timed out waiting for READY signal within monotonic budget");
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        close();
        throw ie;
      } catch (ExecutionException ee) {
        close();
        if (ee.getCause() instanceof Exception ex) throw ex;
        throw new IOException("Readiness read failed", ee.getCause());
      } catch (Throwable t) {
        close();
        throw t;
      }
    }

    public void signalGo() throws Exception {
      long remNanos = Math.min(remainingNanos(), TimeUnit.SECONDS.toNanos(5));
      if (remNanos <= 0) {
        close();
        throw new TimeoutException("Monotonic command budget expired before signalGo");
      }
      Future<?> f = executor.submit(() -> {
        stdinWriter.write("GO\n");
        stdinWriter.flush();
        return null;
      });
      try {
        f.get(remNanos, TimeUnit.NANOSECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        close();
        throw ie;
      }
    }

    public void awaitCommitted(Duration timeout) throws Exception {
      long remNanos = Math.min(remainingNanos(), timeout.toNanos());
      if (remNanos <= 0) {
        close();
        throw new TimeoutException("Child timed out waiting for COMMITTED signal: monotonic budget expired");
      }

      try {
        Future<String> future = executor.submit(() -> readBoundedLine(stdoutStream, MAX_LINE_BYTES));
        String line = future.get(remNanos, TimeUnit.NANOSECONDS);
        if (!"COMMITTED".equals(line)) {
          throw new IllegalStateException("Child failed post-commit handshake: unexpected stdout signal");
        }
      } catch (TimeoutException te) {
        close();
        throw new TimeoutException("Child timed out waiting for COMMITTED signal within monotonic budget");
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        close();
        throw ie;
      } catch (ExecutionException ee) {
        close();
        if (ee.getCause() instanceof Exception ex) throw ex;
        throw new IOException("Committed read failed", ee.getCause());
      } catch (Throwable t) {
        close();
        throw t;
      }
    }

    public void signalRelease() throws Exception {
      long remNanos = Math.min(remainingNanos(), TimeUnit.SECONDS.toNanos(5));
      if (remNanos <= 0) {
        close();
        throw new TimeoutException("Monotonic command budget expired before signalRelease");
      }
      Future<?> f = executor.submit(() -> {
        stdinWriter.write("RELEASE\n");
        stdinWriter.flush();
        return null;
      });
      try {
        f.get(remNanos, TimeUnit.NANOSECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        close();
        throw ie;
      }
    }

    public ChildResult awaitResult(Duration timeout) throws Exception {
      long remNanos = Math.min(remainingNanos(), timeout.toNanos());

      try {
        String responseLine = null;
        if (remNanos > 0 && !closed.get()) {
          try {
            Future<String> lineFuture = executor.submit(() -> readBoundedLine(stdoutStream, MAX_LINE_BYTES));
            responseLine = lineFuture.get(remNanos, TimeUnit.NANOSECONDS);
          } catch (TimeoutException te) {
            close();
            throw new TimeoutException("Child timed out waiting for response line within monotonic budget");
          } catch (ExecutionException ee) {
            // Stream closed or IO error
          }
        }

        remNanos = remainingNanos();
        long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remNanos));
        boolean exited = process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
        if (!exited) {
          process.destroyForcibly();
          exited = process.waitFor(REAP_RESERVE_MS, TimeUnit.MILLISECONDS);
        }
        if (!exited) {
          cleanlyReaped.set(false);
          return unacknowledged(-1, drainer);
        }
        cleanlyReaped.set(true);
        int exitCode = process.exitValue();

        drainerThread.join(500);
        drainerJoined.set(!drainerThread.isAlive());

        if (responseLine == null || responseLine.isBlank() || exitCode != 0) {
          return unacknowledged(exitCode, drainer);
        }

        JsonNode resNode;
        try (JsonParser parser = STRICT_JSON_FACTORY.createParser(responseLine)) {
          resNode = LOCAL_STRICT_MAPPER.readTree(parser);
          if (parser.nextToken() != null) {
            // Refuse trailing tokens
            return unacknowledged(exitCode, drainer);
          }
        } catch (Exception parseError) {
          return unacknowledged(exitCode, drainer);
        }

        if (!resNode.isObject()) {
          return unacknowledged(exitCode, drainer);
        }

        JsonNode jvmIdNode = resNode.get("jvmId");
        if (jvmIdNode == null || !jvmIdNode.isTextual()) {
          return unacknowledged(exitCode, drainer);
        }
        String jvmId = jvmIdNode.textValue();
        if (!STRICT_UUID_PATTERN.matcher(jvmId).matches()) {
          return unacknowledged(exitCode, drainer);
        }

        JsonNode statusNode = resNode.get("status");
        if (statusNode == null || !statusNode.isTextual()) {
          return unacknowledged(exitCode, drainer);
        }
        String status = statusNode.textValue();

        if ("OK".equals(status)) {
          for (Iterator<String> it = resNode.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!ALLOWED_OK_FIELDS.contains(field)) {
              return unacknowledged(exitCode, drainer);
            }
          }

          JsonNode opIdNode = resNode.get("operationId");
          if (opIdNode == null || !opIdNode.isTextual() || opIdNode.textValue().isBlank()) {
            return unacknowledged(exitCode, drainer);
          }
          String operationId = opIdNode.textValue();
          if (expectedOperationId != null && !expectedOperationId.equals(operationId)) {
            return unacknowledged(exitCode, drainer);
          }

          JsonNode resultStatusNode = resNode.get("resultStatus");
          if (resultStatusNode == null || !resultStatusNode.isTextual() || resultStatusNode.textValue().isBlank()) {
            return unacknowledged(exitCode, drainer);
          }
          String resultStatus = resultStatusNode.textValue();

          JsonNode bindingDigestNode = resNode.get("bindingDigest");
          if (bindingDigestNode == null || !bindingDigestNode.isTextual() || bindingDigestNode.textValue().isBlank()) {
            return unacknowledged(exitCode, drainer);
          }

          JsonNode factIdNode = resNode.get("factId");
          if (factIdNode == null || !factIdNode.isTextual() || factIdNode.textValue().isBlank()) {
            return unacknowledged(exitCode, drainer);
          }

          JsonNode hashNode = resNode.get("snapshotHash");
          if (hashNode == null || !hashNode.isTextual() || hashNode.textValue().isBlank()) {
            return unacknowledged(exitCode, drainer);
          }
          String snapshotHash = hashNode.textValue();

          JsonNode snapshot = resNode.get("snapshot");
          if (snapshot == null || !snapshot.isObject() || snapshot.isEmpty()) {
            return unacknowledged(exitCode, drainer);
          }

          try {
            BusinessOperationValidator.validate(snapshot);
          } catch (Exception validationFailed) {
            return unacknowledged(exitCode, drainer);
          }

          String expectedHash = CanonicalJson.sha256(snapshot);
          if (!expectedHash.equals(snapshotHash)) {
            return unacknowledged(exitCode, drainer);
          }

          if (!operationId.equals(snapshot.path("operationId").asText())) {
            return unacknowledged(exitCode, drainer);
          }

          if (!resultStatus.equals(snapshot.path("authorization").path("status").asText())) {
            return unacknowledged(exitCode, drainer);
          }

          // Strict factId join: snapshot must have transitions and last fact sequence must match factId
          JsonNode transitions = snapshot.path("transitions");
          if (!transitions.isArray() || transitions.isEmpty()) {
            return unacknowledged(exitCode, drainer);
          }
          JsonNode lastFact = transitions.get(transitions.size() - 1);
          int lastSeq = lastFact.path("sequence").asInt(-1);
          if (lastSeq < 0) {
            return unacknowledged(exitCode, drainer);
          }
          String expectedFactId = operationId + "#" + lastSeq;
          if (!expectedFactId.equals(factIdNode.textValue())) {
            return unacknowledged(exitCode, drainer);
          }

          // Strict bindingDigest join: bind against expectedProfile and verify digest
          if (expectedProfile != null) {
            try {
              var bound = GitHubBusinessOperationBinding.bind(SCOPE, snapshot, expectedProfile);
              if (!bound.bindingDigest().equals(bindingDigestNode.textValue())) {
                return unacknowledged(exitCode, drainer);
              }
            } catch (Exception bindingFailed) {
              return unacknowledged(exitCode, drainer);
            }
          }

          return new ChildResult(
              true, exitCode, jvmId, "OK", null, resultStatus, snapshotHash, snapshot,
              drainer.categories(), drainer.lineCount(), drainer.byteCount(), drainer.isTruncated()
          );
        } else if ("REFUSED".equals(status)) {
          for (Iterator<String> it = resNode.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!ALLOWED_REFUSED_FIELDS.contains(field)) {
              return unacknowledged(exitCode, drainer);
            }
          }
          JsonNode codeNode = resNode.get("code");
          if (codeNode == null || !codeNode.isTextual()) {
            return unacknowledged(exitCode, drainer);
          }
          String code = codeNode.textValue();
          if (Arrays.stream(BusinessOperationStorageException.Code.values()).noneMatch(c -> c.name().equals(code))) {
            return unacknowledged(exitCode, drainer);
          }
          return new ChildResult(
              true, exitCode, jvmId, "REFUSED", code, null, null, null,
              drainer.categories(), drainer.lineCount(), drainer.byteCount(), drainer.isTruncated()
          );
        } else {
          return unacknowledged(exitCode, drainer);
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ie;
      } finally {
        close();
      }
    }

    private static ChildResult unacknowledged(int exitCode, StderrDrainer drainer) {
      return new ChildResult(
          false, exitCode, null, "UNACKNOWLEDGED", null, null, null, null,
          drainer.categories(), drainer.lineCount(), drainer.byteCount(), drainer.isTruncated()
      );
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      // 1. Destroy owned process FIRST to unblock any pending stream I/O
      boolean reaped = false;
      if (process.isAlive()) {
        process.destroyForcibly();
        try {
          reaped = process.waitFor(REAP_RESERVE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      } else {
        reaped = true;
      }
      cleanlyReaped.set(reaped);

      // 2. Shut down IO executor with finite wait
      executor.shutdownNow();
      boolean execTerm = false;
      try {
        execTerm = executor.awaitTermination(500, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      executorTerminated.set(execTerm);

      // 3. Close streams
      try { stdinWriter.close(); } catch (Throwable ignored) {}
      try { stdoutStream.close(); } catch (Throwable ignored) {}

      // 4. Join drainer thread with finite wait
      boolean joined = false;
      try {
        drainerThread.join(500);
        joined = !drainerThread.isAlive();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      drainerJoined.set(joined);
    }
  }

  public static Path findRepoRoot() {
    String prop = System.getProperty("tracepermit.repositoryRoot");
    if (prop != null && !prop.isBlank()) {
      return Path.of(prop);
    }
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("contracts/vectors/business-operation-v1.json"))) {
        return current;
      }
      current = current.getParent();
    }
    return Path.of(".");
  }

  public static ObjectNode createBaselineOkResponse(Path repoRoot) {
    try {
      ObjectNode policy = createPolicy("require_approval");
      var target = createExpectedProfile(policy);
      ObjectNode snapshot = createInitialOperation(target, repoRoot);
      ObjectNode response = JsonSupport.MAPPER.createObjectNode();
      response.put("jvmId", JVM_ID);
      populateOk(response, snapshot, SCOPE, target);
      return response;
    } catch (Exception e) {
      throw new RuntimeException("Failed to create baseline OK response", e);
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
