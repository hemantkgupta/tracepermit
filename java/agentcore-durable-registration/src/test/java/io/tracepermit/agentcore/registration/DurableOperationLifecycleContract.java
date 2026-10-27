package io.tracepermit.agentcore.registration;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_AUTHENTICATION_EXPIRED;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_DISPATCH_ALREADY_STARTED;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_EXECUTOR_NOT_AUTHORIZED;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_NOT_APPROVED;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_PERMIT_INVALID;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_PROFILE_MISMATCH;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_REVIEWER_NOT_AUTHORIZED;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_TARGET_PRECONDITION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.BusinessOperationAdmissionSettings;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationEvaluation;
import io.tracepermit.application.BusinessOperationExecution;
import io.tracepermit.application.BusinessOperationReview;
import io.tracepermit.application.BusinessOperationReviewSettings;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.application.TrustedOperationEvaluatorContext;
import io.tracepermit.application.TrustedOperationGroupMembership;
import io.tracepermit.application.TrustedOperationReviewerContext;
import io.tracepermit.application.TrustedRequesterContext;
import io.tracepermit.application.TrustedReviewerContext;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import io.tracepermit.persistence.identity.WorkloadIdentityReplayDatabase;
import io.tracepermit.persistence.operations.JdbcBusinessOperationLifecycleRepository;
import io.tracepermit.persistence.operations.JdbcBusinessOperationRepository;
import io.tracepermit.persistence.operations.JdbcGitHubOperationAdmissionRepository;
import io.tracepermit.persistence.operations.JdbcGitHubOperationDeliveryRepository;
import io.tracepermit.persistence.operations.JdbcGitHubOperationPermitIssuer;
import io.tracepermit.persistence.operations.JdbcGitHubOperationReviewRepository;
import io.tracepermit.persistence.operations.OperationAuthorityAdministration;
import io.tracepermit.persistence.operations.OperationDatabase;
import io.tracepermit.persistence.operations.SyntheticGitHubDeliveryTarget;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Permanent cross-module contract over existing public components only. Low-level crash, commit,
 * cleanup, lock-wait and migration matrices remain owned by their focused repository contracts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
abstract class DurableOperationLifecycleContract {
  private static final String HASH = "sha256:" + "a".repeat(64);
  private static final String GROUP = "synthetic-reviewers";
  private static final BusinessOperationScope SCOPE =
      new BusinessOperationScope(
          "synthetic-tenant", "synthetic-workspace", "workload:synthetic-agent");
  private static final BusinessOperationTransitionPlanner PLANNER =
      new BusinessOperationTransitionPlanner();

  private DurableOperationLifecycleDatabases databases;
  private DurableOperationLifecycleIdentity identity;
  private KeyPair permitKeys;
  private ObjectNode policy;
  private GitHubBusinessOperationBinding.ExpectedProfile expected;
  private BusinessOperationAuthorityProfile authority;

  abstract String image();

  @BeforeAll
  void startDatabase() throws Exception {
    databases = new DurableOperationLifecycleDatabases(image());
    policy =
        (ObjectNode)
            JsonSupport.MAPPER.readTree(
                """
                {"schemaVersion":"1.0","id":"synthetic-github-draft-pr","version":1,
                 "name":"Synthetic","description":"Synthetic only","enabled":true,
                 "defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
                 "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic",
                 "match":{"toolNames":["github.pull_request.create"]},
                 "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW",
                 "approvalGroup":"synthetic-reviewers"}}]}
                """);
    expected = expectedProfile(101);
  }

  @BeforeEach
  void resetStoresAndTrust() throws Exception {
    databases.reset();
    identity = new DurableOperationLifecycleIdentity(Instant.now().minusSeconds(1));
    permitKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    authority = null;
  }

  @AfterAll
  void stopDatabase() {
    if (databases != null) databases.close();
  }

  @Test
  void registrationRemainsExecutionDisabledUntilExplicitAuthorityComponentsAct() {
    Registered registered = register("registration-only");
    JsonNode initial = registered.initial();

    assertThat(registered.executionEnabled()).isFalse();
    assertThat(initial.path("authorization").path("status").asText()).isEqualTo("evaluating");
    assertThat(initial.path("execution").path("status").asText()).isEqualTo("not_started");
    assertThat(initial.path("evidence").path("status").asText()).isEqualTo("pending");
    assertThat(initial.path("transitions").size()).isEqualTo(3);
    assertThat(count("tp_operation_review_bindings")).isZero();
    assertThat(count("tp_operation_admissions")).isZero();
    assertThat(count("tp_operation_admission_outbox")).isZero();
    assertThat(count("tp_operation_delivery_events")).isZero();

    activateAuthority(initial);
    assertCode(() -> issuer().issue(execution(initial)), OPERATION_NOT_APPROVED);
    assertThat(current(initial)).isEqualTo(initial);
    assertThat(count("tp_operation_lifecycle_facts")).isZero();
    assertThat(count("tp_operation_review_bindings")).isZero();
    assertThat(count("tp_operation_admissions")).isZero();
    assertThat(count("tp_operation_delivery_events")).isZero();
  }

