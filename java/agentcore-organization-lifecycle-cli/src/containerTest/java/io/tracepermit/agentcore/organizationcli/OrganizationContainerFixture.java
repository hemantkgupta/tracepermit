package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.persistence.identity.WorkloadIdentityReplayDatabase;
import io.tracepermit.persistence.operations.OperationAuthorityAdministration;
import io.tracepermit.persistence.operations.OperationDatabase;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Generated identities plus exact PostgreSQL16.4 bootstrap and restricted runtime grants. */
final class OrganizationContainerFixture implements AutoCloseable {
  private static final String RUNTIME_USER = "tp_org_operator_runtime";
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

  final OrganizationOperatorFixture identity = new OrganizationOperatorFixture();
  final PostgreSQLContainer<?> container;
  final Source replayAdmin;
  final Source operationAdmin;
  final Source runtimeOperations;
  private final String runtimeSecret;

  OrganizationContainerFixture() {
    container =
        new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withPassword(UUID.randomUUID().toString())
            .withStartupTimeout(Duration.ofSeconds(60))
            .withCreateContainerCmdModifier(
                command ->
                    command
                        .getHostConfig()
                        .withPortBindings(
                            new PortBinding(
                                Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                                new ExposedPort(5432))));
    runtimeSecret = UUID.randomUUID().toString();
    Source replay = null;
    Source operations = null;
    Source runtime = null;
    try {
      container.start();
      assertThat(container.getHost()).isIn("localhost", "127.0.0.1");
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
      Source bootstrap =
          source(
              container.getDatabaseName(), container.getUsername(), container.getPassword());
      execute(bootstrap, "CREATE DATABASE tp_org_replay");
      execute(bootstrap, "CREATE DATABASE tp_org_operations");
      replay = source("tp_org_replay", container.getUsername(), container.getPassword());
      operations =
          source("tp_org_operations", container.getUsername(), container.getPassword());
      WorkloadIdentityReplayDatabase.initializeEmpty(replay, 64);
      OperationDatabase.initializeDeliveryEmpty(operations);
      execute(
          bootstrap,
          "CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD '" + runtimeSecret + "'");
      execute(replay, "GRANT USAGE ON SCHEMA public TO " + RUNTIME_USER);
      execute(operations, "GRANT USAGE ON SCHEMA public TO " + RUNTIME_USER);
      for (String table :
          List.of(
              "tp_identity_replay_schema_history",
              "tp_workload_replay_claims",
              "tp_workload_replay_state")) {
        grant(replay, "SELECT", table);
      }
      grant(replay, "INSERT", "tp_workload_replay_claims");
      grant(replay, "UPDATE", "tp_workload_replay_state");
      for (String table : OPERATION_TABLES) grant(operations, "SELECT", table);
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
        grant(operations, "INSERT", table);
      }
      execute(
          operations,
          "GRANT UPDATE(snapshot_hash, snapshot_json) ON tp_operation_lifecycle_heads TO "
              + RUNTIME_USER);
      execute(
          operations,
          "GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO " + RUNTIME_USER);
      runtime = source("tp_org_operations", RUNTIME_USER, runtimeSecret);
      database((ObjectNode) identity.configuration.path("replay"), "tp_org_replay");
      database((ObjectNode) identity.configuration.path("operations"), "tp_org_operations");
      OrganizationOperatorConfig.parse(identity.configuration);
    } catch (RuntimeException | Error failure) {
      container.stop();
      throw new AssertionError("ORGANIZATION_CONTAINER_POSTGRES_SETUP_FAILED", failure);
    }
    replayAdmin = replay;
    operationAdmin = operations;
    runtimeOperations = runtime;
  }

  String databaseContainerId() {
    String value = container.getContainerId();
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw new AssertionError("ORGANIZATION_POSTGRES_CONTAINER_ID_REQUIRED");
    }
    return value;
  }

  byte[] offlineInput() {
    return OrganizationOperatorFixture.frames(identity.configBytes());
  }

  byte[] registrationInput(String requestKey) {
    return OrganizationOperatorFixture.frames(
        identity.configBytes(),
        identity.requesterToken(
            OrganizationOperatorFixture.REQUESTER_SUBJECT,
            Set.of("operations.register")),
        identity.registrationBytes(requestKey));
  }

  byte[] input(String operationId, String command, String selected) {
    byte[] token =
        command.equals("review")
            ? identity.reviewerToken(OrganizationOperatorFixture.REVIEWER_SUBJECT)
            : identity.requesterToken(
                OrganizationOperatorFixture.REQUESTER_SUBJECT,
                Set.of(
                    command.equals("evaluate")
                        ? "operations.evaluate"
                        : "operations.execute"));
    byte[] operation = identity.commandBytes(operationId, command, selected);
    if (!command.equals("deliver-synthetic")) {
      return OrganizationOperatorFixture.frames(identity.configBytes(), token, operation);
    }
    byte[] key = identity.privateKey();
    try {
      return OrganizationOperatorFixture.frames(identity.configBytes(), token, key, operation);
    } finally {
      java.util.Arrays.fill(key, (byte) 0);
    }
  }

  void activateExactRegisteredAuthority() {
    var profile = OrganizationOperatorConfig.parse(identity.configuration).authorityProfile();
    OperationAuthorityAdministration.stageNew(operationAdmin, profile);
    OperationAuthorityAdministration.activateStaged(operationAdmin, profile);
  }

  void assertRuntimeGrantsRemainRestricted(String operationId) {
    assertDenied(
        runtimeOperations,
        "DELETE FROM tp_operation_delivery_events WHERE operation_id = '"
            + operationId
            + "'");
    assertDenied(runtimeOperations, "CREATE TABLE tp_forbidden (id INTEGER)");
    assertDenied(
        runtimeOperations,
        "UPDATE tp_operation_authority_controls SET control_state = control_state "
            + "WHERE scope_hash = '"
            + OrganizationOperatorConfig.parse(identity.configuration).scope().fingerprint()
            + "'");
  }

  private void database(ObjectNode node, String catalog) {
    node.put("host", "127.0.0.1");
    node.put("port", 5432);
    node.put("catalog", catalog);
    node.put("user", RUNTIME_USER);
    node.put("password", runtimeSecret);
  }

  private Source source(String catalog, String user, String password) {
    return new Source(
        "jdbc:postgresql://127.0.0.1:"
            + container.getMappedPort(5432)
            + "/"
            + catalog
            + "?connectTimeout=5&socketTimeout=10",
        user,
        password);
  }

  private static void grant(Source source, String privilege, String table) {
    execute(source, "GRANT " + privilege + " ON " + table + " TO " + RUNTIME_USER);
  }

  private static void execute(DataSource source, String sql) {
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(sql);
    } catch (SQLException failure) {
      throw new AssertionError("ORGANIZATION_CONTAINER_ADMIN_ACTION_FAILED", failure);
    }
  }

  private static void assertDenied(Source source, String sql) {
    assertThatThrownBy(
            () -> {
              try (Connection connection = source.getConnection();
                  var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute(sql);
              }
            })
        .isInstanceOfSatisfying(
            SQLException.class,
            failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
  }

  @Override
  public void close() {
    container.stop();
  }

  record Source(String url, String user, String password) implements DataSource {
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
