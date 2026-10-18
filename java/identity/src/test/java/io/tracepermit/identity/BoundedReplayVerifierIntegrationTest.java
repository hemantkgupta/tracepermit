package io.tracepermit.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Integration proof for the bounded process-local replay stores inside the real verifiers.
 *
 * <p>Scope is deliberately narrow: when a bounded store is saturated, a fresh, validly
 * signed credential is refused with the replay refusal code and no dispatch follows. The
 * test-local target counter only proves that dispatch follows successful verification —
 * store cardinality is not an invocation counter, and this proof does not extend to public
 * distributed abuse protection.
 */
final class BoundedReplayVerifierIntegrationTest {
  private static final Instant BASE = Instant.parse("2030-01-01T00:00:30Z");
  private static final String FINGERPRINT = "sha256:" + "1".repeat(64);
  private static final String ISSUER = "tracepermit-endpoint:device-simulated-001";
  private static final String TENANT = "tenant:acme";
  private static final String SCHEDULER = "scheduler:primary";
  private static final String SCHEDULER_ISSUER = "tracepermit-scheduler:primary";
  private static final String WORKLOAD = "workload:daily-cost-report";
  private static final String AUDIENCE = "tracepermit-gateway";
  private static final String SCOPE = "aws.cost.read";
  private static final String RESOURCE = "aws:simulated:account/123456789012/cost-report";
  private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
  private static final String EVENT_ID = "00000000-0000-4000-8000-000000000015";
  private static final String SCHEDULE_ID = "schedule:daily-cost-report";
  private static final String RUN_ID = "run:2030-01-01";

  @Test
  void saturatedSourceAttestationStoreRefusesFreshValidAttestationWithoutDispatch() {
    KeyPair keyPair = ed25519();
    MutableClock clock = new MutableClock(BASE);
    InMemorySourceAttestationReplayStore store = new InMemorySourceAttestationReplayStore(1, 1);
    SourceAttestationSigner signer = new SourceAttestationSigner(keyPair.getPrivate());
    SourceAttestationVerifier verifier =
        new SourceAttestationVerifier(endpointRegistry(keyPair), store, clock);
    AtomicInteger targetCalls = new AtomicInteger();

    // Fill the single slot with a directly recorded (already verified) generation.
    assertThat(
            store.consume(ISSUER, UUID.randomUUID().toString(), BASE.plusSeconds(60), BASE))
        .isTrue();

    // A fresh, validly signed attestation is refused with the replay refusal code and
    // no dispatch follows.
    String saturated =
        signer.sign(claims(request(), UUID.randomUUID().toString(), BASE.minusSeconds(15)));
    assertThatThrownBy(() -> verifier.verify(saturated, request(), FINGERPRINT))
        .isInstanceOf(SourceAttestationException.class)
        .extracting(error -> ((SourceAttestationException) error).code())
        .isEqualTo("SOURCE_ATTESTATION_REPLAYED");
    assertThat(targetCalls.get()).isZero();
    assertThat(store.liveEntries()).isEqualTo(1);
    assertThat(store.saturatedRefusals()).isEqualTo(1);

    // Positive control: after genuine expiry and bounded reclamation, verification
    // succeeds again and only then does dispatch follow.
    clock.advance(Duration.ofSeconds(120));
    Instant now = clock.instant();
    String renewed = signer.sign(claims(request(), UUID.randomUUID().toString(), now.minusSeconds(15)));
    VerifiedSourceEvidence verified = verifier.verify(renewed, request(), FINGERPRINT);
    assertThat(verified.issuer()).isEqualTo(ISSUER);
    targetCalls.incrementAndGet();
    assertThat(targetCalls.get()).isEqualTo(1);
    assertThat(store.reclamationRemovals()).isEqualTo(1);
  }

