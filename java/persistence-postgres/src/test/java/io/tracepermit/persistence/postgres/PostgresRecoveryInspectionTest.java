package io.tracepermit.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ApprovalDecision;
import io.tracepermit.execution.ApprovalExecutionClaim;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.TenantPartition;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

final class PostgresRecoveryInspectionTest {
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
  private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
  private static final TenantPartition TENANT = new TenantPartition("synthetic");
  private PGSimpleDataSource source;
  private PostgresDurableEnforcementState state;

  @BeforeAll static void start() { POSTGRES.start(); }
  @AfterAll static void stop() { POSTGRES.stop(); }
  @BeforeEach void fresh() throws Exception {
    String name = "inspection_" + UUID.randomUUID().toString().replace("-", "");
    try (var c = configured(POSTGRES.getDatabaseName()).getConnection(); var s = c.createStatement()) {
      s.execute("CREATE DATABASE " + name + " TEMPLATE template0");
    }
    source = configured(name);
    PostgresDatabaseLifecycle.initializeEmpty(source);
    state = PostgresDurableEnforcementState.openExisting(source, Clock.fixed(NOW, ZoneOffset.UTC),
        () -> UUID.randomUUID().toString());
  }

  @Test void emptyReadyAndQuarantinedReportsAreCompleteAndDoNotChangeState() {
    var ready = PostgresRecoveryInspection.inspect(source);
    assertThat(ready.recovery().mode()).isEqualTo("ready");
    assertThat(ready.executions()).isEqualTo(new PostgresRecoveryInspection.Executions(0, 0, 0, 0));
    assertThat(ready.approvals()).isEqualTo(new PostgresRecoveryInspection.Approvals(0, 0, 0, 0, 0, 0));
    assertThat(ready.reconciliationQueued()).isZero();
    assertThat(ready.transactionStartedAt()).isNotNull();
    var quarantine = PostgresRecoveryQuarantine.quarantine(source, NOW);
    assertThat(PostgresRecoveryInspection.inspect(source).recovery()).isEqualTo(quarantine);
    assertThat(PostgresRecoveryQuarantine.status(source)).isEqualTo(quarantine);
  }

  @Test void countsStoredStatusesAcrossTenantsWithoutRefreshingExpiryOrReleasingAuthority() {
    state.claim(claim(TENANT, "executing", 'a'));
    var other = new TenantPartition("other-synthetic");
    for (var entry : List.of("succeeded", "failed", "unknown")) {
      var attempt = state.claim(claim(other, entry, entry.charAt(0) == 's' ? 'b' : entry.charAt(0) == 'f' ? 'c' : 'd')).attempt();
      state.complete(other, attempt.attemptId(), entry, "SIMULATOR_OBSERVED", NOW.plusSeconds(1));
    }
    var pending = binding("pending"); var approved = binding("approved"); var denied = binding("denied");
    state.createPending(pending); state.createPending(approved); state.createPending(denied);
    state.decide(decision(approved, "approve")); state.decide(decision(denied, "deny"));
    PostgresRecoveryQuarantine.quarantine(source, NOW);
    var report = PostgresRecoveryInspection.inspect(source);
    assertThat(report.executions()).isEqualTo(new PostgresRecoveryInspection.Executions(1, 1, 1, 1));
    // NOW is in the past: inspection reports stored pending/approved, not eligibility or lazy expiry.
    assertThat(report.approvals()).isEqualTo(new PostgresRecoveryInspection.Approvals(1, 1, 1, 0, 0, 0));
    assertThat(report.reconciliationQueued()).isEqualTo(1);
    assertThat(PostgresRecoveryInspection.inspect(source).executions()).isEqualTo(report.executions());
    assertThat(state.find(TENANT, "pending").status()).isEqualTo("pending");
    assertThatThrownBy(() -> state.claim(claim(TENANT, "not-released", 'e'))).hasMessage("DATABASE_RECOVERY_QUARANTINED");
  }

