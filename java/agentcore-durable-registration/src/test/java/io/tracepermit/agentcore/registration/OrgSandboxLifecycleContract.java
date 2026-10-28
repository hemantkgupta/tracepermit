package io.tracepermit.agentcore.registration;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_AUTHENTICATION_EXPIRED;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_DISPATCH_ALREADY_STARTED;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_NOT_APPROVED;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_PERMIT_INVALID;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_PROFILE_MISMATCH;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_STATE_STALE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.agentcore.OktaOperationAuthority;
import io.tracepermit.adapters.remote.agentcore.OktaOperationAuthorityException;
import io.tracepermit.adapters.remote.agentcore.OktaOperationReviewerRoster;
import io.tracepermit.adapters.remote.github.BoundGitHubOperation;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubOperationProfile;
import io.tracepermit.adapters.remote.github.GitHubOrganizationOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubOrganizationTargetBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.BusinessOperationAdmissionSettings;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationEvaluation;
import io.tracepermit.application.BusinessOperationExecution;
import io.tracepermit.application.BusinessOperationRepository;
import io.tracepermit.application.BusinessOperationReview;
import io.tracepermit.application.BusinessOperationReviewSettings;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import io.tracepermit.identity.OidcTokenProfile;
import io.tracepermit.identity.WorkloadIdentityReplayStore;
import io.tracepermit.persistence.identity.JdbcWorkloadIdentityReplayStore;
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
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
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
 * Three-engine qualification of the existing organization-profile library composition.
 *
 * <p>Every identity and signing key is generated in-process. The only target is the fixed synthetic
 * target; no provider credential, network transport, ingress, EKS or real GitHub dispatch exists in
 * this fixture.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
abstract class OrgSandboxLifecycleContract {
  private static final String ISSUER = "https://synthetic.okta.invalid/oauth2/org-sandbox";
  private static final String AUDIENCE = "https://tracepermit.synthetic.invalid/org-sandbox";
  private static final String EXTERNAL_TENANT = "synthetic-org";
  private static final String TENANT = "tenant:synthetic-org";
  private static final String WORKSPACE = "github-org-sandbox";
  private static final String REQUESTER_CLIENT = "tracepermit-org-requester";
  private static final String REVIEWER_CLIENT = "tracepermit-org-reviewer";
  private static final String REQUESTER_SUBJECT = "00u-synthetic-requester";
  private static final String REVIEWER_SUBJECT = "00u-synthetic-reviewer";
  private static final String REQUESTER_GROUP = "org-sandbox-requesters";
  private static final String REVIEWER_GROUP = "org-sandbox-reviewers";
  private static final String REPOSITORY = "example-sandbox/tracepermit-change-control";
  private static final String HEAD = "tracepermit/fixture-change";
  private static final String BASE = "main";
  private static final String HASH = "sha256:" + "a".repeat(64);
  private static final BusinessOperationScope SCOPE =
      new BusinessOperationScope(TENANT, WORKSPACE, "principal:synthetic-requester-owner");
  private static final BusinessOperationTransitionPlanner PLANNER =
      new BusinessOperationTransitionPlanner();

  private OrgSandboxDatabases databases;
  private IdentityFixture identity;
  private KeyPair permitKeys;
  private ObjectNode policy;
  private GitHubOrganizationOperationBinding.ExpectedProfile expected;
  private OktaOperationReviewerRoster roster;

  abstract String image();