  @Test
  void originalPermitJoinsAdmissionAndSuccessfulDeliveryEvidence() {
    Registered registered = register("successful-lifecycle");
    activateAuthority(registered.initial());
    JsonNode approved = approve(evaluate(registered.initial()));
    BusinessOperationExecution approvalOwner = execution(approved);
    String original = issuer().issue(approvalOwner).serialized();
    String other = issuer().issue(approvalOwner).serialized();

    JsonNode claimed = admission().admit(approvalOwner, original);
    var target =
        new SyntheticGitHubDeliveryTarget(
            expected, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    var delivery = delivery(target);
    BusinessOperationExecution claimedOwner = execution(claimed);

    assertCode(() -> delivery.claim(claimedOwner, other), OPERATION_PERMIT_INVALID);
    assertThat(delivery.reconcile(SCOPE, id(claimed)).syntheticInvocations()).isZero();
    assertThat(count("tp_operation_delivery_events")).isZero();

    var lease = delivery.claim(claimedOwner, original);
    assertThat(delivery.dispatch(lease, claimedOwner, original)).isEqualTo("succeeded");
    var reconciliation = delivery.reconcile(SCOPE, id(claimed));
    assertThat(reconciliation.durableState()).isEqualTo("succeeded");
    assertThat(reconciliation.dispatchStarted()).isTrue();
    assertThat(reconciliation.syntheticObservation()).isEqualTo("succeeded");
    assertThat(reconciliation.syntheticInvocations()).isEqualTo(1);

    assertThat(countReplay("tp_workload_replay_claims")).isEqualTo(1);
    assertThat(count("tp_business_operations")).isEqualTo(1);
    assertThat(count("tp_business_operation_facts")).isEqualTo(3);
    assertThat(count("tp_operation_lifecycle_heads")).isEqualTo(1);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(5);
    assertThat(count("tp_operation_review_bindings")).isEqualTo(1);
    assertThat(count("tp_operation_admissions")).isEqualTo(1);
    assertThat(count("tp_operation_admission_outbox")).isEqualTo(1);
    assertThat(count("tp_operation_delivery_events")).isEqualTo(3);

    long facts = count("tp_operation_lifecycle_facts");
    long events = count("tp_operation_delivery_events");
    var lostSyntheticMemory =
        delivery(
            new SyntheticGitHubDeliveryTarget(
                expected, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS));
    for (int index = 0; index < 3; index++) {
      var readOnly = lostSyntheticMemory.reconcile(SCOPE, id(claimed));
      assertThat(readOnly.durableState()).isEqualTo("succeeded");
      assertThat(readOnly.dispatchStarted()).isTrue();
      assertThat(readOnly.syntheticObservation()).isEqualTo("unknown");
      assertThat(readOnly.syntheticInvocations()).isZero();
    }
    assertCode(
        () -> lostSyntheticMemory.claim(execution(current(claimed)), original),
        OPERATION_DISPATCH_ALREADY_STARTED);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(facts);
    assertThat(count("tp_operation_delivery_events")).isEqualTo(events);
  }

  @Test
  void confirmedRejectionAndUnknownStayDistinctAndNeverResend() {
    Completed rejected =
        complete("confirmed-rejection", SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_REJECTION);
    assertThat(rejected.dispatchResult()).isEqualTo("failed");
    assertThat(rejected.delivery().reconcile(SCOPE, rejected.operationId()))
        .satisfies(
            result -> {
              assertThat(result.durableState()).isEqualTo("failed");
              assertThat(result.dispatchStarted()).isTrue();
              assertThat(result.syntheticObservation()).isEqualTo("failed");
              assertThat(result.syntheticInvocations()).isEqualTo(1);
            });
    assertCode(
        () ->
            rejected
                .delivery()
                .claim(execution(current(rejected.claimed())), rejected.permit()),
        OPERATION_DISPATCH_ALREADY_STARTED);

    Completed unknown = complete("unknown-result", SyntheticGitHubDeliveryTarget.Outcome.UNKNOWN);
    assertThat(unknown.dispatchResult()).isEqualTo("unknown");
    long facts = count("tp_operation_lifecycle_facts");
    long events = count("tp_operation_delivery_events");
    for (int index = 0; index < 3; index++) {
      assertThat(unknown.delivery().reconcile(SCOPE, unknown.operationId()))
          .satisfies(
              result -> {
                assertThat(result.durableState()).isEqualTo("unknown");
                assertThat(result.dispatchStarted()).isTrue();
                assertThat(result.syntheticObservation()).isEqualTo("unknown");
                assertThat(result.syntheticInvocations()).isEqualTo(1);
              });
    }
    assertCode(
        () -> unknown.delivery().claim(execution(current(unknown.claimed())), unknown.permit()),
        OPERATION_DISPATCH_ALREADY_STARTED);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(facts);
    assertThat(count("tp_operation_delivery_events")).isEqualTo(events);

    assertThat(countReplay("tp_workload_replay_claims")).isEqualTo(2);
    assertThat(count("tp_business_operations")).isEqualTo(2);
    assertThat(count("tp_business_operation_facts")).isEqualTo(6);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(10);
    assertThat(count("tp_operation_review_bindings")).isEqualTo(2);
    assertThat(count("tp_operation_admissions")).isEqualTo(2);
    assertThat(count("tp_operation_admission_outbox")).isEqualTo(2);
    assertThat(count("tp_operation_delivery_events")).isEqualTo(6);
  }

  @Test
  void identityApprovalGenerationBindingAndPreconditionNegativesFailClosed() {
    Registered registered = register("authority-negatives");
    activateAuthority(registered.initial());
    JsonNode pending = evaluate(registered.initial());
    long pendingFacts = count("tp_operation_lifecycle_facts");

    assertCode(() -> issuer().issue(execution(pending)), OPERATION_NOT_APPROVED);
    assertCode(() -> nonIndependentReview(pending), OPERATION_REVIEWER_NOT_AUTHORIZED);
    assertCode(
        () -> execution(pending, requester(SCOPE.requesterPrincipalId(), Set.of("operations.evaluate"), fresh())),
        OPERATION_EXECUTOR_NOT_AUTHORIZED);
    assertCode(
        () -> execution(pending, requester("workload:other", Set.of("operations.execute"), fresh())),
        OPERATION_EXECUTOR_NOT_AUTHORIZED);
    assertCode(
        () -> issuer().issue(executionInWrongScope(pending)), OPERATION_PROFILE_MISMATCH);
    assertCode(
        () ->
            issuer()
                .issue(
                    execution(
                        pending,
                        requester(
                            SCOPE.requesterPrincipalId(),
                            Set.of("operations.execute"),
                            Instant.now().minusSeconds(1)))),
        OPERATION_AUTHENTICATION_EXPIRED);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(pendingFacts);
    assertThat(count("tp_operation_admissions")).isZero();
    assertThat(count("tp_operation_delivery_events")).isZero();

    JsonNode approved = approve(pending);
    var observed = PLANNER.observe(SCOPE, approved);
    var staleGeneration =
        new BusinessOperationTransitionPlanner.StateToken(
            observed.operationId(),
            observed.recoveryGeneration() + 1,
            observed.lastSequence(),
            observed.authorizationVersion(),
            observed.executionVersion(),
            observed.evidenceVersion(),
            observed.snapshotHash());
    assertCode(
        () -> issuer().issue(execution(approved, staleGeneration)), OPERATION_PROFILE_MISMATCH);
    assertCode(
        () ->
            new JdbcGitHubOperationPermitIssuer(
                    databases.operations,
                    authority,
                    expectedProfile(102),
                    permitKeys.getPrivate())
                .issue(execution(approved)),
        OPERATION_PROFILE_MISMATCH);

    BusinessOperationExecution owner = execution(approved);
    String permit = issuer().issue(owner).serialized();
    JsonNode claimed = admission().admit(owner, permit);
    var wrongTarget =
        new SyntheticGitHubDeliveryTarget(
            expectedProfile(102), SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    var delivery = delivery(wrongTarget);
    BusinessOperationExecution claimedOwner = execution(claimed);
    var lease = delivery.claim(claimedOwner, permit);
    assertCode(
        () -> delivery.dispatch(lease, claimedOwner, permit),
        OPERATION_TARGET_PRECONDITION_FAILED);
    assertThat(delivery.reconcile(SCOPE, id(claimed)))
        .satisfies(
            result -> {
              assertThat(result.durableState()).isEqualTo("claimed");
              assertThat(result.dispatchStarted()).isFalse();
              assertThat(result.syntheticInvocations()).isZero();
            });
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(3);
    assertThat(count("tp_operation_admissions")).isEqualTo(1);
    assertThat(count("tp_operation_delivery_events")).isEqualTo(1);
  }

  @Test
  void restrictedRuntimeCannotRewriteDeleteOrCreateDeliveryEvidence() throws Exception {
    Completed completed =
        complete("restricted-runtime", SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    long facts = count("tp_operation_lifecycle_facts");
    long events = count("tp_operation_delivery_events");

    databases.assertDeliveryPrivileges(completed.operationId(), authority);

    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(facts);
    assertThat(count("tp_operation_delivery_events")).isEqualTo(events);
    assertThat(completed.delivery().reconcile(SCOPE, completed.operationId()).syntheticInvocations())
        .isEqualTo(1);
  }

  private Registered register(String key) {
    var enrollment =
        new AgentCoreRegistrationIngress.Enrollment(
            DurableOperationLifecycleIdentity.ISSUER,
            DurableOperationLifecycleIdentity.ISSUER + "#workload:synthetic",
            "synthetic-client",
            SCOPE,
            expected,
            "synthetic-retention",
            Duration.ofDays(30));
    var registration =
        DurableAgentCoreRegistration.create(
            identity.verifier(),
            enrollment,
            identity.clock(),
            databases.replay,
            databases.operations,
            64,
            Duration.ofSeconds(2));
    ObjectNode request =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("schemaVersion", "1.0")
            .put("clientRequestKey", key)
            .put("title", "Synthetic durable lifecycle " + key);
    var receipt =
        registration.register(
            identity.token(), null, request.toString().getBytes(StandardCharsets.UTF_8));
    assertThat(receipt.created()).isTrue();
    JsonNode initial =
        new JdbcBusinessOperationRepository(databases.operations)
            .find(SCOPE, receipt.operationId())
            .orElseThrow();
    return new Registered(receipt.operationId(), receipt.executionEnabled(), initial);
  }

  private void activateAuthority(JsonNode initial) {
    if (authority != null) {
      assertThat(authority.evaluate(initial).outcome()).isEqualTo("require_approval");
      return;
    }
    authority =
        new BusinessOperationAuthorityProfile(
            SCOPE,
            expected.adapterId(),
            1,
            policy,
            CanonicalJson.sha256(initial.path("artifactDigests")),
            initial.path("resourceBindingHash").asText(),
            new BusinessOperationReviewSettings(GROUP, HASH, Duration.ofMinutes(2)),
            new BusinessOperationAdmissionSettings(
                "https://synthetic-issuer.invalid",
                "synthetic-key",
                "synthetic-admission",
                permitKeys.getPublic(),
                Duration.ofSeconds(60)));
    OperationAuthorityAdministration.stageNew(databases.operationAdmin, authority);
    OperationAuthorityAdministration.activateStaged(databases.operationAdmin, authority);
  }

  private JsonNode evaluate(JsonNode initial) {
    return reviews()
        .evaluate(
            new BusinessOperationEvaluation(
                SCOPE,
                id(initial),
                PLANNER.observe(SCOPE, initial),
                new TrustedOperationEvaluatorContext(
                    requester(
                        SCOPE.requesterPrincipalId(), Set.of("operations.evaluate"), fresh()),
                    SCOPE.workspaceId())));
  }

  private JsonNode approve(JsonNode pending) {
    Instant membershipExpiry = Instant.now().plusSeconds(180);
    var reviewer =
        new TrustedReviewerContext(
            new TenantPartition(SCOPE.tenantId()),
            "principal:synthetic-reviewer",
            "human",
            true,
            Set.of("operation_reviewer"),
            Set.of("operations.decide"),
            "synthetic-test",
            HASH);
    var command =
        new BusinessOperationReview(
            SCOPE,
            id(pending),
            PLANNER.observe(SCOPE, pending),
            new TrustedOperationReviewerContext(
                reviewer, SCOPE.workspaceId(), membershipExpiry.plusSeconds(30)),
            BusinessOperationReview.Decision.APPROVE);
    var membership =
        new TrustedOperationGroupMembership(
            SCOPE.tenantId(),
            SCOPE.workspaceId(),
            reviewer.reviewerSubjectId(),
            GROUP,
            HASH,
            Instant.now().minusSeconds(1),
            membershipExpiry);
    return reviews().review(command, membership);
  }

  private void nonIndependentReview(JsonNode pending) {
    var reviewer =
        new TrustedReviewerContext(
            new TenantPartition(SCOPE.tenantId()),
            SCOPE.requesterPrincipalId(),
            "human",
            true,
            Set.of("operation_reviewer"),
            Set.of("operations.decide"),
            "synthetic-test",
            HASH);
    new BusinessOperationReview(
        SCOPE,
        id(pending),
        PLANNER.observe(SCOPE, pending),
        new TrustedOperationReviewerContext(reviewer, SCOPE.workspaceId(), fresh()),
        BusinessOperationReview.Decision.APPROVE);
  }

  private Completed complete(
      String key, SyntheticGitHubDeliveryTarget.Outcome targetOutcome) {
    Registered registered = register(key);
    activateAuthority(registered.initial());
    JsonNode approved = approve(evaluate(registered.initial()));
    BusinessOperationExecution owner = execution(approved);
    String permit = issuer().issue(owner).serialized();
    JsonNode claimed = admission().admit(owner, permit);
    var target = new SyntheticGitHubDeliveryTarget(expected, targetOutcome);
    var delivery = delivery(target);
    BusinessOperationExecution claimedOwner = execution(claimed);
    String result =
        delivery.dispatch(delivery.claim(claimedOwner, permit), claimedOwner, permit);
    return new Completed(id(claimed), permit, claimed, delivery, result);
  }

  private BusinessOperationExecution execution(JsonNode snapshot) {
    return execution(
        snapshot,
        PLANNER.observe(SCOPE, snapshot),
        requester(SCOPE.requesterPrincipalId(), Set.of("operations.execute"), fresh()));
  }

  private BusinessOperationExecution execution(
      JsonNode snapshot, BusinessOperationTransitionPlanner.StateToken token) {
    return execution(
        snapshot,
        token,
        requester(SCOPE.requesterPrincipalId(), Set.of("operations.execute"), fresh()));
  }

  private BusinessOperationExecution execution(
      JsonNode snapshot, TrustedRequesterContext requester) {
    return execution(snapshot, PLANNER.observe(SCOPE, snapshot), requester);
  }

  private BusinessOperationExecution execution(
      JsonNode snapshot,
      BusinessOperationTransitionPlanner.StateToken token,
      TrustedRequesterContext requester) {
    return new BusinessOperationExecution(
        SCOPE, id(snapshot), token, requester, SCOPE.workspaceId());
  }

  private BusinessOperationExecution executionInWrongScope(JsonNode snapshot) {
    var wrong =
        new BusinessOperationScope(
            SCOPE.tenantId(), "other-workspace", SCOPE.requesterPrincipalId());
    return new BusinessOperationExecution(
        wrong,
        id(snapshot),
        PLANNER.observe(SCOPE, snapshot),
        requester(SCOPE.requesterPrincipalId(), Set.of("operations.execute"), fresh()),
        wrong.workspaceId());
  }

  private TrustedRequesterContext requester(
      String subject, Set<String> scopes, Instant expiresAt) {
    return new TrustedRequesterContext(
        new TenantPartition(SCOPE.tenantId()),
        subject,
        true,
        Set.of("operation_requester"),
        scopes,
        "synthetic-test",
        HASH,
        expiresAt);
  }

  private JdbcGitHubOperationReviewRepository reviews() {
    return new JdbcGitHubOperationReviewRepository(databases.operations, authority, expected);
  }

  private JdbcGitHubOperationPermitIssuer issuer() {
    return new JdbcGitHubOperationPermitIssuer(
        databases.operations, authority, expected, permitKeys.getPrivate());
  }

  private JdbcGitHubOperationAdmissionRepository admission() {
    return new JdbcGitHubOperationAdmissionRepository(databases.operations, authority, expected);
  }

  private JdbcGitHubOperationDeliveryRepository delivery(
      SyntheticGitHubDeliveryTarget target) {
    return new JdbcGitHubOperationDeliveryRepository(
        databases.operations, authority, expected, target);
  }

  private JsonNode current(JsonNode operation) {
    return new JdbcBusinessOperationLifecycleRepository(databases.operations)
        .findCurrent(SCOPE, id(operation))
        .orElseThrow();
  }

  private GitHubBusinessOperationBinding.ExpectedProfile expectedProfile(long repositoryId) {
    return new GitHubBusinessOperationBinding.ExpectedProfile(
        "github-draft-pr-v1",
        "test",
        1,
        new GitHubBusinessOperationBinding.PolicyRelease(
            "synthetic-github-draft-pr", 1, CanonicalJson.sha256(policy)),
        Map.of("policy_bundle", HASH, "adapter_release", HASH),
        new GitHubPilotTargetBinding(
            71,
            repositoryId,
            201,
            "tracepermit/agentcore-pr-pilot",
            "synthetic-change",
            "main",
            "a".repeat(40),
            "b".repeat(40)));
  }

  private long count(String table) {
    return DurableOperationLifecycleDatabases.count(databases.operationAdmin, table);
  }

  private long countReplay(String table) {
    return DurableOperationLifecycleDatabases.count(databases.replayAdmin, table);
  }

  private static String id(JsonNode operation) {
    return operation.path("operationId").asText();
  }

  private static Instant fresh() {
    return Instant.now().plusSeconds(240);
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
      BusinessOperationStorageException.Code code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessOperationStorageException.class,
            failure -> assertThat(failure.code()).isEqualTo(code))
        .hasMessage(code.name())
        .hasNoCause();
  }

  private record Registered(String operationId, boolean executionEnabled, JsonNode initial) {}

  private record Completed(
      String operationId,
      String permit,
      JsonNode claimed,
      JdbcGitHubOperationDeliveryRepository delivery,
      String dispatchResult) {}

  private static final class DurableOperationLifecycleIdentity {
    static final String ISSUER = "https://identity.example.invalid";
    private static final String AUDIENCE = "https://registration.example.invalid";
    private final KeyPair key;
    private final Clock clock;
    private final Instant now;

    DurableOperationLifecycleIdentity(Instant now) throws Exception {
      this.now = now;
      clock = Clock.fixed(now, ZoneOffset.UTC);
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      key = generator.generateKeyPair();
    }

    Clock clock() {
      return clock;
    }

    OidcAccessTokenVerifier verifier() {
      return new OidcAccessTokenVerifier(
          issuer -> ISSUER.equals(issuer) ? Optional.of(provider()) : Optional.empty(),
          (configuration, keyId, algorithm, observed) -> {
            if (!"synthetic-key".equals(keyId) || !"RS256".equals(algorithm)) {
              throw new IllegalStateException("SYNTHETIC_UNTRUSTED_KEY");
            }
            return (RSAPublicKey) key.getPublic();
          },
          clock);
    }

    String token() {
      ObjectNode header =
          JsonSupport.MAPPER
              .createObjectNode()
              .put("alg", "RS256")
              .put("typ", "at+jwt")
              .put("kid", "synthetic-key");
      ObjectNode claims =
          JsonSupport.MAPPER
              .createObjectNode()
              .put("iss", ISSUER)
              .put("aud", AUDIENCE)
              .put("sub", "workload:synthetic")
              .put("client_id", "synthetic-client")
              .put("org_id", "synthetic-org")
              .put("scope", "operations.register")
              .put("iat", now.minusSeconds(30).getEpochSecond())
              .put("exp", now.plusSeconds(270).getEpochSecond())
              .put("jti", UUID.randomUUID().toString());
      var encoder = Base64.getUrlEncoder().withoutPadding();
      String input =
          encoder.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8))
              + "."
              + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
      try {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return "Bearer " + input + "." + encoder.encodeToString(signer.sign());
      } catch (Exception failure) {
        throw new AssertionError("SYNTHETIC_SIGNING_FAILED");
      }
    }

    private static OidcProviderConfiguration provider() {
      return new OidcProviderConfiguration(
          URI.create(ISSUER),
          AUDIENCE,
          URI.create(ISSUER + "/jwks"),
          URI.create(AUDIENCE + "/metadata"),
          List.of(URI.create(ISSUER)),
          "Synthetic durable lifecycle registration",
          Map.of("synthetic-client", "Synthetic client"),
          "org_id",
          Map.of("synthetic-org", SCOPE.tenantId()),
          Set.of(ConnectorScope.of("operations.register")),
          ConnectorOrigin.CLI,
          Duration.ofMinutes(5),
          Duration.ofMinutes(1),
          Duration.ofSeconds(30));
    }
  }

