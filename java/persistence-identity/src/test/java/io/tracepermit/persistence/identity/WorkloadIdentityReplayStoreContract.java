package io.tracepermit.persistence.identity;

import static io.tracepermit.persistence.identity.WorkloadIdentityReplayException.Code.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
abstract class WorkloadIdentityReplayStoreContract {
  private static final Instant T = Instant.parse("2030-01-01T00:00:00Z");
  private static final String TENANT = "tenant:acme";
  private static final String ISSUER = "tracepermit-scheduler:primary";

  private JdbcDatabaseContainer<?> database;
  private TestSource source;
  private TestSource runtimeSource;
  private JdbcWorkloadIdentityReplayStore store;

  abstract JdbcDatabaseContainer<?> container();

  @BeforeAll
  void start() {
    database = container().withPassword(UUID.randomUUID().toString());
    database.start();
    source = new TestSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    assertCode(() -> new JdbcWorkloadIdentityReplayStore(source), REPLAY_SCHEMA_NOT_READY);
    WorkloadIdentityReplayDatabase.initializeEmpty(source);
    assertCode(() -> WorkloadIdentityReplayDatabase.initializeEmpty(source), REPLAY_SCHEMA_NOT_READY);
    provisionRuntimeUser();
  }

  @AfterAll
  void stop() {
    if (database != null) {
      database.stop();
    }
  }

  @BeforeEach
  void reset() throws SQLException {
    source.fault = Fault.NONE;
    grantRuntimePermissions();
    execute("DELETE FROM tp_workload_replay_claims");
    execute("UPDATE tp_workload_replay_state SET total_claims = 0 WHERE singleton = 1");
    store = new JdbcWorkloadIdentityReplayStore(source);
  }

  // --------------------------------------------------------------------------------------
  // 1. First consume and replay refusal
  // --------------------------------------------------------------------------------------
  @Test
  void firstConsumeSucceedsAndSubsequentReplayFails() {
    assertThat(store.consume(TENANT, ISSUER, "token-1", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, ISSUER, "token-1", T.plusSeconds(300), T)).isFalse();
    assertThat(store.totalClaims()).isEqualTo(1);
    assertThat(store.isClaimed(TENANT, ISSUER, "token-1")).isTrue();
    assertThat(store.isClaimed(TENANT, ISSUER, "token-2")).isFalse();
  }

