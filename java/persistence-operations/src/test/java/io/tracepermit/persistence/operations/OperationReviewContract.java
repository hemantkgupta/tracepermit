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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** Owned synthetic JDBC qualification. No provider, target, listener, credential or execution port. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
abstract class OperationReviewContract {
  private static final BusinessOperationScope SCOPE = new BusinessOperationScope(
      "synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private static final String GROUP = "synthetic-reviewers";
  private static final String HASH = "sha256:" + "a".repeat(64);
  private static final BusinessOperationTransitionPlanner PLANNER = new BusinessOperationTransitionPlanner();
  private JdbcDatabaseContainer<?> database;
  private TestSource source;
  private TestSource runtime;
  private BusinessOperationAuthorityProfile profile;
  private GitHubBusinessOperationBinding.ExpectedProfile target;
  private ObjectNode initial;

  abstract JdbcDatabaseContainer<?> container();

  @BeforeAll
  void start() throws Exception {
    database = isolatedContainer();
    database.start();
    assertLoopback(database);
    source = new TestSource(database, database.getUsername(), database.getPassword());
    OperationDatabase.initializeReviewEmpty(source);
    String password = UUID.randomUUID().toString();
    var owner = postgres() ? source : new TestSource(database, "root", database.getPassword());
    try (var connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      if (postgres()) {
        statement.execute("CREATE USER tp_review_runtime PASSWORD '" + password + "'");
        statement.execute("GRANT USAGE ON SCHEMA public TO tp_review_runtime");
        for (String table : List.of("tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts",
            "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_authority_controls", "tp_operation_review_bindings")) {
          statement.execute("GRANT SELECT ON " + table + " TO tp_review_runtime");
        }
        statement.execute("GRANT INSERT ON tp_operation_lifecycle_facts, tp_operation_review_bindings TO tp_review_runtime");
        statement.execute("GRANT UPDATE(snapshot_hash, snapshot_json) ON tp_operation_lifecycle_heads TO tp_review_runtime");
        statement.execute("GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO tp_review_runtime");
      } else {
        statement.execute("CREATE USER 'tp_review_runtime'@'%' IDENTIFIED BY '" + password + "'");
        String prefix = "`" + database.getDatabaseName() + "`.";
        for (String table : List.of("tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts",
            "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_authority_controls", "tp_operation_review_bindings")) {
          statement.execute("GRANT SELECT ON " + prefix + table + " TO 'tp_review_runtime'@'%'");
        }
        statement.execute("GRANT INSERT ON " + prefix + "tp_operation_lifecycle_facts TO 'tp_review_runtime'@'%'");
        statement.execute("GRANT INSERT ON " + prefix + "tp_operation_review_bindings TO 'tp_review_runtime'@'%'");
        statement.execute("GRANT UPDATE(snapshot_hash, snapshot_json) ON " + prefix + "tp_operation_lifecycle_heads TO 'tp_review_runtime'@'%'");
      }
    }
    runtime = new TestSource(database, "tp_review_runtime", password);
  }

  @AfterAll void stop() { if (database != null) database.stop(); }

  private JdbcDatabaseContainer<?> isolatedContainer() {
    var owned = container().withPassword(UUID.randomUUID().toString());
    owned.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
        owned.getExposedPorts().stream().map(port -> new PortBinding(Ports.Binding.bindIp("127.0.0.1"),
            new ExposedPort(port))).toArray(PortBinding[]::new)));
    return owned;
  }

  private static void assertLoopback(JdbcDatabaseContainer<?> owned) {
    var bindings = owned.getContainerInfo().getNetworkSettings().getPorts().getBindings();
    assertThat(bindings).hasSize(1);
    assertThat(bindings.values().iterator().next()).hasSize(1)
        .allSatisfy(binding -> assertThat(binding.getHostIp()).isEqualTo("127.0.0.1"));
  }

  @BeforeEach
  void reset() throws Exception {
    source.fault = "none";
    runtime.fault = "none";
    runtime.lockAttempt = null;
    for (String table : List.of("tp_operation_review_bindings", "tp_operation_lifecycle_facts", "tp_operation_lifecycle_heads",
        "tp_business_operation_facts", "tp_business_operations", "tp_operation_authority_controls")) execute("DELETE FROM " + table);
    configure("require_approval", Duration.ofMinutes(1));
    register();
  }

  @Test
  void exactEvaluationAndIndependentReviewCommitOneClosedJournalAndRetainOriginalRegistration() throws Exception {
    var writer = writer(runtime);
    runtime.opens.set(0);
    runtime.lockOrder.clear();
    JsonNode pending = writer.evaluate(evaluation(initial, fresh()));
    assertThat(runtime.opens.get()).isEqualTo(1);
    assertThat(runtime.lockOrder).containsExactly("control", "head");
    assertThat(pending.path("authorization").path("status").asText()).isEqualTo("awaiting_approval");
    assertThat(count("tp_operation_review_bindings")).isEqualTo(1);
    var binding = binding();
    assertThat(Instant.parse(binding.path("approvalDeadline").asText()))
        .isEqualTo(Instant.parse(binding.path("evaluatedAt").asText()).plusSeconds(60));
    // The real strict digest legitimately changes with the pending snapshot.
    assertThat(GitHubBusinessOperationBinding.bind(SCOPE, pending, target).bindingDigest())
        .isNotEqualTo(binding.path("bindingDigest").asText());
    JsonNode approved = writer(runtime).review(review(pending, BusinessOperationReview.Decision.APPROVE, fresh()), member(fresh()));
    assertThat(approved.path("authorization").path("status").asText()).isEqualTo("approved");
    assertThat(approved.path("execution")).isEqualTo(initial.path("execution"));
    assertThat(approved.path("evidence")).isEqualTo(initial.path("evidence"));
    assertCurrent(approved);
    assertThat(new JdbcBusinessOperationRepository(source).find(SCOPE, id())).contains(initial);
    assertThat(count("tp_business_operation_facts")).isEqualTo(3);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(2);
    assertThat(binding()).isEqualTo(binding);
    assertCode(() -> writer.review(review(pending, BusinessOperationReview.Decision.DENY, fresh()), member(fresh())), OPERATION_STATE_STALE);
  }

  @Test
  void policyBlockAndHumanDenialHaveDistinctClosedFactsAndNeitherIsExecution() throws Exception {
    JsonNode pending = writer(runtime).evaluate(evaluation(initial, fresh()));
    JsonNode denied = writer(runtime).review(review(pending, BusinessOperationReview.Decision.DENY, fresh()), member(fresh()));
    assertThat(denied.path("authorization").path("status").asText()).isEqualTo("denied");
    assertCurrent(denied);
    reset();
    execute("DELETE FROM tp_operation_authority_controls");
    execute("DELETE FROM tp_operation_lifecycle_heads"); // no lifecycle facts/bindings yet
    execute("DELETE FROM tp_business_operation_facts");
    execute("DELETE FROM tp_business_operations");
    configure("block", Duration.ofMinutes(1));
    register();
    JsonNode blocked = writer(runtime).evaluate(evaluation(initial, fresh()));
    assertThat(blocked.path("authorization").path("status").asText()).isEqualTo("denied");
    assertThat(binding().has("approvalDeadline")).isFalse();
    assertCurrent(blocked);
    assertCode(() -> writer(runtime).review(review(blocked, BusinessOperationReview.Decision.APPROVE, fresh()), member(fresh())), OPERATION_NOT_REVIEWABLE);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(1);
  }

  @Test
  void equivalentOperationTimestampSpellingsSurviveEvaluationWithoutRewritingOriginal() throws Exception {
    for (int digits : List.of(3, 9)) {
      reset();
      registerEquivalentTimestamp(digits);
      String original = CanonicalJson.canonicalize(initial);
      JsonNode pending = writer(runtime).evaluate(evaluation(initial, fresh()));
      JsonNode approved = writer(runtime).review(review(pending, BusinessOperationReview.Decision.APPROVE, fresh()), member(fresh()));
      assertCurrent(approved);
      assertThat(CanonicalJson.canonicalize(new JdbcBusinessOperationRepository(source).find(SCOPE, id()).orElseThrow()))
          .isEqualTo(original);
      assertThat(binding().path("evaluatedSnapshotHash").asText()).isEqualTo(CanonicalJson.sha256(initial));
    }
  }

  @Test
  void equivalentOperationTimestampSpellingsSurvivePreEvaluationWithdrawal() throws Exception {
    for (int digits : List.of(3, 9)) {
      reset();
      registerEquivalentTimestamp(digits);
      String original = CanonicalJson.canonicalize(initial);
      JsonNode cancelled = writer(runtime).withdraw(withdrawal(initial));
      assertCurrent(cancelled);
      assertThat(count("tp_operation_review_bindings")).isZero();
      assertThat(CanonicalJson.canonicalize(new JdbcBusinessOperationRepository(source).find(SCOPE, id()).orElseThrow()))
          .isEqualTo(original);
    }
  }

  @Test
  void populatedV3WithEquivalentTimestampSpellingUpgradesAndCanEvaluate() throws Exception {
    try (var extra = isolatedContainer()) {
      extra.start();
      assertLoopback(extra);
      var owner = new TestSource(extra, extra.getUsername(), extra.getPassword());
      OperationDatabase.initializeAuthorityEmpty(owner);
      ObjectNode original = equivalentTimestamp(operation(target), 9);
      new JdbcBusinessOperationRepository(owner).createOrGet(SCOPE, original);
      OperationAuthorityAdministration.stageNew(owner, profile);
      OperationAuthorityAdministration.activateStaged(owner, profile);
      OperationDatabase.upgradeReviewFromV3(owner);
      JsonNode pending = writer(owner).evaluate(evaluation(original, fresh()));
      String operationId = original.path("operationId").asText();
      assertThat(CanonicalJson.canonicalize(new JdbcBusinessOperationLifecycleRepository(owner)
          .findCurrent(SCOPE, operationId).orElseThrow())).isEqualTo(CanonicalJson.canonicalize(pending));
      assertThat(CanonicalJson.canonicalize(new JdbcBusinessOperationRepository(owner).find(SCOPE, operationId).orElseThrow()))
          .isEqualTo(CanonicalJson.canonicalize(original));
    }
  }

  @Test
  void equivalentOperationTimestampStillRejectsWriterClockRegression() throws Exception {
    for (String table : List.of("tp_operation_lifecycle_heads", "tp_business_operation_facts", "tp_business_operations")) {
      execute("DELETE FROM " + table);
    }
    initial = equivalentTimestamp(initial, 9, storeNow().plusSeconds(60)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    register();
    assertCode(() -> writer(runtime).evaluate(evaluation(initial, fresh())), OPERATION_CLOCK_REGRESSION);
    assertCode(() -> writer(runtime).withdraw(withdrawal(initial)), OPERATION_CLOCK_REGRESSION);
    assertThat(count("tp_operation_lifecycle_facts")).isZero();
    assertThat(count("tp_operation_review_bindings")).isZero();
    assertCurrent(initial);
  }

  @Test
  void withdrawalsBeforeAndAfterEvaluationUseControlFirstAndNeverConsumeApproval() throws Exception {
    var legacy = new JdbcBusinessOperationLifecycleRepository(runtime);
    assertCode(() -> legacy.withdraw(withdrawal(initial)), OPERATION_SCHEMA_NOT_READY);
    JsonNode cancelled = writer(runtime).withdraw(withdrawal(initial));
    assertThat(count("tp_operation_review_bindings")).isZero();
    assertCurrent(cancelled);
    reset();
    JsonNode pending = writer(runtime).evaluate(evaluation(initial, fresh()));
    var binding = binding();
    cancelled = writer(runtime).withdraw(withdrawal(pending));
    assertCurrent(cancelled);
    assertThat(binding()).isEqualTo(binding);
    assertCode(() -> writer(runtime).review(review(pending, BusinessOperationReview.Decision.APPROVE, fresh()), member(fresh())), OPERATION_STATE_STALE);
  }

  @Test
  void missingOrInactiveCurrentControlAndChangedGenerationRefuseWithoutOperationEffects() throws Exception {
    OperationAuthorityAdministration.quarantine(source, profile);
    assertCode(() -> writer(runtime).evaluate(evaluation(initial, fresh())), OPERATION_AUTHORITY_NOT_READY);
    assertThat(count("tp_operation_review_bindings")).isZero();
    assertThat(count("tp_operation_lifecycle_facts")).isZero();
    execute("UPDATE tp_operation_authority_controls SET recovery_generation = 2");
    assertCode(() -> writer(runtime).evaluate(evaluation(initial, fresh())), OPERATION_PROFILE_MISMATCH);
    assertCurrent(initial);
  }

  @Test
  void evaluatorAuthenticationAndExactGroupSourceAndFreshnessAreRecheckedForBothDecisions() throws Exception {
    assertCode(() -> writer(runtime).evaluate(evaluation(initial, Instant.EPOCH)), OPERATION_AUTHENTICATION_EXPIRED);
    assertThat(count("tp_operation_review_bindings")).isZero();
    JsonNode pending = writer(runtime).evaluate(evaluation(initial, fresh()));
    for (var decision : BusinessOperationReview.Decision.values()) {
      var command = review(pending, decision, fresh());
      Instant observed = Instant.now().minusSeconds(1);
      for (var changed : List.of(new TrustedOperationGroupMembership(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer",
              "other-group", HASH, observed, fresh()),
          new TrustedOperationGroupMembership(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", GROUP,
              "sha256:" + "b".repeat(64), observed, fresh()))) {
        assertCode(() -> writer(runtime).review(command, changed), OPERATION_GROUP_MEMBERSHIP_INVALID);
      }
      var expired = new TrustedOperationGroupMembership(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", GROUP,
          HASH, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
      assertCode(() -> writer(runtime).review(command, expired), OPERATION_GROUP_MEMBERSHIP_EXPIRED);
      assertCode(() -> writer(runtime).review(review(pending, decision, Instant.EPOCH), member(fresh())), OPERATION_AUTHENTICATION_EXPIRED);
    }
    assertCurrent(pending);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(1);
  }

  @Test
  void separatelyConfiguredStrictTargetIsAppliedToActuallyLoadedData() throws Exception {
    var old = target.targetBinding();
    var changed = new GitHubBusinessOperationBinding.ExpectedProfile(target.adapterId(), target.environment(), 1,
        target.policyRelease(), target.artifactDigests(), new GitHubPilotTargetBinding(old.installationId(),
        old.repositoryId() + 1, old.ownerId(), old.repository(), old.head(), old.base(), old.headSha(), old.baseSha()));
    var wrong = new JdbcGitHubOperationReviewRepository(runtime, profile, changed);
    assertCode(() -> wrong.evaluate(evaluation(initial, fresh())), OPERATION_PROFILE_MISMATCH);
    assertCurrent(initial);
    assertThat(count("tp_operation_review_bindings")).isZero();
  }

  @Test
  void retainedReviewOwnerAndReaderRefuseAnUnknownInstalledSchemaVersion() throws Exception {
    var retained = writer(runtime);
    var reader = new JdbcBusinessOperationLifecycleRepository(runtime);
    execute("UPDATE tp_operation_schema_history SET version = '5' WHERE version = '4' AND success = TRUE");
    try {
      assertCode(() -> retained.evaluate(evaluation(initial, fresh())), OPERATION_SCHEMA_NOT_READY);
      assertCode(() -> reader.findCurrent(SCOPE, id()), OPERATION_STORAGE_CORRUPT);
      assertThat(count("tp_operation_review_bindings")).isZero();
      assertThat(count("tp_operation_lifecycle_facts")).isZero();
    } finally {
      execute("UPDATE tp_operation_schema_history SET version = '4' WHERE version = '5' AND success = TRUE");
    }
    assertCurrent(initial);
    for (String kind : List.of("failed", "repeatable")) {
      try (var connection = source.getConnection(); var statement = connection.prepareStatement("""
          INSERT INTO tp_operation_schema_history
            (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
          SELECT installed_rank + 100, ?, description, type, 'tracepermit_synthetic_later_protocol',
                 checksum, installed_by, execution_time, ?
            FROM tp_operation_schema_history WHERE version = '4'
          """)) {
        statement.setQueryTimeout(5);
        statement.setString(1, "failed".equals(kind) ? "5" : null);
        statement.setBoolean(2, !"failed".equals(kind));
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      try {
        assertCode(() -> retained.evaluate(evaluation(initial, fresh())), OPERATION_SCHEMA_NOT_READY);
        assertCode(() -> reader.findCurrent(SCOPE, id()), OPERATION_STORAGE_CORRUPT);
        assertThat(count("tp_operation_review_bindings")).isZero();
      } finally {
        execute("DELETE FROM tp_operation_schema_history WHERE script = 'tracepermit_synthetic_later_protocol'");
      }
    }
  }

  @Test
  void bindingFactAndHeadFailuresRollBackTogetherBeforeCommit() throws Exception {
    var writer = writer(runtime);
    for (String phase : List.of("binding", "fact", "head")) {
      runtime.fault = phase;
      assertCode(() -> writer.evaluate(evaluation(initial, fresh())), OPERATION_STORAGE_UNAVAILABLE);
      runtime.fault = "none";
      assertCurrent(initial);
      assertThat(count("tp_operation_review_bindings")).isZero();
      assertThat(count("tp_operation_lifecycle_facts")).isZero();
    }
    JsonNode pending = writer.evaluate(evaluation(initial, fresh()));
    for (String phase : List.of("fact", "head")) {
      runtime.fault = phase;
      assertCode(() -> writer.review(review(pending, BusinessOperationReview.Decision.APPROVE, fresh()), member(fresh())), OPERATION_STORAGE_UNAVAILABLE);
      runtime.fault = "none";
      assertCurrent(pending);
      assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(1);
    }
  }

  @Test
  void uncertainCommitAndCleanupNeverAcknowledgeSuccessOrRetainPrivateCauses() throws Exception {
    for (String phase : List.of("before-commit", "after-commit", "close-after-commit")) {
      reset();
      var writer = writer(runtime);
      runtime.fault = phase;
      assertCode(() -> writer.evaluate(evaluation(initial, fresh())), OPERATION_COMMIT_UNKNOWN);
      runtime.fault = "none";
      assertThat(count("tp_operation_review_bindings")).isEqualTo("before-commit".equals(phase) ? 0 : 1);
      assertThat(count("tp_operation_lifecycle_facts")).isEqualTo("before-commit".equals(phase) ? 0 : 1);
      assertThat(current().path("authorization").path("status").asText())
          .isEqualTo("before-commit".equals(phase) ? "evaluating" : "awaiting_approval");
    }
    for (String phase : List.of("before-commit", "after-commit", "close-after-commit")) {
      reset();
      var writer = writer(runtime);
      JsonNode pending = writer.evaluate(evaluation(initial, fresh()));
      runtime.fault = phase;
      assertCode(() -> writer.review(review(pending, BusinessOperationReview.Decision.DENY, fresh()), member(fresh())), OPERATION_COMMIT_UNKNOWN);
      runtime.fault = "none";
      assertThat(count("tp_operation_lifecycle_facts")).isEqualTo("before-commit".equals(phase) ? 1 : 2);
      assertThat(current().path("authorization").path("status").asText())
          .isEqualTo("before-commit".equals(phase) ? "awaiting_approval" : "denied");
    }
  }

  @Test
  void populatedV3UpgradeValidatesBeforeDdlAndRetainedLegacyRuntimeRefusesNewProtocol() throws Exception {
    try (var extra = isolatedContainer()) {
      extra.start();
      assertLoopback(extra);
      var owner = new TestSource(extra, extra.getUsername(), extra.getPassword());
      OperationDatabase.initializeAuthorityEmpty(owner);
      var original = operation(target);
      String operationId = original.path("operationId").asText();
      new JdbcBusinessOperationRepository(owner).createOrGet(SCOPE, original);
      String password = UUID.randomUUID().toString();
      var grantOwner = postgres() ? owner : new TestSource(extra, "root", extra.getPassword());
      try (var connection = grantOwner.getConnection(); var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        if (postgres()) {
          statement.execute("CREATE USER tp_retained_v3 PASSWORD '" + password + "'");
          statement.execute("GRANT USAGE ON SCHEMA public TO tp_retained_v3");
          statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO tp_retained_v3");
          statement.execute("GRANT INSERT ON tp_operation_lifecycle_facts TO tp_retained_v3");
          statement.execute("GRANT UPDATE(snapshot_hash, snapshot_json) ON tp_operation_lifecycle_heads TO tp_retained_v3");
        } else {
          statement.execute("CREATE USER 'tp_retained_v3'@'%' IDENTIFIED BY '" + password + "'");
          for (String table : List.of("tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts",
              "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_authority_controls")) {
            statement.execute("GRANT SELECT ON `" + extra.getDatabaseName() + "`." + table + " TO 'tp_retained_v3'@'%'");
          }
          statement.execute("GRANT INSERT ON `" + extra.getDatabaseName() + "`.tp_operation_lifecycle_facts TO 'tp_retained_v3'@'%'");
          statement.execute("GRANT UPDATE(snapshot_hash, snapshot_json) ON `" + extra.getDatabaseName() + "`.tp_operation_lifecycle_heads TO 'tp_retained_v3'@'%'");
        }
      }
      var oldRuntime = new TestSource(extra, "tp_retained_v3", password);
      var retained = new JdbcBusinessOperationLifecycleRepository(oldRuntime);
      JsonNode withdrawn = retained.withdraw(withdrawal(original));
      JsonNode actor;
      try (var connection = owner.getConnection(); var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        try (var rows = statement.executeQuery("SELECT actor_json FROM tp_operation_lifecycle_facts")) {
          rows.next(); actor = StoredOperationJson.read(rows.getString(1), 4096);
        }
        statement.executeUpdate("UPDATE tp_operation_lifecycle_facts SET actor_json = '{}'");
      }
      assertCode(() -> OperationDatabase.upgradeReviewFromV3(owner), OPERATION_SCHEMA_NOT_READY);
      try (var connection = owner.getConnection()) {
        assertThat(OperationDatabase.hasReviewBindings(connection)).isFalse();
        try (var statement = connection.prepareStatement("UPDATE tp_operation_lifecycle_facts SET actor_json = ?")) {
          statement.setQueryTimeout(5);
          statement.setString(1, CanonicalJson.canonicalize(actor));
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }
      }
      OperationDatabase.upgradeReviewFromV3(owner);
      assertThat(CanonicalJson.canonicalize(new JdbcBusinessOperationLifecycleRepository(owner)
          .findCurrent(SCOPE, operationId).orElseThrow())).isEqualTo(CanonicalJson.canonicalize(withdrawn));
      try (var connection = oldRuntime.getConnection()) {
        // No newly granted SELECT on the V4 binding table. History must still prevent old writes.
        assertThat(OperationDatabase.hasReviewBindings(connection)).isTrue();
        try (var statement = connection.createStatement()) {
          statement.setQueryTimeout(5);
          assertThatThrownBy(() -> statement.executeQuery("SELECT binding_hash FROM tp_operation_review_bindings"))
              .isInstanceOf(SQLException.class);
        }
      }
      assertCode(() -> retained.withdraw(withdrawal(original)), OPERATION_SCHEMA_NOT_READY);
      assertCode(() -> OperationDatabase.upgradeReviewFromV3(owner), OPERATION_SCHEMA_NOT_READY);
    }
  }

  @Test
  void realHeadWaitRechecksGroupExpiryForApprovalAndDenial() throws Exception {
    JsonNode pending = writer(runtime).evaluate(evaluation(initial, fresh()));
    var writer = writer(runtime);
    for (var decision : BusinessOperationReview.Decision.values()) {
      try (var holder = source.getConnection()) {
        holder.setAutoCommit(false);
        lock(holder, "tp_operation_lifecycle_heads", "operation_id", id());
        Instant observed = storeNow();
        Instant expiry = observed.plusMillis(800);
        var membership = new TrustedOperationGroupMembership(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer",
            GROUP, HASH, observed, expiry);
        runtime.lockAttempt = new CountDownLatch(1);
        runtime.signalHeadOnly = true;
        var pool = Executors.newSingleThreadExecutor();
        try {
          var future = pool.submit(() -> writer.review(review(pending, decision, fresh()), membership));
          assertThat(runtime.lockAttempt.await(3, TimeUnit.SECONDS)).isTrue();
          assertThat(storeNow()).isBefore(expiry.minusMillis(150));
          assertThat(future.isDone()).isFalse();
          awaitExpiry(expiry);
          assertThat(future.isDone()).isFalse();
          holder.rollback();
          assertFutureCode(future, OPERATION_GROUP_MEMBERSHIP_EXPIRED);
        } finally {
          holder.rollback();
          runtime.signalHeadOnly = false;
          pool.shutdownNow();
          assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
      }
    }
    assertCurrent(pending);
  }

  @Test
  void closedReaderRejectsMissingExtraAndChangedBindingsAndUnknownJournalEnvelopes() throws Exception {
    JsonNode pending = writer(runtime).evaluate(evaluation(initial, fresh()));
    JsonNode captured = binding();
    execute("DELETE FROM tp_operation_review_bindings");
    assertCode(this::current, OPERATION_STORAGE_CORRUPT);
    writeBinding(captured);
    assertCurrent(pending);
    for (String field : List.of("extra", "authorizationVersion", "approvalDeadline", "outcome")) {
      ObjectNode bad = (ObjectNode) captured.deepCopy();
      switch (field) {
        case "extra" -> bad.put("extra", true);
        case "authorizationVersion" -> bad.put(field, 3);
        case "approvalDeadline" -> bad.put(field, bad.path("evaluatedAt").asText());
        case "outcome" -> bad.put(field, "allow");
        default -> throw new AssertionError();
      }
      replaceBinding(bad);
      assertCode(this::current, OPERATION_STORAGE_CORRUPT);
    }
    replaceBinding(captured);
    JsonNode actor = json("SELECT actor_json FROM tp_operation_lifecycle_facts");
    ObjectNode badActor = (ObjectNode) actor.deepCopy();
    badActor.put("journalFormat", "future-unknown");
    try (var connection = source.getConnection(); var statement = connection.prepareStatement(
        "UPDATE tp_operation_lifecycle_facts SET actor_hash = ?, actor_json = ?")) {
      statement.setQueryTimeout(5);
      statement.setString(1, CanonicalJson.sha256(badActor));
      statement.setString(2, CanonicalJson.canonicalize(badActor));
      statement.executeUpdate();
    }
    assertCode(this::current, OPERATION_STORAGE_CORRUPT);
    reset();
    writeBinding(captured); // same scope, but operation id is now different
    assertCode(this::current, OPERATION_STORAGE_CORRUPT);
  }

  @Test
  void thirtyTwoMixedReviewAndWithdrawalContendersHaveExactlyOneAcknowledgedWinner() throws Exception {
    JsonNode pending = writer(runtime).evaluate(evaluation(initial, fresh()));
    var writer = writer(runtime);
    var ready = new CountDownLatch(32);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(32);
    var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
    try {
      for (int index = 0; index < 32; index++) {
        boolean approve = index % 2 == 0;
        futures.add(pool.submit(() -> {
          ready.countDown();
          if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("synthetic start timeout");
          try {
            if (approve) writer.review(review(pending, BusinessOperationReview.Decision.APPROVE, fresh()), member(fresh()));
            else writer.withdraw(withdrawal(pending));
            return true;
          } catch (BusinessOperationStorageException failure) {
            assertThat(failure.code()).isEqualTo(OPERATION_STATE_STALE);
            return false;
          }
        }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      int winners = 0;
      for (var future : futures) if (future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) winners++;
      assertThat(winners).isEqualTo(1);
      assertThat(current().path("authorization").path("status").asText()).isIn("approved", "cancelled");
      assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(2);
      assertThat(count("tp_operation_review_bindings")).isEqualTo(1);
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void writerTimeAfterActualControlAndHeadWaitRejectsExpiredEvaluationAndApproval() throws Exception {
    var evaluationWriter = writer(runtime);
    // Lock before taking the fresh writer observation, not before container setup or validation.
    try (var holder = source.getConnection()) {
      holder.setAutoCommit(false);
      lock(holder, "tp_operation_authority_controls", "scope_hash", SCOPE.fingerprint());
      Instant deadline = storeNow().plusMillis(800);
      runtime.lockAttempt = new CountDownLatch(1);
      var pool = Executors.newSingleThreadExecutor();
      try {
        var future = pool.submit(() -> evaluationWriter.evaluate(evaluation(initial, deadline)));
        assertThat(runtime.lockAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(storeNow()).isBefore(deadline.minusMillis(150));
        assertThat(future.isDone()).isFalse();
        awaitExpiry(deadline);
        assertThat(future.isDone()).isFalse();
        holder.rollback();
        assertFutureCode(future, OPERATION_AUTHENTICATION_EXPIRED);
      } finally {
        holder.rollback();
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
    assertThat(count("tp_operation_review_bindings")).isZero();
    // An explicitly short configured lifetime; this does not change any default or gate.
    execute("DELETE FROM tp_operation_authority_controls");
    ObjectNode registered = initial;
    configure("require_approval", Duration.ofMillis(800));
    initial = registered;
    var reviewWriter = writer(runtime);
    JsonNode pending = reviewWriter.evaluate(evaluation(initial, fresh()));
    Instant deadline = Instant.parse(binding().path("approvalDeadline").asText());
    try (var holder = source.getConnection()) {
      holder.setAutoCommit(false);
      lock(holder, "tp_operation_lifecycle_heads", "operation_id", id());
      runtime.lockAttempt = new CountDownLatch(1);
      runtime.signalHeadOnly = true;
      var pool = Executors.newSingleThreadExecutor();
      try {
        var future = pool.submit(() -> reviewWriter.review(review(pending, BusinessOperationReview.Decision.APPROVE, fresh()), member(fresh())));
        assertThat(runtime.lockAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(storeNow()).isBefore(deadline.minusMillis(150));
        assertThat(future.isDone()).isFalse();
        awaitExpiry(deadline);
        assertThat(future.isDone()).isFalse();
        holder.rollback();
        assertFutureCode(future, OPERATION_APPROVAL_EXPIRED);
      } finally {
        holder.rollback();
        runtime.signalHeadOnly = false;
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
    assertCurrent(pending);
  }

  @Test
  void runtimeCanInsertBindingButCannotRewriteOrDeleteItOrChangeControlPins() throws Exception {
    JsonNode pending = writer(runtime).evaluate(evaluation(initial, fresh()));
    for (String sql : List.of("UPDATE tp_operation_review_bindings SET binding_hash = binding_hash",
        "DELETE FROM tp_operation_review_bindings", "UPDATE tp_operation_authority_controls SET profile_hash = profile_hash",
        "UPDATE tp_business_operations SET snapshot_hash = snapshot_hash")) {
      try (var admin = source.getConnection(); var statement = admin.createStatement()) {
        admin.setAutoCommit(false);
        statement.setQueryTimeout(5);
        assertThat(statement.executeUpdate(sql)).isEqualTo(1); // valid statement, not duplicate-key failure
        admin.rollback();
      }
      try (var connection = runtime.getConnection(); var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        assertThatThrownBy(() -> statement.executeUpdate(sql)).isInstanceOfSatisfying(SQLException.class, failure -> {
          if (postgres()) assertThat(failure.getSQLState()).isEqualTo("42501");
          else {
            assertThat(failure.getSQLState()).isEqualTo("42000");
            assertThat(failure.getErrorCode()).isEqualTo(1142);
          }
        });
      }
    }
    assertCurrent(pending);
  }

  private void configure(String outcome, Duration lifetime) throws Exception {
    ObjectNode policy = (ObjectNode) JsonSupport.MAPPER.readTree("""
        {"schemaVersion":"1.0","id":"synthetic-github-draft-pr","version":1,"name":"Synthetic",
         "description":"No target authority","enabled":true,"defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
         "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic",
         "match":{"toolNames":["github.pull_request.create"]},
         "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW","approvalGroup":"synthetic-reviewers"}}]}
        """);
    ((ObjectNode) policy.path("rules").get(0).path("decision")).put("outcome", outcome);
    target = new GitHubBusinessOperationBinding.ExpectedProfile("github-draft-pr-v1", "test", 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", 1, CanonicalJson.sha256(policy)),
        Map.of("policy_bundle", HASH, "adapter_release", HASH),
        new GitHubPilotTargetBinding(71, 101, 201, "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40)));
    initial = operation(target);
    profile = new BusinessOperationAuthorityProfile(SCOPE, target.adapterId(), 1, policy,
        CanonicalJson.sha256(initial.path("artifactDigests")), initial.path("resourceBindingHash").asText(),
        new BusinessOperationReviewSettings(GROUP, HASH, lifetime));
    OperationAuthorityAdministration.stageNew(source, profile);
    OperationAuthorityAdministration.activateStaged(source, profile);
  }

  private void register() { new JdbcBusinessOperationRepository(source).createOrGet(SCOPE, initial); }
  private void registerEquivalentTimestamp(int digits) throws Exception {
    for (String table : List.of("tp_operation_lifecycle_heads", "tp_business_operation_facts", "tp_business_operations")) {
      execute("DELETE FROM " + table);
    }
    initial = equivalentTimestamp(initial, digits);
    register();
  }

  private static ObjectNode equivalentTimestamp(ObjectNode snapshot, int digits) {
    // Use a whole-second last transition so both .000Z and .000000000Z are valid
    // non-Instant.toString spellings. Keep the dimension/history instants coherent.
    return equivalentTimestamp(snapshot, digits, Instant.parse(snapshot.path("updatedAt").asText()).plusSeconds(1)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
  }

  private static ObjectNode equivalentTimestamp(ObjectNode snapshot, int digits, Instant at) {
    var copy = snapshot.deepCopy();
    ObjectNode last = (ObjectNode) copy.path("transitions").get(copy.path("transitions").size() - 1);
    last.put("at", at.toString());
    ((ObjectNode) copy.path(last.path("dimension").asText())).put("updatedAt", at.toString());
    String spelling = new java.time.format.DateTimeFormatterBuilder().appendInstant(digits).toFormatter()
        .format(at);
    assertThat(spelling).isNotEqualTo(Instant.parse(spelling).toString());
    copy.put("updatedAt", spelling);
    return (ObjectNode) BusinessOperationValidator.validate(copy);
  }
  private JdbcGitHubOperationReviewRepository writer(DataSource ds) { return new JdbcGitHubOperationReviewRepository(ds, profile, target); }
  private JsonNode current() { return new JdbcBusinessOperationLifecycleRepository(source).findCurrent(SCOPE, id()).orElseThrow(); }
  private void assertCurrent(JsonNode expected) {
    // Canonical bytes, not Jackson's JDBC IntNode versus planner LongNode implementation width.
    assertThat(CanonicalJson.canonicalize(current())).isEqualTo(CanonicalJson.canonicalize(expected));
  }
  private String id() { return initial.path("operationId").asText(); }
  private static Instant fresh() { return Instant.now().plusSeconds(120); }
  private static BusinessOperationEvaluation evaluation(JsonNode before, Instant expiry) {
    return new BusinessOperationEvaluation(SCOPE, before.path("operationId").asText(), PLANNER.observe(SCOPE, before),
        new TrustedOperationEvaluatorContext(requester(expiry), SCOPE.workspaceId()));
  }
  private static TrustedRequesterContext requester(Instant expiry) {
    return new TrustedRequesterContext(new TenantPartition(SCOPE.tenantId()), SCOPE.requesterPrincipalId(), true,
        Set.of("operation_requester"), Set.of("operations.evaluate", "operations.cancel"), "synthetic-test", HASH, expiry);
  }
  private static BusinessOperationWithdrawal withdrawal(JsonNode before) {
    return new BusinessOperationWithdrawal(SCOPE, before.path("operationId").asText(), PLANNER.observe(SCOPE, before), requester(fresh()));
  }
  private static BusinessOperationReview review(JsonNode before, BusinessOperationReview.Decision decision, Instant expiry) {
    var identity = new TrustedReviewerContext(new TenantPartition(SCOPE.tenantId()), "principal:reviewer", "human", true,
        Set.of("operation_reviewer"), Set.of("operations.decide"), "synthetic-test", HASH);
    return new BusinessOperationReview(SCOPE, before.path("operationId").asText(), PLANNER.observe(SCOPE, before),
        new TrustedOperationReviewerContext(identity, SCOPE.workspaceId(), expiry), decision);
  }
  private static TrustedOperationGroupMembership member(Instant expiry) {
    // A stable expiry earlier than independently sampled authentication expiry.
    return new TrustedOperationGroupMembership(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", GROUP,
        HASH, Instant.now().minusSeconds(1), expiry.minusSeconds(30));
  }

  private static ObjectNode operation(GitHubBusinessOperationBinding.ExpectedProfile expected) {
    ObjectNode value = (ObjectNode) JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot"))
        .resolve("contracts/vectors/business-operation-v1.json")).path("operation").deepCopy();
    value.put("operationId", UUID.randomUUID().toString());
    ((ArrayNode) value.path("transitions")).remove(3);
    ((ObjectNode) value.path("authorization")).put("status", "evaluating").put("version", 1)
        .put("updatedAt", value.path("createdAt").asText());
    value.put("updatedAt", value.path("transitions").get(2).path("at").asText());
    ObjectNode action = (ObjectNode) value.path("canonicalAction").path("action");
    ObjectNode arguments = (ObjectNode) action.path("arguments");
    arguments.remove("owner");
    arguments.put("repository", expected.targetBinding().repository()).put("head", expected.targetBinding().head()).put("base", "main");
    ((ObjectNode) action.path("target")).put("accountId", expected.targetBinding().repository())
        .put("resourceType", "pull_request").put("environment", expected.environment());
    value.set("resourceBinding", expected.resourceBinding());
    value.putObject("policyRelease").put("policyId", expected.policyRelease().policyId())
        .put("version", 1).put("contentHash", expected.policyRelease().contentHash());
    var artifacts = value.putArray("artifactDigests");
    new java.util.TreeMap<>(expected.artifactDigests()).forEach((kind, hash) -> artifacts.addObject().put("kind", kind).put("digest", hash));
    value.put("canonicalActionHash", CanonicalJson.sha256(value.path("canonicalAction")));
    value.put("semanticPayloadDigest", CanonicalJson.sha256(action));
    value.put("resourceBindingHash", CanonicalJson.sha256(value.path("resourceBinding")));
    value.put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(value.path("operationId").asText(), expected.adapterId(), 1));
    return (ObjectNode) BusinessOperationValidator.validate(value);
  }

  private boolean postgres() { return database.getJdbcUrl().startsWith("jdbc:postgresql:"); }
  private void execute(String sql) throws SQLException {
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(sql);
    }
  }
  private int count(String table) throws SQLException {
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { rows.next(); return rows.getInt(1); }
    }
  }
  private JsonNode json(String sql) throws SQLException {
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return StoredOperationJson.read(rows.getString(1), 8192); }
    }
  }
  private JsonNode binding() throws SQLException { return json("SELECT binding_json FROM tp_operation_review_bindings"); }
  private void writeBinding(JsonNode binding) throws SQLException {
    ObjectNode copy = (ObjectNode) binding.deepCopy();
    copy.put("operationId", id());
    try (var connection = source.getConnection()) { OperationReviewJournal.insert(connection, new OperationReviewJournal.Binding(copy, CanonicalJson.sha256(copy))); }
  }
  private void replaceBinding(JsonNode binding) throws SQLException {
    execute("DELETE FROM tp_operation_review_bindings");
    writeBinding(binding);
  }
  private Instant storeNow() throws SQLException {
    try (var connection = source.getConnection()) {
      return JdbcBusinessOperationLifecycleRepository.storeTime(connection, OperationDatabase.inspectWriter(connection));
    }
  }
  private void awaitExpiry(Instant deadline) throws Exception {
    long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!storeNow().isAfter(deadline)) {
      assertThat(System.nanoTime()).isLessThan(bound);
      Thread.sleep(10);
    }
  }
  private static void lock(Connection connection, String table, String column, String value) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT " + column + " FROM " + table + " WHERE " + column + " = ? FOR UPDATE")) {
      statement.setQueryTimeout(5);
      statement.setString(1, value);
      try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
    }
  }
  private static void assertFutureCode(java.util.concurrent.Future<?> future, BusinessOperationStorageException.Code code) throws Exception {
    try { future.get(10, TimeUnit.SECONDS); fail("expected fixed refusal"); }
    catch (java.util.concurrent.ExecutionException failure) {
      assertThat(failure.getCause()).isInstanceOfSatisfying(BusinessOperationStorageException.class, fixed -> assertThat(fixed.code()).isEqualTo(code));
    }
  }
  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, BusinessOperationStorageException.Code code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationStorageException.class, fixed -> {
      assertThat(fixed.code()).isEqualTo(code);
      fixed.addSuppressed(new IllegalStateException("synthetic-private-cleanup"));
      assertThat(fixed.getSuppressed()).isEmpty();
    }).hasMessage(code.name()).hasNoCause();
  }

  private static final class TestSource implements DataSource {
    private final JdbcDatabaseContainer<?> database;
    private final String user;
    private final String password;
    private volatile String fault = "none";
    private volatile CountDownLatch lockAttempt;
    private volatile boolean signalHeadOnly;
    private final AtomicInteger opens = new AtomicInteger();
    private final List<String> lockOrder = java.util.Collections.synchronizedList(new ArrayList<>());
    TestSource(JdbcDatabaseContainer<?> database, String user, String password) {
      this.database = database; this.user = user; this.password = password;
    }
    @Override public Connection getConnection() throws SQLException {
      Connection delegate = DriverManager.getConnection(database.getJdbcUrl(), user, password);
      opens.incrementAndGet();
      var committed = new AtomicBoolean();
      return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        if (method.getName().equals("commit")) {
          if (fault.equals("before-commit")) throw synthetic();
          delegate.commit(); committed.set(true);
          if (fault.equals("after-commit")) throw synthetic();
          return null;
        }
        if (method.getName().equals("close") && committed.get() && fault.equals("close-after-commit")) {
          delegate.close(); throw synthetic();
        }
        Object result = invoke(method, delegate, args);
        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
          return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (ignored, call, values) -> {
            if (call.getName().equals("executeQuery") && sql.contains("FOR UPDATE")) {
              boolean head = sql.contains("tp_operation_lifecycle_heads");
              lockOrder.add(head ? "head" : "control");
              if (lockAttempt != null && (!signalHeadOnly || head)) lockAttempt.countDown();
            }
            if (call.getName().equals("executeUpdate") && ((fault.equals("binding") && sql.contains("INSERT INTO tp_operation_review_bindings"))
                || (fault.equals("fact") && sql.contains("INSERT INTO tp_operation_lifecycle_facts"))
                || (fault.equals("head") && sql.contains("UPDATE tp_operation_lifecycle_heads")))) throw synthetic();
            return invoke(call, result, values);
          });
        }
        return result;
      });
    }
    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
      try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static SQLException synthetic() { return new SQLException("synthetic-private-driver-value", "HY000", 1644); }
    @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
  }
}
