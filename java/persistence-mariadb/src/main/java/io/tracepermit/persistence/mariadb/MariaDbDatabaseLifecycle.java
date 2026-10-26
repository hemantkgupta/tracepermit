package io.tracepermit.persistence.mariadb;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Explicit empty-database installation and validate-only MariaDB startup. */
public final class MariaDbDatabaseLifecycle {
  public static final int CURRENT_VERSION = 1;
  public static final String MIGRATION_LOCATION = "classpath:db/migration-mariadb";

  private static final Set<String> TABLES = Set.of(
      "flyway_schema_history", "tp_mariadb_schema_guard", "tp_consumed_execution_permits");
  private static final Set<String> TRACEPERMIT_TABLES =
      Set.of("tp_mariadb_schema_guard", "tp_consumed_execution_permits");

  private MariaDbDatabaseLifecycle() {}

  /** First installation only. Never repairs, cleans, baselines, or adopts existing objects. */
  public static MariaDbEngineProfile initializeEmpty(DataSource source) {
    Objects.requireNonNull(source, "source");
    try (Connection lockConnection = source.getConnection()) {
      MariaDbEngineProfile profile = inspect(lockConnection);
      String lockName = initializationLock(profile.databaseName());
      acquireInitializationLock(lockConnection, lockName);
      try {
        requireEmptyDatabase(lockConnection);
        flyway(source).migrate();
        MariaDbEngineProfile validated = validateExisting(source);
        if (!validated.equals(profile)) {
          throw new IllegalStateException("DATABASE_PROFILE_CHANGED_DURING_INITIALIZATION");
        }
        releaseInitializationLock(lockConnection, lockName);
        return validated;
      } catch (RuntimeException | SQLException failure) {
        // Preserve the initiating failure if explicit release also fails.
        try {
          releaseInitializationLock(lockConnection, lockName);
        } catch (RuntimeException | SQLException releaseFailure) {
          failure.addSuppressed(releaseFailure);
        }
        throw failure;
      }
    } catch (SQLException failure) {
      throw new MariaDbStateException("MariaDB initialization failed", failure);
    }
  }

  /** Reads metadata and Flyway state only; never migrates, repairs, cleans, or baselines. */
  public static MariaDbEngineProfile validateExisting(DataSource source) {
    Objects.requireNonNull(source, "source");
    try (Connection connection = source.getConnection()) {
      MariaDbEngineProfile profile = inspect(connection);
      requireExpectedObjects(connection);
      requireSchemaGuard(connection);
      flyway(source).validate();
      return profile;
    } catch (SQLException failure) {
      throw new MariaDbStateException("MariaDB validation failed", failure);
    }
  }

  /** Returns only non-secret engine and schema identity fields needed for operator evidence. */
  public static MariaDbEngineProfile inspect(DataSource source) {
    Objects.requireNonNull(source, "source");
    try (Connection connection = source.getConnection()) {
      return inspect(connection);
    } catch (SQLException failure) {
      throw new MariaDbStateException("MariaDB inspection failed", failure);
    }
  }

  private static MariaDbEngineProfile inspect(Connection connection) throws SQLException {
    String productName = connection.getMetaData().getDatabaseProductName();
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT VERSION(), @@default_storage_engine, DATABASE()")) {
      statement.setQueryTimeout(5);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new IllegalStateException("DATABASE_PROFILE_UNAVAILABLE");
        }
        String reportedVersion = rows.getString(1);
        String engine = rows.getString(2);
        String database = rows.getString(3);
        if (reportedVersion == null || engine == null || database == null || rows.next()) {
          throw new IllegalStateException("DATABASE_PROFILE_UNAVAILABLE");
        }
        return MariaDbEngineProfile.qualified(productName, reportedVersion, engine, database);
      }
    }
  }

  private static void acquireInitializationLock(Connection connection, String lockName)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
      statement.setQueryTimeout(5);
      statement.setString(1, lockName);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull() || rows.next()) {
          throw new IllegalStateException("DATABASE_INITIALIZATION_BUSY");
        }
      }
    }
  }

  private static void releaseInitializationLock(Connection connection, String lockName)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      statement.setQueryTimeout(5);
      statement.setString(1, lockName);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull() || rows.next()) {
          throw new IllegalStateException("DATABASE_INITIALIZATION_LOCK_RELEASE_FAILED");
        }
      }
    }
  }

  private static void requireEmptyDatabase(Connection connection) throws SQLException {
    String sql = """
        SELECT
          (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()) +
          (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = DATABASE()) +
          (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE()) +
          (SELECT COUNT(*) FROM information_schema.events WHERE event_schema = DATABASE())
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next() || rows.getLong(1) != 0 || rows.next()) {
          throw new IllegalStateException("DATABASE_INITIALIZATION_REQUIRES_EMPTY_DATABASE");
        }
      }
    }
  }

  private static void requireExpectedObjects(Connection connection) throws SQLException {
    Map<String, String> tableEngines = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT table_name, engine
          FROM information_schema.tables
         WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
        """)) {
      statement.setQueryTimeout(5);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          tableEngines.put(rows.getString(1), rows.getString(2));
        }
      }
    }
    if (!tableEngines.keySet().equals(TABLES)) {
      throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
    }
    for (String table : TRACEPERMIT_TABLES) {
      if (!"InnoDB".equalsIgnoreCase(tableEngines.get(table))) {
        throw new IllegalStateException("DATABASE_STORAGE_ENGINE_UNSUPPORTED");
      }
    }

    Set<String> extraObjects = new HashSet<>();
    collectNames(connection, extraObjects,
        "SELECT table_name FROM information_schema.views WHERE table_schema = DATABASE()");
    collectNames(connection, extraObjects,
        "SELECT routine_name FROM information_schema.routines WHERE routine_schema = DATABASE()");
    collectNames(connection, extraObjects,
        "SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema = DATABASE()");
    collectNames(connection, extraObjects,
        "SELECT event_name FROM information_schema.events WHERE event_schema = DATABASE()");
    if (!extraObjects.isEmpty()) {
      throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
    }
  }

  private static void collectNames(Connection connection, Set<String> names, String sql)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          names.add(rows.getString(1));
        }
      }
    }
  }

  private static void requireSchemaGuard(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT contract_version, engine_family
          FROM tp_mariadb_schema_guard
         WHERE singleton = 1
        """)) {
      statement.setQueryTimeout(5);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()
            || rows.getInt(1) != CURRENT_VERSION
            || !"mariadb".equals(rows.getString(2))
            || rows.next()) {
          throw new IllegalStateException("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
        }
      }
    }
  }

  private static Flyway flyway(DataSource source) {
    return Flyway.configure()
        .dataSource(source)
        .locations(MIGRATION_LOCATION)
        .validateMigrationNaming(true)
        .failOnMissingLocations(true)
        .cleanDisabled(true)
        .load();
  }

  private static String initializationLock(String databaseName) {
    UUID databaseId = UUID.nameUUIDFromBytes(databaseName.getBytes(StandardCharsets.UTF_8));
    return "tracepermit-mariadb-init-" + databaseId;
  }
}