  @Test
  void saturatedWorkloadStoreRefusesFreshValidCredentialWithoutDispatch() {
    KeyPair keyPair = ed25519();
    MutableClock clock = new MutableClock(BASE);
    InMemoryWorkloadIdentityReplayStore store = new InMemoryWorkloadIdentityReplayStore(1, 1);
    TestSigner signer = new TestSigner(SCHEDULER_ISSUER, "scheduler-key-001", keyPair);
    StaticWorkloadTrustRegistry registry =
        new StaticWorkloadTrustRegistry(
            List.of(
                new WorkloadTrustRegistry.TrustedWorkloadKey(
                    SCHEDULER_ISSUER,
                    "scheduler-key-001",
                    TENANT,
                    SCHEDULER,
                    Set.of(WorkloadTrustRegistry.KeyUsage.WORKLOAD_IDENTITY),
                    WorkloadTrustRegistry.KeyState.ACTIVE,
                    keyPair.getPublic())));
    ScheduledWorkloadVerifier verifier =
        new ScheduledWorkloadVerifier(
            registry, new InMemoryCredentialRevocationChecker(), store, clock);
    AtomicInteger targetCalls = new AtomicInteger();

    assertThat(
            store.consume(
                TENANT, SCHEDULER_ISSUER, UUID.randomUUID().toString(), BASE.plusSeconds(300), BASE))
        .isTrue();

    String saturated =
        signer.sign(
            workloadClaims(action(), UUID.randomUUID().toString(), BASE.minusSeconds(15)),
            "tracepermit-workload-identity+jws");
    assertThatThrownBy(
            () -> verifier.verify(saturated, List.of(), action(), context(clock.instant())))
        .isInstanceOf(WorkloadCredentialException.class)
        .extracting(error -> ((WorkloadCredentialException) error).code())
        .isEqualTo("WORKLOAD_CREDENTIAL_REPLAYED");
    assertThat(targetCalls.get()).isZero();
    assertThat(store.liveEntries()).isEqualTo(1);
    assertThat(store.saturatedRefusals()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(400));
    Instant now = clock.instant();
    String renewed =
        signer.sign(
            workloadClaims(action(), UUID.randomUUID().toString(), now.minusSeconds(15)),
            "tracepermit-workload-identity+jws");
    VerifiedScheduledWorkload verified = verifier.verify(renewed, List.of(), action(), context(now));
    assertThat(verified.subjectId()).isEqualTo(WORKLOAD);
    targetCalls.incrementAndGet();
    assertThat(targetCalls.get()).isEqualTo(1);
    assertThat(store.reclamationRemovals()).isEqualTo(1);
  }

  private static StaticEndpointKeyRegistry endpointRegistry(KeyPair keyPair) {
    return new StaticEndpointKeyRegistry(
        List.of(
            new EndpointKeyRegistry.EnrolledEndpointKey(
                ISSUER, "endpoint-key-001", "device-simulated-001", keyPair.getPublic())));
  }

  private static ObjectNode request() {
    ObjectNode request = JsonSupport.MAPPER.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", 99);
    request.put("method", "tools/list");
    request.set("params", JsonSupport.MAPPER.createObjectNode());
    return request;
  }

  private static ObjectNode claims(JsonNode boundRequest, String tokenId, Instant issuedAt) {
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("version", "1.0");
    claims.put("type", "tracepermit-source-attestation+jws");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("requestBindingProfile", "tracepermit-mcp-method-params-v1");
    claims.put("issuer", ISSUER);
    claims.put("keyId", "endpoint-key-001");
    claims.put("audience", SourceAttestationVerifier.DEFAULT_AUDIENCE);
    claims.put("tokenId", tokenId);
    claims.put("issuedAt", issuedAt.toString());
    claims.put("expiresAt", issuedAt.plusSeconds(60).toString());
    claims.put("connectorCredentialFingerprint", FINGERPRINT);
    claims.put("requestHash", SourceAttestationVerifier.hashRequest(boundRequest));
    ObjectNode device = claims.putObject("device");
    device.put("deviceId", "device-simulated-001");
    device.put("platform", "macos");
    device.put("assurance", "development");
    ObjectNode process = claims.putObject("process");
    process.put("executableName", "codex");
    process.put("executableHash", "sha256:" + "2".repeat(64));
    process.put("parentExecutableName", "terminal");
    process.put("startedAt", issuedAt.toString());
    ObjectNode trigger = claims.putObject("triggerEvidence");
    trigger.put("requestedMode", "interactive");
    trigger.put("terminalAttached", true);
    return claims;
  }

  private static ObjectNode workloadClaims(JsonNode boundAction, String tokenId, Instant issuedAt) {
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("version", "1.0");
    claims.put("type", "tracepermit-workload-identity+jws");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("requestBindingProfile", "tracepermit-scheduled-action-v1");
    claims.put("issuer", SCHEDULER_ISSUER);
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
    claims.put("issuedAt", issuedAt.toString());
    claims.put("expiresAt", issuedAt.plusSeconds(120).toString());
    return claims;
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

  private static TrustedScheduledActionContext context(Instant observedAt) {
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
        false,
        observedAt);
  }

  private static KeyPair ed25519() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record TestSigner(String issuer, String keyId, KeyPair keyPair) {
    private String sign(ObjectNode claims, String type) {
      try {
        ObjectNode header = JsonSupport.MAPPER.createObjectNode();
        header.put("alg", "EdDSA");
        header.put("typ", type);
        header.put("kid", keyId);
        String signingInput =
            encode(CanonicalJson.canonicalize(header).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "."
                + encode(
                    CanonicalJson.canonicalize(claims).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.security.Signature signature = java.security.Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return signingInput + "." + encode(signature.sign());
      } catch (java.security.GeneralSecurityException exception) {
        throw new IllegalStateException(exception);
      }
    }
  }

  private static String encode(byte[] value) {
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
