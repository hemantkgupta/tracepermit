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
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.DurableClaimResult;
import io.tracepermit.execution.DurableClaimStatus;
import io.tracepermit.execution.DurableEvidenceRecord;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.EvidenceIntegrityResult;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.policy.PolicyTransitionRecord;
import io.tracepermit.policy.PolicyVersionRecord;
import io.tracepermit.policy.RollbackRecord;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Simulator-only PostgreSQL backup and restore qualification test.
 *
 * <p>Demonstrates bounded database recovery behaviors using standard PostgreSQL {@code pg_dump}
 * custom archive ({@code -Fc}) and {@code pg_restore} into isolated Testcontainers fixtures.
 *
 * <p>Qualification Scenarios:
 * <ol>
 *   <li>Invalid archive rejection, plus characterization that empty-database bootstrap
 *       is not proof of successful recovery.
 *   <li>Post-consumption backup preserves exact snapshots, permits, attempts, evidence chains,
 *       and rejects replays with zero new target invocations.
 *   <li>Pending and approved requests preserve bindings and timestamps without automatic dispatch,
 *       and honor strict clock expiry.
 *   <li>Policy revisions, active selections, and Flyway metadata survive restore with exact parity.
 *   <li>CRITICAL ROLLBACK LIMIT (Hazard Proof): Point-in-time restore to a pre-consumption snapshot (T0)
 *       after real-world execution at T1 revives old unconsumed authority, causing a duplicate execution
 *       against an independent synthetic target counter. Proves why stale restores require quarantine and cannot
 *       rely on PostgreSQL alone.
 * </ol>
 */
final class PostgresBackupRestoreQualificationTest {

  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot", "."));
  private static final Instant T0 = Instant.parse("2026-10-17T00:00:00Z");
  private static final TenantPartition ACME = new TenantPartition("acme");
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine");
  private static final AtomicLong ID_GEN = new AtomicLong();
  private static KeyPair permitKey;

  @BeforeAll
  static void startContainer() throws Exception {
    permitKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    POSTGRES.start();
  }

  @AfterAll
  static void stopContainer() {
    POSTGRES.stop();
  }

