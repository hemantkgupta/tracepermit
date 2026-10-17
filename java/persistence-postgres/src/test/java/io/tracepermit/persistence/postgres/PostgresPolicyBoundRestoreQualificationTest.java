package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.ApprovalExecutionResult;
import io.tracepermit.application.HumanApprovalService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.SimulatedTarget;
import io.tracepermit.application.TrustedReviewerContext;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.execution.ApprovalConsumptionResult;
import io.tracepermit.execution.ApprovalConsumptionStatus;
import io.tracepermit.execution.ApprovalExecutionClaim;
import io.tracepermit.execution.ApprovalPolicyBinding;
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.DurableEvidenceRecord;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.policy.ActivePolicySnapshot;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Simulator-only PostgreSQL backup and restore qualification for populated V9
 * policy-generation-bound approvals (ADR 0042, FR-007, FR-008, FR-013, SEC-005,
 * SEC-006, REL-001, REL-003, REL-005, OPS-004).
 *
 * <p>Demonstrates bounded database recovery behaviors using standard PostgreSQL
 * {@code pg_dump -Fc} custom archive format and {@code pg_restore} with
 * {@code --single-transaction --exit-on-error} into fresh, isolated Testcontainers fixtures.
 *
 * <p>Scenarios Qualified:
 * <ol>
 *   <li>Scenario A: Restored valid pending/bound approval survives restore with complete
 *       binding, canonical action, and Flyway/schema-guard parity verified BEFORE constructors;
 *       subsequent review and single execution succeed without auto-dispatch; replay rejected.
 *   <li>Scenario B: Restored consumed bound approval preserves exact bound state, attempt,
 *       and evidence records; resumption and direct claim return {@code APPROVAL_NOT_EXECUTABLE}
 *       and {@code APPROVAL_REPLAYED} with zero new target effects.
 *   <li>Scenario C: Restored stale binding after activation rejects resumption with
 *       {@code STALE_POLICY_GENERATION} and zero target invocations or consumed permits.
 *   <li>Scenario D: Restored stale binding after rollback (v1/gen1 -> v2/gen2 -> v1/gen3)
 *       rejects stale gen1 approval despite matching content hash and version number;
 *       freshly requested gen3 control executes normally.
 *   <li>Scenario E (HAZARD PROOF): Explicit stale-backup hazard demonstrates that restoring
 *       a T0 snapshot rolls back both approval state and active generation inside the database,
 *       enabling duplicate target execution (counter=2). Empirically demonstrates the
 *       <b>UNSAFE RECOVERY GAP</b>.
 * </ol>
 */
final class PostgresPolicyBoundRestoreQualificationTest {

  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot", "."));
  private static final Instant T0 = Instant.parse("2026-10-17T00:00:00Z");
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine");
  private static final AtomicLong ID_COUNTER = new AtomicLong();
  private static final Pattern DB_NAME_PATTERN = Pattern.compile("^tp_pbrq_[a-z0-9_]{5,35}$");

  private static KeyPair permitKey;

