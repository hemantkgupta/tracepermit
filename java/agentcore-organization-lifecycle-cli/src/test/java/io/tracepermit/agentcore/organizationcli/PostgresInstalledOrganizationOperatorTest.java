package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.persistence.identity.WorkloadIdentityReplayDatabase;
import io.tracepermit.persistence.operations.OperationAuthorityAdministration;
import io.tracepermit.persistence.operations.OperationDatabase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;

/** Installed PostgreSQL 16.4 contract; every delivery remains in-process and synthetic. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(240)
final class PostgresInstalledOrganizationOperatorTest {
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
  private static final String RUNTIME_USER = "tp_org_operator_runtime";

  private PostgreSQLContainer<?> postgres;
  private Source replayAdmin;
  private Source operationAdmin;
  private String runtimeSecret;
  private Path extractedRoot;
  private Path launcher;
  private OrganizationOperatorFixture fixture;

  @BeforeAll
  void installPostgresAndExtractCandidate() throws Exception {
    postgres =
        new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withPassword(UUID.randomUUID().toString())
            .withStartupTimeout(Duration.ofSeconds(60));
    postgres.start();
    assertThat(postgres.getHost()).isIn("localhost", "127.0.0.1");
    Source bootstrap =
        source(postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword());
    execute(bootstrap, "CREATE DATABASE tp_org_replay");
    execute(bootstrap, "CREATE DATABASE tp_org_operations");
    replayAdmin = source("tp_org_replay", postgres.getUsername(), postgres.getPassword());
    operationAdmin = source("tp_org_operations", postgres.getUsername(), postgres.getPassword());
    WorkloadIdentityReplayDatabase.initializeEmpty(replayAdmin, 64);
    OperationDatabase.initializeDeliveryEmpty(operationAdmin);

    runtimeSecret = UUID.randomUUID().toString();
    execute(
        bootstrap,
        "CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD '" + runtimeSecret + "'");
    execute(replayAdmin, "GRANT USAGE ON SCHEMA public TO " + RUNTIME_USER);
    execute(operationAdmin, "GRANT USAGE ON SCHEMA public TO " + RUNTIME_USER);
    for (String table :
        List.of(
            "tp_identity_replay_schema_history",
            "tp_workload_replay_claims",
            "tp_workload_replay_state")) {
      grant(replayAdmin, "SELECT", table);
    }
    grant(replayAdmin, "INSERT", "tp_workload_replay_claims");
    grant(replayAdmin, "UPDATE", "tp_workload_replay_state");
    for (String table : OPERATION_TABLES) grant(operationAdmin, "SELECT", table);
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
      grant(operationAdmin, "INSERT", table);
    }
    execute(
        operationAdmin,
        "GRANT UPDATE(snapshot_hash, snapshot_json) ON tp_operation_lifecycle_heads TO "
            + RUNTIME_USER);
    execute(
        operationAdmin,
        "GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO " + RUNTIME_USER);

    extractedRoot = Files.createTempDirectory("tp-organization-operator-candidate-");
    assertThat(
            extractedRoot
                .toRealPath()
                .startsWith(Path.of(System.getProperty("tracepermit.repositoryRoot")).toRealPath()))
        .isFalse();
    Process tar =
        new ProcessBuilder(
                "/usr/bin/tar",
                "-xf",
                System.getProperty("tracepermit.organization.archive"),
                "-C",
                extractedRoot.toString())
            .start();
    assertThat(tar.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThat(tar.exitValue()).isZero();
    try (var children = Files.list(extractedRoot)) {
      Path home = children.findFirst().orElseThrow();
      launcher = home.resolve("bin/tracepermit-synthetic-organization-operator");
    }
    assertThat(Files.isExecutable(launcher)).isTrue();
  }

  @BeforeEach
  void resetAndActivateExactAuthority() {
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
    fixture = new OrganizationOperatorFixture();
    ObjectNode replay = (ObjectNode) fixture.configuration.path("replay");
    ObjectNode operations = (ObjectNode) fixture.configuration.path("operations");
    database(replay, "tp_org_replay");
    database(operations, "tp_org_operations");
    var profile = OrganizationOperatorConfig.parse(fixture.configuration).authorityProfile();
    OperationAuthorityAdministration.stageNew(operationAdmin, profile);
    OperationAuthorityAdministration.activateStaged(operationAdmin, profile);
  }

  @AfterAll
  void cleanup() throws Exception {
    if (postgres != null) postgres.stop();
    if (extractedRoot != null && Files.exists(extractedRoot)) {
      try (var paths = Files.walk(extractedRoot)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  @Test
  void separateProcessesCompleteLifecycleAndRuntimeGrantsRemainRestricted() throws Exception {
    String operationId = register("valid-lifecycle").operationId();
    assertThat(
            call(
                    "inspect",
                    requester("operations.execute"),
                    command(operationId, "inspect", null))
                .outcome())
        .isEqualTo("OBSERVED");
    assertThat(
            call(
                    "evaluate",
                    requester("operations.evaluate"),
                    command(operationId, "evaluate", null))
                .outcome())
        .isEqualTo("EVALUATED");
    assertThat(call("review", reviewer(), command(operationId, "review", "APPROVE")).outcome())
        .isEqualTo("REVIEW_APPROVED");
    OrganizationOperatorReceipt delivered =
        call(
            "deliver-synthetic",
            requester("operations.execute"),
            fixture.privateKey(),
            command(operationId, "deliver-synthetic", "CONFIRMED_SUCCESS"));
    assertThat(delivered.outcome()).isEqualTo("DELIVERY_SUCCEEDED");
    assertThat(delivered.dispatchStarted()).isTrue();
    OrganizationOperatorReceipt reconciled =
        call("reconcile", requester("operations.execute"), command(operationId, "reconcile", null));
    assertThat(reconciled.outcome()).isEqualTo("RECONCILED");
    assertThat(reconciled.dispatchStarted()).isTrue();

    Source runtime = source("tp_org_operations", RUNTIME_USER, runtimeSecret);
    assertDenied(
        runtime,
        "DELETE FROM tp_operation_delivery_events WHERE operation_id = '"
            + operationId
            + "'");
    assertDenied(runtime, "CREATE TABLE tp_forbidden (id INTEGER)");
    assertDenied(
        runtime,
        "UPDATE tp_operation_authority_controls SET control_state = control_state "
            + "WHERE scope_hash = '"
            + OrganizationOperatorConfig.parse(fixture.configuration).scope().fingerprint()
            + "'");
  }

  @Test
  void inProcessRuntimeCompletesTheSameBoundLifecycle() {
    OrganizationOperatorReceipt registered =
        direct(
            "register",
            requester("operations.register"),
            fixture.registrationBytes("direct-valid-lifecycle"));
    assertThat(registered.outcome()).isEqualTo("CREATED");
    String operationId = registered.operationId();
    assertThat(
            direct(
                    "inspect",
                    requester("operations.execute"),
                    command(operationId, "inspect", null))
                .outcome())
        .isEqualTo("OBSERVED");
    assertThat(
            direct(
                    "evaluate",
                    requester("operations.evaluate"),
                    command(operationId, "evaluate", null))
                .outcome())
        .isEqualTo("EVALUATED");
    assertThat(
            direct("review", reviewer(), command(operationId, "review", "APPROVE")).outcome())
        .isEqualTo("REVIEW_APPROVED");
    assertThat(
            direct(
                    "deliver-synthetic",
                    requester("operations.execute"),
                    fixture.privateKey(),
                    command(operationId, "deliver-synthetic", "CONFIRMED_SUCCESS"))
                .outcome())
        .isEqualTo("DELIVERY_SUCCEEDED");
    OrganizationOperatorReceipt reconciled =
        direct(
            "reconcile",
            requester("operations.execute"),
            command(operationId, "reconcile", null));
    assertThat(reconciled.outcome()).isEqualTo("RECONCILED");
    assertThat(reconciled.dispatchStarted()).isTrue();
  }

  @Test
  void inProcessDenialAndUnknownRemainFailClosedWithoutResend() {
    OrganizationOperatorReceipt deniedRegistration =
        direct(
            "register",
            requester("operations.register"),
            fixture.registrationBytes("direct-denied-lifecycle"));
    String denied = deniedRegistration.operationId();
    assertThat(
            direct(
                    "deliver-synthetic",
                    requester("operations.execute"),
                    fixture.privateKey(),
                    command(denied, "deliver-synthetic", "CONFIRMED_SUCCESS"))
                .outcome())
        .isEqualTo("REJECTED");
    assertThat(
            direct(
                    "evaluate",
                    requester("operations.evaluate"),
                    command(denied, "evaluate", null))
                .outcome())
        .isEqualTo("EVALUATED");
    assertThat(direct("review", reviewer(), command(denied, "review", "DENY")).outcome())
        .isEqualTo("REVIEW_DENIED");
    assertThat(
            direct(
                    "deliver-synthetic",
                    requester("operations.execute"),
                    fixture.privateKey(),
                    command(denied, "deliver-synthetic", "CONFIRMED_SUCCESS"))
                .outcome())
        .isEqualTo("REJECTED");

    OrganizationOperatorReceipt unknownRegistration =
        direct(
            "register",
            requester("operations.register"),
            fixture.registrationBytes("direct-unknown-lifecycle"));
    String unknown = unknownRegistration.operationId();
    assertThat(
            direct(
                    "evaluate",
                    requester("operations.evaluate"),
                    command(unknown, "evaluate", null))
                .outcome())
        .isEqualTo("EVALUATED");
    assertThat(direct("review", reviewer(), command(unknown, "review", "APPROVE")).outcome())
        .isEqualTo("REVIEW_APPROVED");
    assertThat(
            direct(
                    "deliver-synthetic",
                    requester("operations.execute"),
                    fixture.privateKey(),
                    command(unknown, "deliver-synthetic", "UNKNOWN"))
                .outcome())
        .isEqualTo("DELIVERY_UNKNOWN");
    OrganizationOperatorReceipt reconciled =
        direct(
            "reconcile",
            requester("operations.execute"),
            command(unknown, "reconcile", null));
    assertThat(reconciled.outcome()).isEqualTo("RECONCILED");
    assertThat(reconciled.dispatchStarted()).isTrue();
    assertThat(
            direct(
                    "deliver-synthetic",
                    requester("operations.execute"),
                    fixture.privateKey(),
                    command(unknown, "deliver-synthetic", "CONFIRMED_SUCCESS"))
                .outcome())
        .isEqualTo("REJECTED");
  }

  @Test
  void installedCheckConfigIsOfflineWithAnApparentlyReadyLoopbackPort() throws Exception {
    try (var sentinel = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
      sentinel.setSoTimeout(200);
      int port = sentinel.getLocalPort();
      ((ObjectNode) fixture.configuration.path("replay")).put("port", port);
      ((ObjectNode) fixture.configuration.path("operations")).put("port", port);
      assertThat(call("check-config").outcome()).isEqualTo("CONFIG_VALID");
      assertThatThrownBy(sentinel::accept)
          .isInstanceOf(java.net.SocketTimeoutException.class);
    }
  }

  @Test
  void denialAndRecordedUnknownNeverBecomeResendAuthority() throws Exception {
    String denied = register("denied-lifecycle").operationId();
    assertThat(
            call(
                    "deliver-synthetic",
                    requester("operations.execute"),
                    fixture.privateKey(),
                    command(denied, "deliver-synthetic", "CONFIRMED_SUCCESS"))
                .outcome())
        .isEqualTo("REJECTED");
    assertThat(count(operationAdmin, "tp_operation_delivery_events")).isZero();
    assertThat(
            call(
                    "evaluate",
                    requester("operations.evaluate"),
                    command(denied, "evaluate", null))
                .outcome())
        .isEqualTo("EVALUATED");
    assertThat(call("review", reviewer(), command(denied, "review", "DENY")).outcome())
        .isEqualTo("REVIEW_DENIED");
    assertThat(
            call(
                    "deliver-synthetic",
                    requester("operations.execute"),
                    fixture.privateKey(),
                    command(denied, "deliver-synthetic", "CONFIRMED_SUCCESS"))
                .outcome())
        .isEqualTo("REJECTED");
    assertThat(count(operationAdmin, "tp_operation_delivery_events")).isZero();

    String unknown = approved("unknown-lifecycle");
    OrganizationOperatorReceipt first =
        call(
            "deliver-synthetic",
            requester("operations.execute"),
            fixture.privateKey(),
            command(unknown, "deliver-synthetic", "UNKNOWN"));
    assertThat(first.outcome()).isEqualTo("DELIVERY_UNKNOWN");
    assertThat(first.exitCode()).isZero();
    OrganizationOperatorReceipt observed =
        call("reconcile", requester("operations.execute"), command(unknown, "reconcile", null));
    assertThat(observed.outcome()).isEqualTo("RECONCILED");
    assertThat(observed.dispatchStarted()).isTrue();
    assertThat(
            call(
                    "deliver-synthetic",
                    requester("operations.execute"),
                    fixture.privateKey(),
                    command(unknown, "deliver-synthetic", "CONFIRMED_SUCCESS"))
                .outcome())
        .isEqualTo("REJECTED");
  }

  @Test
  void eightInstalledContendersProduceOneAcknowledgedDeliveryReceipt() throws Exception {
    String operationId = approved("contended-lifecycle");
    var work = new ArrayList<Callable<OrganizationOperatorReceipt>>();
    for (int index = 0; index < 8; index++) {
      work.add(
          () ->
              call(
                  "deliver-synthetic",
                  requester("operations.execute"),
                  fixture.privateKey(),
                  command(operationId, "deliver-synthetic", "CONFIRMED_SUCCESS")));
    }
    long successes;
    try (var executor = Executors.newFixedThreadPool(8)) {
      var futures = executor.invokeAll(work, 120, TimeUnit.SECONDS);
      List<OrganizationOperatorReceipt> receipts = new ArrayList<>();
      for (var future : futures) {
        assertThat(future.isCancelled()).isFalse();
        receipts.add(future.get(1, TimeUnit.SECONDS));
      }
      successes =
          receipts.stream()
              .filter(value -> value.outcome().equals("DELIVERY_SUCCEEDED"))
              .count();
      assertThat(successes).isBetween(0L, 1L);
      assertThat(
              receipts.stream()
                  .filter(value -> !value.outcome().equals("DELIVERY_SUCCEEDED")))
          .hasSize(8 - (int) successes)
          .allSatisfy(
              value -> {
                // A concurrent pre-effect admission write may be conservatively UNKNOWN rather
                // than REJECTED. Both outcomes are non-authorizing and forbid automatic retry.
                assertThat(value.outcome()).isIn("REJECTED", "UNKNOWN");
                assertThat(value.exitCode()).isEqualTo(1);
                assertThat(value.operationId()).isNull();
                assertThat(value.dispatchStarted()).isNull();
              });
    }
    OrganizationOperatorReceipt reconciled =
        call(
            "reconcile",
            requester("operations.execute"),
            command(operationId, "reconcile", null));
    assertThat(reconciled.outcome()).isEqualTo("RECONCILED");
    assertThat(reconciled.dispatchStarted()).isEqualTo(successes == 1);
  }

  private String approved(String requestKey) throws Exception {
    String operationId = register(requestKey).operationId();
    assertThat(
            call(
                    "evaluate",
                    requester("operations.evaluate"),
                    command(operationId, "evaluate", null))
                .outcome())
        .isEqualTo("EVALUATED");
    assertThat(call("review", reviewer(), command(operationId, "review", "APPROVE")).outcome())
        .isEqualTo("REVIEW_APPROVED");
    return operationId;
  }

  private OrganizationOperatorReceipt register(String requestKey) throws Exception {
    OrganizationOperatorReceipt result =
        call(
            "register",
            requester("operations.register"),
            fixture.registrationBytes(requestKey));
    assertThat(result.outcome()).isEqualTo("CREATED");
    assertThat(result.created()).isTrue();
    return result;
  }

  private byte[] requester(String scope) {
    return fixture.requesterToken(OrganizationOperatorFixture.REQUESTER_SUBJECT, Set.of(scope));
  }

  private byte[] reviewer() {
    return fixture.reviewerToken(OrganizationOperatorFixture.REVIEWER_SUBJECT);
  }

  private byte[] command(String operationId, String command, String value) {
    return fixture.commandBytes(operationId, command, value);
  }

  private OrganizationOperatorReceipt call(String command, byte[]... frames) throws Exception {
    byte[][] all = new byte[frames.length + 1][];
    all[0] = fixture.configBytes();
    System.arraycopy(frames, 0, all, 1, frames.length);
    ProcessBuilder builder =
        new ProcessBuilder(launcher.toString(), command).directory(extractedRoot.toFile());
    builder.environment().clear();
    builder.environment().put("PATH", "/usr/bin:/bin");
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    Process process = builder.start();
    var output = new ByteArrayOutputStream();
    var errors = new ByteArrayOutputStream();
    Thread out = capture(process.getInputStream(), output);
    Thread err = capture(process.getErrorStream(), errors);
    try {
      process.getOutputStream().write(OrganizationOperatorFixture.frames(all));
      process.getOutputStream().close();
      assertThat(process.waitFor(70, TimeUnit.SECONDS)).isTrue();
      out.join(1_000);
      err.join(1_000);
      assertThat(out.isAlive() || err.isAlive()).isFalse();
      assertThat(errors.toByteArray()).isEmpty();
      assertThat(output.size()).isLessThanOrEqualTo(OrganizationOperatorProtocol.RECEIPT_BYTES);
      OrganizationOperatorReceipt receipt =
          OrganizationOperatorReceipt.decode(output.toByteArray(), process.exitValue());
      assertThat(receipt.bytes()).isEqualTo(output.toByteArray());
      return receipt;
    } finally {
      process.toHandle().destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
  }

  private OrganizationOperatorReceipt direct(String command, byte[]... frames) {
    byte[][] all = new byte[frames.length + 1][];
    all[0] = fixture.configBytes();
    System.arraycopy(frames, 0, all, 1, frames.length);
    return OrganizationOperatorWorker.execute(
        new ByteArrayInputStream(OrganizationOperatorFixture.frames(all)), command);
  }

  private Thread capture(java.io.InputStream source, ByteArrayOutputStream target) {
    Thread thread =
        new Thread(
            () -> {
              try {
                source.transferTo(target);
              } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
              }
            });
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private void database(ObjectNode node, String catalog) {
    node.put("port", postgres.getMappedPort(5432));
    node.put("catalog", catalog);
    node.put("user", RUNTIME_USER);
    node.put("password", runtimeSecret);
  }

  private Source source(String database, String user, String password) {
    return new Source(
        "jdbc:postgresql://127.0.0.1:"
            + postgres.getMappedPort(5432)
            + "/"
            + database
            + "?connectTimeout=5&socketTimeout=10",
        user,
        password);
  }

  private static void grant(Source source, String privilege, String table) {
    execute(source, "GRANT " + privilege + " ON " + table + " TO " + RUNTIME_USER);
  }

  private static void execute(Source source, String sql) {
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(sql);
    } catch (SQLException failure) {
      throw new AssertionError("ORGANIZATION_OPERATOR_ADMIN_FAILED", failure);
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
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
  }

  private static long count(Source source, String table) {
    try (Connection connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
        if (!rows.next()) throw new SQLException("ORGANIZATION_OPERATOR_COUNT_MISSING");
        long count = rows.getLong(1);
        if (rows.next()) throw new SQLException("ORGANIZATION_OPERATOR_COUNT_DUPLICATE");
        return count;
      }
    } catch (SQLException failure) {
      throw new AssertionError("ORGANIZATION_OPERATOR_COUNT_FAILED", failure);
    }
  }

  private record Source(String url, String user, String password) implements DataSource {
    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String selectedUser, String selectedPassword)
        throws SQLException {
      return DriverManager.getConnection(url, selectedUser, selectedPassword);
    }

    @Override
    public PrintWriter getLogWriter() {
      return DriverManager.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter writer) {
      DriverManager.setLogWriter(writer);
    }

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
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return false;
    }
  }
}
