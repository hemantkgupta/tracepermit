package io.tracepermit.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.execution.ApprovalHistoryQuery;
import io.tracepermit.execution.TenantPartition;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Genuine predecessor history and real PostgreSQL DDL; no operator database or target. */
final class PostgresDatabaseUpgradeTest {
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
  private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
  private static final TenantPartition TENANT = new TenantPartition("synthetic");
  private PGSimpleDataSource source;

  @BeforeAll static void start() { POSTGRES.start(); }
  @AfterAll static void stop() { POSTGRES.stop(); }
  @BeforeEach void fresh() throws Exception {
    String name = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (var c = configured(POSTGRES.getDatabaseName()).getConnection(); var s = c.createStatement()) {
      s.execute("CREATE DATABASE " + name + " TEMPLATE template0");
    }
    source = configured(name);
  }

  @Test void explicitUpgradePreservesEveryExistingRowAndPredecessorHistory() throws Exception {
    predecessor("9"); seed();
    Map<String, String> before = snapshot();
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.validateExisting(source))
        .hasMessage("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
    assertThat(snapshot()).isEqualTo(before);
    PostgresDatabaseLifecycle.upgradeFromV9(source);
    assertThat(version()).isEqualTo(10);
    assertThat(snapshot()).isEqualTo(before);
    PostgresDurableEnforcementState.openExisting(source, Clock.systemUTC(), () -> "id");
    PostgresPermitConsumptionStore.openExisting(source);
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(source))
        .hasMessage("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
    assertThat(version()).isEqualTo(10);
    assertThat(snapshot()).isEqualTo(before);
  }

