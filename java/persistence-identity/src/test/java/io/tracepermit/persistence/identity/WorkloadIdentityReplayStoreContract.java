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
    source.dmlCanary = null;
    source.closeCanary = null;
    grantRuntimePermissions();
    execute("DELETE FROM tp_workload_replay_claims");
    execute("UPDATE tp_workload_replay_state SET total_claims = 0, capacity = "
        + WorkloadIdentityReplayDatabase.DEFAULT_CAPACITY + " WHERE singleton = 1");
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
  // 3. Delimiter injection and boundary collision safety
  // --------------------------------------------------------------------------------------
  @Test
  void delimiterInjectionDoesNotCauseCollision() {
    // Two distinct tuple pairs that would collide under naive concatenation
    assertThat(store.consume("tenantA", "issuerB", "tokenC", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenantA:issuerB", "x", "tokenC", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenantA", "issuerB:tokenC", "x", T.plusSeconds(300), T)).isTrue();

    // Semicolon/colon variations
    assertThat(store.consume("tenant;1", "issuer", "token", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenant", "1;issuer", "token", T.plusSeconds(300), T)).isTrue();

    assertThat(store.totalClaims()).isEqualTo(5);
  }

  // --------------------------------------------------------------------------------------
  // 4. Exact case sensitivity and trailing space preservation
  // --------------------------------------------------------------------------------------
  @Test
  void caseSensitivityIsPreservedWithoutFolding() {
    assertThat(store.consume("TENANT", ISSUER, "token-case", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenant", ISSUER, "token-case", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, "ISSUER", "token-case", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, ISSUER, "TOKEN-CASE", T.plusSeconds(300), T)).isTrue();

    assertThat(store.totalClaims()).isEqualTo(4);
  }

  @Test
  void trailingSpaceIsPreservedWithoutTrimming() {
    assertThat(store.consume("tenant ", ISSUER, "token-space", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume("tenant", ISSUER, "token-space", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, "issuer ", "token-space", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, ISSUER, "token-space ", T.plusSeconds(300), T)).isTrue();

    assertThat(store.totalClaims()).isEqualTo(4);
  }

  // --------------------------------------------------------------------------------------
  // 5. Strict expiry and boundary equality
  // --------------------------------------------------------------------------------------
  @Test
  void expiredAndExactBoundarySubmissionsFailClosedWithoutWriting() {
    // expired: expiresAt < now
    assertThat(store.consume(TENANT, ISSUER, "token-expired", T.minusSeconds(1), T)).isFalse();

    // exact boundary: expiresAt == now
    assertThat(store.consume(TENANT, ISSUER, "token-boundary", T, T)).isFalse();

    assertThat(store.totalClaims()).isZero();
    assertThat(store.isClaimed(TENANT, ISSUER, "token-expired")).isFalse();
    assertThat(store.isClaimed(TENANT, ISSUER, "token-boundary")).isFalse();
  }

  // --------------------------------------------------------------------------------------
  // 6. No expiry refresh on replay attempt
  // --------------------------------------------------------------------------------------
  @Test
  void duplicateSubmissionNeverRefreshesExpiry() {
    Instant originalExpiry = T.plusSeconds(100);
    Instant extendedExpiry = T.plusSeconds(500);

    assertThat(store.consume(TENANT, ISSUER, "token-fixed-expiry", originalExpiry, T)).isTrue();
    assertThat(store.findClaimExpiry(TENANT, ISSUER, "token-fixed-expiry")).contains(originalExpiry);

    // Replay attempt with later expiry date is rejected and does not overwrite existing expiry
    assertThat(store.consume(TENANT, ISSUER, "token-fixed-expiry", extendedExpiry, T)).isFalse();
    assertThat(store.findClaimExpiry(TENANT, ISSUER, "token-fixed-expiry")).contains(originalExpiry);
    assertThat(store.totalClaims()).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------------
  // 7. Reconstruction durability and concurrent racing stores
  // --------------------------------------------------------------------------------------
  @Test
  void claimsSurviveStoreReconstruction() {
    assertThat(store.consume(TENANT, ISSUER, "token-persist", T.plusSeconds(300), T)).isTrue();

    // Construct fresh store instance with agreed capacity
    var freshStore = new JdbcWorkloadIdentityReplayStore(source, WorkloadIdentityReplayDatabase.DEFAULT_CAPACITY);
    assertThat(freshStore.totalClaims()).isEqualTo(1);
    assertThat(freshStore.isClaimed(TENANT, ISSUER, "token-persist")).isTrue();
    assertThat(freshStore.consume(TENANT, ISSUER, "token-persist", T.plusSeconds(300), T)).isFalse();
  }

  @Test
  void concurrentRacingStoresSucceedExactlyOnce() throws Exception {
    int threadCount = 16;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    List<Future<Boolean>> futures = new ArrayList<>();
    List<JdbcWorkloadIdentityReplayStore> instances = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      instances.add(new JdbcWorkloadIdentityReplayStore(source, WorkloadIdentityReplayDatabase.DEFAULT_CAPACITY));
    }

    String raceToken = "racing-token-" + UUID.randomUUID();
    for (var instance : instances) {
      futures.add(executor.submit(() -> {
        startLatch.await();
        return instance.consume(TENANT, ISSUER, raceToken, T.plusSeconds(300), T);
      }));
    }

    startLatch.countDown();
    int winners = 0;
    for (var future : futures) {
      if (future.get(10, TimeUnit.SECONDS)) {
        winners++;
      }
    }
    executor.shutdown();

    assertThat(winners).isEqualTo(1);
    assertThat(store.totalClaims()).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------------
  // 8. Finite capacity and fail-closed saturation
  // --------------------------------------------------------------------------------------
  @Test
  void finiteCapacitySaturatesAndRefusesWithoutEvictingLiveEntries() throws SQLException {
    execute("UPDATE tp_workload_replay_state SET capacity = 3 WHERE singleton = 1");
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

  @Test
  void independentFinalSlotContentionWith16StoresAndDistinctTokens() throws Exception {
    execute("UPDATE tp_workload_replay_state SET capacity = 1 WHERE singleton = 1");
    int threadCount = 16;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    List<Future<Boolean>> futures = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      String token = "final-slot-token-" + i;
      var storeInstance = new JdbcWorkloadIdentityReplayStore(source, 1);
      futures.add(executor.submit(() -> {
        startLatch.await();
        return storeInstance.consume(TENANT, ISSUER, token, T.plusSeconds(300), T);
      }));
    }

    startLatch.countDown();
    int winners = 0;
    int losers = 0;
    for (var future : futures) {
      if (future.get(10, TimeUnit.SECONDS)) {
        winners++;
      } else {
        losers++;
      }
    }
    executor.shutdown();

    assertThat(winners).isEqualTo(1);
    assertThat(losers).isEqualTo(15);
    assertThat(store.totalClaims()).isEqualTo(1);
    try (Connection conn = source.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM tp_workload_replay_claims");
         ResultSet rs = ps.executeQuery()) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
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

  @Test
  void rollbackAfterRealSuccessfulInsertFollowedByCounterFailure() throws SQLException {
    source.fault = Fault.COUNTER_FAILURE_AFTER_INSERT;
    assertCode(() -> store.consume(TENANT, ISSUER, "token-counter-fail", T.plusSeconds(300), T), REPLAY_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;

    // Verify claim row is cleanly rolled back and not orphaned in the database
    assertThat(store.totalClaims()).isZero();
    assertThat(store.isClaimed(TENANT, ISSUER, "token-counter-fail")).isFalse();
    try (Connection conn = source.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM tp_workload_replay_claims");
         ResultSet rs = ps.executeQuery()) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isZero();
    }
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
  void afterCommitFailureThrowsCommitUnknown() {
    source.fault = Fault.AFTER_COMMIT;
    assertCode(() -> store.consume(TENANT, ISSUER, "token-after-commit-fail", T.plusSeconds(300), T), REPLAY_COMMIT_UNKNOWN);
    source.fault = Fault.NONE;

    // Row was committed, so duplicate attempt is rejected
    assertThat(store.consume(TENANT, ISSUER, "token-after-commit-fail", T.plusSeconds(300), T)).isFalse();
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
  void unrelatedConstraintViolationThrowsStorageUnavailableRatherThanReplayRefusal() {
    assertThat(store.consume(TENANT, ISSUER, "token-dup", T.plusSeconds(300), T)).isTrue();
    assertThat(store.consume(TENANT, ISSUER, "token-dup", T.plusSeconds(300), T)).isFalse();

    source.fault = Fault.UNRELATED_CONSTRAINT;
    assertCode(() -> store.consume(TENANT, ISSUER, "token-unrelated", T.plusSeconds(300), T), REPLAY_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;
  }

  // --------------------------------------------------------------------------------------
  // 13. Malformed schema, physical DDL negatives, and version validation
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

  @Test
  void physicalSchemaValidationRejectsMissingColumn() throws SQLException {
    String dropSql = isPostgreSql()
        ? "ALTER TABLE tp_workload_replay_claims DROP COLUMN expires_at CASCADE"
        : "ALTER TABLE tp_workload_replay_claims DROP CONSTRAINT tp_workload_replay_claim_time_order, DROP COLUMN expires_at";
    execute(dropSql);
    try {
      assertCode(() -> WorkloadIdentityReplayDatabase.validateExisting(source), REPLAY_SCHEMA_NOT_READY);
      assertCode(() -> new JdbcWorkloadIdentityReplayStore(source), REPLAY_SCHEMA_NOT_READY);
    } finally {
      String restoreSql = isPostgreSql()
          ? "ALTER TABLE tp_workload_replay_claims ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT '2030-01-01 00:00:00+00', ADD CONSTRAINT tp_workload_replay_claim_time_order CHECK (claimed_at < expires_at)"
          : "ALTER TABLE tp_workload_replay_claims ADD COLUMN expires_at DATETIME(6) NOT NULL DEFAULT '2030-01-01 00:00:00', ADD CONSTRAINT tp_workload_replay_claim_time_order CHECK (claimed_at < expires_at)";
      execute(restoreSql);
    }
  }

  // --------------------------------------------------------------------------------------
  // 14. Conflicting capacity rejection and reconstruction
  // --------------------------------------------------------------------------------------
  @Test
  void conflictingCapacityRejectionAndReconstruction() throws SQLException {
    execute("UPDATE tp_workload_replay_state SET capacity = 1 WHERE singleton = 1");
    var store1 = new JdbcWorkloadIdentityReplayStore(source, 1);
    assertThat(store1.consume(TENANT, ISSUER, "token-conf-1", T.plusSeconds(300), T)).isTrue();

    // Store 2 configured with capacity 2 is rejected
    assertCode(() -> new JdbcWorkloadIdentityReplayStore(source, 2), REPLAY_CAPACITY_MISMATCH);

    // Store 3 with agreed capacity 1 constructs and verifies saturation
    var store3 = new JdbcWorkloadIdentityReplayStore(source, 1);
    assertThat(store3.consume(TENANT, ISSUER, "token-conf-2", T.plusSeconds(300), T)).isFalse();
    assertThat(store3.totalClaims()).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------------
  // 15. Real lock wait past expiry fails closed with zero writes
  // --------------------------------------------------------------------------------------
  @Test
  void realLockWaitPastExpiryFailsClosedWithZeroWrites() throws Exception {
    Instant now = Instant.now();
    Instant shortExpiry = now.plusMillis(200);

    Connection conn1 = source.getConnection();
    conn1.setAutoCommit(false);
    try (PreparedStatement ps = conn1.prepareStatement(
        "UPDATE tp_workload_replay_state SET total_claims = total_claims WHERE singleton = 1")) {
      ps.executeUpdate();

      ExecutorService executor = Executors.newSingleThreadExecutor();
      Future<Boolean> consumeFuture = executor.submit(() ->
          store.consume(TENANT, ISSUER, "token-lock-wait-expiry", shortExpiry, now));

      // Wait 400ms while holding the lock, ensuring monotonic time passes shortExpiry
      Thread.sleep(400);
      assertThat(consumeFuture.isDone()).isFalse();

      // Release lock
      conn1.commit();

      Boolean result = consumeFuture.get(5, TimeUnit.SECONDS);
      executor.shutdown();

      assertThat(result).isFalse();
      assertThat(store.totalClaims()).isZero();
      assertThat(store.isClaimed(TENANT, ISSUER, "token-lock-wait-expiry")).isFalse();
    } finally {
      conn1.close();
    }
  }

  // --------------------------------------------------------------------------------------
  // 16. Fixed diagnostics, combined error+cleanup redaction, and no canary leaks
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

  @Test
  void combinedErrorAndCleanupFailureNeverLeaksSuppressedOrCanary() {
    String canaryDml = "canary-dml-secret-password-xyz";
    String canaryClose = "canary-close-secret-token-abc";
    source.dmlCanary = canaryDml;
    source.closeCanary = canaryClose;
    source.fault = Fault.COMBINED_DML_AND_CLOSE_ERROR;

    assertThatThrownBy(() -> store.consume(TENANT, ISSUER, "token-combined-fail", T.plusSeconds(300), T))
        .isInstanceOfSatisfying(WorkloadIdentityReplayException.class, ex -> {
          assertThat(ex.code()).isEqualTo(REPLAY_STORAGE_PERMISSION_DENIED);
          assertThat(ex.getCause()).isNull();
          assertThat(ex.getSuppressed()).isEmpty();
          assertThat(ex.getMessage()).doesNotContain(canaryDml);
          assertThat(ex.getMessage()).doesNotContain(canaryClose);
          assertThat(ex.toString()).doesNotContain(canaryDml);
          assertThat(ex.toString()).doesNotContain(canaryClose);
        });

    source.fault = Fault.NONE;
    source.dmlCanary = null;
    source.closeCanary = null;
  }

  // --------------------------------------------------------------------------------------
  // 17. Explicit upgrade from V1 to V2
  // --------------------------------------------------------------------------------------
  @Test
  void explicitUpgradeFromV1ToV2() throws SQLException {
    makeV1Fixture();
    try {
      assertCode(() -> WorkloadIdentityReplayDatabase.validateExisting(source), REPLAY_SCHEMA_NOT_READY);

      WorkloadIdentityReplayDatabase.upgradeFromV1(source, 5000);
      assertThat(WorkloadIdentityReplayDatabase.readPersistedCapacity(source)).isEqualTo(5000);

      var upgradedStore = new JdbcWorkloadIdentityReplayStore(source, 5000);
      assertThat(upgradedStore.consume(TENANT, ISSUER, "token-upgraded", T.plusSeconds(300), T)).isTrue();
    } finally {
      if (!isCapacityPresent()) {
        try {
          WorkloadIdentityReplayDatabase.upgradeFromV1(source);
        } catch (Exception ignored) {
        }
      }
      execute("UPDATE tp_workload_replay_state SET capacity = "
          + WorkloadIdentityReplayDatabase.DEFAULT_CAPACITY + ", total_claims = 0 WHERE singleton = 1");
      execute("DELETE FROM tp_workload_replay_claims");
    }
  }

  // ======================================================================================
  // Helpers and Fixtures
  // ======================================================================================

  private void makeV1Fixture() throws SQLException {
    execute("ALTER TABLE tp_workload_replay_state DROP COLUMN capacity");
    execute("DELETE FROM tp_identity_replay_schema_history WHERE version = '2'");
  }

  private boolean isCapacityPresent() {
    try (Connection conn = source.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT capacity FROM tp_workload_replay_state WHERE singleton = 1");
         ResultSet rs = ps.executeQuery()) {
      return rs.next();
    } catch (SQLException e) {
      return false;
    }
  }

  private boolean isPostgreSql() {
    return database.getJdbcUrl().startsWith("jdbc:postgresql:");
  }

  private void execute(String sql) throws SQLException {
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(sql);
    }
  }

  private void provisionRuntimeUser() {
    boolean postgres = isPostgreSql();
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
    boolean postgres = isPostgreSql();
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
    boolean postgres = isPostgreSql();
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
    COUNTER_FAILURE_AFTER_INSERT,
    COMBINED_DML_AND_CLOSE_ERROR,
    UNRELATED_CONSTRAINT
  }

  static final class TestSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;
    private volatile Fault fault = Fault.NONE;
    private volatile String dmlCanary;
    private volatile String closeCanary;

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
            if (method.getName().equals("close")) {
              if (committed.get() && fault == Fault.CLOSE_AFTER_COMMIT) {
                delegate.close();
                throw synthetic("08006", 0);
              }
              if (fault == Fault.COMBINED_DML_AND_CLOSE_ERROR) {
                delegate.close();
                String msg = closeCanary != null ? "canary-close-" + closeCanary : "synthetic-close-error";
                throw new SQLException(msg, "08006", 0);
              }
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
                      if (fault == Fault.COUNTER_FAILURE_AFTER_INSERT
                          && sql.contains("UPDATE tp_workload_replay_state")) {
                        throw synthetic("08006", 0);
                      }
                      if (fault == Fault.COMBINED_DML_AND_CLOSE_ERROR) {
                        String msg = dmlCanary != null ? "canary-dml-" + dmlCanary : "synthetic-dml-error";
                        String state = url.startsWith("jdbc:postgresql:") ? "42501" : "42000";
                        int vendor = url.startsWith("jdbc:postgresql:") ? 0 : 1142;
                        throw new SQLException(msg, state, vendor);
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
