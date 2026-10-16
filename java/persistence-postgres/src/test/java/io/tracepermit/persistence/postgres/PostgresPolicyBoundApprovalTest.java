package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.ApprovalExecutionResult;
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
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.policy.ActivePolicySnapshot;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PostgresPolicyBoundApprovalTest {

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
  void stagedAndActivatedPolicyCanBeBoundToApprovalRequestAndExecuted() {
    ObjectNode policyV1 = samplePolicy(POLICY_ID, 1);
    store.stage(WORKSPACE_ID, policyV1, NOW);
    store.activate(WORKSPACE_ID, POLICY_ID, 1, null, NOW, null, "initial activation");

    ActivePolicySnapshot snapshot = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();
    assertThat(snapshot.activationGeneration()).isEqualTo(1L);
    assertThat(snapshot.version()).isEqualTo(1);

    String approvalRequestId = "req-success-" + ID_COUNTER.incrementAndGet();
    JsonNode action = sampleAction(approvalRequestId);
    ApprovalBinding binding = approvalBinding(approvalRequestId, action, 1);
    ApprovalPolicyBinding policyBinding = new ApprovalPolicyBinding(
        WORKSPACE_ID, POLICY_ID, snapshot.version(), snapshot.activationGeneration(),
        snapshot.contentHash(), NOW);

    ApprovalRequestState pending = state.createPendingBound(binding, action, policyBinding);
    assertThat(pending.status()).isEqualTo("pending");

    Optional<ApprovalPolicyBinding> foundBinding = state.findPolicyBinding(TENANT, approvalRequestId);
    assertThat(foundBinding).isPresent();
    assertThat(foundBinding.get()).isEqualTo(policyBinding);

    String decisionId = "dec-" + ID_COUNTER.incrementAndGet();
    state.decide(new ApprovalDecision(
        TENANT,
        approvalRequestId,
        decisionId,
        "reviewer-1",
        "human",
        hash('e'),
        "approve",
        "APPROVER_OK",
        NOW.plusSeconds(5)));

    ApprovalExecutionClaim claim = executionClaim(binding, decisionId, "permit-" + approvalRequestId, NOW.plusSeconds(10), policyBinding);
    ApprovalConsumptionResult result = state.consumeAndClaim(claim);

    assertThat(result.status()).isEqualTo(ApprovalConsumptionStatus.STARTED);
    assertThat(result.approval().status()).isEqualTo("consumed");
  }

  @Test
  void activationOfNewPolicyVersionRejectsPendingApprovalWithStalePolicyGeneration() {
    ObjectNode policyV1 = samplePolicy(POLICY_ID, 1);
    ObjectNode policyV2 = samplePolicy(POLICY_ID, 2);
    store.stage(WORKSPACE_ID, policyV1, NOW);
    store.activate(WORKSPACE_ID, POLICY_ID, 1, null, NOW, null, "v1 activation");

    ActivePolicySnapshot snap1 = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();
    String approvalRequestId = "req-stale-v2-" + ID_COUNTER.incrementAndGet();
    JsonNode action = sampleAction(approvalRequestId);
    ApprovalBinding binding = approvalBinding(approvalRequestId, action, 1);
    ApprovalPolicyBinding policyBindingV1 = new ApprovalPolicyBinding(
        WORKSPACE_ID, POLICY_ID, snap1.version(), snap1.activationGeneration(),
        snap1.contentHash(), NOW);

    state.createPendingBound(binding, action, policyBindingV1);
    String decisionId = "dec-" + ID_COUNTER.incrementAndGet();
    state.decide(new ApprovalDecision(
        TENANT,
        approvalRequestId,
        decisionId,
        "reviewer-1",
        "human",
        hash('e'),
        "approve",
        "APPROVER_OK",
        NOW.plusSeconds(5)));

    // Stage and activate v2 (advances generation to 2)
    store.stage(WORKSPACE_ID, policyV2, NOW.plusSeconds(10));
    store.activate(WORKSPACE_ID, POLICY_ID, 2, 1, NOW.plusSeconds(10), null, "v2 activation");

    ActivePolicySnapshot snap2 = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();
    assertThat(snap2.activationGeneration()).isEqualTo(2L);
    assertThat(snap2.version()).isEqualTo(2);

    // Attempt to execute the approval created under v1
    ApprovalExecutionClaim claim = executionClaim(binding, decisionId, "permit-" + approvalRequestId, NOW.plusSeconds(15), policyBindingV1);
    ApprovalConsumptionResult result = state.consumeAndClaim(claim);

    assertThat(result.status()).isEqualTo(ApprovalConsumptionStatus.STALE_POLICY_GENERATION);
    // Approval remains approved, not consumed
    ApprovalRequestState current = state.find(TENANT, approvalRequestId);
    assertThat(current.status()).isEqualTo("approved");
    assertThat(current.consumedAttemptId()).isNull();
  }

  @Test
  void rollbackRestoringSamePolicyVersionAndDocumentFailsStaleApprovalAcrossGenerationAdvancement() {
    ObjectNode policyV1 = samplePolicy(POLICY_ID, 1);
    ObjectNode policyV2 = samplePolicy(POLICY_ID, 2);
    store.stage(WORKSPACE_ID, policyV1, NOW);
    store.stage(WORKSPACE_ID, policyV2, NOW.plusSeconds(1));
    store.activate(WORKSPACE_ID, POLICY_ID, 1, null, NOW, null, "v1 activation");

    ActivePolicySnapshot snap1 = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();
    assertThat(snap1.activationGeneration()).isEqualTo(1L);

    String req1Id = "req-rb-1-" + ID_COUNTER.incrementAndGet();
    JsonNode action1 = sampleAction(req1Id);
    ApprovalBinding binding1 = approvalBinding(req1Id, action1, 1);
    ApprovalPolicyBinding bindingV1Gen1 = new ApprovalPolicyBinding(
        WORKSPACE_ID, POLICY_ID, snap1.version(), snap1.activationGeneration(),
        snap1.contentHash(), NOW);

    state.createPendingBound(binding1, action1, bindingV1Gen1);
    String dec1Id = "dec-1-" + ID_COUNTER.incrementAndGet();
    state.decide(new ApprovalDecision(
        TENANT,
        req1Id,
        dec1Id,
        "reviewer-1",
        "human",
        hash('e'),
        "approve",
        "APPROVER_OK",
        NOW.plusSeconds(5)));

    // Activate v2 (gen 2)
    store.activate(WORKSPACE_ID, POLICY_ID, 2, 1, NOW.plusSeconds(10), null, "v2 activation");
    ActivePolicySnapshot snap2 = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();
    assertThat(snap2.activationGeneration()).isEqualTo(2L);

    // Rollback to v1 (gen 3)
    store.rollback(WORKSPACE_ID, POLICY_ID, null, 1, NOW.plusSeconds(20), null, "rollback to v1");
    ActivePolicySnapshot snap3 = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();
    assertThat(snap3.version()).isEqualTo(1);
    assertThat(snap3.contentHash()).isEqualTo(snap1.contentHash());
    assertThat(snap3.activationGeneration()).isEqualTo(3L);

    // The approval created under generation 1 MUST be rejected with STALE_POLICY_GENERATION
    ApprovalExecutionClaim claim1 = executionClaim(binding1, dec1Id, "permit-" + req1Id, NOW.plusSeconds(25), bindingV1Gen1);
    ApprovalConsumptionResult result1 = state.consumeAndClaim(claim1);
    assertThat(result1.status()).isEqualTo(ApprovalConsumptionStatus.STALE_POLICY_GENERATION);

    // Fresh approval created under restored v1 (generation 3) MUST succeed
    String req2Id = "req-rb-2-" + ID_COUNTER.incrementAndGet();
    JsonNode action2 = sampleAction(req2Id);
    ApprovalBinding binding2 = approvalBinding(req2Id, action2, 1);
    ApprovalPolicyBinding bindingV1Gen3 = new ApprovalPolicyBinding(
        WORKSPACE_ID, POLICY_ID, snap3.version(), snap3.activationGeneration(),
        snap3.contentHash(), NOW.plusSeconds(25));

    state.createPendingBound(binding2, action2, bindingV1Gen3);
    String dec2Id = "dec-2-" + ID_COUNTER.incrementAndGet();
    state.decide(new ApprovalDecision(
        TENANT,
        req2Id,
        dec2Id,
        "reviewer-1",
        "human",
        hash('e'),
        "approve",
        "APPROVER_OK",
        NOW.plusSeconds(26)));

    ApprovalExecutionClaim claim2 = executionClaim(binding2, dec2Id, "permit-" + req2Id, NOW.plusSeconds(30), bindingV1Gen3);
    ApprovalConsumptionResult result2 = state.consumeAndClaim(claim2);
    assertThat(result2.status()).isEqualTo(ApprovalConsumptionStatus.STARTED);
    assertThat(result2.approval().status()).isEqualTo("consumed");
  }

  @Test
  void unboundLegacyApprovalFailsClosedWhenClaimedUnderPolicyBindingVerification() {
    ObjectNode policyV1 = samplePolicy(POLICY_ID, 1);
    store.stage(WORKSPACE_ID, policyV1, NOW);
    store.activate(WORKSPACE_ID, POLICY_ID, 1, null, NOW, null, "v1 activation");
    ActivePolicySnapshot snap1 = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();

    String legacyReqId = "req-legacy-" + ID_COUNTER.incrementAndGet();
    JsonNode action = sampleAction(legacyReqId);
    ApprovalBinding binding = approvalBinding(legacyReqId, action, 1);

    // Legacy creation without tp_approval_policy_bindings row
    state.createPending(binding, action);
    String decisionId = "dec-leg-" + ID_COUNTER.incrementAndGet();
    state.decide(new ApprovalDecision(
        TENANT,
        legacyReqId,
        decisionId,
        "reviewer-1",
        "human",
        hash('e'),
        "approve",
        "APPROVER_OK",
        NOW.plusSeconds(5)));

    assertThat(state.findPolicyBinding(TENANT, legacyReqId)).isEmpty();

    ApprovalPolicyBinding expectedBinding = new ApprovalPolicyBinding(
        WORKSPACE_ID, POLICY_ID, snap1.version(), snap1.activationGeneration(),
        snap1.contentHash(), NOW);
    ApprovalExecutionClaim claim = executionClaim(binding, decisionId, "permit-" + legacyReqId, NOW.plusSeconds(10), expectedBinding);

    // Attempting claim with policy verification MUST fail closed
    ApprovalConsumptionResult result = state.consumeAndClaim(claim);
    assertThat(result.status()).isEqualTo(ApprovalConsumptionStatus.STALE_POLICY_GENERATION);
  }

  @Test
  void endToEndHumanApprovalServiceWithPostgresLifecycleAndPersistence() {
    ObjectNode policyV1 = samplePolicy(POLICY_ID, 1);
    ObjectNode policyV2 = samplePolicy(POLICY_ID, 2);
    store.stage(WORKSPACE_ID, policyV1, NOW);
    store.stage(WORKSPACE_ID, policyV2, NOW.plusSeconds(1));
    store.activate(WORKSPACE_ID, POLICY_ID, 1, null, NOW, null, "v1 activation");

    var permits = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "fixture-ed25519",
        permitKey,
        30,
        CLOCK,
        () -> "permit-" + ID_COUNTER.incrementAndGet(),
        new PostgresPermitConsumptionStore(dataSource));

    AtomicInteger dispatches = new AtomicInteger();
    HumanApprovalService service = new HumanApprovalService(
        new PolicyEvaluator(),
        store,
        WORKSPACE_ID,
        POLICY_ID,
        permits,
        state,
        state,
        event -> {
          dispatches.incrementAndGet();
          return JsonSupport.MAPPER.createObjectNode().put("applied", true);
        },
        () -> SecurityReadiness.allReady(CLOCK.instant()),
        CLOCK,
        () -> "approval-" + ID_COUNTER.incrementAndGet(),
        "urn:tracepermit:approval-durable-test-executor",
        "simulator",
        300);

    JsonNode event = sampleAction("event-candidate");
    TrustedTenantContext tenantContext = new TrustedTenantContext(TENANT);
    ApprovalExecutionResult requested = service.requestApproval(tenantContext, event);
    assertThat(requested.outcome()).isEqualTo("pending_approval");
    String reqId = requested.approval().binding().approvalRequestId();

    TrustedReviewerContext reviewer = new TrustedReviewerContext(
        TENANT,
        "user:bob@example.invalid",
        "human",
        true,
        Set.of(HumanApprovalService.REVIEWER_ROLE),
        Set.of(HumanApprovalService.REVIEWER_SCOPE),
        "oidc",
        hash('f'));

    service.review(reviewer, reqId, "approve", "REVIEWER_AUTHORIZED");

    // Before execute, operator activates v2
    store.activate(WORKSPACE_ID, POLICY_ID, 2, 1, NOW.plusSeconds(10), null, "v2 activation");

    // Execute must fail closed with STALE_POLICY_GENERATION and zero target mutations
    assertThatThrownBy(() -> service.executeApproved(tenantContext, reqId, event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("STALE_POLICY_GENERATION");

    assertThat(dispatches.get()).isZero();
  }

  @Test
  void concurrentClaimVsActivationStrictSerialization() throws Exception {
    ObjectNode policyV1 = samplePolicy(POLICY_ID, 1);
    ObjectNode policyV2 = samplePolicy(POLICY_ID, 2);
    store.stage(WORKSPACE_ID, policyV1, NOW);
    store.stage(WORKSPACE_ID, policyV2, NOW.plusSeconds(1));
    store.activate(WORKSPACE_ID, POLICY_ID, 1, null, NOW, null, "v1 activation");
    ActivePolicySnapshot snap1 = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();

    String approvalRequestId = "req-race-" + ID_COUNTER.incrementAndGet();
    JsonNode action = sampleAction(approvalRequestId);
    ApprovalBinding binding = approvalBinding(approvalRequestId, action, 1);
    ApprovalPolicyBinding policyBindingV1 = new ApprovalPolicyBinding(
        WORKSPACE_ID, POLICY_ID, snap1.version(), snap1.activationGeneration(),
        snap1.contentHash(), NOW);

    state.createPendingBound(binding, action, policyBindingV1);
    String decisionId = "dec-race-" + ID_COUNTER.incrementAndGet();
    state.decide(new ApprovalDecision(
        TENANT,
        approvalRequestId,
        decisionId,
        "reviewer-1",
        "human",
        hash('e'),
        "approve",
        "APPROVER_OK",
        NOW.plusSeconds(5)));

    CountDownLatch startGate = new CountDownLatch(1);
    AtomicReference<ApprovalConsumptionStatus> claimStatus = new AtomicReference<>();
    AtomicReference<Exception> activationError = new AtomicReference<>();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> claimTask = executor.submit(() -> {
        try {
          startGate.await();
          ApprovalExecutionClaim claim = executionClaim(binding, decisionId, "permit-" + approvalRequestId, NOW.plusSeconds(10), policyBindingV1);
          claimStatus.set(state.consumeAndClaim(claim).status());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });

      Future<?> activateTask = executor.submit(() -> {
        try {
          startGate.await();
          store.activate(WORKSPACE_ID, POLICY_ID, 2, 1, NOW.plusSeconds(10), null, "v2 race");
        } catch (Exception e) {
          activationError.set(e);
        }
      });

      startGate.countDown();
      claimTask.get(10, TimeUnit.SECONDS);
      activateTask.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(activationError.get()).isNull();
    assertThat(claimStatus.get()).isNotNull();

    // Strict serialization: either claim won before activation (STARTED),
    // or activation won before claim (STALE_POLICY_GENERATION).
    assertThat(claimStatus.get()).isIn(
        ApprovalConsumptionStatus.STARTED,
        ApprovalConsumptionStatus.STALE_POLICY_GENERATION
    );

    ApprovalRequestState finalApproval = state.find(TENANT, approvalRequestId);
    if (claimStatus.get() == ApprovalConsumptionStatus.STARTED) {
      assertThat(finalApproval.status()).isEqualTo("consumed");
      assertThat(finalApproval.consumedAttemptId()).isNotNull();
    } else {
      assertThat(finalApproval.status()).isEqualTo("approved");
      assertThat(finalApproval.consumedAttemptId()).isNull();
    }
  }

  @Test
  void databaseTriggerEnforcesImmutablePolicyBinding() throws Exception {
    ObjectNode policyV1 = samplePolicy(POLICY_ID, 1);
    store.stage(WORKSPACE_ID, policyV1, NOW);
    store.activate(WORKSPACE_ID, POLICY_ID, 1, null, NOW, null, "v1 activation");
    ActivePolicySnapshot snapshot = store.activePolicySnapshot(WORKSPACE_ID, POLICY_ID).orElseThrow();

    String approvalRequestId = "req-imm-" + ID_COUNTER.incrementAndGet();
    JsonNode action = sampleAction(approvalRequestId);
    ApprovalBinding binding = approvalBinding(approvalRequestId, action, 1);
    ApprovalPolicyBinding policyBinding = new ApprovalPolicyBinding(
        WORKSPACE_ID, POLICY_ID, snapshot.version(), snapshot.activationGeneration(),
        snapshot.contentHash(), NOW);

    state.createPendingBound(binding, action, policyBinding);

    // Attempting direct UPDATE or DELETE on tp_approval_policy_bindings must fail via database trigger
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.executeUpdate(
          "UPDATE tp_approval_policy_bindings SET policy_version = 99 WHERE approval_request_id = '" + approvalRequestId + "'"))
          .isInstanceOf(SQLException.class);

      assertThatThrownBy(() -> statement.executeUpdate(
          "DELETE FROM tp_approval_policy_bindings WHERE approval_request_id = '" + approvalRequestId + "'"))
          .isInstanceOf(SQLException.class);
    }
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
    event.put("eventId", UUID.randomUUID().toString());
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