  /**
   * A failed restore has no recovered state. The ordinary constructor can nevertheless
   * initialize an empty database, so it must not be treated as a recovery-readiness gate.
   */
  @Test
  void invalidArchiveFailsRestoreButEmptyBootstrapIsNotARecoveryGate() throws Exception {
    String destDb = randomDbName("inv_dst");
    String corruptDumpPath = "/tmp/corrupt_" + UUID.randomUUID() + ".dump";

    try {
      createDatabase(destDb);

      // Write corrupted / truncated archive data inside the container
      ExecResult writeCorrupt = POSTGRES.execInContainer(
          "sh", "-c", "umask 077; printf 'PGDMP_CORRUPTED_HEADER_DATA_TRUNCATED' > \"$1\"",
          "synthetic-corrupt-archive", corruptDumpPath);
      assertThat(writeCorrupt.getExitCode()).isZero();

      // Attempt restore: pg_restore MUST fail with non-zero exit code
      ExecResult restoreResult = restoreDatabase(destDb, corruptDumpPath);
      assertThat(restoreResult.getExitCode())
          .as("pg_restore must reject corrupted archive file")
          .isNotZero();
      assertThat(restoreResult.getStderr())
          .as("pg_restore stderr must cite archive corruption")
          .containsAnyOf("did not find magic string", "corrupt", "unrecognized file format", "error");

      // The failed restore target has no recovered state. These queries alone are not a startup gate:
      // The schema guard table does not exist, and no enforcement tables were restored.
      DataSource failedTarget = dataSourceFor(destDb);
      try (Connection connection = failedTarget.getConnection();
           Statement statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.executeQuery("SELECT contract_version FROM tp_enforcement_schema_guard WHERE singleton = TRUE"))
            .as("Schema guard table must not exist on failed restore target")
            .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> statement.executeQuery("SELECT count(*) FROM tp_consumed_execution_permits"))
            .as("Consumed permits table must not exist on failed restore target")
            .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> statement.executeQuery("SELECT count(*) FROM tp_approval_requests"))
            .as("Approval requests table must not exist on failed restore target")
            .isInstanceOf(SQLException.class);
      }

      assertThatThrownBy(() -> readContractVersion(failedTarget))
          .as("readContractVersion must fail on unpopulated database")
          .isInstanceOf(SQLException.class);

      // This is a gap characterization, NOT permission to reconnect a restored runtime.
      // No server, approval service or target is started against this failed destination.
      var emptyBootstrap = new PostgresDurableEnforcementState(
          failedTarget, Clock.fixed(T0, ZoneOffset.UTC), PostgresBackupRestoreQualificationTest::nextId);
      assertThat(readContractVersion(failedTarget)).isPositive();
      assertThat(emptyBootstrap.listApprovals(ACME, null, 50)).isEmpty();
      assertThat(emptyBootstrap.readEvidence(ACME)).isEmpty();
    } finally {
      POSTGRES.execInContainer("rm", "-f", corruptDumpPath);
      dropDatabase(destDb);
    }
  }

  /**
   * Scenario 1: A backup taken AFTER a request is consumed preserves exact action snapshot,
   * approval/permit consumption, execution attempt, and evidence chain. Recreating services
   * against the restored database preserves replay protection with zero new target invocations.
   */
  @Test
  void consumedExecutionAndApprovalSurvivesRestoreWithIntegrityAndZeroReplayDispatches()
      throws Exception {
    String sourceDb = randomDbName("con_src");
    String destDb = randomDbName("con_dst");
    String dumpPath = "/tmp/consumed_" + UUID.randomUUID() + ".dump";

    try {
      createDatabase(sourceDb);
      DataSource sourceDs = dataSourceFor(sourceDb);

      AtomicInteger targetInvocations = new AtomicInteger();
      Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

      PostgresDurableEnforcementState sourceState =
          new PostgresDurableEnforcementState(sourceDs, clock, PostgresBackupRestoreQualificationTest::nextId);
      HumanApprovalService sourceApprovalService = approvalService(
          sourceDs, sourceState, action -> {
            targetInvocations.incrementAndGet();
            return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
          }, clock);

      // Request approval with exact action snapshot persisted
      JsonNode modifyAction = approvalEvent();
      ApprovalExecutionResult pending = sourceApprovalService.requestApprovalWithSnapshot(
          new TrustedTenantContext(ACME), modifyAction);
      String requestId = pending.approval().binding().approvalRequestId();
      String actionHash = pending.approval().binding().actionHash();

      // Review and approve
      sourceApprovalService.review(
          reviewer(ACME, "user:bob@example.com"), requestId, "approve", "CHANGE_REVIEWED");

      // Execute and consume
      ApprovalExecutionResult executed = sourceApprovalService.executeApproved(
          new TrustedTenantContext(ACME), requestId, modifyAction);
      assertThat(executed.outcome()).isEqualTo("succeeded");
      assertThat(executed.approval().status()).isEqualTo("consumed");
      assertThat(targetInvocations).hasValue(1);

      // Verify pre-backup state in source DB
      assertThat(sourceState.find(ACME, requestId).status()).isEqualTo("consumed");
      assertThat(sourceState.findAction(ACME, requestId)).isEqualTo(modifyAction);
      List<DurableEvidenceRecord> sourceEvidence = sourceState.readEvidence(ACME);
      assertThat(sourceEvidence).hasSize(5); // requested, approved, consumed, claimed, succeeded
      assertValidEvidenceChain(sourceEvidence);
      ApprovalRequestState sourceRequest = sourceState.find(ACME, requestId);
      ExecutionAttempt sourceAttempt = sourceState.findByIdempotencyKey(
          ACME, "simulator", executed.attempt().idempotencyKey());
      List<JsonNode> sourceMigrations = readMigrationHistory(sourceDs);
      int sourceSchema = readContractVersion(sourceDs);

      // Take pg_dump custom archive
      ExecResult dumpResult = dumpDatabase(sourceDb, dumpPath);
      assertThat(dumpResult.getExitCode()).as("pg_dump must succeed").isZero();

      // Restore into fresh destination database
      createDatabase(destDb);
      ExecResult restoreResult = restoreDatabase(destDb, dumpPath);
      assertThat(restoreResult.getExitCode()).as("pg_restore must succeed").isZero();

      // Recreate all repositories and services on restored database, retaining target simulator counter
      DataSource destDs = dataSourceFor(destDb);
      assertThat(readMigrationHistory(destDs)).isEqualTo(sourceMigrations);
      assertThat(readContractVersion(destDs)).isEqualTo(sourceSchema);
      PostgresDurableEnforcementState restoredState =
          new PostgresDurableEnforcementState(destDs, clock, PostgresBackupRestoreQualificationTest::nextId);
      PostgresPermitConsumptionStore restoredPermitStore =
          new PostgresPermitConsumptionStore(destDs, ACME);
      HumanApprovalService restoredApprovalService = approvalService(
          destDs, restoredState, action -> {
            targetInvocations.incrementAndGet();
            return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
          }, clock);

      // 1. Assert exact action snapshot survives
      JsonNode restoredAction = restoredState.findAction(ACME, requestId);
      assertThat(restoredAction).isNotNull();
      assertThat(restoredAction).isEqualTo(modifyAction);
      assertThat(CanonicalJson.sha256(restoredAction)).isEqualTo(actionHash);

      // 2. Assert approval request state and binding integrity
      ApprovalRequestState restoredRequest = restoredState.find(ACME, requestId);
      assertThat(restoredRequest).isNotNull();
      assertThat(restoredRequest).isEqualTo(sourceRequest);
      assertThat(restoredRequest.status()).isEqualTo("consumed");
      assertThat(restoredRequest.binding().approvalRequestId()).isEqualTo(requestId);
      assertThat(restoredRequest.binding().actionHash()).isEqualTo(actionHash);
      assertThat(restoredRequest.binding().toolName()).isEqualTo("aws.ec2.update_security_group");

      // 3. Assert permit consumption survived
      String permitId = executed.attempt().permitId();
      assertThat(restoredPermitStore.isConsumed(permitId)).isTrue();

      // 4. Assert execution attempt integrity
      ExecutionAttempt restoredAttempt = restoredState.findByIdempotencyKey(
          ACME, "simulator", executed.attempt().idempotencyKey());
      assertThat(restoredAttempt).isNotNull();
      assertThat(restoredAttempt).isEqualTo(sourceAttempt);
      assertThat(restoredAttempt.status()).isEqualTo("succeeded");
      assertThat(restoredAttempt.attemptId()).isEqualTo(executed.attempt().attemptId());
      assertThat(restoredAttempt.transitions()).extracting(item -> item.to())
          .containsExactly("executing", "succeeded");

      // 5. Assert evidence chain survived and validates cryptographically
      List<DurableEvidenceRecord> restoredEvidence = restoredState.readEvidence(ACME);
      assertThat(restoredEvidence).hasSize(5);
      assertThat(restoredEvidence).isEqualTo(sourceEvidence);
      assertValidEvidenceChain(restoredEvidence);
      assertThat(restoredEvidence.getLast().eventHash())
          .isEqualTo(sourceEvidence.getLast().eventHash());

      // 6. Assert replay rejection across services with ZERO new target invocations
      assertThatThrownBy(() -> restoredApprovalService.executeApproved(
          new TrustedTenantContext(ACME), requestId, modifyAction))
          .as("Replay of consumed approval request must be rejected")
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("APPROVAL_NOT_EXECUTABLE");

      // Attempting direct permit replay on the restored state must also be rejected
      DurableExecutionClaim duplicateClaim = new DurableExecutionClaim(
          ACME,
          permitId,
          T0.plusSeconds(30),
          "simulator",
          "sha256:" + "f".repeat(64),
          "evt-replay",
          "trace-replay",
          "eval-replay",
          "user:alice@example.com",
          actionHash,
          "pol-aws-web-001",
          1,
          "allow",
          List.of("sha256:" + "e".repeat(64)),
          T0);
      DurableClaimResult claimConflict = restoredState.claim(duplicateClaim);
      assertThat(claimConflict.status()).isEqualTo(DurableClaimStatus.PERMIT_REPLAYED);

      // Verify simulator invocation count remained strictly at 1
      assertThat(targetInvocations)
          .as("Target invocation count must remain unchanged after rejected replays")
          .hasValue(1);

    } finally {
      POSTGRES.execInContainer("rm", "-f", dumpPath);
      dropDatabase(sourceDb);
      dropDatabase(destDb);
    }
  }

  /**
   * Scenario 2: Pending and approved requests captured in a backup retain exact identity, action,
   * and expiry bindings on restore. Store inspection and service startup do NOT dispatch them
   * automatically. Clock advance past expiry exercises fail-closed rejection without silent renewal.
   */
  @Test
  void pendingAndApprovedRequestsPreserveBindingsWithoutAutomaticDispatchAndExpireCorrectly()
      throws Exception {
    String sourceDb = randomDbName("pend_src");
    String destDb = randomDbName("pend_dst");
    String dumpPath = "/tmp/pending_" + UUID.randomUUID() + ".dump";

    try {
      createDatabase(sourceDb);
      DataSource sourceDs = dataSourceFor(sourceDb);

      AtomicInteger targetInvocations = new AtomicInteger();
      Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

      PostgresDurableEnforcementState sourceState =
          new PostgresDurableEnforcementState(sourceDs, clock, PostgresBackupRestoreQualificationTest::nextId);
      HumanApprovalService sourceApprovalService = approvalService(
          sourceDs, sourceState, action -> {
            targetInvocations.incrementAndGet();
            return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
          }, clock);

      // Request 1: Remains pending
      JsonNode action1 = approvalEvent();
      ApprovalExecutionResult pending1 = sourceApprovalService.requestApprovalWithSnapshot(
          new TrustedTenantContext(ACME), action1);
      String reqId1 = pending1.approval().binding().approvalRequestId();

      // Request 2: Reviewed and approved, but NOT consumed
      JsonNode action2 = approvalEvent();
      ((ObjectNode) action2).put("eventId", "018f47a2-f5ce-7a4b-8f54-80e5d3eaf099");
      ApprovalExecutionResult pending2 = sourceApprovalService.requestApprovalWithSnapshot(
          new TrustedTenantContext(ACME), action2);
      String reqId2 = pending2.approval().binding().approvalRequestId();
      sourceApprovalService.review(
          reviewer(ACME, "user:bob@example.com"), reqId2, "approve", "CHANGE_REVIEWED");

      // Verify pre-backup states and target invocations
      assertThat(sourceState.find(ACME, reqId1).status()).isEqualTo("pending");
      assertThat(sourceState.find(ACME, reqId2).status()).isEqualTo("approved");
      assertThat(targetInvocations).hasValue(0);
      ApprovalRequestState originalPending = sourceState.find(ACME, reqId1);
      ApprovalRequestState originalApproved = sourceState.find(ACME, reqId2);
      List<JsonNode> sourceMigrations = readMigrationHistory(sourceDs);
      int sourceSchema = readContractVersion(sourceDs);

      // Dump and restore
      ExecResult dumpResult = dumpDatabase(sourceDb, dumpPath);
      assertThat(dumpResult.getExitCode()).isZero();

      createDatabase(destDb);
      ExecResult restoreResult = restoreDatabase(destDb, dumpPath);
      assertThat(restoreResult.getExitCode()).isZero();

      // Recreate state on restored database at T0
      DataSource destDs = dataSourceFor(destDb);
      assertThat(readMigrationHistory(destDs)).isEqualTo(sourceMigrations);
      assertThat(readContractVersion(destDs)).isEqualTo(sourceSchema);
      PostgresDurableEnforcementState restoredState =
          new PostgresDurableEnforcementState(destDs, clock, PostgresBackupRestoreQualificationTest::nextId);

      // Assert store inspection does NOT dispatch any requests automatically
      assertThat(targetInvocations).as("Zero target dispatches on store restart/query").hasValue(0);

      ApprovalRequestState restoredReq1 = restoredState.find(ACME, reqId1);
      assertThat(restoredReq1).isNotNull();
      assertThat(restoredReq1).isEqualTo(originalPending);
      assertThat(restoredReq1.status()).isEqualTo("pending");
      assertThat(restoredReq1.binding().expiresAt()).isEqualTo(T0.plusSeconds(300));
      assertThat(restoredState.findAction(ACME, reqId1)).isEqualTo(action1);

      ApprovalRequestState restoredReq2 = restoredState.find(ACME, reqId2);
      assertThat(restoredReq2).isNotNull();
      assertThat(restoredReq2).isEqualTo(originalApproved);
      assertThat(restoredReq2.status()).isEqualTo("approved");
      assertThat(restoredReq2.binding().expiresAt()).isEqualTo(T0.plusSeconds(300));
      assertThat(restoredState.findAction(ACME, reqId2)).isEqualTo(action2);

      // Constructing the service also must not dispatch or change recovered authority.
      approvalService(destDs, restoredState, action -> {
        targetInvocations.incrementAndGet();
        return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
      }, clock);
      assertThat(targetInvocations).hasValue(0);
      assertThat(restoredState.find(ACME, reqId1)).isEqualTo(originalPending);
      assertThat(restoredState.find(ACME, reqId2)).isEqualTo(originalApproved);

      // Expiry is exclusive: reject exactly at T0 + 300 seconds, without sleeping.
      Clock expiredClock = Clock.fixed(T0.plusSeconds(300), ZoneOffset.UTC);
      PostgresDurableEnforcementState expiredState = new PostgresDurableEnforcementState(
          destDs, expiredClock, PostgresBackupRestoreQualificationTest::nextId);
      HumanApprovalService expiredService = approvalService(
          destDs, expiredState, action -> {
            targetInvocations.incrementAndGet();
            return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
          }, expiredClock);

      // Review on pending request after expiry must fail closed with APPROVAL_EXPIRED
      assertThatThrownBy(() -> expiredService.review(
          reviewer(ACME, "user:bob@example.com"), reqId1, "approve", "CHANGE_REVIEWED"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("APPROVAL_EXPIRED");
      assertThat(restoredState.find(ACME, reqId1).status()).isEqualTo("expired");

      // Execution on approved request after expiry must fail closed with APPROVAL_EXPIRED
      assertThatThrownBy(() -> expiredService.executeApproved(
          new TrustedTenantContext(ACME), reqId2, action2))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("APPROVAL_EXPIRED");
      assertThat(restoredState.find(ACME, reqId2).status()).isEqualTo("expired");

      // Target counter remains strictly 0 - no execution was permitted
      assertThat(targetInvocations).as("No execution occurs on expired requests").hasValue(0);

    } finally {
      POSTGRES.execInContainer("rm", "-f", dumpPath);
      dropDatabase(sourceDb);
      dropDatabase(destDb);
    }
  }

  /**
   * Scenario 3: Declared policy revision, active selection, transition history, and exact
   * Flyway metadata survive restore with complete parity.
   */
  @Test
  void policyRevisionsActiveSelectionAndFlywayMetadataSurviveRestore() throws Exception {
    String sourceDb = randomDbName("pol_src");
    String destDb = randomDbName("pol_dst");
    String dumpPath = "/tmp/policy_" + UUID.randomUUID() + ".dump";

    try {
      createDatabase(sourceDb);
      DataSource sourceDs = dataSourceFor(sourceDb);

      PostgresPolicyLifecycleStore sourceStore = new PostgresPolicyLifecycleStore(
          sourceDs, Clock.fixed(T0, ZoneOffset.UTC), PostgresBackupRestoreQualificationTest::nextId);

      String workspaceId = "ws-backup-qualification";
      String policyId = "pol-backup-test";

      ObjectNode v1Doc = samplePolicy(policyId, 1);
      ObjectNode v2Doc = samplePolicy(policyId, 2);
      v2Doc.put("description", "Version 2 description for backup test");

      // Stage v1, stage v2, activate v1, activate v2, rollback to v1
      PolicyVersionRecord v1Staged = sourceStore.stage(workspaceId, v1Doc, T0);
      PolicyVersionRecord v2Staged = sourceStore.stage(workspaceId, v2Doc, T0.plusSeconds(5));

      sourceStore.activate(workspaceId, policyId, 1, null, T0.plusSeconds(10), "operator-alice", "rollout v1");
      sourceStore.activate(workspaceId, policyId, 2, 1, T0.plusSeconds(20), "operator-bob", "rollout v2");
      RollbackRecord sourceRollback = sourceStore.rollback(
          workspaceId, policyId, 2, null, T0.plusSeconds(30), "operator-charlie", "anomaly detected");

      assertThat(sourceRollback.toVersion()).isEqualTo(1);
      PolicyVersionRecord sourceActive = sourceStore.activeVersion(workspaceId, policyId).orElseThrow();
      assertThat(sourceActive.version()).isEqualTo(1);

      // Verify Flyway history in source DB
      List<String> sourceMigrations = readInstalledMigrations(sourceDs);
      assertThat(sourceMigrations).contains("V1", "V2", "V3", "V4", "V5", "V6", "V7");
      List<JsonNode> sourceMigrationHistory = readMigrationHistory(sourceDs);
      int sourceSchema = readContractVersion(sourceDs);
      JsonNode sourcePointer = readActivePointer(sourceDs, workspaceId, policyId);
      assertThat(sourcePointer.path("activation_sequence").asLong()).isEqualTo(3);
      List<PolicyVersionRecord> sourceVersions = sourceStore.listVersions(workspaceId, policyId);
      List<PolicyTransitionRecord> sourceTransitions = sourceStore.activationHistory(workspaceId, policyId);
      List<RollbackRecord> sourceRollbacks = sourceStore.rollbackHistory(workspaceId, policyId);

      // Backup source database
      ExecResult dumpResult = dumpDatabase(sourceDb, dumpPath);
      assertThat(dumpResult.getExitCode()).isZero();

      // Restore into fresh destination database
      createDatabase(destDb);
      ExecResult restoreResult = restoreDatabase(destDb, dumpPath);
      assertThat(restoreResult.getExitCode()).isZero();

      // Recreate stores against restored database
      DataSource destDs = dataSourceFor(destDb);
      // Read raw restored metadata BEFORE constructors can apply missing migrations.
      assertThat(readMigrationHistory(destDs)).isEqualTo(sourceMigrationHistory);
      assertThat(readContractVersion(destDs)).isEqualTo(sourceSchema);
      assertThat(readActivePointer(destDs, workspaceId, policyId)).isEqualTo(sourcePointer);
      PostgresPolicyLifecycleStore restoredStore = new PostgresPolicyLifecycleStore(
          destDs, Clock.fixed(T0, ZoneOffset.UTC), PostgresBackupRestoreQualificationTest::nextId);

      // Verify active version
      Optional<PolicyVersionRecord> restoredActiveOpt = restoredStore.activeVersion(workspaceId, policyId);
      assertThat(restoredActiveOpt).isPresent();
      PolicyVersionRecord restoredActive = restoredActiveOpt.get();
      assertThat(restoredActive).isEqualTo(sourceActive);
      assertThat(restoredActive.version()).isEqualTo(1);
      assertThat(restoredActive.contentHash()).isEqualTo(v1Staged.contentHash());
      assertThat(restoredActive.document()).isEqualTo(v1Doc);

      // Verify version listing (ordered descending by version)
      List<PolicyVersionRecord> versions = restoredStore.listVersions(workspaceId, policyId);
      assertThat(versions).isEqualTo(sourceVersions);
      assertThat(versions.getFirst().contentHash()).isEqualTo(v2Staged.contentHash());
      assertThat(versions).hasSize(2);
      assertThat(versions).extracting(PolicyVersionRecord::version).containsExactly(2, 1);

      // Verify activation history (3 transitions: activate, activate, rollback)
      List<PolicyTransitionRecord> transitions = restoredStore.activationHistory(workspaceId, policyId);
      assertThat(transitions).isEqualTo(sourceTransitions);
      assertThat(transitions).hasSize(3);
      assertThat(transitions.get(0).transitionType()).isEqualTo("activate");
      assertThat(transitions.get(0).toVersion()).isEqualTo(1);
      assertThat(transitions.get(1).transitionType()).isEqualTo("activate");
      assertThat(transitions.get(1).toVersion()).isEqualTo(2);
      assertThat(transitions.get(2).transitionType()).isEqualTo("rollback");
      assertThat(transitions.get(2).toVersion()).isEqualTo(1);

      // Verify rollback history
      List<RollbackRecord> rollbacks = restoredStore.rollbackHistory(workspaceId, policyId);
      assertThat(rollbacks).isEqualTo(sourceRollbacks);
      assertThat(rollbacks).hasSize(1);
      assertThat(rollbacks.getFirst().fromVersion()).isEqualTo(2);
      assertThat(rollbacks.getFirst().toVersion()).isEqualTo(1);
      assertThat(rollbacks.getFirst().reason()).isEqualTo("anomaly detected");

      // Verify Flyway schema history parity
      List<String> destMigrations = readInstalledMigrations(destDs);
      assertThat(destMigrations).isEqualTo(sourceMigrations);
      assertThat(readMigrationHistory(destDs)).isEqualTo(sourceMigrationHistory);

      // Verify schema guard
      int contractVersion = readContractVersion(destDs);
      assertThat(contractVersion).isEqualTo(sourceSchema);

    } finally {
      POSTGRES.execInContainer("rm", "-f", dumpPath);
      dropDatabase(sourceDb);
      dropDatabase(destDb);
    }
  }

  /**
   * Scenario 4: CRITICAL ROLLBACK LIMIT (The Stale-Restore / Time-Travel Hazard).
   *
   * <p>Demonstrates the unavoidable vulnerability when an operator restores a database to a snapshot
   * taken at T0 (pre-consumption) after an action was already executed and consumed at T1 in the external world.
   *
   * <p>Because standard PostgreSQL backup/restore operates solely within the database boundary and has
   * no external witness or epoch fence, restoring the T0 snapshot revives the unconsumed authority.
   * If services are re-attached to the restored database without quarantine or revocation, calling
   * {@code executeApproved} DISPATCHES A SECOND TIME to the target, creating a duplicate execution.
   *
   * <p>This test proves why point-in-time recovery without external coordination is an UNSAFE RECOVERY GAP.
   */
  @Test
  void criticalRollbackLimitDemonstratesUnsafeAuthorityRevivalOnStaleRestore() throws Exception {
    String sourceDb = randomDbName("stale_src");
    String destDb = randomDbName("stale_dst");
    String dumpPathT0 = "/tmp/stale_t0_" + UUID.randomUUID() + ".dump";

    try {
      createDatabase(sourceDb);
      DataSource sourceDs = dataSourceFor(sourceDb);

      // The synthetic target ledger / invocation counter represents the external target reality.
      // It exists outside the database and tracks synthetic calls, never a real target effect.
      AtomicInteger externalTargetLedger = new AtomicInteger(0);

      Clock clockT0 = Clock.fixed(T0, ZoneOffset.UTC);
      PostgresDurableEnforcementState stateT0 =
          new PostgresDurableEnforcementState(sourceDs, clockT0, PostgresBackupRestoreQualificationTest::nextId);
      HumanApprovalService approvalServiceT0 = approvalService(
          sourceDs, stateT0, action -> {
            externalTargetLedger.incrementAndGet();
            return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
          }, clockT0);

      // Step 1: At T0, request and approve an action. Target has NOT been invoked yet.
      JsonNode modifyAction = approvalEvent();
      ApprovalExecutionResult pending = approvalServiceT0.requestApprovalWithSnapshot(
          new TrustedTenantContext(ACME), modifyAction);
      String requestId = pending.approval().binding().approvalRequestId();
      approvalServiceT0.review(
          reviewer(ACME, "user:bob@example.com"), requestId, "approve", "CHANGE_REVIEWED");

      assertThat(stateT0.find(ACME, requestId).status()).isEqualTo("approved");
      ApprovalRequestState approvedAtT0 = stateT0.find(ACME, requestId);
      assertThat(externalTargetLedger.get())
          .as("External target must have 0 invocations at T0")
          .isZero();

      // Step 2: Take backup at T0 (capturing the request in 'approved' but UNCONSUMED state)
      ExecResult dumpResult = dumpDatabase(sourceDb, dumpPathT0);
      assertThat(dumpResult.getExitCode()).isZero();

      // Step 3: At T1 (T0 + 10s), execute the approved action in the primary system.
      // The permit is consumed, database transitions to 'consumed', and target ledger records 1 effect.
      Clock clockT1 = Clock.fixed(T0.plusSeconds(10), ZoneOffset.UTC);
      PostgresDurableEnforcementState stateT1 = new PostgresDurableEnforcementState(
          sourceDs, clockT1, PostgresBackupRestoreQualificationTest::nextId);
      HumanApprovalService approvalServiceT1 = approvalService(
          sourceDs, stateT1, action -> {
            externalTargetLedger.incrementAndGet();
            return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
          }, clockT1);

      ApprovalExecutionResult executedT1 = approvalServiceT1.executeApproved(
          new TrustedTenantContext(ACME), requestId, modifyAction);
      assertThat(executedT1.outcome()).isEqualTo("succeeded");
      assertThat(stateT0.find(ACME, requestId).status()).isEqualTo("consumed");
      assertThat(externalTargetLedger.get())
          .as("External target must have exactly 1 invocation after T1 execution")
          .isEqualTo(1);

      // Verify that replay against the current database is blocked
      assertThatThrownBy(() -> approvalServiceT1.executeApproved(
          new TrustedTenantContext(ACME), requestId, modifyAction))
          .isInstanceOf(IllegalStateException.class);
      assertThat(externalTargetLedger.get()).isEqualTo(1);

      // Step 4: At T2 (T0 + 20s), a disaster occurs.
      // Restore the pre-consumption backup (T0) into this test's fresh destination database only.
      createDatabase(destDb);
      ExecResult restoreResult = restoreDatabase(destDb, dumpPathT0);
      assertThat(restoreResult.getExitCode()).isZero();

      // Step 5: Connect services to the restored database at T2 (before approval expiry of 300s).
      // The external target ledger STILL HAS the real-world execution record (count = 1).
      Clock clockT2 = Clock.fixed(T0.plusSeconds(20), ZoneOffset.UTC);
      DataSource destDs = dataSourceFor(destDb);
      PostgresDurableEnforcementState restoredState =
          new PostgresDurableEnforcementState(destDs, clockT2, PostgresBackupRestoreQualificationTest::nextId);
      HumanApprovalService restoredService = approvalService(
          destDs, restoredState, action -> {
            externalTargetLedger.incrementAndGet();
            return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
          }, clockT2);

      // Step 6: Inspect restored database state:
      // The restored database has NO KNOWLEDGE of the execution that occurred at T1!
      // The approval request is back in 'approved' status, and no consumed permit row exists.
      ApprovalRequestState staleRequestState = restoredState.find(ACME, requestId);
      assertThat(staleRequestState).isEqualTo(approvedAtT0);
      assertThat(restoredState.findAction(ACME, requestId)).isEqualTo(modifyAction);
      assertThat(new PostgresPermitConsumptionStore(destDs, ACME).isConsumed(executedT1.attempt().permitId()))
          .isFalse();
      assertThat(externalTargetLedger).hasValue(1);
      assertThat(staleRequestState.status())
          .as("Stale restore from T0 revived 'approved' status")
          .isEqualTo("approved");

      // Step 7: HAZARD DEMONSTRATION:
      // A caller or automated agent re-executes the request against the restored database.
      // Because the restored database believes the approval is unconsumed, it succeeds and
      // DISPATCHES TO THE TARGET A SECOND TIME!
      ApprovalExecutionResult duplicatedExecution = restoredService.executeApproved(
          new TrustedTenantContext(ACME), requestId, modifyAction);

      assertThat(duplicatedExecution.outcome()).isEqualTo("succeeded");
      assertThat(duplicatedExecution.approval().status()).isEqualTo("consumed");
      assertThat(duplicatedExecution.approval().binding()).isEqualTo(approvedAtT0.binding());
      assertThat(duplicatedExecution.attempt().idempotencyKey()).isEqualTo(executedT1.attempt().idempotencyKey());
      assertThat(duplicatedExecution.attempt().permitId()).isNotEqualTo(executedT1.attempt().permitId());
      assertThat(duplicatedExecution.attempt().attemptId()).isNotEqualTo(executedT1.attempt().attemptId());

      // ASSERT THE UNSAFE RECOVERY GAP:
      // The external target ledger has now been invoked TWICE for the same approval!
      assertThat(externalTargetLedger.get())
          .as("UNSAFE RECOVERY GAP: Stale database restore revived authority and caused duplicate target dispatch")
          .isEqualTo(2);

    } finally {
      POSTGRES.execInContainer("rm", "-f", dumpPathT0);
      dropDatabase(sourceDb);
      dropDatabase(destDb);
    }
  }

  @Test
  void restoredUnknownSchemaGuardIsRejectedWithoutRepair() throws Exception {
    String sourceDb = randomDbName("guard_src");
    String destDb = randomDbName("guard_dst");
    String dumpPath = "/tmp/guard_" + UUID.randomUUID() + ".dump";
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    try {
      createDatabase(sourceDb);
      DataSource source = dataSourceFor(sourceDb);
      new PostgresDurableEnforcementState(source, clock, PostgresBackupRestoreQualificationTest::nextId);
      List<JsonNode> history = readMigrationHistory(source);
      assertThat(dumpDatabase(sourceDb, dumpPath).getExitCode()).isZero();
      createDatabase(destDb);
      assertThat(restoreDatabase(destDb, dumpPath).getExitCode()).isZero();
      DataSource destination = dataSourceFor(destDb);
      assertThat(readMigrationHistory(destination)).isEqualTo(history);
      // Fixture-superuser corruption, not a runtime application's granted operation.
      try (var connection = destination.getConnection(); var sql = connection.createStatement()) {
        sql.execute("ALTER TABLE tp_enforcement_schema_guard DROP CONSTRAINT tp_enforcement_schema_guard_contract_version_check");
        sql.executeUpdate("UPDATE tp_enforcement_schema_guard SET contract_version = 999 WHERE singleton = TRUE");
      }
      assertThatThrownBy(() -> new PostgresDurableEnforcementState(
          destination, clock, PostgresBackupRestoreQualificationTest::nextId))
          .isInstanceOf(IllegalStateException.class).hasMessage("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
      assertThat(readContractVersion(destination)).isEqualTo(999);
      assertThat(readMigrationHistory(destination)).isEqualTo(history);
    } finally {
      POSTGRES.execInContainer("rm", "-f", dumpPath);
      dropDatabase(sourceDb);
      dropDatabase(destDb);
    }
  }

  // Helper methods: fixed tools and generated names inside this owned disposable container only.

  private static ExecResult dumpDatabase(String dbName, String dumpPath) throws Exception {
    requireFixtureName(dbName, dumpPath);
    assertThat(POSTGRES.execInContainer("test", "!", "-e", dumpPath).getExitCode()).isZero();
    ExecResult result = POSTGRES.execInContainer(
        "sh", "-c", "umask 077; exec \"$@\"", "synthetic-dump",
        "pg_dump",
        "-h", "/var/run/postgresql",
        "-U", POSTGRES.getUsername(),
        "-d", dbName,
        "-Fc",
        "-f", dumpPath);
    if (result.getExitCode() == 0) assertPrivateArchive(dumpPath);
    return result;
  }

  private static ExecResult restoreDatabase(String dbName, String dumpPath) throws Exception {
    requireFixtureName(dbName, dumpPath);
    assertPrivateArchive(dumpPath);
    try (var connection = dataSourceFor(dbName).getConnection(); var sql = connection.createStatement();
         var result = sql.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getLong(1)).as("Restore destination must be a fresh fixture").isZero();
    }
    return POSTGRES.execInContainer(
        "pg_restore",
        "--single-transaction", "--exit-on-error",
        "-h", "/var/run/postgresql",
        "-U", POSTGRES.getUsername(),
        "-d", dbName,
        dumpPath);
  }

  private static void assertPrivateArchive(String path) throws Exception {
    ExecResult metadata = POSTGRES.execInContainer("stat", "-c", "%a:%s", path);
    assertThat(metadata.getExitCode()).isZero();
    String[] fields = metadata.getStdout().trim().split(":");
    assertThat(fields).hasSize(2);
    assertThat(fields[0]).as("Archive must be owner-only").isEqualTo("600");
    assertThat(Long.parseLong(fields[1])).as("Synthetic archive must be bounded")
        .isBetween(1L, 4L * 1024 * 1024);
  }

  private static void requireFixtureName(String database, String archive) {
    requireFixtureDatabase(database);
    if (!archive.matches("/tmp/[a-z0-9_]+_[a-f0-9-]{36}\\.dump")) {
      throw new IllegalArgumentException("Only generated backup fixture targets are supported.");
    }
  }

  private static void requireFixtureDatabase(String database) {
    if (!database.matches("tp_[a-z_]+_[a-f0-9]{16}")) {
      throw new IllegalArgumentException("Only generated backup fixture databases are supported.");
    }
  }

  private static void createDatabase(String dbName) throws SQLException {
    requireFixtureDatabase(dbName);
    try (Connection connection = defaultDataSource().getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + dbName + " TEMPLATE template0");
    }
  }

  private static void dropDatabase(String dbName) {
    requireFixtureDatabase(dbName);
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

  private static String randomDbName(String prefix) {
    return "tp_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  private static String nextId() {
    return "id-" + ID_GEN.incrementAndGet();
  }

  private static HumanApprovalService approvalService(
      DataSource dataSource,
      PostgresDurableEnforcementState repository,
      io.tracepermit.application.SimulatedTarget target,
      Clock clock) {
    ExecutionPermitAuthority authority = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "approval-test-key",
        permitKey,
        30,
        clock,
        () -> "permit-" + nextId(),
        new PostgresPermitConsumptionStore(dataSource, ACME));
    return new HumanApprovalService(
        new PolicyEvaluator(),
        approvalPolicy(),
        authority,
        repository,
        repository,
        target,
        () -> SecurityReadiness.allReady(clock.instant()),
        clock,
        () -> "appr-" + nextId(),
        "urn:tracepermit:backup-qualification-executor",
        "simulator",
        300);
  }

  private static JsonNode approvalEvent() {
    return JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json"));
  }

  private static JsonNode approvalPolicy() {
    return JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
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

  private static List<String> readInstalledMigrations(DataSource dataSource) throws SQLException {
    String sql = "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank";
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      List<String> versions = new ArrayList<>();
      while (resultSet.next()) {
        versions.add("V" + resultSet.getString(1));
      }
      return versions;
    }
  }

  private static int readContractVersion(DataSource dataSource) throws SQLException {
    String sql = "SELECT contract_version FROM tp_enforcement_schema_guard WHERE singleton = TRUE";
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      if (resultSet.next()) {
        return resultSet.getInt(1);
      }
      throw new IllegalStateException("Missing tp_enforcement_schema_guard singleton");
    }
  }

  private static List<JsonNode> readMigrationHistory(DataSource dataSource) throws Exception {
    try (var connection = dataSource.getConnection(); var sql = connection.createStatement();
         var rows = sql.executeQuery("SELECT to_jsonb(h)::text FROM flyway_schema_history h ORDER BY installed_rank")) {
      List<JsonNode> history = new ArrayList<>();
      while (rows.next()) history.add(JsonSupport.MAPPER.readTree(rows.getString(1)));
      return List.copyOf(history);
    }
  }

  private static JsonNode readActivePointer(DataSource source, String workspace, String policy) throws Exception {
    try (var connection = source.getConnection(); var statement = connection.prepareStatement(
        "SELECT to_jsonb(p)::text FROM tp_active_policies p WHERE workspace_id = ? AND policy_id = ?")) {
      statement.setString(1, workspace);
      statement.setString(2, policy);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        JsonNode pointer = JsonSupport.MAPPER.readTree(rows.getString(1));
        assertThat(rows.next()).isFalse();
        return pointer;
      }
    }
  }

  private static void assertValidEvidenceChain(List<DurableEvidenceRecord> evidence) {
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
}
