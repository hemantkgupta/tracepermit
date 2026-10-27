package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;

import io.tracepermit.application.BusinessOperationStorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Isolated operation store lifecycle; it never adopts or migrates the existing enforcement DB. */
public final class OperationDatabase {
  private static final Set<String> V1_TABLES = Set.of(
      "tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts");
  private static final Set<String> V2_TABLES = Set.of(
      "tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts",
      "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts");
  private static final Set<String> V3_TABLES = Set.of(
      "tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts",
      "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_authority_controls");
  private static final Set<String> V4_TABLES = Set.of(
      "tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts",
      "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_authority_controls",
      "tp_operation_review_bindings");
  private static final Set<String> V6_TABLES = Set.of(
      "tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts",
      "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_authority_controls",
      "tp_operation_review_bindings", "tp_operation_admissions", "tp_operation_admission_outbox");
  private static final Set<String> V7_TABLES = java.util.stream.Stream.concat(V6_TABLES.stream(),
      java.util.stream.Stream.of("tp_operation_delivery_events")).collect(java.util.stream.Collectors.toUnmodifiableSet());

  enum Engine {
    POSTGRESQL("postgresql"), MARIADB("mariadb");

    final String directory;

    Engine(String directory) {
      this.directory = directory;
    }

    boolean duplicate(SQLException failure) {
      return this == POSTGRESQL
          ? "23505".equals(failure.getSQLState())
          : failure.getErrorCode() == 1062 && "23000".equals(failure.getSQLState());
    }
  }

  private OperationDatabase() {}

  /** Explicit first install in an empty dedicated schema/database; runtime never calls this. */
  public static void initializeEmpty(DataSource source) {
    initializeEmpty(source, "2");
  }

  /** Explicit opt-in install; controls start absent/inactive, never automatically ready. */
  public static void initializeAuthorityEmpty(DataSource source) {
    initializeEmpty(source, "3");
  }

  /** Explicit V4 install. No default upgrade, policy activation or execution capability. */
  public static void initializeReviewEmpty(DataSource source) {
    initializeEmpty(source, "4");
  }

  /** Explicit stopped-writer V6 opt-in; default installation remains V2. */
  public static void initializeAdmissionEmpty(DataSource source) {
    initializeEmpty(source, "6");
  }

  /** Explicit stopped-writer synthetic delivery opt-in. No runtime migration or default change. */
  public static void initializeDeliveryEmpty(DataSource source) { initializeEmpty(source, "7"); }