  private static final class DurableOperationLifecycleDatabases implements AutoCloseable {
    private static final String USER = "tp_durable_lifecycle";
    private static final List<String> OPERATION_TABLES =
        List.of(
            "tp_operation_schema_history",
            "tp_business_operations",
            "tp_business_operation_facts",
            "tp_operation_lifecycle_heads",
            "tp_operation_lifecycle_facts",
            "tp_operation_authority_controls",
            "tp_operation_review_bindings",
            "tp_operation_admissions",
            "tp_operation_admission_outbox",
            "tp_operation_delivery_events");

    final JdbcDatabaseContainer<?> container;
    final boolean postgres;
    final BoundedJdbcSource replayAdmin;
    final BoundedJdbcSource operationAdmin;
    final BoundedJdbcSource replay;
    final BoundedJdbcSource operations;

    DurableOperationLifecycleDatabases(String image) {
      postgres = image.startsWith("postgres:");
      int port = postgres ? 5432 : 3306;
      container =
          postgres ? new PostgreSQLContainer<>(image) : new MariaDBContainer<>(image);
      String secret = UUID.randomUUID().toString();
      container
          .withPassword(secret)
          .withStartupTimeout(Duration.ofSeconds(60))
          .withCreateContainerCmdModifier(
              command ->
                  command
                      .getHostConfig()
                      .withPortBindings(
                          new PortBinding(
                              Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                              new ExposedPort(port))));
      try {
        container.start();
        var bindings = container.getContainerInfo().getHostConfig().getPortBindings().getBindings();
        assertThat(bindings).hasSize(1);
        for (var endpoints : bindings.values()) {
          for (var endpoint : endpoints) {
            assertThat(endpoint.getHostIp()).isEqualTo("127.0.0.1");
          }
        }
        String owner = postgres ? container.getUsername() : "root";
        String prefix =
            (postgres ? "jdbc:postgresql" : "jdbc:mariadb")
                + "://127.0.0.1:"
                + container.getMappedPort(port)
                + "/";
        String bounds =
            postgres
                ? "?connectTimeout=5&socketTimeout=10"
                : "?connectTimeout=5000&socketTimeout=10000";
        var bootstrap =
            new BoundedJdbcSource(
                prefix + container.getDatabaseName() + bounds, owner, secret);
        execute(bootstrap, "CREATE DATABASE tp_replay");
        execute(bootstrap, "CREATE DATABASE tp_operations");
        replayAdmin = new BoundedJdbcSource(prefix + "tp_replay" + bounds, owner, secret);
        operationAdmin =
            new BoundedJdbcSource(prefix + "tp_operations" + bounds, owner, secret);
        WorkloadIdentityReplayDatabase.initializeEmpty(replayAdmin, 64);
        OperationDatabase.initializeDeliveryEmpty(operationAdmin);

        String password = UUID.randomUUID().toString();
        execute(
            bootstrap,
            postgres
                ? "CREATE ROLE " + USER + " LOGIN PASSWORD '" + password + "'"
                : "CREATE USER '" + USER + "'@'%' IDENTIFIED BY '" + password + "'");
        replay = new BoundedJdbcSource(replayAdmin.url, USER, password);
        operations = new BoundedJdbcSource(operationAdmin.url, USER, password);
        if (postgres) {
          execute(replayAdmin, "GRANT USAGE ON SCHEMA public TO " + USER);
          execute(operationAdmin, "GRANT USAGE ON SCHEMA public TO " + USER);
        }
        for (String table :
            List.of(
                "tp_identity_replay_schema_history",
                "tp_workload_replay_claims",
                "tp_workload_replay_state")) {
          grant(replayAdmin, "tp_replay", "SELECT", table);
        }
        grant(replayAdmin, "tp_replay", "INSERT", "tp_workload_replay_claims");
        grant(replayAdmin, "tp_replay", "UPDATE", "tp_workload_replay_state");
        for (String table : OPERATION_TABLES) {
          grant(operationAdmin, "tp_operations", "SELECT", table);
        }
        for (String table :
            List.of(
                "tp_business_operations",
                "tp_business_operation_facts",
                "tp_operation_lifecycle_heads",
                "tp_operation_lifecycle_facts",
                "tp_operation_review_bindings",
                "tp_operation_admissions",
                "tp_operation_admission_outbox",
                "tp_operation_delivery_events")) {
          grant(operationAdmin, "tp_operations", "INSERT", table);
        }
        execute(
            operationAdmin,
            "GRANT UPDATE(snapshot_hash, snapshot_json) ON "
                + table("tp_operations", "tp_operation_lifecycle_heads")
                + " TO "
                + principal());
        if (postgres) {
          execute(
              operationAdmin,
              "GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO " + USER);
        }
      } catch (RuntimeException | Error failure) {
        container.stop();
        throw new AssertionError("SYNTHETIC_DURABLE_LIFECYCLE_DATABASE_SETUP_FAILED", failure);
      }
    }

