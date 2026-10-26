package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.*;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** Synthetic owned fixtures. No endpoint, target, sender, raw permit evidence or production input. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
abstract class OperationAdmissionContract {
  private static final String HASH = "sha256:" + "a".repeat(64);
  private static final BusinessOperationScope SCOPE = new BusinessOperationScope("synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private static final BusinessOperationTransitionPlanner PLANNER = new BusinessOperationTransitionPlanner();
  private JdbcDatabaseContainer<?> database;
  private Source owner;
  private Source runtime;
  private KeyPair keys;
  private BusinessOperationAuthorityProfile profile;
  private GitHubBusinessOperationBinding.ExpectedProfile target;
  private JsonNode initial;
  abstract JdbcDatabaseContainer<?> container();

  @BeforeAll void start() throws Exception {
    database = container().withPassword(UUID.randomUUID().toString());
    database.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(database.getExposedPorts().stream()
        .map(port -> new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(port))).toArray(PortBinding[]::new)));
    database.start();
    assertThat(database.getContainerInfo().getNetworkSettings().getPorts().getBindings().values()).allSatisfy(values ->
        assertThat(values).hasSize(1).allSatisfy(value -> assertThat(value.getHostIp()).isEqualTo("127.0.0.1")));
    owner = new Source(database.getUsername(), database.getPassword());
    OperationDatabase.initializeAdmissionEmpty(owner);
    String password = UUID.randomUUID().toString();
    var administrator = pg() ? owner : new Source("root", database.getPassword());
    try (var connection = administrator.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      String user = pg() ? "tp_admission_runtime" : "'tp_admission_runtime'@'%'";
      statement.execute(pg() ? "CREATE USER " + user + " PASSWORD '" + password + "'"
          : "CREATE USER " + user + " IDENTIFIED BY '" + password + "'");
      if (pg()) statement.execute("GRANT USAGE ON SCHEMA public TO " + user);
      String prefix = pg() ? "" : "`" + database.getDatabaseName() + "`.";
      for (String table : tables()) statement.execute("GRANT SELECT ON " + prefix + table + " TO " + user);
      for (String table : List.of("tp_operation_lifecycle_facts", "tp_operation_review_bindings", "tp_operation_admissions", "tp_operation_admission_outbox")) {
        statement.execute("GRANT INSERT ON " + prefix + table + " TO " + user);
      }
      statement.execute("GRANT UPDATE(snapshot_hash, snapshot_json) ON " + prefix + "tp_operation_lifecycle_heads TO " + user);
      if (pg()) statement.execute("GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO " + user);
    }
    runtime = new Source("tp_admission_runtime", password);
  }
  @AfterAll void stop() { if (database != null) database.stop(); }

  @BeforeEach void reset() throws Exception {
    owner.fault = "none"; runtime.fault = "none";
    for (String table : List.of("tp_operation_admission_outbox", "tp_operation_admissions", "tp_operation_review_bindings",
        "tp_operation_lifecycle_facts", "tp_operation_lifecycle_heads", "tp_business_operation_facts", "tp_business_operations", "tp_operation_authority_controls")) sql("DELETE FROM " + table);
    keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    ObjectNode policy = (ObjectNode) JsonSupport.MAPPER.readTree("""
        {"schemaVersion":"1.0","id":"synthetic-github-draft-pr","version":1,"name":"Synthetic",
         "description":"No target authority","enabled":true,"defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
         "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic","match":{"toolNames":["github.pull_request.create"]},
         "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW","approvalGroup":"synthetic-reviewers"}}]}
        """);
    target = new GitHubBusinessOperationBinding.ExpectedProfile("github-draft-pr-v1", "test", 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", 1, CanonicalJson.sha256(policy)),
        Map.of("policy_bundle", HASH, "adapter_release", HASH),
        new GitHubPilotTargetBinding(71, 101, 201, "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40)));
    initial = operation();
    profile = new BusinessOperationAuthorityProfile(SCOPE, target.adapterId(), 1, policy,
        CanonicalJson.sha256(initial.path("artifactDigests")), initial.path("resourceBindingHash").asText(),
        new BusinessOperationReviewSettings("synthetic-reviewers", HASH, Duration.ofMinutes(2)),
        new BusinessOperationAdmissionSettings("https://synthetic-issuer.invalid", "synthetic-key", "synthetic-admission", keys.getPublic(), Duration.ofSeconds(60)));
    OperationAuthorityAdministration.stageNew(owner, profile); OperationAuthorityAdministration.activateStaged(owner, profile);
    new JdbcBusinessOperationRepository(owner).createOrGet(SCOPE, initial);
  }

  @Test void admissionAtomicallyPersistsOneInitialAttemptFactHeadAndAnchoredOutbox() throws Exception {
    JsonNode approved = approved();
    var permit = issuer().issue(execution(approved, fresh()));
    JsonNode result = admission().admit(execution(approved, fresh()), permit.serialized());
    assertThat(result.path("execution").path("status").asText()).isEqualTo("claimed");
    assertThat(CanonicalJson.canonicalize(result.path("authorization"))).isEqualTo(CanonicalJson.canonicalize(approved.path("authorization")));
    assertThat(CanonicalJson.canonicalize(result.path("evidence"))).isEqualTo(CanonicalJson.canonicalize(approved.path("evidence")));
    assertThat(count("tp_operation_admissions")).isEqualTo(1); assertThat(count("tp_operation_admission_outbox")).isEqualTo(1);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(3);
    assertCurrent(result);
    try (var connection = owner.getConnection(); var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT admission_json FROM tp_operation_admissions")) {
      assertThat(rows.next()).isTrue(); assertThat(rows.getString(1).contains(permit.serialized())).isFalse();
    }
    assertCode(() -> admission().admit(execution(approved, fresh()), permit.serialized()), OPERATION_STATE_STALE);
    assertThat(count("tp_operation_admissions")).isEqualTo(1);
  }

  @Test void separatelyIssuedPermitsStillAllocateOnlyOneAttemptAcrossThirtyTwoContenders() throws Exception {
    JsonNode approved = approved(); var repository = admission();
    List<String> permits = new ArrayList<>(); var issuer = issuer();
    for (int index = 0; index < 4; index++) permits.add(issuer.issue(execution(approved, fresh())).serialized());
    var executor = Executors.newFixedThreadPool(32); var start = new CountDownLatch(1);
    try {
      List<Future<Boolean>> contenders = new ArrayList<>();
      for (int index = 0; index < 32; index++) {
        String permit = permits.get(index % permits.size());
        contenders.add(executor.submit(() -> {
          if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("synthetic start timeout");
          try { repository.admit(execution(approved, fresh()), permit); return true; }
          catch (BusinessOperationStorageException fixed) { assertThat(fixed.code()).isEqualTo(OPERATION_STATE_STALE); return false; }
        }));
      }
      start.countDown(); int winners = 0;
      for (var future : contenders) if (future.get(60, TimeUnit.SECONDS)) winners++;
      assertThat(winners).isEqualTo(1);
    } finally { start.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    assertThat(count("tp_operation_admissions")).isEqualTo(1); assertThat(count("tp_operation_admission_outbox")).isEqualTo(1);
    assertThat(current().path("execution").path("status").asText()).isEqualTo("claimed");
  }

  @Test void malformedSignatureIsRefusedBeforeAnyAdmissionDmlAndStaleAuthorityIsNotPromoted() throws Exception {
    JsonNode approved = approved(); var permit = issuer().issue(execution(approved, fresh()));
    String token = permit.serialized();
    assertCode(() -> admission().admit(execution(approved, fresh()), token + "x"), OPERATION_PERMIT_INVALID);
    OperationAuthorityAdministration.quarantine(owner, profile);
    assertCode(() -> admission().admit(execution(approved, fresh()), token), OPERATION_AUTHORITY_NOT_READY);
    assertCurrent(approved); assertThat(count("tp_operation_admissions")).isZero();
  }

  @Test void everyKnownRollbackPhaseLeavesNoPartialAdmissionAndNoRawCause() throws Exception {
    JsonNode approved = approved(); String permit = issuer().issue(execution(approved, fresh())).serialized();
    var repository = admission();
    for (String fault : List.of("admission", "fact", "head", "outbox")) {
      runtime.fault = fault;
      try { assertCode(() -> repository.admit(execution(approved, fresh()), permit), OPERATION_STORAGE_UNAVAILABLE); }
      finally { runtime.fault = "none"; }
      assertCurrent(approved); assertThat(count("tp_operation_admissions")).isZero(); assertThat(count("tp_operation_admission_outbox")).isZero();
    }
  }

  @Test void acknowledgedCommitWithLostCommitReplyOrCleanupNeverReturnsSuccess() throws Exception {
    JsonNode approved = approved(); var repository = admission(); String permit = issuer().issue(execution(approved, fresh())).serialized();
    runtime.fault = "after-commit";
    try { assertCode(() -> repository.admit(execution(approved, fresh()), permit), OPERATION_COMMIT_UNKNOWN); }
    finally { runtime.fault = "none"; }
    assertThat(current().path("execution").path("status").asText()).isEqualTo("claimed");
    assertThat(count("tp_operation_admissions")).isEqualTo(1); assertThat(count("tp_operation_admission_outbox")).isEqualTo(1);
    assertCode(() -> repository.admit(execution(approved, fresh()), permit), OPERATION_STATE_STALE);
  }

  @Test void missingOutboxIsRejectedByClosedCurrentReader() throws Exception {
    JsonNode approved = approved(); var permit = issuer().issue(execution(approved, fresh()));
    JsonNode result = admission().admit(execution(approved, fresh()), permit.serialized()); assertCurrent(result);
    sql("DELETE FROM tp_operation_admission_outbox");
    assertCode(this::current, OPERATION_STORAGE_CORRUPT);
  }

  @Test void signedButSubstitutedAuthorityBindingsNeverBecomeAdmission() throws Exception {
    JsonNode approved = approved(); var codec = new OperationPermitCodec(profile.admissionSettings(), keys.getPrivate());
    JsonNode original = codec.verify(issuer().issue(execution(approved, fresh())).serialized()).claims();
    var repository = admission();
    for (String field : List.of("profileHash", "targetProfileHash", "targetBindingDigest", "canonicalActionHash", "semanticPayloadDigest",
        "policyReleaseHash", "artifactDigestsHash", "resourceBindingHash", "preconditionBindingHash", "targetIdempotencyKeyHash",
        "reviewBindingHash", "evaluationFactHash", "evaluationActorHash", "approvalFactHash", "approvalActorHash")) {
      ObjectNode changed = original.deepCopy(); changed.put(field, "sha256:" + "c".repeat(64));
      String token = codec.sign(changed);
      assertCode(() -> repository.admit(execution(approved, fresh()), token), OPERATION_PERMIT_INVALID);
    }
    assertCurrent(approved); assertThat(count("tp_operation_admissions")).isZero(); assertThat(count("tp_operation_admission_outbox")).isZero();
  }

  @Test void signedFutureOrExpiredPermitAndExpiredFreshOwnerAreRefused() throws Exception {
    JsonNode approved = approved(); var codec = new OperationPermitCodec(profile.admissionSettings(), keys.getPrivate());
    String original = issuer().issue(execution(approved, fresh())).serialized(); JsonNode claims = codec.verify(original).claims();
    Instant now = storeNow(); ObjectNode expired = claims.deepCopy();
    expired.put("issuedAt", now.minusSeconds(20).toString()).put("expiresAt", now.minusSeconds(1).toString());
    String expiredToken = codec.sign(expired); var repository = admission();
    assertCode(() -> repository.admit(execution(approved, fresh()), expiredToken), OPERATION_PERMIT_EXPIRED);
    ObjectNode future = claims.deepCopy(); future.put("issuedAt", now.plusSeconds(10).toString()).put("expiresAt", now.plusSeconds(30).toString());
    String futureToken = codec.sign(future);
    assertCode(() -> repository.admit(execution(approved, fresh()), futureToken), OPERATION_CLOCK_REGRESSION);
    assertCode(() -> repository.admit(execution(approved, Instant.EPOCH), original), OPERATION_AUTHENTICATION_EXPIRED);
    assertCurrent(approved); assertThat(count("tp_operation_admissions")).isZero();
  }

  @Test void freshDifferentOwnerCredentialIsAllowedButCleanupUncertaintyNeverAcknowledgesAdmission() throws Exception {
    JsonNode approved = approved(); var repository = admission(); String permit = issuer().issue(execution(approved, fresh())).serialized();
    var refreshed = new TrustedRequesterContext(new TenantPartition(SCOPE.tenantId()), SCOPE.requesterPrincipalId(), true,
        Set.of("operation_requester"), Set.of("operations.execute"), "synthetic-test", "sha256:" + "b".repeat(64), fresh());
    var command = new BusinessOperationExecution(SCOPE, id(), PLANNER.observe(SCOPE, approved), refreshed, SCOPE.workspaceId());
    runtime.fault = "close-after-commit";
    try { assertCode(() -> repository.admit(command, permit), OPERATION_COMMIT_UNKNOWN); }
    finally { runtime.fault = "none"; }
    assertThat(current().path("execution").path("status").asText()).isEqualTo("claimed");
    assertThat(count("tp_operation_admissions")).isEqualTo(1); assertThat(count("tp_operation_admission_outbox")).isEqualTo(1);
  }

  @Test void runtimeCannotRewriteOrDeleteImmutableAdmissionOrOutboxWithValidAdminPositiveControls() throws Exception {
    JsonNode approved = approved(); var result = admission().admit(execution(approved, fresh()), issuer().issue(execution(approved, fresh())).serialized());
    for (String table : List.of("tp_operation_admissions", "tp_operation_admission_outbox")) {
      for (String sql : List.of("UPDATE " + table + " SET operation_id = operation_id WHERE operation_id = ?", "DELETE FROM " + table + " WHERE operation_id = ?")) {
        try (var positive = owner.getConnection()) {
          positive.setAutoCommit(false);
          // Delete the owned dependent inside this rolled-back administrative control;
          // otherwise the legitimate FK, rather than privileges, would reject parent DELETE.
          if (table.equals("tp_operation_admissions") && sql.startsWith("DELETE")) {
            try (var dependent = positive.prepareStatement("DELETE FROM tp_operation_admission_outbox WHERE operation_id = ?")) {
              dependent.setQueryTimeout(5); dependent.setString(1, id()); assertThat(dependent.executeUpdate()).isEqualTo(1);
            }
          }
          try (var statement = positive.prepareStatement(sql)) { statement.setQueryTimeout(5); statement.setString(1, id()); assertThat(statement.executeUpdate()).isEqualTo(1); }
          finally { positive.rollback(); }
        }
        try (var denied = runtime.getConnection(); var statement = denied.prepareStatement(sql)) {
          statement.setQueryTimeout(5); statement.setString(1, id());
          assertThatThrownBy(statement::executeUpdate).isInstanceOfSatisfying(SQLException.class, failure -> {
            assertThat(failure.getSQLState()).isEqualTo(pg() ? "42501" : "42000");
            if (!pg()) assertThat(failure.getErrorCode()).isEqualTo(1142);
          });
        }
      }
    }
    assertCurrent(result);
  }

  @Test void successfulInsertAfterRealUniqueHolderRollbackPastSignedExpiryCannotAdmit() throws Exception {
    JsonNode approved = approved(); var repository = admission(); var issuer = issuer();
    ObjectNode other = initial.deepCopy(); String otherId = UUID.randomUUID().toString();
    other.put("operationId", otherId).put("clientRequestKeyHash", "sha256:" + "b".repeat(64))
        .put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(otherId, target.adapterId(), 1));
    new JdbcBusinessOperationRepository(owner).createOrGet(SCOPE, BusinessOperationValidator.validate(other));
    Instant expiry = storeNow().plusSeconds(2);
    var permit = issuer.issue(execution(approved, expiry));
    JsonNode claims = new OperationPermitCodec(profile.admissionSettings(), null).verify(permit.serialized()).claims();
    var executor = Executors.newSingleThreadExecutor(); runtime.insertionAttempt = new CountDownLatch(1);
    try (var holder = owner.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement = holder.prepareStatement("INSERT INTO tp_operation_admissions (operation_id, permit_id, attempt_id, admission_hash, admission_json) VALUES (?, ?, ?, ?, ?)")) {
        statement.setQueryTimeout(5); statement.setString(1, otherId); statement.setString(2, claims.path("permitId").asText());
        statement.setString(3, UUID.randomUUID().toString()); statement.setString(4, HASH); statement.setString(5, "{}");
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      var future = executor.submit(() -> repository.admit(execution(approved, fresh()), permit.serialized()));
      assertThat(runtime.insertionAttempt.await(3, TimeUnit.SECONDS)).isTrue();
      awaitDatabaseWait(runtime.insertionPid.get(), expiry);
      long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      while (!storeNow().isAfter(expiry)) { assertThat(System.nanoTime()).isLessThan(bound); Thread.sleep(10); }
      assertThat(future.isDone()).isFalse(); holder.rollback();
      try { future.get(10, TimeUnit.SECONDS); fail("expired admission must refuse"); }
      catch (ExecutionException rejected) {
        assertThat(rejected.getCause()).isInstanceOfSatisfying(BusinessOperationStorageException.class,
            fixed -> assertThat(fixed.code()).isEqualTo(OPERATION_PERMIT_EXPIRED));
      }
    } finally {
      runtime.insertionAttempt = null; executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertCurrent(approved); assertThat(count("tp_operation_admissions")).isZero(); assertThat(count("tp_operation_admission_outbox")).isZero();
  }

  private Instant storeNow() throws SQLException {
    try (var connection = owner.getConnection()) { return JdbcBusinessOperationLifecycleRepository.storeTime(connection, OperationDatabase.inspectWriter(connection)); }
  }

  @Test void populatedV4UpgradeChecksClosedHistoryBeforeEitherDdlAndPreservesApprovedBytes() throws Exception {
    var extra = container().withPassword(UUID.randomUUID().toString());
    extra.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(extra.getExposedPorts().stream()
        .map(port -> new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(port))).toArray(PortBinding[]::new)));
    try (extra) {
      extra.start(); var administrator = new Source(extra, extra.getUsername(), extra.getPassword());
      OperationDatabase.initializeReviewEmpty(administrator);
      OperationAuthorityAdministration.stageNew(administrator, profile); OperationAuthorityAdministration.activateStaged(administrator, profile);
      new JdbcBusinessOperationRepository(administrator).createOrGet(SCOPE, initial);
      JsonNode approved = approved(administrator); String originalActorHash;
      try (var connection = administrator.getConnection(); var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        try (var rows = statement.executeQuery("SELECT actor_hash FROM tp_operation_lifecycle_facts WHERE sequence_number = 5")) {
          assertThat(rows.next()).isTrue(); originalActorHash = rows.getString(1);
        }
        assertThat(statement.executeUpdate("UPDATE tp_operation_lifecycle_facts SET actor_hash = '" + HASH + "' WHERE sequence_number = 5")).isEqualTo(1);
      }
      assertCode(() -> OperationDatabase.upgradeAdmissionFromV4(administrator), OPERATION_SCHEMA_NOT_READY);
      try (var connection = administrator.getConnection(); var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_operation_schema_history WHERE version IN ('5', '6')")) {
          assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
        }
        try (var names = connection.getMetaData().getTables(connection.getCatalog(), pg() ? "public" : null, "tp_operation_admissions", new String[] {"TABLE"})) {
          assertThat(names.next()).isFalse();
        }
        assertThat(statement.executeUpdate("UPDATE tp_operation_lifecycle_facts SET actor_hash = '" + originalActorHash + "' WHERE sequence_number = 5")).isEqualTo(1);
      }
      OperationDatabase.upgradeAdmissionFromV4(administrator);
      JsonNode restored = new JdbcBusinessOperationLifecycleRepository(administrator).findCurrent(SCOPE, id()).orElseThrow();
      assertThat(CanonicalJson.canonicalize(restored)).isEqualTo(CanonicalJson.canonicalize(approved));
      assertThat(CanonicalJson.canonicalize(new JdbcBusinessOperationRepository(administrator).createOrGet(SCOPE, initial).operation()))
          .isEqualTo(CanonicalJson.canonicalize(initial));
    }
  }

  @Test void partialV5InstallationIsNeverAcceptedOrAutomaticallyRepaired() throws Exception {
    var extra = container().withPassword(UUID.randomUUID().toString());
    extra.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(extra.getExposedPorts().stream()
        .map(port -> new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(port))).toArray(PortBinding[]::new)));
    try (extra) {
      extra.start(); var administrator = new Source(extra, extra.getUsername(), extra.getPassword());
      OperationDatabase.initializeReviewEmpty(administrator);
      org.flywaydb.core.Flyway.configure().dataSource(administrator).table("tp_operation_schema_history").target("5")
          .locations("classpath:db/operations/" + (pg() ? "postgresql" : "mariadb"))
          .createSchemas(false).cleanDisabled(true).loggers().load().migrate();
      assertCode(() -> new JdbcGitHubOperationAdmissionRepository(administrator, profile, target), OPERATION_SCHEMA_NOT_READY);
      assertCode(() -> new JdbcGitHubOperationReviewRepository(administrator, profile, target), OPERATION_SCHEMA_NOT_READY);
      assertCode(() -> new JdbcBusinessOperationLifecycleRepository(administrator), OPERATION_SCHEMA_NOT_READY);
      try (var connection = administrator.getConnection(); var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_operation_schema_history WHERE version = '6'")) {
          assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
        }
      }
    }
  }

  @Test void retainedAdmissionOwnerRefusesUnknownFailedOrRepeatableLatestProtocolBeforeWrites() throws Exception {
    JsonNode approved = approved(); var repository = admission(); String token = issuer().issue(execution(approved, fresh())).serialized();
    for (int kind = 0; kind < 3; kind++) {
      try (var connection = owner.getConnection(); var statement = connection.prepareStatement("""
          INSERT INTO tp_operation_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
          SELECT installed_rank + 100, ?, description, type, 'tracepermit_synthetic_admission_protocol', checksum, installed_by, execution_time, ?
            FROM tp_operation_schema_history WHERE version = '6'
          """)) {
        statement.setQueryTimeout(5); statement.setString(1, kind == 0 ? "7" : kind == 1 ? "6" : null);
        statement.setBoolean(2, kind != 1); assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      try { assertCode(() -> repository.admit(execution(approved, fresh()), token), OPERATION_SCHEMA_NOT_READY); }
      finally { sql("DELETE FROM tp_operation_schema_history WHERE script = 'tracepermit_synthetic_admission_protocol'"); }
      assertCurrent(approved); assertThat(count("tp_operation_admissions")).isZero();
    }
  }
  private void awaitDatabaseWait(int pid, Instant expiry) throws Exception {
    var inspector = pg() ? owner : new Source("root", database.getPassword());
    long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    try (var connection = inspector.getConnection(); var statement = connection.prepareStatement(pg()
        ? "SELECT COUNT(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'"
        : "SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id = ? AND trx_state = 'LOCK WAIT'")) {
      statement.setQueryTimeout(3); statement.setInt(1, pid);
      while (true) {
        try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); if (rows.getInt(1) == 1) break; }
        assertThat(System.nanoTime()).isLessThan(bound); Thread.sleep(10);
      }
    }
    assertThat(storeNow()).isBefore(expiry); // Prove actual database wait began while signed authority was live.
  }

  private JdbcGitHubOperationAdmissionRepository admission() { return new JdbcGitHubOperationAdmissionRepository(runtime, profile, target); }
  private JdbcGitHubOperationPermitIssuer issuer() { return new JdbcGitHubOperationPermitIssuer(runtime, profile, target, keys.getPrivate()); }
  private JsonNode approved() { return approved(runtime); }
  private JsonNode approved(DataSource dataSource) {
    var writer = new JdbcGitHubOperationReviewRepository(dataSource, profile, target);
    var identity = requester(Set.of("operations.evaluate"), fresh());
    JsonNode pending = writer.evaluate(new BusinessOperationEvaluation(SCOPE, id(), PLANNER.observe(SCOPE, initial), new TrustedOperationEvaluatorContext(identity, SCOPE.workspaceId())));
    var reviewer = new TrustedReviewerContext(new TenantPartition(SCOPE.tenantId()), "principal:reviewer", "human", true,
        Set.of("operation_reviewer"), Set.of("operations.decide"), "synthetic-test", HASH);
    return writer.review(new BusinessOperationReview(SCOPE, id(), PLANNER.observe(SCOPE, pending),
        new TrustedOperationReviewerContext(reviewer, SCOPE.workspaceId(), fresh()), BusinessOperationReview.Decision.APPROVE),
        new TrustedOperationGroupMembership(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", "synthetic-reviewers", HASH,
            Instant.now().minusSeconds(1), fresh().minusSeconds(30)));
  }
  private BusinessOperationExecution execution(JsonNode approved, Instant expiry) {
    return new BusinessOperationExecution(SCOPE, id(), PLANNER.observe(SCOPE, approved), requester(Set.of("operations.execute"), expiry), SCOPE.workspaceId());
  }
  private static TrustedRequesterContext requester(Set<String> scopes, Instant expiry) {
    return new TrustedRequesterContext(new TenantPartition(SCOPE.tenantId()), SCOPE.requesterPrincipalId(), true,
        Set.of("operation_requester"), scopes, "synthetic-test", HASH, expiry);
  }
  private JsonNode current() { return new JdbcBusinessOperationLifecycleRepository(runtime).findCurrent(SCOPE, id()).orElseThrow(); }
  private void assertCurrent(JsonNode expected) { assertThat(CanonicalJson.canonicalize(current())).isEqualTo(CanonicalJson.canonicalize(expected)); }
  private String id() { return initial.path("operationId").asText(); }
  private static Instant fresh() { return Instant.now().plusSeconds(180); }
  private boolean pg() { return database.getJdbcUrl().startsWith("jdbc:postgresql:"); }
  private static List<String> tables() { return List.of("tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts", "tp_operation_lifecycle_heads",
      "tp_operation_lifecycle_facts", "tp_operation_authority_controls", "tp_operation_review_bindings", "tp_operation_admissions", "tp_operation_admission_outbox"); }
  private void sql(String sql) throws SQLException { try (var connection = owner.getConnection(); var statement = connection.createStatement()) { statement.setQueryTimeout(5); statement.execute(sql); } }
  private int count(String table) throws SQLException {
    try (var connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5); try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { assertThat(rows.next()).isTrue(); return rows.getInt(1); }
    }
  }
  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, BusinessOperationStorageException.Code code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationStorageException.class, fixed -> {
      assertThat(fixed.code()).isEqualTo(code); fixed.addSuppressed(new IllegalStateException("synthetic-private")); assertThat(fixed.getSuppressed()).isEmpty();
    }).hasMessage(code.name()).hasNoCause();
  }
  private ObjectNode operation() {
    ObjectNode value = (ObjectNode) JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot")).resolve("contracts/vectors/business-operation-v1.json")).path("operation").deepCopy();
    value.put("operationId", UUID.randomUUID().toString()); ((ArrayNode) value.path("transitions")).remove(3);
    ((ObjectNode) value.path("authorization")).put("status", "evaluating").put("version", 1).put("updatedAt", value.path("createdAt").asText());
    value.put("updatedAt", value.path("transitions").get(2).path("at").asText());
    ObjectNode action = (ObjectNode) value.path("canonicalAction").path("action"); ObjectNode arguments = (ObjectNode) action.path("arguments");
    arguments.remove("owner"); arguments.put("repository", target.targetBinding().repository()).put("head", target.targetBinding().head()).put("base", "main");
    ((ObjectNode) action.path("target")).put("accountId", target.targetBinding().repository()).put("resourceType", "pull_request").put("environment", target.environment());
    value.set("resourceBinding", target.resourceBinding()); value.putObject("policyRelease").put("policyId", target.policyRelease().policyId()).put("version", 1).put("contentHash", target.policyRelease().contentHash());
    var artifacts = value.putArray("artifactDigests"); new TreeMap<>(target.artifactDigests()).forEach((kind, hash) -> artifacts.addObject().put("kind", kind).put("digest", hash));
    value.put("canonicalActionHash", CanonicalJson.sha256(value.path("canonicalAction"))).put("semanticPayloadDigest", CanonicalJson.sha256(action)).put("resourceBindingHash", CanonicalJson.sha256(value.path("resourceBinding")));
    value.put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(idOf(value), target.adapterId(), 1));
    return (ObjectNode) BusinessOperationValidator.validate(value);
  }
  private static String idOf(JsonNode value) { return value.path("operationId").asText(); }
  private final class Source implements DataSource {
    private final JdbcDatabaseContainer<?> owned;
    private final String user; private final String password; volatile String fault = "none";
    volatile CountDownLatch insertionAttempt;
    final AtomicInteger insertionPid = new AtomicInteger();
    Source(String user, String password) { this(database, user, password); }
    Source(JdbcDatabaseContainer<?> owned, String user, String password) { this.owned = owned; this.user = user; this.password = password; }
    @Override public Connection getConnection() throws SQLException {
      boolean postgres = owned.getJdbcUrl().startsWith("jdbc:postgresql:");
      String url = owned.getJdbcUrl() + (owned.getJdbcUrl().contains("?") ? "&" : "?")
          + (postgres ? "connectTimeout=5&socketTimeout=15" : "connectTimeout=5000&socketTimeout=15000");
      Connection delegate = DriverManager.getConnection(url, user, password); boolean[] committed = {false};
      return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        if (method.getName().equals("commit")) { delegate.commit(); committed[0] = true; if (fault.equals("after-commit")) throw new SQLException("synthetic-private"); return null; }
        if (method.getName().equals("close") && committed[0] && fault.equals("close-after-commit")) { delegate.close(); throw new SQLException("synthetic-private"); }
        Object result = invoke(method, delegate, args);
        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
          return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (ignored, call, values) -> {
            if (call.getName().equals("executeUpdate") && sql.contains("INSERT INTO tp_operation_admissions") && insertionAttempt != null) {
              try (var query = delegate.prepareStatement(postgres ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()")) {
                query.setQueryTimeout(3); try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); insertionPid.set(rows.getInt(1)); }
              }
              insertionAttempt.countDown();
            }
            if (call.getName().equals("executeUpdate") && ((fault.equals("admission") && sql.contains("INSERT INTO tp_operation_admissions"))
                || (fault.equals("fact") && sql.contains("INSERT INTO tp_operation_lifecycle_facts"))
                || (fault.equals("head") && sql.contains("UPDATE tp_operation_lifecycle_heads"))
                || (fault.equals("outbox") && sql.contains("INSERT INTO tp_operation_admission_outbox")))) throw new SQLException("synthetic-private");
            return invoke(call, result, values);
          });
        }
        return result;
      });
    }
    private Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable { try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); } }
    @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) {}
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
  }
}
