package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.execution.ApprovalConsumptionResult;
import io.tracepermit.execution.ApprovalConsumptionStatus;
import io.tracepermit.execution.ApprovalDecision;
import io.tracepermit.execution.ApprovalExecutionClaim;
import io.tracepermit.execution.ApprovalPolicyBinding;
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.DurableApprovalActionState;
import io.tracepermit.execution.DurableApprovalState;
import io.tracepermit.execution.DurablePolicyBoundApprovalState;
import io.tracepermit.execution.DurableClaimResult;
import io.tracepermit.execution.DurableEnforcementState;
import io.tracepermit.execution.DurableEvidenceRecord;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.ExecutionTransition;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.policy.InMemoryPolicyLifecycleStore;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.policy.PolicyLifecycleService;
import io.tracepermit.policy.PolicyLifecycleStore;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class HumanApprovalServiceTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");
  private static final TenantPartition ACME = new TenantPartition("acme");

  @Test
  void snapshotRequestCannotSilentlyFallBackToAStoreWithoutSnapshotSupport() {
    FakeState state = new FakeState();
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = service(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.nullNode();
    });
    assertThatThrownBy(() -> service.requestApprovalWithSnapshot(
        new TrustedTenantContext(ACME), event())).hasMessage("APPROVAL_SNAPSHOT_UNAVAILABLE");
    assertThat(state.state).isNull();
    assertThat(targetCalls).hasValue(0);
  }

  @Test
  void pendingApprovalAndTrustedReviewerAuthorizationRemainSeparateFromTargetExecution() {
    FakeState state = new FakeState();
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = service(state, event -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("simulated", true);
    });

    ApprovalExecutionResult pending = service.requestApproval(
        new TrustedTenantContext(ACME), event());
    assertThat(pending.outcome()).isEqualTo("pending_approval");
    assertThat(pending.approval().status()).isEqualTo("pending");
    assertThat(pending.approval().binding().originalDecision()).isEqualTo("require_approval");
    assertThat(targetCalls).hasValue(0);

    assertThatThrownBy(() -> service.review(
        reviewer("user:bob@example.com", false, Set.of(), Set.of()),
        pending.approval().binding().approvalRequestId(),
        "approve",
        "AUTHORIZED_REVIEW"))
        .hasMessage("REVIEWER_NOT_AUTHORIZED");
    assertThatThrownBy(() -> service.review(
        reviewer("user:alice@example.com", true, Set.of(HumanApprovalService.REVIEWER_ROLE),
            Set.of(HumanApprovalService.REVIEWER_SCOPE)),
        pending.approval().binding().approvalRequestId(),
        "approve",
        "AUTHORIZED_REVIEW"))
        .hasMessage("SELF_APPROVAL_DENIED");
    assertThat(state.state.status()).isEqualTo("pending");
  }

  @Test
  void scheduledWorkloadWithoutSeparateActorCanBindAndConsumeAnExactDeletionApproval() {
    TenantPartition tenant = ACME;
    ObjectNode deletion =
        (ObjectNode) JsonSupport.read(ROOT.resolve("fixtures/scheduler-aws-read.json"));
    ((ObjectNode) deletion.path("action")).put("toolName", "aws.ec2.delete_security_group");
    ((ObjectNode) deletion.path("action")).set(
        "arguments", JsonSupport.MAPPER.createObjectNode().put("groupId", "sg-demo"));
    ((ObjectNode) deletion.path("action").path("target")).put("resourceType", "security-group");
    FakeState state = new FakeState();
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = service(
        state,
        JsonSupport.read(ROOT.resolve("policies/aws-production-deletion-demo.json")),
        action -> {
          targetCalls.incrementAndGet();
          return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
        });

    ApprovalExecutionResult pending = service.requestApproval(new TrustedTenantContext(tenant), deletion);
    assertThat(pending.outcome()).isEqualTo("pending_approval");
    assertThat(pending.evaluation().decision().matchedRuleId())
        .isEqualTo("approve-simulated-production-security-group-deletion");
    assertThat(targetCalls).hasValue(0);
    TrustedReviewerContext reviewer = new TrustedReviewerContext(
        tenant, "user:reviewer@demo.invalid", "human", true,
        Set.of(HumanApprovalService.REVIEWER_ROLE), Set.of(HumanApprovalService.REVIEWER_SCOPE),
        "development_oidc", hash('7'));
    service.review(reviewer, pending.approval().binding().approvalRequestId(), "approve", "REVIEWED");

    ObjectNode changed = deletion.deepCopy();
    ((ObjectNode) changed.path("action").path("arguments")).put("groupId", "sg-other");
    assertThatThrownBy(() -> service.executeApproved(
        new TrustedTenantContext(tenant), pending.approval().binding().approvalRequestId(), changed))
        .hasMessage("APPROVAL_BINDING_MISMATCH");
    assertThat(targetCalls).hasValue(0);

    ApprovalExecutionResult executed = service.executeApproved(
        new TrustedTenantContext(tenant), pending.approval().binding().approvalRequestId(), deletion);
    assertThat(executed.outcome()).isEqualTo("succeeded");
    assertThat(executed.approval().status()).isEqualTo("consumed");
    assertThat(targetCalls).hasValue(1);
  }

  @Test
  void approvedExactRequestExecutesAndDenialNeverDoes() {
    FakeState state = new FakeState();
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = service(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
    });
    ApprovalExecutionResult pending = service.requestApproval(new TrustedTenantContext(ACME), event());
    ApprovalRequestState approved = service.review(
        authorizedReviewer(), pending.approval().binding().approvalRequestId(),
        "approve", "CHANGE_REVIEWED");
    assertThat(approved.status()).isEqualTo("approved");

    ApprovalExecutionResult result = service.executeApproved(
        new TrustedTenantContext(ACME), approved.binding().approvalRequestId(), event());
    assertThat(result.outcome()).isEqualTo("succeeded");
    assertThat(result.approval().status()).isEqualTo("consumed");
    assertThat(result.result().path("mode").asText()).isEqualTo("simulated");
    assertThat(targetCalls).hasValue(1);

    FakeState deniedState = new FakeState();
    HumanApprovalService deniedService = service(deniedState, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.nullNode();
    });
    ApprovalExecutionResult second = deniedService.requestApproval(new TrustedTenantContext(ACME), event());
    assertThat(deniedService.review(
        authorizedReviewer(), second.approval().binding().approvalRequestId(),
        "deny", "CHANGE_REJECTED").status()).isEqualTo("denied");
    assertThatThrownBy(() -> deniedService.executeApproved(
        new TrustedTenantContext(ACME), second.approval().binding().approvalRequestId(), event()))
        .hasMessage("APPROVAL_NOT_EXECUTABLE");
    assertThat(targetCalls).hasValue(1);
  }

  @Test
  void requestMutationPolicyChangeAndPreConsumptionFailureFailClosed() {
    FakeState state = new FakeState();
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = service(state, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.nullNode();
    });
    ApprovalExecutionResult pending = service.requestApproval(new TrustedTenantContext(ACME), event());
    service.review(
        authorizedReviewer(), pending.approval().binding().approvalRequestId(),
        "approve", "CHANGE_REVIEWED");

    JsonNode mutated = event();
    ((ObjectNode) mutated.path("action").path("arguments")).put("port", 8443);
    assertThatThrownBy(() -> service.executeApproved(
        new TrustedTenantContext(ACME), pending.approval().binding().approvalRequestId(), mutated))
        .hasMessage("APPROVAL_BINDING_MISMATCH");

    JsonNode changedPolicy = JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    ((ObjectNode) changedPolicy).put("version", 3);
    HumanApprovalService newPolicyService = service(state, changedPolicy, action -> {
      targetCalls.incrementAndGet();
      return JsonSupport.MAPPER.nullNode();
    });
    assertThatThrownBy(() -> newPolicyService.executeApproved(
        new TrustedTenantContext(ACME), pending.approval().binding().approvalRequestId(), event()))
        .hasMessage("APPROVAL_BINDING_MISMATCH");

    state.consumeFailure = new IllegalStateException("DURABLE_STATE_UNAVAILABLE");
    assertThatThrownBy(() -> service.executeApproved(
        new TrustedTenantContext(ACME), pending.approval().binding().approvalRequestId(), event()))
        .isSameAs(state.consumeFailure);
    assertThat(targetCalls).hasValue(0);
  }

  @Test
  void wrongTenantInvalidDecisionAndUnreadySecurityDependenciesFailClosed() {
    FakeState state = new FakeState();
    HumanApprovalService service = service(state, action -> JsonSupport.MAPPER.nullNode());
    ApprovalExecutionResult pending = service.requestApproval(new TrustedTenantContext(ACME), event());
    TrustedReviewerContext otherTenant = new TrustedReviewerContext(
        new TenantPartition("other"), "user:bob@example.com", "human", true,
        Set.of(HumanApprovalService.REVIEWER_ROLE), Set.of(HumanApprovalService.REVIEWER_SCOPE),
        "oidc", hash('7'));
    assertThatThrownBy(() -> service.review(
        otherTenant, pending.approval().binding().approvalRequestId(), "approve", "REVIEWED"))
        .hasMessage("APPROVAL_REQUEST_NOT_FOUND");
    assertThatThrownBy(() -> service.review(
        authorizedReviewer(), pending.approval().binding().approvalRequestId(), "maybe", "REVIEWED"))
        .hasMessage("The approval decision is invalid.");

    HumanApprovalService unavailable = service(
        state,
        JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json")),
        action -> JsonSupport.MAPPER.nullNode(),
        () -> SecurityReadiness.forRuntime(false, NOW));
    assertThatThrownBy(() -> unavailable.requestApproval(new TrustedTenantContext(ACME), event()))
        .hasMessage("SECURITY_DEPENDENCY_UNAVAILABLE");

    assertThatThrownBy(() -> service.requestApproval(
        new TrustedTenantContext(ACME),
        JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"))))
        .hasMessage("APPROVAL_NOT_REQUIRED");
    assertThatThrownBy(() -> service.requestApproval(
        new TrustedTenantContext(new TenantPartition("other")), event()))
        .hasMessage("TENANT_CONTEXT_MISMATCH");
    assertThatThrownBy(() -> new HumanApprovalService(
        new PolicyEvaluator(),
        JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json")),
        permitAuthority(new AtomicInteger()),
        state,
        state,
        action -> JsonSupport.MAPPER.nullNode(),
        () -> SecurityReadiness.allReady(NOW),
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> "approval-invalid",
        "audience",
        "simulator",
        0)).hasMessage("Approval lifetime must be between 1 and 3600 seconds.");
  }

  @Test
  void consumptionStatusesAndTargetFailuresUseBoundedFailClosedSemantics() {
    for (ApprovalConsumptionStatus status : List.of(
        ApprovalConsumptionStatus.APPROVAL_EXPIRED,
        ApprovalConsumptionStatus.APPROVAL_REPLAYED)) {
      FakeState state = approvedState();
      state.consumptionStatus = status;
      HumanApprovalService service = service(state, action -> JsonSupport.MAPPER.nullNode());
      String expected = status == ApprovalConsumptionStatus.APPROVAL_EXPIRED
          ? "APPROVAL_EXPIRED"
          : "APPROVAL_REPLAYED";
      assertThatThrownBy(() -> service.executeApproved(
          new TrustedTenantContext(ACME), state.state.binding().approvalRequestId(), event()))
          .hasMessage(expected);
    }

    for (String attemptStatus : List.of("executing", "unknown", "failed", "succeeded")) {
      FakeState state = approvedState();
      state.consumptionStatus = ApprovalConsumptionStatus.IDEMPOTENCY_DUPLICATE;
      state.attemptStatus = attemptStatus;
      HumanApprovalService service = service(state, action -> {
        throw new AssertionError("A duplicate must not dispatch.");
      });
      if ("succeeded".equals(attemptStatus)) {
        assertThat(service.executeApproved(
            new TrustedTenantContext(ACME), state.state.binding().approvalRequestId(), event()).outcome())
            .isEqualTo("succeeded");
      } else {
        String expected = switch (attemptStatus) {
          case "executing" -> "EXECUTION_ALREADY_IN_PROGRESS";
          case "unknown" -> "EXECUTION_OUTCOME_UNKNOWN";
          default -> "EXECUTION_PREVIOUSLY_FAILED";
        };
        assertThatThrownBy(() -> service.executeApproved(
            new TrustedTenantContext(ACME), state.state.binding().approvalRequestId(), event()))
            .hasMessage(expected);
      }
    }

    FakeState deniedState = approvedState();
    HumanApprovalService denied = service(deniedState, action -> {
      throw new TargetExecutionException("SIMULATOR_DENIED", "The simulator denied the action.");
    });
    assertThatThrownBy(() -> denied.executeApproved(
        new TrustedTenantContext(ACME), deniedState.state.binding().approvalRequestId(), event()))
        .isInstanceOf(TargetExecutionException.class);
    assertThat(deniedState.completedOutcome).isEqualTo("failed");

    FakeState unknownState = approvedState();
    HumanApprovalService unknown = service(unknownState, action -> {
      throw new IllegalStateException("connection-lost");
    });
    assertThatThrownBy(() -> unknown.executeApproved(
        new TrustedTenantContext(ACME), unknownState.state.binding().approvalRequestId(), event()))
        .hasMessage("connection-lost");
    assertThat(unknownState.completedOutcome).isEqualTo("unknown");

    FakeState completionState = approvedState();
    completionState.completeFailure = new IllegalStateException("database-down");
    HumanApprovalService completion = service(
        completionState, action -> JsonSupport.MAPPER.createObjectNode().put("ok", true));
    assertThatThrownBy(() -> completion.executeApproved(
        new TrustedTenantContext(ACME), completionState.state.binding().approvalRequestId(), event()))
        .hasMessage("DURABLE_STATE_UNAVAILABLE_AFTER_DISPATCH")
        .hasCause(completionState.completeFailure);
  }

  private static HumanApprovalService service(FakeState state, SimulatedTarget target) {
    return service(
        state,
        JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json")),
        target);
  }

  private static HumanApprovalService service(
      FakeState state, JsonNode policy, SimulatedTarget target) {
    return service(state, policy, target, () -> SecurityReadiness.allReady(NOW));
  }

  private static HumanApprovalService service(
      FakeState state,
      JsonNode policy,
      SimulatedTarget target,
      java.util.function.Supplier<SecurityReadiness> readiness) {
    AtomicInteger ids = new AtomicInteger();
    return new HumanApprovalService(
        new PolicyEvaluator(),
        policy,
        permitAuthority(ids),
        state,
        state,
        target,
        readiness,
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> "approval-" + ids.incrementAndGet(),
        "urn:tracepermit:approval-test-executor",
        "simulator",
        300);
  }

  private static ExecutionPermitAuthority permitAuthority(AtomicInteger ids) {
    return new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "approval-test-key",
        new byte[32],
        30,
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> "permit-" + ids.incrementAndGet(),
        new InMemoryPermitConsumptionStore());
  }

  private static FakeState approvedState() {
    FakeState state = new FakeState();
    HumanApprovalService service = service(state, action -> JsonSupport.MAPPER.nullNode());
    String requestId = service.requestApproval(
        new TrustedTenantContext(ACME), event()).approval().binding().approvalRequestId();
    service.review(authorizedReviewer(), requestId, "approve", "CHANGE_REVIEWED");
    return state;
  }

  private static TrustedReviewerContext authorizedReviewer() {
    return reviewer(
        "user:bob@example.com",
        true,
        Set.of(HumanApprovalService.REVIEWER_ROLE),
        Set.of(HumanApprovalService.REVIEWER_SCOPE));
  }

  private static TrustedReviewerContext reviewer(
      String subject, boolean authenticated, Set<String> roles, Set<String> scopes) {
    return new TrustedReviewerContext(
        ACME, subject, "human", authenticated, roles, scopes, "oidc", hash('7'));
  }

  private static JsonNode event() {
    return JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json"));
  }

  private static String hash(char digit) {
    return "sha256:" + String.valueOf(digit).repeat(64);
  }

  private static HumanApprovalService durableService(
      PolicyLifecycleStore store,
      String workspaceId,
      String policyId,
      DurableApprovalState state,
      SimulatedTarget target) {
    AtomicInteger ids = new AtomicInteger();
    return new HumanApprovalService(
        new PolicyEvaluator(),
        store,
        workspaceId,
        policyId,
        permitAuthority(ids),
        state,
        state instanceof DurableEnforcementState ? (DurableEnforcementState) state : null,
        target,
        () -> SecurityReadiness.allReady(NOW),
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> "approval-" + ids.incrementAndGet(),
        "urn:tracepermit:approval-test-executor",
        "simulator",
        300);
  }

  @Test
  void durablePolicyModeRejectsCreationWhenNoActivePolicyExists() {
    InMemoryPolicyLifecycleStore store = new InMemoryPolicyLifecycleStore();
    FakeState state = new FakeState();
    HumanApprovalService service = durableService(store, "default", "pol-local", state, action -> JsonSupport.MAPPER.nullNode());

    assertThatThrownBy(() -> service.requestApproval(new TrustedTenantContext(ACME), event()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ACTIVE_POLICY_UNAVAILABLE");
  }

  @Test
  void durablePolicyModeExecutesSuccessfullyWhenPolicyGenerationIsCurrent() {
    PolicyLifecycleService lifecycle = new PolicyLifecycleService();
    ObjectNode policy = sampleApprovalPolicy(1);
    lifecycle.stage(policy, NOW);
    lifecycle.activate("pol-approval-test", 1, NOW);

    FakePolicyBoundState state = new FakePolicyBoundState();
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = durableService(
        lifecycle.store(), "default", "pol-approval-test", state,
        action -> {
          targetCalls.incrementAndGet();
          return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
        });

    ApprovalExecutionResult requested = service.requestApproval(new TrustedTenantContext(ACME), event());
    assertThat(requested.outcome()).isEqualTo("pending_approval");
    assertThat(state.findPolicyBinding(ACME, requested.approval().binding().approvalRequestId())).isPresent();
    assertThat(state.findPolicyBinding(ACME, requested.approval().binding().approvalRequestId()).get().policyGeneration())
        .isEqualTo(1L);

    service.review(authorizedReviewer(), requested.approval().binding().approvalRequestId(), "approve", "CHANGE_REVIEWED");
    ApprovalExecutionResult executed = service.executeApproved(new TrustedTenantContext(ACME),
        requested.approval().binding().approvalRequestId(), event());

    assertThat(executed.outcome()).isEqualTo("succeeded");
    assertThat(targetCalls.get()).isEqualTo(1);
  }

  @Test
  void durablePolicyModeRejectsExecutionWhenNewVersionIsActivated() {
    PolicyLifecycleService lifecycle = new PolicyLifecycleService();
    ObjectNode v1 = sampleApprovalPolicy(1);
    ObjectNode v2 = sampleApprovalPolicy(2);
    lifecycle.stage(v1, NOW);
    lifecycle.stage(v2, NOW.plusSeconds(1));
    lifecycle.activate("pol-approval-test", 1, NOW);

    FakePolicyBoundState state = new FakePolicyBoundState();
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = durableService(
        lifecycle.store(), "default", "pol-approval-test", state,
        action -> {
          targetCalls.incrementAndGet();
          return JsonSupport.MAPPER.nullNode();
        });

    ApprovalExecutionResult requested = service.requestApproval(new TrustedTenantContext(ACME), event());
    String reqId = requested.approval().binding().approvalRequestId();
    service.review(authorizedReviewer(), reqId, "approve", "CHANGE_REVIEWED");

    // Operator activates v2 (generation 2)
    lifecycle.activate("pol-approval-test", 2, NOW.plusSeconds(2));

    // Execute must fail closed before dispatch
    assertThatThrownBy(() -> service.executeApproved(new TrustedTenantContext(ACME), reqId, event()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("STALE_POLICY_GENERATION");
    assertThat(targetCalls.get()).isEqualTo(0);
  }

  @Test
  void durablePolicyModeRejectsExecutionAfterRollbackRestoringSameVersionAndDocument() {
    PolicyLifecycleService lifecycle = new PolicyLifecycleService();
    ObjectNode v1 = sampleApprovalPolicy(1);
    ObjectNode v2 = sampleApprovalPolicy(2);
    lifecycle.stage(v1, NOW);
    lifecycle.stage(v2, NOW.plusSeconds(1));
    lifecycle.activate("pol-approval-test", 1, NOW);

    FakePolicyBoundState state = new FakePolicyBoundState();
    AtomicInteger targetCalls = new AtomicInteger();
    HumanApprovalService service = durableService(
        lifecycle.store(), "default", "pol-approval-test", state,
        action -> {
          targetCalls.incrementAndGet();
          return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
        });

    // 1. Request approval under v1 (generation 1)
    ApprovalExecutionResult req1 = service.requestApproval(new TrustedTenantContext(ACME), event());
    String req1Id = req1.approval().binding().approvalRequestId();
    service.review(authorizedReviewer(), req1Id, "approve", "CHANGE_REVIEWED");

    // 2. Activate v2 (generation 2)
    lifecycle.activate("pol-approval-test", 2, NOW.plusSeconds(2));

    // 3. Rollback to v1 (generation 3)
    lifecycle.rollback("pol-approval-test", NOW.plusSeconds(3), "rollback to v1");

    // 4. Old approval (created at gen 1) MUST be rejected even though v1 is currently active!
    assertThatThrownBy(() -> service.executeApproved(new TrustedTenantContext(ACME), req1Id, event()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("STALE_POLICY_GENERATION");
    assertThat(targetCalls.get()).isEqualTo(0);

    // 5. Fresh approval created under restored v1 (generation 3) MUST succeed
    ApprovalExecutionResult req2 = service.requestApproval(new TrustedTenantContext(ACME), event());
    String req2Id = req2.approval().binding().approvalRequestId();
    service.review(authorizedReviewer(), req2Id, "approve", "CHANGE_REVIEWED");
    ApprovalExecutionResult exec2 = service.executeApproved(new TrustedTenantContext(ACME), req2Id, event());
    assertThat(exec2.outcome()).isEqualTo("succeeded");
    assertThat(targetCalls.get()).isEqualTo(1);
  }

  @Test
  void unboundApprovalFailsClosedInDurablePolicyMode() {
    PolicyLifecycleService lifecycle = new PolicyLifecycleService();
    ObjectNode v1 = sampleApprovalPolicy(1);
    lifecycle.stage(v1, NOW);
    lifecycle.activate("pol-approval-test", 1, NOW);

    FakePolicyBoundState state = new FakePolicyBoundState();
    // Simulate legacy approval created without durable policy binding
    HumanApprovalService staticService = service(state, action -> JsonSupport.MAPPER.nullNode());
    ApprovalExecutionResult legacyReq = staticService.requestApproval(new TrustedTenantContext(ACME), event());
    String legacyId = legacyReq.approval().binding().approvalRequestId();
    staticService.review(authorizedReviewer(), legacyId, "approve", "CHANGE_REVIEWED");

    // Attempt to execute via durable service
    HumanApprovalService durableService = durableService(
        lifecycle.store(), "default", "pol-approval-test", state, action -> JsonSupport.MAPPER.nullNode());

    assertThatThrownBy(() -> durableService.executeApproved(new TrustedTenantContext(ACME), legacyId, event()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("STALE_POLICY_GENERATION");
  }

  private static ObjectNode sampleApprovalPolicy(int version) {
    ObjectNode policy = JsonSupport.MAPPER.createObjectNode();
    policy.put("schemaVersion", "1.0");
    policy.put("id", "pol-approval-test");
    policy.put("version", version);
    policy.put("name", "Sample Approval Policy v" + version);
    policy.put("description", "A sample policy requiring approval for tests.");
    policy.put("enabled", true);
    policy.putObject("defaultDecision")
        .put("outcome", "require_approval")
        .put("reasonCode", "SAMPLE_REQUIRE_APPROVAL");
    policy.putArray("rules");
    return policy;
  }

  private static class FakeState
      implements DurableApprovalState, DurableEnforcementState {
    protected ApprovalRequestState state;
    protected RuntimeException consumeFailure;
    protected RuntimeException completeFailure;
    protected ApprovalConsumptionStatus consumptionStatus = ApprovalConsumptionStatus.STARTED;
    protected String attemptStatus = "executing";
    protected String completedOutcome;

    @Override
    public ApprovalRequestState createPending(ApprovalBinding binding) {
      state = new ApprovalRequestState(
          binding, "pending", 1, null, null, null, null, null, null,
          binding.createdAt(), null, null);
      return state;
    }
    @Override
    public ApprovalRequestState find(TenantPartition tenant, String approvalRequestId) {
      return state != null
              && state.binding().tenant().equals(tenant)
              && state.binding().approvalRequestId().equals(approvalRequestId)
          ? state
          : null;
    }

    @Override
    public ApprovalRequestState decide(ApprovalDecision decision) {
      String status = "approve".equals(decision.decision()) ? "approved" : "denied";
      state = new ApprovalRequestState(
          state.binding(), status, 2, decision.approvalDecisionId(),
          decision.reviewerSubjectId(), decision.reviewerAuthenticationMethod(),
          decision.reviewerCredentialFingerprint(), decision.reasonCode(), decision.decidedAt(),
          decision.decidedAt(), null, null);
      return state;
    }

    @Override
    public ApprovalConsumptionResult consumeAndClaim(ApprovalExecutionClaim claim) {
      if (consumeFailure != null) throw consumeFailure;
      ExecutionAttempt attempt = attempt(attemptStatus);
      if (consumptionStatus != ApprovalConsumptionStatus.STARTED) {
        return new ApprovalConsumptionResult(
            consumptionStatus,
            state,
            consumptionStatus == ApprovalConsumptionStatus.IDEMPOTENCY_DUPLICATE ? attempt : null);
      }
      state = new ApprovalRequestState(
          state.binding(), "consumed", 3, state.approvalDecisionId(), state.reviewerSubjectId(),
          state.reviewerAuthenticationMethod(), state.reviewerCredentialFingerprint(),
          state.decisionReasonCode(), state.decidedAt(), claim.occurredAt(),
          claim.executionClaim().permitId(), attempt.attemptId());
      return new ApprovalConsumptionResult(consumptionStatus, state, attempt);
    }

    @Override
    public DurableClaimResult claim(DurableExecutionClaim claim) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ExecutionAttempt complete(
        TenantPartition tenant,
        String attemptId,
        String outcome,
        String reasonCode,
        Instant occurredAt) {
      if (completeFailure != null) throw completeFailure;
      completedOutcome = outcome;
      return attempt(outcome);
    }

    @Override
    public ExecutionAttempt reconcile(
        TenantPartition tenant,
        String adapterId,
        String idempotencyKey,
        String outcome,
        String reasonCode,
        Instant occurredAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ExecutionAttempt findByIdempotencyKey(
        TenantPartition tenant, String adapterId, String idempotencyKey) {
      return null;
    }

    @Override
    public List<DurableEvidenceRecord> readEvidence(TenantPartition tenant) {
      return List.of();
    }

    protected static ExecutionAttempt attempt(String status) {
      return new ExecutionAttempt(
          "attempt-1", "018f47a2-f5ce-7a4b-8f54-80e5d3eaf092", "permit-1",
          hash('6'), status, NOW, NOW,
          List.of(new ExecutionTransition(1, null, "executing", NOW, "EXECUTION_CLAIMED")),
          null);
    }
  }

  private static final class FakePolicyBoundState extends FakeState
      implements DurablePolicyBoundApprovalState {
    private ApprovalPolicyBinding policyBinding;
    private JsonNode actionSnapshot;

    @Override
    public List<ApprovalRequestState> listApprovals(
        TenantPartition tenant, String requesterSubjectId, int limit) {
      return state != null ? List.of(state) : List.of();
    }

    @Override
    public ApprovalRequestState createPending(ApprovalBinding binding, JsonNode canonicalAction) {
      this.actionSnapshot = canonicalAction;
      return createPending(binding);
    }

    @Override
    public ApprovalRequestState createPendingBound(
        ApprovalBinding binding, JsonNode canonicalAction, ApprovalPolicyBinding policyBinding) {
      this.actionSnapshot = canonicalAction;
      this.policyBinding = policyBinding;
      return createPending(binding);
    }

    @Override
    public JsonNode findAction(TenantPartition tenant, String approvalRequestId) {
      return actionSnapshot;
    }

    @Override
    public Optional<ApprovalPolicyBinding> findPolicyBinding(TenantPartition tenant, String approvalRequestId) {
      return Optional.ofNullable(policyBinding);
    }

    @Override
    public ApprovalConsumptionResult consumeAndClaim(ApprovalExecutionClaim claim) {
      if (consumeFailure != null) throw consumeFailure;
      ExecutionAttempt attempt = attempt(attemptStatus);
      if (consumptionStatus != ApprovalConsumptionStatus.STARTED) {
        return new ApprovalConsumptionResult(
            consumptionStatus,
            state,
            consumptionStatus == ApprovalConsumptionStatus.IDEMPOTENCY_DUPLICATE ? attempt : null);
      }
      if (policyBinding != null) {
        if (claim.policyBinding() == null || !policyBinding.equals(claim.policyBinding())) {
          return new ApprovalConsumptionResult(
              ApprovalConsumptionStatus.STALE_POLICY_GENERATION, state, null);
        }
      }
      state = new ApprovalRequestState(
          state.binding(), "consumed", 3, state.approvalDecisionId(), state.reviewerSubjectId(),
          state.reviewerAuthenticationMethod(), state.reviewerCredentialFingerprint(),
          state.decisionReasonCode(), state.decidedAt(), claim.occurredAt(),
          claim.executionClaim().permitId(), attempt.attemptId());
      return new ApprovalConsumptionResult(consumptionStatus, state, attempt);
    }
  }
}
