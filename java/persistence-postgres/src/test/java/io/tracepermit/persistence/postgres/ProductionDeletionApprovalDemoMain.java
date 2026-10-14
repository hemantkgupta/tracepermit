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
import io.tracepermit.identity.InMemoryCredentialRevocationChecker;
import io.tracepermit.identity.InMemoryWorkloadIdentityReplayStore;
import io.tracepermit.identity.ScheduledActionAdapter;
import io.tracepermit.identity.ScheduledWorkloadVerifier;
import io.tracepermit.identity.StaticWorkloadTrustRegistry;
import io.tracepermit.identity.TrustedScheduledActionContext;
import io.tracepermit.identity.WorkloadTrustRegistry;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.LogManager;

/** Presenter-facing proof that a signed production-labelled deletion needs one exact human approval. */
public final class ProductionDeletionApprovalDemoMain {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final String TENANT_ID = "tenant:demo";
  private static final TenantPartition TENANT = new TenantPartition(TENANT_ID);
  private static final String SCHEDULER = "scheduler:local-deletion-demo";
  private static final String WORKLOAD = "workload:scheduled-cleanup-demo";
  private static final String TOOL = "tool:aws-security-group-simulator";
  private static final String REVIEWER = "user:reviewer@demo.invalid";
  private static final String AUDIENCE = "tracepermit-gateway";
  private static final String SCOPE = "aws.ec2.security_group.delete";
  private static final String RESOURCE = "aws:simulated:security-group/sg-demo";
  private static final String SCHEDULE = "schedule:production-cleanup-review";
  private static final String SCHEDULER_ISSUER = "tracepermit-scheduler:local-deletion-demo";
  private static final String WORKLOAD_ISSUER = "tracepermit-workload:scheduled-cleanup-demo";

  private ProductionDeletionApprovalDemoMain() {}

  public static void main(String[] arguments) {
    boolean automatic = parseArguments(arguments);
    try {
      run(automatic);
    } catch (RuntimeException | IOException failure) {
      System.err.println("Scenario 8 failed safely: " + safeCode(failure));
      System.exit(1);
    }
  }

