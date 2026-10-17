package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.DurableExecutionService;
import io.tracepermit.application.ExecutionReconciliationWorker;
import io.tracepermit.application.HumanApprovalService;
import io.tracepermit.application.ReconciliationObservation;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.SimulatedTarget;
import io.tracepermit.application.TargetExecutionException;
import io.tracepermit.application.TrustedReviewerContext;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real transactions; injected faults never own a target invocation or new authority. */
final class PostgresCompletionFailureRecoveryTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final TenantPartition TENANT = new TenantPartition("acme");
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine");
  private static final AtomicInteger IDS = new AtomicInteger();
  private static final String PRIVATE_ERROR = "synthetic-upstream-detail-not-for-evidence";
  private PGSimpleDataSource database;
  private FaultDataSource faults;
  private PostgresDurableEnforcementState state;

  @BeforeAll static void start() { POSTGRES.start(); }
  @AfterAll static void stop() { POSTGRES.stop(); }

  @BeforeEach
  void reset() throws SQLException {
    database = new PGSimpleDataSource();
    database.setUrl(POSTGRES.getJdbcUrl());
    database.setUser(POSTGRES.getUsername());
    database.setPassword(POSTGRES.getPassword());
    faults = new FaultDataSource(database);
    state = new PostgresDurableEnforcementState(faults, CLOCK, () -> "attempt-" + IDS.incrementAndGet());
    try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          TRUNCATE tp_approval_policy_bindings,
                   tp_approval_action_snapshots, tp_approval_transitions, tp_approval_requests,
                   tp_evidence_anchors, tp_evidence_outbox, tp_evidence_events, tp_evidence_streams,
                   tp_reconciliation_queue, tp_execution_transitions, tp_execution_attempts,
                   tp_consumed_execution_permits
          """);
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void rollbackBeforeCommitRecordsUnknownThenLookupResolvesWithoutRedispatch(boolean approval) {
    Scenario scenario = scenario(approval, null, Fault.BEFORE_COMMIT);
    assertUncertain(scenario);
    assertPersisted(scenario, "unknown", 1);
    assertThat(attempt(scenario).transitions()).extracting(item -> item.reasonCode())
        .containsExactly("EXECUTION_CLAIMED", "DURABLE_COMPLETION_UNCONFIRMED");
    AtomicInteger lookups = new AtomicInteger();
    var worker = new ExecutionReconciliationWorker(state, candidate -> {
      assertThat(candidate.tenant()).isEqualTo(TENANT);
      assertThat(candidate.adapterId()).isEqualTo("simulator");
      assertThat(candidate.idempotencyKey()).isEqualTo(scenario.key());
      lookups.incrementAndGet();
      return new ReconciliationObservation("succeeded", "SIMULATOR_LOOKUP_CONFIRMED");
    }, CLOCK, "synthetic-reader", Duration.ofSeconds(30), Duration.ofSeconds(5));
    assertThat(worker.reconcileBatch(TENANT, 1).reconciled()).isEqualTo(1);
    assertThat(lookups).hasValue(1);
    assertPersisted(scenario, "succeeded", 0);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void lostCommitAcknowledgementPreservesSucceededAndDoesNotReportSuccess(boolean approval) {
    Scenario scenario = scenario(approval, null, Fault.AFTER_COMMIT);
    assertUncertain(scenario);
    assertPersisted(scenario, "succeeded", 0);
    assertThat(attempt(scenario).transitions()).hasSize(2);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void targetDenialWithRolledBackCompletionAlsoEntersLookupOnlyRecovery(boolean approval) {
    Scenario scenario = scenario(approval,
        new TargetExecutionException("SIMULATOR_DENIED", "Synthetic target denied."),
        Fault.BEFORE_COMMIT);
    assertUncertain(scenario);
    assertPersisted(scenario, "unknown", 1);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void lostFailedCommitAcknowledgementCannotDowngradeFailedToUnknown(boolean approval) {
    Scenario scenario = scenario(approval,
        new TargetExecutionException("SIMULATOR_DENIED", "Synthetic target denied."),
        Fault.AFTER_COMMIT);
    assertUncertain(scenario);
    assertPersisted(scenario, "failed", 0);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void lostUnknownCommitAcknowledgementDoesNotDuplicateTransitionOrQueue(boolean approval) {
    Scenario scenario = scenario(approval, new IllegalStateException("synthetic-outcome-lost"),
        Fault.AFTER_COMMIT);
    assertUncertain(scenario);
    assertPersisted(scenario, "unknown", 1);
    assertThat(attempt(scenario).transitions()).hasSize(2);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void unknownRecordingCommitAcknowledgementLossStillLeavesExactlyOneQueuedUnknown(boolean approval) {
    Scenario scenario = scenario(approval, null, Fault.BEFORE_COMMIT, Fault.AFTER_COMMIT);
    assertUncertain(scenario);
    assertPersisted(scenario, "unknown", 1);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void persistentOutageIsBoundedAndCannotTurnExecutingIntoRetryableAuthority(boolean approval) {
    Scenario scenario = scenario(approval, null, Fault.OUTAGE);
    assertUncertain(scenario);
    faults.clear();
    assertPersisted(scenario, "executing", 0);
  }

  private Scenario scenario(boolean approval, RuntimeException targetFailure, Fault... failures) {
    AtomicInteger targetCalls = new AtomicInteger();
    SimulatedTarget target = action -> {
      targetCalls.incrementAndGet();
      faults.arm(failures);
      if (targetFailure != null) throw targetFailure;
      return JsonSupport.MAPPER.createObjectNode().put("simulated", true);
    };
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER,
        "synthetic-completion-key", new byte[32], 30, CLOCK,
        () -> "permit-" + IDS.incrementAndGet(), new InMemoryPermitConsumptionStore());
    JsonNode policy = JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    JsonNode action;
    Runnable run;
    String approvalId;
    if (approval) {
      action = JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json"));
      var service = new HumanApprovalService(new PolicyEvaluator(), policy, permits, state, state,
          target, () -> SecurityReadiness.allReady(NOW), CLOCK,
          () -> "approval-" + IDS.incrementAndGet(), "urn:tracepermit:completion-test", "simulator", 300);
      approvalId = service.requestApproval(new TrustedTenantContext(TENANT), action)
          .approval().binding().approvalRequestId();
      service.review(new TrustedReviewerContext(TENANT, "user:reviewer@demo.invalid", "human", true,
          Set.of(HumanApprovalService.REVIEWER_ROLE), Set.of(HumanApprovalService.REVIEWER_SCOPE),
          "synthetic_oidc", "sha256:" + "7".repeat(64)), approvalId, "approve", "REVIEWED");
      run = () -> service.executeApproved(new TrustedTenantContext(TENANT), approvalId, action);
    } else {
      JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
      action = JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
      var service = new DurableExecutionService(new PolicyEvaluator(), policy, permits, state, target,
          () -> SecurityReadiness.allReady(NOW), CLOCK, "urn:tracepermit:completion-test", "simulator");
      approvalId = null;
      run = () -> service.execute(new TrustedTenantContext(TENANT), action);
    }
    return new Scenario(run, targetCalls,
        InMemoryExecutionLedger.idempotencyKey(action, CanonicalJson.sha256(action)), approvalId);
  }

  private void assertUncertain(Scenario scenario) {
    assertThatThrownBy(scenario.run()::run)
        .hasMessage("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH");
    assertThat(scenario.targetCalls()).hasValue(1);
    assertThat(faults.acquisitions).hasValue(2);
  }

  private ExecutionAttempt attempt(Scenario scenario) {
    return state.findByIdempotencyKey(TENANT, "simulator", scenario.key());
  }

  private void assertPersisted(Scenario scenario, String status, long queued) {
    faults.clear();
    ExecutionAttempt stored = attempt(scenario);
    assertThat(stored.status()).isEqualTo(status);
    assertThat(count("tp_execution_attempts")).isEqualTo(1);
    assertThat(count("tp_consumed_execution_permits")).isEqualTo(1);
    assertThat(count("tp_reconciliation_queue")).isEqualTo(queued);
    if (scenario.approvalId() != null) {
      assertThat(state.find(TENANT, scenario.approvalId()).status()).isEqualTo("consumed");
    }
    assertEvidence();
    // Replay is either a bounded refusal or an already-recorded success, never another effect.
    try { scenario.run().run(); } catch (RuntimeException expectedRefusal) {
      assertThat(expectedRefusal.getMessage()).isIn("APPROVAL_NOT_EXECUTABLE",
          "EXECUTION_OUTCOME_UNKNOWN", "EXECUTION_ALREADY_IN_PROGRESS", "EXECUTION_PREVIOUSLY_FAILED");
    }
    assertThat(scenario.targetCalls()).hasValue(1);
    assertThat(count("tp_execution_attempts")).isEqualTo(1);
    assertThat(count("tp_consumed_execution_permits")).isEqualTo(1);
    assertThat(attempt(scenario)).isEqualTo(stored);
  }

  private void assertEvidence() {
    var evidence = state.readEvidence(TENANT);
    List<JsonNode> envelopes = evidence.stream().map(record -> {
      ObjectNode integrity = JsonSupport.MAPPER.createObjectNode()
          .put("streamId", record.streamId()).put("sequence", record.sequence())
          .put("algorithm", "sha256").put("previousEventHash", record.previousEventHash())
          .put("eventHash", record.eventHash());
      ObjectNode envelope = JsonSupport.MAPPER.createObjectNode();
      envelope.set("payload", record.payload());
      envelope.set("integrity", integrity);
      return (JsonNode) envelope;
    }).toList();
    assertThat(InMemoryEvidenceLedger.verifyRecords(envelopes, evidence.getLast().eventHash()).valid())
        .isTrue();
    assertThat(evidence.toString()).doesNotContain(PRIVATE_ERROR);
    assertThat(count("tp_evidence_outbox")).isEqualTo(evidence.size());
  }

  private long count(String table) {
    // Only private test constants above may choose a table in this disposable container.
    try (Connection connection = database.getConnection(); Statement statement = connection.createStatement();
         var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    } catch (SQLException failure) { throw new AssertionError(failure); }
  }

  private record Scenario(Runnable run, AtomicInteger targetCalls, String key, String approvalId) {}
  private enum Fault { BEFORE_COMMIT, AFTER_COMMIT, OUTAGE }

  private static final class FaultDataSource implements DataSource {
    private final DataSource delegate;
    private final ConcurrentLinkedQueue<Fault> commits = new ConcurrentLinkedQueue<>();
    private final AtomicInteger acquisitions = new AtomicInteger();
    private boolean tracking;
    private boolean outage;

    FaultDataSource(DataSource delegate) { this.delegate = delegate; }
    void arm(Fault... faults) {
      commits.addAll(Arrays.asList(faults));
      outage = faults.length > 0 && faults[0] == Fault.OUTAGE;
      tracking = true;
    }
    void clear() { commits.clear(); tracking = false; outage = false; }

    @Override public Connection getConnection() throws SQLException {
      if (tracking) acquisitions.incrementAndGet();
      if (outage) throw new SQLException(PRIVATE_ERROR);
      Connection connection = delegate.getConnection();
      return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
          new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            if ("commit".equals(method.getName())) {
              Fault fault = commits.poll();
              if (fault == Fault.BEFORE_COMMIT) throw new SQLException(PRIVATE_ERROR);
              if (fault == Fault.AFTER_COMMIT) {
                connection.commit();
                throw new SQLException(PRIVATE_ERROR);
              }
            }
            try { return method.invoke(connection, args); }
            catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
          });
    }
    @Override public Connection getConnection(String user, String password) { throw new UnsupportedOperationException(); }
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return Logger.getLogger("synthetic-completion"); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }
  }
}
