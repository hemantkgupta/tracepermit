package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.BusinessOperationValidator;
import java.nio.file.Path;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.JdbcDatabaseContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class OperationAuthorityControlContract {
  private static final BusinessOperationScope SCOPE = new BusinessOperationScope(
      "synthetic-tenant", "synthetic-workspace", "principal:synthetic-requester");
  private static final String HASH = "sha256:" + "1".repeat(64);
  private JdbcDatabaseContainer<?> database;
  private TestSource source;
  private TestSource runtime;

  abstract JdbcDatabaseContainer<?> container();

  @BeforeAll
  void start() throws Exception {
    database = container().withPassword(UUID.randomUUID().toString());
    database.start();
    source = new TestSource(database, database.getUsername(), database.getPassword());
    OperationDatabase.initializeAuthorityEmpty(source);
    OperationDatabase.validateExisting(source);
    String password = UUID.randomUUID().toString();
    boolean pg = postgres();
    TestSource owner = pg ? source : new TestSource(database, "root", database.getPassword());
    try (Connection connection = owner.getConnection(); var statement = connection.createStatement()) {
      if (pg) {
        statement.execute("CREATE USER tp_authority_runtime PASSWORD '" + password + "'");
        statement.execute("GRANT USAGE ON SCHEMA public TO tp_authority_runtime");
        statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO tp_authority_runtime");
        statement.execute("GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO tp_authority_runtime");
      } else {
        statement.execute("CREATE USER 'tp_authority_runtime'@'%' IDENTIFIED BY '" + password + "'");
        statement.execute("GRANT SELECT ON `" + database.getDatabaseName() + "`.* TO 'tp_authority_runtime'@'%'");
      }
    }
    runtime = new TestSource(database, "tp_authority_runtime", password);
  }

  @AfterAll
  void stop() {
    if (database != null) database.stop();
  }

  @BeforeEach
  void reset() throws SQLException {
    source.fault = "none";
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM tp_operation_authority_controls");
    }
  }

  @Test
  void absentStagedReadyAndReconstructedGuardNeverGrantAnExecutionClaim() throws Exception {
    var profile = profile(SCOPE, 1, "original");
    assertGuard(profile, OPERATION_AUTHORITY_NOT_READY);
    OperationAuthorityAdministration.stageNew(source, profile);
    assertGuard(profile, OPERATION_AUTHORITY_NOT_READY);
    OperationAuthorityAdministration.activateStaged(source, profile);
    try (Connection connection = writer(runtime)) {
      OperationAuthorityControl.requireCurrent(connection, profile);
      connection.rollback();
    }
    try (Connection connection = writer(runtime)) {
      OperationAuthorityControl.requireCurrent(connection, profile(SCOPE, 1, "original"));
      connection.rollback();
    }
    assertThat(count("tp_business_operations")).isZero();
    assertThat(count("tp_operation_lifecycle_facts")).isZero();
    assertThat(count("tp_operation_authority_controls")).isEqualTo(1);
  }

  @Test
  void exactProfileScopeAndGenerationMustMatchEvenAfterValidRehashing() throws Exception {
    var profile = ready();
    assertGuard(profile(SCOPE, 2, "original"), OPERATION_PROFILE_MISMATCH);
    assertGuard(profile(SCOPE, 1, "changed"), OPERATION_PROFILE_MISMATCH);
    assertGuard(profile(new BusinessOperationScope(SCOPE.tenantId(), "other", SCOPE.requesterPrincipalId()),
        1, "original"), OPERATION_AUTHORITY_NOT_READY);
    assertGuard(profile(new BusinessOperationScope("other", SCOPE.workspaceId(), SCOPE.requesterPrincipalId()),
        1, "original"), OPERATION_AUTHORITY_NOT_READY);
    assertGuard(profile(new BusinessOperationScope(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:other"),
        1, "original"), OPERATION_AUTHORITY_NOT_READY);
    assertCode(() -> OperationAuthorityAdministration.stageNew(source, profile), OPERATION_STORAGE_UNAVAILABLE);
  }

  @Test
  void quarantinedGenerationCannotBeReactivatedAndReplacementMustAdvance() throws Exception {
    var old = ready();
    OperationAuthorityAdministration.quarantine(source, old);
    assertGuard(old, OPERATION_AUTHORITY_NOT_READY);
    assertCode(() -> OperationAuthorityAdministration.activateStaged(source, old), OPERATION_STATE_STALE);
    assertCode(() -> OperationAuthorityAdministration.stageReplacement(source, old,
        profile(SCOPE, 1, "changed")), OPERATION_COMMAND_INVALID);
    assertCode(() -> OperationAuthorityAdministration.stageReplacement(source, old,
        profile(new BusinessOperationScope("other", "other", "principal:other"), 2, "changed")),
        OPERATION_COMMAND_INVALID);
    var next = profile(SCOPE, 2, "changed");
    OperationAuthorityAdministration.stageReplacement(source, old, next);
    assertGuard(next, OPERATION_AUTHORITY_NOT_READY);
    assertGuard(old, OPERATION_PROFILE_MISMATCH);
    OperationAuthorityAdministration.activateStaged(source, next);
    try (Connection connection = writer(runtime)) {
      OperationAuthorityControl.requireCurrent(connection, next);
      connection.rollback();
    }
    assertCode(() -> OperationAuthorityAdministration.activateStaged(source, old), OPERATION_STATE_STALE);
    assertCode(() -> OperationAuthorityAdministration.quarantine(source, old), OPERATION_STATE_STALE);
    assertCode(() -> OperationAuthorityAdministration.stageReplacement(source, old,
        profile(SCOPE, 3, "other")), OPERATION_STATE_STALE);
  }

  @Test
  void readyPolicyReplacementAlsoStartsInactiveAndCannotReuseTheOldGeneration() throws Exception {
    var old = ready();
    var next = profile(SCOPE, 2, "changed");
    OperationAuthorityAdministration.stageReplacement(source, old, next);
    assertGuard(old, OPERATION_PROFILE_MISMATCH);
    assertGuard(next, OPERATION_AUTHORITY_NOT_READY);
    assertCode(() -> OperationAuthorityAdministration.stageReplacement(source, next, old), OPERATION_COMMAND_INVALID);
    OperationAuthorityAdministration.activateStaged(source, next);
    try (Connection connection = writer(runtime)) {
      OperationAuthorityControl.requireCurrent(connection, next);
      connection.rollback();
    }
  }

  @Test
  void autocommitOtherIsolationReadOnlyAndNullAreNeverAProtectedTransaction() throws Exception {
    var profile = ready();
    assertCode(() -> OperationAuthorityControl.requireCurrent(null, profile), OPERATION_COMMAND_INVALID);
    try (Connection connection = runtime.getConnection()) {
      assertCode(() -> OperationAuthorityControl.requireCurrent(connection, profile), OPERATION_COMMAND_INVALID);
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      assertCode(() -> OperationAuthorityControl.requireCurrent(connection, profile), OPERATION_COMMAND_INVALID);
      connection.rollback();
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setReadOnly(true);
      assertCode(() -> OperationAuthorityControl.requireCurrent(connection, profile), OPERATION_COMMAND_INVALID);
      connection.rollback();
    }
    try (Connection connection = writer(runtime)) {
      assertCode(() -> OperationAuthorityControl.requireCurrent(connection, null), OPERATION_COMMAND_INVALID);
    }
  }

  @Test
  void runtimeCanLockButCannotEnrollActivateReplaceQuarantineDeleteOrChangeAuthorityColumns() throws Exception {
    var profile = ready();
    try (Connection connection = writer(runtime)) {
      OperationAuthorityControl.requireCurrent(connection, profile);
      connection.rollback();
    }
    for (String sql : new String[] {
        "UPDATE tp_operation_authority_controls SET control_state = 'staged'",
        "UPDATE tp_operation_authority_controls SET recovery_generation = 2",
        "UPDATE tp_operation_authority_controls SET profile_hash = 'synthetic'",
        "DELETE FROM tp_operation_authority_controls",
        "ALTER TABLE tp_operation_authority_controls ADD COLUMN forbidden INTEGER",
        "INSERT INTO tp_operation_authority_controls SELECT * FROM tp_operation_authority_controls"}) {
      try (Connection connection = runtime.getConnection(); var statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class);
      }
    }
    assertCode(() -> OperationAuthorityAdministration.quarantine(runtime, profile), OPERATION_STORAGE_UNAVAILABLE);
    assertCode(() -> OperationAuthorityAdministration.stageNew(runtime, profile), OPERATION_STORAGE_UNAVAILABLE);
    assertCode(() -> OperationAuthorityAdministration.activateStaged(runtime, profile), OPERATION_STORAGE_UNAVAILABLE);
    assertCode(() -> OperationAuthorityAdministration.stageReplacement(runtime, profile,
        profile(SCOPE, 2, "new")), OPERATION_STORAGE_UNAVAILABLE);
    if (postgres()) {
      try (Connection connection = runtime.getConnection(); var statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.executeUpdate("UPDATE tp_operation_authority_controls SET lock_marker = 1"))
            .isInstanceOf(SQLException.class);
      }
    }
  }

  @Test
  void callerOwnedTransactionRetainsControlLockUntilItEnds() throws Exception {
    var profile = ready();
    var attempted = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    try (Connection holder = writer(runtime)) {
      OperationAuthorityControl.requireCurrent(holder, profile);
      source.lockAttempt = attempted;
      var result = pool.submit(() -> {
        OperationAuthorityAdministration.quarantine(source, profile);
      });
      assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(100);
      assertThat(result.isDone()).isFalse();
      holder.commit();
      result.get(10, TimeUnit.SECONDS);
      assertGuard(profile, OPERATION_AUTHORITY_NOT_READY);
    } finally {
      source.lockAttempt = null;
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void waitingGuardRechecksTheCommittedReplacementNotAnEarlierProfileRead() throws Exception {
    var old = ready();
    var next = profile(SCOPE, 2, "changed");
    var reached = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    try (Connection holder = writer(source)) {
      try (var statement = holder.prepareStatement("""
          UPDATE tp_operation_authority_controls SET profile_hash = ?, recovery_generation = 2,
            control_state = 'staged' WHERE scope_hash = ?
          """)) {
        statement.setString(1, next.fingerprint());
        statement.setString(2, SCOPE.fingerprint());
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      runtime.lockAttempt = reached;
      var result = pool.submit(() -> {
        assertGuard(old, OPERATION_PROFILE_MISMATCH);
        return null;
      });
      assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(result.isDone()).isFalse();
      holder.commit();
      result.get(10, TimeUnit.SECONDS);
    } finally {
      runtime.lockAttempt = null;
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void lostCommitAndCleanupAcknowledgementsAreUnknownEvenWhenTheControlChanged() throws Exception {
    for (String fault : new String[] {"commit", "close"}) {
      var profile = profile(SCOPE, fault.equals("commit") ? 1 : 2, fault);
      source.fault = fault;
      assertCode(() -> OperationAuthorityAdministration.stageNew(source, profile), OPERATION_COMMIT_UNKNOWN);
      source.fault = "none";
      assertGuard(profile, OPERATION_AUTHORITY_NOT_READY);
      try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM tp_operation_authority_controls");
      }
      OperationAuthorityAdministration.stageNew(source, profile);
      source.fault = fault;
      assertCode(() -> OperationAuthorityAdministration.activateStaged(source, profile), OPERATION_COMMIT_UNKNOWN);
      source.fault = "none";
      // Uncertainty is not rollback: activation committed, but the caller received no success.
      // A later control guard is still NOT approval/admission or authority to dispatch.
      try (Connection connection = writer(runtime)) {
        OperationAuthorityControl.requireCurrent(connection, profile);
        connection.rollback();
      }
      try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM tp_operation_authority_controls");
      }
    }
  }

  @Test
  void statementAndRollbackFaultsHaveFixedCodesWithoutRetainedSensitiveDiagnostics() throws Exception {
    for (String fault : new String[] {"write", "rollback"}) {
      source.fault = fault;
      assertCode(() -> OperationAuthorityAdministration.stageNew(source, profile(SCOPE, 1, "original")),
          OPERATION_STORAGE_UNAVAILABLE);
      source.fault = "none";
      assertThat(count("tp_operation_authority_controls")).isZero();
    }
  }

  @Test
  void schemaDetectionDoesNotExposeUncheckedDatasourceDiagnostics() throws Exception {
    var profile = profile(SCOPE, 1, "original");
    source.fault = "connect";
    assertCode(() -> OperationDatabase.validateExisting(source), OPERATION_SCHEMA_NOT_READY);
    assertCode(() -> OperationAuthorityAdministration.stageNew(source, profile), OPERATION_SCHEMA_NOT_READY);
    source.fault = "none";
    assertThat(count("tp_operation_authority_controls")).isZero();
  }

  @Test
  void v2IsNotAutomaticallyUpgradedAndExplicitUpgradePreservesItsMigrationHistory() throws Exception {
    try (var extra = container().withPassword(UUID.randomUUID().toString())) {
      extra.start();
      var v2 = new TestSource(extra, extra.getUsername(), extra.getPassword());
      OperationDatabase.initializeEmpty(v2);
      ObjectNode initial = initialOperation();
      var context = initial.path("trustedContext");
      var scope = new BusinessOperationScope(context.path("tenantId").asText(), context.path("workspaceId").asText(),
          context.path("requesterPrincipalId").asText());
      var repository = new JdbcBusinessOperationRepository(v2);
      repository.createOrGet(scope, initial);
      assertCode(() -> OperationAuthorityAdministration.stageNew(v2, profile(SCOPE, 1, "original")),
          OPERATION_SCHEMA_NOT_READY);
      try (Connection connection = v2.getConnection(); var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_operation_schema_history WHERE version = '3'")) {
        rows.next();
        assertThat(rows.getInt(1)).isZero();
      }
      try (Connection connection = v2.getConnection(); var statement = connection.prepareStatement(
          "UPDATE tp_operation_lifecycle_heads SET snapshot_json = ? WHERE operation_id = ?")) {
        statement.setString(1, "{");
        statement.setString(2, initial.path("operationId").asText());
        statement.executeUpdate();
      }
      assertCode(() -> OperationDatabase.upgradeAuthorityFromV2(v2), OPERATION_SCHEMA_NOT_READY);
      try (Connection connection = v2.getConnection(); var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_operation_schema_history WHERE version = '3'")) {
        rows.next();
        assertThat(rows.getInt(1)).isZero();
      }
      try (Connection connection = v2.getConnection(); var statement = connection.prepareStatement(
          "UPDATE tp_operation_lifecycle_heads SET snapshot_json = ? WHERE operation_id = ?")) {
        statement.setString(1, CanonicalJson.canonicalize(initial));
        statement.setString(2, initial.path("operationId").asText());
        statement.executeUpdate();
      }
      OperationDatabase.upgradeAuthorityFromV2(v2);
      OperationDatabase.validateExisting(v2);
      var reopened = new JdbcBusinessOperationRepository(v2).find(scope, initial.path("operationId").asText()).orElseThrow();
      assertThat(CanonicalJson.canonicalize(reopened)).isEqualTo(CanonicalJson.canonicalize(initial));
      var current = new JdbcBusinessOperationLifecycleRepository(v2).findCurrent(scope, initial.path("operationId").asText()).orElseThrow();
      assertThat(CanonicalJson.canonicalize(current)).isEqualTo(CanonicalJson.canonicalize(initial));
      OperationAuthorityAdministration.stageNew(v2, profile(SCOPE, 1, "original"));
      assertCode(() -> OperationDatabase.upgradeAuthorityFromV2(v2), OPERATION_SCHEMA_NOT_READY);
    }
  }

  private static ObjectNode initialOperation() {
    ObjectNode operation = (ObjectNode) JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot"))
        .resolve("contracts/vectors/business-operation-v1.json")).path("operation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) operation.path("transitions")).remove(3);
    ((ObjectNode) operation.path("authorization")).put("status", "evaluating").put("version", 1)
        .put("updatedAt", operation.path("createdAt").asText());
    operation.put("updatedAt", operation.path("transitions").get(2).path("at").asText());
    return (ObjectNode) BusinessOperationValidator.validate(operation);
  }

  private BusinessOperationAuthorityProfile ready() {
    var profile = profile(SCOPE, 1, "original");
    OperationAuthorityAdministration.stageNew(source, profile);
    OperationAuthorityAdministration.activateStaged(source, profile);
    return profile;
  }

  private void assertGuard(BusinessOperationAuthorityProfile profile, BusinessOperationStorageException.Code code)
      throws Exception {
    try (Connection connection = writer(runtime)) {
      assertCode(() -> OperationAuthorityControl.requireCurrent(connection, profile), code);
      connection.rollback();
    }
  }

  private static Connection writer(DataSource source) throws SQLException {
    Connection connection = source.getConnection();
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    connection.setAutoCommit(false);
    return connection;
  }

  private int count(String table) throws SQLException {
    try (Connection connection = source.getConnection(); var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private boolean postgres() {
    return database.getDockerImageName().startsWith("postgres:");
  }

  private static BusinessOperationAuthorityProfile profile(BusinessOperationScope scope, long generation, String label) {
    try {
      ObjectNode policy = (ObjectNode) JsonSupport.MAPPER.readTree("""
          {"schemaVersion":"1.0","id":"synthetic-policy","version":1,"name":"Synthetic",
           "enabled":true,"defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
           "rules":[{"id":"synthetic-rule","priority":1,"description":"Synthetic exact rule",
            "match":{"toolNames":["github.pull_request.create"]},
            "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW","approvalGroup":"synthetic-reviewers"}}]}
          """);
      policy.put("description", label);
      return new BusinessOperationAuthorityProfile(scope, "synthetic-adapter", generation, policy, HASH, HASH);
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      BusinessOperationStorageException.Code code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationStorageException.class, failure -> {
      assertThat(failure.code()).isEqualTo(code);
      assertThat(failure.getSuppressed()).isEmpty();
    }).hasMessage(code.name()).hasNoCause();
  }

  private static final class TestSource implements DataSource {
    private final JdbcDatabaseContainer<?> database;
    private final String user;
    private final String password;
    private volatile String fault = "none";
    private volatile CountDownLatch lockAttempt;

    private TestSource(JdbcDatabaseContainer<?> database, String user, String password) {
      this.database = database;
      this.user = user;
      this.password = password;
    }

    @Override public Connection getConnection() throws SQLException {
      if (fault.equals("connect")) throw new IllegalStateException("synthetic-sensitive-canary");
      Connection delegate = DriverManager.getConnection(database.getJdbcUrl(), user, password);
      var committed = new AtomicBoolean();
      var mutating = new AtomicBoolean();
      return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
          (proxy, method, args) -> {
            if (method.getName().equals("rollback") && fault.equals("rollback")) throw synthetic();
            Object result;
            try {
              result = method.invoke(delegate, args);
            } catch (InvocationTargetException failure) {
              throw failure.getCause();
            }
            if (method.getName().equals("commit") && mutating.get()) {
              committed.set(true);
              if (fault.equals("commit")) throw synthetic();
            }
            if (method.getName().equals("close") && committed.get() && fault.equals("close")) throw synthetic();
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
              return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.sql.PreparedStatement.class},
                  (ignored, query, values) -> {
                    if (query.getName().equals("executeQuery") && sql.contains("FOR UPDATE") && lockAttempt != null) {
                      lockAttempt.countDown();
                    }
                    if (query.getName().equals("executeUpdate")
                        && sql.contains("UPDATE tp_operation_authority_controls") && lockAttempt != null) {
                      lockAttempt.countDown();
                    }
                    boolean write = query.getName().equals("executeUpdate") && sql.contains("tp_operation_authority_controls");
                    if (write && (fault.equals("write") || fault.equals("rollback"))) {
                      throw synthetic();
                    }
                    try {
                      Object response = query.invoke(result, values);
                      if (write && Integer.valueOf(1).equals(response)) mutating.set(true);
                      return response;
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
            }
            return result;
          });
    }

    private static SQLException synthetic() {
      return new SQLException("synthetic-sensitive-canary", "HY000", 1644);
    }

    @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
  }
}
