package io.tracepermit.persistence.identity;

import static io.tracepermit.persistence.identity.WorkloadIdentityReplayException.Code.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Isolated durable workload-identity replay database lifecycle and validation.
 *
 * <p>Enforces dedicated empty-only schema initialization, strict version and engine validation,
 * exact table whitelisting, and fail-closed handling of read-only/replica or corrupt topologies.
 */
public final class WorkloadIdentityReplayDatabase {
  private static final Set<String> EXPECTED_TABLES = Set.of(
      "tp_identity_replay_schema_history",
      "tp_workload_replay_claims",
      "tp_workload_replay_state");

  public enum Engine {
    POSTGRESQL("postgresql"),
    MARIADB("mariadb");

    final String directory;

    Engine(String directory) {
      this.directory = directory;
    }

    public boolean isDuplicateKey(SQLException failure) {
      for (Throwable current = failure; current != null; current = current.getCause()) {
        if (current instanceof SQLException sql) {
          for (SQLException item = sql; item != null; item = item.getNextException()) {
            if (this == POSTGRESQL && "23505".equals(item.getSQLState())) {
              return true;
            }
            if (this == MARIADB
                && item.getErrorCode() == 1062
                && "23000".equals(item.getSQLState())) {
              return true;
            }
          }
        }
      }
      return false;
    }

    public boolean isPermissionDenied(SQLException failure) {
      for (Throwable current = failure; current != null; current = current.getCause()) {
        if (current instanceof SQLException sql) {
          for (SQLException item = sql; item != null; item = item.getNextException()) {
            if (this == POSTGRESQL && "42501".equals(item.getSQLState())) {
              return true;
            }
            if (this == MARIADB
                && (item.getErrorCode() == 1142 || item.getErrorCode() == 1143)
                && "42000".equals(item.getSQLState())) {
              return true;
            }
          }
        }
      }
      return false;
    }
  }

  private WorkloadIdentityReplayDatabase() {}

  /** Explicit first install in an empty dedicated schema/database; runtime never calls this. */
  public static void initializeEmpty(DataSource source) {
    try (Connection connection = source.getConnection()) {
      Engine engine = inspectWriter(connection);
      if (!tables(connection).isEmpty()) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
      requireNoExecutableObjects(connection, engine);
      flyway(source, engine).migrate();
      validateExisting(source);
    } catch (WorkloadIdentityReplayException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw error(REPLAY_SCHEMA_NOT_READY);
    }
  }

  /** Validate only: no automatic migrate, baseline, repair, clean or adoption. */
  public static void validateExisting(DataSource source) {
    validateSchema(source, "1");
  }

  private static void validateSchema(DataSource source, String version) {
    try (Connection connection = source.getConnection()) {
      Engine engine = inspectWriter(connection);
      if (!tables(connection).equals(EXPECTED_TABLES)) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
      requireNoExecutableObjects(connection, engine);
      Flyway flyway = flyway(source, engine, version);
      flyway.validate();
      if (flyway.info().current() == null
          || !version.equals(flyway.info().current().getVersion().getVersion())
          || flyway.info().pending().length != 0) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
      requireUnique(connection, "tp_workload_replay_claims", List.of("claim_hash"));
      requireUnique(connection, "tp_workload_replay_state", List.of("singleton"));

      if (engine == Engine.MARIADB) {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name IN
               ('tp_workload_replay_claims', 'tp_workload_replay_state') AND engine = 'InnoDB'
            """)) {
          statement.setQueryTimeout(5);
          try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != 2) {
              throw error(REPLAY_SCHEMA_NOT_READY);
            }
          }
        }
      }

      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT singleton, total_claims FROM tp_workload_replay_state WHERE singleton = 1
          """)) {
        statement.setQueryTimeout(5);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next() || rows.getInt(1) != 1 || rows.getLong(2) < 0) {
            throw error(REPLAY_SCHEMA_NOT_READY);
          }
        }
      }
    } catch (WorkloadIdentityReplayException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw error(REPLAY_SCHEMA_NOT_READY);
    }
  }

  public static Engine inspectWriter(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName();
    String version = connection.getMetaData().getDatabaseProductVersion();
    if ("PostgreSQL".equals(product) && version.matches("^16\\.4(?:[^0-9].*)?$")) {
      if (!"public".equals(connection.getSchema())) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT pg_is_in_recovery(), current_setting('transaction_read_only')")) {
        statement.setQueryTimeout(5);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next() || rows.getBoolean(1) || !"off".equals(rows.getString(2))) {
            throw error(REPLAY_STORAGE_READ_ONLY);
          }
        }
      }
      return Engine.POSTGRESQL;
    }
    if ("MariaDB".equals(product)
        && version.matches("^(11\\.4\\.13|10\\.6\\.25)(?:[-.+].*)?$")) {
      String database = connection.getCatalog();
      if (database == null
          || database.isBlank()
          || Set.of("mysql", "sys", "information_schema", "performance_schema")
              .contains(database.toLowerCase(Locale.ROOT))) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT @@read_only, @@innodb_flush_log_at_trx_commit, @@log_bin, @@sync_binlog")) {
        statement.setQueryTimeout(5);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next() || rows.getBoolean(1) || rows.getInt(2) != 1
              || (rows.getBoolean(3) && rows.getInt(4) != 1)) {
            throw error(REPLAY_STORAGE_READ_ONLY);
          }
        }
      }
      return Engine.MARIADB;
    }
    throw error(REPLAY_SCHEMA_NOT_READY);
  }

  private static Set<String> tables(Connection connection) throws SQLException {
    Set<String> names = new HashSet<>();
    try (ResultSet rows = connection.getMetaData().getTables(
        connection.getCatalog(), schema(connection), "%", new String[] {"TABLE", "VIEW"})) {
      while (rows.next()) {
        names.add(rows.getString("TABLE_NAME"));
      }
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
    if (!indexes.containsValue(columns)) {
      throw error(REPLAY_SCHEMA_NOT_READY);
    }
  }

  private static void requireNoExecutableObjects(Connection connection, Engine engine)
      throws SQLException {
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
        if (!rows.next() || rows.getLong(1) != 0) {
          throw error(REPLAY_SCHEMA_NOT_READY);
        }
      }
    }
  }

  private static String schema(Connection connection) throws SQLException {
    return "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
        ? connection.getSchema() : null;
  }

  private static Flyway flyway(DataSource source, Engine engine) {
    return flyway(source, engine, "1");
  }

  private static Flyway flyway(DataSource source, Engine engine, String target) {
    return Flyway.configure()
        .dataSource(source)
        .table("tp_identity_replay_schema_history")
        .target(target)
        .locations("classpath:db/identity/" + engine.directory)
        .createSchemas(false)
        .cleanDisabled(true)
        .validateMigrationNaming(true)
        .failOnMissingLocations(true)
        .loggers()
        .load();
  }

  private static WorkloadIdentityReplayException error(WorkloadIdentityReplayException.Code code) {
    return new WorkloadIdentityReplayException(code);
  }
}