  @BeforeAll
  static void startContainer() throws Exception {
    POSTGRES.start();
    permitKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  @AfterAll
  static void stopContainer() {
    POSTGRES.stop();
  }

  /**
   * Scenario A: Restored valid pending/bound approval.
   *
   * <p>Persists a real nonempty {@code tp_approval_policy_bindings} row and matching policy
   * catalog/active generation. Verifies raw restored current-schema rows, guard, and Flyway metadata
   * BEFORE constructors can migrate or recreate anything. Confirms reconstructing services dispatches
   * zero effects, distinct synthetic reviewer can approve, exact resumption executes once, and
   * replay adds no dispatch.
   */
  @Test
  void scenarioA_restoredValidPendingBoundApproval() throws Exception {
    String sourceDb = randomDbName("scen_a_src");
    String destDb = randomDbName("scen_a_dst");
    String dumpPath = randomDumpPath("scen_a");

    String workspaceId = "ws-scen-a";
    String policyId = "pol-scen-a";
    TenantPartition tenant = new TenantPartition(workspaceId);
    ControllableClock clock = new ControllableClock(T0, ZoneOffset.UTC);
    AtomicInteger targetCounter = new AtomicInteger();
    SimulatedTarget target = event -> {
      targetCounter.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("applied", true);
    };

    try {
      createDatabase(sourceDb);
      DataSource sourceDs = dataSourceFor(sourceDb);
      PostgresDurableEnforcementState.migrate(
          sourceDs, PostgresDurableEnforcementState.MIGRATION_LOCATION);

      PostgresPolicyLifecycleStore sourceStore =
          new PostgresPolicyLifecycleStore(sourceDs, clock, () -> "plt-" + nextId());
      PostgresDurableEnforcementState sourceState =
          new PostgresDurableEnforcementState(sourceDs, clock, () -> "claim-" + nextId());
      HumanApprovalService sourceService =
          createApprovalService(sourceDs, sourceStore, sourceState, workspaceId, policyId, permitKey, target, clock);

      // Stage and activate v1 (gen 1)
      ObjectNode policyV1 = samplePolicy(policyId, 1);
      sourceStore.stage(workspaceId, policyV1, clock.instant());
      sourceStore.activate(workspaceId, policyId, 1, null, clock.instant(), null, "initial activation v1");

      ActivePolicySnapshot sourceSnap = sourceStore.activePolicySnapshot(workspaceId, policyId).orElseThrow();
      assertThat(sourceSnap.activationGeneration()).isEqualTo(1L);
      assertThat(sourceSnap.version()).isEqualTo(1);

      // Create pending bound approval via opt-in service
      String approvalRequestId = "req-a-" + nextId();
      JsonNode action = sampleAction(workspaceId, approvalRequestId);
      TrustedTenantContext tenantContext = new TrustedTenantContext(tenant);

      ApprovalExecutionResult reqResult = sourceService.requestApprovalWithSnapshot(tenantContext, action);
      assertThat(reqResult.outcome()).isEqualTo("pending_approval");
      String reqId = reqResult.approval().binding().approvalRequestId();

      ApprovalPolicyBinding sourceBinding = sourceState.findPolicyBinding(tenant, reqId).orElseThrow();
      assertThat(sourceBinding.workspaceId()).isEqualTo(workspaceId);
      assertThat(sourceBinding.policyId()).isEqualTo(policyId);
      assertThat(sourceBinding.policyVersion()).isEqualTo(1);
      assertThat(sourceBinding.policyGeneration()).isEqualTo(1L);
      assertThat(sourceBinding.policyContentHash()).isEqualTo(sourceSnap.contentHash());

      // Capture backup archive from source database
      var captured = captureDatabase(sourceDs);
      dumpDatabase(sourceDb, dumpPath);

      // Restore into fresh, empty destination database
      createDatabase(destDb);
      restoreDatabase(destDb, dumpPath);

      // STEP 1: Verify raw restored rows, guard, and Flyway metadata BEFORE constructors
      DataSource destDs = dataSourceFor(destDb);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);
      try (Connection conn = destDs.getConnection();
           Statement stmt = conn.createStatement()) {

        // Contract guard version must match the current package
        try (ResultSet rs = stmt.executeQuery(
            "SELECT contract_version FROM tp_enforcement_schema_guard WHERE singleton = TRUE")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt("contract_version")).isEqualTo(9);
        }

        // Flyway migration history must contain all 9 migrations successful
        try (ResultSet rs = stmt.executeQuery(
            "SELECT count(*) FROM flyway_schema_history WHERE success = TRUE")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt(1)).isEqualTo(9);
        }

        // Raw tp_approval_policy_bindings row check
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT tenant_id, approval_request_id, workspace_id, policy_id, policy_version, "
                + "policy_generation, policy_content_hash, bound_at "
                + "FROM tp_approval_policy_bindings WHERE tenant_id = ? AND approval_request_id = ?")) {
          ps.setString(1, tenant.value());
          ps.setString(2, reqId);
          try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("workspace_id")).isEqualTo(workspaceId);
            assertThat(rs.getString("policy_id")).isEqualTo(policyId);
            assertThat(rs.getInt("policy_version")).isEqualTo(1);
            assertThat(rs.getLong("policy_generation")).isEqualTo(1L);
            assertThat(rs.getString("policy_content_hash")).isEqualTo(sourceSnap.contentHash());
            assertThat(rs.getTimestamp("bound_at").toInstant()).isEqualTo(sourceBinding.boundAt());
          }
        }

        // Raw tp_approval_action_snapshots check
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT canonical_action FROM tp_approval_action_snapshots "
                + "WHERE tenant_id = ? AND approval_request_id = ?")) {
          ps.setString(1, tenant.value());
          ps.setString(2, reqId);
          try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            String snapshotJson = rs.getString("canonical_action");
            assertThat(CanonicalJson.sha256(JsonSupport.MAPPER.readTree(snapshotJson)))
                .isEqualTo(CanonicalJson.sha256(action));
          }
        }

        // Raw tp_approval_requests status check
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT status FROM tp_approval_requests WHERE tenant_id = ? AND approval_request_id = ?")) {
          ps.setString(1, tenant.value());
          ps.setString(2, reqId);
          try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("pending");
          }
        }

        // Raw tp_active_policies check
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT active_version, activation_sequence FROM tp_active_policies "
                + "WHERE workspace_id = ? AND policy_id = ?")) {
          ps.setString(1, workspaceId);
          ps.setString(2, policyId);
          try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("active_version")).isEqualTo(1);
            assertThat(rs.getLong("activation_sequence")).isEqualTo(1L);
          }
        }
      }

      // STEP 2: Recreate store, state, and approval service on destination database
      PostgresPolicyLifecycleStore destStore =
          new PostgresPolicyLifecycleStore(destDs, clock, () -> "plt-" + nextId());
      PostgresDurableEnforcementState destState =
          new PostgresDurableEnforcementState(destDs, clock, () -> "claim-" + nextId());
      HumanApprovalService destService =
          createApprovalService(destDs, destStore, destState, workspaceId, policyId, permitKey, target, clock);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);

      // Recheck schema guard and Flyway history after constructors
      try (Connection conn = destDs.getConnection();
           Statement stmt = conn.createStatement()) {
        try (ResultSet rs = stmt.executeQuery(
            "SELECT contract_version FROM tp_enforcement_schema_guard WHERE singleton = TRUE")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt("contract_version")).isEqualTo(9);
        }
        try (ResultSet rs = stmt.executeQuery(
            "SELECT count(*) FROM flyway_schema_history WHERE success = TRUE")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt(1)).isEqualTo(9);
        }
      }

      // Reconstructed active snapshot and binding match exactly
      ActivePolicySnapshot restoredSnap = destStore.activePolicySnapshot(workspaceId, policyId).orElseThrow();
      assertThat(restoredSnap).isEqualTo(sourceSnap);
      ApprovalPolicyBinding restoredBinding = destState.findPolicyBinding(tenant, reqId).orElseThrow();
      assertThat(restoredBinding).isEqualTo(sourceBinding);
      assertThat(CanonicalJson.sha256(destState.findAction(tenant, reqId)))
          .isEqualTo(CanonicalJson.sha256(action));

      // Restoring and recreating services MUST dispatch zero target effects
      assertThat(targetCounter.get())
          .as("Constructing services against restored DB must dispatch zero effects")
          .isZero();

      // Distinct trusted synthetic reviewer approves (fixture identities are synthetic and not external IdP qualification)
      TrustedReviewerContext reviewer = reviewer(tenant, "user:security-reviewer-2@example.invalid");
      ApprovalRequestState reviewed = destService.review(reviewer, reqId, "approve", "REVIEWER_AUTHORIZED");
      assertThat(reviewed.status()).isEqualTo("approved");

      // Exact resumption executes once
      ApprovalExecutionResult execResult = destService.executeApproved(tenantContext, reqId, action);
      assertThat(execResult.outcome()).isEqualTo("succeeded");
      assertThat(execResult.approval().status()).isEqualTo("consumed");
      assertThat(targetCounter.get())
          .as("Single approved execution must dispatch to target exactly once")
          .isEqualTo(1);

      // Replay attempt fails without adding dispatch
      assertThatThrownBy(() -> destService.executeApproved(tenantContext, reqId, action))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("APPROVAL_NOT_EXECUTABLE");

      assertThat(targetCounter.get())
          .as("Replay attempt must add zero target dispatches")
          .isEqualTo(1);

    } finally {
      cleanArchive(dumpPath);
      dropDatabase(sourceDb);
      dropDatabase(destDb);
    }
  }

  /**
   * Scenario B: Restored consumed bound approval.
   *
   * <p>Captures backup AFTER an approval has been executed and consumed. After restore and service
   * recreation, exact bound state, attempt records, and evidence records survive. Resumption and direct
   * claim return {@code APPROVAL_NOT_EXECUTABLE} and {@code APPROVAL_REPLAYED} with zero new effects.
   */
  @Test
  void scenarioB_restoredConsumedBoundApproval() throws Exception {
    String sourceDb = randomDbName("scen_b_src");
    String destDb = randomDbName("scen_b_dst");
    String dumpPath = randomDumpPath("scen_b");

    String workspaceId = "ws-scen-b";
    String policyId = "pol-scen-b";
    TenantPartition tenant = new TenantPartition(workspaceId);
    ControllableClock clock = new ControllableClock(T0, ZoneOffset.UTC);
    AtomicInteger targetCounter = new AtomicInteger();
    SimulatedTarget target = event -> {
      targetCounter.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("applied", true);
    };

    try {
      createDatabase(sourceDb);
      DataSource sourceDs = dataSourceFor(sourceDb);
      PostgresDurableEnforcementState.migrate(
          sourceDs, PostgresDurableEnforcementState.MIGRATION_LOCATION);

      PostgresPolicyLifecycleStore sourceStore =
          new PostgresPolicyLifecycleStore(sourceDs, clock, () -> "plt-" + nextId());
      PostgresDurableEnforcementState sourceState =
          new PostgresDurableEnforcementState(sourceDs, clock, () -> "claim-" + nextId());
      HumanApprovalService sourceService =
          createApprovalService(sourceDs, sourceStore, sourceState, workspaceId, policyId, permitKey, target, clock);

      ObjectNode policyV1 = samplePolicy(policyId, 1);
      sourceStore.stage(workspaceId, policyV1, clock.instant());
      sourceStore.activate(workspaceId, policyId, 1, null, clock.instant(), null, "initial v1");

      String approvalRequestId = "req-b-" + nextId();
      JsonNode action = sampleAction(workspaceId, approvalRequestId);
      TrustedTenantContext tenantContext = new TrustedTenantContext(tenant);

      ApprovalExecutionResult reqResult = sourceService.requestApprovalWithSnapshot(tenantContext, action);
      String reqId = reqResult.approval().binding().approvalRequestId();

      TrustedReviewerContext reviewer = reviewer(tenant, "user:reviewer-b@example.invalid");
      sourceService.review(reviewer, reqId, "approve", "REVIEWER_AUTHORIZED");

      // Execute on source: transitions to 'consumed' and dispatches target once
      ApprovalExecutionResult execResult = sourceService.executeApproved(tenantContext, reqId, action);
      assertThat(execResult.outcome()).isEqualTo("succeeded");
      assertThat(execResult.approval().status()).isEqualTo("consumed");
      assertThat(targetCounter.get()).isEqualTo(1);

      ApprovalPolicyBinding sourceBinding = sourceState.findPolicyBinding(tenant, reqId).orElseThrow();
      ApprovalRequestState sourceRequest = sourceState.find(tenant, reqId);
      var sourceAttempt = sourceState.findByIdempotencyKey(tenant, "simulator", execResult.attempt().idempotencyKey());
      var sourceEvidence = sourceState.readEvidence(tenant);
      assertThat(sourceEvidence).hasSize(5);
      assertValidEvidenceChain(sourceEvidence);

      // Back up consumed state
      var captured = captureDatabase(sourceDs);
      dumpDatabase(sourceDb, dumpPath);

      // Restore to destination DB
      createDatabase(destDb);
      restoreDatabase(destDb, dumpPath);

      DataSource destDs = dataSourceFor(destDb);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);
      PostgresPolicyLifecycleStore destStore =
          new PostgresPolicyLifecycleStore(destDs, clock, () -> "plt-" + nextId());
      PostgresDurableEnforcementState destState =
          new PostgresDurableEnforcementState(destDs, clock, () -> "claim-" + nextId());
      HumanApprovalService destService =
          createApprovalService(destDs, destStore, destState, workspaceId, policyId, permitKey, target, clock);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);

      // Preserved exact bound state
      ApprovalPolicyBinding restoredBinding = destState.findPolicyBinding(tenant, reqId).orElseThrow();
      assertThat(restoredBinding).isEqualTo(sourceBinding);

      // Preserved exact request and attempt status
      ApprovalRequestState restoredRequest = destState.find(tenant, reqId);
      assertThat(restoredRequest).isEqualTo(sourceRequest);
      assertThat(restoredRequest.status()).isEqualTo("consumed");
      assertThat(restoredRequest.consumedAttemptId()).isNotNull();
      assertThat(destState.findAction(tenant, reqId)).isEqualTo(action);
      assertThat(destState.findByIdempotencyKey(tenant, "simulator", sourceAttempt.idempotencyKey()))
          .isEqualTo(sourceAttempt);
      assertThat(destState.readEvidence(tenant)).isEqualTo(sourceEvidence);
      assertValidEvidenceChain(destState.readEvidence(tenant));
      assertThat(targetCounter).hasValue(1);

      // Calling executeApproved against consumed request throws APPROVAL_NOT_EXECUTABLE
      assertThatThrownBy(() -> destService.executeApproved(tenantContext, reqId, action))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("APPROVAL_NOT_EXECUTABLE");

      // Direct consumeAndClaim call returns APPROVAL_REPLAYED
      String decisionId = restoredRequest.approvalDecisionId();
      ApprovalExecutionClaim directClaim = executionClaim(
          restoredRequest.binding(), decisionId, "permit-replay-b", clock.instant().plusSeconds(10), restoredBinding);
      ApprovalConsumptionResult claimResult = destState.consumeAndClaim(directClaim);
      assertThat(claimResult.status()).isEqualTo(ApprovalConsumptionStatus.APPROVAL_REPLAYED);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);

      // Dispatches remain exactly 1
      assertThat(targetCounter.get())
          .as("Resumption and direct replay must add zero target dispatches")
          .isEqualTo(1);

    } finally {
      cleanArchive(dumpPath);
      dropDatabase(sourceDb);
      dropDatabase(destDb);
    }
  }

  /**
   * Scenario C: Restored stale binding after activation.
   *
   * <p>Approval is bound and approved under v1 generation 1, then v2 generation 2 is activated before
   * backup. After restore, resumption rejects with {@code STALE_POLICY_GENERATION} and zero target dispatch,
   * no consumed permit row, and no new execution attempt.
   */
  @Test
  void scenarioC_restoredStaleBindingAfterActivation() throws Exception {
    String sourceDb = randomDbName("scen_c_src");
    String destDb = randomDbName("scen_c_dst");
    String dumpPath = randomDumpPath("scen_c");

    String workspaceId = "ws-scen-c";
    String policyId = "pol-scen-c";
    TenantPartition tenant = new TenantPartition(workspaceId);
    ControllableClock clock = new ControllableClock(T0, ZoneOffset.UTC);
    AtomicInteger targetCounter = new AtomicInteger();
    SimulatedTarget target = event -> {
      targetCounter.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("applied", true);
    };

    try {
      createDatabase(sourceDb);
      DataSource sourceDs = dataSourceFor(sourceDb);
      PostgresDurableEnforcementState.migrate(
          sourceDs, PostgresDurableEnforcementState.MIGRATION_LOCATION);

      PostgresPolicyLifecycleStore sourceStore =
          new PostgresPolicyLifecycleStore(sourceDs, clock, () -> "plt-" + nextId());
      PostgresDurableEnforcementState sourceState =
          new PostgresDurableEnforcementState(sourceDs, clock, () -> "claim-" + nextId());
      HumanApprovalService sourceService =
          createApprovalService(sourceDs, sourceStore, sourceState, workspaceId, policyId, permitKey, target, clock);

      // Stage v1 and v2
      ObjectNode policyV1 = samplePolicy(policyId, 1);
      ObjectNode policyV2 = samplePolicy(policyId, 2);
      sourceStore.stage(workspaceId, policyV1, clock.instant());
      sourceStore.stage(workspaceId, policyV2, clock.instant().plusSeconds(1));

      // Activate v1 -> gen 1
      sourceStore.activate(workspaceId, policyId, 1, null, clock.instant(), null, "activate v1");

      // Request and approve under v1 gen 1
      String approvalRequestId = "req-c-" + nextId();
      JsonNode action = sampleAction(workspaceId, approvalRequestId);
      TrustedTenantContext tenantContext = new TrustedTenantContext(tenant);

      ApprovalExecutionResult reqResult = sourceService.requestApprovalWithSnapshot(tenantContext, action);
      String reqId = reqResult.approval().binding().approvalRequestId();

      TrustedReviewerContext reviewer = reviewer(tenant, "user:reviewer-c@example.invalid");
      sourceService.review(reviewer, reqId, "approve", "REVIEWER_AUTHORIZED");

      // Advance active policy to v2 -> gen 2 BEFORE backup
      clock.advance(Duration.ofSeconds(10));
      sourceStore.activate(workspaceId, policyId, 2, 1, clock.instant(), null, "activate v2");
      ActivePolicySnapshot snapV2 = sourceStore.activePolicySnapshot(workspaceId, policyId).orElseThrow();
      assertThat(snapV2.activationGeneration()).isEqualTo(2L);
      assertThat(snapV2.version()).isEqualTo(2);

      // Capture backup containing v1-bound approval and v2-active policy
      var captured = captureDatabase(sourceDs);
      dumpDatabase(sourceDb, dumpPath);

      // Restore to destination DB
      createDatabase(destDb);
      restoreDatabase(destDb, dumpPath);

      DataSource destDs = dataSourceFor(destDb);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);
      PostgresPolicyLifecycleStore destStore =
          new PostgresPolicyLifecycleStore(destDs, clock, () -> "plt-" + nextId());
      PostgresDurableEnforcementState destState =
          new PostgresDurableEnforcementState(destDs, clock, () -> "claim-" + nextId());
      HumanApprovalService destService =
          createApprovalService(destDs, destStore, destState, workspaceId, policyId, permitKey, target, clock);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);

      // Verify active snapshot is v2 gen 2
      ActivePolicySnapshot restoredActive = destStore.activePolicySnapshot(workspaceId, policyId).orElseThrow();
      assertThat(restoredActive.version()).isEqualTo(2);
      assertThat(restoredActive.activationGeneration()).isEqualTo(2L);

      // Verify bound policy on approval is v1 gen 1
      ApprovalPolicyBinding restoredBinding = destState.findPolicyBinding(tenant, reqId).orElseThrow();
      assertThat(restoredBinding.policyVersion()).isEqualTo(1);
      assertThat(restoredBinding.policyGeneration()).isEqualTo(1L);

      // Resumption via HumanApprovalService MUST reject with STALE_POLICY_GENERATION
      assertThatThrownBy(() -> destService.executeApproved(tenantContext, reqId, action))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("STALE_POLICY_GENERATION");

      // Direct consumeAndClaim call also rejects with STALE_POLICY_GENERATION
      ApprovalRequestState restoredReq = destState.find(tenant, reqId);
      ApprovalExecutionClaim staleClaim = executionClaim(
          restoredReq.binding(), restoredReq.approvalDecisionId(), "permit-stale-c", clock.instant().plusSeconds(15), restoredBinding);
      ApprovalConsumptionResult claimResult = destState.consumeAndClaim(staleClaim);
      assertThat(claimResult.status()).isEqualTo(ApprovalConsumptionStatus.STALE_POLICY_GENERATION);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);

      // Verifications: zero dispatches, zero consumed permits, approval remains approved
      assertThat(targetCounter.get())
          .as("Stale policy generation must prevent all target dispatches")
          .isZero();
      assertThat(destState.find(tenant, reqId).status()).isEqualTo("approved");

      try (Connection conn = destDs.getConnection();
           PreparedStatement ps = conn.prepareStatement(
               "SELECT count(*) FROM tp_consumed_execution_permits WHERE tenant_id = ?")) {
        ps.setString(1, tenant.value());
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt(1)).isZero();
        }
      }

      try (Connection conn = destDs.getConnection();
           PreparedStatement ps = conn.prepareStatement(
               "SELECT count(*) FROM tp_execution_attempts WHERE tenant_id = ?")) {
        ps.setString(1, tenant.value());
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt(1)).isZero();
        }
      }

    } finally {
      cleanArchive(dumpPath);
      dropDatabase(sourceDb);
      dropDatabase(destDb);
    }
  }

  /**
   * Scenario D: Restored stale binding after rollback.
   *
   * <p>Binds under v1/gen1, activates v2/gen2, then rolls back to v1/gen3 before backup.
   * After restore, version number and document hash match v1, but generation differs (gen1 vs gen3).
   * The original gen1 approval is still rejected without dispatch. A freshly requested and approved
   * gen3 control executes once.
   */
  @Test
  void scenarioD_restoredStaleBindingAfterRollback() throws Exception {
    String sourceDb = randomDbName("scen_d_src");
    String destDb = randomDbName("scen_d_dst");
    String dumpPath = randomDumpPath("scen_d");

    String workspaceId = "ws-scen-d";
    String policyId = "pol-scen-d";
    TenantPartition tenant = new TenantPartition(workspaceId);
    ControllableClock clock = new ControllableClock(T0, ZoneOffset.UTC);
    AtomicInteger targetCounter = new AtomicInteger();
    SimulatedTarget target = event -> {
      targetCounter.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("applied", true);
    };

    try {
      createDatabase(sourceDb);
      DataSource sourceDs = dataSourceFor(sourceDb);
      PostgresDurableEnforcementState.migrate(
          sourceDs, PostgresDurableEnforcementState.MIGRATION_LOCATION);

      PostgresPolicyLifecycleStore sourceStore =
          new PostgresPolicyLifecycleStore(sourceDs, clock, () -> "plt-" + nextId());
      PostgresDurableEnforcementState sourceState =
          new PostgresDurableEnforcementState(sourceDs, clock, () -> "claim-" + nextId());
      HumanApprovalService sourceService =
          createApprovalService(sourceDs, sourceStore, sourceState, workspaceId, policyId, permitKey, target, clock);

      // Stage v1 and v2
      ObjectNode policyV1 = samplePolicy(policyId, 1);
      ObjectNode policyV2 = samplePolicy(policyId, 2);
      sourceStore.stage(workspaceId, policyV1, clock.instant());
      sourceStore.stage(workspaceId, policyV2, clock.instant().plusSeconds(1));

      // 1. Activate v1 -> gen 1
      sourceStore.activate(workspaceId, policyId, 1, null, clock.instant(), null, "activate v1");

      // 2. Request and approve req1 under v1 gen 1
      String req1Id = "req-d-1-" + nextId();
      JsonNode action1 = sampleAction(workspaceId, req1Id);
      TrustedTenantContext tenantContext = new TrustedTenantContext(tenant);

      ApprovalExecutionResult req1Result = sourceService.requestApprovalWithSnapshot(tenantContext, action1);
      String actualReq1Id = req1Result.approval().binding().approvalRequestId();
      TrustedReviewerContext reviewer = reviewer(tenant, "user:reviewer-d@example.invalid");
      sourceService.review(reviewer, actualReq1Id, "approve", "REVIEWER_AUTHORIZED");

      // 3. Activate v2 -> gen 2
      clock.advance(Duration.ofSeconds(10));
      sourceStore.activate(workspaceId, policyId, 2, 1, clock.instant(), null, "activate v2");

      // 4. Rollback to v1 -> gen 3
      clock.advance(Duration.ofSeconds(10));
      sourceStore.rollback(workspaceId, policyId, 2, 1, clock.instant(), "operator", "rollback to v1");
      ActivePolicySnapshot snapGen3 = sourceStore.activePolicySnapshot(workspaceId, policyId).orElseThrow();
      assertThat(snapGen3.version()).isEqualTo(1);
      assertThat(snapGen3.activationGeneration()).isEqualTo(3L);

      // Back up database containing gen 1 bound approval and gen 3 active policy
      var captured = captureDatabase(sourceDs);
      dumpDatabase(sourceDb, dumpPath);

      // Restore to destination DB
      createDatabase(destDb);
      restoreDatabase(destDb, dumpPath);

      DataSource destDs = dataSourceFor(destDb);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);
      PostgresPolicyLifecycleStore destStore =
          new PostgresPolicyLifecycleStore(destDs, clock, () -> "plt-" + nextId());
      PostgresDurableEnforcementState destState =
          new PostgresDurableEnforcementState(destDs, clock, () -> "claim-" + nextId());
      HumanApprovalService destService =
          createApprovalService(destDs, destStore, destState, workspaceId, policyId, permitKey, target, clock);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);

      // In restored database: active policy is version 1, content hash matches v1, generation is 3
      ActivePolicySnapshot restoredActive = destStore.activePolicySnapshot(workspaceId, policyId).orElseThrow();
      assertThat(restoredActive.version()).isEqualTo(1);
      assertThat(restoredActive.activationGeneration()).isEqualTo(3L);
      assertThat(restoredActive.contentHash()).isEqualTo(snapGen3.contentHash());

      // Original req1 binding has version 1, content hash matches v1, BUT generation is 1
      ApprovalPolicyBinding req1Binding = destState.findPolicyBinding(tenant, actualReq1Id).orElseThrow();
      assertThat(req1Binding.policyVersion()).isEqualTo(1);
      assertThat(req1Binding.policyGeneration()).isEqualTo(1L);
      assertThat(req1Binding.policyContentHash()).isEqualTo(restoredActive.contentHash());

      // Executing req1 MUST reject with STALE_POLICY_GENERATION despite version and content hash matching
      assertThatThrownBy(() -> destService.executeApproved(tenantContext, actualReq1Id, action1))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("STALE_POLICY_GENERATION");

      assertThat(targetCounter.get())
          .as("Stale gen 1 approval must not dispatch target under gen 3")
          .isZero();
      assertThat(captureDatabase(destDs)).isEqualTo(captured);

      // Freshly requested, reviewed, and executed gen 3 control
      String req2Id = "req-d-2-" + nextId();
      JsonNode action2 = sampleAction(workspaceId, req2Id);
      ApprovalExecutionResult req2Result = destService.requestApprovalWithSnapshot(tenantContext, action2);
      String actualReq2Id = req2Result.approval().binding().approvalRequestId();

      ApprovalPolicyBinding req2Binding = destState.findPolicyBinding(tenant, actualReq2Id).orElseThrow();
      assertThat(req2Binding.policyVersion()).isEqualTo(1);
      assertThat(req2Binding.policyGeneration()).isEqualTo(3L);

      destService.review(reviewer, actualReq2Id, "approve", "REVIEWER_AUTHORIZED");

      ApprovalExecutionResult exec2Result = destService.executeApproved(tenantContext, actualReq2Id, action2);
      assertThat(exec2Result.outcome()).isEqualTo("succeeded");
      assertThat(exec2Result.approval().status()).isEqualTo("consumed");
      assertThat(targetCounter.get())
          .as("Fresh gen 3 control must execute and dispatch target once")
          .isEqualTo(1);

    } finally {
      cleanArchive(dumpPath);
      dropDatabase(sourceDb);
      dropDatabase(destDb);
    }
  }

  /**
   * Scenario E: Explicit stale-backup hazard in generation-bound mode.
   *
   * <p>At T0 back up an approved, unconsumed gen1 approval and gen1 active policy.
   * At T1 execute once on the source, then advance its policy generation to gen 2.
   * At T2 restore the T0 snapshot while still within the original approval lifetime.
   *
   * <p>Proves that approval status and active generation roll back together inside the DB,
   * making the unchanged old policy binding current again.
   * The independent target counter is 1 before restored resumption, but becomes 2 upon resumption,
   * demonstrating the <b>UNSAFE RECOVERY GAP</b>.
   */
  @Test
  void scenarioE_explicitStaleBackupHazardInGenerationBoundMode() throws Exception {
    String sourceDb = randomDbName("scen_e_src");
    String destDb = randomDbName("scen_e_dst");
    String dumpPathT0 = randomDumpPath("scen_e_t0");

    String workspaceId = "ws-scen-e";
    String policyId = "pol-scen-e";
    TenantPartition tenant = new TenantPartition(workspaceId);
    ControllableClock clock = new ControllableClock(T0, ZoneOffset.UTC);
    AtomicInteger targetCounter = new AtomicInteger();
    SimulatedTarget target = event -> {
      targetCounter.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("applied", true);
    };

    try {
      createDatabase(sourceDb);
      DataSource sourceDs = dataSourceFor(sourceDb);
      PostgresDurableEnforcementState.migrate(
          sourceDs, PostgresDurableEnforcementState.MIGRATION_LOCATION);

      PostgresPolicyLifecycleStore sourceStore =
          new PostgresPolicyLifecycleStore(sourceDs, clock, () -> "plt-" + nextId());
      PostgresDurableEnforcementState sourceState =
          new PostgresDurableEnforcementState(sourceDs, clock, () -> "claim-" + nextId());
      HumanApprovalService sourceService =
          createApprovalService(sourceDs, sourceStore, sourceState, workspaceId, policyId, permitKey, target, clock);

      // Stage v1 and v2
      ObjectNode policyV1 = samplePolicy(policyId, 1);
      ObjectNode policyV2 = samplePolicy(policyId, 2);
      sourceStore.stage(workspaceId, policyV1, clock.instant());
      sourceStore.stage(workspaceId, policyV2, clock.instant().plusSeconds(1));

      // Activate v1 -> gen 1
      sourceStore.activate(workspaceId, policyId, 1, null, clock.instant(), null, "activate v1");

      // T0: Request and approve under v1 gen 1
      String approvalRequestId = "req-e-" + nextId();
      JsonNode action = sampleAction(workspaceId, approvalRequestId);
      TrustedTenantContext tenantContext = new TrustedTenantContext(tenant);

      ApprovalExecutionResult reqResult = sourceService.requestApprovalWithSnapshot(tenantContext, action);
      String reqId = reqResult.approval().binding().approvalRequestId();

      TrustedReviewerContext reviewer = reviewer(tenant, "user:reviewer-e@example.invalid");
      sourceService.review(reviewer, reqId, "approve", "REVIEWER_AUTHORIZED");

      // Snapshot taken at T0: approval is 'approved' (unconsumed), active generation is 1
      var captured = captureDatabase(sourceDs);
      var originalRequest = sourceState.find(tenant, reqId);
      var originalBinding = sourceState.findPolicyBinding(tenant, reqId).orElseThrow();
      dumpDatabase(sourceDb, dumpPathT0);

      // T1: Source executes the approved action
      clock.advance(Duration.ofSeconds(10));
      ApprovalExecutionResult execT1 = sourceService.executeApproved(tenantContext, reqId, action);
      assertThat(execT1.outcome()).isEqualTo("succeeded");
      assertThat(execT1.approval().status()).isEqualTo("consumed");
      assertThat(targetCounter.get())
          .as("Target dispatched once on primary source at T1")
          .isEqualTo(1);

      // Advance source policy generation to 2 at T1
      sourceStore.activate(workspaceId, policyId, 2, 1, clock.instant(), null, "activate v2 at T1");
      ActivePolicySnapshot sourceSnapT1 = sourceStore.activePolicySnapshot(workspaceId, policyId).orElseThrow();
      assertThat(sourceSnapT1.activationGeneration()).isEqualTo(2L);
      assertThat(sourceState.find(tenant, reqId).status()).isEqualTo("consumed");
      assertThat(sourceState.findPolicyBinding(tenant, reqId)).contains(originalBinding);
      // Same-database quarantine is also rolled back by the older archive: not an external witness.
      assertThat(PostgresRecoveryQuarantine.quarantine(sourceDs, clock.instant()).mode()).isEqualTo("quarantined");

      // T2: Restore T0 snapshot into destination DB (still within 300s approval lifetime: T0 + 30s)
      clock.set(T0.plusSeconds(30));
      createDatabase(destDb);
      restoreDatabase(destDb, dumpPathT0);

      DataSource destDs = dataSourceFor(destDb);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);
      // Strict operator schema validation is not an external recovery fence either.
      PostgresDatabaseLifecycle.validateExisting(destDs);
      assertThat(PostgresRecoveryQuarantine.status(destDs).mode()).isEqualTo("ready");
      PostgresPolicyLifecycleStore destStore =
          new PostgresPolicyLifecycleStore(destDs, clock, () -> "plt-" + nextId());
      PostgresDurableEnforcementState destState =
          new PostgresDurableEnforcementState(destDs, clock, () -> "claim-" + nextId());
      HumanApprovalService destService =
          createApprovalService(destDs, destStore, destState, workspaceId, policyId, permitKey, target, clock);
      assertThat(captureDatabase(destDs)).isEqualTo(captured);

      // PROVE ROLLBACK IN RESTORED DATABASE:
      // Active generation and approval status roll back; the unchanged old gen1 binding matches again.
      ActivePolicySnapshot restoredActive = destStore.activePolicySnapshot(workspaceId, policyId).orElseThrow();
      assertThat(restoredActive.activationGeneration())
          .as("Restored database rolled back active generation from 2 to 1")
          .isEqualTo(1L);

      ApprovalRequestState restoredReq = destState.find(tenant, reqId);
      assertThat(restoredReq).isEqualTo(originalRequest);
      assertThat(clock.instant()).isBefore(restoredReq.binding().expiresAt());
      assertThat(restoredReq.status())
          .as("Restored database revived unconsumed 'approved' status")
          .isEqualTo("approved");

      ApprovalPolicyBinding restoredBinding = destState.findPolicyBinding(tenant, reqId).orElseThrow();
      assertThat(restoredBinding).isEqualTo(originalBinding);
      assertThat(restoredBinding.policyGeneration())
          .as("The original immutable binding is still generation 1")
          .isEqualTo(1L);

      // Target counter is STILL 1 before restored resumption
      assertThat(targetCounter.get()).isEqualTo(1);

      // HAZARD OBSERVATION: Resumption against restored service succeeds and causes second dispatch!
      ApprovalExecutionResult execT2 = destService.executeApproved(tenantContext, reqId, action);
      assertThat(execT2.outcome()).isEqualTo("succeeded");
      assertThat(execT2.approval().status()).isEqualTo("consumed");
      assertThat(execT2.attempt().permitId()).isNotEqualTo(execT1.attempt().permitId());
      assertThat(execT2.attempt().attemptId()).isNotEqualTo(execT1.attempt().attemptId());
      assertThat(execT2.attempt().idempotencyKey()).isEqualTo(execT1.attempt().idempotencyKey());
      assertThat(sourceState.find(tenant, reqId).status()).isEqualTo("consumed");
      assertThat(sourceStore.activePolicySnapshot(workspaceId, policyId).orElseThrow().activationGeneration())
          .isEqualTo(2L);

      // ASSERT THE UNSAFE RECOVERY GAP:
      // Target counter is now 2 because the database's internal generation rolled back with the snapshot!
      assertThat(targetCounter.get())
          .as("UNSAFE RECOVERY GAP: Stale database restore rolled back both approval state and active policy generation, causing duplicate target dispatch")
          .isEqualTo(2);

    } finally {
      cleanArchive(dumpPathT0);
      dropDatabase(sourceDb);
      dropDatabase(destDb);
    }
  }

  // --- Helper Methods ---

  /** Entire captured table rows, not just counts/status. Fixed identifiers only; no operator input. */
  private static Map<String, List<JsonNode>> captureDatabase(DataSource source) throws Exception {
    Map<String, List<JsonNode>> captured = new LinkedHashMap<>();
    for (String table : List.of(
        "flyway_schema_history", "tp_enforcement_schema_guard", "tp_recovery_quarantine", "tp_approval_requests",
        "tp_approval_transitions", "tp_approval_action_snapshots", "tp_approval_policy_bindings",
        "tp_policy_versions", "tp_active_policies", "tp_policy_lifecycle_transitions",
        "tp_consumed_execution_permits", "tp_execution_attempts", "tp_execution_transitions",
        "tp_evidence_streams", "tp_evidence_events", "tp_evidence_outbox",
        "tp_evidence_anchors", "tp_reconciliation_queue")) {
      List<JsonNode> values = new ArrayList<>();
      try (var connection = source.getConnection(); var statement = connection.createStatement();
           var rows = statement.executeQuery(
               "SELECT to_jsonb(r)::text FROM public." + table + " r ORDER BY to_jsonb(r)::text")) {
        while (rows.next()) values.add(JsonSupport.MAPPER.readTree(rows.getString(1)));
      }
      captured.put(table, List.copyOf(values));
    }
    assertThat(captured.get("tp_approval_policy_bindings")).hasSize(1);
    assertThat(captured.get("tp_approval_action_snapshots")).hasSize(1);
    assertThat(captured.get("tp_active_policies")).hasSize(1);
    assertThat(captured.get("tp_enforcement_schema_guard")).hasSize(1);
    assertThat(captured.get("tp_enforcement_schema_guard").getFirst().path("contract_version").asInt())
        .isEqualTo(9);
    var history = captured.get("flyway_schema_history");
    assertThat(history).hasSize(9);
    assertThat(history.stream().map(row -> row.path("version").asText()).sorted().toList())
        .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
    assertThat(history).allSatisfy(row -> {
      assertThat(row.path("success").asBoolean()).isTrue();
      assertThat(row.path("checksum").isIntegralNumber()).isTrue();
    });
    return Map.copyOf(captured);
  }

  private static void assertValidEvidenceChain(List<DurableEvidenceRecord> evidence) {
    List<JsonNode> records = evidence.stream().map(record -> {
      ObjectNode integrity = JsonSupport.MAPPER.createObjectNode();
      integrity.put("algorithm", "sha256");
      if (record.previousEventHash() == null) integrity.putNull("previousEventHash");
      else integrity.put("previousEventHash", record.previousEventHash());
      integrity.put("eventHash", record.eventHash());
      ObjectNode envelope = JsonSupport.MAPPER.createObjectNode();
      envelope.set("payload", record.payload());
      envelope.set("integrity", integrity);
      return (JsonNode) envelope;
    }).toList();
    assertThat(InMemoryEvidenceLedger.verifyRecords(records, evidence.getLast().eventHash()).valid())
        .isTrue();
  }

  private static ExecResult dumpDatabase(String dbName, String dumpPath) throws Exception {
    validateDbName(dbName);
    ExecResult result = POSTGRES.execInContainer(
        "env", "PGPASSWORD=" + POSTGRES.getPassword(),
        "pg_dump",
        "-U", POSTGRES.getUsername(),
        "-d", dbName,
        "-Fc",
        "-f", dumpPath);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException("pg_dump failed (exit " + result.getExitCode() + "): " + result.getStderr());
    }

    // Set 0600 permissions inside container
    ExecResult chmodResult = POSTGRES.execInContainer("chmod", "0600", dumpPath);
    if (chmodResult.getExitCode() != 0) {
      throw new IllegalStateException("chmod 0600 failed: " + chmodResult.getStderr());
    }

    // Conservative small-fixture size bound check (< 5 MB)
    ExecResult statResult = POSTGRES.execInContainer("stat", "-c", "%s", dumpPath);
    assertThat(statResult.getExitCode()).as("archive size check must succeed").isZero();
    long size = Long.parseLong(statResult.getStdout().trim());
    assertThat(size).isGreaterThan(0).isLessThan(5 * 1024 * 1024);
    ExecResult mode = POSTGRES.execInContainer("stat", "-c", "%a", dumpPath);
    assertThat(mode.getExitCode()).isZero();
    assertThat(mode.getStdout().trim()).isEqualTo("600");
    return result;
  }

  private static ExecResult restoreDatabase(String dbName, String dumpPath) throws Exception {
    validateDbName(dbName);
    ExecResult result = POSTGRES.execInContainer(
        "env", "PGPASSWORD=" + POSTGRES.getPassword(),
        "pg_restore",
        "-U", POSTGRES.getUsername(),
        "-d", dbName,
        "--single-transaction",
        "--exit-on-error",
        dumpPath);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException("pg_restore failed (exit " + result.getExitCode() + "): " + result.getStderr());
    }
    return result;
  }

  private static void createDatabase(String dbName) throws SQLException {
    validateDbName(dbName);
    try (Connection connection = defaultDataSource().getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + dbName + " TEMPLATE template0");
    }
  }

  private static void dropDatabase(String dbName) {
    if (dbName == null || !DB_NAME_PATTERN.matcher(dbName).matches()) {
      return;
    }
    try (Connection connection = defaultDataSource().getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute(
          "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
              + dbName
              + "' AND pid <> pg_backend_pid()");
      statement.execute("DROP DATABASE IF EXISTS " + dbName);
    } catch (SQLException ignored) {
    }
  }

  private static void cleanArchive(String dumpPath) {
    if (dumpPath != null && dumpPath.startsWith("/tmp/tp_pbrq_")) {
      try {
        POSTGRES.execInContainer("rm", "-f", dumpPath);
      } catch (Exception ignored) {
      }
    }
  }

  private static void validateDbName(String dbName) {
    if (dbName == null || !DB_NAME_PATTERN.matcher(dbName).matches()) {
      throw new IllegalArgumentException("Invalid test database name: " + dbName);
    }
  }

  private static String randomDbName(String prefix) {
    String name = "tp_pbrq_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    validateDbName(name);
    return name;
  }

  private static String randomDumpPath(String prefix) {
    return "/tmp/tp_pbrq_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ".dump";
  }

  private static DataSource defaultDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static DataSource dataSourceFor(String dbName) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    String defaultUrl = POSTGRES.getJdbcUrl();
    String dbUrl = defaultUrl.substring(0, defaultUrl.lastIndexOf('/') + 1) + dbName;
    source.setUrl(dbUrl);
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static String nextId() {
    return "id-" + ID_COUNTER.incrementAndGet();
  }

  private static ObjectNode sampleAction(String workspaceId, String approvalRequestId) {
    ObjectNode event = (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json"));
    event.put("eventId", UUID.randomUUID().toString());
    ((ObjectNode) event.path("identity").path("subject")).put("organizationId", workspaceId);
    ((ObjectNode) event.path("identity").path("actor")).put("organizationId", workspaceId);
    ((ObjectNode) event.path("clientClaims")).put("requestId", approvalRequestId);
    ((ObjectNode) event.path("clientClaims")).put("tenantId", workspaceId);
    return event;
  }

  private static ObjectNode samplePolicy(String id, int version) {
    ObjectNode policy = (ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    policy.put("id", id);
    policy.put("version", version);
    return policy;
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
        "sha256:" + "7".repeat(64));
  }

  private static HumanApprovalService createApprovalService(
      DataSource dataSource,
      PostgresPolicyLifecycleStore store,
      PostgresDurableEnforcementState state,
      String workspaceId,
      String policyId,
      KeyPair permitKey,
      SimulatedTarget target,
      Clock clock) {
    var permits = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "fixture-ed25519",
        permitKey,
        30,
        clock,
        () -> "permit-" + nextId(),
        new PostgresPermitConsumptionStore(dataSource, new TenantPartition(workspaceId)));
    return new HumanApprovalService(
        new PolicyEvaluator(),
        store,
        workspaceId,
        policyId,
        permits,
        state,
        state,
        target,
        () -> SecurityReadiness.allReady(clock.instant()),
        clock,
        () -> "approval-" + nextId(),
        "urn:tracepermit:policy-bound-recovery-executor",
        "simulator",
        300);
  }

  private static ApprovalExecutionClaim executionClaim(
      ApprovalBinding binding,
      String decisionId,
      String permitId,
      Instant at,
      ApprovalPolicyBinding policyBinding) {
    String dummyHash = "sha256:" + "a".repeat(64);
    DurableExecutionClaim claim = new DurableExecutionClaim(
        binding.tenant(),
        permitId,
        at.plusSeconds(30),
        "simulator",
        dummyHash,
        binding.eventId(),
        "trace-recovery-test",
        binding.evaluationId(),
        binding.subjectId(),
        binding.actionHash(),
        binding.policyId(),
        binding.policyVersion(),
        "allow",
        List.of(dummyHash),
        at);
    return new ApprovalExecutionClaim(binding, decisionId, claim, at, policyBinding);
  }

  /**
   * Fully controllable test clock that avoids thread sleeps and guarantees
   * deterministic advancement across time boundaries.
   */
  static final class ControllableClock extends Clock {
    private final ZoneId zone;
    private Instant current;

    ControllableClock(Instant start, ZoneId zone) {
      this.current = Objects.requireNonNull(start);
      this.zone = Objects.requireNonNull(zone);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new ControllableClock(current, zone);
    }

    @Override
    public Instant instant() {
      return current;
    }

    public synchronized void advance(Duration duration) {
      this.current = this.current.plus(duration);
    }

    public synchronized void set(Instant newInstant) {
      this.current = Objects.requireNonNull(newInstant);
    }
  }
}
