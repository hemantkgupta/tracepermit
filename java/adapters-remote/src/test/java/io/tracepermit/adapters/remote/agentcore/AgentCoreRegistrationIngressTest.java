package io.tracepermit.adapters.remote.agentcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubOrganizationOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubOrganizationTargetBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.BusinessOperationRegistration;
import io.tracepermit.application.BusinessOperationRepository;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationStorageException.Code;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.InMemoryWorkloadIdentityReplayStore;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import io.tracepermit.identity.OidcTokenProfile;
import io.tracepermit.identity.WorkloadIdentityReplayStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Local RSA fixtures and a recording repository port; never provider interoperability evidence. */
final class AgentCoreRegistrationIngressTest {
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:30Z");
  private static final String ISSUER = "https://identity.example.invalid";
  private static final String AUDIENCE = "https://registration.example.invalid";
  private static final BusinessOperationScope SCOPE =
      new BusinessOperationScope("synthetic-tenant", "synthetic-workspace", "workload:synthetic-agent");
  private static final KeyPair KEY = keyPair();
  private final RecordingRepository repository = new RecordingRepository();
  private final AtomicInteger keyCalls = new AtomicInteger();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void signedEnrolledWorkloadRegistersExactOperatorProfileWithExecutionDisabled() throws Exception {
    var enrollment = enrollment(SCOPE);
    var ingress = ingress(enrollment, new InMemoryWorkloadIdentityReplayStore());
    String authorization = token(claims -> {});
    var receipt = ingress.register(authorization, null, body());
    assertThat(receipt.created()).isTrue();
    assertThat(receipt.executionEnabled()).isFalse();
    JsonNode operation = repository.latest;
    assertThat(BusinessOperationValidator.validate(operation)).isEqualTo(operation);
    var bound = enrollment.targetProfile().bind(SCOPE, operation);
    assertThat(bound.targetBinding()).isEqualTo(enrollment.targetProfile().targetBinding());
    assertThat(operation.path("trustedContext")).isEqualTo(SCOPE.trustedContext());
    // Independently calculated with Python hashlib + sorted compact UTF-8 JSON, not this builder.
    assertThat(operation.path("clientRequestKeyHash").textValue())
        .isEqualTo("sha256:c4afaa73080ce040ab21012be074552c47c5c28173420cc88bf005bafe2e207f");
    assertThat(operation.path("authorization").path("status").textValue()).isEqualTo("evaluating");
    assertThat(operation.path("execution").path("status").textValue()).isEqualTo("not_started");
    assertThat(operation.path("transitions").size()).isEqualTo(3);
    assertThat(operation.path("canonicalAction").path("identity").path("subject").path("type").textValue()).isEqualTo("workload");
    assertThat(operation.path("canonicalAction").path("lineage").path("delegations")).isEmpty();
    assertThat(operation.toString()).doesNotContain(authorization.substring(7), "synthetic-key-1");
    assertThat(operation.path("canonicalAction").path("identity").path("evidence").get(0)
        .path("credentialFingerprint").textValue()).matches("sha256:[a-f0-9]{64}");
    assertThat(receipt.operationId()).isEqualTo(operation.path("operationId").textValue());
    assertThat(repository.calls.get()).isEqualTo(1);
    assertThat(keyCalls.get()).isEqualTo(1);
    assertThat(enrollment.toString()).doesNotContain(ISSUER, SCOPE.tenantId());
  }

  @Test
  void consumesExactTokenOnceAndRequiresFreshIdentityForIdempotentRetry() throws Exception {
    var ingress = ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore());
    String original = token(claims -> {});
    var first = ingress.register(original, null, body());
    JsonNode firstStored = repository.latest.deepCopy();
    rejected(() -> ingress.register(original, null, body()), "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(repository.calls.get()).isEqualTo(1);
    var second = ingress.register(token(claims -> claims.put("jti", "fresh-token")), null, body());
    assertThat(second.created()).isFalse();
    assertThat(second.operationId()).isEqualTo(first.operationId());
    assertThat(repository.latest).isEqualTo(firstStored);
  }

