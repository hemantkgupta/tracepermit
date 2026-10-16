package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.HumanApprovalService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.TrustedReviewerContext;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.execution.ApprovalConsumptionResult;
import io.tracepermit.execution.ApprovalConsumptionStatus;
import io.tracepermit.execution.ApprovalDecision;
import io.tracepermit.execution.ApprovalExecutionClaim;
import io.tracepermit.execution.ApprovalPolicyBinding;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.policy.ActivePolicySnapshot;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.policy.PolicyLifecycleStore;
import io.tracepermit.security.ExecutionPermitAuthority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Forced PostgreSQL interleavings and fault cases; all identities and targets are synthetic. */
final class PostgresPolicyGenerationBoundaryTest {

  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine");
  private static final AtomicLong ID_COUNTER = new AtomicLong();
  private static final String WORKSPACE_ID = "ws-bound-test";
  private static final String POLICY_ID = "pol-bound-test";
  private static final TenantPartition TENANT = new TenantPartition(WORKSPACE_ID);

  private static KeyPair permitKey;
  private DataSource dataSource;
  private PostgresPolicyLifecycleStore store;
  private PostgresDurableEnforcementState state;

  @BeforeAll
  static void startPostgres() throws Exception {
    POSTGRES.start();
    permitKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @BeforeEach
  void resetDatabase() throws SQLException {
    dataSource = configuredDataSource();
    store = new PostgresPolicyLifecycleStore(dataSource, CLOCK, () -> "plt-" + ID_COUNTER.incrementAndGet());
    state = new PostgresDurableEnforcementState(dataSource, CLOCK, () -> "claim-" + ID_COUNTER.incrementAndGet());
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          TRUNCATE tp_approval_policy_bindings,
                   tp_approval_action_snapshots, tp_approval_transitions, tp_approval_requests,
                   tp_evidence_anchors, tp_evidence_outbox,
                   tp_evidence_events, tp_evidence_streams,
                   tp_reconciliation_queue,
                   tp_execution_transitions, tp_execution_attempts,
                   tp_consumed_execution_permits,
                   tp_policy_lifecycle_transitions, tp_active_policies, tp_policy_versions
          """);
    }
  }

  @Test
  void claimWinningShareLockMustCommitBeforeActivationCanProceed() throws Exception {
    ApprovalExecutionClaim claim = approvedClaim(true);
    GateSource gate = new GateSource(GateMode.AFTER_POLICY_LOCK);
    var gatedState = new PostgresDurableEnforcementState(gate.source, CLOCK,
        () -> "gate-" + ID_COUNTER.incrementAndGet());
    gate.armed.set(true);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ApprovalConsumptionResult> claiming = pool.submit(() -> gatedState.consumeAndClaim(claim));
      assertThat(gate.paused.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> activation = pool.submit(() -> store.activate(
          WORKSPACE_ID, POLICY_ID, 2, 1, NOW.plusSeconds(10), null, "review activation"));
      assertThat(awaitActivePolicyLockWait()).as("activator actually waits on PostgreSQL lock").isTrue();
      assertThat(activation.isDone()).isFalse();
      gate.release.countDown();
      assertThat(claiming.get(10, TimeUnit.SECONDS).status()).isEqualTo(ApprovalConsumptionStatus.STARTED);
      activation.get(10, TimeUnit.SECONDS);
      assertThat(state.find(TENANT, claim.binding().approvalRequestId()).status()).isEqualTo("consumed");
      assertThat(rowCount("tp_execution_attempts")).isEqualTo(1);
      assertThat(rowCount("tp_consumed_execution_permits")).isEqualTo(1);
      assertThat(store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow().version()).isEqualTo(2);
    } finally {
      gate.release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void activationWinningUncommittedUpdateMustRejectWaitingClaimWithoutMutation() throws Exception {
    ApprovalExecutionClaim claim = approvedClaim(true);
    int evidenceBefore = state.readEvidence(TENANT).size();
    GateSource gate = new GateSource(GateMode.BEFORE_COMMIT);
    var gatedStore = new PostgresPolicyLifecycleStore(gate.source, CLOCK,
        () -> "gate-policy-" + ID_COUNTER.incrementAndGet());
    gate.armed.set(true);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> activation = pool.submit(() -> gatedStore.activate(
          WORKSPACE_ID, POLICY_ID, 2, 1, NOW.plusSeconds(10), null, "review activation"));
      assertThat(gate.paused.await(5, TimeUnit.SECONDS)).isTrue();
      Future<ApprovalConsumptionResult> claiming = pool.submit(() -> state.consumeAndClaim(claim));
      assertThat(awaitActivePolicyLockWait()).as("claim actually waits on PostgreSQL lock").isTrue();
      assertThat(claiming.isDone()).isFalse();
      gate.release.countDown();
      activation.get(10, TimeUnit.SECONDS);
      assertThat(claiming.get(10, TimeUnit.SECONDS).status())
          .isEqualTo(ApprovalConsumptionStatus.STALE_POLICY_GENERATION);
      assertThat(rowCount("tp_execution_attempts")).isZero();
      assertThat(rowCount("tp_consumed_execution_permits")).isZero();
      assertThat(state.readEvidence(TENANT)).hasSize(evidenceBefore);
      assertThat(state.find(TENANT, claim.binding().approvalRequestId()).status()).isEqualTo("approved");
    } finally {
      gate.release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void creationRacingActivationKeepsOriginalSnapshotAndCannotExecute() {
    initialPolicies();
    ActivePolicySnapshot original = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();
    AtomicBoolean firstRead = new AtomicBoolean(true);
    PolicyLifecycleStore racingStore = (PolicyLifecycleStore) Proxy.newProxyInstance(
        PolicyLifecycleStore.class.getClassLoader(), new Class<?>[] {PolicyLifecycleStore.class},
        (proxy, method, args) -> {
          Object result = invoke(store, method, args);
          if (method.getName().equals("activePolicySnapshot") && firstRead.compareAndSet(true, false)) {
            store.activate(WORKSPACE_ID, POLICY_ID, 2, 1, NOW.plusSeconds(10), null, "racing creation");
          }
          return result;
        });
    AtomicInteger dispatches = new AtomicInteger();
    HumanApprovalService service = service(racingStore, state, dispatches);
    JsonNode event = sampleAction("creation-race");
    var request = service.requestApprovalWithSnapshot(new TrustedTenantContext(TENANT), event);
    String id = request.approval().binding().approvalRequestId();
    ApprovalPolicyBinding saved = state.findPolicyBinding(TENANT, id).orElseThrow();
    assertThat(saved.policyVersion()).isEqualTo(1);
    assertThat(saved.policyGeneration()).isEqualTo(1);
    assertThat(saved.policyContentHash()).isEqualTo(original.contentHash());
    assertThat(CanonicalJson.sha256(state.findAction(TENANT, id)))
        .isEqualTo(request.approval().binding().actionHash());
    service.review(reviewer(), id, "approve", "REVIEW_OK");
    assertThatThrownBy(() -> service.executeApproved(new TrustedTenantContext(TENANT), id, event))
        .hasMessage("STALE_POLICY_GENERATION");
    assertThat(dispatches.get()).isZero();
    assertThat(rowCount("tp_execution_attempts")).isZero();
    assertThat(rowCount("tp_consumed_execution_permits")).isZero();
  }

  @Test
  void oldClaimConstructorCannotSkipStoredBindingRevalidation() {
    ApprovalExecutionClaim bound = approvedClaim(true);
    store.activate(WORKSPACE_ID, POLICY_ID, 2, 1, NOW.plusSeconds(10), null, "review stale claim");
    var omitted = new ApprovalExecutionClaim(
        bound.binding(), bound.approvalDecisionId(), bound.executionClaim(), bound.occurredAt());
    assertThat(state.consumeAndClaim(omitted).status())
        .isEqualTo(ApprovalConsumptionStatus.STALE_POLICY_GENERATION);
    assertThat(rowCount("tp_execution_attempts")).isZero();
    assertThat(rowCount("tp_consumed_execution_permits")).isZero();
  }

  @Test
  void failingBindingInsertRollsBackPendingSnapshotAndEvidenceTogether() {
    initialPolicies();
    String id = "review-invalid-" + ID_COUNTER.incrementAndGet();
    JsonNode action = sampleAction(id);
    ApprovalBinding binding = approvalBinding(id, action, 3);
    var absentRevision = new ApprovalPolicyBinding(WORKSPACE_ID, POLICY_ID, 3, 3, hash('a'), NOW);
    assertThatThrownBy(() -> state.createPendingBound(binding, action, absentRevision))
        .isInstanceOf(PostgresStateException.class);
    assertThat(state.find(TENANT, id)).isNull();
    assertThat(state.findAction(TENANT, id)).isNull();
    assertThat(state.findPolicyBinding(TENANT, id)).isEmpty();
    assertThat(state.readEvidence(TENANT)).isEmpty();
  }

  @Test
  void lostClaimCommitAcknowledgementCannotDispatchOrBeRetriedAsFreshApproval() {
    initialPolicies();
    GateSource gate = new GateSource(GateMode.AFTER_COMMIT_FAILURE);
    var gatedState = new PostgresDurableEnforcementState(gate.source, CLOCK,
        () -> "gate-ack-" + ID_COUNTER.incrementAndGet());
    AtomicInteger dispatches = new AtomicInteger();
    HumanApprovalService service = service(store, gatedState, dispatches);
    JsonNode event = sampleAction("lost-ack");
    String id = service.requestApprovalWithSnapshot(new TrustedTenantContext(TENANT), event)
        .approval().binding().approvalRequestId();
    service.review(reviewer(), id, "approve", "REVIEW_OK");
    gate.armed.set(true);
    assertThatThrownBy(() -> service.executeApproved(new TrustedTenantContext(TENANT), id, event))
        .isInstanceOf(PostgresStateException.class);
    assertThat(dispatches.get()).isZero();
    assertThat(state.find(TENANT, id).status()).isEqualTo("consumed");
    assertThat(rowCount("tp_execution_attempts")).isEqualTo(1);
    assertThat(rowCount("tp_consumed_execution_permits")).isEqualTo(1);
    assertThatThrownBy(() -> service.executeApproved(new TrustedTenantContext(TENANT), id, event))
        .hasMessage("APPROVAL_NOT_EXECUTABLE");
    assertThat(dispatches.get()).isZero();
  }

  @Test
  void unboundLegacyClaimWithoutPolicyContextRemainsStaticCompatibilityNotDurableMode() {
    ApprovalExecutionClaim unbound = approvedClaim(false);
    store.activate(WORKSPACE_ID, POLICY_ID, 2, 1, NOW.plusSeconds(10), null, "review legacy boundary");
    AtomicInteger dispatches = new AtomicInteger();
    HumanApprovalService durable = service(store, state, dispatches);
    JsonNode event = state.findAction(TENANT, unbound.binding().approvalRequestId());
    assertThatThrownBy(() -> durable.executeApproved(new TrustedTenantContext(TENANT),
        unbound.binding().approvalRequestId(), event)).hasMessage("STALE_POLICY_GENERATION");
    assertThat(dispatches.get()).isZero();
    assertThat(rowCount("tp_execution_attempts")).isZero();
    var omitted = new ApprovalExecutionClaim(
        unbound.binding(), unbound.approvalDecisionId(), unbound.executionClaim(), unbound.occurredAt());
    // Characterization, not a guarantee of durable-mode rejection: trusted raw
    // callers that omit BOTH contexts retain the old static-mode port behavior.
    assertThat(state.consumeAndClaim(omitted).status()).isEqualTo(ApprovalConsumptionStatus.STARTED);
    assertThat(rowCount("tp_execution_attempts")).isEqualTo(1);
  }

  @Test
  void activationAfterJavaSnapshotCanMintPermitButCannotClaimOrDispatch() {
    initialPolicies();
    AtomicBoolean activateAfterRead = new AtomicBoolean();
    PolicyLifecycleStore racing = (PolicyLifecycleStore) Proxy.newProxyInstance(
        PolicyLifecycleStore.class.getClassLoader(), new Class<?>[] {PolicyLifecycleStore.class},
        (proxy, method, args) -> {
          Object result = invoke(store, method, args);
          if (method.getName().equals("activePolicySnapshot")
              && activateAfterRead.compareAndSet(true, false)) {
            store.activate(WORKSPACE_ID, POLICY_ID, 2, 1, NOW.plusSeconds(10), null, "pre-permit race");
          }
          return result;
        });
    AtomicInteger minted = new AtomicInteger();
    AtomicInteger dispatches = new AtomicInteger();
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER,
        "review-mint", permitKey, 30, CLOCK, () -> "review-minted-" + minted.incrementAndGet(),
        new PostgresPermitConsumptionStore(dataSource));
    var service = new HumanApprovalService(new PolicyEvaluator(), racing, WORKSPACE_ID, POLICY_ID,
        permits, state, state, action -> {
          dispatches.incrementAndGet();
          return JsonSupport.MAPPER.createObjectNode();
        }, () -> SecurityReadiness.allReady(NOW), CLOCK,
        () -> "review-precheck-" + ID_COUNTER.incrementAndGet(),
        "urn:tracepermit:review-executor", "simulator", 300);
    JsonNode event = sampleAction("pre-permit-race");
    String id = service.requestApprovalWithSnapshot(new TrustedTenantContext(TENANT), event)
        .approval().binding().approvalRequestId();
    service.review(reviewer(), id, "approve", "REVIEW_OK");
    int evidenceBefore = state.readEvidence(TENANT).size();
    activateAfterRead.set(true);
    assertThatThrownBy(() -> service.executeApproved(new TrustedTenantContext(TENANT), id, event))
        .hasMessage("STALE_POLICY_GENERATION");
    assertThat(minted.get()).isEqualTo(1);
    assertThat(dispatches.get()).isZero();
    assertThat(rowCount("tp_execution_attempts")).isZero();
    assertThat(rowCount("tp_consumed_execution_permits")).isZero();
    assertThat(state.readEvidence(TENANT)).hasSize(evidenceBefore);
    assertThat(state.find(TENANT, id).status()).isEqualTo("approved");
  }

  private void initialPolicies() {
    store.stage(WORKSPACE_ID, samplePolicy(POLICY_ID, 1), NOW);
    ObjectNode strict = samplePolicy(POLICY_ID, 2);
    ((ObjectNode) strict.path("defaultDecision")).put("outcome", "block");
    store.stage(WORKSPACE_ID, strict, NOW);
    store.activate(WORKSPACE_ID, POLICY_ID, 1, null, NOW, null, "review initial");
  }

  private ApprovalExecutionClaim approvedClaim(boolean bound) {
    initialPolicies();
    ActivePolicySnapshot snapshot = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();
    String id = "review-" + ID_COUNTER.incrementAndGet();
    JsonNode action = sampleAction(id);
    ApprovalBinding binding = approvalBinding(id, action, 1);
    ApprovalPolicyBinding policyBinding = new ApprovalPolicyBinding(WORKSPACE_ID, POLICY_ID, 1,
        snapshot.activationGeneration(), snapshot.contentHash(), NOW);
    if (bound) state.createPendingBound(binding, action, policyBinding);
    else state.createPending(binding, action);
    String decision = "review-decision-" + ID_COUNTER.incrementAndGet();
    state.decide(new ApprovalDecision(TENANT, id, decision, "reviewer-1", "human",
        hash('e'), "approve", "REVIEW_OK", NOW.plusSeconds(5)));
    return executionClaim(binding, decision, "review-permit-" + id, NOW.plusSeconds(10),
        bound ? policyBinding : null);
  }

  private HumanApprovalService service(PolicyLifecycleStore catalog,
      PostgresDurableEnforcementState approvals, AtomicInteger dispatches) {
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER,
        "review-ed25519", permitKey, 30, CLOCK, () -> "review-p-" + ID_COUNTER.incrementAndGet(),
        new PostgresPermitConsumptionStore(dataSource));
    return new HumanApprovalService(new PolicyEvaluator(), catalog, WORKSPACE_ID, POLICY_ID,
        permits, approvals, approvals, action -> {
          dispatches.incrementAndGet();
          return JsonSupport.MAPPER.createObjectNode().put("simulated", true);
        }, () -> SecurityReadiness.allReady(NOW), CLOCK,
        () -> "review-a-" + ID_COUNTER.incrementAndGet(),
        "urn:tracepermit:review-executor", "simulator", 300);
  }

  private static TrustedReviewerContext reviewer() {
    return new TrustedReviewerContext(TENANT, "user:bob@example.invalid", "human", true,
        Set.of(HumanApprovalService.REVIEWER_ROLE), Set.of(HumanApprovalService.REVIEWER_SCOPE),
        "synthetic-review", hash('f'));
  }

  private long rowCount(String table) {
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
         var rows = s.executeQuery("SELECT count(*) FROM " + table)) {
      rows.next();
      return rows.getLong(1);
    } catch (SQLException failure) {
      throw new AssertionError(failure);
    }
  }

  private boolean awaitActivePolicyLockWait() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
           var rows = s.executeQuery("""
               SELECT count(*) FROM pg_stat_activity
               WHERE datname = current_database() AND pid <> pg_backend_pid()
                 AND wait_event_type = 'Lock' AND query LIKE '%tp_active_policies%'
               """)) {
        rows.next();
        if (rows.getInt(1) > 0) return true;
      }
      Thread.sleep(25);
    }
    return false;
  }

  private enum GateMode { AFTER_POLICY_LOCK, BEFORE_COMMIT, AFTER_COMMIT_FAILURE }

  private final class GateSource {
    final AtomicBoolean armed = new AtomicBoolean();
    final CountDownLatch paused = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final DataSource source;

    GateSource(GateMode mode) {
      source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
          new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
            Object value = invoke(dataSource, method, args);
            if (!method.getName().equals("getConnection")) return value;
            Connection connection = (Connection) value;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (cp, cm, ca) -> {
                  if (cm.getName().equals("commit") && mode == GateMode.BEFORE_COMMIT
                      && armed.compareAndSet(true, false)) pause();
                  Object result = invoke(connection, cm, ca);
                  if (cm.getName().equals("commit") && mode == GateMode.AFTER_COMMIT_FAILURE
                      && armed.compareAndSet(true, false)) {
                    throw new SQLException("synthetic lost commit acknowledgement", "08006");
                  }
                  if (cm.getName().equals("prepareStatement") && ca[0] instanceof String sql
                      && mode == GateMode.AFTER_POLICY_LOCK && sql.contains("FOR SHARE OF a")) {
                    PreparedStatement statement = (PreparedStatement) result;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                        new Class<?>[] {PreparedStatement.class}, (sp, sm, sa) -> {
                          Object rows = invoke(statement, sm, sa);
                          if (sm.getName().equals("executeQuery") && armed.compareAndSet(true, false)) pause();
                          return rows;
                        });
                  }
                  return result;
                });
          });
    }

    private void pause() throws Exception {
      paused.countDown();
      if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("review gate release timeout");
    }
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
    try { return method.invoke(target, args); }
    catch (InvocationTargetException failure) { throw failure.getCause(); }
  }

  private static ApprovalBinding approvalBinding(String approvalRequestId, JsonNode action, int version) {
    String actionHash = CanonicalJson.sha256(action);
    String requestHash = CanonicalJson.sha256(JsonSupport.MAPPER.createObjectNode()
        .put("approvalRequestId", approvalRequestId)
        .put("actionHash", actionHash));
    return new ApprovalBinding(
        TENANT,
        approvalRequestId,
        requestHash,
        actionHash,
        action.path("eventId").asText(),
        "eval-" + approvalRequestId,
        action.path("identity").path("subject").path("id").asText(),
        CanonicalJson.sha256(action.path("identity").path("actor")),
        POLICY_ID,
        version,
        "require_approval",
        action.path("action").path("toolName").asText(),
        CanonicalJson.sha256(action.path("action").path("arguments")),
        "default",
        NOW,
        NOW.plusSeconds(3600),
        1);
  }

  private static ApprovalExecutionClaim executionClaim(
      ApprovalBinding binding, String decisionId, String permitId, Instant at, ApprovalPolicyBinding policyBinding) {
    DurableExecutionClaim executionClaim = new DurableExecutionClaim(
        TENANT,
        permitId,
        at.plusSeconds(30),
        "simulator",
        hash('c'),
        binding.eventId(),
        "trace-1",
        binding.evaluationId(),
        binding.subjectId(),
        binding.actionHash(),
        binding.policyId(),
        binding.policyVersion(),
        "allow",
        List.of(hash('d')),
        at);
    return new ApprovalExecutionClaim(binding, decisionId, executionClaim, at, policyBinding);
  }

  private static JsonNode sampleAction(String approvalRequestId) {
    ObjectNode event = (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json"));
    event.put("eventId", UUID.nameUUIDFromBytes(
        approvalRequestId.getBytes(StandardCharsets.UTF_8)).toString());
    ((ObjectNode) event.path("identity").path("subject")).put("organizationId", WORKSPACE_ID);
    ((ObjectNode) event.path("identity").path("actor")).put("organizationId", WORKSPACE_ID);
    ((ObjectNode) event.path("clientClaims")).put("requestId", approvalRequestId);
    ((ObjectNode) event.path("clientClaims")).put("tenantId", WORKSPACE_ID);
    return event;
  }

  private static String hash(char digit) {
    return "sha256:" + String.valueOf(digit).repeat(64);
  }

  private static ObjectNode samplePolicy(String id, int version) {
    ObjectNode policy = (ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    policy.put("id", id);
    policy.put("version", version);
    return policy;
  }

  private static DataSource configuredDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }
}
