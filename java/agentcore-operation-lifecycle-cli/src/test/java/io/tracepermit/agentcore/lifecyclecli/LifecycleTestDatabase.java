package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.persistence.identity.WorkloadIdentityReplayDatabase;
import io.tracepermit.persistence.operations.OperationDatabase;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Test-only exact V7 stores with a restricted numeric-loopback runtime principal. */
final class LifecycleTestDatabase implements AutoCloseable {
  private static final String USER = "tp_lifecycle_cli";
  private static final List<String> OPERATION_TABLES =
      List.of(
          "tp_operation_schema_history",
          "tp_business_operations",
          "tp_business_operation_facts",
          "tp_operation_lifecycle_heads",
          "tp_operation_lifecycle_facts",
          "tp_operation_authority_controls",
          "tp_operation_review_bindings",
          "tp_operation_admissions",
          "tp_operation_admission_outbox",
          "tp_operation_delivery_events");

  final JdbcDatabaseContainer<?> container;
  final boolean postgres;
  final String engine;
  final String owner;
  final String administratorPassword;
  final String runtimePassword;
  final int port;
  final Source replayAdmin;
  final Source operationAdmin;
  final Source replay;
  final Source operations;

  LifecycleTestDatabase(String image) {
    postgres = image.startsWith("postgres:");
    engine =
        postgres
            ? "POSTGRESQL_16_4"
            : image.contains("11.4.13") ? "MARIADB_11_4_13" : "MARIADB_10_6_25";
    int internal = postgres ? 5432 : 3306;
    container = postgres ? new PostgreSQLContainer<>(image) : new MariaDBContainer<>(image);
    administratorPassword = UUID.randomUUID().toString();
    runtimePassword = UUID.randomUUID().toString();
    container
        .withPassword(administratorPassword)
        .withStartupTimeout(Duration.ofSeconds(60))
        .withCreateContainerCmdModifier(
            command ->
                command
                    .getHostConfig()
                    .withPortBindings(
                        new PortBinding(
                            Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                            new ExposedPort(internal))));
    try {
      container.start();
      port = container.getMappedPort(internal);
      container
          .getContainerInfo()
          .getHostConfig()
          .getPortBindings()
          .getBindings()
          .values()
          .forEach(
              endpoints -> {
                for (var endpoint : endpoints) {
                  assertThat(endpoint.getHostIp()).isEqualTo("127.0.0.1");
                }
              });
      owner = postgres ? container.getUsername() : "root";
      Source bootstrap = source(container.getDatabaseName(), owner, administratorPassword);
      execute(bootstrap, "CREATE DATABASE tp_lifecycle_replay");
      execute(bootstrap, "CREATE DATABASE tp_lifecycle_operations");
      replayAdmin = source("tp_lifecycle_replay", owner, administratorPassword);
      operationAdmin = source("tp_lifecycle_operations", owner, administratorPassword);
      WorkloadIdentityReplayDatabase.initializeEmpty(replayAdmin, 64);
      OperationDatabase.initializeDeliveryEmpty(operationAdmin);
      execute(
          bootstrap,
          postgres
              ? "CREATE ROLE " + USER + " LOGIN PASSWORD '" + runtimePassword + "'"
              : "CREATE USER '" + USER + "'@'%' IDENTIFIED BY '" + runtimePassword + "'");
      replay = source("tp_lifecycle_replay", USER, runtimePassword);
      operations = source("tp_lifecycle_operations", USER, runtimePassword);
      if (postgres) {
        execute(replayAdmin, "GRANT USAGE ON SCHEMA public TO " + USER);
        execute(operationAdmin, "GRANT USAGE ON SCHEMA public TO " + USER);
      }
      for (String table :
          List.of(
              "tp_identity_replay_schema_history",
              "tp_workload_replay_claims",
              "tp_workload_replay_state")) {
        grant(replayAdmin, "tp_lifecycle_replay", "SELECT", table);
      }
      grant(replayAdmin, "tp_lifecycle_replay", "INSERT", "tp_workload_replay_claims");
      grant(replayAdmin, "tp_lifecycle_replay", "UPDATE", "tp_workload_replay_state");
      for (String table : OPERATION_TABLES) {
        grant(operationAdmin, "tp_lifecycle_operations", "SELECT", table);
      }
      for (String table :
          List.of(
              "tp_business_operations",
              "tp_business_operation_facts",
              "tp_operation_lifecycle_heads",
              "tp_operation_lifecycle_facts",
              "tp_operation_review_bindings",
              "tp_operation_admissions",
              "tp_operation_admission_outbox",
              "tp_operation_delivery_events")) {
        grant(operationAdmin, "tp_lifecycle_operations", "INSERT", table);
      }
      execute(
          operationAdmin,
          "GRANT UPDATE(snapshot_hash, snapshot_json) ON "
              + table("tp_lifecycle_operations", "tp_operation_lifecycle_heads")
              + " TO "
              + principal());
      if (postgres) {
        execute(
            operationAdmin,
            "GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO " + USER);
      }
    } catch (RuntimeException | Error failure) {
      container.stop();
      throw new AssertionError("SYNTHETIC_LIFECYCLE_DATABASE_SETUP_FAILED", failure);
    }
  }