    void reset() {
      replay.reset();
      operations.reset();
      for (String table :
          List.of(
              "tp_operation_delivery_events",
              "tp_operation_admission_outbox",
              "tp_operation_admissions",
              "tp_operation_review_bindings",
              "tp_operation_lifecycle_facts",
              "tp_operation_lifecycle_heads",
              "tp_business_operation_facts",
              "tp_business_operations",
              "tp_operation_authority_controls")) {
        execute(operationAdmin, "DELETE FROM " + table);
      }
      execute(replayAdmin, "DELETE FROM tp_workload_replay_claims");
      execute(
          replayAdmin,
          "UPDATE tp_workload_replay_state SET total_claims=0, capacity=64 WHERE singleton=1");
    }

    void assertDeliveryPrivileges(
        String operationId, BusinessOperationAuthorityProfile authority) throws SQLException {
      for (String sql :
          List.of(
              "UPDATE tp_operation_delivery_events SET event_hash = event_hash WHERE operation_id = ?",
              "DELETE FROM tp_operation_delivery_events WHERE operation_id = ?")) {
        try (var connection = operationAdmin.getConnection()) {
          connection.setAutoCommit(false);
          try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setString(1, operationId);
            assertThat(statement.executeUpdate()).isEqualTo(3);
          } finally {
            connection.rollback();
          }
        }
        try (var connection = operations.getConnection();
            var statement = connection.prepareStatement(sql)) {
          statement.setQueryTimeout(5);
          statement.setString(1, operationId);
          assertDenied(statement::executeUpdate);
        }
      }

