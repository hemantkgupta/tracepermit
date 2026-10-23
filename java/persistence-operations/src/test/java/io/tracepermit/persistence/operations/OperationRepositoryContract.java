package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationRegistration;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.JdbcDatabaseContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class OperationRepositoryContract {
  private static final BusinessOperationScope SCOPE =
      new BusinessOperationScope("synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private JdbcDatabaseContainer<?> database;
  private TestSource source;
  private TestSource runtimeSource;
  private JdbcBusinessOperationRepository repository;

  abstract JdbcDatabaseContainer<?> container();

  @BeforeAll
  void start() {
    database = container().withPassword(UUID.randomUUID().toString());
    database.start();
    source = new TestSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    assertCode(() -> new JdbcBusinessOperationRepository(source), OPERATION_SCHEMA_NOT_READY);
    OperationDatabase.initializeEmpty(source);
    assertCode(() -> OperationDatabase.initializeEmpty(source), OPERATION_SCHEMA_NOT_READY);
    provisionRuntimeUser();
  }

  @AfterAll
  void stop() {
    if (database != null) database.stop();
  }

  @BeforeEach
  void reset() throws SQLException {
    source.fault = Fault.NONE;
    execute("DELETE FROM tp_business_operation_facts");
    execute("DELETE FROM tp_business_operations");
    repository = new JdbcBusinessOperationRepository(source);
  }

  @Test
  void restartAndTransportRetryReturnOriginalImmutableSnapshotAndPolicy() {
    ObjectNode first = operation();
    BusinessOperationRegistration created = repository.createOrGet(SCOPE, first);
    assertThat(created.created()).isTrue();
    ObjectNode retry = operation();
    ((ObjectNode) retry.path("canonicalAction")).put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) retry.path("canonicalAction").path("trigger")).put("requestId", "transport-retry");
    ((ObjectNode) retry.path("policyRelease")).put("version", 2);
    rehash(retry);
    var restarted = new JdbcBusinessOperationRepository(source);
    BusinessOperationRegistration existing = restarted.createOrGet(SCOPE, retry);
    assertThat(existing.created()).isFalse();
    assertThat(existing.operation()).isEqualTo(first);
    assertThat(restarted.find(SCOPE, first.path("operationId").asText())).contains(first);
    assertCounts(1, 3);
    ((ObjectNode) created.operation()).put("operationId", "mutated-return");
    assertThat(created.operation()).isEqualTo(first);
    assertThat(created.toString()).doesNotContain("title", "Synthetic governed change", "canonicalAction");
  }

  @Test
  void changedSemanticResourceAdapterOrRecoveryGenerationConflictsWithoutWriting() {
    ObjectNode first = operation();
    repository.createOrGet(SCOPE, first);
    for (String change : List.of("payload", "resource", "adapter", "generation")) {
      ObjectNode retry = operation();
      switch (change) {
        case "payload" -> ((ObjectNode) retry.path("canonicalAction").path("action").path("arguments"))
            .put("title", "Changed governed content");
        case "resource" -> ((ObjectNode) retry.path("resourceBinding").path("precondition"))
            .put("valueHash", "sha256:" + "9".repeat(64));
        case "adapter" -> retry.put("adapterId", "github-other-v1");
        case "generation" -> retry.put("recoveryGeneration", 2);
        default -> throw new AssertionError(change);
      }
      rehash(retry);
      assertCode(() -> repository.createOrGet(SCOPE, retry), OPERATION_REQUEST_KEY_CONFLICT);
      assertCounts(1, 3);
    }
    assertThat(repository.find(SCOPE, first.path("operationId").asText())).contains(first);
  }

  @Test
  void tenantWorkspaceRequesterAndCaseAreIsolatedEvenWithTheSameKey() {
    for (BusinessOperationScope scope : List.of(SCOPE,
        new BusinessOperationScope("Synthetic-tenant", SCOPE.workspaceId(), SCOPE.requesterPrincipalId()),
        new BusinessOperationScope(SCOPE.tenantId(), "other-workspace", SCOPE.requesterPrincipalId()),
        new BusinessOperationScope(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:other"))) {
      ObjectNode input = operation(scope);
      assertThat(repository.createOrGet(scope, input).created()).isTrue();
      if (!scope.equals(SCOPE)) {
        assertThat(repository.find(SCOPE, input.path("operationId").asText())).isEmpty();
      }
    }
    assertCounts(4, 12);
    assertCode(() -> repository.createOrGet(
        new BusinessOperationScope("other", "workspace", "principal:other"), operation()), OPERATION_SCOPE_MISMATCH);
    assertCode(() -> repository.find(SCOPE, "1-1-1-1-1"), OPERATION_ID_INVALID);
  }

  @Test
  void conflictingOperationIdDoesNotBecomeARetryOrLeakAnotherScope() {
    ObjectNode first = operation();
    repository.createOrGet(SCOPE, first);
    ObjectNode otherKey = first.deepCopy();
    otherKey.put("clientRequestKeyHash", "sha256:" + "a".repeat(64));
    assertCode(() -> repository.createOrGet(SCOPE, otherKey), OPERATION_ID_CONFLICT);
    BusinessOperationScope other = new BusinessOperationScope("other", "workspace", "principal:other");
    ObjectNode collision = operation(other);
    collision.put("operationId", first.path("operationId").asText());
    rehash(collision);
    assertCode(() -> repository.createOrGet(other, collision), OPERATION_ID_CONFLICT);
    assertCounts(1, 3);
  }

  @Test
  void sixteenConcurrentRegistrationsAcrossInstancesHaveOneWinner() throws Exception {
    List<JdbcBusinessOperationRepository> replicas = List.of(repository,
        new JdbcBusinessOperationRepository(source), new JdbcBusinessOperationRepository(source));
    var results = race(16, index -> replicas.get(index % replicas.size()).createOrGet(SCOPE, operation()));
    assertThat(results.stream().filter(BusinessOperationRegistration::created)).hasSize(1);
    assertThat(results.stream().map(result -> result.operation().path("operationId").asText()).distinct())
        .hasSize(1);
    assertCounts(1, 3);
  }

  @org.junit.jupiter.api.RepeatedTest(20)
  void competingPayloadsHaveOneSemanticWinnerAndAllOtherPayloadsConflict() throws Exception {
    var results = race(16, index -> {
      ObjectNode input = operation();
      ((ObjectNode) input.path("canonicalAction").path("action").path("arguments"))
          .put("title", "Synthetic payload " + (index % 2));
      rehash(input);
      try {
        return repository.createOrGet(SCOPE, input).created() ? "created" : "existing";
      } catch (BusinessOperationStorageException failure) {
        assertThat(failure.code()).as("fixed SQL categories: %s", source.sqlCategories)
            .isEqualTo(OPERATION_REQUEST_KEY_CONFLICT);
        return "conflict";
      }
    });
    assertThat(results).containsOnly("created", "existing", "conflict");
    assertThat(results.stream().filter("created"::equals)).hasSize(1);
    assertThat(results.stream().filter("existing"::equals)).hasSize(7);
    assertThat(results.stream().filter("conflict"::equals)).hasSize(8);
    assertCounts(1, 3);
  }

  @Test
  void failureDuringInitialFactsRollsBackTheWholeRegistration() {
    source.fault = Fault.SECOND_FACT;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
    assertThat(repository.createOrGet(SCOPE, operation()).created()).isTrue();
    assertCounts(1, 3);
  }

  @Test
  void lostCommitAcknowledgementNeverReportsCreatedAndExplicitRetryReadsOriginal() {
    ObjectNode first = operation();
    source.fault = Fault.AFTER_COMMIT;
    assertCode(() -> repository.createOrGet(SCOPE, first), OPERATION_COMMIT_UNKNOWN);
    source.fault = Fault.NONE;
    assertCounts(1, 3);
    assertThat(repository.createOrGet(SCOPE, operation()).created()).isFalse();
    assertThat(repository.find(SCOPE, first.path("operationId").asText())).contains(first);
  }

  @Test
  void failureBeforeCommitIsAlsoUnknownAndDoesNotAssumeDurability() {
    source.fault = Fault.BEFORE_COMMIT;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_COMMIT_UNKNOWN);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
  }

  @Test
  void cleanupFailureAfterCommitIsUnknownInsteadOfFalseSuccess() {
    source.fault = Fault.CLOSE_AFTER_COMMIT;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_COMMIT_UNKNOWN);
    source.fault = Fault.NONE;
    assertCounts(1, 3);
  }

  @Test
  void nonDuplicateIntegrityFailuresAndFailedRollbackNeverBecomeIdempotentSuccess() {
    source.fault = Fault.NOT_A_DUPLICATE;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
    source.fault = Fault.ROLLBACK;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
  }

  @Test
  void registrationRejectsPreauthorizedStatesAndOversizedSnapshotsBeforeOpeningTheDatabase() {
    JsonNode progressed = JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot"))
        .resolve("contracts/vectors/business-operation-v1.json")).path("operation");
    assertCode(() -> repository.createOrGet(SCOPE, progressed), OPERATION_INITIAL_STATE_REQUIRED);
    ObjectNode large = operation();
    ((ObjectNode) large.path("canonicalAction").path("action").path("arguments"))
        .put("body", "é".repeat(150_000));
    rehash(large);
    assertCode(() -> repository.createOrGet(SCOPE, large), OPERATION_TOO_LARGE);
    assertCounts(0, 0);
  }

  @Test
  void uncommittedProposalCannotAdvanceOrReplaceTheRegistrationSnapshot() {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var planner = new BusinessOperationTransitionPlanner();
    var proposal = planner.propose(SCOPE, initial, planner.observe(SCOPE, initial),
        BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION,
        "approved", "SYNTHETIC_SHAPE_ONLY", java.time.Instant.parse(initial.path("updatedAt").asText()));

    assertCode(() -> repository.createOrGet(SCOPE, proposal.snapshot()), OPERATION_INITIAL_STATE_REQUIRED);
    assertThat(repository.find(SCOPE, initial.path("operationId").asText())).contains(initial);
    assertCounts(1, 3);
  }

  @Test
  void snapshotAndInitialFactCorruptionFailClosedOnReadsAndRetries() throws SQLException {
    ObjectNode first = operation();
    repository.createOrGet(SCOPE, first);
    execute("UPDATE tp_business_operations SET snapshot_hash = 'sha256:" + "f".repeat(64) + "'");
    assertCode(() -> repository.find(SCOPE, first.path("operationId").asText()), OPERATION_STORAGE_CORRUPT);
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_STORAGE_CORRUPT);
    execute("DELETE FROM tp_business_operation_facts");
    execute("DELETE FROM tp_business_operations");
    repository.createOrGet(SCOPE, first);
    execute("DELETE FROM tp_business_operation_facts WHERE sequence_number = 2");
    assertCode(() -> repository.find(SCOPE, first.path("operationId").asText()), OPERATION_STORAGE_CORRUPT);
  }

  @Test
  void startupDetectsMissingUniquenessAndNeverRepairsTheSchema() throws SQLException {
    boolean postgres = database.getJdbcUrl().startsWith("jdbc:postgresql:");
    execute(postgres
        ? "ALTER TABLE tp_business_operations DROP CONSTRAINT tp_operation_request_key"
        : "ALTER TABLE tp_business_operations DROP PRIMARY KEY");
    try {
      assertCode(() -> new JdbcBusinessOperationRepository(source), OPERATION_SCHEMA_NOT_READY);
    } finally {
      execute("ALTER TABLE tp_business_operations ADD CONSTRAINT tp_operation_request_key PRIMARY KEY (scope_hash, request_key_hash)");
    }
    OperationDatabase.validateExisting(source);
  }

  @Test
  void diagnosticExceptionsContainOnlyFixedCodes() {
    source.fault = Fault.NOT_A_DUPLICATE;
    assertThatThrownBy(() -> repository.createOrGet(SCOPE, operation()))
        .isInstanceOf(BusinessOperationStorageException.class)
        .hasMessage("OPERATION_STORAGE_UNAVAILABLE")
        .hasNoCause();
  }

  @Test
  void restrictedRuntimeUserCanValidateRegisterAndReadButCannotMutateOrDelete() throws SQLException {
    var runtime = new JdbcBusinessOperationRepository(runtimeSource);
    ObjectNode input = operation();
    assertThat(runtime.createOrGet(SCOPE, input).created()).isTrue();
    assertThat(runtime.find(SCOPE, input.path("operationId").asText())).contains(input);
    try (Connection connection = runtimeSource.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM tp_business_operations"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.executeUpdate("UPDATE tp_business_operation_facts SET fact_json = '{}'"))
          .isInstanceOf(SQLException.class);
    }
    assertCounts(1, 3);
  }

  @Test
  void runtimeRefusesUnsupportedEngineVersionsAndWriterReadOnlySessions() {
    source.fault = Fault.UNKNOWN_ENGINE;
    assertCode(() -> new JdbcBusinessOperationRepository(source), OPERATION_DATABASE_UNSUPPORTED);
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_DATABASE_UNSUPPORTED);
    source.fault = Fault.READ_ONLY;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_WRITER_REQUIRED);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
  }

  private void provisionRuntimeUser() {
    boolean postgres = database.getJdbcUrl().startsWith("jdbc:postgresql:");
    String password = UUID.randomUUID().toString();
    TestSource owner = postgres ? source : new TestSource(database.getJdbcUrl(), "root", database.getPassword());
    try (Connection connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      if (postgres) {
        statement.execute("CREATE ROLE tp_operation_runtime LOGIN PASSWORD '" + password + "'");
        statement.execute("GRANT USAGE ON SCHEMA public TO tp_operation_runtime");
        statement.execute("GRANT SELECT, INSERT ON tp_business_operations, tp_business_operation_facts TO tp_operation_runtime");
        statement.execute("GRANT SELECT ON tp_operation_schema_history TO tp_operation_runtime");
      } else {
        statement.execute("CREATE USER 'tp_operation_runtime'@'%' IDENTIFIED BY '" + password + "'");
        String databaseName = database.getDatabaseName();
        for (String table : List.of("tp_business_operations", "tp_business_operation_facts")) {
          statement.execute("GRANT SELECT, INSERT ON `" + databaseName + "`." + table + " TO 'tp_operation_runtime'@'%'");
        }
        statement.execute("GRANT SELECT ON `" + databaseName + "`.tp_operation_schema_history TO 'tp_operation_runtime'@'%'");
      }
      runtimeSource = new TestSource(database.getJdbcUrl(), "tp_operation_runtime", password);
    } catch (SQLException failure) {
      throw new AssertionError("synthetic runtime user provisioning failed");
    }
  }

  private <T> List<T> race(int count, IndexedCall<T> call) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(count);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<T>>();
      for (int index = 0; index < count; index++) {
        final int task = index;
        futures.add(executor.submit(() -> {
          if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("race start timed out");
          return call.run(task);
        }));
      }
      start.countDown();
      var results = new ArrayList<T>();
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      for (var future : futures) results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      return results;
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @FunctionalInterface
  private interface IndexedCall<T> {
    T run(int index) throws Exception;
  }

  private static ObjectNode operation() {
    return operation(SCOPE);
  }

  private static ObjectNode operation(BusinessOperationScope scope) {
    ObjectNode operation = (ObjectNode) JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot"))
        .resolve("contracts/vectors/business-operation-v1.json")).path("operation").deepCopy();
    operation.put("operationId", UUID.randomUUID().toString());
    operation.set("trustedContext", scope.trustedContext());
    ObjectNode subject = (ObjectNode) operation.path("canonicalAction").path("identity").path("subject");
    subject.put("id", scope.requesterPrincipalId());
    subject.put("organizationId", scope.tenantId());
    // Keep all synthetic evidence/lineage references coherent with the trusted requester.
    ObjectNode evidence = (ObjectNode) operation.path("canonicalAction").path("identity").path("evidence").get(0);
    evidence.put("subjectId", scope.requesterPrincipalId());
    ObjectNode lineage = (ObjectNode) operation.path("canonicalAction").path("lineage");
    lineage.put("rootPrincipalId", scope.requesterPrincipalId());
    ((ObjectNode) lineage.path("hops").get(0)).put("subjectId", scope.requesterPrincipalId());
    ((ArrayNode) operation.path("transitions")).remove(3);
    ObjectNode authorization = (ObjectNode) operation.path("authorization");
    authorization.put("status", "evaluating");
    authorization.put("version", 1);
    authorization.put("updatedAt", operation.path("createdAt").asText());
    operation.put("updatedAt", operation.path("transitions").get(2).path("at").asText());
    rehash(operation);
    return operation;
  }

  private static void rehash(ObjectNode operation) {
    operation.put("canonicalActionHash", CanonicalJson.sha256(operation.path("canonicalAction")));
    operation.put("semanticPayloadDigest", CanonicalJson.sha256(operation.path("canonicalAction").path("action")));
    operation.put("resourceBindingHash", CanonicalJson.sha256(operation.path("resourceBinding")));
    operation.put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(
        operation.path("operationId").asText(), operation.path("adapterId").asText(), operation.path("recoveryGeneration").asLong()));
  }

  private void execute(String sql) throws SQLException {
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(sql);
    }
  }

  private void assertCounts(int operations, int facts) {
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_business_operations")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(operations);
      }
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_business_operation_facts")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(facts);
      }
    } catch (SQLException failure) {
      throw new AssertionError("synthetic count query failed");
    }
  }

  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      BusinessOperationStorageException.Code code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationStorageException.class,
        failure -> assertThat(failure.code()).isEqualTo(code));
  }

  private enum Fault { NONE, SECOND_FACT, BEFORE_COMMIT, AFTER_COMMIT, CLOSE_AFTER_COMMIT, NOT_A_DUPLICATE, ROLLBACK, UNKNOWN_ENGINE, READ_ONLY }

  private static final class TestSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;
    private volatile Fault fault = Fault.NONE;
    private final java.util.Set<String> sqlCategories = java.util.concurrent.ConcurrentHashMap.newKeySet();

    TestSource(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection delegate = DriverManager.getConnection(url, user, password);
      AtomicBoolean committed = new AtomicBoolean();
      AtomicInteger facts = new AtomicInteger();
      return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
          (proxy, method, args) -> {
            if (method.getName().equals("commit")) {
              if (fault == Fault.BEFORE_COMMIT) throw synthetic("08006", 0);
              delegate.commit();
              committed.set(true);
              if (fault == Fault.AFTER_COMMIT) throw synthetic("08006", 0);
              return null;
            }
            if (method.getName().equals("rollback") && fault == Fault.ROLLBACK) {
              delegate.rollback();
              throw synthetic("08006", 0);
            }
            if (method.getName().equals("close") && committed.get() && fault == Fault.CLOSE_AFTER_COMMIT) {
              delegate.close();
              throw synthetic("08006", 0);
            }
            Object result = invoke(method, delegate, args);
            if (method.getName().equals("getMetaData") && fault == Fault.UNKNOWN_ENGINE) {
              return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.sql.DatabaseMetaData.class},
                  (ignored, metadataMethod, metadataArgs) -> metadataMethod.getName().equals("getDatabaseProductVersion")
                      ? "99.0.0" : invoke(metadataMethod, result, metadataArgs));
            }
            if (method.getName().equals("prepareStatement") && fault == Fault.READ_ONLY
                && args[0] instanceof String sql && (sql.contains("pg_is_in_recovery") || sql.contains("@@read_only"))) {
              return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                  (ignored, statementMethod, statementArgs) -> {
                    Object queryResult = invoke(statementMethod, result, statementArgs);
                    if (statementMethod.getName().equals("executeQuery")) {
                      return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.sql.ResultSet.class},
                          (ignoredRows, rowMethod, rowArgs) -> rowMethod.getName().equals("getBoolean")
                              && Integer.valueOf(1).equals(rowArgs[0]) ? true : invoke(rowMethod, queryResult, rowArgs));
                    }
                    return queryResult;
                  });
            }
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.stripLeading().startsWith("INSERT")) {
              PreparedStatement statement = (PreparedStatement) result;
              return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                  (ignored, statementMethod, statementArgs) -> {
                    if (statementMethod.getName().equals("executeUpdate")) {
                      if (fault == Fault.NOT_A_DUPLICATE || fault == Fault.ROLLBACK) throw synthetic("23503", 1452);
                      if (fault == Fault.SECOND_FACT && sql.contains("tp_business_operation_facts") && facts.incrementAndGet() == 2) {
                        throw synthetic("HY000", 1644);
                      }
                    }
                    return invoke(statementMethod, statement, statementArgs);
                  });
            }
            return result;
          });
    }

    private static SQLException synthetic(String state, int vendor) {
      return new SQLException("synthetic-sensitive-database-error", state, vendor);
    }

    private Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException failure) {
        if (failure.getCause() instanceof SQLException sql) {
          String state = sql.getSQLState();
          sqlCategories.add((state != null && state.matches("[A-Z0-9]{5}") ? state : "OTHER")
              + "/" + sql.getErrorCode());
        }
        throw failure.getCause();
      }
    }

    @Override public Connection getConnection(String username, String suppliedPassword) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
  }
}
