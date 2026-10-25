package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationRegistration;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.application.BusinessOperationWithdrawal;
import io.tracepermit.application.TrustedRequesterContext;
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
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.JdbcDatabaseContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class OperationRepositoryContract {
  private static final BusinessOperationScope SCOPE =
      new BusinessOperationScope("synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private JdbcDatabaseContainer<?> database;
  private TestSource source;
  private TestSource runtimeSource;
  private JdbcBusinessOperationRepository repository;

  abstract JdbcDatabaseContainer<?> container();

  @BeforeAll
  void start() {
    database = container().withPassword(UUID.randomUUID().toString());
    database.start();
    source = new TestSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    assertCode(() -> new JdbcBusinessOperationRepository(source), OPERATION_SCHEMA_NOT_READY);
    OperationDatabase.initializeEmpty(source);
    assertCode(() -> OperationDatabase.initializeEmpty(source), OPERATION_SCHEMA_NOT_READY);
    provisionRuntimeUser();
  }

  @AfterAll
  void stop() {
    if (database != null) database.stop();
  }

  @BeforeEach
  void reset() throws SQLException {
    source.fault = Fault.NONE;
    grantLifecycleRuntime();
    execute("DELETE FROM tp_operation_lifecycle_facts");
    execute("DELETE FROM tp_operation_lifecycle_heads");
    execute("DELETE FROM tp_business_operation_facts");
    execute("DELETE FROM tp_business_operations");
    repository = new JdbcBusinessOperationRepository(source);
  }

  @Test
  void restartAndTransportRetryReturnOriginalImmutableSnapshotAndPolicy() {
    ObjectNode first = operation();
    BusinessOperationRegistration created = repository.createOrGet(SCOPE, first);
    assertThat(created.created()).isTrue();
    ObjectNode retry = operation();
    ((ObjectNode) retry.path("canonicalAction")).put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) retry.path("canonicalAction").path("trigger")).put("requestId", "transport-retry");
    ((ObjectNode) retry.path("policyRelease")).put("version", 2);
    rehash(retry);
    var restarted = new JdbcBusinessOperationRepository(source);
    BusinessOperationRegistration existing = restarted.createOrGet(SCOPE, retry);
    assertThat(existing.created()).isFalse();
    assertThat(existing.operation()).isEqualTo(first);
    assertThat(restarted.find(SCOPE, first.path("operationId").asText())).contains(first);
    assertCounts(1, 3);
    ((ObjectNode) created.operation()).put("operationId", "mutated-return");
    assertThat(created.operation()).isEqualTo(first);
    assertThat(created.toString()).doesNotContain("title", "Synthetic governed change", "canonicalAction");
  }

  @Test
  void changedSemanticResourceAdapterOrRecoveryGenerationConflictsWithoutWriting() {
    ObjectNode first = operation();
    repository.createOrGet(SCOPE, first);
    for (String change : List.of("payload", "resource", "adapter", "generation")) {
      ObjectNode retry = operation();
      switch (change) {
        case "payload" -> ((ObjectNode) retry.path("canonicalAction").path("action").path("arguments"))
            .put("title", "Changed governed content");
        case "resource" -> ((ObjectNode) retry.path("resourceBinding").path("precondition"))
            .put("valueHash", "sha256:" + "9".repeat(64));
        case "adapter" -> retry.put("adapterId", "github-other-v1");
        case "generation" -> retry.put("recoveryGeneration", 2);
        default -> throw new AssertionError(change);
      }
      rehash(retry);
      assertCode(() -> repository.createOrGet(SCOPE, retry), OPERATION_REQUEST_KEY_CONFLICT);
      assertCounts(1, 3);
    }
    assertThat(repository.find(SCOPE, first.path("operationId").asText())).contains(first);
  }

  @Test
  void tenantWorkspaceRequesterAndCaseAreIsolatedEvenWithTheSameKey() {
    for (BusinessOperationScope scope : List.of(SCOPE,
        new BusinessOperationScope("Synthetic-tenant", SCOPE.workspaceId(), SCOPE.requesterPrincipalId()),
        new BusinessOperationScope(SCOPE.tenantId(), "other-workspace", SCOPE.requesterPrincipalId()),
        new BusinessOperationScope(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:other"))) {
      ObjectNode input = operation(scope);
      assertThat(repository.createOrGet(scope, input).created()).isTrue();
      if (!scope.equals(SCOPE)) {
        assertThat(repository.find(SCOPE, input.path("operationId").asText())).isEmpty();
      }
    }
    assertCounts(4, 12);
    assertCode(() -> repository.createOrGet(
        new BusinessOperationScope("other", "workspace", "principal:other"), operation()), OPERATION_SCOPE_MISMATCH);
    assertCode(() -> repository.find(SCOPE, "1-1-1-1-1"), OPERATION_ID_INVALID);
  }

  @Test
  void conflictingOperationIdDoesNotBecomeARetryOrLeakAnotherScope() {
    ObjectNode first = operation();
    repository.createOrGet(SCOPE, first);
    ObjectNode otherKey = first.deepCopy();
    otherKey.put("clientRequestKeyHash", "sha256:" + "a".repeat(64));
    assertCode(() -> repository.createOrGet(SCOPE, otherKey), OPERATION_ID_CONFLICT);
    BusinessOperationScope other = new BusinessOperationScope("other", "workspace", "principal:other");
    ObjectNode collision = operation(other);
    collision.put("operationId", first.path("operationId").asText());
    rehash(collision);
    assertCode(() -> repository.createOrGet(other, collision), OPERATION_ID_CONFLICT);
    assertCounts(1, 3);
  }

  @Test
  void sixteenConcurrentRegistrationsAcrossInstancesHaveOneWinner() throws Exception {
    List<JdbcBusinessOperationRepository> replicas = List.of(repository,
        new JdbcBusinessOperationRepository(source), new JdbcBusinessOperationRepository(source));
    var results = race(16, index -> replicas.get(index % replicas.size()).createOrGet(SCOPE, operation()));
    assertThat(results.stream().filter(BusinessOperationRegistration::created)).hasSize(1);
    assertThat(results.stream().map(result -> result.operation().path("operationId").asText()).distinct())
        .hasSize(1);
    assertCounts(1, 3);
  }

  @org.junit.jupiter.api.RepeatedTest(20)
  void competingPayloadsHaveOneSemanticWinnerAndAllOtherPayloadsConflict() throws Exception {
    var results = race(16, index -> {
      ObjectNode input = operation();
      ((ObjectNode) input.path("canonicalAction").path("action").path("arguments"))
          .put("title", "Synthetic payload " + (index % 2));
      rehash(input);
      try {
        return repository.createOrGet(SCOPE, input).created() ? "created" : "existing";
      } catch (BusinessOperationStorageException failure) {
        assertThat(failure.code()).as("fixed SQL categories: %s", source.sqlCategories)
            .isEqualTo(OPERATION_REQUEST_KEY_CONFLICT);
        return "conflict";
      }
    });
    assertThat(results).containsOnly("created", "existing", "conflict");
    assertThat(results.stream().filter("created"::equals)).hasSize(1);
    assertThat(results.stream().filter("existing"::equals)).hasSize(7);
    assertThat(results.stream().filter("conflict"::equals)).hasSize(8);
    assertCounts(1, 3);
  }

  @Test
  void failureDuringInitialFactsRollsBackTheWholeRegistration() {
    source.fault = Fault.SECOND_FACT;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
    assertThat(repository.createOrGet(SCOPE, operation()).created()).isTrue();
    assertCounts(1, 3);
  }

  @Test
  void lostCommitAcknowledgementNeverReportsCreatedAndExplicitRetryReadsOriginal() {
    ObjectNode first = operation();
    source.fault = Fault.AFTER_COMMIT;
    assertCode(() -> repository.createOrGet(SCOPE, first), OPERATION_COMMIT_UNKNOWN);
    source.fault = Fault.NONE;
    assertCounts(1, 3);
    assertThat(repository.createOrGet(SCOPE, operation()).created()).isFalse();
    assertThat(repository.find(SCOPE, first.path("operationId").asText())).contains(first);
  }

  @Test
  void failureBeforeCommitIsAlsoUnknownAndDoesNotAssumeDurability() {
    source.fault = Fault.BEFORE_COMMIT;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_COMMIT_UNKNOWN);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
  }

  @Test
  void cleanupFailureAfterCommitIsUnknownInsteadOfFalseSuccess() {
    source.fault = Fault.CLOSE_AFTER_COMMIT;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_COMMIT_UNKNOWN);
    source.fault = Fault.NONE;
    assertCounts(1, 3);
  }

  @Test
  void nonDuplicateIntegrityFailuresAndFailedRollbackNeverBecomeIdempotentSuccess() {
    source.fault = Fault.NOT_A_DUPLICATE;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
    source.fault = Fault.ROLLBACK;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
  }

  @Test
  void registrationRejectsPreauthorizedStatesAndOversizedSnapshotsBeforeOpeningTheDatabase() {
    JsonNode progressed = JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot"))
        .resolve("contracts/vectors/business-operation-v1.json")).path("operation");
    assertCode(() -> repository.createOrGet(SCOPE, progressed), OPERATION_INITIAL_STATE_REQUIRED);
    ObjectNode large = operation();
    ((ObjectNode) large.path("canonicalAction").path("action").path("arguments"))
        .put("body", "é".repeat(150_000));
    rehash(large);
    assertCode(() -> repository.createOrGet(SCOPE, large), OPERATION_TOO_LARGE);
    assertCounts(0, 0);
  }

  @Test
  void uncommittedProposalCannotAdvanceOrReplaceTheRegistrationSnapshot() {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var planner = new BusinessOperationTransitionPlanner();
    var proposal = planner.propose(SCOPE, initial, planner.observe(SCOPE, initial),
        BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION,
        "approved", "SYNTHETIC_SHAPE_ONLY", java.time.Instant.parse(initial.path("updatedAt").asText()));

    assertCode(() -> repository.createOrGet(SCOPE, proposal.snapshot()), OPERATION_INITIAL_STATE_REQUIRED);
    assertThat(repository.find(SCOPE, initial.path("operationId").asText())).contains(initial);
    assertCounts(1, 3);
  }

  @Test
  void snapshotAndInitialFactCorruptionFailClosedOnReadsAndRetries() throws SQLException {
    ObjectNode first = operation();
    repository.createOrGet(SCOPE, first);
    execute("UPDATE tp_business_operations SET snapshot_hash = 'sha256:" + "f".repeat(64) + "'");
    assertCode(() -> repository.find(SCOPE, first.path("operationId").asText()), OPERATION_STORAGE_CORRUPT);
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_STORAGE_CORRUPT);
    execute("DELETE FROM tp_operation_lifecycle_heads");
    execute("DELETE FROM tp_business_operation_facts");
    execute("DELETE FROM tp_business_operations");
    repository.createOrGet(SCOPE, first);
    execute("DELETE FROM tp_business_operation_facts WHERE sequence_number = 2");
    assertCode(() -> repository.find(SCOPE, first.path("operationId").asText()), OPERATION_STORAGE_CORRUPT);
  }

  @Test
  void startupDetectsMissingUniquenessAndNeverRepairsTheSchema() throws SQLException {
    boolean postgres = database.getJdbcUrl().startsWith("jdbc:postgresql:");
    execute(postgres
        ? "ALTER TABLE tp_business_operations DROP CONSTRAINT tp_operation_request_key"
        : "ALTER TABLE tp_business_operations DROP PRIMARY KEY");
    try {
      assertCode(() -> new JdbcBusinessOperationRepository(source), OPERATION_SCHEMA_NOT_READY);
    } finally {
      execute("ALTER TABLE tp_business_operations ADD CONSTRAINT tp_operation_request_key PRIMARY KEY (scope_hash, request_key_hash)");
    }
    OperationDatabase.validateExisting(source);
  }

  @Test
  void diagnosticExceptionsContainOnlyFixedCodes() {
    source.fault = Fault.NOT_A_DUPLICATE;
    assertThatThrownBy(() -> repository.createOrGet(SCOPE, operation()))
        .isInstanceOf(BusinessOperationStorageException.class)
        .hasMessage("OPERATION_STORAGE_UNAVAILABLE")
        .hasNoCause();
  }

  @Test
  void restrictedRuntimeUserCanValidateRegisterAndReadButCannotMutateOrDelete() throws SQLException {
    var runtime = new JdbcBusinessOperationRepository(runtimeSource);
    ObjectNode input = operation();
    assertThat(runtime.createOrGet(SCOPE, input).created()).isTrue();
    assertThat(runtime.find(SCOPE, input.path("operationId").asText())).contains(input);
    try (Connection connection = runtimeSource.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM tp_business_operations"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.executeUpdate("UPDATE tp_business_operation_facts SET fact_json = '{}'"))
          .isInstanceOf(SQLException.class);
    }
    assertCounts(1, 3);
  }

  @Test
  void runtimeRefusesUnsupportedEngineVersionsAndWriterReadOnlySessions() {
    source.fault = Fault.UNKNOWN_ENGINE;
    assertCode(() -> new JdbcBusinessOperationRepository(source), OPERATION_DATABASE_UNSUPPORTED);
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_DATABASE_UNSUPPORTED);
    source.fault = Fault.READ_ONLY;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_WRITER_REQUIRED);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
  }

  @Test
  void withdrawalIsAtomicDurableAndNeverReplacesOriginalRegistrationOrOtherDimensions() throws SQLException {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(runtimeSource);
    assertThat(lifecycle.findCurrent(SCOPE, id(initial))).contains(initial);
    JsonNode cancelled = lifecycle.withdraw(withdrawal(initial, Instant.now().plusSeconds(60)));
    assertThat(cancelled.path("authorization").path("status").asText()).isEqualTo("cancelled");
    assertThat(cancelled.path("authorization").path("version").asInt()).isEqualTo(2);
    assertThat(cancelled.path("transitions")).hasSize(4);
    assertThat(cancelled.path("execution")).isEqualTo(initial.path("execution"));
    assertThat(cancelled.path("evidence")).isEqualTo(initial.path("evidence"));
    var restarted = new JdbcBusinessOperationLifecycleRepository(runtimeSource);
    assertThat(restarted.findCurrent(SCOPE, id(initial)).map(CanonicalJson::canonicalize))
        .contains(CanonicalJson.canonicalize(cancelled));
    assertThat(repository.find(SCOPE, id(initial))).contains(initial);
    assertThat(repository.createOrGet(SCOPE, operation()).operation()).isEqualTo(initial);
    assertCounts(1, 3);
    assertLifecycleCounts(1, 1);
    ((ObjectNode) cancelled).put("operationId", "mutated-copy");
    assertThat(restarted.findCurrent(SCOPE, id(initial)).orElseThrow().path("operationId").asText()).isEqualTo(id(initial));
    try (Connection connection = source.getConnection(); var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT actor_json FROM tp_operation_lifecycle_facts")) {
      assertThat(rows.next()).isTrue();
      JsonNode actor = JsonSupport.MAPPER.readTree(rows.getString(1));
      assertThat(actor.path("credentialFingerprint").asText()).isEqualTo("sha256:" + "b".repeat(64));
      assertThat(actor.has("token")).isFalse();
    } catch (java.io.IOException failure) {
      throw new AssertionError("synthetic actor JSON invalid");
    }
  }

  @org.junit.jupiter.api.RepeatedTest(10)
  void sixteenLifecycleContendersHaveExactlyOneWinnerWithoutRebaseOrRetry() throws Exception {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var command = withdrawal(initial, Instant.now().plusSeconds(60));
    var stores = List.of(new JdbcBusinessOperationLifecycleRepository(source),
        new JdbcBusinessOperationLifecycleRepository(source));
    var outcomes = race(16, index -> {
      try {
        stores.get(index % stores.size()).withdraw(command);
        return "cancelled";
      } catch (BusinessOperationStorageException failure) {
        assertThat(failure.code()).isEqualTo(OPERATION_STATE_STALE);
        return "stale";
      }
    });
    assertThat(outcomes.stream().filter("cancelled"::equals)).hasSize(1);
    assertThat(outcomes.stream().filter("stale"::equals)).hasSize(15);
    assertCounts(1, 3);
    assertLifecycleCounts(1, 1);
  }

  @Test
  void staleWholeStateAndTerminalCancellationCannotBecomeAnIdempotentSuccess() {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    var planner = new BusinessOperationTransitionPlanner();
    var token = planner.observe(SCOPE, initial);
    var wrong = new BusinessOperationTransitionPlanner.StateToken(token.operationId(), token.recoveryGeneration() + 1,
        token.lastSequence(), token.authorizationVersion(), token.executionVersion(), token.evidenceVersion(), token.snapshotHash());
    var context = requester(Instant.now().plusSeconds(60));
    assertCode(() -> lifecycle.withdraw(new BusinessOperationWithdrawal(SCOPE, id(initial), wrong, context)), OPERATION_STATE_STALE);
    JsonNode cancelled = lifecycle.withdraw(withdrawal(initial, Instant.now().plusSeconds(60)));
    assertCode(() -> lifecycle.withdraw(withdrawal(initial, Instant.now().plusSeconds(60))), OPERATION_STATE_STALE);
    assertCode(() -> lifecycle.withdraw(withdrawal(cancelled, Instant.now().plusSeconds(60))), OPERATION_NOT_WITHDRAWABLE);
    assertLifecycleCounts(1, 1);
  }

  @Test
  void wrongScopeAndAbsentOperationReturnNoCurrentStateOrCrossWorkspaceMutation() {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    for (var scope : List.of(new BusinessOperationScope(SCOPE.tenantId(), "other", SCOPE.requesterPrincipalId()),
        new BusinessOperationScope("other", SCOPE.workspaceId(), SCOPE.requesterPrincipalId()),
        new BusinessOperationScope(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:other"))) {
      assertThat(lifecycle.findCurrent(scope, id(initial))).isEmpty();
    }
    var other = new BusinessOperationScope(SCOPE.tenantId(), "other", SCOPE.requesterPrincipalId());
    var command = new BusinessOperationWithdrawal(other, id(initial), new BusinessOperationTransitionPlanner().observe(SCOPE, initial),
        requester(Instant.now().plusSeconds(60)));
    assertCode(() -> lifecycle.withdraw(command), OPERATION_NOT_FOUND);
    assertThat(lifecycle.findCurrent(SCOPE, UUID.randomUUID().toString())).isEmpty();
    assertCode(() -> lifecycle.withdraw(null), OPERATION_COMMAND_INVALID);
    assertLifecycleCounts(1, 0);
  }

  @Test
  void expiredAuthorityAndFutureProjectionTimeCauseZeroWrites() {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    assertCode(() -> lifecycle.withdraw(withdrawal(initial, Instant.now().minusSeconds(10))), OPERATION_AUTHENTICATION_EXPIRED);
    ObjectNode future = operation();
    future.put("clientRequestKeyHash", "sha256:" + "c".repeat(64));
    Instant later = Instant.now().plusSeconds(3600);
    future.put("createdAt", later.toString());
    for (int index = 0; index < 3; index++) {
      ObjectNode fact = (ObjectNode) future.path("transitions").get(index);
      String time = later.plusMillis(index).toString();
      fact.put("at", time);
      ((ObjectNode) future.path(fact.path("dimension").asText())).put("updatedAt", time);
    }
    future.put("updatedAt", later.plusMillis(2).toString());
    repository.createOrGet(SCOPE, future);
    assertCode(() -> lifecycle.withdraw(withdrawal(future, Instant.now().plusSeconds(60))), OPERATION_CLOCK_REGRESSION);
    assertLifecycleCounts(2, 0);
  }

  @Test
  void authenticationDeadlineIsRecheckedAfterAnActualHeadRowLockWait() throws Exception {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    var executor = Executors.newSingleThreadExecutor();
    try (Connection held = source.getConnection()) {
      held.setAutoCommit(false);
      try (var statement = held.prepareStatement("SELECT * FROM tp_operation_lifecycle_heads WHERE operation_id = ? FOR UPDATE")) {
        statement.setString(1, id(initial));
        try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
      }
      // The authority check uses writer time, not the independent test-host clock. Start the
      // unchanged 800ms validity window only once the real holder owns the row lock.
      Instant deadline = writerTime(held).plusMillis(800);
      var command = withdrawal(initial, deadline);
      // Install the signal after acquiring our lock, so only the actual contender counts.
      source.lockAttempt = new CountDownLatch(1);
      var result = executor.submit(() -> {
        assertCode(() -> lifecycle.withdraw(command), OPERATION_AUTHENTICATION_EXPIRED);
      });
      assertThat(source.lockAttempt.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(writerTime(held)).as("contender reached the real row lock before authority expired").isBefore(deadline);
      assertThat(result.isDone()).as("contender cannot finish while the holder owns the row").isFalse();
      long stop = System.nanoTime() + Duration.ofSeconds(4).toNanos();
      while (writerTime(held).isBefore(deadline.plusMillis(150))) {
        if (System.nanoTime() > stop) throw new AssertionError("deadline wait exceeded bound");
        Thread.sleep(20);
      }
      assertThat(result.isDone()).as("genuine lock wait continues past the writer deadline").isFalse();
      held.rollback();
      result.get(10, TimeUnit.SECONDS);
    } finally {
      source.lockAttempt = null;
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertLifecycleCounts(1, 0);
  }

  private Instant writerTime(Connection connection) throws SQLException {
    String sql = database.getJdbcUrl().startsWith("jdbc:postgresql:")
        ? "SELECT clock_timestamp()" : "SELECT UTC_TIMESTAMP(6)";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(1);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        var value = rows.getTimestamp(1, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
        assertThat(value).isNotNull();
        assertThat(rows.next()).isFalse();
        return value.toInstant();
      }
    }
  }

  @Test
  void readersNeverCombineAnUncommittedFactWithTheOldOrNewHead() throws Exception {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    source.updateReached = new CountDownLatch(1);
    source.updateRelease = new CountDownLatch(1);
    source.fault = Fault.PAUSE_HEAD_UPDATE;
    var executor = Executors.newSingleThreadExecutor();
    try {
      var result = executor.submit(() -> lifecycle.withdraw(withdrawal(initial, Instant.now().plusSeconds(60))));
      assertThat(source.updateReached.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(lifecycle.findCurrent(SCOPE, id(initial))).contains(initial);
      source.updateRelease.countDown();
      JsonNode cancelled = result.get(10, TimeUnit.SECONDS);
      assertThat(lifecycle.findCurrent(SCOPE, id(initial)).map(CanonicalJson::canonicalize))
          .contains(CanonicalJson.canonicalize(cancelled));
    } finally {
      source.updateRelease.countDown();
      source.fault = Fault.NONE;
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void journalOrHeadWriteFailureRollsBackBothAndKeepsOriginalFacts() {
    for (Fault fault : List.of(Fault.LIFECYCLE_FACT, Fault.HEAD_UPDATE, Fault.ROLLBACK)) {
      ObjectNode initial = operation();
      repository.createOrGet(SCOPE, initial);
      var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
      source.fault = fault;
      assertCode(() -> lifecycle.withdraw(withdrawal(initial, Instant.now().plusSeconds(60))), OPERATION_STORAGE_UNAVAILABLE);
      source.fault = Fault.NONE;
      assertThat(lifecycle.findCurrent(SCOPE, id(initial))).contains(initial);
      assertLifecycleCounts(1, 0);
      try {
        execute("DELETE FROM tp_operation_lifecycle_heads");
        execute("DELETE FROM tp_business_operation_facts");
        execute("DELETE FROM tp_business_operations");
      } catch (SQLException failure) { throw new AssertionError("synthetic cleanup failed"); }
    }
  }

  @Test
  void commitOrCleanupAcknowledgementLossIsUnknownRegardlessOfActualCommit() throws SQLException {
    for (Fault fault : List.of(Fault.BEFORE_COMMIT, Fault.AFTER_COMMIT, Fault.CLOSE_AFTER_COMMIT)) {
      ObjectNode initial = operation();
      repository.createOrGet(SCOPE, initial);
      var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
      source.fault = fault;
      assertCode(() -> lifecycle.withdraw(withdrawal(initial, Instant.now().plusSeconds(60))), OPERATION_COMMIT_UNKNOWN);
      source.fault = Fault.NONE;
      JsonNode observed = lifecycle.findCurrent(SCOPE, id(initial)).orElseThrow();
      assertThat(observed.path("authorization").path("status").asText())
          .isEqualTo(fault == Fault.BEFORE_COMMIT ? "evaluating" : "cancelled");
      assertLifecycleCounts(1, fault == Fault.BEFORE_COMMIT ? 0 : 1);
      execute("DELETE FROM tp_operation_lifecycle_facts");
      execute("DELETE FROM tp_operation_lifecycle_heads");
      execute("DELETE FROM tp_business_operation_facts");
      execute("DELETE FROM tp_business_operations");
    }
  }

  @Test
  void currentHeadFactChainAndActorIntegrityAreValidatedWithoutTrustingDesiredLabels() throws SQLException {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    JsonNode cancelled = lifecycle.withdraw(withdrawal(initial, Instant.now().plusSeconds(60)));
    for (String change : List.of("snapshot_hash", "snapshot_json")) {
      execute("UPDATE tp_operation_lifecycle_heads SET " + change + " = '{}' ");
      assertCode(() -> lifecycle.findCurrent(SCOPE, id(initial)), OPERATION_STORAGE_CORRUPT);
      restoreHead(cancelled);
    }
    for (String field : List.of("fact_json", "actor_json", "previous_snapshot_hash", "result_snapshot_hash", "actor_hash")) {
      String original;
      try (Connection connection = source.getConnection(); var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT " + field + " FROM tp_operation_lifecycle_facts")) {
        assertThat(rows.next()).isTrue();
        original = rows.getString(1);
      }
      execute("UPDATE tp_operation_lifecycle_facts SET " + field + " = '{}' ");
      assertCode(() -> lifecycle.findCurrent(SCOPE, id(initial)), OPERATION_STORAGE_CORRUPT);
      try (Connection connection = source.getConnection(); var statement = connection.prepareStatement(
          "UPDATE tp_operation_lifecycle_facts SET " + field + " = ?")) {
        statement.setString(1, original); statement.executeUpdate();
      }
    }
    execute("DELETE FROM tp_operation_lifecycle_facts");
    assertCode(() -> lifecycle.findCurrent(SCOPE, id(initial)), OPERATION_STORAGE_CORRUPT);
    assertThat(repository.find(SCOPE, id(initial))).contains(initial);
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{0}/{1}")
  @org.junit.jupiter.params.provider.MethodSource("ambiguousStoredJsonCases")
  void ambiguousStoredJsonNeverBecomesAValidCurrentProjection(String location, String ambiguity) throws Exception {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    lifecycle.withdraw(withdrawal(initial, Instant.now().plusSeconds(60)));
    String table = switch (location) {
      case "original" -> "tp_business_operations";
      case "head" -> "tp_operation_lifecycle_heads";
      default -> "tp_operation_lifecycle_facts";
    };
    String column = switch (location) {
      case "fact" -> "fact_json";
      case "actor" -> "actor_json";
      default -> "snapshot_json";
    };
    String stored = readStoredJson(table, column, id(initial));
    writeStoredJson(table, column, id(initial), ambiguousJson(stored, ambiguity));
    assertCode(() -> lifecycle.findCurrent(SCOPE, id(initial)), OPERATION_STORAGE_CORRUPT);
    if (location.equals("original")) {
      assertCode(() -> repository.find(SCOPE, id(initial)), OPERATION_STORAGE_CORRUPT);
      assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_STORAGE_CORRUPT);
    } else {
      assertThat(repository.find(SCOPE, id(initial))).contains(initial);
    }
    assertCounts(1, 3);
    assertLifecycleCounts(1, 1);
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> ambiguousStoredJsonCases() {
    return java.util.stream.Stream.of("original", "head", "fact", "actor")
        .flatMap(location -> java.util.stream.Stream.of("trailing", "duplicate")
            .map(ambiguity -> org.junit.jupiter.params.provider.Arguments.of(location, ambiguity)));
  }

  @Test
  void structurallyValidApprovedHeadWithoutItsTrustedJournalNeverBecomesCurrentAuthority() throws SQLException {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var planner = new BusinessOperationTransitionPlanner();
    JsonNode proposal = planner.propose(SCOPE, initial, planner.observe(SCOPE, initial),
        BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION, "approved", "SYNTHETIC_SHAPE_ONLY",
        Instant.parse(initial.path("updatedAt").asText())).snapshot();
    restoreHead(proposal);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    assertCode(() -> lifecycle.findCurrent(SCOPE, id(initial)), OPERATION_STORAGE_CORRUPT);
    assertCode(() -> lifecycle.withdraw(withdrawal(initial, Instant.now().plusSeconds(60))), OPERATION_STORAGE_CORRUPT);
    assertLifecycleCounts(1, 0);
    assertThat(repository.find(SCOPE, id(initial))).contains(initial);
  }

  @Test
  void actorBindingCannotBeChangedEvenWithARecomputedActorHash() throws SQLException {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    var command = withdrawal(initial, Instant.now().plusSeconds(60));
    lifecycle.withdraw(command);
    for (String field : List.of("scopeHash", "principalId", "authenticationMethod", "credentialFingerprint", "authorityExpiresAt", "extra")) {
      ObjectNode actor = command.actor();
      actor.put(field, field.equals("authorityExpiresAt") ? initial.path("createdAt").asText() : "invalid value");
      try (Connection connection = source.getConnection(); var statement = connection.prepareStatement(
          "UPDATE tp_operation_lifecycle_facts SET actor_hash = ?, actor_json = ?")) {
        statement.setString(1, CanonicalJson.sha256(actor));
        statement.setString(2, CanonicalJson.canonicalize(actor));
        statement.executeUpdate();
      }
      assertCode(() -> lifecycle.findCurrent(SCOPE, id(initial)), OPERATION_STORAGE_CORRUPT);
    }
  }

  @Test
  void columnScopedRuntimeGrantAllowsHeadLockButNoRegistrationFactOrIdentityRewrite() throws SQLException {
    ObjectNode initial = operation();
    var registrar = new JdbcBusinessOperationRepository(runtimeSource);
    registrar.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(runtimeSource);
    lifecycle.withdraw(withdrawal(initial, Instant.now().plusSeconds(60)));
    try (Connection connection = runtimeSource.getConnection(); var statement = connection.createStatement()) {
      for (String sql : List.of("UPDATE tp_business_operations SET snapshot_json = '{}'",
          "UPDATE tp_business_operation_facts SET fact_json = '{}'",
          "UPDATE tp_operation_lifecycle_facts SET fact_json = '{}'",
          "UPDATE tp_operation_lifecycle_heads SET operation_id = operation_id",
          "DELETE FROM tp_operation_lifecycle_facts", "DELETE FROM tp_operation_lifecycle_heads",
          "CREATE TABLE unauthorized_table (id INTEGER)")) {
        assertThatThrownBy(() -> statement.executeUpdate(sql)).isInstanceOf(SQLException.class);
      }
    }
    assertCounts(1, 3);
    assertLifecycleCounts(1, 1);
  }

  @Test
  void explicitV1UpgradePreservesPopulatedOriginalRowsAndAddsNoAuthority() throws SQLException {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    makeV1Fixture();
    try {
      assertCode(() -> new JdbcBusinessOperationRepository(source), OPERATION_SCHEMA_NOT_READY);
      assertCode(() -> new JdbcBusinessOperationLifecycleRepository(source), OPERATION_SCHEMA_NOT_READY);
      assertThat(tableCount()).isEqualTo(3);
      OperationDatabase.upgradeFromV1(source);
      assertThat(new JdbcBusinessOperationRepository(source).find(SCOPE, id(initial))).contains(initial);
      assertThat(new JdbcBusinessOperationLifecycleRepository(source).findCurrent(SCOPE, id(initial))).contains(initial);
      assertThat(repository.createOrGet(SCOPE, operation()).operation()).isEqualTo(initial);
      assertLifecycleCounts(1, 0);
      assertCounts(1, 3);
      assertCode(() -> OperationDatabase.upgradeFromV1(source), OPERATION_SCHEMA_NOT_READY);
    } finally {
      if (tableCount() == 3) OperationDatabase.upgradeFromV1(source);
    }
  }

  @Test
  void upgradeRefusesPartialObjectsAndDoesNotRepairOrAdoptThem() throws SQLException {
    makeV1Fixture();
    execute("CREATE TABLE tp_operation_lifecycle_heads (wrong_column INTEGER)");
    try {
      assertCode(() -> OperationDatabase.upgradeFromV1(source), OPERATION_SCHEMA_NOT_READY);
      assertCode(() -> new JdbcBusinessOperationLifecycleRepository(source), OPERATION_SCHEMA_NOT_READY);
      assertThat(tableCount()).isEqualTo(4);
    } finally {
      execute("DROP TABLE tp_operation_lifecycle_heads");
      OperationDatabase.upgradeFromV1(source);
    }
  }

  @Test
  void upgradeRejectsCorruptPopulatedPredecessorBeforeAnyDdl() throws SQLException {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    makeV1Fixture();
    execute("UPDATE tp_business_operations SET snapshot_hash = 'sha256:" + "f".repeat(64) + "'");
    try {
      assertCode(() -> OperationDatabase.upgradeFromV1(source), OPERATION_SCHEMA_NOT_READY);
      assertThat(tableCount()).isEqualTo(3);
    } finally {
      execute("UPDATE tp_business_operations SET snapshot_hash = '" + CanonicalJson.sha256(initial) + "'");
      OperationDatabase.upgradeFromV1(source);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"trailing", "duplicate"})
  void upgradeRejectsAmbiguousPredecessorJsonBeforeAnyDdl(String ambiguity) throws Exception {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    makeV1Fixture();
    String original = readStoredJson("tp_business_operations", "snapshot_json", id(initial));
    try {
      writeStoredJson("tp_business_operations", "snapshot_json", id(initial), ambiguousJson(original, ambiguity));
      assertCode(() -> OperationDatabase.upgradeFromV1(source), OPERATION_SCHEMA_NOT_READY);
      assertThat(tableCount()).isEqualTo(3);
    } finally {
      writeStoredJson("tp_business_operations", "snapshot_json", id(initial), original);
      if (tableCount() == 3) OperationDatabase.upgradeFromV1(source);
    }
  }

  @Test
  void upgradeRejectsChangedMigrationChecksumWithoutRepair() throws SQLException {
    makeV1Fixture();
    int checksum;
    try (Connection connection = source.getConnection(); var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT checksum FROM tp_operation_schema_history WHERE version = '1'")) {
      assertThat(rows.next()).isTrue(); checksum = rows.getInt(1);
    }
    execute("UPDATE tp_operation_schema_history SET checksum = 12345 WHERE version = '1'");
    try {
      assertCode(() -> OperationDatabase.upgradeFromV1(source), OPERATION_SCHEMA_NOT_READY);
      assertThat(tableCount()).isEqualTo(3);
    } finally {
      execute("UPDATE tp_operation_schema_history SET checksum = " + checksum + " WHERE version = '1'");
      OperationDatabase.upgradeFromV1(source);
    }
  }

  @Test
  void registrationHeadFailureCannotLeaveAnUnanchoredRegistration() {
    source.fault = Fault.HEAD_INSERT;
    assertCode(() -> repository.createOrGet(SCOPE, operation()), OPERATION_STORAGE_UNAVAILABLE);
    source.fault = Fault.NONE;
    assertCounts(0, 0);
    assertLifecycleCounts(0, 0);
  }

  @Test
  void rejectedCommandPlusCloseFailureHasNoSuppressedDriverDiagnostic() {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    var t = new BusinessOperationTransitionPlanner().observe(SCOPE, initial);
    var wrong = new BusinessOperationTransitionPlanner.StateToken(t.operationId(), t.recoveryGeneration() + 1,
        t.lastSequence(), t.authorizationVersion(), t.executionVersion(), t.evidenceVersion(), t.snapshotHash());
    var command = new BusinessOperationWithdrawal(SCOPE, id(initial), wrong, requester(Instant.now().plusSeconds(60)));
    source.fault = Fault.CLOSE_ALWAYS;
    try {
      assertSafeFailure(() -> lifecycle.withdraw(command), OPERATION_STATE_STALE);
    } finally { source.fault = Fault.NONE; }
    assertLifecycleCounts(1, 0);
  }

  @Test
  void corruptCurrentReadPlusCloseFailureHasNoSuppressedDriverDiagnostic() throws SQLException {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    var lifecycle = new JdbcBusinessOperationLifecycleRepository(source);
    execute("UPDATE tp_operation_lifecycle_heads SET snapshot_hash = '{}'");
    source.fault = Fault.CLOSE_ALWAYS;
    try {
      assertSafeFailure(() -> lifecycle.findCurrent(SCOPE, id(initial)), OPERATION_STORAGE_CORRUPT);
    } finally { source.fault = Fault.NONE; }
  }

  @Test
  void corruptRegistrationReadPlusCloseFailureHasNoSuppressedDriverDiagnostic() throws SQLException {
    ObjectNode initial = operation();
    repository.createOrGet(SCOPE, initial);
    execute("UPDATE tp_business_operations SET snapshot_hash = '{}'");
    source.fault = Fault.CLOSE_ALWAYS;
    try {
      assertSafeFailure(() -> repository.find(SCOPE, id(initial)), OPERATION_STORAGE_CORRUPT);
    } finally { source.fault = Fault.NONE; }
  }

  private static void assertSafeFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
      BusinessOperationStorageException.Code code) {
    assertThatThrownBy(call).isInstanceOfSatisfying(BusinessOperationStorageException.class, failure -> {
      assertThat(failure.code()).isEqualTo(code);
      assertThat(failure.getSuppressed()).isEmpty();
    }).hasMessage(code.name()).hasNoCause();
  }

  @Test
  void runtimeDetectsMissingLifecycleUniquenessAndNeverRepairsIt() throws SQLException {
    boolean postgres = database.getJdbcUrl().startsWith("jdbc:postgresql:");
    // InnoDB requires a supporting child-FK index even while this synthetic PK is absent.
    if (!postgres) execute("CREATE INDEX synthetic_fk_support ON tp_operation_lifecycle_facts (operation_id)");
    execute(postgres ? "ALTER TABLE tp_operation_lifecycle_facts DROP CONSTRAINT tp_operation_lifecycle_facts_pkey"
        : "ALTER TABLE tp_operation_lifecycle_facts DROP PRIMARY KEY");
    try {
      assertCode(() -> new JdbcBusinessOperationLifecycleRepository(source), OPERATION_SCHEMA_NOT_READY);
    } finally {
      execute("ALTER TABLE tp_operation_lifecycle_facts ADD PRIMARY KEY (operation_id, sequence_number)");
      if (!postgres) execute("DROP INDEX synthetic_fk_support ON tp_operation_lifecycle_facts");
    }
    OperationDatabase.validateExisting(source);
  }

  private static String ambiguousJson(String original, String ambiguity) throws java.io.IOException {
    if (ambiguity.equals("trailing")) return original + " false";
    String field = JsonSupport.MAPPER.readTree(original).fieldNames().next();
    return "{\"" + field + "\":null," + original.substring(1);
  }

  private String readStoredJson(String table, String column, String id) throws SQLException {
    // Identifiers are fixed test-source cases, never external input.
    try (Connection connection = source.getConnection();
        var statement = connection.prepareStatement("SELECT " + column + " FROM " + table + " WHERE operation_id = ?")) {
      statement.setString(1, id);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        String value = rows.getString(1);
        assertThat(rows.next()).isFalse();
        return value;
      }
    }
  }

  private void writeStoredJson(String table, String column, String id, String value) throws SQLException {
    try (Connection connection = source.getConnection();
        var statement = connection.prepareStatement("UPDATE " + table + " SET " + column + " = ? WHERE operation_id = ?")) {
      statement.setString(1, value);
      statement.setString(2, id);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private void makeV1Fixture() throws SQLException {
    // Only these disposable test-container tables are removed; immutable V1 bytes/data stay intact.
    execute("DROP TABLE tp_operation_lifecycle_facts");
    execute("DROP TABLE tp_operation_lifecycle_heads");
    execute("DELETE FROM tp_operation_schema_history WHERE version = '2'");
  }

  private int tableCount() throws SQLException {
    try (Connection connection = source.getConnection(); var rows = connection.getMetaData().getTables(
        connection.getCatalog(), database.getJdbcUrl().startsWith("jdbc:postgresql:") ? "public" : null,
        "%", new String[] {"TABLE"})) {
      int count = 0;
      while (rows.next()) count++;
      return count;
    }
  }

  private void restoreHead(JsonNode snapshot) throws SQLException {
    try (Connection connection = source.getConnection(); var statement = connection.prepareStatement(
        "UPDATE tp_operation_lifecycle_heads SET snapshot_hash = ?, snapshot_json = ?")) {
      statement.setString(1, CanonicalJson.sha256(snapshot));
      statement.setString(2, CanonicalJson.canonicalize(snapshot));
      statement.executeUpdate();
    }
  }

  private static String id(JsonNode operation) { return operation.path("operationId").asText(); }

  private static TrustedRequesterContext requester(Instant expires) {
    return new TrustedRequesterContext(new TenantPartition(SCOPE.tenantId()), SCOPE.requesterPrincipalId(),
        true, Set.of(BusinessOperationWithdrawal.ROLE), Set.of(BusinessOperationWithdrawal.SCOPE),
        "synthetic-test", "sha256:" + "b".repeat(64), expires);
  }

  private static BusinessOperationWithdrawal withdrawal(JsonNode before, Instant expires) {
    return new BusinessOperationWithdrawal(SCOPE, id(before), new BusinessOperationTransitionPlanner().observe(SCOPE, before), requester(expires));
  }

  private void assertLifecycleCounts(int heads, int facts) {
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      for (var expected : java.util.Map.of("tp_operation_lifecycle_heads", heads, "tp_operation_lifecycle_facts", facts).entrySet()) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + expected.getKey())) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(expected.getValue());
        }
      }
    } catch (SQLException failure) { throw new AssertionError("synthetic count query failed"); }
  }

  private void provisionRuntimeUser() {
    boolean postgres = database.getJdbcUrl().startsWith("jdbc:postgresql:");
    String password = UUID.randomUUID().toString();
    TestSource owner = postgres ? source : new TestSource(database.getJdbcUrl(), "root", database.getPassword());
    try (Connection connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      if (postgres) {
        statement.execute("CREATE ROLE tp_operation_runtime LOGIN PASSWORD '" + password + "'");
        statement.execute("GRANT USAGE ON SCHEMA public TO tp_operation_runtime");
        statement.execute("GRANT SELECT, INSERT ON tp_business_operations, tp_business_operation_facts TO tp_operation_runtime");
        statement.execute("GRANT SELECT, INSERT ON tp_operation_lifecycle_heads, tp_operation_lifecycle_facts TO tp_operation_runtime");
        statement.execute("GRANT UPDATE (snapshot_hash, snapshot_json) ON tp_operation_lifecycle_heads TO tp_operation_runtime");
        statement.execute("GRANT SELECT ON tp_operation_schema_history TO tp_operation_runtime");
      } else {
        statement.execute("CREATE USER 'tp_operation_runtime'@'%' IDENTIFIED BY '" + password + "'");
        String databaseName = database.getDatabaseName();
        for (String table : List.of("tp_business_operations", "tp_business_operation_facts",
            "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts")) {
          statement.execute("GRANT SELECT, INSERT ON `" + databaseName + "`." + table + " TO 'tp_operation_runtime'@'%'");
        }
        statement.execute("GRANT UPDATE (snapshot_hash, snapshot_json) ON `" + databaseName
            + "`.tp_operation_lifecycle_heads TO 'tp_operation_runtime'@'%'");
        statement.execute("GRANT SELECT ON `" + databaseName + "`.tp_operation_schema_history TO 'tp_operation_runtime'@'%'");
      }
      runtimeSource = new TestSource(database.getJdbcUrl(), "tp_operation_runtime", password);
    } catch (SQLException failure) {
      throw new AssertionError("synthetic runtime user provisioning failed");
    }
  }

  private void grantLifecycleRuntime() throws SQLException {
    boolean postgres = database.getJdbcUrl().startsWith("jdbc:postgresql:");
    TestSource owner = postgres ? source : new TestSource(database.getJdbcUrl(), "root", database.getPassword());
    try (Connection connection = owner.getConnection(); var statement = connection.createStatement()) {
      if (postgres) {
        statement.execute("GRANT SELECT, INSERT ON tp_operation_lifecycle_heads, tp_operation_lifecycle_facts TO tp_operation_runtime");
        statement.execute("GRANT UPDATE (snapshot_hash, snapshot_json) ON tp_operation_lifecycle_heads TO tp_operation_runtime");
      } else {
        String name = database.getDatabaseName();
        for (String table : List.of("tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts")) {
          statement.execute("GRANT SELECT, INSERT ON `" + name + "`." + table + " TO 'tp_operation_runtime'@'%'");
        }
        statement.execute("GRANT UPDATE (snapshot_hash, snapshot_json) ON `" + name
            + "`.tp_operation_lifecycle_heads TO 'tp_operation_runtime'@'%'");
      }
    }
  }

  private <T> List<T> race(int count, IndexedCall<T> call) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(count);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<T>>();
      for (int index = 0; index < count; index++) {
        final int task = index;
        futures.add(executor.submit(() -> {
          if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("race start timed out");
          return call.run(task);
        }));
      }
      start.countDown();
      var results = new ArrayList<T>();
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      for (var future : futures) results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      return results;
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @FunctionalInterface
  private interface IndexedCall<T> {
    T run(int index) throws Exception;
  }

  private static ObjectNode operation() {
    return operation(SCOPE);
  }

  private static ObjectNode operation(BusinessOperationScope scope) {
    ObjectNode operation = (ObjectNode) JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot"))
        .resolve("contracts/vectors/business-operation-v1.json")).path("operation").deepCopy();
    operation.put("operationId", UUID.randomUUID().toString());
    operation.set("trustedContext", scope.trustedContext());
    ObjectNode subject = (ObjectNode) operation.path("canonicalAction").path("identity").path("subject");
    subject.put("id", scope.requesterPrincipalId());
    subject.put("organizationId", scope.tenantId());
    // Keep all synthetic evidence/lineage references coherent with the trusted requester.
    ObjectNode evidence = (ObjectNode) operation.path("canonicalAction").path("identity").path("evidence").get(0);
    evidence.put("subjectId", scope.requesterPrincipalId());
    ObjectNode lineage = (ObjectNode) operation.path("canonicalAction").path("lineage");
    lineage.put("rootPrincipalId", scope.requesterPrincipalId());
    ((ObjectNode) lineage.path("hops").get(0)).put("subjectId", scope.requesterPrincipalId());
    ((ArrayNode) operation.path("transitions")).remove(3);
    ObjectNode authorization = (ObjectNode) operation.path("authorization");
    authorization.put("status", "evaluating");
    authorization.put("version", 1);
    authorization.put("updatedAt", operation.path("createdAt").asText());
    operation.put("updatedAt", operation.path("transitions").get(2).path("at").asText());
    rehash(operation);
    return operation;
  }

  private static void rehash(ObjectNode operation) {
    operation.put("canonicalActionHash", CanonicalJson.sha256(operation.path("canonicalAction")));
    operation.put("semanticPayloadDigest", CanonicalJson.sha256(operation.path("canonicalAction").path("action")));
    operation.put("resourceBindingHash", CanonicalJson.sha256(operation.path("resourceBinding")));
    operation.put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(
        operation.path("operationId").asText(), operation.path("adapterId").asText(), operation.path("recoveryGeneration").asLong()));
  }

  private void execute(String sql) throws SQLException {
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(sql);
    }
  }

  private void assertCounts(int operations, int facts) {
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_business_operations")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(operations);
      }
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM tp_business_operation_facts")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(facts);
      }
    } catch (SQLException failure) {
      throw new AssertionError("synthetic count query failed");
    }
  }

  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      BusinessOperationStorageException.Code code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationStorageException.class,
        failure -> assertThat(failure.code()).isEqualTo(code));
  }

  private enum Fault { NONE, SECOND_FACT, BEFORE_COMMIT, AFTER_COMMIT, CLOSE_AFTER_COMMIT, NOT_A_DUPLICATE, ROLLBACK,
    UNKNOWN_ENGINE, READ_ONLY, LIFECYCLE_FACT, HEAD_UPDATE, PAUSE_HEAD_UPDATE, HEAD_INSERT, CLOSE_ALWAYS }

  private static final class TestSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;
    private volatile Fault fault = Fault.NONE;
    private volatile CountDownLatch lockAttempt;
    private CountDownLatch updateReached;
    private CountDownLatch updateRelease;
    private final java.util.Set<String> sqlCategories = java.util.concurrent.ConcurrentHashMap.newKeySet();

    TestSource(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection delegate = DriverManager.getConnection(url, user, password);
      AtomicBoolean committed = new AtomicBoolean();
      AtomicInteger facts = new AtomicInteger();
      return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
          (proxy, method, args) -> {
            if (method.getName().equals("commit")) {
              if (fault == Fault.BEFORE_COMMIT) throw synthetic("08006", 0);
              delegate.commit();
              committed.set(true);
              if (fault == Fault.AFTER_COMMIT) throw synthetic("08006", 0);
              return null;
            }
            if (method.getName().equals("rollback") && fault == Fault.ROLLBACK) {
              delegate.rollback();
              throw synthetic("08006", 0);
            }
            if (method.getName().equals("close") && (fault == Fault.CLOSE_ALWAYS
                || committed.get() && fault == Fault.CLOSE_AFTER_COMMIT)) {
              delegate.close();
              throw synthetic("08006", 0);
            }
            Object result = invoke(method, delegate, args);
            if (method.getName().equals("getMetaData") && fault == Fault.UNKNOWN_ENGINE) {
              return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.sql.DatabaseMetaData.class},
                  (ignored, metadataMethod, metadataArgs) -> metadataMethod.getName().equals("getDatabaseProductVersion")
                      ? "99.0.0" : invoke(metadataMethod, result, metadataArgs));
            }
            if (method.getName().equals("prepareStatement") && fault == Fault.READ_ONLY
                && args[0] instanceof String sql && (sql.contains("pg_is_in_recovery") || sql.contains("@@read_only"))) {
              return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                  (ignored, statementMethod, statementArgs) -> {
                    Object queryResult = invoke(statementMethod, result, statementArgs);
                    if (statementMethod.getName().equals("executeQuery")) {
                      return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.sql.ResultSet.class},
                          (ignoredRows, rowMethod, rowArgs) -> rowMethod.getName().equals("getBoolean")
                              && Integer.valueOf(1).equals(rowArgs[0]) ? true : invoke(rowMethod, queryResult, rowArgs));
                    }
                    return queryResult;
                  });
            }
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
              PreparedStatement statement = (PreparedStatement) result;
              return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                  (ignored, statementMethod, statementArgs) -> {
                    if (statementMethod.getName().equals("executeQuery") && sql.contains("FOR UPDATE") && lockAttempt != null) {
                      lockAttempt.countDown();
                    }
                    if (statementMethod.getName().equals("executeUpdate")) {
                      if ((fault == Fault.NOT_A_DUPLICATE || fault == Fault.ROLLBACK) && sql.stripLeading().startsWith("INSERT")) throw synthetic("23503", 1452);
                      if (fault == Fault.SECOND_FACT && sql.contains("tp_business_operation_facts") && facts.incrementAndGet() == 2) {
                        throw synthetic("HY000", 1644);
                      }
                      if (fault == Fault.LIFECYCLE_FACT && sql.contains("INSERT INTO tp_operation_lifecycle_facts")) throw synthetic("HY000", 1644);
                      if (fault == Fault.HEAD_INSERT && sql.contains("INSERT INTO tp_operation_lifecycle_heads")) throw synthetic("HY000", 1644);
                      if (sql.contains("UPDATE tp_operation_lifecycle_heads")) {
                        if (fault == Fault.HEAD_UPDATE) throw synthetic("HY000", 1644);
                        if (fault == Fault.PAUSE_HEAD_UPDATE) {
                          updateReached.countDown();
                          if (!updateRelease.await(10, TimeUnit.SECONDS)) throw synthetic("HY000", 1644);
                        }
                      }
                    }
                    return invoke(statementMethod, statement, statementArgs);
                  });
            }
            return result;
          });
    }

    private static SQLException synthetic(String state, int vendor) {
      return new SQLException("synthetic-sensitive-database-error", state, vendor);
    }

    private Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException failure) {
        if (failure.getCause() instanceof SQLException sql) {
          String state = sql.getSQLState();
          sqlCategories.add((state != null && state.matches("[A-Z0-9]{5}") ? state : "OTHER")
              + "/" + sql.getErrorCode());
        }
        throw failure.getCause();
      }
    }

    @Override public Connection getConnection(String username, String suppliedPassword) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
  }
}