  private static void run(boolean automatic) throws IOException {
    System.out.println("\nTracePermit Live Scenario 8 — Production deletion requires approval");
    System.out.println("====================================================================");
    System.out.println("All AWS names are simulated; no AWS SDK, endpoint, or credential is configured.");
    System.out.println("Starting disposable PostgreSQL 16 for durable approval and evidence state...");
    LogManager.getLogManager().reset();
    try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")) {
      postgres.start();
      executeScenario(automatic, dataSource(postgres));
    }
    System.out.println("\n[PASS] Scenario 8 complete; disposable PostgreSQL was removed.");
  }

  private static void executeScenario(boolean automatic, DataSource dataSource) throws IOException {
    Clock clock = Clock.systemUTC();
    AtomicLong identifiers = new AtomicLong();
    PostgresDurableEnforcementState state = new PostgresDurableEnforcementState(
        dataSource, clock, () -> "scenario8-state-" + identifiers.incrementAndGet());
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    AtomicInteger dispatches = new AtomicInteger();
    byte[] permitKey = new byte[32];
    new SecureRandom().nextBytes(permitKey);
    HumanApprovalService approvals = new HumanApprovalService(
        new PolicyEvaluator(),
        JsonSupport.read(ROOT.resolve("policies/aws-production-deletion-demo.json")),
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "scenario8-ephemeral-key",
            permitKey,
            30,
            clock,
            () -> "scenario8-permit-" + identifiers.incrementAndGet(),
            new InMemoryPermitConsumptionStore()),
        state,
        state,
        action -> {
          dispatches.incrementAndGet();
          return simulator.invoke(action);
        },
        () -> SecurityReadiness.allReady(clock.instant()),
        clock,
        () -> "scenario8-approval-" + identifiers.incrementAndGet(),
        "urn:tracepermit:scenario8-simulator",
        "simulator",
        60);

    JsonNode action = signedScheduledDeletion(clock);
    require("authenticated".equals(action.path("identity").path("subject").path("assurance").asText()),
        "WORKLOAD_NOT_AUTHENTICATED");
    require(action.path("lineage").path("delegations").size() == 1, "DELEGATION_MISSING");
    require(simulator.snapshot().path("aws").path("securityGroups").has("sg-demo"),
        "SIMULATED_GROUP_MISSING_BEFORE_APPROVAL");
    TrustedTenantContext tenant = new TrustedTenantContext(TENANT);

    System.out.println("\n1. Verified scheduler -> critical action -> pending approval");
    ApprovalExecutionResult pending = approvals.requestApproval(tenant, action);
    ApprovalRequestState request = pending.approval();
    require("pending_approval".equals(pending.outcome()), "PENDING_RESULT_MISMATCH");
    require("pending".equals(request.status()), "PENDING_STATE_MISMATCH");
    require("destructive".equals(pending.evaluation().classification().actionClass()),
        "DELETION_NOT_DESTRUCTIVE");
    require("critical".equals(pending.evaluation().classification().severity()),
        "DELETION_NOT_CRITICAL");
    require("approve-simulated-production-security-group-deletion".equals(
        pending.evaluation().decision().matchedRuleId()), "EXPLICIT_APPROVAL_RULE_MISSING");
    require("PRODUCTION_DELETION_REQUIRES_HUMAN_APPROVAL".equals(
        pending.evaluation().decision().reasonCode()), "APPROVAL_REASON_MISMATCH");
    require(Duration.between(request.binding().createdAt(), request.binding().expiresAt()).getSeconds() == 60,
        "APPROVAL_LIFETIME_MISMATCH");
    require(dispatches.get() == 0, "TARGET_CALLED_WHILE_PENDING");
    System.out.println("   Subject: authenticated " + WORKLOAD + "; scheduled trigger");
    System.out.println("   Signed delegation: " + SCOPE + " on one simulated security group");
    System.out.println("   Classification: destructive / critical; target label: prod");
    System.out.println("   Policy: require_approval; durable status=pending; simulator dispatches=0");
    System.out.println("   Binding: exact action, subject, lineage, policy v"
        + request.binding().policyVersion() + ", 1-minute expiry, maxUses=1");

    System.out.println("\n2. Reviewer authority is independent of the agent request");
    expectFailure("REVIEWER_NOT_AUTHORIZED", () -> approvals.review(
        unprivilegedReviewer(), request.binding().approvalRequestId(), "approve", "REVIEWED"));
    require(dispatches.get() == 0, "TARGET_CALLED_FOR_UNAUTHORIZED_REVIEWER");
    System.out.println("   [PASS] A human without the reviewer role/scope cannot approve");
    if (!automatic) {
      System.out.println("   Press Enter to let the separate development reviewer adapter approve.");
      System.out.println("   The keypress controls presentation timing, not approval authority.");
      require(new BufferedReader(new InputStreamReader(System.in)).readLine() != null,
          "PRESENTER_INPUT_CLOSED");
    }
    ApprovalRequestState approved = approvals.review(
        authorizedReviewer(), request.binding().approvalRequestId(), "approve", "DELETION_REVIEWED");
    require("approved".equals(approved.status()), "APPROVED_STATE_MISMATCH");
    require(dispatches.get() == 0, "TARGET_CALLED_AT_REVIEW_TIME");
    System.out.println("   [APPROVED] Separate development reviewer; simulator dispatches still 0");

    System.out.println("\n3. Mutated request cannot reuse the approval");
    ObjectNode changed = action.deepCopy();
    ((ObjectNode) changed.path("action").path("arguments")).put("groupId", "sg-other");
    expectFailure("APPROVAL_BINDING_MISMATCH", () -> approvals.executeApproved(
        tenant, request.binding().approvalRequestId(), changed));
    require(dispatches.get() == 0, "TARGET_CALLED_FOR_MUTATED_REQUEST");
    require(simulator.snapshot().path("aws").path("securityGroups").has("sg-demo"),
        "SIMULATED_GROUP_CHANGED_BEFORE_EXACT_EXECUTION");
    System.out.println("   [PASS] Changed groupId rejected before dispatch; sg-demo still exists");

    System.out.println("\n4. Atomic consumption -> one in-memory deletion");
    ApprovalExecutionResult executed = approvals.executeApproved(
        tenant, request.binding().approvalRequestId(), action);
    require("succeeded".equals(executed.outcome()), "EXECUTION_OUTCOME_MISMATCH");
    require("consumed".equals(executed.approval().status()), "APPROVAL_NOT_CONSUMED");
    require(dispatches.get() == 1, "TARGET_DISPATCH_COUNT_MISMATCH");
    require("simulated".equals(executed.result().path("mode").asText())
        && executed.result().path("deleted").asBoolean(), "NON_SIMULATED_OR_MISSING_DELETION");
    require(!simulator.snapshot().path("aws").path("securityGroups").has("sg-demo"),
        "SIMULATED_GROUP_NOT_REMOVED");
    System.out.println("   [PASS] PostgreSQL consumed approval and claimed execution before dispatch");
    System.out.println("   [EXECUTED] sg-demo removed from local simulator exactly once");

    System.out.println("\n5. Replay and evidence integrity");
    expectFailure("APPROVAL_NOT_EXECUTABLE", () -> approvals.executeApproved(
        tenant, request.binding().approvalRequestId(), action));
    require(dispatches.get() == 1, "TARGET_CALLED_ON_REPLAY");
    List<DurableEvidenceRecord> evidence = state.readEvidence(TENANT);
    List<String> kinds = evidence.stream().map(record -> record.payload().path("kind").asText()).toList();
    require(kinds.containsAll(List.of(
        "approval.requested", "approval.approved", "approval.consumed",
        "execution.claimed", "target.succeeded")), "EVIDENCE_LIFECYCLE_INCOMPLETE");
    require(verifyEvidence(evidence).valid(), "EVIDENCE_CHAIN_INVALID");
    System.out.println("   [PASS] Approval replay rejected; total simulator dispatches=1");
    System.out.println("   [PASS] Durable evidence chain valid with " + evidence.size() + " bounded records");
    System.out.println("\nSecurity conclusion: verified origin and narrow delegation did not authorize");
    System.out.println("deletion. Only a separate review of the exact canonical action enabled one");
    System.out.println("permit-backed simulated effect. No real AWS target was contacted.");
  }

  private static JsonNode signedScheduledDeletion(Clock clock) {
    Signer scheduler = signer(SCHEDULER_ISSUER, "scheduler-runtime-key", SCHEDULER);
    Signer workload = signer(WORKLOAD_ISSUER, "workload-runtime-key", WORKLOAD);
    ScheduledActionAdapter adapter = new ScheduledActionAdapter(new ScheduledWorkloadVerifier(
        new StaticWorkloadTrustRegistry(List.of(
            trusted(scheduler, WorkloadTrustRegistry.KeyUsage.WORKLOAD_IDENTITY),
            trusted(workload, WorkloadTrustRegistry.KeyUsage.DELEGATION))),
        new InMemoryCredentialRevocationChecker(),
        new InMemoryWorkloadIdentityReplayStore(),
        clock));
    Instant now = clock.instant();
    ObjectNode action = JsonSupport.MAPPER.createObjectNode();
    action.put("protocol", "sdk");
    action.put("toolName", "aws.ec2.delete_security_group");
    action.putObject("arguments").put("groupId", "sg-demo");
    action.putObject("target")
        .put("system", "aws")
        .put("environment", "prod")
        .put("accountId", "simulated-123")
        .put("resourceType", "security-group");
    TrustedScheduledActionContext context = new TrustedScheduledActionContext(
        TENANT_ID, SCHEDULER, SCHEDULE, "run:simulated-production-deletion",
        UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", ""),
        AUDIENCE, SCOPE, RESOURCE, true, now);
    ObjectNode input = JsonSupport.MAPPER.createObjectNode();
    input.put("schemaVersion", "1.0");
    input.putObject("declaredIntent")
        .put("goal", "Request review before deleting one simulated production-labelled security group")
        .put("source", "job_definition");
    input.set("action", action.deepCopy());
    input.putObject("clientClaims").put("untrustedHint", "not-authority");
    return adapter.verifyAndNormalize(
        input,
        scheduler.sign(workloadClaims(action, context, now), "tracepermit-workload-identity+jws"),
        List.of(workload.sign(delegationClaims(context, now), "tracepermit-delegation-grant+jws")),
        context);
  }

  private static ObjectNode workloadClaims(JsonNode action, TrustedScheduledActionContext context, Instant now) {
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("version", "1.0");
    claims.put("type", "tracepermit-workload-identity+jws");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("requestBindingProfile", "tracepermit-scheduled-action-v1");
    claims.put("issuer", SCHEDULER_ISSUER);
    claims.put("keyId", "scheduler-runtime-key");
    claims.put("audience", AUDIENCE);
    claims.put("tokenId", UUID.randomUUID().toString());
    claims.put("tenantId", TENANT_ID);
    claims.put("subjectId", WORKLOAD);
    claims.put("schedulerId", SCHEDULER);
    claims.put("scheduleId", context.scheduleId());
    claims.put("runId", context.runId());
    claims.put("eventId", context.eventId());
    claims.put("traceId", context.traceId());
    claims.put("actionHash", CanonicalJson.sha256(action));
    claims.put("issuedAt", now.minusSeconds(1).toString());
    claims.put("expiresAt", now.plusSeconds(120).toString());
    return claims;
  }

  private static ObjectNode delegationClaims(TrustedScheduledActionContext context, Instant now) {
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("version", "1.0");
    claims.put("type", "tracepermit-delegation-grant+jws");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("issuer", WORKLOAD_ISSUER);
    claims.put("keyId", "workload-runtime-key");
    claims.put("tenantId", TENANT_ID);
    claims.put("tokenId", UUID.randomUUID().toString());
    claims.put("actorId", WORKLOAD);
    claims.put("subjectId", TOOL);
    claims.put("audience", AUDIENCE);
    claims.putArray("scopes").add(SCOPE);
    claims.putArray("resources").add(RESOURCE);
    claims.put("purpose", "Request review for one simulated security-group deletion");
    claims.put("traceId", context.traceId());
    claims.put("issuedAt", now.minusSeconds(1).toString());
    claims.put("expiresAt", now.plusSeconds(300).toString());
    return claims;
  }

  private static WorkloadTrustRegistry.TrustedWorkloadKey trusted(
      Signer signer, WorkloadTrustRegistry.KeyUsage usage) {
    return new WorkloadTrustRegistry.TrustedWorkloadKey(
        signer.issuer(), signer.keyId(), TENANT_ID, signer.principalId(),
        Set.of(usage), WorkloadTrustRegistry.KeyState.ACTIVE, signer.keyPair().getPublic());
  }

  private static Signer signer(String issuer, String keyId, String principalId) {
    try {
      return new Signer(issuer, keyId, principalId,
          KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("RUNTIME_SIGNER_UNAVAILABLE", failure);
    }
  }

  private static TrustedReviewerContext authorizedReviewer() {
    return reviewer(Set.of(HumanApprovalService.REVIEWER_ROLE),
        Set.of(HumanApprovalService.REVIEWER_SCOPE));
  }

  private static TrustedReviewerContext unprivilegedReviewer() {
    return reviewer(Set.of(), Set.of());
  }

  private static TrustedReviewerContext reviewer(Set<String> roles, Set<String> scopes) {
    return new TrustedReviewerContext(
        TENANT, REVIEWER, "human", true, roles, scopes, "development_oidc",
        CanonicalJson.sha256(JsonSupport.MAPPER.getNodeFactory().textNode("scenario8-development-reviewer")));
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

  private static void expectFailure(String code, Runnable operation) {
    try {
      operation.run();
      throw new IllegalStateException("EXPECTED_" + code);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      if (!code.equals(failure.getMessage())) throw failure;
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

  private static String safeCode(Throwable failure) {
    String message = failure.getMessage();
    if (message != null && message.matches("^[A-Z][A-Z0-9_]{0,127}$")) return message;
    return "SCENARIO_RUNTIME_FAILURE";
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new IllegalStateException(code);
  }

  private record Signer(String issuer, String keyId, String principalId, KeyPair keyPair) {
    private String sign(ObjectNode claims, String type) {
      try {
        ObjectNode header = JsonSupport.MAPPER.createObjectNode();
        header.put("alg", "EdDSA");
        header.put("typ", type);
        header.put("kid", keyId);
        String signingInput =
            encode(CanonicalJson.canonicalize(header).getBytes(StandardCharsets.UTF_8))
                + "."
                + encode(CanonicalJson.canonicalize(claims).getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + encode(signature.sign());
      } catch (GeneralSecurityException failure) {
        throw new IllegalStateException("RUNTIME_SIGNATURE_FAILED", failure);
      }
    }

    private static String encode(byte[] value) {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
  }
}
