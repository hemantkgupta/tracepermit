package io.tracepermit.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ScheduledWorkloadVerifierTest {
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:30Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String TENANT = "tenant:acme";
  private static final String SCHEDULER = "scheduler:primary";
  private static final String WORKLOAD = "workload:daily-cost-report";
  private static final String AGENT = "agent:report-builder";
  private static final String TOOL = "tool:aws-cost-simulator";
  private static final String AUDIENCE = "tracepermit-gateway";
  private static final String SCOPE = "aws.cost.read";
  private static final String RESOURCE = "aws:simulated:account/123456789012/cost-report";
  private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
  private static final String EVENT_ID = "00000000-0000-4000-8000-000000000015";
  private static final String SCHEDULE_ID = "schedule:daily-cost-report";
  private static final String RUN_ID = "run:2030-01-01";

  @Test
  void verifiesCompleteChainAndBuildsOnlySafeCanonicalEvidence() {
    Fixture fixture = fixture();
    Chain chain = fixture.chain(action(), claims -> {});

    VerifiedScheduledWorkload verified =
        fixture.verifier().verify(
            chain.workload(), List.of(chain.child(), chain.root()), action(), context(true));

    assertThat(verified.subjectId()).isEqualTo(WORKLOAD);
    assertThat(verified.delegations()).extracting(VerifiedScheduledWorkload.VerifiedDelegationGrant::subjectId)
        .containsExactly(AGENT, TOOL);
    assertThat(verified.credentialFingerprint()).matches("^sha256:[a-f0-9]{64}$");
    assertThat(verified.toString())
        .doesNotContain(chain.workload(), chain.root(), chain.child(), "signature", "private");

    Fixture adapterFixture = fixture();
    Chain adapterChain = adapterFixture.chain(action(), claims -> {});
    JsonNode canonical =
        new ScheduledActionAdapter(adapterFixture.verifier())
            .verifyAndNormalize(
                scheduledInput(),
                adapterChain.workload(),
                List.of(adapterChain.child(), adapterChain.root()),
                context(true));

    assertThat(canonical.path("identity").path("subject").path("id").asText())
        .isEqualTo(WORKLOAD);
    assertThat(canonical.path("trigger").path("kind").asText()).isEqualTo("scheduled");
    assertThat(canonical.path("lineage").path("delegations")).hasSize(2);
    assertThat(canonical.path("clientClaims")).isEmpty();
    assertThat(canonical.toString())
        .doesNotContain(
            adapterChain.workload(),
            adapterChain.root(),
            adapterChain.child(),
            "signature",
            "must-not-enter-canonical-evidence");
  }

  @Test
  void malformedTamperedWrongAudienceWrongKeyAndCredentialConfusionFailClosed() {
    assertCode(
        () -> fixture().verifier().verify("not-a-jws", List.of(), action(), context(false)),
        "MALFORMED_WORKLOAD_CREDENTIAL");

    Fixture tamperedFixture = fixture();
    Chain tamperedChain = tamperedFixture.chain(action(), claims -> {});
    assertCode(
        () ->
            tamperedFixture
                .verifier()
                .verify(tamper(tamperedChain.workload()), List.of(), action(), context(false)),
        "INVALID_WORKLOAD_CREDENTIAL");

    Fixture audienceFixture = fixture();
    String wrongAudience =
        audienceFixture.workloadSigner().sign(
            workloadClaims(action(), UUID.randomUUID().toString())
                .put("audience", "another-gateway"),
            "tracepermit-workload-identity+jws");
    assertCode(
        () -> audienceFixture.verifier().verify(wrongAudience, List.of(), action(), context(false)),
        "INVALID_WORKLOAD_CREDENTIAL");

    Fixture wrongKeyFixture = fixture();
    TestSigner attacker = signer("tracepermit-scheduler:primary", "scheduler-key-001", SCHEDULER);
    String wrongKey =
        attacker.sign(
            workloadClaims(action(), UUID.randomUUID().toString()),
            "tracepermit-workload-identity+jws");
    assertCode(
        () -> wrongKeyFixture.verifier().verify(wrongKey, List.of(), action(), context(false)),
        "INVALID_WORKLOAD_CREDENTIAL");

    Fixture confusionFixture = fixture();
    Chain confusionChain = confusionFixture.chain(action(), claims -> {});
    assertCode(
        () ->
            confusionFixture
                .verifier()
                .verify(
                    confusionChain.workload(),
                    List.of(confusionChain.workload()),
                    action(),
                    context(true)),
        "INVALID_DELEGATION_CREDENTIAL");
  }

  @Test
  void actionMutationAndTrustedFieldInjectionFailBeforeDispatch() {
    Fixture mutationFixture = fixture();
    Chain chain = mutationFixture.chain(action(), claims -> {});
    ObjectNode changedAction = action();
    changedAction.put("toolName", "aws.cost.delete");
    assertCode(
        () ->
            mutationFixture
                .verifier()
                .verify(chain.workload(), List.of(chain.root(), chain.child()), changedAction, context(true)),
        "INVALID_WORKLOAD_CREDENTIAL");

    Fixture injectionFixture = fixture();
    Chain injectionChain = injectionFixture.chain(action(), claims -> {});
    ObjectNode injected = scheduledInput();
    injected.putObject("identity").put("subjectId", "attacker");
    assertCode(
        () ->
            new ScheduledActionAdapter(injectionFixture.verifier())
                .verifyAndNormalize(
                    injected,
                    injectionChain.workload(),
                    List.of(injectionChain.root(), injectionChain.child()),
                    context(true)),
        "INVALID_SCHEDULED_ACTION");
  }

  @Test
  void scheduledAdapterAcceptsOptionalIntentAndRejectsMalformedEnvelopes() {
    Fixture fixture = fixture();
    Chain chain = fixture.chain(action(), claims -> {});
    ObjectNode withoutIntent = scheduledInput();
    withoutIntent.remove("declaredIntent");
    JsonNode canonical =
        new ScheduledActionAdapter(fixture.verifier())
            .verifyAndNormalize(
                withoutIntent, chain.workload(), List.of(), context(false));
    assertThat(canonical.has("declaredIntent")).isFalse();

    Fixture invalidFixture = fixture();
    ScheduledActionAdapter adapter = new ScheduledActionAdapter(invalidFixture.verifier());
    assertInvalidScheduledInput(adapter, null);
    assertInvalidScheduledInput(adapter, JsonSupport.MAPPER.createArrayNode());
    ObjectNode missingClaims = scheduledInput();
    missingClaims.remove("clientClaims");
    assertInvalidScheduledInput(adapter, missingClaims);
    ObjectNode wrongVersion = scheduledInput();
    wrongVersion.put("schemaVersion", "2.0");
    assertInvalidScheduledInput(adapter, wrongVersion);
    ObjectNode wrongAction = scheduledInput();
    wrongAction.put("action", "not-an-object");
    assertInvalidScheduledInput(adapter, wrongAction);
    ObjectNode wrongClaims = scheduledInput();
    wrongClaims.put("clientClaims", "not-an-object");
    assertInvalidScheduledInput(adapter, wrongClaims);
    ObjectNode wrongIntent = scheduledInput();
    wrongIntent.put("declaredIntent", "not-an-object");
    assertInvalidScheduledInput(adapter, wrongIntent);
    ObjectNode injectedAction = scheduledInput();
    ((ObjectNode) injectedAction.path("action")).put("verified", true);
    assertInvalidScheduledInput(adapter, injectedAction);
    ObjectNode injectedTarget = scheduledInput();
    ((ObjectNode) injectedTarget.path("action").path("target"))
        .put("credential", "request-controlled");
    assertInvalidScheduledInput(adapter, injectedTarget);
    ObjectNode injectedIntent = scheduledInput();
    ((ObjectNode) injectedIntent.path("declaredIntent")).put("approved", true);
    assertInvalidScheduledInput(adapter, injectedIntent);
  }

  @Test
  void expiryRevocationUnknownStatusAndReplayFailClosed() {
    Fixture expiredFixture = fixture();
    ObjectNode expiredClaims = workloadClaims(action(), UUID.randomUUID().toString());
    expiredClaims.put("expiresAt", "2030-01-01T00:00:20Z");
    String expired =
        expiredFixture
            .workloadSigner()
            .sign(expiredClaims, "tracepermit-workload-identity+jws");
    assertCode(
        () -> expiredFixture.verifier().verify(expired, List.of(), action(), context(false)),
        "EXPIRED_WORKLOAD_CREDENTIAL");

    Fixture revokedFixture = fixture();
    String revokedId = UUID.randomUUID().toString();
    revokedFixture
        .revocation()
        .revoke(TENANT, "tracepermit-scheduler:primary", revokedId);
    String revoked =
        revokedFixture
            .workloadSigner()
            .sign(workloadClaims(action(), revokedId), "tracepermit-workload-identity+jws");
    assertCode(
        () -> revokedFixture.verifier().verify(revoked, List.of(), action(), context(false)),
        "WORKLOAD_CREDENTIAL_REVOKED");

    Fixture unavailableFixture = fixture();
    unavailableFixture.revocation().setAvailable(false);
    Chain unavailable = unavailableFixture.chain(action(), claims -> {});
    assertCode(
        () ->
            unavailableFixture
                .verifier()
                .verify(unavailable.workload(), List.of(), action(), context(false)),
        "WORKLOAD_REVOCATION_UNAVAILABLE");

    Fixture replayFixture = fixture();
    Chain replay = replayFixture.chain(action(), claims -> {});
    replayFixture.verifier().verify(replay.workload(), List.of(), action(), context(false));
    assertCode(
        () ->
            replayFixture
                .verifier()
                .verify(replay.workload(), List.of(), action(), context(false)),
        "WORKLOAD_CREDENTIAL_REPLAYED");
  }

  @Test
  void revokedKeysAndExpiredDelegationsFailClosed() {
    Fixture revokedKeyFixture = fixture(WorkloadTrustRegistry.KeyState.REVOKED);
    Chain revokedKey = revokedKeyFixture.chain(action(), claims -> {});
    assertCode(
        () ->
            revokedKeyFixture
                .verifier()
                .verify(revokedKey.workload(), List.of(), action(), context(false)),
        "WORKLOAD_KEY_REVOKED");

    Fixture expiredGrantFixture = fixture();
    Chain expiredGrant =
        expiredGrantFixture.chain(
            action(), claims -> claims.put("expiresAt", "2030-01-01T00:00:20Z"));
    assertCode(
        () ->
            expiredGrantFixture
                .verifier()
                .verify(
                    expiredGrant.workload(),
                    List.of(expiredGrant.root(), expiredGrant.child()),
                    action(),
                    context(true)),
        "EXPIRED_DELEGATION_CREDENTIAL");

    Fixture revokedGrantFixture = fixture();
    String rootId = UUID.randomUUID().toString();
    String root = revokedGrantFixture.root(rootId, claims -> {});
    revokedGrantFixture
        .revocation()
        .revoke(TENANT, "tracepermit-workload:daily-cost-report", rootId);
    String child = revokedGrantFixture.child(root, rootId, UUID.randomUUID().toString(), claims -> {});
    String workload = revokedGrantFixture.workload(action(), UUID.randomUUID().toString(), claims -> {});
    assertCode(
        () ->
            revokedGrantFixture
                .verifier()
                .verify(workload, List.of(root, child), action(), context(true)),
        "DELEGATION_REVOKED");
  }

  @Test
  void incompleteBroadenedAndBrokenDelegationChainsFailClosed() {
    assertBrokenChain(claims -> scopes(claims).add("aws.admin"));
    assertBrokenChain(claims -> resources(claims).add("aws:simulated:account/all"));
    assertBrokenChain(claims -> claims.put("expiresAt", "2030-01-01T00:06:00Z"));
    assertBrokenChain(claims -> claims.put("parentTokenHash", "sha256:" + "0".repeat(64)));
    assertBrokenChain(
        claims -> claims.put("actorId", WORKLOAD), "INVALID_DELEGATION_CREDENTIAL");
    assertBrokenChain(claims -> {
      ArrayNode values = JsonSupport.MAPPER.createArrayNode();
      values.add("aws.cost.other");
      claims.set("scopes", values);
    });

    Fixture missingFixture = fixture();
    Chain missing = missingFixture.chain(action(), claims -> {});
    assertCode(
        () ->
            missingFixture
                .verifier()
                .verify(missing.workload(), List.of(missing.child()), action(), context(true)),
        "INVALID_DELEGATION_CHAIN");
  }

  @Test
  void directWorkloadIsAllowedOnlyWhenTrustedMappingDoesNotRequireDelegation() {
    Fixture acceptedFixture = fixture();
    Chain accepted = acceptedFixture.chain(action(), claims -> {});
    assertThat(
            acceptedFixture
                .verifier()
                .verify(accepted.workload(), List.of(), action(), context(false))
                .delegations())
        .isEmpty();

    Fixture rejectedFixture = fixture();
    Chain rejected = rejectedFixture.chain(action(), claims -> {});
    assertCode(
        () ->
            rejectedFixture
                .verifier()
                .verify(rejected.workload(), List.of(), action(), context(true)),
        "INVALID_DELEGATION_CHAIN");
  }

  @Test
  void exactlyOneOfThirtyTwoConcurrentWorkloadClaimsSucceeds() throws Exception {
    Fixture fixture = fixture();
    Chain chain = fixture.chain(action(), claims -> {});
    List<Callable<String>> operations = new ArrayList<>();
    for (int index = 0; index < 32; index++) {
      operations.add(
          () -> {
            try {
              fixture
                  .verifier()
                  .verify(chain.workload(), List.of(chain.root(), chain.child()), action(), context(true));
              return "accepted";
            } catch (WorkloadCredentialException exception) {
              return exception.code();
            }
          });
    }

    List<String> results;
    try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
      results =
          executor.invokeAll(operations).stream()
              .map(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
              .toList();
    }

    assertThat(results).filteredOn("accepted"::equals).hasSize(1);
    assertThat(results).filteredOn("WORKLOAD_CREDENTIAL_REPLAYED"::equals).hasSize(31);
  }

  @Test
  void unknownFormatsKeysAndStrictStructureFailClosed() {
    assertInvalidWorkloadClaims(claims -> claims.put("version", "2.0"));
    assertInvalidWorkloadClaims(claims -> claims.put("canonicalizationProfile", "unknown"));
    assertInvalidWorkloadClaims(claims -> claims.put("requestBindingProfile", "unknown"));
    assertInvalidWorkloadClaims(claims -> claims.put("unexpected", true));
    assertInvalidWorkloadClaims(claims -> claims.put("issuer", "tracepermit-scheduler:unknown"));
    assertInvalidWorkloadClaims(claims -> claims.put("keyId", "unknown-key"));

    Fixture headerFixture = fixture();
    String unknownAlgorithm =
        headerFixture
            .workloadSigner()
            .signWithHeader(
                workloadClaims(action(), UUID.randomUUID().toString()),
                "tracepermit-workload-identity+jws",
                "none",
                "scheduler-key-001");
    assertCode(
        () -> headerFixture.verifier().verify(unknownAlgorithm, List.of(), action(), context(false)),
        "INVALID_WORKLOAD_CREDENTIAL");

    Fixture kidFixture = fixture();
    String wrongHeaderKey =
        kidFixture
            .workloadSigner()
            .signWithHeader(
                workloadClaims(action(), UUID.randomUUID().toString()),
                "tracepermit-workload-identity+jws",
                "EdDSA",
                "other-key");
    assertCode(
        () -> kidFixture.verifier().verify(wrongHeaderKey, List.of(), action(), context(false)),
        "INVALID_WORKLOAD_CREDENTIAL");

    assertCode(
        () -> fixture().verifier().verify(null, List.of(), action(), context(false)),
        "MALFORMED_WORKLOAD_CREDENTIAL");
    assertCode(
        () -> fixture().verifier().verify(".", List.of(), action(), context(false)),
        "MALFORMED_WORKLOAD_CREDENTIAL");
    assertCode(
        () -> fixture().verifier().verify("%%%%.e30.AA", List.of(), action(), context(false)),
        "MALFORMED_WORKLOAD_CREDENTIAL");
    assertCode(
        () -> fixture().verifier().verify("e30.e30.AA", List.of(), action(), context(false)),
        "INVALID_WORKLOAD_CREDENTIAL");
  }

  @Test
  void temporalAndTrustedContextSubstitutionFailClosed() {
    assertInvalidWorkloadClaims(
        claims -> claims.put("expiresAt", claims.path("issuedAt").asText()));
    assertInvalidWorkloadClaims(
        claims -> claims.put("expiresAt", "2029-12-31T23:59:59Z"));
    assertInvalidWorkloadClaims(
        claims -> claims.put("expiresAt", "2030-01-01T00:05:01Z"));
    assertInvalidWorkloadClaims(
        claims -> {
          claims.put("issuedAt", "2030-01-01T00:00:36Z");
          claims.put("expiresAt", "2030-01-01T00:01:00Z");
        });
    assertInvalidWorkloadClaims(
        claims -> {
          claims.put("issuedAt", "not-an-instant");
          claims.put("expiresAt", "2030-01-01T00:01:00Z");
        });

    assertInvalidWorkloadClaims(claims -> claims.put("scheduleId", "schedule:other"));
    assertInvalidWorkloadClaims(claims -> claims.put("runId", "run:other"));
    assertInvalidWorkloadClaims(
        claims -> claims.put("eventId", "00000000-0000-4000-8000-000000000099"));
    assertInvalidWorkloadClaims(
        claims -> claims.put("traceId", "ffffffffffffffffffffffffffffffff"));

    Fixture futureContextFixture = fixture();
    Chain futureContext = futureContextFixture.chain(action(), claims -> {});
    assertCode(
        () ->
            futureContextFixture
                .verifier()
                .verify(
                    futureContext.workload(),
                    List.of(),
                    action(),
                    context(false, NOW.plusSeconds(6))),
        "INVALID_WORKLOAD_CREDENTIAL");
  }

  @Test
  void duplicateBranchingDisconnectedAndNonAuthorizingChainsFailClosed() {
    Fixture duplicateFixture = fixture();
    Chain duplicate = duplicateFixture.chain(action(), claims -> {});
    assertCode(
        () ->
            duplicateFixture
                .verifier()
                .verify(
                    duplicate.workload(),
                    List.of(duplicate.root(), duplicate.root()),
                    action(),
                    context(true)),
        "INVALID_DELEGATION_CHAIN");

    Fixture rootsFixture = fixture();
    String rootOne = rootsFixture.root(UUID.randomUUID().toString(), claims -> {});
    String rootTwo = rootsFixture.root(UUID.randomUUID().toString(), claims -> {});
    String rootsWorkload = rootsFixture.workload(action(), UUID.randomUUID().toString(), claims -> {});
    assertCode(
        () ->
            rootsFixture
                .verifier()
                .verify(rootsWorkload, List.of(rootOne, rootTwo), action(), context(true)),
        "INVALID_DELEGATION_CHAIN");

    Fixture branchFixture = fixture();
    String branchRootId = UUID.randomUUID().toString();
    String branchRoot = branchFixture.root(branchRootId, claims -> {});
    String branchOne =
        branchFixture.child(
            branchRoot, branchRootId, UUID.randomUUID().toString(), claims -> {});
    String branchTwo =
        branchFixture.child(
            branchRoot, branchRootId, UUID.randomUUID().toString(), claims -> {});
    String branchWorkload =
        branchFixture.workload(action(), UUID.randomUUID().toString(), claims -> {});
    assertCode(
        () ->
            branchFixture
                .verifier()
                .verify(
                    branchWorkload,
                    List.of(branchRoot, branchOne, branchTwo),
                    action(),
                    context(true)),
        "INVALID_DELEGATION_CHAIN");

    Fixture disconnectedFixture = fixture();
    String disconnectedRoot =
        disconnectedFixture.root(UUID.randomUUID().toString(), claims -> {});
    String disconnected =
        disconnectedFixture.child(
            disconnectedRoot,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            claims -> {});
    String disconnectedWorkload =
        disconnectedFixture.workload(action(), UUID.randomUUID().toString(), claims -> {});
    assertCode(
        () ->
            disconnectedFixture
                .verifier()
                .verify(
                    disconnectedWorkload,
                    List.of(disconnectedRoot, disconnected),
                    action(),
                    context(true)),
        "INVALID_DELEGATION_CHAIN");

    assertBrokenChain(
        claims -> {
          ArrayNode values = JsonSupport.MAPPER.createArrayNode();
          values.add("aws.cost.export");
          claims.set("scopes", values);
        });
    assertBrokenChain(
        claims -> {
          ArrayNode values = JsonSupport.MAPPER.createArrayNode();
          values.add("aws:simulated:account/123456789012");
          claims.set("resources", values);
        });
    assertBrokenChain(claims -> claims.put("issuedAt", "2029-12-31T23:59:59Z"));
  }

  @Test
  void delegationKeyStateRevocationAvailabilityAndDepthFailClosed() {
    Fixture revokedKeyFixture =
        fixture(
            WorkloadTrustRegistry.KeyState.ACTIVE,
            WorkloadTrustRegistry.KeyState.REVOKED,
            WorkloadTrustRegistry.KeyState.ACTIVE);
    Chain revokedKey = revokedKeyFixture.chain(action(), claims -> {});
    assertCode(
        () ->
            revokedKeyFixture
                .verifier()
                .verify(
                    revokedKey.workload(),
                    List.of(revokedKey.root(), revokedKey.child()),
                    action(),
                    context(true)),
        "DELEGATION_KEY_REVOKED");

    Fixture unavailableFixture = fixture();
    Chain unavailable = unavailableFixture.chain(action(), claims -> {});
    CredentialRevocationChecker grantUnavailable =
        (tenantId, issuer, tokenId, now) ->
            issuer.startsWith("tracepermit-scheduler:")
                ? CredentialRevocationChecker.RevocationStatus.ACTIVE
                : CredentialRevocationChecker.RevocationStatus.UNKNOWN;
    ScheduledWorkloadVerifier unavailableVerifier =
        new ScheduledWorkloadVerifier(
            unavailableFixture.registry(),
            grantUnavailable,
            new InMemoryWorkloadIdentityReplayStore(),
            CLOCK);
    assertCode(
        () ->
            unavailableVerifier.verify(
                unavailable.workload(),
                List.of(unavailable.root(), unavailable.child()),
                action(),
                context(true)),
        "DELEGATION_REVOCATION_UNAVAILABLE");

    Fixture depthFixture = fixture();
    Chain depth = depthFixture.chain(action(), claims -> {});
    assertCode(
        () ->
            depthFixture
                .verifier()
                .verify(
                    depth.workload(),
                    List.of(
                        depth.root(), depth.child(), depth.child(), depth.child(), depth.child(),
                        depth.child(), depth.child(), depth.child(), depth.child()),
                    action(),
                    context(true)),
        "INVALID_DELEGATION_CHAIN");
  }

  private static void assertInvalidWorkloadClaims(Consumer<ObjectNode> mutation) {
    Fixture fixture = fixture();
    String compact = fixture.workload(action(), UUID.randomUUID().toString(), mutation);
    assertCode(
        () -> fixture.verifier().verify(compact, List.of(), action(), context(false)),
        "INVALID_WORKLOAD_CREDENTIAL");
  }

  private static void assertInvalidScheduledInput(
      ScheduledActionAdapter adapter, JsonNode input) {
    assertCode(
        () -> adapter.verifyAndNormalize(input, "unused", List.of(), context(false)),
        "INVALID_SCHEDULED_ACTION");
  }

  private static void assertBrokenChain(Consumer<ObjectNode> childMutation) {
    assertBrokenChain(childMutation, "INVALID_DELEGATION_CHAIN");
  }

  private static void assertBrokenChain(
      Consumer<ObjectNode> childMutation, String expectedCode) {
    Fixture fixture = fixture();
    Chain chain = fixture.chain(action(), childMutation);
    assertCode(
        () ->
            fixture
                .verifier()
                .verify(chain.workload(), List.of(chain.root(), chain.child()), action(), context(true)),
        expectedCode);
  }

  private static Fixture fixture() {
    return fixture(
        WorkloadTrustRegistry.KeyState.ACTIVE,
        WorkloadTrustRegistry.KeyState.ACTIVE,
        WorkloadTrustRegistry.KeyState.ACTIVE);
  }

  private static Fixture fixture(WorkloadTrustRegistry.KeyState schedulerState) {
    return fixture(
        schedulerState,
        WorkloadTrustRegistry.KeyState.ACTIVE,
        WorkloadTrustRegistry.KeyState.ACTIVE);
  }

  private static Fixture fixture(
      WorkloadTrustRegistry.KeyState schedulerState,
      WorkloadTrustRegistry.KeyState workloadState,
      WorkloadTrustRegistry.KeyState agentState) {
    TestSigner scheduler = signer("tracepermit-scheduler:primary", "scheduler-key-001", SCHEDULER);
    TestSigner workload =
        signer("tracepermit-workload:daily-cost-report", "workload-key-001", WORKLOAD);
    TestSigner agent = signer("tracepermit-agent:report-builder", "agent-key-001", AGENT);
    StaticWorkloadTrustRegistry registry =
        new StaticWorkloadTrustRegistry(
            List.of(
                trusted(
                    scheduler,
                    Set.of(WorkloadTrustRegistry.KeyUsage.WORKLOAD_IDENTITY),
                    schedulerState),
                trusted(
                    workload,
                    Set.of(WorkloadTrustRegistry.KeyUsage.DELEGATION),
                    workloadState),
                trusted(
                    agent,
                    Set.of(WorkloadTrustRegistry.KeyUsage.DELEGATION),
                    agentState)));
    InMemoryCredentialRevocationChecker revocation = new InMemoryCredentialRevocationChecker();
    return new Fixture(
        scheduler,
        workload,
        agent,
        revocation,
        registry,
        new ScheduledWorkloadVerifier(
            registry, revocation, new InMemoryWorkloadIdentityReplayStore(), CLOCK));
  }

  private static WorkloadTrustRegistry.TrustedWorkloadKey trusted(
      TestSigner signer,
      Set<WorkloadTrustRegistry.KeyUsage> usages,
      WorkloadTrustRegistry.KeyState state) {
    return new WorkloadTrustRegistry.TrustedWorkloadKey(
        signer.issuer(),
        signer.keyId(),
        TENANT,
        signer.principalId(),
        usages,
        state,
        signer.keyPair().getPublic());
  }

  private static TestSigner signer(String issuer, String keyId, String principalId) {
    try {
      return new TestSigner(
          issuer, keyId, principalId, KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static ObjectNode workloadClaims(JsonNode boundAction, String tokenId) {
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("version", "1.0");
    claims.put("type", "tracepermit-workload-identity+jws");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("requestBindingProfile", "tracepermit-scheduled-action-v1");
    claims.put("issuer", "tracepermit-scheduler:primary");
    claims.put("keyId", "scheduler-key-001");
    claims.put("audience", AUDIENCE);
    claims.put("tokenId", tokenId);
    claims.put("tenantId", TENANT);
    claims.put("subjectId", WORKLOAD);
    claims.put("schedulerId", SCHEDULER);
    claims.put("scheduleId", SCHEDULE_ID);
    claims.put("runId", RUN_ID);
    claims.put("eventId", EVENT_ID);
    claims.put("traceId", TRACE_ID);
    claims.put("actionHash", CanonicalJson.sha256(boundAction));
    claims.put("issuedAt", "2030-01-01T00:00:00Z");
    claims.put("expiresAt", "2030-01-01T00:02:00Z");
    return claims;
  }

  private static ObjectNode rootClaims(String tokenId) {
    ObjectNode claims = grantClaims(tokenId, WORKLOAD, AGENT);
    claims.put("issuer", "tracepermit-workload:daily-cost-report");
    claims.put("keyId", "workload-key-001");
    claims.put("issuedAt", "2030-01-01T00:00:00Z");
    claims.put("expiresAt", "2030-01-01T00:05:00Z");
    scopes(claims).add("aws.cost.export");
    resources(claims).add("aws:simulated:account/123456789012");
    return claims;
  }

  private static ObjectNode childClaims(
      String parentCompact, String parentTokenId, String tokenId) {
    ObjectNode claims = grantClaims(tokenId, AGENT, TOOL);
    claims.put("issuer", "tracepermit-agent:report-builder");
    claims.put("keyId", "agent-key-001");
    claims.put("parentTokenId", parentTokenId);
    claims.put("parentTokenHash", hash(parentCompact));
    claims.put("issuedAt", "2030-01-01T00:00:10Z");
    claims.put("expiresAt", "2030-01-01T00:04:00Z");
    return claims;
  }

  private static ObjectNode grantClaims(String tokenId, String actorId, String subjectId) {
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("version", "1.0");
    claims.put("type", "tracepermit-delegation-grant+jws");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("tenantId", TENANT);
    claims.put("tokenId", tokenId);
    claims.put("actorId", actorId);
    claims.put("subjectId", subjectId);
    claims.put("audience", AUDIENCE);
    claims.putArray("scopes").add(SCOPE);
    claims.putArray("resources").add(RESOURCE);
    claims.put("purpose", "Generate the scheduled cost report");
    claims.put("traceId", TRACE_ID);
    return claims;
  }

  private static ArrayNode scopes(ObjectNode claims) {
    return (ArrayNode) claims.path("scopes");
  }

  private static ArrayNode resources(ObjectNode claims) {
    return (ArrayNode) claims.path("resources");
  }

  private static ObjectNode scheduledInput() {
    ObjectNode input = JsonSupport.MAPPER.createObjectNode();
    input.put("schemaVersion", "1.0");
    ObjectNode intent = input.putObject("declaredIntent");
    intent.put("goal", "Generate the daily cost report");
    intent.put("source", "job_definition");
    input.set("action", action());
    ObjectNode clientClaims = input.putObject("clientClaims");
    clientClaims.put("jobClass", "report");
    clientClaims.put("bearerToken", "must-not-enter-canonical-evidence");
    return input;
  }

  private static ObjectNode action() {
    ObjectNode action = JsonSupport.MAPPER.createObjectNode();
    action.put("protocol", "mcp");
    action.put("toolName", "aws.cost.get");
    action.putObject("arguments").put("day", "2030-01-01");
    ObjectNode target = action.putObject("target");
    target.put("system", "aws-simulator");
    target.put("environment", "test");
    target.put("accountId", "123456789012");
    target.put("resourceType", "cost-report");
    return action;
  }

  private static TrustedScheduledActionContext context(boolean requireDelegation) {
    return context(requireDelegation, NOW);
  }

  private static TrustedScheduledActionContext context(
      boolean requireDelegation, Instant observedAt) {
    return new TrustedScheduledActionContext(
        TENANT,
        SCHEDULER,
        SCHEDULE_ID,
        RUN_ID,
        EVENT_ID,
        TRACE_ID,
        AUDIENCE,
        SCOPE,
        RESOURCE,
        requireDelegation,
        observedAt);
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String expected) {
    assertThatThrownBy(operation)
        .isInstanceOf(WorkloadCredentialException.class)
        .extracting(error -> ((WorkloadCredentialException) error).code())
        .isEqualTo(expected);
  }

  private static String tamper(String compact) {
    String[] segments = compact.split("\\.");
    char replacement = segments[2].charAt(0) == 'A' ? 'B' : 'A';
    return segments[0] + "." + segments[1] + "." + replacement + segments[2].substring(1);
  }

  private static String hash(String value) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private record TestSigner(String issuer, String keyId, String principalId, KeyPair keyPair) {
    private String sign(ObjectNode claims, String type) {
      return signWithHeader(claims, type, "EdDSA", keyId);
    }

    private String signWithHeader(ObjectNode claims, String type, String algorithm, String headerKeyId) {
      try {
        ObjectNode header = JsonSupport.MAPPER.createObjectNode();
        header.put("alg", algorithm);
        header.put("typ", type);
        header.put("kid", headerKeyId);
        String signingInput =
            encode(CanonicalJson.canonicalize(header).getBytes(StandardCharsets.UTF_8))
                + "."
                + encode(CanonicalJson.canonicalize(claims).getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + encode(signature.sign());
      } catch (GeneralSecurityException exception) {
        throw new IllegalStateException(exception);
      }
    }
  }

  private record Fixture(
      TestSigner workloadSigner,
      TestSigner rootSigner,
      TestSigner childSigner,
      InMemoryCredentialRevocationChecker revocation,
      StaticWorkloadTrustRegistry registry,
      ScheduledWorkloadVerifier verifier) {
    private Chain chain(JsonNode action, Consumer<ObjectNode> childMutation) {
      String rootId = UUID.randomUUID().toString();
      String root = root(rootId, claims -> {});
      String child = child(root, rootId, UUID.randomUUID().toString(), childMutation);
      return new Chain(
          workload(action, UUID.randomUUID().toString(), claims -> {}), root, child);
    }

    private String workload(
        JsonNode action, String tokenId, Consumer<ObjectNode> mutation) {
      ObjectNode claims = workloadClaims(action, tokenId);
      mutation.accept(claims);
      return workloadSigner.sign(claims, "tracepermit-workload-identity+jws");
    }

    private String root(String tokenId, Consumer<ObjectNode> mutation) {
      ObjectNode claims = rootClaims(tokenId);
      mutation.accept(claims);
      return rootSigner.sign(claims, "tracepermit-delegation-grant+jws");
    }

    private String child(
        String parent, String parentId, String tokenId, Consumer<ObjectNode> mutation) {
      ObjectNode claims = childClaims(parent, parentId, tokenId);
      mutation.accept(claims);
      return childSigner.sign(claims, "tracepermit-delegation-grant+jws");
    }
  }

  private record Chain(String workload, String root, String child) {}
}