      String ddl = "CREATE TABLE tp_durable_lifecycle_forbidden (id INTEGER)";
      try (var connection = operationAdmin.getConnection();
          var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        statement.execute(ddl);
        statement.execute("DROP TABLE tp_durable_lifecycle_forbidden");
      }
      try (var connection = operations.getConnection();
          var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        assertDenied(() -> statement.execute(ddl));
      }

      try (var connection = operations.getConnection();
          var statement =
              connection.prepareStatement(
                  "UPDATE tp_operation_authority_controls SET control_state = control_state WHERE scope_hash = ?")) {
        statement.setQueryTimeout(5);
        statement.setString(1, authority.scope().fingerprint());
        assertDenied(statement::executeUpdate);
      }
      if (postgres) {
        try (var connection = operations.getConnection()) {
          connection.setAutoCommit(false);
          try (var statement =
              connection.prepareStatement(
                  "UPDATE tp_operation_authority_controls SET lock_marker = lock_marker WHERE scope_hash = ?")) {
            statement.setQueryTimeout(5);
            statement.setString(1, authority.scope().fingerprint());
            assertThat(statement.executeUpdate()).isEqualTo(1);
          } finally {
            connection.rollback();
          }
        }
      }
    }

    private void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
      assertThatThrownBy(call)
          .isInstanceOfSatisfying(
              SQLException.class,
              failure ->
                  assertThat(
                          postgres
                              ? "42501".equals(failure.getSQLState())
                              : Set.of(1044, 1142, 1143).contains(failure.getErrorCode()))
                      .isTrue());
    }

    private void grant(
        BoundedJdbcSource administrator, String database, String privilege, String table) {
      execute(
          administrator,
          "GRANT "
              + privilege
              + " ON "
              + table(database, table)
              + " TO "
              + principal());
    }

    private String table(String database, String table) {
      return postgres ? table : "`" + database + "`." + table;
    }

    private String principal() {
      return postgres ? USER : "'" + USER + "'@'%'";
    }

    static void execute(BoundedJdbcSource source, String sql) {
      try (var connection = source.getConnection();
          var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        statement.execute(sql);
      } catch (SQLException failure) {
        throw new AssertionError("SYNTHETIC_DURABLE_LIFECYCLE_ADMIN_ACTION_FAILED", failure);
      }
    }

    static long count(BoundedJdbcSource source, String table) {
      try (var connection = source.getConnection();
          var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          if (!rows.next()) {
            throw new AssertionError("SYNTHETIC_DURABLE_LIFECYCLE_COUNT_MISSING");
          }
          return rows.getLong(1);
        }
      } catch (SQLException failure) {
        throw new AssertionError("SYNTHETIC_DURABLE_LIFECYCLE_COUNT_FAILED", failure);
      }
    }

    @Override
    public void close() {
      container.stop();
    }
  }
}
