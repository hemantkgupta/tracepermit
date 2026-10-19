package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.policy.PolicyState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real database regressions for lifecycle transaction and first-activation boundaries. */
final class PostgresPolicyLifecycleBoundaryTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
  private PostgresPolicyLifecycleStore store;

  @BeforeAll static void start() { POSTGRES.start(); }
  @AfterAll static void stop() { POSTGRES.stop(); }

  @BeforeEach void reset() throws SQLException {
    store = new PostgresPolicyLifecycleStore(source());
    try (var connection = source().getConnection(); var statement = connection.createStatement()) {
      statement.execute("TRUNCATE tp_approval_policy_bindings, tp_policy_lifecycle_transitions, tp_active_policies, tp_policy_versions");
    }
    store.stage("ws", policy(1), NOW);
    store.stage("ws", policy(2), NOW);
  }

  @Test void concurrentFirstActivationsLeaveOneActiveRevisionAndConnectedHistory() throws Exception {
    var fault = new FaultSource();
    var replica = new PostgresPolicyLifecycleStore(fault.source);
    fault.mode.set(Mode.PAUSE_FIRST_ACTIVE_READ);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> replica.activate("ws", "pol-boundary", 1, null, NOW, null, "first"));
      assertThat(fault.readCaptured.await(10, TimeUnit.SECONDS)).isTrue();
      var second = executor.submit(() -> replica.activate("ws", "pol-boundary", 2, null, NOW.plusSeconds(1), null, "second"));
      try {
        second.get(300, TimeUnit.MILLISECONDS);
      } catch (TimeoutException expectedWhileFirstHoldsLock) {
        // A correctly serialized second transition waits until the first is released.
      } finally {
        fault.releaseRead.countDown();
      }
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      fault.releaseRead.countDown();
    }
    assertThat(store.listVersions("ws", "pol-boundary").stream().filter(v -> v.isActive()).count()).isOne();
    var history = store.activationHistory("ws", "pol-boundary");
    assertThat(history).hasSize(2);
    assertThat(history.getLast().fromVersion()).isEqualTo(history.getFirst().toVersion());
    assertThat(store.activeVersion("ws", "pol-boundary").orElseThrow().version())
        .isEqualTo(history.getLast().toVersion());
  }

  @Test void uncheckedActivationFailureRollsBackBeforeLeaseReleaseCanCommit() {
    activateOne();
    var fault = new FaultSource();
    var failing = new PostgresPolicyLifecycleStore(fault.source, java.time.Clock.systemUTC(), () -> {
      throw new IllegalStateException("synthetic transition id failure");
    });
    fault.mode.set(Mode.COMMIT_ON_CLOSE);
    assertThatThrownBy(() -> failing.activate("ws", "pol-boundary", 2, 1, NOW, null, "fail"))
        .isInstanceOf(IllegalStateException.class).hasMessage("synthetic transition id failure");
    assertUnchangedAtOne();
  }

  @Test void uncheckedRollbackFailureRollsBackBeforeLeaseReleaseCanCommit() {
    activateOne();
    store.activate("ws", "pol-boundary", 2, 1, NOW, null, "second");
    var fault = new FaultSource();
    var failing = new PostgresPolicyLifecycleStore(fault.source, java.time.Clock.systemUTC(), () -> {
      throw new IllegalStateException("synthetic transition id failure");
    });
    fault.mode.set(Mode.COMMIT_ON_CLOSE);
    assertThatThrownBy(() -> failing.rollback("ws", "pol-boundary", 2, null, NOW, null, "fail"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.activeVersion("ws", "pol-boundary").orElseThrow().version()).isEqualTo(2);
    assertThat(store.getVersion("ws", "pol-boundary", 2).orElseThrow().state()).isEqualTo(PolicyState.ACTIVE);
    assertThat(store.activationHistory("ws", "pol-boundary")).hasSize(2);
    assertThat(store.rollbackHistory("ws", "pol-boundary")).isEmpty();
  }

  @Test void idempotentActivationDoesNotAcquireANestedConnection() {
    activateOne();
    var fault = new FaultSource();
    var replica = new PostgresPolicyLifecycleStore(fault.source);
    fault.mode.set(Mode.ONE_LEASE);
    assertThat(replica.activate("ws", "pol-boundary", 1, 1, NOW, null, "same").isActive()).isTrue();
    assertThat(store.activationHistory("ws", "pol-boundary")).hasSize(1);
  }

  @Test void reactivationReturnsTheSameMetadataThatWasPersisted() {
    activateOne();
    store.activate("ws", "pol-boundary", 2, 1, NOW.plusSeconds(1), null, "second");
    var activated = store.activate("ws", "pol-boundary", 1, 2, NOW.plusSeconds(2), null, "restore");
    assertThat(activated).isEqualTo(store.getVersion("ws", "pol-boundary", 1).orElseThrow());
    assertThat(activated.deprecatedAt()).isNull();
  }

  @Test void failedCommitPreservesOriginalFailureAndSuppressesRollbackFailure() {
    activateOne();
    var fault = new FaultSource();
    var replica = new PostgresPolicyLifecycleStore(fault.source);
    fault.mode.set(Mode.COMMIT_AND_ROLLBACK_FAILURE);
    assertThatThrownBy(() -> replica.activate("ws", "pol-boundary", 2, 1, NOW, null, "fail"))
        .isInstanceOf(PostgresStateException.class).satisfies(failure -> {
          assertThat(failure.getCause()).hasMessage("synthetic commit failure");
          assertThat(failure.getCause().getSuppressed()).hasSize(1);
        });
    assertUnchangedAtOne();
  }

  @Test void lostCommitAcknowledgementIsFailureButDurableHistoryIsRetained() {
    activateOne();
    var fault = new FaultSource();
    var replica = new PostgresPolicyLifecycleStore(fault.source);
    fault.mode.set(Mode.AFTER_COMMIT);
    assertThatThrownBy(() -> replica.activate("ws", "pol-boundary", 2, 1, NOW, null, "uncertain"))
        .isInstanceOf(PostgresStateException.class);
    assertThat(store.activeVersion("ws", "pol-boundary").orElseThrow().version()).isEqualTo(2);
    assertThat(store.activationHistory("ws", "pol-boundary")).hasSize(2);
  }

  @Test void writesRestoreTheOriginalAutoCommitModeOnSuccess() {
    var fault = new FaultSource();
    var replica = new PostgresPolicyLifecycleStore(fault.source);
    fault.mode.set(Mode.CHECK_AUTO_COMMIT);
    replica.activate("ws", "pol-boundary", 1, null, NOW, null, "first");
    assertThat(fault.autoCommitAtClose.get()).isTrue();
  }

  @Test void manualTransactionLeasesCommitStagingActivationAndRollback() {
    var fault = new FaultSource();
    var replica = new PostgresPolicyLifecycleStore(fault.source);
    fault.mode.set(Mode.MANUAL);
    replica.stage("ws", policy(3), NOW);
    assertThat(store.getVersion("ws", "pol-boundary", 3)).isPresent();
    replica.activate("ws", "pol-boundary", 1, null, NOW, null, "first");
    replica.activate("ws", "pol-boundary", 2, 1, NOW, null, "second");
    replica.rollback("ws", "pol-boundary", 2, null, NOW, null, "restore");
    assertThat(store.activeVersion("ws", "pol-boundary").orElseThrow().version()).isOne();
    assertThat(store.activationHistory("ws", "pol-boundary")).hasSize(3);
    assertThat(fault.autoCommitAtClose.get()).isFalse();
  }

  @Test void failedStagingCommitDoesNotLeaveARevisionAndLostAcknowledgementDoesNotReportSuccess() {
    var fault = new FaultSource();
    var replica = new PostgresPolicyLifecycleStore(fault.source);
    fault.mode.set(Mode.BEFORE_COMMIT);
    assertThatThrownBy(() -> replica.stage("ws", policy(3), NOW)).isInstanceOf(PostgresStateException.class);
    assertThat(store.getVersion("ws", "pol-boundary", 3)).isEmpty();
    fault.mode.set(Mode.AFTER_COMMIT);
    assertThatThrownBy(() -> replica.stage("ws", policy(3), NOW)).isInstanceOf(PostgresStateException.class);
    assertThat(store.getVersion("ws", "pol-boundary", 3)).isPresent();
    assertThat(store.activationHistory("ws", "pol-boundary")).isEmpty();
  }

  @Test void failedRollbackCommitAndCloseHaveExplicitDurableOutcomes() {
    activateOne();
    store.activate("ws", "pol-boundary", 2, 1, NOW, null, "second");
    var fault = new FaultSource();
    var replica = new PostgresPolicyLifecycleStore(fault.source);
    fault.mode.set(Mode.BEFORE_COMMIT);
    assertThatThrownBy(() -> replica.rollback("ws", "pol-boundary", 2, null, NOW, null, "fail"))
        .isInstanceOf(PostgresStateException.class);
    assertThat(store.activeVersion("ws", "pol-boundary").orElseThrow().version()).isEqualTo(2);
    assertThat(store.activationHistory("ws", "pol-boundary")).hasSize(2);
    fault.mode.set(Mode.CLOSE_FAILURE);
    assertThatThrownBy(() -> replica.rollback("ws", "pol-boundary", 2, null, NOW, null, "uncertain"))
        .isInstanceOf(PostgresStateException.class);
    assertThat(store.activeVersion("ws", "pol-boundary").orElseThrow().version()).isOne();
    assertThat(store.activationHistory("ws", "pol-boundary")).hasSize(3);
  }

  @Test void concurrentRestagingIsIdempotentAndConflictingContentCannotReplace() throws Exception {
    try (var executor = Executors.newFixedThreadPool(8)) {
      var futures = java.util.stream.IntStream.range(0, 16).mapToObj(index ->
          executor.submit(() -> store.stage("ws", policy(3), NOW))).toList();
      String hash = futures.getFirst().get(10, TimeUnit.SECONDS).contentHash();
      for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS).contentHash()).isEqualTo(hash);
      var changed = policy(3).put("description", "different immutable document");
      assertThatThrownBy(() -> store.stage("ws", changed, NOW)).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("POLICY_VERSION_IMMUTABLE");
      assertThat(store.getVersion("ws", "pol-boundary", 3).orElseThrow().contentHash()).isEqualTo(hash);
      assertThat(store.listVersions("ws", "pol-boundary")).hasSize(3);
      assertThat(store.activationHistory("ws", "pol-boundary")).isEmpty();
    }
  }

  @Test void workspaceMutationsAndLifecycleMetadataRemainIsolated() {
    activateOne();
    var other = policy(1).put("description", "other workspace document");
    store.stage("other", other, NOW);
    store.activate("other", "pol-boundary", 1, null, NOW, null, "other");
    assertThatThrownBy(() -> store.activate("other", "pol-boundary", 2, 1, NOW, null, "missing"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.rollback("other", "pol-boundary", 1, null, NOW, null, "missing"))
        .isInstanceOf(IllegalStateException.class);
    assertUnchangedAtOne();
    assertThat(store.activationHistory("other", "pol-boundary")).hasSize(1);
    store.activate("ws", "pol-boundary", 2, 1, NOW, null, "second");
    store.rollback("ws", "pol-boundary", 2, null, NOW, null, "restore");
    store.rollback("ws", "pol-boundary", 1, null, NOW, null, "toggle");
    var reactivated = store.activeVersion("ws", "pol-boundary").orElseThrow();
    assertThat(reactivated.version()).isEqualTo(2);
    assertThat(reactivated.rolledBackAt()).isNull();
    assertThat(reactivated.deprecatedAt()).isNull();
    assertThat(store.activeVersion("other", "pol-boundary").orElseThrow().document()).isEqualTo(other);
  }

  @Test void revisionMetadataAndTransitionHistoryRejectOrdinarySqlMutation() throws SQLException {
    activateOne();
    try (var connection = source().getConnection(); var statement = connection.createStatement()) {
      for (String column : new String[] {"name", "description"}) {
        assertThatThrownBy(() -> statement.executeUpdate("UPDATE tp_policy_versions SET " + column
            + " = 'changed' WHERE workspace_id = 'ws'" )).isInstanceOf(SQLException.class);
      }
      assertThatThrownBy(() -> statement.executeUpdate("UPDATE tp_policy_lifecycle_transitions SET reason = 'changed'"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM tp_policy_lifecycle_transitions"))
          .isInstanceOf(SQLException.class);
    }
    assertThat(store.activationHistory("ws", "pol-boundary")).hasSize(1);
  }

  @Test void migrationUpgradesV6WithoutDiscardingExistingClaims() throws SQLException {
    var upgrade = source();
    try (var connection = source().getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA upgrade_fixture");
    }
    upgrade.setCurrentSchema("upgrade_fixture");
    org.flywaydb.core.Flyway.configure().dataSource(upgrade)
        .locations(PostgresDurableEnforcementState.MIGRATION_LOCATION).target("6").load().migrate();
    try (var connection = upgrade.getConnection(); var statement = connection.createStatement()) {
      statement.execute("""
          INSERT INTO tp_consumed_execution_permits VALUES
          ('ws', 'permit-before-upgrade', CURRENT_TIMESTAMP + INTERVAL '1 minute',
           'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', CURRENT_TIMESTAMP)
          """);
    }
    var upgraded = new PostgresPolicyLifecycleStore(upgrade);
    upgraded.stage("ws", policy(1), NOW);
    assertThat(new PostgresPermitConsumptionStore(upgrade).isConsumed("ws", "permit-before-upgrade")).isTrue();
    try (var connection = upgrade.getConnection(); var statement = connection.createStatement();
         var rows = statement.executeQuery("SELECT contract_version FROM tp_enforcement_schema_guard")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(PostgresDatabaseLifecycle.CURRENT_VERSION);
    }
  }

  @Test void unknownOrMissingSchemaGuardRejectsThePolicyStoreAtStartup() throws SQLException {
    var incompatible = source();
    try (var connection = source().getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA incompatible_policy_fixture");
    }
    incompatible.setCurrentSchema("incompatible_policy_fixture");
    PostgresDurableEnforcementState.migrate(incompatible, PostgresDurableEnforcementState.MIGRATION_LOCATION);
    try (var connection = incompatible.getConnection(); var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE tp_enforcement_schema_guard DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check");
      statement.execute("UPDATE tp_enforcement_schema_guard SET contract_version = 999");
      assertThatThrownBy(() -> new PostgresPolicyLifecycleStore(incompatible))
          .isInstanceOf(IllegalStateException.class).hasMessage("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
      statement.execute("DELETE FROM tp_enforcement_schema_guard");
      assertThatThrownBy(() -> new PostgresPolicyLifecycleStore(incompatible))
          .isInstanceOf(IllegalStateException.class).hasMessage("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
    }
  }

  private void activateOne() { store.activate("ws", "pol-boundary", 1, null, NOW, null, "first"); }

  private void assertUnchangedAtOne() {
    assertThat(store.activeVersion("ws", "pol-boundary").orElseThrow().version()).isOne();
    assertThat(store.getVersion("ws", "pol-boundary", 1).orElseThrow().isActive()).isTrue();
    assertThat(store.getVersion("ws", "pol-boundary", 2).orElseThrow().isStaged()).isTrue();
    assertThat(store.activationHistory("ws", "pol-boundary")).hasSize(1);
  }

  private static ObjectNode policy(int version) {
    var document = (ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    document.put("id", "pol-boundary").put("version", version);
    return document;
  }

  private enum Mode { NONE, PAUSE_FIRST_ACTIVE_READ, COMMIT_ON_CLOSE, ONE_LEASE,
    COMMIT_AND_ROLLBACK_FAILURE, AFTER_COMMIT, CHECK_AUTO_COMMIT, MANUAL, BEFORE_COMMIT, CLOSE_FAILURE }

  private static final class FaultSource {
    final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NONE);
    final AtomicBoolean paused = new AtomicBoolean();
    final AtomicBoolean autoCommitAtClose = new AtomicBoolean();
    final AtomicInteger leases = new AtomicInteger();
    final CountDownLatch readCaptured = new CountDownLatch(1);
    final CountDownLatch releaseRead = new CountDownLatch(1);
    final DataSource delegate = source();
    final DataSource source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
        new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
          if (!method.getName().equals("getConnection") || mode.get() == Mode.NONE) {
            return invoke(delegate, method, args);
          }
          if (mode.get() == Mode.ONE_LEASE && leases.get() > 0) throw new SQLException("nested lease");
          var connection = (Connection) invoke(delegate, method, args);
          if (mode.get() == Mode.MANUAL) connection.setAutoCommit(false);
          leases.incrementAndGet();
          return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
              (cp, cm, ca) -> {
                String name = cm.getName();
                if (name.equals("commit") && (mode.get() == Mode.COMMIT_AND_ROLLBACK_FAILURE
                    || mode.get() == Mode.BEFORE_COMMIT)) {
                  throw new SQLException("synthetic commit failure");
                }
                if (name.equals("rollback") && mode.get() == Mode.COMMIT_AND_ROLLBACK_FAILURE) {
                  throw new SQLException("synthetic rollback failure");
                }
                if (name.equals("close")) {
                  if (mode.get() == Mode.COMMIT_ON_CLOSE) connection.setAutoCommit(true);
                  autoCommitAtClose.set(connection.getAutoCommit());
                  leases.decrementAndGet();
                }
                Object result = invoke(connection, cm, ca);
                if (name.equals("commit") && mode.get() == Mode.AFTER_COMMIT) {
                  throw new SQLException("synthetic lost commit acknowledgement");
                }
                if (name.equals("close") && mode.get() == Mode.CLOSE_FAILURE) {
                  throw new SQLException("synthetic close failure");
                }
                if (name.equals("prepareStatement") && mode.get() == Mode.PAUSE_FIRST_ACTIVE_READ
                    && ((String) ca[0]).startsWith("SELECT active_version") && paused.compareAndSet(false, true)) {
                  var statement = (PreparedStatement) result;
                  return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                      new Class<?>[] {PreparedStatement.class}, (sp, sm, sa) -> {
                        Object value = invoke(statement, sm, sa);
                        if (sm.getName().equals("executeQuery")) {
                          readCaptured.countDown();
                          if (!releaseRead.await(10, TimeUnit.SECONDS)) throw new SQLException("test release timeout");
                        }
                        return value;
                      });
                }
                return result;
              });
        });
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
    try { return method.invoke(target, args); }
    catch (InvocationTargetException failure) { throw failure.getCause(); }
  }

  private static PGSimpleDataSource source() {
    var source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }
}
