package io.tracepermit.agentcore.registration;

import static io.tracepermit.agentcore.registration.RegistrationIdentity.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.agentcore.http.AgentCoreRegistrationHttpServer;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real HTTP, cached JWKS, shared replay, and durable registration through the closed factory. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
abstract class DurableRegistrationHttpContract {
  private final List<DurableAgentCoreRegistrationHttpServer> servers = new ArrayList<>();
  private final List<JwksFixture> identities = new ArrayList<>();
  private RegistrationDatabases db;

  abstract String image();

  @BeforeAll
  void startDatabase() {
    db = new RegistrationDatabases(image());
  }

  @AfterAll
  void stopDatabase() {
    if (db != null) db.close();
  }

  @BeforeEach
  void reset() {
    db.reset();
    db.replay.reset();
    db.operations.reset();
  }

  @AfterEach
  void closeOwnedResourcesAndCheckInventory() {
    if (db.replay.releaseWrite != null) db.replay.releaseWrite.countDown();
    for (int index = servers.size() - 1; index >= 0; index--) servers.get(index).close();
    for (JwksFixture identity : identities) identity.close();
    servers.clear();
    identities.clear();
    assertThat(db.replay.poolCloses.get()).isZero();
    assertThat(db.operations.poolCloses.get()).isZero();
    assertThat(db.replay.opens.get()).isEqualTo(db.replay.closes.get());
    assertThat(db.operations.opens.get()).isEqualTo(db.operations.closes.get());
    assertThat(tableNames()).containsExactlyInAnyOrder(
        "tp_operation_schema_history",
        "tp_business_operations",
        "tp_business_operation_facts",
        "tp_operation_lifecycle_heads",
        "tp_operation_lifecycle_facts");
    assertThat(count(db.operationAdmin, "tp_operation_lifecycle_facts")).isZero();
  }

  @Test
  void closedPublicSurfacePersistsOnlyExecutionDisabledInitialMetadata() throws Exception {
    assertThat(DurableAgentCoreRegistrationHttpServer.class.getConstructors()).isEmpty();
    assertThat(
            java.util.Arrays.stream(
                    DurableAgentCoreRegistrationHttpServer.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName))
        .containsExactlyInAnyOrder("create", "port", "close");
    assertThat(
            java.util.Arrays.stream(
                    DurableAgentCoreRegistrationHttpServer.class.getDeclaredMethods())
                .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                .map(Class::getName))
        .noneMatch(
            name ->
                name.contains("Supplier")
                    || name.contains("BusinessOperationRepository")
                    || name.contains("WorkloadIdentityReplayStore"));

    JwksFixture identity = identity();
    var server = open(identity, enrollment(), db.replay, db.operations, defaults());
    assertThat(server.port()).isBetween(1, 65_535);
    db.replay.reset();
    db.operations.reset();

    String authorization = token();
    var response = RegistrationHttpTestClient.send(server.port(), authorization, bytes(request()));
    assertThat(response.status()).isEqualTo(201);
    JsonNode receipt = JsonSupport.MAPPER.readTree(response.body());
    assertThat(receipt.fieldNames()).toIterable()
        .containsExactlyInAnyOrder("created", "operationId", "executionEnabled");
    assertThat(receipt.path("created").booleanValue()).isTrue();
    assertThat(receipt.path("executionEnabled").booleanValue()).isFalse();
    assertThat(receipt.path("operationId").textValue()).isNotBlank();
    assertThat(response.headers()).contains("Cache-Control: no-store").doesNotContain("Location:");
    assertThat(response.body()).doesNotContain(authorization, "sha256:", "canonicalAction");
    assertThat(identity.fetches).hasValue(1);
    assertCounts(1, 1);

    JsonNode stored = original();
    assertThat(BusinessOperationValidator.validate(stored).isObject()).isTrue();
    assertThat(stored.path("authorization").path("status").textValue()).isEqualTo("evaluating");
    assertThat(stored.path("execution").path("status").textValue()).isEqualTo("not_started");
    assertThat(stored.path("evidence").path("status").textValue()).isEqualTo("pending");
    assertThat(stored.path("canonicalAction").path("lineage").path("delegations")).isEmpty();
  }