  @ParameterizedTest @ValueSource(strings = {"7", "8", "10"})
  void unsupportedPredecessorsAreNotAdoptedOrRepaired(String target) throws Exception {
    predecessor(target);
    String history = history();
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(source)).isInstanceOf(RuntimeException.class);
    assertThat(history()).isEqualTo(history);
    assertThat(version()).isEqualTo(Integer.parseInt(target));
  }

  @Test void emptyDatabaseIsNotInitializedByUpgrade() throws Exception {
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(source)).hasMessage("DATABASE_SCHEMA_NOT_READY");
    assertThat(scalar("SELECT count(*) FROM pg_tables WHERE schemaname='public'")).isZero();
  }

  @ParameterizedTest @ValueSource(strings = {
      "UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'",
      "UPDATE flyway_schema_history SET success=FALSE WHERE version='8'",
      "DELETE FROM flyway_schema_history WHERE version='8'",
      "UPDATE flyway_schema_history SET version='99' WHERE version='8'",
      "ALTER TABLE tp_approval_requests DISABLE TRIGGER tp_approval_request_update_guard",
      "ALTER TABLE tp_approval_transitions DISABLE TRIGGER tp_00_recovery_review_guard",
      "ALTER TABLE tp_approval_requests ENABLE ROW LEVEL SECURITY",
      "CREATE TABLE unrelated(id int)",
      "DROP TABLE tp_approval_action_snapshots",
      "ALTER TABLE tp_enforcement_schema_guard DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check; UPDATE tp_enforcement_schema_guard SET contract_version=999",
      "CREATE SCHEMA unexpected"
  })
  void invalidInstallationRefusesUpgradeWithoutChangingHistory(String change) throws Exception {
    predecessor("9"); sql(change);
    String before = history();
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(source)).isInstanceOf(RuntimeException.class);
    assertThat(history()).isEqualTo(before);
    assertThat(scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='cancellation_subject_id'")).isZero();
  }

  @Test void quarantineRefusesUpgrade() throws Exception {
    predecessor("9");
    sql("UPDATE tp_recovery_quarantine SET mode='quarantined', recovery_id='00000000-0000-0000-0000-000000000001', quarantined_at=CURRENT_TIMESTAMP");
    String before = history();
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(source)).isInstanceOf(RuntimeException.class);
    assertThat(version()).isEqualTo(9); assertThat(history()).isEqualTo(before);
  }

  @ParameterizedTest @ValueSource(strings = {"executing", "unknown"})
  void unresolvedExecutionRefusesUpgrade(String status) throws Exception {
    predecessor("9"); seedExecution(status);
    Map<String, String> before = snapshot();
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(source)).isInstanceOf(RuntimeException.class);
    assertThat(version()).isEqualTo(9); assertThat(snapshot()).isEqualTo(before);
  }

  @Test void runtimeReadPrivilegesDoNotGrantUpgradeAuthority() throws Exception {
    predecessor("9");
    String role = "runtime_" + UUID.randomUUID().toString().replace("-", "");
    sql("CREATE ROLE " + role + " LOGIN PASSWORD 'synthetic-only'; GRANT USAGE ON SCHEMA public TO " + role
        + "; GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + role);
    var restricted = configured(source.getDatabaseName()); restricted.setUser(role); restricted.setPassword("synthetic-only");
    String before = history();
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(restricted)).hasMessage("DATABASE_UPGRADE_OWNER_REQUIRED");
    assertThat(history()).isEqualTo(before); assertThat(version()).isEqualTo(9);
  }

  @Test void databaseOwnerWithoutClusterSuperuserCanUpgrade() throws Exception {
    String role = "installer_" + UUID.randomUUID().toString().replace("-", "");
    sql("CREATE ROLE " + role + " LOGIN NOSUPERUSER PASSWORD 'synthetic-owner-only'; ALTER DATABASE "
        + source.getDatabaseName() + " OWNER TO " + role);
    var owner = configured(source.getDatabaseName()); owner.setUser(role); owner.setPassword("synthetic-owner-only");
    source = owner;
    predecessor("9"); seed(); var before = snapshot();
    PostgresDatabaseLifecycle.upgradeFromV9(source);
    assertThat(version()).isEqualTo(10); assertThat(snapshot()).isEqualTo(before);
  }

  @Test void ownerTriggerNegativeControlIsDetectedAndDemonstratesDatabaseTrustLimit() throws Exception {
    predecessor("9"); seed(); PostgresDatabaseLifecycle.upgradeFromV9(source);
    String otherOwner = cancellation().replace("principal:alice", "principal:bob");
    assertThatThrownBy(() -> sql(otherOwner)).isInstanceOf(SQLException.class);
    // Deliberately weakened control in this disposable test database only.
    sql("ALTER TABLE tp_approval_transitions DISABLE TRIGGER tp_10_approval_cancellation_guard");
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.validateExisting(source)).hasMessage("DATABASE_SCHEMA_NOT_READY");
    sql(otherOwner);
    assertThat(scalar("SELECT count(*) FROM tp_approval_requests WHERE status='cancelled'")).isEqualTo(1);
    assertThat(scalar("SELECT count(*) FROM tp_execution_attempts")).isEqualTo(1); // Seeded, no new claim.
  }

  @Test void heldLockFailsFastAndLeaseIsReleasedOnFailureAndSuccess() throws Exception {
    predecessor("9");
    try (var owner = source.getConnection(); var s = owner.createStatement()) {
      s.execute("SELECT pg_advisory_lock(" + PostgresDatabaseLifecycle.INITIALIZATION_LOCK + ")");
      assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(source)).hasMessage("DATABASE_UPGRADE_BUSY");
      assertThat(version()).isEqualTo(9);
    }
    PostgresDatabaseLifecycle.upgradeFromV9(source);
    try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(
        "SELECT pg_try_advisory_lock(" + PostgresDatabaseLifecycle.INITIALIZATION_LOCK + ")")) {
      r.next(); assertThat(r.getBoolean(1)).isTrue();
    }
  }

  @Test void competingUpgradersHaveOnlyOneSuccessfulMigration() throws Exception {
    predecessor("9"); seed();
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).factory());
    try {
      var jobs = List.of(pool.submit(() -> upgradeAt(start)), pool.submit(() -> upgradeAt(start)));
      start.countDown();
      var results = List.of(jobs.getFirst().get(30, TimeUnit.SECONDS), jobs.getLast().get(30, TimeUnit.SECONDS));
      assertThat(results.stream().filter("upgraded"::equals).count()).isEqualTo(1);
      assertThat(results).isSubsetOf("upgraded", "DATABASE_UPGRADE_BUSY", "INCOMPATIBLE_DURABLE_STATE_SCHEMA");
    } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    PostgresDatabaseLifecycle.validateExisting(source);
    assertThat(scalar("SELECT count(*) FROM flyway_schema_history WHERE version='10'")).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void retainedPhysicalLeaseDoesNotRetainUpgradeLock(boolean failure) throws Exception {
    predecessor("9");
    if (failure) sql("CREATE TABLE unexpected(value int)");
    try (Connection physical = source.getConnection()) {
      Connection lease = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
          (p, m, a) -> m.getName().equals("close") ? null : invoke(physical, m, a));
      AtomicInteger calls = new AtomicInteger();
      DataSource borrowed = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
          (p, m, a) -> m.getName().equals("getConnection") && calls.getAndIncrement() == 0 ? lease : invoke(source, m, a));
      if (failure) assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(borrowed)).hasMessage("DATABASE_SCHEMA_NOT_READY");
      else PostgresDatabaseLifecycle.upgradeFromV9(borrowed);
      assertThat(physical.isClosed()).isFalse();
      try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(
          "SELECT pg_try_advisory_lock(" + PostgresDatabaseLifecycle.INITIALIZATION_LOCK + ")")) {
        r.next(); assertThat(r.getBoolean(1)).isTrue();
      }
    }
  }

  @Test void ddlLockTimeoutLeavesPredecessorIntactAndReleasesUpgradeLease() throws Exception {
    predecessor("9");
    String before = history();
    try (var blocker = source.getConnection(); var s = blocker.createStatement()) {
      blocker.setAutoCommit(false);
      s.execute("LOCK TABLE tp_approval_requests IN ACCESS SHARE MODE");
      var pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).factory());
      try {
        var job = pool.submit(() -> assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(source))
            .isInstanceOf(RuntimeException.class));
        job.get(20, TimeUnit.SECONDS);
      } finally {
        blocker.rollback(); pool.shutdownNow();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
    assertThat(version()).isEqualTo(9); assertThat(history()).isEqualTo(before);
    PostgresDatabaseLifecycle.upgradeFromV9(source);
  }

  @Test void unavailableConnectionAndWrongSearchPathNeverUpgrade() throws Exception {
    predecessor("9"); String before = history();
    var wrong = configured(source.getDatabaseName()); wrong.setCurrentSchema("pg_catalog");
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(wrong)).hasMessage("DATABASE_SCHEMA_NOT_READY");
    var unavailable = configured(source.getDatabaseName()); unavailable.setPortNumbers(new int[] {1});
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(unavailable)).isInstanceOf(PostgresStateException.class);
    assertThat(version()).isEqualTo(9); assertThat(history()).isEqualTo(before);
  }

  @Test void failedDdlRollsBackNewColumnsGuardAndHistoryAndReleasesLock() throws Exception {
    predecessor("9"); seed();
    sql("""
        CREATE FUNCTION fail_v10() RETURNS event_trigger LANGUAGE plpgsql AS $$ BEGIN
          IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND column_name='cancellation_subject_id')
          THEN RAISE EXCEPTION 'synthetic upgrade failure'; END IF; END $$;
        CREATE EVENT TRIGGER fail_v10 ON ddl_command_end WHEN TAG IN ('ALTER TABLE') EXECUTE FUNCTION fail_v10();
        """);
    Map<String, String> before = snapshot();
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(source)).isInstanceOf(RuntimeException.class);
    assertThat(version()).isEqualTo(9); assertThat(snapshot()).isEqualTo(before);
    assertThat(scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='cancellation_subject_id'")).isZero();
    sql("DROP EVENT TRIGGER fail_v10; DROP FUNCTION fail_v10()");
    PostgresDatabaseLifecycle.upgradeFromV9(source);
  }

  @Test void cancellationStorageRequiresOwnerProvenanceAndIsTerminalWithoutCreatingAuthority() throws Exception {
    predecessor("9"); seed(); PostgresDatabaseLifecycle.upgradeFromV9(source);
    String transition = cancellation();
    for (String invalid : List.of(
        transition.replace("principal:alice", "principal:bob"),
        transition.replace("'principal:alice'", "NULL"),
        transition.replace("'synthetic_oidc'", "NULL"),
        transition.replace("'sha256:" + "c".repeat(64) + "'", "NULL"),
        transition.replace("'2026-10-19T12:01:00Z'", "NULL"),
        transition.replace("'REQUESTER_CANCELLED'", "'REVIEWED'"),
        transition.replace("'2026-10-19T12:01:00Z'", "'2026-10-19T12:00:01Z'"),
        transition.replace("'2026-10-19T12:00:01Z'", "'2026-10-19T14:00:00Z'"))) {
      assertThatThrownBy(() -> sql(invalid)).isInstanceOf(SQLException.class);
    }
    assertThatThrownBy(() -> sql(transition.replace("'cancelled'", "'expired'"))).isInstanceOf(SQLException.class);
    sql(transition);
    var state = PostgresDurableEnforcementState.openExisting(source, Clock.fixed(NOW, ZoneOffset.UTC), () -> "id");
    assertThat(state.find(TENANT, "request").status()).isEqualTo("cancelled");
    assertThat(state.find(TENANT, "request").reviewerSubjectId()).isNull();
    assertThat(state.approvalHistory(TENANT, null, new ApprovalHistoryQuery(10, "cancelled", null, null, null, null)).items()).hasSize(1);
    assertThat(PostgresRecoveryInspection.inspect(source).approvals().cancelled()).isEqualTo(1);
    assertThatThrownBy(() -> sql(transition)).isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> sql("UPDATE tp_approval_requests SET status='pending' WHERE approval_request_id='request'"))
        .isInstanceOf(SQLException.class);
    for (String terminal : List.of("approved", "consumed", "expired", "pending")) {
      assertThatThrownBy(() -> sql("INSERT INTO tp_approval_transitions(tenant_id,approval_request_id,sequence,from_status,to_status,reason_code,occurred_at)"
          + " VALUES ('synthetic','request',3,'cancelled','" + terminal + "','INVALID','2026-10-19T12:00:02Z')"))
          .isInstanceOf(SQLException.class);
    }
    assertThatThrownBy(() -> sql("""
        INSERT INTO tp_approval_transitions(tenant_id,approval_request_id,sequence,from_status,to_status,reason_code,occurred_at,
          approval_decision_id,reviewer_subject_id,reviewer_authentication_method,reviewer_credential_fingerprint)
        VALUES ('synthetic','request',3,'cancelled','approved','REVIEWED','2026-10-19T12:00:02Z',
          'decision','principal:bob','synthetic_oidc','sha256:%s')
        """.formatted("d".repeat(64)))).isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> sql("""
        INSERT INTO tp_approval_transitions(tenant_id,approval_request_id,sequence,from_status,to_status,reason_code,occurred_at,permit_id,attempt_id)
        VALUES ('synthetic','request',3,'cancelled','consumed','CONSUMED','2026-10-19T12:00:02Z','permit','attempt')
        """)).isInstanceOf(SQLException.class);
    assertThat(scalar("SELECT count(*) FROM tp_execution_attempts")).isEqualTo(1); // Unchanged seeded succeeded attempt.
    assertThat(scalar("SELECT count(*) FROM tp_consumed_execution_permits")).isEqualTo(1);
  }

  @Test void lostFinalValidationAcknowledgementDoesNotReportSuccessOrUndoCommittedUpgrade() throws Exception {
    predecessor("9"); seed(); Map<String, String> before = snapshot();
    AtomicBoolean lost = new AtomicBoolean();
    DataSource interrupted = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p,m,a) -> {
      Object value = invoke(source, m, a);
      if (!m.getName().equals("getConnection")) return value;
      Connection c = (Connection) value;
      return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (cp,cm,ca) -> {
        Object statement = invoke(c, cm, ca);
        if (!cm.getName().equals("createStatement")) return statement;
        return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, (sp,sm,sa) -> {
          Object rows = invoke(statement, sm, sa);
          if (!sm.getName().equals("executeQuery") || !sa[0].toString().contains("SELECT contract_version")) return rows;
          return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (rp,rm,ra) -> {
            Object result = invoke(rows, rm, ra);
            if (rm.getName().equals("getInt") && Integer.valueOf(10).equals(result) && lost.compareAndSet(false, true))
              throw new SQLException("synthetic lost validation acknowledgement");
            return result;
          });
        });
      });
    });
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(interrupted)).isInstanceOf(PostgresStateException.class);
    assertThat(lost).isTrue(); assertThat(version()).isEqualTo(10); assertThat(snapshot()).isEqualTo(before);
    PostgresDatabaseLifecycle.validateExisting(source);
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.upgradeFromV9(source)).hasMessage("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
  }

  private String upgradeAt(CountDownLatch start) throws Exception {
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    try { PostgresDatabaseLifecycle.upgradeFromV9(source); return "upgraded"; }
    catch (IllegalStateException failure) { return failure.getMessage(); }
  }
  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
    try { return method.invoke(target, args); } catch (InvocationTargetException e) { throw e.getCause(); }
  }
  private void predecessor(String version) {
    Flyway.configure().dataSource(source).locations(PostgresDurableEnforcementState.MIGRATION_LOCATION)
        .loggers(new String[0]).target(version).load().migrate();
  }
  private void seed() throws Exception {
    seedExecution("succeeded");
    String hash = "sha256:" + "a".repeat(64);
    sql("""
        INSERT INTO tp_approval_requests(tenant_id,approval_request_id,request_hash,action_hash,event_id,evaluation_id,
          subject_id,actor_lineage_hash,policy_id,policy_version,original_decision,tool_name,resource_hash,approval_group,
          created_at,expires_at,max_uses,status,transition_sequence,updated_at)
        VALUES ('synthetic','request','%s','%s','event','evaluation','principal:alice','%s','policy',1,'require_approval',
          'aws.ec2.update_security_group','%s','reviewers','2026-10-19T12:00:00Z','2026-10-19T13:00:00Z',1,'pending',1,'2026-10-19T12:00:00Z');
        INSERT INTO tp_approval_transitions(tenant_id,approval_request_id,sequence,to_status,reason_code,occurred_at)
          VALUES ('synthetic','request',1,'pending','APPROVAL_REQUIRED','2026-10-19T12:00:00Z');
        INSERT INTO tp_approval_action_snapshots VALUES ('synthetic','request','{"fixture":"migration-only","port":443}');
        INSERT INTO tp_policy_versions(workspace_id,policy_id,version,name,description,content_hash,document,current_state,staged_at)
          VALUES ('workspace','policy',1,'Synthetic','SQL migration fixture','%s','{}','active','2026-10-19T12:00:00Z');
        INSERT INTO tp_active_policies VALUES ('workspace','policy',1,'2026-10-19T12:00:00Z',1,'2026-10-19T12:00:00Z');
        INSERT INTO tp_approval_policy_bindings VALUES ('synthetic','request','workspace','policy',1,1,'%s','2026-10-19T12:00:00Z');
        INSERT INTO tp_evidence_streams(tenant_id,stream_id) VALUES ('synthetic','execution');
        INSERT INTO tp_evidence_events(tenant_id,stream_id,sequence,evidence_event_id,payload,event_hash,recorded_at)
          VALUES ('synthetic','execution',1,'evidence','{"evidenceEventId":"evidence","sequence":1,"fixture":"migration-only"}','%s','2026-10-19T12:00:00Z');
        INSERT INTO tp_evidence_outbox(tenant_id,outbox_id,stream_id,sequence,evidence_event_id,payload_hash,created_at,next_attempt_at)
          VALUES ('synthetic','outbox','execution',1,'evidence','%s','2026-10-19T12:00:00Z','2026-10-19T12:00:00Z');
        """.formatted(hash,hash,hash,hash,hash,hash,hash,hash));
  }
  private void seedExecution(String status) throws Exception {
    String hash = "sha256:" + "b".repeat(64);
    sql("""
        INSERT INTO tp_consumed_execution_permits VALUES ('synthetic','permit','2026-10-19T13:00:00Z','%s','2026-10-19T12:00:00Z');
        INSERT INTO tp_execution_attempts(tenant_id,attempt_id,permit_id,adapter_id,idempotency_key,event_id,trace_id,evaluation_id,
          subject_id,action_hash,policy_id,policy_version,decision,credential_fingerprints,status,transition_sequence,started_at,updated_at)
        VALUES ('synthetic','attempt','permit','simulator','%s','event','trace','evaluation','principal:alice','%s','policy',1,
          'allow','[]','executing',1,'2026-10-19T12:00:00Z','2026-10-19T12:00:00Z');
        INSERT INTO tp_execution_transitions VALUES ('synthetic','attempt',1,NULL,'executing','2026-10-19T12:00:00Z','CLAIMED');
        """.formatted(hash,hash,hash));
    if (!status.equals("executing")) sql("INSERT INTO tp_execution_transitions VALUES ('synthetic','attempt',2,'executing','"
        + status + "','2026-10-19T12:00:01Z','SYNTHETIC_COMPLETION')");
  }
  private String cancellation() {
    return """
        INSERT INTO tp_approval_transitions(tenant_id,approval_request_id,sequence,from_status,to_status,reason_code,occurred_at,
          cancellation_subject_id,cancellation_authentication_method,cancellation_credential_fingerprint,cancellation_authority_expires_at)
        VALUES ('synthetic','request',2,'pending','cancelled','REQUESTER_CANCELLED','2026-10-19T12:00:01Z',
          'principal:alice','synthetic_oidc','sha256:%s','2026-10-19T12:01:00Z')
        """.formatted("c".repeat(64));
  }
  private Map<String, String> snapshot() throws Exception {
    var values = new LinkedHashMap<String, String>();
    try (var c = source.getConnection(); var s = c.createStatement()) {
      var tables = new ArrayList<String>();
      try (var r = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'tp_enforcement_schema_guard' ORDER BY tablename")) {
        while (r.next()) tables.add(r.getString(1));
      }
      for (String table : tables) {
        String suffix = table.equals("flyway_schema_history") ? " WHERE version <> '10'" : "";
        String row = table.equals("tp_approval_transitions")
            ? "to_jsonb(t) - ARRAY['cancellation_subject_id','cancellation_authentication_method','cancellation_credential_fingerprint','cancellation_authority_expires_at']" : "to_jsonb(t)";
        try (var r = s.executeQuery("SELECT coalesce(jsonb_agg(row ORDER BY row::text),'[]'::jsonb)::text FROM (SELECT "
            + row + " AS row FROM public." + table + " t" + suffix + ") q")) { r.next(); values.put(table,r.getString(1)); }
      }
    }
    return values;
  }
  private int version() throws Exception { return scalar("SELECT contract_version FROM tp_enforcement_schema_guard"); }
  private String history() throws Exception {
    try (var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT jsonb_agg(to_jsonb(h) ORDER BY installed_rank)::text FROM flyway_schema_history h")) {
      r.next(); return r.getString(1);
    }
  }
  private int scalar(String sql) throws Exception { try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getInt(1);} }
  private void sql(String sql) throws SQLException { try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);} }
  private static PGSimpleDataSource configured(String name) {
    var ds=new PGSimpleDataSource();ds.setServerNames(new String[]{"127.0.0.1"});ds.setPortNumbers(new int[]{POSTGRES.getMappedPort(5432)});
    ds.setDatabaseName(name);ds.setUser(POSTGRES.getUsername());ds.setPassword(POSTGRES.getPassword());ds.setCurrentSchema("public");
    ds.setConnectTimeout(3);ds.setSocketTimeout(15);return ds;
  }
}
