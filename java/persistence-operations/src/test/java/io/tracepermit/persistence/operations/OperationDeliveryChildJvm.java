package io.tracepermit.persistence.operations;

import static io.tracepermit.persistence.operations.OperationReviewChildJvm.HASH;
import static io.tracepermit.persistence.operations.OperationReviewChildJvm.SCOPE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.*;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.execution.TenantPartition;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Test-only real JVM halt/restart probe. Credentials/permits travel ONLY over anonymous stdin. */
public final class OperationDeliveryChildJvm {
  private static final Set<String> PHASES = Set.of("before_claim", "after_claim", "before_dispatch_started",
      "after_dispatch_started", "after_target_before_result");
  private OperationDeliveryChildJvm() {}

  public static void main(String[] args) {
    String stage = "setup";
    try {
      if (args.length != 0 || Runtime.version().feature() != 21) throw new Failure();
      JsonNode setup = frame();
      fields(setup, Set.of("jdbcUrl", "username", "password", "publicKey", "policy", "resourceBindingHash",
          "artifactHash", "profileHash", "operationId", "phase", "mode"));
      String id = text(setup, "operationId");
      if (!UUID.fromString(id).toString().equals(id)) throw new Failure();
      String mode = text(setup, "mode"), phase = text(setup, "phase");
      if (!Set.of("crash", "observe", "attempt").contains(mode) || (mode.equals("crash") && !PHASES.contains(phase))) throw new Failure();
      JsonNode policy = setup.path("policy");
      var expected = new GitHubBusinessOperationBinding.ExpectedProfile("github-draft-pr-v1", "test", 1,
          new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", 1, CanonicalJson.sha256(policy)),
          Map.of("policy_bundle", HASH, "adapter_release", HASH),
          new GitHubPilotTargetBinding(71, 101, 201, "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40)));
      var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(text(setup, "publicKey"))));
      var profile = new BusinessOperationAuthorityProfile(SCOPE, expected.adapterId(), 1, policy,
          text(setup, "artifactHash"), text(setup, "resourceBindingHash"),
          new BusinessOperationReviewSettings("synthetic-reviewers", HASH, Duration.ofMinutes(2)),
          new BusinessOperationAdmissionSettings("https://synthetic-issuer.invalid", "synthetic-key", "synthetic-admission", key, Duration.ofSeconds(60)));
      if (!profile.fingerprint().equals(text(setup, "profileHash"))) throw new Failure();
      DataSource source = new Source(text(setup, "jdbcUrl"), text(setup, "username"), text(setup, "password"));
      var target = new SyntheticGitHubDeliveryTarget(expected, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
      stage = "bootstrap";
      var repository = new JdbcGitHubOperationDeliveryRepository(source, profile, expected, target, point -> {
        if (mode.equals("crash") && point.equals(phase)) {
          emit("CRASH:" + phase + ":" + target.invocations(id));
          Runtime.getRuntime().halt(79); // Real process death: no catch/finally or shutdown-hook cleanup.
        }
      });
      emit("READY"); // Bootstrap ends BEFORE the parent issues/admit its short-lived permit.
      stage = "request";
      JsonNode request = frame(); fields(request, Set.of("permit", "identityExpiresAt"));
      if (mode.equals("observe")) {
        stage = "reconcile";
        var observation = repository.reconcile(SCOPE, id);
        emit("OBSERVE:" + observation.durableState() + ":" + observation.dispatchStarted() + ":" + observation.syntheticInvocations());
        return;
      }
      stage = "current";
      JsonNode head = current(source, id);
      var identity = new TrustedRequesterContext(new TenantPartition(SCOPE.tenantId()), SCOPE.requesterPrincipalId(), true,
          Set.of("operation_requester"), Set.of("operations.execute"), "synthetic-test", HASH, Instant.parse(text(request, "identityExpiresAt")));
      var command = new BusinessOperationExecution(SCOPE, id, new BusinessOperationTransitionPlanner().observe(SCOPE, head), identity, SCOPE.workspaceId());
      try {
        stage = "claim";
        var lease = repository.claim(command, text(request, "permit"));
        stage = "dispatch";
        String result = repository.dispatch(lease, command, text(request, "permit"));
        emit("DONE:" + result + ":" + target.invocations(id));
      } catch (BusinessOperationStorageException fixed) {
        emit("REFUSED:" + fixed.code().name() + ":" + target.invocations(id));
      }
    } catch (Throwable rejected) {
      // Fixed phase/category only: never echo private input, driver messages or a cause stack.
      emit("ERROR:" + stage + ":" + category(rejected));
      System.err.println("CHILD_ERROR");
      System.exit(3);
    }
  }

  private static JsonNode frame() throws IOException {
    String value = OperationReviewChildJvm.readBoundedLine(System.in, 65_536);
    if (value == null) throw new Failure();
    return StoredOperationJson.read(value, 65_536);
  }
  private static void emit(String value) { System.out.println(value); System.out.flush(); }
  private static JsonNode current(DataSource source, String id) throws SQLException {
    // The concrete sender already validated this exact V7 datasource at bootstrap.
    try (var connection = source.getConnection()) {
      OperationDatabase.inspectWriter(connection); OperationDatabase.requireDeliveryVersion(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); connection.setAutoCommit(false);
      try {
        JsonNode initial = JdbcBusinessOperationRepository.read(connection, SCOPE, "operation_id", id).orElseThrow(Failure::new);
        return JdbcBusinessOperationLifecycleRepository.current(connection, SCOPE, id, initial, false);
      } finally { connection.rollback(); }
    }
  }
  private static String text(JsonNode value, String key) {
    if (!value.path(key).isTextual()) throw new Failure(); return value.path(key).asText();
  }
  private static void fields(JsonNode value, Set<String> expected) {
    Set<String> actual = new HashSet<>(); value.fieldNames().forEachRemaining(actual::add);
    if (!value.isObject() || !actual.equals(expected)) throw new Failure();
  }
  private static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    Failure() { this("UNCLASSIFIED"); }
    Failure(String category) { super("DELIVERY_CHILD_FAILURE:" + category, null, false, true); }
  }

