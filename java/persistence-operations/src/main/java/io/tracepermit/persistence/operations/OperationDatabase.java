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
    try (Connection connection = source.getConnection()) {
      Engine engine = inspectWriter(connection);
      if (!tables(connection).isEmpty()) throw error(OPERATION_SCHEMA_NOT_READY);
      requireNoExecutableObjects(connection, engine);
      flyway(source, engine).migrate();
      validateExisting(source);
    } catch (BusinessOperationStorageException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_SCHEMA_NOT_READY);
    }
  }

  /** Validate only: no automatic migrate, baseline, repair, clean or adoption. */
  public static void validateExisting(DataSource source) {
    validateSchema(source, "2");
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
      boolean lifecycle = "2".equals(version);
      if (!tables(connection).equals(lifecycle ? V2_TABLES : V1_TABLES)) throw error(OPERATION_SCHEMA_NOT_READY);
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
      if (engine == Engine.MARIADB) {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name IN
               (%s) AND engine = 'InnoDB'
            """.formatted(lifecycle
                ? "'tp_business_operations', 'tp_business_operation_facts', 'tp_operation_lifecycle_heads', 'tp_operation_lifecycle_facts'"
                : "'tp_business_operations', 'tp_business_operation_facts'"))) {
          statement.setQueryTimeout(5);
          try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != (lifecycle ? 4 : 2)) throw error(OPERATION_SCHEMA_NOT_READY);
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
          || !"operation_id".equals(keys.getString("PKCOLUMN_NAME"))
          || !"operation_id".equals(keys.getString("FKCOLUMN_NAME")) || keys.next()) {
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
          "SELECT @@read_only, @@innodb_flush_log_at_trx_commit, @@log_bin, @@sync_binlog")) {
        statement.setQueryTimeout(5);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next() || rows.getBoolean(1) || rows.getInt(2) != 1
              || (rows.getBoolean(3) && rows.getInt(4) != 1)) {
            throw error(OPERATION_WRITER_REQUIRED);
          }
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
          (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = current_schema())
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
