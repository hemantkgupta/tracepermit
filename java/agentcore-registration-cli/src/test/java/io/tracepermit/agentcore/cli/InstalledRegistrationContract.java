package io.tracepermit.agentcore.cli;

import static io.tracepermit.agentcore.cli.SyntheticIdentity.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
abstract class InstalledRegistrationContract {
  private LocalDatabases db;
  private InstalledArchive installed;
  abstract String image();

  @BeforeAll void start() throws Exception { installed = new InstalledArchive(); db = new LocalDatabases(image()); }
  @AfterAll void stop() throws Exception { if (db != null) db.close(); if (installed != null) installed.close(); }
  @BeforeEach void reset() { db.reset(); }
  @AfterEach void noAuthorityOrUnexpectedTables() throws Exception {
    assertThat(LocalDatabases.count(db.operationAdmin, "tp_operation_lifecycle_facts")).isZero();
    try (var c = db.operationAdmin.getConnection(); var rows = c.getMetaData().getTables(c.getCatalog(),
        db.postgres ? "public" : null, "%", new String[]{"TABLE", "VIEW"})) {
      var names = new java.util.TreeSet<String>(); while (rows.next()) names.add(rows.getString("TABLE_NAME"));
      assertThat(names).containsExactlyInAnyOrder("tp_operation_schema_history", "tp_business_operations",
          "tp_business_operation_facts", "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts");
    }
    try (var c = db.operationAdmin.getConnection(); var s = c.createStatement()) {
      s.setQueryTimeout(5);
      try (var rows = s.executeQuery(db.postgres
          ? "SELECT COUNT(*) FROM pg_stat_activity WHERE usename='tp_cli_runtime'"
          : "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER='tp_cli_runtime'")) {
        assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).as("runtime connections closed").isZero();
      }
    }
  }

  private Receipt call(String token, ObjectNode body) throws Exception {
    return installed.call("register-once", input(db.config(false), token, body));
  }
  private void counts(long replay, long operations) {
    assertThat(LocalDatabases.count(db.replayAdmin, "tp_workload_replay_claims")).isEqualTo(replay);
    assertThat(LocalDatabases.count(db.operationAdmin, "tp_business_operations")).isEqualTo(operations);
    assertThat(LocalDatabases.count(db.operationAdmin, "tp_business_operation_facts")).isEqualTo(operations * 3);
    assertThat(LocalDatabases.count(db.operationAdmin, "tp_operation_lifecycle_heads")).isEqualTo(operations);
  }

  @Test void actualInstalledRestartReplayConflictAndOriginalReturn() throws Exception {
    String firstToken = token();
    Receipt first = call(firstToken, request()); assertThat(first.outcome()).isEqualTo("CREATED"); counts(1, 1);
    assertThat(call(firstToken, request()).exitCode()).isNotZero(); counts(1, 1);
    Receipt retry = call(token(), request()); assertThat(retry.outcome()).isEqualTo("EXISTING");
    assertThat(retry.operationId()).isEqualTo(first.operationId()); counts(2, 1);
    String conflict = token();
    assertThat(call(conflict, request().put("title", "Changed content")).outcome()).isEqualTo("REJECTED"); counts(3, 1);
    assertThat(call(conflict, request().put("clientRequestKey", "changed-key")).exitCode()).isNotZero(); counts(3, 1);
    // Also exercise the exact same real factory path in this JVM for coverage; a new token returns original.
    Receipt local = Worker.execute(new java.io.ByteArrayInputStream(input(db.config(false), token(), request())), "register-once");
    assertThat(local.outcome()).isEqualTo("EXISTING"); assertThat(local.operationId()).isEqualTo(first.operationId()); counts(4, 1);
  }

  @Test void thirtyTwoInstalledProcessesOneTokenOneWinner() throws Exception { race(false); counts(1, 1); }
  @Test void thirtyTwoInstalledProcessesDistinctTokensOneKey() throws Exception { race(true); counts(32, 1); }

  private void race(boolean distinct) throws Exception {
    String shared = token(); var tokens = new ArrayList<String>();
    for (int i = 0; i < 32; i++) tokens.add(distinct ? token() : shared);
    var pool = Executors.newFixedThreadPool(32); var ready = new CountDownLatch(32); var go = new CountDownLatch(1);
    List<java.util.concurrent.Future<Receipt>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 32; i++) { int index = i; futures.add(pool.submit(() -> {
        ready.countDown(); if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("RACE_NOT_RELEASED");
        return call(tokens.get(index), request());
      })); }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
      var receipts = new ArrayList<Receipt>();
      for (var future : futures) receipts.add(future.get(80, TimeUnit.SECONDS));
      assertThat(receipts.stream().filter(r -> r.outcome().equals("CREATED")).count()).isEqualTo(1);
      var outcomes = receipts.stream().collect(java.util.stream.Collectors.groupingBy(Receipt::outcome, java.util.stream.Collectors.counting()));
      assertThat(receipts.stream().filter(r -> r.outcome().equals("EXISTING")).count()).as("fixed outcomes %s", outcomes).isEqualTo(distinct ? 31 : 0);
      if (distinct) assertThat(receipts.stream().map(Receipt::operationId).distinct().count()).isEqualTo(1);
      else assertThat(receipts.stream().filter(r -> r.exitCode() != 0).count()).isEqualTo(31);
    } finally { go.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
  }

  @ParameterizedTest @ValueSource(strings = {"signature", "issuer", "audience", "scope", "subject", "client", "tenant", "expired"})
  void realSignatureAndEnrollmentNegatives(String kind) throws Exception {
    String authorization = token(c -> {
      switch (kind) {
        case "issuer" -> c.put("iss", "https://other.example.invalid");
        case "audience" -> c.put("aud", "https://other.example.invalid");
        case "scope" -> c.put("scope", "operations.other");
        case "subject" -> c.put("sub", "workload:other");
        case "client" -> c.put("client_id", "other-client");
        case "tenant" -> c.put("org_id", "other-org");
        case "expired" -> c.put("iat", Instant.now().minusSeconds(200).getEpochSecond())
            .put("exp", Instant.now().minusSeconds(30).getEpochSecond());
        default -> { }
      }
    });
    if (kind.equals("signature")) {
      int start = authorization.lastIndexOf('.') + 1;
      authorization = authorization.substring(0, start) + (authorization.charAt(start) == 'A' ? 'B' : 'A') + authorization.substring(start + 1);
    }
    assertThat(call(authorization, request()).outcome()).isEqualTo("REJECTED"); counts(0, 0);
  }

  @ParameterizedTest @ValueSource(strings = {"authenticated", "delegated", "verified", "humanAuthorization", "tenantId", "target"})
  void bodyCannotSupplyAuthorityOrHumanPair(String field) throws Exception {
    assertThat(call(token(), request().put(field, "forbidden")).outcome()).isEqualTo("INPUT_INVALID"); counts(0, 0);
  }

  @Test void humanSecondTokenFrameRejectedBeforeEffects() throws Exception {
    byte[] bytes = frames(bytes(db.config(false)), token().getBytes(StandardCharsets.UTF_8), bytes(request()), token().getBytes(StandardCharsets.UTF_8));
    assertThat(installed.call("register-once", bytes).outcome()).isEqualTo("INPUT_INVALID"); counts(0, 0);
  }

  @Test void capacityAgreementAndSaturationHaveNoFallback() throws Exception {
    var mismatch = db.config(false).put("replayCapacity", 129);
    assertThat(installed.call("register-once", input(mismatch, token(), request())).outcome()).isEqualTo("NOT_READY"); counts(0, 0);
    LocalDatabases.execute(db.replayAdmin, "UPDATE tp_workload_replay_state SET capacity=1 WHERE singleton=1");
    var config = db.config(false).put("replayCapacity", 1);
    assertThat(installed.call("register-once", input(config, token(), request())).outcome()).isEqualTo("CREATED");
    assertThat(installed.call("register-once", input(config, token(), request().put("clientRequestKey", "overflow"))).exitCode()).isNotZero(); counts(1, 1);
  }

  @ParameterizedTest @ValueSource(strings = {"replay", "operations"})
  void realLeastPrivilegeDenialAndValidAdminPositive(String store) throws Exception {
    boolean replay = store.equals("replay");
    db.grant(replay ? db.replayAdmin : db.operationAdmin, "INSERT",
        replay ? "tp_workload_replay_claims" : "tp_business_operation_facts", false);
    String authorization = token(); assertThat(call(authorization, request()).exitCode()).isNotZero(); counts(replay ? 0 : 1, 0);
    var admin = db.config(true);
    assertThat(installed.call("register-once", input(admin, token(), request())).outcome()).isEqualTo("CREATED"); counts(replay ? 1 : 2, 1);
    if (!replay) assertThat(installed.call("register-once", input(admin, authorization, request())).exitCode()).isNotZero();
  }

  @ParameterizedTest @ValueSource(strings = {"replay", "operations"})
  void unreadyExistingSchemaNotRepaired(String field) throws Exception {
    var config = db.config(true); ((ObjectNode)config.get(field)).put("catalog", "tp_empty");
    assertThat(installed.call("register-once", input(config, token(), request())).outcome()).isEqualTo("NOT_READY"); counts(0, 0);
    try (var c = db.source("tp_empty", db.owner, db.adminSecret).getConnection();
         var tables = c.getMetaData().getTables(c.getCatalog(), db.postgres ? "public" : null, "%", new String[]{"TABLE"})) {
      assertThat(tables.next()).isFalse();
    }
    assertThat(call(token(), request()).outcome()).isEqualTo("CREATED"); counts(1, 1);
  }

  @Test void offlineCheckConfigHasNoDurableWrites() throws Exception {
    assertThat(installed.call("check-config", frames(bytes(db.config(false)))).outcome()).isEqualTo("CONFIG_VALID"); counts(0, 0);
  }

  @Test void concreteProducerOriginalReadDiagnostic() throws Exception {
    var config = OperatorConfig.parse(db.config(false)); var clock = java.time.Clock.systemUTC();
    var factory = io.tracepermit.agentcore.registration.DurableAgentCoreRegistration.create(
        config.verifier(clock), config.enrollment, clock, db.replay, db.operations, 128, java.time.Duration.ofSeconds(2));
    var first = factory.register(token(), null, bytes(request())); counts(1, 1);
    // Surface only fixed producer codes. No driver message, token, request or snapshot is emitted.
    try {
      var repository = new io.tracepermit.persistence.operations.JdbcBusinessOperationRepository(db.operations);
      assertThat(repository.find(config.enrollment.scope(), first.operationId())).isPresent();
      assertThat(factory.register(token(), null, bytes(request())).operationId()).isEqualTo(first.operationId());
    } catch (io.tracepermit.application.BusinessOperationStorageException failure) {
      throw new AssertionError("FIXED_PRODUCER_READ_CODE=" + failure.code());
    } catch (io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress.IngressException failure) {
      throw new AssertionError("FIXED_PRODUCER_INGRESS_CODE=" + failure.code());
    }
  }

  @Test void installedWorkerDiagnosticChannelProbe() throws Exception {
    for (int i = 0; i < 2; i++) {
      Process process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", installed.home.resolve("lib/*").toString(), Worker.class.getName(), "register-once")
          .directory(installed.root.toFile()).start();
      var output = new java.io.ByteArrayOutputStream(); var errors = new java.io.ByteArrayOutputStream();
      byte[] frame = input(db.config(false), token(), request());
      Thread writer = InstalledArchive.thread(() -> { try { process.getOutputStream().write(frame); process.getOutputStream().close(); } catch (Exception ignored) { } });
      Thread out = InstalledArchive.thread(() -> { try { process.getInputStream().transferTo(output); } catch (Exception ignored) { } });
      Thread err = InstalledArchive.thread(() -> { try { process.getErrorStream().transferTo(errors); } catch (Exception ignored) { } });
      try {
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue(); writer.join(1000); out.join(1000); err.join(1000);
        assertThat(Receipt.decode(output.toByteArray(), process.exitValue()).outcome()).isEqualTo(i == 0 ? "CREATED" : "EXISTING");
        assertThat(errors.size()).as("worker suppresses all driver diagnostics").isZero();
      } finally { process.toHandle().destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
    }
    counts(2, 1);
  }

  @ParameterizedTest @ValueSource(strings = {"replay", "operations", "worker-death", "readback-denied"})
  void actualJdbcCommitAcknowledgementLossIsUnknownAndNeverRetries(String mode) throws Exception {
    boolean replay = mode.equals("replay");
    String authorization = token(); var config = db.config(false);
    try (var gate = new CommitGate(db.port, replay)) {
      ((ObjectNode)config.get(replay ? "replay" : "operations")).put("port", gate.port());
      var pool = Executors.newSingleThreadExecutor();
      try {
        var future = pool.submit(() -> installed.call("register-once", input(config, authorization, request())));
        assertThat(gate.held.await(15, TimeUnit.SECONDS)).as("real COMMIT reply held").isTrue();
        // The observer uses a separate administrative connection, not the affected JDBC connection.
        counts(1, replay ? 0 : 1);
        if (mode.equals("worker-death")) {
          List<ProcessHandle> workers = ProcessHandle.current().descendants()
              .filter(p -> p.info().arguments().map(a -> java.util.Arrays.asList(a).contains(Worker.class.getName())).orElse(false)).toList();
          assertThat(workers).hasSize(1); assertThat(workers.get(0).destroyForcibly()).isTrue();
        }
        if (mode.equals("readback-denied")) db.grant(db.operationAdmin, "SELECT", "tp_business_operations", false);
        gate.loseAcknowledgement();
        Receipt lost = future.get(30, TimeUnit.SECONDS);
        assertThat(lost.outcome()).isEqualTo("UNKNOWN"); assertThat(lost.exitCode()).isNotZero(); assertThat(lost.operationId()).isNull();
        counts(1, replay ? 0 : 1);
      } finally {
        db.grant(db.operationAdmin, "SELECT", "tp_business_operations", true);
        gate.loseAcknowledgement(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
    assertThat(call(authorization, request().put("clientRequestKey", "cannot-bypass")).exitCode()).isNotZero();
    counts(1, replay ? 0 : 1);
    Receipt explicit = call(token(), request());
    assertThat(explicit.outcome()).isEqualTo(replay ? "CREATED" : "EXISTING"); counts(2, 1);
  }

  @Test void workerDeathBeforeInputHasNoEffects() throws Exception {
    Process process = installed.start("register-once");
    try {
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
      List<ProcessHandle> children = List.of();
      while (children.isEmpty() && System.nanoTime() < deadline) {
        children = process.descendants().filter(p -> p.info().arguments()
            .map(a -> java.util.Arrays.asList(a).contains(Worker.class.getName())).orElse(false)).toList();
        if (children.isEmpty()) Thread.sleep(10);
      }
      assertThat(children).hasSize(1); assertThat(children.get(0).destroyForcibly()).isTrue();
      assertThat(process.waitFor(8, TimeUnit.SECONDS)).isTrue();
      byte[] output = process.getInputStream().readNBytes(1024);
      assertThat(Receipt.decode(output, process.exitValue()).outcome()).isEqualTo("UNKNOWN"); counts(0, 0);
    } finally { process.toHandle().destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
  }

  @Test void malformedExistingSchemaRemainsUnrepaired() throws Exception {
    LocalDatabases.execute(db.operationAdmin, "ALTER TABLE tp_business_operation_facts RENAME TO tp_unready_fixture");
    try {
      assertThat(call(token(), request()).outcome()).isEqualTo("NOT_READY");
      assertThat(LocalDatabases.count(db.replayAdmin, "tp_workload_replay_claims")).isZero();
      assertThat(LocalDatabases.count(db.operationAdmin, "tp_business_operations")).isZero();
      assertThat(LocalDatabases.count(db.operationAdmin, "tp_unready_fixture")).isZero();
    } finally { LocalDatabases.execute(db.operationAdmin, "ALTER TABLE tp_unready_fixture RENAME TO tp_business_operation_facts"); }
    assertThat(call(token(), request()).outcome()).isEqualTo("CREATED"); counts(1, 1);
  }

  @Test void wrongCredentialUsesFixedCanarySafeReceipt() throws Exception {
    var config = db.config(false); ((ObjectNode)config.get("replay")).put("password", "SYNTHETIC_SECRET_CANARY");
    assertThat(installed.call("register-once", input(config, token(), request().put("body", "SYNTHETIC_BODY_CANARY"))).outcome()).isEqualTo("NOT_READY");
    counts(0, 0);
  }
}