  @BeforeAll
  void startDatabase() throws Exception {
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
                 "approvalGroup":"org-sandbox-reviewers"}}]}
                """);
    expected = organizationProfile(organizationTarget(61, 71, 101, 201, REPOSITORY, HEAD, BASE));
    databases = new OrgSandboxDatabases(image());
  }

  @BeforeEach
  void resetStoresAndTrust() throws Exception {
    databases.reset();
    identity = new IdentityFixture();
    permitKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    roster = reviewerRoster(REVIEWER_SUBJECT);
  }

  @AfterAll
  void stopDatabase() {
    if (databases != null) databases.close();
  }

  @Test
  void verifiedOktaOrganizationLifecyclePersistsOneSyntheticSuccessUnderRestrictedGrants()
      throws Exception {
    Composition composition = composition("org-valid");
    JsonNode approved = approve(evaluate(composition), composition);
    BusinessOperationExecution owner = execution(approved, composition, Duration.ofMinutes(4));
    String permit = issuer(composition).issue(owner).serialized();
    JsonNode claimed = admission(composition).admit(owner, permit);
    var target =
        new SyntheticGitHubDeliveryTarget(
            expected, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    var delivery = delivery(composition, target);
    BusinessOperationExecution claimedOwner =
        execution(claimed, composition, Duration.ofMinutes(4));

    var lease = delivery.claim(claimedOwner, permit);
    assertThat(delivery.dispatch(lease, claimedOwner, permit)).isEqualTo("succeeded");
    assertThat(delivery.reconcile(SCOPE, id(claimed)))
        .satisfies(
            result -> {
              assertThat(result.durableState()).isEqualTo("succeeded");
              assertThat(result.dispatchStarted()).isTrue();
              assertThat(result.syntheticObservation()).isEqualTo("succeeded");
              assertThat(result.syntheticInvocations()).isEqualTo(1);
            });
    assertThat(countReplay("tp_workload_replay_claims")).isEqualTo(1);
    assertThat(count("tp_operation_review_bindings")).isEqualTo(1);
    assertThat(count("tp_operation_admissions")).isEqualTo(1);
    assertThat(count("tp_operation_admission_outbox")).isEqualTo(1);
    assertThat(count("tp_operation_delivery_events")).isEqualTo(3);

    databases.assertRestrictedRuntime(id(claimed), composition.profile());
  }

  @Test
  void pendingAndDeniedApprovalNeverInvokeTheSyntheticTarget() {
    Composition composition = composition("org-denied");
    JsonNode pending = evaluate(composition);
    var target =
        new SyntheticGitHubDeliveryTarget(
            expected, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    var delivery = delivery(composition, target);

    assertCode(
        () -> issuer(composition).issue(execution(pending, composition, Duration.ofMinutes(4))),
        OPERATION_NOT_APPROVED);
    assertThat(delivery.reconcile(SCOPE, id(pending)).syntheticInvocations()).isZero();

    JsonNode denied = review(pending, composition, BusinessOperationReview.Decision.DENY);
    assertThat(denied.path("authorization").path("status").asText()).isEqualTo("denied");
    assertCode(
        () -> issuer(composition).issue(execution(denied, composition, Duration.ofMinutes(4))),
        OPERATION_NOT_APPROVED);
    assertThat(delivery.reconcile(SCOPE, id(denied)).syntheticInvocations()).isZero();
    assertThat(count("tp_operation_admissions")).isZero();
    assertThat(count("tp_operation_delivery_events")).isZero();
  }

  @Test
  void selfReviewAndCrossClientSameSubjectAreRefusedBeforeDurableReview() {
    Registered registered = registerOrganization("org-independent-review");

    OktaOperationReviewerRoster selfRoster = reviewerRoster(REVIEWER_SUBJECT);
    String reviewerPrincipal = selfRosterEntry(REVIEWER_SUBJECT).principalId();
    BusinessOperationScope selfScope =
        new BusinessOperationScope(TENANT, WORKSPACE, reviewerPrincipal);
    BusinessOperationAuthorityProfile selfProfile =
        authorityProfile(registered.initial(), expected, selfRoster, selfScope);
    OktaOperationAuthority selfAuthority = authority(selfScope, selfRoster, selfProfile);
    assertAuthorityRejected(
        () ->
            selfAuthority.reviewer(
                identity.token(
                    REVIEWER_CLIENT,
                    REVIEWER_SUBJECT,
                    REVIEWER_GROUP,
                    Set.of("operations.decide"),
                    Duration.ofMinutes(5))));

    OktaOperationReviewerRoster sameSubjectRoster = reviewerRoster(REQUESTER_SUBJECT);
    BusinessOperationAuthorityProfile sameSubjectProfile =
        authorityProfile(registered.initial(), expected, sameSubjectRoster, SCOPE);
    OktaOperationAuthority sameSubjectAuthority =
        authority(SCOPE, sameSubjectRoster, sameSubjectProfile);
    assertAuthorityRejected(
        () ->
            sameSubjectAuthority.reviewer(
                identity.token(
                    REVIEWER_CLIENT,
                    REQUESTER_SUBJECT,
                    REVIEWER_GROUP,
                    Set.of("operations.decide"),
                    Duration.ofMinutes(5))));

    assertThat(count("tp_operation_lifecycle_facts")).isZero();
    assertThat(count("tp_operation_review_bindings")).isZero();
    assertThat(count("tp_operation_admissions")).isZero();
    assertThat(count("tp_operation_delivery_events")).isZero();
  }

  @Test
  void profileAppRepositoryRefAndShaSubstitutionsFailClosed() {
    Composition composition = composition("org-substitutions");
    var original = expected.targetBinding();
    List<GitHubOrganizationOperationBinding.ExpectedProfile> substitutions =
        List.of(
            new GitHubOrganizationOperationBinding.ExpectedProfile(
                expected.adapterId(),
                expected.environment(),
                expected.recoveryGeneration() + 1,
                expected.policyRelease(),
                expected.artifactDigests(),
                original),
            new GitHubOrganizationOperationBinding.ExpectedProfile(
                expected.adapterId(),
                "staging",
                expected.recoveryGeneration(),
                expected.policyRelease(),
                expected.artifactDigests(),
                original),
            organizationProfile(
                organizationTarget(
                    original.appId() + 1,
                    original.installationId(),
                    original.repositoryId(),
                    original.ownerId(),
                    original.repository(),
                    original.head(),
                    original.base())),
            organizationProfile(
                organizationTarget(
                    original.appId(),
                    original.installationId(),
                    original.repositoryId(),
                    original.ownerId(),
                    "example-sandbox/other-change-control",
                    original.head(),
                    original.base())),
            organizationProfile(
                organizationTarget(
                    original.appId(),
                    original.installationId(),
                    original.repositoryId(),
                    original.ownerId(),
                    original.repository(),
                    "tracepermit/other-fixture-change",
                    original.base())),
            organizationProfile(
                new GitHubOrganizationTargetBinding(
                    original.appId(),
                    original.installationId(),
                    original.repositoryId(),
                    original.ownerId(),
                    original.repository(),
                    original.head(),
                    original.base(),
                    "c".repeat(40),
                    original.baseSha(),
                    GitHubOrganizationTargetBinding.requiredPermissions())));

    for (GitHubOperationProfile substitution : substitutions) {
      var changed =
          new JdbcGitHubOperationReviewRepository(
              databases.operations, composition.profile(), substitution);
      assertCode(
          () ->
              changed.evaluate(
                  new BusinessOperationEvaluation(
                      SCOPE,
                      composition.registered().operationId(),
                      PLANNER.observe(SCOPE, composition.registered().initial()),
                      composition
                          .authority()
                          .evaluator(identity.requesterToken("operations.evaluate")))),
          OPERATION_PROFILE_MISMATCH);
    }

    assertThat(count("tp_operation_lifecycle_facts")).isZero();
    assertThat(count("tp_operation_review_bindings")).isZero();
    assertThat(count("tp_operation_admissions")).isZero();
    assertThat(count("tp_operation_delivery_events")).isZero();
  }

  @Test
  void organizationPermitsAreAdapterPinnedBeforeAdmission() {
    Composition composition = composition("org-adapter-pin");
    JsonNode approved = approve(evaluate(composition), composition);
    BusinessOperationExecution owner = execution(approved, composition, Duration.ofMinutes(4));
    String permit = issuer(composition).issue(owner).serialized();

    var legacyAdmission =
        new JdbcGitHubOperationAdmissionRepository(
            databases.operations, composition.profile(), legacyProfile());
    assertCode(() -> legacyAdmission.admit(owner, permit), OPERATION_PERMIT_INVALID);
    assertThat(count("tp_operation_admissions")).isZero();

    JsonNode claimed = admission(composition).admit(owner, permit);
    assertThat(claimed.path("execution").path("status").asText()).isEqualTo("claimed");
    assertThat(count("tp_operation_admissions")).isEqualTo(1);
    assertThat(count("tp_operation_delivery_events")).isZero();
  }

  @Test
  void eightReviewContendersProduceOneDurableDecisionAndNoTargetCall() throws Exception {
    Composition composition = composition("org-review-contention");
    JsonNode pending = evaluate(composition);
    var target =
        new SyntheticGitHubDeliveryTarget(
            expected, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    var delivery = delivery(composition, target);
    int contenders = 8;
    CountDownLatch start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(contenders);
    List<Future<ContenderResult>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < contenders; index++) {
        OktaOperationAuthority.ReviewAuthority reviewer =
            composition
                .authority()
                .reviewer(
                    identity.token(
                        REVIEWER_CLIENT,
                        REVIEWER_SUBJECT,
                        REVIEWER_GROUP,
                        Set.of("operations.decide"),
                        Duration.ofMinutes(5)));
        var command =
            new BusinessOperationReview(
                SCOPE,
                id(pending),
                PLANNER.observe(SCOPE, pending),
                reviewer.reviewer(),
                BusinessOperationReview.Decision.APPROVE);
        futures.add(
            pool.submit(
                () -> {
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("ORG_SANDBOX_CONTENDER_START_TIMEOUT");
                  }
                  try {
                    reviews(composition).review(command, reviewer.membership());
                    return new ContenderResult(true, null);
                  } catch (BusinessOperationStorageException rejected) {
                    return new ContenderResult(false, rejected.code());
                  }
                }));
      }
      start.countDown();
      List<ContenderResult> results = new ArrayList<>();
      for (Future<ContenderResult> future : futures) {
        results.add(future.get(20, TimeUnit.SECONDS));
      }
      assertThat(results.stream().filter(ContenderResult::committed)).hasSize(1);
      assertThat(results.stream().filter(result -> !result.committed()).map(ContenderResult::code))
          .containsOnly(OPERATION_STATE_STALE);
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(current(pending).path("authorization").path("status").asText())
        .isEqualTo("approved");
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(2);
    assertThat(delivery.reconcile(SCOPE, id(pending)).syntheticInvocations()).isZero();
    assertThat(count("tp_operation_admissions")).isZero();
    assertThat(count("tp_operation_delivery_events")).isZero();
  }

  @Test
  void reviewerExpiryDuringRealHeadLockWaitRefusesWithoutCommit() throws Exception {
    Composition composition = composition("org-review-expiry");
    JsonNode pending = evaluate(composition);
    OktaOperationAuthority.ReviewAuthority reviewer =
        composition
            .authority()
            .reviewer(
                identity.token(
                    REVIEWER_CLIENT,
                    REVIEWER_SUBJECT,
                    REVIEWER_GROUP,
                    Set.of("operations.decide"),
                    Duration.ofSeconds(3)));
    var command =
        new BusinessOperationReview(
            SCOPE,
            id(pending),
            PLANNER.observe(SCOPE, pending),
            reviewer.reviewer(),
            BusinessOperationReview.Decision.APPROVE);
    CountDownLatch entered = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    Future<JsonNode> waiting = null;
    try (Connection holder = databases.operationAdmin.getConnection()) {
      holder.setAutoCommit(false);
      lockHead(holder, id(pending));
      databases.operations.armHeadLockProbe();
      waiting =
          pool.submit(
              () -> {
                entered.countDown();
                return reviews(composition).review(command, reviewer.membership());
              });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      int waitingPid = databases.operations.awaitHeadLockAttempt();
      databases.awaitRuntimeLockWait(
          waitingPid, reviewer.reviewer().authorityExpiresAt());
      Future<JsonNode> blocked = waiting;
      assertThatThrownBy(() -> blocked.get(250, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      awaitAfter(reviewer.reviewer().authorityExpiresAt());
      holder.rollback();
      assertFutureCode(waiting, OPERATION_AUTHENTICATION_EXPIRED);
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(current(pending).path("authorization").path("status").asText())
        .isEqualTo("awaiting_approval");
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(1);
    assertThat(count("tp_operation_admissions")).isZero();
    assertThat(count("tp_operation_delivery_events")).isZero();
  }

  @Test
  void durableUnknownReconcilesReadOnlyAndNeverResends() {
    Composition composition = composition("org-unknown");
    JsonNode approved = approve(evaluate(composition), composition);
    BusinessOperationExecution owner = execution(approved, composition, Duration.ofMinutes(4));
    String permit = issuer(composition).issue(owner).serialized();
    JsonNode claimed = admission(composition).admit(owner, permit);
    var target =
        new SyntheticGitHubDeliveryTarget(expected, SyntheticGitHubDeliveryTarget.Outcome.UNKNOWN);
    var delivery = delivery(composition, target);
    BusinessOperationExecution claimedOwner =
        execution(claimed, composition, Duration.ofMinutes(4));

    assertThat(delivery.dispatch(delivery.claim(claimedOwner, permit), claimedOwner, permit))
        .isEqualTo("unknown");
    long facts = count("tp_operation_lifecycle_facts");
    long events = count("tp_operation_delivery_events");
    assertThat(delivery.reconcile(SCOPE, id(claimed)))
        .satisfies(
            result -> {
              assertThat(result.durableState()).isEqualTo("unknown");
              assertThat(result.dispatchStarted()).isTrue();
              assertThat(result.syntheticObservation()).isEqualTo("unknown");
              assertThat(result.syntheticInvocations()).isEqualTo(1);
            });
    assertCode(
        () ->
            delivery.claim(
                execution(current(claimed), composition, Duration.ofMinutes(4)), permit),
        OPERATION_DISPATCH_ALREADY_STARTED);

    var restartedTarget =
        new SyntheticGitHubDeliveryTarget(expected, SyntheticGitHubDeliveryTarget.Outcome.UNKNOWN);
    var restarted = delivery(composition, restartedTarget);
    for (int index = 0; index < 3; index++) {
      assertThat(restarted.reconcile(SCOPE, id(claimed)))
          .satisfies(
              result -> {
                assertThat(result.durableState()).isEqualTo("unknown");
                assertThat(result.dispatchStarted()).isTrue();
                assertThat(result.syntheticObservation()).isEqualTo("unknown");
                assertThat(result.syntheticInvocations()).isZero();
              });
    }
    assertCode(
        () ->
            restarted.claim(
                execution(current(claimed), composition, Duration.ofMinutes(4)), permit),
        OPERATION_DISPATCH_ALREADY_STARTED);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(facts);
    assertThat(count("tp_operation_delivery_events")).isEqualTo(events);
  }

  @Test
  void legacyTrackEHashesConstructorsAndPermitWireRemainCompatible() throws Exception {
    GitHubBusinessOperationBinding.ExpectedProfile legacy = legacyProfile();
    Registered registered = registerLegacy("legacy-compatibility", legacy);
    BoundGitHubOperation original =
        GitHubBusinessOperationBinding.bind(SCOPE, registered.initial(), legacy);
    BoundGitHubOperation generalized =
        ((GitHubOperationProfile) legacy).bind(SCOPE, registered.initial());
    assertThat(generalized.profileFingerprint()).isEqualTo(original.profileFingerprint());
    assertThat(generalized.bindingDigest()).isEqualTo(original.bindingDigest());

    BusinessOperationAuthorityProfile profile =
        authorityProfile(registered.initial(), legacy, roster, SCOPE);
    OperationAuthorityAdministration.stageNew(databases.operationAdmin, profile);
    OperationAuthorityAdministration.activateStaged(databases.operationAdmin, profile);
    OktaOperationAuthority verified = authority(SCOPE, roster, profile);
    Composition composition = new Composition(registered, profile, verified);
    JsonNode approved = approve(evaluate(composition, legacy), composition, legacy);
    BusinessOperationExecution owner = execution(approved, composition, Duration.ofMinutes(4));

    var legacyIssuer =
        new JdbcGitHubOperationPermitIssuer(
            databases.operations, profile, legacy, permitKeys.getPrivate());
    String permit = legacyIssuer.issue(owner).serialized();
    String[] wire = permit.split("\\.", -1);
    assertThat(wire).hasSize(3);
    assertThat(new String(Base64.getUrlDecoder().decode(wire[0]), StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"alg\":\"EdDSA\",\"kid\":\"synthetic-permit-key\","
                + "\"typ\":\"tracepermit-business-operation-permit-v1+jws\"}");
    String payload =
        new String(Base64.getUrlDecoder().decode(wire[1]), StandardCharsets.UTF_8);
    JsonNode claims = JsonSupport.MAPPER.readTree(payload);
    assertThat(payload).isEqualTo(CanonicalJson.canonicalize(claims));
    assertThat(claims.path("adapterId").asText())
        .isEqualTo(GitHubBusinessOperationBinding.ADAPTER_ID);

    var legacyAdmission =
        new JdbcGitHubOperationAdmissionRepository(databases.operations, profile, legacy);
    JsonNode claimed = legacyAdmission.admit(owner, permit);
    var target =
        new SyntheticGitHubDeliveryTarget(
            legacy, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    var legacyDelivery =
        new JdbcGitHubOperationDeliveryRepository(databases.operations, profile, legacy, target);
    BusinessOperationExecution claimedOwner =
        execution(claimed, composition, Duration.ofMinutes(4));
    assertThat(
            legacyDelivery.dispatch(
                legacyDelivery.claim(claimedOwner, permit), claimedOwner, permit))
        .isEqualTo("succeeded");

    assertThat(
            AgentCoreRegistrationIngress.class.getConstructor(
                OidcAccessTokenVerifier.class,
                WorkloadIdentityReplayStore.class,
                BusinessOperationRepository.class,
                Clock.class,
                AgentCoreRegistrationIngress.Enrollment.class))
        .isNotNull();
    assertThat(
            JdbcGitHubOperationReviewRepository.class.getConstructor(
                DataSource.class,
                BusinessOperationAuthorityProfile.class,
                GitHubBusinessOperationBinding.ExpectedProfile.class))
        .isNotNull();
    assertThat(
            JdbcGitHubOperationPermitIssuer.class.getConstructor(
                DataSource.class,
                BusinessOperationAuthorityProfile.class,
                GitHubBusinessOperationBinding.ExpectedProfile.class,
                java.security.PrivateKey.class))
        .isNotNull();
    assertThat(
            JdbcGitHubOperationAdmissionRepository.class.getConstructor(
                DataSource.class,
                BusinessOperationAuthorityProfile.class,
                GitHubBusinessOperationBinding.ExpectedProfile.class))
        .isNotNull();
    assertThat(
            JdbcGitHubOperationDeliveryRepository.class.getConstructor(
                DataSource.class,
                BusinessOperationAuthorityProfile.class,
                GitHubBusinessOperationBinding.ExpectedProfile.class,
                SyntheticGitHubDeliveryTarget.class))
        .isNotNull();
  }

  private Composition composition(String key) {
    Registered registered = registerOrganization(key);
    BusinessOperationAuthorityProfile profile =
        authorityProfile(registered.initial(), expected, roster, SCOPE);
    OperationAuthorityAdministration.stageNew(databases.operationAdmin, profile);
    OperationAuthorityAdministration.activateStaged(databases.operationAdmin, profile);
    return new Composition(registered, profile, authority(SCOPE, roster, profile));
  }

  private Registered registerOrganization(String key) {
    var enrollment =
        new AgentCoreRegistrationIngress.OrganizationEnrollment(
            ISSUER,
            ISSUER + "#" + REQUESTER_SUBJECT,
            REQUESTER_CLIENT,
            SCOPE,
            expected,
            "synthetic-org-retention",
            Duration.ofDays(30));
    var ingress =
        AgentCoreRegistrationIngress.organization(
            identity.requesterVerifier,
            new JdbcWorkloadIdentityReplayStore(databases.replay, 64, Duration.ofSeconds(2)),
            new JdbcBusinessOperationRepository(databases.operations),
            identity.clock,
            enrollment);
    return register(key, ingress);
  }

  private Registered registerLegacy(
      String key, GitHubBusinessOperationBinding.ExpectedProfile profile) {
    var enrollment =
        new AgentCoreRegistrationIngress.Enrollment(
            ISSUER,
            ISSUER + "#" + REQUESTER_SUBJECT,
            REQUESTER_CLIENT,
            SCOPE,
            profile,
            "synthetic-track-e-retention",
            Duration.ofDays(30));
    var ingress =
        new AgentCoreRegistrationIngress(
            identity.requesterVerifier,
            new JdbcWorkloadIdentityReplayStore(databases.replay, 64, Duration.ofSeconds(2)),
            new JdbcBusinessOperationRepository(databases.operations),
            identity.clock,
            enrollment);
    return register(key, ingress);
  }

  private Registered register(String key, AgentCoreRegistrationIngress ingress) {
    ObjectNode request =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("schemaVersion", "1.0")
            .put("clientRequestKey", key)
            .put("title", "Synthetic organization sandbox " + key);
    var receipt =
        ingress.register(
            identity.requesterToken("operations.register"),
            null,
            request.toString().getBytes(StandardCharsets.UTF_8));
    assertThat(receipt.created()).isTrue();
    assertThat(receipt.executionEnabled()).isFalse();
    JsonNode initial =
        new JdbcBusinessOperationRepository(databases.operations)
            .find(SCOPE, receipt.operationId())
            .orElseThrow();
    return new Registered(receipt.operationId(), initial);
  }

  private BusinessOperationAuthorityProfile authorityProfile(
      JsonNode initial,
      GitHubOperationProfile target,
      OktaOperationReviewerRoster selectedRoster,
      BusinessOperationScope scope) {
    return new BusinessOperationAuthorityProfile(
        scope,
        target.adapterId(),
        target.recoveryGeneration(),
        policy,
        CanonicalJson.sha256(initial.path("artifactDigests")),
        initial.path("resourceBindingHash").asText(),
        new BusinessOperationReviewSettings(
            REVIEWER_GROUP, selectedRoster.fingerprint(), Duration.ofMinutes(2)),
        new BusinessOperationAdmissionSettings(
            "https://synthetic-permit-issuer.invalid",
            "synthetic-permit-key",
            "synthetic-org-admission",
            permitKeys.getPublic(),
            Duration.ofSeconds(60)));
  }

  private OktaOperationAuthority authority(
      BusinessOperationScope scope,
      OktaOperationReviewerRoster selectedRoster,
      BusinessOperationAuthorityProfile profile) {
    return new OktaOperationAuthority(
        identity.requesterVerifier,
        identity.reviewerVerifier,
        scope,
        new OktaOperationAuthority.RequesterEnrollment(
            ISSUER,
            ISSUER + "#" + REQUESTER_SUBJECT,
            REQUESTER_CLIENT,
            TENANT,
            WORKSPACE,
            scope.requesterPrincipalId()),
        selectedRoster,
        profile,
        REVIEWER_CLIENT,
        identity.clock);
  }

  private JsonNode evaluate(Composition composition) {
    return evaluate(composition, expected);
  }

  private JsonNode evaluate(Composition composition, GitHubOperationProfile target) {
    JsonNode initial = composition.registered().initial();
    return reviews(composition, target)
        .evaluate(
            new BusinessOperationEvaluation(
                SCOPE,
                id(initial),
                PLANNER.observe(SCOPE, initial),
                composition.authority().evaluator(identity.requesterToken("operations.evaluate"))));
  }

  private JsonNode approve(JsonNode pending, Composition composition) {
    return review(pending, composition, BusinessOperationReview.Decision.APPROVE);
  }

  private JsonNode approve(
      JsonNode pending, Composition composition, GitHubOperationProfile target) {
    return review(pending, composition, BusinessOperationReview.Decision.APPROVE, target);
  }

  private JsonNode review(
      JsonNode pending, Composition composition, BusinessOperationReview.Decision decision) {
    return review(pending, composition, decision, expected);
  }

  private JsonNode review(
      JsonNode pending,
      Composition composition,
      BusinessOperationReview.Decision decision,
      GitHubOperationProfile target) {
    OktaOperationAuthority.ReviewAuthority reviewer =
        composition
            .authority()
            .reviewer(
                identity.token(
                    REVIEWER_CLIENT,
                    REVIEWER_SUBJECT,
                    REVIEWER_GROUP,
                    Set.of("operations.decide"),
                    Duration.ofMinutes(5)));
    var command =
        new BusinessOperationReview(
            SCOPE,
            id(pending),
            PLANNER.observe(SCOPE, pending),
            reviewer.reviewer(),
            decision);
    return reviews(composition, target).review(command, reviewer.membership());
  }

  private BusinessOperationExecution execution(
      JsonNode snapshot, Composition composition, Duration tokenLifetime) {
    return new BusinessOperationExecution(
        SCOPE,
        id(snapshot),
        PLANNER.observe(SCOPE, snapshot),
        composition
            .authority()
            .requester(
                identity.token(
                    REQUESTER_CLIENT,
                    REQUESTER_SUBJECT,
                    REQUESTER_GROUP,
                    Set.of("operations.execute"),
                    tokenLifetime),
                "operations.execute"),
        WORKSPACE);
  }

  private JdbcGitHubOperationReviewRepository reviews(Composition composition) {
    return reviews(composition, expected);
  }

  private JdbcGitHubOperationReviewRepository reviews(
      Composition composition, GitHubOperationProfile target) {
    return new JdbcGitHubOperationReviewRepository(
        databases.operations, composition.profile(), target);
  }

  private JdbcGitHubOperationPermitIssuer issuer(Composition composition) {
    return new JdbcGitHubOperationPermitIssuer(
        databases.operations, composition.profile(), expected, permitKeys.getPrivate());
  }

  private JdbcGitHubOperationAdmissionRepository admission(Composition composition) {
    return new JdbcGitHubOperationAdmissionRepository(
        databases.operations, composition.profile(), expected);
  }

  private JdbcGitHubOperationDeliveryRepository delivery(
      Composition composition, SyntheticGitHubDeliveryTarget target) {
    return new JdbcGitHubOperationDeliveryRepository(
        databases.operations, composition.profile(), expected, target);
  }

  private JsonNode current(JsonNode operation) {
    return new JdbcBusinessOperationLifecycleRepository(databases.operations)
        .findCurrent(SCOPE, id(operation))
        .orElseThrow();
  }

  private GitHubOrganizationOperationBinding.ExpectedProfile organizationProfile(
      GitHubOrganizationTargetBinding target) {
    return new GitHubOrganizationOperationBinding.ExpectedProfile(
        GitHubOrganizationOperationBinding.ADAPTER_ID,
        "test",
        1,
        policyRelease(),
        artifacts(),
        target);
  }

  private GitHubBusinessOperationBinding.ExpectedProfile legacyProfile() {
    return new GitHubBusinessOperationBinding.ExpectedProfile(
        GitHubBusinessOperationBinding.ADAPTER_ID,
        "test",
        1,
        policyRelease(),
        artifacts(),
        new GitHubPilotTargetBinding(
            71,
            101,
            201,
            "tracepermit/agentcore-pr-pilot",
            "synthetic-change",
            "main",
            "a".repeat(40),
            "b".repeat(40)));
  }

  private GitHubBusinessOperationBinding.PolicyRelease policyRelease() {
    return new GitHubBusinessOperationBinding.PolicyRelease(
        "synthetic-github-draft-pr", 1, CanonicalJson.sha256(policy));
  }

  private static Map<String, String> artifacts() {
    return Map.of("policy_bundle", HASH, "adapter_release", HASH);
  }

  private static GitHubOrganizationTargetBinding organizationTarget(
      long app,
      long installation,
      long repositoryId,
      long owner,
      String repository,
      String head,
      String base) {
    return new GitHubOrganizationTargetBinding(
        app,
        installation,
        repositoryId,
        owner,
        repository,
        head,
        base,
        "a".repeat(40),
        "b".repeat(40),
        GitHubOrganizationTargetBinding.requiredPermissions());
  }

  private static OktaOperationReviewerRoster reviewerRoster(String subject) {
    return new OktaOperationReviewerRoster(List.of(selfRosterEntry(subject)));
  }

  private static OktaOperationReviewerRoster.Entry selfRosterEntry(String subject) {
    return new OktaOperationReviewerRoster.Entry(
        ISSUER + "#" + subject,
        REVIEWER_CLIENT,
        TENANT,
        WORKSPACE,
        REVIEWER_GROUP,
        true);
  }

  private void lockHead(Connection connection, String operationId) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT operation_id FROM tp_operation_lifecycle_heads "
                + "WHERE operation_id = ? FOR UPDATE")) {
      statement.setQueryTimeout(5);
      statement.setString(1, operationId);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(operationId);
        assertThat(rows.next()).isFalse();
      }
    }
  }

  private static void awaitAfter(Instant deadline) throws InterruptedException {
    long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!Instant.now().isAfter(deadline.plusMillis(150))) {
      assertThat(System.nanoTime()).isLessThan(bound);
      Thread.sleep(10);
    }
  }

  private static void assertFutureCode(
      Future<?> future, BusinessOperationStorageException.Code code) {
    assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
        .isInstanceOfSatisfying(
            ExecutionException.class,
            wrapper ->
                assertThat(wrapper.getCause())
                    .isInstanceOfSatisfying(
                        BusinessOperationStorageException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code)));
  }

  private long count(String table) {
    return OrgSandboxDatabases.count(databases.operationAdmin, table);
  }

  private long countReplay(String table) {
    return OrgSandboxDatabases.count(databases.replayAdmin, table);
  }

  private static String id(JsonNode operation) {
    return operation.path("operationId").asText();
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

  private static void assertAuthorityRejected(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOf(OktaOperationAuthorityException.class)
        .hasMessage("OKTA_AUTHORITY_REJECTED")
        .hasNoCause();
  }

  private record Registered(String operationId, JsonNode initial) {}

  private record Composition(
      Registered registered,
      BusinessOperationAuthorityProfile profile,
      OktaOperationAuthority authority) {}

  private record ContenderResult(
      boolean committed, BusinessOperationStorageException.Code code) {}

  private static final class IdentityFixture {
    private static final String REQUESTER_KEY_ID = "synthetic-requester-key";
    private static final String REVIEWER_KEY_ID = "synthetic-reviewer-key";
    private final Clock clock = Clock.systemUTC();
    private final KeyPair requesterKey = rsa();
    private final KeyPair reviewerKey = rsa();
    private final OidcAccessTokenVerifier requesterVerifier =
        verifier(
            REQUESTER_CLIENT,
            REQUESTER_KEY_ID,
            requesterKey,
            Set.of("operations.register", "operations.evaluate", "operations.execute"));
    private final OidcAccessTokenVerifier reviewerVerifier =
        verifier(
            REVIEWER_CLIENT,
            REVIEWER_KEY_ID,
            reviewerKey,
            Set.of("operations.decide"));

    String requesterToken(String scope) {
      return token(
          REQUESTER_CLIENT,
          REQUESTER_SUBJECT,
          REQUESTER_GROUP,
          Set.of(scope),
          Duration.ofMinutes(5));
    }

    String token(
        String client,
        String subject,
        String group,
        Set<String> scopes,
        Duration lifetime) {
      try {
        Instant now = clock.instant();
        KeyPair key = REQUESTER_CLIENT.equals(client) ? requesterKey : reviewerKey;
        String keyId =
            REQUESTER_CLIENT.equals(client) ? REQUESTER_KEY_ID : REVIEWER_KEY_ID;
        ObjectNode header =
            JsonSupport.MAPPER
                .createObjectNode()
                .put("alg", "RS256")
                .put("typ", "JWT")
                .put("kid", keyId);
        ObjectNode claims =
            JsonSupport.MAPPER
                .createObjectNode()
                .put("iss", ISSUER)
                .put("aud", AUDIENCE)
                .put("sub", subject)
                .put("cid", client)
                .put("tracepermit_tenant", EXTERNAL_TENANT)
                .put("iat", now.minusSeconds(1).getEpochSecond())
                .put("exp", now.plus(lifetime).getEpochSecond())
                .put("jti", UUID.randomUUID().toString());
        scopes.stream().sorted().forEach(value -> claims.withArray("scp").add(value));
        claims.putArray("groups").add(group);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String body =
            encoder.encodeToString(JsonSupport.MAPPER.writeValueAsBytes(header))
                + "."
                + encoder.encodeToString(JsonSupport.MAPPER.writeValueAsBytes(claims));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(body.getBytes(StandardCharsets.US_ASCII));
        return "Bearer " + body + "." + encoder.encodeToString(signature.sign());
      } catch (Exception failure) {
        throw new AssertionError("ORG_SANDBOX_TOKEN_SIGNING_FAILED", failure);
      }
    }

    private OidcAccessTokenVerifier verifier(
        String client, String keyId, KeyPair key, Set<String> scopes) {
      OidcProviderConfiguration provider = provider(client, scopes);
      return new OidcAccessTokenVerifier(
          issuer -> ISSUER.equals(issuer) ? Optional.of(provider) : Optional.empty(),
          (configured, observedKeyId, algorithm, observed) -> {
            if (configured != provider
                || !keyId.equals(observedKeyId)
                || !"RS256".equals(algorithm)) {
              throw new IllegalArgumentException("ORG_SANDBOX_UNTRUSTED_KEY");
            }
            return (RSAPublicKey) key.getPublic();
          },
          clock);
    }

    private static OidcProviderConfiguration provider(String client, Set<String> scopes) {
      return new OidcProviderConfiguration(
          URI.create(ISSUER),
          AUDIENCE,
          URI.create(ISSUER + "/v1/keys"),
          URI.create(AUDIENCE + "/.well-known/oauth-protected-resource"),
          List.of(URI.create(ISSUER)),
          "Synthetic organization sandbox",
          Map.of(client, "Synthetic client"),
          "tracepermit_tenant",
          Map.of(EXTERNAL_TENANT, TENANT),
          scopes.stream()
              .map(ConnectorScope::of)
              .collect(java.util.stream.Collectors.toUnmodifiableSet()),
          ConnectorOrigin.CLI,
          Duration.ofMinutes(15),
          Duration.ofMinutes(5),
          Duration.ofSeconds(30),
          OidcTokenProfile.okta(
              "groups",
              Map.of(
                  REQUESTER_GROUP, "REQUESTER",
                  REVIEWER_GROUP, "REVIEWER")));
    }

    private static KeyPair rsa() {
      try {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
      } catch (Exception failure) {
        throw new ExceptionInInitializerError(failure);
      }
    }
  }

  private static final class OrgSandboxDatabases implements AutoCloseable {
    private static final String USER = "tp_org_sandbox_runtime";
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
    final ObservedJdbcSource operations;

    OrgSandboxDatabases(String image) {
      postgres = image.startsWith("postgres:");
      int port = postgres ? 5432 : 3306;
      container =
          postgres ? new PostgreSQLContainer<>(image) : new MariaDBContainer<>(image);
      String ownerSecret = UUID.randomUUID().toString();
      container
          .withPassword(ownerSecret)
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
                prefix + container.getDatabaseName() + bounds, owner, ownerSecret);
        execute(bootstrap, "CREATE DATABASE tp_org_replay");
        execute(bootstrap, "CREATE DATABASE tp_org_operations");
        replayAdmin =
            new BoundedJdbcSource(prefix + "tp_org_replay" + bounds, owner, ownerSecret);
        operationAdmin =
            new BoundedJdbcSource(prefix + "tp_org_operations" + bounds, owner, ownerSecret);
        WorkloadIdentityReplayDatabase.initializeEmpty(replayAdmin, 64);
        OperationDatabase.initializeDeliveryEmpty(operationAdmin);

        String runtimeSecret = UUID.randomUUID().toString();
        execute(
            bootstrap,
            postgres
                ? "CREATE ROLE " + USER + " LOGIN PASSWORD '" + runtimeSecret + "'"
                : "CREATE USER '" + USER + "'@'%' IDENTIFIED BY '" + runtimeSecret + "'");
        replay = new BoundedJdbcSource(replayAdmin.url, USER, runtimeSecret);
        operations =
            new ObservedJdbcSource(
                new BoundedJdbcSource(operationAdmin.url, USER, runtimeSecret), postgres);
        if (postgres) {
          execute(replayAdmin, "GRANT USAGE ON SCHEMA public TO " + USER);
          execute(operationAdmin, "GRANT USAGE ON SCHEMA public TO " + USER);
        }
        for (String table :
            List.of(
                "tp_identity_replay_schema_history",
                "tp_workload_replay_claims",
                "tp_workload_replay_state")) {
          grant(replayAdmin, "tp_org_replay", "SELECT", table);
        }
        grant(replayAdmin, "tp_org_replay", "INSERT", "tp_workload_replay_claims");
        grant(replayAdmin, "tp_org_replay", "UPDATE", "tp_workload_replay_state");
        for (String table : OPERATION_TABLES) {
          grant(operationAdmin, "tp_org_operations", "SELECT", table);
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
          grant(operationAdmin, "tp_org_operations", "INSERT", table);
        }
        execute(
            operationAdmin,
            "GRANT UPDATE(snapshot_hash, snapshot_json) ON "
                + table("tp_org_operations", "tp_operation_lifecycle_heads")
                + " TO "
                + principal());
        if (postgres) {
          execute(
              operationAdmin,
              "GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO " + USER);
        }
      } catch (RuntimeException | Error failure) {
        container.stop();
        throw new AssertionError("ORG_SANDBOX_DATABASE_SETUP_FAILED", failure);
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

    void assertRestrictedRuntime(
        String operationId, BusinessOperationAuthorityProfile authority) throws SQLException {
      for (String sql :
          List.of(
              "UPDATE tp_operation_delivery_events SET event_hash = event_hash "
                  + "WHERE operation_id = ?",
              "DELETE FROM tp_operation_delivery_events WHERE operation_id = ?")) {
        try (var connection = operations.getConnection();
            var statement = connection.prepareStatement(sql)) {
          statement.setQueryTimeout(5);
          statement.setString(1, operationId);
          assertDenied(statement::executeUpdate);
        }
      }
      try (var connection = operations.getConnection();
          var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        assertDenied(
            () -> statement.execute("CREATE TABLE tp_org_sandbox_forbidden (id INTEGER)"));
      }
      try (var connection = operations.getConnection();
          var statement =
              connection.prepareStatement(
                  "UPDATE tp_operation_authority_controls SET control_state = control_state "
                      + "WHERE scope_hash = ?")) {
        statement.setQueryTimeout(5);
        statement.setString(1, authority.scope().fingerprint());
        assertDenied(statement::executeUpdate);
      }
    }

    void awaitRuntimeLockWait(int pid, Instant authorityExpiry)
        throws SQLException, InterruptedException {
      String sql =
          postgres
              ? "SELECT COUNT(*) FROM pg_stat_activity "
                  + "WHERE pid = ? AND wait_event_type = 'Lock'"
              : "SELECT COUNT(*) FROM information_schema.INNODB_TRX "
                  + "WHERE trx_mysql_thread_id = ? AND trx_state = 'LOCK WAIT'";
      long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      try (var connection = operationAdmin.getConnection();
          var statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(2);
        statement.setInt(1, pid);
        while (true) {
          try (var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            if (rows.getInt(1) == 1) break;
          }
          assertThat(System.nanoTime()).isLessThan(bound);
          Thread.sleep(10);
        }
      }
      assertThat(Instant.now()).isBefore(authorityExpiry);
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
        throw new AssertionError("ORG_SANDBOX_ADMIN_ACTION_FAILED", failure);
      }
    }

    static long count(BoundedJdbcSource source, String table) {
      try (var connection = source.getConnection();
          var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          if (!rows.next()) throw new AssertionError("ORG_SANDBOX_COUNT_MISSING");
          return rows.getLong(1);
        }
      } catch (SQLException failure) {
        throw new AssertionError("ORG_SANDBOX_COUNT_FAILED", failure);
      }
    }

    @Override
    public void close() {
      container.stop();
    }
  }

  /** Test-only owner probe: capture only the numeric backend id for the exact head-lock attempt. */
  private static final class ObservedJdbcSource implements DataSource, AutoCloseable {
    private final BoundedJdbcSource delegate;
    private final boolean postgres;
    private final AtomicInteger headLockPid = new AtomicInteger(-1);
    private volatile CountDownLatch headLockAttempt;

    ObservedJdbcSource(BoundedJdbcSource delegate, boolean postgres) {
      this.delegate = delegate;
      this.postgres = postgres;
    }

    void reset() {
      delegate.reset();
      headLockPid.set(-1);
      headLockAttempt = null;
    }

    void armHeadLockProbe() {
      headLockPid.set(-1);
      headLockAttempt = new CountDownLatch(1);
    }

    int awaitHeadLockAttempt() throws InterruptedException {
      CountDownLatch attempt = headLockAttempt;
      if (attempt == null || !attempt.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError("ORG_SANDBOX_HEAD_LOCK_ATTEMPT_TIMEOUT");
      }
      int pid = headLockPid.get();
      if (pid <= 0) throw new AssertionError("ORG_SANDBOX_HEAD_LOCK_PID_MISSING");
      return pid;
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection connection = delegate.getConnection();
      int pid;
      try (var statement = connection.createStatement()) {
        statement.setQueryTimeout(2);
        try (var rows =
            statement.executeQuery(
                postgres ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()")) {
          if (!rows.next()) throw new SQLException("ORG_SANDBOX_BACKEND_ID_MISSING");
          pid = rows.getInt(1);
          if (pid <= 0 || rows.next()) {
            throw new SQLException("ORG_SANDBOX_BACKEND_ID_INVALID");
          }
        }
      } catch (SQLException failure) {
        connection.close();
        throw failure;
      }
      int ownedPid = pid;
      return (Connection)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                if (method.getName().equals("prepareStatement")
                    && arguments != null
                    && arguments.length > 0
                    && arguments[0] instanceof String sql) {
                  String normalized = sql.toLowerCase(Locale.ROOT);
                  CountDownLatch attempt = headLockAttempt;
                  if (attempt != null
                      && normalized.contains("tp_operation_lifecycle_heads")
                      && normalized.contains("for update")) {
                    headLockPid.set(ownedPid);
                    attempt.countDown();
                  }
                }
                return invoke(method, connection, arguments);
              });
    }

    private static Object invoke(Method method, Object target, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException wrapped) {
        throw wrapped.getCause();
      }
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PrintWriter getLogWriter() {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter writer) {
      delegate.setLogWriter(writer);
    }

    @Override
    public void setLoginTimeout(int seconds) {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
      return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return false;
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
