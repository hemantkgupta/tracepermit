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
import io.tracepermit.execution.DurableEvidenceRecord;
import io.tracepermit.execution.EvidenceIntegrityResult;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import io.tracepermit.simulator.StatefulTargetSimulator;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.LogManager;

/** Presenter-facing, simulator-only harness for the durable human-approval path. */
public final class RequestBoundApprovalDemoMain {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final TenantPartition TENANT = new TenantPartition("acme");
  private static final String REQUESTER = "user:alice@example.com";
  private static final String REVIEWER = "user:bob@example.com";

  private RequestBoundApprovalDemoMain() {}

  public static void main(String[] arguments) {
    boolean automatic = parseArguments(arguments);
    try {
      run(automatic);
    } catch (RuntimeException | IOException failure) {
      System.err.println("Scenario 3 failed safely: " + safeCode(failure));
      System.exit(1);
    }
  }

  private static void run(boolean automatic) throws IOException {
    heading();
    System.out.println("Starting an ephemeral PostgreSQL 16 container for durable approval state...");
    LogManager.getLogManager().reset();
    try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")) {
      postgres.start();
      executeScenario(automatic, dataSource(postgres));
    }
    System.out.println("\nScenario 3 complete. The disposable PostgreSQL container was removed.");
  }

  private static void executeScenario(boolean automatic, DataSource dataSource) throws IOException {
    Clock clock = Clock.systemUTC();
    AtomicLong identifiers = new AtomicLong();
    PostgresDurableEnforcementState durableState = new PostgresDurableEnforcementState(
        dataSource, clock, () -> "scenario3-state-" + identifiers.incrementAndGet());
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    AtomicInteger targetCalls = new AtomicInteger();
    byte[] ephemeralPermitKey = new byte[32];
    new SecureRandom().nextBytes(ephemeralPermitKey);
    ExecutionPermitAuthority permitAuthority = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "scenario3-ephemeral-key",
        ephemeralPermitKey,
        30,
        clock,
        () -> "scenario3-permit-" + identifiers.incrementAndGet(),
        new InMemoryPermitConsumptionStore());
    HumanApprovalService approvals = new HumanApprovalService(
        new PolicyEvaluator(),
        JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json")),
        permitAuthority,
        durableState,
        durableState,
        action -> {
          targetCalls.incrementAndGet();
          return simulator.invoke(action);
        },
        () -> SecurityReadiness.allReady(clock.instant()),
        clock,
        () -> "scenario3-approval-" + identifiers.incrementAndGet(),
        "urn:tracepermit:scenario3-simulator",
        "simulator",
        300);

    JsonNode action = JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json"));
    TrustedTenantContext tenantContext = new TrustedTenantContext(TENANT);

    System.out.println("\n1. Agent request -> policy -> pending approval");
    System.out.println("   Requester: " + REQUESTER);
    System.out.println("   Action:    aws.ec2.update_security_group on simulated sg-demo");
    ApprovalExecutionResult pending = approvals.requestApproval(tenantContext, action);
    ApprovalRequestState pendingState = pending.approval();
    require("pending_approval".equals(pending.outcome()), "PENDING_RESULT_MISMATCH");
    require("pending".equals(pendingState.status()), "PENDING_STATE_MISMATCH");
    require(targetCalls.get() == 0, "TARGET_CALLED_BEFORE_APPROVAL");
    System.out.println("   [REQUIRE APPROVAL] Policy returned "
        + pending.evaluation().decision().reasonCode());
    System.out.println("   [PASS] Durable status=pending; target dispatches=0");
    System.out.println("   Binding: exact action + subject + lineage + policy v"
        + pendingState.binding().policyVersion() + " + 5-minute expiry + maxUses=1");

    System.out.println("\n2. Separation of duties");
    expectFailure(
        "SELF_APPROVAL_DENIED",
        () -> approvals.review(
            trustedReviewer(REQUESTER),
            pendingState.binding().approvalRequestId(),
            "approve",
            "CHANGE_REVIEWED"));
    require(targetCalls.get() == 0, "TARGET_CALLED_AFTER_SELF_APPROVAL");
    System.out.println("   [PASS] Requester self-approval was rejected; target dispatches=0");

    if (!automatic) {
      System.out.println("\n   Press Enter to let the separate development reviewer adapter submit");
      System.out.println("   its server-owned simulated OIDC context. The keypress is only a");
      System.out.println("   presentation pause; it is not approval authority.");
      String presenterSignal = new BufferedReader(new InputStreamReader(System.in)).readLine();
      require(presenterSignal != null, "PRESENTER_INPUT_CLOSED");
    }

    ApprovalRequestState approved = approvals.review(
        trustedReviewer(REVIEWER),
        pendingState.binding().approvalRequestId(),
        "approve",
        "CHANGE_REVIEWED");
    require("approved".equals(approved.status()), "APPROVED_STATE_MISMATCH");
    System.out.println("   [APPROVED] Independent reviewer " + REVIEWER);
    System.out.println("   [PASS] Trusted adapter supplied tenant, human identity, role, and scope");

    System.out.println("\n3. Exact-request binding");
    JsonNode changedAction = action.deepCopy();
    ((ObjectNode) changedAction.path("action").path("arguments")).put("port", 8443);
    expectFailure(
        "APPROVAL_BINDING_MISMATCH",
        () -> approvals.executeApproved(
            tenantContext, pendingState.binding().approvalRequestId(), changedAction));
    require(targetCalls.get() == 0, "TARGET_CALLED_FOR_MUTATED_ACTION");
    System.out.println("   [PASS] Changing port 443 -> 8443 invalidated the approval; dispatches=0");

    System.out.println("\n4. Atomic approval consumption -> one simulated effect");
    ApprovalExecutionResult executed = approvals.executeApproved(
        tenantContext, pendingState.binding().approvalRequestId(), action);
    require("succeeded".equals(executed.outcome()), "EXECUTION_OUTCOME_MISMATCH");
    require("consumed".equals(executed.approval().status()), "APPROVAL_NOT_CONSUMED");
    require(targetCalls.get() == 1, "TARGET_DISPATCH_COUNT_MISMATCH");
    require("simulated".equals(executed.result().path("mode").asText()), "NON_SIMULATED_RESULT");
    require(
        simulator.snapshot().path("aws").path("securityGroups").path("sg-demo").size() == 1,
        "SIMULATOR_EFFECT_COUNT_MISMATCH");
    System.out.println("   [PASS] PostgreSQL consumed approval and claimed execution before dispatch");
    System.out.println("   [EXECUTED] Simulator changed sg-demo once; approval status=consumed");

    System.out.println("\n5. Replay and evidence checks");
    expectFailure(
        "APPROVAL_NOT_EXECUTABLE",
        () -> approvals.executeApproved(
            tenantContext, pendingState.binding().approvalRequestId(), action));
    require(targetCalls.get() == 1, "TARGET_CALLED_ON_REPLAY");
    List<DurableEvidenceRecord> evidence = durableState.readEvidence(TENANT);
    List<String> kinds = evidence.stream()
        .map(record -> record.payload().path("kind").asText())
        .toList();
    require(kinds.containsAll(List.of(
        "approval.requested",
        "approval.approved",
        "approval.consumed",
        "execution.claimed",
        "target.succeeded")), "EVIDENCE_LIFECYCLE_INCOMPLETE");
    EvidenceIntegrityResult integrity = verifyEvidence(evidence);
    require(integrity.valid(), "EVIDENCE_CHAIN_INVALID");
    System.out.println("   [PASS] Replay was rejected; total target dispatches remain 1");
    System.out.println("   [PASS] Durable evidence chain valid with " + evidence.size()
        + " bounded lifecycle records");
    System.out.println("\nSecurity conclusion: approval authorized one exact request, not Alice or");
    System.out.println("the agent generally. PostgreSQL consumed that authority atomically before");
    System.out.println("the simulator effect, and mutation or replay could not execute.");
  }

  private static TrustedReviewerContext trustedReviewer(String subjectId) {
    return new TrustedReviewerContext(
        TENANT,
        subjectId,
        "human",
        true,
        Set.of(HumanApprovalService.REVIEWER_ROLE),
        Set.of(HumanApprovalService.REVIEWER_SCOPE),
        "development_oidc",
        CanonicalJson.sha256(JsonSupport.MAPPER.getNodeFactory()
            .textNode("scenario3-development-reviewer:" + subjectId)));
  }

  private static EvidenceIntegrityResult verifyEvidence(List<DurableEvidenceRecord> evidence) {
    require(!evidence.isEmpty(), "EVIDENCE_MISSING");
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
    return InMemoryEvidenceLedger.verifyRecords(records, evidence.getLast().eventHash());
  }

  private static void expectFailure(String expectedCode, Runnable operation) {
    try {
      operation.run();
      throw new IllegalStateException("EXPECTED_" + expectedCode);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      if (!expectedCode.equals(failure.getMessage())) throw failure;
    }
  }

  private static DataSource dataSource(PostgreSQLContainer<?> postgres) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(postgres.getJdbcUrl());
    source.setUser(postgres.getUsername());
    source.setPassword(postgres.getPassword());
    return source;
  }

  private static boolean parseArguments(String[] arguments) {
    if (arguments.length == 0) return false;
    if (arguments.length == 1 && "--auto".equals(arguments[0])) return true;
    throw new IllegalArgumentException("INVALID_SCENARIO_ARGUMENTS");
  }

  private static void heading() {
    System.out.println("\nTracePermit Live Scenario 3 — Request-bound human approval");
    System.out.println("===========================================================");
    System.out.println("Target: disposable PostgreSQL + local AWS simulator only");
    System.out.println("Reviewer: development-only trusted adapter; no remote approval API");
  }

  private static String safeCode(Throwable failure) {
    String message = failure.getMessage();
    if (message != null && message.matches("^[A-Z][A-Z0-9_]{0,127}$")) return message;
    return "SCENARIO_RUNTIME_FAILURE";
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new IllegalStateException(code);
  }
}
