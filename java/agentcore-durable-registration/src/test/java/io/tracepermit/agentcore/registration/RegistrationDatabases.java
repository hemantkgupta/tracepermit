package io.tracepermit.agentcore.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.persistence.identity.WorkloadIdentityReplayDatabase;
import io.tracepermit.persistence.operations.OperationDatabase;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** One owned loopback-only engine, with two separately initialized dedicated databases. */
final class RegistrationDatabases implements AutoCloseable {
  static final String USER = "tp_durable_registration";
  static final List<String> OP_TABLES = List.of("tp_operation_lifecycle_facts", "tp_operation_lifecycle_heads",
      "tp_business_operation_facts", "tp_business_operations");
  final JdbcDatabaseContainer<?> container;
  final boolean postgres;
  final BoundedJdbcSource replayAdmin;
  final BoundedJdbcSource operationAdmin;
  final BoundedJdbcSource replay;
  final BoundedJdbcSource operations;

  RegistrationDatabases(String image) {
    postgres = image.startsWith("postgres:");
    int port = postgres ? 5432 : 3306;
    container = postgres ? new PostgreSQLContainer<>(image) : new MariaDBContainer<>(image);
    String secret = UUID.randomUUID().toString();
    container.withPassword(secret).withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
        new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(port))));
    container.withStartupTimeout(Duration.ofSeconds(60));
    try {
      container.start();
      var bindings = container.getContainerInfo().getHostConfig().getPortBindings().getBindings();
      assertThat(bindings).hasSize(1);
      for (var binding : bindings.values()) for (var endpoint : binding) {
        assertThat(endpoint.getHostIp()).isEqualTo("127.0.0.1");
      }
      String owner = postgres ? container.getUsername() : "root";
      String prefix = (postgres ? "jdbc:postgresql" : "jdbc:mariadb") + "://127.0.0.1:"
          + container.getMappedPort(port) + "/";
      String bounds = postgres ? "?connectTimeout=5&socketTimeout=10" : "?connectTimeout=5000&socketTimeout=10000";
      var bootstrap = new BoundedJdbcSource(prefix + container.getDatabaseName() + bounds, owner, secret);
      execute(bootstrap, "CREATE DATABASE tp_replay");
      execute(bootstrap, "CREATE DATABASE tp_operations");
      replayAdmin = new BoundedJdbcSource(prefix + "tp_replay" + bounds, owner, secret);
      operationAdmin = new BoundedJdbcSource(prefix + "tp_operations" + bounds, owner, secret);
      WorkloadIdentityReplayDatabase.initializeEmpty(replayAdmin, 128);
      OperationDatabase.initializeEmpty(operationAdmin);
      String runtimePassword = UUID.randomUUID().toString();
      execute(bootstrap, postgres ? "CREATE ROLE " + USER + " LOGIN PASSWORD '" + runtimePassword + "'"
          : "CREATE USER '" + USER + "'@'%' IDENTIFIED BY '" + runtimePassword + "'");
      replay = new BoundedJdbcSource(replayAdmin.url, USER, runtimePassword);
      operations = new BoundedJdbcSource(operationAdmin.url, USER, runtimePassword);
      if (postgres) {
        execute(replayAdmin, "GRANT USAGE ON SCHEMA public TO " + USER);
        execute(operationAdmin, "GRANT USAGE ON SCHEMA public TO " + USER);
      }
      for (String table : List.of("tp_identity_replay_schema_history", "tp_workload_replay_claims", "tp_workload_replay_state")) {
        grant(replayAdmin, "SELECT", table, true);
      }
      grant(replayAdmin, "INSERT", "tp_workload_replay_claims", true);
      grant(replayAdmin, "UPDATE", "tp_workload_replay_state", true);
      grant(operationAdmin, "SELECT", "tp_operation_schema_history", true);
      for (String table : OP_TABLES) {
        grant(operationAdmin, "SELECT", table, true);
        if (!table.equals("tp_operation_lifecycle_facts")) grant(operationAdmin, "INSERT", table, true);
      }
    } catch (RuntimeException | Error failure) {
      container.stop(); throw new AssertionError("SYNTHETIC_DATABASE_SETUP_FAILED");
    }
  }

  void reset() {
    replay.reset(); operations.reset();
    grant(replayAdmin, "INSERT", "tp_workload_replay_claims", true);
    grant(operationAdmin, "INSERT", "tp_business_operation_facts", true);
    for (String table : OP_TABLES) execute(operationAdmin, "DELETE FROM " + table);
    execute(replayAdmin, "DELETE FROM tp_workload_replay_claims");
    execute(replayAdmin, "UPDATE tp_workload_replay_state SET total_claims=0, capacity=128 WHERE singleton=1");
  }

  void grant(BoundedJdbcSource admin, String privilege, String table, boolean allow) {
    String catalog = admin == replayAdmin ? "tp_replay" : "tp_operations";
    execute(admin, (allow ? "GRANT " : "REVOKE ") + privilege + " ON "
        + (postgres ? table : "`" + catalog + "`." + table)
        + (allow ? " TO " : " FROM ") + (postgres ? USER : "'" + USER + "'@'%'") );
  }

  static void execute(BoundedJdbcSource source, String sql) {
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5); statement.execute(sql);
    } catch (SQLException failure) { throw new AssertionError("SYNTHETIC_ADMIN_ACTION_FAILED"); }
  }

  static long count(BoundedJdbcSource source, String table) {
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
        if (!rows.next()) throw new AssertionError("SYNTHETIC_COUNT_MISSING");
        return rows.getLong(1);
      }
    } catch (SQLException failure) { throw new AssertionError("SYNTHETIC_COUNT_FAILED"); }
  }

  @Override public void close() { container.stop(); }
}