  @Test
  void independentServersShareReplayAndImmutableRequestKeyRegistration() throws Exception {
    JwksFixture firstIdentity = identity();
    JwksFixture secondIdentity = identity();
    var first = open(firstIdentity, enrollment(), db.replay, db.operations, defaults());
    var second = open(secondIdentity, enrollment(), db.replay, db.operations, defaults());
    db.replay.reset();
    db.operations.reset();

    String sharedToken = token();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      var one = executor.submit(() -> racedSend(first.port(), sharedToken, ready, start));
      var two = executor.submit(() -> racedSend(second.port(), sharedToken, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertCounts(1, 1);
    JsonNode immutable = original();
    String operationId = immutable.path("operationId").textValue();
    String originalHash = CanonicalJson.sha256(immutable);

    for (DurableAgentCoreRegistrationHttpServer server : List.of(first, second)) {
      var existing = RegistrationHttpTestClient.send(server.port(), token(), bytes(request()));
      assertThat(existing.status()).isEqualTo(200);
      assertThat(JsonSupport.MAPPER.readTree(existing.body()).path("operationId").textValue())
          .isEqualTo(operationId);
    }
    assertCounts(3, 1);

    String conflictingToken = token();
    var conflict =
        RegistrationHttpTestClient.send(
            second.port(), conflictingToken, bytes(request().put("title", "Changed semantic content")));
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.body()).isEqualTo("{\"error\":\"AGENTCORE_REQUEST_KEY_CONFLICT\"}");
    int operationOpens = db.operations.opens.get();
    assertThat(
            RegistrationHttpTestClient.send(
                    first.port(),
                    conflictingToken,
                    bytes(request().put("clientRequestKey", "different-key")))
                .body())
        .isEqualTo("{\"error\":\"AGENTCORE_REGISTRATION_REJECTED\"}");
    assertThat(db.operations.opens.get()).isEqualTo(operationOpens);

    var profile = enrollment().targetProfile();
    var changedProfile =
        new GitHubBusinessOperationBinding.ExpectedProfile(
            profile.adapterId(),
            profile.environment(),
            profile.recoveryGeneration(),
            new GitHubBusinessOperationBinding.PolicyRelease(
                "new-proposed-policy", 2, "sha256:" + "8".repeat(64)),
            java.util.Map.of("adapter_release", "sha256:" + "9".repeat(64)),
            profile.targetBinding());
    var proposed =
        open(identity(), enrollment(SCOPE, changedProfile), db.replay, db.operations, defaults());
    var proposalOnly =
        RegistrationHttpTestClient.send(proposed.port(), token(), bytes(request()));
    assertThat(proposalOnly.status()).isEqualTo(200);
    assertThat(JsonSupport.MAPPER.readTree(proposalOnly.body()).path("operationId").textValue())
        .isEqualTo(operationId);
    assertThat(CanonicalJson.sha256(original())).isEqualTo(originalHash);
    assertCounts(5, 1);
  }

  @Test
  void terminatedChildProcessesShareTheDurableReplayBoundary() throws Exception {
    String authorization = token();
    byte[] body = bytes(request().put("clientRequestKey", "process-boundary-key"));
    assertThat(RegistrationChild.callHttp(db, authorization, body)).isEqualTo("CREATED");
    assertThat(RegistrationChild.callHttp(db, authorization, body)).isEqualTo("REPLAY_REJECTED");
    assertThat(RegistrationChild.callHttp(db, token(), body)).isEqualTo("EXISTING");
    assertCounts(2, 1);
  }

  @Test
  void invalidIdentityAuthorityFieldsAndHumanPairingHaveNoDurableEffect() throws Exception {
    var server = open(identity(), enrollment(), db.replay, db.operations, defaults());
    db.replay.reset();
    db.operations.reset();

    for (String field :
        List.of(
            "identity",
            "role",
            "authenticated",
            "verified",
            "delegated",
            "target",
            "policyRelease",
            "recoveryGeneration")) {
      var rejected =
          RegistrationHttpTestClient.send(
              server.port(), token(), bytes(request().put(field, "untrusted")));
      assertThat(rejected.status()).isEqualTo(400);
    }
    for (String mutation : List.of("expired", "scope", "enrollment")) {
      String authorization =
          token(
              claims -> {
                if (mutation.equals("expired")) claims.put("exp", NOW.minusSeconds(1).getEpochSecond());
                if (mutation.equals("scope")) claims.put("scope", "operations.other");
                if (mutation.equals("enrollment")) claims.put("sub", "workload:other");
              });
      assertThat(RegistrationHttpTestClient.send(server.port(), authorization, bytes(request())).status())
          .isEqualTo(401);
    }
    String valid = token();
    String badSignature = valid.substring(0, valid.length() - 1) + (valid.endsWith("A") ? "B" : "A");
    assertThat(RegistrationHttpTestClient.send(server.port(), badSignature, bytes(request())).status())
        .isEqualTo(401);
    var humanPair =
        RegistrationHttpTestClient.send(server.port(), token(), token(), bytes(request()));
    assertThat(humanPair.status()).isEqualTo(400);
    assertThat(humanPair.body())
        .isEqualTo("{\"error\":\"AGENTCORE_DELEGATION_CONTRACT_REQUIRED\"}");
    assertCounts(0, 0);
    assertThat(db.operations.opens.get()).isZero();
  }

  @Test
  void unreadyCapacityDisagreementAndSaturationFailClosed() throws Exception {
    JwksFixture identity = identity();
    assertThatThrownBy(
            () ->
                DurableAgentCoreRegistrationHttpServer.create(
                    identity.verifier(),
                    enrollment(),
                    CLOCK,
                    db.replay,
                    db.operations,
                    129,
                    Duration.ofSeconds(2),
                    0,
                    defaults()))
        .isInstanceOf(DurableAgentCoreRegistration.InitializationException.class)
        .hasMessage("DURABLE_REGISTRATION_NOT_READY")
        .hasNoCause();
    assertCounts(0, 0);

    RegistrationDatabases.execute(
        db.replayAdmin,
        "ALTER TABLE tp_workload_replay_claims RENAME TO tp_unready_replay_fixture");
    try {
      assertThatThrownBy(
              () ->
                  DurableAgentCoreRegistrationHttpServer.create(
                      identity.verifier(),
                      enrollment(),
                      CLOCK,
                      db.replay,
                      db.operations,
                      128,
                      Duration.ofSeconds(2),
                      0,
                      defaults()))
          .isInstanceOf(DurableAgentCoreRegistration.InitializationException.class)
          .hasMessage("DURABLE_REGISTRATION_NOT_READY");
    } finally {
      RegistrationDatabases.execute(
          db.replayAdmin,
          "ALTER TABLE tp_unready_replay_fixture RENAME TO tp_workload_replay_claims");
    }

    RegistrationDatabases.execute(
        db.replayAdmin, "UPDATE tp_workload_replay_state SET capacity=1 WHERE singleton=1");
    var server = open(identity, enrollment(), db.replay, db.operations, defaults(), 1);
    db.replay.reset();
    db.operations.reset();
    assertThat(RegistrationHttpTestClient.send(server.port(), token(), bytes(request())).status())
        .isEqualTo(201);
    assertThat(
            RegistrationHttpTestClient.send(
                    server.port(),
                    token(),
                    bytes(request().put("clientRequestKey", "capacity-overflow")))
                .body())
        .isEqualTo("{\"error\":\"AGENTCORE_REGISTRATION_REJECTED\"}");
    assertCounts(1, 1);
  }

  @ParameterizedTest
  @EnumSource(
      value = BoundedJdbcSource.Fault.class,
      names = {"FIRST_WRITE", "AFTER_COMMIT", "CLOSE_COMMIT"})
  void replayWriteCommitAndCloseFaultsNeverReachRegistration(BoundedJdbcSource.Fault fault)
      throws Exception {
    var server = open(identity(), enrollment(), db.replay, db.operations, defaults());
    db.replay.reset();
    db.operations.reset();
    db.replay.fault = fault;
    String authorization = token();
    var rejected =
        RegistrationHttpTestClient.send(server.port(), authorization, bytes(request()));
    assertThat(rejected.status()).isEqualTo(409);
    assertThat(rejected.body()).isEqualTo("{\"error\":\"AGENTCORE_REGISTRATION_REJECTED\"}");
    assertThat(db.operations.opens.get()).isZero();
    boolean committed = fault != BoundedJdbcSource.Fault.FIRST_WRITE;
    assertCounts(committed ? 1 : 0, 0);

    db.replay.fault = BoundedJdbcSource.Fault.NONE;
    if (committed) {
      assertThat(
              RegistrationHttpTestClient.send(
                      server.port(), authorization, bytes(request()))
                  .status())
          .isEqualTo(409);
      assertThat(db.operations.opens.get()).isZero();
    }
    assertThat(RegistrationHttpTestClient.send(server.port(), token(), bytes(request())).status())
        .isEqualTo(201);
    assertCounts(committed ? 2 : 1, 1);
  }

  @ParameterizedTest
  @EnumSource(
      value = BoundedJdbcSource.Fault.class,
      names = {"FIRST_WRITE", "AFTER_COMMIT", "CLOSE_COMMIT"})
  void registrationWriteCommitAndCloseFaultsKeepSpentIdentityAndExplicitRecovery(
      BoundedJdbcSource.Fault fault) throws Exception {
    var server = open(identity(), enrollment(), db.replay, db.operations, defaults());
    db.replay.reset();
    db.operations.reset();
    db.operations.fault = fault;
    String authorization = token();
    var unknown = RegistrationHttpTestClient.send(server.port(), authorization, bytes(request()));
    assertThat(unknown.status()).isEqualTo(503);
    boolean committed = fault != BoundedJdbcSource.Fault.FIRST_WRITE;
    assertThat(unknown.body())
        .isEqualTo(
            committed
                ? "{\"error\":\"AGENTCORE_REGISTRATION_UNKNOWN\"}"
                : "{\"error\":\"AGENTCORE_STORAGE_UNAVAILABLE\"}");
    assertCounts(1, committed ? 1 : 0);
    String originalHash = committed ? CanonicalJson.sha256(original()) : null;

    db.operations.fault = BoundedJdbcSource.Fault.NONE;
    int calls = db.operations.opens.get();
    assertThat(
            RegistrationHttpTestClient.send(server.port(), authorization, bytes(request())).status())
        .isEqualTo(409);
    assertThat(db.operations.opens.get()).isEqualTo(calls);
    var recovered = RegistrationHttpTestClient.send(server.port(), token(), bytes(request()));
    assertThat(recovered.status()).isEqualTo(committed ? 200 : 201);
    assertThat(JsonSupport.MAPPER.readTree(recovered.body()).path("executionEnabled").booleanValue())
        .isFalse();
    if (committed) assertThat(CanonicalJson.sha256(original())).isEqualTo(originalHash);
    assertCounts(2, 1);
  }

  @Test
  void transportTimeoutDoesNotProveRollbackOrFreeIgnoredInterruptionCapacity()
      throws Exception {
    var limits =
        new AgentCoreRegistrationHttpServer.Limits(
            1,
            1,
            Duration.ofMillis(250),
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofMillis(100));
    var server = open(identity(), enrollment(), db.replay, db.operations, limits);
    db.replay.reset();
    db.operations.reset();
    db.replay.writeEntered = new CountDownLatch(1);
    db.replay.releaseWrite = new CountDownLatch(1);
    db.replay.fault = BoundedJdbcSource.Fault.BLOCK_FIRST_WRITE;

    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> RegistrationHttpTestClient.send(server.port(), token(), bytes(request())));
      assertThat(db.replay.writeEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var second =
          executor.submit(
              () ->
                  RegistrationHttpTestClient.send(
                      server.port(),
                      token(),
                      bytes(request().put("clientRequestKey", "must-not-start-early"))));
      assertThat(first.get(5, TimeUnit.SECONDS).status()).isZero();
      assertThat(second.get(5, TimeUnit.SECONDS).status()).isZero();
      assertThat(db.replay.opens.get()).isEqualTo(1);
      assertThat(db.operations.opens.get()).isZero();
      assertThat(count(db.replayAdmin, "tp_workload_replay_claims")).isZero();

      db.replay.releaseWrite.countDown();
      await(() -> count(db.operationAdmin, "tp_business_operations") == 1);
      assertCounts(1, 1);
      assertThat(db.replay.commits.get()).isEqualTo(1);
    } finally {
      db.replay.releaseWrite.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    db.replay.fault = BoundedJdbcSource.Fault.NONE;
    var recovery =
        open(identity(), enrollment(), db.replay.fresh(), db.operations.fresh(), defaults());
    var recovered = RegistrationHttpTestClient.send(recovery.port(), token(), bytes(request()));
    assertThat(recovered.status()).isEqualTo(200);
    assertThat(JsonSupport.MAPPER.readTree(recovered.body()).path("executionEnabled").booleanValue())
        .isFalse();
    assertCounts(2, 1);
  }

  private int racedSend(
      int port, String authorization, CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("SYNTHETIC_START_TIMEOUT");
    return RegistrationHttpTestClient.send(port, authorization, bytes(request())).status();
  }

  private JwksFixture identity() {
    JwksFixture identity = jwksFixture();
    identities.add(identity);
    return identity;
  }

  private DurableAgentCoreRegistrationHttpServer open(
      JwksFixture identity,
      AgentCoreRegistrationIngress.Enrollment selectedEnrollment,
      BoundedJdbcSource replay,
      BoundedJdbcSource operations,
      AgentCoreRegistrationHttpServer.Limits limits) {
    return open(identity, selectedEnrollment, replay, operations, limits, 128);
  }

  private DurableAgentCoreRegistrationHttpServer open(
      JwksFixture identity,
      AgentCoreRegistrationIngress.Enrollment selectedEnrollment,
      BoundedJdbcSource replay,
      BoundedJdbcSource operations,
      AgentCoreRegistrationHttpServer.Limits limits,
      long capacity) {
    var server =
        DurableAgentCoreRegistrationHttpServer.create(
            identity.verifier(),
            selectedEnrollment,
            CLOCK,
            replay,
            operations,
            capacity,
            Duration.ofSeconds(2),
            0,
            limits);
    servers.add(server);
    return server;
  }

  private static AgentCoreRegistrationHttpServer.Limits defaults() {
    return AgentCoreRegistrationHttpServer.Limits.defaults();
  }

  private void assertCounts(long replay, long operations) {
    assertThat(count(db.replayAdmin, "tp_workload_replay_claims")).isEqualTo(replay);
    try (var connection = db.replayAdmin.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows =
          statement.executeQuery(
              "SELECT total_claims FROM tp_workload_replay_state WHERE singleton=1")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).isEqualTo(replay);
      }
    } catch (SQLException failure) {
      throw new AssertionError("SYNTHETIC_COUNTER_READ_FAILED");
    }
    assertThat(count(db.operationAdmin, "tp_business_operations")).isEqualTo(operations);
    assertThat(count(db.operationAdmin, "tp_business_operation_facts")).isEqualTo(operations * 3);
    assertThat(count(db.operationAdmin, "tp_operation_lifecycle_heads")).isEqualTo(operations);
    assertThat(count(db.operationAdmin, "tp_operation_lifecycle_facts")).isZero();
  }

  private JsonNode original() {
    try (var connection = db.operationAdmin.getConnection();
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT snapshot_json FROM tp_business_operations")) {
        assertThat(rows.next()).isTrue();
        return JsonSupport.MAPPER.readTree(rows.getString(1));
      }
    } catch (Exception failure) {
      throw new AssertionError("SYNTHETIC_SNAPSHOT_READ_FAILED");
    }
  }

  private Set<String> tableNames() {
    try (var connection = db.operationAdmin.getConnection();
        var rows =
            connection
                .getMetaData()
                .getTables(
                    connection.getCatalog(), db.postgres ? "public" : null, "%", new String[] {"TABLE", "VIEW"})) {
      Set<String> names = new TreeSet<>();
      while (rows.next()) names.add(rows.getString("TABLE_NAME"));
      return names;
    } catch (SQLException failure) {
      throw new AssertionError("SYNTHETIC_INVENTORY_FAILED");
    }
  }

  private static long count(BoundedJdbcSource source, String table) {
    return RegistrationDatabases.count(source, table);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertThat(condition.getAsBoolean()).isTrue();
  }

}
