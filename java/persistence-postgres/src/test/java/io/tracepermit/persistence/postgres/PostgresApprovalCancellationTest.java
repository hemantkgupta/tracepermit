package io.tracepermit.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.execution.ApprovalCancellation;
import io.tracepermit.execution.ApprovalDecision;
import io.tracepermit.execution.ApprovalExecutionClaim;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.TenantPartition;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real PostgreSQL locks, transactions and failure injection; never an effectful target. */
final class PostgresApprovalCancellationTest {
  private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
  private static final TenantPartition TENANT = new TenantPartition("synthetic");
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
  private final MutableClock clock = new MutableClock();
  private PGSimpleDataSource source;
  private PostgresDurableEnforcementState state;

  @BeforeAll static void start() { POSTGRES.start(); }
  @AfterAll static void stop() { POSTGRES.stop(); }
  @BeforeEach void fresh() throws Exception {
    String database = "cancel_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = configured(POSTGRES.getDatabaseName()).getConnection(); var sql = connection.createStatement()) {
      sql.execute("CREATE DATABASE " + database + " TEMPLATE template0");
    }
    source = configured(database);
    PostgresDatabaseLifecycle.initializeEmpty(source);
    state = repository(source);
    state.createPending(binding());
  }

  @Test void withdrawalCommitsTerminalStateDistinctProvenanceEvidenceAndOutboxAcrossRestart() throws Exception {
    assertThat(state.cancel(intent()).status()).isEqualTo("cancelled");
    var cancelled = repository(source).find(TENANT, "request-1");
    assertThat(cancelled.transitionSequence()).isEqualTo(2);
    assertThat(cancelled.approvalDecisionId()).isNull();
    assertThat(cancelled.reviewerSubjectId()).isNull();
    assertThat(cancelled.consumedPermitId()).isNull();
    assertThat(cancelled.consumedAttemptId()).isNull();
    try (var connection = source.getConnection(); var sql = connection.createStatement();
         var row = sql.executeQuery("SELECT * FROM tp_approval_transitions WHERE to_status='cancelled'")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString("cancellation_subject_id")).isEqualTo("issuer#owner");
      assertThat(row.getString("cancellation_authentication_method")).isEqualTo("oidc-access-token");
      assertThat(row.getString("cancellation_credential_fingerprint")).isEqualTo(hash('f'));
      assertThat(row.getString("reviewer_subject_id")).isNull();
      assertThat(row.getString("reason_code")).isEqualTo("REQUESTER_CANCELLED");
      assertThat(row.next()).isFalse();
    }
    var evidence = state.readEvidence(TENANT);
    assertThat(evidence).hasSize(2);
    var payload = evidence.getLast().payload();
    assertThat(payload.path("schemaVersion").asText()).isEqualTo("3.0");
    assertThat(payload.path("kind").asText()).isEqualTo("approval.cancelled");
    assertThat(payload.path("cancellation").path("credentialFingerprint").asText()).isEqualTo(hash('f'));
    assertThat(payload.path("cancellation").path("requesterSubjectId").asText()).isEqualTo("issuer#owner");
    assertThat(payload.toString()).doesNotContain("reviewer", "permitId", "attemptId", "Bearer", "arguments");
    assertThat(evidence.getLast().previousEventHash()).isEqualTo(evidence.getFirst().eventHash());
    assertThat(count("tp_evidence_outbox")).isEqualTo(2);
    noAuthority();
    assertThatThrownBy(() -> state.decide(decision("approve"))).hasMessage("APPROVAL_ALREADY_DECIDED");
    assertThatThrownBy(() -> state.consumeAndClaim(claim())).hasMessage("APPROVAL_NOT_EXECUTABLE");
    assertThatThrownBy(() -> state.cancel(intent())).hasMessage("APPROVAL_NOT_CANCELLABLE");
    assertThat(count("tp_evidence_events")).isEqualTo(2);
    noAuthority();
  }

  @Test void ownerTenantHashAndStateAreRecheckedWithinTheLockedTransaction() {
    assertThatThrownBy(() -> state.cancel(new ApprovalCancellation(new TenantPartition("other"),
        "request-1", hash('a'), "issuer#owner", "oidc-access-token", hash('f'), NOW.plusSeconds(60))))
        .hasMessage("APPROVAL_REQUEST_NOT_FOUND");
    assertThatThrownBy(() -> state.cancel(new ApprovalCancellation(TENANT,
        "request-1", hash('a'), "other-owner", "oidc-access-token", hash('f'), NOW.plusSeconds(60))))
        .hasMessage("REQUESTER_NOT_AUTHORIZED");
    assertThatThrownBy(() -> state.cancel(new ApprovalCancellation(TENANT,
        "request-1", hash('b'), "issuer#owner", "oidc-access-token", hash('f'), NOW.plusSeconds(60))))
        .hasMessage("APPROVAL_BINDING_MISMATCH");
    assertThat(state.find(TENANT, "request-1").status()).isEqualTo("pending");
    assertThat(state.readEvidence(TENANT)).hasSize(1);
    noAuthority();
  }

  @ParameterizedTest @ValueSource(strings = {"approved", "denied", "expired", "consumed"})
  void noNonPendingStateCanBeRelabelledCancellation(String status) {
    if (status.equals("expired")) clock.now = NOW.plusSeconds(301);
    state.decide(decision(status.equals("denied") ? "deny" : "approve"));
    if (status.equals("consumed")) state.consumeAndClaim(claim());
    long evidence = state.readEvidence(TENANT).size();
    assertThatThrownBy(() -> state.cancel(intent())).hasMessage("APPROVAL_NOT_CANCELLABLE");
    assertThat(state.find(TENANT, "request-1").status()).isEqualTo(status);
    assertThat(state.readEvidence(TENANT)).hasSize((int) evidence);
  }

  @ParameterizedTest @ValueSource(strings = {"tp_evidence_events", "tp_evidence_outbox"})
  void failedEvidenceOrOutboxInsertRollsBackAggregateTransitionAndStreamHead(String table) throws Exception {
    sql("CREATE FUNCTION reject_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic'; END $$");
    sql("CREATE TRIGGER synthetic_failure BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION reject_insert()");
    assertThatThrownBy(() -> state.cancel(intent())).isInstanceOf(PostgresStateException.class);
    assertThat(state.find(TENANT, "request-1").status()).isEqualTo("pending");
    assertThat(count("tp_approval_transitions")).isEqualTo(1);
    assertThat(count("tp_evidence_events")).isEqualTo(1);
    assertThat(count("tp_evidence_outbox")).isEqualTo(1);
    noAuthority();
    sql("DROP TRIGGER synthetic_failure ON " + table);
    assertThat(state.cancel(intent()).status()).isEqualTo("cancelled");
    assertThat(state.readEvidence(TENANT).getLast().sequence()).isEqualTo(2);
  }

  @Test void quarantineRefusesWithdrawalWithoutAnEmergencyEscape() {
    PostgresRecoveryQuarantine.quarantine(source, NOW);
    assertThatThrownBy(() -> state.cancel(intent())).hasMessage("DATABASE_RECOVERY_QUARANTINED");
    assertThat(state.find(TENANT, "request-1").status()).isEqualTo("pending");
    assertThat(state.readEvidence(TENANT)).hasSize(1);
    noAuthority();
  }

  @ParameterizedTest @ValueSource(strings = {"cancel", "approve", "deny"})
  void bothReviewLockSchedulesAndDuplicateCancellationHaveExactlyOneWinner(String first) throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var firstStore = repository(intercept((connection, operation, args, result) -> {
      if (operation.equals("prepareStatement") && args[0] instanceof String sql
          && sql.contains("FROM tp_approval_requests") && sql.endsWith(" FOR UPDATE")) {
        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
            (proxy, method, values) -> {
              Object value = invoke(method, result, values);
              if (method.getName().equals("executeQuery")) {
                locked.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new SQLException("Synthetic lock gate timed out");
              }
              return value;
            });
      }
      return result;
    }));
    var pool = Executors.newFixedThreadPool(2, task -> {
      Thread thread = new Thread(task, "cancellation-race"); thread.setDaemon(true); return thread;
    });
    try {
      var winner = pool.submit(() -> first.equals("cancel") ? firstStore.cancel(intent()) : firstStore.decide(decision(first)));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var loser = pool.submit(() -> {
        try {
          if (first.equals("cancel")) state.decide(decision("approve")); else state.cancel(intent());
          return "UNEXPECTED_SUCCESS";
        } catch (IllegalStateException failure) { return failure.getMessage(); }
      });
      awaitBlocked();
      release.countDown();
      String expected = first.equals("cancel") ? "cancelled" : first.equals("approve") ? "approved" : "denied";
      assertThat(winner.get(10, TimeUnit.SECONDS).status()).isEqualTo(expected);
      assertThat(loser.get(10, TimeUnit.SECONDS)).isEqualTo(first.equals("cancel")
          ? "APPROVAL_ALREADY_DECIDED" : "APPROVAL_NOT_CANCELLABLE");
      assertThat(state.find(TENANT, "request-1").status()).isEqualTo(expected);
      assertThat(state.readEvidence(TENANT)).hasSize(2);
      noAuthority();
    } finally {
      release.countDown(); pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).as("bounded race cleanup").isTrue();
    }
  }

  @Test void concurrentOwnerWithdrawalsCommitOnlyOneCancellation() throws Exception {
    var pool = executor(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      var work = (java.util.concurrent.Callable<String>) () -> {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic start timeout");
        try { return state.cancel(intent()).status(); }
        catch (IllegalStateException failure) { return failure.getMessage(); }
      };
      var one = pool.submit(work); var two = pool.submit(work); start.countDown();
      assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("cancelled", "APPROVAL_NOT_CANCELLABLE");
      assertThat(count("tp_approval_transitions")).isEqualTo(2);
      assertThat(count("tp_evidence_outbox")).isEqualTo(2);
      noAuthority();
    } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
  }

  @ParameterizedTest @ValueSource(strings = {"request", "authentication"})
  void expiryWhileWaitingForRequestLockIsRecheckedUsingStoreTime(String expired) throws Exception {
    var pool = executor(1);
    try (var blocker = source.getConnection()) {
      blocker.setAutoCommit(false);
      try (var lock = blocker.createStatement()) {
        lock.execute("SELECT * FROM tp_approval_requests FOR UPDATE");
      }
      try {
        var waiting = pool.submit(() -> {
          try { state.cancel(intent()); return "UNEXPECTED_SUCCESS"; }
          catch (IllegalStateException | SecurityException failure) { return failure.getMessage(); }
        });
        awaitBlocked();
        clock.now = expired.equals("request") ? NOW.plusSeconds(301) : NOW.plusSeconds(600);
        blocker.rollback();
        assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(expired.equals("request")
            ? "APPROVAL_EXPIRED" : "CANCELLATION_AUTHENTICATION_EXPIRED");
      } finally { blocker.rollback(); }
      assertThat(state.find(TENANT, "request-1").status()).isEqualTo("pending");
      assertThat(count("tp_evidence_events")).isEqualTo(1);
      noAuthority();
    } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
  }

  @Test void lostCommitAcknowledgementDoesNotReportSuccessOrAutomaticallyRetry() {
    var uncertain = repository(intercept((connection, operation, args, result) -> {
      if (operation.equals("commit")) throw new SQLException("Synthetic acknowledgement lost");
      return result;
    }));
    assertThatThrownBy(() -> uncertain.cancel(intent())).isInstanceOf(PostgresStateException.class);
    assertThat(state.find(TENANT, "request-1").status()).isEqualTo("cancelled");
    assertThat(count("tp_approval_transitions")).isEqualTo(2);
    assertThat(count("tp_evidence_outbox")).isEqualTo(2);
    noAuthority();
  }

  private void awaitBlocked() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    try (var connection = source.getConnection(); var sql = connection.createStatement()) {
      sql.setQueryTimeout(2);
      while (System.nanoTime() < deadline) {
        try (var rows = sql.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
            + " AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%tp_approval_requests%'")) {
          rows.next(); if (rows.getInt(1) > 0) return;
        }
        Thread.sleep(10);
      }
    }
    throw new AssertionError("Competing transaction never reached the actual PostgreSQL request lock");
  }

  @FunctionalInterface private interface AfterOperation {
    Object apply(Connection connection, String operation, Object[] args, Object result) throws Exception;
  }
  private DataSource intercept(AfterOperation hook) {
    return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
        (proxy, method, args) -> {
          Object value = invoke(method, source, args);
          if (!method.getName().equals("getConnection")) return value;
          Connection connection = (Connection) value;
          return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
              (p, operation, values) -> hook.apply(connection, operation.getName(), values,
                  invoke(operation, connection, values)));
        });
  }
  private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
    try { return method.invoke(target, args); }
    catch (InvocationTargetException failure) { throw failure.getCause(); }
  }
  private PostgresDurableEnforcementState repository(DataSource dataSource) {
    return PostgresDurableEnforcementState.openExisting(dataSource, clock, () -> UUID.randomUUID().toString());
  }
  private static ApprovalBinding binding() {
    return new ApprovalBinding(TENANT, "request-1", hash('a'), hash('b'), "event", "evaluation",
        "issuer#owner", hash('c'), "policy", 1, "require_approval", "simulated.change", hash('d'),
        "reviewers", NOW, NOW.plusSeconds(300), 1);
  }
  private ApprovalDecision decision(String decision) {
    return new ApprovalDecision(TENANT, "request-1", "decision-1", "issuer#reviewer", "oidc-access-token",
        hash('e'), decision, "REVIEWED", clock.instant());
  }
  private ApprovalCancellation intent() {
    return new ApprovalCancellation(TENANT, "request-1", hash('a'), "issuer#owner", "oidc-access-token",
        hash('f'), NOW.plusSeconds(600));
  }
  private ApprovalExecutionClaim claim() {
    var binding = binding();
    return new ApprovalExecutionClaim(binding, "decision-1", new DurableExecutionClaim(TENANT, "permit-1",
        NOW.plusSeconds(60), "simulator", hash('1'), "event", "trace", "evaluation", "issuer#owner",
        hash('b'), "policy", 1, "allow", List.of(), NOW), NOW);
  }
  private void noAuthority() {
    assertThat(count("tp_execution_attempts")).isZero();
    assertThat(count("tp_consumed_execution_permits")).isZero();
  }
  private long count(String table) {
    try (var connection = source.getConnection(); var sql = connection.createStatement();
         var rows = sql.executeQuery("SELECT count(*) FROM " + table)) { rows.next(); return rows.getLong(1); }
    catch (SQLException failure) { throw new AssertionError(failure); }
  }
  private void sql(String command) throws SQLException {
    try (var connection = source.getConnection(); var sql = connection.createStatement()) { sql.execute(command); }
  }
  private static PGSimpleDataSource configured(String name) {
    var source = new PGSimpleDataSource();
    source.setServerNames(new String[] {POSTGRES.getHost()}); source.setPortNumbers(new int[] {POSTGRES.getMappedPort(5432)});
    source.setDatabaseName(name); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
    source.setConnectTimeout(3); source.setSocketTimeout(10); return source;
  }
  private static java.util.concurrent.ExecutorService executor(int threads) {
    return Executors.newFixedThreadPool(threads, task -> {
      Thread thread = new Thread(task, "pending-cancellation-test"); thread.setDaemon(true); return thread;
    });
  }
  private static String hash(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
  private static final class MutableClock extends Clock {
    private volatile Instant now = NOW;
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
