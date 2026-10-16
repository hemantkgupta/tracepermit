package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.ExecutionPermitException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PostgresPermitConsumptionStoreTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
  private static final String ACTION_HASH = "sha256:" + "a".repeat(64);
  private static final TenantPartition ACME = new TenantPartition("acme");
  private static final TenantPartition OTHER = new TenantPartition("other");
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine");

  private DataSource dataSource;
  private PostgresPermitConsumptionStore store;

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
    dataSource = configuredDataSource();
    store = new PostgresPermitConsumptionStore(dataSource, ACME);
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("""
          TRUNCATE tp_approval_action_snapshots, tp_approval_transitions, tp_approval_requests,
                   tp_evidence_anchors, tp_evidence_outbox,
                   tp_evidence_events, tp_evidence_streams,
                   tp_reconciliation_queue,
                   tp_execution_transitions, tp_execution_attempts,
                   tp_consumed_execution_permits
          """);
    }
  }

  @Test
  void singlePermitCanBeConsumedExactlyOnce() {
    String permitId = "permit-single-" + UUID.randomUUID();
    Instant expiresAt = NOW.plusSeconds(60);

    assertThat(store.isConsumed(permitId)).isFalse();
    assertThat(store.consume(permitId, expiresAt, ACTION_HASH, NOW)).isTrue();
    assertThat(store.isConsumed(permitId)).isTrue();

    // Replay within lifetime must return false
    assertThat(store.consume(permitId, expiresAt, ACTION_HASH, NOW.plusSeconds(10))).isFalse();

    // Replay after expiry must still return false
    assertThat(store.consume(permitId, expiresAt, ACTION_HASH, NOW.plusSeconds(70))).isFalse();
    assertThat(store.isConsumed(permitId)).isTrue();
  }

  @Test
  void thirtyTwoConcurrentClaimsAcrossInstancesHaveExactlyOneWinner() throws Exception {
    String permitId = "permit-concurrent-" + UUID.randomUUID();
    Instant expiresAt = NOW.plusSeconds(60);
    List<PostgresPermitConsumptionStore> replicas = List.of(
        new PostgresPermitConsumptionStore(dataSource, ACME),
        new PostgresPermitConsumptionStore(dataSource, ACME),
        new PostgresPermitConsumptionStore(dataSource, ACME),
        new PostgresPermitConsumptionStore(dataSource, ACME));

    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> tasks = IntStream.range(0, 32)
        .mapToObj(index -> (Callable<Boolean>) () -> {
          start.await();
          return replicas.get(index % replicas.size()).consume(permitId, expiresAt, ACTION_HASH, NOW);
        })
        .toList();

    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      int winners = 0;
      for (var future : futures) {
        if (future.get()) winners++;
      }
      assertThat(winners).isEqualTo(1);
    }
    assertThat(store.isConsumed(permitId)).isTrue();
  }

  @Test
  void storeRestartPreservesConsumedStatus() {
    String permitId = "permit-restart-" + UUID.randomUUID();
    Instant expiresAt = NOW.plusSeconds(120);

    PostgresPermitConsumptionStore firstInstance = new PostgresPermitConsumptionStore(dataSource, ACME);
    assertThat(firstInstance.consume(permitId, expiresAt, ACTION_HASH, NOW)).isTrue();

    // Restart: new store instance connecting to same database
    PostgresPermitConsumptionStore restartedInstance = new PostgresPermitConsumptionStore(dataSource, ACME);
    assertThat(restartedInstance.isConsumed(permitId)).isTrue();
    assertThat(restartedInstance.consume(permitId, expiresAt, ACTION_HASH, NOW.plusSeconds(5))).isFalse();
  }

  @Test
  void expiredPermitCannotBeConsumed() {
    String permitId = "permit-expired-" + UUID.randomUUID();
    Instant expiresAt = NOW.minusSeconds(1);

    // Cannot consume a permit whose expiry is not after now
    assertThat(store.consume(permitId, expiresAt, ACTION_HASH, NOW)).isFalse();
    assertThat(store.isConsumed(permitId)).isFalse();

    // Same for exact boundary: expiresAt == now
    assertThat(store.consume(permitId, NOW, ACTION_HASH, NOW)).isFalse();
    assertThat(store.isConsumed(permitId)).isFalse();
  }

  @Test
  void explicitActionHashIsRequired() throws SQLException {
    String permitExplicit = "permit-hash-explicit-" + UUID.randomUUID();
    String explicitHash = "sha256:" + "a".repeat(64);
    Instant expiresAt = NOW.plusSeconds(60);

    assertThat(store.consume(ACME, permitExplicit, expiresAt, explicitHash, NOW)).isTrue();
    assertThat(readActionHash(ACME, permitExplicit)).isEqualTo(explicitHash);

    String permitDerived = "permit-hash-derived-" + UUID.randomUUID();
    assertThatThrownBy(() -> store.consume(ACME, permitDerived, expiresAt, null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.consume(permitDerived, expiresAt, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.consume(ACME, permitDerived, expiresAt, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.isConsumed(permitDerived)).isFalse();

    // Invalid action hash must fail-closed
    assertThatThrownBy(() -> store.consume(ACME, "permit-bad", expiresAt, "invalid-hash", NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void multiTenantIsolation() {
    String permitId = "permit-tenant-shared-" + UUID.randomUUID();
    Instant expiresAt = NOW.plusSeconds(60);

    // Tenant ACME consumes permit
    assertThat(store.consume(ACME, permitId, expiresAt, ACTION_HASH, NOW)).isTrue();
    assertThat(store.isConsumed(ACME, permitId)).isTrue();
    assertThat(store.isConsumed(OTHER, permitId)).isFalse();

    // Tenant OTHER consumes the same permit ID independently without collision
    assertThat(store.consume(OTHER, permitId, expiresAt, ACTION_HASH, NOW)).isTrue();
    assertThat(store.isConsumed(OTHER, permitId)).isTrue();

    // Both tenants cannot re-consume their own permit
    assertThat(store.consume(ACME, permitId, expiresAt, ACTION_HASH, NOW.plusSeconds(1))).isFalse();
    assertThat(store.consume(OTHER, permitId, expiresAt, ACTION_HASH, NOW.plusSeconds(1))).isFalse();

    // Third tenant sees it unconsumed
    TenantPartition third = new TenantPartition("tenant-c");
    assertThat(store.isConsumed(third, permitId)).isFalse();
  }

  @Test
  void pruningExpiredUnreferencedPermits() throws SQLException {
    String unreferencedExpired = "permit-unref-exp-" + UUID.randomUUID();
    String unreferencedLive = "permit-unref-live-" + UUID.randomUUID();
    String referencedAttemptExpired = "permit-ref-att-exp-" + UUID.randomUUID();
    String referencedApprovalExpired = "permit-ref-app-exp-" + UUID.randomUUID();

    // 1. Unreferenced expired permit
    store.consume(ACME, unreferencedExpired, NOW.minusSeconds(10), ACTION_HASH, NOW.minusSeconds(20));
    // 2. Unreferenced live permit
    store.consume(ACME, unreferencedLive, NOW.plusSeconds(60), ACTION_HASH, NOW);
    // 3. Referenced expired permit (referenced in tp_execution_attempts)
    store.consume(ACME, referencedAttemptExpired, NOW.minusSeconds(10), ACTION_HASH, NOW.minusSeconds(20));
    insertDummyExecutionAttempt(ACME, referencedAttemptExpired, NOW.minusSeconds(20));
    // 4. Referenced expired permit (referenced in tp_approval_requests)
    store.consume(ACME, referencedApprovalExpired, NOW.minusSeconds(10), ACTION_HASH, NOW.minusSeconds(20));
    String attemptForApproval = insertDummyExecutionAttempt(ACME, referencedApprovalExpired, NOW.minusSeconds(20));
    insertDummyApprovalRequest(ACME, referencedApprovalExpired, attemptForApproval, NOW.minusSeconds(20));

    // Prune expired unreferenced at NOW
    int pruned = store.pruneExpiredUnreferenced(ACME, NOW);
    assertThat(pruned).isEqualTo(1);

    assertThat(store.isConsumed(ACME, unreferencedExpired)).isFalse();
    assertThat(store.isConsumed(ACME, unreferencedLive)).isTrue();
    assertThat(store.isConsumed(ACME, referencedAttemptExpired)).isTrue();
    assertThat(store.isConsumed(ACME, referencedApprovalExpired)).isTrue();
  }

  @Test
  void executionPermitAuthorityIntegration() {
    byte[] key = new byte[32];
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    ExecutionPermitAuthority authority = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "test-key",
        key,
        30,
        clock,
        () -> "permit-" + UUID.randomUUID(),
        store);

    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    JsonNode event = JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
    JsonNode evaluation = vector.path("evaluation");
    String audience = vector.path("audience").asText();

    String permit = authority.issue(event, evaluation, audience);

    // First verification and consumption must succeed
    JsonNode claims = authority.verifyAndConsume(permit, event, audience);
    assertThat(claims.path("permitId").asText()).isNotBlank();
    assertThat(store.isConsumed(claims.path("permitId").asText())).isTrue();

    // Replay verification and consumption must fail with EXECUTION_PERMIT_REPLAYED
    assertThatThrownBy(() -> authority.verifyAndConsume(permit, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting("code")
        .isEqualTo("EXECUTION_PERMIT_REPLAYED");

    // Replay across authority restart on fresh instance still fails
    ExecutionPermitAuthority restartedAuthority = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "test-key",
        key,
        30,
        clock,
        () -> "permit-" + UUID.randomUUID(),
        new PostgresPermitConsumptionStore(dataSource, ACME));

    assertThatThrownBy(() -> restartedAuthority.verifyAndConsume(permit, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting("code")
        .isEqualTo("EXECUTION_PERMIT_REPLAYED");
  }

  @Test
  void inputValidationFailures() {
    Instant expiresAt = NOW.plusSeconds(60);

    assertThatThrownBy(() -> store.consume(null, expiresAt, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.consume("", expiresAt, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.consume("permit with spaces", expiresAt, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.consume("a".repeat(257), expiresAt, NOW))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> store.consume((TenantPartition) null, "permit-1", expiresAt, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.consume(ACME, "permit-1", null, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.consume(ACME, "permit-1", expiresAt, null))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> store.isConsumed((TenantPartition) null, "permit-1"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.isConsumed(ACME, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.isConsumed(ACME, ""))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> store.pruneExpiredUnreferenced((TenantPartition) null, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.pruneExpiredUnreferenced(ACME, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void databaseFailureWrappedInPostgresStateException() throws SQLException {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE FUNCTION tp_test_reject_permit() RETURNS TRIGGER AS $$
          BEGIN RAISE EXCEPTION 'test simulated database error'; END;
          $$ LANGUAGE plpgsql
          """);
      statement.execute("""
          CREATE TRIGGER tp_test_reject_permit_trigger
          BEFORE INSERT ON tp_consumed_execution_permits
          FOR EACH ROW EXECUTE FUNCTION tp_test_reject_permit()
          """);
    }
    try {
      assertThatThrownBy(() -> store.consume("permit-failing", NOW.plusSeconds(60), ACTION_HASH, NOW))
          .isInstanceOf(PostgresStateException.class)
          .hasMessageContaining("Failed to record consumed execution permit");
    } finally {
      try (Connection connection = dataSource.getConnection();
           Statement statement = connection.createStatement()) {
        statement.execute(
            "DROP TRIGGER IF EXISTS tp_test_reject_permit_trigger ON tp_consumed_execution_permits");
        statement.execute("DROP FUNCTION IF EXISTS tp_test_reject_permit()");
      }
    }
  }

  private String readActionHash(TenantPartition tenant, String permitId) throws SQLException {
    String sql = "SELECT action_hash FROM tp_consumed_execution_permits WHERE tenant_id = ? AND permit_id = ?";
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant.value());
      statement.setString(2, permitId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }
  }

  private String insertDummyExecutionAttempt(
      TenantPartition tenant, String permitId, Instant at) throws SQLException {
    String attemptId = "attempt-" + UUID.randomUUID();
    String sql = """
        INSERT INTO tp_execution_attempts
          (tenant_id, attempt_id, permit_id, adapter_id, idempotency_key,
           event_id, trace_id, evaluation_id, subject_id, action_hash,
           policy_id, policy_version, decision, credential_fingerprints,
           status, transition_sequence, started_at, updated_at)
        VALUES (?, ?, ?, 'adapter-1', ?, 'event-1', 'trace-1', 'eval-1', 'subject-1',
                ?, 'pol-1', 1, 'allow', '[]'::jsonb, 'executing', 1, ?, ?)
        """;
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant.value());
      statement.setString(2, attemptId);
      statement.setString(3, permitId);
      statement.setString(4, "sha256:" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
      statement.setString(5, "sha256:" + "c".repeat(64));
      statement.setObject(6, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
      statement.setObject(7, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
      statement.executeUpdate();
      return attemptId;
    }
  }

  private void insertDummyApprovalRequest(
      TenantPartition tenant, String permitId, String attemptId, Instant at) throws SQLException {
    String sql = """
        INSERT INTO tp_approval_requests
          (tenant_id, approval_request_id, request_hash, action_hash, event_id,
           evaluation_id, subject_id, actor_lineage_hash, policy_id, policy_version,
           original_decision, tool_name, resource_hash, approval_group, created_at,
           expires_at, max_uses, status, transition_sequence, approval_decision_id,
           reviewer_subject_id, reviewer_authentication_method, reviewer_credential_fingerprint,
           decision_reason_code, decided_at, updated_at, consumed_permit_id, consumed_attempt_id)
        VALUES (?, ?, ?, ?, 'event-1', 'eval-1', 'subject-1', ?, 'pol-1', 1,
                'require_approval', 'test_tool', ?, 'security-ops', ?, ?, 1,
                'consumed', 2, 'dec-1', 'user:reviewer@example.com', 'test-auth',
                ?, 'APPROVED', ?, ?, ?, ?)
        """;
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant.value());
      statement.setString(2, "req-" + UUID.randomUUID());
      statement.setString(3, "sha256:" + "d".repeat(64));
      statement.setString(4, "sha256:" + "e".repeat(64));
      statement.setString(5, "sha256:" + "f".repeat(64));
      statement.setString(6, "sha256:" + "1".repeat(64));
      statement.setObject(7, OffsetDateTime.ofInstant(at.minusSeconds(30), ZoneOffset.UTC));
      statement.setObject(8, OffsetDateTime.ofInstant(at.plusSeconds(300), ZoneOffset.UTC));
      statement.setString(9, "sha256:" + "2".repeat(64));
      statement.setObject(10, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
      statement.setObject(11, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
      statement.setString(12, permitId);
      statement.setString(13, attemptId);
      statement.executeUpdate();
    }
  }

  private static PGSimpleDataSource configuredDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }
}