  // --------------------------------------------------------------------------------------
  // 2. Independent tenant and issuer separation
  // --------------------------------------------------------------------------------------
  @Test
  void tenantAndIssuerNamespaceSeparatesIdenticalToken() {
    assertThat(store.consume("tenant:alpha", ISSUER, "token-common", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenant:beta", ISSUER, "token-common", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, "issuer:one", "token-common", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, "issuer:two", "token-common", T.plusSeconds(300), T)).isTrue();

    // Replay of each is refused
    assertThat(store.consume("tenant:alpha", ISSUER, "token-common", T.plusSeconds(300), T)).isFalse();
    assertThat(store.consume("tenant:beta", ISSUER, "token-common", T.plusSeconds(300), T)).isFalse();
    assertThat(store.consume(TENANT, "issuer:one", "token-common", T.plusSeconds(300), T)).isFalse();
    assertThat(store.consume(TENANT, "issuer:two", "token-common", T.plusSeconds(300), T)).isFalse();

    assertThat(store.totalClaims()).isEqualTo(4);
  }

  // --------------------------------------------------------------------------------------
  // 3. Delimiter, case, trailing space, and hash substitution controls
  // --------------------------------------------------------------------------------------
  @Test
  void delimiterInjectionDoesNotCauseCollision() {
    assertThat(store.consume("t\u0000i", "i", "token-x", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("t", "i\u0000x", "token-pad", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("t", "i", "token-x\u0000pad", T.plusSeconds(300), T)).isTrue();
    assertThat(store.totalClaims()).isEqualTo(3);
  }

  @Test
  void caseSensitivityIsEnforced() {
    assertThat(store.consume("tenant:case", ISSUER, "token-case", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("TENANT:CASE", ISSUER, "token-case", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenant:case", ISSUER, "TOKEN-CASE", T.plusSeconds(300), T)).isTrue();
    assertThat(store.totalClaims()).isEqualTo(3);
  }

  @Test
  void trailingSpaceDoesNotCollideOrNormalize() {
    assertThat(store.consume("tenant:acme", ISSUER, "token-space", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenant:acme ", ISSUER, "token-space", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenant:acme", ISSUER + " ", "token-space", T.plusSeconds(300), T)).isTrue();
    assertThat(store.totalClaims()).isEqualTo(3);
  }

  // --------------------------------------------------------------------------------------
  // 4. Strict expiry and equality boundary
  // --------------------------------------------------------------------------------------
  @Test
  void expiredOfferAndExactEqualityBoundaryFailClosedWritingNothing() {
    // Exact equality boundary (now == expiresAt) is expired: fails closed and writes nothing
    assertThat(store.consume(TENANT, ISSUER, "token-equal", T, T)).isFalse();
    assertThat(store.totalClaims()).isZero();
    assertThat(store.isClaimed(TENANT, ISSUER, "token-equal")).isFalse();

    // Past expiry (now > expiresAt): fails closed and writes nothing
    assertThat(store.consume(TENANT, ISSUER, "token-past", T.minusSeconds(1), T)).isFalse();
    assertThat(store.totalClaims()).isZero();
    assertThat(store.isClaimed(TENANT, ISSUER, "token-past")).isFalse();

    // Valid future deadline succeeds
    assertThat(store.consume(TENANT, ISSUER, "token-future", T.plusSeconds(1), T)).isTrue();
    assertThat(store.totalClaims()).isEqualTo(1);
    assertThat(store.isClaimed(TENANT, ISSUER, "token-future")).isTrue();
  }

  // --------------------------------------------------------------------------------------
  // 5. No expiry refresh on replay
  // --------------------------------------------------------------------------------------
  @Test
  void duplicateWithLaterExpiryDoesNotRefreshOriginalClaim() {
    Instant originalExpiry = T.plusSeconds(60);
    assertThat(store.consume(TENANT, ISSUER, "token-no-refresh", originalExpiry, T)).isTrue();

    // Replay with a much later requested expiry returns false
    Instant laterRequested = T.plusSeconds(3600);
    assertThat(store.consume(TENANT, ISSUER, "token-no-refresh", laterRequested, T.plusSeconds(10))).isFalse();

    // Stored expiry remains the original expiry
    var storedExpiry = store.findClaimExpiry(TENANT, ISSUER, "token-no-refresh");
    assertThat(storedExpiry).isPresent();
    assertThat(storedExpiry.get().getEpochSecond()).isEqualTo(originalExpiry.getEpochSecond());
    assertThat(store.totalClaims()).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------------
  // 6. Reconstruction across store instances
  // --------------------------------------------------------------------------------------
  @Test
  void reconstructionPreservesDurableReplayRejection() {
    assertThat(store.consume(TENANT, ISSUER, "token-durable", T.plusSeconds(300), T)).isTrue();

    // Reconstruct brand new store instance
    var freshStore = new JdbcWorkloadIdentityReplayStore(source);
    assertThat(freshStore.consume(TENANT, ISSUER, "token-durable", T.plusSeconds(300), T)).isFalse();
    assertThat(freshStore.isClaimed(TENANT, ISSUER, "token-durable")).isTrue();
  }

  // --------------------------------------------------------------------------------------
  // 7. Genuinely concurrent independent store instances
  // --------------------------------------------------------------------------------------
  @Test
  void concurrentStoreInstancesRacingSameTokenProduceExactlyOneWinner() throws Exception {
    int workers = 16;
    List<JdbcWorkloadIdentityReplayStore> instances = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      instances.add(new JdbcWorkloadIdentityReplayStore(source));
    }

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    List<Future<Boolean>> futures = new ArrayList<>();

    for (int i = 0; i < workers; i++) {
      final int index = i;
      futures.add(executor.submit(() -> {
        if (!start.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("race start gate timed out");
        }
        return instances.get(index).consume(TENANT, ISSUER, "token-race", T.plusSeconds(300), T);
      }));
    }

    start.countDown();
    int winners = 0;
    for (Future<Boolean> future : futures) {
      if (future.get(10, TimeUnit.SECONDS)) {
        winners++;
      }
    }
    executor.shutdownNow();

    assertThat(winners).isEqualTo(1);
    assertThat(store.totalClaims()).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------------
  // 8. Finite capacity and fail-closed saturation
  // --------------------------------------------------------------------------------------
  @Test
  void finiteCapacitySaturatesAndRefusesWithoutEvictingLiveEntries() {
    var boundedStore = new JdbcWorkloadIdentityReplayStore(source, 3);

    assertThat(boundedStore.consume(TENANT, ISSUER, "cap-1", T.plusSeconds(300), T)).isTrue();
    assertThat(boundedStore.consume(TENANT, ISSUER, "cap-2", T.plusSeconds(300), T)).isTrue();
    assertThat(boundedStore.consume(TENANT, ISSUER, "cap-3", T.plusSeconds(300), T)).isTrue();
    assertThat(boundedStore.totalClaims()).isEqualTo(3);

    // 4th claim exceeds capacity: fails closed
    assertThat(boundedStore.consume(TENANT, ISSUER, "cap-4", T.plusSeconds(300), T)).isFalse();
    assertThat(boundedStore.totalClaims()).isEqualTo(3);
    assertThat(boundedStore.isClaimed(TENANT, ISSUER, "cap-4")).isFalse();

    // No eviction: all three original live entries remain claimed and rejected
    assertThat(boundedStore.consume(TENANT, ISSUER, "cap-1", T.plusSeconds(300), T)).isFalse();
    assertThat(boundedStore.consume(TENANT, ISSUER, "cap-2", T.plusSeconds(300), T)).isFalse();
    assertThat(boundedStore.consume(TENANT, ISSUER, "cap-3", T.plusSeconds(300), T)).isFalse();
    assertThat(boundedStore.totalClaims()).isEqualTo(3);
  }

  // --------------------------------------------------------------------------------------
  // 9. Atomic counter and claim rollback
  // --------------------------------------------------------------------------------------
  @Test
  void claimRollbackRestoresStateAtomically() {
    source.fault = Fault.ROLLBACK;
    assertCode(() -> store.consume(TENANT, ISSUER, "token-fail", T.plusSeconds(300), T), REPLAY_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;

    assertThat(store.totalClaims()).isZero();
    assertThat(store.isClaimed(TENANT, ISSUER, "token-fail")).isFalse();

    // After fault is cleared, the token can be consumed normally
    assertThat(store.consume(TENANT, ISSUER, "token-fail", T.plusSeconds(300), T)).isTrue();
    assertThat(store.totalClaims()).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------------
  // 10. Before/after-commit and close failures
  // --------------------------------------------------------------------------------------
  @Test
  void beforeCommitFailureFailsClosedWithoutWriting() {
    source.fault = Fault.BEFORE_COMMIT;
    assertCode(() -> store.consume(TENANT, ISSUER, "token-commit-fail", T.plusSeconds(300), T), REPLAY_COMMIT_UNKNOWN);
    source.fault = Fault.NONE;

    assertThat(store.totalClaims()).isZero();
    assertThat(store.isClaimed(TENANT, ISSUER, "token-commit-fail")).isFalse();
  }

  @Test
  void closeAfterCommitThrowsCommitUnknown() {
    source.fault = Fault.CLOSE_AFTER_COMMIT;
    assertCode(() -> store.consume(TENANT, ISSUER, "token-close-fail", T.plusSeconds(300), T), REPLAY_COMMIT_UNKNOWN);
    source.fault = Fault.NONE;

    // Row was committed, so duplicate attempt is rejected
    assertThat(store.consume(TENANT, ISSUER, "token-close-fail", T.plusSeconds(300), T)).isFalse();
  }

  // --------------------------------------------------------------------------------------
  // 11. Restricted runtime privileges and permission denial
  // --------------------------------------------------------------------------------------
  @Test
  void restrictedRuntimeUserOperatesNormallyThenFailsOnPermissionRevocation() throws SQLException {
    var runtimeStore = new JdbcWorkloadIdentityReplayStore(runtimeSource);
    assertThat(runtimeStore.consume(TENANT, ISSUER, "token-runtime-1", T.plusSeconds(300), T)).isTrue();
    assertThat(runtimeStore.consume(TENANT, ISSUER, "token-runtime-1", T.plusSeconds(300), T)).isFalse();

    revokeRuntimePermissions();
    assertCode(() -> runtimeStore.consume(TENANT, ISSUER, "token-runtime-2", T.plusSeconds(300), T),
        REPLAY_STORAGE_PERMISSION_DENIED);
  }

  // --------------------------------------------------------------------------------------
  // 12. Duplicate versus unrelated constraint errors
  // --------------------------------------------------------------------------------------
  @Test
  void unrelatedConstraintViolationThrowsStorageUnavailableRatherThanReplayRefusal() throws SQLException {
    // Normal duplicate returns false
    assertThat(store.consume(TENANT, ISSUER, "token-dup", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, ISSUER, "token-dup", T.plusSeconds(300), T)).isFalse();

    // Fault that simulates an unrelated constraint violation (e.g. foreign key or check violation)
    source.fault = Fault.UNRELATED_CONSTRAINT;
    assertCode(() -> store.consume(TENANT, ISSUER, "token-unrelated", T.plusSeconds(300), T), REPLAY_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;
  }

  // --------------------------------------------------------------------------------------
  // 13. Malformed schema and version validation
  // --------------------------------------------------------------------------------------
  @Test
  void corruptOrExtraTableFailsSchemaValidation() throws SQLException {
    execute("CREATE TABLE tp_extra_corrupt_table (id INT PRIMARY KEY)");
    try {
      assertCode(() -> WorkloadIdentityReplayDatabase.validateExisting(source), REPLAY_SCHEMA_NOT_READY);
      assertCode(() -> new JdbcWorkloadIdentityReplayStore(source), REPLAY_SCHEMA_NOT_READY);
    } finally {
      execute("DROP TABLE tp_extra_corrupt_table");
    }
  }

  // --------------------------------------------------------------------------------------
  // 14. Fixed diagnostics with secret canaries
  // --------------------------------------------------------------------------------------
  @Test
  void secretCanariesAndCausesAreNeverLeakedInExceptions() {
    String canaryToken = "canary-secret-token-xyz-12345";
    source.fault = Fault.BEFORE_COMMIT;

    assertThatThrownBy(() -> store.consume(TENANT, ISSUER, canaryToken, T.plusSeconds(300), T))
        .isInstanceOf(WorkloadIdentityReplayException.class)
        .hasMessageNotContaining(canaryToken)
        .hasMessageNotContaining("synthetic-sensitive-database-error")
        .hasNoCause();

    source.fault = Fault.NONE;
  }

  // ======================================================================================
  // Helpers and Fixtures
  // ======================================================================================

  private void execute(String sql) throws SQLException {
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(sql);
    }
  }

  private void provisionRuntimeUser() {
    boolean postgres = database.getJdbcUrl().startsWith("jdbc:postgresql:");
    String password = UUID.randomUUID().toString();
    TestSource owner = postgres ? source : new TestSource(database.getJdbcUrl(), "root", database.getPassword());
    try (Connection connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      if (postgres) {
        statement.execute("CREATE ROLE tp_identity_runtime LOGIN PASSWORD '" + password + "'");
        statement.execute("GRANT USAGE ON SCHEMA public TO tp_identity_runtime");
        statement.execute("GRANT SELECT, INSERT ON tp_workload_replay_claims TO tp_identity_runtime");
        statement.execute("GRANT SELECT, UPDATE ON tp_workload_replay_state TO tp_identity_runtime");
        statement.execute("GRANT SELECT ON tp_identity_replay_schema_history TO tp_identity_runtime");
      } else {
        statement.execute("CREATE USER 'tp_identity_runtime'@'%' IDENTIFIED BY '" + password + "'");
        String databaseName = database.getDatabaseName();
        statement.execute("GRANT SELECT, INSERT ON `" + databaseName + "`.tp_workload_replay_claims TO 'tp_identity_runtime'@'%'");
        statement.execute("GRANT SELECT, UPDATE ON `" + databaseName + "`.tp_workload_replay_state TO 'tp_identity_runtime'@'%'");
        statement.execute("GRANT SELECT ON `" + databaseName + "`.tp_identity_replay_schema_history TO 'tp_identity_runtime'@'%'");
      }
      runtimeSource = new TestSource(database.getJdbcUrl(), "tp_identity_runtime", password);
    } catch (SQLException failure) {
      throw new AssertionError("runtime user provisioning failed", failure);
    }
  }

  private void grantRuntimePermissions() throws SQLException {
    if (runtimeSource == null) return;
    boolean postgres = database.getJdbcUrl().startsWith("jdbc:postgresql:");
    TestSource owner = postgres ? source : new TestSource(database.getJdbcUrl(), "root", database.getPassword());
    try (Connection connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      if (postgres) {
        statement.execute("GRANT SELECT, INSERT ON tp_workload_replay_claims TO tp_identity_runtime");
        statement.execute("GRANT SELECT, UPDATE ON tp_workload_replay_state TO tp_identity_runtime");
      } else {
        String name = database.getDatabaseName();
        statement.execute("GRANT SELECT, INSERT ON `" + name + "`.tp_workload_replay_claims TO 'tp_identity_runtime'@'%'");
        statement.execute("GRANT SELECT, UPDATE ON `" + name + "`.tp_workload_replay_state TO 'tp_identity_runtime'@'%'");
      }
    }
  }

  private void revokeRuntimePermissions() throws SQLException {
    boolean postgres = database.getJdbcUrl().startsWith("jdbc:postgresql:");
    TestSource owner = postgres ? source : new TestSource(database.getJdbcUrl(), "root", database.getPassword());
    try (Connection connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      if (postgres) {
        statement.execute("REVOKE INSERT ON tp_workload_replay_claims FROM tp_identity_runtime");
      } else {
        String name = database.getDatabaseName();
        statement.execute("REVOKE INSERT ON `" + name + "`.tp_workload_replay_claims FROM 'tp_identity_runtime'@'%'");
      }
    }
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      WorkloadIdentityReplayException.Code code) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(WorkloadIdentityReplayException.class,
            failure -> assertThat(failure.code()).isEqualTo(code));
  }

  private enum Fault {
    NONE,
    BEFORE_COMMIT,
    AFTER_COMMIT,
    CLOSE_AFTER_COMMIT,
    ROLLBACK,
    UNRELATED_CONSTRAINT
  }

  static final class TestSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;
    private volatile Fault fault = Fault.NONE;

    TestSource(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection delegate = DriverManager.getConnection(url, user, password);
      AtomicBoolean committed = new AtomicBoolean();
      return (Connection) Proxy.newProxyInstance(
          getClass().getClassLoader(),
          new Class<?>[] {Connection.class},
          (proxy, method, args) -> {
            if (method.getName().equals("commit")) {
              if (fault == Fault.BEFORE_COMMIT) {
                throw synthetic("08006", 0);
              }
              delegate.commit();
              committed.set(true);
              if (fault == Fault.AFTER_COMMIT) {
                throw synthetic("08006", 0);
              }
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
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
              PreparedStatement statement = (PreparedStatement) result;
              return Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {PreparedStatement.class},
                  (ignored, statementMethod, statementArgs) -> {
                    if (statementMethod.getName().equals("executeUpdate")) {
                      if ((fault == Fault.UNRELATED_CONSTRAINT || fault == Fault.ROLLBACK)
                          && sql.contains("INSERT INTO tp_workload_replay_claims")) {
                        throw synthetic("23514", 4025);
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
        throw failure.getCause();
      }
    }

    @Override public Connection getConnection(String u, String p) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) {}
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
  }
}