  private static String category(Throwable failure) {
    if (failure instanceof TimeoutException) return "TIMEOUT";
    if (failure instanceof SQLException) return "SQL";
    if (failure instanceof BusinessOperationStorageException storage) return storage.code().name();
    if (failure instanceof Failure) return "PROTOCOL";
    if (failure instanceof IOException) return "IO";
    if (failure instanceof InterruptedException) return "INTERRUPTED";
    return "OTHER";
  }

  /** Reuses existing bounded strict UTF8 and stderr categorization without changing the producer. */
  static final class Managed implements AutoCloseable {
    private final Process process;
    private final ExecutorService io;
    private final OperationReviewChildJvm.StderrDrainer stderr;
    private boolean closed;
    private Managed(Process process, ExecutorService io) {
      this.process = process; this.io = io;
      this.stderr = new OperationReviewChildJvm.StderrDrainer(process.getErrorStream());
    }
    static Managed start(ObjectNode setup) throws Exception {
      String classpath = System.getProperty("tracepermit.operationsTestClasspath");
      if (classpath == null || classpath.isBlank()) throw new Failure();
      Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", classpath, OperationDeliveryChildJvm.class.getName()).start();
      ExecutorService io = null;
      Managed result = null;
      try {
        io = Executors.newFixedThreadPool(3);
        result = new Managed(process, io);
        io.submit(result.stderr);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        result.write(setup, end);
        String ready = result.line(end);
        if (!ready.equals("READY")) throw new Failure(safeError(ready) ? ready : "READY_PROTOCOL");
        return result;
      } catch (Throwable failure) {
        if (result != null) result.close();
        else cleanup(process, io);
        if (failure instanceof Failure fixed) throw fixed;
        throw new Failure("BOOTSTRAP_" + category(failure));
      }
    }
    String execute(ObjectNode request) throws Exception {
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      write(request, end);
      String result = line(end);
      if (safeError(result)) throw new Failure(result);
      if (!safeResponse(result)) throw new Failure("RESPONSE_PROTOCOL");
      return result;
    }
    private void write(JsonNode value, long end) throws Exception {
      byte[] bytes = (CanonicalJson.canonicalize(value) + "\n").getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 65_536) throw new Failure();
      Future<?> write = io.submit(() -> {
        try { process.getOutputStream().write(bytes); process.getOutputStream().flush(); }
        catch (IOException failure) { throw new Failure(); }
      });
      write.get(remaining(end), TimeUnit.NANOSECONDS);
    }
    private String line(long end) throws Exception {
      Future<String> read = io.submit(() -> OperationReviewChildJvm.readBoundedLine(process.getInputStream(), 256));
      String value = read.get(remaining(end), TimeUnit.NANOSECONDS);
      if (value == null) throw new Failure("STDOUT_EOF"); return value;
    }
    private static boolean safeResponse(String value) {
      String[] parts = value.split(":", -1);
      try {
        if (parts.length == 3 && parts[0].equals("CRASH")) return PHASES.contains(parts[1]) && count(parts[2]);
        if (parts.length == 3 && parts[0].equals("REFUSED")) {
          BusinessOperationStorageException.Code.valueOf(parts[1]); return count(parts[2]);
        }
        if (parts.length == 3 && parts[0].equals("DONE")) return Set.of("succeeded", "failed", "unknown").contains(parts[1]) && count(parts[2]);
        return parts.length == 4 && parts[0].equals("OBSERVE") && Set.of("claimed", "executing", "succeeded", "failed", "unknown").contains(parts[1])
            && Set.of("true", "false").contains(parts[2]) && count(parts[3]);
      } catch (RuntimeException rejected) { return false; }
    }
    private static boolean count(String value) { return value.matches("[0-9]{1,3}"); }
    private static boolean safeError(String value) {
      String[] parts = value.split(":", -1);
      if (parts.length != 3 || !parts[0].equals("ERROR")
          || !Set.of("setup", "bootstrap", "request", "reconcile", "current", "claim", "dispatch").contains(parts[1])) return false;
      if (Set.of("TIMEOUT", "SQL", "PROTOCOL", "IO", "INTERRUPTED", "OTHER").contains(parts[2])) return true;
      try { BusinessOperationStorageException.Code.valueOf(parts[2]); return true; }
      catch (IllegalArgumentException rejected) { return false; }
    }
    long pid() { return process.pid(); }
    int finish() throws Exception {
      if (!process.waitFor(5, TimeUnit.SECONDS)) throw new Failure("FINISH_TIMEOUT"); return process.exitValue();
    }
    boolean alive() { return process.isAlive(); }
    List<String> stderrCategories() { return stderr.categories(); }
    @Override public void close() {
      if (closed) return;
      closed = true;
      try { cleanup(process, io); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Failure(); }
      catch (Exception failure) { throw new Failure(); }
    }
    private static void cleanup(Process process, ExecutorService io) throws Exception {
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      boolean reaped = false, stopped = io == null;
      try {
        if (process.isAlive()) process.destroyForcibly(); // Only this immediately owned child.
        reaped = process.waitFor(remaining(end), TimeUnit.NANOSECONDS);
      } finally {
        try { process.getOutputStream().close(); } catch (IOException ignored) {}
        try { process.getInputStream().close(); } catch (IOException ignored) {}
        try { process.getErrorStream().close(); } catch (IOException ignored) {}
        if (io != null) { io.shutdownNow(); stopped = io.awaitTermination(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS); }
      }
      if (!reaped || !stopped || process.isAlive()) throw new Failure("CLEANUP_NOT_ACKNOWLEDGED");
    }
    private static long remaining(long end) { long value = end - System.nanoTime(); if (value <= 0) throw new Failure(); return value; }
  }

  private static final class Source implements DataSource {
    private final String url, user, password;
    Source(String url, String user, String password) {
      if (!url.matches("jdbc:(postgresql|mariadb)://(localhost|127\\.0\\.0\\.1):[0-9]+/[A-Za-z0-9_]+(?:\\?.*)?")) throw new Failure();
      this.url = url + (url.contains("?") ? "&" : "?") + (url.startsWith("jdbc:postgresql:") ? "connectTimeout=5&socketTimeout=15" : "connectTimeout=5000&socketTimeout=15000");
      this.user = user; this.password = password;
    }
    @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) {}
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
  }
}
