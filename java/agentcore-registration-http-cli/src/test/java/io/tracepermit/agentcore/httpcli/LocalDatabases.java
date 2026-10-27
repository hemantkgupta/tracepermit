package io.tracepermit.agentcore.httpcli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.persistence.identity.WorkloadIdentityReplayDatabase;
import io.tracepermit.persistence.operations.OperationDatabase;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.logging.Logger;
import java.util.UUID;
import javax.sql.DataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Test-only initialized stores, published only on numeric loopback. */
final class LocalDatabases implements AutoCloseable {
  final JdbcDatabaseContainer<?> container;
  final boolean postgres;
  final String engine;
  final String user;
  final String password;
  final int port;
  final DataSource replay;
  final DataSource operations;

  LocalDatabases(String image) {
    postgres = image.startsWith("postgres:");
    engine =
        postgres
            ? "POSTGRESQL_16_4"
            : image.contains("11.4.13") ? "MARIADB_11_4_13" : "MARIADB_10_6_25";
    int internal = postgres ? 5432 : 3306;
    container =
        postgres ? new PostgreSQLContainer<>(image) : new MariaDBContainer<>(image);
    String adminSecret = UUID.randomUUID().toString();
    container
        .withPassword(adminSecret)
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
      user = postgres ? container.getUsername() : "root";
      password = adminSecret;
      var bootstrap = source(container.getDatabaseName());
      execute(bootstrap, "CREATE DATABASE tp_replay");
      execute(bootstrap, "CREATE DATABASE tp_operations");
      replay = source("tp_replay");
      operations = source("tp_operations");
      WorkloadIdentityReplayDatabase.initializeEmpty(replay, 128);
      OperationDatabase.initializeEmpty(operations);
      for (var endpoints :
          container.getContainerInfo().getHostConfig().getPortBindings().getBindings().values()) {
        for (var endpoint : endpoints) assertThat(endpoint.getHostIp()).isEqualTo("127.0.0.1");
      }
    } catch (RuntimeException | Error failure) {
      container.stop();
      throw failure;
    }
  }

  ObjectNode config(String catalog) {
    return HttpCliFixtures.JSON
        .createObjectNode()
        .put("engine", engine)
        .put("host", "127.0.0.1")
        .put("port", port)
        .put("catalog", catalog)
        .put("user", user)
        .put("password", password);
  }

  void reset() {
    for (String table :
        java.util.List.of(
            "tp_operation_lifecycle_facts",
            "tp_operation_lifecycle_heads",
            "tp_business_operation_facts",
            "tp_business_operations")) execute(operations, "DELETE FROM " + table);
    execute(replay, "DELETE FROM tp_workload_replay_claims");
    execute(
        replay,
        "UPDATE tp_workload_replay_state SET total_claims=0, capacity=128 WHERE singleton=1");
  }

  long count(DataSource source, String table) {
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
        if (!rows.next()) throw new AssertionError("SYNTHETIC_COUNT_MISSING");
        return rows.getLong(1);
      }
    } catch (SQLException failure) {
      throw new AssertionError("SYNTHETIC_COUNT_FAILED");
    }
  }

  private DataSource source(String catalog) {
    String url =
        (postgres ? "jdbc:postgresql://" : "jdbc:mariadb://")
            + "127.0.0.1:"
            + port
            + "/"
            + catalog;
    return new DriverSource(url, user, password);
  }

  static void execute(DataSource source, String sql) {
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(sql);
    } catch (SQLException failure) {
      throw new AssertionError("SYNTHETIC_ADMIN_FAILED", failure);
    }
  }

  @Override
  public void close() {
    container.stop();
  }

  private record DriverSource(String url, String user, String password) implements DataSource {
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
    public PrintWriter getLogWriter() throws SQLException {
      return DriverManager.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter writer) throws SQLException {
      DriverManager.setLogWriter(writer);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return DriverManager.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getLogger("io.tracepermit.synthetic");
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      if (type.isInstance(this)) return type.cast(this);
      throw new SQLException("SYNTHETIC_UNWRAP_UNSUPPORTED");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return type.isInstance(this);
    }
  }
}