  @Test void runtimeEvenWithAllSelectPrivilegesCannotReadInstallationSummary() throws Exception {
    String role = "runtime_" + UUID.randomUUID().toString().replace("-", "");
    sql("CREATE ROLE " + role + " LOGIN PASSWORD 'synthetic-only'; GRANT USAGE ON SCHEMA public TO " + role
        + "; GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + role);
    var runtime = configured(source.getDatabaseName()); runtime.setUser(role); runtime.setPassword("synthetic-only");
    assertThat(PostgresRecoveryQuarantine.status(runtime).mode()).isEqualTo("ready");
    assertThatThrownBy(() -> PostgresRecoveryInspection.inspect(runtime)).hasMessage("RECOVERY_INSPECTION_OPERATOR_REQUIRED");
  }

  @Test void consumedAndExpiredAreStoredStatesNotInferredFromCounts() {
    var consumed = binding("consumed"); var expired = binding("expired");
    state.createPending(consumed); state.createPending(expired);
    var decision = decision(consumed, "approve"); state.decide(decision);
    state.consumeAndClaim(new ApprovalExecutionClaim(consumed, decision.approvalDecisionId(),
        claim(TENANT, "consumed", 'f'), NOW));
    state.decide(new ApprovalDecision(TENANT, expired.approvalRequestId(), "decision-expired",
        "user:bob@demo.invalid", "synthetic_oidc", hash('e'), "approve", "REVIEWED", NOW.plusSeconds(301)));
    var report = PostgresRecoveryInspection.inspect(source);
    assertThat(report.approvals()).isEqualTo(new PostgresRecoveryInspection.Approvals(0, 0, 0, 1, 1, 0));
    assertThat(report.executions().executing()).isEqualTo(1);
    assertThat(report.reconciliationQueued()).isZero();
    // Inspection must not manufacture an unknown queue entry for the abandoned executing attempt.
    assertThat(state.find(TENANT, "consumed").status()).isEqualTo("consumed");
  }

  @Test void inheritedOwnerCanInspect() throws Exception {
    String role = "operator_" + UUID.randomUUID().toString().replace("-", "");
    sql("CREATE ROLE " + role + " LOGIN PASSWORD 'synthetic-only'; GRANT " + POSTGRES.getUsername() + " TO " + role);
    var operator = configured(source.getDatabaseName()); operator.setUser(role); operator.setPassword("synthetic-only");
    assertThat(PostgresRecoveryInspection.inspect(operator).recovery().mode()).isEqualTo("ready");
  }

  @ParameterizedTest @ValueSource(strings = {"tp_execution_attempts", "tp_approval_requests", "tp_reconciliation_queue", "tp_recovery_quarantine"})
  void unexpectedRlsCannotSilentlyUnderreport(String table) throws Exception {
    sql("ALTER TABLE public." + table + " ENABLE ROW LEVEL SECURITY");
    assertThatThrownBy(() -> PostgresRecoveryInspection.inspect(source)).hasMessage("RECOVERY_INSPECTION_OPERATOR_REQUIRED");
  }

  @Test void snapshotDoesNotMixLaterQuarantineAndClaimsAndDoesNotBlockThem() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r); t.setDaemon(true); return t; });
    try {
      var future = executor.submit(() -> PostgresRecoveryInspection.inspect(intercept(c -> {
        entered.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Release timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
      })));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      state.claim(claim(TENANT, "concurrent", 'a'));
      PostgresRecoveryQuarantine.quarantine(source, NOW);
      release.countDown();
      var before = future.get(5, TimeUnit.SECONDS);
      assertThat(before.recovery().mode()).isEqualTo("ready");
      assertThat(before.executions().executing()).isZero();
      var after = PostgresRecoveryInspection.inspect(source);
      assertThat(after.recovery().mode()).isEqualTo("quarantined");
      assertThat(after.executions().executing()).isEqualTo(1);
    } finally {
      release.countDown(); executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test void actualSnapshotConnectionIsReadOnlyWithFiniteQueryAndLockBudgets() {
    AtomicBoolean checked = new AtomicBoolean();
    assertThatThrownBy(() -> PostgresRecoveryInspection.inspect(intercept(c -> {
      try (var s = c.createStatement()) {
        assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        for (String setting : List.of("transaction_read_only", "statement_timeout", "lock_timeout")) {
          try (var r = s.executeQuery("SHOW " + setting)) {
            r.next(); assertThat(r.getString(1)).isEqualTo(setting.equals("transaction_read_only") ? "on" : setting.equals("statement_timeout") ? "5s" : "1s");
          }
        }
        assertThatThrownBy(() -> s.executeUpdate("INSERT INTO public.tp_consumed_execution_permits(tenant_id) VALUES('forbidden')"))
            .isInstanceOf(SQLException.class).satisfies(f -> assertThat(((SQLException) f).getSQLState()).isEqualTo("25006"));
        // The deliberate denied write aborts this transaction: no report may escape afterward.
        checked.set(true);
      } catch (SQLException failure) { throw new AssertionError(failure); }
    }))).isInstanceOf(PostgresStateException.class);
    assertThat(checked).isTrue();
  }

  @Test void missingInvariantRefusesInspectionWithoutRepair() throws Exception {
    sql("ALTER TABLE tp_recovery_quarantine DISABLE TRIGGER tp_recovery_quarantine_guard");
    assertThatThrownBy(() -> PostgresRecoveryInspection.inspect(source)).hasMessage("DATABASE_SCHEMA_NOT_READY");
  }

  @Test void exclusiveTableLockFailsWithLockBudgetInsteadOfPrintingPartialCounts() throws Exception {
    try (var c = source.getConnection(); var s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("LOCK TABLE public.tp_execution_attempts IN ACCESS EXCLUSIVE MODE");
      assertThatThrownBy(() -> PostgresRecoveryInspection.inspect(source))
          .isInstanceOf(PostgresStateException.class).hasCauseInstanceOf(SQLException.class)
          .satisfies(f -> assertThat(((SQLException) f.getCause()).getSQLState()).isEqualTo("55P03"));
      c.rollback();
    }
    assertThat(PostgresRecoveryInspection.inspect(source).executions().executing()).isZero();
  }

  @Test void aggregateFailureNeverReturnsPartialCounts() {
    assertThatThrownBy(() -> PostgresRecoveryInspection.inspect(intercept(c -> {
      throw new IllegalStateException("Synthetic read failure");
    }))).hasMessage("Synthetic read failure");
    assertThat(PostgresRecoveryInspection.inspect(source).executions().executing()).isZero();
  }

  @Test void failureEndingReadOnlyTransactionDoesNotReturnSuccess() {
    DataSource broken = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
      try {
        Object value = m.invoke(source, a);
        if (!m.getName().equals("getConnection")) return value;
        var c = (Connection) value;
        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (cp, cm, ca) -> {
          if (cm.getName().equals("rollback") && c.getTransactionIsolation() == Connection.TRANSACTION_REPEATABLE_READ)
            throw new SQLException("Synthetic read-only rollback failure");
          try { return cm.invoke(c, ca); }
          catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
      } catch (InvocationTargetException failure) { throw failure.getCause(); }
    });
    assertThatThrownBy(() -> PostgresRecoveryInspection.inspect(broken)).isInstanceOf(PostgresStateException.class);
    assertThat(PostgresRecoveryInspection.inspect(source).recovery().mode()).isEqualTo("ready");
  }

  // Pause/fault after the owner query establishes the snapshot, before the guard/aggregate reads.
  private DataSource intercept(Consumer<Connection> hook) {
    return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
      try {
        Object result = m.invoke(source, a);
        if (!m.getName().equals("getConnection")) return result;
        var connection = (Connection) result;
        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (cp, cm, ca) -> {
          try {
            Object value = cm.invoke(connection, ca);
            if (!cm.getName().equals("createStatement") || connection.getAutoCommit()) return value;
            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, (sp, sm, sa) -> {
              try {
                Object rows = sm.invoke(value, sa);
                if (sm.getName().equals("executeQuery") && sa[0].toString().contains("count(*) = 4")) hook.accept(connection);
                return rows;
              } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
          } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
      } catch (InvocationTargetException failure) { throw failure.getCause(); }
    });
  }
  private ApprovalBinding binding(String id) {
    return new ApprovalBinding(TENANT, id, CanonicalJson.sha256(JsonSupport.MAPPER.createObjectNode().put("id", id)), hash('b'),
        "event-" + id, "evaluation-1", "user:alice@demo.invalid", hash('c'), "policy-1", 1, "require_approval",
        "aws.ec2.update_security_group", hash('d'), "default", NOW, NOW.plusSeconds(300), 1);
  }
  private ApprovalDecision decision(ApprovalBinding binding, String choice) {
    return new ApprovalDecision(TENANT, binding.approvalRequestId(), "decision-" + binding.approvalRequestId(),
        "user:bob@demo.invalid", "synthetic_oidc", hash('e'), choice, "REVIEWED", NOW);
  }
  private DurableExecutionClaim claim(TenantPartition tenant, String id, char hash) {
    return new DurableExecutionClaim(tenant, id, NOW.plusSeconds(30), "simulator", hash(hash), "event-" + id,
        "trace-1", "evaluation-1", "user:alice@demo.invalid", hash('b'), "policy-1", 1, "allow", List.of(hash('c')), NOW);
  }
  private static String hash(char c) { return "sha256:" + String.valueOf(c).repeat(64); }
  private PGSimpleDataSource configured(String name) {
    var ds = new PGSimpleDataSource(); ds.setServerNames(new String[] {"127.0.0.1"});
    ds.setPortNumbers(new int[] {POSTGRES.getMappedPort(5432)}); ds.setDatabaseName(name);
    ds.setUser(POSTGRES.getUsername()); ds.setPassword(POSTGRES.getPassword());
    ds.setCurrentSchema("public"); ds.setConnectTimeout(3); ds.setSocketTimeout(10); return ds;
  }
  private void sql(String text) throws SQLException {
    try (var c = source.getConnection(); var s = c.createStatement()) { s.setQueryTimeout(5); s.execute(text); }
  }
}
