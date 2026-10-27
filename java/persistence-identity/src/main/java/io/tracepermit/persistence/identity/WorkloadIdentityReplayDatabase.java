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
 * exact table and column physical schema verification, agreed capacity enforcement, and
 * fail-closed handling of read-only/replica or corrupt topologies.
 */
public final class WorkloadIdentityReplayDatabase {
  public static final long DEFAULT_CAPACITY = 10_000L;

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

  /** Explicit first install in an empty dedicated schema/database with default capacity. */
  public static void initializeEmpty(DataSource source) {
    initializeEmpty(source, DEFAULT_CAPACITY);
  }

  /** Explicit first install in an empty dedicated schema/database with operator-agreed capacity. */
  public static void initializeEmpty(DataSource source, long capacity) {
    if (capacity <= 0) {
      throw error(REPLAY_INPUT_INVALID);
    }
    try (Connection connection = source.getConnection()) {
      Engine engine = inspectWriter(connection);
      if (!tables(connection).isEmpty()) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
      requireNoExecutableObjects(connection, engine);
      flyway(source, engine, "2").migrate();
      if (capacity != DEFAULT_CAPACITY) {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE tp_workload_replay_state SET capacity = ? WHERE singleton = 1
            """)) {
          statement.setQueryTimeout(5);
          statement.setLong(1, capacity);
          statement.executeUpdate();
        }
      }
      validateExisting(source, capacity);
    } catch (WorkloadIdentityReplayException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw error(REPLAY_SCHEMA_NOT_READY);
    }
  }

  /**
   * Explicit additive upgrade from V1 to V2 with default capacity.
   */
  public static void upgradeFromV1(DataSource source) {
    upgradeFromV1(source, DEFAULT_CAPACITY);
  }

  /**
   * Explicit additive upgrade from V1 to V2 with operator-agreed capacity.
   */
  public static void upgradeFromV1(DataSource source, long capacity) {
    if (capacity <= 0) {
      throw error(REPLAY_INPUT_INVALID);
    }
    validateSchema(source, "1");
    try (Connection connection = source.getConnection()) {
      Engine engine = inspectWriter(connection);
      flyway(source, engine, "2").migrate();
      if (capacity != DEFAULT_CAPACITY) {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE tp_workload_replay_state SET capacity = ? WHERE singleton = 1
            """)) {
          statement.setQueryTimeout(5);
          statement.setLong(1, capacity);
          statement.executeUpdate();
        }
      }
      validateExisting(source, capacity);
    } catch (WorkloadIdentityReplayException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw error(REPLAY_SCHEMA_NOT_READY);
    }
  }

  /** Validate only: no automatic migrate, baseline, repair, clean or adoption. */
  public static void validateExisting(DataSource source) {
    validateSchema(source, "2");
  }

  /** Validate existing schema at version 2 and assert agreed capacity match. */
  public static void validateExisting(DataSource source, long expectedCapacity) {
    validateExisting(source);
    long actualCapacity = readPersistedCapacity(source);
    if (actualCapacity != expectedCapacity) {
      throw error(REPLAY_CAPACITY_MISMATCH);
    }
  }

  /** Read persisted capacity from the database state row. */
  public static long readPersistedCapacity(DataSource source) {
    try (Connection connection = source.getConnection()) {
      inspectWriter(connection);
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT capacity FROM tp_workload_replay_state WHERE singleton = 1
          """)) {
        statement.setQueryTimeout(5);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            throw error(REPLAY_SCHEMA_NOT_READY);
          }
          long capacity = rows.getLong(1);
          if (capacity <= 0) {
            throw error(REPLAY_SCHEMA_NOT_READY);
          }
          return capacity;
        }
      }
    } catch (WorkloadIdentityReplayException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw error(REPLAY_SCHEMA_NOT_READY);
    }
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

      // Physical schema column validation
      validateClaimsTable(connection, engine);
      validateStateTable(connection, version);

      // Primary key validation
      requirePrimaryKey(connection, "tp_workload_replay_claims", "claim_hash");
      requirePrimaryKey(connection, "tp_workload_replay_state", "singleton");

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

      boolean isV2 = "2".equals(version);
      String stateQuery = isV2
          ? "SELECT singleton, total_claims, capacity FROM tp_workload_replay_state WHERE singleton = 1"
          : "SELECT singleton, total_claims FROM tp_workload_replay_state WHERE singleton = 1";
      try (PreparedStatement statement = connection.prepareStatement(stateQuery)) {
        statement.setQueryTimeout(5);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next() || rows.getInt(1) != 1 || rows.getLong(2) < 0) {
            throw error(REPLAY_SCHEMA_NOT_READY);
          }
          if (isV2 && rows.getLong(3) <= 0) {
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

  private record ColumnInfo(String name, String typeName, int columnSize, boolean isNullable) {}

  private static void validateClaimsTable(Connection connection, Engine engine) throws SQLException {
    Map<String, ColumnInfo> columns = readColumns(connection, "tp_workload_replay_claims");
    Set<String> expectedColumnNames = Set.of(
        "claim_hash", "tenant_hash", "token_fingerprint", "expires_at", "claimed_at");
    if (!columns.keySet().equals(expectedColumnNames)) {
      throw error(REPLAY_SCHEMA_NOT_READY);
    }
    for (ColumnInfo col : columns.values()) {
      if (col.isNullable()) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
    }
    ColumnInfo claimHash = columns.get("claim_hash");
    ColumnInfo tenantHash = columns.get("tenant_hash");
    ColumnInfo tokenFingerprint = columns.get("token_fingerprint");
    if (claimHash.columnSize() != 71 || tenantHash.columnSize() != 71 || tokenFingerprint.columnSize() != 71) {
      throw error(REPLAY_SCHEMA_NOT_READY);
    }
    ColumnInfo expiresAt = columns.get("expires_at");
    ColumnInfo claimedAt = columns.get("claimed_at");
    if (engine == Engine.POSTGRESQL) {
      if (!isPostgresTimestampType(expiresAt.typeName()) || !isPostgresTimestampType(claimedAt.typeName())) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
      validatePostgresCollation(connection);
    } else {
      if (!"datetime".equalsIgnoreCase(expiresAt.typeName()) || !"datetime".equalsIgnoreCase(claimedAt.typeName())) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
      validateMariaDbCollation(connection);
    }
  }

  private static boolean isPostgresTimestampType(String typeName) {
    return "timestamptz".equalsIgnoreCase(typeName)
        || "timestamp with time zone".equalsIgnoreCase(typeName);
  }

  private static void validatePostgresCollation(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT COUNT(*)
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tp_workload_replay_claims'
           AND column_name IN ('claim_hash', 'tenant_hash', 'token_fingerprint')
           AND collation_name = 'C'
        """)) {
      statement.setQueryTimeout(5);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.getInt(1) != 3) {
          throw error(REPLAY_SCHEMA_NOT_READY);
        }
      }
    }
  }

  private static void validateMariaDbCollation(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT COUNT(*)
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'tp_workload_replay_claims'
           AND column_name IN ('claim_hash', 'tenant_hash', 'token_fingerprint')
           AND collation_name = 'ascii_bin'
        """)) {
      statement.setQueryTimeout(5);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.getInt(1) != 3) {
          throw error(REPLAY_SCHEMA_NOT_READY);
        }
      }
    }
  }

  private static void validateStateTable(Connection connection, String version) throws SQLException {
    Map<String, ColumnInfo> columns = readColumns(connection, "tp_workload_replay_state");
    boolean isV2 = "2".equals(version);
    Set<String> expectedColumnNames = isV2
        ? Set.of("singleton", "total_claims", "capacity")
        : Set.of("singleton", "total_claims");
    if (!columns.keySet().equals(expectedColumnNames)) {
      throw error(REPLAY_SCHEMA_NOT_READY);
    }
    for (ColumnInfo col : columns.values()) {
      if (col.isNullable()) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
    }
  }

  private static Map<String, ColumnInfo> readColumns(Connection connection, String table) throws SQLException {
    Map<String, ColumnInfo> columns = new HashMap<>();
    try (ResultSet rs = connection.getMetaData().getColumns(
        connection.getCatalog(), schema(connection), table, "%")) {
      while (rs.next()) {
        String name = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
        String typeName = rs.getString("TYPE_NAME");
        int size = rs.getInt("COLUMN_SIZE");
        String nullableStr = rs.getString("IS_NULLABLE");
        boolean nullable = !"NO".equalsIgnoreCase(nullableStr);
        columns.put(name, new ColumnInfo(name, typeName, size, nullable));
      }
    }
    return columns;
  }

  private static void requirePrimaryKey(Connection connection, String table, String expectedColumn)
      throws SQLException {
    try (ResultSet rs = connection.getMetaData().getPrimaryKeys(
        connection.getCatalog(), schema(connection), table)) {
      if (!rs.next() || !expectedColumn.equalsIgnoreCase(rs.getString("COLUMN_NAME")) || rs.next()) {
        throw error(REPLAY_SCHEMA_NOT_READY);
      }
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
