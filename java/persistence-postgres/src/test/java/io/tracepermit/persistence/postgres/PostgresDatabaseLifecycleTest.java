package io.tracepermit.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/** Only generated databases in this owned disposable container; no operator configuration. */
final class PostgresDatabaseLifecycleTest {
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
  private PGSimpleDataSource source;

  @BeforeAll static void start() { POSTGRES.start(); }
  @AfterAll static void stop() { POSTGRES.stop(); }

  @BeforeEach void freshDatabase() throws Exception {
    String name = "init_" + UUID.randomUUID().toString().replace("-", "");
    var admin = configured(POSTGRES.getDatabaseName());
    try (var connection = admin.getConnection(); var sql = connection.createStatement()) {
      sql.execute("CREATE DATABASE " + name + " TEMPLATE template0");
    }
    source = configured(name);
    // Databases are owned by this test and removed with its single container, never reused/reset.
  }

  @Test void strictOpenOfEmptyDatabaseDoesNotInitializeIt() throws Exception {
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.validateExisting(source)).hasMessage("DATABASE_SCHEMA_NOT_READY");
    assertThatThrownBy(() -> PostgresDurableEnforcementState.openExisting(source, Clock.systemUTC(), () -> "id"))
        .hasMessage("DATABASE_SCHEMA_NOT_READY");
    assertThatThrownBy(() -> PostgresPermitConsumptionStore.openExisting(source)).hasMessage("DATABASE_SCHEMA_NOT_READY");
    assertThat(tables()).isZero();
  }

  @Test void explicitInitializeThenStrictOpenPreservesCompleteMigrationHistory() throws Exception {
    PostgresDatabaseLifecycle.initializeEmpty(source);
    String before = history();
    PostgresDatabaseLifecycle.validateExisting(source);
    PostgresDurableEnforcementState.openExisting(source, Clock.systemUTC(), () -> "id");
    PostgresPermitConsumptionStore.openExisting(source);
    assertThat(history()).isEqualTo(before);
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.initializeEmpty(source))
        .hasMessage("DATABASE_INITIALIZATION_REQUIRES_EMPTY_DATABASE");
    assertThat(history()).isEqualTo(before);
  }

  @Test void migrationDiscoveryAndChecksumsAreOrderedAndDeterministic() throws Exception {
    PostgresDatabaseLifecycle.initializeEmpty(source);
    try (var c = source.getConnection(); var s = c.createStatement();
         var r = s.executeQuery(
             "SELECT installed_rank, version, description, type, script, checksum, success " +
             "FROM public.flyway_schema_history ORDER BY installed_rank")) {
      int count = 0;
      while (r.next()) {
        count++;
        assertThat(r.getInt("installed_rank")).isEqualTo(count);
        assertThat(r.getString("version")).isEqualTo(String.valueOf(count));
        assertThat(r.getInt("checksum")).isNotZero();
        assertThat(r.getBoolean("success")).isTrue();
      }
      assertThat(count).isEqualTo(PostgresDatabaseLifecycle.CURRENT_VERSION);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "CREATE TABLE public.unrelated (id int)",
      "CREATE FUNCTION public.unrelated() RETURNS int LANGUAGE sql AS 'SELECT 1'",
      "CREATE TYPE public.unrelated AS ENUM ('synthetic')",
      "CREATE SCHEMA unrelated",
      "CREATE SCHEMA pgx_shadow",
      "CREATE COLLATION public.unrelated FROM pg_catalog.\"C\"",
      "SELECT lo_create(0)",
      "CREATE EXTENSION hstore",
      "DROP SCHEMA public"
  })
  void initializationRefusesNonemptyOrMissingPublicSchema(String change) throws Exception {
    execute(change);
    int before = tables();
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.initializeEmpty(source))
        .hasMessage("DATABASE_INITIALIZATION_REQUIRES_EMPTY_DATABASE");
    assertThat(tables()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "DELETE FROM public.tp_enforcement_schema_guard",
      "ALTER TABLE public.tp_enforcement_schema_guard DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check; UPDATE public.tp_enforcement_schema_guard SET contract_version = 999",
      "DROP TABLE public.tp_approval_action_snapshots",
      "ALTER TABLE public.tp_approval_policy_bindings DISABLE TRIGGER tp_approval_policy_binding_immutable",
      "DROP TABLE public.tp_recovery_quarantine CASCADE",
      "ALTER TABLE public.tp_recovery_quarantine DISABLE TRIGGER tp_recovery_quarantine_guard; DELETE FROM public.tp_recovery_quarantine",
      "ALTER TABLE public.tp_consumed_execution_permits DISABLE TRIGGER tp_00_recovery_permit_guard",
      "UPDATE public.flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'",
      "DELETE FROM public.flyway_schema_history WHERE version = '8'",
      "UPDATE public.flyway_schema_history SET version = '99' WHERE version = '8'",
      "UPDATE public.flyway_schema_history SET success = FALSE WHERE version = '8'",
      "UPDATE public.flyway_schema_history SET checksum = checksum + 1 WHERE version = '10'",
      "DELETE FROM public.flyway_schema_history WHERE version = '10'",
      "UPDATE public.flyway_schema_history SET version = '99' WHERE version = '10'",
      "UPDATE public.flyway_schema_history SET success = FALSE WHERE version = '10'"
  })
  void strictValidationRejectsInconsistentStateWithoutRepair(String change) throws Exception {
    PostgresDatabaseLifecycle.initializeEmpty(source);
    execute(change);
    String before = history();
    int beforeTables = tables();
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.validateExisting(source)).isInstanceOf(RuntimeException.class);
    assertThat(history()).isEqualTo(before);
    assertThat(tables()).isEqualTo(beforeTables);
  }

  @Test void wrongCurrentSchemaIsRejectedInsteadOfUsingRoleSearchPath() throws Exception {
    source.setCurrentSchema("pg_catalog");
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.initializeEmpty(source)).hasMessage("DATABASE_SCHEMA_NOT_READY");
    assertThat(tables()).isZero();
    source.setCurrentSchema("public");
    PostgresDatabaseLifecycle.initializeEmpty(source);
    execute("CREATE SCHEMA shadow");
    source.setCurrentSchema("shadow");
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.validateExisting(source)).hasMessage("DATABASE_SCHEMA_NOT_READY");
  }

  @Test void competingInitializersHaveOneWinnerAndNeverRepairOrReset() throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs = List.of(pool.submit(() -> initializeAt(start)), pool.submit(() -> initializeAt(start)));
      start.countDown();
      var results = List.of(jobs.get(0).get(20, TimeUnit.SECONDS), jobs.get(1).get(20, TimeUnit.SECONDS));
      assertThat(results).isSubsetOf("initialized", "DATABASE_INITIALIZATION_BUSY", "DATABASE_INITIALIZATION_REQUIRES_EMPTY_DATABASE");
      assertThat(results.stream().filter("initialized"::equals).count()).isEqualTo(1);
    }
    PostgresDatabaseLifecycle.validateExisting(source);
  }

  @Test void heldSessionLockFailsFastAndReleasesOnOwnerClose() throws Exception {
    try (var owner = source.getConnection(); var sql = owner.createStatement()) {
      sql.execute("SELECT pg_advisory_lock(" + PostgresDatabaseLifecycle.INITIALIZATION_LOCK + ")");
      try (var pool = Executors.newSingleThreadExecutor()) {
        var result = pool.submit(() -> {
          assertThatThrownBy(() -> PostgresDatabaseLifecycle.initializeEmpty(source)).hasMessage("DATABASE_INITIALIZATION_BUSY");
        });
        result.get(5, TimeUnit.SECONDS);
      }
      assertThat(tables()).isZero();
    }
    PostgresDatabaseLifecycle.initializeEmpty(source);
  }

  @Test void initializerExplicitlyUnlocksEvenWhenLeaseCloseRetainsPhysicalSession() throws Exception {
    try (var physical = source.getConnection()) {
      var lease = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
          new Class<?>[] {Connection.class}, (p, method, args) -> {
            if (method.getName().equals("close")) return null;
            try { return method.invoke(physical, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
          });
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var pool = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
          javax.sql.DataSource.class.getClassLoader(), new Class<?>[] {javax.sql.DataSource.class},
          (p, method, args) -> {
            if (method.getName().equals("getConnection") && calls.getAndIncrement() == 0) return lease;
            try { return method.invoke(source, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
          });
      PostgresDatabaseLifecycle.initializeEmpty(pool);
      assertThat(physical.isClosed()).isFalse();
      try (var observer = source.getConnection(); var sql = observer.createStatement(); var rows = sql.executeQuery(
          "SELECT pg_try_advisory_lock(" + PostgresDatabaseLifecycle.INITIALIZATION_LOCK + ")")) {
        rows.next(); assertThat(rows.getBoolean(1)).isTrue();
      }
    }
  }

  @Test void strictStoresOpenUnderNonownerRoleWithoutSchemaCreationOrHistoryWrites() throws Exception {
    PostgresDatabaseLifecycle.initializeEmpty(source);
    String role = "runtime_" + UUID.randomUUID().toString().replace("-", "");
    execute("CREATE ROLE " + role + " LOGIN PASSWORD 'synthetic-runtime-test-password'");
    execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO " + role
        + "; GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + role);
    var restricted = configured(source.getDatabaseName());
    restricted.setUser(role);
    restricted.setPassword("synthetic-runtime-test-password");
    String before = history();
    PostgresDatabaseLifecycle.validateExisting(restricted);
    PostgresDurableEnforcementState.openExisting(restricted, Clock.systemUTC(), () -> "id");
    PostgresPermitConsumptionStore.openExisting(restricted);
    try (Connection connection = restricted.getConnection(); var sql = connection.createStatement()) {
      assertThatThrownBy(() -> sql.execute("CREATE TABLE public.forbidden(id int)")).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> sql.execute("CREATE SCHEMA forbidden")).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> sql.execute("UPDATE public.flyway_schema_history SET checksum = 0")).isInstanceOf(SQLException.class);
    }
    assertThat(history()).isEqualTo(before);
  }

  @Test void connectionFailureDoesNotInitializeOrValidate() throws Exception {
    var unavailable = configured(source.getDatabaseName());
    unavailable.setPortNumbers(new int[] {1});
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.initializeEmpty(unavailable)).isInstanceOf(PostgresStateException.class);
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.validateExisting(unavailable)).isInstanceOf(PostgresStateException.class);
    assertThat(tables()).isZero();
  }

  @ParameterizedTest @ValueSource(strings = {"7", "8"})
  void olderInstallationIsNotSilentlyUpgradedByStrictOpenOrInit(String version) throws Exception {
    Flyway.configure().dataSource(source).locations(PostgresDurableEnforcementState.MIGRATION_LOCATION)
        .target(version).load().migrate();
    String before = history();
    assertThatThrownBy(() -> PostgresDurableEnforcementState.openExisting(source, Clock.systemUTC(), () -> "id"))
        .hasMessage("DATABASE_SCHEMA_NOT_READY");
    assertThatThrownBy(() -> PostgresDatabaseLifecycle.initializeEmpty(source))
        .hasMessage("DATABASE_INITIALIZATION_REQUIRES_EMPTY_DATABASE");
    assertThat(history()).isEqualTo(before);
    try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(
        "SELECT contract_version FROM public.tp_enforcement_schema_guard")) {
      r.next(); assertThat(r.getInt(1)).isEqualTo(Integer.parseInt(version));
    }
  }

  @Test void databaseOwnerCanInitializeWithoutClusterSuperuserRights() throws Exception {
    String role = "installer_" + UUID.randomUUID().toString().replace("-", "");
    execute("CREATE ROLE " + role + " LOGIN PASSWORD 'synthetic-installer-password'");
    execute("ALTER DATABASE " + source.getDatabaseName() + " OWNER TO " + role);
    var installer = configured(source.getDatabaseName());
    installer.setUser(role); installer.setPassword("synthetic-installer-password");
    PostgresDatabaseLifecycle.initializeEmpty(installer);
    PostgresDatabaseLifecycle.validateExisting(installer);
  }

  private String initializeAt(CountDownLatch start) throws Exception {
    start.await();
    try { PostgresDatabaseLifecycle.initializeEmpty(source); return "initialized"; }
    catch (IllegalStateException failure) { return failure.getMessage(); }
  }

  private static PGSimpleDataSource configured(String database) {
    var ds = new PGSimpleDataSource();
    ds.setServerNames(new String[] {"127.0.0.1"});
    ds.setPortNumbers(new int[] {POSTGRES.getMappedPort(5432)});
    ds.setDatabaseName(database); ds.setUser(POSTGRES.getUsername()); ds.setPassword(POSTGRES.getPassword());
    ds.setCurrentSchema("public"); ds.setConnectTimeout(3); ds.setSocketTimeout(5);
    return ds;
  }

  private void execute(String sql) throws Exception {
    try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(sql); }
  }

  private int tables() throws Exception {
    try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(
        "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")) { r.next(); return r.getInt(1); }
  }

  private String history() throws Exception {
    try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(
        "SELECT jsonb_agg(to_jsonb(h) ORDER BY installed_rank)::text FROM public.flyway_schema_history h")) {
      r.next(); return r.getString(1);
    }
  }
}