  private static void initializeEmpty(DataSource source, String version) {
    try (Connection connection = source.getConnection()) {
      Engine engine = inspectWriter(connection);
      if (!tables(connection).isEmpty()) throw error(OPERATION_SCHEMA_NOT_READY);
      requireNoExecutableObjects(connection, engine);
      flyway(source, engine, version).migrate();
      validateSchema(source, version);
    } catch (BusinessOperationStorageException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_SCHEMA_NOT_READY);
    }
  }

  /** Validate only: no automatic migrate, baseline, repair, clean or adoption. */
  public static void validateExisting(DataSource source) {
    String version;
    try (Connection connection = source.getConnection()) {
      Set<String> names = tables(connection);
      version = names.contains("tp_operation_delivery_events") ? "7" : names.contains("tp_operation_admissions") ? "6" : names.contains("tp_operation_review_bindings") ? "4"
          : names.contains("tp_operation_authority_controls") ? "3" : "2";
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_SCHEMA_NOT_READY);
    }
    validateSchema(source, version);
  }

  /** V3/V4 control callers; default V2 registration/withdrawal remains supported. */
  static void validateAuthorityExisting(DataSource source) {
    String version;
    try (Connection connection = source.getConnection()) {
      version = hasDelivery(connection) ? "7" : hasAdmissions(connection) ? "6" : hasReviewBindings(connection) ? "4" : "3";
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_SCHEMA_NOT_READY);
    }
    // Version routing is a stopped-DDL startup observation, not an authority transaction.
    // Release its connection before validation/Flyway obtains its own bounded connections.
    validateSchema(source, version);
  }

  static void validateReviewExisting(DataSource source) {
    String version;
    try (Connection connection = source.getConnection()) {
      version = hasDelivery(connection) ? "7" : hasAdmissions(connection) ? "6" : "4";
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_SCHEMA_NOT_READY);
    }
    validateSchema(source, version);
  }

  static void validateAdmissionExisting(DataSource source) {
    String version;
    try (Connection connection = source.getConnection()) { version = hasDelivery(connection) ? "7" : "6"; }
    catch (SQLException | RuntimeException failure) { throw error(OPERATION_SCHEMA_NOT_READY); }
    validateSchema(source, version);
  }

  static void validateDeliveryExisting(DataSource source) { validateSchema(source, "7"); }

  static boolean hasDelivery(Connection connection) throws SQLException {
    try (var statement = JdbcBusinessOperationRepository.prepare(connection,
        "SELECT version FROM tp_operation_schema_history WHERE version = '7'"); var rows = statement.executeQuery()) {
      return rows.next(); // Includes failed/partial history; never fall back to an older writer.
    }
  }

  static void requireDeliveryVersion(Connection connection) throws SQLException {
    requireProtocol(connection, true);
    if (!hasDelivery(connection)) throw error(OPERATION_SCHEMA_NOT_READY);
  }

  static boolean hasAdmissions(Connection connection) throws SQLException {
    try (var statement = JdbcBusinessOperationRepository.prepare(connection,
        "SELECT version FROM tp_operation_schema_history WHERE version IN ('5', '6')" ); var rows = statement.executeQuery()) {
      return rows.next(); // A partial/failed V5 must route to validation/refusal, never V4 fallback.
    }
  }

  static boolean hasReviewBindings(Connection connection) throws SQLException {
    if (tables(connection).contains("tp_operation_review_bindings")) return true;
    // A retained V3 runtime can have no SELECT on the newly installed table. MariaDB then hides
    // it from table metadata. History is already readable by that runtime: never mistake filtered
    // table visibility for permission to use the old, head-first withdrawal path after V4.
    try (var statement = JdbcBusinessOperationRepository.prepare(connection,
        "SELECT version FROM tp_operation_schema_history WHERE version = '4' AND success = TRUE");
        var rows = statement.executeQuery()) {
      return rows.next();
    }
  }

  /** Retained V4 readers/writers cannot silently operate after an unknown protocol upgrade. */
  static void requireReviewVersion(Connection connection) throws SQLException {
    requireProtocol(connection, false);
  }

  static void requireAdmissionVersion(Connection connection) throws SQLException {
    requireProtocol(connection, true);
  }

  private static void requireProtocol(Connection connection, boolean admission) throws SQLException {
    try (var statement = JdbcBusinessOperationRepository.prepare(connection, """
        SELECT version, success FROM tp_operation_schema_history ORDER BY installed_rank DESC
        """); var rows = statement.executeQuery()) {
      if (!rows.next()) throw error(OPERATION_SCHEMA_NOT_READY);
      String version = rows.getString(1);
      boolean deliveryTable = tables(connection).contains("tp_operation_delivery_events");
      if (version == null || !(admission ? Set.of("6", "7") : Set.of("4", "6", "7")).contains(version) || !rows.getBoolean(2)
          || ("7".equals(version) != deliveryTable)) {
        throw error(OPERATION_SCHEMA_NOT_READY);
      }
    }
  }

  /** Explicit V6->V7: populated closed replay, rollback and cleanup all precede its single DDL. */
  public static void upgradeDeliveryFromV6(DataSource source) {
    validateSchema(source, "6");
    Engine engine;
    try (Connection connection = source.getConnection()) {
      engine = inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try (var statement = JdbcBusinessOperationRepository.prepare(connection,
          "SELECT operation_id, snapshot_json FROM tp_business_operations"); var rows = statement.executeQuery()) {
        while (rows.next()) {
          var context = StoredOperationJson.read(rows.getString(2), JdbcBusinessOperationRepository.MAX_BYTES).path("trustedContext");
          var scope = new io.tracepermit.application.BusinessOperationScope(context.path("tenantId").asText(),
              context.path("workspaceId").asText(), context.path("requesterPrincipalId").asText());
          var initial = JdbcBusinessOperationRepository.read(connection, scope, "operation_id", rows.getString(1))
              .orElseThrow(() -> error(OPERATION_SCHEMA_NOT_READY));
          JdbcBusinessOperationLifecycleRepository.current(connection, scope, rows.getString(1), initial, false);
        }
      } finally { connection.rollback(); }
    } catch (SQLException | RuntimeException failure) { throw error(OPERATION_SCHEMA_NOT_READY); }
    try { flyway(source, engine, "7").migrate(); validateDeliveryExisting(source); }
    catch (RuntimeException failure) { throw error(OPERATION_SCHEMA_NOT_READY); }
  }

  /** Explicit V4->V6, with populated closed replay BEFORE either individual CREATE TABLE. */
  public static void upgradeAdmissionFromV4(DataSource source) {
    validateSchema(source, "4");
    Engine engine;
    try (Connection connection = source.getConnection()) {
      engine = inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try (var statement = JdbcBusinessOperationRepository.prepare(connection,
          "SELECT operation_id, snapshot_json FROM tp_business_operations"); var rows = statement.executeQuery()) {
        while (rows.next()) {
          var json = StoredOperationJson.read(rows.getString(2), JdbcBusinessOperationRepository.MAX_BYTES);
          var context = json.path("trustedContext");
          var scope = new io.tracepermit.application.BusinessOperationScope(context.path("tenantId").asText(),
              context.path("workspaceId").asText(), context.path("requesterPrincipalId").asText());
          var initial = JdbcBusinessOperationRepository.read(connection, scope, "operation_id", rows.getString(1))
              .orElseThrow(() -> error(OPERATION_SCHEMA_NOT_READY));
          JdbcBusinessOperationLifecycleRepository.current(connection, scope, rows.getString(1), initial, false);
        }
      } finally { connection.rollback(); }
    } catch (SQLException | RuntimeException failure) { throw error(OPERATION_SCHEMA_NOT_READY); }
    // The stopped-writer read/rollback/cleanup has ended before Flyway or validation borrows.
    try {
      flyway(source, engine, "6").migrate();
      validateAdmissionExisting(source);
    } catch (RuntimeException failure) { throw error(OPERATION_SCHEMA_NOT_READY); }
  }

  /** Explicit stopped-writer V3->V4. Validate populated immutable/current state BEFORE DDL. */
  public static void upgradeReviewFromV3(DataSource source) {
    validateSchema(source, "3");
    try (Connection connection = source.getConnection()) {
      Engine engine = inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try (var statement = JdbcBusinessOperationRepository.prepare(connection,
          "SELECT operation_id, snapshot_json FROM tp_business_operations");
          var rows = statement.executeQuery()) {
        while (rows.next()) {
          var json = StoredOperationJson.read(rows.getString(2), JdbcBusinessOperationRepository.MAX_BYTES);
          var context = json.path("trustedContext");
          var scope = new io.tracepermit.application.BusinessOperationScope(context.path("tenantId").asText(),
              context.path("workspaceId").asText(), context.path("requesterPrincipalId").asText());
          var initial = JdbcBusinessOperationRepository.read(connection, scope, "operation_id", rows.getString(1))
              .orElseThrow(() -> error(OPERATION_SCHEMA_NOT_READY));
          JdbcBusinessOperationLifecycleRepository.current(connection, scope, rows.getString(1), initial, false);
        }
      } finally {
        connection.rollback();
      }
      flyway(source, engine, "4").migrate();
      validateReviewExisting(source);
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_SCHEMA_NOT_READY);
    }
  }

  /** Stopped-writer, additive opt-in upgrade. No activation, repair or partial-DDL recovery. */
  public static void upgradeAuthorityFromV2(DataSource source) {
    validateSchema(source, "2");
    try (Connection connection = source.getConnection()) {
      Engine engine = inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try (PreparedStatement statement = JdbcBusinessOperationRepository.prepare(connection,
          "SELECT operation_id, snapshot_json FROM tp_business_operations");
          ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          var json = StoredOperationJson.read(rows.getString(2), JdbcBusinessOperationRepository.MAX_BYTES);
          var context = json.path("trustedContext");
          var scope = new io.tracepermit.application.BusinessOperationScope(
              context.path("tenantId").asText(), context.path("workspaceId").asText(),
              context.path("requesterPrincipalId").asText());
          var initial = JdbcBusinessOperationRepository.read(connection, scope, "operation_id", rows.getString(1))
              .orElseThrow(() -> error(OPERATION_SCHEMA_NOT_READY));
          JdbcBusinessOperationLifecycleRepository.current(connection, scope, rows.getString(1), initial, false);
        }
      } finally {
        connection.rollback();
      }
      flyway(source, engine, "3").migrate();
      validateAuthorityExisting(source);
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_SCHEMA_NOT_READY);
    }
  }

  /**
   * Explicit additive upgrade, with writers stopped by the operator. Never repairs/resumes partial
   * MariaDB DDL and never changes V1 migration bytes, original snapshots or original facts.
   */
  public static void upgradeFromV1(DataSource source) {
    validateSchema(source, "1");
    try (Connection connection = source.getConnection()) {
      Engine engine = inspectWriter(connection);
      // Check the populated predecessor, not just Flyway's version label, before any DDL.
      try (PreparedStatement statement = JdbcBusinessOperationRepository.prepare(connection, """
          SELECT operation_id, snapshot_json FROM tp_business_operations
          """); ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String stored = rows.getString(2);
          var json = StoredOperationJson.read(stored, JdbcBusinessOperationRepository.MAX_BYTES);
          var context = json.path("trustedContext");
          var scope = new io.tracepermit.application.BusinessOperationScope(
              context.path("tenantId").asText(), context.path("workspaceId").asText(),
              context.path("requesterPrincipalId").asText());
          if (JdbcBusinessOperationRepository.read(connection, scope, "operation_id", rows.getString(1)).isEmpty()) {
            throw error(OPERATION_SCHEMA_NOT_READY);
          }
        }
      }
      flyway(source, engine).migrate();
      validateExisting(source);
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_SCHEMA_NOT_READY);
    }
  }

  private static void validateSchema(DataSource source, String version) {
    try (Connection connection = source.getConnection()) {
      Engine engine = inspectWriter(connection);
      boolean lifecycle = !"1".equals(version);
      boolean delivery = "7".equals(version);
      boolean admission = delivery || "6".equals(version);
      boolean review = admission || "4".equals(version);
      boolean authority = review || "3".equals(version);
      if (!tables(connection).equals(delivery ? V7_TABLES : admission ? V6_TABLES : review ? V4_TABLES : authority ? V3_TABLES : lifecycle ? V2_TABLES : V1_TABLES)) {
        throw error(OPERATION_SCHEMA_NOT_READY);
      }
      requireNoExecutableObjects(connection, engine);
      Flyway flyway = flyway(source, engine, version);
      flyway.validate();
      if (flyway.info().current() == null
          || !version.equals(flyway.info().current().getVersion().getVersion())
          || flyway.info().pending().length != 0) {
        throw error(OPERATION_SCHEMA_NOT_READY);
      }
      requireUnique(connection, "tp_business_operations", List.of("operation_id"));
      requireUnique(connection, "tp_business_operations", List.of("scope_hash", "request_key_hash"));
      requireUnique(connection, "tp_business_operation_facts", List.of("operation_id", "sequence_number"));
      requireForeignKey(connection, "tp_business_operation_facts", "tp_business_operations");
      if (lifecycle) {
        requireUnique(connection, "tp_operation_lifecycle_heads", List.of("operation_id"));
        requireUnique(connection, "tp_operation_lifecycle_facts", List.of("operation_id", "sequence_number"));
        requireForeignKey(connection, "tp_operation_lifecycle_heads", "tp_business_operations");
        requireForeignKey(connection, "tp_operation_lifecycle_facts", "tp_operation_lifecycle_heads");
      }
      if (authority) requireUnique(connection, "tp_operation_authority_controls", List.of("scope_hash"));
      if (review) {
        requireUnique(connection, "tp_operation_review_bindings", List.of("operation_id"));
        requireForeignKey(connection, "tp_operation_review_bindings", "tp_operation_lifecycle_heads");
      }
      if (admission) {
        requireUnique(connection, "tp_operation_admissions", List.of("operation_id"));
        requireUnique(connection, "tp_operation_admissions", List.of("permit_id"));
        requireUnique(connection, "tp_operation_admissions", List.of("attempt_id"));
        requireUnique(connection, "tp_operation_admission_outbox", List.of("operation_id"));
        requireForeignKey(connection, "tp_operation_admissions", "tp_operation_lifecycle_heads");
        requireForeignKey(connection, "tp_operation_admission_outbox", "tp_operation_admissions");
      }
      if (delivery) {
        requireUnique(connection, "tp_operation_delivery_events", List.of("operation_id", "event_sequence"));
        requireForeignKey(connection, "tp_operation_delivery_events", "tp_operation_admission_outbox");
      }
      OperationPhysicalSchema.require(connection, engine, version);
      if (engine == Engine.MARIADB) {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name IN
               (%s) AND engine = 'InnoDB'
            """.formatted(delivery
                ? "'tp_business_operations', 'tp_business_operation_facts', 'tp_operation_lifecycle_heads', 'tp_operation_lifecycle_facts', 'tp_operation_authority_controls', 'tp_operation_review_bindings', 'tp_operation_admissions', 'tp_operation_admission_outbox', 'tp_operation_delivery_events'"
                : admission
                ? "'tp_business_operations', 'tp_business_operation_facts', 'tp_operation_lifecycle_heads', 'tp_operation_lifecycle_facts', 'tp_operation_authority_controls', 'tp_operation_review_bindings', 'tp_operation_admissions', 'tp_operation_admission_outbox'"
                : review
                ? "'tp_business_operations', 'tp_business_operation_facts', 'tp_operation_lifecycle_heads', 'tp_operation_lifecycle_facts', 'tp_operation_authority_controls', 'tp_operation_review_bindings'"
                : authority
                ? "'tp_business_operations', 'tp_business_operation_facts', 'tp_operation_lifecycle_heads', 'tp_operation_lifecycle_facts', 'tp_operation_authority_controls'"
                : lifecycle ? "'tp_business_operations', 'tp_business_operation_facts', 'tp_operation_lifecycle_heads', 'tp_operation_lifecycle_facts'"
                : "'tp_business_operations', 'tp_business_operation_facts'"))) {
          statement.setQueryTimeout(5);
          try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != (delivery ? 9 : admission ? 8 : review ? 6 : authority ? 5 : lifecycle ? 4 : 2)) {
              throw error(OPERATION_SCHEMA_NOT_READY);
            }
          }
        }
      }
    } catch (BusinessOperationStorageException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_SCHEMA_NOT_READY);
    }
  }

  private static void requireForeignKey(Connection connection, String table, String parent)
      throws SQLException {
    try (ResultSet keys = connection.getMetaData().getImportedKeys(
        connection.getCatalog(), schema(connection), table)) {
      if (!keys.next() || !parent.equals(keys.getString("PKTABLE_NAME"))
          || !java.util.Objects.equals(connection.getCatalog(), keys.getString("PKTABLE_CAT"))
          || !java.util.Objects.equals(schema(connection), keys.getString("PKTABLE_SCHEM"))
          || !"operation_id".equals(keys.getString("PKCOLUMN_NAME"))
          || !"operation_id".equals(keys.getString("FKCOLUMN_NAME"))
          || !Set.of(java.sql.DatabaseMetaData.importedKeyNoAction, java.sql.DatabaseMetaData.importedKeyRestrict)
              .contains((int) keys.getShort("DELETE_RULE"))
          || !Set.of(java.sql.DatabaseMetaData.importedKeyNoAction, java.sql.DatabaseMetaData.importedKeyRestrict)
              .contains((int) keys.getShort("UPDATE_RULE"))
          || keys.getShort("DEFERRABILITY") != java.sql.DatabaseMetaData.importedKeyNotDeferrable || keys.next()) {
        throw error(OPERATION_SCHEMA_NOT_READY);
      }
    }
  }

  static Engine inspectWriter(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName();
    String version = connection.getMetaData().getDatabaseProductVersion();
    if ("PostgreSQL".equals(product) && version.matches("^16\\.4(?:[^0-9].*)?$")) {
      if (!"public".equals(connection.getSchema())) {
        throw error(OPERATION_DATABASE_UNSUPPORTED);
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT pg_is_in_recovery(), current_setting('transaction_read_only')")) {
        statement.setQueryTimeout(5);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next() || rows.getBoolean(1) || !"off".equals(rows.getString(2))) {
            throw error(OPERATION_WRITER_REQUIRED);
          }
        }
      }
      return Engine.POSTGRESQL;
    }
    if ("MariaDB".equals(product)
        && version.matches("^(11\\.4\\.13|10\\.6\\.25)(?:[-.+].*)?$")) {
      String database = connection.getCatalog();
      if (database == null || database.isBlank() || Set.of("mysql", "sys", "information_schema", "performance_schema")
          .contains(database.toLowerCase(java.util.Locale.ROOT))) {
        throw error(OPERATION_DATABASE_UNSUPPORTED);
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT @@read_only, @@innodb_flush_log_at_trx_commit, @@log_bin, @@sync_binlog, @@foreign_key_checks, @@check_constraint_checks")) {
        statement.setQueryTimeout(5);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next() || rows.getBoolean(1) || rows.getInt(2) != 1
              || (rows.getBoolean(3) && rows.getInt(4) != 1)) {
            throw error(OPERATION_WRITER_REQUIRED);
          }
          if (rows.getInt(5) != 1 || rows.getInt(6) != 1) throw error(OPERATION_SCHEMA_NOT_READY);
        }
      }
      return Engine.MARIADB;
    }
    throw error(OPERATION_DATABASE_UNSUPPORTED);
  }

  private static Set<String> tables(Connection connection) throws SQLException {
    Set<String> names = new HashSet<>();
    try (ResultSet rows = connection.getMetaData().getTables(
        connection.getCatalog(), schema(connection), "%", new String[] {"TABLE", "VIEW"})) {
      while (rows.next()) names.add(rows.getString("TABLE_NAME"));
    }
    return names;
  }

  private static void requireUnique(Connection connection, String table, List<String> columns)
      throws SQLException {
    Map<String, List<String>> indexes = new HashMap<>();
    try (ResultSet rows = connection.getMetaData().getIndexInfo(
        connection.getCatalog(), schema(connection), table, true, false)) {
      while (rows.next()) {
        String name = rows.getString("INDEX_NAME");
        String column = rows.getString("COLUMN_NAME");
        if (name != null && column != null && rows.getString("FILTER_CONDITION") == null) {
          indexes.computeIfAbsent(name, unused -> new ArrayList<>()).add(column);
        }
      }
    }
    if (!indexes.containsValue(columns)) throw error(OPERATION_SCHEMA_NOT_READY);
  }

  private static void requireNoExecutableObjects(Connection connection, Engine engine) throws SQLException {
    String sql = engine == Engine.POSTGRESQL ? """
        SELECT
          (SELECT COUNT(*) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n
            ON n.oid = p.pronamespace WHERE n.nspname = current_schema()) +
          (SELECT COUNT(*) FROM pg_catalog.pg_trigger t
            JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = current_schema() AND NOT t.tgisinternal)
        """ : """
        SELECT
          (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = DATABASE()) +
          (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE()) +
          (SELECT COUNT(*) FROM information_schema.events WHERE event_schema = DATABASE())
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.getLong(1) != 0) throw error(OPERATION_SCHEMA_NOT_READY);
      }
    }
  }

  private static String schema(Connection connection) throws SQLException {
    return "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
        ? connection.getSchema() : null;
  }

  private static Flyway flyway(DataSource source, Engine engine) {
    return flyway(source, engine, "2");
  }

  private static Flyway flyway(DataSource source, Engine engine, String target) {
    return Flyway.configure()
        .dataSource(source)
        .table("tp_operation_schema_history")
        .target(target)
        .locations("classpath:db/operations/" + engine.directory)
        .createSchemas(false)
        .cleanDisabled(true)
        .validateMigrationNaming(true)
        .failOnMissingLocations(true)
        .loggers()
        .load();
  }

  private static BusinessOperationStorageException error(
      BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