  @Test
  void changedContentReachesScopedRepositoryAndItsConflictStaysFixed() throws Exception {
    var ingress = ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore());
    ingress.register(token(claims -> {}), null, body());
    repository.failure = new BusinessOperationStorageException(Code.OPERATION_REQUEST_KEY_CONFLICT);
    byte[] changed = request().put("title", "Different synthetic change").toString().getBytes(StandardCharsets.UTF_8);
    rejected(() -> ingress.register(token(claims -> claims.put("jti", "fresh-token")), null, changed),
        "AGENTCORE_REQUEST_KEY_CONFLICT");
    assertThat(repository.calls.get()).isEqualTo(2);
    assertThat(repository.proposed.path("semanticPayloadDigest")).isNotEqualTo(repository.latest.path("semanticPayloadDigest"));
    assertThat(repository.proposed.path("clientRequestKeyHash")).isEqualTo(repository.latest.path("clientRequestKeyHash"));
  }

  @Test
  void changedOperatorTargetAndGenerationCannotCreateAnotherNamespaceForTheSameScopedKey() throws Exception {
    var original = enrollment(SCOPE);
    ingress(original, new InMemoryWorkloadIdentityReplayStore()).register(token(claims -> {}), null, body());
    var p = original.targetProfile();
    var old = p.targetBinding();
    var changedTarget = new GitHubPilotTargetBinding(old.installationId(), old.repositoryId(), old.ownerId(),
        old.repository(), old.head(), old.base(), "c".repeat(40), old.baseSha());
    var changedProfile = new GitHubBusinessOperationBinding.ExpectedProfile(p.adapterId(), p.environment(), 2,
        p.policyRelease(), p.artifactDigests(), changedTarget);
    var changedEnrollment = new AgentCoreRegistrationIngress.Enrollment(original.issuer(), original.verifiedSubjectId(),
        original.clientId(), SCOPE, changedProfile, original.retentionPolicyId(), original.retention());
    repository.failure = new BusinessOperationStorageException(Code.OPERATION_REQUEST_KEY_CONFLICT);
    rejected(() -> ingress(changedEnrollment, new InMemoryWorkloadIdentityReplayStore())
        .register(token(claims -> claims.put("jti", "new-profile-token")), null, body()), "AGENTCORE_REQUEST_KEY_CONFLICT");
    assertThat(repository.proposed.path("clientRequestKeyHash")).isEqualTo(repository.latest.path("clientRequestKeyHash"));
    assertThat(repository.proposed.path("resourceBindingHash")).isNotEqualTo(repository.latest.path("resourceBindingHash"));
    assertThat(repository.proposed.path("recoveryGeneration").longValue()).isEqualTo(2);
    assertThat(repository.proposed.path("semanticPayloadDigest")).isEqualTo(repository.latest.path("semanticPayloadDigest"));
  }

  @Test
  void scopePartitionsTheSameClientKeyAndCannotBeSelectedByTheBody() throws Exception {
    var first = ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore());
    first.register(token(claims -> {}), null, body());
    String firstHash = repository.latest.path("clientRequestKeyHash").textValue();
    var other = new BusinessOperationScope(SCOPE.tenantId(), "other-workspace", SCOPE.requesterPrincipalId());
    repository.latest = null;
    ingress(enrollment(other), new InMemoryWorkloadIdentityReplayStore()).register(token(claims -> {}), null, body());
    assertThat(repository.latest.path("clientRequestKeyHash").textValue()).isNotEqualTo(firstHash);
    assertThat(repository.scope).isEqualTo(other);
  }

  @ParameterizedTest
  @ValueSource(strings = {"iss", "aud", "sub", "client_id", "org_id", "scope", "exp", "nbf", "iat", "cnf"})
  void rejectsWrongIdentityFactsBeforeRegistration(String field) throws Exception {
    String authorization = token(claims -> {
      switch (field) {
        case "exp" -> claims.put(field, NOW.getEpochSecond());
        case "iat", "nbf" -> claims.put(field, NOW.plusSeconds(120).getEpochSecond());
        case "scope" -> claims.put(field, "operations.other");
        case "cnf" -> claims.putObject(field).put("x5t#S256", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        default -> claims.put(field, "https://unrelated.example.invalid");
      }
    });
    rejected(() -> ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore())
        .register(authorization, null, body()), "AGENTCORE_IDENTITY_REJECTED");
    assertThat(repository.calls.get()).isZero();
  }

  @Test
  void enrollmentRejectsMappedTenantSubstitution() throws Exception {
    var substituted = new BusinessOperationScope("other-tenant", SCOPE.workspaceId(), SCOPE.requesterPrincipalId());
    rejected(() -> ingress(enrollment(substituted), new InMemoryWorkloadIdentityReplayStore())
        .register(token(claims -> {}), null, body()), "AGENTCORE_IDENTITY_REJECTED");
    assertThat(repository.calls.get()).isZero();
  }

  @Test
  void oktaRegistrationRequiresVerifiedRequesterRoleInAdditionToExactEnrollmentAndScope()
      throws Exception {
    var enrollment = enrollment(SCOPE);
    var accepted =
        new AgentCoreRegistrationIngress(
            oktaVerifier(),
            new InMemoryWorkloadIdentityReplayStore(),
            repository,
            clock,
            enrollment);
    accepted.register(oktaToken("pilot-requesters", "requester-token"), null, body());
    assertThat(repository.calls.get()).isEqualTo(1);

    var refused =
        new AgentCoreRegistrationIngress(
            oktaVerifier(),
            new InMemoryWorkloadIdentityReplayStore(),
            repository,
            clock,
            enrollment);
    rejected(
        () -> refused.register(oktaToken("pilot-reviewers", "reviewer-token"), null, body()),
        "AGENTCORE_IDENTITY_REJECTED");
    assertThat(repository.calls.get()).isEqualTo(1);
  }

  @Test
  void closedOrganizationFactoryRegistersAndRebindsTheExactOrganizationProfile()
      throws Exception {
    var enrollment = organizationEnrollment(SCOPE);
    var ingress =
        AgentCoreRegistrationIngress.organization(
            oktaVerifier(),
            new InMemoryWorkloadIdentityReplayStore(),
            repository,
            clock,
            enrollment);

    var receipt =
        ingress.register(
            oktaToken("pilot-requesters", "organization-requester-token"), null, body());

    assertThat(receipt.created()).isTrue();
    assertThat(repository.latest.path("adapterId").asText())
        .isEqualTo(GitHubOrganizationOperationBinding.ADAPTER_ID);
    assertThat(enrollment.targetProfile().bind(SCOPE, repository.latest).targetBinding())
        .isEqualTo(enrollment.targetProfile().targetBinding());
  }

  @Test
  void organizationEnrollmentRejectsTheLegacyStandardOidcProfile() throws Exception {
    var ingress =
        AgentCoreRegistrationIngress.organization(
            verifier(),
            new InMemoryWorkloadIdentityReplayStore(),
            repository,
            clock,
            organizationEnrollment(SCOPE));

    rejected(
        () -> ingress.register(token(claims -> {}), null, body()),
        "AGENTCORE_IDENTITY_REJECTED");
    assertThat(repository.calls.get()).isZero();
  }

  @Test
  void rejectsUnrelatedValidTokenPairWithoutTreatingItAsDelegation() throws Exception {
    String workload = token(claims -> {});
    String human = token(claims -> { claims.put("sub", "human:synthetic"); claims.put("jti", "human-token"); });
    // Both tokens independently pass the unchanged verifier.
    assertThat(verifier().authenticate(workload)).isNotNull();
    assertThat(verifier().authenticate(human)).isNotNull();
    keyCalls.set(0);
    rejected(() -> ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore())
        .register(workload, human, body()), "AGENTCORE_DELEGATION_CONTRACT_REQUIRED");
    assertThat(keyCalls.get()).isZero();
    assertThat(repository.calls.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"authenticated", "delegated", "verified", "approved", "trustedContext", "tenantId", "workspaceId", "requesterPrincipalId", "target", "profile", "policyRelease", "recoveryGeneration", "operationId", "adapterId", "arguments", "humanToken"})
  void refusesEveryUnsupportedAuthorityOrRoutingField(String field) throws Exception {
    byte[] json = request().put(field, "untrusted").toString().getBytes(StandardCharsets.UTF_8);
    rejected(() -> ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore())
        .register(token(claims -> {}), null, json), "AGENTCORE_REQUEST_INVALID");
    assertThat(keyCalls.get()).isZero();
    assertThat(repository.calls.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "null", "[]", "", " ", "{", "{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"}",
      "{\"a\":{\"x\":1,\"x\":2}}", "{} {}", "{\"a\":[[[[[]]]]]}", "{\"a\":1e9999999999999999999999999999999999}"})
  void rejectsAmbiguousMalformedOrDeepJson(String json) throws Exception {
    rejected(() -> ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore())
        .register(token(claims -> {}), null, json.getBytes(StandardCharsets.UTF_8)), "AGENTCORE_REQUEST_INVALID");
    assertThat(keyCalls.get()).isZero();
    assertThat(repository.calls.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"schemaVersion", "clientRequestKey", "title", "body"})
  void rejectsWrongTypesAndMissingOrBlankRequiredFields(String field) throws Exception {
    var ingress = ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore());
    for (JsonNode value : List.of(JsonSupport.MAPPER.nullNode(), JsonSupport.MAPPER.createObjectNode(), JsonSupport.MAPPER.getNodeFactory().numberNode(1))) {
      ObjectNode json = request(); json.set(field, value);
      rejected(() -> ingress.register(token(claims -> {}), null, json.toString().getBytes(StandardCharsets.UTF_8)), "AGENTCORE_REQUEST_INVALID");
    }
    ObjectNode blank = request().put(field, " ");
    rejected(() -> ingress.register(token(claims -> {}), null, blank.toString().getBytes(StandardCharsets.UTF_8)), "AGENTCORE_REQUEST_INVALID");
    assertThat(repository.calls.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"schema", "key", "title", "body", "name", "bytes", "utf8", "surrogate", "null"})
  void parserBoundsAndUnsupportedSchemaFailBeforeVerification(String mutation) throws Exception {
    ObjectNode json = request();
    switch (mutation) {
      case "schema" -> json.put("schemaVersion", "2.0");
      case "key" -> json.put("clientRequestKey", "x".repeat(129));
      case "title" -> json.put("title", "x".repeat(257));
      case "body" -> json.put("body", "x".repeat(16_385));
      case "name" -> json.put("x".repeat(65), "bad");
      case "surrogate" -> json.put("title", "\uD800");
      default -> { }
    }
    byte[] bytes = switch (mutation) {
      case "null" -> null;
      case "bytes" -> new byte[AgentCoreRegistrationIngress.MAXIMUM_REQUEST_BYTES + 1];
      case "utf8" -> new byte[] {(byte) 0xc3, (byte) 0x28};
      case "surrogate" -> "{\"schemaVersion\":\"1.0\",\"clientRequestKey\":\"x\",\"title\":\"\\uD800\"}".getBytes(StandardCharsets.UTF_8);
      default -> json.toString().getBytes(StandardCharsets.UTF_8);
    };
    rejected(() -> ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore())
        .register(token(claims -> {}), null, bytes), "AGENTCORE_REQUEST_INVALID");
    assertThat(keyCalls.get()).isZero();
    assertThat(repository.calls.get()).isZero();
  }

  @Test
  void preservesMaximumAllowedTitleBodyAndUnicodeWithoutNormalization() throws Exception {
    ObjectNode json = request().put("title", "t".repeat(256)).put("body", "x".repeat(16_380) + "😀");
    ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore()).register(token(claims -> {}), null,
        json.toString().getBytes(StandardCharsets.UTF_8));
    assertThat(repository.latest.path("canonicalAction").path("action").path("arguments").path("body").textValue())
        .isEqualTo(json.path("body").textValue());
  }

  @ParameterizedTest
  @EnumSource(value = Code.class, names = {"OPERATION_COMMIT_UNKNOWN", "OPERATION_STORAGE_UNAVAILABLE", "OPERATION_ID_CONFLICT"})
  void fixedRepositoryFailuresNeverRetryAndConsumeIdentity(Code code) throws Exception {
    repository.failure = new BusinessOperationStorageException(code);
    var ingress = ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore());
    String authorization = token(claims -> {});
    rejected(() -> ingress.register(authorization, null, body()), code == Code.OPERATION_COMMIT_UNKNOWN
        ? "AGENTCORE_REGISTRATION_UNKNOWN" : "AGENTCORE_STORAGE_UNAVAILABLE");
    rejected(() -> ingress.register(authorization, null, body()), "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(repository.calls.get()).isEqualTo(1);
  }

  @Test
  void replayDependencyAndRepositoryDiagnosticsCannotEscape() throws Exception {
    var ingress = ingress(enrollment(SCOPE), (tenant, issuer, id, expiry, now) -> {
      throw new IllegalStateException("SENSITIVE replay diagnostics");
    });
    rejected(() -> ingress.register(token(claims -> {}), null, body()), "AGENTCORE_REGISTRATION_REJECTED");
    repository.failure = new IllegalStateException("SENSITIVE repository diagnostics");
    rejected(() -> ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore())
        .register(token(claims -> {}), null, body()), "AGENTCORE_STORAGE_UNAVAILABLE");
  }

  @Test
  void rejectsInvalidSignatureAndOversizedAuthorization() throws Exception {
    String valid = token(claims -> {});
    String corrupted = valid.substring(0, valid.lastIndexOf('.') + 1) + "A".repeat(342);
    var ingress = ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore());
    for (String authorization : List.of("", "Bearer ", corrupted, "Bearer " + "x".repeat(16_385))) {
      rejected(() -> ingress.register(authorization, null, body()), "AGENTCORE_IDENTITY_REJECTED");
    }
    rejected(() -> ingress.register(null, null, body()), "AGENTCORE_IDENTITY_REJECTED");
    assertThat(repository.calls.get()).isZero();
  }

  @Test
  void sharedBoundedReplayStoreGivesOneWinnerUnderContention() throws Exception {
    var ingress = ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore());
    String authorization = token(claims -> {});
    CountDownLatch ready = new CountDownLatch(16), start = new CountDownLatch(1);
    AtomicInteger accepted = new AtomicInteger(), refused = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(16)) {
      List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 16; i++) futures.add(executor.submit(() -> {
        ready.countDown();
        try {
          assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
          ingress.register(authorization, null, body()); accepted.incrementAndGet();
        } catch (AgentCoreRegistrationIngress.IngressException expected) {
          assertThat(expected.code()).isEqualTo("AGENTCORE_REGISTRATION_REJECTED"); refused.incrementAndGet();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        }
      }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
      for (var future : futures) future.get(15, TimeUnit.SECONDS);
    }
    assertThat(accepted.get()).isEqualTo(1); assertThat(refused.get()).isEqualTo(15);
    assertThat(repository.calls.get()).isEqualTo(1);
  }

  @Test
  void identityExpiresDuringConstructionOrReplayCapacityRefusesBeforeRepository() throws Exception {
    AtomicReference<Instant> now = new AtomicReference<>(NOW);
    Clock moving = new Clock() {
      @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId zone) { return this; }
      @Override public Instant instant() { return now.getAndSet(NOW.plusSeconds(300)); }
    };
    var ingress = new AgentCoreRegistrationIngress(verifier(), new InMemoryWorkloadIdentityReplayStore(), repository, moving, enrollment(SCOPE));
    rejected(() -> ingress.register(token(claims -> {}), null, body()), "AGENTCORE_REGISTRATION_REJECTED");
    var replay = new InMemoryWorkloadIdentityReplayStore(1, 1);
    assertThat(replay.consume("other", "issuer", "id", NOW.plusSeconds(300), NOW)).isTrue();
    rejected(() -> ingress(enrollment(SCOPE), replay).register(token(claims -> {}), null, body()), "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(repository.calls.get()).isZero();
  }

  @Test
  void noExecutionCredentialOrTransportDependencyCanBeSupplied() throws Exception {
    var publicConstructors =
        java.util.Arrays.stream(AgentCoreRegistrationIngress.class.getDeclaredConstructors())
            .filter(constructor -> java.lang.reflect.Modifier.isPublic(constructor.getModifiers()))
            .toList();
    assertThat(publicConstructors).hasSize(1);
    assertThat(publicConstructors.getFirst().getParameterTypes())
        .containsExactly(OidcAccessTokenVerifier.class, WorkloadIdentityReplayStore.class,
            BusinessOperationRepository.class, Clock.class, AgentCoreRegistrationIngress.Enrollment.class);
    var organization = AgentCoreRegistrationIngress.class.getDeclaredMethod(
        "organization", OidcAccessTokenVerifier.class, WorkloadIdentityReplayStore.class,
        BusinessOperationRepository.class, Clock.class,
        AgentCoreRegistrationIngress.OrganizationEnrollment.class);
    assertThat(java.lang.reflect.Modifier.isPublic(organization.getModifiers())).isTrue();
    assertThat(java.lang.reflect.Modifier.isStatic(organization.getModifiers())).isTrue();
    assertThat(organization.getReturnType()).isEqualTo(AgentCoreRegistrationIngress.class);
    assertThat(java.util.Arrays.stream(AgentCoreRegistrationIngress.class.getDeclaredMethods())
        .map(java.lang.reflect.Method::getName)).doesNotContain("execute", "dispatch", "credentials");
  }

  @ParameterizedTest
  @ValueSource(strings = {"typ", "alg", "kid", "extra"})
  void rejectsUnsupportedOrUntrustedSignedHeaders(String field) throws Exception {
    ObjectNode header = JsonSupport.MAPPER.createObjectNode().put("alg", "RS256")
        .put("typ", "at+jwt").put("kid", "synthetic-key");
    header.put(field, "unsupported");
    String valid = token(claims -> {});
    String[] segments = valid.substring(7).split("\\.");
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input = encoder.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8)) + "." + segments[1];
    Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(KEY.getPrivate());
    signer.update(input.getBytes(StandardCharsets.US_ASCII));
    String changed = "Bearer " + input + "." + encoder.encodeToString(signer.sign());
    rejected(() -> ingress(enrollment(SCOPE), new InMemoryWorkloadIdentityReplayStore())
        .register(changed, null, body()), "AGENTCORE_IDENTITY_REJECTED");
    assertThat(repository.calls.get()).isZero();
  }

  @Test
  void rejectsInvalidEnrollmentWithoutLeakingItsValues() {
    var good = enrollment(SCOPE);
    assertThatThrownBy(() -> new AgentCoreRegistrationIngress.Enrollment("http://bad.invalid",
        good.verifiedSubjectId(), good.clientId(), SCOPE, good.targetProfile(), good.retentionPolicyId(), good.retention()))
        .hasMessage("AGENTCORE_CONFIGURATION_INVALID").hasNoCause();
    assertThatThrownBy(() -> new AgentCoreRegistrationIngress.Enrollment(good.issuer(),
        "unrelated-subject", good.clientId(), SCOPE, good.targetProfile(), good.retentionPolicyId(), good.retention()))
        .hasMessage("AGENTCORE_CONFIGURATION_INVALID").hasNoCause();
    assertThatThrownBy(() -> new AgentCoreRegistrationIngress.Enrollment(good.issuer(),
        good.verifiedSubjectId(), "", SCOPE, good.targetProfile(), good.retentionPolicyId(), good.retention()))
        .hasMessage("AGENTCORE_CONFIGURATION_INVALID").hasNoCause();
    for (Duration retention : List.of(Duration.ZERO, Duration.ofDays(366))) {
      assertThatThrownBy(() -> new AgentCoreRegistrationIngress.Enrollment(good.issuer(),
          good.verifiedSubjectId(), good.clientId(), SCOPE, good.targetProfile(), good.retentionPolicyId(), retention))
          .hasMessage("AGENTCORE_CONFIGURATION_INVALID").hasNoCause();
    }
  }

  private AgentCoreRegistrationIngress ingress(AgentCoreRegistrationIngress.Enrollment enrollment, WorkloadIdentityReplayStore replay) {
    return new AgentCoreRegistrationIngress(verifier(), replay, repository, clock, enrollment);
  }

  private OidcAccessTokenVerifier verifier() {
    var provider = new OidcProviderConfiguration(URI.create(ISSUER), AUDIENCE,
        URI.create(ISSUER + "/jwks"), URI.create(AUDIENCE + "/metadata"), List.of(URI.create(ISSUER)),
        "Synthetic registration", Map.of("synthetic-client", "Synthetic client"), "org_id",
        Map.of("synthetic-org", SCOPE.tenantId()), Set.of(AgentCoreRegistrationIngress.REQUIRED_SCOPE),
        ConnectorOrigin.CLI, Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(30));
    return new OidcAccessTokenVerifier(issuer -> ISSUER.equals(issuer) ? Optional.of(provider) : Optional.empty(),
        (config, id, algorithm, now) -> {
          keyCalls.incrementAndGet();
          if (!"synthetic-key".equals(id) || !"RS256".equals(algorithm)) throw new IllegalStateException("Untrusted key");
          return (RSAPublicKey) KEY.getPublic();
        }, clock);
  }

  private OidcAccessTokenVerifier oktaVerifier() {
    var provider =
        new OidcProviderConfiguration(
            URI.create(ISSUER),
            AUDIENCE,
            URI.create(ISSUER + "/jwks"),
            URI.create(AUDIENCE + "/metadata"),
            List.of(URI.create(ISSUER)),
            "Synthetic registration",
            Map.of("synthetic-client", "Synthetic client"),
            "tracepermit_tenant",
            Map.of("synthetic-org", SCOPE.tenantId()),
            Set.of(AgentCoreRegistrationIngress.REQUIRED_SCOPE),
            ConnectorOrigin.CLI,
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30),
            OidcTokenProfile.okta(
                "groups",
                Map.of(
                    "pilot-requesters", "REQUESTER",
                    "pilot-reviewers", "REVIEWER")));
    return new OidcAccessTokenVerifier(
        issuer -> ISSUER.equals(issuer) ? Optional.of(provider) : Optional.empty(),
        (config, id, algorithm, now) -> {
          keyCalls.incrementAndGet();
          if (!"synthetic-key".equals(id) || !"RS256".equals(algorithm)) {
            throw new IllegalStateException("Untrusted key");
          }
          return (RSAPublicKey) KEY.getPublic();
        },
        clock);
  }

  private static AgentCoreRegistrationIngress.Enrollment enrollment(BusinessOperationScope scope) {
    var target = new GitHubPilotTargetBinding(71, 101, 201, "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40));
    var profile = new GitHubBusinessOperationBinding.ExpectedProfile("github-draft-pr-v1", "test", 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-policy", 1, "sha256:" + "5".repeat(64)),
        Map.of("adapter_release", "sha256:" + "7".repeat(64)), target);
    return new AgentCoreRegistrationIngress.Enrollment(ISSUER, ISSUER + "#workload:synthetic", "synthetic-client",
        scope, profile, "synthetic-retention", Duration.ofDays(30));
  }

  private static AgentCoreRegistrationIngress.OrganizationEnrollment organizationEnrollment(
      BusinessOperationScope scope) {
    var target =
        new GitHubOrganizationTargetBinding(
            61,
            71,
            101,
            201,
            "example-sandbox/tracepermit-change-control",
            "tracepermit/synthetic-change",
            "main",
            "a".repeat(40),
            "b".repeat(40),
            GitHubOrganizationTargetBinding.requiredPermissions());
    var profile =
        new GitHubOrganizationOperationBinding.ExpectedProfile(
            GitHubOrganizationOperationBinding.ADAPTER_ID,
            "test",
            1,
            new GitHubBusinessOperationBinding.PolicyRelease(
                "synthetic-policy", 1, "sha256:" + "5".repeat(64)),
            Map.of("adapter_release", "sha256:" + "7".repeat(64)),
            target);
    return new AgentCoreRegistrationIngress.OrganizationEnrollment(
        ISSUER,
        ISSUER + "#workload:synthetic",
        "synthetic-client",
        scope,
        profile,
        "synthetic-retention",
        Duration.ofDays(30));
  }

  private static byte[] body() { return request().toString().getBytes(StandardCharsets.UTF_8); }
  private static ObjectNode request() {
    return JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0")
        .put("clientRequestKey", "synthetic-key-1").put("title", "Synthetic change");
  }

  private static KeyPair keyPair() {
    try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
    catch (java.security.GeneralSecurityException failure) { throw new ExceptionInInitializerError(failure); }
  }

  private static String token(Consumer<ObjectNode> mutate) throws Exception {
    ObjectNode header = JsonSupport.MAPPER.createObjectNode().put("alg", "RS256").put("typ", "at+jwt").put("kid", "synthetic-key");
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode().put("iss", ISSUER).put("aud", AUDIENCE)
        .put("sub", "workload:synthetic").put("client_id", "synthetic-client").put("org_id", "synthetic-org")
        .put("scope", "operations.register").put("iat", NOW.minusSeconds(30).getEpochSecond())
        .put("exp", NOW.plusSeconds(270).getEpochSecond()).put("jti", "synthetic-token");
    mutate.accept(claims);
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input = encoder.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
        + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
    Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(KEY.getPrivate());
    signer.update(input.getBytes(StandardCharsets.US_ASCII));
    return "Bearer " + input + "." + encoder.encodeToString(signer.sign());
  }

  private static String oktaToken(String group, String tokenId) throws Exception {
    ObjectNode header =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("alg", "RS256")
            .put("typ", "JWT")
            .put("kid", "synthetic-key");
    ObjectNode claims =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("iss", ISSUER)
            .put("aud", AUDIENCE)
            .put("sub", "workload:synthetic")
            .put("cid", "synthetic-client")
            .put("tracepermit_tenant", "synthetic-org")
            .put("iat", NOW.minusSeconds(30).getEpochSecond())
            .put("exp", NOW.plusSeconds(270).getEpochSecond())
            .put("jti", tokenId);
    claims.putArray("scp").add("operations.register");
    claims.putArray("groups").add(group);
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input =
        encoder.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(KEY.getPrivate());
    signer.update(input.getBytes(StandardCharsets.US_ASCII));
    return "Bearer " + input + "." + encoder.encodeToString(signer.sign());
  }

  private static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
    assertThatThrownBy(action).isInstanceOf(AgentCoreRegistrationIngress.IngressException.class)
        .hasMessage(code).hasNoCause().satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
  }

  private static final class RecordingRepository implements BusinessOperationRepository {
    final AtomicInteger calls = new AtomicInteger();
    volatile ObjectNode latest;
    volatile ObjectNode proposed;
    volatile BusinessOperationScope scope;
    volatile RuntimeException failure;
    @Override public BusinessOperationRegistration createOrGet(BusinessOperationScope trusted, JsonNode candidate) {
      calls.incrementAndGet(); scope = trusted; proposed = (ObjectNode) candidate.deepCopy();
      if (failure != null) throw failure;
      boolean created = latest == null;
      if (created) latest = (ObjectNode) BusinessOperationValidator.validate(candidate);
      return new BusinessOperationRegistration(created, latest);
    }
    @Override public Optional<JsonNode> find(BusinessOperationScope scope, String id) {
      throw new AssertionError("Registration ingress must not read snapshots");
    }
  }
}