  ObjectNode operationConfig() {
    return JsonSupport.MAPPER
        .createObjectNode()
        .put("engine", engine)
        .put("host", "127.0.0.1")
        .put("port", port)
        .put("catalog", "tp_lifecycle_operations")
        .put("user", USER)
        .put("password", runtimePassword);
  }

  void reset() {
    for (String table :
        List.of(
            "tp_operation_delivery_events",
            "tp_operation_admission_outbox",
            "tp_operation_admissions",
            "tp_operation_review_bindings",
            "tp_operation_lifecycle_facts",
            "tp_operation_lifecycle_heads",
            "tp_business_operation_facts",
            "tp_business_operations",
            "tp_operation_authority_controls")) {
      execute(operationAdmin, "DELETE FROM " + table);
    }
    execute(replayAdmin, "DELETE FROM tp_workload_replay_claims");
    execute(
        replayAdmin,
        "UPDATE tp_workload_replay_state SET total_claims=0, capacity=64 WHERE singleton=1");
  }

  long count(String table) {
    try (Connection connection = operationAdmin.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
        if (!rows.next()) throw new AssertionError("SYNTHETIC_COUNT_MISSING");
        return rows.getLong(1);
      }
    } catch (SQLException failure) {
      throw new AssertionError("SYNTHETIC_COUNT_FAILED", failure);
    }
  }

  private Source source(String catalog, String user, String password) {
    String scheme = postgres ? "jdbc:postgresql" : "jdbc:mariadb";
    String bounds =
        postgres
            ? "?connectTimeout=5&socketTimeout=10"
            : "?connectTimeout=5000&socketTimeout=10000";
    return new Source(
        scheme + "://127.0.0.1:" + port + "/" + catalog + bounds, user, password);
  }

  private void grant(Source administrator, String database, String privilege, String table) {
    execute(
        administrator,
        "GRANT "
            + privilege
            + " ON "
            + table(database, table)
            + " TO "
            + principal());
  }

  private String table(String database, String name) {
    return postgres ? name : "`" + database + "`." + name;
  }

  private String principal() {
    return postgres ? USER : "'" + USER + "'@'%'";
  }

  static void execute(DataSource source, String sql) {
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(sql);
    } catch (SQLException failure) {
      throw new AssertionError("SYNTHETIC_ADMIN_ACTION_FAILED", failure);
    }
  }

  @Override
  public void close() {
    container.stop();
  }

  static final class Source implements DataSource {
    private final String url;
    private final String user;
    private final String password;

    Source(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String suppliedUser, String suppliedPassword)
        throws SQLException {
      return DriverManager.getConnection(url, suppliedUser, suppliedPassword);
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter writer) {}

    @Override
    public void setLoginTimeout(int seconds) {
      DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
      return DriverManager.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      if (type.isInstance(this)) return type.cast(this);
      throw new SQLException("UNSUPPORTED_UNWRAP");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return type.isInstance(this);
    }
  }
}
