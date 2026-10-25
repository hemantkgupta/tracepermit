package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationReviewSettings;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.application.BusinessOperationWithdrawal;
import io.tracepermit.application.TrustedRequesterContext;
import io.tracepermit.execution.TenantPartition;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
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
  void storedGenerationIsCheckedIndependentlyOfTheUnchangedProfileHash() throws Exception {
    var profile = ready();
    try (Connection connection = source.getConnection(); var statement = connection.prepareStatement(
        "UPDATE tp_operation_authority_controls SET recovery_generation = 2 WHERE scope_hash = ?")) {
      statement.setString(1, SCOPE.fingerprint());
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    try (Connection connection = source.getConnection(); var statement = connection.prepareStatement(
        "SELECT profile_hash, recovery_generation FROM tp_operation_authority_controls WHERE scope_hash = ?")) {
      statement.setString(1, SCOPE.fingerprint());
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(profile.fingerprint());
        assertThat(rows.getLong(2)).isEqualTo(2);
      }
    }
    assertGuard(profile, OPERATION_PROFILE_MISMATCH);
    assertThat(count("tp_business_operations")).isZero();
    assertThat(count("tp_operation_lifecycle_facts")).isZero();
  }

  @Test
  void thirtyTwoConcurrentActivationsAcrossFourDatasourcesHaveOneAcknowledgedWinner() throws Exception {
    var profile = profile(SCOPE, 1, "original");
    OperationAuthorityAdministration.stageNew(source, profile);
    var owners = new ArrayList<TestSource>();
    for (int index = 0; index < 4; index++) {
      owners.add(new TestSource(database, database.getUsername(), database.getPassword()));
    }
    var arrived = new CountDownLatch(32);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(32);
    var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
    try {
      for (int index = 0; index < 32; index++) {
        var owner = owners.get(index % owners.size());
        results.add(pool.submit(() -> {
          arrived.countDown();
          if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("synthetic start timeout");
          try {
            OperationAuthorityAdministration.activateStaged(owner, profile);
            return true;
          } catch (BusinessOperationStorageException rejected) {
            assertThat(rejected.code()).isEqualTo(OPERATION_STATE_STALE);
            return false;
          }
        }));
      }
      assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      start.countDown();
      int winners = 0;
      for (var result : results) {
        if (result.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) winners++;
      }
      assertThat(winners).isEqualTo(1);
      try (Connection connection = writer(runtime)) {
        OperationAuthorityControl.requireCurrent(connection, profile);
        connection.rollback();
      }
      assertThat(count("tp_operation_authority_controls")).isEqualTo(1);
      assertThat(count("tp_business_operations")).isZero();
      assertThat(count("tp_operation_lifecycle_facts")).isZero();
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
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
        "ALTER TABLE tp_operation_authority_controls ADD COLUMN forbidden INTEGER"}) {
      try (Connection connection = runtime.getConnection(); var statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class);
      }
    }
    var unused = profile(new BusinessOperationScope(SCOPE.tenantId(), "unused-workspace",
        SCOPE.requesterPrincipalId()), 1, "unused");
    // A duplicate key must not masquerade as INSERT privilege denial. Prove these exact values
    // are insertable with the owner, roll them back, and then require an actual ACL rejection.
    try (Connection connection = writer(source)) {
      assertThat(insertControl(connection, unused)).isEqualTo(1);
      connection.rollback();
    }
    assertThat(count("tp_operation_authority_controls")).isEqualTo(1);
    try (Connection connection = writer(runtime)) {
      assertThatThrownBy(() -> insertControl(connection, unused))
          .isInstanceOfSatisfying(SQLException.class, failure -> {
            assertThat(failure.getSQLState()).isEqualTo(postgres() ? "42501" : "42000");
            if (!postgres()) assertThat(failure.getErrorCode()).isEqualTo(1142);
          });
      connection.rollback();
    }
    assertCode(() -> OperationAuthorityAdministration.quarantine(runtime, profile), OPERATION_STORAGE_UNAVAILABLE);
    assertCode(() -> OperationAuthorityAdministration.stageNew(runtime, unused), OPERATION_STORAGE_UNAVAILABLE);
    assertCode(() -> OperationAuthorityAdministration.activateStaged(runtime, profile), OPERATION_STORAGE_UNAVAILABLE);
    assertCode(() -> OperationAuthorityAdministration.stageReplacement(runtime, profile,
        profile(SCOPE, 2, "new")), OPERATION_STORAGE_UNAVAILABLE);
    assertThat(count("tp_operation_authority_controls")).isEqualTo(1);
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
  void physicalReadinessRejectsRenamedControlColumn() throws Exception {
    assertRenamedColumnRejected("tp_operation_authority_controls", "profile_hash");
  }

  @Test
  void physicalReadinessRejectsRenamedLifecycleActorColumn() throws Exception {
    assertRenamedColumnRejected("tp_operation_lifecycle_facts", "actor_json");
  }

  private void assertRenamedColumnRejected(String table, String column) throws Exception {
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE " + table + " RENAME COLUMN " + column + " TO fixture_missing_column");
      try {
        assertThatThrownBy(() -> statement.executeQuery("SELECT " + column + " FROM " + table + " WHERE 1 = 0"))
            .isInstanceOf(SQLException.class);
        assertCode(() -> OperationDatabase.validateExisting(source), OPERATION_SCHEMA_NOT_READY);
        assertCode(() -> OperationDatabase.validateAuthorityExisting(source), OPERATION_SCHEMA_NOT_READY);
      } finally {
        statement.execute("ALTER TABLE " + table + " RENAME COLUMN fixture_missing_column TO " + column);
      }
    }
    OperationDatabase.validateExisting(source);
    OperationDatabase.validateAuthorityExisting(source);
  }

  @Test
  void physicalReadinessRejectsRemovedMarkerCheck() throws Exception {
    ready();
    String name;
    try (Connection connection = source.getConnection(); var statement = connection.createStatement();
        var rows = statement.executeQuery(postgres() ? """
          SELECT conname FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
           WHERE n.nspname = current_schema() AND t.relname = 'tp_operation_authority_controls'
             AND c.contype = 'c' AND pg_get_constraintdef(c.oid) LIKE '%lock_marker%'
          """ : """
          SELECT CONSTRAINT_NAME FROM information_schema.check_constraints
           WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tp_operation_authority_controls'
             AND CHECK_CLAUSE LIKE '%lock_marker%'
          """)) {
      assertThat(rows.next()).isTrue();
      name = rows.getString(1);
      assertThat(name).matches("[A-Za-z_][A-Za-z0-9_]*");
      assertThat(rows.next()).isFalse();
    }
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute(postgres() ? "ALTER TABLE tp_operation_authority_controls DROP CONSTRAINT " + name
          : "ALTER TABLE tp_operation_authority_controls MODIFY COLUMN lock_marker SMALLINT NOT NULL DEFAULT 0");
      try {
        try (Connection mutation = writer(source); var update = mutation.createStatement()) {
          assertThat(update.executeUpdate("UPDATE tp_operation_authority_controls SET lock_marker = 1")).isEqualTo(1);
          mutation.rollback();
        }
        assertCode(() -> OperationDatabase.validateExisting(source), OPERATION_SCHEMA_NOT_READY);
        assertCode(() -> OperationDatabase.validateAuthorityExisting(source), OPERATION_SCHEMA_NOT_READY);
      } finally {
        statement.execute(postgres() ? "ALTER TABLE tp_operation_authority_controls ADD CONSTRAINT " + name + " CHECK (lock_marker = 0)"
            : "ALTER TABLE tp_operation_authority_controls MODIFY COLUMN lock_marker SMALLINT NOT NULL DEFAULT 0 CHECK (lock_marker = 0)");
      }
    }
    OperationDatabase.validateAuthorityExisting(source);
  }

  protected void assertTruncateTriggerRejected() throws Exception {
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA tp_fixture_aux");
      try {
        statement.execute("CREATE FUNCTION tp_fixture_aux.noop_trigger() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
        statement.execute("CREATE TRIGGER tp_fixture_truncate AFTER TRUNCATE ON public.tp_operation_authority_controls FOR EACH STATEMENT EXECUTE FUNCTION tp_fixture_aux.noop_trigger()");
        try {
          assertCode(() -> OperationDatabase.validateExisting(source), OPERATION_SCHEMA_NOT_READY);
          assertCode(() -> OperationDatabase.validateAuthorityExisting(source), OPERATION_SCHEMA_NOT_READY);
        } finally {
          statement.execute("DROP TRIGGER tp_fixture_truncate ON public.tp_operation_authority_controls");
          statement.execute("DROP FUNCTION tp_fixture_aux.noop_trigger()");
        }
      } finally {
        statement.execute("DROP SCHEMA tp_fixture_aux");
      }
    }
    OperationDatabase.validateAuthorityExisting(source);
  }

  @Test
  void physicalReadinessRejectsTypeNullabilityAndDefaultDrift() throws Exception {
    String[][] mutations = postgres() ? new String[][] {
        {"ALTER COLUMN profile_hash TYPE VARCHAR(8) COLLATE \"C\"", "ALTER COLUMN profile_hash TYPE VARCHAR(71) COLLATE \"C\""},
        {"ALTER COLUMN control_state DROP NOT NULL", "ALTER COLUMN control_state SET NOT NULL"},
        {"ALTER COLUMN lock_marker SET DEFAULT 1", "ALTER COLUMN lock_marker SET DEFAULT 0"}
    } : new String[][] {
        {"MODIFY COLUMN profile_hash VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL", "MODIFY COLUMN profile_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"},
        {"MODIFY COLUMN profile_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL", "MODIFY COLUMN profile_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"},
        {"MODIFY COLUMN profile_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NULL", "MODIFY COLUMN profile_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL"},
        {"ALTER COLUMN lock_marker SET DEFAULT 1", "ALTER COLUMN lock_marker SET DEFAULT 0"}
    };
    for (String[] mutation : mutations) {
      try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
        statement.execute("ALTER TABLE tp_operation_authority_controls " + mutation[0]);
        try {
          assertCode(() -> OperationDatabase.validateExisting(source), OPERATION_SCHEMA_NOT_READY);
          assertCode(() -> OperationDatabase.validateAuthorityExisting(source), OPERATION_SCHEMA_NOT_READY);
        } finally {
          statement.execute("ALTER TABLE tp_operation_authority_controls " + mutation[1]);
        }
      }
      OperationDatabase.validateAuthorityExisting(source);
    }
  }

  @Test
  void v2PhysicalReadinessRejectsMissingJournalColumnBeforeUpgrade() throws Exception {
    try (var extra = container().withPassword(UUID.randomUUID().toString())) {
      extra.start();
      var v2 = new TestSource(extra, extra.getUsername(), extra.getPassword());
      OperationDatabase.initializeEmpty(v2);
      try (Connection connection = v2.getConnection(); var statement = connection.createStatement()) {
        statement.execute("ALTER TABLE tp_operation_lifecycle_facts RENAME COLUMN actor_json TO missing_actor");
        try {
          assertCode(() -> OperationDatabase.validateExisting(v2), OPERATION_SCHEMA_NOT_READY);
          assertCode(() -> new JdbcBusinessOperationLifecycleRepository(v2), OPERATION_SCHEMA_NOT_READY);
          assertCode(() -> OperationDatabase.upgradeAuthorityFromV2(v2), OPERATION_SCHEMA_NOT_READY);
          try (var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_operation_schema_history WHERE version = '3'")) {
            rows.next();
            assertThat(rows.getInt(1)).isZero();
          }
        } finally {
          statement.execute("ALTER TABLE tp_operation_lifecycle_facts RENAME COLUMN missing_actor TO actor_json");
        }
      }
      OperationDatabase.validateExisting(v2);
    }
  }

  protected void assertDisabledSessionConstraintsRejected() throws Exception {
    for (String setting : new String[] {"check_constraint_checks", "foreign_key_checks"}) {
      try (Connection connection = writer(source); var statement = connection.createStatement()) {
        statement.execute("SET SESSION " + setting + " = 0");
        try {
          assertCode(() -> OperationDatabase.inspectWriter(connection), OPERATION_SCHEMA_NOT_READY);
        } finally {
          statement.execute("SET SESSION " + setting + " = 1");
          connection.rollback();
        }
        OperationDatabase.inspectWriter(connection);
      }
    }
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
      var lifecycle = new JdbcBusinessOperationLifecycleRepository(v2);
      var requester = new TrustedRequesterContext(new TenantPartition(scope.tenantId()), scope.requesterPrincipalId(),
          true, Set.of(BusinessOperationWithdrawal.ROLE), Set.of(BusinessOperationWithdrawal.SCOPE),
          "synthetic-test", HASH, Instant.now().plusSeconds(60));
      var withdrawn = lifecycle.withdraw(new BusinessOperationWithdrawal(scope, initial.path("operationId").asText(),
          new BusinessOperationTransitionPlanner().observe(scope, initial), requester));
      assertThat(withdrawn.path("authorization").path("status").asText()).isEqualTo("cancelled");
      String actorHash;
      try (Connection connection = v2.getConnection(); var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT actor_hash FROM tp_operation_lifecycle_facts")) {
        assertThat(rows.next()).isTrue();
        actorHash = rows.getString(1);
        assertThat(actorHash).isNotEqualTo(HASH);
        assertThat(rows.next()).isFalse();
      }
      try (Connection connection = v2.getConnection(); var statement = connection.prepareStatement(
          "UPDATE tp_operation_lifecycle_facts SET actor_hash = ?")) {
        statement.setString(1, HASH);
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      assertCode(() -> OperationDatabase.upgradeAuthorityFromV2(v2), OPERATION_SCHEMA_NOT_READY);
      OperationDatabase.validateExisting(v2);
      try (Connection connection = v2.getConnection(); var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_operation_schema_history WHERE version = '3'")) {
        rows.next();
        assertThat(rows.getInt(1)).isZero();
      }
      try (Connection connection = v2.getConnection(); var statement = connection.prepareStatement(
          "UPDATE tp_operation_lifecycle_facts SET actor_hash = ?")) {
        statement.setString(1, actorHash);
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      OperationDatabase.upgradeAuthorityFromV2(v2);
      OperationDatabase.validateExisting(v2);
      var reopened = new JdbcBusinessOperationRepository(v2).find(scope, initial.path("operationId").asText()).orElseThrow();
      assertThat(CanonicalJson.canonicalize(reopened)).isEqualTo(CanonicalJson.canonicalize(initial));
      var current = new JdbcBusinessOperationLifecycleRepository(v2).findCurrent(scope, initial.path("operationId").asText()).orElseThrow();
      assertThat(CanonicalJson.canonicalize(current)).isEqualTo(CanonicalJson.canonicalize(withdrawn));
      try (Connection connection = v2.getConnection(); var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_operation_lifecycle_facts")) {
        rows.next();
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
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

  @Test
  void explicitReviewSettingsAreDurablyPinnedAndReplacementNeedsNewGeneration() throws Exception {
    var settings = new BusinessOperationReviewSettings("synthetic-reviewers", HASH, java.time.Duration.ofMinutes(1));
    var original = profile(SCOPE, 1, "original", settings);
    OperationAuthorityAdministration.stageNew(source, original);
    OperationAuthorityAdministration.activateStaged(source, original);
    try (Connection connection = writer(runtime)) {
      OperationAuthorityControl.requireCurrent(connection, original);
      connection.rollback();
    }
    for (var changed : java.util.List.of(
        new BusinessOperationReviewSettings("other-reviewers", HASH, settings.approvalLifetime()),
        new BusinessOperationReviewSettings(settings.reviewerGroupId(), "sha256:" + "b".repeat(64), settings.approvalLifetime()),
        new BusinessOperationReviewSettings(settings.reviewerGroupId(), HASH, settings.approvalLifetime().plusNanos(1)))) {
      var wrong = profile(SCOPE, 1, "original", changed);
      assertThat(wrong.fingerprint()).isNotEqualTo(original.fingerprint());
      assertGuard(wrong, OPERATION_PROFILE_MISMATCH);
      assertCode(() -> OperationAuthorityAdministration.stageReplacement(source, original, wrong), OPERATION_COMMAND_INVALID);
    }
    assertGuard(profile(SCOPE, 1, "original"), OPERATION_PROFILE_MISMATCH);
    var next = profile(SCOPE, 2, "original", new BusinessOperationReviewSettings(
        "other-reviewers", HASH, java.time.Duration.ofMinutes(2)));
    OperationAuthorityAdministration.stageReplacement(source, original, next);
    assertGuard(original, OPERATION_PROFILE_MISMATCH);
    assertGuard(next, OPERATION_AUTHORITY_NOT_READY); // Explicit replacement is inactive.
    OperationAuthorityAdministration.activateStaged(source, next);
    try (Connection connection = writer(runtime)) {
      OperationAuthorityControl.requireCurrent(connection, next);
      connection.rollback();
    }
    for (String table : new String[] {"tp_business_operations", "tp_business_operation_facts",
        "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts"}) assertThat(count(table)).isZero();
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

  private static int insertControl(Connection connection, BusinessOperationAuthorityProfile profile)
      throws SQLException {
    try (var statement = connection.prepareStatement("""
        INSERT INTO tp_operation_authority_controls
          (scope_hash, profile_hash, recovery_generation, control_state) VALUES (?, ?, ?, 'staged')
        """)) {
      statement.setString(1, profile.scope().fingerprint());
      statement.setString(2, profile.fingerprint());
      statement.setLong(3, profile.recoveryGeneration());
      return statement.executeUpdate();
    }
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
    return profile(scope, generation, label, null);
  }

  private static BusinessOperationAuthorityProfile profile(BusinessOperationScope scope, long generation, String label,
      BusinessOperationReviewSettings settings) {
    try {
      ObjectNode policy = (ObjectNode) JsonSupport.MAPPER.readTree("""
          {"schemaVersion":"1.0","id":"synthetic-policy","version":1,"name":"Synthetic",
           "enabled":true,"defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
           "rules":[{"id":"synthetic-rule","priority":1,"description":"Synthetic exact rule",
            "match":{"toolNames":["github.pull_request.create"]},
            "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW","approvalGroup":"synthetic-reviewers"}}]}
          """);
      policy.put("description", label);
      return settings == null ? new BusinessOperationAuthorityProfile(scope, "synthetic-adapter", generation, policy, HASH, HASH)
          : new BusinessOperationAuthorityProfile(scope, "synthetic-adapter", generation, policy, HASH, HASH, settings);
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
