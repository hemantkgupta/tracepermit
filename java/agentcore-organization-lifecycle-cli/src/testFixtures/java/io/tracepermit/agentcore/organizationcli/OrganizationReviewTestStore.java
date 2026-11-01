package io.tracepermit.agentcore.organizationcli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.persistence.identity.WorkloadIdentityReplayDatabase;
import io.tracepermit.persistence.operations.JdbcGitHubOperationInspector;
import io.tracepermit.persistence.operations.OperationAuthorityAdministration;
import io.tracepermit.persistence.operations.OperationDatabase;
import java.io.ByteArrayInputStream;
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
import org.testcontainers.containers.MariaDBContainer;

/** Test-only temporary exact MariaDB, restricted runtime and independent administrator observer. */
public final class OrganizationReviewTestStore implements AutoCloseable {
  private static final String USER = "tp_review_runtime";
  private static final Set<String> TABLES = Set.of("tp_operation_schema_history",
      "tp_business_operations", "tp_business_operation_facts", "tp_operation_lifecycle_heads",
      "tp_operation_lifecycle_facts", "tp_operation_authority_controls", "tp_operation_review_bindings",
      "tp_operation_admissions", "tp_operation_admission_outbox", "tp_operation_delivery_events");
  public final OrganizationOperatorFixture fixture = OrganizationOperatorFixture.auth0();
  private final MariaDBContainer<?> maria;
  private final Source admin;
  private boolean closed;

