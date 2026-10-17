package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.EnforcingMcpDispatcher;
import io.tracepermit.adapters.remote.RemoteMcpResponse;
import io.tracepermit.application.DurableExecutionService;
import io.tracepermit.application.Ed25519EvidenceAnchorVerifier;
import io.tracepermit.application.EvidenceOutboxPublisher;
import io.tracepermit.application.ExecutionReconciliationWorker;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.ApprovalExecutionResult;
import io.tracepermit.application.HumanApprovalService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.ReconciliationObservation;
import io.tracepermit.application.TrustedReviewerContext;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.DurableClaimResult;
import io.tracepermit.execution.DurableClaimStatus;
import io.tracepermit.execution.DurableEvidenceRecord;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.EvidenceAnchorReceipt;
import io.tracepermit.execution.EvidenceOutboxEntry;
import io.tracepermit.execution.EvidenceIntegrityResult;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.ExecutionTransition;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.ReconciliationCandidate;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.CredentialFingerprint;
import io.tracepermit.identity.TokenSenderConstraint;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.simulator.StatefulTargetSimulator;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PostgresDurableEnforcementStateTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");
  private static final TenantPartition ACME = new TenantPartition("acme");
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine");
  private static final AtomicLong IDS = new AtomicLong();

  private DataSource dataSource;
  private PostgresDurableEnforcementState state;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @BeforeEach
  void resetDatabase() throws SQLException {
    dataSource = dataSource("public");
    state = repository(dataSource);
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          TRUNCATE tp_approval_policy_bindings,
                   tp_approval_action_snapshots, tp_approval_transitions, tp_approval_requests,
                   tp_evidence_anchors, tp_evidence_outbox,
                   tp_evidence_events, tp_evidence_streams,
                   tp_reconciliation_queue,
                   tp_execution_transitions, tp_execution_attempts,
                   tp_consumed_execution_permits
          """);
    }
  }

  @Test
  void thirtyTwoConcurrentClaimsAcrossRepositoryInstancesHaveExactlyOneWinner()
      throws Exception {
    List<PostgresDurableEnforcementState> replicas = List.of(
        repository(dataSource), repository(dataSource), repository(dataSource), repository(dataSource));
    DurableExecutionClaim claim = claim(ACME, "permit-shared", hash('a'), NOW);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<DurableClaimStatus>> tasks = java.util.stream.IntStream.range(0, 32)
        .mapToObj(index -> (Callable<DurableClaimStatus>) () -> {
          start.await();
          return replicas.get(index % replicas.size()).claim(claim).status();
        })
        .toList();

    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      long winners = 0;
      long replays = 0;
      for (var future : futures) {
        DurableClaimStatus result = future.get();
        if (result == DurableClaimStatus.STARTED) winners++;
        if (result == DurableClaimStatus.PERMIT_REPLAYED) replays++;
      }
      assertThat(winners).isEqualTo(1);
      assertThat(replays).isEqualTo(31);
    }
    assertCounts(1, 1, 1, 1);
  }

  @Test
  void replayAndEvidenceChainSurviveRepositoryRestart() {
    DurableExecutionClaim claim = claim(ACME, "permit-restart", hash('b'), NOW);
    DurableClaimResult first = state.claim(claim);
    state.complete(ACME, first.attempt().attemptId(), "succeeded", "TARGET_CONFIRMED_SUCCESS", NOW.plusSeconds(1));

    PostgresDurableEnforcementState restarted = repository(dataSource);
    assertThat(restarted.claim(claim).status()).isEqualTo(DurableClaimStatus.PERMIT_REPLAYED);
    List<DurableEvidenceRecord> evidence = restarted.readEvidence(ACME);
    assertThat(evidence).hasSize(2);
    assertValidChain(evidence);
  }

  @Test
  void tenantPartitionsDoNotCollideAndBodyCannotSelectStorageTenant() {
    TenantPartition other = new TenantPartition("other");
    assertThat(state.claim(claim(ACME, "permit-tenant", hash('c'), NOW)).started()).isTrue();
    assertThat(state.claim(claim(other, "permit-tenant", hash('c'), NOW)).started()).isTrue();

    assertThat(count("tp_consumed_execution_permits", "tenant_id = 'acme'")).isEqualTo(1);
    assertThat(count("tp_consumed_execution_permits", "tenant_id = 'other'")).isEqualTo(1);

    JsonNode event = allowEvent();
    ((ObjectNode) event.path("clientClaims")).put("tenantId", "other");
    DurableExecutionService service = service(state, action -> JsonSupport.MAPPER.createObjectNode().put("ok", true));
    ExecutionResult result = service.execute(new TrustedTenantContext(ACME), event);
    assertThat(result.outcome()).isEqualTo("succeeded");
    assertThat(count("tp_execution_attempts", "tenant_id = 'other'")).isEqualTo(1);
    assertThat(count("tp_execution_attempts", "tenant_id = 'acme'")).isEqualTo(2);
  }

  @Test
  void duplicateIdempotencyRollsBackSecondPermitAndDoesNotDispatchAgain() {
    String key = hash('d');
    assertThat(state.claim(claim(ACME, "permit-first", key, NOW)).started()).isTrue();
    DurableClaimResult duplicate = state.claim(claim(ACME, "permit-second", key, NOW.plusMillis(1)));
    assertThat(duplicate.status()).isEqualTo(DurableClaimStatus.IDEMPOTENCY_DUPLICATE);
    assertCounts(1, 1, 1, 1);

    AtomicInteger targetCalls = new AtomicInteger();
    DurableExecutionService service = service(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("safe", true);
    });
    JsonNode event = allowEvent();
    assertThat(service.execute(new TrustedTenantContext(ACME), event).outcome()).isEqualTo("succeeded");
    assertThat(service.execute(new TrustedTenantContext(ACME), event).outcome()).isEqualTo("succeeded");
    assertThat(targetCalls).hasValue(1);
  }

  @Test
  void simulatorRunsOnlyAfterTheClaimTransactionIsCommitted() {
    AtomicInteger visibleClaimsDuringTarget = new AtomicInteger();
    DurableExecutionService service = service(state, action -> {
      visibleClaimsDuringTarget.set((int) count("tp_consumed_execution_permits", "tenant_id = 'acme'"));
      return JsonSupport.MAPPER.createObjectNode().put("safe", true);
    });

    assertThat(service.execute(new TrustedTenantContext(ACME), allowEvent()).outcome())
        .isEqualTo("succeeded");
    assertThat(visibleClaimsDuringTarget).hasValue(1);
  }

  @Test
  void remoteDispatcherUsesTrustedTenantContextAndDurableRedactedEvidence() throws Exception {
    AtomicInteger ids = new AtomicInteger();
    DurableExecutionService durable = service(state, new StatefulTargetSimulator());
    EnforcingMcpDispatcher dispatcher =
        new EnforcingMcpDispatcher(
            durable::execute,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> String.format("00000000-0000-7000-8000-%012d", ids.incrementAndGet()),
            () -> String.format("%032x", ids.get()));
    AuthenticatedConnector connector =
        new AuthenticatedConnector(
            "https://identity.example.invalid#user:alice",
            "Alice Example",
            ACME.value(),
            ConnectorOrigin.CHATGPT_WEB,
            "chatgpt-connector",
            "ChatGPT Web",
            "https://identity.example.invalid",
            NOW,
            "oauth-access-token-jwt-rs256-v1",
            "oidc-key-001",
            new CredentialFingerprint(hash('a')),
            List.of(ConnectorScope.of("aws.logs.read")),
            NOW.plusSeconds(300),
            Optional.of(
                new TokenSenderConstraint(
                    TokenSenderConstraint.MTLS_X5T_S256_V1,
                    new CredentialFingerprint(hash('b')))));
    JsonNode call =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":19,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"aws.logs.filter_log_events\",\"arguments\":{"
                + "\"logGroupName\":\"/app/prod\",\"filterPattern\":\"ERROR\",\"limit\":20}}}");

    RemoteMcpResponse response = dispatcher.dispatch(call, connector);
    JsonNode content =
        JsonSupport.MAPPER.readTree(
            response.body().path("result").path("content").get(0).path("text").asText());

    assertThat(content.path("execution").path("status").asText()).isEqualTo("executed");
    assertThat(content.path("execution").path("result").path("matchCount").asInt()).isEqualTo(2);
    List<DurableEvidenceRecord> evidence = repository(dataSource).readEvidence(ACME);
    assertThat(evidence).hasSize(2);
    assertValidChain(evidence);
    assertThat(evidence.toString())
        .contains(hash('a'), hash('b'))
        .doesNotContain(
            "logGroupName", "filterPattern", "ERROR", "events", "Bearer", "x5t#S256");
    assertThat(count("tp_evidence_outbox", "tenant_id = 'acme'")).isEqualTo(2);
  }

  @Test
  void rollbackLeavesNoPartialClaimAttemptEvidenceOrOutbox() {
    AtomicInteger calls = new AtomicInteger();
    PostgresDurableEnforcementState broken = new PostgresDurableEnforcementState(
        dataSource,
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> switch (calls.incrementAndGet()) {
          case 1 -> "attempt-valid";
          case 2 -> "evidence-valid";
          default -> "invalid id with spaces";
        });
    assertThatThrownBy(() -> broken.claim(claim(ACME, "permit-rollback", hash('e'), NOW)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outbox ID");
    assertCounts(0, 0, 0, 0);
  }

  @Test
  void unknownReconciliationAppendsHistoryWithoutRewritingEarlierEvidence() {
    DurableClaimResult begun = state.claim(claim(ACME, "permit-unknown", hash('f'), NOW));
    state.complete(
        ACME,
        begun.attempt().attemptId(),
        "unknown",
        "TARGET_OUTCOME_UNCONFIRMED",
        NOW.plusSeconds(1));
    List<DurableEvidenceRecord> before = state.readEvidence(ACME);

    PostgresDurableEnforcementState restarted = repository(dataSource);
    ExecutionAttempt reconciled = restarted.reconcile(
        ACME,
        "simulator",
        hash('f'),
        "succeeded",
        "RECONCILIATION_CONFIRMED_SUCCESS",
        NOW.plusSeconds(2));
    List<DurableEvidenceRecord> after = restarted.readEvidence(ACME);

    assertThat(reconciled.status()).isEqualTo("succeeded");
    assertThat(reconciled.transitions()).extracting(item -> item.to())
        .containsExactly("executing", "unknown", "succeeded");
    assertThat(after).hasSize(3);
    assertThat(after.subList(0, 2)).isEqualTo(before);
    assertThat(after.get(2).payload().path("kind").asText()).isEqualTo("reconciliation.completed");
    assertValidChain(after);
    assertThat(count("tp_reconciliation_queue", "TRUE")).isZero();
  }

  @Test
  void reconciliationWorkersLeaseOnceAndAppendAConfirmedOutcomeWithoutRedispatch()
      throws Exception {
    DurableClaimResult begun = state.claim(claim(ACME, "permit-worker", hash('6'), NOW));
    state.complete(
        ACME,
        begun.attempt().attemptId(),
        "unknown",
        "TARGET_OUTCOME_UNCONFIRMED",
        NOW.plusSeconds(1));
    List<DurableEvidenceRecord> before = state.readEvidence(ACME);
    List<PostgresDurableEnforcementState> replicas =
        List.of(repository(dataSource), repository(dataSource), repository(dataSource));
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<List<ReconciliationCandidate>>> tasks =
        java.util.stream.IntStream.range(0, 32)
            .mapToObj(
                index ->
                    (Callable<List<ReconciliationCandidate>>)
                        () -> {
                          start.await();
                          return replicas
                              .get(index % replicas.size())
                              .claimUnknownBatch(
                                  ACME,
                                  "reconciler-" + index,
                                  1,
                                  NOW.plusSeconds(1),
                                  NOW.plusSeconds(31));
                        })
            .toList();
    List<ReconciliationCandidate> winners = new java.util.ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      for (var future : futures) winners.addAll(future.get());
    }
    assertThat(winners).hasSize(1);
    ReconciliationCandidate winner = winners.getFirst();
    assertThatThrownBy(
            () ->
                state.completeReconciliation(
                    winner,
                    "reconciler-stale",
                    "succeeded",
                    "RECONCILIATION_CONFIRMED_SUCCESS",
                    NOW.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);

    ExecutionAttempt result =
        state.completeReconciliation(
            winner,
            winner.leaseOwner(),
            "succeeded",
            "RECONCILIATION_CONFIRMED_SUCCESS",
            NOW.plusSeconds(2));
    assertThat(result.status()).isEqualTo("succeeded");
    assertThat(result.transitions()).extracting(ExecutionTransition::to)
        .containsExactly("executing", "unknown", "succeeded");
    assertThat(state.readEvidence(ACME).subList(0, before.size())).isEqualTo(before);
    assertThat(count("tp_reconciliation_queue", "TRUE")).isZero();
  }

  @Test
  void reconciliationWorkerDefersUnconfirmedLookupsAndRecoversAfterRestart() {
    DurableClaimResult begun = state.claim(claim(ACME, "permit-defer", hash('7'), NOW));
    state.complete(
        ACME,
        begun.attempt().attemptId(),
        "unknown",
        "TARGET_OUTCOME_UNCONFIRMED",
        NOW.plusSeconds(1));
    AtomicInteger lookups = new AtomicInteger();
    ExecutionReconciliationWorker first =
        new ExecutionReconciliationWorker(
            repository(dataSource),
            candidate -> {
              lookups.incrementAndGet();
              return new ReconciliationObservation("unknown", "TARGET_STILL_UNCONFIRMED");
            },
            Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
            "reconciler-first",
            Duration.ofSeconds(30),
            Duration.ofSeconds(10));
    assertThat(first.reconcileBatch(ACME, 10).deferred()).isEqualTo(1);
    assertThat(state.findByIdempotencyKey(ACME, "simulator", hash('7')).status())
        .isEqualTo("unknown");
    assertThat(state.claimUnknownBatch(
            ACME,
            "reconciler-early",
            1,
            NOW.plusSeconds(10),
            NOW.plusSeconds(40)))
        .isEmpty();

    ExecutionReconciliationWorker restarted =
        new ExecutionReconciliationWorker(
            repository(dataSource),
            candidate -> {
              lookups.incrementAndGet();
              return new ReconciliationObservation(
                  "failed", "RECONCILIATION_CONFIRMED_FAILURE");
            },
            Clock.fixed(NOW.plusSeconds(11), ZoneOffset.UTC),
            "reconciler-restarted",
            Duration.ofSeconds(30),
            Duration.ofSeconds(10));
    assertThat(restarted.reconcileBatch(ACME, 10).reconciled()).isEqualTo(1);
    assertThat(lookups).hasValue(2);
    assertThat(state.findByIdempotencyKey(ACME, "simulator", hash('7')).status())
        .isEqualTo("failed");
    assertThat(count("tp_reconciliation_queue", "TRUE")).isZero();
  }

  @Test
  void evidenceOutboxPublishesSignedReceiptsWithConcurrentLeaseExclusion() throws Exception {
    state.claim(claim(ACME, "permit-anchor", hash('4'), NOW));
    List<PostgresDurableEnforcementState> replicas =
        List.of(repository(dataSource), repository(dataSource), repository(dataSource));
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<List<EvidenceOutboxEntry>>> tasks =
        java.util.stream.IntStream.range(0, 32)
            .mapToObj(
                index ->
                    (Callable<List<EvidenceOutboxEntry>>)
                        () -> {
                          start.await();
                          return replicas
                              .get(index % replicas.size())
                              .claimEvidenceBatch(
                                  ACME,
                                  "publisher-" + index,
                                  1,
                                  NOW,
                                  NOW.plusSeconds(30));
                        })
            .toList();
    List<EvidenceOutboxEntry> winners = new java.util.ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      for (var future : futures) winners.addAll(future.get());
    }
    assertThat(winners).hasSize(1);

    EvidenceOutboxEntry first = winners.getFirst();
    state.releaseEvidenceClaim(
        first, first.leaseOwner(), "ANCHOR_RETRY_REQUIRED", NOW.plusSeconds(2));
    assertThat(state.claimEvidenceBatch(
            ACME, "publisher-final", 10, NOW.plusSeconds(1), NOW.plusSeconds(31)))
        .isEmpty();

    KeyPair anchor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Clock publicationClock = Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC);
    EvidenceOutboxPublisher publisher =
        new EvidenceOutboxPublisher(
            state,
            entry -> signedReceipt(entry, anchor, publicationClock.instant()),
            new Ed25519EvidenceAnchorVerifier(
                Map.of("external-audit:anchor-key-1", anchor.getPublic())),
            publicationClock,
            "publisher-final",
            Duration.ofSeconds(30),
            Duration.ofSeconds(10));
    assertThat(publisher.publishBatch(ACME, 10).published()).isEqualTo(1);

    List<EvidenceAnchorReceipt> anchors = state.readEvidenceAnchors(ACME);
    assertThat(anchors).hasSize(1);
    anchors.getFirst().requireBinding(firstWithLease(first, "publisher-final", NOW.plusSeconds(32), 2));
    new Ed25519EvidenceAnchorVerifier(
            Map.of("external-audit:anchor-key-1", anchor.getPublic()))
        .verify(anchors.getFirst());
    assertThat(state.claimEvidenceBatch(
            ACME, "publisher-late", 10, NOW.plusSeconds(3), NOW.plusSeconds(33)))
        .isEmpty();
    assertThat(count("tp_evidence_anchors", "tenant_id = 'acme'")).isEqualTo(1);
  }

  @Test
  void anchorStorageRejectsStaleWorkersMutationAndCrossTenantReads() throws Exception {
    state.claim(claim(ACME, "permit-anchor-guard", hash('5'), NOW));
    EvidenceOutboxEntry entry = state.claimEvidenceBatch(
            ACME, "publisher-owner", 1, NOW, NOW.plusSeconds(30))
        .getFirst();
    KeyPair anchor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    EvidenceAnchorReceipt receipt = signedReceipt(entry, anchor, NOW.plusSeconds(1));

    assertThatThrownBy(
            () ->
                state.markEvidencePublished(
                    entry, receipt, "publisher-other", NOW.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
    state.markEvidencePublished(entry, receipt, "publisher-owner", NOW.plusSeconds(1));
    assertThat(state.readEvidenceAnchors(new TenantPartition("other-tenant"))).isEmpty();
    try (Connection connection = dataSource.getConnection()) {
      assertThatThrownBy(
              () ->
                  execute(
                      connection,
                      "UPDATE tp_evidence_anchors SET provider = 'tampered' WHERE tenant_id = 'acme'"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(
              () ->
                  execute(
                      connection,
                      "DELETE FROM tp_evidence_anchors WHERE tenant_id = 'acme'"))
          .isInstanceOf(SQLException.class);
    }
    assertThat(allDurableRows())
        .doesNotContain("PRIVATE KEY", "Bearer ", "raw-argument", "target-result");
  }

  @Test
  void databaseConstraintsRejectMutationAndIllegalTransitions() throws SQLException {
    DurableClaimResult begun = state.claim(claim(ACME, "permit-constraints", hash('1'), NOW));
    String attemptId = begun.attempt().attemptId();
    try (Connection connection = dataSource.getConnection()) {
      assertThatThrownBy(() -> execute(connection,
          "UPDATE tp_execution_attempts SET status = 'succeeded' WHERE tenant_id = 'acme'"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(connection,
          "UPDATE tp_evidence_events SET event_hash = 'sha256:" + "0".repeat(64) + "' WHERE tenant_id = 'acme'"))
          .isInstanceOf(SQLException.class);
      String illegal = """
          INSERT INTO tp_execution_transitions
            (tenant_id, attempt_id, sequence, from_status, to_status, occurred_at, reason_code)
          VALUES ('acme', ?, 2, 'executing', 'executing', ?, 'ILLEGAL_TRANSITION')
          """;
      try (PreparedStatement statement = connection.prepareStatement(illegal)) {
        statement.setString(1, attemptId);
        statement.setObject(2, NOW.plusSeconds(1).atOffset(ZoneOffset.UTC));
        assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class);
      }
    }
    assertThat(state.findByIdempotencyKey(ACME, "simulator", hash('1')).status())
        .isEqualTo("executing");
  }

  @Test
  void storedRowsOmitRawArgumentsResultsTokensAndCredentials() {
    String argumentSecret = "raw-argument-MUST-NOT-PERSIST";
    String resultSecret = "raw-result-MUST-NOT-PERSIST";
    JsonNode event = allowEvent();
    ((ObjectNode) event.path("action").path("arguments")).put("filterPattern", argumentSecret);
    ((ObjectNode) event.path("clientClaims")).put("bearerToken", "bearer-MUST-NOT-PERSIST");
    DurableExecutionService service = service(state, action ->
        JsonSupport.MAPPER.createObjectNode().put("secretResult", resultSecret));

    assertThat(service.execute(new TrustedTenantContext(ACME), event).outcome()).isEqualTo("succeeded");
    String stored = allDurableRows();
    assertThat(stored)
        .doesNotContain(argumentSecret)
        .doesNotContain(resultSecret)
        .doesNotContain("bearer-MUST-NOT-PERSIST")
        .doesNotContain("arguments")
        .doesNotContain("secretResult");
    assertThat(stored).contains("sha256:", "TARGET_CONFIRMED_SUCCESS", "execution.claimed");
  }

  @Test
  void malformedMigrationAndIncompatibleSchemaFailStartup() throws Exception {
    DataSource malformed = isolatedSchema("malformed");
    Path migrations = Files.createTempDirectory("tracepermit-malformed-migration-");
    try {
      Files.writeString(migrations.resolve("V1__broken.sql"), "CREATE TABLE definitely_broken (");
      assertThatThrownBy(() -> PostgresDurableEnforcementState.migrate(
          malformed, "filesystem:" + migrations.toAbsolutePath()))
          .isInstanceOf(FlywayException.class);
    } finally {
      deleteRecursively(migrations);
    }

    DataSource incompatible = isolatedSchema("incompatible");
    PostgresDurableEnforcementState.migrate(
        incompatible, PostgresDurableEnforcementState.MIGRATION_LOCATION);
    try (Connection connection = incompatible.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("ALTER TABLE tp_enforcement_schema_guard DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check");
      statement.execute("UPDATE tp_enforcement_schema_guard SET contract_version = 9");
    }
    assertThatThrownBy(() -> new PostgresDurableEnforcementState(
        incompatible, Clock.fixed(NOW, ZoneOffset.UTC), PostgresDurableEnforcementStateTest::nextId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
  }

  @Test
  void simulatorAllowAndPolicyBlockBehaviorRemainSeparated() {
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    DurableExecutionService service = service(state, simulator);
    ExecutionResult allowed = service.execute(new TrustedTenantContext(ACME), allowEvent());
    assertThat(allowed.outcome()).isEqualTo("succeeded");
    assertThat(allowed.result().path("mode").asText()).isEqualTo("simulated");

    ExecutionResult approvalRequired = service.execute(
        new TrustedTenantContext(ACME),
        JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json")));
    assertThat(approvalRequired.outcome()).isEqualTo("require_approval");
    assertThat(state.readEvidence(ACME)).hasSize(2);
  }

  @Test
  void approvalLifecycleIsDurableRequestBoundAndDispatchesOnlyAfterConsumption() {
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = approvalService(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
    }, Clock.fixed(NOW, ZoneOffset.UTC), approvalPolicy());

    ApprovalExecutionResult pending = service.requestApproval(
        new TrustedTenantContext(ACME), approvalEvent());
    String requestId = pending.approval().binding().approvalRequestId();
    assertThat(pending.outcome()).isEqualTo("pending_approval");
    assertThat(targetCalls).hasValue(0);
    assertThat(count("tp_approval_requests", "status = 'pending'")).isEqualTo(1);
    assertThat(count("tp_consumed_execution_permits", "TRUE")).isZero();

    PostgresDurableEnforcementState restarted = repository(dataSource);
    assertThat(restarted.find(ACME, requestId).status()).isEqualTo("pending");
    HumanApprovalService afterRestart = approvalService(restarted, action -> {
      assertThat(restarted.find(ACME, requestId).status()).isEqualTo("consumed");
      assertThat(count("tp_consumed_execution_permits", "TRUE")).isEqualTo(1);
      assertThat(count("tp_execution_attempts", "status = 'executing'")).isEqualTo(1);
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
    }, Clock.fixed(NOW, ZoneOffset.UTC), approvalPolicy());
    assertThat(afterRestart.review(
        reviewer(ACME, "user:bob@example.com"), requestId, "approve", "CHANGE_REVIEWED").status())
        .isEqualTo("approved");

    ApprovalExecutionResult executed = afterRestart.executeApproved(
        new TrustedTenantContext(ACME), requestId, approvalEvent());
    assertThat(executed.outcome()).isEqualTo("succeeded");
    assertThat(executed.approval().status()).isEqualTo("consumed");
    assertThat(targetCalls).hasValue(1);
    assertThat(restarted.find(ACME, requestId).status()).isEqualTo("consumed");
    assertThat(count("tp_consumed_execution_permits", "TRUE")).isEqualTo(1);
    assertThat(count("tp_execution_attempts", "TRUE")).isEqualTo(1);
    assertValidChain(restarted.readEvidence(ACME));
  }

  @Test
  void denialUnauthorizedWrongTenantSelfApprovalAndExpiryFailClosed() {
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = approvalService(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.nullNode();
    }, Clock.fixed(NOW, ZoneOffset.UTC), approvalPolicy());
    ApprovalExecutionResult pending = service.requestApproval(
        new TrustedTenantContext(ACME), approvalEvent());
    String requestId = pending.approval().binding().approvalRequestId();

    TrustedReviewerContext unauthorized = new TrustedReviewerContext(
        ACME, "user:bob@example.com", "human", true, Set.of(), Set.of(), "oidc", hash('7'));
    assertThatThrownBy(() -> service.review(
        unauthorized, requestId, "approve", "CHANGE_REVIEWED"))
        .hasMessage("REVIEWER_NOT_AUTHORIZED");
    assertThatThrownBy(() -> service.review(
        reviewer(new TenantPartition("other"), "user:bob@example.com"),
        requestId, "approve", "CHANGE_REVIEWED"))
        .hasMessage("APPROVAL_REQUEST_NOT_FOUND");
    assertThatThrownBy(() -> service.review(
        reviewer(ACME, "user:alice@example.com"), requestId, "approve", "CHANGE_REVIEWED"))
        .hasMessage("SELF_APPROVAL_DENIED");

    assertThat(service.review(
        reviewer(ACME, "user:bob@example.com"), requestId, "deny", "CHANGE_REJECTED").status())
        .isEqualTo("denied");
    assertThatThrownBy(() -> service.executeApproved(
        new TrustedTenantContext(ACME), requestId, approvalEvent()))
        .hasMessage("APPROVAL_NOT_EXECUTABLE");
    assertThat(targetCalls).hasValue(0);

    JsonNode secondEvent = approvalEvent();
    ((ObjectNode) secondEvent).put("eventId", "018f47a2-f5ce-7a4b-8f54-80e5d3eaf099");
    HumanApprovalService requestService = approvalService(
        state, action -> JsonSupport.MAPPER.nullNode(), Clock.fixed(NOW, ZoneOffset.UTC), approvalPolicy());
    String expiredId = requestService.requestApproval(
        new TrustedTenantContext(ACME), secondEvent).approval().binding().approvalRequestId();
    HumanApprovalService later = approvalService(
        state,
        action -> {
          targetCalls.incrementAndGet();
          return JsonSupport.MAPPER.nullNode();
        },
        Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC),
        approvalPolicy());
    assertThatThrownBy(() -> later.review(
        reviewer(ACME, "user:bob@example.com"), expiredId, "approve", "CHANGE_REVIEWED"))
        .hasMessage("APPROVAL_EXPIRED");
    assertThat(state.find(ACME, expiredId).status()).isEqualTo("expired");
    assertThat(targetCalls).hasValue(0);

    JsonNode approvedThenExpired = approvalEvent();
    ((ObjectNode) approvedThenExpired).put("eventId", "018f47a2-f5ce-7a4b-8f54-80e5d3eaf097");
    String approvedThenExpiredId = requestService.requestApproval(
        new TrustedTenantContext(ACME), approvedThenExpired).approval().binding().approvalRequestId();
    requestService.review(
        reviewer(ACME, "user:bob@example.com"), approvedThenExpiredId,
        "approve", "CHANGE_REVIEWED");
    assertThatThrownBy(() -> later.executeApproved(
        new TrustedTenantContext(ACME), approvedThenExpiredId, approvedThenExpired))
        .hasMessage("APPROVAL_EXPIRED");
    assertThat(state.find(ACME, approvedThenExpiredId).status()).isEqualTo("expired");
    assertThat(targetCalls).hasValue(0);
  }

  @Test
  void everyMaterialMutationAndPolicyVersionChangeInvalidatesApproval() {
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = approvalService(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.nullNode();
    }, Clock.fixed(NOW, ZoneOffset.UTC), approvalPolicy());
    String requestId = service.requestApproval(
        new TrustedTenantContext(ACME), approvalEvent()).approval().binding().approvalRequestId();
    service.review(reviewer(ACME, "user:bob@example.com"), requestId, "approve", "CHANGE_REVIEWED");

    JsonNode argumentsChanged = approvalEvent();
    ((ObjectNode) argumentsChanged.path("action").path("arguments")).put("port", 8443);
    assertBindingMismatch(service, requestId, argumentsChanged);
    JsonNode resourceChanged = approvalEvent();
    ((ObjectNode) resourceChanged.path("action").path("target")).put("accountId", "simulated-999");
    assertBindingMismatch(service, requestId, resourceChanged);
    JsonNode actorChanged = approvalEvent();
    ((ObjectNode) actorChanged.path("identity").path("actor")).put("displayName", "Changed actor");
    assertBindingMismatch(service, requestId, actorChanged);
    JsonNode subjectChanged = approvalEvent();
    ((ObjectNode) subjectChanged.path("identity").path("subject")).put("id", "user:mallory@example.com");
    ((ObjectNode) subjectChanged.path("identity").path("evidence").get(0)).put(
        "subjectId", "user:mallory@example.com");
    ((ObjectNode) subjectChanged.path("lineage")).put("rootPrincipalId", "user:mallory@example.com");
    ((ObjectNode) subjectChanged.path("lineage").path("hops").get(0)).put(
        "subjectId", "user:mallory@example.com");
    ((ObjectNode) subjectChanged.path("lineage").path("delegations").get(0)).put(
        "actorId", "user:mallory@example.com");
    assertBindingMismatch(service, requestId, subjectChanged);

    JsonNode changedPolicy = approvalPolicy();
    ((ObjectNode) changedPolicy).put("id", "pol-aws-web-002");
    ((ObjectNode) changedPolicy).put("version", 3);
    HumanApprovalService changedPolicyService = approvalService(
        state,
        action -> {
          targetCalls.incrementAndGet();
          return JsonSupport.MAPPER.nullNode();
        },
        Clock.fixed(NOW, ZoneOffset.UTC),
        changedPolicy);
    assertBindingMismatch(changedPolicyService, requestId, approvalEvent());
    assertThat(targetCalls).hasValue(0);
    assertThat(state.find(ACME, requestId).status()).isEqualTo("approved");
  }

  @Test
  void thirtyTwoConcurrentApprovalConsumersProduceOneExecutionWinner() throws Exception {
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = approvalService(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("ok", true);
    }, Clock.fixed(NOW, ZoneOffset.UTC), approvalPolicy());
    String requestId = service.requestApproval(
        new TrustedTenantContext(ACME), approvalEvent()).approval().binding().approvalRequestId();
    service.review(reviewer(ACME, "user:bob@example.com"), requestId, "approve", "CHANGE_REVIEWED");
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> tasks = java.util.stream.IntStream.range(0, 32)
        .mapToObj(ignored -> (Callable<Boolean>) () -> {
          start.await();
          try {
            return "succeeded".equals(service.executeApproved(
                new TrustedTenantContext(ACME), requestId, approvalEvent()).outcome());
          } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).isIn("APPROVAL_REPLAYED", "APPROVAL_NOT_EXECUTABLE");
            return false;
          }
        }).toList();
    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      int winners = 0;
      for (var future : futures) if (future.get()) winners++;
      assertThat(winners).isEqualTo(1);
    }
    assertThat(targetCalls).hasValue(1);
    assertThat(count("tp_approval_transitions", "to_status = 'consumed'")).isEqualTo(1);
    assertCounts(1, 1, 5, 5);
  }

  @Test
  void concurrentApproveAndDenyCannotCreateContradictoryFinalStates() throws Exception {
    HumanApprovalService service = approvalService(
        state, action -> JsonSupport.MAPPER.nullNode(), Clock.fixed(NOW, ZoneOffset.UTC), approvalPolicy());
    String requestId = service.requestApproval(
        new TrustedTenantContext(ACME), approvalEvent()).approval().binding().approvalRequestId();
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> tasks = java.util.stream.IntStream.range(0, 32)
        .mapToObj(index -> (Callable<Boolean>) () -> {
          start.await();
          try {
            service.review(
                reviewer(ACME, "user:reviewer-" + index + "@example.com"),
                requestId,
                index % 2 == 0 ? "approve" : "deny",
                index % 2 == 0 ? "CHANGE_REVIEWED" : "CHANGE_REJECTED");
            return true;
          } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("APPROVAL_ALREADY_DECIDED");
            return false;
          }
        }).toList();
    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      int winners = 0;
      for (var future : futures) if (future.get()) winners++;
      assertThat(winners).isEqualTo(1);
    }
    assertThat(state.find(ACME, requestId).status()).isIn("approved", "denied");
    assertThat(count("tp_approval_transitions", "to_status IN ('approved', 'denied')"))
        .isEqualTo(1);
  }

  @Test
  void databaseFailureBeforeApprovalCreationOrDecisionFailsClosed() throws SQLException {
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = approvalService(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.nullNode();
    }, Clock.fixed(NOW, ZoneOffset.UTC), approvalPolicy());

    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE FUNCTION tp_test_reject_approval_request() RETURNS TRIGGER AS $$
          BEGIN RAISE EXCEPTION 'test approval request failure'; END;
          $$ LANGUAGE plpgsql
          """);
      statement.execute("""
          CREATE TRIGGER tp_test_reject_approval_request_trigger
          BEFORE INSERT ON tp_approval_requests
          FOR EACH ROW EXECUTE FUNCTION tp_test_reject_approval_request()
          """);
    }
    try {
      assertThatThrownBy(() -> service.requestApproval(
          new TrustedTenantContext(ACME), approvalEvent()))
          .isInstanceOf(PostgresStateException.class)
          .hasMessage("DURABLE_STATE_UNAVAILABLE");
    } finally {
      try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
        statement.execute(
            "DROP TRIGGER tp_test_reject_approval_request_trigger ON tp_approval_requests");
        statement.execute("DROP FUNCTION tp_test_reject_approval_request()");
      }
    }
    assertThat(targetCalls).hasValue(0);
    assertThat(count("tp_approval_requests", "TRUE")).isZero();
    assertThat(count("tp_approval_transitions", "TRUE")).isZero();
    assertThat(count("tp_evidence_events", "TRUE")).isZero();
    assertThat(count("tp_evidence_outbox", "TRUE")).isZero();

    String requestId = service.requestApproval(
        new TrustedTenantContext(ACME), approvalEvent()).approval().binding().approvalRequestId();
    long evidenceBefore = count("tp_evidence_events", "TRUE");
    long outboxBefore = count("tp_evidence_outbox", "TRUE");
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE FUNCTION tp_test_reject_approval_decision() RETURNS TRIGGER AS $$
          BEGIN RAISE EXCEPTION 'test approval decision failure'; END;
          $$ LANGUAGE plpgsql
          """);
      statement.execute("""
          CREATE TRIGGER tp_test_reject_approval_decision_trigger
          BEFORE INSERT ON tp_approval_transitions
          FOR EACH ROW WHEN (NEW.to_status IN ('approved', 'denied'))
          EXECUTE FUNCTION tp_test_reject_approval_decision()
          """);
    }
    try {
      assertThatThrownBy(() -> service.review(
          reviewer(ACME, "user:bob@example.com"), requestId,
          "approve", "CHANGE_REVIEWED"))
          .isInstanceOf(PostgresStateException.class)
          .hasMessage("DURABLE_STATE_UNAVAILABLE");
    } finally {
      try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
        statement.execute(
            "DROP TRIGGER tp_test_reject_approval_decision_trigger ON tp_approval_transitions");
        statement.execute("DROP FUNCTION tp_test_reject_approval_decision()");
      }
    }
    assertThat(targetCalls).hasValue(0);
    assertThat(state.find(ACME, requestId).status()).isEqualTo("pending");
    assertThat(count("tp_approval_transitions", "TRUE")).isEqualTo(1);
    assertThat(count("tp_evidence_events", "TRUE")).isEqualTo(evidenceBefore);
    assertThat(count("tp_evidence_outbox", "TRUE")).isEqualTo(outboxBefore);
  }

  @Test
  void approvalConsumptionRollbackIsAtomicAndPreventsDispatch() throws SQLException {
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = approvalService(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.nullNode();
    }, Clock.fixed(NOW, ZoneOffset.UTC), approvalPolicy());
    String requestId = service.requestApproval(
        new TrustedTenantContext(ACME), approvalEvent()).approval().binding().approvalRequestId();
    service.review(reviewer(ACME, "user:bob@example.com"), requestId, "approve", "CHANGE_REVIEWED");
    long evidenceBefore = count("tp_evidence_events", "TRUE");
    long outboxBefore = count("tp_evidence_outbox", "TRUE");
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE FUNCTION tp_test_reject_outbox() RETURNS TRIGGER AS $$
          BEGIN RAISE EXCEPTION 'test outbox failure'; END;
          $$ LANGUAGE plpgsql
          """);
      statement.execute("""
          CREATE TRIGGER tp_test_reject_outbox_trigger
          BEFORE INSERT ON tp_evidence_outbox
          FOR EACH ROW EXECUTE FUNCTION tp_test_reject_outbox()
          """);
    }
    try {
      assertThatThrownBy(() -> service.executeApproved(
          new TrustedTenantContext(ACME), requestId, approvalEvent()))
          .isInstanceOf(PostgresStateException.class)
          .hasMessage("DURABLE_STATE_UNAVAILABLE");
    } finally {
      try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
        statement.execute("DROP TRIGGER tp_test_reject_outbox_trigger ON tp_evidence_outbox");
        statement.execute("DROP FUNCTION tp_test_reject_outbox()");
      }
    }
    assertThat(targetCalls).hasValue(0);
    assertThat(state.find(ACME, requestId).status()).isEqualTo("approved");
    assertThat(count("tp_consumed_execution_permits", "TRUE")).isZero();
    assertThat(count("tp_execution_attempts", "TRUE")).isZero();
    assertThat(count("tp_approval_transitions", "to_status = 'consumed'")).isZero();
    assertThat(count("tp_evidence_events", "TRUE")).isEqualTo(evidenceBefore);
    assertThat(count("tp_evidence_outbox", "TRUE")).isEqualTo(outboxBefore);
  }

  @Test
  void approvalEvidenceIsAppendOnlyAndSecretMinimized() throws SQLException {
    String argumentSecret = "raw-approval-argument-MUST-NOT-PERSIST";
    String resultSecret = "raw-approval-result-MUST-NOT-PERSIST";
    JsonNode event = approvalEvent();
    ((ObjectNode) event.path("clientClaims")).put("prompt", argumentSecret);
    HumanApprovalService service = approvalService(
        state,
        action -> JsonSupport.MAPPER.createObjectNode().put("secret", resultSecret),
        Clock.fixed(NOW, ZoneOffset.UTC),
        approvalPolicy());
    String requestId = service.requestApproval(
        new TrustedTenantContext(ACME), event).approval().binding().approvalRequestId();
    service.review(reviewer(ACME, "user:bob@example.com"), requestId, "approve", "CHANGE_REVIEWED");
    service.executeApproved(new TrustedTenantContext(ACME), requestId, event);

    assertThat(allDurableRows())
        .doesNotContain(argumentSecret)
        .doesNotContain(resultSecret)
        .doesNotContain("compactPermit")
        .doesNotContain("sourceAttestation")
        .contains("approval.requested", "approval.approved", "approval.consumed");
    try (Connection connection = dataSource.getConnection()) {
      assertThatThrownBy(() -> execute(connection,
          "UPDATE tp_approval_transitions SET reason_code = 'TAMPERED' WHERE tenant_id = 'acme'"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> execute(connection,
          "UPDATE tp_approval_requests SET status = 'denied' WHERE tenant_id = 'acme'"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void unknownSchemaAndUnknownToolRemainConservative() {
    HumanApprovalService service = approvalService(
        state, new StatefulTargetSimulator(), Clock.fixed(NOW, ZoneOffset.UTC), approvalPolicy());
    JsonNode invalidSchema = approvalEvent();
    ((ObjectNode) invalidSchema).put("schemaVersion", "99.0");
    assertThatThrownBy(() -> service.requestApproval(
        new TrustedTenantContext(ACME), invalidSchema)).isInstanceOf(RuntimeException.class);

    JsonNode unknownTool = approvalEvent();
    ((ObjectNode) unknownTool).put("eventId", "018f47a2-f5ce-7a4b-8f54-80e5d3eaf098");
    ((ObjectNode) unknownTool.path("action")).put("toolName", "aws.ec2.unknown_operation");
    String requestId = service.requestApproval(
        new TrustedTenantContext(ACME), unknownTool).approval().binding().approvalRequestId();
    service.review(reviewer(ACME, "user:bob@example.com"), requestId, "approve", "CHANGE_REVIEWED");
    assertThatThrownBy(() -> service.executeApproved(
        new TrustedTenantContext(ACME), requestId, unknownTool))
        .isInstanceOf(io.tracepermit.application.TargetExecutionException.class)
        .extracting(error -> ((io.tracepermit.application.TargetExecutionException) error).code())
        .isEqualTo("SIMULATOR_TOOL_NOT_SUPPORTED");
    assertThat(state.find(ACME, requestId).status()).isEqualTo("consumed");
    assertThat(state.findByIdempotencyKey(
        ACME, "simulator", io.tracepermit.execution.InMemoryExecutionLedger.idempotencyKey(
            unknownTool, io.tracepermit.contracts.CanonicalJson.sha256(unknownTool))).status())
        .isEqualTo("failed");
  }

  private PostgresDurableEnforcementState repository(DataSource source) {
    return new PostgresDurableEnforcementState(
        source, Clock.fixed(NOW, ZoneOffset.UTC), PostgresDurableEnforcementStateTest::nextId);
  }

  private DurableExecutionService service(
      PostgresDurableEnforcementState repository,
      io.tracepermit.application.SimulatedTarget target) {
    AtomicInteger permits = new AtomicInteger();
    ExecutionPermitAuthority authority = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "durable-test-key",
        new byte[32],
        30,
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> "permit-service-" + permits.incrementAndGet(),
        new PostgresPermitConsumptionStore(dataSource, ACME));
    return new DurableExecutionService(
        new PolicyEvaluator(),
        JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json")),
        authority,
        repository,
        target,
        () -> SecurityReadiness.allReady(NOW),
        Clock.fixed(NOW, ZoneOffset.UTC),
        "urn:tracepermit:durable-test-executor",
        "simulator");
  }

  private HumanApprovalService approvalService(
      PostgresDurableEnforcementState repository,
      io.tracepermit.application.SimulatedTarget target,
      Clock serviceClock,
      JsonNode activePolicy) {
    ExecutionPermitAuthority authority = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "approval-durable-test-key",
        new byte[32],
        30,
        serviceClock,
        () -> "approval-permit-" + IDS.incrementAndGet(),
        new PostgresPermitConsumptionStore(dataSource, ACME));
    return new HumanApprovalService(
        new PolicyEvaluator(),
        activePolicy,
        authority,
        repository,
        repository,
        target,
        () -> SecurityReadiness.allReady(serviceClock.instant()),
        serviceClock,
        () -> "approval-id-" + IDS.incrementAndGet(),
        "urn:tracepermit:approval-durable-test-executor",
        "simulator",
        300);
  }

  private static TrustedReviewerContext reviewer(TenantPartition tenant, String subjectId) {
    return new TrustedReviewerContext(
        tenant,
        subjectId,
        "human",
        true,
        Set.of(HumanApprovalService.REVIEWER_ROLE),
        Set.of(HumanApprovalService.REVIEWER_SCOPE),
        "oidc",
        hash('7'));
  }

  private static JsonNode approvalEvent() {
    return JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json"));
  }

  private static JsonNode approvalPolicy() {
    return JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
  }

  private static void assertBindingMismatch(
      HumanApprovalService service, String requestId, JsonNode event) {
    assertThatThrownBy(() -> service.executeApproved(
        new TrustedTenantContext(ACME), requestId, event))
        .hasMessage("APPROVAL_BINDING_MISMATCH");
  }

  private static DurableExecutionClaim claim(
      TenantPartition tenant, String permitId, String idempotencyKey, Instant at) {
    return new DurableExecutionClaim(
        tenant,
        permitId,
        at.plusSeconds(30),
        "simulator",
        idempotencyKey,
        "event-1",
        "trace-1",
        "evaluation-1",
        "user:alice@example.com",
        hash('9'),
        "policy-1",
        1,
        "allow",
        List.of(hash('8')),
        at);
  }

  private static JsonNode allowEvent() {
    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    return JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
  }

  private static String hash(char digit) {
    return "sha256:" + String.valueOf(digit).repeat(64);
  }

  private static String nextId() {
    return "durable-id-" + IDS.incrementAndGet();
  }

  private static EvidenceOutboxEntry firstWithLease(
      EvidenceOutboxEntry entry, String workerId, Instant leaseExpiresAt, int attempts) {
    return new EvidenceOutboxEntry(
        entry.tenant(),
        entry.outboxId(),
        entry.streamId(),
        entry.sequence(),
        entry.evidenceEventId(),
        entry.payloadHash(),
        entry.createdAt(),
        attempts,
        workerId,
        leaseExpiresAt);
  }

  private static EvidenceAnchorReceipt signedReceipt(
      EvidenceOutboxEntry entry, KeyPair keyPair, Instant anchoredAt) {
    String placeholder = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
    EvidenceAnchorReceipt unsigned =
        new EvidenceAnchorReceipt(
            EvidenceAnchorReceipt.VERSION,
            entry.tenant(),
            entry.outboxId(),
            entry.streamId(),
            entry.sequence(),
            entry.evidenceEventId(),
            entry.payloadHash(),
            "external-audit",
            "anchor-key-1",
            "anchor-" + entry.outboxId(),
            anchoredAt,
            "Ed25519",
            placeholder);
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keyPair.getPrivate());
      signer.update(unsigned.signingInput());
      return new EvidenceAnchorReceipt(
          unsigned.schemaVersion(),
          unsigned.tenant(),
          unsigned.outboxId(),
          unsigned.streamId(),
          unsigned.sequence(),
          unsigned.evidenceEventId(),
          unsigned.payloadHash(),
          unsigned.provider(),
          unsigned.keyId(),
          unsigned.anchorId(),
          unsigned.anchoredAt(),
          unsigned.signatureAlgorithm(),
          Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void assertValidChain(List<DurableEvidenceRecord> evidence) {
    List<JsonNode> records = evidence.stream().map(record -> {
      ObjectNode integrity = JsonSupport.MAPPER.createObjectNode();
      integrity.put("algorithm", "sha256");
      if (record.previousEventHash() == null) {
        integrity.putNull("previousEventHash");
      } else {
        integrity.put("previousEventHash", record.previousEventHash());
      }
      integrity.put("eventHash", record.eventHash());
      ObjectNode envelope = JsonSupport.MAPPER.createObjectNode();
      envelope.set("payload", record.payload());
      envelope.set("integrity", integrity);
      return (JsonNode) envelope;
    }).toList();
    EvidenceIntegrityResult result = InMemoryEvidenceLedger.verifyRecords(
        records, evidence.getLast().eventHash());
    assertThat(result.valid()).isTrue();
  }

  private void assertCounts(long permits, long attempts, long evidence, long outbox) {
    assertThat(count("tp_consumed_execution_permits", "TRUE")).isEqualTo(permits);
    assertThat(count("tp_execution_attempts", "TRUE")).isEqualTo(attempts);
    assertThat(count("tp_evidence_events", "TRUE")).isEqualTo(evidence);
    assertThat(count("tp_evidence_outbox", "TRUE")).isEqualTo(outbox);
  }

  private long count(String table, String condition) {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table + " WHERE " + condition)) {
      result.next();
      return result.getLong(1);
    } catch (SQLException exception) {
      throw new AssertionError(exception);
    }
  }

  private String allDurableRows() {
    List<String> tables = List.of(
        "tp_approval_requests",
        "tp_approval_transitions",
        "tp_consumed_execution_permits",
        "tp_execution_attempts",
        "tp_execution_transitions",
        "tp_reconciliation_queue",
        "tp_evidence_events",
        "tp_evidence_outbox",
        "tp_evidence_anchors");
    StringBuilder rows = new StringBuilder();
    try (Connection connection = dataSource.getConnection()) {
      for (String table : tables) {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT row_to_json(value)::text FROM " + table + " value")) {
          while (result.next()) rows.append(result.getString(1));
        }
      }
      return rows.toString();
    } catch (SQLException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private static DataSource isolatedSchema(String schema) throws SQLException {
    PGSimpleDataSource administration = configuredDataSource();
    try (Connection connection = administration.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }
    return dataSource(schema);
  }

  private static DataSource dataSource(String schema) {
    PGSimpleDataSource source = configuredDataSource();
    source.setCurrentSchema(schema);
    return source;
  }

  private static PGSimpleDataSource configuredDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static void deleteRecursively(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}
