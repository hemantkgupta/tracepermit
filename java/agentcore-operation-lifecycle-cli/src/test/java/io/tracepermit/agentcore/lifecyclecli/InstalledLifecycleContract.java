package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.agentcore.registration.DurableAgentCoreRegistration;
import io.tracepermit.agentcore.cli.InstalledOperationsRuntime;
import io.tracepermit.agentcore.cli.InstalledPrivateInput;
import io.tracepermit.application.BusinessOperationAdmissionSettings;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationReviewSettings;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.persistence.operations.JdbcBusinessOperationRepository;
import io.tracepermit.persistence.operations.JdbcGitHubOperationInspector;
import io.tracepermit.persistence.operations.OperationAuthorityAdministration;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/** Actual extracted archive, process restart, real OIDC and exact V7 stores on one pinned engine. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(240)
abstract class InstalledLifecycleContract {
  private static final String HASH = "sha256:" + "a".repeat(64);
  private static final String GROUP = "synthetic-reviewers";
  private static final ObjectNode POLICY = policy();
  private static final BusinessOperationScope SCOPE =
      new BusinessOperationScope(
          LifecycleTestIdentity.TENANT,
          LifecycleTestIdentity.WORKSPACE,
          LifecycleTestIdentity.principal(
              LifecycleTestIdentity.REQUESTER_ISSUER
                  + "#"
                  + LifecycleTestIdentity.REQUESTER_SUBJECT));

  private LifecycleTestDatabase database;
  private InstalledLifecycleArchive installed;
  private LifecycleTestIdentity identity;
  private KeyPair permitKeys;
  private GitHubBusinessOperationBinding.ExpectedProfile expected;
  private BusinessOperationAuthorityProfile authority;

  abstract String image();

  boolean directWorker() {
    return false;
  }

  @BeforeAll
  void start() throws Exception {
    installed = new InstalledLifecycleArchive();
    database = new LifecycleTestDatabase(image());
  }

  @AfterAll
  void stop() throws Exception {
    if (database != null) database.close();
    if (installed != null) installed.close();
  }

  @BeforeEach
  void reset() throws Exception {
    database.reset();
    identity = new LifecycleTestIdentity();
    permitKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    expected = expected();
    authority = null;
  }

  @Test
  void separateInstalledProcessesCompleteAndReconcileTheLifecycle() throws Exception {
    JsonNode initial = register("installed-restart");
    activate(initial);
    ObjectNode config = config(initial);
    String operationId = initial.path("operationId").asText();

    LifecycleReceipt inspected =
        call("inspect", config, identity.requesterToken(), command(operationId), null);
    assertState(inspected, "OBSERVED", "evaluating", "not_started", false);

    LifecycleReceipt evaluated =
        call("evaluate", config, identity.requesterToken(), command(operationId), null);
    assertState(evaluated, "EVALUATED", "awaiting_approval", "not_started", false);

    ObjectNode review = command(operationId).put("decision", "APPROVE");
    LifecycleReceipt approved =
        call("review", config, identity.reviewerToken(), review, null);
    assertState(approved, "REVIEW_APPROVED", "approved", "not_started", false);

    ObjectNode deliver = command(operationId).put("syntheticOutcome", "CONFIRMED_SUCCESS");
    LifecycleReceipt delivered =
        call(
            "deliver-synthetic",
            config,
            identity.requesterToken(),
            deliver,
            permitKeys.getPrivate().getEncoded());
    assertState(delivered, "DELIVERY_SUCCEEDED", "approved", "succeeded", true);

    LifecycleReceipt reconciled =
        call("reconcile", config, identity.requesterToken(), command(operationId), null);
    assertState(reconciled, "RECONCILED", "approved", "succeeded", true);
    assertThat(database.count("tp_operation_review_bindings")).isOne();
    assertThat(database.count("tp_operation_admissions")).isOne();
    assertThat(database.count("tp_operation_admission_outbox")).isOne();
    assertThat(database.count("tp_operation_delivery_events")).isEqualTo(3);
    assertThat(database.count("tp_operation_lifecycle_facts")).isEqualTo(5);
  }

  @Test
  void realIdentityFramingAndPrivateKeyNegativesPrecedeEffects() throws Exception {
    JsonNode initial = register("installed-negatives");
    activate(initial);
    ObjectNode config = config(initial);
    String operationId = initial.path("operationId").asText();

    LifecycleReceipt offline = execute("check-config", frames(bytes(config)));
    assertThat(offline.outcome()).isEqualTo("CONFIG_VALID");
    assertThat(database.count("tp_operation_lifecycle_facts")).isZero();

    ObjectNode extra = command(operationId).put("authenticated", true);
    LifecycleReceipt malformed =
        call("evaluate", config, identity.requesterToken(), extra, null);
    assertThat(malformed.outcome()).isEqualTo("INPUT_INVALID");
    assertThat(database.count("tp_operation_lifecycle_facts")).isZero();

    String invalid = corrupt(identity.requesterToken());
    LifecycleReceipt rejected = call("evaluate", config, invalid, command(operationId), null);
    assertThat(rejected.outcome()).isEqualTo("REJECTED");
    assertThat(database.count("tp_operation_lifecycle_facts")).isZero();

    approve(config, operationId);
    KeyPair wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    ObjectNode delivery = command(operationId).put("syntheticOutcome", "CONFIRMED_SUCCESS");
    LifecycleReceipt wrongKey =
        call(
            "deliver-synthetic",
            config,
            identity.requesterToken(),
            delivery,
            wrong.getPrivate().getEncoded());
    assertThat(wrongKey.outcome()).isEqualTo("REJECTED");
    assertThat(database.count("tp_operation_admissions")).isZero();
    assertThat(database.count("tp_operation_delivery_events")).isZero();

    byte[] trailing =
        concat(
            lifecycleInput(
                config,
                identity.requesterToken(),
                delivery,
                permitKeys.getPrivate().getEncoded()),
            new byte[] {1});
    LifecycleReceipt eof = execute("deliver-synthetic", trailing);
    assertThat(eof.outcome()).isEqualTo("INPUT_INVALID");
    assertThat(database.count("tp_operation_admissions")).isZero();

    LifecycleReceipt valid =
        call(
            "deliver-synthetic",
            config,
            identity.requesterToken(),
            delivery,
            permitKeys.getPrivate().getEncoded());
    assertThat(valid.outcome()).isEqualTo("DELIVERY_SUCCEEDED");
  }

  @Test
  void syntheticRejectedAndUnknownOutcomesStayDurableAndNeverRetry() throws Exception {
    for (String selected : List.of("CONFIRMED_REJECTION", "UNKNOWN")) {
      JsonNode initial = register("outcome-" + selected.toLowerCase());
      activate(initial);
      ObjectNode config = config(initial);
      String operationId = initial.path("operationId").asText();
      approve(config, operationId);

      ObjectNode command = command(operationId).put("syntheticOutcome", selected);
      LifecycleReceipt delivery =
          call(
              "deliver-synthetic",
              config,
              identity.requesterToken(),
              command,
              permitKeys.getPrivate().getEncoded());
      assertThat(delivery.outcome())
          .isEqualTo(selected.equals("UNKNOWN") ? "DELIVERY_UNKNOWN" : "DELIVERY_REJECTED");
      assertThat(delivery.executionStatus())
          .isEqualTo(selected.equals("UNKNOWN") ? "unknown" : "failed");
      assertThat(delivery.dispatchStarted()).isTrue();

      LifecycleReceipt repeat =
          call(
              "deliver-synthetic",
              config,
              identity.requesterToken(),
              command,
              permitKeys.getPrivate().getEncoded());
      assertThat(repeat.outcome()).isEqualTo("REJECTED");
      LifecycleReceipt reconciled =
          call("reconcile", config, identity.requesterToken(), command(operationId), null);
      assertThat(reconciled.executionStatus()).isEqualTo(delivery.executionStatus());
      assertThat(reconciled.dispatchStarted()).isTrue();
    }
    assertThat(database.count("tp_operation_delivery_events")).isEqualTo(6);
  }

  @Test
  void installedAdmissionContentionNeverDispatchesMoreThanOnceOrRetries() throws Exception {
    JsonNode initial = register("installed-contention");
    activate(initial);
    ObjectNode config = config(initial);
    String operationId = initial.path("operationId").asText();
    approve(config, operationId);
    ObjectNode delivery = command(operationId).put("syntheticOutcome", "CONFIRMED_SUCCESS");

    int contenders = 8;
    var pool = Executors.newFixedThreadPool(contenders);
    var ready = new CountDownLatch(contenders);
    var release = new CountDownLatch(1);
    var futures = new ArrayList<java.util.concurrent.Future<LifecycleReceipt>>();
    try {
      for (int index = 0; index < contenders; index++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("CONTENTION_NOT_RELEASED");
                  }
                  return call(
                      "deliver-synthetic",
                      config,
                      identity.requesterToken(),
                      delivery,
                      permitKeys.getPrivate().getEncoded());
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      var receipts = new ArrayList<LifecycleReceipt>();
      for (var future : futures) receipts.add(future.get(90, TimeUnit.SECONDS));
      long successes =
          receipts.stream().filter(r -> r.outcome().equals("DELIVERY_SUCCEEDED")).count();
      assertThat(successes).isBetween(0L, 1L);
      assertThat(receipts.stream().filter(r -> r.exitCode() != 0))
          .hasSize(contenders - (int) successes);
      assertThat(receipts)
          .allMatch(
              receipt ->
                  receipt.outcome().equals("DELIVERY_SUCCEEDED")
                      || receipt.outcome().equals("REJECTED")
                      || receipt.outcome().equals("UNKNOWN"));
      assertThat(receipts.stream().filter(receipt -> receipt.exitCode() != 0))
          .allSatisfy(
              receipt -> {
                assertThat(receipt.outcome()).isIn("REJECTED", "UNKNOWN");
                assertThat(receipt.operationId()).isNull();
                assertThat(receipt.dispatchStarted()).isNull();
              });
      assertThat(database.count("tp_operation_admissions")).isOne();
      assertThat(database.count("tp_operation_delivery_events")).isEqualTo(successes * 3);
      LifecycleReceipt reconciled =
          call("reconcile", config, identity.requesterToken(), command(operationId), null);
      assertThat(reconciled.dispatchStarted()).isEqualTo(successes == 1);
      assertThat(reconciled.executionStatus())
          .isEqualTo(successes == 1 ? "succeeded" : "claimed");
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  final void verifyMalformedOperatorConfigurationIsRejectedBeforeLifecycleEffects()
      throws Exception {
    JsonNode initial = register("configuration-boundaries");
    activate(initial);
    ObjectNode valid = config(initial);

    List<Consumer<ObjectNode>> mutations =
        List.of(
            root -> root.put("schemaVersion", "2.0"),
            root -> root.put("unexpected", true),
            root -> object(root, "operations").remove("port"),
            root -> object(root, "scope").remove("tenantId"),
            root -> object(root, "expectedTarget").put("recoveryGeneration", 0),
            root -> object(root, "expectedTarget").put("policyContentHash", "sha256:bad"),
            root -> object(root, "expectedTarget").put("artifactDigests", "not-an-object"),
            root -> object(root, "expectedTarget").putObject("artifactDigests"),
            root ->
                object(object(root, "expectedTarget"), "artifactDigests")
                    .put("invalid key!", HASH),
            root ->
                object(object(root, "expectedTarget"), "artifactDigests")
                    .put("policy_bundle", "sha256:bad"),
            root -> object(root, "authority").put("policy", "not-an-object"),
            root -> object(root, "authority").put("artifactDigestsHash", "sha256:bad"),
            root -> object(root, "authority").put("permitPublicKeySpki", "%"),
            root ->
                object(root, "authority")
                    .put(
                        "permitPublicKeySpki",
                        Base64.getEncoder().encodeToString(new byte[] {1})),
            root -> object(root, "authority").put("permitLifetimeSeconds", 0),
            root -> object(root, "requesterProvider").put("algorithm", "none"),
            root -> object(root, "requesterProvider").put("publicKeySpki", "%"),
            root ->
                object(root, "requesterProvider")
                    .put(
                        "publicKeySpki",
                        Base64.getEncoder().encodeToString(new byte[] {1})),
            root -> object(root, "requesterProvider").put("keyId", "invalid key"),
            root -> object(root, "requesterProvider").put("supportedScopes", "not-an-array"),
            root ->
                object(root, "requesterProvider")
                    .putArray("supportedScopes")
                    .add("operations.evaluate")
                    .add("operations.evaluate"),
            root -> object(root, "requesterEnrollment").put("workspaceId", "wrong"),
            root -> root.put("reviewerMemberships", "not-an-array"),
            root -> root.putArray("reviewerMemberships"),
            root ->
                ((ObjectNode)
                        ((com.fasterxml.jackson.databind.node.ArrayNode)
                                root.get("reviewerMemberships"))
                            .get(0))
                    .put("groupId", ""),
            root -> {
              var memberships =
                  (com.fasterxml.jackson.databind.node.ArrayNode) root.get("reviewerMemberships");
              memberships.add(memberships.get(0).deepCopy());
            },
            root ->
                ((ObjectNode)
                        ((com.fasterxml.jackson.databind.node.ArrayNode)
                                root.get("reviewerMemberships"))
                            .get(0))
                    .put("groupId", "different-reviewers"),
            root ->
                ((ObjectNode)
                        ((com.fasterxml.jackson.databind.node.ArrayNode)
                                root.get("reviewerMemberships"))
                            .get(0))
                    .put("human", false));

    for (Consumer<ObjectNode> mutation : mutations) {
      ObjectNode invalid = valid.deepCopy();
      mutation.accept(invalid);
      LifecycleReceipt receipt = execute("check-config", frames(bytes(invalid)));
      assertThat(receipt.outcome()).isEqualTo("CONFIG_INVALID");
    }
    assertThat(database.count("tp_operation_lifecycle_facts")).isZero();

    LifecycleOperatorConfig parsed = LifecycleOperatorConfig.parse(valid);
    assertThat(parsed.toString()).isEqualTo("LifecycleOperatorConfig[redacted]");
    assertThat(parsed.requesterProvider().toString()).isEqualTo("LifecycleProvider[redacted]");
    assertThat(parsed.memberships().toString()).isEqualTo("LifecycleMemberships[redacted]");
  }

  final void verifyClosedProtocolFacadesAndInspector() throws Exception {
    JsonNode initial = register("focused-boundary-coverage");
    activate(initial);
    ObjectNode valid = config(initial);
    String operationId = initial.path("operationId").asText();

    byte[] encodedKey = permitKeys.getPrivate().getEncoded();
    String requesterToken = identity.requesterToken();
    ObjectNode delivery = command(operationId).put("syntheticOutcome", "CONFIRMED_SUCCESS");
    try (LifecycleProtocol.Input input =
        LifecycleProtocol.read(
            new ByteArrayInputStream(
                lifecycleInput(valid, requesterToken, delivery, encodedKey)),
            "deliver-synthetic")) {
      assertThat(input.configuration()).isNotNull();
      assertThat(input.bearer()).isEqualTo(requesterToken);
      assertThat(input.privateKey()).isEqualTo(encodedKey).isNotSameAs(encodedKey);
      assertThat(input.command().operationId()).isEqualTo(operationId);
      assertThat(input.command().toString()).isEqualTo("LifecycleCommand[redacted]");
      assertThat(input.toString()).isEqualTo("LifecycleInput[redacted]");
    }

    assertThat(execute("unsupported", new byte[0]).outcome()).isEqualTo("USAGE");
    assertThat(call("inspect", valid, "not-a-jwt", command(operationId), null).outcome())
        .isEqualTo("INPUT_INVALID");
    assertThat(
            call(
                    "inspect",
                    valid,
                    identity.requesterToken(),
                    command(operationId).put("profile", "wrong"),
                    null)
                .outcome())
        .isEqualTo("INPUT_INVALID");
    assertThat(
            call(
                    "inspect",
                    valid,
                    identity.requesterToken(),
                    command("NOT-A-UUID"),
                    null)
                .outcome())
        .isEqualTo("INPUT_INVALID");
    assertThat(
            call(
                    "inspect",
                    valid,
                    identity.requesterToken(),
                    command("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
                    null)
                .outcome())
        .isEqualTo("INPUT_INVALID");
    assertThat(
            call(
                    "review",
                    valid,
                    identity.reviewerToken(),
                    command(operationId).put("decision", "MAYBE"),
                    null)
                .outcome())
        .isEqualTo("INPUT_INVALID");
    assertThat(
            call(
                    "deliver-synthetic",
                    valid,
                    identity.requesterToken(),
                    command(operationId).put("syntheticOutcome", "MAYBE"),
                    encodedKey)
                .outcome())
        .isEqualTo("INPUT_INVALID");

    InstalledOperationsRuntime.Plan plan =
        InstalledOperationsRuntime.parse(valid.path("operations"));
    assertThat(plan.source()).isNotNull();
    assertThat(plan.toString()).isEqualTo("InstalledOperationsPlan[redacted]");
    assertThatThrownBy(
            () -> InstalledOperationsRuntime.parse(JsonSupport.MAPPER.createObjectNode()))
        .isInstanceOfSatisfying(
            InstalledOperationsRuntime.Failure.class,
            failure -> assertThat(failure.initCause(new RuntimeException("private"))).isSameAs(failure));
    assertThat(InstalledPrivateInput.anonymousPipe()).isIn(true, false);

    var inspector = new JdbcGitHubOperationInspector(database.operations, authority);
    JdbcGitHubOperationInspector.Inspection inspection = inspector.inspect(SCOPE, operationId);
    JsonNode first = inspection.operation();
    ((ObjectNode) first).put("title", "mutated copy");
    assertThat(inspection.operation().path("title").asText()).isNotEqualTo("mutated copy");
    assertThat(inspection.dispatchStarted()).isFalse();
    assertThatThrownBy(() -> new JdbcGitHubOperationInspector(null, authority))
        .isInstanceOf(BusinessOperationStorageException.class);
    assertThatThrownBy(() -> new JdbcGitHubOperationInspector(database.operations, null))
        .isInstanceOf(BusinessOperationStorageException.class);
    var wrongScope =
        new BusinessOperationScope(SCOPE.tenantId(), "wrong", SCOPE.requesterPrincipalId());
    assertThatThrownBy(() -> inspector.inspect(wrongScope, operationId))
        .isInstanceOf(BusinessOperationStorageException.class);
    assertThatThrownBy(() -> inspector.inspect(SCOPE, "not-a-uuid"))
        .isInstanceOf(BusinessOperationStorageException.class);
    assertThatThrownBy(() -> inspector.inspect(SCOPE, UUID.randomUUID().toString()))
        .isInstanceOf(BusinessOperationStorageException.class);

    LifecycleFailure fixed = new LifecycleFailure("INPUT_INVALID");
    assertThat(fixed.initCause(new RuntimeException("private"))).isSameAs(fixed);
  }

  private void approve(ObjectNode config, String operationId) throws Exception {
    assertThat(
            call("evaluate", config, identity.requesterToken(), command(operationId), null)
                .outcome())
        .isEqualTo("EVALUATED");
    assertThat(
            call(
                    "review",
                    config,
                    identity.reviewerToken(),
                    command(operationId).put("decision", "APPROVE"),
                    null)
                .outcome())
        .isEqualTo("REVIEW_APPROVED");
  }

  private JsonNode register(String key) {
    var enrollment =
        new AgentCoreRegistrationIngress.Enrollment(
            LifecycleTestIdentity.REGISTRATION_ISSUER,
            LifecycleTestIdentity.REGISTRATION_ISSUER
                + "#"
                + LifecycleTestIdentity.REGISTRATION_SUBJECT,
            LifecycleTestIdentity.REGISTRATION_CLIENT,
            SCOPE,
            expected,
            "synthetic-retention",
            Duration.ofDays(30));
    var registration =
        DurableAgentCoreRegistration.create(
            identity.registrationVerifier(),
            enrollment,
            Clock.systemUTC(),
            database.replay,
            database.operations,
            64,
            Duration.ofSeconds(2));
    ObjectNode request =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("schemaVersion", "1.0")
            .put("clientRequestKey", key)
            .put("title", "Synthetic installed lifecycle " + key);
    var receipt =
        registration.register(
            identity.registrationToken(),
            null,
            request.toString().getBytes(StandardCharsets.UTF_8));
    assertThat(receipt.created()).isTrue();
    return new JdbcBusinessOperationRepository(database.operations)
        .find(SCOPE, receipt.operationId())
        .orElseThrow();
  }

  private void activate(JsonNode initial) {
    if (authority != null) return;
    String membershipFingerprint = LifecycleMemberships.parse(roster()).fingerprint();
    authority =
        new BusinessOperationAuthorityProfile(
            SCOPE,
            expected.adapterId(),
            1,
            POLICY,
            CanonicalJson.sha256(initial.path("artifactDigests")),
            initial.path("resourceBindingHash").asText(),
            new BusinessOperationReviewSettings(
                GROUP, membershipFingerprint, Duration.ofMinutes(2)),
            new BusinessOperationAdmissionSettings(
                "https://synthetic-permit-issuer.invalid",
                "synthetic-permit-key",
                "synthetic-operation-admission",
                permitKeys.getPublic(),
                Duration.ofSeconds(60)));
    OperationAuthorityAdministration.stageNew(database.operationAdmin, authority);
    OperationAuthorityAdministration.activateStaged(database.operationAdmin, authority);
  }

  private ObjectNode config(JsonNode initial) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "1.0").put("profile", LifecycleProtocol.PROFILE);
    root.set("operations", database.operationConfig());
    root.putObject("scope")
        .put("tenantId", SCOPE.tenantId())
        .put("workspaceId", SCOPE.workspaceId())
        .put("requesterPrincipalId", SCOPE.requesterPrincipalId());
    ObjectNode target = root.putObject("expectedTarget");
    target
        .put("adapterId", expected.adapterId())
        .put("environment", expected.environment())
        .put("recoveryGeneration", expected.recoveryGeneration())
        .put("policyId", expected.policyRelease().policyId())
        .put("policyVersion", expected.policyRelease().version())
        .put("policyContentHash", expected.policyRelease().contentHash());
    expected.artifactDigests().forEach(target.putObject("artifactDigests")::put);
    GitHubPilotTargetBinding binding = expected.targetBinding();
    target
        .put("installationId", binding.installationId())
        .put("repositoryId", binding.repositoryId())
        .put("ownerId", binding.ownerId())
        .put("repository", binding.repository())
        .put("head", binding.head())
        .put("base", binding.base())
        .put("headSha", binding.headSha())
        .put("baseSha", binding.baseSha());
    ObjectNode authorityJson = root.putObject("authority");
    authorityJson
        .put("adapterId", expected.adapterId())
        .put("recoveryGeneration", 1)
        .set("policy", POLICY.deepCopy());
    authorityJson
        .put("artifactDigestsHash", CanonicalJson.sha256(initial.path("artifactDigests")))
        .put("resourceBindingHash", initial.path("resourceBindingHash").asText())
        .put("reviewerGroupId", GROUP)
        .put("membershipSourceFingerprint", LifecycleMemberships.parse(roster()).fingerprint())
        .put("approvalLifetimeSeconds", 120)
        .put("permitIssuer", "https://synthetic-permit-issuer.invalid")
        .put("permitKeyId", "synthetic-permit-key")
        .put("permitAudience", "synthetic-operation-admission")
        .put(
            "permitPublicKeySpki",
            Base64.getEncoder().encodeToString(permitKeys.getPublic().getEncoded()))
        .put("permitLifetimeSeconds", 60);
    root.set("requesterProvider", identity.requesterProvider());
    root.set("reviewerProvider", identity.reviewerProvider());
    root.putObject("requesterEnrollment")
        .put("verifiedSubjectId", identity.requesterVerifiedSubject())
        .put("clientId", LifecycleTestIdentity.REQUESTER_CLIENT)
        .put("tenantId", SCOPE.tenantId())
        .put("workspaceId", SCOPE.workspaceId())
        .put("requesterPrincipalId", SCOPE.requesterPrincipalId());
    root.set("reviewerMemberships", roster());
    return root;
  }

  private com.fasterxml.jackson.databind.node.ArrayNode roster() {
    var roster = JsonSupport.MAPPER.createArrayNode();
    roster
        .addObject()
        .put("verifiedSubjectId", identity.reviewerVerifiedSubject())
        .put("clientId", LifecycleTestIdentity.REVIEWER_CLIENT)
        .put("tenantId", SCOPE.tenantId())
        .put("workspaceId", SCOPE.workspaceId())
        .put("groupId", GROUP)
        .put("human", true);
    return roster;
  }

  private GitHubBusinessOperationBinding.ExpectedProfile expected() {
    return new GitHubBusinessOperationBinding.ExpectedProfile(
        "github-draft-pr-v1",
        "test",
        1,
        new GitHubBusinessOperationBinding.PolicyRelease(
            "synthetic-lifecycle-policy", 1, CanonicalJson.sha256(POLICY)),
        Map.of("policy_bundle", HASH, "adapter_release", HASH),
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

  private LifecycleReceipt call(
      String name, ObjectNode config, String token, ObjectNode command, byte[] privateKey)
      throws Exception {
    return execute(name, lifecycleInput(config, token, command, privateKey));
  }

  private LifecycleReceipt execute(String name, byte[] input) throws Exception {
    return directWorker()
        ? LifecycleWorker.execute(new ByteArrayInputStream(input), name)
        : installed.call(name, input);
  }

  private static byte[] lifecycleInput(
      ObjectNode config, String token, ObjectNode command, byte[] privateKey) {
    return privateKey == null
        ? frames(bytes(config), token.getBytes(StandardCharsets.UTF_8), bytes(command))
        : frames(
            bytes(config),
            token.getBytes(StandardCharsets.UTF_8),
            privateKey,
            bytes(command));
  }

  private static ObjectNode object(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (!(value instanceof ObjectNode object)) throw new ClassCastException(field);
    return object;
  }

  private static ObjectNode command(String operationId) {
    return JsonSupport.MAPPER
        .createObjectNode()
        .put("schemaVersion", "1.0")
        .put("profile", LifecycleProtocol.PROFILE)
        .put("operationId", operationId);
  }

  private static byte[] bytes(JsonNode value) {
    return value.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] frames(byte[]... values) {
    try {
      var output = new ByteArrayOutputStream();
      var data = new DataOutputStream(output);
      for (byte[] value : values) {
        data.writeInt(value.length);
        data.write(value);
      }
      return output.toByteArray();
    } catch (java.io.IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static String corrupt(String token) {
    int index = token.lastIndexOf('.') + 1;
    char replacement = token.charAt(index) == 'A' ? 'B' : 'A';
    return token.substring(0, index) + replacement + token.substring(index + 1);
  }

  private static void assertState(
      LifecycleReceipt receipt,
      String outcome,
      String authorization,
      String execution,
      boolean dispatchStarted) {
    assertThat(receipt.outcome()).isEqualTo(outcome);
    assertThat(receipt.authorizationStatus()).isEqualTo(authorization);
    assertThat(receipt.executionStatus()).isEqualTo(execution);
    assertThat(receipt.dispatchStarted()).isEqualTo(dispatchStarted);
    assertThat(receipt.exitCode()).isZero();
  }

  private static ObjectNode policy() {
    try {
      return (ObjectNode)
          JsonSupport.MAPPER.readTree(
              """
              {"schemaVersion":"1.0","id":"synthetic-lifecycle-policy","version":1,
               "name":"Synthetic lifecycle","description":"Synthetic only","enabled":true,
               "defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
               "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic",
               "match":{"toolNames":["github.pull_request.create"]},
               "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW",
               "approvalGroup":"synthetic-reviewers"}}]}
              """);
    } catch (Exception failure) {
      throw new AssertionError("SYNTHETIC_POLICY_INVALID", failure);
    }
  }
}