  public OrganizationReviewTestStore() {
    fixture.useReviewHelperClaims();
    String password = UUID.randomUUID().toString();
    maria = new MariaDBContainer<>("mariadb:11.4.13").withPassword(password)
        .withImagePullPolicy(image -> false).withStartupTimeout(Duration.ofSeconds(60))
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(3306))));
    try {
      maria.start();
      require(Set.of("localhost", "127.0.0.1").contains(maria.getHost()));
      maria.getContainerInfo().getHostConfig().getPortBindings().getBindings().values()
          .forEach(bindings -> { for (var binding : bindings) require("127.0.0.1".equals(binding.getHostIp())); });
      Source bootstrap = source(maria.getDatabaseName(), "root", password);
      try (var connection = bootstrap.getConnection()) {
        require("MariaDB".equals(connection.getMetaData().getDatabaseProductName())
            && connection.getMetaData().getDatabaseProductVersion().matches("11\\.4\\.13(?:[^0-9.].*)?"));
      }
      execute(bootstrap, "CREATE DATABASE tp_review_operations");
      execute(bootstrap, "CREATE DATABASE tp_review_replay");
      admin = source("tp_review_operations", "root", password);
      Source replay = source("tp_review_replay", "root", password);
      OperationDatabase.initializeDeliveryEmpty(admin);
      WorkloadIdentityReplayDatabase.initializeEmpty(replay, 64);
      String runtime = UUID.randomUUID().toString();
      execute(bootstrap, "CREATE USER '" + USER + "'@'%' IDENTIFIED BY '" + runtime + "'");
      for (String table : TABLES) grant(admin, "tp_review_operations", "SELECT", table);
      for (String table : List.of("tp_business_operations", "tp_business_operation_facts",
          "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_review_bindings",
          "tp_operation_admissions", "tp_operation_admission_outbox", "tp_operation_delivery_events")) {
        grant(admin, "tp_review_operations", "INSERT", table);
      }
      grant(admin, "tp_review_operations", "UPDATE(snapshot_hash, snapshot_json)", "tp_operation_lifecycle_heads");
      for (String table : List.of("tp_identity_replay_schema_history", "tp_workload_replay_claims", "tp_workload_replay_state")) {
        grant(replay, "tp_review_replay", "SELECT", table);
      }
      grant(replay, "tp_review_replay", "INSERT", "tp_workload_replay_claims");
      grant(replay, "tp_review_replay", "UPDATE", "tp_workload_replay_state");
      configure("operations", "tp_review_operations", runtime);
      configure("replay", "tp_review_replay", runtime);
      var config = OrganizationOperatorConfig.parse(fixture.configuration);
      OperationAuthorityAdministration.stageNew(admin, config.authorityProfile());
      OperationAuthorityAdministration.activateStaged(admin, config.authorityProfile());
    } catch (Exception | AssertionError failure) {
      maria.stop();throw new AssertionError("SYNTHETIC_REVIEW_DATABASE_SETUP_FAILED");
    }
  }

  private void configure(String field, String catalog, String secret) {
    ((ObjectNode)fixture.configuration.path(field)).put("engine", "MARIADB_11_4_13")
        .put("port", maria.getMappedPort(3306)).put("catalog", catalog)
        .put("user", USER).put("password", secret);
  }

  public String pending(String requestKey) {
    return pending(requestKey, "No live provider. Unicode data: π 😀\u2028A second line.");
  }

  public String pending(String requestKey, String body) {
    ObjectNode registration;
    try {
      registration = (ObjectNode)io.tracepermit.contracts.JsonSupport.MAPPER
          .readTree(fixture.registrationBytes(requestKey));
    } catch (java.io.IOException failure) { throw new AssertionError("SYNTHETIC_REVIEW_REQUEST_FAILED"); }
    registration.put("body", body);
    var registered = worker("register", fixture.requesterToken(fixture.requesterSubject(),
        Set.of("operations.register")), registration.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    requireOutcome(registered, "CREATED", "REGISTER");
    String id = registered.operationId();
    requireOutcome(worker("evaluate", fixture.requesterToken(fixture.requesterSubject(),
        Set.of("operations.evaluate")), fixture.commandBytes(id, "evaluate", null)), "EVALUATED", "EVALUATE");
    return id;
  }

  public JsonNode snapshot(String id) {
    var config = OrganizationOperatorConfig.parse(fixture.configuration);
    return new JdbcGitHubOperationInspector(admin, config.authorityProfile())
        .inspect(config.scope(), id).operation();
  }

  /** Independent test administrator read; does not pretend to validate current authority. */
  public JsonNode rawSnapshot(String id) {
    try (var connection = admin.getConnection();var statement = connection.prepareStatement(
        "SELECT snapshot_json FROM tp_operation_lifecycle_heads WHERE operation_id = ?")) {
      statement.setQueryTimeout(5);statement.setString(1, id);
      try (var rows = statement.executeQuery()) {
        require(rows.next());return io.tracepermit.contracts.JsonSupport.MAPPER.readTree(rows.getString(1));
      }
    } catch (java.io.IOException | SQLException failure) { throw new AssertionError("SYNTHETIC_REVIEW_OBSERVER_FAILED"); }
  }

  public void deny(String id) {
    require("REVIEW_DENIED".equals(worker("review", fixture.reviewerToken(fixture.reviewerSubject()),
        fixture.commandBytes(id, "review", "DENY")).outcome()));
  }

  public long count(String table) {
    require(TABLES.contains(table));
    try (var connection = admin.getConnection();var query = connection.createStatement()) {
      query.setQueryTimeout(5);
      try (var rows = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
        require(rows.next());return rows.getLong(1);
      }
    } catch (SQLException failure) { throw new AssertionError("SYNTHETIC_REVIEW_COUNT_FAILED"); }
  }

  public void quarantine() {
    execute(admin, "UPDATE tp_operation_authority_controls SET control_state='quarantined'");
  }

  public String deliveryOutcome(String id) {
    return worker("deliver-synthetic", fixture.requesterToken(fixture.requesterSubject(),
        Set.of("operations.execute")), fixture.privateKey(),
        fixture.commandBytes(id, "deliver-synthetic", "CONFIRMED_SUCCESS")).outcome();
  }

  private OrganizationOperatorReceipt worker(String command, byte[]... values) {
    byte[][] all = new byte[values.length + 1][];all[0] = fixture.configBytes();
    System.arraycopy(values, 0, all, 1, values.length);
    return OrganizationOperatorWorker.execute(new ByteArrayInputStream(OrganizationOperatorFixture.frames(all)), command);
  }
  private Source source(String catalog, String user, String password) {
    return new Source("jdbc:mariadb://127.0.0.1:" + maria.getMappedPort(3306) + "/" + catalog
        + "?connectTimeout=5000&socketTimeout=10000", user, password);
  }
  private static void grant(Source source, String database, String privilege, String table) {
    execute(source, "GRANT " + privilege + " ON " + database + "." + table + " TO '" + USER + "'@'%'");
  }
  private static void execute(Source source, String sql) {
    try (var connection = source.getConnection();var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);statement.execute(sql);
    } catch (SQLException failure) { throw new AssertionError("SYNTHETIC_REVIEW_ADMIN_FAILED"); }
  }
  private static void requireOutcome(OrganizationOperatorReceipt receipt, String expected, String phase) {
    // Closed receipt outcomes only: never emit request bodies, identities, tokens or JDBC errors.
    if (!expected.equals(receipt.outcome())) {
      throw new AssertionError("SYNTHETIC_REVIEW_" + phase + "_EXPECTED_" + expected + "_GOT_" + receipt.outcome());
    }
  }
  private static void require(boolean value) { if (!value) throw new AssertionError("SYNTHETIC_REVIEW_FIXTURE_INVALID"); }
  @Override public synchronized void close() {
    if (!closed) {closed = true;maria.stop();}
  }

  private record Source(String url, String user, String password) implements DataSource {
    @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    @Override public Connection getConnection(String u, String p) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter p) {}
    @Override public void setLoginTimeout(int s) {}
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
    @Override public String toString() { return "SyntheticReviewSource[redacted]"; }
  }
}
