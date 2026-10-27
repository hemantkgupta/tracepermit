package io.tracepermit.persistence.operations;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** Characterizes metadata observability, not a second readiness/security validator. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class OperationMetadataVisibilityContract {
  private static final String CONTROL = "tp_operation_authority_controls";
  private static final String HEAD = "tp_operation_lifecycle_heads";
  private static final String SELECT = "mvq_select";
  private static final String REGISTER = "mvq_register";
  private static final Map<String, Integer> COLUMNS = Map.of(
      "tp_business_operations", 6, "tp_business_operation_facts", 3,
      HEAD, 3, "tp_operation_lifecycle_facts", 7, CONTROL, 5,
      "tp_operation_schema_history", 10);
  private static final Map<String, Integer> CHECKS = Map.of(
      "tp_business_operations", 1, "tp_business_operation_facts", 2,
      HEAD, 1, "tp_operation_lifecycle_facts", 3, CONTROL, 3,
      "tp_operation_schema_history", 0);
  private JdbcDatabaseContainer<?> database;
  private Source owner;
  private Source select;
  private Source registration;
  private boolean pg;

  abstract JdbcDatabaseContainer<?> container();

  @BeforeAll
  @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void start() {
    database = container().withPassword(UUID.randomUUID().toString())
        .withStartupAttempts(1).withStartupTimeout(Duration.ofSeconds(90));
    int port = database.getDockerImageName().startsWith("postgres:") ? 5432 : 3306;
    database.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
        new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(port))));
    try {
      database.start();
      pg = database.getDockerImageName().startsWith("postgres:");
      owner = new Source(pg ? database.getUsername() : "root", database.getPassword(), false);
      OperationDatabase.initializeAuthorityEmpty(owner);
      String selectPassword = UUID.randomUUID().toString();
      String registrationPassword = UUID.randomUUID().toString();
      for (var entry : Map.of(SELECT, selectPassword, REGISTER, registrationPassword).entrySet()) {
        execute(owner, pg ? "CREATE ROLE " + entry.getKey() + " LOGIN PASSWORD '" + entry.getValue() + "'"
            : "CREATE USER '" + entry.getKey() + "'@'%' IDENTIFIED BY '" + entry.getValue() + "'");
        if (pg) execute(owner, "GRANT USAGE ON SCHEMA public TO " + entry.getKey());
      }
      // SELECT control is deliberately table-scoped. Never substitute the existing
      // authority fixture's MariaDB database-wide SELECT to make metadata visible.
      for (String table : COLUMNS.keySet()) {
        grant(SELECT, "SELECT", table);
        if (!table.equals(CONTROL)) {
          grant(REGISTER, table.equals("tp_operation_schema_history") ? "SELECT" : "SELECT, INSERT", table);
        }
      }
      grant(REGISTER, "UPDATE(snapshot_hash, snapshot_json)", HEAD);
      select = new Source(SELECT, selectPassword, false);
      registration = new Source(REGISTER, registrationPassword, false);
      try (Connection c = owner.getConnection()) {
        assertEquals(pg ? "42.7.14" : "3.5.7", c.getMetaData().getDriverVersion());
        System.out.printf("MVQ version server=%s driver=%s%n", c.getMetaData().getDatabaseProductVersion(), c.getMetaData().getDriverVersion());
      }
      System.out.println("MVQ engine=" + database.getDockerImageName() + " fixture=READY");
    } catch (Exception failure) {
      stop();
      throw new AssertionError("FIXTURE_START_FAILED");
    }
  }

  @AfterAll
  void stop() {
    if (database == null) return;
    var executor = Executors.newSingleThreadExecutor(r -> {
      var thread = new Thread(r, "mvq-owned-container-cleanup");
      thread.setDaemon(true);
      return thread;
    });
    try {
      executor.submit(database::stop).get(25, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError("OWNED_CONTAINER_CLEANUP_FAILED");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void requiredStructureSeparatesDeclaredChecksFromPhysicalKeysAndVisibility() {
    for (String table : COLUMNS.keySet().stream().sorted().toList()) {
      List<String> ownerColumns = columns(owner, table);
      assertEquals(COLUMNS.get(table), ownerColumns.size(), "OWNER_COLUMNS");
      int ownerChecks = checks(owner, table);
      assertEquals(CHECKS.get(table), ownerChecks, "OWNER_DECLARED_CHECKS");
      int expectedTableChecks = ownerChecks + (pg ? (int) ownerColumns.stream().filter(c -> c.endsWith(":NO")).count() : 0);
      int expectedDeclaredKeys = table.equals(CONTROL) || table.equals("tp_operation_schema_history") ? 1 : 2;
      for (var role : roles().entrySet()) {
        Source source = role.getValue();
        boolean accessible = source != registration || !table.equals(CONTROL);
        List<String> visible = columns(source, table);
        assertEquals(accessible ? ownerColumns : List.of(), visible, "REQUIRED_COLUMN_OBSERVABILITY");
        int directChecks = checks(source, table);
        int tableChecks = count(source, "SELECT COUNT(*) FROM information_schema.table_constraints WHERE "
            + schemaColumn("table_schema") + " AND table_name='" + table + "' AND constraint_type='CHECK'");
        int declaredKeys = count(source, "SELECT COUNT(*) FROM information_schema.table_constraints WHERE "
            + schemaColumn("table_schema") + " AND table_name='" + table
            + "' AND constraint_type IN ('PRIMARY KEY','UNIQUE','FOREIGN KEY')");
        assertTrue(directChecks <= ownerChecks, "CHECK_VISIBILITY_BOUND");
        if (source != owner) assertEquals(0, directChecks, "TABLE_SCOPED_DIRECT_CHECKS_HIDDEN");
        boolean tableConstraintsVisible = source == owner || source == registration
            && !table.equals(CONTROL) && !table.equals("tp_operation_schema_history");
        assertEquals(tableConstraintsVisible ? expectedTableChecks : 0, tableChecks, "TABLE_CONSTRAINT_CHECK_VISIBILITY");
        assertEquals(tableConstraintsVisible ? expectedDeclaredKeys : 0, declaredKeys, "DECLARED_KEY_VISIBILITY");
        if (!pg) {
          if (accessible) {
            String ddl = showCreate(source, table);
            assertTrue(ddl.equals(showCreate(owner, table)), "SHOW_CREATE_COMPLETE_DECLARATION");
            if (CHECKS.get(table) > 0) assertTrue(ddl.contains("CHECK"), "SHOW_CREATE_CHECK_POSITIVE");
          } else {
            denied(source, "SHOW CREATE TABLE " + table);
          }
        }
        System.out.printf("MVQ structure role=%s table=%s columns=%d directChecks=%d tableChecks=%d declaredKeys=%d category=%s%n",
            role.getKey(), table, visible.size(), directChecks, tableChecks, declaredKeys,
            directChecks == ownerChecks && accessible ? "OBSERVABLE" : "REQUIRED_METADATA_OBSERVABILITY_FAILURE");
      }
    }
    // Owner-only declarations are meaningful positive controls; no assertion of
    // current production validator acceptance under limited visibility is made.
    String clauses = strings(owner, pg ? """
        SELECT pg_get_constraintdef(oid) FROM pg_catalog.pg_constraint
        WHERE conrelid='tp_operation_authority_controls'::regclass AND contype='c' ORDER BY conname
        """ : "SELECT check_clause FROM information_schema.check_constraints WHERE "
            + schemaColumn("constraint_schema") + " AND table_name='" + CONTROL + "' ORDER BY constraint_name").toString();
    for (String required : List.of("lock_marker", "recovery_generation", "control_state", "9007199254740991", "quarantined")) {
      assertTrue(clauses.contains(required), "OWNER_REQUIRED_CHECK_DECLARATION");
    }
  }

  @Test
  void knownExecutableObjectsExposeHiddenZeroAndPostgresTruncateCatalogControl() {
    try {
      if (pg) {
        execute(owner, "CREATE SCHEMA mvq_private");
        execute(owner, "CREATE FUNCTION mvq_private.mvq_uncalled() RETURNS int LANGUAGE sql AS 'SELECT 1'");
        execute(owner, "REVOKE ALL ON FUNCTION mvq_private.mvq_uncalled() FROM PUBLIC");
        execute(owner, "CREATE FUNCTION mvq_private.mvq_trigger() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");
        execute(owner, "REVOKE ALL ON FUNCTION mvq_private.mvq_trigger() FROM PUBLIC");
        execute(owner, "CREATE TRIGGER mvq_control BEFORE UPDATE ON " + CONTROL + " FOR EACH ROW EXECUTE FUNCTION mvq_private.mvq_trigger()");
        execute(owner, "CREATE TRIGGER mvq_head BEFORE UPDATE ON " + HEAD + " FOR EACH ROW EXECUTE FUNCTION mvq_private.mvq_trigger()");
        execute(owner, "CREATE TRIGGER mvq_truncate BEFORE TRUNCATE ON " + CONTROL + " FOR EACH STATEMENT EXECUTE FUNCTION mvq_private.mvq_trigger()");
      } else {
        execute(owner, "CREATE TRIGGER mvq_control BEFORE UPDATE ON " + CONTROL + " FOR EACH ROW SET @mvq_unused = 1");
        execute(owner, "CREATE TRIGGER mvq_head BEFORE UPDATE ON " + HEAD + " FOR EACH ROW SET @mvq_unused = 1");
        execute(owner, "CREATE PROCEDURE mvq_uncalled() SELECT 1");
        execute(owner, "CREATE EVENT mvq_disabled ON SCHEDULE EVERY 1 DAY DISABLE DO SET @mvq_unused = 1");
      }
      for (var role : roles().entrySet()) {
        Source source = role.getValue();
        int control = triggerCount(source, "mvq_control");
        int head = triggerCount(source, "mvq_head");
        int routine = count(source, "SELECT COUNT(*) FROM information_schema.routines WHERE "
            + (pg ? "routine_schema='mvq_private'" : schemaColumn("routine_schema")) + " AND routine_name='mvq_uncalled'");
        int events = pg ? 0 : count(source, "SELECT COUNT(*) FROM information_schema.events WHERE "
            + schemaColumn("event_schema") + " AND event_name='mvq_disabled'");
        assertEquals(source == owner ? 1 : 0, control, "CONTROL_TRIGGER_POSITIVE_DETECTION");
        int expectedHead = source == owner || source == registration && (pg || database.getDockerImageName().contains("11.4")) ? 1 : 0;
        assertEquals(expectedHead, head, "VERSION_SPECIFIC_HEAD_TRIGGER_DETECTION");
        assertEquals(source == owner ? 1 : 0, routine, "UNCALLED_ROUTINE_DETECTION");
        assertEquals(!pg && source == owner ? 1 : 0, events, "DISABLED_EVENT_DETECTION");
        System.out.printf("MVQ hidden role=%s controlTriggers=%d headTriggers=%d routines=%d events=%d category=%s%n",
            role.getKey(), control, head, routine, events, source == owner ? "OWNER_POSITIVE" : "INCOMPLETE_INVENTORY");
        if (pg) {
          assertEquals(0, triggerCount(source, "mvq_truncate"), "INFORMATION_SCHEMA_OMITS_TRUNCATE");
          int catalog = count(source, "SELECT COUNT(*) FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_proc p ON p.oid=t.tgfoid JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE t.tgname='mvq_truncate' AND NOT t.tgisinternal AND n.nspname='mvq_private'");
          assertEquals(1, catalog, "CATALOG_TRUNCATE_POSITIVE");
          assertEquals(1, count(source, "SELECT COUNT(*) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='mvq_private' AND p.proname='mvq_uncalled'"));
          System.out.printf("MVQ postgresCatalog role=%s truncateTriggers=%d outsidePublicRoutines=1%n", role.getKey(), catalog);
        }
      }
      if (!pg) assertEquals(1, count(owner, "SELECT COUNT(*) FROM information_schema.events WHERE "
          + schemaColumn("event_schema") + " AND event_name='mvq_disabled' AND status='DISABLED'"));
    } finally {
      if (pg) {
        execute(owner, "DROP TRIGGER IF EXISTS mvq_control ON " + CONTROL);
        execute(owner, "DROP TRIGGER IF EXISTS mvq_head ON " + HEAD);
        execute(owner, "DROP TRIGGER IF EXISTS mvq_truncate ON " + CONTROL);
        execute(owner, "DROP FUNCTION IF EXISTS mvq_private.mvq_trigger()");
        execute(owner, "DROP FUNCTION IF EXISTS mvq_private.mvq_uncalled()");
        execute(owner, "DROP SCHEMA IF EXISTS mvq_private");
      } else {
        execute(owner, "DROP TRIGGER IF EXISTS mvq_control");
        execute(owner, "DROP TRIGGER IF EXISTS mvq_head");
        execute(owner, "DROP PROCEDURE IF EXISTS mvq_uncalled");
        execute(owner, "DROP EVENT IF EXISTS mvq_disabled");
      }
    }
  }

  @Test
  void realImportedKeyRoutesDistinguishVisibleHiddenMissingAndInvalidKeys() {
    try {
      execute(owner, "CREATE TABLE mvq_parent (id INTEGER PRIMARY KEY, alternate INTEGER UNIQUE)");
      execute(owner, "CREATE TABLE mvq_valid (parent_id INTEGER, FOREIGN KEY(parent_id) REFERENCES mvq_parent(id) ON UPDATE CASCADE ON DELETE RESTRICT)");
      execute(owner, "CREATE TABLE mvq_missing (parent_id INTEGER)");
      execute(owner, "CREATE TABLE mvq_invalid (parent_id INTEGER, FOREIGN KEY(parent_id) REFERENCES mvq_parent(alternate) ON UPDATE CASCADE ON DELETE RESTRICT)");
      execute(owner, "CREATE TABLE mvq_denied (parent_id INTEGER, FOREIGN KEY(parent_id) REFERENCES mvq_parent(id) ON UPDATE CASCADE ON DELETE RESTRICT)");
      for (String table : List.of("mvq_parent", "mvq_valid", "mvq_missing", "mvq_invalid")) {
        grant(SELECT, "SELECT", table);
        grant(REGISTER, "SELECT, INSERT", table);
      }
      for (var role : roles().entrySet()) {
        for (boolean informationSchema : pg ? List.of(false) : List.of(false, true)) {
          Source source = role.getValue().route(informationSchema);
          boolean hidden = !pg && informationSchema && role.getValue() == select;
          List<Key> valid = keys(source, "mvq_valid");
          if (hidden) assertEquals(List.of(), valid, "REQUIRED_FK_OBSERVABILITY_FAILURE");
          else requireExactKey(valid, "mvq_valid", "mvq_parent", "parent_id", "id", DatabaseMetaData.importedKeyCascade, DatabaseMetaData.importedKeyRestrict);
          List<Key> missing = keys(source, "mvq_missing");
          assertEquals(List.of(), missing, "MISSING_PHYSICAL_KEY");
          assertThrows(AssertionError.class, () -> requireExactKey(missing, "mvq_missing", "mvq_parent", "parent_id", "id", DatabaseMetaData.importedKeyCascade, DatabaseMetaData.importedKeyRestrict));
          if (!hidden) {
            List<Key> invalid = keys(source, "mvq_invalid");
            requireExactKey(invalid, "mvq_invalid", "mvq_parent", "parent_id", "alternate", DatabaseMetaData.importedKeyCascade, DatabaseMetaData.importedKeyRestrict);
            assertThrows(AssertionError.class, () -> requireExactKey(invalid, "mvq_invalid", "mvq_parent", "parent_id", "id", DatabaseMetaData.importedKeyCascade, DatabaseMetaData.importedKeyRestrict));
          }
          String[] children = {"tp_business_operation_facts", HEAD, "tp_operation_lifecycle_facts"};
          String[] parents = {"tp_business_operations", "tp_business_operations", HEAD};
          for (int i = 0; i < children.length; i++) {
            List<Key> actual = keys(source, children[i]);
            if (hidden) assertEquals(List.of(), actual, "REQUIRED_PHYSICAL_KEY_HIDDEN");
            else requireExactKey(actual, children[i], parents[i], "operation_id", "operation_id",
                pg ? DatabaseMetaData.importedKeyNoAction : DatabaseMetaData.importedKeyRestrict,
                pg ? DatabaseMetaData.importedKeyNoAction : DatabaseMetaData.importedKeyRestrict);
          }
          if (role.getValue() == owner) {
            requireExactKey(keys(source, "mvq_denied"), "mvq_denied", "mvq_parent", "parent_id", "id", DatabaseMetaData.importedKeyCascade, DatabaseMetaData.importedKeyRestrict);
          } else if (!pg) {
            denied(source, "SHOW CREATE TABLE mvq_denied");
            assertEquals(List.of(), keys(source, "mvq_denied"), "DENIED_SHOW_CREATE_FALLBACK_IS_HIDDEN");
          }
          System.out.printf("MVQ fk role=%s route=%s valid=%d missing=%d category=%s%n",
              role.getKey(), informationSchema ? "getImportedKeysUsingIs=true" : "DEFAULT", valid.size(), missing.size(), hidden ? "REQUIRED_METADATA_OBSERVABILITY_FAILURE" : "EXACT_KEY_VISIBLE");
        }
      }
    } finally {
      for (String table : List.of("mvq_denied", "mvq_invalid", "mvq_missing", "mvq_valid", "mvq_parent")) execute(owner, "DROP TABLE IF EXISTS " + table);
    }
  }

  @Test
  void validOwnerStatementsRequireRealRuntimePermissionDenial() {
    String hash = "sha256:" + "1".repeat(64);
    try {
      execute(owner, "INSERT INTO " + CONTROL + "(scope_hash,profile_hash,recovery_generation,control_state) VALUES ('" + hash + "','" + hash + "',1,'staged')");
      for (String change : List.of("profile_hash='sha256:" + "2".repeat(64) + "'", "recovery_generation=2", "control_state='quarantined'")) {
        String sql = "UPDATE " + CONTROL + " SET " + change + " WHERE scope_hash='" + hash + "'";
        try (Connection c = owner.getConnection(); Statement s = statement(c)) {
          c.setAutoCommit(false);
          try { assertEquals(1, s.executeUpdate(sql), "OWNER_VALID_CONTROL_CHANGE"); }
          finally { c.rollback(); }
        } catch (SQLException failure) { throw safe(failure); }
        for (Source runtime : List.of(select, registration)) denied(runtime, sql);
      }
      var probes = new ArrayList<Probe>();
      probes.add(new Probe("CREATE TABLE mvq_forbidden(id INTEGER)", "DROP TABLE mvq_forbidden"));
      probes.add(new Probe("ALTER TABLE " + CONTROL + " ADD COLUMN mvq_extra INTEGER", "ALTER TABLE " + CONTROL + " DROP COLUMN mvq_extra"));
      if (pg) {
        probes.add(new Probe("CREATE FUNCTION public.mvq_forbidden() RETURNS int LANGUAGE sql AS 'SELECT 1'", "DROP FUNCTION public.mvq_forbidden()"));
        execute(owner, "CREATE FUNCTION public.mvq_trigger_denial() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");
        probes.add(new Probe("CREATE TRIGGER mvq_forbidden BEFORE UPDATE ON " + CONTROL + " FOR EACH ROW EXECUTE FUNCTION public.mvq_trigger_denial()", "DROP TRIGGER mvq_forbidden ON " + CONTROL));
      } else {
        probes.add(new Probe("CREATE PROCEDURE mvq_forbidden() SELECT 1", "DROP PROCEDURE mvq_forbidden"));
        probes.add(new Probe("CREATE TRIGGER mvq_forbidden BEFORE UPDATE ON " + CONTROL + " FOR EACH ROW SET @mvq_unused=1", "DROP TRIGGER mvq_forbidden"));
        probes.add(new Probe("CREATE EVENT mvq_forbidden ON SCHEDULE EVERY 1 DAY DISABLE DO SET @mvq_unused=1", "DROP EVENT mvq_forbidden"));
      }
      for (Probe probe : probes) {
        execute(owner, probe.sql());
        execute(owner, probe.cleanup());
        for (Source runtime : List.of(select, registration)) denied(runtime, probe.sql());
      }
      // Existing column-scoped UPDATE is still usable without executing a business operation.
      execute(registration, "UPDATE " + HEAD + " SET snapshot_hash=snapshot_hash,snapshot_json=snapshot_json WHERE 1=0");
      denied(registration, "UPDATE " + HEAD + " SET operation_id=operation_id WHERE 1=0");
      System.out.println("MVQ authority category=OWNER_VALID_RUNTIME_PERMISSION_DENIED");
    } finally {
      if (pg) execute(owner, "DROP FUNCTION IF EXISTS public.mvq_trigger_denial()");
      execute(owner, "DELETE FROM " + CONTROL + " WHERE scope_hash='" + hash + "'");
    }
  }

  private record Probe(String sql, String cleanup) {}
  private record Key(String childCatalog, String childSchema, String child, String childColumn,
      String parentCatalog, String parentSchema, String parent, String parentColumn,
      int sequence, int update, int delete, int deferrability) {}

  private Map<String, Source> roles() {
    Map<String, Source> roles = new LinkedHashMap<>();
    roles.put("owner", owner); roles.put("table_select", select); roles.put("registration", registration);
    return roles;
  }

  private void grant(String user, String privileges, String table) {
    execute(owner, "GRANT " + privileges + " ON " + (pg ? table : "`" + database.getDatabaseName() + "`." + table)
        + " TO " + (pg ? user : "'" + user + "'@'%'"));
  }

  private String schemaColumn(String column) {
    return column + (pg ? "='public'" : "=DATABASE()");
  }

  private int checks(Source source, String table) {
    return count(source, "SELECT COUNT(*) FROM information_schema.check_constraints WHERE "
        + schemaColumn("constraint_schema") + (pg
            ? " AND constraint_name IN (SELECT conname FROM pg_catalog.pg_constraint WHERE conrelid='" + table + "'::regclass AND contype='c')"
            : " AND table_name='" + table + "'"));
  }

  private List<String> columns(Source source, String table) {
    return strings(source, "SELECT column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns WHERE "
        .replace("column_name || ':' || data_type || ':' || is_nullable", pg ? "column_name || ':' || data_type || ':' || is_nullable" : "CONCAT(column_name,':',data_type,':',is_nullable)")
        + schemaColumn("table_schema") + " AND table_name='" + table + "' ORDER BY ordinal_position");
  }

  private int triggerCount(Source source, String name) {
    return count(source, "SELECT COUNT(*) FROM information_schema.triggers WHERE "
        + schemaColumn("trigger_schema") + " AND trigger_name='" + name + "'");
  }

  private static Statement statement(Connection c) throws SQLException {
    Statement s = c.createStatement(); s.setQueryTimeout(5); s.setMaxRows(200); return s;
  }

  private static void execute(Source source, String sql) {
    try (Connection c = source.getConnection(); Statement s = statement(c)) { s.execute(sql); }
    catch (SQLException failure) { throw safe(failure); }
  }

  private static int count(Source source, String sql) {
    List<String> values = strings(source, sql);
    assertEquals(1, values.size(), "BOUNDED_AGGREGATE");
    return Integer.parseInt(values.getFirst());
  }

  private static List<String> strings(Source source, String sql) {
    try (Connection c = source.getConnection(); Statement s = statement(c); ResultSet r = s.executeQuery(sql)) {
      List<String> values = new ArrayList<>();
      while (r.next()) { assertTrue(values.size() < 199, "METADATA_ROW_BOUND"); values.add(r.getString(1)); }
      return values;
    } catch (SQLException failure) { throw safe(failure); }
  }

  private static String showCreate(Source source, String table) {
    try (Connection c = source.getConnection(); Statement s = statement(c); ResultSet r = s.executeQuery("SHOW CREATE TABLE " + table)) {
      assertTrue(r.next(), "SHOW_CREATE_POSITIVE");
      String ddl = r.getString(2);
      assertTrue(ddl.length() < 32768, "DDL_SIZE_BOUND");
      assertFalse(r.next(), "DDL_ROW_BOUND");
      return ddl;
    } catch (SQLException failure) { throw safe(failure); }
  }

  private List<Key> keys(Source source, String table) {
    try (Connection c = source.getConnection(); ResultSet r = c.getMetaData().getImportedKeys(c.getCatalog(), pg ? "public" : null, table)) {
      List<Key> keys = new ArrayList<>();
      while (r.next()) {
        assertTrue(keys.size() < 4, "FK_ROW_BOUND");
        keys.add(new Key(r.getString("FKTABLE_CAT"), r.getString("FKTABLE_SCHEM"), r.getString("FKTABLE_NAME"), r.getString("FKCOLUMN_NAME"),
            r.getString("PKTABLE_CAT"), r.getString("PKTABLE_SCHEM"), r.getString("PKTABLE_NAME"), r.getString("PKCOLUMN_NAME"),
            r.getInt("KEY_SEQ"), r.getInt("UPDATE_RULE"), r.getInt("DELETE_RULE"), r.getInt("DEFERRABILITY")));
      }
      return keys;
    } catch (SQLException failure) { throw safe(failure); }
  }

  private void requireExactKey(List<Key> keys, String child, String parent, String childColumn, String parentColumn, int update, int delete) {
    assertEquals(List.of(new Key(database.getDatabaseName(), pg ? "public" : null, child, childColumn,
        database.getDatabaseName(), pg ? "public" : null, parent, parentColumn, 1, update, delete, DatabaseMetaData.importedKeyNotDeferrable)), keys, "EXACT_PHYSICAL_KEY");
  }

  private void denied(Source source, String sql) {
    try (Connection c = source.getConnection(); Statement s = statement(c)) {
      try { s.execute(sql); }
      catch (SQLException failure) {
        if (pg) assertEquals("42501", failure.getSQLState(), "PERMISSION_SQLSTATE");
        else {
          assertEquals("42000", failure.getSQLState(), "PERMISSION_SQLSTATE");
          assertTrue(List.of(1044, 1142, 1143, 1227, 1370).contains(failure.getErrorCode()), "PERMISSION_VENDOR_CODE");
        }
        System.out.printf("MVQ denial role=%s sqlState=%s vendor=%d category=PERMISSION_DENIED%n", source.user, failure.getSQLState(), failure.getErrorCode());
        return;
      }
      fail("RUNTIME_AUTHORITY_UNEXPECTEDLY_GRANTED");
    } catch (SQLException failure) { throw safe(failure); }
  }

  private static AssertionError safe(SQLException failure) {
    return new AssertionError("FIXTURE_SQL_FAILED state=" + failure.getSQLState() + " vendor=" + failure.getErrorCode());
  }

  private final class Source implements DataSource {
    private final String user;
    private final String password;
    private final boolean informationSchema;
    private Source(String user, String password, boolean informationSchema) {
      this.user = user; this.password = password; this.informationSchema = informationSchema;
    }
    private Source route(boolean route) { return new Source(user, password, route); }
    @Override public Connection getConnection() throws SQLException {
      Properties properties = new Properties();
      properties.setProperty("user", user); properties.setProperty("password", password);
      properties.setProperty("connectTimeout", pg ? "5" : "5000");
      properties.setProperty("socketTimeout", pg ? "10" : "10000");
      if (pg) properties.setProperty("options", "-c statement_timeout=5000 -c lock_timeout=3000");
      else if (informationSchema) properties.setProperty("getImportedKeysUsingIs", "true");
      try {
        Connection c = DriverManager.getConnection(database.getJdbcUrl(), properties);
        try (Statement s = statement(c)) {
          if (!pg) {
            s.execute("SET SESSION innodb_lock_wait_timeout=3");
            s.execute("SET SESSION lock_wait_timeout=3");
            s.execute("SET SESSION max_statement_time=5");
          }
        } catch (SQLException failure) { c.close(); throw failure; }
        return c;
      } catch (SQLException failure) {
        throw new SQLException("FIXTURE_CONNECTION_FAILED", failure.getSQLState(), failure.getErrorCode());
      }
    }
    @Override public Connection getConnection(String username, String suppliedPassword) throws SQLException { throw new SQLFeatureNotSupportedException("FIXTURE_FIXED_ROLE"); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() { return Logger.getLogger("mvq"); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException("FIXTURE_NO_UNWRAP"); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
  }
}
