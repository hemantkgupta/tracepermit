package io.tracepermit.agentcore.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.persistence.identity.WorkloadIdentityReplayDatabase;
import io.tracepermit.persistence.operations.OperationDatabase;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Test-only schema owners and unchanged minimum runtime grants, numeric loopback publication. */
final class LocalDatabases implements AutoCloseable {
  static final String USER = "tp_cli_runtime";
  static final List<String> OPERATIONS = List.of("tp_operation_lifecycle_facts", "tp_operation_lifecycle_heads",
      "tp_business_operation_facts", "tp_business_operations");
  final JdbcDatabaseContainer<?> container;
  final boolean postgres;
  final String engine;
  final String owner;
  final String adminSecret = UUID.randomUUID().toString();
  final String runtimeSecret = UUID.randomUUID().toString();
  final LoopbackSource replayAdmin;
  final LoopbackSource operationAdmin;
  final LoopbackSource replay;
  final LoopbackSource operations;
  final int port;

  LocalDatabases(String image) {
    postgres = image.startsWith("postgres:");
    engine = postgres ? "POSTGRESQL_16_4" : image.contains("11.4.13") ? "MARIADB_11_4_13" : "MARIADB_10_6_25";
    int internal = postgres ? 5432 : 3306;
    container = postgres ? new PostgreSQLContainer<>(image) : new MariaDBContainer<>(image);
    container.withPassword(adminSecret).withStartupTimeout(Duration.ofSeconds(60))
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(internal))));
    try {
      container.start(); port = container.getMappedPort(internal);
      for (var endpoints : container.getContainerInfo().getHostConfig().getPortBindings().getBindings().values())
        for (var endpoint : endpoints) assertThat(endpoint.getHostIp()).isEqualTo("127.0.0.1");
      owner = postgres ? container.getUsername() : "root";
      var bootstrap = source(container.getDatabaseName(), owner, adminSecret);
      execute(bootstrap, "CREATE DATABASE tp_replay"); execute(bootstrap, "CREATE DATABASE tp_operations");
      execute(bootstrap, "CREATE DATABASE tp_empty");
      replayAdmin = source("tp_replay", owner, adminSecret); operationAdmin = source("tp_operations", owner, adminSecret);
      WorkloadIdentityReplayDatabase.initializeEmpty(replayAdmin, 128); OperationDatabase.initializeEmpty(operationAdmin);
      execute(bootstrap, postgres ? "CREATE ROLE " + USER + " LOGIN PASSWORD '" + runtimeSecret + "'"
          : "CREATE USER '" + USER + "'@'%' IDENTIFIED BY '" + runtimeSecret + "'");
      replay = source("tp_replay", USER, runtimeSecret); operations = source("tp_operations", USER, runtimeSecret);
      if (postgres) {
        execute(replayAdmin, "GRANT USAGE ON SCHEMA public TO " + USER);
        execute(operationAdmin, "GRANT USAGE ON SCHEMA public TO " + USER);
      }
      for (String table : List.of("tp_identity_replay_schema_history", "tp_workload_replay_claims", "tp_workload_replay_state"))
        grant(replayAdmin, "SELECT", table, true);
      grant(replayAdmin, "INSERT", "tp_workload_replay_claims", true);
      grant(replayAdmin, "UPDATE", "tp_workload_replay_state", true);
      grant(operationAdmin, "SELECT", "tp_operation_schema_history", true);
      for (String table : OPERATIONS) {
        grant(operationAdmin, "SELECT", table, true);
        if (!table.equals("tp_operation_lifecycle_facts")) grant(operationAdmin, "INSERT", table, true);
      }
    } catch (RuntimeException | Error failed) { container.stop(); throw new AssertionError("SYNTHETIC_SETUP_FAILED"); }
  }

  LoopbackSource source(String catalog, String user, String secret) {
    return new LoopbackSource(new OperatorConfig.Database(engine, port, catalog, user, secret));
  }
  ObjectNode config(boolean admin) {
    var config = SyntheticIdentity.config();
    for (String field : List.of("replay", "operations")) {
      var db = (ObjectNode) config.get(field);
      db.put("engine", engine).put("port", port).put("user", admin ? owner : USER)
          .put("password", admin ? adminSecret : runtimeSecret);
    }
    return config;
  }
  void reset() {
    grant(replayAdmin, "INSERT", "tp_workload_replay_claims", true);
    grant(operationAdmin, "INSERT", "tp_business_operation_facts", true);
    for (String table : OPERATIONS) execute(operationAdmin, "DELETE FROM " + table);
    execute(replayAdmin, "DELETE FROM tp_workload_replay_claims");
    execute(replayAdmin, "UPDATE tp_workload_replay_state SET total_claims=0, capacity=128 WHERE singleton=1");
  }
  void grant(DataSource admin, String privilege, String table, boolean allow) {
    String catalog = admin == replayAdmin ? "tp_replay" : "tp_operations";
    execute(admin, (allow ? "GRANT " : "REVOKE ") + privilege + " ON "
        + (postgres ? table : "`" + catalog + "`." + table)
        + (allow ? " TO " : " FROM ") + (postgres ? USER : "'" + USER + "'@'%'"));
  }
  static void execute(DataSource source, String sql) {
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5); statement.execute(sql);
    } catch (SQLException failed) { throw new AssertionError("SYNTHETIC_ADMIN_FAILED"); }
  }
  static long count(DataSource source, String table) {
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
        if (!rows.next()) throw new AssertionError("SYNTHETIC_COUNT_MISSING");
        return rows.getLong(1);
      }
    } catch (SQLException failed) { throw new AssertionError("SYNTHETIC_COUNT_FAILED"); }
  }
  @Override public void close() { container.stop(); }
}
